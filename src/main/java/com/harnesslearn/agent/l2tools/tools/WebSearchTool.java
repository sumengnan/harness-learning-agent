package com.harnesslearn.agent.l2tools.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import com.harnesslearn.agent.l2tools.Tool;

public class WebSearchTool implements Tool {
    public interface SearchBackend { String search(String query) throws Exception; }
    private final SearchBackend backend;
    private final ObjectMapper mapper = new ObjectMapper();
    public WebSearchTool(SearchBackend backend) { this.backend = backend; }
    public String name() { return "web_search"; }
    public String description() { return "联网搜索 agent harness 相关资料，返回候选 URL 与摘要。参数: {query}"; }
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode a = mapper.readTree(call.argumentsJson());
            return ToolResult.ok(backend.search(a.get("query").asText()));
        } catch (Exception e) { return ToolResult.fail("web_search 失败: " + e.getMessage()); }
    }
}
