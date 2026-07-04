package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.L1ContextAssembler;
import com.harnesslearn.agent.l2tools.L2ToolSystem;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l6guardrail.L6Guardrail;
import com.harnesslearn.agent.observability.TraceStep;
import com.harnesslearn.agent.observability.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写自主 Agent 循环：把 L1（上下文装配）、L2（工具系统）、L5（独立验证）、
 * L6（约束校验+恢复）串成一个显式的 think→act→observe 循环，并把每步埋点到 TraceStore。
 *
 * <p><b>软轨道</b>靠 L1 依据任务状态注入的提示引导模型收敛；
 * <b>硬约束</b>靠 {@code maxSteps} 步数上限 + finish 必过 L5 验证兜底。
 */
public class AgentLoop implements L3Orchestrator {
    private final ChatLanguageModel model;
    private final L1ContextAssembler l1;
    private final L2ToolSystem l2;
    private final L5Evaluator l5;
    private final L6Guardrail l6;
    private final int maxSteps;
    private final TraceStore trace;
    private final ModelStepParser parser = new ModelStepParser();

    /** no-op trace：6 参便利构造用，未接可观测性时不落盘。 */
    private static final TraceStore NOOP_TRACE = new TraceStore() {
        @Override public void append(TraceStep s) {}
        @Override public List<TraceStep> load(String runId) { return List.of(); }
    };

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps) {
        this(model, l1, l2, l5, l6, maxSteps, NOOP_TRACE);
    }

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps, TraceStore trace) {
        this.model = model; this.l1 = l1; this.l2 = l2; this.l5 = l5; this.l6 = l6;
        this.maxSteps = maxSteps; this.trace = trace;
    }

    @Override
    public AgentRun run(TaskSpec task) {
        WorkingState state = WorkingState.start(task.runId(), task.userQuery(), maxSteps);
        List<RetrievedChunk> gathered = new ArrayList<>();
        List<Artifact> evidence = new ArrayList<>();
        int verifyAttempts = 0;
        int invalidAttempts = 0;
        int seq = 0;

        while (!state.budgetExhausted()) {
            AssembledContext ctx = l1.assemble(task, state, gathered);
            String rawResp = model.generate(ctx.messages(), List.of()).content().text();
            ModelStep step = parser.parse(rawResp);
            trace.append(new TraceStep(task.runId(), seq++, "L3", "model_step", step.thought()));

            if (step.isFinish()) {
                AgentOutput output = new AgentOutput(step.finalAnswer(), List.copyOf(evidence));
                if (!l6.validateOutput(output).valid()) {
                    state.recordStep("产出为空，重试");
                    continue;
                }
                Verdict v = l5.verify(task, output, evidence);   // 硬约束：finish 必过 L5
                trace.append(new TraceStep(task.runId(), seq++, "L5", "verdict",
                    "pass=" + v.pass() + " issues=" + v.issues()));
                if (v.pass()) return new AgentRun(task.runId(), output, true, "completed");
                RecoveryDecision d = l6.onFailure(new FailureContext(FailureTypes.VERIFICATION_FAILED,
                    ++verifyAttempts, v.issues().toString()));
                trace.append(new TraceStep(task.runId(), seq++, "L6", "recovery",
                    d.strategy() + ":" + d.note()));
                state.addOpenQuestion("验证未过: " + v.issues());
                if (d.strategy() == RecoveryStrategy.ABORT || d.strategy() == RecoveryStrategy.ROLLBACK)
                    return new AgentRun(task.runId(), output, false, "verification_failed:" + d.note());
                state.recordStep("按验证反馈重试");   // RETRY/DEGRADE：继续循环
                continue;
            }

            if (step.hasToolCalls()) {
                ToolCall call = step.toolCalls().get(0);
                if (!l6.validateAction(call).valid()) {
                    state.recordStep("非法工具调用，跳过");
                    continue;
                }
                DistilledResult dr = l2.invoke(call);
                gathered.addAll(dr.chunks());
                for (RetrievedChunk c : dr.chunks())
                    evidence.add(new Artifact(c.id(), task.runId(), "evidence", c.sourceUri(), c.text(), java.util.Map.of()));
                trace.append(new TraceStep(task.runId(), seq++, "L2", "tool_invoke",
                    call.name() + "：" + dr.note()));
                state.recordStep("调用 " + call.name() + "：" + dr.note());
                continue;
            }

            // 既非 finish 也无工具 = 非法/解析失败
            RecoveryDecision d = l6.onFailure(new FailureContext(FailureTypes.INVALID_OUTPUT, ++invalidAttempts, step.thought()));
            trace.append(new TraceStep(task.runId(), seq++, "L6", "recovery", d.strategy() + ":" + d.note()));
            if (d.strategy() == RecoveryStrategy.ABORT)
                return new AgentRun(task.runId(), new AgentOutput("(中止)", List.copyOf(evidence)), false, "invalid_output");
            state.recordStep("非法输出，重试");
        }
        // 预算耗尽：强制收尾，产出最佳可得
        return new AgentRun(task.runId(),
            new AgentOutput("(预算耗尽，基于已有信息的部分结果)", List.copyOf(evidence)),
            false, "budget_exhausted");
    }
}
