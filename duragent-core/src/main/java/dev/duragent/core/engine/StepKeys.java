package dev.duragent.core.engine;

import java.util.regex.Pattern;

/**
 * Step keys are the identity of a side effect, so they get normalized exactly once, here.
 *
 * <p>A key must be stable across restarts and redeploys. If you rename a step, you have
 * changed its identity: the engine will treat the new name as a brand new step and execute
 * the side effect again. That is a deliberate trade-off, and it is why renames deserve a
 * migration note in the changelog.
 */
public final class StepKeys {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:#\\-]{1,191}");

    private StepKeys() {
    }

    public static String normalize(String stepName) {
        if (stepName == null) {
            throw new IllegalArgumentException("step name must not be null");
        }
        String key = stepName.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("step name must not be blank");
        }
        if (!ALLOWED.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "invalid step name '" + stepName + "': allowed characters are letters, digits, "
                            + "'.', '_', ':', '#', '-' and the key must be at most 191 chars");
        }
        return key;
    }
}
