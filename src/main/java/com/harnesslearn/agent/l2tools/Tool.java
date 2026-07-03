package com.harnesslearn.agent.l2tools;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
public interface Tool {
    String name();
    String description();
    ToolResult execute(ToolCall call);
}
