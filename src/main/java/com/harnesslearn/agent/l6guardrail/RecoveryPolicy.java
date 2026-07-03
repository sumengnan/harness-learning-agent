package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.FailureContext;
import com.harnesslearn.agent.domain.RecoveryDecision;
import com.harnesslearn.agent.domain.RecoveryStrategy;

/** 确定性的 失败类型 → 恢复策略 映射引擎。 */
public class RecoveryPolicy {
    private final int maxRetries;

    public RecoveryPolicy(int maxRetries) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries 不能为负: " + maxRetries);
        this.maxRetries = maxRetries;
    }

    public RecoveryDecision decide(FailureContext ctx) {
        boolean exhausted = ctx.attempt() > maxRetries;
        return switch (ctx.failureType()) {
            case "web_search_failed", "fetch_page_failed", "evidence_insufficient", "subagent_failed"
                -> new RecoveryDecision(RecoveryStrategy.DEGRADE, "降级：改用本地/父Agent兜底");
            case "invalid_output", "invalid_tool_args"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.ABORT, "重试超限")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "带错误反馈重试");
            case "verification_failed"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.ROLLBACK, "回滚 checkpoint")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "issues 回灌重试");
            case "llm_call_failed"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.DEGRADE, "降级本地语料")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "指数退避重试");
            case "budget_exhausted", "loop_detected"
                -> new RecoveryDecision(RecoveryStrategy.ABORT, "强制收尾");
            default -> new RecoveryDecision(RecoveryStrategy.ABORT, "未知失败: " + ctx.failureType());
        };
    }
}
