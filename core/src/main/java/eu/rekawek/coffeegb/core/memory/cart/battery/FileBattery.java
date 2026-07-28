package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class FileBattery implements Battery {

    private static final Logger LOG = LoggerFactory.getLogger(FileBattery.class);

    private final File saveFile;

    private final byte[] clockBuffer;

    private final byte[] ramBuffer;

    private final AtomicFileWriter persistence;

    private final Path legacySaveFile;

    private final boolean migrateLegacySave;

    private boolean isClockPresent;

    private boolean isDirty;

    private long generation;

    private int deferredFlushDepth;

    private boolean legacyMigrationChecked;

    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private BatteryPersistenceFailedEvent pendingFailure;

    public FileBattery(File saveFile, int ramSize) {
        this(saveFile, null, false, ramSize, AtomicFileWriter.system());
    }

    FileBattery(File saveFile, int ramSize, AtomicFileWriter persistence) {
        this(saveFile, null, false, ramSize, persistence);
    }

    public FileBattery(
            File saveFile,
            File legacySaveFile,
            boolean migrateLegacySave,
            int ramSize) {
        this(saveFile, legacySaveFile, migrateLegacySave, ramSize, AtomicFileWriter.system());
    }

    FileBattery(
            File saveFile,
            File legacySaveFile,
            boolean migrateLegacySave,
            int ramSize,
            AtomicFileWriter persistence) {
        this.saveFile = saveFile;
        this.clockBuffer = new byte[11 * 4];
        this.ramBuffer = new byte[ramSize];
        this.persistence = persistence;
        this.legacySaveFile = legacySaveFile == null ? null : legacySaveFile.toPath();
        this.migrateLegacySave = migrateLegacySave;
    }

    @Override
    public synchronized void loadRam(int[] ram) {
        loadRamWithClock(ram, null);
    }

    @Override
    public synchronized void saveRam(int[] ram) {
        saveRamWithClock(ram, null);
    }

    @Override
    public synchronized void loadRamWithClock(int[] ram, long[] clockData) {
        try {
            migrateLegacySaveIfNeeded();
            LoadedBattery loaded =
                    persistence.read(saveFile.toPath(), recovered -> {
                        if (!Files.exists(recovered)) {
                            return null;
                        }
                        long saveLength = Files.size(recovered);
                        if (saveLength >= 0x2000) {
                            // Strip a possible RTC suffix; small EEPROM saves are used as-is.
                            saveLength = saveLength - (saveLength % 0x2000);
                        }
                        int[] loadedRam = new int[ram.length];
                        long[] loadedClock =
                                clockData == null ? null : new long[clockData.length];
                        try (InputStream is = Files.newInputStream(recovered)) {
                            loadRam(loadedRam, is, saveLength);
                            if (loadedClock != null) {
                                loadClock(loadedClock, is);
                            }
                        }
                        return new LoadedBattery(loadedRam, loadedClock);
                    });
            if (loaded == null) {
                return;
            }
            System.arraycopy(loaded.ram, 0, ram, 0, ram.length);
            if (clockData != null) {
                System.arraycopy(loaded.clock, 0, clockData, 0, clockData.length);
            }
        } catch (IOException e) {
            reportFailure(BatteryPersistenceFailedEvent.Operation.LOAD, e);
        }
    }

    @Override
    public synchronized void saveRamWithClock(int[] ram, long[] clockData) {
        doSaveRam(ram);
        if (clockData != null) {
            doSaveClock(clockData);
            isClockPresent = true;
        }
        isDirty = true;
        generation++;
    }

    @Override
    public void flush() {
        BatteryFlush capture;
        synchronized (this) {
            if (deferredFlushDepth > 0) {
                return;
            }
            capture = captureCurrentFlush();
        }
        BatteryPersistenceResult result = capture.persist();
        capture.complete(result);
        if (result instanceof BatteryPersistenceResult.Failure failure) {
            reportFailure(BatteryPersistenceFailedEvent.Operation.SAVE, failure.cause());
        }
    }

    @Override
    public BatteryFlush prepareFlush(Runnable captureMapperState) {
        Objects.requireNonNull(captureMapperState, "captureMapperState");
        synchronized (this) {
            deferredFlushDepth++;
            try {
                captureMapperState.run();
            } finally {
                deferredFlushDepth--;
            }
            return captureCurrentFlush();
        }
    }

    private BatteryFlush captureCurrentFlush() {
        if (!isDirty) {
            return BatteryFlush.none();
        }
        long capturedGeneration = generation;
        int clockBytes = isClockPresent ? clockBuffer.length : 0;
        byte[] intended = new byte[ramBuffer.length + clockBytes];
        System.arraycopy(ramBuffer, 0, intended, 0, ramBuffer.length);
        if (isClockPresent) {
            System.arraycopy(clockBuffer, 0, intended, ramBuffer.length, clockBuffer.length);
        }
        return new FileBatteryFlush(capturedGeneration, intended);
    }

    private synchronized void completeFlush(
            long capturedGeneration,
            BatteryPersistenceResult result) {
        if (result instanceof BatteryPersistenceResult.Success
                && generation == capturedGeneration) {
            isClockPresent = false;
            isDirty = false;
        }
    }

    private void migrateLegacySaveIfNeeded() throws IOException {
        if (legacyMigrationChecked) {
            return;
        }
        if (!migrateLegacySave || legacySaveFile == null) {
            legacyMigrationChecked = true;
            return;
        }
        Path target = saveFile.toPath();
        if (persistence.exists(target) || !persistence.exists(legacySaveFile)) {
            legacyMigrationChecked = true;
            return;
        }
        byte[] legacyBytes =
                persistence.read(
                        legacySaveFile,
                        source -> {
                            int maximum = ramBuffer.length + clockBuffer.length;
                            return readBoundedLegacySave(source, maximum);
                        });
        // Import rather than delete: older portable-JAR versions retain their fallback save.
        persistence.write(target, legacyBytes);
        legacyMigrationChecked = true;
    }

    private static byte[] readBoundedLegacySave(Path source, int maximum) throws IOException {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(Math.min(maximum, 8 * 1024));
        byte[] buffer = new byte[Math.min(Math.max(maximum + 1, 1), 8 * 1024)];
        int total = 0;
        try (InputStream input = Files.newInputStream(source)) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return bytes.toByteArray();
                }
                if (read == 0) {
                    int value = input.read();
                    if (value < 0) {
                        return bytes.toByteArray();
                    }
                    if (total == maximum) {
                        throw new IOException(
                                "Legacy battery save exceeds the expected cartridge size");
                    }
                    bytes.write(value);
                    total++;
                } else {
                    if (read > maximum - total) {
                        throw new IOException(
                                "Legacy battery save exceeds the expected cartridge size");
                    }
                    bytes.write(buffer, 0, read);
                    total += read;
                }
            }
        }
    }

    @Override
    public synchronized void init(EventBus eventBus) {
        this.eventBus = eventBus == null ? EventBus.NULL_EVENT_BUS : eventBus;
        if (pendingFailure != null) {
            postFailure(pendingFailure);
            pendingFailure = null;
        }
    }

    private static void loadClock(long[] clockData, InputStream is) throws IOException {
        byte[] byteBuff = new byte[4 * clockData.length];
        IOUtils.read(is, byteBuff);
        ByteBuffer buff = ByteBuffer.wrap(byteBuff);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        while (buff.hasRemaining()) {
            clockData[i++] = buff.getInt();
        }
    }

    private static void loadRam(int[] ram, InputStream is, long length) throws IOException {
        byte[] buffer = new byte[ram.length];
        int bytesToRead = (int) Math.min(length, ram.length);
        IOUtils.read(is, buffer, 0, bytesToRead);
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i] & 0xff;
        }
    }

    private void doSaveClock(long[] clockData) {
        ByteBuffer buff = ByteBuffer.wrap(clockBuffer);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        // The released battery format has eleven 32-bit RTC words. HuC3 and TAMA5 expose
        // twelve-element compatibility arrays, but their twelfth word is unused; retain the
        // on-disk format instead of overflowing the fixed legacy buffer.
        int persistedWords = Math.min(clockData.length, clockBuffer.length / Integer.BYTES);
        for (int i = 0; i < persistedWords; i++) {
            buff.putInt((int) clockData[i]);
        }
    }

    private void doSaveRam(int[] ram) {
        for (int i = 0; i < ram.length; i++) {
            if (i >= ramBuffer.length) {
                return;
            }
            ramBuffer[i] = (byte) (ram[i]);
        }
    }

    @Override
    public synchronized ComponentState<Battery> captureState() {
        return new FileBatteryState(clockBuffer.clone(), ramBuffer.clone(), isClockPresent, isDirty);
    }

    @Override
    public synchronized ComponentState<Battery> captureState(MachineStateCapture capture) {
        return new FileBatteryState(
                capture.bytes(clockBuffer),
                capture.bytes(ramBuffer),
                isClockPresent,
                isDirty);
    }

    @Override
    public synchronized void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareBytes(clockBuffer);
        capture.declareBytes(ramBuffer);
    }

    @Override
    public synchronized void restoreState(ComponentState<Battery> state) {
        if (!(state instanceof FileBatteryState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.clockBuffer.length != mem.clockBuffer.length) {
            throw new IllegalArgumentException("ComponentState clockBuffer length doesn't match");
        }
        if (this.ramBuffer.length != mem.ramBuffer.length) {
            throw new IllegalArgumentException("ComponentState ramBuffer length doesn't match");
        }
        System.arraycopy(mem.clockBuffer, 0, this.clockBuffer, 0, this.clockBuffer.length);
        System.arraycopy(mem.ramBuffer, 0, this.ramBuffer, 0, this.ramBuffer.length);
        this.isClockPresent = mem.isClockPresent;
        this.isDirty = mem.isDirty;
        generation++;
    }

    private synchronized void reportFailure(
            BatteryPersistenceFailedEvent.Operation operation,
            IOException e) {
        LOG.warn("Unable to {} battery file {}", operation.name().toLowerCase(), saveFile, e);
        String detail = e.getClass().getSimpleName();
        String message = "Unable to " + operation.name().toLowerCase() + " battery save "
                + saveFile.getName() + " (" + detail + ").";
        if (operation == BatteryPersistenceFailedEvent.Operation.SAVE) {
            message += " Changes remain pending and will be retried.";
        }
        BatteryPersistenceFailedEvent event =
                new BatteryPersistenceFailedEvent(operation, saveFile.getName(), message);
        if (eventBus == EventBus.NULL_EVENT_BUS) {
            pendingFailure = event;
        } else {
            postFailure(event);
        }
    }

    private void postFailure(BatteryPersistenceFailedEvent event) {
        try {
            eventBus.post(event);
        } catch (RuntimeException subscriberFailure) {
            // A UI/error subscriber must not turn an already-handled persistence failure into an
            // emulator-thread failure.
            LOG.warn("Battery persistence failure subscriber threw an exception", subscriberFailure);
        }
    }

    synchronized boolean isDirtyForTesting() {
        return isDirty;
    }

    synchronized boolean isClockPresentForTesting() {
        return isClockPresent;
    }

    private final class FileBatteryFlush implements BatteryFlush {

        private final long capturedGeneration;

        private final byte[] intended;

        private FileBatteryFlush(long capturedGeneration, byte[] intended) {
            this.capturedGeneration = capturedGeneration;
            this.intended = intended;
        }

        @Override
        public BatteryPersistenceResult persist() {
            try {
                persistence.write(saveFile.toPath(), intended);
                return new BatteryPersistenceResult.Success(1);
            } catch (IOException e) {
                String detail = e.getClass().getSimpleName();
                String message =
                        "Unable to save battery save "
                                + saveFile.getName()
                                + " ("
                                + detail
                                + "). Changes remain pending and can be retried.";
                return new BatteryPersistenceResult.Failure(
                        BatteryPersistenceResult.FailureKind.WRITE_FAILED,
                        saveFile.getName(),
                        message,
                        e);
            }
        }

        @Override
        public void complete(BatteryPersistenceResult result) {
            completeFlush(capturedGeneration, result);
        }
    }

    private record LoadedBattery(int[] ram, long[] clock) {
    }

    record FileBatteryState(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements ComponentState<Battery> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    record FileBatteryMemento(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements Memento<Battery> {
    }
}
