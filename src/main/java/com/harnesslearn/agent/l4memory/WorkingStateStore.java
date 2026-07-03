package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.WorkingState;
public interface WorkingStateStore {
    /** 写入或覆盖（UPSERT）指定 run 的工作状态检查点。 */
    void checkpoint(String runId, WorkingState state);

    /**
     * 加载指定 run 的工作状态。
     *
     * @throws org.springframework.dao.EmptyResultDataAccessException 若不存在该 runId 的检查点
     */
    WorkingState load(String runId);
}
