package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.DisplayViewport
import eu.rekawek.coffeegb.swing.io.ScreenTranslationOverlay
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ScreenTranslationTest {

  @Test
  fun `settings store round-trips and resolves Gemini OpenAI and localhost configurations`() {
    val backing = mutableMapOf<String, String>()
    val node =
        object : ScreenTranslationPreferenceNode {
          override fun get(key: String): String? = backing[key]

          override fun put(key: String, value: String) {
            backing[key] = value
          }
        }
    val store = ScreenTranslationPreferencesStore(node)
    assertEquals(ScreenTranslationSettings(), store.load())

    val saved =
        ScreenTranslationSettings(
            provider = ScreenTranslationProvider.OPENAI_COMPATIBLE,
            apiKey = "  sk-test-123  ",
            baseUrl = "https://api.openai.com/v1/",
            model = "gpt-4o",
            targetLanguage = "English",
            pauseWhileTranslating = true,
        )
    store.save(saved)
    val loaded = store.load()
    assertEquals("sk-test-123", loaded.apiKey)
    assertEquals("https://api.openai.com/v1", loaded.baseUrl)
    assertEquals("gpt-4o", loaded.model)
    assertTrue(loaded.pauseWhileTranslating)

    val resolvedOpenAi = loaded.resolve { null }
    assertNotNull(resolvedOpenAi)
    assertEquals(ResolvedTranslationProtocol.OPENAI_COMPATIBLE, resolvedOpenAi.protocol)
    assertEquals("sk-test-123", resolvedOpenAi.apiKey)

    val resolvedGeminiEnv =
        ScreenTranslationSettings().resolve { key ->
          if (key == "GEMINI_API_KEY") "AIzaSyTestKey" else null
        }
    assertNotNull(resolvedGeminiEnv)
    assertEquals(ResolvedTranslationProtocol.GEMINI, resolvedGeminiEnv.protocol)
    assertEquals("AIzaSyTestKey", resolvedGeminiEnv.apiKey)
    assertEquals(ScreenTranslationSettings.DEFAULT_GEMINI_MODEL, resolvedGeminiEnv.model)

    val resolvedOllamaLocal =
        ScreenTranslationSettings(baseUrl = "http://localhost:11434/v1").resolve { null }
    assertNotNull(resolvedOllamaLocal)
    assertEquals(ResolvedTranslationProtocol.OPENAI_COMPATIBLE, resolvedOllamaLocal.protocol)
    assertEquals("", resolvedOllamaLocal.apiKey)

    assertNull(ScreenTranslationSettings().resolve { null })
  }

  @Test
  fun `vision client builds Gemini and OpenAI requests and normalizes region coordinates`() {
    val frame =
        SwingDisplay.CapturedTranslationFrame(
            160,
            144,
            IntArray(160 * 144) { 0x112233 },
            160,
            144,
            IntArray(160 * 144) { 0x112233 },
        )
    var capturedRequest: ScreenTranslationHttpRequest? = null
    val geminiClient =
        HttpScreenTranslationClient(
            transport = { request ->
              capturedRequest = request
              ScreenTranslationHttpResponse(
                  200,
                  """
                  {
                    "candidates": [
                      {
                        "content": {
                          "parts": [
                            {
                              "text": "```json\n{\"regions\":[{\"x\":8,\"y\":96,\"width\":144,\"height\":40,\"original\":\"かいがん\",\"translation\":\"Seashore\"}],\"summary\":\"Seashore\"}\n```"
                            }
                          ]
                        }
                      }
                    ]
                  }
                  """
                      .trimIndent(),
              )
            },
            upscaleFactor = 2,
        )
    val geminiConfig =
        ResolvedScreenTranslationConfig(
            protocol = ResolvedTranslationProtocol.GEMINI,
            apiKey = "AIzaTest",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-2.5-flash",
            targetLanguage = "English",
            pauseWhileTranslating = false,
        )
    val geminiResult = geminiClient.translate(frame, geminiConfig)
    assertNotNull(capturedRequest)
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        capturedRequest!!.uri.toString(),
    )
    assertEquals("AIzaTest", capturedRequest!!.headers["x-goog-api-key"])
    assertEquals(1, geminiResult.regions.size)
    assertEquals("Seashore", geminiResult.regions.single().text())
    assertEquals(8, geminiResult.regions.single().x())
    assertEquals(96, geminiResult.regions.single().y())

    val openAiClient =
        HttpScreenTranslationClient(
            transport = { request ->
              capturedRequest = request
              ScreenTranslationHttpResponse(
                  200,
                  """
                  {
                    "choices": [
                      {
                        "message": {
                          "content": "{\"regions\":[{\"x\":0.05,\"y\":0.5,\"width\":0.9,\"height\":0.25,\"translation\":\"Normalized box\"}]}"
                        }
                      }
                    ]
                  }
                  """
                      .trimIndent(),
              )
            },
            upscaleFactor = 1,
        )
    val openAiConfig =
        ResolvedScreenTranslationConfig(
            protocol = ResolvedTranslationProtocol.OPENAI_COMPATIBLE,
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            targetLanguage = "English",
            pauseWhileTranslating = false,
        )
    val openAiResult = openAiClient.translate(frame, openAiConfig)
    assertEquals(
        "https://api.openai.com/v1/chat/completions",
        capturedRequest!!.uri.toString(),
    )
    assertEquals("Bearer sk-test", capturedRequest!!.headers["Authorization"])
    assertEquals(1, openAiResult.regions.size)
    assertEquals(8, openAiResult.regions.single().x())
    assertEquals(72, openAiResult.regions.single().y())
    assertEquals(144, openAiResult.regions.single().width())
    assertEquals(36, openAiResult.regions.single().height())
  }

  @Test
  fun `overlay ignores blinking cursor pixels but dismisses when dialogue advances and paints cleanly`() {
    val baseRgb = IntArray(160 * 144) { 0x000000 }
    val overlay =
        ScreenTranslationOverlay(
            160,
            144,
            listOf(ScreenTranslationOverlay.Region(8, 96, 144, 40, "Hello, world!")),
            "",
            160,
            144,
            baseRgb,
        )

    // Blinking 8x8 cursor (64 pixels = 0.27% of 23,040) must NOT dismiss the overlay.
    val cursorBlinkRgb = baseRgb.clone()
    for (i in 0 until 64) {
      cursorBlinkRgb[i] = 0xFFFFFF
    }
    assertFalse(overlay.shouldDismissForFrame(160, 144, cursorBlinkRgb))

    // Advancing dialogue (> 5% of screen pixels changed) MUST dismiss the overlay.
    val nextScreenRgb = baseRgb.clone()
    for (i in 0 until 2_000) {
      nextScreenRgb[i] = 0xFFFFFF
    }
    assertTrue(overlay.shouldDismissForFrame(160, 144, nextScreenRgb))

    // Verify painting changes pixels inside the region bounds.
    val canvas = BufferedImage(320, 288, BufferedImage.TYPE_INT_ARGB)
    val g = canvas.createGraphics()
    g.color = Color.BLACK
    g.fillRect(0, 0, 320, 288)
    val viewport = DisplayViewport.calculate(320, 288, 160, 144, 0, DisplayScaleMode.EXPLICIT_2X)
    overlay.paint(g, viewport)
    g.dispose()

    var nonBlackInBottomHalf = 0
    for (y in 192 until 280) {
      for (x in 16 until 304) {
        if ((canvas.getRGB(x, y) and 0x00ffffff) != 0) {
          nonBlackInBottomHalf++
        }
      }
    }
    assertTrue(nonBlackInBottomHalf > 0, "Expected overlay box and text to be painted")
  }

  @Test
  fun `controller translates on demand toggles overlay on second press and cleans up on lifecycle`() {
    val eventBus = EventBusImpl()
    val fakeDisplay = FakeTranslationDisplaySurface()
    val backing = mutableMapOf<String, String>()
    val store =
        ScreenTranslationPreferencesStore(
            object : ScreenTranslationPreferenceNode {
              override fun get(key: String): String? = backing[key]

              override fun put(key: String, value: String) {
                backing[key] = value
              }
            })
    store.save(
        ScreenTranslationSettings(
            provider = ScreenTranslationProvider.GEMINI,
            apiKey = "AIzaTest",
            pauseWhileTranslating = true,
        ))

    var paused = false
    val statuses = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    try {
      onEdt {
        val controller =
            ScreenTranslationController(
                owner = null,
                eventBus = eventBus,
                display = fakeDisplay,
                store = store,
                backend = { _, _ ->
                  ScreenTranslationResult(
                      regions =
                          listOf(
                              ScreenTranslationOverlay.Region(
                                  8, 96, 144, 40, "Welcome to Pallet Town!")),
                  )
                },
                dialogFactory =
                    DesktopDialogFactory {
                      DesktopThemeTokens.capture(DesktopAppearance.LIGHT)
                    },
                isGamePaused = { paused },
                isNetplayActive = { false },
                setGamePaused = { paused = it },
                onStatusMessage = statuses::add,
                onWarningMessage = warnings::add,
                executor = DirectExecutorService(),
            )

        // 1st press: captures frame, pauses game, installs translation overlay.
        controller.translateOrDismiss()
        assertTrue(paused)
        assertNotNull(fakeDisplay.overlay)
        assertEquals(
            "Welcome to Pallet Town!",
            fakeDisplay.overlay!!.regions().single().text(),
        )
        assertTrue(warnings.isEmpty())

        // 2nd press while overlay is visible: dismisses overlay and resumes game.
        controller.translateOrDismiss()
        assertNull(fakeDisplay.overlay)
        assertFalse(paused)
        assertEquals("Translation cleared", statuses.last())

        // Re-translate and verify ROM loading lifecycle clears the overlay.
        controller.translateOrDismiss()
        assertNotNull(fakeDisplay.overlay)
        eventBus.post(Controller.RomLoadingEvent(File("next.gb")))
        assertNull(fakeDisplay.overlay)

        controller.close()
      }
    } finally {
      eventBus.close()
    }
  }

  private class FakeTranslationDisplaySurface : ScreenTranslationDisplaySurface {
    var overlay: ScreenTranslationOverlay? = null
    private var translatingState = false
    val notifications = mutableListOf<String>()
    var frame: SwingDisplay.CapturedTranslationFrame? =
        SwingDisplay.CapturedTranslationFrame(
            160,
            144,
            IntArray(160 * 144) { 0x224466 },
            160,
            144,
            IntArray(160 * 144) { 0x224466 },
        )

    override fun captureTranslationFrame(): SwingDisplay.CapturedTranslationFrame? = frame

    override fun setTranslationOverlay(overlay: ScreenTranslationOverlay?) {
      this.overlay = overlay
    }

    override fun clearTranslationOverlay(): Boolean {
      val had = overlay != null
      overlay = null
      return had
    }

    override fun setTranslating(translating: Boolean) {
      this.translatingState = translating
    }

    override fun isTranslating(): Boolean = translatingState

    override fun showTransientNotification(text: String) {
      notifications += text
    }
  }

  private class DirectExecutorService : AbstractExecutorService() {
    private var terminated = false

    override fun shutdown() {
      terminated = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      terminated = true
      return mutableListOf()
    }

    override fun isShutdown(): Boolean = terminated

    override fun isTerminated(): Boolean = terminated

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

    override fun execute(command: Runnable) {
      command.run()
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
