package eu.rekawek.coffeegb.debug.command.cpu;

import eu.rekawek.coffeegb.cpu.Opcodes;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;

import java.util.List;
import java.util.Optional;

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
        String arg = commandLine.getArgument("opcode");
        Opcode opcode = getOpcodeFromArg(arg);
        if (opcode == null) {
            System.out.println("Can't found opcode for " + arg);
            return;
        }

        System.out.println(String.format("%02X %s", opcode.getOpcode(), opcode.getLabel()));
        for (Op o : opcode.getOps()) {
            System.out.println(o);
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
        Optional<Opcode> opcode = Opcodes.COMMANDS.stream().filter(o -> compactedArg.equalsIgnoreCase(o.getLabel())).findFirst();
        if (!opcode.isPresent()) {
            opcode = Opcodes.EXT_COMMANDS.stream().filter(o -> compactedArg.equalsIgnoreCase(o.getLabel())).findFirst();
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
}
