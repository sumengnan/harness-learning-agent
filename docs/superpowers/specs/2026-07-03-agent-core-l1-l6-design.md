# 子项目 B：Agent 核心（L1–L6）设计规格

- 日期：2026-07-03
- 范围：整个平台的**子项目 B —— 6 层自主决策 Agent 核心**
- 状态：已通过头脑风暴逐节确认，待评审

---

## 0. 背景与范围界定

### 0.1 整体愿景拆分

用户的完整需求（"Harness 学习小助手：生产级自主决策 Agent + 多源采集 + 全量可观测 + 前端"）是一个**多子系统平台**，拆分为 4 个独立子项目，各走自己的 规格 → 计划 → 实现 周期：

| 子系统 | 职责 |
|--------|------|
| **A. 数据采集与清洗管道** | 从公众号 / 官网 / 博客 / 视频平台抓取 → 去重 → 垃圾剔除 → 相关性过滤 → 结构化 & 向量化存储 |
| **B. 6 层自主 Agent 核心（本规格）** | langchain4j 实现的生产级自主决策循环 + 子 Agent 编排 |
| **C. 可观测性（横切）** | 全链路日志、追踪、指标 —— 不是独立子项目，嵌入 A/B/D 每一层 |
| **D. 前端（Next.js + React）** | 对话界面、来源展示、任务状态可视化 |

关系：B 是心脏；A 为 B 提供知识库；C 包裹 B；D 是通往 B 的界面。**构建顺序：B → A → D**，C 从第一天焊死进 B。

### 0.2 本规格只覆盖子项目 B

**"Harness" 的确切含义**：AI Agent Harness / 智能体脚手架。本助手帮用户学习**如何构建 AI Agent 脚手架**本身（agent 工程、上下文管理、工具编排）。相关性过滤要保留的正是这类内容。

### 0.3 已确认的关键决策

| 维度 | 决策 |
|------|------|
| 任务形态 | 深度问答（RAG QA）、专题综述/学习报告生成、最新进展追踪/简报、学习路径规划（旗舰任务：综述） |
| 后端 LLM | 国内模型（DeepSeek / Qwen），走 OpenAI 兼容接口 |
| 部署规模 | 本地单用户演示（无登录），架构预留升级到多用户 |
| L4 存储 | SQLite + 本地向量库，预留 Postgres + pgvector |
| B 的数据面 | 本地种子语料 + 联网搜索（Tavily/SearXNG + Jsoup）；公众号/视频留给子项目 A |
| 核心引擎 | **混合**：顶层 planner 用手写 Agent 循环拿到 L1–L6 完整控制权；子 Agent 用 langchain4j AI Services 封装有界子任务 |

---

## 1. 技术栈

| 层面 | 选型 | 理由 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.x | DI、配置、REST/SSE、Micrometer 观测集成 |
| Agent 框架 | langchain4j（core + open-ai 模块） | 用 OpenAI 兼容协议接 DeepSeek/Qwen |
| Embedding | 本地 ONNX（bge-small-zh-v1.5） | 离线、零调用成本、中文友好 |
| 向量库 | SQLite-vec（或 langchain4j 文件持久化的 InMemoryEmbeddingStore） | 本地零依赖，接口抽象，可换 pgvector |
| 状态/记忆 | SQLite | 任务状态、中间产物、长期记忆分表存储 |
| 联网工具 | Tavily 或 SearXNG + Jsoup 抓正文 | 覆盖官网/博客类 |
| 观测 | Logback JSON + langchain4j `ChatModelListener` + 领域 AgentTrace | 每次 LLM 调用、每步决策全量落盘 |
| 测试 | JUnit 5 + `FakeChatModel`（自定义 `ChatModel`） | 自主循环确定性、零成本、可重放测试 |

---

## 2. 模块结构（按 6 层分包，边界即职责）

```
com.harnesslearn.agent
├── l1context/      信息边界层：角色/目标、上下文装配、信息裁剪
├── l2tools/        工具系统层：工具注册表、调用、结果提炼、相关性过滤
├── l3orchestrate/  执行编排层：手写 Agent 循环（planner）
├── l4memory/       记忆状态层：WorkingState / Scratchpad / LongTermMemory
├── l5eval/         评估观测层：独立验证器（grounding/完整性）
├── l6guardrail/    约束恢复层：规则校验 + 重试/回滚/降级
├── subagent/       子 Agent：有界子任务（AI Services 封装）
├── observability/  追踪、监听器、日志配置
├── llm/            模型配置（DeepSeek/Qwen 可切换）
└── api/            REST + SSE 控制器
```

每个包对外只暴露一个接口（如 `L2ToolSystem`、`L5Evaluator`），内部实现可替换而不影响调用方 —— 这是能独立测试每一层的前提。

---

## 3. 数据流（以"专题综述"为例）

```
请求(SSE) → L1装配上下文(角色+目标+仅相关信息)
   ↓
L3 planner 循环开始 ──┐
   │  模型推理→决定动作 │
   │       ↓           │
   │  L2 执行工具(搜索/检索/抓取)→提炼→相关性过滤(剔垃圾)
   │       ↓           │
   │  L4 记录中间产物&工作状态(与对话隔离)
   │       ↓           │
   │  [需要时] 派发子 Agent(逐来源摘要) ← subagent
   │       ↓           │
   └──── 模型判定"是否完成" ─┘（未完成则继续循环）
   ↓
L5 独立验证器审查产出(有据可查?覆盖全?)
   ↓
L6 规则校验 → 失败则 重试/回滚/降级
   ↓
最终报告返回 + 更新长期记忆
   ↑
observability 全程记录每一步 trace
```

**关键点**：L5 验证器是**独立的一次模型调用**（换视角审查），不复用生成时的上下文 —— 这才叫"独立于生成过程的验证机制"。

---

## 4. L1–L6 逐层设计

每层对外只暴露一个接口，内部可替换、可独立测试。接口签名为设计示意，实现时以计划为准。

### L1 · 信息边界层 —— Agent 该知道什么

**职责**：定义角色与目标、裁剪无关信息、把任务状态结构化组织进上下文。

```java
interface L1ContextAssembler {
    AssembledContext assemble(TaskSpec task, WorkingState state, List<RetrievedChunk> candidates);
}
record AssembledContext(String systemPrompt, List<ChatMessage> messages, ContextBudget budget);
```

- **角色**："AI Agent Harness 学习助手"，system prompt 显式圈定范围（只讨论 agent 工程/上下文/工具编排；越界问题礼貌拒绝）。
- **任务状态块**：把 `目标 / 已完成步骤 / 待解问题 / 约束` 渲染成紧凑结构化段落，而非堆原始对话历史。
- **预算裁剪**：按与当前查询相关度排序候选信息，超预算截断低相关部分。上下文里永远只放"该知道的"。

### L2 · 工具系统层 —— 怎么和外部世界交互

**职责**：控制暴露哪些工具、执行、**提炼**结果再回填、剔除垃圾数据。

```java
interface Tool { ToolSpec spec(); ToolResult execute(ToolCall call); }
interface L2ToolSystem {
    List<ToolSpec> availableTools(TaskContext ctx);   // 控制"何时能用什么"
    DistilledResult invoke(ToolCall call);            // 执行 + 提炼 + 相关性过滤
}
```

- **工具集（B）**：`web_search`、`fetch_page`(Jsoup 抽正文)、`local_retrieve`(向量检索)、`spawn_subagent`、`finish`。
- **提炼**：抓到的网页不整页塞回上下文 —— 先抽正文→分块→留与任务最相关的 top-k，避免原始 HTML 污染判断。
- **相关性过滤**：见 §7。

### L3 · 执行编排层 —— 多步骤怎么串起来（Agent 的心脏）

**职责**：让模型按"理解目标→判断信息→分析→生成→检查"轨道推进。

```java
interface L3Orchestrator { AgentRun run(TaskSpec task); }  // 驱动循环，返回含完整 trace 的运行结果
```

手写循环伪代码：
```
while (!done && budget.ok()):
    ctx   = L1.assemble(task, state, L4.relevant())
    step  = model.call(ctx)               // 模型自主决定下一步
    if step.isTool:     obs = L2.invoke(step.call); L4.record(obs)
    if step.isSubAgent: dispatch(step)
    if step.isFinish:   done = true
verdict = L5.verify(task, output, evidence)
L6.enforce(verdict)                        // 失败→重试/回滚/降级
```

- **和 workflow 的本质区别**：五个阶段是**软轨道**（靠任务状态块提示 + 可选校验引导），**状态转移由模型自主决定**；workflow 会把顺序写死。
- **硬约束由 L6 兜底**（"未过 L5 验证不允许 finish"、最大步数），而非硬编码流程 —— 这就是"自主但可控"。

### L4 · 记忆与状态层 —— 中间结果怎么管

**职责**：把三类状态**物理隔离**，避免混在一起污染判断。

```java
interface WorkingStateStore { WorkingState load(String runId); void checkpoint(String runId, WorkingState s); }
interface ArtifactStore     { ArtifactId put(Artifact a); List<Artifact> query(ArtifactQuery q); }
interface LongTermMemory    { List<RetrievedChunk> retrieve(String q, int k); void remember(MemoryItem m); }
```

| 存储 | 装什么 | 生命周期 |
|---|---|---|
| **WorkingState**（当前任务） | 目标、计划、已完成步骤、待解问题、预算 | 单次运行，checkpoint 到 SQLite |
| **Artifacts**（中间产物） | 检索块、逐来源摘要、草稿分节 | 按 key 存取，L1 只按需拉取 |
| **LongTermMemory**（长期） | 种子语料向量库、跨会话学到的事实/偏好 | 持久 |

**关键**：对话历史 ≠ 任务状态 ≠ 中间产物，三者互不泄漏。

### L5 · 评估与观测层 —— 怎么知道自己做对了

**职责**：建立**独立于生成过程**的验证机制。

```java
interface L5Evaluator { Verdict verify(TaskSpec task, AgentOutput output, List<Artifact> evidence); }
record Verdict(boolean pass, List<Issue> issues, double confidence);
```

- **独立的一次模型调用**，critic 人格，只看"产出 + 证据"，**看不到生成时的思维链**。
- 验证维度：**有据可查**（每条论断能否在证据里找到支撑）、**完整性**（是否覆盖任务要求方面）、**相关性**（是否跑题）、**格式契约**（结构化输出是否合规）。
- Verdict 回喂 L3（可触发再一轮）和 L6。

### L6 · 约束、校验与恢复层 —— 出错了怎么办

**职责**：规则拦截错误，失败时重试/回滚/降级。

```java
interface L6Guardrail {
    ValidationResult validateAction(ToolCall call);   // 动作前：参数 schema、预算/步数上限
    ValidationResult validateOutput(AgentOutput out); // 动作后：输出契约、安全
    RecoveryDecision onFailure(FailureContext ctx);   // retry | rollback | degrade | abort
}
```

- **预防式校验（确定性规则，非模型决定）**：工具参数 schema、输出 JSON 契约、步数/预算上限、"必须先过 L5 才能 finish"不变量。
- **恢复策略**：重试（issues 回灌带反馈改）、回滚（退回上一 L4 checkpoint 重做该步）、降级（联网失败→仅本地语料；子 Agent 失败→父 Agent 兜底；产出"基于有限信息"的答案而非崩溃）。
- 本质是 `失败类型 → 策略` 的**确定性策略引擎**，作为可靠安全网。详见 §6。

---

## 5. 子 Agent 设计

顶层 planner（L3 手写循环）可派发**有界子 Agent**；子 Agent 用 langchain4j `@AiService` 封装，各有窄工具集和独立上下文。

```java
interface SubAgent<I, O> {
    SubAgentSpec spec();                 // 名称/职责/输入输出 schema/可用工具/预算
    O run(I input, SubContext ctx);
}
interface SubAgentDispatcher {
    <I,O> O dispatch(SubAgent<I,O> agent, I input);
    <I,O> List<O> dispatchParallel(SubAgent<I,O> agent, List<I> inputs);  // 虚拟线程并行
}
```

| 子 Agent | 输入 → 输出 | 用途 |
|---|---|---|
| `SourceSummarizer` | 单个来源(URL/块集) → 结构化摘要(要点+可引用片段+相关度) | 综述/简报的逐来源压缩，**可并行** |
| `SectionWriter` | 大纲一节 + 证据 → 该节草稿 | 长报告分节生成 |
| `QueryExpander`(可选) | 子问题 → 多检索查询 + 汇总 | 复杂问答的检索扩展 |

- **为什么关键**：主 planner 上下文**不被 N 个来源原文淹没** —— 每来源在子 Agent 里压成摘要，主 Agent 只集成摘要。这是 L1 信息边界在多 Agent 层面的延伸。
- **隔离与容错**：每个子 Agent 有独立上下文窗口 + 独立预算，只读分配给它的 artifacts，产出写回 `ArtifactStore`；父 Agent 只看提炼结果。子 Agent 失败由 L6 捕获 → 降级（父 Agent 兜底）。

---

## 6. 错误处理矩阵（L6 策略引擎映射表）

确定性映射，**每个失败都记 trace，永不静默失败** —— 要么恢复，要么明确降级并告知产出局限。

| 失败类型 | 检测点 | 策略 |
|---|---|---|
| LLM 调用失败（超时/限流/5xx） | LLM 客户端 | 指数退避重试 N 次 → 仍失败：降级用本地语料 / abort |
| 模型返回不合法（工具参数或 JSON 契约不符） | L6 `validateAction/Output` | 拦截 → 带错误信息回灌重试 → 超次数 abort 该步 |
| `web_search` 无结果/超时 | L2 | 降级到 `local_retrieve` |
| `fetch_page` 抓取失败/403 | L2 | 跳过该来源、记录、继续其他来源 |
| 相关性过滤后证据不足（全被丢） | L2→L3 | 再检索（换查询）；仍不足 → "基于有限信息"作答 + 标注低置信 |
| 子 Agent 失败/超预算 | Dispatcher | 父 Agent 兜底直接处理该子任务 |
| L5 验证不通过 | L5→L6 | issues 回灌 L3 重跑；连续 K 次不过 → 回滚 checkpoint 或降级产出 + 标注"未通过完整验证" |
| 循环超步数/超预算 | L3 + L6 | 强制收尾：用现有 artifacts 产出最佳可得结果 + 标注不完整 |
| 死循环（反复调同一无效工具） | L3 重复动作检测 | 注入"换策略"提示 → 仍重复则 abort |

---

## 7. 相关性过滤 / 垃圾剔除（确保都是 harness 相关）

多道闸，从粗到细，**全部发生在 L2 提炼阶段（进入上下文之前）**：

1. **来源级名单**（可选）：高质量域名加权，已知垃圾站直接拒。
2. **正文抽取**：Jsoup 去导航/广告/评论区噪音。
3. **相关度打分闸门（核心）**：
   - bge-small-zh 把内容块 embed，与"agent harness 领域中心向量"（种子语料 centroid + 一组 anchor 查询）算余弦相似度；
   - 低于阈值 τ → 直接丢弃并记日志（`dropped, score, reason`）；
   - 边界分数（τ±δ）的块交给轻量 LLM 二分类（relevant / not），避免误杀。
4. **去重**：embedding 近邻或 SimHash 去重，避免同一资料多份挤占上下文。
5. **末端兜底**：L5 的"相关性"维度再审最终证据。

**核心**：垃圾数据在进入上下文前就被拦掉，绝不影响模型判断；每次丢弃都留痕（可观测、阈值可调）。

---

## 8. 全量可观测性（把日志输出全）

三层，缺一不可：

1. **结构化日志（Logback JSON）** —— 每条带 `runId / stepId / layer / event`，可按 `runId` grep 出整条链路。
2. **LLM 调用级追踪（langchain4j `ChatModelListener`）** —— `onRequest / onResponse / onError` 钩子，记录**每一次**模型调用的 prompt、response、token、耗时、模型名。这是"全量"的核心。
3. **领域级 AgentTrace** —— 每个 run 一棵 trace 树，持久化到 SQLite，通过 REST + SSE 实时推送（为子项目 D 预留）：

```
run
 └─ step[i]
     ├─ L1: 装配了哪些信息 / 裁掉了什么 / token 预算
     ├─ L2: 工具 / 参数 / 原始大小→提炼后大小 / 相关性过滤丢弃数
     ├─ L3: 模型思考 / 决定的动作 / 当前阶段
     ├─ L4: checkpoint / artifact 存取
     ├─ L5: verdict + issues + confidence
     └─ L6: 触发的校验 / 失败类型 / 恢复策略
```

- **指标（Micrometer）**：每 run 的步数、token 消耗、工具/子 Agent 次数、验证通过率、恢复触发次数、端到端耗时。
- **配置**：默认 INFO 结构化日志，DEBUG 输出全 prompt；API key 等敏感信息脱敏。

---

## 9. 分层测试策略（TDD，先写测试）

因每层接口隔离，**每层都能独立测**。关键使能器是 **`FakeChatModel`**（langchain4j 自定义 `ChatModel`）—— 可编排返回序列，让整个自主循环**确定性、零成本、可重放**地测试。

| 层/对象 | 测什么 | 手段 |
|---|---|---|
| **L1** | 裁剪后只留相关块、结构化块正确、超预算被截 | 固定 fixture |
| **L2** | 提炼逻辑、相关性过滤（喂垃圾块→被丢）、去重 | mock 工具 + 固定块 |
| **L3** | 给定"调工具→观察→finish"脚本，循环正确推进并正确调用 L2/L4/L5/L6 | FakeChatModel 编排 |
| **L4** | 三存储 CRUD + 隔离性（写 WorkingState 不影响 Artifacts） | 内存 SQLite |
| **L5** | 喂"有据/无据"产出 → 断言 verdict | FakeChatModel 固定 critic |
| **L6** | 每种失败类型 → 正确策略；schema 拦截 | 参数化测试 |
| **子 Agent** | 输入输出契约、并行 dispatch、失败降级 | FakeChatModel |
| **集成** | 端到端编排一个完整"综述"run → trace 完整、产出结构、L5 被调、观测齐全 | FakeChatModel 全脚本 |
| **相关性过滤评测集** | 小标注集（相关/垃圾）→ precision/recall 达阈值，防回归 | 标注 fixture |
| **可观测性** | 每 run 产生完整 AgentTrace、`ChatModelListener` 触发、敏感信息脱敏 | 断言 trace |
| **契约冒烟（可选）** | 少量真实 DeepSeek/Qwen 调用 | `@Tag("live")`，CI 默认跳过 |

命令（占位，脚手架建好后填入 CLAUDE.md）：`mvn test` / 单测 `mvn -Dtest=L3OrchestratorTest test` / live `mvn -Ptest-live test`。

---

## 10. 非目标（YAGNI，本子项目不做）

- 多源抓取管道（公众号/视频平台）—— 子项目 A。
- 用户体系、鉴权、多租户 —— 部署规模确定为本地单用户。
- 前端界面 —— 子项目 D（B 只提供 REST + SSE 接口与 trace 数据）。
- 分布式/横向扩展、Postgres+pgvector 落地 —— 仅在接口层预留，不实现。

---

## 11. 开放问题（实现计划阶段细化）

1. 联网搜索选 Tavily（需 API key）还是自建 SearXNG（无 key、需部署）—— 实现时按环境定，接口层抽象为 `web_search`。
2. 种子语料的具体来源清单与规模（几十篇 agent 工程文档）—— 实现前需准备一小批。
3. `FakeChatModel` 的脚本 DSL 形态 —— 计划阶段定义。
