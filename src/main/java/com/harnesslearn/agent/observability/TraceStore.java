package com.harnesslearn.agent.observability;
import java.util.List;
public interface TraceStore {
    void append(TraceStep step);
    List<TraceStep> load(String runId);
}
