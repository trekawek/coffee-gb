package eu.rekawek.coffeegb.debug;

import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;

public interface Command {

    CommandPattern getPattern();

    void run(ParsedCommandLine commandLine);
}
