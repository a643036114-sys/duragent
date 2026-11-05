package dev.duragent.spring.web;

import com.jayway.jsonpath.JsonPath;
import dev.duragent.core.engine.Worker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the whole story through HTTP: create a run, watch it park on an approval, approve it,
 * watch it finish. The scheduled poller is off so the test drives the worker itself.
 */
@SpringBootTest(properties = {
        "duragent.worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class RunApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Worker worker;

    @Test
    @DisplayName("a refund parks for approval, then completes after the decision")
    void refundParksThenCompletes() throws Exception {
        MvcResult created = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflow":"refund-agent","input":{"amount":500}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String runId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // The worker runs "plan", reaches "refund" and parks the run.
        assertTrue(worker.pollOnce());

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.timeline.length()").value(1))
                .andExpect(jsonPath("$.timeline[0].node").value("plan"))
                .andExpect(jsonPath("$.budget.usedTokens").value(1200));

        MvcResult pending = mvc.perform(get("/api/runs/{id}/approvals", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].node").value("refund"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn();

        String approvalId = JsonPath.read(pending.getResponse().getContentAsString(), "$[0].id");

        mvc.perform(post("/api/approvals/{id}/approve", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"by":"manager@corp","comment":"ok"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("manager@corp"));

        // The resumed run is claimable again; the worker replays "plan" for free and finishes.
        assertTrue(worker.pollOnce());

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.timeline.length()").value(3))
                .andExpect(jsonPath("$.timeline[2].node").value("notify"))
                .andExpect(jsonPath("$.budget.usedTokens").value(1200));
    }

    @Test
    @DisplayName("a summary run completes without any human")
    void summaryRunCompletes() throws Exception {
        MvcResult created = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflow":"summary-agent","input":{"topic":"durable execution"}}
                                """))
                .andExpect(status().isAccepted())
                .andReturn();

        String runId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertTrue(worker.pollOnce());

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.budget.usedTokens").value(3300));
    }

    @Test
    @DisplayName("an unknown run is a 404 with a readable body")
    void unknownRunIs404() throws Exception {
        mvc.perform(get("/api/runs/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
