package eu.rekawek.coffeegb.core.experimental.serial;

import eu.rekawek.coffeegb.core.signal.EdgeDetector;
import eu.rekawek.coffeegb.core.signal.SignalDelayLine;
import eu.rekawek.coffeegb.core.signal.SrLatch;

import static eu.rekawek.coffeegb.core.signal.SrLatch.Dominance.CLEAR;

/**
 * Detached serial/IF experiment. This is deliberately not an {@code AddressSpace}: register
 * accesses arrive as bus strobes and every state element observes the same old state before the
 * half-dot is committed.
 *
 * <p>The model contains no event deadlines. The serial request is a wire produced by the eighth
 * shift, the CPU acknowledge is another wire, and their collision is resolved by the IF latch.
 * HALT sees the stored request through a fitted four-CPU-clock delay line while ordinary code and
 * the IF data bus see the latch directly. The external DMG gate trace instead contains a
 * phase-transparent pending bank, a combinational pending path, and one wake DFF; this experiment's
 * four stages are a production-equivalence projection, not a recovered gate topology.</p>
 */
final class SerialSignalMachine {

    static final int SB = 0xff01;

    static final int SC = 0xff02;

    static final int DIV = 0xff04;

    static final int IF = 0xff0f;

    static final int IE = 0xffff;

    static final int SERIAL_MASK = 1 << 3;

    record Profile(boolean cgb, boolean dmgCompatibility, boolean doubleSpeed) {

        boolean colorMode() {
            return cgb && !dmgCompatibility;
        }

        int initialDividerLow() {
            return cgb ? 0 : 8;
        }
    }

    record BusWrite(boolean active, int address, int value) {

        static final BusWrite NONE = new BusWrite(false, 0, 0);

        static BusWrite to(int address, int value) {
            if ((value & ~0xff) != 0) {
                throw new IllegalArgumentException("bus value must be an unsigned byte");
            }
            return new BusWrite(true, address, value);
        }
    }

    /** All resolved input wires for one 8.39 MHz half-dot. */
    record Inputs(
            boolean cpuClockEdge,
            BusWrite busWrite,
            boolean serialInterruptAcknowledge,
            int incomingBit) {

        Inputs {
            if (busWrite == null) {
                throw new NullPointerException("busWrite");
            }
            if (incomingBit < -1 || incomingBit > 1) {
                throw new IllegalArgumentException("incomingBit must be -1, 0, or 1");
            }
        }

        static Inputs idle(boolean cpuClockEdge) {
            return new Inputs(cpuClockEdge, BusWrite.NONE, false, 1);
        }
    }

    record Observation(
            int sb,
            int sc,
            int receivedBits,
            int dividerLow,
            boolean serialClock,
            int sentBits,
            boolean serialRequestWire,
            boolean interruptAcknowledgeWire,
            boolean readableIf,
            boolean runningCpuRequest,
            boolean haltWakeRequest) {
    }

    record Snapshot(
            int sb,
            int sc,
            int receivedBits,
            int sentBits,
            int dividerLow,
            boolean serialClock,
            boolean interruptFlag,
            boolean interruptEnabled,
            long haltWakePipeline) {
    }

    private final Profile profile;

    private final SerialClockCell clock;

    /** The real IF bit is clear-dominant when request and CPU acknowledge meet. */
    private final SrLatch interruptFlag = new SrLatch(CLEAR, false);

    /** Four CPU clocks from stored IF visibility to the HALT wake observation point. */
    private final SignalDelayLine haltWakePipeline = new SignalDelayLine(4, false);

    private int sb;

    private int sc = 0x02;

    private int receivedBits;

    private int sentBits;

    private boolean interruptEnabled;

    private boolean serialRequestWire;

    private boolean interruptAcknowledgeWire;

    SerialSignalMachine(Profile profile) {
        this.profile = profile;
        this.clock = new SerialClockCell(profile.initialDividerLow());
    }

    /**
     * Resolves and commits one half-dot. No call made from this method mutates a value that a later
     * subsystem is allowed to sample as its old state.
     */
    Observation step(Inputs inputs) {
        BusWrite write = inputs.busWrite();
        boolean writeSb = write.active() && write.address() == SB;
        boolean writeSc = write.active() && write.address() == SC;
        boolean writeDiv = write.active() && write.address() == DIV;
        boolean writeIf = write.active() && write.address() == IF;
        boolean writeIe = write.active() && write.address() == IE;

        int nextSb = writeSb ? write.value() : sb;
        int nextSc = sc;
        int nextReceivedBits = receivedBits;
        boolean forcedClockLevel = false;
        boolean clockLevelForced = false;

        if (writeSc) {
            boolean startsTransfer = (write.value() & 0x80) != 0;
            nextReceivedBits = 0;
            if (startsTransfer) {
                // SC.7 sets the transfer latch and clears the serial output flip-flop. Changing
                // the CGB tap select can then expose a high source through the mux.
                forcedClockLevel = false;
                clockLevelForced = true;
                if (profile.colorMode()
                        && (sc & 0x80) != 0
                        && ((sc ^ write.value()) & 0x02) != 0) {
                    int oldTapBelow = (sc & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    int newTapBelow = (write.value() & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    if ((clock.dividerLow() & oldTapBelow) != 0
                            && (clock.dividerLow() & newTapBelow) == 0) {
                        forcedClockLevel = true;
                    }
                }
            }
            nextSc = write.value();
        }

        int halfPeriod = internalClockHalfPeriod(nextSc);
        boolean internalTransfer = (nextSc & 0x81) == 0x81;
        SerialClockCell.Resolution clockResolution = clock.resolve(
                inputs.cpuClockEdge(),
                writeDiv,
                internalTransfer,
                halfPeriod,
                clockLevelForced,
                forcedClockLevel);

        boolean internalShift = internalTransfer && clockResolution.fallingEdgeFromDivider();
        boolean externalShift = inputs.cpuClockEdge()
                && (nextSc & 0x01) == 0
                && (nextSc & 0x80) != 0
                && inputs.incomingBit() != -1;
        boolean shift = internalShift || externalShift;
        boolean request = false;
        int nextSentBits = sentBits;
        if (shift) {
            nextSb = ((nextSb << 1) | (inputs.incomingBit() & 1)) & 0xff;
            nextReceivedBits++;
            if (internalShift) {
                nextSentBits++;
            }
            if (nextReceivedBits == 8) {
                nextSc &= 0x7f;
                nextReceivedBits = 0;
                request = true;
            }
        }

        boolean ifSet = request || (writeIf && (write.value() & SERIAL_MASK) != 0);
        boolean ifClear = inputs.serialInterruptAcknowledge()
                || (writeIf && (write.value() & SERIAL_MASK) == 0);
        interruptFlag.resolve(ifSet, ifClear);

        // The synchronizer samples the previously committed IF value. A request produced on this
        // edge is therefore captured by stage zero on the following CPU edge and reaches stage
        // three four CPU clocks after it first became readable.
        if (inputs.cpuClockEdge()) {
            haltWakePipeline.resolve(interruptFlag.q() && interruptEnabled);
        }

        sb = nextSb;
        sc = nextSc;
        receivedBits = nextReceivedBits;
        sentBits = nextSentBits;
        if (writeIe) {
            interruptEnabled = (write.value() & SERIAL_MASK) != 0;
        }
        clock.commit(clockResolution);
        interruptFlag.commit();
        if (inputs.cpuClockEdge()) {
            haltWakePipeline.commit();
        }
        serialRequestWire = request;
        interruptAcknowledgeWire = inputs.serialInterruptAcknowledge();
        return observe();
    }

    Observation observe() {
        boolean requested = interruptFlag.q() && interruptEnabled;
        return new Observation(
                sb,
                readSc(),
                receivedBits,
                clock.dividerLow(),
                clock.outputLevel(),
                sentBits,
                serialRequestWire,
                interruptAcknowledgeWire,
                interruptFlag.q(),
                requested,
                requested && haltWakePipeline.output());
    }

    Snapshot snapshot() {
        return new Snapshot(
                sb,
                sc,
                receivedBits,
                sentBits,
                clock.dividerLow(),
                clock.outputLevel(),
                interruptFlag.q(),
                interruptEnabled,
                haltWakePipeline.state());
    }

    void restore(Snapshot snapshot) {
        sb = snapshot.sb();
        sc = snapshot.sc();
        receivedBits = snapshot.receivedBits();
        sentBits = snapshot.sentBits();
        clock.restore(snapshot.dividerLow(), snapshot.serialClock());
        interruptFlag.restore(snapshot.interruptFlag());
        interruptEnabled = snapshot.interruptEnabled();
        haltWakePipeline.restore(snapshot.haltWakePipeline());
        serialRequestWire = false;
        interruptAcknowledgeWire = false;
    }

    int cpuClocksPerMasterDot() {
        return profile.doubleSpeed() ? 2 : 1;
    }

    int internalClockHalfPeriod() {
        return internalClockHalfPeriod(sc);
    }

    private int internalClockHalfPeriod(int effectiveSc) {
        return profile.colorMode() && (effectiveSc & 0x02) != 0 ? 8 : 256;
    }

    private int readSc() {
        return sc | (profile.colorMode() ? 0x7c : 0x7e);
    }

    /**
     * The serial clock is a divider stage followed by an output flip-flop, not a combinational
     * alias of one DIV bit. Resetting the divider toggles that flip-flop iff the stage immediately
     * below the selected tap was high. The falling edge, if any, is simply observed at the output.
     */
    private static final class SerialClockCell {

        private final EdgeDetector outputEdge = new EdgeDetector(false);

        private int dividerLow;

        private SerialClockCell(int initialDividerLow) {
            dividerLow = initialDividerLow & 0xff;
        }

        private Resolution resolve(
                boolean cpuClockEdge,
                boolean dividerReset,
                boolean internalTransfer,
                int halfPeriod,
                boolean outputForced,
                boolean forcedOutputLevel) {
            boolean nextOutput = outputForced ? forcedOutputLevel : outputEdge.previousLevel();
            int nextDividerLow = dividerLow;
            boolean transitionFromDivider = false;

            if (dividerReset) {
                nextDividerLow = 0;
                if (!internalTransfer) {
                    nextOutput = false;
                } else if ((dividerLow & (halfPeriod >> 1)) != 0) {
                    nextOutput = !nextOutput;
                    transitionFromDivider = true;
                }
            } else if (cpuClockEdge) {
                if (internalTransfer && (dividerLow & (halfPeriod - 1)) == halfPeriod - 1) {
                    nextOutput = !nextOutput;
                    transitionFromDivider = true;
                }
                nextDividerLow = (dividerLow + 1) & 0xff;
            }

            outputEdge.resolve(nextOutput);
            return new Resolution(
                    nextDividerLow,
                    nextOutput,
                    transitionFromDivider && outputEdge.falling());
        }

        private void commit(Resolution resolution) {
            dividerLow = resolution.nextDividerLow();
            outputEdge.commit();
        }

        private int dividerLow() {
            return dividerLow;
        }

        private boolean outputLevel() {
            return outputEdge.previousLevel();
        }

        private void restore(int dividerLow, boolean outputLevel) {
            this.dividerLow = dividerLow & 0xff;
            outputEdge.restore(outputLevel);
        }

        private record Resolution(
                int nextDividerLow,
                boolean nextOutputLevel,
                boolean fallingEdgeFromDivider) {
        }
    }
}
