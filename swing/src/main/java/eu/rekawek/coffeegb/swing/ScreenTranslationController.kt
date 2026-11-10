package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.swing.io.ScreenTranslationOverlay
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.Desktop
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.KeyEvent
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
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
    private val defaults: ScreenTranslationSettings = ScreenTranslationSettings(),
    private val openUri: (URI) -> Unit = ::openBrowserUri,
) : JPanel(GridBagLayout()), DesktopThemeRefreshHook {
  private val providerDrafts = mutableMapOf<ScreenTranslationProvider, Pair<String, String>>()
  private var activeProvider: ScreenTranslationProvider = initial.provider
  private var switchingProvider = false

  val descriptionText =
      javax.swing.JTextArea(
              "Translate non-English dialogue and menus directly on the game screen using Google Gemini, " +
                  "OpenAI, or a local/LAN Ollama vision model.")
          .apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            putClientProperty("html.disable", true)
            getAccessibleContext().accessibleName = "Screen translation overview"
            getAccessibleContext().accessibleDescription = text
          }

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
      JPasswordField(initial.apiKey, 24).apply {
        getAccessibleContext().accessibleName = "Vision API key"
        toolTipText = "API key for Google Gemini or OpenAI"
      }

  val getApiKeyButton =
      JButton("Get API Key…").apply {
        getAccessibleContext().accessibleName = "Get API key in browser"
        addActionListener {
          val provider =
              (providerCombo.selectedItem as? ScreenTranslationProvider)
                  ?: ScreenTranslationProvider.GEMINI
          val url = provider.apiKeyCreationUrl ?: return@addActionListener
          runCatching { openUri(URI.create(url)) }
        }
      }

  val baseUrlField =
      JTextField("", 28).apply {
        getAccessibleContext().accessibleName = "Base URL override"
      }

  val modelField =
      JTextField("", 28).apply {
        getAccessibleContext().accessibleName = "Vision model name"
      }

  val targetLanguageField =
      JTextField(initial.targetLanguage, 20).apply {
        getAccessibleContext().accessibleName = "Target language"
      }

  val pauseCheckBox =
      JCheckBox(
              "Pause emulation while translating or viewing overlay",
              initial.pauseWhileTranslating,
          )
          .apply {
            isOpaque = false
            getAccessibleContext().accessibleName = "Pause emulation while translating"
            minimumSize = preferredSize
          }

  val providerLabel = createRowLabel("Provider:", providerCombo)
  val apiKeyLabel = createRowLabel("API Key:", apiKeyField)
  val baseUrlLabel = createRowLabel("Base URL:", baseUrlField)
  val modelLabel = createRowLabel("Model:", modelField)
  val targetLanguageLabel = createRowLabel("Target Language:", targetLanguageField)

  init {
    border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
    getAccessibleContext().accessibleName = "Screen translation settings"
    seedProviderDrafts(initial)
    val initialDraft = providerDrafts[initial.provider] ?: ("" to "")
    baseUrlField.text = initialDraft.first
    modelField.text = initialDraft.second

    var row = 0
    add(
        descriptionText,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row++
          gridwidth = 3
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(0, 4, 12, 4)
        },
    )
    addRow(row++, providerLabel, providerCombo)
    addApiKeyRow(row++, apiKeyLabel, apiKeyField, getApiKeyButton)
    addRow(row++, baseUrlLabel, baseUrlField)
    addRow(row++, modelLabel, modelField)
    addRow(row++, targetLanguageLabel, targetLanguageField)
    add(
        pauseCheckBox,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row++
          gridwidth = 2
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(4, 4, 4, 4)
        },
    )
    add(
        JPanel().apply { isOpaque = false },
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          gridwidth = 3
          weightx = 1.0
          weighty = 1.0
          fill = GridBagConstraints.BOTH
        },
    )

    updateProviderVisibilityAndHints(initial.provider)

    providerCombo.addActionListener {
      if (switchingProvider) return@addActionListener
      val nextProvider =
          (providerCombo.selectedItem as? ScreenTranslationProvider)
              ?: ScreenTranslationProvider.GEMINI
      if (nextProvider != activeProvider) {
        providerDrafts[activeProvider] = baseUrlField.text.trim() to modelField.text.trim()
        activeProvider = nextProvider
        val nextDraft = providerDrafts[nextProvider] ?: ("" to "")
        switchingProvider = true
        try {
          baseUrlField.text = nextDraft.first
          modelField.text = nextDraft.second
        } finally {
          switchingProvider = false
        }
      }
      updateProviderVisibilityAndHints(nextProvider)
    }
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    descriptionText.background = tokens.surface
    descriptionText.foreground = tokens.secondaryText
  }

  fun restoreDefaults() {
    switchingProvider = true
    try {
      providerDrafts.clear()
      seedProviderDrafts(defaults)
      activeProvider = defaults.provider
      providerCombo.selectedItem = defaults.provider
      apiKeyField.text = defaults.apiKey
      baseUrlField.text = defaults.baseUrl
      modelField.text = defaults.model
      targetLanguageField.text = defaults.targetLanguage
      pauseCheckBox.isSelected = defaults.pauseWhileTranslating
      updateProviderVisibilityAndHints(defaults.provider)
    } finally {
      switchingProvider = false
    }
  }

  fun draftFingerprint(): List<Any?> =
      listOf(
          providerCombo.selectedItem,
          String(apiKeyField.password),
          baseUrlField.text,
          modelField.text,
          targetLanguageField.text,
          pauseCheckBox.isSelected,
      )

  fun toSettings(): ScreenTranslationSettings {
    val provider =
        (providerCombo.selectedItem as? ScreenTranslationProvider)
            ?: ScreenTranslationProvider.GEMINI
    return ScreenTranslationSettings(
            provider = provider,
            apiKey = if (provider.requiresApiKey) String(apiKeyField.password) else "",
            baseUrl = if (provider.supportsCustomBaseUrl) baseUrlField.text else "",
            model = if (provider.supportsModelSelection) modelField.text else "",
            targetLanguage = targetLanguageField.text,
            pauseWhileTranslating = pauseCheckBox.isSelected,
        )
        .sanitized()
  }

  private fun seedProviderDrafts(settings: ScreenTranslationSettings) {
    val isOllamaUrl = ScreenTranslationSettings.isOllamaEndpoint(settings.baseUrl)
    val isOllamaModel = ScreenTranslationSettings.isOllamaModel(settings.model)
    if (settings.provider == ScreenTranslationProvider.OLLAMA || isOllamaUrl || isOllamaModel) {
      providerDrafts[ScreenTranslationProvider.OLLAMA] = settings.baseUrl to settings.model
    }
    if (settings.provider != ScreenTranslationProvider.OLLAMA) {
      val nonOllamaUrl = settings.baseUrl.takeUnless { isOllamaUrl }.orEmpty()
      val nonOllamaModel = settings.model.takeUnless { isOllamaModel }.orEmpty()
      providerDrafts[settings.provider] = nonOllamaUrl to nonOllamaModel
    }
  }

  private fun updateProviderVisibilityAndHints(provider: ScreenTranslationProvider) {
    apiKeyLabel.isVisible = provider.requiresApiKey
    apiKeyField.isVisible = provider.requiresApiKey
    getApiKeyButton.isVisible = provider.apiKeyCreationUrl != null
    getApiKeyButton.toolTipText =
        provider.apiKeyCreationUrl?.let { "Open ${provider.displayName} API key page ($it)" }
    baseUrlLabel.isVisible = provider.supportsCustomBaseUrl
    baseUrlField.isVisible = provider.supportsCustomBaseUrl
    modelLabel.isVisible = provider.supportsModelSelection
    modelField.isVisible = provider.supportsModelSelection

    when (provider) {
      ScreenTranslationProvider.GEMINI -> {
        apiKeyField.toolTipText = "Google Gemini API key"
        modelField.toolTipText =
            "Optional model override (defaults to ${ScreenTranslationSettings.DEFAULT_GEMINI_MODEL})"
      }
      ScreenTranslationProvider.OPENAI_COMPATIBLE -> {
        apiKeyField.toolTipText = "OpenAI API key"
        modelField.toolTipText =
            "Optional model override (defaults to ${ScreenTranslationSettings.DEFAULT_OPENAI_MODEL})"
      }
      ScreenTranslationProvider.OLLAMA -> {
        baseUrlField.toolTipText =
            "Ollama endpoint URL (defaults to ${ScreenTranslationSettings.DEFAULT_OLLAMA_BASE_URL})"
        modelField.toolTipText =
            "Ollama vision model (defaults to ${ScreenTranslationSettings.DEFAULT_OLLAMA_MODEL})"
      }
    }
    revalidate()
    repaint()
  }

  private fun createRowLabel(labelText: String, component: java.awt.Component): JLabel =
      JLabel(labelText).apply {
        putClientProperty("html.disable", true)
        labelFor = component
      }

  private fun addRow(row: Int, label: JLabel, component: java.awt.Component) {
    add(
        label,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          gridwidth = 1
          weightx = 0.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.NONE
          insets = Insets(4, 4, 4, 4)
        },
    )
    add(
        component,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          gridwidth = 2
          weightx = 1.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        },
    )
  }

  private fun addApiKeyRow(
      row: Int,
      label: JLabel,
      field: java.awt.Component,
      button: java.awt.Component,
  ) {
    add(
        label,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          gridwidth = 1
          weightx = 0.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.NONE
          insets = Insets(4, 4, 4, 4)
        },
    )
    add(
        field,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          gridwidth = 1
          weightx = 1.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        },
    )
    add(
        button,
        GridBagConstraints().apply {
          gridx = 2
          gridy = row
          gridwidth = 1
          weightx = 0.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_END
          fill = GridBagConstraints.NONE
          insets = Insets(4, 4, 4, 4)
        },
    )
  }

  companion object {
    private fun openBrowserUri(uri: URI) {
      if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
          desktop.browse(uri)
        }
      }
    }
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
    private val openTranslationPreferences: (() -> Unit)? = null,
    private val promptForSettingsWhenUnconfigured:
        (ScreenTranslationSettings) -> ScreenTranslationSettings? =
        { current -> showSettingsModal(owner, dialogFactory, current) },
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
    var resolved = settings.resolve()
    if (resolved == null) {
      if (openTranslationPreferences != null) {
        openTranslationPreferences.invoke()
        settings = store.load()
      } else {
        val updated = promptForSettingsWhenUnconfigured(settings) ?: return
        store.save(updated)
        settings = updated
      }
      resolved = settings.resolve()
      if (resolved == null) {
        onWarningMessage(
            "Screen translation requires a Gemini/OpenAI API key or an Ollama endpoint.")
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
    onStatusMessage("Translating screen with ${activeConfig.model}…")

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
                    display.showTransientNotification(
                        "No non-${activeConfig.targetLanguage} text detected")
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

  fun currentSettings(): ScreenTranslationSettings = store.load()

  fun saveSettings(settings: ScreenTranslationSettings) {
    store.save(settings)
  }

  fun showSettingsDialog() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Screen translation settings must open on the EDT"
    }
    if (closed) return
    if (openTranslationPreferences != null) {
      openTranslationPreferences.invoke()
      return
    }
    val current = store.load()
    val updated = showSettingsModal(owner, dialogFactory, current) ?: return
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

    internal fun isLocalOllamaListening(): Boolean =
        runCatching {
              Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 11434), 100)
                true
              }
            }
            .getOrDefault(false)

    internal fun showSettingsModal(
        owner: Window?,
        dialogFactory: DesktopDialogFactory,
        current: ScreenTranslationSettings,
    ): ScreenTranslationSettings? {
      val parentWindow = owner ?: return null
      val formPanel = ScreenTranslationSettingsPanel(current)
      val decision =
          dialogFactory.showForm(
              owner = parentWindow,
              spec =
                  DesktopFormSpec(
                      title = "Screen Translation Settings",
                      heading = "Configure Vision Screen Translation",
                      description =
                          "Translate non-English dialogue and menus directly on the game screen using Google Gemini, OpenAI, or a local Ollama endpoint.",
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
                      resizable = false,
                  ),
              content = formPanel,
          )
      return if (decision == TranslationSettingsDecision.SAVE) formPanel.toSettings() else null
    }
  }
}
