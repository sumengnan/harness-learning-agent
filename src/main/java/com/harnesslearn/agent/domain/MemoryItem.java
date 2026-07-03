package com.harnesslearn.agent.domain;
import java.util.Map;
public record MemoryItem(String text, Map<String,String> meta) {}
