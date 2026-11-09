package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.swing.io.ScreenTranslationOverlay
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.KeyEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

internal interface ScreenTranslationDisplaySurface {
  fun captureTranslationFrame(): SwingDisplay.CapturedTranslationFrame?

  fun setTranslationOverlay(overlay: ScreenTranslationOverlay?)

  fun clearTranslationOverlay(): Boolean

  fun setTranslating(translating: Boolean)

  fun isTranslating(): Boolean

  fun showTransientNotification(text: String)
}

internal class SwingDisplayTranslationSurface(
    private val display: SwingDisplay,
) : ScreenTranslationDisplaySurface {
  override fun captureTranslationFrame(): SwingDisplay.CapturedTranslationFrame? =
      display.captureTranslationFrame()

  override fun setTranslationOverlay(overlay: ScreenTranslationOverlay?) {
    display.setTranslationOverlay(overlay)
  }

  override fun clearTranslationOverlay(): Boolean = display.clearTranslationOverlay()

  override fun setTranslating(translating: Boolean) {
    display.setTranslating(translating)
  }

  override fun isTranslating(): Boolean = display.isTranslating()

  override fun showTransientNotification(text: String) {
    display.showTransientNotification(text)
  }
}

private enum class TranslationSettingsDecision {
  SAVE,
  CANCEL,
}

internal class ScreenTranslationSettingsPanel(
    initial: ScreenTranslationSettings,
    env: (String) -> String? = System::getenv,
) : JPanel(GridBagLayout()) {
  val providerCombo =
      JComboBox(ScreenTranslationProvider.entries.toTypedArray()).apply {
        selectedItem = initial.provider
        getAccessibleContext().accessibleName = "Translation provider"
        renderer =
            object : DefaultListCellRenderer() {
              override fun getListCellRendererComponent(
                  list: JList<*>?,
                  value: Any?,
                  index: Int,
                  isSelected: Boolean,
                  cellHasFocus: Boolean,
              ) =
                  super.getListCellRendererComponent(
                          list,
                          (value as? ScreenTranslationProvider)?.displayName ?: value,
                          index,
                          isSelected,
                          cellHasFocus,
                      )
            }
      }

  val apiKeyField =
      JPasswordField(initial.apiKey, 28).apply {
        getAccessibleContext().accessibleName = "Vision API key"
        val hasEnvKey =
            listOf("COFFEEGB_TRANSLATE_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "OPENAI_API_KEY")
                .any { !env(it)?.trim().isNullOrEmpty() }
        toolTipText =
            if (hasEnvKey) {
              "Leave blank to use the API key detected in your environment variables"
            } else {
              "API key for Gemini or your OpenAI-compatible provider (leave blank for local Ollama)"
            }
      }

  val baseUrlField =
      JTextField(initial.baseUrl, 28).apply {
        getAccessibleContext().accessibleName = "Base URL override"
        toolTipText =
            "Optional custom endpoint (e.g., https://api.openai.com/v1 or http://localhost:11434/v1)"
      }

  val modelField =
      JTextField(initial.model, 28).apply {
        getAccessibleContext().accessibleName = "Vision model name"
        toolTipText =
            "Optional model override (defaults to ${ScreenTranslationSettings.DEFAULT_GEMINI_MODEL} or ${ScreenTranslationSettings.DEFAULT_OPENAI_MODEL})"
      }

  val targetLanguageField =
      JTextField(initial.targetLanguage, 20).apply {
        getAccessibleContext().accessibleName = "Target language"
      }

  val pauseCheckBox =
      JCheckBox("Pause emulation while translating or viewing overlay", initial.pauseWhileTranslating)
          .apply {
            getAccessibleContext().accessibleName = "Pause emulation while translating"
          }

  init {
    getAccessibleContext().accessibleName = "Screen translation settings"
    val envNotice =
        listOf("COFFEEGB_TRANSLATE_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "OPENAI_API_KEY")
            .firstOrNull { !env(it)?.trim().isNullOrEmpty() }

    var row = 0
    addLabeledRow(row++, "Provider:", providerCombo)
    addLabeledRow(row++, "API Key:", apiKeyField)
    if (envNotice != null) {
      val hint =
          JLabel("Environment key detected ($envNotice). Leave API Key blank to use it.").apply {
            putClientProperty("html.disable", true)
          }
      add(
          hint,
          GridBagConstraints().apply {
            gridx = 1
            gridy = row++
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 0, 8, 0)
          },
      )
    }
    addLabeledRow(
        row++,
        "Base URL (optional):",
        baseUrlField,
    )
    addLabeledRow(
        row++,
        "Model (optional):",
        modelField,
    )
    addLabeledRow(
        row++,
        "Target Language:",
        targetLanguageField,
    )
    add(
        pauseCheckBox,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          gridwidth = 2
          anchor = GridBagConstraints.WEST
          insets = Insets(6, 0, 0, 0)
        },
    )
  }

  fun toSettings(): ScreenTranslationSettings =
      ScreenTranslationSettings(
              provider =
                  (providerCombo.selectedItem as? ScreenTranslationProvider)
                      ?: ScreenTranslationProvider.AUTO,
              apiKey = String(apiKeyField.password),
              baseUrl = baseUrlField.text,
              model = modelField.text,
              targetLanguage = targetLanguageField.text,
              pauseWhileTranslating = pauseCheckBox.isSelected,
          )
          .sanitized()

  private fun addLabeledRow(row: Int, labelText: String, component: java.awt.Component) {
    val label =
        JLabel(labelText).apply {
          putClientProperty("html.disable", true)
          labelFor = component
        }
    add(
        label,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          anchor = GridBagConstraints.WEST
          insets = Insets(0, 0, 8, 12)
        },
    )
    add(
        component,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(0, 0, 8, 0)
        },
    )
  }
}

internal class ScreenTranslationController(
    private val owner: Window?,
    private val eventBus: EventBus,
    private val display: ScreenTranslationDisplaySurface,
    private val store: ScreenTranslationPreferencesStore = ScreenTranslationPreferencesStore(),
    private val backend: ScreenTranslationBackend = HttpScreenTranslationClient(),
    private val dialogFactory: DesktopDialogFactory,
    private val isGamePaused: () -> Boolean = { false },
    private val isNetplayActive: () -> Boolean = { false },
    private val setGamePaused: (Boolean) -> Unit = {},
    private val onStatusMessage: (String) -> Unit = {},
    private val onWarningMessage: (String) -> Unit = {},
    private val env: (String) -> String? = System::getenv,
    private val promptForSettingsWhenUnconfigured: (ScreenTranslationSettings) -> ScreenTranslationSettings? =
        { current -> showSettingsModal(owner, dialogFactory, current, env) },
    private val executor: ExecutorService = defaultTranslationExecutor(),
) : AutoCloseable {

  private val requestGeneration = AtomicLong()
  private var activeFuture: Future<*>? = null
  private var pausedByTranslation = false
  @Volatile private var closed = false

  init {
    eventBus.register<Controller.RomLoadingEvent> {
      dispatchSwingMutation { resetForLifecycle() }
    }
    eventBus.register<Controller.EmulationStoppedEvent> {
      dispatchSwingMutation { resetForLifecycle() }
    }
    eventBus.register<ControllerOwnershipChangingEvent> {
      dispatchSwingMutation { resetForLifecycle() }
    }
    eventBus.register<LogicalPlayerButtonPressEvent> {
      dispatchSwingMutation { dismissOnButtonPress() }
    }
    eventBus.register<ButtonPressEvent> {
      dispatchSwingMutation { dismissOnButtonPress() }
    }
  }

  fun translateOrDismiss() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Screen translation must be triggered on the EDT"
    }
    if (closed) return

    // Pressing Translate while an overlay is visible dismisses it immediately.
    if (display.clearTranslationOverlay()) {
      resumeIfPausedByTranslation()
      onStatusMessage("Translation cleared")
      return
    }

    // Pressing Translate while a request is in flight cancels the active request.
    if (display.isTranslating() || activeFuture?.isDone == false) {
      cancelInFlight()
      resumeIfPausedByTranslation()
      onStatusMessage("Translation cancelled")
      return
    }

    val frame = display.captureTranslationFrame()
    if (frame == null) {
      display.showTransientNotification("No game screen to translate")
      return
    }

    var settings = store.load()
    var resolved = settings.resolve(env)
    if (resolved == null) {
      val updated = promptForSettingsWhenUnconfigured(settings) ?: return
      store.save(updated)
      settings = updated
      resolved = settings.resolve(env)
      if (resolved == null) {
        onWarningMessage(
            "Screen translation requires a Gemini/OpenAI API key or a localhost endpoint.")
        display.showTransientNotification("API key required for translation")
        return
      }
    }

    val activeConfig = resolved
    if (activeConfig.pauseWhileTranslating && !isGamePaused() && !isNetplayActive()) {
      pausedByTranslation = true
      setGamePaused(true)
    }

    val generation = requestGeneration.incrementAndGet()
    display.setTranslating(true)
    onStatusMessage("Translating screen…")

    val future =
        executor.submit {
          val outcome = runCatching { backend.translate(frame, activeConfig) }
          dispatchSwingMutation {
            if (closed || generation != requestGeneration.get()) {
              return@dispatchSwingMutation
            }
            activeFuture = null
            display.setTranslating(false)
            outcome.fold(
                onSuccess = { result ->
                  if (result.isEmpty) {
                    resumeIfPausedByTranslation()
                    display.showTransientNotification("No non-${activeConfig.targetLanguage} text detected")
                    onStatusMessage("No non-${activeConfig.targetLanguage} text detected on screen")
                  } else {
                    val overlay = result.toOverlay(frame)
                    display.setTranslationOverlay(overlay)
                    val regionCount = overlay.regions().size
                    onStatusMessage(
                        if (regionCount > 0) {
                          "Translated $regionCount text ${if (regionCount == 1) "region" else "regions"} (press any Game Boy button or Translate again to clear)"
                        } else {
                          "Screen translated (press any Game Boy button or Translate again to clear)"
                        })
                  }
                },
                onFailure = { error ->
                  resumeIfPausedByTranslation()
                  val reason = error.message?.takeIf { it.isNotBlank() } ?: "Request failed"
                  display.showTransientNotification("Translation failed")
                  onWarningMessage("Screen translation failed: $reason")
                },
            )
          }
        }
    if (display.isTranslating() && !future.isDone) {
      activeFuture = future
    }
  }

  fun showSettingsDialog() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Screen translation settings must open on the EDT"
    }
    if (closed) return
    val current = store.load()
    val updated = showSettingsModal(owner, dialogFactory, current, env) ?: return
    store.save(updated)
    onStatusMessage("Screen translation settings saved")
  }

  private fun dismissOnButtonPress() {
    if (closed) return
    if (display.clearTranslationOverlay()) {
      resumeIfPausedByTranslation()
      onStatusMessage("Translation cleared")
    }
  }

  private fun resetForLifecycle() {
    cancelInFlight()
    display.clearTranslationOverlay()
    pausedByTranslation = false
  }

  private fun cancelInFlight() {
    requestGeneration.incrementAndGet()
    activeFuture?.cancel(true)
    activeFuture = null
    display.setTranslating(false)
  }

  private fun resumeIfPausedByTranslation() {
    if (pausedByTranslation) {
      pausedByTranslation = false
      if (!isNetplayActive()) {
        setGamePaused(false)
      }
    }
  }

  override fun close() {
    closed = true
    requestGeneration.incrementAndGet()
    activeFuture?.cancel(true)
    activeFuture = null
    executor.shutdownNow()
    runCatching { executor.awaitTermination(200, TimeUnit.MILLISECONDS) }
  }

  companion object {
    private fun defaultTranslationExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-screen-translation").apply { isDaemon = true }
        }

    internal fun showSettingsModal(
        owner: Window?,
        dialogFactory: DesktopDialogFactory,
        current: ScreenTranslationSettings,
        env: (String) -> String?,
    ): ScreenTranslationSettings? {
      val parentWindow = owner ?: return null
      val formPanel = ScreenTranslationSettingsPanel(current, env)
      val decision =
          dialogFactory.showForm(
              owner = parentWindow,
              spec =
                  DesktopFormSpec(
                      title = "Screen Translation Settings",
                      heading = "Configure Vision Screen Translation",
                      description =
                          "Translate non-English dialogue and menus directly on the game screen using Google Gemini, an OpenAI-compatible Vision API, or a local VLM endpoint (such as Ollama).",
                      contentAccessibleName = "Screen translation settings form",
                      buttons =
                          DesktopDialogButtons(
                              primary =
                                  DesktopDialogAction(
                                      label = "Save",
                                      result = TranslationSettingsDecision.SAVE,
                                      mnemonic = KeyEvent.VK_S,
                                      accessibleDescription = "Save screen translation settings",
                                  ),
                              cancel =
                                  DesktopDialogAction(
                                      label = "Cancel",
                                      result = TranslationSettingsDecision.CANCEL,
                                  ),
                              defaultButton = DesktopDialogDefaultButton.PRIMARY,
                          ),
                  ),
              content = formPanel,
          )
      return if (decision == TranslationSettingsDecision.SAVE) formPanel.toSettings() else null
    }
  }
}
