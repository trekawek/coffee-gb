package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.DmgPixelFifo;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.gpu.TileAttributes;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.DmgLcdOutputSignalCone.OUTSIDE_CGB;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.DmgLcdOutputSignalCone.OUTSIDE_LCD_DISABLE_WITH_TOKENS_IN_FLIGHT;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.DmgLcdOutputSignalCone.OUTSIDE_SUB_DOT_ANALOG_PAD_WAVEFORM;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.DmgLcdOutputSignalCone.RAW_TO_LCD_DOTS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DmgLcdOutputSignalConeTest {

    private static final int IDENTITY = 0xe4;

    private static final int REVERSE = 0x1b;

    @Test
    public void immutableRawTokenTraversesThreeRegistersThenOpensThePanelClock() {
        DmgLcdOutputSignalCone cone = cone(IDENTITY, 0, 0, 1);
        DmgLcdOutputSignalCone.RawPixel raw = raw(2, 0, false, false);

        cone.driveRaw(raw);
        cone.tick();
        tick(cone, RAW_TO_LCD_DOTS - 1);

        assertEquals(RAW_TO_LCD_DOTS, cone.dot());
        assertEquals(0, cone.outputSize());
        assertFalse(cone.panelClockRunning());

        cone.tick();
        assertTrue(cone.panelClockRunning());
        assertTrue(cone.openingTokenPending());
        assertEquals("the opening edge captures but does not yet mux the token",
                0, cone.outputSize());

        cone.tick();
        assertEquals(1, cone.outputSize());
        assertSame("the consumer retains the immutable source token", raw,
                cone.output(0).source());
        assertEquals(4, cone.output(0).dot());
        assertEquals(2, cone.output(0).shade());
    }

    @Test
    public void paletteWriteAtEveryTokenAgeMatchesTheProductionOutputCone() throws Exception {
        // A raw-2 pixel maps to 2 through E4, 1 through 1B, and 3 through their write-dot OR.
        int[] expected = {1, 1, 1, 3, 2};
        for (int writeDot = 0; writeDot <= 4; writeDot++) {
            DmgLcdOutputSignalCone cone = cone(IDENTITY, 0, 0, 1);
            ProductionCone production = new ProductionCone(IDENTITY, 0, 0, 0x91);
            for (int dot = 0; dot <= 4; dot++) {
                if (dot == writeDot) {
                    cone.writeBgp(REVERSE);
                    production.writeBgp(REVERSE);
                    assertEquals("CPU readback is not the panel latch", REVERSE, cone.cpuBgp());
                }
                if (dot == 0) {
                    cone.driveRaw(raw(2, 0, false, false));
                }
                cone.tick();
                production.tick(dot == 0, 2);
            }

            assertEquals("detached write phase " + writeDot,
                    expected[writeDot], cone.output(0).shade());
            assertEquals("production differential write phase " + writeDot,
                    production.frame()[0], cone.output(0).shade());
            production.close();
        }
    }

    @Test
    public void eitherObjectPaletteUsesTheSameWriteLatchWhileItsTokenIsInFlight()
            throws Exception {
        int[] expected = {1, 1, 1, 3, 2};
        for (boolean palette1 : booleans()) {
            for (int writeDot = 0; writeDot <= 4; writeDot++) {
                DmgLcdOutputSignalCone cone = cone(0, IDENTITY, IDENTITY, 2);
                ProductionCone production = new ProductionCone(0, IDENTITY, IDENTITY, 0x92);
                production.overlayObjects(2, 0, palette1);

                for (int dot = 0; dot <= 4; dot++) {
                    if (dot == writeDot) {
                        if (palette1) {
                            cone.writeObp1(REVERSE);
                            production.writeObp1(REVERSE);
                        } else {
                            cone.writeObp0(REVERSE);
                            production.writeObp0(REVERSE);
                        }
                    }
                    if (dot == 0) {
                        cone.driveRaw(raw(0, 2, palette1, false));
                    }
                    cone.tick();
                    production.tick(dot == 0, 0);
                }

                assertEquals("detached OBJ palette=" + palette1 + " phase=" + writeDot,
                        expected[writeDot], cone.output(0).shade());
                assertEquals("production OBJ palette=" + palette1 + " phase=" + writeDot,
                        production.frame()[0], cone.output(0).shade());
                production.close();
            }
        }
    }

    @Test
    public void panelOpeningEdgeCapturesPalettesButLeavesLcdcLiveForTheNextDot()
            throws Exception {
        DmgLcdOutputSignalCone cone = cone(IDENTITY, 0, 0, 0);
        ProductionCone production = new ProductionCone(IDENTITY, 0, 0, 0x90);
        DmgLcdOutputSignalCone.RawPixel first = raw(2, 0, false, false);
        DmgLcdOutputSignalCone.RawPixel second = raw(2, 0, false, false);

        for (int dot = 0; dot <= 4; dot++) {
            if (dot == 4) {
                // The first token captured E4 on dot 3. On dot 4 LCDC.0's set path opens
                // the BG mux, while the simultaneous BGP write is visible only to the
                // ordinary second token as E4|1B.
                cone.writeLcdcEnables(1);
                cone.writeBgp(REVERSE);
                production.writeLcdc(0x91, false);
                production.writeBgp(REVERSE);
            }
            if (dot == 0) {
                cone.driveRaw(first);
            } else if (dot == 1) {
                cone.driveRaw(second);
            }
            cone.tick();
            production.tick(dot < 2, 2);
        }

        assertEquals(2, cone.outputSize());
        assertEquals("first: captured E4 palette, live rising LCDC.0", 2,
                cone.output(0).shade());
        assertEquals("second: live E4|1B palette on the same output dot", 3,
                cone.output(1).shade());
        assertEquals(cone.output(0).dot(), cone.output(1).dot());
        assertEquals(production.frame()[0], cone.output(0).shade());
        assertEquals(production.frame()[1], cone.output(1).shade());
        production.close();
    }

    @Test
    public void objectEnableClearIsAnIndependentControlWireNotATokenRepair() throws Exception {
        for (boolean clear : new boolean[] {false, true}) {
            DmgLcdOutputSignalCone cone = cone(IDENTITY, REVERSE, 0, 3);
            ProductionCone production = new ProductionCone(IDENTITY, REVERSE, 0, 0x93);
            DmgLcdOutputSignalCone.RawPixel raw = raw(1, 3, false, false);
            production.overlayObjects(3, 3, false);

            for (int dot = 0; dot <= 4; dot++) {
                if (dot == 4) {
                    cone.writeLcdcEnables(1);
                    production.writeLcdc(0x91, clear);
                    if (clear) {
                        cone.driveObjectEnableClear();
                    }
                }
                if (dot < 2) {
                    cone.driveRaw(raw);
                }
                cone.tick();
                production.tick(dot < 2, 1);
            }

            int expectedShade = clear ? 1 : 0;
            assertEquals(expectedShade, cone.output(0).shade());
            assertEquals(expectedShade, cone.output(1).shade());
            assertEquals(production.frame()[0], cone.output(0).shade());
            assertEquals(production.frame()[1], cone.output(1).shade());
            assertSame(raw, cone.output(0).source());
            assertSame(raw, cone.output(1).source());
            production.close();
        }
    }

    @Test
    public void backgroundEnableSetBeforeResetEnvelopeActsOnlyAtTheConsumer() {
        DmgLcdOutputSignalCone cone = cone(IDENTITY, 0, 0, 1);
        DmgLcdOutputSignalCone.RawPixel raw = raw(3, 0, false, false);
        driveTwo(cone, raw);
        tick(cone, 2); // dot 3 captures the opening token

        cone.writeLcdcEnables(0);
        assertTrue("falling LCDC.0 remains high for the write-dot envelope",
                cone.panelBackgroundEnable());
        cone.tick();
        assertEquals(3, cone.output(0).raw());
        assertEquals(3, cone.output(1).raw());

        cone.driveRaw(raw);
        tick(cone, 4);
        assertFalse(cone.panelBackgroundEnable());
        assertEquals("the later token is forced to BG index zero", 0,
                cone.output(2).raw());
    }

    @Test
    public void paletteLatchEnvelopeIsOldOrNewForEveryBytePair() {
        for (int oldValue = 0; oldValue <= 0xff; oldValue++) {
            for (int newValue = 0; newValue <= 0xff; newValue++) {
                DmgLcdOutputSignalCone cone = cone(oldValue, 0, 0, 1);
                cone.writeBgp(newValue);
                assertEquals(oldValue | newValue, cone.panelBgp());
                assertEquals(newValue, cone.cpuBgp());
                cone.tick();
                assertEquals(newValue, cone.panelBgp());
            }
        }
    }

    @Test
    public void backgroundObjectPriorityAndPaletteMuxMatchesItsTruthTable() {
        int bgp = 0xe4;
        int obp0 = 0x1b;
        int obp1 = 0xb1;
        for (int background = 0; background < 4; background++) {
            for (int object = 0; object < 4; object++) {
                for (boolean bgEnable : booleans()) {
                    for (boolean objEnable : booleans()) {
                        for (boolean behind : booleans()) {
                            for (boolean palette1 : booleans()) {
                                DmgLcdOutputSignalCone cone = cone(
                                        bgp, obp0, obp1,
                                        (bgEnable ? 1 : 0) | (objEnable ? 2 : 0));
                                DmgLcdOutputSignalCone.RawPixel token =
                                        raw(background, object, palette1, behind);
                                cone.driveRaw(token);
                                tick(cone, 5);

                                int effectiveBg = bgEnable ? background : 0;
                                boolean drawObject = object != 0 && objEnable
                                        && !(behind && effectiveBg != 0);
                                int expectedRaw = drawObject ? object : effectiveBg;
                                int expectedPalette = drawObject
                                        ? (palette1 ? obp1 : obp0) : bgp;
                                int expectedShade = (expectedPalette >>> (expectedRaw * 2)) & 3;
                                assertEquals(expectedRaw, cone.output(0).raw());
                                assertEquals(expectedPalette, cone.output(0).palette());
                                assertEquals(expectedShade, cone.output(0).shade());
                                assertEquals(drawObject, cone.output(0).objectSelected());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void forwardFetcherAndConsumerConeComposeWithoutARepairCallback() {
        ForwardDmgPixelPipeline fetch = new ForwardDmgPixelPipeline();
        DmgLcdOutputSignalCone output = cone(IDENTITY, REVERSE, 0, 3);
        fetch.seedRawFifo(4, 2);

        for (int i = 0; i < 8; i++) {
            int pops = fetch.fifoPopX();
            fetch.tick();
            if (fetch.fifoPopX() != pops) {
                int packed = fetch.lastPoppedRaw();
                output.driveRaw(raw(packed & 3, (packed >>> 2) & 3, false, false));
            }
            output.tick();
        }

        assertEquals(4, fetch.lcdX());
        assertEquals(4, output.outputSize());
        for (int x = 0; x < 4; x++) {
            DmgLcdOutputSignalCone.RawPixel token = output.output(x).source();
            assertEquals(fetch.lcdRaw(x), token.background() | (token.object() << 2));
        }
        assertEquals("the interface opening phase affects only the panel consumer", 4,
                output.output(0).dot());
    }

    @Test
    public void unknownPanelResetAndImpossibleDoubleDrivesAreExecutableFalsifiers() {
        DmgLcdOutputSignalCone empty = cone(0, 0, 0, 0);
        empty.disableLcd();

        DmgLcdOutputSignalCone cone = cone(0, 0, 0, 0);
        cone.driveRaw(raw(0, 0, false, false));
        assertThrows(UnsupportedOperationException.class, cone::disableLcd);
        assertThrows(IllegalStateException.class,
                () -> cone.driveRaw(raw(1, 0, false, false)));

        cone.writeBgp(0x12);
        assertThrows(IllegalStateException.class, () -> cone.writeBgp(0x34));
        assertEquals(OUTSIDE_CGB
                        | OUTSIDE_LCD_DISABLE_WITH_TOKENS_IN_FLIGHT
                        | OUTSIDE_SUB_DOT_ANALOG_PAD_WAVEFORM,
                DmgLcdOutputSignalCone.incompleteBehaviorMask());
    }

    private static DmgLcdOutputSignalCone cone(int bgp, int obp0, int obp1, int lcdc) {
        return new DmgLcdOutputSignalCone(bgp, obp0, obp1, lcdc);
    }

    private static DmgLcdOutputSignalCone.RawPixel raw(
            int background, int object, boolean palette1, boolean behind) {
        return new DmgLcdOutputSignalCone.RawPixel(background, object, palette1, behind);
    }

    private static void driveTwo(DmgLcdOutputSignalCone cone,
                                 DmgLcdOutputSignalCone.RawPixel raw) {
        cone.driveRaw(raw);
        cone.tick();
        cone.driveRaw(raw);
        cone.tick();
    }

    private static void tick(DmgLcdOutputSignalCone cone, int dots) {
        for (int i = 0; i < dots; i++) {
            cone.tick();
        }
    }

    private static boolean[] booleans() {
        return new boolean[] {false, true};
    }

    /** Current production FIFO, used only as a differential oracle for the local cone. */
    private static final class ProductionCone implements AutoCloseable {

        private static final Method REGISTERS_TICK = method(GpuRegisterValues.class,
                "tickConflicts");

        private static final Method LCDC_TICK = method(Lcdc.class, "tickConflicts");

        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);

        private final AtomicReference<int[]> frame = new AtomicReference<>();

        private final Display display = new Display(false);

        private final GpuRegisterValues registers = new GpuRegisterValues();

        private final Lcdc lcdc = new Lcdc();

        private final DmgPixelFifo fifo;

        private ProductionCone(int bgp, int obp0, int obp1, int lcdcValue) {
            display.init(eventBus);
            eventBus.register(e -> frame.set(e.pixels().clone()),
                    Display.DmgFrameReadyEvent.class);
            registers.setGbc(false);
            registers.put(GpuRegister.BGP, bgp);
            registers.put(GpuRegister.OBP0, obp0);
            registers.put(GpuRegister.OBP1, obp1);
            // Establish fixture state without creating a DMG write-conflict pulse.
            lcdc.setGbc(true);
            lcdc.set(lcdcValue);
            lcdc.setGbc(false);
            fifo = new DmgPixelFifo(display, lcdc, registers, null);
            fifo.startLine();
        }

        private void writeBgp(int value) {
            registers.setByte(GpuRegister.BGP.getAddress(), value);
        }

        private void writeObp0(int value) {
            registers.setByte(GpuRegister.OBP0.getAddress(), value);
        }

        private void writeObp1(int value) {
            registers.setByte(GpuRegister.OBP1.getAddress(), value);
        }

        private void writeLcdc(int value, boolean objectClear) {
            lcdc.set(value, objectClear);
        }

        private void overlayObjects(int first, int second, boolean palette1) {
            fifo.setOverlay(new int[] {first, second, 0, 0, 0, 0, 0, 0},
                    0, TileAttributes.valueOf(palette1 ? 0x10 : 0), 0);
        }

        private void tick(boolean pop, int background) throws Exception {
            REGISTERS_TICK.invoke(registers);
            LCDC_TICK.invoke(lcdc);
            fifo.outputTick();
            if (pop) {
                fifo.enqueuePixel(background);
                fifo.putPixelToScreen();
            }
        }

        private int[] frame() {
            display.frameIsReady();
            return frame.get();
        }

        @Override
        public void close() {
            eventBus.close();
        }

        private static Method method(Class<?> type, String name) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
