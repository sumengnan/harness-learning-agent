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

class AgentLoopPersistenceTest {

    private JdbcTemplate jdbc;
    private SqliteWorkingStateStore wss;
    private SqliteArtifactStore artifacts;

    @BeforeEach
    void setUp() {
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
        WorkingState loaded = wss.load("run-p1");
        assertThat(loaded.completedSteps()).isNotEmpty();
    }

    @Test
    void persistsEvidenceArtifactWithRunIdPrefixedId() {
        loopWith(planner(), stubL2("c1"), wss, artifacts)
            .run(new TaskSpec("run-p2", TaskType.SURVEY, "综述", Map.of()));
        List<Artifact> ev = artifacts.query(new ArtifactQuery("run-p2", "evidence"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).id()).isEqualTo("run-p2:c1");
        assertThat(ev.get(0).content()).contains("上下文工程");
    }

    @Test
    void crossRunSameChunkIdDoNotCollide() {
        loopWith(planner(), stubL2("dup"), wss, artifacts)
            .run(new TaskSpec("runA", TaskType.SURVEY, "A", Map.of()));
        loopWith(planner(), stubL2("dup"), wss, artifacts)
            .run(new TaskSpec("runB", TaskType.SURVEY, "B", Map.of()));
        assertThat(artifacts.query(new ArtifactQuery("runA", "evidence"))).hasSize(1);
        assertThat(artifacts.query(new ArtifactQuery("runB", "evidence"))).hasSize(1);
    }

    @Test
    void persistenceFailureIsBestEffortRunStillCompletes() {
        WorkingStateStore boomW = new WorkingStateStore() {
            @Override public void checkpoint(String runId, WorkingState s) { throw new RuntimeException("boom-w"); }
            @Override public WorkingState load(String runId) { throw new RuntimeException("boom-w"); }
        };
        ArtifactStore boomA = new ArtifactStore() {
            @Override public void put(Artifact a) { throw new RuntimeException("boom-a"); }
            @Override public List<Artifact> query(ArtifactQuery q) { return List.of(); }
        };
        // 落盘全程抛异常，但 best-effort 应让主循环照常跑完 tool→final 并成功
        AgentRun run = loopWith(planner(), stubL2("c1"), boomW, boomA)
            .run(new TaskSpec("run-boom", TaskType.SURVEY, "综述", Map.of()));
        assertThat(run.success()).isTrue();
    }
}
