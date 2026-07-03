package com.harnesslearn.agent.llm;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
    /**
     * {@code @Lazy}：OpenAiChatModel.builder().build() 在 api-key 为空/null 时会立即抛出
     * IllegalArgumentException（见 openai4j OpenAiClient.Builder#openAiApiKey）。测试/CI
     * 环境下 DEEPSEEK_API_KEY 通常未设置（application.yml 中默认空字符串），若此 bean 采用
     * 默认的 eager 单例创建，会导致所有加载完整 Spring 上下文的测试（如
     * AgentApplicationTest#contextLoads）在启动阶段失败。加 @Lazy 让 bean 延迟到真正被注入使用
     * 时才构建，使上下文测试不依赖是否配置了真实 API key。
     */
    @Bean
    @Lazy
    public ChatLanguageModel chatLanguageModel(LlmProperties props) {
        return OpenAiChatModel.builder()
            .baseUrl(props.baseUrl())
            .apiKey(props.apiKey())
            .modelName(props.modelName())
            .temperature(props.temperature())
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    /**
     * {@code @Lazy}：BgeSmallZhEmbeddingModel 会在构造时加载较重的本地 ONNX 模型（数十 MB
     * 权重 + tokenizer 原生库解压）。若采用默认 eager 单例创建，所有加载完整 Spring 上下文的
     * 测试（如 AgentApplicationTest#contextLoads）都会在启动阶段付出这份加载开销。加 @Lazy
     * 让模型延迟到真正被注入使用时才构建，避免拖慢上下文测试。
     */
    @Bean
    @Lazy
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }
}
