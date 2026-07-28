package eu.rekawek.coffeegb.core.memory.cart.battery;

import java.io.IOException;

/** Typed outcome of persisting one immutable battery/RTC capture. */
public interface BatteryPersistenceResult {

    record Success(int filesWritten) implements BatteryPersistenceResult {

        public Success {
            if (filesWritten < 0) {
                throw new IllegalArgumentException("filesWritten must not be negative");
            }
        }
    }

    record Failure(
            FailureKind kind,
            String fileName,
            String message,
            IOException cause) implements BatteryPersistenceResult {

        public Failure {
            if (kind == null || fileName == null || message == null || cause == null) {
                throw new NullPointerException("Battery persistence failure fields are required");
            }
        }
    }

    enum FailureKind {
        WRITE_FAILED,
        TIMED_OUT
    }
}
