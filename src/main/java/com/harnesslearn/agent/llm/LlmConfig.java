package com.harnesslearn.agent.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    @Bean
    public dev.langchain4j.model.embedding.EmbeddingModel embeddingModel() {
        return new dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel();
    }

    @Bean
    public dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> embeddingStore() {
        return new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
    }
}
