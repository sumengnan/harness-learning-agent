package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCorpusResourceTest {

    /** 防手写 JSON 格式错误 / 空字段进生产：断言可解析且条数 ≥10、每条 text/uri 非空。 */
    @Test
    void productionSeedCorpusIsParseableAndNonTrivial() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/seed-corpus.json")) {
            assertThat(is).as("seed-corpus.json 应在 classpath").isNotNull();
            CorpusSeeder.SeedEntry[] entries =
                new ObjectMapper().readValue(is, CorpusSeeder.SeedEntry[].class);
            assertThat(entries.length).isGreaterThanOrEqualTo(10);
            for (CorpusSeeder.SeedEntry e : entries) {
                assertThat(e.text()).isNotBlank();
                assertThat(e.uri()).isNotBlank();
            }
        }
    }
}
