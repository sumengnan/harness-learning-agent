package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import java.util.List;
public interface LongTermMemory {
    void remember(MemoryItem item);

    /**
     * 按语义相似度检索最相关的若干条记忆。
     * <p>契约：
     * <ul>
     *   <li>{@code k} 即底层向量库的 {@code maxResults}，<b>无相关性下限（无 minScore）</b>：
     *       只要库非空就返回最多 k 条；库内条数 &lt; k 时返回更少（可能为空列表）。</li>
     *   <li>返回的 {@link RetrievedChunk#relevanceScore()} 来自 langchain4j 的
     *       {@code EmbeddingMatch#score()}，语义为归一化到 [0,1] 的余弦相似度。</li>
     *   <li>{@link RetrievedChunk#sourceUri()} 取自 segment metadata 的 "uri" 键；
     *       当该键缺失时为 <b>{@code null}</b>（{@code Metadata.getString} 缺键返回 null，不抛异常），
     *       调用方需自行处理 null。</li>
     * </ul>
     */
    List<RetrievedChunk> retrieve(String query, int k);
}
