package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugResult;
import eu.rekawek.coffeegb.core.debug.DebugStepKind;
import eu.rekawek.coffeegb.core.debug.DebugStepResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class Step extends DebugPortCommand {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("step", "s")
                    .withDescription("steps instruction (default), machine-cycle, or frame")
                    .build();

    public Step(
            Supplier<DebugPort> debugPortSupplier,
            long timeoutMillis,
            PrintStream output,
            PrintStream error) {
        super(debugPortSupplier, timeoutMillis, output, error);
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        DebugStepKind kind = parseKind(commandLine.getRemainingArguments());
        DebugPort port = activePort();
        if (port == null) return;
        if (!port.capabilities().supports(kind)) {
            error.printf("UNSUPPORTED_STEP: This session does not support %s stepping.%n", kind);
            return;
        }
        DebugResult<DebugStepResult> result = await(port.step(kind));
        if (result == null) return;
        DebugStepResult step = result.value();
        output.printf(
                "Step %s stopped for %s after %d ticks and %d retirements; tick=%d, PC=%04X.%n",
                step.kind(),
                step.stopReason(),
                step.ticksExecuted(),
                step.instructionsRetired(),
                step.snapshot().masterTick(),
                step.snapshot().registers().pc());
    }

    private DebugStepKind parseKind(List<String> arguments) {
        if (arguments.isEmpty()) return DebugStepKind.INSTRUCTION;
        if (arguments.size() > 1) {
            throw new IllegalArgumentException("Unexpected argument: " + arguments.get(1));
        }
        return switch (arguments.get(0).toLowerCase(Locale.ROOT)) {
            case "instruction", "insn", "i" -> DebugStepKind.INSTRUCTION;
            case "machine-cycle", "machine_cycle", "cycle", "m" ->
                    DebugStepKind.MACHINE_CYCLE;
            case "frame", "f" -> DebugStepKind.FRAME;
            default -> throw new IllegalArgumentException(
                    "Unknown step kind: " + arguments.get(0)
                            + "; expected instruction, machine-cycle, or frame");
        };
    }
}
