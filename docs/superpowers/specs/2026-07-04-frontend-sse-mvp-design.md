# 子项目 D · 前端 SSE MVP 设计规格

> 状态：已批准（brainstorming 分节确认）。下一步：writing-plans。

## §0 背景与定位

「Harness 学习小助手」整体分解为 4 个子项目：A 数据采集与清洗管道、B 6 层自主 Agent 核心（已合并 master）、C 可观测性（已焊入 B）、D 前端。构建顺序 B→A→D。本规格是 D。

**核心定位（用户决策）**：MVP —— **任务提交 + 结果展示**。不做完整 6 层可视化探险，不做 run 历史/语料管理控制台。

**技术栈（用户决策）**：Vite + React + TypeScript SPA，构建产物入 `src/main/resources/static/`，Spring Boot 同源托管。

**等待体验（用户决策）**：后端改 **SSE 流式推送**（而非同步阻塞转圈或轮询）。

**SSE 事件粒度（用户决策）**：**逐步 layer 事件（轻量 trace）**——每步推 `{seq, layer, event, detail}`，前端渲染 L1–L6 步骤时间线 + 最终结果。

### 现有后端契约（master 0cc2b39，不改动的部分）
- `POST /runs`，body `{type, query}`，**同步阻塞**返回 `{runId, success, output, termination}`。**本轮保留不动**，其 `AgentControllerTest`（`@WebMvcTest`）继续有效。
- `TaskType` 四枚举：`QA / SURVEY / DIGEST / LEARNING_PATH`。
- `AgentRun(runId, AgentOutput output, boolean success, String terminationReason)`；`AgentOutput(String content, List<Artifact> evidence)`。
- `L3Orchestrator.run(TaskSpec)` 同步门面。
- `AgentLoop` 每步调私有 `safeAppend(TraceStep)` → 注入的 `TraceStore`（单例 `SqliteTraceStore`）。`TraceStep(runId, seq, layer, event, detail)`。埋点层/事件：`L3/model_step`、`L5/verdict`、`L6/recovery`、`L2/tool_invoke`。
- 无前端脚手架、无 `static/`、无 Node 工程——D 从零起步。

## §1 架构总览与工程形态

单进程（Spring Boot）；前端是其静态资源。三块组件：

1. **前端 SPA**（仓库根 `frontend/`）：单页，提交任务 → 开 SSE → 实时渲染 L1–L6 步骤时间线 → 展示最终结果 + 证据。
2. **后端 SSE 端点**（`api/` 新增）：`GET /runs/stream`，跑 agent 并把每步 `TraceStep` 作为 SSE 事件推出，结束推最终结果。保留 `POST /runs` 不动。
3. **事件桥**（`observability/` 新增）：`RunEventBus`（进程内、按 runId 订阅）+ `CompositeTraceStore`（`@Primary`，既落 SQLite 又发布到 bus）。`AgentLoop` 零改动。

**构建/交付**：
- 开发期：Vite dev server（5173）+ proxy `/runs*` → `localhost:8080`。
- 交付期：`npm run build` 输出到 `static/`；`mvn` **不**联动 Node（保持 `mvn test` 无 Node 依赖、无需 API key）。`static/` 构建产物 gitignore，文档说明手动构建。

**关键取舍**：`CompositeTraceStore` + `RunEventBus` 做「tee」，让 SSE 实时拿到 agent 每步事件，而 `AgentLoop` 零改动。发布 best-effort（无订阅者廉价空转），与全项目风格一致。

## §2 后端 SSE 接线

### `RunEventBus`（`observability/`，新增）
进程内事件总线，按 runId 分发。
- `Subscription subscribe(String runId)`：注册订阅，返回句柄（内部有界 `BlockingQueue<TraceStep>`）。
- `void publish(String runId, TraceStep step)`：投递给该 runId 订阅者；**无订阅者空转**（best-effort）。
- `void complete(String runId)`：投递终止哨兵，让 SSE 侧收尾。
- `ConcurrentHashMap<String, 订阅>`；订阅结束即移除，防泄漏。有界队列 + 超时/丢弃策略防慢消费者堆积。

### `CompositeTraceStore`（`observability/`，新增，`@Primary`）
装饰器，构造 `(SqliteTraceStore delegate, RunEventBus bus)`。
- `append(step)`：先 `delegate.append(step)`（落库，保持现有行为），再 `bus.publish(step.runId(), step)`（发布）。**两步各自 best-effort，互不拖累**（任一 catch 记 WARN）。
- `load(runId)`：委托 delegate。
- `AgentLoop` 因 `@Primary` 注入到它 → AgentLoop 一行不改。

### `RunStreamController`（`api/`，新增）
`GET /runs/stream?type=…&query=…` 返回 `SseEmitter`。流程：
1. 生成 `runId`；
2. `bus.subscribe(runId)`；
3. 虚拟线程（`newVirtualThreadPerTaskExecutor`，与子项目 B Dispatcher 一致）上跑 `orchestrator.run(new TaskSpec(runId, TaskType.valueOf(type), query, Map.of()))`；
4. 主处理侧循环从订阅队列取 `TraceStep`，每条以 SSE `event: step`、`data:` = JSON `{seq, layer, event, detail}` 推出；
5. run 返回后，推 `event: result`、`data:` = `{success, output, evidence, termination}`；
6. `bus.complete(runId)` + `emitter.complete()`。

**数据流**：
```
浏览器 EventSource ──GET /runs/stream?type&query──▶ RunStreamController
                                                      │ subscribe(runId)
                                                      │ 虚拟线程: orchestrator.run(task)
        ┌──────────── AgentLoop 每步 trace.append(step) ────────────┐
        ▼                                                            │
CompositeTraceStore ──① delegate.append→SQLite（落库）                │
        └──────② bus.publish(runId, step)──▶ 订阅队列 ──▶ SSE event:step ──▶ 浏览器实时渲染
run 结束 ──▶ SSE event:result（output+evidence）──▶ complete
```

**为什么这样切**：AgentLoop 零改动，风险最低；落库与推流解耦、各自 best-effort；runId 由控制器先生成再建订阅，保证事件不丢、键不错位；虚拟线程让同步的 `orchestrator.run` 能边跑边推。

## §3 前端组件与数据流

### 工程结构（`frontend/`）
Vite + React + TS。`vite.config.ts` 设 `build.outDir = '../src/main/resources/static'`、dev proxy `/runs*`→`8080`。`src/main.tsx` 挂载 `App`。

### 组件树（单一职责小组件）
```
App
├─ TaskForm        提交表单
├─ RunTimeline     实时步骤时间线
└─ ResultPanel     最终结果 + 证据
```
- **`TaskForm`**：`type` 下拉（4 个 TaskType，中文标签）+ `query` 多行输入 + 提交按钮。提交时校验非空；运行中禁用。
- **`RunTimeline`**：随 SSE `event: step` 逐条追加。每行：`[L?]` 层徽章（L1–L6 各一色）+ event 名 + detail 摘要（超长截断，可点开）。顶部活动指示（脉冲点=运行中）。
- **`ResultPanel`**：收到 `event: result` 后显示。`success` 徽章 + `termination` 文案 + `output` Markdown 渲染 + `evidence` 列表（每条：来源 URI 链接 + 内容片段，可折叠）。

### 状态机（`App` 持有，`useReducer`）
```
idle ──submit──▶ streaming ──event:result──▶ done
                    │  └──event:step──▶ 追加 timeline（留在 streaming）
                    ├──event:error──▶ error
                    └──连接断开────▶ error
done/error ──再次 submit──▶ 清空 timeline/result，回到 streaming
```

### SSE 客户端
原生 `EventSource`（`GET /runs/stream?type=…&query=…`，参数 URL 编码）。封装 `useRunStream(params)` hook：内部 `new EventSource(url)`，监听 `step`/`result`/`error` 三类命名事件，dispatch 到 reducer；组件卸载或运行结束 `es.close()`。
- **取舍**：`EventSource` 只支持 GET，query 走 URL 参数。本工具 query 是一句提问/主题，长度可控；若将来 query 可能很长，再换 `fetch`+`ReadableStream`（POST body）。MVP 先 `EventSource`。

### 类型契约（`src/api/types.ts`，对齐后端 record）
```ts
type TaskType = 'QA' | 'SURVEY' | 'DIGEST' | 'LEARNING_PATH';
interface StepEvent   { seq: number; layer: string; event: string; detail: string; }
// 对齐后端 record Artifact(id, runId, kind, key, content, meta)；来源 URI 在 meta.uri
interface Artifact    { id: string; runId: string; kind: string; key: string; content: string; meta: Record<string,string>; }
interface ResultEvent { success: boolean; output: string; evidence: Artifact[]; termination: string; }
```
> `ResultPanel` 展示证据时，来源链接取 `artifact.meta.uri`（可能缺省，缺则不显示链接）。

### 样式
手写 CSS（`app.css`），干净极简、单列布局、深浅色跟随系统——不引 UI 组件库。Markdown 渲染用轻量库 `marked`（唯一运行时第三方依赖）。

## §4 错误处理与边界

### 后端（全线 best-effort，异常不拖垮进程）
- **worker 线程 `orchestrator.run` 抛异常**：`RunStreamController` catch → 推 `event: error`（`data:{message}`）→ `bus.complete` → `emitter.completeWithError`。agent 自身失败多已被 L6 消化为 `success=false`（正常走 `event: result`）；这里兜意外 RuntimeException。
- **`CompositeTraceStore` 两步各自 best-effort**：落库失败不影响推流，推流失败（无订阅者/队列满）不影响落库，各 catch 记 WARN。
- **`RunEventBus` 防泄漏**：SSE `onCompletion`/`onTimeout`/`onError` 三回调都注销订阅、移除 map 条目；有界队列 + 超时/丢弃防慢消费者堆积。
- **SSE 超时**：`SseEmitter` 设较长超时（如 5 分钟，> max-steps×单步耗时上界）；超时当 error 收尾。
- **参数校验**：`type` 非法（非 4 枚举）→ 400；`query` 空 → 400。（顺手补齐：现有 `POST /runs` 的 `TaskType.valueOf` 非法抛 500，SSE 端点显式校验返 400。）

### 前端
- **`event: error` / 连接断开 / `EventSource.onerror`**：转 `error` 态，显示可读错误 + 「重试」按钮（重发同参数）。
- **区分「运行中断开」与「正常结束关闭」**：正常结束由 `event: result` 后主动 `es.close()`，该 close 不算错误；仅未收 result 前的 onerror 判 error（reducer 用 `done` 标志区分）。
- **空结果/证据为空**：`ResultPanel` 显示「无证据」占位，不崩。
- **超长 detail/output**：timeline detail 截断可展开；output 容器限高滚动。
- **重复提交防护**：`streaming` 态禁用提交按钮。

### 边界与并发
SSE 端点每次请求独立 runId、独立订阅，天然隔离；`RunEventBus` 用 `ConcurrentHashMap` + 线程安全队列，支持多并发 SSE run。SQLite 单写者由 HikariCP `pool-size=1` 串行化（已有）。高并发非 MVP 目标。

## §5 测试策略

### 后端（内存 SQLite + `FakeChatModel`，`mvn test` 无需 API key、无 Node）
- **`RunEventBusTest`**（纯单元）：subscribe→publish→取到；无订阅者 publish 空转不抛；complete 投递哨兵；注销后不再收。
- **`CompositeTraceStoreTest`**（纯单元）：`append` 同时落 delegate（内存 SQLite 可查）+ 发布到 bus（stub 收到）；delegate 抛异常 publish 仍执行，反之亦然。
- **`RunStreamControllerTest`**（`@WebMvcTest` + MockMvc async 或 `WebTestClient`）：`FakeChatModel` 脚本驱动 orchestrator 产生确定步骤，断言 SSE 流依次含 `event: step`（≥1，含 L 层标签）后跟一条 `event: result`（success/output）；`type` 非法→400；`query` 空→400；worker 异常→`event: error`。
- **回归**：现有 `AgentControllerTest`（`POST /runs`）保持通过。

### 前端（Vitest + React Testing Library，`npm test`，与 `mvn` 解耦）
- **`useRunStream`**：注入假 `EventSource`，依次触发 `step`/`result`，断言状态流转 `idle→streaming→done`、timeline 累积；`error`→error 态；未收 result 就 close→error。
- **`TaskForm`**：空 query 禁提交；`streaming` 禁用按钮；type 下拉 4 项。
- **`RunTimeline`**：给定 step 列表渲染对应层徽章与条数。
- **`ResultPanel`**：success/失败徽章、evidence 空占位、Markdown 基本项。
- **`App` 冒烟**：全流程假 SSE 跑 submit→timeline→result。

**不做**：真实 LLM / 真实浏览器 E2E（Playwright）不在 MVP。

## §6 非目标（YAGNI）
- ❌ 6 层决策**完整可视化**探险（层过滤、trace 回放、图形化）——只做轻量线性时间线。
- ❌ run **历史列表 / 持久化回看**（`GET /runs/{id}`、列表页）——只做当次运行实时流。
- ❌ 语料库浏览 / 采集管道管理 / 手动触发（「完整控制台」定位，本轮未选）。
- ❌ 逐 **token** 流式输出（需改 langchain4j 流式接口与整个循环）。
- ❌ 鉴权 / 多用户 / 会话保存。
- ❌ Maven 联动 Node 构建（保持 `mvn test` 纯净，手动 `npm run build`）。
- ❌ 真实浏览器 E2E、SSR、PWA、国际化。
