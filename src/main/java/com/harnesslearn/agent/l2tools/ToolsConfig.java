package com.harnesslearn.agent.l2tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.l2tools.tools.FetchPageTool;
import com.harnesslearn.agent.l2tools.tools.LocalRetrieveTool;
import com.harnesslearn.agent.l2tools.tools.WebSearchTool;
import com.harnesslearn.agent.l2tools.tools.WebSearchTool.SearchBackend;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 工具层装配：3 个工具 bean + Tavily Web 搜索后端。 */
@Configuration
public class ToolsConfig {

    /**
     * Tavily 搜索后端。当未配置 api-key 时，{@code search(...)} 被调用时才抛异常，
     * 使上层 WebSearchTool 捕获后降级（bean 构造阶段不抛，保证无 key 时上下文仍可启动）。
     */
    @Bean
    public SearchBackend tavilyBackend(@Value("${agent.tools.tavily-api-key:}") String apiKey) {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
        return query -> {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("未配置 TAVILY_API_KEY，Web 搜索不可用");
            }
            String body = mapper.createObjectNode()
                .put("api_key", apiKey)
                .put("query", query)
                .put("max_results", 5)
                .toString();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Tavily 搜索失败, HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    sb.append("- ").append(r.path("title").asText(""))
                      .append(" | ").append(r.path("url").asText(""))
                      .append("\n").append(r.path("content").asText("")).append("\n\n");
                }
            }
            return sb.toString().isBlank() ? resp.body() : sb.toString();
        };
    }

    @Bean
    public Tool localRetrieveTool(LongTermMemory memory) { return new LocalRetrieveTool(memory); }

    @Bean
    public Tool fetchPageTool() { return new FetchPageTool(); }

    @Bean
    public Tool webSearchTool(SearchBackend backend) { return new WebSearchTool(backend); }
}
