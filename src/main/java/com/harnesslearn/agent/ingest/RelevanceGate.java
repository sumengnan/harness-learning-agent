package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.RetrievedChunk;
import com.harnesslearn.agent.l2tools.RelevanceFilter;
import java.util.List;

/**
 * 相关性闸门：用与 Agent 运行时相同的 {@link RelevanceFilter}（同一把余弦-质心尺子）
 * 判正文是否属 harness 主题。把正文包成单元素 RetrievedChunk 交给 filter，kept 非空即相关。
 */
public class RelevanceGate {
    private final RelevanceFilter filter;
    public RelevanceGate(RelevanceFilter filter) { this.filter = filter; }

    public boolean isRelevant(String body) {
        if (body == null || body.isBlank()) return false;
        var probe = new RetrievedChunk("gate", "", body, 0.0);
        return !filter.filter(List.of(probe)).kept().isEmpty();
    }
}
