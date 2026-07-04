package com.harnesslearn.agent.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时轮询触发增量采集。cron 来自 agent.ingest.poll-cron。
 * 顶层 try/catch：整轮异常吞掉并 WARN，绝不让 @Scheduled 单线程因异常静默停摆。
 */
public class IngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
    private final IngestionService service;

    public IngestionScheduler(IngestionService service) { this.service = service; }

    @Scheduled(cron = "${agent.ingest.poll-cron}")
    public void poll() {
        try {
            service.ingestAll();
        } catch (RuntimeException e) {
            log.warn("定时采集整轮异常，已吞掉以保调度存活", e);
        }
    }
}
