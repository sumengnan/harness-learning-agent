package com.harnesslearn.agent.ingest;

import java.util.List;

/** 一个配置来源。 */
public record Source(String id, SourceType type, String url, List<String> tags) {}
