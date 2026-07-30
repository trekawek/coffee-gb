package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.Command;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugResult;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Shared bounded wait and error rendering for console commands backed by {@link DebugPort}. */
abstract class DebugPortCommand implements Command {

    private final Supplier<DebugPort> debugPortSupplier;

    private final long timeoutMillis;

    protected final PrintStream output;

    protected final PrintStream error;

    DebugPortCommand(
            Supplier<DebugPort> debugPortSupplier,
            long timeoutMillis,
            PrintStream output,
            PrintStream error) {
        this.debugPortSupplier = Objects.requireNonNull(debugPortSupplier, "debugPortSupplier");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    protected final DebugPort activePort() {
        DebugPort port = debugPortSupplier.get();
        if (port == null) {
            error.println("NO_ACTIVE_SESSION: Load a ROM before using machine debug commands.");
            return null;
        }
        if (port.isClosed()) {
            error.println("PORT_CLOSED: The attached debug session is closed.");
            return null;
        }
        return port;
    }

    protected final <T> DebugResult<T> await(CompletionStage<DebugResult<T>> stage) {
        if (stage == null) {
            error.println("CONSOLE_FAILURE: The debug port returned no completion stage.");
            return null;
        }
        DebugResult<T> result;
        try {
            result = stage.toCompletableFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            error.printf(
                    "CONSOLE_TIMEOUT: No result after %d ms; the admitted command may still "
                            + "complete on its session.%n",
                    timeoutMillis);
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error.println("CONSOLE_INTERRUPTED: Interrupted while waiting for the debug session.");
            return null;
        } catch (CancellationException failure) {
            error.println("CONSOLE_FAILURE: The debug completion was cancelled.");
            return null;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String message = cause.getMessage();
            error.printf(
                    "CONSOLE_FAILURE: %s%s%n",
                    cause.getClass().getSimpleName(),
                    message == null || message.isBlank() ? "" : ": " + message);
            return null;
        }
        if (result == null) {
            error.println("CONSOLE_FAILURE: The debug port completed without a result.");
            return null;
        }
        if (result.isFailure()) {
            error.printf("%s: %s%n", result.error().code(), result.error().message());
            return null;
        }
        return result;
    }

    protected static void requireNoExtraArguments(
            eu.rekawek.coffeegb.core.debug.CommandPattern.ParsedCommandLine commandLine) {
        if (!commandLine.getRemainingArguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Unexpected argument: " + commandLine.getRemainingArguments().get(0));
        }
    }
}
