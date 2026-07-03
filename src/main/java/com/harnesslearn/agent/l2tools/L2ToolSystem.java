package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.DistilledResult;
import com.harnesslearn.agent.domain.ToolCall;
import java.util.List;

/** L2 工具系统门面：派发工具执行 → 提炼为块 → 相关性过滤 → 返回 DistilledResult。 */
public interface L2ToolSystem {
    List<String> availableTools();
    DistilledResult invoke(ToolCall call);
}
