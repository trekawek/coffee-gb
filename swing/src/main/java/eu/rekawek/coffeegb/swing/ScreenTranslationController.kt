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
import javax.swing.JTabbedPane
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
  private val initialClean = initial.sanitized()

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
        selectedItem = initialClean.provider
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

  val geminiApiKeyField =
      JPasswordField(initialClean.geminiApiKey, 24).apply {
        getAccessibleContext().accessibleName = "Google Gemini API key"
        toolTipText = "Google Gemini API key"
      }

  val geminiGetApiKeyButton =
      JButton("Get API Key…").apply {
        getAccessibleContext().accessibleName = "Get Google Gemini API key in browser"
        toolTipText =
            "Open Google Gemini API key page (${ScreenTranslationProvider.GEMINI.apiKeyCreationUrl})"
        addActionListener {
          val url = ScreenTranslationProvider.GEMINI.apiKeyCreationUrl ?: return@addActionListener
          runCatching { openUri(URI.create(url)) }
        }
      }

  val geminiModelField =
      JTextField(initialClean.geminiModel, 28).apply {
        getAccessibleContext().accessibleName = "Google Gemini model name"
        toolTipText =
            "Optional model override (defaults to ${ScreenTranslationSettings.DEFAULT_GEMINI_MODEL})"
      }

  val openAiApiKeyField =
      JPasswordField(initialClean.openAiApiKey, 24).apply {
        getAccessibleContext().accessibleName = "OpenAI API key"
        toolTipText = "OpenAI API key"
      }

  val openAiGetApiKeyButton =
      JButton("Get API Key…").apply {
        getAccessibleContext().accessibleName = "Get OpenAI API key in browser"
        toolTipText =
            "Open OpenAI API key page (${ScreenTranslationProvider.OPENAI_COMPATIBLE.apiKeyCreationUrl})"
        addActionListener {
          val url =
              ScreenTranslationProvider.OPENAI_COMPATIBLE.apiKeyCreationUrl
                  ?: return@addActionListener
          runCatching { openUri(URI.create(url)) }
        }
      }

  val openAiModelField =
      JTextField(initialClean.openAiModel, 28).apply {
        getAccessibleContext().accessibleName = "OpenAI model name"
        toolTipText =
            "Optional model override (defaults to ${ScreenTranslationSettings.DEFAULT_OPENAI_MODEL})"
      }

  val ollamaBaseUrlField =
      JTextField(initialClean.ollamaBaseUrl, 28).apply {
        getAccessibleContext().accessibleName = "Ollama base URL"
        toolTipText =
            "Ollama endpoint URL (defaults to ${ScreenTranslationSettings.DEFAULT_OLLAMA_BASE_URL})"
      }

  val ollamaModelField =
      JTextField(initialClean.ollamaModel, 28).apply {
        getAccessibleContext().accessibleName = "Ollama vision model name"
        toolTipText =
            "Ollama vision model (defaults to ${ScreenTranslationSettings.DEFAULT_OLLAMA_MODEL})"
      }

  val providerTabs =
      JTabbedPane().apply {
        getAccessibleContext().accessibleName = "Provider configuration tabs"
      }

  val targetLanguageField =
      JTextField(initialClean.targetLanguage, 20).apply {
        getAccessibleContext().accessibleName = "Target language"
      }

  val pauseCheckBox =
      JCheckBox(
              "Pause emulation while translating or viewing overlay",
              initialClean.pauseWhileTranslating,
          )
          .apply {
            isOpaque = false
            getAccessibleContext().accessibleName = "Pause emulation while translating"
            minimumSize = preferredSize
          }

  val providerLabel = createRowLabel("Provider:", providerCombo)
  val geminiApiKeyLabel = createRowLabel("API Key:", geminiApiKeyField)
  val geminiModelLabel = createRowLabel("Model:", geminiModelField)
  val openAiApiKeyLabel = createRowLabel("API Key:", openAiApiKeyField)
  val openAiModelLabel = createRowLabel("Model:", openAiModelField)
  val ollamaBaseUrlLabel = createRowLabel("Base URL:", ollamaBaseUrlField)
  val ollamaModelLabel = createRowLabel("Model:", ollamaModelField)
  val targetLanguageLabel = createRowLabel("Target Language:", targetLanguageField)

  val apiKeyField: JPasswordField
    get() =
        when (selectedProvider()) {
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> openAiApiKeyField
          ScreenTranslationProvider.GEMINI,
          ScreenTranslationProvider.OLLAMA -> geminiApiKeyField
        }

  val getApiKeyButton: JButton
    get() =
        when (selectedProvider()) {
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> openAiGetApiKeyButton
          ScreenTranslationProvider.GEMINI,
          ScreenTranslationProvider.OLLAMA -> geminiGetApiKeyButton
        }

  val baseUrlField: JTextField
    get() = ollamaBaseUrlField

  val modelField: JTextField
    get() =
        when (selectedProvider()) {
          ScreenTranslationProvider.GEMINI -> geminiModelField
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> openAiModelField
          ScreenTranslationProvider.OLLAMA -> ollamaModelField
        }

  init {
    border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
    getAccessibleContext().accessibleName = "Screen translation settings"

    providerTabs.addTab(
        ScreenTranslationProvider.GEMINI.displayName,
        createApiKeyProviderTabPanel(
            apiKeyLabel = geminiApiKeyLabel,
            apiKeyField = geminiApiKeyField,
            getApiKeyButton = geminiGetApiKeyButton,
            modelLabel = geminiModelLabel,
            modelField = geminiModelField,
        ),
    )
    providerTabs.setToolTipTextAt(
        ScreenTranslationProvider.GEMINI.ordinal,
        "Configure Google Gemini API key and model.",
    )
    providerTabs.addTab(
        ScreenTranslationProvider.OPENAI_COMPATIBLE.displayName,
        createApiKeyProviderTabPanel(
            apiKeyLabel = openAiApiKeyLabel,
            apiKeyField = openAiApiKeyField,
            getApiKeyButton = openAiGetApiKeyButton,
            modelLabel = openAiModelLabel,
            modelField = openAiModelField,
        ),
    )
    providerTabs.setToolTipTextAt(
        ScreenTranslationProvider.OPENAI_COMPATIBLE.ordinal,
        "Configure OpenAI API key and model.",
    )
    providerTabs.addTab(
        ScreenTranslationProvider.OLLAMA.displayName,
        createOllamaTabPanel(),
    )
    providerTabs.setToolTipTextAt(
        ScreenTranslationProvider.OLLAMA.ordinal,
        "Configure local or LAN Ollama endpoint URL and vision model.",
    )
    providerTabs.selectedIndex = initialClean.provider.ordinal

    var row = 0
    add(
        descriptionText,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row++
          gridwidth = 2
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(0, 4, 12, 4)
        },
    )
    addRow(this, row++, providerLabel, providerCombo)
    add(
        providerTabs,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row++
          gridwidth = 2
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(6, 4, 8, 4)
        },
    )
    addRow(this, row++, targetLanguageLabel, targetLanguageField)
    add(
        pauseCheckBox,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row++
          gridwidth = 1
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
          gridwidth = 2
          weightx = 1.0
          weighty = 1.0
          fill = GridBagConstraints.BOTH
        },
    )

    providerCombo.addActionListener {
      val nextProvider = selectedProvider()
      if (providerTabs.selectedIndex != nextProvider.ordinal) {
        providerTabs.selectedIndex = nextProvider.ordinal
      }
    }
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    descriptionText.background = tokens.surface
    descriptionText.foreground = tokens.secondaryText
  }

  fun restoreDefaults() {
    val cleanDefaults = defaults.sanitized()
    providerCombo.selectedItem = cleanDefaults.provider
    providerTabs.selectedIndex = cleanDefaults.provider.ordinal
    geminiApiKeyField.text = cleanDefaults.geminiApiKey
    geminiModelField.text = cleanDefaults.geminiModel
    openAiApiKeyField.text = cleanDefaults.openAiApiKey
    openAiModelField.text = cleanDefaults.openAiModel
    ollamaBaseUrlField.text = cleanDefaults.ollamaBaseUrl
    ollamaModelField.text = cleanDefaults.ollamaModel
    targetLanguageField.text = cleanDefaults.targetLanguage
    pauseCheckBox.isSelected = cleanDefaults.pauseWhileTranslating
  }

  fun draftFingerprint(): List<Any?> =
      listOf(
          providerCombo.selectedItem,
          String(geminiApiKeyField.password),
          geminiModelField.text,
          String(openAiApiKeyField.password),
          openAiModelField.text,
          ollamaBaseUrlField.text,
          ollamaModelField.text,
          targetLanguageField.text,
          pauseCheckBox.isSelected,
      )

  fun toSettings(): ScreenTranslationSettings {
    val provider = selectedProvider()
    return ScreenTranslationSettings(
            provider = provider,
            geminiApiKey = String(geminiApiKeyField.password),
            geminiModel = geminiModelField.text,
            openAiApiKey = String(openAiApiKeyField.password),
            openAiModel = openAiModelField.text,
            ollamaBaseUrl = ollamaBaseUrlField.text,
            ollamaModel = ollamaModelField.text,
            targetLanguage = targetLanguageField.text,
            pauseWhileTranslating = pauseCheckBox.isSelected,
        )
        .sanitized()
  }

  private fun selectedProvider(): ScreenTranslationProvider =
      (providerCombo.selectedItem as? ScreenTranslationProvider)
          ?: ScreenTranslationProvider.GEMINI

  private fun createApiKeyProviderTabPanel(
      apiKeyLabel: JLabel,
      apiKeyField: JPasswordField,
      getApiKeyButton: JButton,
      modelLabel: JLabel,
      modelField: JTextField,
  ): JPanel =
      JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(8, 6, 8, 6)
        addApiKeyRow(this, 0, apiKeyLabel, apiKeyField, getApiKeyButton)
        addRow(this, 1, modelLabel, modelField, fieldGridWidth = 2)
        add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
              gridx = 0
              gridy = 2
              gridwidth = 3
              weightx = 1.0
              weighty = 1.0
              fill = GridBagConstraints.BOTH
            },
        )
      }

  private fun createOllamaTabPanel(): JPanel =
      JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(8, 6, 8, 6)
        addRow(this, 0, ollamaBaseUrlLabel, ollamaBaseUrlField)
        addRow(this, 1, ollamaModelLabel, ollamaModelField)
        add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
              gridx = 0
              gridy = 2
              gridwidth = 2
              weightx = 1.0
              weighty = 1.0
              fill = GridBagConstraints.BOTH
            },
        )
      }

  private fun createRowLabel(labelText: String, component: java.awt.Component): JLabel =
      JLabel(labelText).apply {
        putClientProperty("html.disable", true)
        labelFor = component
      }

  private fun addRow(
      container: JPanel,
      row: Int,
      label: JLabel,
      component: java.awt.Component,
      fieldGridWidth: Int = 1,
  ) {
    container.add(
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
    container.add(
        component,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          gridwidth = fieldGridWidth
          weightx = 1.0
          weighty = 0.0
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        },
    )
  }

  private fun addApiKeyRow(
      container: JPanel,
      row: Int,
      label: JLabel,
      field: java.awt.Component,
      button: java.awt.Component,
  ) {
    container.add(
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
    container.add(
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
    container.add(
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
                  val shortReason =
                      reason
                          .removePrefix("Translation service returned ")
                          .take(56)
                  display.showTransientNotification("Translation failed: $shortReason")
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
