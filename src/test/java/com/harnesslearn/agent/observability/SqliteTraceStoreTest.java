package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteTraceStoreTest {
    @Test
    void appendsAndReadsStepsInOrder() {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memTrace?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        var store = new SqliteTraceStore(jt);
        // 乱序插入（先 seq=1 再 seq=0），验证 load 按 seq 升序回读而非插入序
        store.append(new TraceStep("run1",1,"L2","tool_invoke","local_retrieve 保留2块"));
        store.append(new TraceStep("run1",0,"L3","model_step","决定调工具"));
        List<TraceStep> steps = store.load("run1");
        assertThat(steps).extracting(TraceStep::layer).containsExactly("L3","L2");
    }
}
