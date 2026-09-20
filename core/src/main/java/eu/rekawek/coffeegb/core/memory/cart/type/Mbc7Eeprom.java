package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

public class Mbc7Eeprom  {

    private enum State {
        IDLE, COMMAND, READING, WRITING
    }

    private final Battery battery;

    private final int[] eeprom = new int[256];

    private boolean dirty;

    private State state = State.IDLE;

    private int bitsRead;

    private int command;

    private int address;

    private int writeValue;

    private boolean writeEnabled;

    private boolean sk;

    private int doBit = 1;

    public Mbc7Eeprom(Battery battery) {
        this.battery = battery;
        for (int i = 0; i < 256; i++) {
            eeprom[i] = 0xff;
        }
        battery.loadRam(eeprom);
    }

    public void flush() {
        if (dirty) {
            battery.saveRam(eeprom);
            battery.flush();
        }
    }

    public void write(int value) {
        boolean oldSk = sk;
        sk = (value & 0x40) != 0;
        boolean cs = (value & 0x80) != 0;
        boolean di = (value & 0x02) != 0;

        if (!cs) {
            state = State.IDLE;
            doBit = 1;
            return;
        }

        if (oldSk || !sk) {
            return;
        }

        switch (state) {
            case IDLE -> {
                if (di) {
                    state = State.COMMAND;
                    bitsRead = 0;
                    command = 0;
                }
            }
            case COMMAND -> {
                command = (command << 1) | (di ? 1 : 0);
                bitsRead++;
                if (bitsRead == 10) {
                    int op = (command >> 8) & 3;
                    int addr = command & 0xff;
                    switch (op) {
                        case 0 -> {
                            int subOp = (addr >> 6) & 3;
                            switch (subOp) {
                                case 0 -> { // EWDS
                                    writeEnabled = false;
                                    state = State.IDLE;
                                }
                                case 1 -> { // WRAL
                                    state = State.WRITING;
                                    bitsRead = 0;
                                    address = -1;
                                    writeValue = 0;
                                }
                                case 2 -> { // ERAL
                                    if (writeEnabled) {
                                        for (int i = 0; i < 256; i++) {
                                            eeprom[i] = 0xff;
                                        }
                                        dirty = true;
                                    }
                                    state = State.IDLE;
                                }
                                case 3 -> { // EWEN
                                    writeEnabled = true;
                                    state = State.IDLE;
                                }
                            }
                        }
                        case 1 -> { // WRITE
                            state = State.WRITING;
                            bitsRead = 0;
                            address = addr & 0x7f;
                            writeValue = 0;
                        }
                        case 2 -> { // READ
                            state = State.READING;
                            bitsRead = 0;
                            address = addr & 0x7f;
                            doBit = 0;
                        }
                        case 3 -> { // ERASE
                            if (writeEnabled) {
                                int a = (addr & 0x7f) * 2;
                                eeprom[a] = 0xff;
                                eeprom[a + 1] = 0xff;
                                dirty = true;
                            }
                            state = State.IDLE;
                        }
                    }
                }
            }
            case READING -> {
                if (bitsRead == 0) {
                    doBit = 0;
                } else {
                    int bitIndex = 16 - bitsRead;
                    int byteAddr = (address * 2) + (bitIndex < 8 ? 1 : 0);
                    int bitInByte = bitIndex % 8;
                    doBit = (eeprom[byteAddr] >> bitInByte) & 1;
                }
                bitsRead++;
                if (bitsRead == 17) {
                    state = State.IDLE;
                }
            }
            case WRITING -> {
                writeValue = (writeValue << 1) | (di ? 1 : 0);
                bitsRead++;
                if (bitsRead == 16) {
                    if (writeEnabled) {
                        if (address == -1) {
                            for (int i = 0; i < 128; i++) {
                                eeprom[i * 2] = (writeValue >> 8) & 0xff;
                                eeprom[i * 2 + 1] = writeValue & 0xff;
                            }
                        } else {
                            eeprom[address * 2] = (writeValue >> 8) & 0xff;
                            eeprom[address * 2 + 1] = writeValue & 0xff;
                        }
                        dirty = true;
                    }
                    state = State.IDLE;
                    doBit = 1;
                }
            }
        }
    }

    public int read() {
        return doBit;
    }

    /** BESS stores EEPROM words in little-endian order, unlike our byte-oriented backing store. */
    public byte[] captureBessRam() {
        byte[] result = new byte[eeprom.length];
        for (int i = 0; i < result.length; i++) result[i] = (byte) eeprom[i ^ 1];
        return result;
    }

    public void restoreBessRam(byte[] data) {
        for (int i = 0; i < eeprom.length; i++) {
            eeprom[i ^ 1] = i < data.length ? data[i] & 0xff : 0;
        }
        dirty = true;
    }

    public int captureBessFlags() {
        if (state != State.IDLE) {
            throw new IllegalArgumentException("BESS cannot save an active MBC7 EEPROM command");
        }
        // DI and CS do not affect an idle command and will be supplied by the next CPU write.
        return (doBit << 1) | (sk ? 8 : 0) | (writeEnabled ? 0x20 : 0);
    }

    public void restoreBessControl(int flags, int argumentBits, int command, int pendingBits) {
        if (argumentBits != 0 || command != 0 || pendingBits != 0xffff) {
            throw new IllegalArgumentException("BESS cannot load an active MBC7 EEPROM command");
        }
        state = State.IDLE;
        bitsRead = 0;
        this.command = 0;
        address = 0;
        writeValue = 0;
        doBit = (flags >> 1) & 1;
        sk = (flags & 8) != 0;
        writeEnabled = (flags & 0x20) != 0;
    }

    public ComponentState<Mbc7Eeprom> captureState() {
        return new EepromState(
                eeprom.clone(),
                state,
                bitsRead,
                command,
                address,
                writeValue,
                writeEnabled,
                sk,
                doBit
        );
    }

    public ComponentState<Mbc7Eeprom> captureState(MachineStateCapture capture) {
        return new EepromState(
                capture.ints(eeprom),
                state,
                bitsRead,
                command,
                address,
                writeValue,
                writeEnabled,
                sk,
                doBit
        );
    }

    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts(eeprom);
    }

    public void restoreState(ComponentState<Mbc7Eeprom> state) {
        if (state instanceof EepromState mem) {
            System.arraycopy(mem.eeprom, 0, this.eeprom, 0, 256);
            this.state = mem.state;
            this.bitsRead = mem.bitsRead;
            this.command = mem.command;
            this.address = mem.address;
            this.writeValue = mem.writeValue;
            this.writeEnabled = mem.writeEnabled;
            this.sk = mem.sk;
            this.doBit = mem.doBit;
        }
    }

    private record EepromState(
            int[] eeprom,
            State state,
            int bitsRead,
            int command,
            int address,
            int writeValue,
            boolean writeEnabled,
            boolean sk,
            int doBit
    ) implements ComponentState<Mbc7Eeprom> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record EepromMemento(
            int[] eeprom,
            State state,
            int bitsRead,
            int command,
            int address,
            int writeValue,
            boolean writeEnabled,
            boolean sk,
            int doBit
    ) implements Memento<Mbc7Eeprom> {
    }
}
