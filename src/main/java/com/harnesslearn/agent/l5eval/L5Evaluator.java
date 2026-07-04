package com.harnesslearn.agent.l5eval;

import com.harnesslearn.agent.domain.*;
import java.util.List;

/**
 * L5 评估与观测层：独立于生成过程的验证器。
 *
 * <p>以 critic（审查员）人格，只依据【产出】和【证据】判断 Agent 输出是否合格，
 * 输出结构化 {@link Verdict}（pass/confidence/issues），忽略任何生成过程。
 */
public interface L5Evaluator {
    Verdict verify(TaskSpec task, AgentOutput output, List<Artifact> evidence);
}
