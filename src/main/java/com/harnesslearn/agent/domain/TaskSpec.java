package com.harnesslearn.agent.domain;
import java.util.Map;
public record TaskSpec(String runId, TaskType type, String userQuery, Map<String,Object> params) {}
