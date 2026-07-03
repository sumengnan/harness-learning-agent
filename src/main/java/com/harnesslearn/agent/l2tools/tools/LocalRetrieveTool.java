package com.harnesslearn.agent.l2tools.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l2tools.Tool;
import com.harnesslearn.agent.l4memory.LongTermMemory;

public class LocalRetrieveTool implements Tool {
    private final LongTermMemory memory;
    private final ObjectMapper mapper = new ObjectMapper();
    public LocalRetrieveTool(LongTermMemory memory) { this.memory = memory; }
    public String name() { return "local_retrieve"; }
    public String description() { return "在本地知识库中向量检索 agent harness 资料。参数: {query, k}"; }
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode a = mapper.readTree(call.argumentsJson());
            int k = a.has("k") ? a.get("k").asInt() : 5;
            var hits = memory.retrieve(a.get("query").asText(), k);
            return ToolResult.ok(mapper.writeValueAsString(hits));
        } catch (Exception e) { return ToolResult.fail("local_retrieve 失败: " + e.getMessage()); }
    }
}
