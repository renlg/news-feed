package com.newsfeed.astock;

import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AStockService {

    public static final String ALL_BOARDS = "全部";
    public static final List<String> BOARDS = List.of(
            ALL_BOARDS, "沪主板", "深主板", "创业板", "科创板", "北交所"
    );

    private static final String BOARD_CASE = """
            CASE
                WHEN sec_code LIKE '60%' THEN '沪主板'
                WHEN substr(sec_code, 1, 3) IN ('000', '001', '002', '003') THEN '深主板'
                WHEN substr(sec_code, 1, 3) IN ('300', '301') THEN '创业板'
                WHEN substr(sec_code, 1, 3) IN ('688', '689') THEN '科创板'
                WHEN sec_code LIKE '8%' OR sec_code LIKE '4%' OR sec_code LIKE '920%' THEN '北交所'
                ELSE '其他'
            END
            """;

    private final String databaseUrl;

    public AStockService(@Value("${astock.sqlite.url:jdbc:sqlite:/opt/a-stock/data/stock.db}")
                         String databaseUrl) {
        this.databaseUrl = databaseUrl;
    }

    public StockPage findStocks(String requestedBoard, String keyword, int requestedPage, int requestedSize) {
        String board = normalizeBoard(requestedBoard);
        String search = keyword == null ? "" : keyword.trim();
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 100);
        String where = " WHERE (? = '' OR sec_code LIKE ? OR sec_name LIKE ?) "
                + (ALL_BOARDS.equals(board) ? "" : " AND " + BOARD_CASE + " = ? ");

        try (Connection connection = openConnection()) {
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM stock_pool" + where)) {
                bindFilters(statement, search, board);
                try (ResultSet resultSet = statement.executeQuery()) {
                    total = resultSet.next() ? resultSet.getLong(1) : 0;
                }
            }

            int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
            if (totalPages == 0) {
                page = 0;
            } else if (page >= totalPages) {
                page = totalPages - 1;
            }
            List<Stock> stocks = new ArrayList<>();
            String sql = "SELECT sec_code, sec_name, industry, list_date, " + BOARD_CASE
                    + " AS board FROM stock_pool" + where
                    + " ORDER BY sec_code ASC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindFilters(statement, search, board);
                statement.setInt(index++, size);
                statement.setInt(index, page * size);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        stocks.add(mapStock(resultSet));
                    }
                }
            }
            return new StockPage(stocks, page, size, total, totalPages);
        } catch (SQLException e) {
            throw new AStockDataAccessException("读取 A 股列表失败", e);
        }
    }

    public Optional<Stock> findStock(String secCode) {
        String sql = "SELECT sec_code, sec_name, industry, list_date, " + BOARD_CASE
                + " AS board FROM stock_pool WHERE sec_code = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapStock(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AStockDataAccessException("读取股票基本信息失败", e);
        }
    }

    public List<Kline> findKlines(String secCode, int limit) {
        String sql = "SELECT trade_date, open, close, high, low, volume, amount, pct_chg "
                + "FROM kline_daily WHERE sec_code = ? ORDER BY trade_date DESC LIMIT ?";
        List<Kline> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Kline(rs.getString("trade_date"), nullableDouble(rs, "open"),
                            nullableDouble(rs, "close"), nullableDouble(rs, "high"),
                            nullableDouble(rs, "low"), nullableDouble(rs, "volume"),
                            nullableDouble(rs, "amount"), nullableDouble(rs, "pct_chg")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取日线行情失败", e);
        }
    }

    public Optional<Valuation> findLatestValuation(String secCode) {
        String sql = "SELECT trade_date, pe_ttm, pb, ps_ttm, total_mv, circ_mv, div_yield "
                + "FROM valuation WHERE sec_code = ? ORDER BY trade_date DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new Valuation(rs.getString("trade_date"),
                        nullableDouble(rs, "pe_ttm"), nullableDouble(rs, "pb"),
                        nullableDouble(rs, "ps_ttm"), nullableDouble(rs, "total_mv"),
                        nullableDouble(rs, "circ_mv"), nullableDouble(rs, "div_yield"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw dataError("读取估值数据失败", e);
        }
    }

    public List<MoneyFlow> findMoneyFlows(String secCode, int limit) {
        String sql = "SELECT trade_date, main_net, super_net, big_net, mid_net, small_net "
                + "FROM moneyflow WHERE sec_code = ? ORDER BY trade_date DESC LIMIT ?";
        List<MoneyFlow> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MoneyFlow(rs.getString("trade_date"), nullableDouble(rs, "main_net"),
                            nullableDouble(rs, "super_net"), nullableDouble(rs, "big_net"),
                            nullableDouble(rs, "mid_net"), nullableDouble(rs, "small_net")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取主力资金流失败", e);
        }
    }

    public List<HolderNumber> findHolderNumbers(String secCode, int limit) {
        String sql = "SELECT end_date, holder_num, holder_num_chg, avg_hold FROM holder_num "
                + "WHERE sec_code = ? ORDER BY end_date DESC LIMIT ?";
        List<HolderNumber> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HolderNumber(rs.getString("end_date"), nullableDouble(rs, "holder_num"),
                            nullableDouble(rs, "holder_num_chg"), nullableDouble(rs, "avg_hold")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取股东户数失败", e);
        }
    }

    public List<Margin> findMargins(String secCode, int limit) {
        String sql = "SELECT trade_date, rz_balance, rq_volume, rzrq_balance, rq_balance, rq_mcl, rzrq_chg "
                + "FROM margin WHERE sec_code = ? ORDER BY trade_date DESC LIMIT ?";
        List<Margin> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Margin(rs.getString("trade_date"), nullableDouble(rs, "rz_balance"),
                            nullableDouble(rs, "rq_volume"), nullableDouble(rs, "rzrq_balance"),
                            nullableDouble(rs, "rq_balance"), nullableDouble(rs, "rq_mcl"),
                            nullableDouble(rs, "rzrq_chg")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取融资融券数据失败", e);
        }
    }

    public Optional<Consensus> findLatestConsensus(String secCode) {
        String sql = "SELECT rating_org_num, rating_buy, rating_add, rating_neutral, rating_reduce, "
                + "rating_sale, eps1, eps2, eps3, eps4, year1, year2, year3, year4, "
                + "aimprice_max, aimprice_min, fetch_date FROM consensus "
                + "WHERE sec_code = ? ORDER BY fetch_date DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new Consensus(
                        nullableDouble(rs, "rating_org_num"), nullableDouble(rs, "rating_buy"),
                        nullableDouble(rs, "rating_add"), nullableDouble(rs, "rating_neutral"),
                        nullableDouble(rs, "rating_reduce"), nullableDouble(rs, "rating_sale"),
                        nullableDouble(rs, "eps1"), nullableDouble(rs, "eps2"),
                        nullableDouble(rs, "eps3"), nullableDouble(rs, "eps4"),
                        rs.getString("year1"), rs.getString("year2"), rs.getString("year3"),
                        rs.getString("year4"), nullableDouble(rs, "aimprice_max"),
                        nullableDouble(rs, "aimprice_min"), rs.getString("fetch_date"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw dataError("读取一致预期失败", e);
        }
    }

    public List<Forecast> findForecasts(String secCode, int limit) {
        String sql = "SELECT report_date, notice_date, fc_type, fc_value, yoy FROM forecast "
                + "WHERE sec_code = ? ORDER BY notice_date DESC, report_date DESC LIMIT ?";
        List<Forecast> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Forecast(rs.getString("report_date"), rs.getString("notice_date"),
                            rs.getString("fc_type"), nullableDouble(rs, "fc_value"),
                            nullableDouble(rs, "yoy")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取盈利预测失败", e);
        }
    }

    public List<NorthboundHolding> findNorthboundHoldings(String secCode, int limit) {
        String sql = "SELECT end_date, hold_shares, hold_shares_ratio, hold_market_cap, org_quantity, "
                + "total_shares_ratio, date_type FROM northbound_hold WHERE sec_code = ? "
                + "ORDER BY end_date DESC LIMIT ?";
        List<NorthboundHolding> rows = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secCode);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new NorthboundHolding(rs.getString("end_date"),
                            nullableDouble(rs, "hold_shares"), nullableDouble(rs, "hold_shares_ratio"),
                            nullableDouble(rs, "hold_market_cap"), nullableDouble(rs, "org_quantity"),
                            nullableDouble(rs, "total_shares_ratio"), rs.getString("date_type")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw dataError("读取北向持股失败", e);
        }
    }

    private Connection openConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection(databaseUrl, config.toProperties());
    }

    private int bindFilters(PreparedStatement statement, String search, String board) throws SQLException {
        int index = 1;
        statement.setString(index++, search);
        String pattern = "%" + search + "%";
        statement.setString(index++, pattern);
        statement.setString(index++, pattern);
        if (!ALL_BOARDS.equals(board)) {
            statement.setString(index++, board);
        }
        return index;
    }

    private String normalizeBoard(String board) {
        return board != null && BOARDS.contains(board) ? board : ALL_BOARDS;
    }

    private Stock mapStock(ResultSet rs) throws SQLException {
        return new Stock(rs.getString("sec_code"), rs.getString("sec_name"),
                rs.getString("industry"), rs.getString("list_date"), rs.getString("board"));
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private AStockDataAccessException dataError(String message, SQLException cause) {
        return new AStockDataAccessException(message, cause);
    }

    public record Stock(String secCode, String secName, String industry, String listDate, String board) {
    }

    public record StockPage(List<Stock> content, int number, int size, long totalElements, int totalPages) {
        public boolean isEmpty() { return content.isEmpty(); }
        public boolean isFirst() { return number == 0; }
        public boolean isLast() { return totalPages == 0 || number >= totalPages - 1; }
    }

    public record Kline(String tradeDate, Double open, Double close, Double high, Double low,
                        Double volume, Double amount, Double pctChg) {
    }

    public record Valuation(String tradeDate, Double peTtm, Double pb, Double psTtm,
                            Double totalMv, Double circMv, Double divYield) {
    }

    public record MoneyFlow(String tradeDate, Double mainNet, Double superNet, Double bigNet,
                            Double midNet, Double smallNet) {
    }

    public record HolderNumber(String endDate, Double holderNum, Double holderNumChg, Double avgHold) {
    }

    public record Margin(String tradeDate, Double rzBalance, Double rqVolume, Double rzrqBalance,
                         Double rqBalance, Double rqMcl, Double rzrqChg) {
    }

    public record Consensus(Double ratingOrgNum, Double ratingBuy, Double ratingAdd,
                            Double ratingNeutral, Double ratingReduce, Double ratingSale,
                            Double eps1, Double eps2, Double eps3, Double eps4,
                            String year1, String year2, String year3, String year4,
                            Double aimpriceMax, Double aimpriceMin, String fetchDate) {
    }

    public record Forecast(String reportDate, String noticeDate, String fcType, Double fcValue, Double yoy) {
    }

    public record NorthboundHolding(String endDate, Double holdShares, Double holdSharesRatio,
                                    Double holdMarketCap, Double orgQuantity,
                                    Double totalSharesRatio, String dateType) {
    }

    public static class AStockDataAccessException extends RuntimeException {
        public AStockDataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
