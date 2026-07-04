package com.harnesslearn.agent.support;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可编排返回序列的假模型；用于确定性测试自主循环。
 *
 * <p>{@link ChatLanguageModel} 在 langchain4j 0.35.0 中只有
 * {@link #generate(List)} 是抽象方法；两参数重载
 * {@code generate(List, List)} 是 default 方法，其默认实现会直接抛出
 * {@code IllegalArgumentException("Tools are currently not supported by
 * this model")}。因为本项目的测试（含 {@code FakeChatModelTest}）会调用带
 * {@code toolSpecs} 的重载，这里必须显式覆盖该方法，否则调用会直接抛异常。
 */
public class FakeChatModel implements ChatLanguageModel {
    private final Deque<AiMessage> scripted;
    private final AtomicInteger calls = new AtomicInteger(0);
    private final List<List<ChatMessage>> received = new java.util.ArrayList<>();

    private FakeChatModel(List<AiMessage> messages) {
        this.scripted = new ArrayDeque<>(messages);
    }

    public static FakeChatModel scripted(AiMessage... messages) {
        return new FakeChatModel(List.of(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public synchronized Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
        calls.incrementAndGet();
        received.add(List.copyOf(messages));
        AiMessage next = scripted.poll();
        if (next == null) {
            next = AiMessage.from("[no more scripted responses]");
        }
        return Response.from(next);
    }

    public int callCount() {
        return calls.get();
    }

    /** 每次 generate 收到的完整消息序列（用于断言提示词内容，如重试是否回灌错误反馈）。 */
    public List<List<ChatMessage>> receivedPrompts() {
        return List.copyOf(received);
    }
}
