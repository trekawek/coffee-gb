package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.swing.translation.ScreenTranslator
import eu.rekawek.coffeegb.swing.translation.TranslationException
import eu.rekawek.coffeegb.swing.translation.TranslationRegion
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Owns one explicit screenshot request and its pause, entirely outside the emulation core. */
internal class DesktopScreenTranslationController(
    private val translator: ScreenTranslator,
    private val state: () -> DesktopPresentation,
    private val capture: () -> BufferedImage?,
    private val show: (BufferedImage, List<TranslationRegion>, String) -> Unit,
    private val clear: () -> Unit,
    private val setPaused: (Boolean) -> Unit,
    private val releaseInput: () -> Unit,
    private val notice: (String) -> Unit,
    private val timeoutMillis: Int = 9_000,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
      Thread(task, "screen-translation").apply { isDaemon = true }
    },
) : AutoCloseable {
  private class Request(
      val frame: BufferedImage,
      val generation: Long?,
      val ownsPause: Boolean,
  ) {
    val cancelled = AtomicBoolean()
    val response = AtomicReference<CompletableFuture<List<TranslationRegion>>?>()
    var preparation: Future<*>? = null
    var deadline: Timer? = null

    fun cancel() {
      cancelled.set(true)
      deadline?.stop()
      preparation?.cancel(true)
      response.get()?.cancel(true)
    }
  }

  private var active: Request? = null
  private var closed = false

  fun visible(): Boolean = active != null

  fun toggle() {
    requireEdt()
    if (closed) return
    if (dismiss()) return
    val current = state()
    if (!current.commands.gameLoaded || !current.commands.pauseSupported ||
        current.commands.sessionBusy) return
    val frame = capture()
    if (frame == null) {
      notice("Wait for the first game screen before translating.")
      return
    }
    val request = Request(frame, current.sessionGeneration, !current.commands.paused)
    active = request
    releaseInput()
    if (request.ownsPause) setPaused(true)
    show(frame, emptyList(), "Translating to English…  Esc to cancel")
    request.deadline = Timer(timeoutMillis) {
      if (active === request) {
        request.cancel()
        show(frame, emptyList(), "Translation timed out. Esc to return; try again.")
      }
    }.apply { isRepeats = false; start() }
    request.preparation = worker.submit {
      try {
        if (request.cancelled.get()) return@submit
        val response = translator.translate(frame)
        request.response.set(response)
        if (request.cancelled.get()) {
          response.cancel(true)
        } else {
          response.whenComplete { regions, failure ->
            SwingUtilities.invokeLater { complete(request, regions, failure) }
          }
        }
      } catch (failure: Exception) {
        SwingUtilities.invokeLater { complete(request, null, failure) }
      }
    }
  }

  private fun complete(request: Request, regions: List<TranslationRegion>?, failure: Throwable?) {
    if (active !== request || request.cancelled.get() || closed) return
    if (state().sessionGeneration != request.generation || !state().commands.gameLoaded) {
      invalidate()
      return
    }
    request.deadline?.stop()
    if (failure != null) {
      var cause = failure
      while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
      // Only locally defined messages are suitable for the screen; never display raw HTTP bodies.
      val message = if (cause is TranslationException) cause.message
          else "Could not translate this screen. Check your connection and try again."
      notice(message ?: "Could not translate this screen.")
      show(request.frame, emptyList(), "$message  Esc to return")
    } else {
      val translated = regions.orEmpty()
      show(request.frame, translated,
          if (translated.isEmpty()) "No foreign text found. Esc to return"
          else "English translation · Esc to return")
    }
  }

  /** Explicit dismissal returns only a pause which this feature acquired. */
  fun dismiss(resume: Boolean = true): Boolean {
    requireEdt()
    val request = active ?: return false
    active = null
    request.cancel()
    clear()
    releaseInput()
    if (resume && request.ownsPause && state().commands.gameLoaded &&
        state().sessionGeneration == request.generation) setPaused(false)
    return true
  }

  /** Session replacement/stop owns playback from this point onwards. */
  fun invalidate() {
    dismiss(resume = false)
  }

  override fun close() {
    requireEdt()
    if (closed) return
    invalidate()
    closed = true
    worker.shutdownNow()
    translator.close()
  }

  private fun requireEdt() {
    check(SwingUtilities.isEventDispatchThread()) { "Screen translation must run on the EDT" }
  }
}
