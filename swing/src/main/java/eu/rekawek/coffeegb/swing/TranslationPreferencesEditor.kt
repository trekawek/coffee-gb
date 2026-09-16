package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Draft-only API key editor. Environment credentials never enter the editor. */
internal class TranslationPreferencesEditor private constructor(
    initial: ApplicationSettings.Translation,
    private val defaults: ApplicationSettings.Translation,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()), DesktopThemeRefreshHook {
  constructor(
      initial: ApplicationSettings.Translation,
      defaults: ApplicationSettings.Translation = ApplicationSettings.Translation(),
  ) : this(initial, defaults, requireEdt())

  private var editingEnabled = true

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
      JTextArea(
              "Used to translate game screenshots into English with OpenAI.\n\n" +
                  "A saved key takes precedence over OPENAI_API_KEY. Leave this field empty " +
                  "to use the environment variable.\n\n" +
                  "When saved, the key is stored locally in Coffee GB settings without encryption.")
          .apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 7
            columns = 32
            putClientProperty("html.disable", true)
            getAccessibleContext().accessibleName = "Screen translation API key guidance"
            getAccessibleContext().accessibleDescription = text
          }

  init {
    getAccessibleContext().accessibleName = "Translation preferences"
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    maskApiKey()
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
    createRows()
  }

  internal fun validatedTranslation(): ApplicationSettings.Translation {
    requireEdt()
    return try {
      ApplicationSettings.Translation(apiKey = readKey().trim()).also { keyError.text = " " }
    } catch (_: IllegalArgumentException) {
      keyError.text = KEY_ERROR
      throw PreferenceEditorValidationException(KEY_ERROR, apiKeyField)
    }
  }

  /** Compare even invalid drafts without exposing credentials in diagnostic output. */
  internal fun draftFingerprint(): Any {
    requireEdt()
    return KeyDraft(readKey())
  }

  internal fun restoreDefaults() {
    requireEdt()
    maskApiKey()
    apiKeyField.text = defaults.apiKey
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
    apiKeyField.isEnabled = enabled
    apiKeyField.isEditable = enabled
    showApiKey.isEnabled = enabled
    clearApiKey.isEnabled = enabled && apiKeyField.document.length > 0
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
    clearApiKey.isEnabled = editingEnabled && apiKeyField.document.length > 0
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
    add(label, constraints)
    constraints.gridy = 1
    add(
        JPanel(BorderLayout(8, 0)).apply {
          add(apiKeyField, BorderLayout.CENTER)
          add(clearApiKey, BorderLayout.LINE_END)
        },
        constraints,
    )
    constraints.gridy = 2
    add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { add(showApiKey) }, constraints)
    constraints.gridy = 3
    add(keyError, constraints)
    constraints.gridy = 4
    add(guidance, constraints)
    constraints.gridy = 5
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
  }

  private data class KeyDraft(private val value: String) {
    override fun toString(): String = "KeyDraft([redacted])"
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
