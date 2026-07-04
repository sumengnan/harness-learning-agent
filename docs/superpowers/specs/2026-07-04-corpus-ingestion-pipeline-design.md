# 数据采集与清洗管道（子项目 A）设计规格

> 状态：已批准，待编写实现计划
> 日期：2026-07-04
> 所属：Harness 学习小助手 · 子项目 A（数据采集与清洗管道）
> 依赖：子项目 B（Agent 核心 L1–L6，已合并 master）

## 0. 背景与定位

整体项目「Harness 学习小助手」拆分为四个子项目：A=数据采集与清洗管道、B=6 层自主 Agent 核心（已完成）、C=可观测性（横切，已焊入 B）、D=前端。本规格覆盖**子项目 A**。

A 的职责：从官网/博客/RSS 持续采集 harness 相关材料，经清洗、去重、剔垃圾、相关性过滤、切块后落盘为语料，并灌入 B 的向量记忆库供 Agent 检索。A 是进程内后台辅助模块，与 B 共享 Spring Boot 应用与既有 bean。

### 0.1 已确定的关键决策

| 维度 | 决策 |
|---|---|
| 来源范围（v1） | 官网/博客/RSS（HTTP + HTML/RSS，复用 Jsoup）；公众号/视频作为后续来源适配器 |
| 触发方式 | 配置源清单 + `@Scheduled` 定时轮询，按 URL/guid 增量去重 |
| 持久化 | SQLite 存清洗后语料（真相源）+ 启动时重新 embed 重建内存索引；不引入新向量库 |
| 内容形态 | 清洗后正文切块（≤800 字/块）直接 embed，无 LLM 摘要 |
| 架构 | 进程内新模块 `com.harnesslearn.agent.ingest`，自研单一职责小组件流水线 |

### 0.2 现有基础设施落点（实现须贴合）

- **DDL/建表**：集中在 `l4memory/SchemaInitializer.init()`，用 `JdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ...")`；由 `AgentConfig` 注册的 `ApplicationRunner schemaBootstrap` 启动时调用。
- **Store 模式**：`Sqlite*Store(JdbcTemplate)` 构造注入，注册为 `@Bean`。
- **DataSource**：Spring Boot 自动配置读 `application.yml`，`spring.datasource.url=jdbc:sqlite:${AGENT_DB:./data/agent.db}`，`hikari.maximum-pool-size=1`（SQLite 单写）。无手写 DataSource bean。
- **`@Scheduled` 未启用**：需在 `AgentConfig` 或主类加 `@EnableScheduling`。主类 `com.harnesslearn.agent.AgentApplication`（`@SpringBootApplication`，扫描其下全部子包）。
- **三个复用 bean**：`EmbeddingModel`（`llm/LlmConfig`，`@Lazy` `BgeSmallZhEmbeddingModel`）、`EmbeddingStore<TextSegment>`（`llm/LlmConfig`，`InMemoryEmbeddingStore`）、`LongTermMemory`（`AgentConfig`，`VectorLongTermMemory`）。
- **`VectorLongTermMemory`**：`void remember(MemoryItem item)`（把 `text()`+`meta()` 转 `TextSegment` embed 后 `store.add`）；`List<RetrievedChunk> retrieve(String query, int k)`（`retrieve` 读 metadata 的 `"uri"`）。
- **`MemoryItem`**：`record MemoryItem(String text, Map<String,String> meta) {}`。
- **可复用工具**：`l2tools/tools/FetchPageTool`（Jsoup 抓正文、去 script/style/nav/footer/header/aside、≤8000 字）；`l2tools/RelevanceFilter`（余弦-质心相关性门控，阈值 `agent.filter.relevance-threshold` 默认 0.80）。

## 1. 模块组件划分

新包 `com.harnesslearn.agent.ingest`，每个类单一职责：

| 组件 | 职责 | 依赖 |
|---|---|---|
| `SourceRegistry` | 从配置读来源清单，对外给 `List<Source>` | `IngestProperties` |
| `FeedReader` | 拉一个来源，产出条目清单 `List<FeedItem>`（url、title、guid、发布时间）。RSS 走 XML 解析，普通站点走单页链接提取 | Jsoup |
| `ArticleFetcher` | 拉单条目正文并清洗样板（复用 `FetchPageTool` 的 Jsoup 去样板逻辑），产出纯文本正文 | 复用 `FetchPageTool` |
| `RelevanceGate` | 用现成 `RelevanceFilter` 判正文与 harness 主题相关性，剔垃圾/离题 | 复用 `RelevanceFilter` bean |
| `Chunker` | 清洗后正文按段落+长度切块（≤800 字/块），产出 `List<String>` | 无 |
| `CorpusRepository` | SQLite 读写：`corpus_seen`（增量去重指纹）+ `corpus_chunk`（真相源）。`Sqlite*Store(JdbcTemplate)` 模式 | `JdbcTemplate` |
| `IngestionService` | 编排整条流水线，best-effort | 上述全部 + `LongTermMemory` |
| `IngestionScheduler` | `@Scheduled` 定时调 `IngestionService.ingestAll()` | `IngestionService` |
| `CorpusIndexRebuilder` | 启动 `ApplicationRunner`：从 `corpus_chunk` 读全量重建内存索引 | `CorpusRepository` + `LongTermMemory` |

**与现有 `CorpusSeeder` 的关系**：`seed-corpus.json` 的种子条目改为「首启若 `corpus_chunk` 为空则写入 SQLite」，之后统一由 `CorpusIndexRebuilder` 从 SQLite 重建。`CorpusSeeder` 从「直接灌内存」升级为「灌 SQLite」，种子与抓取语料走同一条持久化+重建路径，消除双份来源。

**装配**：bean 进 `AgentConfig`（或新建 `IngestConfig`）；轮询/重建/种子写入用 `@ConditionalOnProperty` 门控，测试 profile 可关。

## 2. 数据模型 + 建表 DDL

### 2.1 领域 record（`ingest` 包内）

```java
public enum SourceType { RSS, PAGE }

// 一个配置来源
public record Source(String id, SourceType type, String url, List<String> tags) {}

// FeedReader 从来源解析出的一个条目（尚未抓正文）
public record FeedItem(String guid, String url, String title, Long publishedEpochMs) {}

// 落库/重建用的一个语料块
public record CorpusChunk(String id, String sourceId, String url,
                          String title, int seq, String text, Long publishedTs) {}
```

- **去重指纹 `fingerprint`**：`FeedItem.guid` 非空则用 guid，否则用 url。
- `CorpusChunk.id`：确定性主键 = `fingerprint + ":" + seq`，重复抓取时 upsert 覆盖而非叠加。

### 2.2 建表 DDL（追加到 `SchemaInitializer.init()`）

```java
// 增量去重：记录每个来源已见过的条目指纹，避免重复抓取/入库
jdbc.execute("""
    CREATE TABLE IF NOT EXISTS corpus_seen(
      fingerprint TEXT PRIMARY KEY,
      source_id   TEXT,
      url         TEXT,
      title       TEXT,
      first_seen_ts INTEGER)""");

// 真相源：清洗切块后的语料，启动时据此重建内存向量索引
jdbc.execute("""
    CREATE TABLE IF NOT EXISTS corpus_chunk(
      id           TEXT PRIMARY KEY,
      source_id    TEXT,
      url          TEXT,
      title        TEXT,
      seq          INTEGER,
      text         TEXT,
      published_ts INTEGER,
      ingested_ts  INTEGER)""");
```

- 时间戳用 `System.currentTimeMillis()`。

### 2.3 配置模型（`@ConfigurationProperties(prefix="agent.ingest")`）

```java
public record IngestProperties(
    boolean enabled,           // 总开关，测试 profile 置 false
    String pollCron,           // @Scheduled cron
    int chunkMaxChars,         // 切块上限，默认 800
    List<SourceConfig> sources // 来源清单
) {
    public record SourceConfig(String id, SourceType type, String url, List<String> tags) {}
}
```

### 2.4 入内存库时 `MemoryItem` 的 meta

`retrieve` 读 `"uri"`，故必填：`{"uri": url, "title": title, "source": sourceId}`（值为 null 时兜底为空串，沿用 B 的 `Map.of` 空值防护约定）。

## 3. 数据流与增量去重时序

`IngestionService.ingestAll()`：

```
for each Source in SourceRegistry:                    ← 单来源失败 WARN 跳过，不影响其它
  items = FeedReader.read(source)                     ← RSS 解析 / 单页链接提取
  for each FeedItem:
    fp = fingerprint(item)                            ← guid 非空则 guid，否则 url
    if CorpusRepository.hasSeen(fp): continue         ← ① 增量去重：已见即跳过，不抓正文
    body = ArticleFetcher.fetch(item.url)             ← Jsoup 抓 + 去样板
    if body 为空/过短: markSeen(fp); continue         ← 记为已见，避免死链每轮重试
    if not RelevanceGate.isRelevant(body):            ← ② 相关性：离题/垃圾
        markSeen(fp); continue                        ←   记为已见，下次不再抓
    chunks = Chunker.split(body)
    for (seq, chunk) in chunks:
        id = fp + ":" + seq
        CorpusRepository.upsertChunk(CorpusChunk(...)) ← ③ 落 SQLite（真相源）
        LongTermMemory.remember(MemoryItem(chunk,     ← ④ 灌内存向量库
                    {uri,title,source}))
    CorpusRepository.markSeen(fp, source, url, title) ← ⑤ 全部块成功后才标记已见
```

### 三层去重/防重复语义

1. **条目级增量去重**（`corpus_seen`）：`hasSeen(fp)` 命中直接跳过，连正文都不抓——定时轮询不重复烧钱的关键。
2. **块级幂等**（`corpus_chunk.id = fp:seq` upsert）：同一条目异常重抓时 `INSERT OR REPLACE` 覆盖，不产生重复行。
3. **相关性剔除后也 `markSeen`**：离题条目记为已见，避免每轮重抓+embed 判定。

### 关键顺序

`markSeen` 放在**所有块落库成功之后**（步骤 ⑤）。中途 crash 时 `fp` 未标记已见，下轮重抓——宁可重抓（幂等 upsert 兜底）也不丢内容。

## 4. 相关性判定与切块

### 4.1 相关性判定 `RelevanceGate`

复用现成 `RelevanceFilter` bean 与同一阈值（`agent.filter.relevance-threshold` 默认 0.80），保证「进知识库的语料」与「Agent 运行时过滤工具结果」用同一把相关性尺子。

- `RelevanceGate.isRelevant(String body)`：把清洗后正文交给 `RelevanceFilter` 打分，≥阈值判相关。
- **实现细节留给计划阶段核对 `RelevanceFilter` 现有方法签名**：若它暴露的是「过滤列表」而非「单条打分」，则用单元素列表调用、判非空；若有打分方法则直接取分。设计意图是零改动复用，必要时在 `RelevanceFilter` 上补一个薄 `score(text)`/`accepts(text)` 方法（不改内部逻辑）。
- 用**整篇正文**判定（而非逐块），避免长文里个别边缘段落被误杀；整篇过关后再切块。

### 4.2 切块 `Chunker.split(String body) -> List<String>`

- 先按空行/段落分割，**贪心打包**：累积段落直到接近 `maxChars`（默认 800 字），超过就落一块、开新块。
- 单段落本身超上限：按句号/换行硬切到 ≤`maxChars`。
- **v1 不做块间重叠**（YAGNI）；若日后召回差再加 overlap 参数。
- 过滤空块、trim 首尾空白。
- `maxChars` 走配置 `agent.ingest.chunk-max-chars`（默认 800）。用字符数而非 token 数：bge 本地模型无现成 tokenizer 计数接口，800 中文字符在模型 512 token 安全区内，不会截断。

## 5. 启动重建索引 + 种子合并

### 5.1 `CorpusIndexRebuilder`（`ApplicationRunner`）

`InMemoryEmbeddingStore` 进程内、重启即空，`corpus_chunk`（SQLite）是真相源，每次启动必须重灌：

```
1. rows = CorpusRepository.allChunks()          ← 全量读 corpus_chunk
2. for each CorpusChunk:
     LongTermMemory.remember(MemoryItem(
         chunk.text(), {uri, title, source}))    ← 逐条重新 embed 灌内存库
3. log INFO "重建索引：N 块来自 M 篇"
```

- best-effort：单条 embed 失败 WARN 跳过，不阻塞启动。

### 5.2 种子合并（改造现有 `CorpusSeeder`）

```
CorpusSeeder.seed():
  if CorpusRepository.chunkCount() > 0: return 0     ← 已有语料，跳过播种
  for each SeedEntry in seed-corpus.json:
      id = "seed:" + hash(uri)                       ← 种子确定性主键
      CorpusRepository.upsertChunk(...)              ← 只写 SQLite
  return count
```

- 种子只负责把条目写进 SQLite，不再直接灌内存——之后统一由 `CorpusIndexRebuilder` 从 SQLite 重建。种子与抓取语料从此走同一条 SQLite→内存 重建路径。
- 「空库才播种」判断依赖 `corpus_chunk`，避免每次启动重复插入。

### 5.3 启动全景（`@ConditionalOnProperty` 门控）

```
SchemaInitializer.init()      建表（含 corpus_seen / corpus_chunk）
  ↓ @Order(1)
CorpusSeeder.seed()           空库→写种子进 SQLite
  ↓ @Order(2)
CorpusIndexRebuilder.run()    SQLite 全量 → 重建 InMemoryEmbeddingStore
  ↓
IngestionScheduler            @Scheduled 后续定时增量抓取，同样落 SQLite + 灌内存
```

用 `@Order` 固定 `SchemaInitializer` → `CorpusSeeder` → `CorpusIndexRebuilder` 顺序（`SchemaInitializer` 已由 `schemaBootstrap` 注册，需确保其 `@Order` 在最前）。

## 6. 错误处理 + 配置样例

### 6.1 错误处理矩阵（全线 best-effort，与 L4 `safeCheckpoint`、trace `safeAppend` 一致）

| 环节 | 失败场景 | 处理 |
|---|---|---|
| `FeedReader` | 来源 URL 超时/404/XML 畸形 | WARN 记来源 id，跳过整个来源，继续下一个 |
| `ArticleFetcher` | 单条正文抓取失败/空/过短 | WARN，`markSeen(fp)` 记为已见（避免死链每轮重试），跳过该条 |
| `RelevanceGate` | embedding 计算抛异常 | WARN，保守跳过该条（宁漏勿误），`markSeen` |
| `Chunker` | 正常不抛；空块 | 过滤掉空块 |
| `CorpusRepository` 写 | SQLite 写失败 | WARN，该条本轮失败、**不** `markSeen`，下轮重试（幂等 upsert 兜底） |
| `LongTermMemory.remember` | embed/store 失败 | WARN 跳过该块内存灌入，SQLite 已落 → 下次启动重建时补上 |
| `CorpusIndexRebuilder` | 单条重建失败 | WARN 跳过，不阻塞启动 |
| `IngestionScheduler` | 整轮抛异常 | 顶层 try/catch WARN，绝不让定时线程死掉（`@Scheduled` 单线程，异常会静默停掉后续调度） |

**核心原则**：采集是后台辅助，任何失败都不能影响 Agent 主流程和应用启动。SQLite 是真相源——内存库丢了能重建，单轮采集丢了下轮补。

### 6.2 配置样例（`application.yml`）

```yaml
agent:
  ingest:
    enabled: true                    # 总开关；测试 profile 置 false
    poll-cron: "0 0 */6 * * *"       # 每 6 小时轮询一次
    chunk-max-chars: 800
    sources:
      - id: harness-blog
        type: PAGE
        url: https://www.harness.io/blog
        tags: [harness, ci-cd]
      - id: some-rss
        type: RSS
        url: https://example.com/feed.xml
        tags: [harness, devops]
```

### 6.3 测试 profile

```yaml
agent:
  ingest:
    enabled: false                   # 关采集、关调度器、关重建/种子
```

- `IngestionScheduler` / `CorpusIndexRebuilder` / `CorpusSeeder` 均 `@ConditionalOnProperty(name="agent.ingest.enabled", havingValue="true", matchIfMissing=false)` 门控。
- 与现有 `agent.corpus.seed-on-startup` 开关合并：种子逻辑已并入 A，统一到 `agent.ingest.enabled` 之下，移除旧开关，避免两个开关打架。

## 7. 测试策略

沿用项目约定：内存 SQLite（`jdbc:sqlite::memory:` + `SingleConnectionDataSource(url, true)` 保活）+ 真实 bge ONNX，无需 API key，全绿。采集类不打真实网络——用本地 fixture / 桩。

### 7.1 单元测试

| 测试类 | 覆盖 |
|---|---|
| `ChunkerTest` | 段落贪心打包；单段超限硬切；空块过滤；`maxChars` 边界；空正文→空列表 |
| `FeedReaderTest` | 喂本地 RSS XML 字符串 → 解析出 `FeedItem`（guid/url/title/时间）；无 guid 用 url 兜底；畸形 XML → WARN 空列表不抛 |
| `CorpusRepositoryTest` | 内存 SQLite：`markSeen`/`hasSeen` 往返；`upsertChunk` 幂等（同 id 覆盖不叠加）；`allChunks`/`chunkCount` |
| `RelevanceGateTest` | 真实 bge：harness 正文判相关、无关正文（如菜谱）判离题 |

### 7.2 集成测试（管线串联，桩掉网络）

| 测试类 | 覆盖 |
|---|---|
| `IngestionServiceTest` | 桩 `FeedReader`（返 2 条）+ 桩 `ArticleFetcher`（1 条 harness 正文、1 条离题）→ 跑 `ingestAll` → 断言：相关的落 `corpus_chunk` 且已 `remember`，离题的被剔但 `markSeen`；第二次 `ingestAll` 两条都 `hasSeen` → 零新增 |
| `CorpusIndexRebuilderTest` | 预置 `corpus_chunk` 3 块 → 跑 rebuilder → `LongTermMemory.retrieve` 能召回；单条 embed 失败不阻塞 |
| `CorpusSeederTest`（改造现有） | 空库 → 播种进 SQLite（非直接内存）；非空库 → 跳过；缺失资源 WARN 返 0 |
| best-effort 测试 | `FeedReader`/`ArticleFetcher` 抛异常 → `ingestAll` 不抛、其它来源照常；`IngestionScheduler` 整轮异常不杀定时线程 |

### 7.3 回归与 live

- 回归：现有测试全绿（种子改造后 `CorpusSeeder` 断言相应更新）。权威计数用 `mvn clean test`。
- live（可选，`@Tag("live")` 默认不跑）：将来可加真打 `harness.io/blog` 的冒烟测试验证真实 HTML 解析。

## 8. 非目标

- 公众号/视频平台抓取（后续来源适配器）。
- 持久化向量库（sqlite-vec 等）——本期用「SQLite 真相源 + 启动重建内存索引」。
- 块间重叠、LLM 摘要——YAGNI，日后按需。
- 分布式/多进程采集——进程内单实例足够。
