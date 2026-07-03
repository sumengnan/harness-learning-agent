package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    static class EchoTool implements Tool {
        public String name() { return "echo"; }
        public String description() { return "回显参数"; }
        public ToolResult execute(ToolCall call) { return ToolResult.ok(call.argumentsJson()); }
    }

    @Test
    void registersAndDispatchesByName() {
        var reg = new ToolRegistry(List.of(new EchoTool()));
        assertThat(reg.names()).containsExactly("echo");
        ToolResult r = reg.get("echo").execute(new ToolCall("1","echo","{\"x\":1}"));
        assertThat(r.rawContent()).isEqualTo("{\"x\":1}");
    }

    @Test
    void unknownToolThrows() {
        var reg = new ToolRegistry(List.of());
        assertThatThrownBy(() -> reg.get("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
