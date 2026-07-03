package com.harnesslearn.agent.l4memory;
import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import java.util.List;
public interface ArtifactStore {
    void put(Artifact a);
    List<Artifact> query(ArtifactQuery q);
}
