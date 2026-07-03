package com.harnesslearn.agent.domain;
import java.util.Map;
public record Artifact(String id, String runId, String kind, String key, String content, Map<String,String> meta) {}
