package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l2tools.RelevanceFilter;
import com.harnesslearn.agent.l4memory.CorpusSeeder;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 采集管道（子项目 A）的 Spring 装配。种子/重建/调度由 agent.ingest.enabled 门控。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfig {

    @Bean public CorpusRepository corpusRepository(JdbcTemplate jdbc) {
        return new CorpusRepository(jdbc);
    }

    @Bean public SourceRegistry sourceRegistry(IngestProperties props) {
        return new SourceRegistry(props);
    }

    @Bean public FeedReader feedReader() { return new FeedReader(); }

    @Bean public ArticleFetcher articleFetcher() { return new ArticleFetcher(); }

    @Bean public RelevanceGate relevanceGate(RelevanceFilter filter) {
        return new RelevanceGate(filter);
    }

    @Bean public Chunker chunker(IngestProperties props) {
        return new Chunker(props.chunkMaxChars() > 0 ? props.chunkMaxChars() : 800);
    }

    @Bean public IngestionService ingestionService(SourceRegistry r, FeedReader fr,
            ArticleFetcher af, RelevanceGate g, Chunker c, CorpusRepository repo, LongTermMemory m) {
        return new IngestionService(r, fr, af, g, c, repo, m);
    }

    @Bean public CorpusSeeder corpusSeeder(CorpusRepository repo) {
        return new CorpusSeeder(repo, "/seed-corpus.json");
    }

    @Bean public CorpusIndexRebuilder corpusIndexRebuilder(CorpusRepository repo, LongTermMemory m) {
        return new CorpusIndexRebuilder(repo, m);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public IngestionScheduler ingestionScheduler(IngestionService service) {
        return new IngestionScheduler(service);
    }

    /** 空库播种进 SQLite。@Order(2)：在 schemaBootstrap(@Order 1) 之后、重建之前。 */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public ApplicationRunner corpusSeedRunner(CorpusSeeder seeder) {
        return args -> seeder.seed();
    }

    /** 从 SQLite 重建内存索引。@Order(3)：在播种之后。 */
    @Bean
    @Order(3)
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public ApplicationRunner corpusRebuildRunner(CorpusIndexRebuilder rebuilder) {
        return args -> rebuilder.rebuild();
    }
}
