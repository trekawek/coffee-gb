package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Random;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;

/** Authored images only; driven by scripts/compare-performance-matrix.py's validated manifest. */
public final class PerformanceMatrixMain {
    private record Case(Scenario scenario, Profile profile) {}

    private static long advance(Gameboy gameboy, long ticks) {
        long frames = 0;
        while (ticks > 0) {
            int count = (int) Math.min(ticks, gameboy.getClockSpec().controllerTicksPerFrame());
            frames += gameboy.runTicks(count);
            ticks -= count;
        }
        return frames;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        // EventBus worker threads may remain non-daemon after the last authored session.
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "variant trial cases.tsv warmup-ticks measured-ticks seed diagnostic-window id");
        }
        String variant = args[0];
        if (!variant.equals("baseline") && !variant.equals("candidate")
                && !variant.equals("diagnostics")) {
            throw new IllegalArgumentException("Unknown matrix variant");
        }
        int trial = Integer.parseInt(args[1]);
        long warmup = Long.parseLong(args[3]);
        long measured = Long.parseLong(args[4]);
        long seed = Long.parseLong(args[5]);
        int diagnosticWindow = Integer.parseInt(args[6]);
        if (trial < 0 || warmup < 0 || measured <= 0 || diagnosticWindow <= 0) {
            throw new IllegalArgumentException("Invalid matrix budget");
        }
        ArrayList<Case> cases = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid authored case row");
            cases.add(new Case(Scenario.valueOf(fields[0]), Profile.valueOf(fields[1])));
        }
        Collections.shuffle(cases, new Random(seed + trial));
        System.err.printf("matrix=%s variant=%s trial=%d java=%s%n", args[7], variant, trial,
                System.getProperty("java.runtime.version"));
        for (Case workload : cases) {
            PlayerInputHub input = new PlayerInputHub();
            try (Gameboy gameboy = new Gameboy.GameboyConfiguration(
                    new Rom(image(workload.scenario, workload.profile)))
                    .setHardwareProfile(workload.profile.hardware)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(ExecutionMode.PERFORMANCE).setRtcTimeSource(() -> 0L)
                    .setPlayerInputSource(input).setSupportBatterySave(false).build()) {
                var handle = input.openSource(0);
                if (workload.scenario == Scenario.HELD_INPUT) {
                    handle.update(EnumSet.of(Button.A, Button.RIGHT));
                }
                advance(gameboy, warmup);
                gameboy.resetPerformanceBulkCounters();
                PerformanceDiagnostics diagnostics = null;
                if (variant.equals("diagnostics")) {
                    diagnostics = new PerformanceDiagnostics(diagnosticWindow);
                    // The unchanged baseline predates diagnostics. It loads this same harness
                    // and image generator without linking an unavailable Gameboy setter.
                    Gameboy.class.getMethod("setPerformanceDiagnostics", PerformanceDiagnostics.class)
                            .invoke(gameboy, diagnostics);
                }
                long start = System.nanoTime();
                long frames = advance(gameboy, measured);
                long elapsed = System.nanoTime() - start;
                if (diagnostics != null && diagnostics.snapshot().ticks() != measured) {
                    throw new AssertionError("Diagnostics did not account for every measured tick");
                }
                System.out.printf(Locale.ROOT, "%s\t%d\t%s\t%s\t%d\t%d\t%d\t%d\t%d%n",
                        variant, trial, workload.scenario, workload.profile, measured, elapsed,
                        frames, gameboy.getPerformanceEpochTicks(), gameboy.getPerformanceBulkTicks());
                System.out.flush();
            }
        }
    }
}
