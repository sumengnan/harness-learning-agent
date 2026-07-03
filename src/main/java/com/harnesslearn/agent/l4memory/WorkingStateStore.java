package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.WorkingState;
public interface WorkingStateStore {
    void checkpoint(String runId, WorkingState state);
    WorkingState load(String runId);
}
