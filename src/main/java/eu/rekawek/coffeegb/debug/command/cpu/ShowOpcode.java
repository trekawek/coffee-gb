package eu.rekawek.coffeegb.debug.command.cpu;

import eu.rekawek.coffeegb.cpu.Opcodes;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static eu.rekawek.coffeegb.debug.ConsoleUtil.printSeparator;

public class ShowOpcode implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("cpu show opcode")
            .withRequiredArgument("opcode")
            .withDescription("displays opcode information for hex (0xFA) or name (LD A,B) identifier")
            .build();

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(ParsedCommandLine commandLine) {
        String arg;
        if (commandLine.getRemainingArguments().isEmpty()) {
            arg = commandLine.getArgument("opcode");
        } else {
            arg = commandLine.getArgument("opcode") + " " + String.join(" ", commandLine.getRemainingArguments());
        }

        Opcode opcode = getOpcodeFromArg(arg);
        if (opcode == null) {
            System.out.println("Can't found opcode for " + arg);
            return;
        }

        boolean isExt = Opcodes.EXT_COMMANDS.get(opcode.getOpcode()) == opcode;

        List<OpDescription> ops = new ArrayList<>();
        BiFunction<Integer, String, Boolean> addOp = (c, d) -> ops.add(new OpDescription(c, d));
        if (isExt) {
            addOp.apply(4, "read opcode 0xCB");
        }
        addOp.apply(4, String.format("read opcode 0x%02X", opcode.getOpcode()));
        for (int i = 0; i < opcode.getOperandLength(); i++) {
            addOp.apply(4, String.format("read operand %d", i + 1));
        }
        opcode.getOps().stream().map(OpDescription::new).forEach(ops::add);

        List<OpDescription> compacted = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            OpDescription o = ops.get(i);
            if (o.description.equals("wait cycle")) {
                if (!compacted.isEmpty()) {
                    compacted.get(compacted.size() - 1).updateCycles(4);
                }
            } else if (o.description.equals("finish cycle")) {
                if (i < ops.size() - 1) {
                    OpDescription nextOp = ops.get(++i);
                    nextOp.updateCycles(4);
                    compacted.add(nextOp);
                }
            } else {
                compacted.add(o);
            }
        }

        int stringLength = compacted
                .stream()
                .map(OpDescription::toString)
                .map(String::length)
                .mapToInt(Integer::valueOf)
                .max()
                .orElse(0);

        int totalCycles = compacted
                .stream()
                .mapToInt(o -> o.cycles)
                .sum();


        int totalCyclesUntilCondition = compacted
                .stream()
                .filter(new Predicate<OpDescription>() {
                    private boolean conditionalOccurred;
                    @Override
                    public boolean test(OpDescription opDescription) {
                        conditionalOccurred = conditionalOccurred || opDescription.description.startsWith("? ");
                        return !conditionalOccurred;
                    }
                })
                .mapToInt(o -> o.cycles)
                .sum();

        if (isExt) {
            System.out.printf("0xCB%02X %s%n", opcode.getOpcode(), opcode.getLabel());
        } else {
            System.out.printf("0x%02X   %s%n", opcode.getOpcode(), opcode.getLabel());
        }
        printSeparator(stringLength);
        compacted.forEach(System.out::println);
        printSeparator(stringLength);
        if (totalCyclesUntilCondition != totalCycles) {
            System.out.printf("Total cycles: %d / %d%n", totalCycles, totalCyclesUntilCondition);
        } else {
            System.out.printf("Total cycles: %d%n", totalCycles);
        }
    }

    private Opcode getOpcodeFromArg(String arg) {
        if (arg.toLowerCase().matches("0x[0-9a-f]{2}")) {
            return getFromHex(Opcodes.COMMANDS, arg.substring(2));
        } else if (arg.toLowerCase().matches("0xcb[0-9a-f]{2}")) {
            return getFromHex(Opcodes.EXT_COMMANDS, arg.substring(4));
        } else if (arg.toLowerCase().matches("[0-9a-f]{2}")) {
            return getFromHex(Opcodes.COMMANDS, arg);
        } else if (arg.toLowerCase().matches("cb[0-9a-f]{2}")) {
            return getFromHex(Opcodes.EXT_COMMANDS, arg.substring(2));
        }

        String compactedArg = compactOpcodeLabel(arg);
        Optional<Opcode> opcode = Opcodes.COMMANDS
                .stream()
                .filter(Objects::nonNull)
                .filter(o -> compactedArg.equalsIgnoreCase(compactOpcodeLabel(o.getLabel())))
                .findFirst();
        if (!opcode.isPresent()) {
            opcode = Opcodes.EXT_COMMANDS
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(o -> compactedArg.equalsIgnoreCase(compactOpcodeLabel(o.getLabel())))
                    .findFirst();
        }
        return opcode.orElse(null);
    }

    private Opcode getFromHex(List<Opcode> opcodes, String hexArg) {
        return opcodes.get(Integer.parseInt(hexArg, 16));
    }

    private String compactOpcodeLabel(String label) {
        return label
                .replace(" ", "")
                .toLowerCase();
    }

    public static class OpDescription {

        private final String description;

        private int cycles;

        public OpDescription(Op op) {
            this.description = op.toString();
            if (op.writesMemory() || op.readsMemory()) {
                this.cycles = 4;
            }
        }

        public OpDescription(int cycles, String description) {
            this.description = description;
            this.cycles = cycles;
        }

        public void updateCycles(int cycles) {
            this.cycles += cycles;
        }

        @Override
        public String toString() {
            return String.format("%s   %s", cycles == 0 ? " " : String.valueOf(cycles), description);
        }
    }
}
