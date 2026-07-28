package eu.rekawek.coffeegb.core.memory.cart.battery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable two-phase battery/RTC persistence capture.
 *
 * <p>{@link #persist()} performs file I/O and may be called repeatedly for retry. It does not
 * mutate the emulated cartridge. {@link #complete(BatteryPersistenceResult)} acknowledges a
 * successful write at a later safe point; a failure or cancellation deliberately leaves the
 * captured generation dirty.
 */
public interface BatteryFlush {

    BatteryPersistenceResult persist();

    void complete(BatteryPersistenceResult result);

    static BatteryFlush none() {
        return Empty.INSTANCE;
    }

    static BatteryFlush combine(BatteryFlush... captures) {
        Objects.requireNonNull(captures, "captures");
        List<BatteryFlush> nonEmpty = new ArrayList<>();
        Arrays.stream(captures)
                .map(capture -> Objects.requireNonNull(capture, "capture"))
                .filter(capture -> capture != Empty.INSTANCE)
                .forEach(nonEmpty::add);
        if (nonEmpty.isEmpty()) {
            return none();
        }
        if (nonEmpty.size() == 1) {
            return nonEmpty.get(0);
        }
        return new Composite(List.copyOf(nonEmpty));
    }

    final class Empty implements BatteryFlush {

        private static final Empty INSTANCE = new Empty();

        private Empty() {
        }

        @Override
        public BatteryPersistenceResult persist() {
            return new BatteryPersistenceResult.Success(0);
        }

        @Override
        public void complete(BatteryPersistenceResult result) {
            Objects.requireNonNull(result, "result");
        }
    }

    final class Composite implements BatteryFlush {

        private final List<BatteryFlush> captures;

        private Composite(List<BatteryFlush> captures) {
            this.captures = captures;
        }

        @Override
        public BatteryPersistenceResult persist() {
            int filesWritten = 0;
            for (BatteryFlush capture : captures) {
                BatteryPersistenceResult result = capture.persist();
                if (result instanceof BatteryPersistenceResult.Failure) {
                    return result;
                }
                filesWritten += ((BatteryPersistenceResult.Success) result).filesWritten();
            }
            return new BatteryPersistenceResult.Success(filesWritten);
        }

        @Override
        public void complete(BatteryPersistenceResult result) {
            Objects.requireNonNull(result, "result");
            if (result instanceof BatteryPersistenceResult.Success) {
                for (BatteryFlush capture : captures) {
                    capture.complete(result);
                }
            }
        }
    }
}
