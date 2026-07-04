# LongTermMemory 种子语料摄取实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 应用启动时读内置 `seed-corpus.json` 逐条 `remember()` 填充向量库，使生产 `local_retrieve` 返回真实 harness 资料而非恒空。

**架构：** 新建无 Spring 依赖的 `CorpusSeeder`（读 classpath JSON → 逐条 `MemoryItem` → `memory.remember()`，best-effort WARN，返回成功条数）；`AgentConfig` 加 `corpusSeeder` bean + `@ConditionalOnProperty` 门控的 `corpusBootstrap` ApplicationRunner；`agent.corpus.seed-on-startup`（默认 true）控制是否启动种子，`AgentApplicationTest` 关掉以保持上下文测试轻快。

**技术栈：** Java 21 + Spring Boot 3.3.4 + langchain4j 0.35.0（bge 本地嵌入 + InMemoryEmbeddingStore）+ Jackson + JUnit5/AssertJ。

**规格：** `docs/superpowers/specs/2026-07-04-corpus-seed-ingestion-design.md`

---

## 关键既有契约（勿改这些类）

- `record MemoryItem(String text, Map<String,String> meta)`
- `LongTermMemory`：`void remember(MemoryItem)`；`List<RetrievedChunk> retrieve(String query, int k)`（无 minScore，库非空即返回最多 k 条；`RetrievedChunk.sourceUri` 取自 meta "uri"）。
- `VectorLongTermMemory(EmbeddingModel embed, EmbeddingStore<TextSegment> store)`——唯一实现；`remember` 会 `embed.embed(text)` 后入 store。
- 非量化嵌入类：`dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel`（**勿用量化类**，pom 无此依赖）。
- `AgentConfig` 现有 `longTermMemory` bean（`LongTermMemory` = `VectorLongTermMemory`）、`schemaBootstrap`（`ApplicationRunner`）；已 import `ApplicationRunner`、`org.springframework.context.annotation.Bean/Configuration`、`org.springframework.beans.factory.annotation.Value`、各 l4memory 类。
- `AgentApplicationTest` 现为 `@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")`，仅一个空 `contextLoads()`——它是唯一加载完整 bean 图的测试。

---

## 任务 1：CorpusSeeder 摄取逻辑 + 单元测试

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/l4memory/CorpusSeeder.java`
- 创建：`src/test/resources/test-seed-corpus.json`
- 测试：`src/test/java/com/harnesslearn/agent/l4memory/CorpusSeederTest.java`

- [ ] **步骤 1：写 fixture `src/test/resources/test-seed-corpus.json`**

```json
[
  {"text": "上下文工程：在有限上下文窗口内裁剪无关信息、结构化组织任务状态与中间产物，避免噪声干扰模型判断", "uri": "https://example.com/ctx", "tags": ["L1", "上下文工程"]},
  {"text": "工具系统层控制工具选择与调用时机，并把工具返回结果提炼成结构化块反馈给模型", "uri": "https://example.com/tools", "tags": ["L2"]},
  {"text": "评估与观测层建立独立于生成过程的验证机制，对产出做有据可查、完整性、相关性、格式的审查", "uri": "https://example.com/eval", "tags": ["L5"]}
]
```

- [ ] **步骤 2：写失败测试 `CorpusSeederTest.java`**

```java
package com.harnesslearn.agent.l4memory;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSeederTest {

    private VectorLongTermMemory newMemory() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        return new VectorLongTermMemory(embed, store);
    }

    @Test
    void seedsFixtureAndRetrieveReturnsHits() {
        var memory = newMemory();
        int n = new CorpusSeeder(memory, "/test-seed-corpus.json").seed();
        assertThat(n).isEqualTo(3);                       // fixture 3 条全部摄取
        var hits = memory.retrieve("上下文工程", 3);
        assertThat(hits).isNotEmpty();                    // 库非空，检索有命中
        assertThat(hits).anyMatch(h -> h.text().contains("上下文"));
    }

    @Test
    void missingResourceIsBestEffortReturnsZero() {
        var memory = newMemory();
        int n = new CorpusSeeder(memory, "/no-such-seed.json").seed();
        assertThat(n).isZero();                           // 资源缺失不抛，返回 0
        assertThat(memory.retrieve("任意", 3)).isEmpty();  // 库仍空
    }
}
```

- [ ] **步骤 3：运行验证失败**

运行：`mvn -q -Dtest=CorpusSeederTest test`
预期：编译失败——`CorpusSeeder` 尚不存在。

- [ ] **步骤 4：实现 `CorpusSeeder.java`**

```java
package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 启动种子语料摄取：读内置 JSON 逐条 {@code remember} 填充向量库。
 *
 * <p>best-effort：种子资源缺失 / JSON 解析失败 → 记 WARN 返回 0（不抛、不中断启动）；
 * 单条 {@code remember} 失败 → 记 WARN 跳过该条、继续其余。无 Spring 依赖，可直接 {@code new} 单测。
 */
public class CorpusSeeder {
    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    /** 种子条目。{@code tags} 供未来过滤维度，本轮检索期不消费。 */
    public record SeedEntry(String text, String uri, List<String> tags) {}

    private final LongTermMemory memory;
    private final String resourcePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public CorpusSeeder(LongTermMemory memory, String resourcePath) {
        if (memory == null) throw new IllegalArgumentException("memory 不能为空");
        if (resourcePath == null || resourcePath.isBlank())
            throw new IllegalArgumentException("resourcePath 不能为空");
        this.memory = memory;
        this.resourcePath = resourcePath;
    }

    /**
     * 读种子资源逐条 remember。
     * @return 成功摄取的条数（资源缺失/解析失败返回 0）
     */
    public int seed() {
        SeedEntry[] entries;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("种子语料资源不存在，跳过摄取: {}", resourcePath);
                return 0;
            }
            entries = mapper.readValue(is, SeedEntry[].class);
        } catch (Exception e) {
            log.warn("种子语料解析失败，跳过摄取: {}", resourcePath, e);
            return 0;
        }
        int ok = 0;
        for (SeedEntry e : entries) {
            try {
                memory.remember(new MemoryItem(e.text(), Map.of(
                    "uri", e.uri() == null ? "" : e.uri(),
                    "tags", e.tags() == null ? "" : String.join(",", e.tags()))));
                ok++;
            } catch (RuntimeException ex) {
                log.warn("种子条目摄取失败，跳过: uri={}", e.uri(), ex);
            }
        }
        log.info("种子语料摄取完成: {}/{} 条", ok, entries.length);
        return ok;
    }
}
```

**注意：** `Map.of` 不接受 null 值，故对 `uri`/`tags` 为 null 做了兜底为 `""`；单条 `remember` 失败（如 text 为 null 导致 embed 抛异常）被 per-item catch 兜住，不影响其余条目。

- [ ] **步骤 5：运行验证通过**

运行：`mvn -q -Dtest=CorpusSeederTest test`
预期：PASS（2 个测试方法全绿；会加载本地 bge 模型，稍慢正常）。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/l4memory/CorpusSeeder.java \
        src/test/resources/test-seed-corpus.json \
        src/test/java/com/harnesslearn/agent/l4memory/CorpusSeederTest.java
git commit -m "feat(l4): CorpusSeeder 种子语料摄取（best-effort，无 Spring 依赖）"
```

---

## 任务 2：生产种子语料 seed-corpus.json + 可解析测试

**文件：**
- 创建：`src/main/resources/seed-corpus.json`
- 测试：`src/test/java/com/harnesslearn/agent/l4memory/SeedCorpusResourceTest.java`

- [ ] **步骤 1：写失败测试 `SeedCorpusResourceTest.java`**

```java
package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCorpusResourceTest {

    /** 防手写 JSON 格式错误 / 空字段进生产：断言可解析且条数 ≥10、每条 text/uri 非空。 */
    @Test
    void productionSeedCorpusIsParseableAndNonTrivial() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/seed-corpus.json")) {
            assertThat(is).as("seed-corpus.json 应在 classpath").isNotNull();
            CorpusSeeder.SeedEntry[] entries =
                new ObjectMapper().readValue(is, CorpusSeeder.SeedEntry[].class);
            assertThat(entries.length).isGreaterThanOrEqualTo(10);
            for (CorpusSeeder.SeedEntry e : entries) {
                assertThat(e.text()).isNotBlank();
                assertThat(e.uri()).isNotBlank();
            }
        }
    }
}
```

- [ ] **步骤 2：运行验证失败**

运行：`mvn -q -Dtest=SeedCorpusResourceTest test`
预期：FAIL——`getResourceAsStream("/seed-corpus.json")` 返回 null，`isNotNull()` 断言失败（资源尚未创建）。

- [ ] **步骤 3：写生产种子 `src/main/resources/seed-corpus.json`**（12 条真实 harness/上下文工程要点）

```json
[
  {"text": "自主 Agent 与固定 workflow 的区别：Agent 由模型自主决定下一步动作（工具调用或收尾），而非按预定义流程图执行；这要求显式的 think→act→observe 循环与步数上限兜底。", "uri": "https://example.com/agent-vs-workflow", "tags": ["L3", "自主决策"]},
  {"text": "上下文工程（context engineering）：在有限上下文窗口内裁剪无关信息、结构化组织任务状态与中间产物，避免噪声稀释模型注意力，是 Agent harness 的信息边界层核心。", "uri": "https://example.com/context-engineering", "tags": ["L1", "上下文工程"]},
  {"text": "信息边界层（L1）定义 Agent 该知道什么、不该知道什么：裁剪无关信息、注入角色与目标、把任务状态结构化为可读上下文，引导模型收敛。", "uri": "https://example.com/l1-info-boundary", "tags": ["L1"]},
  {"text": "工具系统层（L2）负责 Agent 与外部世界交互：选择工具、控制调用时机、把工具原始返回提炼成结构化块，并经相关性过滤剔除垃圾再反馈给模型。", "uri": "https://example.com/l2-tools", "tags": ["L2"]},
  {"text": "相关性过滤：用嵌入向量对领域质心的余弦相似度做闸门，低于阈值的内容判为垃圾剔除，避免无关资料污染大模型判断；并按向量近似去重。", "uri": "https://example.com/relevance-filter", "tags": ["L2", "过滤"]},
  {"text": "执行编排层（L3）把多步骤任务串起来：让模型按理解目标、判断信息、分析、生成、检查的轨道推进，用 maxSteps 步数上限与 finish 必过验证作硬约束兜底。", "uri": "https://example.com/l3-orchestrate", "tags": ["L3"]},
  {"text": "记忆与状态层（L4）独立管理当前任务状态、中间产物与长期记忆：工作状态检查点、证据 Artifact、向量化长期知识库分开存放，避免状态混淆。", "uri": "https://example.com/l4-memory", "tags": ["L4"]},
  {"text": "长期记忆用向量库实现：把资料嵌入为向量存储，检索时按语义相似度召回最相关的若干条，供本地检索工具 local_retrieve 使用。", "uri": "https://example.com/l4-vector-memory", "tags": ["L4", "向量检索"]},
  {"text": "评估与观测层（L5）建立独立于生成过程的验证机制：用一个不参与生成的 critic 人格，从有据可查、完整性、相关性、格式契约四个维度审查产出。", "uri": "https://example.com/l5-eval", "tags": ["L5"]},
  {"text": "约束、校验与恢复层（L6）应对出错：预设规则拦截非法动作与空产出，失败时按类型映射到重试、回滚或降级策略，避免 Agent 无限循环或输出垃圾。", "uri": "https://example.com/l6-guardrail", "tags": ["L6"]},
  {"text": "子 Agent（subagent）用于上下文隔离：把有界子任务（如逐来源摘要）交给独立上下文的子 Agent 处理，主 Agent 只看压缩后的要点，降低主循环上下文负担。", "uri": "https://example.com/subagent", "tags": ["subagent", "上下文隔离"]},
  {"text": "可观测性：把 LLM 调用、工具调用、验证结论、恢复决策逐步埋点为 trace 并落盘，配合结构化 JSON 日志，使 Agent 每一步决策可回溯、可诊断。", "uri": "https://example.com/observability", "tags": ["L5", "可观测性"]}
]
```

- [ ] **步骤 4：运行验证通过**

运行：`mvn -q -Dtest=SeedCorpusResourceTest test`
预期：PASS（12 条 ≥10，text/uri 均非空）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/resources/seed-corpus.json \
        src/test/java/com/harnesslearn/agent/l4memory/SeedCorpusResourceTest.java
git commit -m "feat(l4): 内置 12 条 harness 种子语料 + 可解析护栏测试"
```

---

## 任务 3：AgentConfig 装配门控 seeder + 开关 + 全量回归

**文件：**
- 修改：`src/main/java/com/harnesslearn/agent/AgentConfig.java`
- 修改：`src/main/resources/application.yml`
- 修改：`src/test/java/com/harnesslearn/agent/AgentApplicationTest.java`

- [ ] **步骤 1：`AgentConfig` 加两个 import**

在现有 import 区加：
```java
import com.harnesslearn.agent.l4memory.CorpusSeeder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```
（`org.springframework.boot.ApplicationRunner` 已 import，勿重复。）

- [ ] **步骤 2：`AgentConfig` 追加 corpusSeeder bean + 门控 corpusBootstrap runner**

在 `schemaBootstrap` bean 方法之后追加：
```java
    @Bean
    public CorpusSeeder corpusSeeder(LongTermMemory memory) {
        return new CorpusSeeder(memory, "/seed-corpus.json");
    }

    /**
     * 启动时种子语料摄取。用 {@code agent.corpus.seed-on-startup}（默认 true）门控：
     * 关闭时此 runner 不创建，seed() 不执行、不加载 bge 嵌入模型——上下文测试据此保持轻快。
     */
    @Bean
    @ConditionalOnProperty(name = "agent.corpus.seed-on-startup", havingValue = "true", matchIfMissing = true)
    public org.springframework.boot.ApplicationRunner corpusBootstrap(CorpusSeeder seeder) {
        return args -> seeder.seed();
    }
```
**注意：** `corpusSeeder` bean 恒创建但构造廉价——只存 `LongTermMemory` 引用；`LongTermMemory`=`VectorLongTermMemory` 持有 `@Lazy` 的 `embeddingModel` 代理，不实体化，故构造不触发 bge 加载。只有 `corpusBootstrap` 调 `seed()`→`remember`→`embed` 才加载 bge。

- [ ] **步骤 3：`application.yml` 加开关**

在 `agent:` 下（与 `orchestrate`/`filter`/`tools`/`recovery`/`l1` 同级）追加：
```yaml
  corpus:
    seed-on-startup: true
```

- [ ] **步骤 4：`AgentApplicationTest` 关掉种子（保持上下文测试轻快）**

把现有：
```java
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
```
改为：
```java
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.corpus.seed-on-startup=false"
})
```

- [ ] **步骤 5：全量回归**

运行：`mvn test`
预期：全绿。总数 = 原 47 + 任务 1 的 2 + 任务 2 的 1 = **50**，`Failures: 0, Errors: 0`。特别确认：
- `AgentApplicationTest#contextLoads` 绿且未因种子变慢（`corpusBootstrap` 未创建、无 bge 加载）——DI 图完整、无循环。
- `CorpusSeederTest`（2）、`SeedCorpusResourceTest`（1）全绿。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/AgentConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/harnesslearn/agent/AgentApplicationTest.java
git commit -m "feat(config): 启动时种子语料摄取（seed-on-startup 开关门控，测试关）"
```

---

## 自检结果（对照规格）

**1. 规格覆盖度：**
- §1/§4 `CorpusSeeder` + `SeedEntry` → 任务 1 步骤 4 ✅
- §1 `seed-corpus.json`（~12 条）→ 任务 2 步骤 3（12 条）✅
- §1/§4 `AgentConfig` corpusSeeder bean + 门控 corpusBootstrap → 任务 3 步骤 2 ✅
- §3/§4 `application.yml` 开关 + `AgentApplicationTest` 关掉 → 任务 3 步骤 3–4 ✅
- §3 best-effort（资源缺失/解析失败/单条失败 WARN）→ 任务 1 步骤 4 seed() 三处 catch ✅
- §5 测试：CorpusSeederTest（fixture 命中 + 缺失 best-effort）、SeedCorpusResourceTest（生产可解析 ≥10）、回归 → 任务 1/2/3 ✅
- §6 不做 URL/SourceSummarizer/ingest/持久库 → 计划未含，YAGNI ✅

**2. 占位符扫描：** 无 TODO/待定；所有代码/JSON/命令均完整给出。种子 URI 用 `example.com` 占位是**内容占位符而非计划缺陷**（真实公开链接需运营补录，不影响摄取/检索逻辑正确性；护栏测试只校验 uri 非空）。✅

**3. 类型一致性：** `CorpusSeeder(LongTermMemory, String)` 构造器、`seed()` 返回 `int`、`SeedEntry(String text, String uri, List<String> tags)` 在任务 1 定义，任务 2（`CorpusSeeder.SeedEntry[]` 解析）、任务 3（`new CorpusSeeder(memory, "/seed-corpus.json")`）引用一致；`MemoryItem(text, Map<String,String>)`、`memory.retrieve(query,k)`、`BgeSmallZhEmbeddingModel`（非量化）均与既有契约一致。✅
