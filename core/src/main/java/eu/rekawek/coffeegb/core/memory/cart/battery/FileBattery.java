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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;

public class FileBattery implements Battery {

    private static final Logger LOG = LoggerFactory.getLogger(FileBattery.class);

    private BatteryStorage storage;

    private final byte[] clockBuffer;

    private final byte[] ramBuffer;

    private final AtomicFileWriter persistence;

    private boolean isClockPresent;

    private boolean isDirty;

    private long generation;

    private int deferredFlushDepth;

    private boolean fallbackImportChecked;

    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private BatteryPersistenceFailedEvent pendingFailure;

    public FileBattery(File saveFile, int ramSize) {
        this(BatteryStorage.direct(saveFile.toPath()), ramSize, AtomicFileWriter.system());
    }

    FileBattery(File saveFile, int ramSize, AtomicFileWriter persistence) {
        this(BatteryStorage.direct(saveFile.toPath()), ramSize, persistence);
    }

    public FileBattery(
            File saveFile,
            File legacySaveFile,
            boolean migrateLegacySave,
            int ramSize) {
        this(
                legacyStorage(saveFile, legacySaveFile, migrateLegacySave),
                ramSize,
                AtomicFileWriter.system());
    }

    FileBattery(
            File saveFile,
            File legacySaveFile,
            boolean migrateLegacySave,
            int ramSize,
            AtomicFileWriter persistence) {
        this(legacyStorage(saveFile, legacySaveFile, migrateLegacySave), ramSize, persistence);
    }

    public FileBattery(BatteryStorage storage, int ramSize) {
        this(storage, ramSize, AtomicFileWriter.system());
    }

    FileBattery(BatteryStorage storage, int ramSize, AtomicFileWriter persistence) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clockBuffer = new byte[11 * 4];
        this.ramBuffer = new byte[ramSize];
        this.persistence = persistence;
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
            importFallbackIfNeeded();
            BatteryStorage currentStorage = storage;
            currentStorage.ensureTargetSafe();
            LoadedBattery loaded =
                    persistence.read(currentStorage.targetPath(), recovered -> {
                        if (!Files.exists(recovered, LinkOption.NOFOLLOW_LINKS)) {
                            return null;
                        }
                        currentStorage.ensureReadableTarget(recovered);
                        BasicFileAttributes attributes =
                                Files.readAttributes(
                                        recovered,
                                        BasicFileAttributes.class,
                                        LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile()) {
                            throw new IOException("Battery target is not a regular file");
                        }
                        long saveLength = attributes.size();
                        if (saveLength >= 0x2000) {
                            // Strip a possible RTC suffix; small EEPROM saves are used as-is.
                            saveLength = saveLength - (saveLength % 0x2000);
                        }
                        int[] loadedRam = new int[ram.length];
                        long[] loadedClock =
                                clockData == null ? null : new long[clockData.length];
                        try (InputStream is =
                                Files.newInputStream(
                                        recovered,
                                        StandardOpenOption.READ,
                                        LinkOption.NOFOLLOW_LINKS)) {
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
            reportFailure(BatteryPersistenceFailedEvent.Operation.LOAD, storage.targetPath(), e);
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
        Path capturedTarget;
        synchronized (this) {
            if (deferredFlushDepth > 0) {
                return;
            }
            capture = captureCurrentFlush();
            capturedTarget =
                    capture instanceof FileBatteryFlush fileBatteryFlush
                            ? fileBatteryFlush.targetPath()
                            : storage.targetPath();
        }
        BatteryPersistenceResult result = capture.persist();
        capture.complete(result);
        if (result instanceof BatteryPersistenceResult.Failure failure) {
            reportFailure(
                    BatteryPersistenceFailedEvent.Operation.SAVE,
                    capturedTarget,
                    failure.cause());
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

    private void importFallbackIfNeeded() throws IOException {
        if (fallbackImportChecked) {
            return;
        }
        BatteryStorage currentStorage = storage;
        currentStorage.ensureTargetSafe();
        Path target = currentStorage.targetPath();
        boolean targetExists = persistence.exists(target);
        FileTime targetModified = null;
        if (targetExists) {
            currentStorage.ensureReadableTarget(target);
            targetModified =
                    Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS);
        }
        RecoverableImport source = mostRecentRecoverableImport(currentStorage);
        if (source == null
                || (targetModified != null && source.modified().compareTo(targetModified) <= 0)) {
            fallbackImportChecked = true;
            return;
        }
        byte[] legacyBytes =
                persistence.read(
                        source.path(),
                        recovered -> {
                            currentStorage.ensureReadableImport(recovered);
                            int maximum = ramBuffer.length + clockBuffer.length;
                            return readBoundedLegacySave(recovered, maximum);
                        });
        // Import rather than delete: older portable-JAR versions retain their fallback save.
        currentStorage.ensureTargetSafe();
        persistence.write(target, legacyBytes);
        fallbackImportChecked = true;
    }

    private RecoverableImport mostRecentRecoverableImport(BatteryStorage currentStorage)
            throws IOException {
        IOException unsafeSource = null;
        RecoverableImport newest = null;
        for (BatteryStorage.Source source : currentStorage.importSources()) {
            try {
                if (!currentStorage.ensureImportCandidateSafe(source.path())) {
                    continue;
                }
                if (persistence.exists(source.path())) {
                    currentStorage.ensureReadableImport(source.path());
                    RecoverableImport candidate =
                            new RecoverableImport(
                                    source.path(),
                                    Files.getLastModifiedTime(
                                            source.path(), LinkOption.NOFOLLOW_LINKS));
                    if (newest == null
                            || candidate.modified().compareTo(newest.modified()) > 0) {
                        newest = candidate;
                    }
                }
            } catch (IOException failure) {
                if (unsafeSource == null) {
                    unsafeSource = failure;
                }
            }
        }
        if (newest == null && unsafeSource != null) {
            throw unsafeSource;
        }
        return newest;
    }

    private static byte[] readBoundedLegacySave(Path source, int maximum) throws IOException {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(Math.min(maximum, 8 * 1024));
        byte[] buffer = new byte[Math.min(Math.max(maximum + 1, 1), 8 * 1024)];
        int total = 0;
        try (InputStream input =
                Files.newInputStream(
                        source,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS)) {
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
            Path saveFile,
            IOException e) {
        LOG.warn("Unable to {} battery file {}", operation.name().toLowerCase(), saveFile, e);
        String detail = e.getClass().getSimpleName();
        String message = "Unable to " + operation.name().toLowerCase() + " battery save "
                + saveFile.getFileName() + " (" + detail + ").";
        if (operation == BatteryPersistenceFailedEvent.Operation.SAVE) {
            message += " Changes remain pending and will be retried.";
        }
        BatteryPersistenceFailedEvent event =
                new BatteryPersistenceFailedEvent(
                        operation, saveFile.getFileName().toString(), message);
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

    /**
     * Moves future writes to a newly validated Saves destination without touching the filesystem
     * or discarding the running mapper's RAM/RTC generation.
     */
    public synchronized void setStorage(BatteryStorage storage) {
        BatteryStorage updated = Objects.requireNonNull(storage, "storage");
        if (!this.storage.targetPath().equals(updated.targetPath())) {
            isDirty = true;
            generation++;
        }
        this.storage = updated;
        fallbackImportChecked = false;
    }

    private final class FileBatteryFlush implements BatteryFlush {

        private final long capturedGeneration;

        private final byte[] intended;

        private final BatteryStorage capturedStorage;

        private FileBatteryFlush(long capturedGeneration, byte[] intended) {
            this.capturedGeneration = capturedGeneration;
            this.intended = intended;
            this.capturedStorage = storage;
        }

        private Path targetPath() {
            return capturedStorage.targetPath();
        }

        @Override
        public BatteryPersistenceResult persist() {
            try {
                capturedStorage.ensureTargetSafe();
                persistence.write(capturedStorage.targetPath(), intended);
                return new BatteryPersistenceResult.Success(1);
            } catch (IOException e) {
                String detail = e.getClass().getSimpleName();
                String fileName = capturedStorage.targetPath().getFileName().toString();
                String message =
                        "Unable to save battery save "
                                + fileName
                                + " ("
                                + detail
                                + "). Changes remain pending and can be retried.";
                return new BatteryPersistenceResult.Failure(
                        BatteryPersistenceResult.FailureKind.WRITE_FAILED,
                        fileName,
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

    private record RecoverableImport(Path path, FileTime modified) {
    }

    private static BatteryStorage legacyStorage(
            File saveFile,
            File legacySaveFile,
            boolean migrateLegacySave) {
        Objects.requireNonNull(saveFile, "saveFile");
        List<Path> imports =
                migrateLegacySave && legacySaveFile != null
                        ? List.of(legacySaveFile.toPath())
                        : List.of();
        return BatteryStorage.direct(saveFile.toPath(), imports);
    }

    record FileBatteryState(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements ComponentState<Battery> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    record FileBatteryMemento(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements Memento<Battery> {
    }
}
