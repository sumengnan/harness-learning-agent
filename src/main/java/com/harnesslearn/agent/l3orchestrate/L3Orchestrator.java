package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;

/** L3 执行编排层门面：接收任务规格，跑完自主循环，返回一次完整运行结果。 */
public interface L3Orchestrator {
    AgentRun run(TaskSpec task);
}
