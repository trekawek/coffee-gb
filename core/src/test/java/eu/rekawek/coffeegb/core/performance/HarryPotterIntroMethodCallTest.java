package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/** Counts core-package method entries needed to produce a fixed number of video frames. */
public class HarryPotterIntroMethodCallTest {

    private static final String DEFAULT_ROM = "Z:\\emu\\roms\\gbc\\H\\"
            + "Harry Potter and the Sorcerer's Stone (USA, Europe) "
            + "(En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc";

    // 1,200 frames is approximately 20 seconds; another 600 is approximately 10 seconds.
    private static final int DEFAULT_TARGET_FRAMES = 1_800;

    @Test
    public void countsCoreMethodCallsToTargetFrame() throws Exception {
        File romFile = new File(System.getProperty("harryPotterRom", DEFAULT_ROM));
        if (!romFile.isFile()) {
            throw new IllegalArgumentException("ROM not found: " + romFile);
        }
        int targetFrames = Integer.getInteger("harryPotterTargetFrames", DEFAULT_TARGET_FRAMES);
        if (targetFrames < 1) {
            throw new IllegalArgumentException("harryPotterTargetFrames must be positive");
        }
        int topMethods = Integer.getInteger("harryPotterMethodCallTop", 0);
        if (topMethods < 0) {
            throw new IllegalArgumentException("harryPotterMethodCallTop must not be negative");
        }

        byte[] batteryData = HarryPotterIntroHarness.loadBatteryData();
        AgentCounter counter = AgentCounter.load();
        counter.reset();

        long ticks = 0;
        int frames = 0;
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setBatteryData(batteryData)
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            while (frames < targetFrames) {
                if (gameboy.tick()) {
                    frames++;
                }
                ticks++;
            }

            long methodCalls = counter.get();
            assertEquals(targetFrames, frames);
            System.out.printf("METHOD_CALL_RESULT frames=%d ticks=%d calls=%d%n",
                    frames, ticks, methodCalls);
            System.out.printf("Target frames: %d%n", frames);
            System.out.printf("Emulated ticks: %d%n", ticks);
            System.out.printf("Core method calls: %d%n", methodCalls);
            System.out.printf("Core method calls/frame: %.3f%n", methodCalls / (double) frames);
            System.out.printf("Core method calls/tick: %.6f%n", methodCalls / (double) ticks);
            if (topMethods > 0) {
                System.out.print(counter.report(topMethods));
            }
        }
    }

    private static final class AgentCounter {

        private final Method reset;
        private final Method get;
        private final Method report;

        private AgentCounter(Method reset, Method get, Method report) {
            this.reset = reset;
            this.get = get;
            this.report = report;
        }

        private static AgentCounter load() {
            try {
                Class<?> type = Class.forName("eu.rekawek.coffeegb.harness.MethodCallCounter");
                return new AgentCounter(type.getMethod("reset"), type.getMethod("get"),
                        type.getMethod("report", int.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Method-call agent is not loaded; run the test with -javaagent", e);
            }
        }

        private void reset() throws ReflectiveOperationException {
            reset.invoke(null);
        }

        private long get() throws ReflectiveOperationException {
            return (long) get.invoke(null);
        }

        private String report(int limit) throws ReflectiveOperationException {
            return (String) report.invoke(null, limit);
        }
    }
}
