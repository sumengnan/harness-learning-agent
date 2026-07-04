package com.harnesslearn.agent.l2tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.UUID;

/**
 * L2 工具系统默认实现：接收一个 {@link ToolCall}，经 {@link ToolRegistry} 派发到具体工具，
 * 把原始返回内容提炼成 {@link RetrievedChunk} 列表，再经 {@link RelevanceFilter} 剔除
 * 不相关/重复块，最终包成 {@link DistilledResult}。
 */
public class DefaultL2ToolSystem implements L2ToolSystem {
    private static final Logger log = LoggerFactory.getLogger(DefaultL2ToolSystem.class);

    private final ToolRegistry registry;
    private final RelevanceFilter filter;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultL2ToolSystem(ToolRegistry registry, RelevanceFilter filter) {
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        if (filter == null) throw new IllegalArgumentException("filter 不能为空");
        this.registry = registry;
        this.filter = filter;
    }

    @Override public List<String> availableTools() { return registry.names(); }

    @Override
    public DistilledResult invoke(ToolCall call) {
        ToolResult raw = registry.get(call.name()).execute(call);
        if (!raw.ok()) return new DistilledResult(List.of(), 0, "工具失败: " + raw.error());
        List<RetrievedChunk> chunks = toChunks(raw.rawContent());
        RelevanceFilter.Result filtered = filter.filter(chunks);
        return new DistilledResult(filtered.kept(), filtered.droppedCount(),
            "保留 %d 块，过滤 %d 块".formatted(filtered.kept().size(), filtered.droppedCount()));
    }

    /**
     * 把工具原始内容提炼成块：优先按结构化 chunk 数组解析（如 local_retrieve）；
     * 解析失败则视为纯文本（如 fetch_page / web_search），整体包成单块。
     */
    private List<RetrievedChunk> toChunks(String content) {
        try {
            // 结构化 chunk 列表（local_retrieve）
            return List.of(mapper.readValue(content, RetrievedChunk[].class));
        } catch (Exception e) {
            // 纯文本（fetch_page / web_search）→ 单块。
            // 风险：若工具本意返回结构化列表但字段名漂移/格式变化，也会在此解析失败并
            // 坍缩为单个 inline 块（丢失分块粒度）——这是已知降级路径，靠下方 debug 日志留痕。
            log.debug("结构化解析失败，降级为纯文本单块: {}", e.getMessage());
            return List.of(new RetrievedChunk(UUID.randomUUID().toString(), "inline", content, 0));
        }
    }
}
