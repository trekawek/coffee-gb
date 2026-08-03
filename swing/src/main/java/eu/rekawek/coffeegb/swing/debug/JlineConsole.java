package eu.rekawek.coffeegb.swing.debug;

import eu.rekawek.coffeegb.core.debug.Console;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Desktop JLine presentation for the platform-neutral debugger command processor. */
public final class JlineConsole extends Console implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(JlineConsole.class);

    @Override
    public void run() {
        LineReader lineReader = LineReaderBuilder.builder().build();
        while (!isStopped()) {
            try {
                executeLine(lineReader.readLine("coffee-gb> "));
            } catch (IllegalArgumentException e) {
                error.println(e.getMessage());
            } catch (UserInterruptException e) {
                stop();
            } catch (RuntimeException e) {
                LOG.warn("Console command failed", e);
                error.println("Command failed.");
            }
        }
    }
}
