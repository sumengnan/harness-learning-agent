# 前端 SSE MVP 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给「Harness 学习小助手」加一个 Web 前端：提交任务 → 通过 SSE 实时看 agent 的 L1–L6 逐步进展 → 展示最终结果与证据。

**架构：** 后端在 `observability` 加事件桥（`RunEventBus` + `@Primary CompositeTraceStore`，让 agent 每步 trace 既落库又实时发布），在 `api` 加 SSE 端点 `GET /runs/stream`（虚拟线程跑同步 orchestrator，逐步推 `event:step`，结束推 `event:result`，异常推 `event:fail`）；`AgentLoop` 类零改动，仅改 `AgentConfig` 一处 bean 参数类型。前端新建 `frontend/`（Vite + React + TS），构建产物入 `src/main/resources/static/` 同源托管。

**技术栈：** 后端 Java 21 + Spring Boot 3.3.4（spring-boot-starter-web，含 `SseEmitter`）+ langchain4j 0.35.0；前端 Vite 5 + React 18 + TypeScript 5 + Vitest + React Testing Library + `marked`。

---

## 关键契约（贯穿全计划，务必一致）

后端既有类型（master 0cc2b39，**不改**）：
- `record TraceStep(String runId, int seq, String layer, String event, String detail)`（`observability`）
- `interface TraceStore { void append(TraceStep step); List<TraceStep> load(String runId); }`（`observability`）
- `class SqliteTraceStore implements TraceStore`，构造 `SqliteTraceStore(JdbcTemplate)`
- `record TaskSpec(String runId, TaskType type, String userQuery, Map<String,Object> params)`
- `enum TaskType { QA, SURVEY, DIGEST, LEARNING_PATH }`
- `record AgentRun(String runId, AgentOutput output, boolean success, String terminationReason)`
- `record AgentOutput(String content, List<Artifact> evidence)`
- `record Artifact(String id, String runId, String kind, String key, String content, Map<String,String> meta)`
- `interface L3Orchestrator { AgentRun run(TaskSpec task); }`
- `class SchemaInitializer`，构造 `SchemaInitializer(JdbcTemplate)`，`init()` 建含 `trace_step` 在内的所有表
- 测试替身 `com.harnesslearn.agent.support.FakeChatModel`（本计划后端测试改用 `@MockBean L3Orchestrator`，不直接用它）

SSE 线路事件（本计划新定义）：
- `event: step`，`data:` = `TraceStep` 的 JSON（`{runId,seq,layer,event,detail}`，前端只用后四个）
- `event: result`，`data:` = `{success, output, evidence, termination}` 的 JSON
- `event: fail`，`data:` = `{message}` 的 JSON（**用 `fail` 不用 `error`，避免与原生 `EventSource` 连接错误语义冲突**）

前端 TS 类型（`frontend/src/api/types.ts`，任务 6 定义，后续任务复用同名）：
```ts
export type TaskType = 'QA' | 'SURVEY' | 'DIGEST' | 'LEARNING_PATH';
export interface StepEvent { seq: number; layer: string; event: string; detail: string; }
export interface Artifact { id: string; runId: string; kind: string; key: string; content: string; meta: Record<string, string>; }
export interface ResultEvent { success: boolean; output: string; evidence: Artifact[]; termination: string; }
export type RunStatus = 'idle' | 'streaming' | 'done' | 'error';
```

---

## 文件结构

后端（`src/main/java/com/harnesslearn/agent/`）：
- 创建 `observability/RunEventBus.java` — 进程内按 runId 分发 TraceStep 的事件总线（任务 1）
- 创建 `observability/CompositeTraceStore.java` — `@Primary` 装饰器：落库 + 发布（任务 2）
- 修改 `AgentConfig.java` — 加 `RunEventBus`/`CompositeTraceStore` bean，`agentLoop` 参数 `SqliteTraceStore`→`TraceStore`（任务 3）
- 创建 `api/dto/RunResultDto.java` — SSE result 事件载荷（任务 4）
- 创建 `api/RunStreamController.java` — `GET /runs/stream` SSE 端点（任务 4）

后端测试（`src/test/java/com/harnesslearn/agent/`）：
- `observability/RunEventBusTest.java`（任务 1）
- `observability/CompositeTraceStoreTest.java`（任务 2）
- `api/TraceStoreWiringTest.java`（任务 3）
- `api/RunStreamControllerTest.java`（任务 4）

前端（`frontend/`）：
- `package.json` / `tsconfig.json` / `tsconfig.node.json` / `vite.config.ts` / `index.html` / `.gitignore`（任务 5）
- `src/main.tsx` / `src/App.tsx` / `src/app.css`（任务 5 起，任务 10 完善）
- `src/setupTests.ts` / `src/test/mockEventSource.ts`（任务 5、6）
- `src/api/types.ts` / `src/api/useRunStream.ts`（任务 6）
- `src/components/TaskForm.tsx`（任务 7）/ `RunTimeline.tsx`（任务 8）/ `ResultPanel.tsx`（任务 9）
- 各 `*.test.tsx`

根 `.gitignore`：追加 `frontend/node_modules/` 与 `src/main/resources/static/`（任务 5）。

---

## 任务 1：RunEventBus（进程内事件总线）

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/observability/RunEventBus.java`
- 测试：`src/test/java/com/harnesslearn/agent/observability/RunEventBusTest.java`

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/observability/RunEventBusTest.java`：
```java
package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunEventBusTest {

    private static TraceStep step(String runId, int seq) {
        return new TraceStep(runId, seq, "L3", "model_step", "d" + seq);
    }

    @Test
    void subscribeThenPublishDelivers() throws Exception {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q = bus.subscribe("r1");
        bus.publish("r1", step("r1", 0));
        TraceStep got = q.poll(1, TimeUnit.SECONDS);
        assertThat(got).isNotNull();
        assertThat(got.seq()).isZero();
    }

    @Test
    void publishWithoutSubscriberIsNoOp() {
        var bus = new RunEventBus();
        assertThatCode(() -> bus.publish("ghost", step("ghost", 0))).doesNotThrowAnyException();
    }

    @Test
    void runsAreIsolatedByRunId() {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q1 = bus.subscribe("r1");
        BlockingQueue<TraceStep> q2 = bus.subscribe("r2");
        bus.publish("r1", step("r1", 0));
        assertThat(q1).hasSize(1);
        assertThat(q2).isEmpty();
    }

    @Test
    void unsubscribeStopsDelivery() {
        var bus = new RunEventBus();
        BlockingQueue<TraceStep> q = bus.subscribe("r1");
        bus.unsubscribe("r1");
        bus.publish("r1", step("r1", 0));
        assertThat(q).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=RunEventBusTest test`
预期：编译失败（`RunEventBus` 不存在）。

- [ ] **步骤 3：创建 RunEventBus**

`src/main/java/com/harnesslearn/agent/observability/RunEventBus.java`：
```java
package com.harnesslearn.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 进程内事件总线：按 runId 把 agent 每步 {@link TraceStep} 分发给一个订阅者（SSE 连接）。
 * 有界队列 + offer（不阻塞、满即丢），无订阅者时 publish 空转——全线 best-effort。
 */
public class RunEventBus {
    private static final Logger log = LoggerFactory.getLogger(RunEventBus.class);
    private static final int CAPACITY = 1000;
    private final ConcurrentHashMap<String, BlockingQueue<TraceStep>> subs = new ConcurrentHashMap<>();

    /** 为某次运行注册订阅，返回其事件队列。SSE 端点持有此队列拉取。 */
    public BlockingQueue<TraceStep> subscribe(String runId) {
        BlockingQueue<TraceStep> q = new LinkedBlockingQueue<>(CAPACITY);
        subs.put(runId, q);
        return q;
    }

    /** 投递一步给该 runId 的订阅者；无订阅者空转；队列满则丢弃并 WARN（best-effort）。 */
    public void publish(String runId, TraceStep step) {
        BlockingQueue<TraceStep> q = subs.get(runId);
        if (q == null) return;
        if (!q.offer(step)) {
            log.warn("SSE 事件队列已满，丢弃一步: runId={}, seq={}", runId, step.seq());
        }
    }

    /** 注销订阅，移除队列引用防泄漏。SSE 结束/超时/断开时调用。 */
    public void unsubscribe(String runId) {
        subs.remove(runId);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=RunEventBusTest test`
预期：PASS（4 个测试）。

- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/observability/RunEventBus.java \
        src/test/java/com/harnesslearn/agent/observability/RunEventBusTest.java
git commit -m "feat(sse): RunEventBus 进程内按 runId 事件总线

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 2：CompositeTraceStore（落库 + 发布装饰器）

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/observability/CompositeTraceStore.java`
- 测试：`src/test/java/com/harnesslearn/agent/observability/CompositeTraceStoreTest.java`

**上下文：** `AgentLoop` 每步调 `traceStore.append(step)`。本装饰器包住真实 `SqliteTraceStore`（落库）并在其后把同一步发布到 `RunEventBus`（供 SSE 实时消费）。两步各自 best-effort：任一失败只 WARN，不影响另一个、不上抛。构造收 `TraceStore` 接口（非具体类）以便注入抛异常的假实现测试。

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/observability/CompositeTraceStoreTest.java`：
```java
package com.harnesslearn.agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CompositeTraceStoreTest {

    private SqliteTraceStore sqlite(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new SqliteTraceStore(jt);
    }

    private static TraceStep step(String runId, int seq) {
        return new TraceStep(runId, seq, "L3", "model_step", "d" + seq);
    }

    @Test
    void appendPersistsAndPublishes() {
        var bus = new RunEventBus();
        var delegate = sqlite("memComposite1");
        var composite = new CompositeTraceStore(delegate, bus);
        BlockingQueue<TraceStep> q = bus.subscribe("r1");

        composite.append(step("r1", 0));

        assertThat(delegate.load("r1")).hasSize(1);   // 落库
        assertThat(q).hasSize(1);                      // 发布
        assertThat(composite.load("r1")).hasSize(1);   // load 委托 delegate
    }

    @Test
    void delegateFailureStillPublishes() {
        var bus = new RunEventBus();
        TraceStore throwingDelegate = new TraceStore() {
            public void append(TraceStep s) { throw new RuntimeException("db down"); }
            public List<TraceStep> load(String runId) { return List.of(); }
        };
        var composite = new CompositeTraceStore(throwingDelegate, bus);
        BlockingQueue<TraceStep> q = bus.subscribe("r1");

        assertThatCode(() -> composite.append(step("r1", 0))).doesNotThrowAnyException();
        assertThat(q).hasSize(1);                      // 落库失败不挡发布
    }

    @Test
    void publishFailureStillPersists() {
        var delegate = sqlite("memComposite2");
        RunEventBus throwingBus = new RunEventBus() {
            @Override public void publish(String runId, TraceStep step) { throw new RuntimeException("bus down"); }
        };
        var composite = new CompositeTraceStore(delegate, throwingBus);

        assertThatCode(() -> composite.append(step("r1", 0))).doesNotThrowAnyException();
        assertThat(delegate.load("r1")).hasSize(1);    // 发布失败不挡落库
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=CompositeTraceStoreTest test`
预期：编译失败（`CompositeTraceStore` 不存在）。

- [ ] **步骤 3：创建 CompositeTraceStore**

`src/main/java/com/harnesslearn/agent/observability/CompositeTraceStore.java`：
```java
package com.harnesslearn.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * TraceStore 装饰器：先落库（delegate），再把同一步发布到 RunEventBus 供 SSE 实时消费。
 * 两步各自 best-effort——任一失败只 WARN，不影响另一步、不上抛，绝不拖垮 agent 主循环。
 */
public class CompositeTraceStore implements TraceStore {
    private static final Logger log = LoggerFactory.getLogger(CompositeTraceStore.class);
    private final TraceStore delegate;
    private final RunEventBus bus;

    public CompositeTraceStore(TraceStore delegate, RunEventBus bus) {
        this.delegate = delegate;
        this.bus = bus;
    }

    @Override
    public void append(TraceStep step) {
        try {
            delegate.append(step);
        } catch (RuntimeException e) {
            log.warn("trace 落库失败，跳过: runId={}, seq={}", step.runId(), step.seq(), e);
        }
        try {
            bus.publish(step.runId(), step);
        } catch (RuntimeException e) {
            log.warn("trace 发布失败，跳过: runId={}, seq={}", step.runId(), step.seq(), e);
        }
    }

    @Override
    public List<TraceStep> load(String runId) {
        return delegate.load(runId);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=CompositeTraceStoreTest test`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/observability/CompositeTraceStore.java \
        src/test/java/com/harnesslearn/agent/observability/CompositeTraceStoreTest.java
git commit -m "feat(sse): CompositeTraceStore 落库+发布 best-effort 装饰器

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 3：AgentConfig 接线（@Primary CompositeTraceStore）

**文件：**
- 修改：`src/main/java/com/harnesslearn/agent/AgentConfig.java`
- 测试：`src/test/java/com/harnesslearn/agent/api/TraceStoreWiringTest.java`

**上下文：** 现 `agentLoop` bean 的参数是具体类 `SqliteTraceStore trace`（约 `:136-142`），会绕过装饰器。改成注入接口 `TraceStore`，并新增 `@Primary CompositeTraceStore` bean，使 `AgentLoop` 实际拿到装饰器（`AgentLoop` 类本身不改）。`SqliteTraceStore` bean 仍在，作为 delegate。

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/api/TraceStoreWiringTest.java`：
```java
package com.harnesslearn.agent.api;

import com.harnesslearn.agent.observability.CompositeTraceStore;
import com.harnesslearn.agent.observability.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** 锁住接线：注入 TraceStore 拿到的是 @Primary 的 CompositeTraceStore（装饰器已生效）。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.corpus.seed-on-startup=false"
})
class TraceStoreWiringTest {
    @Autowired TraceStore traceStore;

    @Test
    void primaryTraceStoreIsComposite() {
        assertThat(traceStore).isInstanceOf(CompositeTraceStore.class);
    }
}
```
> 注：`agent.corpus.seed-on-startup=false` 沿用 master 上 `AgentApplicationTest` 的既有开关，避免启动播种拖慢测试。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=TraceStoreWiringTest test`
预期：FAIL——注入的是 `SqliteTraceStore`（无 `@Primary` 装饰器），`isInstanceOf(CompositeTraceStore)` 不成立；或注入歧义。

- [ ] **步骤 3：改 AgentConfig**

在 `src/main/java/com/harnesslearn/agent/AgentConfig.java`：

1. 加 import：
```java
import com.harnesslearn.agent.observability.CompositeTraceStore;
import com.harnesslearn.agent.observability.RunEventBus;
import com.harnesslearn.agent.observability.TraceStore;
import org.springframework.context.annotation.Primary;
```

2. 在 `traceStore` bean 方法（返回 `SqliteTraceStore` 的那个）之后，新增两个 bean：
```java
    @Bean
    public RunEventBus runEventBus() {
        return new RunEventBus();
    }

    @Bean
    @Primary
    public CompositeTraceStore compositeTraceStore(SqliteTraceStore delegate, RunEventBus bus) {
        return new CompositeTraceStore(delegate, bus);
    }
```

3. 把 `agentLoop` bean 的参数类型 `SqliteTraceStore trace` 改为 `TraceStore trace`：
```java
    @Bean
    public L3Orchestrator agentLoop(@org.springframework.context.annotation.Lazy ChatLanguageModel model,
            L1ContextAssembler l1, L2ToolSystem l2,
            L5Evaluator l5, L6Guardrail l6, TraceStore trace,
            SqliteWorkingStateStore wss, SqliteArtifactStore artifacts,
            @Value("${agent.orchestrate.max-steps:20}") int maxSteps) {
        return new AgentLoop(model, l1, l2, l5, l6, maxSteps, trace, wss, artifacts);
    }
```
（方法体不变——`AgentLoop` 的 9 参构造器本就收 `TraceStore`。因 `@Primary`，`trace` 注入到 `CompositeTraceStore`。）

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=TraceStoreWiringTest test`
预期：PASS。

再跑全量回归确认既有测试不受影响：
运行：`mvn clean test`
预期：BUILD SUCCESS，全绿（`AgentControllerTest` 等原有测试不变）。

- [ ] **步骤 5：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/AgentConfig.java \
        src/test/java/com/harnesslearn/agent/api/TraceStoreWiringTest.java
git commit -m "feat(sse): AgentConfig 接线 @Primary CompositeTraceStore

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 4：RunStreamController（SSE 端点）

**文件：**
- 创建：`src/main/java/com/harnesslearn/agent/api/dto/RunResultDto.java`
- 创建：`src/main/java/com/harnesslearn/agent/api/RunStreamController.java`
- 测试：`src/test/java/com/harnesslearn/agent/api/RunStreamControllerTest.java`

**上下文：** `GET /runs/stream?type=&query=` 返回 `SseEmitter`。控制器：校验参数（非法/空→400）→ 生成 runId → `bus.subscribe` → 虚拟线程跑同步 `orchestrator.run` → 主转发循环从队列取步骤推 `event:step` → run 结束推 `event:result` → 异常推 `event:fail`。转发用 `runF.isDone()` 判终止（run 返回时其所有 `append` 已同步完成，drain 一次即取全）。

- [ ] **步骤 1：编写失败的测试**

`src/test/java/com/harnesslearn/agent/api/RunStreamControllerTest.java`：
```java
package com.harnesslearn.agent.api;

import com.harnesslearn.agent.domain.AgentOutput;
import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import com.harnesslearn.agent.observability.RunEventBus;
import com.harnesslearn.agent.observability.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.corpus.seed-on-startup=false"
})
class RunStreamControllerTest {

    @LocalServerPort int port;
    @Autowired RunEventBus bus;
    @MockBean L3Orchestrator orchestrator;

    private String getStream(String query) throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=SURVEY&query=" + query)).GET().build();
        HttpResponse<java.util.stream.Stream<String>> resp =
            client.send(req, HttpResponse.BodyHandlers.ofLines());
        // 有限流：run 结束后 emitter.complete()，服务端关闭连接，ofLines 自然结束
        return resp.body().collect(Collectors.joining("\n"));
    }

    @Test
    void streamsStepsThenResult() throws Exception {
        when(orchestrator.run(any())).thenAnswer(inv -> {
            TaskSpec t = inv.getArgument(0);
            bus.publish(t.runId(), new TraceStep(t.runId(), 0, "L3", "model_step", "思考"));
            bus.publish(t.runId(), new TraceStep(t.runId(), 1, "L2", "tool_invoke", "local_retrieve"));
            return new AgentRun(t.runId(), new AgentOutput("最终综述", List.of()), true, "completed");
        });

        String body = getStream("ctx");

        assertThat(body).contains("event:step");
        assertThat(body).contains("event:result");
        assertThat(body).contains("最终综述");
        assertThat(body).contains("tool_invoke");
    }

    @Test
    void workerExceptionEmitsFail() throws Exception {
        when(orchestrator.run(any())).thenThrow(new RuntimeException("boom"));

        String body = getStream("ctx");

        assertThat(body).contains("event:fail");
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=BOGUS&query=x")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void blankQueryReturns400() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=QA&query=")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
    }
}
```
> 注：`@MockBean L3Orchestrator` 顶替真实 `agentLoop` bean，避免跑真 agent；`RunEventBus` 是真实 bean，mock 在 `run()` 里往它 `publish` 模拟 agent 埋点。上下文仍会加载 bge（`RelevanceFilter` eager 构造），首次较慢属正常。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=RunStreamControllerTest test`
预期：编译失败（`RunStreamController`/`RunResultDto` 不存在）。

- [ ] **步骤 3：创建 RunResultDto**

`src/main/java/com/harnesslearn/agent/api/dto/RunResultDto.java`：
```java
package com.harnesslearn.agent.api.dto;

import com.harnesslearn.agent.domain.Artifact;
import java.util.List;

/** SSE `event: result` 载荷：一次运行的最终结果。 */
public record RunResultDto(boolean success, String output, List<Artifact> evidence, String termination) {}
```

- [ ] **步骤 4：创建 RunStreamController**

`src/main/java/com/harnesslearn/agent/api/RunStreamController.java`：
```java
package com.harnesslearn.agent.api;

import com.harnesslearn.agent.api.dto.RunResultDto;
import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;
import com.harnesslearn.agent.domain.TaskType;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import com.harnesslearn.agent.observability.RunEventBus;
import com.harnesslearn.agent.observability.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SSE 端点：GET /runs/stream。虚拟线程跑同步 orchestrator.run，逐步推 event:step，
 * 结束推 event:result，异常推 event:fail。全线 best-effort，不拖垮进程。
 */
@RestController
public class RunStreamController {
    private static final Logger log = LoggerFactory.getLogger(RunStreamController.class);
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    private final L3Orchestrator orchestrator;
    private final RunEventBus bus;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public RunStreamController(L3Orchestrator orchestrator, RunEventBus bus) {
        this.orchestrator = orchestrator;
        this.bus = bus;
    }

    @GetMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String type, @RequestParam String query) {
        TaskType taskType;
        try {
            taskType = TaskType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知任务类型: " + type);
        }
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }

        String runId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        BlockingQueue<TraceStep> queue = bus.subscribe(runId);

        emitter.onCompletion(() -> bus.unsubscribe(runId));
        emitter.onTimeout(() -> { bus.unsubscribe(runId); emitter.complete(); });
        emitter.onError(e -> bus.unsubscribe(runId));

        exec.submit(() -> pump(emitter, queue, runId, taskType, query));
        return emitter;
    }

    private void pump(SseEmitter emitter, BlockingQueue<TraceStep> queue,
                      String runId, TaskType type, String query) {
        Future<AgentRun> runF = exec.submit(
            () -> orchestrator.run(new TaskSpec(runId, type, query, Map.of())));
        try {
            while (!runF.isDone()) {
                TraceStep s = queue.poll(200, TimeUnit.MILLISECONDS);
                if (s != null) emitter.send(SseEmitter.event().name("step").data(s, MediaType.APPLICATION_JSON));
            }
            TraceStep s;                                  // run 已结束，drain 剩余步骤
            while ((s = queue.poll()) != null) {
                emitter.send(SseEmitter.event().name("step").data(s, MediaType.APPLICATION_JSON));
            }
            AgentRun run = runF.get();                    // 可能抛 ExecutionException
            emitter.send(SseEmitter.event().name("result").data(
                new RunResultDto(run.success(), run.output().content(),
                    run.output().evidence(), run.terminationReason()),
                MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 运行异常，推 fail: runId={}", runId, e);
            try {
                emitter.send(SseEmitter.event().name("fail").data(
                    Map.of("message", String.valueOf(e.getMessage())), MediaType.APPLICATION_JSON));
            } catch (Exception ignore) { /* 连接可能已断，忽略 */ }
            emitter.complete();
        } finally {
            bus.unsubscribe(runId);
        }
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -Dtest=RunStreamControllerTest test`
预期：PASS（4 个测试）。若因线程中断标志被 `InterruptedException` 影响，`queue.poll` 已声明 throws 并被外层 catch 兜住。

- [ ] **步骤 6：Commit**
```bash
git add src/main/java/com/harnesslearn/agent/api/dto/RunResultDto.java \
        src/main/java/com/harnesslearn/agent/api/RunStreamController.java \
        src/test/java/com/harnesslearn/agent/api/RunStreamControllerTest.java
git commit -m "feat(sse): RunStreamController GET /runs/stream 流式推送

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 5：前端脚手架 + 工具链（Vite + React + TS + Vitest）

**文件：**
- 创建：`frontend/package.json`、`frontend/tsconfig.json`、`frontend/tsconfig.node.json`、`frontend/vite.config.ts`、`frontend/index.html`
- 创建：`frontend/src/main.tsx`、`frontend/src/App.tsx`、`frontend/src/app.css`、`frontend/src/setupTests.ts`
- 创建：`frontend/src/App.test.tsx`
- 修改：根 `.gitignore`（追加 `frontend/node_modules/`、`src/main/resources/static/`）

**上下文：** 建立可构建、可测试的前端工程骨架。此任务的 `App` 只是占位标题，真正装配在任务 10。目标是跑通 `npm install`/`npm run build`（产物入 `../src/main/resources/static`）与 `npm test`（Vitest + jsdom + RTL）。

- [ ] **步骤 1：创建工程配置文件**

`frontend/package.json`：
```json
{
  "name": "harness-learn-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "marked": "^12.0.2",
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.4.6",
    "@testing-library/react": "^16.0.0",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "jsdom": "^24.1.0",
    "typescript": "^5.5.3",
    "vite": "^5.3.3",
    "vitest": "^2.0.2"
  }
}
```

`frontend/tsconfig.json`：
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

`frontend/tsconfig.node.json`：
```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true
  },
  "include": ["vite.config.ts"]
}
```

`frontend/vite.config.ts`：
```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/runs': 'http://localhost:8080',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
  },
});
```

`frontend/index.html`：
```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Harness 学习小助手</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **步骤 2：创建入口与占位 App + 测试**

`frontend/src/setupTests.ts`：
```ts
import '@testing-library/jest-dom';
```

`frontend/src/app.css`：
```css
:root { color-scheme: light dark; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
body { margin: 0; }
#root { max-width: 820px; margin: 0 auto; padding: 24px; }
```

`frontend/src/App.tsx`：
```tsx
import './app.css';

export default function App() {
  return (
    <main>
      <h1>Harness 学习小助手</h1>
    </main>
  );
}
```

`frontend/src/main.tsx`：
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
```

`frontend/src/App.test.tsx`：
```tsx
import { render, screen } from '@testing-library/react';
import App from './App';

test('渲染标题', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: 'Harness 学习小助手' })).toBeInTheDocument();
});
```

- [ ] **步骤 3：改根 .gitignore**

在根 `.gitignore` 追加两行：
```
frontend/node_modules/
src/main/resources/static/
```

- [ ] **步骤 4：安装依赖并验证测试链**

运行：
```bash
cd frontend && npm install && npm test
```
预期：`npm install` 成功；`npm test`（Vitest）跑 `App.test.tsx` 1 个测试 PASS。

- [ ] **步骤 5：验证构建产物落到 static/**

运行：
```bash
cd frontend && npm run build
ls ../src/main/resources/static
```
预期：`tsc -b && vite build` 成功；`static/` 下出现 `index.html` 与 `assets/`。（该目录已 gitignore，不提交产物。）

- [ ] **步骤 6：Commit**
```bash
cd ..
git add frontend/package.json frontend/tsconfig.json frontend/tsconfig.node.json \
        frontend/vite.config.ts frontend/index.html \
        frontend/src/main.tsx frontend/src/App.tsx frontend/src/app.css \
        frontend/src/setupTests.ts frontend/src/App.test.tsx .gitignore
git commit -m "feat(frontend): Vite+React+TS 脚手架与 Vitest 工具链

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
> 注意：勿 `git add frontend/node_modules` 或 `src/main/resources/static`（已 gitignore）。若 `frontend/package-lock.json` 生成，一并 `git add` 提交以锁版本。

---

## 任务 6：types.ts + useRunStream hook（SSE 客户端）

**文件：**
- 创建：`frontend/src/api/types.ts`
- 创建：`frontend/src/api/useRunStream.ts`
- 创建：`frontend/src/test/mockEventSource.ts`
- 测试：`frontend/src/api/useRunStream.test.ts`

**上下文：** `useRunStream` 用原生 `EventSource` 连 `/runs/stream`，内部 `useReducer` 管状态机（`idle→streaming→done/error`），监听命名事件 `step`/`result`/`fail` 及连接错误 `onerror`。jsdom 无 `EventSource`，测试用 `MockEventSource` 装到 `globalThis`。

- [ ] **步骤 1：定义类型**

`frontend/src/api/types.ts`：
```ts
export type TaskType = 'QA' | 'SURVEY' | 'DIGEST' | 'LEARNING_PATH';

export interface StepEvent { seq: number; layer: string; event: string; detail: string; }

export interface Artifact {
  id: string; runId: string; kind: string; key: string;
  content: string; meta: Record<string, string>;
}

export interface ResultEvent {
  success: boolean; output: string; evidence: Artifact[]; termination: string;
}

export type RunStatus = 'idle' | 'streaming' | 'done' | 'error';
```

- [ ] **步骤 2：写 MockEventSource 测试替身**

`frontend/src/test/mockEventSource.ts`：
```ts
type Listener = (e: { data: string }) => void;

/** 测试用 EventSource 替身：记录实例，供测试侧手动 emit 命名事件与连接错误。 */
export class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  onerror: ((e: unknown) => void) | null = null;
  private listeners: Record<string, Listener[]> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: Listener) {
    (this.listeners[type] ||= []).push(cb);
  }

  close() { this.closed = true; }

  /** 测试侧：派发一个命名事件，data 为 JSON 序列化对象。 */
  emit(type: string, data: unknown) {
    (this.listeners[type] || []).forEach((cb) => cb({ data: JSON.stringify(data) }));
  }

  /** 测试侧：触发连接错误（对应原生 onerror）。 */
  emitError() { this.onerror?.({}); }

  static reset() { MockEventSource.instances = []; }
  static last(): MockEventSource { return MockEventSource.instances[MockEventSource.instances.length - 1]; }
}

export function installMockEventSource() {
  MockEventSource.reset();
  (globalThis as unknown as { EventSource: unknown }).EventSource = MockEventSource;
}
```

- [ ] **步骤 3：写失败的 hook 测试**

`frontend/src/api/useRunStream.test.ts`：
```ts
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, test } from 'vitest';
import { useRunStream } from './useRunStream';
import { MockEventSource, installMockEventSource } from '../test/mockEventSource';

beforeEach(() => installMockEventSource());

describe('useRunStream', () => {
  test('step 累积 + result 收尾', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'SURVEY', query: 'ctx' }));
    expect(result.current.status).toBe('streaming');

    const es = MockEventSource.last();
    act(() => es.emit('step', { seq: 0, layer: 'L3', event: 'model_step', detail: 'a' }));
    act(() => es.emit('step', { seq: 1, layer: 'L2', event: 'tool_invoke', detail: 'b' }));
    expect(result.current.steps).toHaveLength(2);

    act(() => es.emit('result', { success: true, output: 'done', evidence: [], termination: 'completed' }));
    expect(result.current.status).toBe('done');
    expect(result.current.result?.output).toBe('done');
    expect(es.closed).toBe(true);
  });

  test('fail 事件 → error 态', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emit('fail', { message: 'boom' }));
    expect(result.current.status).toBe('error');
    expect(result.current.error).toContain('boom');
  });

  test('未收 result 前连接断开 → error 态', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emitError());
    expect(result.current.status).toBe('error');
  });

  test('result 后的关闭不算错误', () => {
    const { result } = renderHook(() => useRunStream());
    act(() => result.current.start({ type: 'QA', query: 'x' }));
    const es = MockEventSource.last();
    act(() => es.emit('result', { success: true, output: 'ok', evidence: [], termination: 'completed' }));
    act(() => es.emitError());               // 正常结束后底层可能再触发 onerror
    expect(result.current.status).toBe('done');
  });
});
```

- [ ] **步骤 4：运行测试验证失败**

运行：`cd frontend && npx vitest run src/api/useRunStream.test.ts`
预期：FAIL（`useRunStream` 未定义）。

- [ ] **步骤 5：实现 useRunStream**

`frontend/src/api/useRunStream.ts`：
```ts
import { useCallback, useReducer, useRef } from 'react';
import type { ResultEvent, RunStatus, StepEvent, TaskType } from './types';

interface State { status: RunStatus; steps: StepEvent[]; result: ResultEvent | null; error: string | null; }

type Action =
  | { type: 'START' }
  | { type: 'STEP'; step: StepEvent }
  | { type: 'RESULT'; result: ResultEvent }
  | { type: 'FAIL'; message: string }
  | { type: 'RESET' };

const initial: State = { status: 'idle', steps: [], result: null, error: null };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'START': return { status: 'streaming', steps: [], result: null, error: null };
    case 'STEP': return { ...state, steps: [...state.steps, action.step] };
    case 'RESULT': return { ...state, status: 'done', result: action.result };
    case 'FAIL':
      if (state.status === 'done') return state;         // 已完成，忽略迟到的错误
      return { ...state, status: 'error', error: action.message };
    case 'RESET': return initial;
    default: return state;
  }
}

export interface Params { type: TaskType; query: string; }

export function useRunStream() {
  const [state, dispatch] = useReducer(reducer, initial);
  const esRef = useRef<EventSource | null>(null);

  const start = useCallback((params: Params) => {
    esRef.current?.close();
    dispatch({ type: 'START' });
    const url = `/runs/stream?type=${encodeURIComponent(params.type)}&query=${encodeURIComponent(params.query)}`;
    const es = new EventSource(url);
    esRef.current = es;

    es.addEventListener('step', (e) => dispatch({ type: 'STEP', step: JSON.parse((e as MessageEvent).data) }));
    es.addEventListener('result', (e) => {
      dispatch({ type: 'RESULT', result: JSON.parse((e as MessageEvent).data) });
      es.close();
    });
    es.addEventListener('fail', (e) => {
      const msg = (() => { try { return JSON.parse((e as MessageEvent).data).message; } catch { return '运行失败'; } })();
      dispatch({ type: 'FAIL', message: String(msg) });
      es.close();
    });
    es.onerror = () => { dispatch({ type: 'FAIL', message: '连接中断' }); es.close(); };
  }, []);

  const reset = useCallback(() => { esRef.current?.close(); dispatch({ type: 'RESET' }); }, []);

  return { ...state, start, reset };
}
```
> `FAIL` 在 `status==='done'` 时被 reducer 忽略——保证「result 后底层再触发 onerror」不会翻成 error（对应第 4 个测试）。

- [ ] **步骤 6：运行测试验证通过**

运行：`cd frontend && npx vitest run src/api/useRunStream.test.ts`
预期：PASS（4 个测试）。

- [ ] **步骤 7：Commit**
```bash
cd ..
git add frontend/src/api/types.ts frontend/src/api/useRunStream.ts \
        frontend/src/api/useRunStream.test.ts frontend/src/test/mockEventSource.ts
git commit -m "feat(frontend): types + useRunStream SSE 客户端 hook

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 7：TaskForm 组件

**文件：**
- 创建：`frontend/src/components/TaskForm.tsx`
- 测试：`frontend/src/components/TaskForm.test.tsx`

- [ ] **步骤 1：写失败的测试**

`frontend/src/components/TaskForm.test.tsx`：
```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { TaskForm } from './TaskForm';

test('填 query 后提交回传 type 与 query', async () => {
  const onSubmit = vi.fn();
  render(<TaskForm disabled={false} onSubmit={onSubmit} />);
  await userEvent.type(screen.getByLabelText('问题/主题'), '上下文工程');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));
  expect(onSubmit).toHaveBeenCalledWith({ type: 'QA', query: '上下文工程' });
});

test('query 为空时提交按钮禁用', () => {
  render(<TaskForm disabled={false} onSubmit={() => {}} />);
  expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
});

test('disabled 时按钮禁用', async () => {
  render(<TaskForm disabled onSubmit={() => {}} />);
  await userEvent.type(screen.getByLabelText('问题/主题'), 'x');
  expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
});

test('提供 4 种任务类型', () => {
  render(<TaskForm disabled={false} onSubmit={() => {}} />);
  expect(screen.getAllByRole('option')).toHaveLength(4);
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd frontend && npx vitest run src/components/TaskForm.test.tsx`
预期：FAIL（`TaskForm` 未定义）。

- [ ] **步骤 3：实现 TaskForm**

`frontend/src/components/TaskForm.tsx`：
```tsx
import { useState } from 'react';
import type { Params } from '../api/useRunStream';
import type { TaskType } from '../api/types';

const TYPES: { value: TaskType; label: string }[] = [
  { value: 'QA', label: '问答' },
  { value: 'SURVEY', label: '综述' },
  { value: 'DIGEST', label: '摘要' },
  { value: 'LEARNING_PATH', label: '学习路径' },
];

export function TaskForm({ disabled, onSubmit }: { disabled: boolean; onSubmit: (p: Params) => void }) {
  const [type, setType] = useState<TaskType>('QA');
  const [query, setQuery] = useState('');
  const canSubmit = !disabled && query.trim().length > 0;

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); if (canSubmit) onSubmit({ type, query: query.trim() }); }}
    >
      <label>
        任务类型
        <select value={type} disabled={disabled} onChange={(e) => setType(e.target.value as TaskType)}>
          {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
      </label>
      <label>
        问题/主题
        <textarea
          value={query}
          disabled={disabled}
          rows={3}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>
      <button type="submit" disabled={!canSubmit}>提交</button>
    </form>
  );
}
```
> `<label>问题/主题<textarea/></label>` 的包裹关联使 `getByLabelText('问题/主题')` 命中该 textarea。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd frontend && npx vitest run src/components/TaskForm.test.tsx`
预期：PASS（4 个测试）。

- [ ] **步骤 5：Commit**
```bash
cd ..
git add frontend/src/components/TaskForm.tsx frontend/src/components/TaskForm.test.tsx
git commit -m "feat(frontend): TaskForm 任务提交表单

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 8：RunTimeline 组件

**文件：**
- 创建：`frontend/src/components/RunTimeline.tsx`
- 测试：`frontend/src/components/RunTimeline.test.tsx`

- [ ] **步骤 1：写失败的测试**

`frontend/src/components/RunTimeline.test.tsx`：
```tsx
import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { RunTimeline } from './RunTimeline';
import type { StepEvent } from '../api/types';

const steps: StepEvent[] = [
  { seq: 0, layer: 'L3', event: 'model_step', detail: '思考中' },
  { seq: 1, layer: 'L2', event: 'tool_invoke', detail: 'local_retrieve' },
];

test('渲染每一步及其层标签', () => {
  render(<RunTimeline steps={steps} active={false} />);
  expect(screen.getByText('L3')).toBeInTheDocument();
  expect(screen.getByText('L2')).toBeInTheDocument();
  expect(screen.getByText('model_step')).toBeInTheDocument();
  expect(screen.getByText('tool_invoke')).toBeInTheDocument();
});

test('active 时显示运行中指示', () => {
  render(<RunTimeline steps={steps} active />);
  expect(screen.getByText('运行中…')).toBeInTheDocument();
});

test('无步骤且非 active 不渲染运行中', () => {
  render(<RunTimeline steps={[]} active={false} />);
  expect(screen.queryByText('运行中…')).not.toBeInTheDocument();
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd frontend && npx vitest run src/components/RunTimeline.test.tsx`
预期：FAIL（`RunTimeline` 未定义）。

- [ ] **步骤 3：实现 RunTimeline**

`frontend/src/components/RunTimeline.tsx`：
```tsx
import type { StepEvent } from '../api/types';

export function RunTimeline({ steps, active }: { steps: StepEvent[]; active: boolean }) {
  if (steps.length === 0 && !active) return null;
  return (
    <section aria-label="执行轨迹">
      {active && <p className="timeline-active">运行中…</p>}
      <ol className="timeline">
        {steps.map((s) => (
          <li key={s.seq} className="timeline-step">
            <span className={`layer-badge layer-${s.layer}`}>{s.layer}</span>
            <span className="step-event">{s.event}</span>
            <span className="step-detail">{s.detail}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd frontend && npx vitest run src/components/RunTimeline.test.tsx`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**
```bash
cd ..
git add frontend/src/components/RunTimeline.tsx frontend/src/components/RunTimeline.test.tsx
git commit -m "feat(frontend): RunTimeline 实时步骤时间线

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 9：ResultPanel 组件

**文件：**
- 创建：`frontend/src/components/ResultPanel.tsx`
- 测试：`frontend/src/components/ResultPanel.test.tsx`

**上下文：** 展示最终结果：成功/失败徽章、termination、`marked` 渲染的 Markdown 正文、证据列表（每条取 `meta.uri` 作链接，缺则不显示链接）。证据为空显示占位。

- [ ] **步骤 1：写失败的测试**

`frontend/src/components/ResultPanel.test.tsx`：
```tsx
import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ResultPanel } from './ResultPanel';
import type { ResultEvent } from '../api/types';

const base: ResultEvent = { success: true, output: '**加粗** 结论', evidence: [], termination: 'completed' };

test('渲染 Markdown 正文（加粗成 strong）', () => {
  const { container } = render(<ResultPanel result={base} />);
  expect(container.querySelector('strong')).toHaveTextContent('加粗');
});

test('成功徽章与 termination', () => {
  render(<ResultPanel result={base} />);
  expect(screen.getByText('成功')).toBeInTheDocument();
  expect(screen.getByText(/completed/)).toBeInTheDocument();
});

test('证据为空显示占位', () => {
  render(<ResultPanel result={base} />);
  expect(screen.getByText('无证据')).toBeInTheDocument();
});

test('证据带 meta.uri 渲染来源链接', () => {
  const withEv: ResultEvent = {
    ...base,
    evidence: [{ id: 'a', runId: 'r', kind: 'evidence', key: 'k', content: '片段', meta: { uri: 'http://x' } }],
  };
  render(<ResultPanel result={withEv} />);
  expect(screen.getByRole('link', { name: 'http://x' })).toHaveAttribute('href', 'http://x');
  expect(screen.getByText('片段')).toBeInTheDocument();
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd frontend && npx vitest run src/components/ResultPanel.test.tsx`
预期：FAIL（`ResultPanel` 未定义）。

- [ ] **步骤 3：实现 ResultPanel**

`frontend/src/components/ResultPanel.tsx`：
```tsx
import { marked } from 'marked';
import type { ResultEvent } from '../api/types';

export function ResultPanel({ result }: { result: ResultEvent }) {
  const html = marked.parse(result.output ?? '', { async: false }) as string;
  return (
    <section aria-label="运行结果">
      <header>
        <span className={result.success ? 'badge badge-ok' : 'badge badge-fail'}>
          {result.success ? '成功' : '失败'}
        </span>
        <span className="termination">结束原因：{result.termination}</span>
      </header>
      <article className="output" dangerouslySetInnerHTML={{ __html: html }} />
      <h3>证据</h3>
      {result.evidence.length === 0 ? (
        <p className="evidence-empty">无证据</p>
      ) : (
        <ul className="evidence">
          {result.evidence.map((a) => (
            <li key={a.id}>
              {a.meta?.uri && <a href={a.meta.uri} target="_blank" rel="noreferrer">{a.meta.uri}</a>}
              <p className="evidence-content">{a.content}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
```
> `marked.parse(..., { async: false })` 明确同步返回 `string`，避免类型为 `string | Promise<string>`。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd frontend && npx vitest run src/components/ResultPanel.test.tsx`
预期：PASS（4 个测试）。

- [ ] **步骤 5：Commit**
```bash
cd ..
git add frontend/src/components/ResultPanel.tsx frontend/src/components/ResultPanel.test.tsx
git commit -m "feat(frontend): ResultPanel 结果与证据展示

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 10：App 装配（状态机接线）+ 样式 + 冒烟测试

**文件：**
- 修改：`frontend/src/App.tsx`（整体替换占位版）
- 修改：`frontend/src/app.css`（补组件样式）
- 修改：`frontend/src/App.test.tsx`（整体替换为全流程冒烟）

**上下文：** `App` 用 `useRunStream` 串起三组件：`TaskForm.onSubmit → start`；运行中禁用表单；`RunTimeline` 显示步骤（`active = status==='streaming'`）；有 `result` 显示 `ResultPanel`；`status==='error'` 显示错误横幅 + 重试。

- [ ] **步骤 1：整体替换 App 冒烟测试**

`frontend/src/App.test.tsx`（整体替换）：
```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test } from 'vitest';
import App from './App';
import { MockEventSource, installMockEventSource } from './test/mockEventSource';

beforeEach(() => installMockEventSource());

test('提交→步骤→结果 全流程', async () => {
  render(<App />);
  await userEvent.type(screen.getByLabelText('问题/主题'), '上下文工程');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));

  const es = MockEventSource.last();
  es.emit('step', { seq: 0, layer: 'L3', event: 'model_step', detail: '思考' });
  es.emit('result', { success: true, output: '综述完成', evidence: [], termination: 'completed' });

  expect(await screen.findByText('L3')).toBeInTheDocument();
  expect(await screen.findByLabelText('运行结果')).toBeInTheDocument();
  expect(screen.getByText(/综述完成/)).toBeInTheDocument();
});

test('fail → 错误横幅可重试', async () => {
  render(<App />);
  await userEvent.type(screen.getByLabelText('问题/主题'), 'x');
  await userEvent.click(screen.getByRole('button', { name: '提交' }));
  MockEventSource.last().emit('fail', { message: '出错了' });

  expect(await screen.findByText(/出错了/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
});
```
> `App.test.tsx` 现引用 `MockEventSource`，运行需其在 `beforeEach` 装到全局。

- [ ] **步骤 2：运行测试验证失败**

运行：`cd frontend && npx vitest run src/App.test.tsx`
预期：FAIL（占位 App 没有表单/流程）。

- [ ] **步骤 3：实现 App**

`frontend/src/App.tsx`（整体替换）：
```tsx
import './app.css';
import { useRunStream } from './api/useRunStream';
import { TaskForm } from './components/TaskForm';
import { RunTimeline } from './components/RunTimeline';
import { ResultPanel } from './components/ResultPanel';

export default function App() {
  const run = useRunStream();
  const streaming = run.status === 'streaming';

  return (
    <main>
      <h1>Harness 学习小助手</h1>
      <TaskForm disabled={streaming} onSubmit={run.start} />

      {run.status === 'error' && (
        <div className="error-banner" role="alert">
          <span>{run.error}</span>
          <button type="button" onClick={run.reset}>重试</button>
        </div>
      )}

      <RunTimeline steps={run.steps} active={streaming} />
      {run.result && <ResultPanel result={run.result} />}
    </main>
  );
}
```
> 「重试」按钮调 `reset()` 回到 `idle`，清空时间线/结果，表单重新可用（用户改参数再次提交）。

- [ ] **步骤 4：补样式**

在 `frontend/src/app.css` 末尾追加：
```css
form { display: flex; flex-direction: column; gap: 12px; margin-bottom: 24px; }
form label { display: flex; flex-direction: column; gap: 4px; font-size: 14px; }
textarea, select { font: inherit; padding: 6px 8px; }
button { align-self: flex-start; padding: 8px 20px; cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; }

.error-banner { display: flex; justify-content: space-between; align-items: center;
  gap: 12px; padding: 10px 14px; margin-bottom: 16px;
  border: 1px solid #d33; border-radius: 6px; background: rgba(221,51,51,.08); }

.timeline { list-style: none; padding: 0; margin: 0 0 24px; }
.timeline-step { display: flex; gap: 10px; align-items: baseline;
  padding: 6px 0; border-bottom: 1px solid rgba(128,128,128,.2); }
.timeline-active { color: #888; font-style: italic; }
.layer-badge { flex: none; width: 30px; text-align: center; border-radius: 4px;
  font-size: 12px; font-weight: 600; color: #fff; background: #666; }
.layer-L1 { background:#2a7; } .layer-L2 { background:#37a; } .layer-L3 { background:#73a; }
.layer-L5 { background:#a63; } .layer-L6 { background:#a33; }
.step-event { font-weight: 600; }
.step-detail { color: #888; overflow-wrap: anywhere; }

.badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; color: #fff; }
.badge-ok { background:#2a7; } .badge-fail { background:#a33; }
.termination { margin-left: 10px; color:#888; }
.output { max-height: 480px; overflow: auto; line-height: 1.6; }
.evidence { padding-left: 18px; } .evidence-empty { color:#888; }
.evidence-content { margin: 4px 0 12px; color:#aaa; }
```

- [ ] **步骤 5：运行测试验证通过**

运行：`cd frontend && npx vitest run`
预期：全部前端测试 PASS（App 冒烟 + 前序组件/hook 全绿）。

- [ ] **步骤 6：Commit**
```bash
cd ..
git add frontend/src/App.tsx frontend/src/app.css frontend/src/App.test.tsx
git commit -m "feat(frontend): App 状态机接线 + 样式 + 全流程冒烟测试

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 任务 11：端到端构建集成 + 文档 + 全量回归

**文件：**
- 创建：`frontend/README.md`
- 修改：`CLAUDE.md`（加「前端」一节）

**上下文：** 收尾——确认前端能构建进 `static/`、`mvn test` 与 `npm test` 各自全绿、补构建文档。不联动 Maven 与 Node。

- [ ] **步骤 1：前端构建产物落位验证**

运行：
```bash
cd frontend && npm run build && ls ../src/main/resources/static
```
预期：`static/index.html` 与 `static/assets/` 存在。

- [ ] **步骤 2：前端全量测试**

运行：`cd frontend && npm test`
预期：全部前端测试 PASS。

- [ ] **步骤 3：后端全量回归**

运行：`cd .. && mvn clean test`
预期：BUILD SUCCESS 全绿（含新增 4 个后端测试；`mvn` 不触碰 Node）。

- [ ] **步骤 4：写前端 README**

`frontend/README.md`：
```markdown
# Harness 学习小助手 · 前端

Vite + React + TypeScript 单页应用。提交任务 → SSE 实时看 agent L1–L6 逐步进展 → 展示结果与证据。

## 开发

    npm install
    npm run dev      # 5173，代理 /runs* 到后端 8080

先启动后端：`mvn spring-boot:run`（需 DEEPSEEK_API_KEY 才能真跑 agent）。

## 测试

    npm test         # Vitest + React Testing Library，假 EventSource，无需后端

## 构建

    npm run build    # 产物输出到 ../src/main/resources/static，由 Spring Boot 同源托管

`src/main/resources/static/` 是构建产物，已 gitignore，不提交。`mvn` 不联动 Node。
```

- [ ] **步骤 5：CLAUDE.md 补「前端」一节**

在 `CLAUDE.md` 的「命令」小节后插入：
```markdown
## 前端（子项目 D）

`frontend/` 是 Vite + React + TS 单页应用，SSE 实时展示 agent 执行。
- 开发：`cd frontend && npm run dev`（代理 `/runs*`→8080）
- 测试：`cd frontend && npm test`（Vitest，假 EventSource，无需后端/API key）
- 构建：`cd frontend && npm run build`（产物入 `src/main/resources/static/`，已 gitignore）
- `mvn` 不联动 Node；后端 SSE 端点 `GET /runs/stream?type=&query=`。
```

- [ ] **步骤 6：Commit**
```bash
git add frontend/README.md CLAUDE.md
git commit -m "docs(frontend): 前端构建/测试文档 + CLAUDE.md 前端节

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 完成后

所有任务完成后，用 superpowers:finishing-a-development-branch 收尾（推分支 + 开草稿 PR）。当前分支：`worktree-frontend-sse-mvp`（基于 master）。

**验收标准回顾（对照规格）：**
- 后端 `RunEventBus` + `@Primary CompositeTraceStore` + `RunStreamController`（`GET /runs/stream`）齐备；`AgentLoop` 类零改动，仅 `AgentConfig` 一处参数类型变更。
- SSE 逐步推 `event:step`、结束推 `event:result`、异常推 `event:fail`；非法 type/空 query 返 400；全线 best-effort。
- 前端 `frontend/` Vite+React+TS，`TaskForm`/`RunTimeline`/`ResultPanel` + `useRunStream` 状态机；构建产物入 `static/` 同源托管。
- `POST /runs` 与 `AgentControllerTest` 不动；`mvn clean test` 全绿且不联动 Node；`npm test` 全绿。
- 非目标（完整 trace 可视化、run 历史、语料管理、逐 token 流、鉴权、Maven 联动 Node）均未做。
