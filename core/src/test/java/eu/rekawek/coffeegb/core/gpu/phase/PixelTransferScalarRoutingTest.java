package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.ColorPalette;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Production routing and runtime-restore guards for the E2b scalar skeleton. */
public class PixelTransferScalarRoutingTest {

    @Test
    public void onlyExplicitTimingSkeletonCanBindScalarStorage() {
        PixelTransfer visible = create(false, false);
        PixelTransfer suppressedVisible = create(false, false);
        suppressedVisible.setRenderOutput(false);
        PixelTransfer skeleton = create(false, true);

        assertFalse(visible.usesScalarTimingFifo());
        assertFalse(suppressedVisible.usesScalarTimingFifo());
        assertTrue(skeleton.isTimingSkeleton());
        assertTrue(skeleton.usesScalarTimingFifo());

        // A live role transition would silently discard scalar queue/delay state, so it is
        // rejected. The ordinary visible machine remains full-FIFO in both output modes.
        try {
            skeleton.setRenderOutput(true);
            fail("timing skeleton must not be promoted to a visible/output role");
        } catch (IllegalStateException expected) {
            // Explicit guard.
        }
        assertTrue(skeleton.usesScalarTimingFifo());
    }

    @Test
    public void scalarAndSuppressedFullFifoHaveIdenticalTimingTrace() {
        PixelTransfer scalar = create(false, true);
        PixelTransfer full = create(false, false);
        full.setRenderOutput(false);
        scalar.start();
        full.start();

        for (int tick = 0; tick < 500; tick++) {
            assertEquals("position at tick " + tick, full.getPosition(), scalar.getPosition());
            assertEquals("object penalty at tick " + tick,
                    full.getObjectTimingPenalty(), scalar.getObjectTimingPenalty());
            boolean fullActive = tick(full);
            boolean scalarActive = tick(scalar);
            assertEquals("active at tick " + tick, fullActive, scalarActive);
            if (!fullActive) {
                return;
            }
        }
        throw new AssertionError("pixel transfer did not finish");
    }

    @Test
    public void cgbNativeAndCompatUseScalarTimingStorageWithoutChangingRole() {
        for (boolean dmgCompat : new boolean[] {false, true}) {
            SpeedMode speedMode = new SpeedMode(true);
            speedMode.setDmgCompat(dmgCompat);
            PixelTransfer skeleton = create(true, true, speedMode);
            PixelTransfer output = create(true, false, speedMode);

            skeleton.prepareForTick(speedMode.getSpeedMode(), dmgCompat);
            output.prepareForTick(speedMode.getSpeedMode(), dmgCompat);
            assertTrue("CGB timing skeleton compat=" + dmgCompat,
                    skeleton.usesScalarTimingFifo());
            assertFalse("CGB output compat=" + dmgCompat, output.usesScalarTimingFifo());
        }
    }

    @Test
    public void cgbNativeAndCompatScalarTraceMatchesSuppressedFullFifo() {
        for (boolean dmgCompat : new boolean[] {false, true}) {
            SpeedMode speedMode = new SpeedMode(true);
            speedMode.setDmgCompat(dmgCompat);
            PixelTransfer scalar = create(true, true, speedMode);
            PixelTransfer full = create(true, false, speedMode);
            full.setRenderOutput(false);
            scalar.prepareForTick(speedMode.getSpeedMode(), dmgCompat);
            full.prepareForTick(speedMode.getSpeedMode(), dmgCompat);
            scalar.start();
            full.start();

            for (int tick = 0; tick < 500; tick++) {
                assertEquals("CGB position compat=" + dmgCompat + " tick=" + tick,
                        full.getPosition(), scalar.getPosition());
                assertEquals("CGB penalty compat=" + dmgCompat + " tick=" + tick,
                        full.getObjectTimingPenalty(), scalar.getObjectTimingPenalty());
                boolean fullActive = tick(full);
                boolean scalarActive = tick(scalar);
                assertEquals("CGB active compat=" + dmgCompat + " tick=" + tick,
                        fullActive, scalarActive);
                if (!fullActive) {
                    break;
                }
            }
        }
    }

    @Test
    public void scalarMementoRestoresEveryQueueAndDelayField() {
        PixelTransfer source = create(false, true);
        source.start();
        for (int i = 0; i < 24; i++) {
            tick(source);
        }
        ComponentState<PixelTransfer> structural = source.captureState();

        int expectedPosition = source.getPosition();
        int expectedPenalty = source.getObjectTimingPenalty();
        for (int i = 0; i < 20; i++) {
            tick(source);
        }

        source.restoreState(structural);
        assertTrue(source.usesScalarTimingFifo());
        assertEquals(expectedPosition, source.getPosition());
        assertEquals(expectedPenalty, source.getObjectTimingPenalty());

        PixelTransfer fresh = create(false, true);
        fresh.start();
        for (int i = 0; i < 24; i++) {
            tick(fresh);
        }
        for (int i = 0; i < 40; i++) {
            assertEquals("continuation position at " + i, fresh.getPosition(), source.getPosition());
            assertEquals("continuation penalty at " + i,
                    fresh.getObjectTimingPenalty(), source.getObjectTimingPenalty());
            assertEquals(tick(fresh), tick(source));
        }
    }

    @Test
    public void fullFifoStateRestoresIntoSkeletonByDeoptimizing() {
        PixelTransfer full = create(false, false);
        full.setRenderOutput(false);
        full.start();
        for (int i = 0; i < 19; i++) {
            tick(full);
        }
        ComponentState<PixelTransfer> fullState = full.captureState();

        PixelTransfer skeleton = create(false, true);
        skeleton.start();
        skeleton.restoreState(fullState);
        assertFalse("full/legacy state must force the full FIFO", skeleton.usesScalarTimingFifo());
        assertEquals(full.getPosition(), skeleton.getPosition());
        assertEquals(full.getObjectTimingPenalty(), skeleton.getObjectTimingPenalty());
        for (int i = 0; i < 32; i++) {
            assertEquals(tick(full), tick(skeleton));
            assertEquals(full.getPosition(), skeleton.getPosition());
        }
    }

    @Test
    public void deoptimizedSkeletonDoesNotRebindOnRepeatedSuppressionRequest() {
        PixelTransfer full = create(false, false);
        full.setRenderOutput(false);
        full.start();
        for (int i = 0; i < 19; i++) {
            tick(full);
        }

        PixelTransfer skeleton = create(false, true);
        skeleton.start();
        skeleton.restoreState(full.captureState());
        assertFalse(skeleton.usesScalarTimingFifo());

        // A host may repeat the output-suppression request after a fail-closed legacy restore.
        // It must not switch back to an empty scalar FIFO and discard the recovered full state.
        skeleton.setRenderOutput(false);
        assertFalse(skeleton.usesScalarTimingFifo());
        for (int i = 0; i < 32; i++) {
            assertEquals(tick(full), tick(skeleton));
            assertEquals(full.getPosition(), skeleton.getPosition());
        }
        try {
            skeleton.setRenderOutput(true);
            fail("deoptimized timing skeleton must remain non-output");
        } catch (IllegalStateException expected) {
            // Explicit role guard.
        }
    }

    @Test
    public void missingScalarMementoFailsClosedToFullFifo() {
        PixelTransfer skeleton = create(false, true);
        skeleton.start();
        for (int i = 0; i < 12; i++) {
            tick(skeleton);
        }
        ComponentState<PixelTransfer> stateWithoutFifo = withoutFifoMemento(skeleton.captureState());
        tick(skeleton);

        skeleton.restoreState(stateWithoutFifo);
        assertFalse("missing scalar state must deoptimize", skeleton.usesScalarTimingFifo());
    }

    @Test
    public void scalarStateCannotBeInjectedIntoVisibleOrWrongFamilyMachine() {
        PixelTransfer scalarDmg = create(false, true);
        scalarDmg.start();
        for (int i = 0; i < 12; i++) {
            tick(scalarDmg);
        }
        ComponentState<?> dmgFifo = fifoMemento(scalarDmg.captureState());

        PixelTransfer visibleDmg = create(false, false);
        visibleDmg.start();
        for (int i = 0; i < 8; i++) {
            tick(visibleDmg);
        }
        int visiblePosition = visibleDmg.getPosition();
        int visiblePenalty = visibleDmg.getObjectTimingPenalty();
        expectIllegalArgument(() -> visibleDmg.restoreState(
                withFifoMemento(visibleDmg.captureState(), dmgFifo)));
        assertFalse(visibleDmg.usesScalarTimingFifo());
        assertEquals(visiblePosition, visibleDmg.getPosition());
        assertEquals(visiblePenalty, visibleDmg.getObjectTimingPenalty());

        PixelTransfer cgbSkeleton = create(true, true);
        cgbSkeleton.start();
        for (int i = 0; i < 8; i++) {
            tick(cgbSkeleton);
        }
        int cgbPosition = cgbSkeleton.getPosition();
        expectIllegalArgument(() -> cgbSkeleton.restoreState(
                withFifoMemento(cgbSkeleton.captureState(), dmgFifo)));
        assertTrue(cgbSkeleton.usesScalarTimingFifo());
        assertEquals(cgbPosition, cgbSkeleton.getPosition());
    }

    @Test
    public void wrongFamilyFullStateIsRejectedBeforeScalarDeoptimization() {
        PixelTransfer dmgFull = create(false, false);
        dmgFull.setRenderOutput(false);
        dmgFull.start();
        for (int i = 0; i < 12; i++) {
            tick(dmgFull);
        }
        PixelTransfer cgbFull = create(true, false);
        cgbFull.setRenderOutput(false);
        cgbFull.start();
        for (int i = 0; i < 12; i++) {
            tick(cgbFull);
        }

        PixelTransfer dmgSkeleton = create(false, true);
        dmgSkeleton.start();
        int dmgPosition = dmgSkeleton.getPosition();
        expectIllegalArgument(() -> dmgSkeleton.restoreState(
                withFifoMemento(dmgSkeleton.captureState(), fifoMemento(cgbFull.captureState()))));
        assertTrue(dmgSkeleton.usesScalarTimingFifo());
        assertEquals(dmgPosition, dmgSkeleton.getPosition());

        PixelTransfer cgbSkeleton = create(true, true);
        cgbSkeleton.start();
        int cgbPosition = cgbSkeleton.getPosition();
        expectIllegalArgument(() -> cgbSkeleton.restoreState(
                withFifoMemento(cgbSkeleton.captureState(), fifoMemento(dmgFull.captureState()))));
        assertTrue(cgbSkeleton.usesScalarTimingFifo());
        assertEquals(cgbPosition, cgbSkeleton.getPosition());
    }

    @Test
    public void malformedFullQueueShapesAreRejectedWithoutDeoptimization() {
        for (boolean gbc : new boolean[] {false, true}) {
            PixelTransfer full = create(gbc, false);
            full.setRenderOutput(false);
            full.start();
            for (int i = 0; i < 12; i++) {
                tick(full);
            }
            ComponentState<?> validFifo = fifoMemento(full.captureState());
            ComponentState<?> validPixels = (ComponentState<?>) recordComponent(validFifo, "pixels");
            for (int length : new int[] {15, 17}) {
                ComponentState<?> malformedPixels = replaceRecordComponent(
                        validPixels, "array", new int[length]);
                ComponentState<?> malformedFifo = replaceRecordComponent(
                        validFifo, "pixels", malformedPixels);
                PixelTransfer skeleton = create(gbc, true);
                skeleton.start();
                int position = skeleton.getPosition();
                expectIllegalArgument(() -> skeleton.restoreState(
                        withFifoMemento(skeleton.captureState(), malformedFifo)));
                assertTrue(skeleton.usesScalarTimingFifo());
                assertEquals(position, skeleton.getPosition());
            }
        }
    }

    @Test
    public void oversizedFullDelayRingIsRejectedBeforeScalarDeoptimization() {
        for (boolean gbc : new boolean[] {false, true}) {
            PixelTransfer full = create(gbc, false);
            full.start();
            ComponentState<?> validFifo = fifoMemento(full.captureState());
            for (int delaySize : new int[] {9, 160}) {
                ComponentState<?> malformedFifo = replaceRecordComponent(
                        validFifo, "delaySize", delaySize);
                PixelTransfer skeleton = create(gbc, true);
                skeleton.start();
                int position = skeleton.getPosition();
                expectIllegalArgument(() -> skeleton.restoreState(
                        withFifoMemento(skeleton.captureState(), malformedFifo)));
                assertTrue(skeleton.usesScalarTimingFifo());
                assertEquals(position, skeleton.getPosition());
            }
        }
    }

    @Test
    public void invalidMatchingScalarStateIsRejectedBeforeRebindingOrMutation() {
        PixelTransfer skeleton = create(false, true);
        skeleton.start();
        for (int i = 0; i < 12; i++) {
            tick(skeleton);
        }
        ComponentState<?> validFifo = fifoMemento(skeleton.captureState());
        ComponentState<?> invalidFifo = replaceRecordComponent(validFifo, "delayStamp", new long[7]);
        int position = skeleton.getPosition();
        int penalty = skeleton.getObjectTimingPenalty();

        expectIllegalArgument(() -> skeleton.restoreState(
                withFifoMemento(skeleton.captureState(), invalidFifo)));
        assertTrue(skeleton.usesScalarTimingFifo());
        assertEquals(position, skeleton.getPosition());
        assertEquals(penalty, skeleton.getObjectTimingPenalty());
    }

    @SuppressWarnings("unchecked")
    private static ComponentState<PixelTransfer> withoutFifoMemento(
            ComponentState<PixelTransfer> state) {
        return withFifoMemento(state, null);
    }

    @SuppressWarnings("unchecked")
    private static ComponentState<PixelTransfer> withFifoMemento(
            ComponentState<PixelTransfer> state, ComponentState<?> fifoMemento) {
        try {
            Class<?> type = state.getClass();
            java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                java.lang.reflect.RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                values[i] = component.getName().equals("fifoMemento")
                        ? fifoMemento : accessor.invoke(state);
            }
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return (ComponentState<PixelTransfer>) constructor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not build a legacy PixelTransfer state", failure);
        }
    }

    private static ComponentState<?> fifoMemento(ComponentState<PixelTransfer> state) {
        try {
            for (java.lang.reflect.RecordComponent component : state.getClass().getRecordComponents()) {
                if (component.getName().equals("fifoMemento")) {
                    return (ComponentState<?>) component.getAccessor().invoke(state);
                }
            }
            throw new AssertionError("PixelTransfer state has no fifoMemento component");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect PixelTransfer fifo state", failure);
        }
    }

    private static ComponentState<?> replaceRecordComponent(
            ComponentState<?> state, String name, Object replacement) {
        try {
            Class<?> type = state.getClass();
            java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                java.lang.reflect.RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                values[i] = component.getName().equals(name)
                        ? replacement : accessor.invoke(state);
            }
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return (ComponentState<?>) constructor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not build malformed scalar FIFO state", failure);
        }
    }

    private static Object recordComponent(Object state, String name) {
        try {
            for (java.lang.reflect.RecordComponent component : state.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    java.lang.reflect.Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(state);
                }
            }
            throw new AssertionError("Record has no " + name + " component");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect record component " + name, failure);
        }
    }

    private static void expectIllegalArgument(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected role/type/shape rejection.
        }
    }

    private static PixelTransfer create(boolean gbc, boolean timingSkeleton) {
        return create(gbc, timingSkeleton, new SpeedMode(gbc));
    }

    private static PixelTransfer create(boolean gbc, boolean timingSkeleton, SpeedMode speedMode) {
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(gbc);
        registers.setSpeedMode(speedMode);
        registers.put(GpuRegister.LY, 0);
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(gbc);
        lcdc.set(0x93);
        SpritePosition[] sprites = new SpritePosition[10];
        AddressSpace oam = new Ram(0xfe00, 0xa0);
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new SpritePosition();
            if (i == 0) {
                sprites[i].enable(24, 16, 0xfe00);
            }
        }
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

    private static boolean tick(PixelTransfer transfer) {
        // GPU.tick() advances the output stage before the mode-3 machine.  Keep that boundary in
        // this direct phase test so the finite eight-entry delay ring is exercised legally.
        transfer.outputTick();
        return transfer.tick();
    }
}
