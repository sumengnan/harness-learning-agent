package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import java.util.List;
public interface ArtifactStore {
    /** 写入或按主键 id 覆盖（INSERT OR REPLACE）一个中间产物。 */
    void put(Artifact a);

    /**
     * 按 (runId, kind) 检索中间产物。
     *
     * <p>与 {@link WorkingStateStore#load} 不同：未命中时返回**空 List**，不抛异常。
     */
    List<Artifact> query(ArtifactQuery q);
}
