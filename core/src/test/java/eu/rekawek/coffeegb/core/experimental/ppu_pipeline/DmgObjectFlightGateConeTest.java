package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DmgObjectFlightGateConeTest {

    @Test
    public void fallingBeforeTheFirstByteWithdrawsTheFutureFlight() {
        DmgObjectFlightGateCone cone = new DmgObjectFlightGateCone();

        var matched = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withComparator(true));
        assertTrue(matched.matchGate());
        assertTrue(matched.matchToken());

        var falling = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).withComparator(true));
        assertFalse(falling.matchGate());
        assertFalse(falling.matchToken());

        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).captureLow(0xf0));
        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).captureHigh(0x0f));
        var load = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).onLoadEdge());

        assertFalse(load.shiftBankLoaded());
        assertEquals(0, load.planeA());
        assertEquals(0, load.planeB());
    }

    @Test
    public void fallingAtTheHighAddressCannotRecallACommittedLowByteFlight() {
        DmgObjectFlightGateCone cone = lowByteCommitted();

        var high = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).captureHigh(0x0f));
        assertFalse(high.matchGate());
        assertTrue(high.lowByteValid());
        assertTrue(high.highByteValid());
        assertEquals("LCDC.1 owns neither retained byte", 0, high.outputA());
        assertEquals(0, high.outputB());

        var load = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).onLoadEdge());
        assertTrue(load.shiftBankLoaded());
        assertEquals(0x0f, load.planeA());
        assertEquals(0xf0, load.planeB());
        assertEquals("woxa masks A7 while D1 is low", 0, load.outputA());
        assertEquals("xula masks B7 while D1 is low", 0, load.outputB());
    }

    @Test
    public void disabledOutputDoesNotStopTheLoadedPhysicalShiftBanks() {
        DmgObjectFlightGateCone cone = lowByteCommitted();
        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).captureHigh(0x0f));
        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).onLoadEdge());

        int[] expectedA = {0x1e, 0x3c, 0x78, 0xf0, 0xe0, 0xc0, 0x80, 0x00};
        int[] expectedB = {0xe0, 0xc0, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00};
        for (int i = 0; i < expectedA.length; i++) {
            var shift = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                    .withObjEnable(false).onPixelClock());
            assertEquals("plane A shift " + i, expectedA[i], shift.planeA());
            assertEquals("plane B shift " + i, expectedB[i], shift.planeB());
            assertEquals(0, shift.outputA());
            assertEquals(0, shift.outputB());
        }
    }

    @Test
    public void enabledControlUsesTheSameFlightAndOnlyChangesTheOutputMask() {
        DmgObjectFlightGateCone cone = lowByteCommitted();
        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled().captureHigh(0x0f));
        var load = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled().onLoadEdge());

        assertTrue(load.shiftBankLoaded());
        assertEquals(0x0f, load.planeA());
        assertEquals(0xf0, load.planeB());
        assertEquals(0, load.outputA());
        assertEquals(1, load.outputB());

        var shift = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled().onPixelClock());
        assertEquals(0x1e, shift.planeA());
        assertEquals(0xe0, shift.planeB());
        assertEquals(0, shift.outputA());
        assertEquals(1, shift.outputB());
    }

    @Test
    public void noThreeDotOrPositionInputExistsAtTheExecutableBoundary() {
        assertEquals(EnumSet.of(
                        DmgObjectFlightGateCone.InputBoundary.DMG_LCD_ENABLED_MODE3,
                        DmgObjectFlightGateCone.InputBoundary.OAM_X_COMPARATOR_LEVEL,
                        DmgObjectFlightGateCone.InputBoundary.VRAM_LOW_BYTE_CAPTURE,
                        DmgObjectFlightGateCone.InputBoundary.VRAM_HIGH_BYTE_CAPTURE,
                        DmgObjectFlightGateCone.InputBoundary.SHIFT_BANK_LOAD_EDGE,
                        DmgObjectFlightGateCone.InputBoundary.PIXEL_SHIFT_CLOCK,
                        DmgObjectFlightGateCone.InputBoundary.CPU_VISIBLE_FF40_D1),
                DmgObjectFlightGateCone.inputBoundaries());
    }

    @Test
    public void evidenceAndFiniteFalsifiersRemainExplicit() {
        assertEquals("ee559e1d963e1cc522df512e3bae1b4e5ff96fb5",
                DmgObjectFlightGateCone.NETLIST_REVISION);
        assertEquals(EnumSet.of(
                        DmgObjectFlightGateCone.Evidence.STATIC_THREE_CONSUMER_FF40_D1_CONE,
                        DmgObjectFlightGateCone.Evidence.IVERILOG_NODELAY_TWO_APERTURE_CPU_PROBE,
                        DmgObjectFlightGateCone.Evidence.IVERILOG_DEFAULT_DELAY_LATE_CPU_PHASE_PROBE,
                        DmgObjectFlightGateCone.Evidence.IVERILOG_NODELAY_ENABLED_CONTROL_TRACE),
                DmgObjectFlightGateCone.evidence());
        assertEquals(EnumSet.of(
                        DmgObjectFlightGateCone.Falsifier.PRE_LOW_D1_FALL_STILL_LAUNCHES_A_BYTE,
                        DmgObjectFlightGateCone.Falsifier.POST_LOW_D1_FALL_RECALLS_THE_HIGH_BYTE,
                        DmgObjectFlightGateCone.Falsifier.D1_LOW_STOPS_AN_ALREADY_LOADED_SHIFT_BANK,
                        DmgObjectFlightGateCone.Falsifier.D1_LOW_LEAKS_AN_OBJECT_OUTPUT_BIT,
                        DmgObjectFlightGateCone.Falsifier.A_DIFFERENT_OAM_SLOT_BYPASSES_AROR,
                        DmgObjectFlightGateCone.Falsifier.A_DIFFERENT_X_ROW_OR_TILE_CHANGES_RETIREMENT,
                        DmgObjectFlightGateCone.Falsifier.XFLIP_OR_PRIORITY_USES_A_DIFFERENT_RETIREMENT_PATH,
                        DmgObjectFlightGateCone.Falsifier.AN_UNPROBED_CPU_WRITE_APERTURE_REORDERS_THE_CAPTURE,
                        DmgObjectFlightGateCone.Falsifier.DEFAULT_DELAY_EARLY_APERTURE_DOES_NOT_CANCEL,
                        DmgObjectFlightGateCone.Falsifier.SIMPLIFIED_OAM_MACRO_CHANGES_FETCH_CONTROL,
                        DmgObjectFlightGateCone.Falsifier.PHYSICAL_DMG_DIFFERS_FROM_THE_REVERSE_ENGINEERED_MODEL,
                        DmgObjectFlightGateCone.Falsifier.CGB_TOPOLOGY_DIFFERS),
                DmgObjectFlightGateCone.falsifiers());
    }

    @Test
    public void arbitraryCommittedFlightStateRestoresAndReplaysExactly() {
        DmgObjectFlightGateCone reference = lowByteCommitted();
        DmgObjectFlightGateCone.State snapshot = reference.capture();
        DmgObjectFlightGateCone replay = new DmgObjectFlightGateCone();
        replay.restore(snapshot);

        var high = DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).captureHigh(0x0f);
        assertEquals(reference.step(high), replay.step(high));
        var load = DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withObjEnable(false).onLoadEdge();
        assertEquals(reference.step(load), replay.step(load));
        assertEquals(reference.capture(), replay.capture());

        assertThrows(IllegalArgumentException.class,
                () -> replay.restore(new DmgObjectFlightGateCone.State(
                        false, false, 0x100, false, 0, 0, 0)));
    }

    private static DmgObjectFlightGateCone lowByteCommitted() {
        DmgObjectFlightGateCone cone = new DmgObjectFlightGateCone();
        cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled().withComparator(true));
        var low = cone.step(DmgObjectFlightGateCone.Inputs.idleEnabled()
                .withComparator(true).captureLow(0xf0));
        assertTrue(low.lowByteValid());
        assertFalse(low.matchToken());
        return cone;
    }
}
