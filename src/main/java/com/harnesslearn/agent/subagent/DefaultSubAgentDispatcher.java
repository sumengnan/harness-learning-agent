package com.harnesslearn.agent.subagent;

import java.util.List;
import java.util.concurrent.*;

public class DefaultSubAgentDispatcher implements SubAgentDispatcher {
    @Override
    public <I,O> O dispatch(SubAgent<I,O> agent, I input, O fallback) {
        try { return agent.run(input); }
        catch (Exception e) { return fallback; }
    }
    @Override
    public <I,O> List<O> dispatchParallel(SubAgent<I,O> agent, List<I> inputs, O fallback) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<O>> futures = inputs.stream()
                .map(in -> exec.submit(() -> agent.run(in))).toList();
            return futures.stream().map(f -> {
                try { return f.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return fallback; }
                catch (Exception e) { return fallback; }
            }).toList();
        }
    }
}
