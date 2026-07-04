package com.harnesslearn.agent.domain;
public record RetrievedChunk(String id, String sourceUri, String text, double relevanceScore) {}
