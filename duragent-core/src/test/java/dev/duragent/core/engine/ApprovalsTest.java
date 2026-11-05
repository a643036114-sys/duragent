package dev.duragent.core.engine;

import dev.duragent.core.model.Approval;
import dev.duragent.core.model.ApprovalStatus;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcApprovalStore;
import dev.duragent.core.store.jdbc.JdbcRunStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalsTest {

    private JdbcDataSource dataSource;
    private JdbcApprovalStore approvalStore;
    private RunLifecycle runs;
    private Approvals approvals;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:approvals-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        approvalStore = new JdbcApprovalStore(dataSource);
        runs = new RunLifecycle(new JdbcRunStore(dataSource));
        approvals = new Approvals(approvalStore, runs);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("requesting an approval parks the run and holds no thread")
    void requestParksTheRun() {
        Run run = runs.start("refund-agent", Map.of("ticket", "T-9"));

        Approval approval = approvals.request(run.id(), "refund", "refund 500 CNY", "ticket-agent");

        assertEquals(ApprovalStatus.PENDING, approval.status());
        assertEquals(RunStatus.SUSPENDED, runs.get(run.id()).status());
        assertEquals(1, approvals.pending(run.id()).size());
    }

    @Test
    @DisplayName("asking twice returns the same pending request")
    void requestIsIdempotent() {
        Run run = runs.start("refund-agent", Map.of());

        Approval first = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");
        Approval second = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");

        assertEquals(first.id(), second.id());
        assertEquals(1, approvals.pending(run.id()).size());
    }

    @Test
    @DisplayName("the gate throws on the first pass and parks the run")
    void gateParksUntilApproved() {
        Run run = runs.start("refund-agent", Map.of());

        ApprovalRequiredException pause = assertThrows(ApprovalRequiredException.class,
                () -> approvals.requireApproval(run.id(), "refund", "refund 500 CNY", "agent"));

        assertEquals(RunStatus.SUSPENDED, runs.get(run.id()).status());
        assertEquals("refund", pause.node());
        assertEquals(ApprovalStatus.PENDING,
                approvals.find(UUID.fromString(pause.approvalId())).orElseThrow().status());
    }

    @Test
    @DisplayName("approving resumes the run and lets the gate through")
    void approveResumesTheRun() {
        Run run = runs.start("refund-agent", Map.of());
        Approval approval = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");

        Approval decided = approvals.approve(approval.id(), "manager@corp", "ok, customer is VIP");

        assertEquals(ApprovalStatus.APPROVED, decided.status());
        assertEquals("manager@corp", decided.decidedBy());
        assertEquals(RunStatus.RUNNING, runs.get(run.id()).status());
        assertEquals(2, runs.get(run.id()).attempt());
        assertDoesNotThrow(() -> approvals.requireApproval(run.id(), "refund", "again", "agent"));
    }

    @Test
    @DisplayName("rejecting fails the run and keeps the gate shut")
    void rejectFailsTheRun() {
        Run run = runs.start("refund-agent", Map.of());
        Approval approval = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");

        approvals.reject(approval.id(), "manager@corp", "policy: no refunds after 30 days");

        assertEquals(RunStatus.FAILED, runs.get(run.id()).status());
        ApprovalRejectedException rejected = assertThrows(ApprovalRejectedException.class,
                () -> approvals.requireApproval(run.id(), "refund", "refund 500 CNY", "agent"));
        assertEquals("refund", rejected.node());
    }

    @Test
    @DisplayName("a second decision is ignored instead of resuming the run twice")
    void doubleApprovalIsIdempotent() {
        Run run = runs.start("refund-agent", Map.of());
        Approval approval = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");

        approvals.approve(approval.id(), "manager-a", "ok");
        Approval second = approvals.approve(approval.id(), "manager-b", "also ok");

        assertEquals("manager-a", second.decidedBy(), "first decision wins");
        assertEquals(2, runs.get(run.id()).attempt(), "attempt must not be bumped twice");
    }

    @Test
    @DisplayName("a pending approval is honoured by a brand new service instance")
    void survivesRestart() {
        Run run = runs.start("refund-agent", Map.of());
        Approval approval = approvals.request(run.id(), "refund", "refund 500 CNY", "agent");

        // A different process picks the work up the next morning.
        Approvals restarted = new Approvals(new JdbcApprovalStore(dataSource),
                new RunLifecycle(new JdbcRunStore(dataSource)));
        List<Approval> pending = restarted.pending(run.id());
        assertEquals(1, pending.size());
        assertEquals(approval.id(), pending.get(0).id());

        restarted.approve(approval.id(), "next-morning@corp", "sorry, was asleep");
        assertEquals(RunStatus.RUNNING, runs.get(run.id()).status());
    }
}
