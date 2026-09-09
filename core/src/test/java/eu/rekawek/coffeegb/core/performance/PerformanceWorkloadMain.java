package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;

/** Production runTicks workload runner. Reports contain no ROM paths, bytes or checksums. */
public final class PerformanceWorkloadMain {
    private PerformanceWorkloadMain() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            run(args);
        } catch (Exception e) {
            // ROM/parser exceptions can contain user paths; never serialize their message/stack.
            System.err.println("Performance workload failed (" + e.getClass().getSimpleName() + ")");
            status = 1;
        }
        System.exit(status); // EventBus executors in other runtime configurations can be non-daemon.
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected option/value pairs");
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        for (String key : options.keySet()) {
            if (!List.of("scenario", "profile", "ticks", "warmup-ticks", "diagnostics", "scalar",
                    "split-seed", "rom", "manifest", "input", "id").contains(key)) {
                throw new IllegalArgumentException("unknown option");
            }
        }
        long measured = positive(options.getOrDefault("ticks", "4194304"));
        long warmup = Long.parseLong(options.getOrDefault("warmup-ticks", "4194304"));
        if (warmup < 0) throw new IllegalArgumentException("negative warmup");
        String id = options.getOrDefault("id", "workload");
        if (!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid opaque id");
        List<Path> paths = new ArrayList<>();
        if (options.containsKey("manifest")) {
            if (options.containsKey("rom")) throw new IllegalArgumentException("conflicting inputs");
            for (String line : Files.readAllLines(Path.of(options.get("manifest")))) {
                if (!line.isBlank()) paths.add(Path.of(line));
            }
        } else if (options.containsKey("rom")) {
            paths.add(Path.of(options.get("rom")));
        } else {
            paths.add(null);
        }
        List<Input> input = readInput(options.get("input"));
        int index = 0;
        for (Path path : paths) {
            Profile profile = Profile.valueOf(options.getOrDefault("profile", "CGB")
                    .toUpperCase(Locale.ROOT));
            Scenario scenario = Scenario.valueOf(options.getOrDefault("scenario", "CPU")
                    .toUpperCase(Locale.ROOT));
            Rom rom = path == null ? new Rom(image(scenario, profile)) : new Rom(path.toFile());
            PlayerInputHub hub = new PlayerInputHub();
            try (Gameboy gameboy = new Gameboy.GameboyConfiguration(rom)
                    .setHardwareProfile(profile.hardware)
                    .setBootstrapMode(path == null ? Gameboy.BootstrapMode.SKIP
                            : Gameboy.BootstrapMode.FAST_FORWARD)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setRtcTimeSource(() -> 0L)
                    .setPlayerInputSource(hub).setSupportBatterySave(false).build()) {
                if (Boolean.parseBoolean(options.getOrDefault("scalar", "false"))) {
                    Gameboy.class.getMethod("setPerformanceBatchingEnabled", boolean.class)
                            .invoke(gameboy, false);
                }
                Timeline timeline = new Timeline(gameboy, hub, input,
                        options.containsKey("split-seed")
                                ? new Random(Long.parseLong(options.get("split-seed"))) : null);
                if (path == null && scenario == Scenario.HELD_INPUT) {
                    timeline.handles[0].update(EnumSet.of(Button.A, Button.RIGHT));
                }
                timeline.advance(warmup);
                gameboy.resetPerformanceBulkCounters();
                double nominalRate = (double) gameboy.getClockSpec().ticksPerSecondNumerator()
                        / gameboy.getClockSpec().ticksPerSecondDenominator();
                PerformanceDiagnostics diagnostics = new PerformanceDiagnostics((int) Math.ceil(nominalRate));
                boolean accounting = false;
                if (Boolean.parseBoolean(options.getOrDefault("diagnostics", "true"))) {
                    try {
                        Gameboy.class.getMethod("setPerformanceDiagnostics", PerformanceDiagnostics.class)
                                .invoke(gameboy, diagnostics);
                        accounting = true;
                    } catch (NoSuchMethodException e) {
                        // The same compiled harness can run against the untouched parent core.
                    }
                }
                long start = System.nanoTime();
                long frames = timeline.advance(measured);
                long elapsed = System.nanoTime() - start;
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("schema", "coffee-gb-performance-v1");
                report.put("id", id + "-" + index++);
                report.put("source", path == null ? scenario.name() : "LOCAL");
                report.put("profile", profile.name());
                report.put("speed", gameboy.getSpeedMode().getSpeedMode());
                report.put("ticks", measured);
                report.put("host_ns", elapsed);
                report.put("frame_events", frames);
                report.put("nominal_ticks_per_second", nominalRate);
                report.put("clock_numerator", gameboy.getClockSpec().ticksPerSecondNumerator());
                report.put("clock_denominator", gameboy.getClockSpec().ticksPerSecondDenominator());
                report.put("epochs", gameboy.getPerformanceEpochCount());
                report.put("epoch_ticks", gameboy.getPerformanceEpochTicks());
                report.put("bulk_ticks", gameboy.getPerformanceBulkTicks());
                report.put("diagnostics_enabled", accounting);
                if (accounting) {
                    var snapshot = diagnostics.snapshot();
                    if (snapshot.ticks() != measured) throw new IllegalStateException("tick accounting");
                    report.put("execution_ticks", snapshot.executionTicks());
                    report.put("blockers", snapshot.blockers());
                    report.put("fences", snapshot.fences());
                    report.put("subsystem_ticks", snapshot.subsystemTicks());
                    report.put("span_histogram", snapshot.spanHistogram());
                    report.put("longest_scalar_run", snapshot.longestScalarRun());
                    report.put("direct_lines", snapshot.directLines());
                    report.put("rejected_lines", snapshot.rejectedLines());
                    report.put("longest_rejected_line_run", snapshot.longestRejectedLineRun());
                    List<Object> windows = new ArrayList<>();
                    for (var window : snapshot.windows()) {
                        windows.add(Map.of("ticks", window.ticks(), "scalar", window.scalarTicks(),
                                "epoch", window.epochTicks(), "longest_scalar", window.longestScalarRun(),
                                "rejected_lines", window.rejectedLines()));
                    }
                    report.put("windows", windows);
                    List<Object> reasons = new ArrayList<>();
                    for (var rejection : snapshot.rejectionReasons()) {
                        reasons.add(Map.of("reasons", rejection.reasons().stream().map(Enum::name).toList(),
                                "count", rejection.count()));
                    }
                    report.put("rejection_combinations", reasons);
                    report.put("overflow_combinations", snapshot.overflowCombinations());
                }
                System.out.println(json(report));
            }
        }
    }

    private static long positive(String value) {
        long result = Long.parseLong(value);
        if (result <= 0) throw new IllegalArgumentException("positive tick count required");
        return result;
    }

    private record Input(long tick, int player, EnumSet<Button> buttons) {}

    /** CSV: tick,player,buttons; buttons use A+RIGHT or '-' for released. Ticks follow bootstrap. */
    private static List<Input> readInput(String file) throws Exception {
        List<Input> events = new ArrayList<>();
        if (file == null) return events;
        long previous = -1;
        for (String line : Files.readAllLines(Path.of(file))) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("tick,")) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != 3) throw new IllegalArgumentException("invalid input row");
            long tick = Long.parseLong(fields[0]);
            int player = Integer.parseInt(fields[1]);
            if (tick < 0 || tick < previous || player < 0 || player >= 4) {
                throw new IllegalArgumentException("invalid input boundary");
            }
            EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
            if (!fields[2].equals("-")) {
                for (String button : fields[2].split("\\+")) buttons.add(Button.valueOf(button));
            }
            events.add(new Input(tick, player, buttons));
            previous = tick;
        }
        return events;
    }

    private static final class Timeline {
        private final Gameboy gameboy;
        private final PlayerInputHub.SourceHandle[] handles = new PlayerInputHub.SourceHandle[4];
        private final List<Input> inputs;
        private final Random split;
        private long tick;
        private int next;
        Timeline(Gameboy gameboy, PlayerInputHub hub, List<Input> inputs, Random split) {
            this.gameboy = gameboy;
            this.inputs = inputs;
            this.split = split;
            for (int player = 0; player < 4; player++) handles[player] = hub.openSource(player);
        }
        long advance(long count) {
            long end = Math.addExact(tick, count);
            long frames = 0;
            while (tick < end) {
                while (next < inputs.size() && inputs.get(next).tick() == tick) {
                    Input event = inputs.get(next++);
                    handles[event.player()].update(event.buttons());
                }
                long boundary = next < inputs.size() ? Math.min(end, inputs.get(next).tick()) : end;
                int budget = gameboy.getClockSpec().controllerTicksPerFrame();
                if (split != null) budget = 1 + split.nextInt(budget);
                int ticks = (int) Math.min(boundary - tick, budget);
                frames += gameboy.runTicks(ticks);
                tick += ticks;
            }
            return frames;
        }
    }

    private static String json(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for (var entry : map.entrySet()) fields.add(json(entry.getKey().toString()) + ':' + json(entry.getValue()));
            return '{' + String.join(",", fields) + '}';
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) items.add(json(item));
            return '[' + String.join(",", items) + ']';
        }
        return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
