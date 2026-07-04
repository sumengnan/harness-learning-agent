package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CompositeTraceStoreTest {

    private SqliteTraceStore sqlite(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new SqliteTraceStore(jt);
    }

    private static TraceStep step(String runId, int seq) {
        return new TraceStep(runId, seq, "L3", "model_step", "d" + seq);
    }

    @Test
    void appendPersistsAndPublishes() {
        var bus = new RunEventBus();
        var delegate = sqlite("memComposite1");
        var composite = new CompositeTraceStore(delegate, bus);
        BlockingQueue<TraceStep> q = bus.subscribe("r1");

        composite.append(step("r1", 0));

        assertThat(delegate.load("r1")).hasSize(1);   // 落库
        assertThat(q).hasSize(1);                      // 发布
        assertThat(composite.load("r1")).hasSize(1);   // load 委托 delegate
    }

    @Test
    void delegateFailureStillPublishes() {
        var bus = new RunEventBus();
        TraceStore throwingDelegate = new TraceStore() {
            public void append(TraceStep s) { throw new RuntimeException("db down"); }
            public List<TraceStep> load(String runId) { return List.of(); }
        };
        var composite = new CompositeTraceStore(throwingDelegate, bus);
        BlockingQueue<TraceStep> q = bus.subscribe("r1");

        assertThatCode(() -> composite.append(step("r1", 0))).doesNotThrowAnyException();
        assertThat(q).hasSize(1);                      // 落库失败不挡发布
    }

    @Test
    void publishFailureStillPersists() {
        var delegate = sqlite("memComposite2");
        RunEventBus throwingBus = new RunEventBus() {
            @Override public void publish(String runId, TraceStep step) { throw new RuntimeException("bus down"); }
        };
        var composite = new CompositeTraceStore(delegate, throwingBus);

        assertThatCode(() -> composite.append(step("r1", 0))).doesNotThrowAnyException();
        assertThat(delegate.load("r1")).hasSize(1);    // 发布失败不挡落库
    }
}
