package com.harnesslearn.agent.api.dto;

import com.harnesslearn.agent.domain.Artifact;
import java.util.List;

/** SSE `event: result` 载荷：一次运行的最终结果。 */
public record RunResultDto(boolean success, String output, List<Artifact> evidence, String termination) {}
