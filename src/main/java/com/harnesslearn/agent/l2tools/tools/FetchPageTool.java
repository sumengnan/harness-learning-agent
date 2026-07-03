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
