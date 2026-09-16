package eu.rekawek.coffeegb.swing.translation;

/** English replacement text and its bounding box in native screenshot pixels. */
public record TranslationRegion(String text, int x, int y, int width, int height) {
}
