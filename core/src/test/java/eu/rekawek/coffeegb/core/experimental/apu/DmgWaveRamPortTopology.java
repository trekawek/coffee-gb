package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.signal.SignalDelayLine;

import java.util.Set;

/**
 * Detached digital model of the DMG channel-3 wave-RAM port.
 *
 * <p>The RAM has one physical address port. {@code CH3_ACTIVE} selects either the CPU address or
 * the wave address latch. A channel fetch puts a valid token into the two-T fetch pipeline; that
 * token is also the RAM chip-select/output-enable seen by a simultaneous CPU access. With no
 * token the precharged read bus is {@code 0xff}. Consequently an active-channel CPU access aliases
 * the current wave byte for two T-cycles without an access-window timestamp or address rewrite.
 *
 * <p>This cone deliberately stops at the digital RAM controls visible in the DMG-CPU-B schematic.
 * The dynamic row/column feedback responsible for retrigger corruption is a separate experiment.
 */
final class DmgWaveRamPortTopology {

    enum Falsifier {
        SUB_T_RAM_GATE_PROPAGATION,
        RETRIGGER_ROW_COLUMN_FEEDBACK,
        FETCH_AND_CPU_WRITE_ELECTRICAL_COLLISION,
        CGB_WAVE_RAM_PROFILE
    }

    private static final int WAVE_RAM_START = 0xff30;

    private static final int WAVE_RAM_SIZE = 0x10;

    private final int[] waveRam = new int[WAVE_RAM_SIZE];

    /** BUSA/BANO-style validity stages; nonzero means the physical RAM port is selected. */
    private final SignalDelayLine fetchValid = new SignalDelayLine(2, false);

    private boolean channelActive;

    private int waveAddressLatch;

    private int sampleBuffer;

    static Set<Falsifier> profileFalsifiers() {
        return Set.of(
                Falsifier.SUB_T_RAM_GATE_PROPAGATION,
                Falsifier.RETRIGGER_ROW_COLUMN_FEEDBACK,
                Falsifier.FETCH_AND_CPU_WRITE_ELECTRICAL_COLLISION,
                Falsifier.CGB_WAVE_RAM_PROFILE);
    }

    void setChannelActive(boolean active) {
        channelActive = active;
        if (!active) {
            // CH3_ACTIVE releases the channel address and fetch gates. Wave RAM itself is not
            // reset; the CPU side of the mux becomes transparent immediately.
            fetchValid.restore(0);
        }
    }

    /**
     * Resolves and commits one 4.194304 MHz T-cycle.
     *
     * @param fetchRequest CH3's frequency-overflow/fetch request for this T-cycle
     * @param waveByteAddress byte address supplied by WAVE_INDEX (0..15)
     */
    void tickT(boolean fetchRequest, int waveByteAddress) {
        int checkedAddress = checkedWaveIndex(waveByteAddress);
        if (fetchRequest) {
            waveAddressLatch = checkedAddress;
            // The channel data latch captures before a later CPU bus access in this committed
            // T-cycle. This state is included to keep storage, port, and playback roles distinct.
            sampleBuffer = waveRam[checkedAddress];
        }
        fetchValid.resolve(channelActive && fetchRequest);
        fetchValid.commit();
    }

    int cpuRead(int address) {
        int cpuIndex = checkedCpuAddress(address);
        if (!channelActive) {
            return waveRam[cpuIndex];
        }
        // With /RAM_OE inactive the physical read bus has just been precharged high.
        return ramGateOpen() ? waveRam[waveAddressLatch] : 0xff;
    }

    void cpuWrite(int address, int value) {
        int cpuIndex = checkedCpuAddress(address);
        int checkedValue = value & 0xff;
        if (!channelActive) {
            waveRam[cpuIndex] = checkedValue;
        } else if (ramGateOpen()) {
            waveRam[waveAddressLatch] = checkedValue;
        }
    }

    int sampleBuffer() {
        return sampleBuffer;
    }

    int waveAddressLatch() {
        return waveAddressLatch;
    }

    boolean ramGateOpen() {
        return fetchValid.state() != 0;
    }

    private static int checkedCpuAddress(int address) {
        int index = address - WAVE_RAM_START;
        if (index < 0 || index >= WAVE_RAM_SIZE) {
            throw new IllegalArgumentException("address is outside FF30-FF3F");
        }
        return index;
    }

    private static int checkedWaveIndex(int address) {
        if (address < 0 || address >= WAVE_RAM_SIZE) {
            throw new IllegalArgumentException("wave byte address must be in 0..15");
        }
        return address;
    }
}
