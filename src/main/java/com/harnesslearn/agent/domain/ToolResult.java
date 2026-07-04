package com.harnesslearn.agent.domain;
public record ToolResult(boolean ok, String rawContent, String error) {
    public static ToolResult ok(String content) { return new ToolResult(true, content, null); }
    public static ToolResult fail(String error) { return new ToolResult(false, null, error); }
}
