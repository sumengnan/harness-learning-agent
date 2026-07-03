package com.harnesslearn.agent.domain;
public record AgentRun(String runId, AgentOutput output, boolean success, String terminationReason) {}
