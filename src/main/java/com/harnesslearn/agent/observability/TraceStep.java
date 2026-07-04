package com.harnesslearn.agent.observability;
public record TraceStep(String runId, int seq, String layer, String event, String detail) {}
