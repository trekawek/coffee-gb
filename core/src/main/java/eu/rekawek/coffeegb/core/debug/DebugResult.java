package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/**
 * Immutable result of a debug request.
 *
 * <p>Expected session and command failures are values rather than exceptional completion. A
 * successful command whose result type is {@link Void} is represented by {@link #success()}.
 */
public final class DebugResult<T> {

    private final boolean success;

    private final T value;

    private final DebugError error;

    private DebugResult(boolean success, T value, DebugError error) {
        this.success = success;
        this.value = value;
        this.error = error;
    }

    public static <T> DebugResult<T> success(T value) {
        return new DebugResult<>(true, Objects.requireNonNull(value, "value"), null);
    }

    public static DebugResult<Void> success() {
        return new DebugResult<>(true, null, null);
    }

    public static <T> DebugResult<T> failure(DebugError error) {
        return new DebugResult<>(false, null, Objects.requireNonNull(error, "error"));
    }

    public static <T> DebugResult<T> failure(DebugErrorCode code, String message) {
        return failure(new DebugError(code, message));
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    /**
     * Returns the successful value, which is {@code null} only for a successful {@link Void}
     * command.
     *
     * @throws IllegalStateException if this is a failure
     */
    public T value() {
        if (!success) {
            throw new IllegalStateException("A failed debug result has no value");
        }
        return value;
    }

    /**
     * Returns the failure.
     *
     * @throws IllegalStateException if this is a success
     */
    public DebugError error() {
        if (success) {
            throw new IllegalStateException("A successful debug result has no error");
        }
        return error;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugResult<?> that)) return false;
        return success == that.success
                && Objects.equals(value, that.value)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, value, error);
    }

    @Override
    public String toString() {
        return success ? "DebugResult[success=" + value + "]"
                : "DebugResult[failure=" + error + "]";
    }
}
