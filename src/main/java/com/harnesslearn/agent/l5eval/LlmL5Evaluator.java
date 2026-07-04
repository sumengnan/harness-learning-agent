package com.harnesslearn.agent.l5eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 单次调用的独立验证器。
 *
 * <p>以 critic（审查员）人格发起一次独立调用，只看【产出】和【证据】，
 * 解析模型返回的 critic JSON 为 {@link Verdict}。
 */
public class LlmL5Evaluator implements L5Evaluator {
    private static final String CRITIC = """
        你是独立审查员，不参与生成。只依据【产出】和【证据】判断，忽略任何生成过程。
        从四个维度审查：grounding(有据可查)、completeness(完整性)、relevance(相关性)、format(格式契约)。
        只输出 JSON: {"pass":bool,"confidence":0~1,"issues":[{"dimension":..,"detail":..}]}""";
    private final ChatLanguageModel model;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmL5Evaluator(ChatLanguageModel model) {
        if (model == null) {
            throw new IllegalArgumentException("model 不能为空");
        }
        this.model = model;
    }

    @Override
    public Verdict verify(TaskSpec task, AgentOutput output, List<Artifact> evidence) {
        String evidenceText = evidence.stream().map(Artifact::content).collect(Collectors.joining("\n---\n"));
        String prompt = "任务: " + task.userQuery() + "\n\n【产出】\n" + output.content()
            + "\n\n【证据】\n" + evidenceText;
        String json = model.generate(List.of(SystemMessage.from(CRITIC), UserMessage.from(prompt)),
            List.of()).content().text();
        return parse(json);
    }

    /**
     * 解析 critic JSON 为 {@link Verdict}。
     *
     * <p>契约：解析失败保守判为不通过（pass=false, confidence=0），
     * 并附一条 format 维度的 issue，交由 L6 处置。
     * critic JSON 缺 confidence 字段时默认 0.5（中性置信度）。
     */
    private Verdict parse(String json) {
        try {
            JsonNode n = mapper.readTree(extractJson(json));
            List<Issue> issues = new ArrayList<>();
            if (n.has("issues")) {
                for (JsonNode i : n.get("issues")) {
                    issues.add(new Issue(i.get("dimension").asText(), i.get("detail").asText()));
                }
            }
            return new Verdict(n.get("pass").asBoolean(), issues,
                n.has("confidence") ? n.get("confidence").asDouble() : 0.5);
        } catch (Exception e) {
            return new Verdict(false, List.of(new Issue("format", "验证器输出无法解析")), 0.0);
        }
    }

    /**
     * 从模型输出中截取首个 '{' 到末个 '}' 之间的子串（含两端），
     * 以容忍 JSON 外包裹的说明文字或代码块围栏；无法定位时原样返回。
     */
    private String extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
}
