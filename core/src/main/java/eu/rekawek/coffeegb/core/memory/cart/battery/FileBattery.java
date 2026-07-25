package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.MachineStateCapture;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public class FileBattery implements Battery {

    private static final Logger LOG = LoggerFactory.getLogger(FileBattery.class);

    private final File saveFile;

    private final byte[] clockBuffer;

    private final byte[] ramBuffer;

    private final AtomicFileWriter persistence;

    private boolean isClockPresent;

    private boolean isDirty;

    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private BatteryPersistenceFailedEvent pendingFailure;

    public FileBattery(File saveFile, int ramSize) {
        this(saveFile, ramSize, AtomicFileWriter.system());
    }

    FileBattery(File saveFile, int ramSize, AtomicFileWriter persistence) {
        this.saveFile = saveFile;
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
    }

    public synchronized void flush() {
        if (!isDirty) {
            return;
        }
        int clockBytes = isClockPresent ? clockBuffer.length : 0;
        byte[] intended = new byte[ramBuffer.length + clockBytes];
        System.arraycopy(ramBuffer, 0, intended, 0, ramBuffer.length);
        if (isClockPresent) {
            System.arraycopy(clockBuffer, 0, intended, ramBuffer.length, clockBuffer.length);
        }
        try {
            persistence.write(saveFile.toPath(), intended);
            isClockPresent = false;
            isDirty = false;
        } catch (IOException e) {
            // Keep the exact RAM/RTC buffers and dirty flag for a later retry, including when
            // replacement committed but a post-rename operation reported failure.
            reportFailure(BatteryPersistenceFailedEvent.Operation.SAVE, e);
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
        for (long d : clockData) {
            buff.putInt((int) d);
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
    public synchronized Memento<Battery> saveToMemento() {
        return new FileBatteryMemento(clockBuffer.clone(), ramBuffer.clone(), isClockPresent, isDirty);
    }

    @Override
    public synchronized Memento<Battery> saveToMemento(MachineStateCapture capture) {
        return new FileBatteryMemento(
                capture.bytes(clockBuffer),
                capture.bytes(ramBuffer),
                isClockPresent,
                isDirty);
    }

    @Override
    public synchronized void restoreFromMemento(Memento<Battery> memento) {
        if (!(memento instanceof FileBatteryMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.clockBuffer.length != mem.clockBuffer.length) {
            throw new IllegalArgumentException("Memento clockBuffer length doesn't match");
        }
        if (this.ramBuffer.length != mem.ramBuffer.length) {
            throw new IllegalArgumentException("Memento ramBuffer length doesn't match");
        }
        System.arraycopy(mem.clockBuffer, 0, this.clockBuffer, 0, this.clockBuffer.length);
        System.arraycopy(mem.ramBuffer, 0, this.ramBuffer, 0, this.ramBuffer.length);
        this.isClockPresent = mem.isClockPresent;
        this.isDirty = mem.isDirty;
    }

    private void reportFailure(BatteryPersistenceFailedEvent.Operation operation, IOException e) {
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

    private record LoadedBattery(int[] ram, long[] clock) {
    }

    record FileBatteryMemento(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements Memento<Battery> {
    }
}
