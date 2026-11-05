package dev.duragent.spring.worker;

import dev.duragent.core.engine.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the queue on a timer. One loop, no framework magic: everything it knows about claiming
 * lives in {@link Worker} and {@code RunQueue}.
 *
 * <p>Disabled in tests with {@code duragent.worker.enabled=false} so a test can decide exactly
 * when work happens.
 */
@Component
@ConditionalOnProperty(name = "duragent.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RunPoller {

    private static final Logger log = LoggerFactory.getLogger(RunPoller.class);

    private final Worker worker;

    public RunPoller(Worker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${duragent.worker.poll-interval:500}")
    public void tick() {
        try {
            while (worker.pollOnce()) {
                // keep draining until the queue is empty
            }
        } catch (RuntimeException e) {
            log.warn("poll cycle failed: {}", e.getMessage());
        }
    }
}
