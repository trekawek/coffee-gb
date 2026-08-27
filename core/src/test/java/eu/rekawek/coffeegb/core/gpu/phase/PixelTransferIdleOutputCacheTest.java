package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.ColorPalette;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.DmgPixelFifo;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused lifetime tests for PixelTransfer's transient successful idle-output proof. */
public class PixelTransferIdleOutputCacheTest {

    private static final Field IDLE_CACHE = field("performanceIdleOutputKnownTrue");

    @Test
    public void startTickAndSpecializedEnqueuePathsInvalidateTheProof() {
        PixelTransfer visible = create(true, false);
        assertIdleAndCached(visible);

        visible.start();
        assertCache(false, visible);
        assertFalse(visible.isPerformanceNativeCgbMode2IdleOutput());

        PixelTransfer timing = create(true, true);
        assertIdleAndCached(timing);
        timing.start();
        assertCache(false, timing);
        // machineActive is intentionally ignored by the timing skeleton's mode-2 proof.
        assertIdleAndCached(timing);

        timing.tick();
        assertCache(false, timing);
        assertIdleAndCached(timing);

        timing.advanceSteadyBackgroundSpan(12);
        assertCache(false, timing);
        assertFalse("the specialized path enqueued an output-delay entry",
                timing.isPerformanceNativeCgbMode2IdleOutput());

        PixelTransfer output = create(true, false);
        output.start();
        output.finishPerformanceLine();
        assertIdleAndCached(output);
        output.advanceSteadyBackgroundOutputSpan(12);
        assertCache(false, output);
        assertFalse("the specialized output path enqueued an output-delay entry",
                output.isPerformanceNativeCgbMode2IdleOutput());
    }

    @Test
    public void everyDelayedWindowWriteInvalidatesAndRecovers() {
        PixelTransfer transfer = create(true, false);
        assertIdleAndCached(transfer);

        transfer.scheduleWindowDisplayWrite(false, 2);
        assertCache(false, transfer);
        assertFalse(transfer.isPerformanceNativeCgbMode2IdleOutput());
        transfer.cancelDelayedWindowDisplayWrite();
        assertIdleAndCached(transfer);

        transfer.scheduleWindowXWrite(17, 2);
        assertCache(false, transfer);
        assertFalse(transfer.isPerformanceNativeCgbMode2IdleOutput());
        transfer.cancelDelayedWindowXWrite();
        assertIdleAndCached(transfer);

        transfer.scheduleWindowYWrite(23, 2);
        assertCache(false, transfer);
        assertFalse(transfer.isPerformanceNativeCgbMode2IdleOutput());
        for (int i = 0; i < 3; i++) {
            transfer.advanceWindowYDelay();
        }
        assertIdleAndCached(transfer);
    }

    @Test
    public void fifoRuntimeRestoreAndRepresentationRebindInvalidateTheProof() {
        PixelTransfer idleFull = create(false, false);
        DmgPixelFifo.RuntimeState idleFifo = idleFull.captureDmgFifoRuntimeState();

        PixelTransfer queuedFull = create(false, false);
        queuedFull.start();
        queuedFull.finishPerformanceLine();
        queuedFull.advanceSteadyBackgroundOutputSpan(12);
        assertFalse(queuedFull.isPerformanceDmgIdleOutput());
        DmgPixelFifo.RuntimeState queuedFifo = queuedFull.captureDmgFifoRuntimeState();

        PixelTransfer target = create(false, false);
        assertIdleAndCached(target);
        target.restoreDmgFifoRuntimeState(queuedFifo);
        assertCache(false, target);
        assertFalse(target.isPerformanceDmgIdleOutput());
        target.restoreDmgFifoRuntimeState(idleFifo);
        assertCache(false, target);
        assertIdleAndCached(target);

        ComponentState<PixelTransfer> fullState = create(false, false).captureState();
        ComponentState<PixelTransfer> scalarState = create(false, true).captureState();
        PixelTransfer skeleton = create(false, true);
        assertIdleAndCached(skeleton);

        skeleton.restoreState(fullState);
        assertCache(false, skeleton);
        assertFalse(skeleton.usesScalarTimingFifo());
        assertIdleAndCached(skeleton);

        skeleton.restoreState(scalarState);
        assertCache(false, skeleton);
        assertTrue(skeleton.usesScalarTimingFifo());
        assertIdleAndCached(skeleton);
    }

    @Test
    public void portableRestoreDropsRatherThanSerializesTheTransientProof() {
        PixelTransfer source = create(true, false);
        assertIdleAndCached(source);
        ComponentState<PixelTransfer> portable = source.captureState();
        for (RecordComponent component : portable.getClass().getRecordComponents()) {
            assertFalse("cache must not enter portable state",
                    component.getName().equals("performanceIdleOutputKnownTrue"));
        }

        PixelTransfer target = create(true, false);
        assertIdleAndCached(target);
        target.restoreState(portable);
        assertCache(false, target);
        assertIdleAndCached(target);
    }

    @Test
    public void emptyOutputTicksAndTrustedIdleSpansRetainTheProof() {
        PixelTransfer cgb = create(true, false);
        assertIdleAndCached(cgb);
        cgb.outputTick();
        assertCache(true, cgb);
        cgb.advancePerformanceNativeCgbMode2IdleOutputSpanTrusted(37);
        assertCache(true, cgb);
        assertTrue(cgb.isPerformanceNativeCgbMode2IdleOutput());
        assertEquals(38, outputTicks(cgb));

        PixelTransfer dmg = create(false, false);
        assertIdleAndCached(dmg);
        dmg.outputTick();
        assertCache(true, dmg);
        dmg.advancePerformanceDmgIdleOutputSpanTrusted(41);
        assertCache(true, dmg);
        assertTrue(dmg.isPerformanceDmgIdleOutput());
        assertEquals(42, outputTicks(dmg));
    }

    private static PixelTransfer create(boolean gbc, boolean timingSkeleton) {
        SpeedMode speedMode = new SpeedMode(gbc);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(gbc);
        registers.setSpeedMode(speedMode);
        registers.put(GpuRegister.LY, 0);
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(gbc);
        lcdc.set(0x91);
        SpritePosition[] sprites = new SpritePosition[10];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new SpritePosition();
        }
        AddressSpace oam = new Ram(0xfe00, 0xa0);
        return new PixelTransfer(
                new Display(gbc),
                new Ram(0x8000, 0x2000),
                gbc ? new Ram(0x8000, 0x2000) : null,
                oam,
                lcdc,
                registers,
                gbc,
                new ColorPalette(0xff68),
                new ColorPalette(0xff6a),
                sprites,
                null,
                speedMode,
                0,
                timingSkeleton);
    }

    private static void assertIdleAndCached(PixelTransfer transfer) {
        boolean idle = transfer.isPerformanceDmgIdleOutput();
        if (!idle) {
            idle = transfer.isPerformanceNativeCgbMode2IdleOutput();
        }
        assertTrue(idle);
        assertCache(true, transfer);
    }

    private static void assertCache(boolean expected, PixelTransfer transfer) {
        try {
            assertEquals(expected, IDLE_CACHE.getBoolean(transfer));
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Could not inspect idle-output cache", failure);
        }
    }

    private static long outputTicks(PixelTransfer transfer) {
        try {
            Object fifo = field("fifo").get(transfer);
            Field outputTicks = fifo.getClass().getDeclaredField("outputTicks");
            outputTicks.setAccessible(true);
            return outputTicks.getLong(fifo);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect FIFO output clock", failure);
        }
    }

    private static Field field(String name) {
        try {
            Field field = PixelTransfer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
