package eu.rekawek.coffeegb.swing.translation;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ScreenTranslator extends AutoCloseable {

    /** The caller owns the screenshot and must not mutate it until this request completes. */
    CompletableFuture<List<TranslationRegion>> translate(BufferedImage screenshot);

    @Override
    default void close() {
    }
}
