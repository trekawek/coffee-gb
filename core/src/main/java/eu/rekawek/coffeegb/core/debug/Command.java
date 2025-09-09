package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.CommandPattern.ParsedCommandLine;

public interface Command {

    CommandPattern getPattern();

    void run(ParsedCommandLine commandLine);
}
