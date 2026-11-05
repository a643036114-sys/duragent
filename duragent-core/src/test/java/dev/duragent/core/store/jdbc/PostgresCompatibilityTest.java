package dev.duragent.core.store.jdbc;

import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.json.JsonCodec;
import dev.duragent.core.model.StepRecord;
import dev.duragent.core.model.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the same schema and the same SQL statements run on PostgreSQL, not just H2.
 *
 * <p>Skipped unless a database is supplied:
 * <pre>
 * mvn test -Dtest=PostgresCompatibilityTest \
 *   -Dduragent.pg.url=jdbc:postgresql://127.0.0.1:5432/duragent \
 *   -Dduragent.pg.user=postgres -Dduragent.pg.password=123456
 * </pre>
 */
@EnabledIfSystemProperty(named = "duragent.pg.url", matches = ".+")
class PostgresCompatibilityTest {

    @Test
    void schemaAndDurableStepWorkOnPostgres() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(System.getProperty("duragent.pg.url"));
        dataSource.setUser(System.getProperty("duragent.pg.user", "postgres"));
        dataSource.setPassword(System.getProperty("duragent.pg.password", "postgres"));

        SchemaInitializer.initialize(dataSource);
        JdbcStepStore store = new JdbcStepStore(dataSource);
        DurableSteps steps = new DurableSteps(store, new JsonCodec(), Clock.systemUTC(), Duration.ofMinutes(1));

        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        try {
            String first = steps.run(runId, "postgres-smoke", String.class, () -> {
                executions.incrementAndGet();
                return "hello-pg";
            });
            String second = steps.run(runId, "postgres-smoke", String.class, () -> {
                executions.incrementAndGet();
                return "should-not-happen";
            });

            assertEquals(1, executions.get());
            assertEquals("hello-pg", first);
            assertEquals("hello-pg", second);

            StepRecord record = store.find(runId.toString(), "postgres-smoke").orElseThrow();
            assertEquals(StepStatus.SUCCEEDED, record.status());
            assertEquals(1, record.attempt());
        } finally {
            try (var c = dataSource.getConnection();
                 var ps = c.prepareStatement("DELETE FROM step_record WHERE run_id = ?")) {
                ps.setString(1, runId.toString());
                ps.executeUpdate();
            }
        }
    }
}
