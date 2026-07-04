# L4 持久化接线进 AgentLoop 设计规格

> 子项目 B（Agent 核心 L1–L6）最终整体审查发现的 deferred 集成缝之一。现决定接线 L4 持久化写入侧。

**目标：** 让 `AgentLoop` 每步把 `WorkingState` 检查点写入 `working_state` 表、逐块把 evidence `Artifact` 写入 `artifact` 表，使这两张表从此真正落盘（`trace_step` 已落盘，不改）。

**背景：** `SqliteWorkingStateStore`/`SqliteArtifactStore` 及其接口已实现并单测通过，但被声明为 bean 后无任何调用者——`AgentLoop` 全程内存态。本设计只接线**写入侧**，不含读回 API、崩溃恢复、LongTermMemory 摄取（后两者是独立 deferred 项）。

**范围：** 改 1 个生产类（`AgentLoop`）+ 装配 1 行（`AgentConfig.agentLoop`）+ 新增测试。两个 store 的接口与 Sqlite 实现不改。

---

## 1. 组件与职责边界

| 单元 | 职责 | 本设计是否改动 |
|---|---|---|
| `WorkingStateStore.checkpoint(runId, state)` | UPSERT 工作状态检查点 | 否（既有契约） |
| `ArtifactStore.put(Artifact)` | INSERT OR REPLACE 中间产物（PK=id） | 否（既有契约） |
| `AgentLoop` | 在正确时机调用上述两个 store 落盘 | **是（唯一生产类改动）** |
| `AgentConfig.agentLoop` | 用全参构造器注入真实 store | 是（1 行） |

职责不变：store 只管落盘，AgentLoop 决定何时落盘。

## 2. 构造器扩展（向后兼容）

仿现有 `NOOP_TRACE` 模式，在 `AgentLoop` 内新增两个包内 no-op store 常量与一个全参构造器：

```java
private static final WorkingStateStore NOOP_WSS = (runId, state) -> {};
private static final ArtifactStore NOOP_ARTIFACTS = new ArtifactStore() {
    @Override public void put(Artifact a) {}
    @Override public java.util.List<Artifact> query(ArtifactQuery q) { return java.util.List.of(); }
};

// 全参构造器
public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                 L5Evaluator l5, L6Guardrail l6, int maxSteps, TraceStore trace,
                 WorkingStateStore wss, ArtifactStore artifacts) { ... }
```

现有 6 参、7 参构造器改为委托传 `NOOP_WSS`/`NOOP_ARTIFACTS`。

**后果：** `AgentLoopTest`、`IntegrationSurveyTest`（均用 6 参构造器）零改动、行为不变、不落盘——回归安全。`AgentConfig.agentLoop` 改用全参构造器注入真实 `SqliteWorkingStateStore`/`SqliteArtifactStore` bean。

## 3. 数据流 / 落盘时机

**WorkingState 每步检查点：**
- 在 `run()` 的 while 循环体**开头**调用一次 `safeCheckpoint(state)`（持久化上一轮累积状态）。
- 在 while 循环退出后、以及每个提前 `return` 之前，各补一次 `safeCheckpoint(state)`（捕获终态，含最后一步的 `recordStep`/`addOpenQuestion` 变更）。
- `checkpoint` 是 UPSERT、幂等，重复写同一 runId 只覆盖最新状态。

**evidence Artifact 逐块落盘：**
- 工具分支构建每个 evidence Artifact 时，id 由 `c.id()` 改为 `task.runId() + ":" + c.id()`。
- 构建后立即 `safePut(a)`。
- `AgentOutput.evidence` 仍在内存 `List<Artifact>` 中收集完整列表并原样返回——返回值语义不变，落盘与内存列表并存。

**Artifact id 方案（已决策）：** `runId:chunkId` 前缀。跨 run 唯一（避免不同 run 检索同一 chunk id 时 PK 冲突互相覆盖）、同 run 内同 chunk 幂等去重（INSERT OR REPLACE）、保留与 chunk 的可追溯映射。

## 4. 错误处理（已决策：best-effort）

新增两个私有 best-effort 包装，仿现有 `safeAppend`：

```java
private void safeCheckpoint(WorkingState s) {
    try { wss.checkpoint(s.runId(), s); }
    catch (RuntimeException e) { log.warn("WorkingState 落盘失败 runId={}", s.runId(), e); }
}
private void safePut(Artifact a) {
    try { artifacts.put(a); }
    catch (RuntimeException e) { log.warn("Artifact 落盘失败 id={}", a.id(), e); }
}
```

用 SLF4J logger（`AgentLoop` 目前无 logger，新增一个 `private static final Logger`）。持久化失败记 **WARN** 日志后吞掉，绝不中断主循环——L4 属旁路，run 主产出（`AgentOutput`）不依赖落盘成功，SQLite 抖动不应拖垮整个 run。

> 注：已核实 `WorkingState.runId()` accessor 存在（`domain/WorkingState.java:18`），`safeCheckpoint` 内可直接用 `s.runId()` 作日志与 `checkpoint` 调用。

## 5. 测试

- **`AgentLoopPersistenceTest`（新）**：内存 SQLite（`SingleConnectionDataSource(url, true)` 保活，仓库既有模式）+ 真实 Sqlite store + `FakeChatModel` 脚本（tool→final）。断言：`working_state` 表有该 runId 记录且 `completed_steps` 非空；`artifact` 表按 `(runId,"evidence")` 查到 ≥1 行、id 带 `runId:` 前缀。
- **跨 run 隔离测试**：两个 run 检索同一 chunk id，断言各自 evidence 独立、互不覆盖（验证 runId 前缀方案的正确性）。
- **best-effort 测试**：注入一个 `put`/`checkpoint` 抛 `RuntimeException` 的 store 桩，断言 run 仍正常返回、不抛异常。
- **回归**：现有 43 测试保持全绿（6 参构造器走 NOOP，行为不变）。

## 6. 明确不做（YAGNI）

- 不加读回 API（`GET /runs/{id}/state|evidence`）——本轮只做写入侧。
- 不接线 LongTermMemory 语料摄取——独立 deferred 项，单独决策。
- 不做崩溃恢复 / run resume——`load` 虽已存在，恢复逻辑另议。

---

## 数据流示意

```
AgentLoop.run(task)
  ├─ while 开头: safeCheckpoint(state)         ── working_state UPSERT
  ├─ 工具分支: 逐 chunk 构建 Artifact(id=runId:chunkId)
  │             └─ safePut(artifact)            ── artifact INSERT OR REPLACE
  ├─ 各 return 前: safeCheckpoint(state)        ── 终态落盘
  └─ 返回 AgentRun（evidence 仍来自内存 List，语义不变）
```
