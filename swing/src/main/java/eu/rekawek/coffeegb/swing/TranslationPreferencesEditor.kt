package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.TranslationProvider
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Draft-only provider and API key editor. Environment credentials never enter the editor. */
internal class TranslationPreferencesEditor private constructor(
    initial: ApplicationSettings.Translation,
    private val defaults: ApplicationSettings.Translation,
    private val isMac: Boolean,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()), DesktopThemeRefreshHook {
  constructor(
      initial: ApplicationSettings.Translation,
      defaults: ApplicationSettings.Translation = ApplicationSettings.Translation(),
      isMac: Boolean = System.getProperty("os.name", "").contains("mac", ignoreCase = true),
  ) : this(initial, defaults, isMac, requireEdt())

  internal data class ProviderOption(val provider: TranslationProvider, val label: String) {
    override fun toString(): String = label
  }

  private var editingEnabled = true

  internal val provider =
      JComboBox(
              arrayOf(
                  ProviderOption(TranslationProvider.AUTOMATIC, "Automatic"),
                  ProviderOption(TranslationProvider.APPLE_LOCAL, "Apple (on-device)"),
                  ProviderOption(TranslationProvider.OPENAI, "OpenAI (online)"),
              ))
          .apply {
            selectedItem = (0 until itemCount).map(::getItemAt)
                .first { it.provider == initial.provider }
            getAccessibleContext().accessibleName = "Translation provider"
            getAccessibleContext().accessibleDescription =
                "Automatic uses Apple on a Mac and OpenAI on other platforms."
          }

  internal val apiKeyField =
      JPasswordField(initial.apiKey, 32).apply {
        getAccessibleContext().accessibleName = "OpenAI API key"
        getAccessibleContext().accessibleDescription =
            "API key for translating game screens. Leave empty to use OPENAI_API_KEY."
      }
  internal val showApiKey =
      JCheckBox("Show API key").apply {
        mnemonic = KeyEvent.VK_H
        getAccessibleContext().accessibleName = "Show OpenAI API key"
      }
  internal val clearApiKey =
      JButton("Clear").apply {
        getAccessibleContext().accessibleName = "Clear saved OpenAI API key"
        toolTipText = "Clear the saved key and use OPENAI_API_KEY when available."
      }
  internal val keyError =
      JLabel(" ").apply {
        foreground = desktopValidationErrorColor()
        getAccessibleContext().accessibleName = "OpenAI API key error"
      }
  internal val guidance =
      JTextArea()
          .apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 7
            columns = 32
            putClientProperty("html.disable", true)
            getAccessibleContext().accessibleName = "Screen translation guidance"
          }

  init {
    getAccessibleContext().accessibleName = "Translation preferences"
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    maskApiKey()
    provider.addActionListener {
      maskApiKey()
      updateProviderControls()
    }
    showApiKey.addItemListener {
      apiKeyField.echoChar = if (showApiKey.isSelected) '\u0000' else maskedEchoChar()
    }
    clearApiKey.addActionListener {
      maskApiKey()
      apiKeyField.text = ""
      apiKeyField.requestFocusInWindow()
    }
    apiKeyField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = keyEdited()

          override fun removeUpdate(event: DocumentEvent) = keyEdited()

          override fun changedUpdate(event: DocumentEvent) = keyEdited()
        })
    keyEdited()
    updateProviderControls()
    createRows()
  }

  internal fun validatedTranslation(): ApplicationSettings.Translation {
    requireEdt()
    return try {
      ApplicationSettings.Translation(apiKey = readKey().trim(), provider = selectedProvider())
          .also { keyError.text = " " }
    } catch (_: IllegalArgumentException) {
      keyError.text = KEY_ERROR
      throw PreferenceEditorValidationException(KEY_ERROR, apiKeyField)
    }
  }

  /** Compare even invalid drafts without exposing credentials in diagnostic output. */
  internal fun draftFingerprint(): Any {
    requireEdt()
    return TranslationDraft(selectedProvider(), readKey())
  }

  internal fun restoreDefaults() {
    requireEdt()
    maskApiKey()
    apiKeyField.text = defaults.apiKey
    provider.selectedItem = (0 until provider.itemCount).map(provider::getItemAt)
        .first { it.provider == defaults.provider }
    keyError.text = " "
  }

  internal fun maskApiKey() {
    requireEdt()
    showApiKey.isSelected = false
    apiKeyField.echoChar = maskedEchoChar()
  }

  internal fun setEditingEnabled(enabled: Boolean) {
    requireEdt()
    editingEnabled = enabled
    if (!enabled) maskApiKey()
    updateProviderControls()
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    maskApiKey()
    keyError.foreground = tokens.danger
    guidance.background = tokens.surface
    guidance.foreground = tokens.secondaryText
  }

  override fun removeNotify() {
    maskApiKey()
    super.removeNotify()
  }

  private fun readKey(): String {
    val password = apiKeyField.password
    return try {
      String(password)
    } finally {
      password.fill('\u0000')
    }
  }

  private fun keyEdited() {
    keyError.text = " "
    clearApiKey.isEnabled = editingEnabled && usesOpenAi() && apiKeyField.document.length > 0
  }

  private fun selectedProvider(): TranslationProvider =
      (provider.selectedItem as ProviderOption).provider

  private fun usesOpenAi(): Boolean =
      selectedProvider() == TranslationProvider.OPENAI ||
          (selectedProvider() == TranslationProvider.AUTOMATIC && !isMac)

  private fun updateProviderControls() {
    val cloud = usesOpenAi()
    provider.isEnabled = editingEnabled
    apiKeyField.isEnabled = editingEnabled && cloud
    apiKeyField.isEditable = editingEnabled && cloud
    showApiKey.isEnabled = editingEnabled && cloud
    clearApiKey.isEnabled = editingEnabled && cloud && apiKeyField.document.length > 0
    guidance.text = if (cloud) {
      (if (selectedProvider() == TranslationProvider.AUTOMATIC)
          "Automatic uses OpenAI on this platform.\n\n" else "") +
          "Game screenshots are sent to OpenAI for English translation.\n\n" +
          "A saved key takes precedence over OPENAI_API_KEY. Leave this field empty " +
          "to use the environment variable.\n\n" +
          "When saved, the key is stored locally in Coffee GB settings without encryption."
    } else {
      (if (isMac) "Translate game screens into English on your Mac with macOS 15 or later."
      else "Apple on-device translation requires a Mac running macOS 15 or later.") +
          " No account or API key is needed.\n\n" +
          "Apple may ask to download language support the first time. After setup, " +
          "translation works offline and screenshots stay on your Mac.\n\n" +
          "Any saved OpenAI key is kept for use if you select OpenAI later."
    }
    guidance.getAccessibleContext().accessibleDescription = guidance.text
  }

  private fun createRows() {
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
          weightx = 1.0
          gridx = 0
        }
    val label =
        JLabel("OpenAI API key:").apply {
          displayedMnemonic = KeyEvent.VK_K
          labelFor = apiKeyField
        }
    add(JLabel("Translation provider:").apply { labelFor = provider }, constraints)
    constraints.gridy = 1
    add(provider, constraints)
    constraints.gridy = 2
    add(label, constraints)
    constraints.gridy = 3
    add(
        JPanel(BorderLayout(8, 0)).apply {
          add(apiKeyField, BorderLayout.CENTER)
          add(clearApiKey, BorderLayout.LINE_END)
        },
        constraints,
    )
    constraints.gridy = 4
    add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { add(showApiKey) }, constraints)
    constraints.gridy = 5
    add(keyError, constraints)
    constraints.gridy = 6
    add(guidance, constraints)
    constraints.gridy = 7
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
  }

  private data class TranslationDraft(
      private val provider: TranslationProvider,
      private val key: String,
  ) {
    override fun toString(): String = "TranslationDraft(provider=$provider, key=[redacted])"
  }

  private companion object {
    const val KEY_ERROR =
        "Enter a valid API key (up to 4096 printable characters without spaces), or leave it empty."

    fun maskedEchoChar(): Char =
        (UIManager.get("PasswordField.echoChar") as? Char)?.takeUnless { it == '\u0000' }
            ?: '\u2022'

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Translation preferences must be constructed and accessed on the EDT"
      }
    }
  }
}
