package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.L1ContextAssembler;
import com.harnesslearn.agent.l2tools.L2ToolSystem;
import com.harnesslearn.agent.l4memory.ArtifactStore;
import com.harnesslearn.agent.l4memory.WorkingStateStore;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l6guardrail.L6Guardrail;
import com.harnesslearn.agent.observability.TraceStep;
import com.harnesslearn.agent.observability.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写自主 Agent 循环：把 L1（上下文装配）、L2（工具系统）、L5（独立验证）、
 * L6（约束校验+恢复）串成一个显式的 think→act→observe 循环，并把每步埋点到 TraceStore、
 * 把工作状态检查点与 evidence 逐块落盘到 L4（best-effort）。
 *
 * <p><b>软轨道</b>靠 L1 依据任务状态注入的提示引导模型收敛；
 * <b>硬约束</b>靠 {@code maxSteps} 步数上限 + finish 必过 L5 验证兜底。
 */
public class AgentLoop implements L3Orchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChatLanguageModel model;
    private final L1ContextAssembler l1;
    private final L2ToolSystem l2;
    private final L5Evaluator l5;
    private final L6Guardrail l6;
    private final int maxSteps;
    private final TraceStore trace;
    private final WorkingStateStore wss;
    private final ArtifactStore artifacts;
    private final ModelStepParser parser = new ModelStepParser();

    /** no-op trace：未接可观测性时不落盘。 */
    private static final TraceStore NOOP_TRACE = new TraceStore() {
        @Override public void append(TraceStep s) {}
        @Override public List<TraceStep> load(String runId) { return List.of(); }
    };
    /** no-op 工作状态存储：未接 L4 时不落盘（现有 6/7 参构造器用）。 */
    private static final WorkingStateStore NOOP_WSS = new WorkingStateStore() {
        @Override public void checkpoint(String runId, WorkingState state) {}
        @Override public WorkingState load(String runId) { throw new UnsupportedOperationException("NOOP"); }
    };
    /** no-op 中间产物存储：未接 L4 时不落盘。 */
    private static final ArtifactStore NOOP_ARTIFACTS = new ArtifactStore() {
        @Override public void put(Artifact a) {}
        @Override public List<Artifact> query(ArtifactQuery q) { return List.of(); }
    };

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps) {
        this(model, l1, l2, l5, l6, maxSteps, NOOP_TRACE);
    }

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps, TraceStore trace) {
        this(model, l1, l2, l5, l6, maxSteps, trace, NOOP_WSS, NOOP_ARTIFACTS);
    }

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps, TraceStore trace,
                     WorkingStateStore wss, ArtifactStore artifacts) {
        this.model = model; this.l1 = l1; this.l2 = l2; this.l5 = l5; this.l6 = l6;
        this.maxSteps = maxSteps; this.trace = trace; this.wss = wss; this.artifacts = artifacts;
    }

    /** best-effort 埋点：可观测性失败绝不拖垮 Agent 主流程。 */
    private void safeAppend(TraceStep s) {
        try { trace.append(s); } catch (RuntimeException e) { /* 观测失败不影响主循环 */ }
    }

    /** best-effort 工作状态检查点：落盘失败记 WARN，不中断主循环。 */
    private void safeCheckpoint(WorkingState s) {
        try { wss.checkpoint(s.runId(), s); }
        catch (RuntimeException e) { log.warn("WorkingState 落盘失败 runId={}", s.runId(), e); }
    }

    /** best-effort 中间产物落盘：失败记 WARN，不中断主循环。 */
    private void safePut(Artifact a) {
        try { artifacts.put(a); }
        catch (RuntimeException e) { log.warn("Artifact 落盘失败 id={}", a.id(), e); }
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
            safeCheckpoint(state);                            // 每步入口：持久化累积状态
            AssembledContext ctx = l1.assemble(task, state, gathered);
            String rawResp = model.generate(ctx.messages(), List.of()).content().text();
            ModelStep step = parser.parse(rawResp);
            safeAppend(new TraceStep(task.runId(), seq++, "L3", "model_step", step.thought()));

            if (step.isFinish()) {
                AgentOutput output = new AgentOutput(step.finalAnswer(), List.copyOf(evidence));
                if (!l6.validateOutput(output).valid()) {
                    state.recordStep("产出为空，重试");
                    continue;
                }
                Verdict v = l5.verify(task, output, evidence);   // 硬约束：finish 必过 L5
                safeAppend(new TraceStep(task.runId(), seq++, "L5", "verdict",
                    "pass=" + v.pass() + " issues=" + v.issues()));
                if (v.pass()) {
                    safeCheckpoint(state);
                    return new AgentRun(task.runId(), output, true, "completed");
                }
                RecoveryDecision d = l6.onFailure(new FailureContext(FailureTypes.VERIFICATION_FAILED,
                    ++verifyAttempts, v.issues().toString()));
                safeAppend(new TraceStep(task.runId(), seq++, "L6", "recovery",
                    d.strategy() + ":" + d.note()));
                state.addOpenQuestion("验证未过: " + v.issues());
                if (d.strategy() == RecoveryStrategy.ABORT || d.strategy() == RecoveryStrategy.ROLLBACK) {
                    safeCheckpoint(state);
                    return new AgentRun(task.runId(), output, false, "verification_failed:" + d.note());
                }
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
                for (RetrievedChunk c : dr.chunks()) {
                    Artifact a = new Artifact(task.runId() + ":" + c.id(), task.runId(),
                        "evidence", c.sourceUri(), c.text(), java.util.Map.of());
                    evidence.add(a);
                    safePut(a);                                  // 逐块落盘
                }
                safeAppend(new TraceStep(task.runId(), seq++, "L2", "tool_invoke",
                    call.name() + "：" + dr.note()));
                state.recordStep("调用 " + call.name() + "：" + dr.note());
                continue;
            }

            // 既非 finish 也无工具 = 非法/解析失败
            RecoveryDecision d = l6.onFailure(new FailureContext(FailureTypes.INVALID_OUTPUT, ++invalidAttempts, step.thought()));
            safeAppend(new TraceStep(task.runId(), seq++, "L6", "recovery", d.strategy() + ":" + d.note()));
            if (d.strategy() == RecoveryStrategy.ABORT) {
                safeCheckpoint(state);
                return new AgentRun(task.runId(), new AgentOutput("(中止)", List.copyOf(evidence)), false, "invalid_output");
            }
            String why = step.thought();
            if (why.length() > 200) why = why.substring(0, 200);
            state.addOpenQuestion("上一步输出无法按协议解析（" + why
                + "）。必须严格只输出协议 JSON，勿加任何解释文字或代码块围栏。");
            state.recordStep("非法输出，重试");
        }
        // 预算耗尽：强制收尾，产出最佳可得
        safeCheckpoint(state);
        return new AgentRun(task.runId(),
            new AgentOutput("(预算耗尽，基于已有信息的部分结果)", List.copyOf(evidence)),
            false, "budget_exhausted");
    }
}
