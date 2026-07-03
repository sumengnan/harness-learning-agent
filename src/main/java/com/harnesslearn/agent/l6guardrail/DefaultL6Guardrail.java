package com.harnesslearn.agent.l6guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.AgentOutput;
import com.harnesslearn.agent.domain.FailureContext;
import com.harnesslearn.agent.domain.RecoveryDecision;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ValidationResult;

import java.util.Objects;

/** {@link L6Guardrail} 默认实现：动作/产出确定性校验，失败恢复委托 {@link RecoveryPolicy}。 */
public class DefaultL6Guardrail implements L6Guardrail {
    private final RecoveryPolicy policy;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultL6Guardrail(RecoveryPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
    }

    @Override
    public ValidationResult validateAction(ToolCall call) {
        if (call == null || call.name() == null || call.name().isBlank())
            return ValidationResult.invalid("工具名为空");
        try {
            mapper.readTree(call.argumentsJson());
        } catch (Exception e) {
            return ValidationResult.invalid("工具参数非合法 JSON");
        }
        return ValidationResult.ok();
    }

    @Override
    public ValidationResult validateOutput(AgentOutput output) {
        if (output == null || output.content() == null || output.content().isBlank())
            return ValidationResult.invalid("产出为空");
        return ValidationResult.ok();
    }

    @Override
    public RecoveryDecision onFailure(FailureContext ctx) {
        return policy.decide(ctx);
    }
}
