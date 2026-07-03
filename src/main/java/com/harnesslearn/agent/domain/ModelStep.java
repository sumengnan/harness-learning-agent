package com.harnesslearn.agent.domain;
import java.util.List;
public record ModelStep(String thought, List<ToolCall> toolCalls, String finalAnswer) {
    public boolean isFinish() { return finalAnswer != null; }
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
