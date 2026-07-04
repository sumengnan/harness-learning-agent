package com.harnesslearn.agent.subagent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.List;

/** 有界子任务：把单个来源压成结构化要点，主 Agent 只看摘要（上下文隔离）。 */
public class SourceSummarizer implements SubAgent<SourceSummarizer.Input, String> {
    public record Input(String sourceUri, String rawText) {}
    private static final String SYS = """
        你只做一件事：把给定来源压缩成 3~6 条与 AI agent harness 相关的要点，
        每条尽量可引用。忽略无关内容。只输出要点列表。""";
    private final ChatLanguageModel model;
    public SourceSummarizer(ChatLanguageModel model) { this.model = model; }
    @Override public String name() { return "source_summarizer"; }
    @Override public String run(Input in) {
        return model.generate(List.of(SystemMessage.from(SYS),
            UserMessage.from("来源: " + in.sourceUri() + "\n正文:\n" + in.rawText())),
            List.of()).content().text();
    }
}
