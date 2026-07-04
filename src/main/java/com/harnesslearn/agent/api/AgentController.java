package com.harnesslearn.agent.api;

import com.harnesslearn.agent.api.dto.RunRequest;
import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
public class AgentController {
    private final L3Orchestrator orchestrator;
    public AgentController(L3Orchestrator orchestrator) { this.orchestrator = orchestrator; }

    @PostMapping("/runs")
    public Map<String,Object> run(@RequestBody RunRequest req) {
        TaskSpec task = new TaskSpec(UUID.randomUUID().toString(),
            TaskType.valueOf(req.type()), req.query(), Map.of());
        AgentRun run = orchestrator.run(task);
        return Map.of("runId", run.runId(), "success", run.success(),
            "output", run.output().content(), "termination", run.terminationReason());
    }
}
