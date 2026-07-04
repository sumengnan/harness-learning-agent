# LongTermMemory 种子语料摄取设计规格

> 子项目 B（Agent 核心 L1–L6）最终整体审查发现的第二处 deferred 集成缝。第一处（L4 持久化）已接线完成。现决定接线 LongTermMemory 的写入侧——启动时种子语料摄取。

**目标：** 新增一份内置种子语料，应用启动时逐条 `remember()` 填充 `embeddingStore`，使生产 `local_retrieve` 返回真实 harness 资料而非恒空。

**背景：** `VectorLongTermMemory.remember()`/`retrieve()` 与 `LocalRetrieveTool` 均已实现，但 `remember()` 无任何调用者、`InMemoryEmbeddingStore` 从不填充 → 生产 `local_retrieve` 恒返回空。本设计只接线**启动种子摄取**，不含 URL 抓取、SourceSummarizer 蒸馏、`/ingest` 端点、持久向量库（均为后续独立议题）。

**范围：** 新建种子资源 + `CorpusSeeder` 类 + 装配 1 个门控 runner；改 `AgentConfig`、`application.yml`、`AgentApplicationTest`（加关闭开关）。`VectorLongTermMemory`/`LongTermMemory`/`LocalRetrieveTool` 不改。

---

## 1. 组件与职责边界

| 单元 | 职责 | 改动 |
|---|---|---|
| `resources/seed-corpus.json` | 内置种子语料：~12 条 `{text, uri, tags}` 预整理 harness/上下文工程要点 | 新建 |
| `l4memory/CorpusSeeder` | 读种子资源 → 逐条 `MemoryItem` → `memory.remember()`；无 Spring 依赖，可直接 `new` 单测 | 新建 |
| `AgentConfig` | `CorpusSeeder` bean + 门控的 `ApplicationRunner corpusBootstrap` 启动时 `seed()` | 改（+2 bean 方法） |
| `application.yml` | 新增 `agent.corpus.seed-on-startup: true` | 改 |
| `AgentApplicationTest` | 加 `agent.corpus.seed-on-startup=false` 属性，关掉种子（上下文测试保持轻快） | 改（+1 属性） |
| `VectorLongTermMemory`/`LongTermMemory`/`LocalRetrieveTool` | 已就绪，无人喂数据而已 | **不改** |

## 2. 数据流

```
启动（agent.corpus.seed-on-startup=true）
  → corpusBootstrap(ApplicationRunner) → CorpusSeeder.seed()
     └─ getResourceAsStream("/seed-corpus.json") → 逐条 remember(MemoryItem{text, meta={uri,tags}})
                                                    └─ embed(text)+metadata 入 InMemoryEmbeddingStore
运行 → local_retrieve(query,k) → memory.retrieve → 命中种子内容（非空）
```

## 3. 已决策的设计取舍

- **直接 remember，不过 SourceSummarizer**：种子条目预先整理好，无需 LLM 蒸馏，保持离线、可测。SourceSummarizer 留待未来 `/ingest` 路径，本轮不接线。
- **持久化 YAGNI**：`embeddingStore` 仍是 `InMemoryEmbeddingStore`，每次启动重新种子（确定、无跨重启重复问题）。持久向量库是独立议题，不做。
- **摄取失败 best-effort**：种子资源缺失/JSON 解析失败 → 记 WARN 不中断启动；单条 `remember` 失败 → 记 WARN 跳过该条、继续其余。与 L4 落盘 / trace 的 best-effort 哲学一致。
- **启动开关**：`agent.corpus.seed-on-startup`（默认 `true`）。`corpusBootstrap` runner 用 `@ConditionalOnProperty(name="agent.corpus.seed-on-startup", havingValue="true", matchIfMissing=true)` 门控。测试关掉后该 runner bean 不创建、seeder 不执行、bge 嵌入不触发 → 上下文测试轻快。

## 4. 组件设计

**`CorpusSeeder`**（`l4memory` 包）：
```java
public class CorpusSeeder {
    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);
    private final LongTermMemory memory;
    private final String resourcePath;   // 如 "/seed-corpus.json"
    private final ObjectMapper mapper = new ObjectMapper();

    public CorpusSeeder(LongTermMemory memory, String resourcePath) { ... }

    /** 读种子资源逐条 remember。best-effort：资源缺失/解析失败记 WARN 返回；单条失败记 WARN 跳过。返回成功摄取条数。 */
    public int seed() { ... }

    // 种子条目 DTO
    public record SeedEntry(String text, String uri, java.util.List<String> tags) {}
}
```
- `seed()`：`getClass().getResourceAsStream(resourcePath)` → null 则 WARN 返回 0；`mapper.readValue(is, SeedEntry[].class)` 解析失败 → WARN 返回 0；逐条 `memory.remember(new MemoryItem(e.text(), Map.of("uri", e.uri(), "tags", String.join(",", e.tags()))))`，单条抛异常 → WARN 跳过；返回成功条数。
- `MemoryItem.meta` 是 `Map<String,String>`，故 `tags` 以逗号连接存为单个 String 值（与 `retrieve` 只读 "uri" 的现状兼容）。

**`AgentConfig`** 追加：
```java
@Bean
public CorpusSeeder corpusSeeder(LongTermMemory memory) {
    return new CorpusSeeder(memory, "/seed-corpus.json");
}

@Bean
@ConditionalOnProperty(name = "agent.corpus.seed-on-startup", havingValue = "true", matchIfMissing = true)
public ApplicationRunner corpusBootstrap(CorpusSeeder seeder) {
    return args -> seeder.seed();
}
```
（`corpusSeeder` bean 恒创建但构造廉价——只存引用，`embeddingModel` 是 @Lazy 代理不实体化；只有 `seed()` 被调用才加载 bge。关掉开关时 `corpusBootstrap` 不创建 → `seed()` 不被调 → 不加载 bge。）

**`seed-corpus.json`**（`src/main/resources/`）：~12 条真实 harness/上下文工程要点，形如
```json
[
  {"text": "上下文工程指在有限上下文窗口内裁剪无关信息、结构化组织任务状态……", "uri": "https://example.com/context-engineering", "tags": ["L1","上下文工程"]},
  ...
]
```

## 5. 测试

- **`CorpusSeederTest`（单元）**：真实 `VectorLongTermMemory`（本地 bge + `InMemoryEmbeddingStore`）+ 一个测试 fixture `src/test/resources/test-seed-corpus.json`（3~4 条）。跑 `seed()`，断言返回条数 = fixture 条数，且随后 `memory.retrieve("上下文工程", 3)` 非空、命中 fixture 内容。
- **缺失资源 best-effort 测试**：`new CorpusSeeder(memory, "/no-such.json").seed()`，断言返回 0、不抛，`retrieve` 仍空。
- **生产 seed-corpus.json 可解析测试**：`new CorpusSeeder` 指向 `/seed-corpus.json` 或直接读该资源解析成 `SeedEntry[]`，断言条数 ≥10 且每条 `text`/`uri` 非空（防手写 JSON 进生产时格式错）。
- **回归**：现有 47 测试保持全绿。`AgentApplicationTest` 因加了 `agent.corpus.seed-on-startup=false`，`corpusBootstrap` 不创建、不加载 bge，启动仍轻快、绿。

## 6. 明确不做（YAGNI）

- 不做 URL 抓取 / SourceSummarizer 接线 / `POST /ingest`（用户已选种子文件路径）。
- 不做持久向量库、增量/去重摄取、定时刷新。
- `retrieve` 只读 meta "uri"，`tags` 存了但检索期不用——留作未来过滤维度，本轮不消费。
