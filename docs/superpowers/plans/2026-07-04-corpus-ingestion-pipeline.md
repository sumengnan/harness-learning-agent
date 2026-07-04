# 数据采集与清洗管道（子项目 A）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有 Spring Boot 应用内新增一个进程内后台模块，从官网/博客/RSS 定时采集 harness 相关材料，经清洗/去重/相关性过滤/切块后落 SQLite 并灌入向量记忆库供 Agent 检索。

**架构：** 新包 `com.harnesslearn.agent.ingest`，一组单一职责小组件由 `IngestionService` 串联、`@Scheduled` 轮询触发。SQLite `corpus_chunk` 表为唯一真相源，启动时 `CorpusIndexRebuilder` 从中重建 `InMemoryEmbeddingStore`。全线 best-effort，任何失败不影响 Agent 主流程与应用启动。

**技术栈：** Java 21、Spring Boot 3.3.4、Spring JDBC（`JdbcTemplate` + SQLite）、Jsoup 1.18.1（HTML/XML 解析）、langchain4j bge-small-zh 本地嵌入、JUnit 5 + AssertJ。

**规格来源：** `docs/superpowers/specs/2026-07-04-corpus-ingestion-pipeline-design.md`

---

## 文件结构

**新建（主代码，均在 `src/main/java/com/harnesslearn/agent/ingest/`）：**

| 文件 | 职责 |
|---|---|
| `SourceType.java` | 枚举 `RSS` / `PAGE` |
| `Source.java` | record：一个配置来源（id/type/url/tags） |
| `FeedItem.java` | record：来源解析出的一个条目（guid/url/title/发布时间） |
| `CorpusChunk.java` | record：落库/重建用的一个语料块 |
| `IngestProperties.java` | `@ConfigurationProperties(prefix="agent.ingest")` |
| `SourceRegistry.java` | 从 `IngestProperties` 提供 `List<Source>` |
| `FeedReader.java` | 拉一个来源产出 `List<FeedItem>`（RSS XML 解析 / PAGE 单页） |
| `ArticleFetcher.java` | 抓单条目正文并去样板，产出纯文本 |
| `RelevanceGate.java` | 包 `RelevanceFilter` bean，判正文相关性 |
| `Chunker.java` | 正文按段落+长度贪心切块 |
| `CorpusRepository.java` | SQLite 读写 `corpus_seen` + `corpus_chunk` |
| `IngestionService.java` | 编排整条流水线，best-effort |
| `IngestionScheduler.java` | `@Scheduled` 定时调 `ingestAll()` |
| `CorpusIndexRebuilder.java` | 启动从 `corpus_chunk` 重建内存索引 |
| `IngestConfig.java` | `@Configuration` + `@EnableScheduling` + bean 装配 + 门控 runner |

**修改（主代码）：**

| 文件 | 变更 |
|---|---|
| `l4memory/SchemaInitializer.java` | 追加 `corpus_seen` / `corpus_chunk` 两表 DDL |
| `l4memory/CorpusSeeder.java` | 从「灌 `LongTermMemory`」改为「灌 `CorpusRepository`」，空库才播种 |
| `AgentConfig.java` | 移除 `corpusSeeder`/`corpusBootstrap` bean（迁入 `IngestConfig`）；`schemaBootstrap` 加 `@Order(1)` |
| `resources/application.yml` | 移除 `agent.corpus.seed-on-startup`，加 `agent.ingest.*` 块 |

**新建（测试，`src/test/java/com/harnesslearn/agent/ingest/`）：** `CorpusRepositoryTest`、`ChunkerTest`、`FeedReaderTest`、`ArticleFetcherTest`、`RelevanceGateTest`、`SourceRegistryTest`、`IngestionServiceTest`、`CorpusIndexRebuilderTest`。

**修改（测试）：** `l4memory/CorpusSeederTest`（改为断言 SQLite）、`AgentApplicationTest`、`l4memory/CorpusSeedIntegrationTest`（开关改名 `agent.ingest.enabled`）。

**关键既有 API（实现时直接依赖，勿臆造签名）：**
- `RelevanceFilter.filter(List<RetrievedChunk>) -> RelevanceFilter.Result(List<RetrievedChunk> kept, int droppedCount)`；构造 `RelevanceFilter(EmbeddingModel, List<String> anchors, double threshold, double borderlineDelta)`。
- `RetrievedChunk(String id, String sourceUri, String text, double relevanceScore)`。
- `LongTermMemory.remember(MemoryItem)` / `retrieve(String, int)`；`MemoryItem(String text, Map<String,String> meta)`；`retrieve` 读 meta 的 `"uri"`。
- `SchemaInitializer(JdbcTemplate)`，方法 `init()`；DDL 用 `jdbc.execute("CREATE TABLE IF NOT EXISTS ...")`。
- SQLite 写用 `jdbc.update("INSERT OR REPLACE INTO ...", args...)`，读用 `jdbc.query(sql, rowMapper, args...)`（见 `SqliteArtifactStore`）。

---

## 任务 1：建表 DDL + CorpusChunk + CorpusRepository

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/CorpusChunk.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/CorpusRepository.java`
- 修改：`src/main/java/com/harnesslearn/agent/l4memory/SchemaInitializer.java:6-19`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/CorpusRepositoryTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/CorpusRepositoryTest.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l4memory.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusRepositoryTest {

    private JdbcTemplate jt(String memName) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + memName + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void seenRoundTrip() {
        var repo = new CorpusRepository(jt("memSeen"));
        assertThat(repo.hasSeen("fp-1")).isFalse();
        repo.markSeen("fp-1", "src-a", "http://x/1", "标题");
        assertThat(repo.hasSeen("fp-1")).isTrue();
    }

    @Test
    void upsertChunkIsIdempotent() {
        var repo = new CorpusRepository(jt("memChunk"));
        repo.upsertChunk(new CorpusChunk("id-1", "src-a", "http://x/1", "T", 0, "正文旧", 111L));
        repo.upsertChunk(new CorpusChunk("id-1", "src-a", "http://x/1", "T", 0, "正文新", 111L));
        assertThat(repo.chunkCount()).isEqualTo(1);
        assertThat(repo.allChunks()).singleElement()
            .satisfies(c -> assertThat(c.text()).isEqualTo("正文新"));
    }

    @Test
    void allChunksHandlesNullPublishedTs() {
        var repo = new CorpusRepository(jt("memNull"));
        repo.upsertChunk(new CorpusChunk("id-2", "src-a", "http://x/2", null, 0, "正文", null));
        assertThat(repo.allChunks()).singleElement()
            .satisfies(c -> {
                assertThat(c.publishedTs()).isNull();
                assertThat(c.title()).isNull();
            });
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=CorpusRepositoryTest test`
预期：编译失败（`CorpusChunk`、`CorpusRepository` 尚不存在）。

- [ ] **步骤 3：追加建表 DDL**

在 `SchemaInitializer.init()` 末尾（`:18` 的 trace_step 之后）追加两条：

```java
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS corpus_seen(
              fingerprint TEXT PRIMARY KEY, source_id TEXT, url TEXT,
              title TEXT, first_seen_ts INTEGER)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS corpus_chunk(
              id TEXT PRIMARY KEY, source_id TEXT, url TEXT, title TEXT,
              seq INTEGER, text TEXT, published_ts INTEGER, ingested_ts INTEGER)""");
```

- [ ] **步骤 4：创建 CorpusChunk record**

`src/main/java/com/harnesslearn/agent/ingest/CorpusChunk.java`：

```java
package com.harnesslearn.agent.ingest;

/** 落库/重建用的一个语料块。{@code id} 为确定性主键 = 指纹 + ":" + seq。 */
public record CorpusChunk(String id, String sourceId, String url,
                          String title, int seq, String text, Long publishedTs) {}
```

- [ ] **步骤 5：创建 CorpusRepository**

`src/main/java/com/harnesslearn/agent/ingest/CorpusRepository.java`：

```java
package com.harnesslearn.agent.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** SQLite 读写：corpus_seen（增量去重指纹）+ corpus_chunk（真相源）。 */
public class CorpusRepository {
    private final JdbcTemplate jdbc;
    public CorpusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean hasSeen(String fingerprint) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM corpus_seen WHERE fingerprint=?", Integer.class, fingerprint);
        return n != null && n > 0;
    }

    public void markSeen(String fingerprint, String sourceId, String url, String title) {
        jdbc.update("INSERT OR REPLACE INTO corpus_seen"
            + "(fingerprint,source_id,url,title,first_seen_ts) VALUES(?,?,?,?,?)",
            fingerprint, sourceId, url, title, System.currentTimeMillis());
    }

    public void upsertChunk(CorpusChunk c) {
        jdbc.update("INSERT OR REPLACE INTO corpus_chunk"
            + "(id,source_id,url,title,seq,text,published_ts,ingested_ts) VALUES(?,?,?,?,?,?,?,?)",
            c.id(), c.sourceId(), c.url(), c.title(), c.seq(), c.text(),
            c.publishedTs(), System.currentTimeMillis());
    }

    public int chunkCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM corpus_chunk", Integer.class);
        return n == null ? 0 : n;
    }

    public List<CorpusChunk> allChunks() {
        return jdbc.query(
            "SELECT id,source_id,url,title,seq,text,published_ts FROM corpus_chunk",
            (rs, n) -> new CorpusChunk(
                rs.getString("id"), rs.getString("source_id"), rs.getString("url"),
                rs.getString("title"), rs.getInt("seq"), rs.getString("text"),
                rs.getObject("published_ts") == null ? null : rs.getLong("published_ts")));
    }
}
```

- [ ] **步骤 6：运行测试验证通过**

运行：`mvn -q -Dtest=CorpusRepositoryTest test`
预期：PASS（3 个测试）。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/CorpusChunk.java \
        src/main/java/com/harnesslearn/agent/ingest/CorpusRepository.java \
        src/main/java/com/harnesslearn/agent/l4memory/SchemaInitializer.java \
        src/test/java/com/harnesslearn/agent/ingest/CorpusRepositoryTest.java
git commit -m "feat(ingest): corpus_seen/corpus_chunk 两表 + CorpusRepository

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 2：Chunker

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/Chunker.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/ChunkerTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/ChunkerTest.java`：

```java
package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void packsSmallParagraphsIntoOneChunk() {
        var chunks = new Chunker(100).split("段落一。\n\n段落二。");
        assertThat(chunks).singleElement().satisfies(c -> {
            assertThat(c).contains("段落一");
            assertThat(c).contains("段落二");
        });
    }

    @Test
    void startsNewChunkWhenExceedingLimit() {
        String p1 = "a".repeat(60);
        String p2 = "b".repeat(60);
        var chunks = new Chunker(100).split(p1 + "\n\n" + p2);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(p1);
        assertThat(chunks.get(1)).isEqualTo(p2);
    }

    @Test
    void hardSplitsSingleOversizedParagraph() {
        String big = "x".repeat(250);
        var chunks = new Chunker(100).split(big);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(100);
        assertThat(chunks.get(2)).hasSize(50);
        assertThat(String.join("", chunks)).isEqualTo(big);
    }

    @Test
    void blankOrNullYieldsEmptyList() {
        var c = new Chunker(100);
        assertThat(c.split("")).isEmpty();
        assertThat(c.split("   \n\n  ")).isEmpty();
        assertThat(c.split(null)).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=ChunkerTest test`
预期：编译失败（`Chunker` 不存在）。

- [ ] **步骤 3：创建 Chunker**

`src/main/java/com/harnesslearn/agent/ingest/Chunker.java`：

```java
package com.harnesslearn.agent.ingest;

import java.util.ArrayList;
import java.util.List;

/** 清洗后正文按段落+长度贪心切块（≤maxChars/块），单段超限则硬切。v1 无块间重叠。 */
public class Chunker {
    private final int maxChars;

    public Chunker(int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars 必须为正");
        this.maxChars = maxChars;
    }

    public List<String> split(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.length() > maxChars) {                 // 单段超限，硬切
                flush(cur, out);
                for (int i = 0; i < p.length(); i += maxChars)
                    out.add(p.substring(i, Math.min(i + maxChars, p.length())));
                continue;
            }
            if (cur.length() + p.length() + 1 > maxChars) flush(cur, out);
            if (cur.length() > 0) cur.append('\n');
            cur.append(p);
        }
        flush(cur, out);
        return out;
    }

    private void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=ChunkerTest test`
预期：PASS（4 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/Chunker.java \
        src/test/java/com/harnesslearn/agent/ingest/ChunkerTest.java
git commit -m "feat(ingest): Chunker 段落贪心切块

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 3：Source/SourceType/FeedItem + FeedReader

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/SourceType.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/Source.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/FeedItem.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/FeedReader.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/FeedReaderTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/FeedReaderTest.java`：

```java
package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedReaderTest {

    private static final String RSS = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
          <item>
            <title>Harness CI 新特性</title>
            <link>https://harness.io/blog/ci-feature</link>
            <guid>guid-001</guid>
          </item>
          <item>
            <title>无 guid 的文章</title>
            <link>https://harness.io/blog/no-guid</link>
          </item>
        </channel></rss>""";

    @Test
    void parsesRssItems() {
        List<FeedItem> items = FeedReader.parseRss(RSS);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).guid()).isEqualTo("guid-001");
        assertThat(items.get(0).url()).isEqualTo("https://harness.io/blog/ci-feature");
        assertThat(items.get(0).title()).isEqualTo("Harness CI 新特性");
    }

    @Test
    void guidFallsBackToLinkWhenMissing() {
        List<FeedItem> items = FeedReader.parseRss(RSS);
        assertThat(items.get(1).guid()).isEqualTo("https://harness.io/blog/no-guid");
    }

    @Test
    void malformedXmlYieldsEmptyNoThrow() {
        assertThat(FeedReader.parseRss("这不是 XML <<<")).isEmpty();
    }

    @Test
    void pageSourceYieldsSingleItem() {
        var src = new Source("blog", SourceType.PAGE, "https://harness.io/blog", List.of());
        // PAGE 类型不打网络：read 内部对 PAGE 直接构造单条目，url 即配置 url
        List<FeedItem> items = new FeedReader().read(src);
        assertThat(items).singleElement()
            .satisfies(i -> assertThat(i.url()).isEqualTo("https://harness.io/blog"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=FeedReaderTest test`
预期：编译失败（类型不存在）。

- [ ] **步骤 3：创建三个 record**

`src/main/java/com/harnesslearn/agent/ingest/SourceType.java`：

```java
package com.harnesslearn.agent.ingest;

/** 来源类型：RSS 订阅源 / PAGE 单页文章。 */
public enum SourceType { RSS, PAGE }
```

`src/main/java/com/harnesslearn/agent/ingest/Source.java`：

```java
package com.harnesslearn.agent.ingest;

import java.util.List;

/** 一个配置来源。 */
public record Source(String id, SourceType type, String url, List<String> tags) {}
```

`src/main/java/com/harnesslearn/agent/ingest/FeedItem.java`：

```java
package com.harnesslearn.agent.ingest;

/** FeedReader 从来源解析出的一个条目（尚未抓正文）。 */
public record FeedItem(String guid, String url, String title, Long publishedEpochMs) {}
```

- [ ] **步骤 4：创建 FeedReader**

`src/main/java/com/harnesslearn/agent/ingest/FeedReader.java`：

```java
package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 拉一个来源产出条目清单。RSS 走 XML 解析（支持 RSS &lt;item&gt; 与 Atom &lt;entry&gt;）；
 * PAGE 把配置 URL 本身当作一篇文章。best-effort：抓取/解析失败 → WARN 返回空列表，不抛。
 */
public class FeedReader {
    private static final Logger log = LoggerFactory.getLogger(FeedReader.class);

    public List<FeedItem> read(Source source) {
        try {
            if (source.type() == SourceType.PAGE) {
                return List.of(new FeedItem(source.url(), source.url(), null, null));
            }
            Document doc = Jsoup.connect(source.url())
                .userAgent("Mozilla/5.0").timeout(15000)
                .ignoreContentType(true).parser(Parser.xmlParser()).get();
            return extract(doc);
        } catch (Exception e) {
            log.warn("来源抓取失败，跳过: id={} url={}", source.id(), source.url(), e);
            return List.of();
        }
    }

    /** 供单测直接喂 RSS/Atom XML 字符串。 */
    static List<FeedItem> parseRss(String xml) {
        return extract(Jsoup.parse(xml, "", Parser.xmlParser()));
    }

    private static List<FeedItem> extract(Document doc) {
        List<FeedItem> items = new ArrayList<>();
        for (Element it : doc.select("item, entry")) {
            String link = linkOf(it);
            if (link == null || link.isBlank()) continue;
            String guid = firstNonBlank(text(it, "guid"), text(it, "id"), link);
            items.add(new FeedItem(guid, link, text(it, "title"), null));
        }
        return items;
    }

    private static String linkOf(Element item) {
        Element l = item.selectFirst("link");
        if (l == null) return null;
        String href = l.attr("href");                 // Atom: <link href="..."/>
        return (href != null && !href.isBlank()) ? href : l.text();  // RSS: <link>...</link>
    }

    private static String text(Element parent, String tag) {
        Element e = parent.selectFirst(tag);
        return e == null ? null : e.text();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -Dtest=FeedReaderTest test`
预期：PASS（4 个测试）。

> 注意：`malformedXmlYieldsEmptyNoThrow` 依赖 Jsoup XML 解析器对非法输入的宽容——它不抛异常、只是选不到 `item/entry` 从而返回空列表。若实现后该测试因 Jsoup 行为差异失败，改为断言「不抛异常」即可（`assertThatCode(...).doesNotThrowAnyException()`），语义等价。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/SourceType.java \
        src/main/java/com/harnesslearn/agent/ingest/Source.java \
        src/main/java/com/harnesslearn/agent/ingest/FeedItem.java \
        src/main/java/com/harnesslearn/agent/ingest/FeedReader.java \
        src/test/java/com/harnesslearn/agent/ingest/FeedReaderTest.java
git commit -m "feat(ingest): Source/FeedItem 模型 + FeedReader（RSS/Atom 解析）

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 4：ArticleFetcher

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/ArticleFetcher.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/ArticleFetcherTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/ArticleFetcherTest.java`：

```java
package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleFetcherTest {

    @Test
    void extractsBodyTextStrippingBoilerplate() {
        var doc = Jsoup.parse("""
            <html><body>
              <nav>菜单导航</nav>
              <header>页眉</header>
              <p>这是正文的核心内容。</p>
              <footer>版权所有</footer>
              <script>var x=1;</script>
            </body></html>""");
        String text = ArticleFetcher.extractText(doc);
        assertThat(text).contains("正文的核心内容");
        assertThat(text).doesNotContain("菜单导航");
        assertThat(text).doesNotContain("版权所有");
        assertThat(text).doesNotContain("var x");
    }

    @Test
    void fetchReturnsNullOnBadUrl() {
        assertThat(new ArticleFetcher().fetch("http://invalid.invalid")).isNull();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=ArticleFetcherTest test`
预期：编译失败（`ArticleFetcher` 不存在）。

- [ ] **步骤 3：创建 ArticleFetcher**

`src/main/java/com/harnesslearn/agent/ingest/ArticleFetcher.java`：

```java
package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抓单条目正文并去样板（与 FetchPageTool 相同的 Jsoup 清洗选择器）。
 * best-effort：抓取失败返回 null。整篇上限 MAX，切块前的粗上限。
 */
public class ArticleFetcher {
    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final int MAX = 20000;

    /** 抓取并清洗正文；失败返回 null。 */
    public String fetch(String url) {
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get();
            return extractText(doc);
        } catch (Exception e) {
            log.warn("正文抓取失败，跳过: url={}", url, e);
            return null;
        }
    }

    /** 去样板取正文，供单测直接喂 Document。 */
    static String extractText(Document doc) {
        doc.select("script,style,nav,footer,header,aside").remove();
        String text = doc.body() == null ? "" : doc.body().text();
        return text.length() > MAX ? text.substring(0, MAX) : text;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=ArticleFetcherTest test`
预期：PASS（2 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/ArticleFetcher.java \
        src/test/java/com/harnesslearn/agent/ingest/ArticleFetcherTest.java
git commit -m "feat(ingest): ArticleFetcher 抓正文去样板

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 5：RelevanceGate

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/RelevanceGate.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/RelevanceGateTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/RelevanceGateTest.java`（用真实 bge，首次加载模型较慢属正常）：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l2tools.RelevanceFilter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelevanceGateTest {

    private RelevanceGate gate() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        var filter = new RelevanceFilter(embed, List.of(
            "AI agent 上下文工程与 harness 架构设计",
            "大模型工具调用、执行编排与多步推理"), 0.55, 0.05);
        return new RelevanceGate(filter);
    }

    @Test
    void relevantHarnessTextPasses() {
        assertThat(gate().isRelevant(
            "自主 Agent 的执行编排层负责把多步骤任务串起来，用上下文工程裁剪信息并做工具调用。"))
            .isTrue();
    }

    @Test
    void offTopicTextRejected() {
        assertThat(gate().isRelevant(
            "红烧肉的做法：五花肉切块，冷水下锅焯水，加冰糖炒糖色，小火慢炖四十分钟。"))
            .isFalse();
    }

    @Test
    void blankRejected() {
        assertThat(gate().isRelevant("  ")).isFalse();
    }
}
```

> 阈值 0.55 用于测试判别，与生产 `agent.filter.relevance-threshold`（默认 0.80）无关——此处直接 `new RelevanceFilter` 传测试阈值，不走 Spring bean。生产路径 `RelevanceGate` 注入的是 0.80 阈值的 bean（任务 10 装配）。若 0.55 下菜谱样本意外过关，调低至 0.5 或更换更中性的离题样本；目标是验证「相关判真、离题判假」的二分行为。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=RelevanceGateTest test`
预期：编译失败（`RelevanceGate` 不存在）。

- [ ] **步骤 3：创建 RelevanceGate**

`src/main/java/com/harnesslearn/agent/ingest/RelevanceGate.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.RetrievedChunk;
import com.harnesslearn.agent.l2tools.RelevanceFilter;
import java.util.List;

/**
 * 相关性闸门：用与 Agent 运行时相同的 {@link RelevanceFilter}（同一把余弦-质心尺子）
 * 判正文是否属 harness 主题。把正文包成单元素 RetrievedChunk 交给 filter，kept 非空即相关。
 */
public class RelevanceGate {
    private final RelevanceFilter filter;
    public RelevanceGate(RelevanceFilter filter) { this.filter = filter; }

    public boolean isRelevant(String body) {
        if (body == null || body.isBlank()) return false;
        var probe = new RetrievedChunk("gate", "", body, 0.0);
        return !filter.filter(List.of(probe)).kept().isEmpty();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=RelevanceGateTest test`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/RelevanceGate.java \
        src/test/java/com/harnesslearn/agent/ingest/RelevanceGateTest.java
git commit -m "feat(ingest): RelevanceGate 复用 RelevanceFilter 判相关性

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 6：IngestProperties + SourceRegistry

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/IngestProperties.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/SourceRegistry.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/SourceRegistryTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/SourceRegistryTest.java`：

```java
package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRegistryTest {

    @Test
    void exposesConfiguredSources() {
        var src = new Source("blog", SourceType.PAGE, "https://x/blog", List.of("harness"));
        var props = new IngestProperties(true, "0 0 * * * *", 800, List.of(src));
        assertThat(new SourceRegistry(props).sources()).containsExactly(src);
    }

    @Test
    void nullSourcesTreatedAsEmpty() {
        var props = new IngestProperties(false, null, 800, null);
        assertThat(new SourceRegistry(props).sources()).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=SourceRegistryTest test`
预期：编译失败（类型不存在）。

- [ ] **步骤 3：创建 IngestProperties**

`src/main/java/com/harnesslearn/agent/ingest/IngestProperties.java`：

```java
package com.harnesslearn.agent.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * 采集管道配置。绑定 application.yml 的 agent.ingest.*：
 * enabled 总开关、pollCron 轮询 cron、chunkMaxChars 切块上限、sources 来源清单。
 */
@ConfigurationProperties(prefix = "agent.ingest")
public record IngestProperties(
    boolean enabled, String pollCron, int chunkMaxChars, List<Source> sources) {}
```

- [ ] **步骤 4：创建 SourceRegistry**

`src/main/java/com/harnesslearn/agent/ingest/SourceRegistry.java`：

```java
package com.harnesslearn.agent.ingest;

import java.util.List;

/** 从 IngestProperties 提供来源清单，null 安全。 */
public class SourceRegistry {
    private final IngestProperties props;
    public SourceRegistry(IngestProperties props) { this.props = props; }

    public List<Source> sources() {
        return props.sources() == null ? List.of() : props.sources();
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -Dtest=SourceRegistryTest test`
预期：PASS（2 个测试）。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/IngestProperties.java \
        src/main/java/com/harnesslearn/agent/ingest/SourceRegistry.java \
        src/test/java/com/harnesslearn/agent/ingest/SourceRegistryTest.java
git commit -m "feat(ingest): IngestProperties + SourceRegistry

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 7：IngestionService（编排）

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/IngestionService.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/IngestionServiceTest.java`

**上下文：** 这是整条流水线的编排器。测试用真实 `CorpusRepository`（内存 SQLite）+ 手写桩替身（子类覆盖）替 `FeedReader`/`ArticleFetcher`/`RelevanceGate`，`LongTermMemory` 用记录式假实现（避免加载 bge，保持测试快而确定）。验证：增量去重、相关性剔除、块落库 + remember、best-effort 不崩。

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/IngestionServiceTest.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class IngestionServiceTest {

    // 记录式 LongTermMemory，避免加载 bge
    static class RecordingMemory implements LongTermMemory {
        final List<MemoryItem> items = new ArrayList<>();
        public void remember(MemoryItem item) { items.add(item); }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    private CorpusRepository repo(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new CorpusRepository(jt);
    }

    private SourceRegistry registryWithOneSource() {
        var src = new Source("src-a", SourceType.RSS, "http://feed", List.of());
        return new SourceRegistry(new IngestProperties(true, null, 800, List.of(src)));
    }

    // 桩：固定返回给定条目
    private FeedReader feedWith(FeedItem... items) {
        return new FeedReader() {
            @Override public List<FeedItem> read(Source s) { return List.of(items); }
        };
    }

    @Test
    void ingestsRelevantSkipsOffTopicAndDedups() {
        var repo = repo("memIngest1");
        var memory = new RecordingMemory();
        var itemA = new FeedItem("gA", "http://a", "文章A", null);
        var itemB = new FeedItem("gB", "http://b", "文章B", null);
        var svc = new IngestionService(
            registryWithOneSource(),
            feedWith(itemA, itemB),
            new ArticleFetcher() {
                @Override public String fetch(String url) {
                    return url.equals("http://a")
                        ? "harness 相关正文".repeat(20) : "离题正文".repeat(20);
                }
            },
            new RelevanceGate(null) {
                @Override public boolean isRelevant(String body) { return body.contains("harness"); }
            },
            new Chunker(800), repo, memory);

        svc.ingestAll();

        assertThat(repo.chunkCount()).isGreaterThan(0);
        assertThat(repo.allChunks()).allSatisfy(c -> assertThat(c.url()).isEqualTo("http://a"));
        assertThat(repo.hasSeen("gA")).isTrue();
        assertThat(repo.hasSeen("gB")).isTrue();              // 离题也标记已见
        assertThat(memory.items).isNotEmpty();

        int before = repo.chunkCount();
        svc.ingestAll();                                       // 第二轮：全部已见
        assertThat(repo.chunkCount()).isEqualTo(before);
    }

    @Test
    void perItemFailureIsBestEffort() {
        var repo = repo("memIngest2");
        var memory = new RecordingMemory();
        var itemA = new FeedItem("gA", "http://a", "A", null);
        var itemB = new FeedItem("gB", "http://b", "B", null);
        var svc = new IngestionService(
            registryWithOneSource(),
            feedWith(itemA, itemB),
            new ArticleFetcher() {
                @Override public String fetch(String url) {
                    if (url.equals("http://a")) throw new RuntimeException("boom");
                    return "harness 正文".repeat(20);
                }
            },
            new RelevanceGate(null) {
                @Override public boolean isRelevant(String body) { return true; }
            },
            new Chunker(800), repo, memory);

        assertThatCode(svc::ingestAll).doesNotThrowAnyException();
        assertThat(repo.hasSeen("gB")).isTrue();               // B 正常入库
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=IngestionServiceTest test`
预期：编译失败（`IngestionService` 不存在）。

- [ ] **步骤 3：创建 IngestionService**

`src/main/java/com/harnesslearn/agent/ingest/IngestionService.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * 采集流水线编排：遍历来源 → 解析条目 → 增量去重 → 抓正文 → 相关性过滤 → 切块
 * → 落 SQLite + 灌内存向量库。全线 best-effort：单来源/单条目异常 WARN 跳过，主流程不崩。
 * markSeen 放在所有块落库成功之后：中途失败下轮重抓（幂等 upsert 兜底），宁可重抓不丢内容。
 */
public class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MIN_BODY = 100;   // 短于此判为无效正文

    private final SourceRegistry registry;
    private final FeedReader feedReader;
    private final ArticleFetcher fetcher;
    private final RelevanceGate gate;
    private final Chunker chunker;
    private final CorpusRepository repo;
    private final LongTermMemory memory;

    public IngestionService(SourceRegistry registry, FeedReader feedReader, ArticleFetcher fetcher,
            RelevanceGate gate, Chunker chunker, CorpusRepository repo, LongTermMemory memory) {
        this.registry = registry; this.feedReader = feedReader; this.fetcher = fetcher;
        this.gate = gate; this.chunker = chunker; this.repo = repo; this.memory = memory;
    }

    public void ingestAll() {
        for (Source source : registry.sources()) {
            try { ingestSource(source); }
            catch (RuntimeException e) { log.warn("来源处理异常，跳过: id={}", source.id(), e); }
        }
    }

    private void ingestSource(Source source) {
        for (FeedItem item : feedReader.read(source)) {
            try { ingestItem(source, item); }
            catch (RuntimeException e) { log.warn("条目处理异常，跳过: url={}", item.url(), e); }
        }
    }

    private void ingestItem(Source source, FeedItem item) {
        String fp = fingerprint(item);
        if (repo.hasSeen(fp)) return;                                    // ① 增量去重
        String body = fetcher.fetch(item.url());
        if (body == null || body.length() < MIN_BODY) {                  // 死链/空正文
            repo.markSeen(fp, source.id(), item.url(), item.title()); return;
        }
        if (!gate.isRelevant(body)) {                                    // ② 相关性剔除
            repo.markSeen(fp, source.id(), item.url(), item.title()); return;
        }
        List<String> chunks = chunker.split(body);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            repo.upsertChunk(new CorpusChunk(                            // ③ 落 SQLite
                fp + ":" + i, source.id(), item.url(), item.title(), i, chunk, item.publishedEpochMs()));
            memory.remember(new MemoryItem(chunk, Map.of(               // ④ 灌内存库
                "uri", nz(item.url()), "title", nz(item.title()), "source", nz(source.id()))));
        }
        repo.markSeen(fp, source.id(), item.url(), item.title());        // ⑤ 全部成功后标记
    }

    static String fingerprint(FeedItem item) {
        return (item.guid() != null && !item.guid().isBlank()) ? item.guid() : item.url();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=IngestionServiceTest test`
预期：PASS（2 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/IngestionService.java \
        src/test/java/com/harnesslearn/agent/ingest/IngestionServiceTest.java
git commit -m "feat(ingest): IngestionService 编排流水线（去重/相关性/切块/落库）

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 8：CorpusIndexRebuilder

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilder.java`
- 测试：`src/test/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilderTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilderTest.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusIndexRebuilderTest {

    static class RecordingMemory implements LongTermMemory {
        final List<MemoryItem> items = new ArrayList<>();
        public void remember(MemoryItem item) { items.add(item); }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    static class FlakyMemory implements LongTermMemory {
        int calls = 0; final List<MemoryItem> ok = new ArrayList<>();
        public void remember(MemoryItem item) {
            if (++calls == 1) throw new RuntimeException("embed boom");
            ok.add(item);
        }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    private CorpusRepository repoWith3() {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memRebuild?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        var repo = new CorpusRepository(jt);
        for (int i = 0; i < 3; i++)
            repo.upsertChunk(new CorpusChunk("id" + i, "s", "http://u/" + i, "T" + i, 0, "文本" + i, null));
        return repo;
    }

    @Test
    void rebuildsAllChunksIntoMemory() {
        var memory = new RecordingMemory();
        int n = new CorpusIndexRebuilder(repoWith3(), memory).rebuild();
        assertThat(n).isEqualTo(3);
        assertThat(memory.items).hasSize(3);
        assertThat(memory.items).allSatisfy(m -> assertThat(m.meta()).containsKey("uri"));
    }

    @Test
    void singleFailureIsSkippedNotFatal() {
        var memory = new FlakyMemory();
        int n = new CorpusIndexRebuilder(repoWith3(), memory).rebuild();
        assertThat(n).isEqualTo(2);            // 首条抛异常被跳过
        assertThat(memory.ok).hasSize(2);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=CorpusIndexRebuilderTest test`
预期：编译失败（`CorpusIndexRebuilder` 不存在）。

- [ ] **步骤 3：创建 CorpusIndexRebuilder**

`src/main/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilder.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * 启动重建：从 corpus_chunk（真相源）读全量，逐条重新 embed 灌回内存向量库。
 * InMemoryEmbeddingStore 进程内、重启即空，故每次启动必须重建。best-effort：单条失败跳过。
 */
public class CorpusIndexRebuilder {
    private static final Logger log = LoggerFactory.getLogger(CorpusIndexRebuilder.class);
    private final CorpusRepository repo;
    private final LongTermMemory memory;

    public CorpusIndexRebuilder(CorpusRepository repo, LongTermMemory memory) {
        this.repo = repo; this.memory = memory;
    }

    public int rebuild() {
        List<CorpusChunk> chunks = repo.allChunks();
        int ok = 0;
        for (CorpusChunk c : chunks) {
            try {
                memory.remember(new MemoryItem(c.text(), Map.of(
                    "uri", nz(c.url()), "title", nz(c.title()), "source", nz(c.sourceId()))));
                ok++;
            } catch (RuntimeException e) {
                log.warn("重建索引单条失败，跳过: id={}", c.id(), e);
            }
        }
        log.info("重建向量索引：{}/{} 块", ok, chunks.size());
        return ok;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=CorpusIndexRebuilderTest test`
预期：PASS（2 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilder.java \
        src/test/java/com/harnesslearn/agent/ingest/CorpusIndexRebuilderTest.java
git commit -m "feat(ingest): CorpusIndexRebuilder 启动重建内存索引

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 9：CorpusSeeder 改造（灌 SQLite）

**文件：**
- 修改：`src/main/java/com/harnesslearn/agent/l4memory/CorpusSeeder.java`（整体重写构造与 `seed()`）
- 修改：`src/test/java/com/harnesslearn/agent/l4memory/CorpusSeederTest.java`（改为断言 SQLite）

**上下文：** 现 `CorpusSeeder(LongTermMemory, String)` 直接灌内存库。改为 `CorpusSeeder(CorpusRepository, String)`：空库才播种，只写 `corpus_chunk`，之后由 `CorpusIndexRebuilder` 统一重建。种子块 id = `"seed:" + uri`（确定性、唯一）。`SeedEntry` record 保留不变（`SeedCorpusResourceTest` 仍依赖它）。

- [ ] **步骤 1：改写测试（先让它表达新契约）**

将 `src/test/java/com/harnesslearn/agent/l4memory/CorpusSeederTest.java` 整体替换为：

```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.ingest.CorpusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSeederTest {

    private CorpusRepository repo(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new CorpusRepository(jt);
    }

    @Test
    void seedsFixtureIntoSqlite() {
        var repo = repo("memSeed1");
        int n = new CorpusSeeder(repo, "/test-seed-corpus.json").seed();
        assertThat(n).isEqualTo(3);
        assertThat(repo.chunkCount()).isEqualTo(3);
    }

    @Test
    void skipsWhenCorpusNotEmpty() {
        var repo = repo("memSeed2");
        repo.upsertChunk(new com.harnesslearn.agent.ingest.CorpusChunk(
            "pre", "s", "http://u", "T", 0, "已有", null));
        int n = new CorpusSeeder(repo, "/test-seed-corpus.json").seed();
        assertThat(n).isZero();                    // 非空库跳过
        assertThat(repo.chunkCount()).isEqualTo(1);
    }

    @Test
    void missingResourceIsBestEffortReturnsZero() {
        var repo = repo("memSeed3");
        int n = new CorpusSeeder(repo, "/no-such-seed.json").seed();
        assertThat(n).isZero();
        assertThat(repo.chunkCount()).isZero();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=CorpusSeederTest test`
预期：编译失败（`CorpusSeeder` 构造签名仍是旧的 `LongTermMemory`）。

- [ ] **步骤 3：重写 CorpusSeeder**

将 `src/main/java/com/harnesslearn/agent/l4memory/CorpusSeeder.java` 整体替换为：

```java
package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.ingest.CorpusChunk;
import com.harnesslearn.agent.ingest.CorpusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.List;

/**
 * 启动种子语料摄取：读内置 JSON，空库时逐条写入 corpus_chunk（SQLite 真相源），
 * 之后由 CorpusIndexRebuilder 统一重建内存索引——种子与抓取语料走同一持久化+重建路径。
 *
 * <p>best-effort：库非空跳过；资源缺失/解析失败 → WARN 返回 0（不抛、不中断启动）；
 * 单条写入失败 → WARN 跳过该条。无 Spring 依赖，可直接 {@code new} 单测。
 */
public class CorpusSeeder {
    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    /** 种子条目。{@code tags} 供未来过滤维度，本轮检索期不消费。 */
    public record SeedEntry(String text, String uri, List<String> tags) {}

    private final CorpusRepository repo;
    private final String resourcePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public CorpusSeeder(CorpusRepository repo, String resourcePath) {
        if (repo == null) throw new IllegalArgumentException("repo 不能为空");
        if (resourcePath == null || resourcePath.isBlank())
            throw new IllegalArgumentException("resourcePath 不能为空");
        this.repo = repo;
        this.resourcePath = resourcePath;
    }

    /**
     * 空库时读种子资源逐条写入 SQLite。
     * @return 成功写入的条数（库非空 / 资源缺失 / 解析失败均返回 0）
     */
    public int seed() {
        if (repo.chunkCount() > 0) {
            log.info("语料库非空，跳过种子播种");
            return 0;
        }
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
                repo.upsertChunk(new CorpusChunk(
                    "seed:" + e.uri(), "seed", e.uri(), null, 0, e.text(), null));
                ok++;
            } catch (RuntimeException ex) {
                log.warn("种子条目写入失败，跳过: uri={}", e.uri(), ex);
            }
        }
        log.info("种子语料写入 SQLite 完成: {}/{} 条", ok, entries.length);
        return ok;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=CorpusSeederTest test`
预期：PASS（3 个测试）。

> `SeedCorpusResourceTest` 只依赖 `CorpusSeeder.SeedEntry` record（未改），应仍通过；此步顺带确认：`mvn -q -Dtest=SeedCorpusResourceTest test` 预期 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/l4memory/CorpusSeeder.java \
        src/test/java/com/harnesslearn/agent/l4memory/CorpusSeederTest.java
git commit -m "refactor(ingest): CorpusSeeder 改为灌 SQLite（空库播种）

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 10：IngestionScheduler + IngestConfig 装配 + 全量接线

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/ingest/IngestionScheduler.java`
- 创建：`src/main/java/com/harnesslearn/agent/ingest/IngestConfig.java`
- 修改：`src/main/java/com/harnesslearn/agent/AgentConfig.java`（移除 corpusSeeder/corpusBootstrap；schemaBootstrap 加 `@Order(1)`）
- 修改：`src/main/resources/application.yml`（移除 `agent.corpus.seed-on-startup`，加 `agent.ingest.*`）
- 修改：`src/test/java/com/harnesslearn/agent/AgentApplicationTest.java`（开关改名）
- 修改：`src/test/java/com/harnesslearn/agent/l4memory/CorpusSeedIntegrationTest.java`（开关改名 + poll-cron）

**上下文：** 这是把所有组件装配成运行系统的接线任务。启动顺序 `SchemaInitializer(@Order 1)` → `CorpusSeeder(@Order 2)` → `CorpusIndexRebuilder(@Order 3)`；`IngestionScheduler` 定时触发增量采集。种子/重建/调度均由 `agent.ingest.enabled` 门控。

- [ ] **步骤 1：创建 IngestionScheduler**

`src/main/java/com/harnesslearn/agent/ingest/IngestionScheduler.java`：

```java
package com.harnesslearn.agent.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时轮询触发增量采集。cron 来自 agent.ingest.poll-cron。
 * 顶层 try/catch：整轮异常吞掉并 WARN，绝不让 @Scheduled 单线程因异常静默停摆。
 */
public class IngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
    private final IngestionService service;

    public IngestionScheduler(IngestionService service) { this.service = service; }

    @Scheduled(cron = "${agent.ingest.poll-cron}")
    public void poll() {
        try {
            service.ingestAll();
        } catch (RuntimeException e) {
            log.warn("定时采集整轮异常，已吞掉以保调度存活", e);
        }
    }
}
```

- [ ] **步骤 2：创建 IngestConfig**

`src/main/java/com/harnesslearn/agent/ingest/IngestConfig.java`：

```java
package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l2tools.RelevanceFilter;
import com.harnesslearn.agent.l4memory.CorpusSeeder;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 采集管道（子项目 A）的 Spring 装配。种子/重建/调度由 agent.ingest.enabled 门控。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfig {

    @Bean public CorpusRepository corpusRepository(JdbcTemplate jdbc) {
        return new CorpusRepository(jdbc);
    }

    @Bean public SourceRegistry sourceRegistry(IngestProperties props) {
        return new SourceRegistry(props);
    }

    @Bean public FeedReader feedReader() { return new FeedReader(); }

    @Bean public ArticleFetcher articleFetcher() { return new ArticleFetcher(); }

    @Bean public RelevanceGate relevanceGate(RelevanceFilter filter) {
        return new RelevanceGate(filter);
    }

    @Bean public Chunker chunker(IngestProperties props) {
        return new Chunker(props.chunkMaxChars() > 0 ? props.chunkMaxChars() : 800);
    }

    @Bean public IngestionService ingestionService(SourceRegistry r, FeedReader fr,
            ArticleFetcher af, RelevanceGate g, Chunker c, CorpusRepository repo, LongTermMemory m) {
        return new IngestionService(r, fr, af, g, c, repo, m);
    }

    @Bean public CorpusSeeder corpusSeeder(CorpusRepository repo) {
        return new CorpusSeeder(repo, "/seed-corpus.json");
    }

    @Bean public CorpusIndexRebuilder corpusIndexRebuilder(CorpusRepository repo, LongTermMemory m) {
        return new CorpusIndexRebuilder(repo, m);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public IngestionScheduler ingestionScheduler(IngestionService service) {
        return new IngestionScheduler(service);
    }

    /** 空库播种进 SQLite。@Order(2)：在 schemaBootstrap(@Order 1) 之后、重建之前。 */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public ApplicationRunner corpusSeedRunner(CorpusSeeder seeder) {
        return args -> seeder.seed();
    }

    /** 从 SQLite 重建内存索引。@Order(3)：在播种之后。 */
    @Bean
    @Order(3)
    @ConditionalOnProperty(name = "agent.ingest.enabled", havingValue = "true", matchIfMissing = false)
    public ApplicationRunner corpusRebuildRunner(CorpusIndexRebuilder rebuilder) {
        return args -> rebuilder.rebuild();
    }
}
```

- [ ] **步骤 3：改 AgentConfig——移除旧种子 bean，schemaBootstrap 加 @Order(1)**

在 `AgentConfig.java`：

1. 删除 `corpusSeeder`（`:61-64`）和 `corpusBootstrap`（`:66-76`，含其上方 javadoc）两个 bean 方法。
2. 删除不再使用的 import：`com.harnesslearn.agent.l4memory.CorpusSeeder`（`:12`）。保留 `ConditionalOnProperty` import 仅当它处仍用到——本类删除后若无其他使用则一并删除 `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`（`:33`）。
3. 给 `schemaBootstrap` 加 `@Order(1)`，并 import `org.springframework.core.annotation.Order`：

```java
    @Bean
    @org.springframework.core.annotation.Order(1)
    public ApplicationRunner schemaBootstrap(SchemaInitializer schema) {
        return args -> schema.init();
    }
```

> 说明：`ApplicationRunner` 的执行顺序由 `@Order` 决定（值小者先跑）。schema(1) 建表 → seed(2) 播种 → rebuild(3) 重建，跨 `@Configuration` 全局生效。

- [ ] **步骤 4：改 application.yml**

将 `src/main/resources/application.yml` 末尾的

```yaml
  corpus:
    seed-on-startup: true
```

替换为：

```yaml
  ingest:
    enabled: true
    poll-cron: "0 0 */6 * * *"
    chunk-max-chars: 800
    sources:
      - id: harness-blog
        type: PAGE
        url: https://www.harness.io/blog
        tags: [harness, ci-cd]
```

- [ ] **步骤 5：改两个 @SpringBootTest 的开关名**

`AgentApplicationTest.java`：把 `"agent.corpus.seed-on-startup=false"` 改为 `"agent.ingest.enabled=false"`。

`CorpusSeedIntegrationTest.java`：把 properties 改为（开关改名 + 补 poll-cron，因 enabled=true 时 IngestionScheduler bean 会创建并解析 cron 占位符；同时关掉网络源避免启动轮询打网络——启动只跑种子+重建，不跑 ingestAll，但 sources 留空更稳妥）：

```java
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.ingest.enabled=true",
    "agent.ingest.poll-cron=0 0 0 * * *",
    "agent.ingest.chunk-max-chars=800"
})
```

并把类 javadoc 里「seed-on-startup=true 时，corpusBootstrap…」一句更新为「ingest.enabled=true 时，启动种子写 SQLite 后经 CorpusIndexRebuilder 重建索引，检索非空」。测试体断言不变（`longTermMemory.retrieve(...)` 非空）。

> 为什么检索仍非空：`agent.ingest.enabled=true` → corpusSeedRunner 把 `/seed-corpus.json`（12 条）写入内存 SQLite → corpusRebuildRunner 从 SQLite 逐条 `remember` 灌入与 `LocalRetrieveTool` 共享的同一 `longTermMemory` 单例。`spring.datasource.hikari.maximum-pool-size=1` 保证 `:memory:` 库在建表→播种→重建间由同一连接持有、不丢表。

- [ ] **步骤 6：编译 + 全量回归**

运行：`mvn -q compile`
预期：BUILD SUCCESS（无遗留对旧 `CorpusSeeder(LongTermMemory,...)` 或 `agent.corpus.seed-on-startup` 的引用）。

运行：`mvn clean test`
预期：BUILD SUCCESS，全绿。原有测试 + 新增 ingest 包测试全部通过。权威计数以本命令输出为准（`mvn clean test` 避免 stale surefire 报告导致的计数偏差）。

> 若 `CorpusSeedIntegrationTest` 因 `:memory:` 库跨连接丢表而失败，核对 `application.yml` 的 `hikari.maximum-pool-size: 1` 是否仍在（它是该测试成立的前提；此前 seed-on-startup 版本亦依赖它）。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/ingest/IngestionScheduler.java \
        src/main/java/com/harnesslearn/agent/ingest/IngestConfig.java \
        src/main/java/com/harnesslearn/agent/AgentConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/harnesslearn/agent/AgentApplicationTest.java \
        src/test/java/com/harnesslearn/agent/l4memory/CorpusSeedIntegrationTest.java
git commit -m "feat(ingest): IngestConfig 装配 + @Scheduled 轮询 + 启动重建接线

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 完成后

所有任务完成后，用 superpowers:finishing-a-development-branch 收尾（合并/PR/清理）。当前分支：`worktree-corpus-ingestion-pipeline`。

**验收标准回顾（对照规格）：**
- 进程内 `ingest` 包，9 个组件 + 3 record + IngestProperties + IngestConfig 齐备。
- `corpus_seen`/`corpus_chunk` 两表建立；三层去重（条目级 hasSeen、块级 upsert 幂等、离题 markSeen）生效。
- 相关性复用 `RelevanceFilter`（同一尺子）；切块 ≤800 字无 LLM。
- SQLite 真相源 + 启动重建；种子并入统一路径；`agent.ingest.enabled` 单开关门控。
- 全线 best-effort；`mvn clean test` 全绿。
