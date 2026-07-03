package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import java.util.List;
public interface LongTermMemory {
    void remember(MemoryItem item);
    List<RetrievedChunk> retrieve(String query, int k);
}
