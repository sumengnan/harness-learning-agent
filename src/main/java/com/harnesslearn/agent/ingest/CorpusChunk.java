package com.harnesslearn.agent.ingest;

/** 落库/重建用的一个语料块。id 为确定性主键 = 指纹 + ":" + seq。 */
public record CorpusChunk(String id, String sourceId, String url,
                          String title, int seq, String text, Long publishedTs) {}
