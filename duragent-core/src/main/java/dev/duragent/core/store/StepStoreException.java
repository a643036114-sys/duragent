package dev.duragent.core.store;

/**
 * Wraps the checked {@link java.sql.SQLException} so the engine API stays unchecked.
 */
public class StepStoreException extends RuntimeException {

    public StepStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StepStoreException(String message) {
        super(message);
    }
}
