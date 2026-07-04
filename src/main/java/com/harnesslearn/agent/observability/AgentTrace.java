package com.harnesslearn.agent.observability;
import java.util.List;
public record AgentTrace(String runId, List<TraceStep> steps) {}
