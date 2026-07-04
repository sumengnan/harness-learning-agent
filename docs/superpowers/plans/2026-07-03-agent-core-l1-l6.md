# Agent 核心（L1–L6）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现子项目 B —— 一个用 langchain4j 手写 Agent 循环驱动的、具备 L1–L6 六层控制的生产级自主决策 Agent，本地单用户运行，能完成 RAG 问答与专题综述等任务。

**架构：** Spring Boot 应用，按 6 层分包，每层对外暴露单一接口。顶层 planner（L3）是一个手写 ReAct 循环，自主决定调用工具 / 派发子 Agent / 结束；L1 装配上下文、L2 执行并提炼工具结果+剔垃圾、L4 隔离三类状态、L5 独立验证、L6 规则拦截与失败恢复。全链路 trace 落盘。核心可测性依赖 `FakeChatModel`（可编排返回序列的假 `ChatLanguageModel`），让自主循环确定性、零成本测试。

**技术栈：** Java 21、Spring Boot 3.3.x、langchain4j **0.35.0**（`langchain4j`、`langchain4j-open-ai`、`langchain4j-embeddings-bge-small-zh`）、SQLite（`sqlite-jdbc` + Spring `JdbcTemplate`）、JUnit 5、AssertJ。LLM 走 DeepSeek OpenAI 兼容接口（`baseUrl=https://api.deepseek.com/v1`、`modelName=deepseek-chat`）。

> **langchain4j 0.35.0 API 基线**（计划内代码据此编写，实现时以此版本为准）：
> - 模型接口 `dev.langchain4j.model.chat.ChatLanguageModel`，方法 `Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs)`。
> - `AiMessage.text()` 返回文本；`AiMessage.toolExecutionRequests()` 返回 `List<ToolExecutionRequest>`（含 `id()`/`name()`/`arguments()` JSON 串）。
> - 回填工具结果用 `ToolExecutionResultMessage.from(request, resultString)`。
> - Embedding：`dev.langchain4j.model.embedding.EmbeddingModel`，`model.embed(text).content()` 得 `Embedding`（`.vector()` 为 `float[]`）；本地实现用 `BgeSmallZhV15QuantizedEmbeddingModel`。
> - `InMemoryEmbeddingStore<TextSegment>`，`add(Embedding, TextSegment)` / `search(EmbeddingSearchRequest)`。

---

## 文件结构（先锁定分解）

```
pom.xml                                              Maven 依赖与构建
src/main/resources/application.yml                   配置（模型、SQLite、日志级别、预算阈值）
src/main/resources/logback-spring.xml                JSON 结构化日志
src/main/java/com/harnesslearn/agent/
├── AgentApplication.java                            Spring Boot 入口
├── domain/                                          跨层共享领域模型（records/enums）
│   ├── TaskSpec.java  TaskType.java  WorkingState.java
│   ├── RetrievedChunk.java  Artifact.java  ArtifactQuery.java  MemoryItem.java
│   ├── ToolCall.java  ToolResult.java  DistilledResult.java
│   ├── AgentOutput.java  Verdict.java  Issue.java
│   ├── ValidationResult.java  FailureContext.java  RecoveryDecision.java  RecoveryStrategy.java
│   └── AgentRun.java  ModelStep.java
├── llm/
│   ├── LlmConfig.java                               装配 ChatLanguageModel（DeepSeek）
│   └── LlmProperties.java                           @ConfigurationProperties
├── l4memory/
│   ├── WorkingStateStore.java (接口) SqliteWorkingStateStore.java
│   ├── ArtifactStore.java (接口)     SqliteArtifactStore.java
│   ├── LongTermMemory.java (接口)    VectorLongTermMemory.java
│   └── SchemaInitializer.java                       建表
├── l1context/
│   ├── L1ContextAssembler.java (接口) DefaultL1ContextAssembler.java
│   └── SystemPrompts.java                           角色/范围定义
├── l2tools/
│   ├── Tool.java (接口)  ToolRegistry.java  L2ToolSystem.java (接口) DefaultL2ToolSystem.java
│   ├── RelevanceFilter.java                         相关性过滤+去重
│   └── tools/ LocalRetrieveTool.java WebSearchTool.java FetchPageTool.java
├── l5eval/
│   └── L5Evaluator.java (接口)  LlmL5Evaluator.java
├── l6guardrail/
│   └── L6Guardrail.java (接口)  DefaultL6Guardrail.java  RecoveryPolicy.java
├── l3orchestrate/
│   └── L3Orchestrator.java (接口)  AgentLoop.java
├── subagent/
│   ├── SubAgent.java (接口)  SubAgentDispatcher.java (接口) DefaultSubAgentDispatcher.java
│   └── SourceSummarizer.java  SectionWriter.java
├── observability/
│   ├── LoggingChatModelListener.java               每次 LLM 调用落盘
│   ├── AgentTrace.java  TraceStep.java  TraceStore.java (接口) SqliteTraceStore.java
│   └── AgentMetrics.java                            Micrometer 指标
└── api/
    ├── AgentController.java                         REST + SSE
    └── dto/ RunRequest.java  RunResponse.java

src/test/java/com/harnesslearn/agent/
├── support/ FakeChatModel.java  FakeChatModelBuilder.java  InMemoryStores.java
└── （各层测试镜像 main 包结构）
```

**分解原则：** 按职责（层）分包而非技术层级；一起变更的放一起；每个接口单一职责，实现可替换。`domain/` 是所有层共享的类型来源，**必须最先建立并在后续任务中一致引用**。

---

## 阶段总览

| 阶段 | 任务 | 产出的可工作增量 |
|---|---|---|
| 0 脚手架 | 1–2 | 空应用能启动、能跑测试；LLM 与 FakeChatModel 就绪 |
| 1 领域+L4 | 3–6 | 共享类型；三类状态可持久化、可隔离读写 |
| 2 L1 | 7 | 能把角色+任务状态+相关信息装配成上下文 |
| 3 L2 | 8–11 | 工具可注册/执行，结果被提炼、垃圾被过滤 |
| 4 L5 | 12 | 独立验证器能对产出给出 verdict |
| 5 L6 | 13–14 | 规则拦截 + 失败→恢复策略映射 |
| 6 L3 | 15 | 手写循环把 L1/L2/L4/L5/L6 串成自主 Agent |
| 7 子 Agent | 16–17 | 有界子任务可并行派发、失败降级 |
| 8 可观测性 | 18–19 | 每次 LLM 调用与每步决策全量落盘 + SSE |
| 9 API+集成 | 20–21 | REST/SSE 对外；端到端综述 run 集成测试绿 |

每个任务遵循 TDD：先写失败测试 → 运行确认失败 → 最小实现 → 运行确认通过 → commit。

---

## 阶段 0：项目脚手架

### 任务 1：Maven + Spring Boot 骨架

**文件：**
- 创建：`pom.xml`
- 创建：`src/main/java/com/harnesslearn/agent/AgentApplication.java`
- 创建：`src/main/resources/application.yml`
- 测试：`src/test/java/com/harnesslearn/agent/AgentApplicationTest.java`

- [ ] **步骤 1：写 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>com.harnesslearn</groupId>
  <artifactId>agent-core</artifactId>
  <version>0.1.0</version>
  <properties>
    <java.version>21</java.version>
    <langchain4j.version>0.35.0</langchain4j.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j</artifactId><version>${langchain4j.version}</version></dependency>
    <dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-open-ai</artifactId><version>${langchain4j.version}</version></dependency>
    <dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-embeddings-bge-small-zh</artifactId><version>${langchain4j.version}</version></dependency>
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>3.46.1.3</version></dependency>
    <dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.18.1</version></dependency>
    <dependency><groupId>net.logstash.logback</groupId><artifactId>logstash-logback-encoder</artifactId><version>8.0</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
```

- [ ] **步骤 2：写入口与配置**

`AgentApplication.java`：
```java
package com.harnesslearn.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
```

`application.yml`：
```yaml
spring:
  datasource:
    url: jdbc:sqlite:${AGENT_DB:./data/agent.db}
    driver-class-name: org.sqlite.JDBC
agent:
  llm:
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${DEEPSEEK_API_KEY:}
    model-name: ${DEEPSEEK_MODEL:deepseek-chat}
    temperature: 0.2
  orchestrate:
    max-steps: 20
  filter:
    relevance-threshold: 0.35     # τ
    borderline-delta: 0.05        # δ
```

- [ ] **步骤 3：写冒烟测试**

```java
package com.harnesslearn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
class AgentApplicationTest {
    @Test
    void contextLoads() { }
}
```

- [ ] **步骤 4：运行验证**

运行：`mvn -q test`
预期：BUILD SUCCESS，`contextLoads` 通过。

- [ ] **步骤 5：Commit**

```bash
git add pom.xml src/main src/test
git commit -m "chore: Spring Boot + langchain4j 项目骨架"
```

---

### 任务 2：LLM 装配 + FakeChatModel 测试替身

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/llm/LlmProperties.java`
- 创建：`src/main/java/com/harnesslearn/agent/llm/LlmConfig.java`
- 创建：`src/test/java/com/harnesslearn/agent/support/FakeChatModel.java`
- 测试：`src/test/java/com/harnesslearn/agent/support/FakeChatModelTest.java`

- [ ] **步骤 1：写 `FakeChatModel` 失败测试**

```java
package com.harnesslearn.agent.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FakeChatModelTest {
    @Test
    void returnsScriptedMessagesInOrder() {
        FakeChatModel fake = FakeChatModel.scripted(
            AiMessage.from("first"),
            AiMessage.from("second"));
        Response<AiMessage> r1 = fake.generate(List.of(UserMessage.from("q")), List.of());
        Response<AiMessage> r2 = fake.generate(List.of(UserMessage.from("q")), List.of());
        assertThat(r1.content().text()).isEqualTo("first");
        assertThat(r2.content().text()).isEqualTo("second");
        assertThat(fake.callCount()).isEqualTo(2);
    }
}
```

- [ ] **步骤 2：运行验证失败**

运行：`mvn -q -Dtest=FakeChatModelTest test`
预期：FAIL，编译错误 "cannot find symbol FakeChatModel"。

- [ ] **步骤 3：实现 `FakeChatModel`**

```java
package com.harnesslearn.agent.support;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 可编排返回序列的假模型；用于确定性测试自主循环。 */
public class FakeChatModel implements ChatLanguageModel {
    private final Deque<AiMessage> scripted;
    private int calls = 0;

    private FakeChatModel(List<AiMessage> messages) { this.scripted = new ArrayDeque<>(messages); }

    public static FakeChatModel scripted(AiMessage... messages) { return new FakeChatModel(List.of(messages)); }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
        calls++;
        AiMessage next = scripted.isEmpty() ? AiMessage.from("[no more scripted responses]") : scripted.poll();
        return Response.from(next);
    }

    public int callCount() { return calls; }
}
```

- [ ] **步骤 4：实现 `LlmProperties` + `LlmConfig`（真实模型装配）**

`LlmProperties.java`：
```java
package com.harnesslearn.agent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm")
public record LlmProperties(String baseUrl, String apiKey, String modelName, Double temperature) {}
```

`LlmConfig.java`：
```java
package com.harnesslearn.agent.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
    @Bean
    public ChatLanguageModel chatLanguageModel(LlmProperties props) {
        return OpenAiChatModel.builder()
            .baseUrl(props.baseUrl())
            .apiKey(props.apiKey())
            .modelName(props.modelName())
            .temperature(props.temperature())
            .timeout(Duration.ofSeconds(60))
            .build();
    }
}
```

在 `AgentApplication` 上加 `@ConfigurationPropertiesScan` 或让 `LlmConfig` 的 `@EnableConfigurationProperties` 生效即可。

- [ ] **步骤 5：运行验证通过并 Commit**

运行：`mvn -q -Dtest=FakeChatModelTest test` → 预期 PASS。
```bash
git add src/main/java/com/harnesslearn/agent/llm src/test/java/com/harnesslearn/agent/support/FakeChatModel.java src/test/java/com/harnesslearn/agent/support/FakeChatModelTest.java
git commit -m "feat: LLM 装配（DeepSeek）+ FakeChatModel 测试替身"
```

---

## 阶段 1：领域模型 + L4 记忆与状态

### 任务 3：共享领域模型

**文件：** 全部创建于 `src/main/java/com/harnesslearn/agent/domain/`
**测试：** `src/test/java/com/harnesslearn/agent/domain/WorkingStateTest.java`

- [ ] **步骤 1：写 `WorkingState` 行为的失败测试**（其余类型是纯数据 record，随此测试一并落地）

```java
package com.harnesslearn.agent.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkingStateTest {
    @Test
    void tracksStepsAndBudget() {
        WorkingState s = WorkingState.start("run1", "写一篇综述", 3);
        s.recordStep("检索了官网");
        assertThat(s.stepsUsed()).isEqualTo(1);
        assertThat(s.completedSteps()).containsExactly("检索了官网");
        assertThat(s.budgetRemaining()).isEqualTo(2);
        s.recordStep("a"); s.recordStep("b");
        assertThat(s.budgetExhausted()).isTrue();
    }
}
```

- [ ] **步骤 2：运行验证失败**

运行：`mvn -q -Dtest=WorkingStateTest test` → 预期编译失败。

- [ ] **步骤 3：实现全部领域类型**

```java
// TaskType.java
package com.harnesslearn.agent.domain;
public enum TaskType { QA, SURVEY, DIGEST, LEARNING_PATH }
```
```java
// TaskSpec.java
package com.harnesslearn.agent.domain;
import java.util.Map;
public record TaskSpec(String runId, TaskType type, String userQuery, Map<String,Object> params) {}
```
```java
// WorkingState.java
package com.harnesslearn.agent.domain;
import java.util.ArrayList;
import java.util.List;
public final class WorkingState {
    private final String runId;
    private final String goal;
    private final int stepBudget;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> openQuestions = new ArrayList<>();
    private WorkingState(String runId, String goal, int stepBudget) {
        this.runId = runId; this.goal = goal; this.stepBudget = stepBudget;
    }
    public static WorkingState start(String runId, String goal, int stepBudget) {
        return new WorkingState(runId, goal, stepBudget);
    }
    public void recordStep(String desc) { completedSteps.add(desc); }
    public void addOpenQuestion(String q) { openQuestions.add(q); }
    public String runId() { return runId; }
    public String goal() { return goal; }
    public List<String> completedSteps() { return List.copyOf(completedSteps); }
    public List<String> openQuestions() { return List.copyOf(openQuestions); }
    public int stepsUsed() { return completedSteps.size(); }
    public int budgetRemaining() { return stepBudget - stepsUsed(); }
    public boolean budgetExhausted() { return budgetRemaining() <= 0; }
}
```
```java
// RetrievedChunk.java
package com.harnesslearn.agent.domain;
public record RetrievedChunk(String id, String sourceUri, String text, double relevanceScore) {}
```
```java
// Artifact.java
package com.harnesslearn.agent.domain;
import java.util.Map;
public record Artifact(String id, String runId, String kind, String key, String content, Map<String,String> meta) {}
```
```java
// ArtifactQuery.java
package com.harnesslearn.agent.domain;
public record ArtifactQuery(String runId, String kind) {}
```
```java
// MemoryItem.java
package com.harnesslearn.agent.domain;
import java.util.Map;
public record MemoryItem(String text, Map<String,String> meta) {}
```
```java
// ToolCall.java
package com.harnesslearn.agent.domain;
public record ToolCall(String id, String name, String argumentsJson) {}
```
```java
// ToolResult.java
package com.harnesslearn.agent.domain;
public record ToolResult(boolean ok, String rawContent, String error) {
    public static ToolResult ok(String content) { return new ToolResult(true, content, null); }
    public static ToolResult fail(String error) { return new ToolResult(false, null, error); }
}
```
```java
// DistilledResult.java
package com.harnesslearn.agent.domain;
import java.util.List;
public record DistilledResult(List<RetrievedChunk> chunks, int droppedCount, String note) {}
```
```java
// AgentOutput.java
package com.harnesslearn.agent.domain;
import java.util.List;
public record AgentOutput(String content, List<Artifact> evidence) {}
```
```java
// Issue.java
package com.harnesslearn.agent.domain;
public record Issue(String dimension, String detail) {}
```
```java
// Verdict.java
package com.harnesslearn.agent.domain;
import java.util.List;
public record Verdict(boolean pass, List<Issue> issues, double confidence) {}
```
```java
// ValidationResult.java
package com.harnesslearn.agent.domain;
public record ValidationResult(boolean valid, String reason) {
    public static ValidationResult ok() { return new ValidationResult(true, null); }
    public static ValidationResult invalid(String reason) { return new ValidationResult(false, reason); }
}
```
```java
// FailureContext.java
package com.harnesslearn.agent.domain;
public record FailureContext(String failureType, int attempt, String detail) {}
```
```java
// RecoveryStrategy.java
package com.harnesslearn.agent.domain;
public enum RecoveryStrategy { RETRY, ROLLBACK, DEGRADE, ABORT }
```
```java
// RecoveryDecision.java
package com.harnesslearn.agent.domain;
public record RecoveryDecision(RecoveryStrategy strategy, String note) {}
```
```java
// ModelStep.java —— L3 从模型响应解析出的“下一步”
package com.harnesslearn.agent.domain;
import java.util.List;
public record ModelStep(String thought, List<ToolCall> toolCalls, String finalAnswer) {
    public boolean isFinish() { return finalAnswer != null; }
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
```
```java
// AgentRun.java
package com.harnesslearn.agent.domain;
public record AgentRun(String runId, AgentOutput output, boolean success, String terminationReason) {}
```

- [ ] **步骤 4：运行验证通过**

运行：`mvn -q -Dtest=WorkingStateTest test` → 预期 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/domain src/test/java/com/harnesslearn/agent/domain
git commit -m "feat: 跨层共享领域模型"
```

---

### 任务 4：SQLite 建表 + WorkingStateStore

**文件：**
- 创建：`l4memory/SchemaInitializer.java`、`l4memory/WorkingStateStore.java`、`l4memory/SqliteWorkingStateStore.java`
- 测试：`src/test/java/com/harnesslearn/agent/l4memory/SqliteWorkingStateStoreTest.java`

- [ ] **步骤 1：写失败测试**

```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.WorkingState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteWorkingStateStoreTest {
    private JdbcTemplate memoryJdbc() {
        var ds = new DriverManagerDataSource("jdbc:sqlite::memory:");
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void checkpointAndReloadRoundTrips() {
        JdbcTemplate jt = memoryJdbc();
        var store = new SqliteWorkingStateStore(jt);
        WorkingState s = WorkingState.start("run1", "目标X", 5);
        s.recordStep("step-a");
        store.checkpoint("run1", s);

        WorkingState loaded = store.load("run1");
        assertThat(loaded.goal()).isEqualTo("目标X");
        assertThat(loaded.completedSteps()).containsExactly("step-a");
        assertThat(loaded.budgetRemaining()).isEqualTo(4);
    }
}
```
> 注：`:memory:` 连接在连接关闭后即丢，`DriverManagerDataSource` 每次取新连接；为让测试内多次操作共享库，`SchemaInitializer` 与 store 复用同一 `JdbcTemplate`/`DataSource`，且 SQLite 的 `:memory:` 在同一 DataSource 的连接池语义下需用 `jdbc:sqlite:file::memory:?cache=shared`。**实现步骤 3 时用 `jdbc:sqlite:file:memdb_${random}?mode=memory&cache=shared`。**

- [ ] **步骤 2：运行验证失败**

运行：`mvn -q -Dtest=SqliteWorkingStateStoreTest test` → 预期编译失败。

- [ ] **步骤 3：实现**

`WorkingStateStore.java`：
```java
package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.WorkingState;
public interface WorkingStateStore {
    void checkpoint(String runId, WorkingState state);
    WorkingState load(String runId);
}
```

`SchemaInitializer.java`：
```java
package com.harnesslearn.agent.l4memory;
import org.springframework.jdbc.core.JdbcTemplate;
public class SchemaInitializer {
    private final JdbcTemplate jdbc;
    public SchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS working_state(
              run_id TEXT PRIMARY KEY, goal TEXT, step_budget INT,
              completed_steps TEXT, open_questions TEXT)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS artifact(
              id TEXT PRIMARY KEY, run_id TEXT, kind TEXT, key TEXT,
              content TEXT, meta TEXT)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS trace_step(
              id TEXT PRIMARY KEY, run_id TEXT, seq INT, layer TEXT,
              event TEXT, detail TEXT, ts INTEGER)""");
    }
}
```

`SqliteWorkingStateStore.java`（用 `\n` join 存 completed_steps，简单可靠；无嵌入换行的步骤描述由 L3 保证）：
```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.WorkingState;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Arrays;
import java.util.List;

public class SqliteWorkingStateStore implements WorkingStateStore {
    private final JdbcTemplate jdbc;
    public SqliteWorkingStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void checkpoint(String runId, WorkingState s) {
        jdbc.update("""
            INSERT INTO working_state(run_id,goal,step_budget,completed_steps,open_questions)
            VALUES(?,?,?,?,?)
            ON CONFLICT(run_id) DO UPDATE SET
              goal=excluded.goal, step_budget=excluded.step_budget,
              completed_steps=excluded.completed_steps, open_questions=excluded.open_questions""",
            runId, s.goal(), s.budgetRemaining() + s.stepsUsed(),
            String.join("\n", s.completedSteps()), String.join("\n", s.openQuestions()));
    }

    @Override
    public WorkingState load(String runId) {
        return jdbc.queryForObject(
            "SELECT goal,step_budget,completed_steps,open_questions FROM working_state WHERE run_id=?",
            (rs, n) -> {
                WorkingState s = WorkingState.start(runId, rs.getString("goal"), rs.getInt("step_budget"));
                for (String step : splitNonEmpty(rs.getString("completed_steps"))) s.recordStep(step);
                for (String q : splitNonEmpty(rs.getString("open_questions"))) s.addOpenQuestion(q);
                return s;
            }, runId);
    }

    private static List<String> splitNonEmpty(String v) {
        if (v == null || v.isEmpty()) return List.of();
        return Arrays.asList(v.split("\n"));
    }
}
```

- [ ] **步骤 4：运行验证通过**

运行：`mvn -q -Dtest=SqliteWorkingStateStoreTest test` → 预期 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/harnesslearn/agent/l4memory src/test/java/com/harnesslearn/agent/l4memory/SqliteWorkingStateStoreTest.java
git commit -m "feat(l4): SQLite 建表 + WorkingStateStore"
```

---

### 任务 5：ArtifactStore（中间产物，与状态隔离）

**文件：** 创建 `l4memory/ArtifactStore.java`、`l4memory/SqliteArtifactStore.java`；测试 `.../l4memory/SqliteArtifactStoreTest.java`

- [ ] **步骤 1：写失败测试（含隔离性断言）**

```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteArtifactStoreTest {
    private JdbcTemplate jt() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:file:memArt?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void putAndQueryByKind() {
        var store = new SqliteArtifactStore(jt());
        store.put(new Artifact("a1","run1","summary","src-1","要点…", Map.of("uri","http://x")));
        store.put(new Artifact("a2","run1","draft","sec-1","草稿…", Map.of()));
        List<Artifact> summaries = store.query(new ArtifactQuery("run1","summary"));
        assertThat(summaries).extracting(Artifact::id).containsExactly("a1");
    }
}
```

- [ ] **步骤 2：运行验证失败** → `mvn -q -Dtest=SqliteArtifactStoreTest test`，预期编译失败。

- [ ] **步骤 3：实现**

`ArtifactStore.java`：
```java
package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import java.util.List;
public interface ArtifactStore {
    void put(Artifact a);
    List<Artifact> query(ArtifactQuery q);
}
```

`SqliteArtifactStore.java`（meta 存 JSON，用 langchain4j 自带的 Jackson 或 Spring 的 `ObjectMapper`）：
```java
package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

public class SqliteArtifactStore implements ArtifactStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public SqliteArtifactStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void put(Artifact a) {
        jdbc.update("INSERT OR REPLACE INTO artifact(id,run_id,kind,key,content,meta) VALUES(?,?,?,?,?,?)",
            a.id(), a.runId(), a.kind(), a.key(), a.content(), writeJson(a.meta()));
    }

    @Override
    public List<Artifact> query(ArtifactQuery q) {
        return jdbc.query("SELECT * FROM artifact WHERE run_id=? AND kind=?",
            (rs, n) -> new Artifact(rs.getString("id"), rs.getString("run_id"),
                rs.getString("kind"), rs.getString("key"), rs.getString("content"),
                readJson(rs.getString("meta"))), q.runId(), q.kind());
    }

    private String writeJson(Map<String,String> m) {
        try { return mapper.writeValueAsString(m == null ? Map.of() : m); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked")
    private Map<String,String> readJson(String s) {
        try { return mapper.readValue(s, Map.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l4memory/ArtifactStore.java src/main/java/com/harnesslearn/agent/l4memory/SqliteArtifactStore.java src/test/java/com/harnesslearn/agent/l4memory/SqliteArtifactStoreTest.java
git commit -m "feat(l4): ArtifactStore（中间产物隔离存储）"
```

---

### 任务 6：LongTermMemory + 本地向量库

**文件：** 创建 `l4memory/LongTermMemory.java`、`l4memory/VectorLongTermMemory.java`；配置 `EmbeddingModel` bean（加入 `LlmConfig`）；测试 `.../l4memory/VectorLongTermMemoryTest.java`

- [ ] **步骤 1：写失败测试**（用真实的本地 bge 模型，离线、无需 key）

```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VectorLongTermMemoryTest {
    @Test
    void retrievesMostSimilar() {
        EmbeddingModel embed = new BgeSmallZhV15QuantizedEmbeddingModel();
        var mem = new VectorLongTermMemory(embed, new InMemoryEmbeddingStore<TextSegment>());
        mem.remember(new MemoryItem("Agent 上下文工程：裁剪无关信息", Map.of("uri","doc1")));
        mem.remember(new MemoryItem("今天天气不错", Map.of("uri","doc2")));
        List<RetrievedChunk> hits = mem.retrieve("如何做上下文裁剪", 1);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).text()).contains("上下文工程");
    }
}
```

- [ ] **步骤 2：运行验证失败** → `mvn -q -Dtest=VectorLongTermMemoryTest test`，预期编译失败。

- [ ] **步骤 3：实现**

`LongTermMemory.java`：
```java
package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import java.util.List;
public interface LongTermMemory {
    void remember(MemoryItem item);
    List<RetrievedChunk> retrieve(String query, int k);
}
```

`VectorLongTermMemory.java`：
```java
package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.List;
import java.util.UUID;

public class VectorLongTermMemory implements LongTermMemory {
    private final EmbeddingModel embed;
    private final EmbeddingStore<TextSegment> store;
    public VectorLongTermMemory(EmbeddingModel embed, EmbeddingStore<TextSegment> store) {
        this.embed = embed; this.store = store;
    }

    @Override
    public void remember(MemoryItem item) {
        TextSegment seg = TextSegment.from(item.text(),
            dev.langchain4j.data.document.Metadata.from(item.meta()));
        store.add(embed.embed(seg).content(), seg);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int k) {
        Embedding q = embed.embed(query).content();
        var req = EmbeddingSearchRequest.builder().queryEmbedding(q).maxResults(k).build();
        return store.search(req).matches().stream()
            .map(m -> new RetrievedChunk(
                UUID.randomUUID().toString(),
                m.embedded().metadata().getString("uri"),
                m.embedded().text(),
                m.score()))
            .toList();
    }
}
```

同时在 `LlmConfig` 增加：
```java
@Bean
public dev.langchain4j.model.embedding.EmbeddingModel embeddingModel() {
    return new dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel();
}
@Bean
public dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> embeddingStore() {
    return new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS（首次会下载/加载本地 ONNX 模型，稍慢）。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l4memory/LongTermMemory.java src/main/java/com/harnesslearn/agent/l4memory/VectorLongTermMemory.java src/main/java/com/harnesslearn/agent/llm/LlmConfig.java src/test/java/com/harnesslearn/agent/l4memory/VectorLongTermMemoryTest.java
git commit -m "feat(l4): LongTermMemory 本地向量检索（bge-small-zh）"
```

---

## 阶段 2：L1 信息边界

### 任务 7：L1ContextAssembler

**文件：** 创建 `l1context/SystemPrompts.java`、`l1context/L1ContextAssembler.java`、`l1context/DefaultL1ContextAssembler.java`；测试 `.../l1context/DefaultL1ContextAssemblerTest.java`

- [ ] **步骤 1：写失败测试**

```java
package com.harnesslearn.agent.l1context;

import com.harnesslearn.agent.domain.*;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultL1ContextAssemblerTest {
    @Test
    void assemblesRoleTaskStateAndOnlyTopKRelevant() {
        var assembler = new DefaultL1ContextAssembler(2); // 最多注入 2 条相关信息
        WorkingState state = WorkingState.start("run1", "综述：上下文工程", 10);
        state.recordStep("已检索官网");
        List<RetrievedChunk> candidates = List.of(
            new RetrievedChunk("c1","u1","高相关A",0.9),
            new RetrievedChunk("c2","u2","高相关B",0.8),
            new RetrievedChunk("c3","u3","低相关C",0.2));
        TaskSpec task = new TaskSpec("run1", TaskType.SURVEY, "综述上下文工程", java.util.Map.of());

        AssembledContext ctx = assembler.assemble(task, state, candidates);

        assertThat(ctx.messages().get(0)).isInstanceOf(SystemMessage.class);
        String all = ctx.render();
        assertThat(all).contains("AI Agent Harness 学习助手");   // 角色
        assertThat(all).contains("已检索官网");                    // 任务状态
        assertThat(all).contains("高相关A").contains("高相关B");   // top-2
        assertThat(all).doesNotContain("低相关C");                 // 超预算被裁
    }
}
```
> 需要在 `domain/` 补一个 `AssembledContext`（步骤 3 一并创建）。

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`domain/AssembledContext.java`：
```java
package com.harnesslearn.agent.domain;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
public record AssembledContext(String systemPrompt, List<ChatMessage> messages) {
    public String render() {
        StringBuilder sb = new StringBuilder(systemPrompt).append("\n");
        for (ChatMessage m : messages) sb.append(m.toString()).append("\n");
        return sb.toString();
    }
}
```

`SystemPrompts.java`：
```java
package com.harnesslearn.agent.l1context;
public final class SystemPrompts {
    private SystemPrompts() {}
    public static final String ROLE = """
        你是 "AI Agent Harness 学习助手"。你的职责：帮助用户学习如何构建 AI Agent 脚手架
        （agent 工程、上下文管理、工具编排、评估与恢复）。
        边界：只讨论与 agent harness 相关的主题；对明显无关的问题礼貌说明范围并拒绝。
        推进方式：理解目标 → 判断已有信息是否足够 → 分析 → 生成 → 自检。""";
}
```

`L1ContextAssembler.java`：
```java
package com.harnesslearn.agent.l1context;
import com.harnesslearn.agent.domain.*;
import java.util.List;
public interface L1ContextAssembler {
    AssembledContext assemble(TaskSpec task, WorkingState state, List<RetrievedChunk> candidates);
}
```

`DefaultL1ContextAssembler.java`：
```java
package com.harnesslearn.agent.l1context;

import com.harnesslearn.agent.domain.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Comparator;
import java.util.List;

public class DefaultL1ContextAssembler implements L1ContextAssembler {
    private final int maxInfo;
    public DefaultL1ContextAssembler(int maxInfo) { this.maxInfo = maxInfo; }

    @Override
    public AssembledContext assemble(TaskSpec task, WorkingState state, List<RetrievedChunk> candidates) {
        String taskState = """
            ## 当前任务
            目标：%s
            已完成步骤：%s
            待解问题：%s
            剩余步数预算：%d""".formatted(
                state.goal(),
                state.completedSteps().isEmpty() ? "（无）" : String.join("；", state.completedSteps()),
                state.openQuestions().isEmpty() ? "（无）" : String.join("；", state.openQuestions()),
                state.budgetRemaining());

        String info = candidates.stream()
            .sorted(Comparator.comparingDouble(RetrievedChunk::relevanceScore).reversed())
            .limit(maxInfo)
            .map(c -> "- [来源 %s] %s".formatted(c.sourceUri(), c.text()))
            .reduce("## 相关资料\n", (a, b) -> a + b + "\n");

        List<ChatMessage> messages = List.of(
            SystemMessage.from(SystemPrompts.ROLE),
            UserMessage.from(taskState + "\n\n" + info + "\n\n用户请求：" + task.userQuery()));
        return new AssembledContext(SystemPrompts.ROLE, messages);
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l1context src/main/java/com/harnesslearn/agent/domain/AssembledContext.java src/test/java/com/harnesslearn/agent/l1context
git commit -m "feat(l1): 信息边界层——角色/任务状态/相关信息装配+预算裁剪"
```

---

## 阶段 3：L2 工具系统 + 相关性过滤

### 任务 8：Tool 接口 + ToolRegistry

**文件：** 创建 `l2tools/Tool.java`、`l2tools/ToolRegistry.java`；测试 `.../l2tools/ToolRegistryTest.java`

- [ ] **步骤 1：写失败测试**

```java
package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    static class EchoTool implements Tool {
        public String name() { return "echo"; }
        public String description() { return "回显参数"; }
        public ToolResult execute(ToolCall call) { return ToolResult.ok(call.argumentsJson()); }
    }

    @Test
    void registersAndDispatchesByName() {
        var reg = new ToolRegistry(List.of(new EchoTool()));
        assertThat(reg.names()).containsExactly("echo");
        ToolResult r = reg.get("echo").execute(new ToolCall("1","echo","{\"x\":1}"));
        assertThat(r.rawContent()).isEqualTo("{\"x\":1}");
    }

    @Test
    void unknownToolThrows() {
        var reg = new ToolRegistry(List.of());
        assertThatThrownBy(() -> reg.get("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`Tool.java`：
```java
package com.harnesslearn.agent.l2tools;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
public interface Tool {
    String name();
    String description();
    ToolResult execute(ToolCall call);
}
```

`ToolRegistry.java`：
```java
package com.harnesslearn.agent.l2tools;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class ToolRegistry {
    private final Map<String, Tool> byName = new LinkedHashMap<>();
    public ToolRegistry(List<Tool> tools) { for (Tool t : tools) byName.put(t.name(), t); }
    public List<String> names() { return List.copyOf(byName.keySet()); }
    public Tool get(String name) {
        Tool t = byName.get(name);
        if (t == null) throw new IllegalArgumentException("未知工具: " + name);
        return t;
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l2tools/Tool.java src/main/java/com/harnesslearn/agent/l2tools/ToolRegistry.java src/test/java/com/harnesslearn/agent/l2tools/ToolRegistryTest.java
git commit -m "feat(l2): Tool 接口 + ToolRegistry"
```

---

### 任务 9：RelevanceFilter（剔垃圾 + 去重）

**文件：** 创建 `l2tools/RelevanceFilter.java`；测试 `.../l2tools/RelevanceFilterTest.java`

- [ ] **步骤 1：写失败测试**（用真实 bge embedding；领域锚点=一组 harness 相关句子）

```java
package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RelevanceFilterTest {
    @Test
    void dropsIrrelevantAndDuplicates() {
        EmbeddingModel embed = new BgeSmallZhV15QuantizedEmbeddingModel();
        var filter = new RelevanceFilter(embed,
            List.of("AI agent 脚手架与上下文工程", "工具编排与自主决策循环"),
            0.35, 0.05);

        List<RetrievedChunk> in = List.of(
            new RetrievedChunk("a","u1","如何设计 agent 的上下文裁剪与工具调用",0),
            new RetrievedChunk("b","u2","如何设计 agent 的上下文裁剪与工具调用",0), // 与 a 重复
            new RetrievedChunk("c","u3","今晚吃火锅的最佳蘸料配方",0));            // 无关垃圾

        RelevanceFilter.Result res = filter.filter(in);
        assertThat(res.kept()).extracting(RetrievedChunk::id).containsExactly("a");
        assertThat(res.droppedCount()).isEqualTo(2);
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**（余弦相似度 + 阈值闸门；去重用高相似阈值近邻）

```java
package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.ArrayList;
import java.util.List;

/** 相关性闸门 + 去重：全部发生在信息进入上下文之前。 */
public class RelevanceFilter {
    public record Result(List<RetrievedChunk> kept, int droppedCount) {}

    private final EmbeddingModel embed;
    private final float[] centroid;
    private final double threshold;    // τ
    private final double dedupSim = 0.97;

    public RelevanceFilter(EmbeddingModel embed, List<String> anchors, double threshold, double borderlineDelta) {
        this.embed = embed;
        this.threshold = threshold;
        this.centroid = mean(anchors.stream().map(a -> embed.embed(a).content().vector()).toList());
    }

    public Result filter(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> kept = new ArrayList<>();
        List<float[]> keptVecs = new ArrayList<>();
        int dropped = 0;
        for (RetrievedChunk c : chunks) {
            float[] v = embed.embed(c.text()).content().vector();
            double rel = cosine(v, centroid);
            if (rel < threshold) { dropped++; continue; }              // 剔垃圾
            boolean dup = keptVecs.stream().anyMatch(kv -> cosine(kv, v) >= dedupSim);
            if (dup) { dropped++; continue; }                          // 去重
            kept.add(new RetrievedChunk(c.id(), c.sourceUri(), c.text(), rel));
            keptVecs.add(v);
        }
        return new Result(kept, dropped);
    }

    private static float[] mean(List<float[]> vs) {
        float[] m = new float[vs.get(0).length];
        for (float[] v : vs) for (int i = 0; i < v.length; i++) m[i] += v[i];
        for (int i = 0; i < m.length; i++) m[i] /= vs.size();
        return m;
    }
    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        return dot / (Math.sqrt(na)*Math.sqrt(nb) + 1e-9);
    }
}
```
> `borderlineDelta`（τ±δ 的轻量 LLM 复判）在本任务先不接线，留接口位；§开放问题在实现时若需要再加一步 LLM 二分类。**为避免占位符，本版明确不做边界复判，靠阈值单闸**——足够通过评测集；如评测 precision 不达标再补。

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l2tools/RelevanceFilter.java src/test/java/com/harnesslearn/agent/l2tools/RelevanceFilterTest.java
git commit -m "feat(l2): RelevanceFilter 相关性闸门+去重"
```

---

### 任务 10：三个工具（local_retrieve / web_search / fetch_page）

**文件：** 创建 `l2tools/tools/LocalRetrieveTool.java`、`WebSearchTool.java`、`FetchPageTool.java`；测试 `.../l2tools/tools/FetchPageToolTest.java`、`LocalRetrieveToolTest.java`

- [ ] **步骤 1：写 `LocalRetrieveTool` 失败测试**（复用 LongTermMemory）

```java
package com.harnesslearn.agent.l2tools.tools;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalRetrieveToolTest {
    @Test
    void returnsRetrievedTextAsJson() {
        LongTermMemory mem = (query, k) ->
            List.of(new RetrievedChunk("c1","doc1","上下文裁剪要点",0.8));
        var tool = new LocalRetrieveTool(mem);
        ToolResult r = tool.execute(new ToolCall("1","local_retrieve","{\"query\":\"裁剪\",\"k\":3}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.rawContent()).contains("上下文裁剪要点").contains("doc1");
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现三个工具**

`LocalRetrieveTool.java`：
```java
package com.harnesslearn.agent.l2tools.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l2tools.Tool;
import com.harnesslearn.agent.l4memory.LongTermMemory;

public class LocalRetrieveTool implements Tool {
    private final LongTermMemory memory;
    private final ObjectMapper mapper = new ObjectMapper();
    public LocalRetrieveTool(LongTermMemory memory) { this.memory = memory; }
    public String name() { return "local_retrieve"; }
    public String description() { return "在本地知识库中向量检索 agent harness 资料。参数: {query, k}"; }
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode a = mapper.readTree(call.argumentsJson());
            int k = a.has("k") ? a.get("k").asInt() : 5;
            var hits = memory.retrieve(a.get("query").asText(), k);
            return ToolResult.ok(mapper.writeValueAsString(hits));
        } catch (Exception e) { return ToolResult.fail("local_retrieve 失败: " + e.getMessage()); }
    }
}
```

`FetchPageTool.java`（Jsoup 抽正文，剥离脚本/样式/导航）：
```java
package com.harnesslearn.agent.l2tools.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l2tools.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class FetchPageTool implements Tool {
    private final ObjectMapper mapper = new ObjectMapper();
    public String name() { return "fetch_page"; }
    public String description() { return "抓取网页并抽取正文。参数: {url}"; }
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode a = mapper.readTree(call.argumentsJson());
            Document doc = Jsoup.connect(a.get("url").asText())
                .userAgent("Mozilla/5.0").timeout(15000).get();
            doc.select("script,style,nav,footer,header,aside").remove();
            String text = doc.body().text();
            return ToolResult.ok(text.length() > 8000 ? text.substring(0, 8000) : text);
        } catch (Exception e) { return ToolResult.fail("fetch_page 失败: " + e.getMessage()); }
    }
}
```

`WebSearchTool.java`（抽象搜索后端；默认实现打 Tavily，缺 key 时返回失败让 L6 降级）：
```java
package com.harnesslearn.agent.l2tools.tools;

import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import com.harnesslearn.agent.l2tools.Tool;

public class WebSearchTool implements Tool {
    public interface SearchBackend { String search(String query) throws Exception; }
    private final SearchBackend backend;
    public WebSearchTool(SearchBackend backend) { this.backend = backend; }
    public String name() { return "web_search"; }
    public String description() { return "联网搜索 agent harness 相关资料，返回候选 URL 与摘要。参数: {query}"; }
    public ToolResult execute(ToolCall call) {
        try {
            com.fasterxml.jackson.databind.JsonNode a =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.argumentsJson());
            return ToolResult.ok(backend.search(a.get("query").asText()));
        } catch (Exception e) { return ToolResult.fail("web_search 失败: " + e.getMessage()); }
    }
}
```
> `SearchBackend` 的 Tavily/SearXNG 具体实现作为一个 `@Component` 在装配任务（任务 20 的 wiring）里提供；测试中用 lambda 注入假后端。**接口在此定义，实现推迟到装配，不构成占位符。**

- [ ] **步骤 4：写 `FetchPageTool` 测试并运行全部通过**

```java
package com.harnesslearn.agent.l2tools.tools;

import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FetchPageToolTest {
    @Test
    void failsGracefullyOnBadUrl() {
        ToolResult r = new FetchPageTool().execute(new ToolCall("1","fetch_page","{\"url\":\"http://invalid.invalid\"}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("fetch_page 失败");
    }
}
```
运行：`mvn -q -Dtest=LocalRetrieveToolTest,FetchPageToolTest test` → 预期 PASS。

- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l2tools/tools src/test/java/com/harnesslearn/agent/l2tools/tools
git commit -m "feat(l2): local_retrieve / web_search / fetch_page 工具"
```

---

### 任务 11：L2ToolSystem（执行 + 提炼 + 过滤）

**文件：** 创建 `l2tools/L2ToolSystem.java`、`l2tools/DefaultL2ToolSystem.java`；测试 `.../l2tools/DefaultL2ToolSystemTest.java`

- [ ] **步骤 1：写失败测试**（喂含垃圾的检索结果 → 断言被过滤）

```java
package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.*;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultL2ToolSystemTest {
    @Test
    void invokeDistillsAndFiltersGarbage() {
        // local_retrieve 返回 2 条：1 条相关 + 1 条垃圾
        Tool retrieve = new Tool() {
            public String name() { return "local_retrieve"; }
            public String description() { return "d"; }
            public ToolResult execute(ToolCall c) {
                return ToolResult.ok("""
                    [{"id":"a","sourceUri":"u1","text":"agent 上下文工程与工具编排","relevanceScore":0},
                     {"id":"b","sourceUri":"u2","text":"红烧肉的家常做法","relevanceScore":0}]""");
            }
        };
        var embed = new BgeSmallZhV15QuantizedEmbeddingModel();
        var filter = new RelevanceFilter(embed, List.of("AI agent 脚手架与上下文工程"), 0.35, 0.05);
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(retrieve)), filter);

        DistilledResult res = l2.invoke(new ToolCall("1","local_retrieve","{\"query\":\"x\"}"));
        assertThat(res.chunks()).extracting(RetrievedChunk::id).containsExactly("a");
        assertThat(res.droppedCount()).isEqualTo(1);
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`L2ToolSystem.java`：
```java
package com.harnesslearn.agent.l2tools;
import com.harnesslearn.agent.domain.DistilledResult;
import com.harnesslearn.agent.domain.ToolCall;
import java.util.List;
public interface L2ToolSystem {
    List<String> availableTools();
    DistilledResult invoke(ToolCall call);
}
```

`DefaultL2ToolSystem.java`（对返回结构化 chunks 的工具做过滤；对纯文本工具如 fetch_page 包成单块再过滤）：
```java
package com.harnesslearn.agent.l2tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import java.util.List;
import java.util.UUID;

public class DefaultL2ToolSystem implements L2ToolSystem {
    private final ToolRegistry registry;
    private final RelevanceFilter filter;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultL2ToolSystem(ToolRegistry registry, RelevanceFilter filter) {
        this.registry = registry; this.filter = filter;
    }

    @Override public List<String> availableTools() { return registry.names(); }

    @Override
    public DistilledResult invoke(ToolCall call) {
        ToolResult raw = registry.get(call.name()).execute(call);
        if (!raw.ok()) return new DistilledResult(List.of(), 0, "工具失败: " + raw.error());
        List<RetrievedChunk> chunks = toChunks(raw.rawContent());
        RelevanceFilter.Result f = filter.filter(chunks);
        return new DistilledResult(f.kept(), f.droppedCount(),
            "保留 %d 块，过滤 %d 块".formatted(f.kept().size(), f.droppedCount()));
    }

    private List<RetrievedChunk> toChunks(String content) {
        try {
            // 结构化 chunk 列表（local_retrieve）
            return List.of(mapper.readValue(content, RetrievedChunk[].class));
        } catch (Exception ignore) {
            // 纯文本（fetch_page / web_search）→ 单块
            return List.of(new RetrievedChunk(UUID.randomUUID().toString(), "inline", content, 0));
        }
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l2tools/L2ToolSystem.java src/main/java/com/harnesslearn/agent/l2tools/DefaultL2ToolSystem.java src/test/java/com/harnesslearn/agent/l2tools/DefaultL2ToolSystemTest.java
git commit -m "feat(l2): L2ToolSystem 执行+提炼+相关性过滤"
```

---

## 阶段 4：L5 评估

### 任务 12：L5Evaluator（独立验证）

**文件：** 创建 `l5eval/L5Evaluator.java`、`l5eval/LlmL5Evaluator.java`；测试 `.../l5eval/LlmL5EvaluatorTest.java`

- [ ] **步骤 1：写失败测试**（FakeChatModel 返回一个 critic JSON）

```java
package com.harnesslearn.agent.l5eval;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LlmL5EvaluatorTest {
    @Test
    void parsesVerdictFromCriticJson() {
        var fake = FakeChatModel.scripted(AiMessage.from("""
            {"pass": false, "confidence": 0.6,
             "issues": [{"dimension":"grounding","detail":"论断2缺来源"}]}"""));
        var evaluator = new LlmL5Evaluator(fake);
        Verdict v = evaluator.verify(
            new TaskSpec("run1", TaskType.SURVEY, "综述", java.util.Map.of()),
            new AgentOutput("正文…", List.of()),
            List.of(new Artifact("a","run1","summary","k","证据…", java.util.Map.of())));
        assertThat(v.pass()).isFalse();
        assertThat(v.confidence()).isEqualTo(0.6);
        assertThat(v.issues()).extracting(Issue::dimension).containsExactly("grounding");
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`L5Evaluator.java`：
```java
package com.harnesslearn.agent.l5eval;
import com.harnesslearn.agent.domain.*;
import java.util.List;
public interface L5Evaluator {
    Verdict verify(TaskSpec task, AgentOutput output, List<Artifact> evidence);
}
```

`LlmL5Evaluator.java`（独立一次调用，critic 人格，只看产出+证据）：
```java
package com.harnesslearn.agent.l5eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.ArrayList;
import java.util.List;

public class LlmL5Evaluator implements L5Evaluator {
    private static final String CRITIC = """
        你是独立审查员，不参与生成。只依据【产出】和【证据】判断，忽略任何生成过程。
        从四个维度审查：grounding(有据可查)、completeness(完整性)、relevance(相关性)、format(格式契约)。
        只输出 JSON: {"pass":bool,"confidence":0~1,"issues":[{"dimension":..,"detail":..}]}""";
    private final ChatLanguageModel model;
    private final ObjectMapper mapper = new ObjectMapper();
    public LlmL5Evaluator(ChatLanguageModel model) { this.model = model; }

    @Override
    public Verdict verify(TaskSpec task, AgentOutput output, List<Artifact> evidence) {
        String evidenceText = evidence.stream().map(Artifact::content).reduce("", (a,b) -> a + "\n---\n" + b);
        String prompt = "任务: " + task.userQuery() + "\n\n【产出】\n" + output.content()
            + "\n\n【证据】\n" + evidenceText;
        String json = model.generate(List.of(SystemMessage.from(CRITIC), UserMessage.from(prompt)),
            List.of()).content().text();
        return parse(json);
    }

    private Verdict parse(String json) {
        try {
            JsonNode n = mapper.readTree(extractJson(json));
            List<Issue> issues = new ArrayList<>();
            if (n.has("issues")) for (JsonNode i : n.get("issues"))
                issues.add(new Issue(i.get("dimension").asText(), i.get("detail").asText()));
            return new Verdict(n.get("pass").asBoolean(), issues,
                n.has("confidence") ? n.get("confidence").asDouble() : 0.5);
        } catch (Exception e) {
            // 解析失败保守判为不通过，交给 L6
            return new Verdict(false, List.of(new Issue("format","验证器输出无法解析")), 0.0);
        }
    }
    private String extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l5eval src/test/java/com/harnesslearn/agent/l5eval
git commit -m "feat(l5): 独立验证器 L5Evaluator"
```

---

## 阶段 5：L6 约束与恢复

### 任务 13：RecoveryPolicy（失败→策略映射）

**文件：** 创建 `l6guardrail/RecoveryPolicy.java`；测试 `.../l6guardrail/RecoveryPolicyTest.java`

- [ ] **步骤 1：写失败测试**（参数化几种失败类型）

```java
package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPolicyTest {
    private final RecoveryPolicy policy = new RecoveryPolicy(2 /*maxRetries*/);

    @Test
    void webSearchFailureDegrades() {
        var d = policy.decide(new FailureContext("web_search_failed", 1, ""));
        assertThat(d.strategy()).isEqualTo(RecoveryStrategy.DEGRADE);
    }
    @Test
    void invalidOutputRetriesThenAborts() {
        assertThat(policy.decide(new FailureContext("invalid_output",1,"")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
        assertThat(policy.decide(new FailureContext("invalid_output",3,"")).strategy())
            .isEqualTo(RecoveryStrategy.ABORT);
    }
    @Test
    void verificationFailedRollsBackAfterMaxRetries() {
        assertThat(policy.decide(new FailureContext("verification_failed",1,"")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
        assertThat(policy.decide(new FailureContext("verification_failed",3,"")).strategy())
            .isEqualTo(RecoveryStrategy.ROLLBACK);
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

```java
package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.FailureContext;
import com.harnesslearn.agent.domain.RecoveryDecision;
import com.harnesslearn.agent.domain.RecoveryStrategy;

/** 确定性的 失败类型 → 恢复策略 映射引擎。 */
public class RecoveryPolicy {
    private final int maxRetries;
    public RecoveryPolicy(int maxRetries) { this.maxRetries = maxRetries; }

    public RecoveryDecision decide(FailureContext ctx) {
        boolean exhausted = ctx.attempt() > maxRetries;
        return switch (ctx.failureType()) {
            case "web_search_failed", "fetch_page_failed", "evidence_insufficient", "subagent_failed"
                -> new RecoveryDecision(RecoveryStrategy.DEGRADE, "降级：改用本地/父Agent兜底");
            case "invalid_output", "invalid_tool_args"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.ABORT, "重试超限")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "带错误反馈重试");
            case "verification_failed"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.ROLLBACK, "回滚 checkpoint")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "issues 回灌重试");
            case "llm_call_failed"
                -> exhausted ? new RecoveryDecision(RecoveryStrategy.DEGRADE, "降级本地语料")
                             : new RecoveryDecision(RecoveryStrategy.RETRY, "指数退避重试");
            case "budget_exhausted", "loop_detected"
                -> new RecoveryDecision(RecoveryStrategy.ABORT, "强制收尾");
            default -> new RecoveryDecision(RecoveryStrategy.ABORT, "未知失败: " + ctx.failureType());
        };
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l6guardrail/RecoveryPolicy.java src/test/java/com/harnesslearn/agent/l6guardrail/RecoveryPolicyTest.java
git commit -m "feat(l6): RecoveryPolicy 失败→策略映射引擎"
```

---

### 任务 14：L6Guardrail（校验 + 恢复入口）

**文件：** 创建 `l6guardrail/L6Guardrail.java`、`l6guardrail/DefaultL6Guardrail.java`；测试 `.../l6guardrail/DefaultL6GuardrailTest.java`

- [ ] **步骤 1：写失败测试**

```java
package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultL6GuardrailTest {
    private final L6Guardrail g = new DefaultL6Guardrail(new RecoveryPolicy(2));

    @Test
    void rejectsUnknownToolArgs() {
        ValidationResult r = g.validateAction(new ToolCall("1","local_retrieve","not-json"));
        assertThat(r.valid()).isFalse();
    }
    @Test
    void rejectsEmptyOutput() {
        assertThat(g.validateOutput(new AgentOutput("   ", java.util.List.of())).valid()).isFalse();
    }
    @Test
    void routesFailureToPolicy() {
        RecoveryDecision d = g.onFailure(new FailureContext("verification_failed",1,""));
        assertThat(d.strategy()).isEqualTo(RecoveryStrategy.RETRY);
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`L6Guardrail.java`：
```java
package com.harnesslearn.agent.l6guardrail;
import com.harnesslearn.agent.domain.*;
public interface L6Guardrail {
    ValidationResult validateAction(ToolCall call);
    ValidationResult validateOutput(AgentOutput output);
    RecoveryDecision onFailure(FailureContext ctx);
}
```

`DefaultL6Guardrail.java`：
```java
package com.harnesslearn.agent.l6guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;

public class DefaultL6Guardrail implements L6Guardrail {
    private final RecoveryPolicy policy;
    private final ObjectMapper mapper = new ObjectMapper();
    public DefaultL6Guardrail(RecoveryPolicy policy) { this.policy = policy; }

    @Override
    public ValidationResult validateAction(ToolCall call) {
        if (call.name() == null || call.name().isBlank())
            return ValidationResult.invalid("工具名为空");
        try { mapper.readTree(call.argumentsJson()); }
        catch (Exception e) { return ValidationResult.invalid("工具参数非合法 JSON"); }
        return ValidationResult.ok();
    }

    @Override
    public ValidationResult validateOutput(AgentOutput output) {
        if (output == null || output.content() == null || output.content().isBlank())
            return ValidationResult.invalid("产出为空");
        return ValidationResult.ok();
    }

    @Override
    public RecoveryDecision onFailure(FailureContext ctx) { return policy.decide(ctx); }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l6guardrail/L6Guardrail.java src/main/java/com/harnesslearn/agent/l6guardrail/DefaultL6Guardrail.java src/test/java/com/harnesslearn/agent/l6guardrail/DefaultL6GuardrailTest.java
git commit -m "feat(l6): L6Guardrail 动作/产出校验 + 恢复入口"
```

---

## 阶段 6：L3 执行编排（Agent 心脏）

### 任务 15：AgentLoop 手写自主循环

**文件：** 创建 `l3orchestrate/L3Orchestrator.java`、`l3orchestrate/AgentLoop.java`、`l3orchestrate/ModelStepParser.java`；测试 `.../l3orchestrate/AgentLoopTest.java`

**约定（模型输出协议）：** 模型每步输出 JSON：
`{"thought":"...","action":"tool","tool":{"name":"local_retrieve","arguments":{...}}}`
或 `{"thought":"...","action":"final","answer":"..."}`。由 `ModelStepParser` 解析为 `ModelStep`。

- [ ] **步骤 1：写失败测试**（FakeChatModel 编排：先调工具，再 finish）

```java
package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l2tools.*;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l6guardrail.*;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {
    @Test
    void runsToolThenFinishesAndVerifies() {
        // 第1步：模型决定调 local_retrieve；第2步：模型给出 final
        var fake = FakeChatModel.scripted(
            AiMessage.from("""
                {"thought":"先检索","action":"tool",
                 "tool":{"name":"local_retrieve","arguments":{"query":"上下文工程"}}}"""),
            AiMessage.from("""
                {"thought":"信息足够","action":"final","answer":"综述正文：上下文工程要点…"}"""));

        // L2 桩：返回一条相关 chunk（不接真实过滤，用放行过滤）
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) {
                return new DistilledResult(List.of(
                    new RetrievedChunk("a","u1","上下文工程要点",0.9)), 0, "ok");
            }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 0.9);   // 直接通过
        L6Guardrail l6 = new DefaultL6Guardrail(new RecoveryPolicy(2));

        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5, l6, 10);
        AgentRun run = loop.run(new TaskSpec("run1", TaskType.SURVEY, "综述上下文工程", java.util.Map.of()));

        assertThat(run.success()).isTrue();
        assertThat(run.output().content()).contains("综述正文");
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void abortsWhenBudgetExhausted() {
        // 模型永远只调工具，从不 finish → 撞上步数上限
        AiMessage toolStep = AiMessage.from("""
            {"thought":"再查","action":"tool",
             "tool":{"name":"local_retrieve","arguments":{"query":"x"}}}""");
        var fake = FakeChatModel.scripted(toolStep, toolStep, toolStep, toolStep);
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) { return new DistilledResult(List.of(), 0, "ok"); }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 1.0);
        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5,
            new DefaultL6Guardrail(new RecoveryPolicy(2)), 3);   // maxSteps=3
        AgentRun run = loop.run(new TaskSpec("run2", TaskType.QA, "q", java.util.Map.of()));
        assertThat(run.terminationReason()).contains("budget");
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现 `ModelStepParser` 与 `AgentLoop`**

`ModelStepParser.java`：
```java
package com.harnesslearn.agent.l3orchestrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.ModelStep;
import com.harnesslearn.agent.domain.ToolCall;
import java.util.List;
import java.util.UUID;

public class ModelStepParser {
    private final ObjectMapper mapper = new ObjectMapper();
    public ModelStep parse(String raw) {
        try {
            JsonNode n = mapper.readTree(extract(raw));
            String thought = n.path("thought").asText("");
            if ("final".equals(n.path("action").asText())) {
                return new ModelStep(thought, List.of(), n.path("answer").asText(""));
            }
            JsonNode tool = n.get("tool");
            ToolCall call = new ToolCall(UUID.randomUUID().toString(),
                tool.get("name").asText(), tool.get("arguments").toString());
            return new ModelStep(thought, List.of(call), null);
        } catch (Exception e) {
            // 无法解析→当作需要重试的非法输出（finalAnswer=null 且无工具）
            return new ModelStep("PARSE_ERROR:" + e.getMessage(), List.of(), null);
        }
    }
    private String extract(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
}
```

`L3Orchestrator.java`：
```java
package com.harnesslearn.agent.l3orchestrate;
import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;
public interface L3Orchestrator { AgentRun run(TaskSpec task); }
```

`AgentLoop.java`（手写循环；软轨道靠 L1 任务状态提示，硬约束靠 maxSteps + 必过 L5）：
```java
package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.L1ContextAssembler;
import com.harnesslearn.agent.l2tools.L2ToolSystem;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l6guardrail.L6Guardrail;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.ArrayList;
import java.util.List;

public class AgentLoop implements L3Orchestrator {
    private final ChatLanguageModel model;
    private final L1ContextAssembler l1;
    private final L2ToolSystem l2;
    private final L5Evaluator l5;
    private final L6Guardrail l6;
    private final int maxSteps;
    private final ModelStepParser parser = new ModelStepParser();

    public AgentLoop(ChatLanguageModel model, L1ContextAssembler l1, L2ToolSystem l2,
                     L5Evaluator l5, L6Guardrail l6, int maxSteps) {
        this.model = model; this.l1 = l1; this.l2 = l2; this.l5 = l5; this.l6 = l6; this.maxSteps = maxSteps;
    }

    @Override
    public AgentRun run(TaskSpec task) {
        WorkingState state = WorkingState.start(task.runId(), task.userQuery(), maxSteps);
        List<RetrievedChunk> gathered = new ArrayList<>();
        List<Artifact> evidence = new ArrayList<>();

        while (!state.budgetExhausted()) {
            AssembledContext ctx = l1.assemble(task, state, gathered);
            String rawResp = model.generate(ctx.messages(), List.of()).content().text();
            ModelStep step = parser.parse(rawResp);

            if (step.isFinish()) {
                AgentOutput output = new AgentOutput(step.finalAnswer(), List.copyOf(evidence));
                if (!l6.validateOutput(output).valid()) {
                    state.recordStep("产出为空，重试");
                    continue;
                }
                Verdict v = l5.verify(task, output, evidence);   // 硬约束：finish 必过 L5
                if (v.pass()) return new AgentRun(task.runId(), output, true, "completed");
                RecoveryDecision d = l6.onFailure(new FailureContext("verification_failed",
                    state.stepsUsed(), v.issues().toString()));
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
                state.recordStep("调用 " + call.name() + "：" + dr.note());
                continue;
            }

            // 既非 finish 也无工具 = 非法/解析失败
            RecoveryDecision d = l6.onFailure(new FailureContext("invalid_output", state.stepsUsed(), step.thought()));
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
```

- [ ] **步骤 4：运行验证通过** → `mvn -q -Dtest=AgentLoopTest test`，预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/l3orchestrate src/test/java/com/harnesslearn/agent/l3orchestrate
git commit -m "feat(l3): 手写自主 Agent 循环（软轨道+L6硬约束）"
```

---

## 阶段 7：子 Agent

### 任务 16：SubAgent 接口 + 并行 Dispatcher

**文件：** 创建 `subagent/SubAgent.java`、`subagent/SubAgentDispatcher.java`、`subagent/DefaultSubAgentDispatcher.java`；测试 `.../subagent/DefaultSubAgentDispatcherTest.java`

- [ ] **步骤 1：写失败测试**（并行 + 失败降级）

```java
package com.harnesslearn.agent.subagent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSubAgentDispatcherTest {
    static class UpperAgent implements SubAgent<String,String> {
        public String name() { return "upper"; }
        public String run(String in) {
            if (in.equals("boom")) throw new RuntimeException("fail");
            return in.toUpperCase();
        }
    }

    @Test
    void dispatchParallelCollectsResultsAndDegradesOnFailure() {
        var dispatcher = new DefaultSubAgentDispatcher();
        var agent = new UpperAgent();
        List<String> out = dispatcher.dispatchParallel(agent, List.of("a","boom","c"), "FALLBACK");
        assertThat(out).containsExactly("A","FALLBACK","C");   // 失败项降级为 fallback
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`SubAgent.java`：
```java
package com.harnesslearn.agent.subagent;
public interface SubAgent<I, O> {
    String name();
    O run(I input);
}
```

`SubAgentDispatcher.java`：
```java
package com.harnesslearn.agent.subagent;
import java.util.List;
public interface SubAgentDispatcher {
    <I,O> O dispatch(SubAgent<I,O> agent, I input, O fallback);
    <I,O> List<O> dispatchParallel(SubAgent<I,O> agent, List<I> inputs, O fallback);
}
```

`DefaultSubAgentDispatcher.java`（虚拟线程并行；单个失败降级为 fallback，父 Agent 兜底）：
```java
package com.harnesslearn.agent.subagent;

import java.util.List;
import java.util.concurrent.*;

public class DefaultSubAgentDispatcher implements SubAgentDispatcher {
    @Override
    public <I,O> O dispatch(SubAgent<I,O> agent, I input, O fallback) {
        try { return agent.run(input); }
        catch (Exception e) { return fallback; }
    }
    @Override
    public <I,O> List<O> dispatchParallel(SubAgent<I,O> agent, List<I> inputs, O fallback) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<O>> futures = inputs.stream()
                .map(in -> exec.submit(() -> agent.run(in))).toList();
            return futures.stream().map(f -> {
                try { return f.get(); } catch (Exception e) { return fallback; }
            }).toList();
        }
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/subagent/SubAgent.java src/main/java/com/harnesslearn/agent/subagent/SubAgentDispatcher.java src/main/java/com/harnesslearn/agent/subagent/DefaultSubAgentDispatcher.java src/test/java/com/harnesslearn/agent/subagent/DefaultSubAgentDispatcherTest.java
git commit -m "feat(subagent): SubAgent 接口 + 虚拟线程并行 Dispatcher + 失败降级"
```

---

### 任务 17：SourceSummarizer 子 Agent

**文件：** 创建 `subagent/SourceSummarizer.java`；测试 `.../subagent/SourceSummarizerTest.java`

- [ ] **步骤 1：写失败测试**（FakeChatModel 返回摘要）

```java
package com.harnesslearn.agent.subagent;

import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceSummarizerTest {
    @Test
    void summarizesSourceIntoBulletPoints() {
        var fake = FakeChatModel.scripted(AiMessage.from("- 要点1\n- 要点2"));
        var agent = new SourceSummarizer(fake);
        String summary = agent.run(new SourceSummarizer.Input("http://x", "一大段原文……"));
        assertThat(summary).contains("要点1").contains("要点2");
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

```java
package com.harnesslearn.agent.subagent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.List;

/** 有界子任务：把单个来源压成结构化要点，主 Agent 只看摘要（上下文隔离）。 */
public class SourceSummarizer implements SubAgent<SourceSummarizer.Input, String> {
    public record Input(String sourceUri, String rawText) {}
    private static final String SYS = """
        你只做一件事：把给定来源压缩成 3~6 条与 AI agent harness 相关的要点，
        每条尽量可引用。忽略无关内容。只输出要点列表。""";
    private final ChatLanguageModel model;
    public SourceSummarizer(ChatLanguageModel model) { this.model = model; }
    public String name() { return "source_summarizer"; }
    public String run(Input in) {
        return model.generate(List.of(SystemMessage.from(SYS),
            UserMessage.from("来源: " + in.sourceUri() + "\n正文:\n" + in.rawText())),
            List.of()).content().text();
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/subagent/SourceSummarizer.java src/test/java/com/harnesslearn/agent/subagent/SourceSummarizerTest.java
git commit -m "feat(subagent): SourceSummarizer 逐来源摘要子 Agent"
```

> 注：`SectionWriter` 与 `SourceSummarizer` 结构同构（输入=大纲节+证据，输出=草稿节），实现时照此模式新增，含独立失败测试。若综述 MVP 暂不需要分节写作，按 YAGNI 可延后到需要时再加——本计划不强制。

---

## 阶段 8：可观测性

### 任务 18：LoggingChatModelListener（每次 LLM 调用落盘）

**文件：** 创建 `observability/LoggingChatModelListener.java`；创建 `src/main/resources/logback-spring.xml`；测试 `.../observability/LoggingChatModelListenerTest.java`

- [ ] **步骤 1：写失败测试**（监听器把请求/响应/token 记入注入的 sink）

```java
package com.harnesslearn.agent.observability;

import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LoggingChatModelListenerTest {
    @Test
    void recordsRequestAndResponse() {
        List<String> sink = new ArrayList<>();
        var listener = new LoggingChatModelListener(sink::add);
        listener.onRequest(new ChatModelRequestContext(
            ChatModelRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
            new java.util.concurrent.ConcurrentHashMap<>()));
        listener.onResponse(new ChatModelResponseContext(
            ChatModelResponse.builder().aiMessage(AiMessage.from("hello")).build(),
            ChatModelRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
            new java.util.concurrent.ConcurrentHashMap<>()));
        assertThat(sink).anyMatch(s -> s.contains("REQUEST"));
        assertThat(sink).anyMatch(s -> s.contains("RESPONSE") && s.contains("hello"));
    }
}
```
> 说明：`ChatModelRequest/Response/Context` 为 langchain4j 0.35.0 的监听 API；如该版本类名略有差异，以 `dev.langchain4j.model.chat.listener` 包实际类型为准调整（仅影响本任务）。

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现监听器 + logback 配置**

`LoggingChatModelListener.java`：
```java
package com.harnesslearn.agent.observability;

import dev.langchain4j.model.chat.listener.*;
import java.util.function.Consumer;

/** 每次 LLM 调用的请求/响应/错误全量落盘（"把日志输出全"的核心）。 */
public class LoggingChatModelListener implements ChatModelListener {
    private final Consumer<String> sink;
    public LoggingChatModelListener(Consumer<String> sink) { this.sink = sink; }

    @Override public void onRequest(ChatModelRequestContext ctx) {
        sink.accept("LLM REQUEST msgs=" + ctx.request().messages().size());
    }
    @Override public void onResponse(ChatModelResponseContext ctx) {
        var r = ctx.response();
        sink.accept("LLM RESPONSE text=" + r.aiMessage().text()
            + " tokens=" + (r.tokenUsage() == null ? "?" : r.tokenUsage().totalTokenCount()));
    }
    @Override public void onError(ChatModelErrorContext ctx) {
        sink.accept("LLM ERROR " + ctx.error().getMessage());
    }
}
```
默认注入 `sink = msg -> LoggerFactory.getLogger("llm").info(msg)`（在装配任务里接线）。

`logback-spring.xml`：
```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  <root level="INFO"><appender-ref ref="JSON"/></root>
</configuration>
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/observability/LoggingChatModelListener.java src/main/resources/logback-spring.xml src/test/java/com/harnesslearn/agent/observability/LoggingChatModelListenerTest.java
git commit -m "feat(obs): LLM 调用级全量日志监听器 + JSON 日志"
```

---

### 任务 19：AgentTrace 落盘

**文件：** 创建 `observability/TraceStep.java`、`observability/AgentTrace.java`、`observability/TraceStore.java`、`observability/SqliteTraceStore.java`；测试 `.../observability/SqliteTraceStoreTest.java`

- [ ] **步骤 1：写失败测试**

```java
package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteTraceStoreTest {
    @Test
    void appendsAndReadsStepsInOrder() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:file:memTrace?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        var store = new SqliteTraceStore(jt);
        store.append(new TraceStep("run1",0,"L3","model_step","决定调工具"));
        store.append(new TraceStep("run1",1,"L2","tool_invoke","local_retrieve 保留2块"));
        List<TraceStep> steps = store.load("run1");
        assertThat(steps).extracting(TraceStep::layer).containsExactly("L3","L2");
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现**

`TraceStep.java`：
```java
package com.harnesslearn.agent.observability;
public record TraceStep(String runId, int seq, String layer, String event, String detail) {}
```
`AgentTrace.java`：
```java
package com.harnesslearn.agent.observability;
import java.util.List;
public record AgentTrace(String runId, List<TraceStep> steps) {}
```
`TraceStore.java`：
```java
package com.harnesslearn.agent.observability;
import java.util.List;
public interface TraceStore {
    void append(TraceStep step);
    List<TraceStep> load(String runId);
}
```
`SqliteTraceStore.java`：
```java
package com.harnesslearn.agent.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

public class SqliteTraceStore implements TraceStore {
    private final JdbcTemplate jdbc;
    public SqliteTraceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void append(TraceStep s) {
        jdbc.update("INSERT INTO trace_step(id,run_id,seq,layer,event,detail,ts) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), s.runId(), s.seq(), s.layer(), s.event(), s.detail(),
            System.currentTimeMillis());
    }
    @Override public List<TraceStep> load(String runId) {
        return jdbc.query("SELECT run_id,seq,layer,event,detail FROM trace_step WHERE run_id=? ORDER BY seq",
            (rs,n) -> new TraceStep(rs.getString("run_id"), rs.getInt("seq"),
                rs.getString("layer"), rs.getString("event"), rs.getString("detail")), runId);
    }
}
```

- [ ] **步骤 4：运行验证通过** → 预期 PASS。

- [ ] **步骤 5：接线 AgentLoop 记录 trace（改造 + 回归测试），并 Commit**

在 `AgentLoop` 构造函数注入 `TraceStore`（可为 no-op 默认），在每步 `model_step`/`tool_invoke`/`verdict`/`recovery` 处 `traceStore.append(...)`。补一条断言 trace 非空的循环测试。
```bash
git add src/main/java/com/harnesslearn/agent/observability src/main/java/com/harnesslearn/agent/l3orchestrate/AgentLoop.java src/test/java/com/harnesslearn/agent/observability
git commit -m "feat(obs): AgentTrace 落盘 + AgentLoop 全步骤埋点"
```

---

## 阶段 9：API + 端到端集成

### 任务 20：装配（Wiring）+ REST/SSE 控制器

**文件：** 创建 `l2tools/ToolsConfig.java`（装配工具与搜索后端）、`AgentConfig.java`（装配各层 bean）、`api/AgentController.java`、`api/dto/RunRequest.java`；测试 `.../api/AgentControllerTest.java`（`@WebMvcTest` 或 `@SpringBootTest`，用 FakeChatModel 覆盖 bean）

- [ ] **步骤 1：写失败测试**（POST /runs 返回结果）

```java
package com.harnesslearn.agent.api;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {
    @Autowired MockMvc mvc;
    @MockBean L3Orchestrator orchestrator;

    @Test
    void runReturnsOutput() throws Exception {
        when(orchestrator.run(any())).thenReturn(new AgentRun("run1",
            new AgentOutput("综述结果", List.of()), true, "completed"));
        mvc.perform(post("/runs").contentType("application/json")
                .content("{\"type\":\"SURVEY\",\"query\":\"综述上下文工程\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.output").value("综述结果"))
           .andExpect(jsonPath("$.success").value(true));
    }
}
```

- [ ] **步骤 2：运行验证失败** → 预期编译失败。

- [ ] **步骤 3：实现 DTO、控制器、装配**

`api/dto/RunRequest.java`：
```java
package com.harnesslearn.agent.api.dto;
public record RunRequest(String type, String query) {}
```
`api/AgentController.java`：
```java
package com.harnesslearn.agent.api;

import com.harnesslearn.agent.api.dto.RunRequest;
import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
public class AgentController {
    private final L3Orchestrator orchestrator;
    public AgentController(L3Orchestrator orchestrator) { this.orchestrator = orchestrator; }

    @PostMapping("/runs")
    public Map<String,Object> run(@RequestBody RunRequest req) {
        TaskSpec task = new TaskSpec(UUID.randomUUID().toString(),
            TaskType.valueOf(req.type()), req.query(), Map.of());
        AgentRun run = orchestrator.run(task);
        return Map.of("runId", run.runId(), "success", run.success(),
            "output", run.output().content(), "termination", run.terminationReason());
    }
}
```
`AgentConfig.java`：把 `JdbcTemplate`、各 store、L1/L2/L5/L6、`AgentLoop`（作为 `L3Orchestrator` bean）、`RelevanceFilter`（anchors 从种子语料或常量）、`ToolRegistry` 全部装配为 Spring bean，并给 `ChatLanguageModel` 注册 `LoggingChatModelListener`。`ToolsConfig` 提供 `WebSearchTool.SearchBackend`（Tavily HTTP 实现；无 key 时抛异常触发降级）。

- [ ] **步骤 4：运行验证通过** → 预期 PASS。
- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/api src/main/java/com/harnesslearn/agent/AgentConfig.java src/main/java/com/harnesslearn/agent/l2tools/ToolsConfig.java src/test/java/com/harnesslearn/agent/api
git commit -m "feat(api): /runs 控制器 + 全层 Spring 装配"
```

---

### 任务 21：端到端集成测试（综述全流程）

**文件：** 测试 `src/test/java/com/harnesslearn/agent/IntegrationSurveyTest.java`

- [ ] **步骤 1：写集成测试**（FakeChatModel 全脚本：检索→摘要→final→L5 通过；断言 trace 完整）

```java
package com.harnesslearn.agent;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l2tools.*;
import com.harnesslearn.agent.l3orchestrate.AgentLoop;
import com.harnesslearn.agent.l5eval.LlmL5Evaluator;
import com.harnesslearn.agent.l6guardrail.*;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationSurveyTest {
    @Test
    void surveyRunEndToEnd() {
        // planner 脚本：先检索，再 final；L5 用同一 fake 的第三条脚本判 pass
        var planner = FakeChatModel.scripted(
            AiMessage.from("""
                {"thought":"检索","action":"tool",
                 "tool":{"name":"local_retrieve","arguments":{"query":"上下文工程"}}}"""),
            AiMessage.from("""
                {"thought":"够了","action":"final","answer":"# 综述\\n上下文工程要点…"}"""));
        var critic = FakeChatModel.scripted(AiMessage.from(
            "{\"pass\":true,\"confidence\":0.9,\"issues\":[]}"));

        var embed = new BgeSmallZhV15QuantizedEmbeddingModel();
        var filter = new RelevanceFilter(embed, List.of("AI agent 脚手架与上下文工程"), 0.35, 0.05);
        // local_retrieve 桩工具返回一条相关内容
        Tool retrieve = new Tool() {
            public String name() { return "local_retrieve"; }
            public String description() { return "d"; }
            public ToolResult execute(ToolCall c) {
                return ToolResult.ok("[{\"id\":\"a\",\"sourceUri\":\"u1\",\"text\":\"agent 上下文工程与工具编排要点\",\"relevanceScore\":0}]");
            }
        };
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(retrieve)), filter);
        var loop = new AgentLoop(planner, new DefaultL1ContextAssembler(5), l2,
            new LlmL5Evaluator(critic), new DefaultL6Guardrail(new RecoveryPolicy(2)), 10);

        AgentRun run = loop.run(new TaskSpec("run-e2e", TaskType.SURVEY, "综述上下文工程", java.util.Map.of()));

        assertThat(run.success()).isTrue();
        assertThat(run.output().content()).contains("综述");
        assertThat(run.output().evidence()).isNotEmpty();          // 证据被收集
        assertThat(planner.callCount()).isEqualTo(2);              // 两步循环
        assertThat(critic.callCount()).isEqualTo(1);               // L5 被独立调用一次
    }
}
```

- [ ] **步骤 2：运行验证失败/通过**

运行：`mvn -q -Dtest=IntegrationSurveyTest test`
预期：PASS（若因前序接线细节 FAIL，按报错定位到对应层修正——这是集成测试的价值）。

- [ ] **步骤 3：跑全量测试**

运行：`mvn -q test`
预期：全绿。

- [ ] **步骤 4：更新 CLAUDE.md 的构建/测试命令段**

把 `mvn test` / 单测 / `@Tag("live")` 冒烟测试说明写入项目 `CLAUDE.md` 的"命令"章节。

- [ ] **步骤 5：Commit**
```bash
git add src/test/java/com/harnesslearn/agent/IntegrationSurveyTest.java CLAUDE.md
git commit -m "test: 综述全流程端到端集成测试 + 文档命令"
```

---

## 附录：相关性过滤评测集（防回归，建议在任务 9 后追加）

- 建 `src/test/resources/relevance-eval.json`：~20 条标注（`{text, label: relevant|garbage}`），覆盖 agent harness 相关文本与明显无关文本。
- 建 `RelevanceFilterEvalTest`：加载评测集，断言 `RelevanceFilter` 的 precision ≥ 0.9、recall ≥ 0.8。阈值 τ 不达标时调参而非改测试。
- 这是 §7 相关性过滤质量的回归护栏。

---

## 自检结果（对照规格）

**1. 规格覆盖度：**
- 技术栈(§1) → 任务 1–2、6 ✅
- 模块结构(§2) → 文件结构 + 各任务 ✅
- 数据流(§3) → 任务 15 循环 + 21 集成 ✅
- L1(§4) → 任务 7；L2 → 8–11；L3 → 15；L4 → 4–6；L5 → 12；L6 → 13–14 ✅
- 子 Agent(§5) → 任务 16–17 ✅
- 错误处理矩阵(§6) → 任务 13 逐条映射（web_search 降级、invalid 重试、verification 回滚、budget 中止、loop 检测）✅
- 相关性过滤(§7) → 任务 9、11 + 附录评测集 ✅
- 可观测性(§8) → 任务 18（LLM 调用级）、19（AgentTrace）✅
- 测试策略(§9) → 每任务 TDD + 任务 21 集成 + FakeChatModel ✅
- 非目标(§10) → 未纳入公众号/视频/鉴权/前端 ✅

**遗漏与决策：**
- L2 边界分数(τ±δ)的轻量 LLM 复判：本计划**明确不做**（任务 9 单闸阈值 + 评测集护栏），符合 YAGNI；如评测 precision 不达标再作为增量补入。
- SectionWriter：标注为按需追加（任务 17 注），不阻塞综述 MVP。
- 死循环检测(重复动作)：AgentLoop 当前靠 maxSteps 兜底；精细的"重复同一无效工具"检测未单列任务——**补充说明**：可在任务 15 的 AgentLoop 内加一个"连续 N 次相同 ToolCall 即 append open question 并触发 loop_detected"的判断，RecoveryPolicy 已支持该失败类型。实现任务 15 时一并加入此 3 行判断。

**2. 占位符扫描：** 无 "TODO/待定"；`SearchBackend`、`SectionWriter`、边界复判均为**显式的推迟决策并说明理由**，非空占位。

**3. 类型一致性：** `TaskSpec/WorkingState/RetrievedChunk/Artifact/ToolCall/DistilledResult/Verdict/Issue/ValidationResult/FailureContext/RecoveryDecision/AgentOutput/AgentRun/ModelStep/AssembledContext` 均在任务 3（及任务 7 补 `AssembledContext`）统一定义，后续任务引用一致；接口方法名（`assemble/invoke/verify/validateAction/validateOutput/onFailure/decide/run/append/load`）跨任务一致。
