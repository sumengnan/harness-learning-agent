package com.harnesslearn.agent.domain;

/** 失败类型词表：L3/L5/子Agent 生产、L6 RecoveryPolicy 消费的单一真相源，避免字面量跨层漂移。 */
public final class FailureTypes {
    private FailureTypes() {}
    public static final String WEB_SEARCH_FAILED = "web_search_failed";
    public static final String FETCH_PAGE_FAILED = "fetch_page_failed";
    public static final String EVIDENCE_INSUFFICIENT = "evidence_insufficient";
    public static final String SUBAGENT_FAILED = "subagent_failed";
    public static final String INVALID_OUTPUT = "invalid_output";
    public static final String INVALID_TOOL_ARGS = "invalid_tool_args";
    public static final String VERIFICATION_FAILED = "verification_failed";
    public static final String LLM_CALL_FAILED = "llm_call_failed";
    public static final String BUDGET_EXHAUSTED = "budget_exhausted";
    public static final String LOOP_DETECTED = "loop_detected";
}
