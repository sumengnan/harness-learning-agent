package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.AgentOutput;
import com.harnesslearn.agent.domain.FailureContext;
import com.harnesslearn.agent.domain.RecoveryDecision;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ValidationResult;

/** L6 约束、校验与恢复层门面：动作校验 / 产出校验 / 失败恢复。 */
public interface L6Guardrail {
    ValidationResult validateAction(ToolCall call);
    ValidationResult validateOutput(AgentOutput output);
    RecoveryDecision onFailure(FailureContext ctx);
}
