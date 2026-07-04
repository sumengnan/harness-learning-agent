package com.harnesslearn.agent.domain;
import java.util.List;
public record DistilledResult(List<RetrievedChunk> chunks, int droppedCount, String note) {}
