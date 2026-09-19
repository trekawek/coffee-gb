package eu.rekawek.coffeegb.swing.translation

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.TranslationProvider
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/** Resolves preferences on every request; a local failure never sends the screen to the cloud. */
internal class ConfiguredScreenTranslator(
    private val settings: () -> ApplicationSettings.Translation,
    private val isMac: Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("mac"),
    private val apple: ScreenTranslator = AppleScreenTranslator(),
    private val openAi: ScreenTranslator = OpenAiScreenTranslator { settings().apiKey },
) : ScreenTranslator {
  override fun translate(screenshot: BufferedImage): CompletableFuture<List<TranslationRegion>> =
      translate(screenshot, Consumer {})

  override fun translate(
      screenshot: BufferedImage,
      progress: Consumer<ScreenTranslator.Progress>,
  ): CompletableFuture<List<TranslationRegion>> = provider().translate(screenshot, progress)

  private fun provider(): ScreenTranslator = when (settings().provider) {
    TranslationProvider.AUTOMATIC -> if (isMac) apple else openAi
    TranslationProvider.APPLE_LOCAL -> apple
    TranslationProvider.OPENAI -> openAi
  }

  override fun close() {
    try {
      apple.close()
    } finally {
      openAi.close()
    }
  }
}
