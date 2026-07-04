package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.WorkingState;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Arrays;
import java.util.List;

/**
 * WorkingState 的 SQLite 持久化。
 *
 * <p>列表字段（completed_steps / open_questions）以换行符 {@code \n} join 后存入单个 TEXT 列。
 * 这依赖一个前提：单条步骤描述 / 开放问题内**不含**内嵌换行——由 L3 编排层保证。
 * 若违反该前提，{@link #load} 会把一条记录静默拆成多条，造成数据损坏。
 */
public class SqliteWorkingStateStore implements WorkingStateStore {
    private final JdbcTemplate jdbc;
    public SqliteWorkingStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void checkpoint(String runId, WorkingState s) {
        jdbc.update("""
            INSERT INTO working_state(run_id,goal,step_budget,completed_steps,open_questions)
            VALUES(?,?,?,?,?)
            ON CONFLICT(run_id) DO UPDATE SET
              goal=excluded.goal, step_budget=excluded.step_budget,
              completed_steps=excluded.completed_steps, open_questions=excluded.open_questions""",
            runId, s.goal(), s.budgetRemaining() + s.stepsUsed(),
            String.join("\n", s.completedSteps()), String.join("\n", s.openQuestions()));
    }

    @Override
    public WorkingState load(String runId) {
        return jdbc.queryForObject(
            "SELECT goal,step_budget,completed_steps,open_questions FROM working_state WHERE run_id=?",
            (rs, n) -> {
                WorkingState s = WorkingState.start(runId, rs.getString("goal"), rs.getInt("step_budget"));
                for (String step : splitNonEmpty(rs.getString("completed_steps"))) s.recordStep(step);
                for (String q : splitNonEmpty(rs.getString("open_questions"))) s.addOpenQuestion(q);
                return s;
            }, runId);
    }

    private static List<String> splitNonEmpty(String v) {
        if (v == null || v.isEmpty()) return List.of();
        return Arrays.asList(v.split("\n"));
    }
}
