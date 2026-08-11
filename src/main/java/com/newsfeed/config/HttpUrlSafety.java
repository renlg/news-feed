package com.newsfeed.config;

import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class HttpUrlSafety {

    public String safeHttpUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return value;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URIs are not safe to render as links.
        }
        return null;
    }
}
