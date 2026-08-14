package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmgLcdPowerClockTopologyTest {

    private static final int IDENTITY = 0xe4;

    @Test
    public void namedNetlistGatesReduceToOneLcdcResetWire() {
        for (boolean lcdEnabled : booleans()) {
            DmgLcdPowerClockTopology topology = new DmgLcdPowerClockTopology(lcdEnabled);

            assertEquals(lcdEnabled, topology.ff40D7());
            assertTrue(topology.xebe());
            assertEquals(!lcdEnabled, topology.xodo());
            assertEquals(lcdEnabled, topology.ppuResetN());
        }

        DmgLcdPowerClockTopology topology = new DmgLcdPowerClockTopology(true);
        topology.drivePpuHardResetN(false);
        assertFalse("XONA's reset input clears FF40.7", topology.ff40D7());
        assertFalse(topology.xebe());
        assertTrue(topology.xodo());
        assertFalse(topology.ppuResetN());

        topology.writeLcdc7(true);
        assertFalse("hard reset dominates the FF40 write input", topology.ff40D7());
        topology.drivePpuHardResetN(true);
        assertFalse("reset release does not synthesize a register write", topology.ppuResetN());
        topology.writeLcdc7(true);
        assertTrue(topology.ppuResetN());
    }

    @Test
    public void panelPadsSelectPpuClocksOnAndSlowRefreshClocksOff() {
        for (boolean lcdEnabled : booleans()) {
            for (boolean vclk : booleans()) {
                for (boolean gateClock : booleans()) {
                    for (boolean divider8192 : booleans()) {
                        for (boolean divider4096 : booleans()) {
                            DmgLcdPowerClockTopology topology =
                                    new DmgLcdPowerClockTopology(lcdEnabled);
                            topology.drivePanelClockSources(
                                    vclk, gateClock, divider8192, divider4096);

                            assertEquals(lcdEnabled ? vclk : !divider8192,
                                    topology.cplPad());
                            assertEquals(lcdEnabled ? gateClock : !divider4096,
                                    topology.cpgPad());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void disableResetCancelsRawTokenAtEveryForwardAgeWithoutRasterInputs() {
        for (int age = 0; age <= 4; age++) {
            DmgLcdOutputSignalCone cone =
                    new DmgLcdOutputSignalCone(IDENTITY, 0, 0, 1);
            cone.driveRaw(raw(2));
            tick(cone, age);

            cone.disableLcd();
            assertFalse("reset age " + age, cone.ppuResetN());
            assertFalse("panel-clock start latch age " + age, cone.panelClockRunning());
            assertFalse("opening token age " + age, cone.openingTokenPending());

            tick(cone, 8);
            assertEquals("no reset token can cross the LCD boundary at age " + age,
                    0, cone.outputSize());
        }
    }

    @Test
    public void resetReleaseCannotResurrectAValidCellButAcceptsANewFlight() {
        DmgLcdOutputSignalCone cone =
                new DmgLcdOutputSignalCone(IDENTITY, 0, 0, 1);
        cone.driveRaw(raw(1));
        tick(cone, 2);
        cone.disableLcd();
        cone.enableLcd();

        tick(cone, 8);
        assertEquals(0, cone.outputSize());
        assertFalse(cone.panelClockRunning());

        cone.driveRaw(raw(3));
        tick(cone, 5);
        assertEquals(1, cone.outputSize());
        assertEquals(3, cone.output(0).shade());
        assertTrue(cone.panelClockRunning());
    }

    @Test
    public void resetDoesNotReachBackAcrossTheAlreadyCommittedPanelBoundary() {
        DmgLcdOutputSignalCone cone =
                new DmgLcdOutputSignalCone(IDENTITY, 0, 0, 1);
        cone.driveRaw(raw(2));
        tick(cone, 5);
        assertEquals(1, cone.outputSize());

        cone.disableLcd();
        tick(cone, 8);
        assertEquals("an observation already emitted to the panel is not repaired",
                1, cone.outputSize());
        assertEquals(2, cone.output(0).shade());
    }

    @Test
    public void ppuResetDoesNotEraseTheCpuPaletteLatches() {
        DmgLcdOutputSignalCone cone =
                new DmgLcdOutputSignalCone(IDENTITY, 0, 0, 1);
        cone.writeBgp(0x1b);
        cone.disableLcd();
        cone.tick();

        assertEquals(0x1b, cone.cpuBgp());
        assertEquals(0x1b, cone.panelBgp());

        cone.enableLcd();
        cone.driveRaw(raw(2));
        tick(cone, 5);
        assertEquals(1, cone.output(0).shade());
    }

    private static DmgLcdOutputSignalCone.RawPixel raw(int background) {
        return new DmgLcdOutputSignalCone.RawPixel(background, 0, false, false);
    }

    private static void tick(DmgLcdOutputSignalCone cone, int dots) {
        for (int i = 0; i < dots; i++) {
            cone.tick();
        }
    }

    private static boolean[] booleans() {
        return new boolean[] {false, true};
    }
}
