package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.swing.translation.ScreenTranslator
import eu.rekawek.coffeegb.swing.translation.TranslationException
import eu.rekawek.coffeegb.swing.translation.TranslationRegion
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class DesktopScreenTranslationControllerTest {
  @Test fun `pauses once translates off EDT and resumes only its own pause`() {
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      assertFalse(f.calledOnEdt)
      assertEquals(listOf(true), f.pauses)
      f.response.complete(listOf(TranslationRegion("Hello!", 8, 96, 140, 32)))
      assertTrue(f.finished.await(2, TimeUnit.SECONDS))
      edt {
        assertEquals("Hello!", f.shown.single().text())
        assertTrue(f.controller.dismiss())
        assertFalse(f.controller.visible())
        assertEquals(listOf(true, false), f.pauses)
        assertEquals(2, f.inputReleases)
      }
    }
  }

  @Test fun `does not resume a game that was already paused`() {
    Fixture(paused = true).use { f ->
      edt { f.controller.toggle(); f.controller.dismiss() }
      assertTrue(f.pauses.isEmpty())
    }
  }

  @Test fun `dismiss cancels pending HTTP and ignores late response`() {
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      edt { f.controller.dismiss() }
      // Worker may publish its response future just after the cancellation, but must cancel it.
      assertTrue(f.cancelled.await(2, TimeUnit.SECONDS))
      f.response.complete(listOf(TranslationRegion("stale", 0, 0, 30, 20)))
      edt { assertTrue(f.shown.isEmpty()); assertEquals(1, f.clears) }
    }
  }

  @Test fun `deadline cancels a stalled request and does not retry`() {
    Fixture(timeoutMillis = 100).use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      assertTrue(f.finished.await(2, TimeUnit.SECONDS))
      assertTrue(f.cancelled.await(2, TimeUnit.SECONDS))
      edt {
        assertTrue(f.status.contains("timed out"))
        assertTrue(f.controller.visible())
        assertEquals(1, f.calls)
        f.controller.dismiss()
        assertEquals(listOf(true, false), f.pauses)
      }
    }
  }

  @Test fun `replacement invalidates result without resuming new session`() {
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      edt {
        f.state = f.state.copy(sessionGeneration = 2)
        f.controller.invalidate()
      }
      f.response.complete(listOf(TranslationRegion("stale", 0, 0, 20, 20)))
      edt {
        assertFalse(f.controller.visible())
        assertEquals(listOf(true), f.pauses)
        assertTrue(f.shown.isEmpty())
      }
    }
  }

  @Test fun `language setup suspends ordinary deadline then translation restores it`() {
    Fixture(timeoutMillis = 150, setupTimeoutMillis = 2_000).use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      f.progress!!.accept(ScreenTranslator.Progress.LANGUAGE_SETUP)
      edt { assertTrue(f.status.contains("language setup")) }
      assertFalse(f.cancelled.await(300, TimeUnit.MILLISECONDS))
      f.progress!!.accept(ScreenTranslator.Progress.TRANSLATING)
      assertTrue(f.cancelled.await(2, TimeUnit.SECONDS))
      edt { assertTrue(f.status.contains("timed out")) }
    }
  }

  @Test fun `setup progress after cancellation cannot resurrect overlay or delay new request`() {
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      edt { f.controller.dismiss() }
      f.progress!!.accept(ScreenTranslator.Progress.LANGUAGE_SETUP)
      edt { assertFalse(f.controller.visible()); assertEquals(1, f.clears) }
    }
  }

  @Test fun `language setup is bounded and remains cancellable`() {
    Fixture(timeoutMillis = 2_000, setupTimeoutMillis = 100).use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      f.progress!!.accept(ScreenTranslator.Progress.LANGUAGE_SETUP)
      assertTrue(f.cancelled.await(2, TimeUnit.SECONDS))
      edt {
        assertTrue(f.status.contains("Language setup timed out"))
        f.controller.dismiss()
        assertEquals(listOf(true, false), f.pauses)
      }
    }
  }

  @Test fun `no foreign text and safe failure have useful terminal states`() {
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      f.response.complete(emptyList())
      assertTrue(f.finished.await(2, TimeUnit.SECONDS))
      edt { assertTrue(f.status.contains("No foreign text")) }
    }
    Fixture().use { f ->
      edt { f.controller.toggle() }
      assertTrue(f.called.await(2, TimeUnit.SECONDS))
      f.response.completeExceptionally(TranslationException(
          TranslationException.Kind.MISSING_API_KEY, "Set OPENAI_API_KEY before translating."))
      assertTrue(f.finished.await(2, TimeUnit.SECONDS))
      edt { assertTrue(f.status.contains("OPENAI_API_KEY")) }
    }
  }

  @Test fun `closed idle and unsupported sessions never send a screenshot`() {
    Fixture().use { f ->
      edt {
        f.state = f.state.copy(commands = f.state.commands.copy(pauseSupported = false))
        f.controller.toggle()
        f.controller.close()
        f.controller.toggle()
        assertFalse(f.controller.visible())
        assertEquals(0, f.calls)
        assertTrue(f.pauses.isEmpty())
      }
    }
  }

  private class Fixture(paused: Boolean = false, timeoutMillis: Int = 9_000,
                        setupTimeoutMillis: Int = 15 * 60 * 1_000) : AutoCloseable {
    val called = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val response = object : CompletableFuture<List<TranslationRegion>>() {
      override fun cancel(interrupt: Boolean): Boolean = super.cancel(interrupt).also {
        cancelled.countDown()
      }
    }
    @Volatile var calledOnEdt = false
    @Volatile var calls = 0
    @Volatile var progress: Consumer<ScreenTranslator.Progress>? = null
    var state = DesktopPresentation(gameTitle = "Fixture", sessionGeneration = 1,
        commands = DesktopCommandPresentation(gameLoaded = true, pauseSupported = true, paused = paused))
    val pauses = mutableListOf<Boolean>()
    var inputReleases = 0
    var clears = 0
    var status = ""
    var shown = emptyList<TranslationRegion>()
    val controller = DesktopScreenTranslationController(
        translator = object : ScreenTranslator {
          override fun translate(image: BufferedImage, listener: Consumer<ScreenTranslator.Progress>): CompletableFuture<List<TranslationRegion>> {
            progress = listener
            return translate(image)
          }
          override fun translate(image: BufferedImage): CompletableFuture<List<TranslationRegion>> {
            calls++
            calledOnEdt = SwingUtilities.isEventDispatchThread()
            called.countDown()
            return response
          }
        },
        state = { state },
        capture = { BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB) },
        show = { _, regions, message ->
          assertTrue(SwingUtilities.isEventDispatchThread())
          shown = regions
          status = message
          if (!message.startsWith("Translating")) finished.countDown()
        },
        clear = { clears++; shown = emptyList() },
        setPaused = { value ->
          pauses += value
          state = state.copy(commands = state.commands.copy(paused = value))
        },
        releaseInput = { inputReleases++ },
        notice = {},
        timeoutMillis = timeoutMillis,
        setupTimeoutMillis = setupTimeoutMillis,
    )
    override fun close() = edt { controller.close() }
  }

  companion object {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
  }
}
