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
