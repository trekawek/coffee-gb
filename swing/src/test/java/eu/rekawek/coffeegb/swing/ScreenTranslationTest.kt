package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.DisplayViewport
import eu.rekawek.coffeegb.swing.io.ScreenTranslationOverlay
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Test

class ScreenTranslationTest {

  @Test
  fun `settings store round-trips and resolves Gemini OpenAI and Ollama without env vars`() {
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

    val resolvedOpenAi = loaded.resolve()
    assertNotNull(resolvedOpenAi)
    assertEquals(ResolvedTranslationProtocol.OPENAI_COMPATIBLE, resolvedOpenAi.protocol)
    assertEquals("sk-test-123", resolvedOpenAi.apiKey)

    val resolvedGemini =
        ScreenTranslationSettings(
                provider = ScreenTranslationProvider.GEMINI,
                apiKey = "AIzaSyTestKey",
            )
            .resolve()
    assertNotNull(resolvedGemini)
    assertEquals(ResolvedTranslationProtocol.GEMINI, resolvedGemini.protocol)
    assertEquals("AIzaSyTestKey", resolvedGemini.apiKey)
    assertEquals(ScreenTranslationSettings.DEFAULT_GEMINI_MODEL, resolvedGemini.model)

    val resolvedOllamaLan =
        ScreenTranslationSettings(
                provider = ScreenTranslationProvider.OLLAMA,
                baseUrl = "http://gallifrey:11434/v1",
            )
            .resolve()
    assertNotNull(resolvedOllamaLan)
    assertEquals(ResolvedTranslationProtocol.OPENAI_COMPATIBLE, resolvedOllamaLan.protocol)
    assertEquals("http://gallifrey:11434/v1", resolvedOllamaLan.baseUrl)
    assertEquals(ScreenTranslationSettings.DEFAULT_OLLAMA_MODEL, resolvedOllamaLan.model)
    assertEquals("", resolvedOllamaLan.apiKey)

    // Switching provider to OpenAI while a previous Ollama URL/model was stored must use OpenAI.
    val resolvedSwitchedToOpenAi =
        ScreenTranslationSettings(
                provider = ScreenTranslationProvider.OPENAI_COMPATIBLE,
                apiKey = "sk-openai-key",
                baseUrl = "http://gallifrey:11434/v1",
                model = "qwen2.5vl:7b",
            )
            .resolve()
    assertNotNull(resolvedSwitchedToOpenAi)
    assertEquals(ResolvedTranslationProtocol.OPENAI_COMPATIBLE, resolvedSwitchedToOpenAi.protocol)
    assertEquals(ScreenTranslationSettings.DEFAULT_OPENAI_BASE_URL, resolvedSwitchedToOpenAi.baseUrl)
    assertEquals(ScreenTranslationSettings.DEFAULT_OPENAI_MODEL, resolvedSwitchedToOpenAi.model)
    assertEquals("sk-openai-key", resolvedSwitchedToOpenAi.apiKey)

    assertNull(ScreenTranslationSettings().resolve())
  }

  @Test
  fun `vision client builds Gemini and OpenAI requests and snaps region coordinates to pixel bands`() {
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
            model = ScreenTranslationSettings.DEFAULT_GEMINI_MODEL,
            targetLanguage = "English",
            pauseWhileTranslating = false,
        )
    val geminiResult = geminiClient.translate(frame, geminiConfig)
    assertNotNull(capturedRequest)
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
        capturedRequest!!.uri.toString(),
    )
    assertEquals("AIzaTest", capturedRequest!!.headers["x-goog-api-key"])
    assertEquals(1, geminiResult.regions.size)
    assertEquals("Seashore", geminiResult.regions.single().text())
    assertEquals(8, geminiResult.regions.single().x())
    assertEquals(96, geminiResult.regions.single().y())

    // Verify compressed/misaligned VLM coordinates are snapped to the actual detected text bands
    // and stacked menu options share aligned horizontal bounds.
    val bands =
        listOf(
            DetectedTextBand(x1 = 1, y1 = 16, x2 = 156, y2 = 47),
            DetectedTextBand(x1 = 56, y1 = 64, x2 = 103, y2 = 70),
            DetectedTextBand(x1 = 64, y1 = 80, x2 = 110, y2 = 86),
            DetectedTextBand(x1 = 8, y1 = 112, x2 = 151, y2 = 126),
        )
    val misalignedModelResponse =
        """{"regions":[{"bbox_2d":[250,50,750,100],"original":"連珠倶楽部","translation":"Renju Club"},{"bbox_2d":[350,150,650,200],"original":"たいきょく","translation":"Play Match"},{"bbox_2d":[350,200,650,250],"original":"つめれんじゅ","translation":"Puzzle Mode"}]}"""
    val aligned =
        HttpScreenTranslationClient.parseTranslationPayload(
            rawModelText = misalignedModelResponse,
            sourceWidth = 160,
            sourceHeight = 144,
            candidateBands = bands,
        )
    assertEquals(3, aligned.regions.size)
    // Title banner snapped to Band 0 (y=16..47)
    assertEquals(1, aligned.regions[0].x())
    assertEquals(16, aligned.regions[0].y())
    assertEquals(156, aligned.regions[0].width())
    assertEquals(32, aligned.regions[0].height())
    // Menu options snapped to Band 1 (y=64) and Band 2 (y=80) with unified left edge and width
    assertEquals(56, aligned.regions[1].x())
    assertEquals(64, aligned.regions[1].y())
    assertEquals(55, aligned.regions[1].width())
    assertEquals(7, aligned.regions[1].height())
    assertEquals(56, aligned.regions[2].x())
    assertEquals(80, aligned.regions[2].y())
    assertEquals(55, aligned.regions[2].width())
    assertEquals(7, aligned.regions[2].height())

    // Verify 2D band detection separates a right-side vertical menu (7 items) from a noisy 4-color
    // overworld tilemap on the top-left and a 2-line description box on the bottom-left.
    val multiColumnRgb = IntArray(160 * 144) { 0xF8F8F8 }
    val overworldColors = intArrayOf(0x88E050, 0x389028, 0x185010, 0xD8F878)
    for (y in 0 until 96) {
      for (x in 0 until 96) {
        multiColumnRgb[y * 160 + x] = overworldColors[(x * 3 + y * 5) and 3]
      }
    }
    // Framed menu box on the right (x=98..156, y=6..114)
    for (y in 6..114) {
      multiColumnRgb[y * 160 + 98] = 0x000000
      multiColumnRgb[y * 160 + 100] = 0x000000
      multiColumnRgb[y * 160 + 156] = 0x000000
    }
    val rightMenuYs = intArrayOf(16, 31, 46, 60, 75, 90, 104)
    for (baseY in rightMenuYs) {
      for (y in baseY..(baseY + 5)) {
        for (x in 110..142) {
          if (((x + y) and 1) == 0) {
            multiColumnRgb[y * 160 + x] = 0x000000
          }
        }
      }
    }
    // Two bottom-left description lines (x=8..72 at y=104..109 and x=8..86 at y=119..124)
    for (y in 104..109) {
      for (x in 8..72) {
        if (((x + y) and 1) == 0) {
          multiColumnRgb[y * 160 + x] = 0x000000
        }
      }
    }
    for (y in 119..124) {
      for (x in 8..86) {
        if (((x + y) and 1) == 0) {
          multiColumnRgb[y * 160 + x] = 0x000000
        }
      }
    }
    val detectedMultiCol =
        HttpScreenTranslationClient.detectCandidateTextBands(multiColumnRgb, 160, 144)
    assertEquals(9, detectedMultiCol.size)
    assertEquals(7, detectedMultiCol.count { it.x1 >= 100 })
    assertEquals(2, detectedMultiCol.count { it.x1 < 30 })

    // Verify 2x2 graphical menu buttons (including a 4-color highlighted button) are separated
    // into 4 individual candidate bands while full-bleed top/bottom border strips are ignored.
    val gridMenuRgb = IntArray(160 * 144) { 0x00A8F8 }
    // Full-bleed top curtain strip (y=0..6, x=0..159)
    for (y in 0..6) {
      for (x in 0..159) {
        gridMenuRgb[y * 160 + x] = if ((x and 1) == 0) 0x580000 else 0xF8A8A8
      }
    }
    val buttonSpecs =
        listOf(
            intArrayOf(14, 68, 35, 62, 0),
            intArrayOf(86, 142, 35, 64, 1), // 4-color selected button
            intArrayOf(14, 68, 74, 102, 0),
            intArrayOf(86, 142, 78, 102, 0),
        )
    for (spec in buttonSpecs) {
      val (bx1, bx2, by1, by2, highlighted) = spec
      for (y in by1..by2) {
        for (x in bx1..bx2) {
          val mod = ((x / 2) * 3 + (y / 3) * 2) % 5
          gridMenuRgb[y * 160 + x] =
              if (highlighted == 1) {
                when (mod) {
                  0, 1 -> 0xF81818
                  2 -> 0xF8F800
                  3 -> 0x000000
                  else -> 0x00A8F8
                }
              } else {
                if (mod < 2) 0x107800 else 0xB89800
              }
        }
      }
    }
    val detectedGrid = HttpScreenTranslationClient.detectCandidateTextBands(gridMenuRgb, 160, 144)
    assertEquals(4, detectedGrid.size)
    assertEquals(2, detectedGrid.count { it.x1 < 40 })
    assertEquals(2, detectedGrid.count { it.x1 > 70 })

    // Verify a 3-line bottom RPG dialogue box with dakuten rows (2px above lines 1 and 3) expands
    // to cover the dakuten and merges into one full-sentence dialogue region.
    val dialogueRgb = IntArray(160 * 144) { 0xF8E0B0 }
    for (baseY in intArrayOf(96, 112, 128)) {
      for (y in baseY..(baseY + 7)) {
        for (x in 8..108) {
          if (((x + y) and 1) == 0) {
            dialogueRgb[y * 160 + x] = 0x201850
          }
        }
      }
    }
    // Dakuten marks 2..3 rows above line 1 (y=93..94) and line 3 (y=125..126)
    for (y in 93..94) {
      dialogueRgb[y * 160 + 85] = 0x201850
      dialogueRgb[y * 160 + 87] = 0x201850
      dialogueRgb[y * 160 + 109] = 0x201850
      dialogueRgb[y * 160 + 111] = 0x201850
    }
    for (y in 125..126) {
      dialogueRgb[y * 160 + 69] = 0x201850
      dialogueRgb[y * 160 + 71] = 0x201850
    }
    val detectedDialogue =
        HttpScreenTranslationClient.detectCandidateTextBands(dialogueRgb, 160, 144)
    assertEquals(1, detectedDialogue.size)
    assertEquals(92, detectedDialogue.single().y1)
    assertEquals(135, detectedDialogue.single().y2)
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

    // Palette-flashing text inside a translated region and background animations outside the region
    // must NOT dismiss the overlay while the glyph edges remain intact, but clearing the text MUST
    // dismiss it.
    val glyphScreenRgb = IntArray(160 * 144) { 0x00A8F8 }
    for (y in 40..64) {
      for (x in 84..140) {
        if (((x + y) and 1) == 0) {
          glyphScreenRgb[y * 160 + x] = 0xF81818
        }
      }
    }
    val glyphOverlay =
        ScreenTranslationOverlay(
            160,
            144,
            listOf(ScreenTranslationOverlay.Region(84, 40, 57, 25, "Attack Mode", "アタックモード")),
            "",
            160,
            144,
            glyphScreenRgb,
        )
    val paletteFlashedRgb = glyphScreenRgb.clone()
    for (y in 40..64) {
      for (x in 84..140) {
        if (((x + y) and 1) == 0) {
          paletteFlashedRgb[y * 160 + x] = 0x107800 // Red -> Green palette flash
        }
      }
    }
    for (y in 0..30) {
      for (x in 0..159) {
        paletteFlashedRgb[y * 160 + x] = 0xFFFFFF // Scrolling clouds outside region
      }
    }
    assertFalse(glyphOverlay.shouldDismissForFrame(160, 144, paletteFlashedRgb))
    val clearedMenuRgb = IntArray(160 * 144) { 0x00A8F8 }
    assertTrue(glyphOverlay.shouldDismissForFrame(160, 144, clearedMenuRgb))

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
  fun `settings panel shows and hides API Key Base URL and Model by provider and controller manages lifecycle`() {
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
        val openedUris = mutableListOf<String>()
        val panel =
            ScreenTranslationSettingsPanel(
                ScreenTranslationSettings(
                    provider = ScreenTranslationProvider.OLLAMA,
                    apiKey = "sk-openai",
                    baseUrl = "http://gallifrey:11434/v1",
                    model = "qwen2.5vl:7b",
                    targetLanguage = "English",
                ),
                openUri = { uri -> openedUris += uri.toString() },
            )
        assertEquals("Screen translation settings", panel.accessibleContext.accessibleName)
        // When Ollama is selected: API Key + Get API Key button are hidden; Base URL and Model are visible.
        assertFalse(panel.apiKeyLabel.isVisible)
        assertFalse(panel.apiKeyField.isVisible)
        assertFalse(panel.getApiKeyButton.isVisible)
        assertTrue(panel.baseUrlLabel.isVisible)
        assertTrue(panel.baseUrlField.isVisible)
        assertTrue(panel.modelLabel.isVisible)
        assertTrue(panel.modelField.isVisible)
        assertEquals("http://gallifrey:11434/v1", panel.baseUrlField.text)
        assertEquals("qwen2.5vl:7b", panel.modelField.text)

        // Switching provider from Ollama to OpenAI shows API Key + Get API Key + Model and hides Base URL.
        panel.providerCombo.selectedItem = ScreenTranslationProvider.OPENAI_COMPATIBLE
        assertTrue(panel.apiKeyLabel.isVisible)
        assertTrue(panel.apiKeyField.isVisible)
        assertTrue(panel.getApiKeyButton.isVisible)
        panel.getApiKeyButton.doClick()
        assertEquals(listOf("https://platform.openai.com/api-keys"), openedUris)
        assertFalse(panel.baseUrlLabel.isVisible)
        assertFalse(panel.baseUrlField.isVisible)
        assertTrue(panel.modelLabel.isVisible)
        assertTrue(panel.modelField.isVisible)
        assertEquals("", panel.baseUrlField.text)
        assertEquals("", panel.modelField.text)
        val switchedSettings = panel.toSettings()
        assertEquals(ScreenTranslationProvider.OPENAI_COMPATIBLE, switchedSettings.provider)
        assertEquals("sk-openai", switchedSettings.apiKey)
        assertEquals("", switchedSettings.baseUrl)
        assertEquals("", switchedSettings.model)

        // Switching to Gemini shows API Key + Get API Key + Model and hides Base URL.
        panel.providerCombo.selectedItem = ScreenTranslationProvider.GEMINI
        assertTrue(panel.apiKeyLabel.isVisible)
        assertTrue(panel.apiKeyField.isVisible)
        assertTrue(panel.getApiKeyButton.isVisible)
        panel.getApiKeyButton.doClick()
        assertEquals(
            listOf("https://platform.openai.com/api-keys", "https://aistudio.google.com/apikey"),
            openedUris,
        )
        assertFalse(panel.baseUrlLabel.isVisible)
        assertFalse(panel.baseUrlField.isVisible)
        assertTrue(panel.modelLabel.isVisible)
        assertTrue(panel.modelField.isVisible)

        // Switching back to Ollama restores the Ollama draft URL/model and hides API Key.
        panel.providerCombo.selectedItem = ScreenTranslationProvider.OLLAMA
        assertFalse(panel.apiKeyLabel.isVisible)
        assertFalse(panel.apiKeyField.isVisible)
        assertFalse(panel.getApiKeyButton.isVisible)
        assertTrue(panel.baseUrlLabel.isVisible)
        assertTrue(panel.baseUrlField.isVisible)
        assertEquals("http://gallifrey:11434/v1", panel.baseUrlField.text)
        assertEquals("qwen2.5vl:7b", panel.modelField.text)

        // Verify ScreenTranslationSettingsPanel is hosted in PreferencesPanel under TRANSLATION.
        val preferencesPanel =
            PreferencesPanel(
                eu.rekawek.coffeegb.controller.properties.ApplicationSettings(),
                initialCategory = PreferencesCategory.TRANSLATION,
                initialTranslation =
                    ScreenTranslationSettings(
                        provider = ScreenTranslationProvider.OLLAMA,
                        baseUrl = "http://gallifrey:11434/v1",
                        model = "qwen2.5vl:7b",
                    ),
            )
        assertEquals(PreferencesCategory.TRANSLATION, preferencesPanel.categories.selectedCategory)
        assertFalse(preferencesPanel.isDirty())
        preferencesPanel.translationEditor.providerCombo.selectedItem =
            ScreenTranslationProvider.OPENAI_COMPATIBLE
        preferencesPanel.translationEditor.apiKeyField.text = "sk-live"
        assertTrue(preferencesPanel.isDirty())
        val edit = preferencesPanel.validatedEdit()
        assertNotNull(edit.screenTranslation)
        assertEquals(
            ScreenTranslationProvider.OPENAI_COMPATIBLE,
            edit.screenTranslation!!.provider,
        )
        assertEquals("sk-live", edit.screenTranslation!!.apiKey)
        assertEquals("", edit.screenTranslation!!.baseUrl)
        assertEquals("", edit.screenTranslation!!.model)

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

        // Re-translate and verify pressing any Game Boy button dismisses the overlay and resumes.
        controller.translateOrDismiss()
        assertTrue(paused)
        assertNotNull(fakeDisplay.overlay)
        eventBus.post(LogicalPlayerButtonPressEvent(0, Button.A))
        assertNull(fakeDisplay.overlay)
        assertFalse(paused)

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

  @Test
  fun `live OpenAI gpt_4o_mini translates Japanese Game Boy screen and aligns labels`() {
    val apiKeyFile = File("/home/newton/dev/openai-api")
    Assume.assumeTrue("OpenAI key file not present", apiKeyFile.isFile)
    val apiKey = Files.readString(apiKeyFile.toPath()).trim()
    Assume.assumeTrue("OpenAI key is blank", apiKey.isNotEmpty())

    val frame = captureJapaneseRomOrSyntheticFrame()
    val config =
        checkNotNull(
            ScreenTranslationSettings(
                    provider = ScreenTranslationProvider.OPENAI_COMPATIBLE,
                    apiKey = apiKey,
                )
                .resolve())
    assertEquals(ScreenTranslationSettings.DEFAULT_OPENAI_BASE_URL, config.baseUrl)
    assertEquals(ScreenTranslationSettings.DEFAULT_OPENAI_MODEL, config.model)

    val client = HttpScreenTranslationClient()
    val result = client.translate(frame, config)
    assertFalse(result.isEmpty, "Expected gpt-4o-mini to return a translation for Japanese screen")
    assertTrue(result.regions.isNotEmpty(), "Expected located regions from gpt-4o-mini")

    val overlay = result.toOverlay(frame)
    val canvas = BufferedImage(480, 432, BufferedImage.TYPE_INT_ARGB)
    val g = canvas.createGraphics()
    val viewport = DisplayViewport.calculate(480, 432, 160, 144, 0, DisplayScaleMode.EXPLICIT_2X)
    overlay.paint(g, viewport)
    g.dispose()
  }

  @Test
  fun `live local Ollama qwen2_5vl translates Japanese Game Boy screen and renders overlay`() {
    Assume.assumeTrue(
        "Local Ollama server is not listening on 127.0.0.1:11434",
        ScreenTranslationController.isLocalOllamaListening(),
    )

    val frame = captureJapaneseRomOrSyntheticFrame()
    val config =
        checkNotNull(
            ScreenTranslationSettings(provider = ScreenTranslationProvider.OLLAMA).resolve())
    assertEquals(ScreenTranslationSettings.DEFAULT_OLLAMA_BASE_URL, config.baseUrl)
    assertEquals(ScreenTranslationSettings.DEFAULT_OLLAMA_MODEL, config.model)

    val client = HttpScreenTranslationClient(upscaleFactor = 1)
    val result = client.translate(frame, config)
    assertFalse(result.isEmpty, "Expected qwen2.5vl:7b to return a translation for Japanese screen")

    val overlay = result.toOverlay(frame)
    assertFalse(overlay.isEmpty)
    val combinedText =
        if (overlay.regions().isNotEmpty()) {
          overlay.regions().joinToString("\n") { it.text() }
        } else {
          overlay.fallbackText()
        }
    assertTrue(
        combinedText.any { it in 'A'..'Z' || it in 'a'..'z' },
        "Expected English letters in translated text, got: $combinedText",
    )

    val canvas = BufferedImage(480, 432, BufferedImage.TYPE_INT_ARGB)
    val g = canvas.createGraphics()
    val viewport = DisplayViewport.calculate(480, 432, 160, 144, 0, DisplayScaleMode.EXPLICIT_2X)
    overlay.paint(g, viewport)
    g.dispose()
  }

  private fun captureJapaneseRomOrSyntheticFrame(): SwingDisplay.CapturedTranslationFrame {
    val cachedShot = File("/tmp/renju-check/renju_gb_f380.png")
    if (cachedShot.isFile) {
      val img = ImageIO.read(cachedShot)
      if (img != null && img.width == 160 && img.height == 144) {
        val rgb = img.getRGB(0, 0, 160, 144, null, 0, 160)
        return SwingDisplay.CapturedTranslationFrame(160, 144, rgb, 160, 144, rgb)
      }
    }
    val romFile =
        File(
            "/mnt/nas/emu/roms/gb/_Int_release/Japan/P/Pocket Monsters - Aka (Japan) (Rev A) (SGB Enhanced).gb")
    if (romFile.isFile) {
      val config = Gameboy.GameboyConfiguration(romFile).setSupportBatterySave(false)
      val gb = config.build()
      val eventBus = EventBusImpl(null, null, false)
      val lastFrame = AtomicReference<IntArray>()
      eventBus.register(
          { e ->
            val px = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
            e.toRgb(px, false)
            lastFrame.set(px)
          },
          Display.DmgFrameReadyEvent::class.java,
      )
      gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null)
      for (frameIdx in 0..1000) {
        if (frameIdx == 600 || frameIdx == 850) {
          eventBus.post(ButtonPressEvent(Button.START))
        }
        if (frameIdx == 620 || frameIdx == 870) {
          eventBus.post(ButtonReleaseEvent(Button.START))
        }
        var ticks = 0
        while (!gb.tick() && ticks++ < 200_000) {
          // advance one frame
        }
      }
      val rgb = checkNotNull(lastFrame.get()) { "Expected a rendered frame from ${romFile.name}" }
      return SwingDisplay.CapturedTranslationFrame(
          Display.DISPLAY_WIDTH,
          Display.DISPLAY_HEIGHT,
          rgb,
          Display.DISPLAY_WIDTH,
          Display.DISPLAY_HEIGHT,
          rgb,
      )
    }

    val image = BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color(0xE0, 0xF8, 0xD0)
    g.fillRect(0, 0, 160, 144)
    g.color = Color(0x08, 0x18, 0x20)
    g.drawRect(4, 92, 152, 46)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    g.drawString("はじめまして！", 12, 112)
    g.drawString("せかいへ ようこそ", 12, 130)
    g.dispose()
    val rgb = image.getRGB(0, 0, 160, 144, null, 0, 160)
    return SwingDisplay.CapturedTranslationFrame(160, 144, rgb, 160, 144, rgb)
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
