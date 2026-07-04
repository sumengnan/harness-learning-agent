package com.harnesslearn.agent;

import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l1context.L1ContextAssembler;
import com.harnesslearn.agent.l2tools.DefaultL2ToolSystem;
import com.harnesslearn.agent.l2tools.L2ToolSystem;
import com.harnesslearn.agent.l2tools.RelevanceFilter;
import com.harnesslearn.agent.l2tools.Tool;
import com.harnesslearn.agent.l2tools.ToolRegistry;
import com.harnesslearn.agent.l3orchestrate.AgentLoop;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import com.harnesslearn.agent.l4memory.SqliteArtifactStore;
import com.harnesslearn.agent.l4memory.SqliteWorkingStateStore;
import com.harnesslearn.agent.l4memory.VectorLongTermMemory;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l5eval.LlmL5Evaluator;
import com.harnesslearn.agent.l6guardrail.DefaultL6Guardrail;
import com.harnesslearn.agent.l6guardrail.L6Guardrail;
import com.harnesslearn.agent.l6guardrail.RecoveryPolicy;
import com.harnesslearn.agent.observability.LoggingChatModelListener;
import com.harnesslearn.agent.observability.SqliteTraceStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 各层（L1–L6）+ 各 store + AgentLoop + trace 的 Spring 装配。 */
@Configuration
public class AgentConfig {

    /** RelevanceFilter 的领域锚点，构造时用于计算相关性质心；不能为空。 */
    private static final List<String> AGENT_ANCHORS = List.of(
        "AI agent 上下文工程与 harness 架构设计",
        "大模型工具调用、执行编排与多步推理",
        "智能体记忆、工作状态管理与长期记忆",
        "Agent 评估、可观测性与约束校验恢复");

    @Bean
    public SchemaInitializer schemaInitializer(JdbcTemplate jdbc) {
        return new SchemaInitializer(jdbc);
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    public ApplicationRunner schemaBootstrap(SchemaInitializer schema) {
        return args -> schema.init();
    }

    @Bean
    public SqliteWorkingStateStore workingStateStore(JdbcTemplate jdbc) {
        return new SqliteWorkingStateStore(jdbc);
    }

    @Bean
    public SqliteArtifactStore artifactStore(JdbcTemplate jdbc) {
        return new SqliteArtifactStore(jdbc);
    }

    @Bean
    public SqliteTraceStore traceStore(JdbcTemplate jdbc) {
        return new SqliteTraceStore(jdbc);
    }

    @Bean
    public LongTermMemory longTermMemory(EmbeddingModel embed, EmbeddingStore<TextSegment> store) {
        return new VectorLongTermMemory(embed, store);
    }

    @Bean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    public RelevanceFilter relevanceFilter(EmbeddingModel embed,
            @Value("${agent.filter.relevance-threshold:0.80}") double threshold,
            @Value("${agent.filter.borderline-delta:0.05}") double delta) {
        return new RelevanceFilter(embed, AGENT_ANCHORS, threshold, delta);
    }

    @Bean
    public L2ToolSystem l2ToolSystem(ToolRegistry r, RelevanceFilter f) {
        return new DefaultL2ToolSystem(r, f);
    }

    @Bean
    public L1ContextAssembler l1(@Value("${agent.l1.max-info:12}") int maxInfo) {
        return new DefaultL1ContextAssembler(maxInfo);
    }

    @Bean
    public L5Evaluator l5(@org.springframework.context.annotation.Lazy ChatLanguageModel model) {
        return new LlmL5Evaluator(model);
    }

    @Bean
    public RecoveryPolicy recoveryPolicy(@Value("${agent.recovery.max-retries:2}") int maxRetries) {
        return new RecoveryPolicy(maxRetries);
    }

    @Bean
    public L6Guardrail l6(RecoveryPolicy p) {
        return new DefaultL6Guardrail(p);
    }

    @Bean
    public L3Orchestrator agentLoop(@org.springframework.context.annotation.Lazy ChatLanguageModel model,
            L1ContextAssembler l1, L2ToolSystem l2,
            L5Evaluator l5, L6Guardrail l6, SqliteTraceStore trace,
            SqliteWorkingStateStore wss, SqliteArtifactStore artifacts,
            @Value("${agent.orchestrate.max-steps:20}") int maxSteps) {
        return new AgentLoop(model, l1, l2, l5, l6, maxSteps, trace, wss, artifacts);
    }

    @Bean
    public LoggingChatModelListener loggingChatModelListener() {
        Logger log = LoggerFactory.getLogger("llm.trace");
        return new LoggingChatModelListener(log::info);
    }
}
