package com.newsfeed.config;

import com.newsfeed.model.FeedSource;
import com.newsfeed.repository.FeedSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final FeedSourceRepository feedSourceRepository;

    @Override
    public void run(String... args) {
        log.info("Checking feed sources...");

        // ============================================
        // 国内主流新闻媒体
        // ============================================
        save("新华社", "http://www.xinhuanet.com/politics/news_politics.xml");
        save("人民日报", "http://www.people.com.cn/rss/politics.xml");
        save("中国新闻网", "http://www.chinanews.com.cn/rss/scroll-news.xml");
        save("新浪新闻", "http://rss.sina.com.cn/news/marquee/ddt.xml");
        save("36氪", "https://36kr.com/feed");
        save("IT之家", "https://www.ithome.com/rss/");
        save("少数派", "https://sspai.com/feed");
        save("知乎热榜", "https://rss.aishort.top/?type=zhihu");
        save("百度热搜", "https://rss.aishort.top/?type=baidu");
        save("雷锋网", "https://www.leiphone.com/feed/");
        save("爱范儿", "https://www.ifanr.com/feed");
        save("中国日报", "https://www.chinadaily.com.cn/rss/china_rss.xml");
        save("钛媒体", "https://www.tmtpost.com/feed");

        // ============================================
        // 国际主流新闻媒体
        // ============================================
        save("BBC News", "https://feeds.bbci.co.uk/news/rss.xml");
        save("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml");
        save("Reuters", "https://www.reutersagency.com/feed/", false);
        save("Associated Press", "https://apnews.com/rss", false);
        save("CNN", "http://rss.cnn.com/rss/cnn_topstories.rss", false);
        save("The Guardian", "https://www.theguardian.com/world/rss", false);
        save("New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml", false);
        save("NPR", "https://feeds.npr.org/1001/rss.xml");
        save("CNBC", "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114");
        save("Bloomberg", "https://feeds.bloomberg.com/markets/news.rss");
        save("Wall Street Journal", "https://feeds.a.dj.com/rss/WSJcomUSBusiness.xml");
        save("USA Today", "https://rssfeeds.usatoday.com/UsatodaycomNation-TopStories", false);
        save("Washington Post", "https://feeds.washingtonpost.com/rss/world", false);
        save("The Economist", "https://www.economist.com/feeds/print-sections/77/international.xml", false);
        save("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", false);
        save("ABC News (US)", "https://abcnews.go.com/abcnews/topstories", false);
        save("NBC News", "https://feeds.nbcnews.com/nbcnews/public/news", false);
        save("CBS News", "https://www.cbsnews.com/latest/rss/main");
        save("Fox News", "https://feeds.foxnews.com/foxnews/latest");
        save("Deutsche Welle", "https://rss.dw.com/rdf/rss-en-all");
        save("France 24", "https://www.france24.com/en/rss");
        save("NHK World", "https://www3.nhk.or.jp/rss/news/cat0.xml");

        log.info("Feed sources check complete. Total: {}", feedSourceRepository.count());
    }

    private void save(String name, String url) {
        save(name, url, true);
    }

    private void save(String name, String url, boolean enabled) {
        if (feedSourceRepository.existsByUrl(url)) {
            return;
        }
        FeedSource source = new FeedSource();
        source.setName(name);
        source.setUrl(url);
        source.setProtocol("RSS");
        source.setFetchIntervalMinutes(15);
        source.setEnabled(enabled);
        feedSourceRepository.save(source);
        log.info("Added feed source: {} (enabled={})", name, enabled);
    }
}
