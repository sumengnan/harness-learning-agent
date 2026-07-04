package com.harnesslearn.agent.ingest;

/** FeedReader 从来源解析出的一个条目（尚未抓正文）。 */
public record FeedItem(String guid, String url, String title, Long publishedEpochMs) {}
