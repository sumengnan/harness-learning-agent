package com.harnesslearn.agent.l2tools.tools;

import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FetchPageToolTest {
    @Test
    void failsGracefullyOnBadUrl() {
        ToolResult r = new FetchPageTool().execute(new ToolCall("1","fetch_page","{\"url\":\"http://invalid.invalid\"}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("fetch_page 失败");
    }
}
