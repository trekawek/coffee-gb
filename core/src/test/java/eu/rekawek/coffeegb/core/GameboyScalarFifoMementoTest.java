package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Ordinary Gameboy memento coverage for the production scalar timing FIFO route. */
public class GameboyScalarFifoMementoTest {

    @Test
    public void captureRestoreContinuesDmgNativeCgbCgb0AndCompat() throws IOException {
        ProfileCase[] profiles = {
                new ProfileCase("DMG", HardwareProfileRegistry.DMG, false),
                new ProfileCase("MGB", HardwareProfileRegistry.MGB, false),
                new ProfileCase("SGB", HardwareProfileRegistry.SGB, false),
                new ProfileCase("SGB2", HardwareProfileRegistry.SGB2, false),
                new ProfileCase("CGB", HardwareProfileRegistry.CGB, true),
                new ProfileCase("CGB0", HardwareProfileRegistry.CGB0, true),
                new ProfileCase("CGB-compat", HardwareProfileRegistry.CGB, false),
        };

        for (ProfileCase profile : profiles) {
            try (Gameboy source = build(profile); Gameboy reference = build(profile)) {
                for (int i = 0; i < 320; i++) {
                    assertEquals("initial frame " + profile.name, reference.tick(), source.tick());
                }
                ComponentState<Gameboy> saved = source.captureState();

                for (int i = 0; i < 137; i++) {
                    source.tick();
                }
                source.restoreState(saved);

                for (int i = 0; i < 160; i++) {
                    assertEquivalent(reference, source, profile.name + " tick " + i);
                    assertEquals(
                            "continuation frame " + profile.name + " tick " + i,
                            reference.tick(),
                            source.tick());
                }
            }
        }
    }

    @Test
    public void gpuPreflightRejectsWrongTimingAndOutputStatesAtomically() throws IOException {
        try (Gameboy target = build(new ProfileCase("DMG", HardwareProfileRegistry.DMG, false));
                Gameboy cgb = build(new ProfileCase("CGB", HardwareProfileRegistry.CGB, true))) {
            ComponentState<eu.rekawek.coffeegb.core.gpu.Gpu> before =
                    target.getGpu().captureState();
            ComponentState<?> targetPhase = recordComponent(before, "pixelTransferPhaseMemento");
            ComponentState<?> cgbPhase = recordComponent(cgb.getGpu().captureState(),
                    "pixelTransferPhaseMemento");
            ComponentState<?> wrongTiming = replaceRecordComponent(
                    targetPhase, "fifoMemento", recordComponent(cgbPhase, "fifoMemento"));
            ComponentState<eu.rekawek.coffeegb.core.gpu.Gpu> badTiming =
                    replaceRecordComponent(before, "pixelTransferPhaseMemento", wrongTiming);

            int line = target.getGpu().getLine();
            int ticksInLine = target.getGpu().getTicksInLine();
            expectIllegalArgument(() -> target.getGpu().restoreState(badTiming));
            assertTrue(pixelTransfer(target, "pixelTransferPhase").usesScalarTimingFifo());
            assertFalse(pixelTransfer(target, "pixelMachine").usesScalarTimingFifo());
            assertEquals(line, target.getGpu().getLine());
            assertEquals(ticksInLine, target.getGpu().getTicksInLine());

            ComponentState<?> targetOutput = recordComponent(before, "pixelMachineMemento");
            ComponentState<?> targetScalarFifo = recordComponent(targetPhase, "fifoMemento");
            ComponentState<?> targetFullFifo = recordComponent(targetOutput, "fifoMemento");
            ComponentState<?> fullTiming = replaceRecordComponent(
                    targetPhase, "fifoMemento", targetFullFifo);
            ComponentState<?> scalarOutput = replaceRecordComponent(
                    targetOutput, "fifoMemento", targetScalarFifo);
            ComponentState<eu.rekawek.coffeegb.core.gpu.Gpu> badOutput =
                    replaceRecordComponent(
                            replaceRecordComponent(before, "pixelTransferPhaseMemento", fullTiming),
                            "pixelMachineMemento", scalarOutput);

            expectIllegalArgument(() -> target.getGpu().restoreState(badOutput));
            assertTrue(pixelTransfer(target, "pixelTransferPhase").usesScalarTimingFifo());
            assertFalse(pixelTransfer(target, "pixelMachine").usesScalarTimingFifo());
            assertEquals(line, target.getGpu().getLine());
            assertEquals(ticksInLine, target.getGpu().getTicksInLine());
        }
    }

    @Test
    public void gpuAcceptsLegacyFullTimingStateWithMissingOutputMachine() throws IOException {
        try (Gameboy target = build(new ProfileCase("DMG", HardwareProfileRegistry.DMG, false))) {
            ComponentState<eu.rekawek.coffeegb.core.gpu.Gpu> before =
                    target.getGpu().captureState();
            ComponentState<?> phase = recordComponent(before, "pixelTransferPhaseMemento");
            ComponentState<?> output = recordComponent(before, "pixelMachineMemento");
            ComponentState<?> fullFifo = recordComponent(output, "fifoMemento");
            ComponentState<?> fullTiming = replaceRecordComponent(
                    phase, "fifoMemento", fullFifo);
            ComponentState<eu.rekawek.coffeegb.core.gpu.Gpu> legacy = replaceRecordComponent(
                    replaceRecordComponent(before, "pixelTransferPhaseMemento", fullTiming),
                    "pixelMachineMemento", null);

            target.getGpu().restoreState(legacy);
            assertFalse(pixelTransfer(target, "pixelTransferPhase").usesScalarTimingFifo());
            assertFalse(pixelTransfer(target, "pixelMachine").usesScalarTimingFifo());
        }
    }

    private static Gameboy build(ProfileCase profile) throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x143] = (byte) (profile.colorCartridge ? 0x80 : 0x00);
        bytes[0x147] = 0;
        return new Gameboy.GameboyConfiguration(new Rom(bytes))
                .setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static PixelTransfer pixelTransfer(Gameboy gameboy, String fieldName) {
        try {
            java.lang.reflect.Field field = gameboy.getGpu().getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (PixelTransfer) field.get(gameboy.getGpu());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect GPU " + fieldName, failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ComponentState<T> replaceRecordComponent(
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
            return (ComponentState<T>) constructor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not build altered GPU state", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ComponentState<T> recordComponent(
            ComponentState<?> state, String name) {
        try {
            for (java.lang.reflect.RecordComponent component : state.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    java.lang.reflect.Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return (ComponentState<T>) accessor.invoke(state);
                }
            }
            throw new AssertionError("Record has no " + name + " component");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect GPU state " + name, failure);
        }
    }

    private static void expectIllegalArgument(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected role/type rejection before any live GPU mutation.
        }
    }

    private static void assertEquivalent(Gameboy expected, Gameboy actual, String context) {
        assertEquals(context + " LY", expected.getGpu().getLine(), actual.getGpu().getLine());
        assertEquals(context + " line ticks", expected.getGpu().getTicksInLine(), actual.getGpu().getTicksInLine());
        assertEquals(context + " mode", expected.getGpu().getMode(), actual.getGpu().getMode());
        assertEquals(context + " PC", expected.getCpu().getRegisters().getPC(), actual.getCpu().getRegisters().getPC());
        assertEquals(context + " speed", expected.getSpeedMode().getSpeedMode(), actual.getSpeedMode().getSpeedMode());
    }

    private record ProfileCase(String name, HardwareProfile hardware, boolean colorCartridge) {
    }
}
