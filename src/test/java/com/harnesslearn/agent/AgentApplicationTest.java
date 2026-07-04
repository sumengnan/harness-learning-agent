package com.harnesslearn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.corpus.seed-on-startup=false"
})
class AgentApplicationTest {
    @Test
    void contextLoads() { }
}
