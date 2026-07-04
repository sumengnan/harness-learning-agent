package com.harnesslearn.agent.api;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {
    @Autowired MockMvc mvc;
    @MockBean L3Orchestrator orchestrator;

    @Test
    void runReturnsOutput() throws Exception {
        when(orchestrator.run(any())).thenReturn(new AgentRun("run1",
            new AgentOutput("综述结果", List.of()), true, "completed"));
        mvc.perform(post("/runs").contentType("application/json")
                .content("{\"type\":\"SURVEY\",\"query\":\"综述上下文工程\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.output").value("综述结果"))
           .andExpect(jsonPath("$.success").value(true));
    }
}
