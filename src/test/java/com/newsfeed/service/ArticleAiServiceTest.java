package com.newsfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsfeed.config.AiConfig;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.model.Article;
import com.newsfeed.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleAiServiceTest {

    private ArticleRepository articleRepository;
    private AiConfig aiConfig;
    private ArticleDedupService articleDedupService;

    @BeforeEach
    void setUp() {
        articleRepository = mock(ArticleRepository.class);
        aiConfig = new AiConfig();
        aiConfig.setKey("test-key");
        aiConfig.setBaseUrl("https://example.invalid");
        articleDedupService = new ArticleDedupService(aiConfig);
    }

    @Test
    void resetTodayUsesOneDateBoundedBulkUpdate() {
        when(articleRepository.resetAiProcessingBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(4);
        ArticleAiService service = service(Runnable::run);

        assertThat(service.resetTodayProcessing()).isEqualTo(4);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(articleRepository).resetAiProcessingBetween(since.capture(), until.capture());
        assertThat(since.getValue())
                .isEqualTo(CanonicalTime.at(CanonicalTime.today(), LocalTime.MIDNIGHT));
        assertThat(until.getValue()).isEqualTo(since.getValue().plusDays(1));
        verify(articleRepository, never()).findAll();
    }

    @Test
    void parsesJacksonJsonForTwentyFiveArticlesAndPreservesExistingRssCategory() {
        ArticleAiService service = service(Runnable::run);
        List<Article> articles = new ArrayList<>();
        StringBuilder response = new StringBuilder("```json\n{\"articles\":[");
        for (long id = 1; id <= 25; id++) {
            if (id > 1) response.append(',');
            response.append("{\"id\":").append(id)
                    .append(",\"aiCategory\":\"tech\",\"chineseCategory\":\"科技\",")
                    .append("\"score\":7,\"summary\":\"line\\nquote \\\"ok\\\"\"}");
            articles.add(Article.builder()
                    .id(id)
                    .title("title " + id)
                    .category(id == 1 ? "RSS分类" : null)
                    .build());
        }
        response.append("]}\n```");

        service.parseAndUpdateArticles(response.toString(), articles);

        assertThat(articles).allSatisfy(article -> {
            assertThat(article.getAiProcessed()).isTrue();
            assertThat(article.getAiCategory()).isEqualTo("tech");
            assertThat(article.getAiCategoryName()).isEqualTo("科技");
            assertThat(article.getImportanceScore()).isEqualTo(7);
            assertThat(article.getAiSummary()).isEqualTo("line\nquote \"ok\"");
        });
        assertThat(articles.get(0).getCategory()).isEqualTo("RSS分类");
        assertThat(articles.get(1).getCategory()).isEqualTo("科技");
        verify(articleRepository).saveAll(articles);
    }

    @Test
    void parseFailureLeavesArticleUnprocessedForRetry() {
        Article article = Article.builder().id(1L).title("retry me").build();
        ArticleAiService service = service(Runnable::run);

        service.parseAndUpdateArticles("{\"articles\":[", List.of(article));

        assertThat(article.getAiProcessed()).isFalse();
        assertThat(article.getAiFailCount()).isEqualTo(1);
        verify(articleRepository).saveAll(List.of(article));
    }

    @Test
    void givesUpAfterThreeConsecutiveParseFailures() {
        Article article = Article.builder().id(1L).title("eventually give up").build();
        ArticleAiService service = service(Runnable::run);

        for (int attempt = 1; attempt <= ArticleAiService.MAX_AI_FAILURES; attempt++) {
            service.parseAndUpdateArticles("not json", List.of(article));
            assertThat(article.getAiFailCount()).isEqualTo(attempt);
            assertThat(article.getAiProcessed())
                    .isEqualTo(attempt == ArticleAiService.MAX_AI_FAILURES);
        }

        verify(articleRepository, times(ArticleAiService.MAX_AI_FAILURES))
                .saveAll(List.of(article));
    }

    @Test
    void successfulResultClearsConsecutiveFailureCount() {
        Article article = Article.builder()
                .id(1L)
                .title("recovered")
                .aiFailCount(2)
                .build();
        ArticleAiService service = service(Runnable::run);

        service.parseAndUpdateArticles("""
                {"articles":[{"id":1,"aiCategory":"tech","chineseCategory":"科技",
                "score":7,"summary":"valid summary"}]}
                """, List.of(article));

        assertThat(article.getAiProcessed()).isTrue();
        assertThat(article.getAiFailCount()).isZero();
        assertThat(article.getAiCategory()).isEqualTo("tech");
        assertThat(article.getAiCategoryName()).isEqualTo("科技");
        assertThat(article.getImportanceScore()).isEqualTo(7);
        assertThat(article.getAiSummary()).isEqualTo("valid summary");
        assertThat(article.getCategory()).isEqualTo("科技");
        verify(articleRepository).saveAll(List.of(article));
    }

    @Test
    void processingUsesBatchesOfFour() {
        List<Article> articles = new ArrayList<>();
        for (long id = 1; id <= 26; id++) {
            articles.add(Article.builder().id(id).title("unique " + id).build());
        }
        when(articleRepository.findUnprocessedArticles()).thenReturn(articles);
        ArticleAiService service = spy(service(Runnable::run));
        doNothing().when(service).processBatch(anyList());
        service.processPendingArticles();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Article>> batches = ArgumentCaptor.forClass(List.class);
        verify(service, times(7)).processBatch(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size)
                .containsExactly(4, 4, 4, 4, 4, 4, 2);
    }

    @Test
    void exactTitleDuplicatesReuseTheRepresentativeAiResult() {
        Article older = Article.builder().id(1L).title("Same headline")
                .publishedAt(LocalDateTime.of(2026, 8, 1, 10, 0)).build();
        Article newer = Article.builder().id(2L).title("Same headline")
                .publishedAt(LocalDateTime.of(2026, 8, 1, 11, 0)).build();
        when(articleRepository.findUnprocessedArticles()).thenReturn(List.of(older, newer));
        ArticleAiService service = spy(service(Runnable::run));
        doAnswer(invocation -> {
            List<Article> batch = invocation.getArgument(0);
            assertThat(batch).containsExactly(newer);
            newer.setAiCategory("international");
            newer.setAiCategoryName("国际");
            newer.setImportanceScore(8);
            newer.setAiSummary("shared summary");
            newer.setCategory("国际");
            newer.setAiProcessed(true);
            return null;
        }).when(service).processBatch(anyList());

        service.processPendingArticles();

        assertThat(older.getAiProcessed()).isTrue();
        assertThat(older.getAiCategory()).isEqualTo("international");
        assertThat(older.getAiCategoryName()).isEqualTo("国际");
        assertThat(older.getImportanceScore()).isEqualTo(8);
        assertThat(older.getAiSummary()).isEqualTo("shared summary");
        assertThat(older.getCategory()).isEqualTo("国际");
        verify(service).processBatch(anyList());
        verify(articleRepository).saveAll(List.of(older));
    }

    @Test
    void repeatedManualTriggerDoesNotSubmitConcurrentWork() {
        List<Article> articles = List.of(Article.builder().id(1L).title("one").build());
        when(articleRepository.findUnprocessedArticles()).thenReturn(articles);
        CapturingExecutor executor = new CapturingExecutor();
        ArticleAiService service = service(executor);

        assertThat(service.triggerProcessing()).isEqualTo(1);
        assertThat(service.triggerProcessing()).isZero();
        assertThat(executor.command).isNotNull();
        verify(articleRepository, times(1)).findUnprocessedArticles();
    }

    private ArticleAiService service(Executor executor) {
        return new ArticleAiService(articleRepository, aiConfig, articleDedupService,
                new ObjectMapper(), executor);
    }

    private static final class CapturingExecutor implements Executor {
        private Runnable command;

        @Override
        public void execute(Runnable command) {
            this.command = command;
        }
    }
}
