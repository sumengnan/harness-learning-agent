package com.harnesslearn.agent.l1context;
import com.harnesslearn.agent.domain.*;
import java.util.List;
public interface L1ContextAssembler {
    AssembledContext assemble(TaskSpec task, WorkingState state, List<RetrievedChunk> candidates);
}
