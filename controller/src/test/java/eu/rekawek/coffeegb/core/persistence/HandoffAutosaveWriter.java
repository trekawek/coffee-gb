package eu.rekawek.coffeegb.core.persistence;

import java.io.IOException;
import java.nio.file.Path;

/** Pauses a real controller autosave at the rename boundary for the separate-process regression. */
public final class HandoffAutosaveWriter extends AtomicFileWriter {
    public HandoffAutosaveWriter(Path gameDirectory, boolean hold) {
        super(new NioFileOperations(), (stage, target, temp) -> {
            if (hold && target.getFileName().toString().equals("state.cgbstate")
                    && stage == Stage.AFTER_FORCE_BEFORE_REPLACEMENT) {
                System.out.println("AUTOSAVE_READY");
                if (System.in.read() == -1) throw new IOException("parent closed input");
            }
        }, gameDirectory);
    }
}
