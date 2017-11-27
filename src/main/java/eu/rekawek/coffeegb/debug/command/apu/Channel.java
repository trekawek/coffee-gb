package eu.rekawek.coffeegb.debug.command.apu;

import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.sound.Sound;

import java.util.HashSet;
import java.util.Set;

public class Channel implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("apu chan")
            .withDescription("enable given channels (1-4)")
            .build();

    private final Sound sound;

    public Channel(Sound sound) {
        this.sound = sound;
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        Set<String> channels = new HashSet<>(commandLine.getRemainingArguments());
        for (int i = 1; i <= 4; i++) {
            sound.enableChannel(i - 1, channels.contains(String.valueOf(i)));
        }
    }
}
