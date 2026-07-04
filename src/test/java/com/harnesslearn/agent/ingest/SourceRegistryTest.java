package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRegistryTest {

    @Test
    void exposesConfiguredSources() {
        var src = new Source("blog", SourceType.PAGE, "https://x/blog", List.of("harness"));
        var props = new IngestProperties(true, "0 0 * * * *", 800, List.of(src));
        assertThat(new SourceRegistry(props).sources()).containsExactly(src);
    }

    @Test
    void nullSourcesTreatedAsEmpty() {
        var props = new IngestProperties(false, null, 800, null);
        assertThat(new SourceRegistry(props).sources()).isEmpty();
    }
}
