package com.harnesslearn.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 进程内事件总线：按 runId 把 agent 每步 {@link TraceStep} 分发给一个订阅者（SSE 连接）。
 * 有界队列 + offer（不阻塞、满即丢），无订阅者时 publish 空转——全线 best-effort。
 */
public class RunEventBus {
    private static final Logger log = LoggerFactory.getLogger(RunEventBus.class);
    private static final int CAPACITY = 1000;
    private final ConcurrentHashMap<String, BlockingQueue<TraceStep>> subs = new ConcurrentHashMap<>();

    /** 为某次运行注册订阅，返回其事件队列。SSE 端点持有此队列拉取。 */
    public BlockingQueue<TraceStep> subscribe(String runId) {
        BlockingQueue<TraceStep> q = new LinkedBlockingQueue<>(CAPACITY);
        subs.put(runId, q);
        return q;
    }

    /** 投递一步给该 runId 的订阅者；无订阅者空转；队列满则丢弃并 WARN（best-effort）。 */
    public void publish(String runId, TraceStep step) {
        BlockingQueue<TraceStep> q = subs.get(runId);
        if (q == null) return;
        if (!q.offer(step)) {
            log.warn("SSE 事件队列已满，丢弃一步: runId={}, seq={}", runId, step.seq());
        }
    }

    /** 注销订阅，移除队列引用防泄漏。SSE 结束/超时/断开时调用。 */
    public void unsubscribe(String runId) {
        subs.remove(runId);
    }
}
