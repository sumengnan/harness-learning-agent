package com.harnesslearn.agent.api;

import com.harnesslearn.agent.api.dto.RunResultDto;
import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;
import com.harnesslearn.agent.domain.TaskType;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import com.harnesslearn.agent.observability.RunEventBus;
import com.harnesslearn.agent.observability.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SSE 端点：GET /runs/stream。虚拟线程跑同步 orchestrator.run，逐步推 event:step，
 * 结束推 event:result，异常推 event:fail。全线 best-effort，不拖垮进程。
 */
@RestController
public class RunStreamController {
    private static final Logger log = LoggerFactory.getLogger(RunStreamController.class);
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    private final L3Orchestrator orchestrator;
    private final RunEventBus bus;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public RunStreamController(L3Orchestrator orchestrator, RunEventBus bus) {
        this.orchestrator = orchestrator;
        this.bus = bus;
    }

    @GetMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String type, @RequestParam String query) {
        TaskType taskType;
        try {
            taskType = TaskType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知任务类型: " + type);
        }
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }

        String runId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        BlockingQueue<TraceStep> queue = bus.subscribe(runId);

        emitter.onCompletion(() -> bus.unsubscribe(runId));
        emitter.onTimeout(() -> { bus.unsubscribe(runId); emitter.complete(); });
        emitter.onError(e -> bus.unsubscribe(runId));

        exec.submit(() -> pump(emitter, queue, runId, taskType, query));
        return emitter;
    }

    private void pump(SseEmitter emitter, BlockingQueue<TraceStep> queue,
                      String runId, TaskType type, String query) {
        Future<AgentRun> runF = exec.submit(
            () -> orchestrator.run(new TaskSpec(runId, type, query, Map.of())));
        try {
            while (!runF.isDone()) {
                TraceStep s = queue.poll(200, TimeUnit.MILLISECONDS);
                if (s != null) emitter.send(SseEmitter.event().name("step").data(s, MediaType.APPLICATION_JSON));
            }
            TraceStep s;                                  // run 已结束，drain 剩余步骤
            while ((s = queue.poll()) != null) {
                emitter.send(SseEmitter.event().name("step").data(s, MediaType.APPLICATION_JSON));
            }
            AgentRun run = runF.get();                    // 可能抛 ExecutionException
            emitter.send(SseEmitter.event().name("result").data(
                new RunResultDto(run.success(), run.output().content(),
                    run.output().evidence(), run.terminationReason()),
                MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 运行异常，推 fail: runId={}", runId, e);
            try {
                emitter.send(SseEmitter.event().name("fail").data(
                    Map.of("message", String.valueOf(e.getMessage())), MediaType.APPLICATION_JSON));
            } catch (Exception ignore) { /* 连接可能已断，忽略 */ }
            emitter.complete();
        } finally {
            bus.unsubscribe(runId);
        }
    }
}
