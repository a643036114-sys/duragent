package dev.duragent.core.store.jdbc;

import dev.duragent.core.store.StepStoreException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies the bundled schema. Idempotent by construction ({@code CREATE TABLE IF NOT EXISTS}),
 * so it is safe to call on every boot of a demo app or a test.
 *
 * <p>Real deployments should move to a migration tool (Flyway/Liquibase) before the schema
 * starts changing; see {@code docs/roadmap.md}.
 */
public final class SchemaInitializer {

    public static final String DEFAULT_SCHEMA_RESOURCE = "dev/duragent/schema.sql";

    private SchemaInitializer() {
    }

    public static void initialize(DataSource dataSource) {
        initialize(dataSource, DEFAULT_SCHEMA_RESOURCE);
    }

    public static void initialize(DataSource dataSource, String classpathResource) {
        String script = read(classpathResource);
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            for (String statement : split(script)) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        } catch (SQLException e) {
            throw new StepStoreException("schema initialization failed", e);
        }
    }

    private static String read(String resource) {
        try (InputStream in = SchemaInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new StepStoreException("schema resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StepStoreException("cannot read schema resource " + resource, e);
        }
    }

    private static String[] split(String script) {
        return script.replace("\r\n", "\n").split(";");
    }
}
