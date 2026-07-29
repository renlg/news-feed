package com.newsfeed.service;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;

import java.util.List;

public interface FeedParser {

    String supportedProtocol();

    List<Article> parse(String url, FeedSource source);
}
