package dev.duragent.spring.web;

import dev.duragent.core.engine.Approvals;
import dev.duragent.core.engine.Budgets;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.model.Approval;
import dev.duragent.core.model.Budget;
import dev.duragent.core.model.BudgetEntry;
import dev.duragent.core.model.Checkpoint;
import dev.duragent.core.model.Run;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole HTTP surface. Five endpoints, because the interesting part of the project is not the
 * web layer.
 *
 * <pre>
 * POST /api/runs                        {workflow, input, maxCostMicros?}
 * GET  /api/runs/{id}                   status + timeline + budget
 * GET  /api/runs/{id}/approvals         pending approvals
 * POST /api/approvals/{id}/approve      {by, comment}
 * POST /api/approvals/{id}/reject       {by, comment}
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private static final Budget DEFAULT_BUDGET =
            Budget.of(200_000, 500_000, 30, Duration.ofMinutes(10));

    private final RunLifecycle runs;
    private final Approvals approvals;
    private final Budgets budgets;

    public RunController(RunLifecycle runs, Approvals approvals, Budgets budgets) {
        this.runs = runs;
        this.approvals = approvals;
        this.budgets = budgets;
    }

    public record CreateRunRequest(String workflow, Map<String, Object> input, Long maxCostMicros) {
    }

    public record CreateRunResponse(UUID id, String status) {
    }

    public record CheckpointView(int seq, String node, long createdAtMs) {
    }

    public record BudgetView(long usedTokens, long usedCostMicros, int usedSteps,
                             Long maxTokens, Long maxCostMicros, Integer maxSteps) {
    }

    public record RunView(UUID id, String workflow, String status, String cursor, String error,
                          int attempt, List<CheckpointView> timeline, BudgetView budget) {
    }

    public record ApprovalView(UUID id, UUID runId, String node, String reason, String status,
                               String requestedBy, String decidedBy) {
    }

    public record Decision(String by, String comment) {
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreateRunResponse create(@RequestBody CreateRunRequest request) {
        if (request.workflow() == null || request.workflow().isBlank()) {
            throw new IllegalArgumentException("workflow is required");
        }
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        Run run = runs.enqueue(request.workflow(), input);
        Budget budget = request.maxCostMicros() == null
                ? DEFAULT_BUDGET
                : DEFAULT_BUDGET.withMaxCostMicros(request.maxCostMicros());
        budgets.set(run.id(), budget);
        return new CreateRunResponse(run.id(), run.status().name());
    }

    @GetMapping("/runs/{id}")
    public RunView get(@PathVariable("id") UUID id) {
        Run run = runs.get(id);
        List<CheckpointView> timeline = runs.timeline(id).stream()
                .map(RunController::toView)
                .toList();
        BudgetView budget = budgets.usage(id).map(RunController::toView).orElse(null);
        return new RunView(run.id(), run.workflow(), run.status().name(), run.cursor(), run.error(),
                run.attempt(), timeline, budget);
    }

    @GetMapping("/runs/{id}/approvals")
    public List<ApprovalView> pendingApprovals(@PathVariable("id") UUID id) {
        return approvals.pending(id).stream().map(RunController::toView).toList();
    }

    @PostMapping("/approvals/{id}/approve")
    public ApprovalView approve(@PathVariable("id") UUID id, @RequestBody(required = false) Decision decision) {
        Decision decided = decision == null ? new Decision("http", null) : decision;
        return toView(approvals.approve(id, decided.by(), decided.comment()));
    }

    @PostMapping("/approvals/{id}/reject")
    public ApprovalView reject(@PathVariable("id") UUID id, @RequestBody(required = false) Decision decision) {
        Decision decided = decision == null ? new Decision("http", null) : decision;
        return toView(approvals.reject(id, decided.by(), decided.comment()));
    }

    /** A run that does not exist is a 404, not a 500. */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private static CheckpointView toView(Checkpoint checkpoint) {
        return new CheckpointView(checkpoint.seq(), checkpoint.node(), checkpoint.createdAtMs());
    }

    private static BudgetView toView(BudgetEntry entry) {
        Budget budget = entry.budget();
        return new BudgetView(entry.usedTokens(), entry.usedCostMicros(), entry.usedSteps(),
                budget.maxTokens(), budget.maxCostMicros(), budget.maxSteps());
    }

    private static ApprovalView toView(Approval approval) {
        return new ApprovalView(approval.id(), approval.runId(), approval.node(), approval.reason(),
                approval.status().name(), approval.requestedBy(), approval.decidedBy());
    }

    /** Kept for callers that only have an id and want a boolean. */
    static boolean exists(Optional<Run> run) {
        return run.isPresent();
    }
}
