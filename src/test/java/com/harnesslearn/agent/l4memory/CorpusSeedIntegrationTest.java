package com.harnesslearn.agent.l4memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产路径端到端：ingest.enabled=true 时，启动种子写 SQLite 后经 CorpusIndexRebuilder
 * 重建索引，检索非空。锁住「摄取进的 store == 检索读的 store」这条接线，防回归。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.ingest.enabled=true",
    "agent.ingest.poll-cron=0 0 0 * * *",
    "agent.ingest.chunk-max-chars=800"
})
class CorpusSeedIntegrationTest {

    @Autowired
    LongTermMemory longTermMemory;

    @Test
    void startupSeedingMakesLocalRetrieveNonEmpty() {
        var hits = longTermMemory.retrieve("上下文工程与信息边界", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anyMatch(h -> h.text() != null && !h.text().isBlank());
    }
}
