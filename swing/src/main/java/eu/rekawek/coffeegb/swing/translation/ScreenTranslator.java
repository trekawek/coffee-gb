package eu.rekawek.coffeegb.swing.translation;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ScreenTranslator extends AutoCloseable {

    enum Progress {
        LANGUAGE_SETUP,
        TRANSLATING
    }

    /** The caller owns the screenshot and must not mutate it until this request completes. */
    CompletableFuture<List<TranslationRegion>> translate(BufferedImage screenshot);

    /** Local providers may need an interactive, one-time language download before translating. */
    default CompletableFuture<List<TranslationRegion>> translate(
            BufferedImage screenshot, Consumer<Progress> progress) {
        return translate(screenshot);
    }

    @Override
    default void close() {
    }
}
