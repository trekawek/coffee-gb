package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/**
 * General Vast Fame VF001 protection layered over MBC5. Its config ports can
 * inject a short byte sequence or replace the upper part of fixed bank zero.
 */
public class Vf001General implements MemoryController {

    private final Mbc5 delegate;

    private final int[] rom;

    private final int romBanks;

    private boolean configMode;

    private int runningValue;

    private int cur6000;

    private final int[] cur700x = new int[15];

    private int sequenceStartBank;

    private int sequenceStartAddress;

    private int sequenceLength;

    private final int[] sequence = new int[4];

    private int sequenceBytesLeft;

    private boolean replaceBankZero;

    private int replacementStartAddress;

    private int replacementSourceBank;

    private int selectedRomBank = 1;

    public Vf001General(Rom rom, Battery battery) {
        delegate = new Mbc5(rom, battery);
        this.rom = rom.getRom();
        this.romBanks = Math.max(1, (this.rom.length + 0x3fff) / 0x4000);
    }

    @Override
    public void init(EventBus eventBus) {
        delegate.init(eventBus);
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (address >= 0x2000 && address < 0x3000) {
            selectedRomBank = (selectedRomBank & 0x100) | value;
        } else if (address >= 0x3000 && address < 0x4000) {
            selectedRomBank = (selectedRomBank & 0x0ff) | ((value & 1) << 8);
        } else if (address >= 0x6000 && address < 0x8000) {
            writeConfig(address, value);
        }
        delegate.setByte(address, value);
    }

    private void writeConfig(int address, int value) {
        int effectiveAddress = address & 0xf00f;
        if (effectiveAddress == 0x7000 && value == 0x96) {
            configMode = true;
            runningValue = 0;
            return;
        }
        if (effectiveAddress == 0x700f && value == 0x96) {
            configMode = false;
            return;
        }
        if (!configMode) {
            return;
        }
        if (effectiveAddress >= 0x700b
                || (effectiveAddress > 0x6000 && effectiveAddress < 0x7000)) {
            return;
        }

        runningValue = ((runningValue >>> 1) | ((runningValue & 1) << 7)) ^ value;
        if (effectiveAddress >= 0x7000) {
            cur700x[effectiveAddress & 0x0f] = runningValue;
        } else if (effectiveAddress == 0x6000) {
            cur6000 = runningValue;
        }

        if (effectiveAddress == 0x7000) {
            sequenceStartBank = cur700x[3];
            sequenceStartAddress = (cur700x[2] << 8) | cur700x[1];
            System.arraycopy(cur700x, 4, sequence, 0, sequence.length);
            sequenceLength = switch (cur700x[0] & 0x07) {
                case 4 -> 1;
                case 5 -> 2;
                case 6 -> 3;
                case 7 -> 4;
                default -> 0;
            };
        } else if (effectiveAddress == 0x7008) {
            replacementStartAddress = (cur700x[10] << 8) | cur700x[9];
            replacementSourceBank = cur6000;
            replaceBankZero = (cur700x[8] & 0x0f) == 0x0f;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x8000) {
            if (sequenceLength > 0 && sequenceBytesLeft == 0) {
                boolean bankMatches = sequenceStartBank == 0 && address < 0x3fff
                        || sequenceStartBank == selectedRomBank && address >= 0x4000;
                if (bankMatches && address == sequenceStartAddress) {
                    sequenceBytesLeft = sequenceLength;
                }
            }
            if (sequenceBytesLeft > 0) {
                int index = sequenceLength - sequenceBytesLeft;
                sequenceBytesLeft--;
                return sequence[index];
            }
            if (replaceBankZero && address >= replacementStartAddress && address < 0x4000) {
                int bank = Math.floorMod(replacementSourceBank, romBanks);
                int offset = bank * 0x4000 + address;
                return offset < rom.length ? rom[offset] : 0xff;
            }
        }
        return delegate.getByte(address);
    }

    @Override
    public void flushRam() {
        delegate.flushRam();
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new Vf001GeneralState(delegate.captureState(), configMode, runningValue,
                cur6000, cur700x.clone(), sequenceStartBank, sequenceStartAddress,
                sequenceLength, sequence.clone(), sequenceBytesLeft, replaceBankZero,
                replacementStartAddress, replacementSourceBank, selectedRomBank);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Vf001GeneralState(delegate.captureState(capture), configMode, runningValue,
                cur6000, capture.ints(cur700x), sequenceStartBank, sequenceStartAddress,
                sequenceLength, capture.ints(sequence), sequenceBytesLeft, replaceBankZero,
                replacementStartAddress, replacementSourceBank, selectedRomBank);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        delegate.declareMachineStatePayloads(capture);
        capture.declareInts(cur700x);
        capture.declareInts(sequence);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof Vf001GeneralState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (mem.cur700x.length != cur700x.length || mem.sequence.length != sequence.length) {
            throw new IllegalArgumentException("ComponentState register length doesn't match");
        }
        delegate.restoreState(mem.delegateMemento);
        configMode = mem.configMode;
        runningValue = mem.runningValue;
        cur6000 = mem.cur6000;
        System.arraycopy(mem.cur700x, 0, cur700x, 0, cur700x.length);
        sequenceStartBank = mem.sequenceStartBank;
        sequenceStartAddress = mem.sequenceStartAddress;
        sequenceLength = mem.sequenceLength;
        System.arraycopy(mem.sequence, 0, sequence, 0, sequence.length);
        sequenceBytesLeft = mem.sequenceBytesLeft;
        replaceBankZero = mem.replaceBankZero;
        replacementStartAddress = mem.replacementStartAddress;
        replacementSourceBank = mem.replacementSourceBank;
        selectedRomBank = mem.selectedRomBank;
    }

    private record Vf001GeneralState(ComponentState<MemoryController> delegateMemento,
                                      boolean configMode, int runningValue, int cur6000,
                                      int[] cur700x, int sequenceStartBank,
                                      int sequenceStartAddress, int sequenceLength,
                                      int[] sequence, int sequenceBytesLeft,
                                      boolean replaceBankZero, int replacementStartAddress,
                                      int replacementSourceBank, int selectedRomBank)
            implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record Vf001GeneralMemento(Memento<MemoryController> delegateMemento,
                                        boolean configMode, int runningValue, int cur6000,
                                        int[] cur700x, int sequenceStartBank,
                                        int sequenceStartAddress, int sequenceLength,
                                        int[] sequence, int sequenceBytesLeft,
                                        boolean replaceBankZero, int replacementStartAddress,
                                        int replacementSourceBank, int selectedRomBank)
            implements Memento<MemoryController> {
    }
}
