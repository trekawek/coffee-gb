package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.sound.FrameSequencer;
import eu.rekawek.coffeegb.core.sound.SoundMode3;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.core.experimental.apu.DmgWaveRamPortTopology.Falsifier.CGB_WAVE_RAM_PROFILE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgWaveRamPortTopology.Falsifier.FETCH_AND_CPU_WRITE_ELECTRICAL_COLLISION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgWaveRamPortTopology.Falsifier.RETRIGGER_ROW_COLUMN_FEEDBACK;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgWaveRamPortTopology.Falsifier.SUB_T_RAM_GATE_PROPAGATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Differential evidence for the DMG wave address mux, precharge, and fetch-valid stages. */
public class DmgWaveRamPortTopologyTest {

    private static final int WAVE_RAM_START = 0xff30;

    @Test
    public void twoStageFetchTokenCreatesTheAccessWindowAndAddressAliasing() {
        DmgWaveRamPortTopology port = initializedPort();

        // Inactive CH3 gives the CPU ownership of both address and RAM gate.
        assertEquals(pattern(7), port.cpuRead(WAVE_RAM_START + 7));

        port.setChannelActive(true);
        assertFalse(port.ramGateOpen());
        assertEquals(0xff, port.cpuRead(WAVE_RAM_START + 7));
        port.cpuWrite(WAVE_RAM_START + 7, 0x55); // precharged/closed port: no storage write

        port.tickT(true, 11);
        assertTrue(port.ramGateOpen());
        assertEquals(11, port.waveAddressLatch());
        assertEquals(pattern(11), port.sampleBuffer());
        for (int cpuAddress = 0; cpuAddress < 16; cpuAddress++) {
            assertEquals(pattern(11), port.cpuRead(WAVE_RAM_START + cpuAddress));
        }

        port.tickT(false, 0);
        assertTrue(port.ramGateOpen());
        port.tickT(false, 0);
        assertFalse(port.ramGateOpen());
        assertEquals(0xff, port.cpuRead(WAVE_RAM_START));

        port.setChannelActive(false);
        assertEquals(pattern(7), port.cpuRead(WAVE_RAM_START + 7));
    }

    @Test
    public void activeReadConeMatchesProductionForEveryAddressFetchByteAndGatePhase() {
        int[] periods = {2, 3, 4, 7, 16};
        for (int period : periods) {
            Pair pair = newPair(period);
            int position = 0;
            int fetches = 0;
            int ticks = firstFetchTick(period) + 32 * 2 * period + 2;
            for (int tick = 1; tick <= ticks; tick++) {
                pair.production.tick();
                boolean fetch = isFetchTick(tick, period);
                if (fetch) {
                    position = (position + 1) & 31;
                    fetches++;
                }
                pair.port.tickT(fetch, position >> 1);

                for (int cpuAddress = 0; cpuAddress < 16; cpuAddress++) {
                    String label = "period=" + period + ", tick=" + tick
                            + ", fetch=" + fetches + ", cpu=" + cpuAddress;
                    assertEquals(label,
                            pair.production.getByte(WAVE_RAM_START + cpuAddress),
                            pair.port.cpuRead(WAVE_RAM_START + cpuAddress));
                }
            }
            assertTrue("period=" + period, fetches >= 32);
        }
    }

    @Test
    public void activeWriteConeMatchesProductionForEveryCpuAndWaveAddress() {
        int period = 4; // leaves closed-port phases between consecutive channel fetches
        for (int waveAddress = 0; waveAddress < 16; waveAddress++) {
            for (int cpuAddress = 0; cpuAddress < 16; cpuAddress++) {
                for (int fetchAge = 0; fetchAge <= 2; fetchAge++) {
                    Pair pair = newPair(period);
                    advanceToFetchByte(pair, period, waveAddress);
                    for (int i = 0; i < fetchAge; i++) {
                        pair.production.tick();
                        pair.port.tickT(false, 0);
                    }

                    int value = (0x41 + 13 * waveAddress + 7 * cpuAddress + fetchAge) & 0xff;
                    pair.production.setByte(WAVE_RAM_START + cpuAddress, value);
                    pair.port.cpuWrite(WAVE_RAM_START + cpuAddress, value);

                    // Releasing CH3 exposes every physical byte for a complete storage diff.
                    pair.production.setByte(0xff1a, 0x00);
                    pair.port.setChannelActive(false);
                    for (int address = 0; address < 16; address++) {
                        String label = "wave=" + waveAddress + ", cpu=" + cpuAddress
                                + ", age=" + fetchAge + ", inspect=" + address;
                        assertEquals(label,
                                pair.production.getByte(WAVE_RAM_START + address),
                                pair.port.cpuRead(WAVE_RAM_START + address));
                    }
                }
            }
        }
    }

    @Test
    public void localConeNamesTheUnmodeledProfilesInsteadOfGuessingThem() {
        assertEquals(EnumSet.of(
                        SUB_T_RAM_GATE_PROPAGATION,
                        RETRIGGER_ROW_COLUMN_FEEDBACK,
                        FETCH_AND_CPU_WRITE_ELECTRICAL_COLLISION,
                        CGB_WAVE_RAM_PROFILE),
                DmgWaveRamPortTopology.profileFalsifiers());
    }

    private static Pair newPair(int period) {
        SoundMode3 production = newDmgMode();
        DmgWaveRamPortTopology port = initializedPort();
        for (int address = 0; address < 16; address++) {
            production.setByte(WAVE_RAM_START + address, pattern(address));
        }

        int frequency = 2048 - period;
        production.setByte(0xff1a, 0x80);
        production.setByte(0xff1c, 0x20);
        production.setByte(0xff1d, frequency & 0xff);
        production.setByte(0xff1e, 0x80 | ((frequency >>> 8) & 0x07));
        port.setChannelActive(true);
        return new Pair(production, port);
    }

    private static DmgWaveRamPortTopology initializedPort() {
        DmgWaveRamPortTopology port = new DmgWaveRamPortTopology();
        for (int address = 0; address < 16; address++) {
            port.cpuWrite(WAVE_RAM_START + address, pattern(address));
        }
        return port;
    }

    private static SoundMode3 newDmgMode() {
        SpeedMode speedMode = new SpeedMode(false);
        Timer timer = new Timer(new InterruptManager(false), speedMode);
        SoundMode3 mode = new SoundMode3(new FrameSequencer(), timer, false);
        mode.start();
        return mode;
    }

    private static void advanceToFetchByte(Pair pair, int period, int targetByte) {
        int position = 0;
        int maxTicks = firstFetchTick(period) + 32 * 2 * period;
        for (int tick = 1; tick <= maxTicks; tick++) {
            pair.production.tick();
            boolean fetch = isFetchTick(tick, period);
            if (fetch) {
                position = (position + 1) & 31;
            }
            pair.port.tickT(fetch, position >> 1);
            if (fetch && (position >> 1) == targetByte) {
                return;
            }
        }
        throw new AssertionError("wave byte was never fetched: " + targetByte);
    }

    private static int firstFetchTick(int period) {
        return 2 * (period + 3) - 1;
    }

    private static boolean isFetchTick(int tick, int period) {
        int first = firstFetchTick(period);
        return tick >= first && (tick - first) % (2 * period) == 0;
    }

    private static int pattern(int address) {
        return 0x20 + 7 * address;
    }

    private record Pair(SoundMode3 production, DmgWaveRamPortTopology port) {}
}
