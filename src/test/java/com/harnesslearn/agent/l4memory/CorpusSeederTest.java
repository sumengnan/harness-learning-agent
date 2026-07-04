package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.ingest.CorpusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSeederTest {

    private CorpusRepository repo(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new CorpusRepository(jt);
    }

    @Test
    void seedsFixtureIntoSqlite() {
        var repo = repo("memSeed1");
        int n = new CorpusSeeder(repo, "/test-seed-corpus.json").seed();
        assertThat(n).isEqualTo(3);
        assertThat(repo.chunkCount()).isEqualTo(3);
    }

    @Test
    void skipsWhenCorpusNotEmpty() {
        var repo = repo("memSeed2");
        repo.upsertChunk(new com.harnesslearn.agent.ingest.CorpusChunk(
            "pre", "s", "http://u", "T", 0, "已有", null));
        int n = new CorpusSeeder(repo, "/test-seed-corpus.json").seed();
        assertThat(n).isZero();                    // 非空库跳过
        assertThat(repo.chunkCount()).isEqualTo(1);
    }

    @Test
    void missingResourceIsBestEffortReturnsZero() {
        var repo = repo("memSeed3");
        int n = new CorpusSeeder(repo, "/no-such-seed.json").seed();
        assertThat(n).isZero();
        assertThat(repo.chunkCount()).isZero();
    }

    @Test
    void repoReadFailureIsBestEffortReturnsZero() {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memSeedFail?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        var jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        var throwingRepo = new CorpusRepository(jt) {
            @Override public int chunkCount() { throw new RuntimeException("db boom"); }
        };
        int n = new CorpusSeeder(throwingRepo, "/test-seed-corpus.json").seed();
        assertThat(n).isZero();
    }
}
