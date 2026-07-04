package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteArtifactStoreTest {
    private JdbcTemplate jt() {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memArt?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void putAndQueryByKind() {
        var store = new SqliteArtifactStore(jt());
        store.put(new Artifact("a1","run1","summary","src-1","要点…", Map.of("uri","http://x")));
        store.put(new Artifact("a2","run1","draft","sec-1","草稿…", Map.of()));
        List<Artifact> summaries = store.query(new ArtifactQuery("run1","summary"));
        assertThat(summaries).extracting(Artifact::id).containsExactly("a1");
    }
}
