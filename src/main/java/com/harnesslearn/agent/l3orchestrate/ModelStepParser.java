package com.harnesslearn.agent.l3orchestrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.ModelStep;
import com.harnesslearn.agent.domain.ToolCall;
import java.util.List;
import java.util.UUID;

/**
 * 把模型每步输出的 JSON 文本解析为 {@link ModelStep}。
 *
 * <p>协议：{@code {"thought":..,"action":"tool","tool":{"name":..,"arguments":{..}}}}
 * 表示调工具；{@code {"thought":..,"action":"final","answer":..}} 表示收尾。
 * 无法解析时返回一个既非 finish 也无工具的 ModelStep（thought 以 {@code PARSE_ERROR:} 前缀），
 * 由上层当作非法输出走 L6 恢复重试。
 */
public class ModelStepParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelStep parse(String raw) {
        try {
            JsonNode n = mapper.readTree(extract(raw));
            String thought = n.path("thought").asText("");
            if ("final".equals(n.path("action").asText())) {
                return new ModelStep(thought, List.of(), n.path("answer").asText(""));
            }
            JsonNode tool = n.get("tool");
            ToolCall call = new ToolCall(UUID.randomUUID().toString(),
                tool.get("name").asText(), tool.get("arguments").toString());
            return new ModelStep(thought, List.of(call), null);
        } catch (Exception e) {
            // 无法解析→当作需要重试的非法输出（finalAnswer=null 且无工具）
            return new ModelStep("PARSE_ERROR:" + e.getMessage(), List.of(), null);
        }
    }

    /** 从可能夹带自然语言的响应中截取首个 '{' 到末个 '}' 之间的 JSON 主体。 */
    private String extract(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
}
