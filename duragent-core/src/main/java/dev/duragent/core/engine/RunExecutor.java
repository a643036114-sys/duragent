package dev.duragent.core.engine;

import dev.duragent.core.model.Run;

/**
 * What a worker does with a claimed run. Implementations usually call a compiled graph.
 *
 * <p>The worker only claims and releases; finishing the run (COMPLETED / FAILED / CANCELED) is
 * the executor's business, because only it knows whether the graph reached its end.
 */
@FunctionalInterface
public interface RunExecutor {

    void execute(Run run) throws Exception;
}
