package com.harnesslearn.agent.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * 采集管道配置。绑定 application.yml 的 agent.ingest.*：
 * enabled 总开关、pollCron 轮询 cron、chunkMaxChars 切块上限、sources 来源清单。
 */
@ConfigurationProperties(prefix = "agent.ingest")
public record IngestProperties(
    boolean enabled, String pollCron, int chunkMaxChars, List<Source> sources) {}
