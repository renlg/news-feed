package com.newsfeed.service.rss;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.service.FeedParser;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class RssFeedParser implements FeedParser {

    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_FEED_BYTES = 10L * 1024 * 1024;
    private static final int MAX_AUTHOR_LENGTH = 255;

    @Override
    public String supportedProtocol() {
        return "RSS";
    }

    @Override
    public List<Article> parse(String url, FeedSource source) {
        List<Article> articles = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpResponse<InputStream> response = fetchWithValidatedRedirects(client, URI.create(url));

            SAXBuilder saxBuilder = new SAXBuilder();
            saxBuilder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            saxBuilder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            saxBuilder.setExpandEntities(false);

            try (InputStream is = new SizeLimitedInputStream(response.body(), MAX_FEED_BYTES);
                 XmlReader xmlReader = new XmlReader(is)) {
                StringWriter xmlBuffer = new StringWriter();
                xmlReader.transferTo(xmlBuffer);
                String sanitizedXml = xmlBuffer.toString()
                        .replaceAll("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)", "&amp;");
                Document jdomDoc = saxBuilder.build(new StringReader(sanitizedXml));

                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(jdomDoc);

                for (SyndEntry entry : feed.getEntries()) {
                    String content = null;
                    if (entry.getContents() != null && !entry.getContents().isEmpty()) {
                        content = entry.getContents().get(0).getValue();
                    } else if (entry.getDescription() != null) {
                        content = entry.getDescription().getValue();
                    }

                    String summary = null;
                    if (entry.getDescription() != null) {
                        summary = entry.getDescription().getValue();
                    }

                    // ROME normalizes RFC-822/RFC-3339 timestamps (including their offsets) to
                    // an absolute Date/Instant. Convert that instant to the canonical UTC+8 wall time.
                    // A missing or unparseable timestamp is kept null; fetchedAt is then the consistent
                    // fallback. Zone-less feed dates are interpreted by ROME as UTC before this conversion.
                    LocalDateTime publishedAt = null;
                    Date publishedDate = entry.getPublishedDate();
                    if (publishedDate == null) {
                        publishedDate = entry.getUpdatedDate();
                    }
                    if (publishedDate != null) {
                        publishedAt = CanonicalTime.fromInstant(publishedDate.toInstant());
                    }

                    Article article = Article.builder()
                            .title(entry.getTitle())
                            .link(entry.getLink())
                            .content(content)
                            .summary(summary)
                            .author(truncateAuthor(entry.getAuthor()))
                            .publishedAt(publishedAt)
                            .fetchedAt(CanonicalTime.now())
                            .feedSourceId(source.getId())
                            .build();

                    if (entry.getCategories() != null && !entry.getCategories().isEmpty()) {
                        String categories = entry.getCategories().stream()
                                .map(c -> c.getName())
                                .filter(name -> name != null && !name.trim().isEmpty())
                                .map(String::trim)
                                .distinct()
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");
                        if (!categories.isEmpty()) {
                            article.setCategory(categories);
                        }
                    }

                    articles.add(article);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse feed from {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to parse feed from " + url + ": " + e.getMessage(), e);
        }
        return articles;
    }

    private String truncateAuthor(String author) {
        return author != null && author.length() > MAX_AUTHOR_LENGTH
                ? author.substring(0, MAX_AUTHOR_LENGTH)
                : author;
    }

    private HttpResponse<InputStream> fetchWithValidatedRedirects(HttpClient client, URI initialUri)
            throws IOException, InterruptedException {
        URI currentUri = initialUri;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            validatePublicHttpUri(currentUri);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "NewsFeed/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (!isRedirect(status)) {
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IOException("Feed server returned HTTP " + status);
                }
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > MAX_FEED_BYTES) {
                    response.body().close();
                    throw new IOException("Feed response exceeds 10 MB limit");
                }
                return response;
            }

            String location = response.headers().firstValue("Location").orElse(null);
            response.body().close();
            if (location == null) {
                throw new IOException("Feed redirect is missing a Location header");
            }
            if (redirectCount == MAX_REDIRECTS) {
                throw new IOException("Feed exceeded " + MAX_REDIRECTS + " redirects");
            }
            currentUri = currentUri.resolve(location);
        }
        throw new IOException("Feed redirect handling failed");
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void validatePublicHttpUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("Feed URL must use HTTP or HTTPS");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Feed URL must contain a valid hostname");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Feed hostname did not resolve");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IOException("Feed hostname resolves to a blocked address: " + address.getHostAddress());
            }
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                return isBlockedIpv4(bytes, 12);
            }
        }
        return bytes.length == 4 && isBlockedIpv4(bytes, 0);
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private boolean isBlockedIpv4(byte[] bytes, int offset) {
        int first = Byte.toUnsignedInt(bytes[offset]);
        int second = Byte.toUnsignedInt(bytes[offset + 1]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    private static class SizeLimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        private SizeLimitedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                addBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                addBytes(count);
            }
            return count;
        }

        private void addBytes(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new IOException("Feed response exceeds 10 MB limit");
            }
        }
    }
}
