package com.harnesslearn.agent.domain;
import java.util.List;
public record AgentOutput(String content, List<Artifact> evidence) {}
