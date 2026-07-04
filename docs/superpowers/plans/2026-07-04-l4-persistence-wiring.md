# L4 持久化接线进 AgentLoop 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 `AgentLoop` 每步把 `WorkingState` 检查点写入 `working_state` 表、逐块把 evidence `Artifact` 写入 `artifact` 表，使这两张表真正落盘。

**架构：** 仿现有 `NOOP_TRACE` 模式给 `AgentLoop` 加两个 no-op store 常量 + 一个全参构造器（6/7 参委托传 NOOP，保证现有测试不落盘、行为不变）；在 `run()` 的 while 开头 + 各 return 前 best-effort `safeCheckpoint(state)`，工具分支逐块 `safePut(artifact)`（id 用 `runId:chunkId` 前缀防跨 run PK 冲突）；落盘失败记 WARN 不中断主循环。`AgentConfig` 改注入真实 Sqlite store。

**技术栈：** Java 21 + Spring Boot 3.3.4 + langchain4j 0.35.0 + SQLite（JdbcTemplate）+ JUnit5/AssertJ。

**规格：** `docs/superpowers/specs/2026-07-04-l4-persistence-wiring-design.md`

---

## 关键既有契约（实现时须遵守，勿改这些类）

- `WorkingStateStore.checkpoint(String runId, WorkingState state)` — UPSERT。`load(runId)` 未命中抛 `EmptyResultDataAccessException`。
- `ArtifactStore.put(Artifact a)` — `INSERT OR REPLACE`，PK=`id`。`query(ArtifactQuery)` 未命中返回空 List。
- `record Artifact(String id, String runId, String kind, String key, String content, Map<String,String> meta)`
- `record ArtifactQuery(String runId, String kind)`
- `WorkingState`：`runId()` / `goal()` / `completedSteps()`（返回 `List.copyOf`）/ `openQuestions()` / `recordStep(String)` / `addOpenQuestion(String)` / `budgetExhausted()`。`start(runId, goal, stepBudget)` 静态工厂。
- `SchemaInitializer(JdbcTemplate).init()` 建 `working_state`/`artifact`/`trace_step` 三表。
- L4 测试必用 `org.springframework.jdbc.datasource.SingleConnectionDataSource(url, true)`（内存 SQLite 保活；`DriverManagerDataSource` 会因连接开关销毁内存库而 "no such table"）。参考 `src/test/java/com/harnesslearn/agent/l4memory/SqliteArtifactStoreTest.java`。

---

## 任务 1：AgentLoop 接线 L4 持久化

**文件：**
- 修改：`src/main/java/com/harnesslearn/agent/l3orchestrate/AgentLoop.java`
- 测试：`src/test/java/com/harnesslearn/agent/l3orchestrate/AgentLoopPersistenceTest.java`（创建）

- [ ] **步骤 1：写失败测试 `AgentLoopPersistenceTest.java`**

```java
package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l2tools.L2ToolSystem;
import com.harnesslearn.agent.l4memory.*;
import com.harnesslearn.agent.l5eval.LlmL5Evaluator;
import com.harnesslearn.agent.l6guardrail.DefaultL6Guardrail;
import com.harnesslearn.agent.l6guardrail.RecoveryPolicy;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AgentLoopPersistenceTest {

    private JdbcTemplate jdbc;
    private SqliteWorkingStateStore wss;
    private SqliteArtifactStore artifacts;

    @BeforeEach
    void setUp() {
        // 每个测试独立库名，避免串味；suppressClose 保活内存库
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memAgentLoopPersist" + System.nanoTime() + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(ds);
        new SchemaInitializer(jdbc).init();
        wss = new SqliteWorkingStateStore(jdbc);
        artifacts = new SqliteArtifactStore(jdbc);
    }

    /** 返回固定一块证据的桩 L2，避开 RelevanceFilter 的本地 ONNX embedding 加载。 */
    private L2ToolSystem stubL2(String chunkId) {
        return new L2ToolSystem() {
            @Override public List<String> availableTools() { return List.of("local_retrieve"); }
            @Override public DistilledResult invoke(ToolCall call) {
                return new DistilledResult(
                    List.of(new RetrievedChunk(chunkId, "u1", "agent 上下文工程要点", 0.9)), 0, "1块");
            }
        };
    }

    /** planner：先 local_retrieve，再 final；critic：判 pass。 */
    private AgentLoop loopWith(FakeChatModel planner, L2ToolSystem l2,
                               WorkingStateStore w, ArtifactStore a) {
        var critic = FakeChatModel.scripted(AiMessage.from("{\"pass\":true,\"confidence\":0.9,\"issues\":[]}"));
        return new AgentLoop(planner, new DefaultL1ContextAssembler(5), l2,
            new LlmL5Evaluator(critic), new DefaultL6Guardrail(new RecoveryPolicy(2)), 10,
            AgentLoopTestSupport.NOOP_TRACE, w, a);
    }

    private FakeChatModel planner() {
        return FakeChatModel.scripted(
            AiMessage.from("{\"thought\":\"检索\",\"action\":\"tool\",\"tool\":{\"name\":\"local_retrieve\",\"arguments\":{\"query\":\"x\"}}}"),
            AiMessage.from("{\"thought\":\"够了\",\"action\":\"final\",\"answer\":\"# 综述\\n要点\"}"));
    }

    @Test
    void persistsWorkingStateCheckpoint() {
        AgentRun run = loopWith(planner(), stubL2("c1"), wss, artifacts)
            .run(new TaskSpec("run-p1", TaskType.SURVEY, "综述", Map.of()));
        assertThat(run.success()).isTrue();
        WorkingState loaded = wss.load("run-p1");            // 未命中会抛，命中即证明已落盘
        assertThat(loaded.completedSteps()).isNotEmpty();    // 至少记录了工具调用那一步
    }

    @Test
    void persistsEvidenceArtifactWithRunIdPrefixedId() {
        loopWith(planner(), stubL2("c1"), wss, artifacts)
            .run(new TaskSpec("run-p2", TaskType.SURVEY, "综述", Map.of()));
        List<Artifact> ev = artifacts.query(new ArtifactQuery("run-p2", "evidence"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).id()).isEqualTo("run-p2:c1");   // runId 前缀
        assertThat(ev.get(0).content()).contains("上下文工程");
    }

    @Test
    void crossRunSameChunkIdDoNotCollide() {
        // 两个 run 检索同一 chunk id "dup"，各自 evidence 应独立不互相覆盖
        loopWith(planner(), stubL2("dup"), wss, artifacts)
            .run(new TaskSpec("runA", TaskType.SURVEY, "A", Map.of()));
        loopWith(planner(), stubL2("dup"), wss, artifacts)
            .run(new TaskSpec("runB", TaskType.SURVEY, "B", Map.of()));
        assertThat(artifacts.query(new ArtifactQuery("runA", "evidence"))).hasSize(1);
        assertThat(artifacts.query(new ArtifactQuery("runB", "evidence"))).hasSize(1);
    }

    @Test
    void persistenceFailureIsBestEffortAndDoesNotCrashRun() {
        // 注入 put/checkpoint 均抛异常的 store 桩，run 仍应正常完成
        WorkingStateStore boomW = new WorkingStateStore() {
            @Override public void checkpoint(String runId, WorkingState s) { throw new RuntimeException("boom-w"); }
            @Override public WorkingState load(String runId) { throw new RuntimeException("boom-w"); }
        };
        ArtifactStore boomA = new ArtifactStore() {
            @Override public void put(Artifact a) { throw new RuntimeException("boom-a"); }
            @Override public List<Artifact> query(ArtifactQuery q) { return List.of(); }
        };
        assertThatCode(() ->
            loopWith(planner(), stubL2("c1"), boomW, boomA)
                .run(new TaskSpec("run-boom", TaskType.SURVEY, "综述", Map.of())))
            .doesNotThrowAnyException();
    }
}
```

- [ ] **步骤 2：加测试支持常量 `AgentLoopTestSupport`**

`AgentLoop` 的 `NOOP_TRACE` 是 private，测试用全参构造器需要一个 `TraceStore`。在测试包新建一个极小支持类提供 no-op trace（避免把生产常量改成 public）。

创建 `src/test/java/com/harnesslearn/agent/l3orchestrate/AgentLoopTestSupport.java`：
```java
package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.observability.TraceStep;
import com.harnesslearn.agent.observability.TraceStore;
import java.util.List;

/** 测试用 no-op TraceStore，供全参 AgentLoop 构造器使用。 */
final class AgentLoopTestSupport {
    static final TraceStore NOOP_TRACE = new TraceStore() {
        @Override public void append(TraceStep s) {}
        @Override public List<TraceStep> load(String runId) { return List.of(); }
    };
    private AgentLoopTestSupport() {}
}
```

- [ ] **步骤 3：运行验证失败**

运行：`mvn -q -Dtest=AgentLoopPersistenceTest test`
预期：编译失败——`AgentLoop` 尚无 9 参构造器 `AgentLoop(model,l1,l2,l5,l6,maxSteps,trace,WorkingStateStore,ArtifactStore)`。

- [ ] **步骤 4：改 `AgentLoop.java` 接线持久化**

用下面这份完整文件替换现有 `AgentLoop.java`（在既有版本上增量：加 logger、两个 NOOP store 常量、wss/artifacts 字段、9 参构造器 + 让 7 参委托它、safeCheckpoint/safePut、5 处 checkpoint 调用、artifact id 前缀 + safePut）：

```java
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
            state.recordStep("非法输出，重试");
        }
        // 预算耗尽：强制收尾，产出最佳可得
        safeCheckpoint(state);
        return new AgentRun(task.runId(),
            new AgentOutput("(预算耗尽，基于已有信息的部分结果)", List.copyOf(evidence)),
            false, "budget_exhausted");
    }
}
```

**注意：** 此文件保留了原有全部逻辑（trace 埋点、verifyAttempts/invalidAttempts 语义、evidence 收集），仅做增量接线。5 处 `safeCheckpoint(state)`：while 开头 1 处 + 4 个 return 前各 1 处（completed / verification_failed / invalid_output / budget_exhausted）。

- [ ] **步骤 5：运行验证通过**

运行：`mvn -q -Dtest=AgentLoopPersistenceTest test`
预期：PASS（4 个测试方法全绿）。

- [ ] **步骤 6：确认既有 AgentLoopTest 未受影响**

运行：`mvn -q -Dtest=AgentLoopTest test`
预期：PASS（6 参构造器走 NOOP，行为不变）。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/l3orchestrate/AgentLoop.java \
        src/test/java/com/harnesslearn/agent/l3orchestrate/AgentLoopPersistenceTest.java \
        src/test/java/com/harnesslearn/agent/l3orchestrate/AgentLoopTestSupport.java
git commit -m "feat(l3): AgentLoop 接线 L4 持久化（WorkingState 检查点 + evidence 逐块落盘，best-effort）"
```

---

## 任务 2：AgentConfig 注入真实 store + 全量回归

**文件：**
- 修改：`src/main/java/com/harnesslearn/agent/AgentConfig.java:116-122`（`agentLoop` bean 方法）

- [ ] **步骤 1：改 `agentLoop` bean 用全参构造器注入真实 store**

现有（`AgentConfig.java`）：
```java
    @Bean
    public L3Orchestrator agentLoop(@org.springframework.context.annotation.Lazy ChatLanguageModel model,
            L1ContextAssembler l1, L2ToolSystem l2,
            L5Evaluator l5, L6Guardrail l6, SqliteTraceStore trace,
            @Value("${agent.orchestrate.max-steps:20}") int maxSteps) {
        return new AgentLoop(model, l1, l2, l5, l6, maxSteps, trace);
    }
```

改为（追加两个 store 参数并传入全参构造器；这两个 bean 已在同类 `workingStateStore`/`artifactStore` 方法中定义）：
```java
    @Bean
    public L3Orchestrator agentLoop(@org.springframework.context.annotation.Lazy ChatLanguageModel model,
            L1ContextAssembler l1, L2ToolSystem l2,
            L5Evaluator l5, L6Guardrail l6, SqliteTraceStore trace,
            SqliteWorkingStateStore wss, SqliteArtifactStore artifacts,
            @Value("${agent.orchestrate.max-steps:20}") int maxSteps) {
        return new AgentLoop(model, l1, l2, l5, l6, maxSteps, trace, wss, artifacts);
    }
```

**注意：** `SqliteWorkingStateStore`/`SqliteArtifactStore` 已在 `AgentConfig` import（现有 `workingStateStore`/`artifactStore` bean 用到），无需新增 import。参数类型用具体 Sqlite 类型即可（与现有 `SqliteTraceStore trace` 参数风格一致）。

- [ ] **步骤 2：全量测试回归**

运行：`mvn -q test`
预期：全绿。总数 = 原 43 + 任务 1 新增 4（`AgentLoopPersistenceTest`）= 47，`Failures: 0, Errors: 0`。特别确认 `AgentApplicationTest#contextLoads` 仍绿（全参 `agentLoop` bean 的两个新依赖 `wss`/`artifacts` 都是既有 bean，DI 图完整、无循环）。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/AgentConfig.java
git commit -m "feat(config): agentLoop bean 注入 SqliteWorkingStateStore/SqliteArtifactStore 落盘"
```

---

## 自检结果（对照规格）

**1. 规格覆盖度：**
- §2 构造器扩展（NOOP 常量 + 全参构造器 + 委托）→ 任务 1 步骤 4 ✅
- §3 WorkingState 每步 checkpoint（while 开头 + 各 return 前）→ 任务 1 步骤 4 的 5 处 `safeCheckpoint` ✅
- §3 evidence Artifact `runId:chunkId` + `safePut` → 任务 1 步骤 4 工具分支 ✅
- §4 best-effort WARN 日志 → 任务 1 步骤 4 `safeCheckpoint`/`safePut` + logger ✅
- §5 测试（working_state 断言 / artifact 断言 / 跨 run 隔离 / best-effort 桩）→ 任务 1 步骤 1 的 4 个 @Test ✅
- §5 回归 43 全绿 → 任务 2 步骤 2 ✅
- 装配注入真实 store → 任务 2 步骤 1 ✅
- §6 不做读回 API / LTM 摄取 / resume → 计划未含，YAGNI ✅

**2. 占位符扫描：** 无 TODO/待定；所有代码步骤均含完整代码块。✅

**3. 类型一致性：** 全参构造器签名 `(model,l1,l2,l5,l6,int maxSteps,TraceStore,WorkingStateStore,ArtifactStore)` 在任务 1（定义）与任务 2（调用，传 `SqliteWorkingStateStore`/`SqliteArtifactStore`——是 `WorkingStateStore`/`ArtifactStore` 的实现，型变兼容）一致；测试用同一 9 参构造器（传桩实现 + `AgentLoopTestSupport.NOOP_TRACE`）。`Artifact` 构造器 6 参、`ArtifactQuery(runId,kind)`、`WorkingState.load/completedSteps/runId` 均与既有契约一致。✅
