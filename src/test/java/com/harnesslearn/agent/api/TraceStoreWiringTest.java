package com.harnesslearn.agent.api;

import com.harnesslearn.agent.observability.CompositeTraceStore;
import com.harnesslearn.agent.observability.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** 锁住接线：注入 TraceStore 拿到的是 @Primary 的 CompositeTraceStore（装饰器已生效）。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.ingest.enabled=false"
})
class TraceStoreWiringTest {
    @Autowired TraceStore traceStore;

    @Test
    void primaryTraceStoreIsComposite() {
        assertThat(traceStore).isInstanceOf(CompositeTraceStore.class);
    }
}
