package com.harnesslearn.agent.api;

import com.harnesslearn.agent.domain.AgentOutput;
import com.harnesslearn.agent.domain.AgentRun;
import com.harnesslearn.agent.domain.TaskSpec;
import com.harnesslearn.agent.l3orchestrate.L3Orchestrator;
import com.harnesslearn.agent.observability.RunEventBus;
import com.harnesslearn.agent.observability.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "agent.ingest.enabled=false"
})
class RunStreamControllerTest {

    @LocalServerPort int port;
    @Autowired RunEventBus bus;
    @MockBean L3Orchestrator orchestrator;

    private String getStream(String query) throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=SURVEY&query=" + query)).GET().build();
        HttpResponse<java.util.stream.Stream<String>> resp =
            client.send(req, HttpResponse.BodyHandlers.ofLines());
        return resp.body().collect(Collectors.joining("\n"));
    }

    @Test
    void streamsStepsThenResult() throws Exception {
        when(orchestrator.run(any())).thenAnswer(inv -> {
            TaskSpec t = inv.getArgument(0);
            bus.publish(t.runId(), new TraceStep(t.runId(), 0, "L3", "model_step", "思考"));
            bus.publish(t.runId(), new TraceStep(t.runId(), 1, "L2", "tool_invoke", "local_retrieve"));
            return new AgentRun(t.runId(), new AgentOutput("最终综述", List.of()), true, "completed");
        });

        String body = getStream("ctx");

        assertThat(body).contains("event:step");
        assertThat(body).contains("event:result");
        assertThat(body).contains("最终综述");
        assertThat(body).contains("tool_invoke");
    }

    @Test
    void workerExceptionEmitsFail() throws Exception {
        when(orchestrator.run(any())).thenThrow(new RuntimeException("boom"));

        String body = getStream("ctx");

        assertThat(body).contains("event:fail");
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=BOGUS&query=x")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void blankQueryReturns400() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/runs/stream?type=QA&query=")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
    }
}
