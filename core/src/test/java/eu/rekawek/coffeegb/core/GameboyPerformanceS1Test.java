package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused routing coverage for the native-CGB scalar STAT/PPU prologue. */
public final class GameboyPerformanceS1Test {

    @Test
    public void nativeOwnerMatchesGenericScalarTicksAndWarmResetDropsOwner() throws Exception {
        try (Gameboy generic = nativeSession(); Gameboy specialized = nativeSession()) {
            generic.runTicks(160_000);
            specialized.runTicks(160_000);
            assertTrue(generic.getSpeedMode().getSpeedMode() == 2);
            assertTrue(specialized.getSpeedMode().getSpeedMode() == 2);
            assertDeepStateEquals("initial native state",
                    generic.captureStateWithoutTimeSource(),
                    specialized.captureStateWithoutTimeSource());

            for (int i = 0; i < 96; i++) {
                generic.tick();
                setNativeOwner(specialized, true);
                specialized.tick();
                assertDeepStateEquals("native scalar dot " + i,
                        generic.captureStateWithoutTimeSource(),
                        specialized.captureStateWithoutTimeSource());
            }

            generic.requestWarmReset(false);
            specialized.requestWarmReset(false);
            generic.tick();
            setNativeOwner(specialized, true);
            specialized.tick();
            assertFalse(nativeOwner(specialized));
            assertDeepStateEquals("warm reset generic handoff",
                    generic.captureStateWithoutTimeSource(),
                    specialized.captureStateWithoutTimeSource());
        }
    }

    private static Gameboy nativeSession() throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static void setNativeOwner(Gameboy gameboy, boolean value) throws Exception {
        Field field = Gameboy.class.getDeclaredField("nativeCgbScalarOwner");
        field.setAccessible(true);
        field.setBoolean(gameboy, value);
    }

    private static boolean nativeOwner(Gameboy gameboy) throws Exception {
        Field field = Gameboy.class.getDeclaredField("nativeCgbScalarOwner");
        field.setAccessible(true);
        return field.getBoolean(gameboy);
    }

    private static byte[] doubleSpeedLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e;
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0;
        image[0x103] = 0x4d;
        image[0x104] = 0x10;
        image[0x105] = 0x00;
        image[0x106] = (byte) 0xc3;
        image[0x107] = 0x06;
        image[0x108] = 0x01;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static void assertDeepStateEquals(String path, Object expected, Object actual)
            throws Exception {
        if (expected == null || actual == null) {
            if (expected != actual) {
                throw new AssertionError(path + " expected=" + expected + " actual=" + actual);
            }
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            throw new AssertionError(path + " type expected=" + expected.getClass()
                    + " actual=" + actual.getClass());
        }
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            if (length != Array.getLength(actual)) {
                throw new AssertionError(path + " array length");
            }
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']', Array.get(expected, i),
                        Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof java.util.List<?> expectedList) {
            java.util.List<?> actualList = (java.util.List<?>) actual;
            if (expectedList.size() != actualList.size()) {
                throw new AssertionError(path + " list size");
            }
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']', expectedList.get(i),
                        actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            if (!expected.equals(actual)) {
                throw new AssertionError(path + " expected=" + expected + " actual=" + actual);
            }
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            component.getAccessor().setAccessible(true);
            assertDeepStateEquals(path + '.' + component.getName(),
                    component.getAccessor().invoke(expected),
                    component.getAccessor().invoke(actual));
        }
    }
}
