package com.harnesslearn.agent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm")
public record LlmProperties(String baseUrl, String apiKey, String modelName, Double temperature) {}
