package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.WorkingState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteWorkingStateStoreTest {
    private JdbcTemplate memoryJdbc() {
        var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            "jdbc:sqlite:file:memdb_ws?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void checkpointAndReloadRoundTrips() {
        JdbcTemplate jt = memoryJdbc();
        var store = new SqliteWorkingStateStore(jt);
        WorkingState s = WorkingState.start("run1", "目标X", 5);
        s.recordStep("step-a");
        store.checkpoint("run1", s);

        WorkingState loaded = store.load("run1");
        assertThat(loaded.goal()).isEqualTo("目标X");
        assertThat(loaded.completedSteps()).containsExactly("step-a");
        assertThat(loaded.budgetRemaining()).isEqualTo(4);
    }
}
