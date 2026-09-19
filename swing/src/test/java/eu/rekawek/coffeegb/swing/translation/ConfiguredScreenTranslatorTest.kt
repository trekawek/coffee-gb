package eu.rekawek.coffeegb.swing.translation

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.TranslationProvider
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test

class ConfiguredScreenTranslatorTest {
  @Test fun `automatic Mac translation stays local even when Apple fails and a key exists`() {
    val apple = Stub(fails = true)
    val cloud = Stub()
    ConfiguredScreenTranslator({ ApplicationSettings.Translation("saved-key") }, true, apple, cloud).use {
      assertTrue(it.translate(image).isCompletedExceptionally)
      assertEquals(1, apple.calls)
      assertEquals(0, cloud.calls)
    }
    assertTrue(apple.closed)
    assertTrue(cloud.closed)
  }

  @Test fun `saved provider changes apply to next request and forward setup progress`() {
    var settings = ApplicationSettings.Translation()
    val apple = Stub()
    val cloud = Stub()
    ConfiguredScreenTranslator({ settings }, true, apple, cloud).use {
      val progress = mutableListOf<ScreenTranslator.Progress>()
      it.translate(image, Consumer(progress::add)).join()
      settings = settings.copy(provider = TranslationProvider.OPENAI)
      it.translate(image).join()
      assertEquals(1, apple.calls)
      assertEquals(1, cloud.calls)
      assertEquals(listOf(ScreenTranslator.Progress.LANGUAGE_SETUP), progress)
    }
  }

  @Test fun `automatic elsewhere preserves OpenAI while explicit Apple is respected`() {
    var settings = ApplicationSettings.Translation()
    val apple = Stub()
    val cloud = Stub()
    ConfiguredScreenTranslator({ settings }, false, apple, cloud).use {
      it.translate(image).join()
      settings = settings.copy(provider = TranslationProvider.APPLE_LOCAL)
      it.translate(image).join()
      assertEquals(1, apple.calls)
      assertEquals(1, cloud.calls)
    }
  }

  private class Stub(val fails: Boolean = false) : ScreenTranslator {
    var calls = 0
    var closed = false
    override fun translate(screenshot: BufferedImage): CompletableFuture<List<TranslationRegion>> =
        translate(screenshot, Consumer {})
    override fun translate(screenshot: BufferedImage, progress: Consumer<ScreenTranslator.Progress>): CompletableFuture<List<TranslationRegion>> {
      calls++
      progress.accept(ScreenTranslator.Progress.LANGUAGE_SETUP)
      return if (fails) CompletableFuture.failedFuture(IllegalStateException("unavailable"))
          else CompletableFuture.completedFuture(emptyList())
    }
    override fun close() { closed = true }
  }

  private val image = BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB)
}
