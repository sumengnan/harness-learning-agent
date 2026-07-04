package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunEventBusTest {

    private static TraceStep step(String runId, int seq) {
        return new TraceStep(runId, seq, "L3", "model_step", "d" + seq);
    }

    @Test
    void subscribeThenPublishDelivers() throws Exception {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q = bus.subscribe("r1");
        bus.publish("r1", step("r1", 0));
        TraceStep got = q.poll(1, TimeUnit.SECONDS);
        assertThat(got).isNotNull();
        assertThat(got.seq()).isZero();
    }

    @Test
    void publishWithoutSubscriberIsNoOp() {
        var bus = new RunEventBus();
        assertThatCode(() -> bus.publish("ghost", step("ghost", 0))).doesNotThrowAnyException();
    }

    @Test
    void runsAreIsolatedByRunId() {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q1 = bus.subscribe("r1");
        BlockingQueue<TraceStep> q2 = bus.subscribe("r2");
        bus.publish("r1", step("r1", 0));
        assertThat(q1).hasSize(1);
        assertThat(q2).isEmpty();
    }

    @Test
    void unsubscribeStopsDelivery() {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q = bus.subscribe("r1");
        bus.unsubscribe("r1");
        bus.publish("r1", step("r1", 0));
        assertThat(q).isEmpty();
    }
}
