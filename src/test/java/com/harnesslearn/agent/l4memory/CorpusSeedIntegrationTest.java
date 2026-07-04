package com.harnesslearn.agent.l4memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产路径端到端：seed-on-startup=true 时，corpusBootstrap 在启动时把种子摄取进
 * 与 LocalRetrieveTool 共享的同一 longTermMemory 单例，故检索非空。
 * 锁住「摄取进的 store == 检索读的 store」这条接线，防回归。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.corpus.seed-on-startup=true"
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
