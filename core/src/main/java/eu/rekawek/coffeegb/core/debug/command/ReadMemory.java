package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock;
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugResult;

import java.io.PrintStream;
import java.util.Locale;
import java.util.function.Supplier;

public final class ReadMemory extends DebugPortCommand {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("memory read", "mem")
                    .withRequiredArgument("address-space")
                    .withRequiredArgument("address")
                    .withRequiredArgument("length")
                    .withDescription("reads bounded side-effect-free memory (decimal or 0x hex)")
                    .build();

    public ReadMemory(
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
        requireNoExtraArguments(commandLine);
        DebugAddressSpace addressSpace = parseAddressSpace(commandLine.getArgument("address-space"));
        int address = DebugCommandValues.parseUnsigned(
                "address", commandLine.getArgument("address"), 0xffff);
        int length = DebugCommandValues.parseUnsigned(
                "length", commandLine.getArgument("length"), DebugMemoryRequest.MAX_LENGTH);
        if (length == 0) {
            throw new IllegalArgumentException("Argument length must be at least 1");
        }
        DebugMemoryRequest request = new DebugMemoryRequest(addressSpace, address, length);

        DebugPort port = activePort();
        if (port == null) return;
        int maximum = port.capabilities().maxMemoryReadLength();
        if (length > maximum) {
            error.printf(
                    "INVALID_ARGUMENT: Requested %d bytes, but this session advertises a maximum "
                            + "of %d.%n",
                    length,
                    maximum);
            return;
        }

        DebugResult<DebugMemoryBlock> result = await(port.readMemory(request));
        if (result == null) return;
        print(result.value());
    }

    private DebugAddressSpace parseAddressSpace(String value) {
        try {
            return DebugAddressSpace.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown debug address space: " + value);
        }
    }

    private void print(DebugMemoryBlock block) {
        output.printf(
                "%s %04X-%04X (%d bytes)%n",
                block.addressSpace(),
                block.startAddress(),
                block.endExclusive() - 1,
                block.length());
        for (int row = 0; row < block.length(); row += 16) {
            output.printf("%04X:", block.startAddress() + row);
            int rowLength = Math.min(16, block.length() - row);
            for (int column = 0; column < rowLength; column++) {
                output.printf(" %02X", block.unsignedByteAt(row + column));
            }
            output.println();
        }
    }
}
