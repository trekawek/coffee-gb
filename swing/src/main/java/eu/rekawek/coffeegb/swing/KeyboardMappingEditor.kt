package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Draft-only keyboard binding editor for the desktop Preferences dialog.
 *
 * The editor never persists or applies a mapping. Call [validatedDraft] from an Apply handler after
 * the rest of the Preferences fields have passed validation.
 */
class KeyboardMappingEditor private constructor(
    initialInput: ApplicationSettings.Input,
    defaultInput: ApplicationSettings.Input,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(BorderLayout(8, 8)) {

  constructor(
      initialInput: ApplicationSettings.Input,
      defaultInput: ApplicationSettings.Input = ApplicationSettings.Input.defaults(),
  ) : this(initialInput, defaultInput, requireEventDispatchThread())

  data class Binding(val player: Int, val button: Button) {
    init {
      require(player in 0..3) { "Logical player index must be in 0..3" }
    }

    val displayName: String
      get() = "Player ${player + 1} ${button.keyboardEditorDisplayName()}"
  }

  sealed interface EditResult {
    data class Applied(
        val binding: Binding,
        val key: ApplicationSettings.KeyboardKey?,
    ) : EditResult

    data class Conflict(
        val binding: Binding,
        val key: ApplicationSettings.KeyboardKey,
        val existingBinding: Binding,
    ) : EditResult

    data class Reserved(
        val binding: Binding,
        val keyCode: Int,
    ) : EditResult

    data class Unsupported(
        val binding: Binding,
        val keyCode: Int,
    ) : EditResult
  }

  private data class Capture(val binding: Binding, val allowReserved: Boolean)

  private data class Row(
      val currentBinding: JLabel,
      val capture: JButton,
      val captureDialogKey: JButton,
  )

  private val keyboard =
      initialInput.keyboard.toMutableMap().also { initialInput.toPlayerMapping() }
  private val gamepads = initialInput.gamepads.toMap()
  private val defaultKeyboard =
      defaultInput.keyboard.toMap().also { defaultInput.toPlayerMapping() }
  private val rows = linkedMapOf<Binding, Row>()
  private val tabs = JTabbedPane()
  private val status = JLabel("Choose Capture, then press one key.")

  private var activeCapture: Capture? = null
  private var pendingModifier: Int? = null
  private var suppressedKeyCode: Int? = null
  private var dispatcherInstalled = false
  private var captureWindow: Window? = null

  private val focusOwnerListener =
      PropertyChangeListener { event: PropertyChangeEvent ->
        val next = event.newValue as? Component
        if (
            (activeCapture != null || suppressedKeyCode != null) &&
                (next == null || (next !== this && !SwingUtilities.isDescendingFrom(next, this)))) {
          cancelCapture()
        }
      }

  private val windowFocusListener =
      object : WindowAdapter() {
        override fun windowLostFocus(event: WindowEvent) {
          cancelCapture()
        }
      }

  private val captureDispatcher =
      KeyEventDispatcher { event ->
        val source: Component = event.component ?: return@KeyEventDispatcher false
        if (
            suppressedKeyCode == null &&
                source !== this &&
                !SwingUtilities.isDescendingFrom(source, this)) {
          false
        } else {
          handleCaptureKey(event)
        }
      }

  init {
    requireEventDispatchThread()
    getAccessibleContext().accessibleName = "Keyboard mappings"
    getAccessibleContext().accessibleDescription =
        "Keyboard controls for four players. Changes remain a draft until Preferences is applied."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

    tabs.accessibleContext.accessibleName = "Keyboard mappings by player"
    tabs.accessibleContext.accessibleDescription =
        "Use Left and Right to select a player, then Tab through that player's controls."
    repeat(PLAYER_COUNT) { player ->
      tabs.addTab("Player ${player + 1}", createPlayerPanel(player))
      tabs.setToolTipTextAt(player, "Edit keyboard bindings for Player ${player + 1}")
    }
    tabs.addChangeListener { cancelCapture() }
    add(tabs, BorderLayout.CENTER)

    val footer = JPanel(BorderLayout(8, 0))
    status.accessibleContext.accessibleName = "Keyboard mapping status"
    status.accessibleContext.accessibleDescription = status.text
    footer.add(status, BorderLayout.CENTER)
    footer.add(
        JButton("Restore keyboard defaults").apply {
          accessibleContext.accessibleName = "Restore all keyboard defaults"
          accessibleContext.accessibleDescription =
              "Restore the default keyboard mapping for every player."
          addActionListener { resetToDefaults() }
        },
        BorderLayout.EAST,
    )
    add(footer, BorderLayout.SOUTH)
    refreshRows()
  }

  /**
   * Returns a new validated input draft and preserves the initial gamepad choices unchanged.
   *
   * Conflict edits are rejected immediately, so this method normally cannot fail. The explicit
   * validation is retained as a boundary check for a Preferences Apply handler.
   */
  fun validatedDraft(): ApplicationSettings.Input {
    requireEventDispatchThread()
    return ApplicationSettings.Input(keyboard.toMap(), gamepads).also { it.toPlayerMapping() }
  }

  fun currentBinding(
      player: Int,
      button: Button,
  ): ApplicationSettings.KeyboardKey? {
    requireEventDispatchThread()
    return keyboard[ControllerProperties.PlayerButton(player, button)]
  }

  /**
   * Attempts to assign one Java AWT key code.
   *
   * Tab, Escape, and Enter are rejected by default because they navigate or operate the enclosing
   * dialog. A caller must pass [allowReserved] only following a deliberate "Capture dialog key"
   * action.
   */
  fun editBinding(
      player: Int,
      button: Button,
      keyCode: Int,
      allowReserved: Boolean = false,
  ): EditResult {
    requireEventDispatchThread()
    val binding = Binding(player, button)
    if (keyCode in UNAVAILABLE_GAMEPLAY_KEYS) {
      return EditResult.Reserved(binding, keyCode).also {
        showStatus(
            when (keyCode) {
              KeyEvent.VK_BACK_SPACE -> "Backspace is reserved for Rewind."
              KeyEvent.VK_TAB -> "Tab is reserved for focus navigation."
              else -> "That key is reserved by the desktop frontend."
            })
      }
    }
    if (!allowReserved && keyCode in RESERVED_DIALOG_KEYS) {
      return EditResult.Reserved(binding, keyCode).also {
        showStatus(
            "${KeyEvent.getKeyText(keyCode)} is a dialog key. Use Capture dialog key to assign it.")
      }
    }

    val key =
        try {
          ApplicationSettings.KeyboardKey.fromKeyCode(keyCode)
        } catch (_: IllegalArgumentException) {
          return EditResult.Unsupported(binding, keyCode).also {
            showStatus("That key cannot be stored as a keyboard binding.")
          }
        }
    val target = binding.toPlayerButton()
    val conflict =
        keyboard.entries.firstOrNull { (candidate, candidateKey) ->
          candidate != target && candidateKey.code == key.code
        }
    if (conflict != null) {
      val existing = conflict.key.toBinding()
      return EditResult.Conflict(binding, key, existing).also {
        showStatus(
            "${key.displayName()} is already assigned to ${existing.displayName}. " +
                "Clear that binding first.")
      }
    }

    keyboard[target] = key
    refreshRows()
    showStatus("${binding.displayName} is now ${key.displayName()}.")
    return EditResult.Applied(binding, key)
  }

  fun clearBinding(
      player: Int,
      button: Button,
  ): EditResult.Applied {
    requireEventDispatchThread()
    val binding = Binding(player, button)
    cancelCapture()
    keyboard.remove(binding.toPlayerButton())
    refreshRows()
    showStatus("${binding.displayName} is unassigned.")
    return EditResult.Applied(binding, null)
  }

  fun resetBinding(
      player: Int,
      button: Button,
  ): EditResult {
    requireEventDispatchThread()
    cancelCapture()
    val binding = Binding(player, button)
    val defaultKey = defaultKeyboard[binding.toPlayerButton()]
    if (defaultKey == null) {
      return clearBinding(player, button)
    }
    return editBinding(player, button, defaultKey.code, allowReserved = true)
  }

  fun resetToDefaults() {
    requireEventDispatchThread()
    cancelCapture()
    keyboard.clear()
    keyboard.putAll(defaultKeyboard)
    refreshRows()
    showStatus("Keyboard mappings restored to defaults.")
  }

  fun cancelCapture() {
    requireEventDispatchThread()
    val hadCapture = activeCapture != null || suppressedKeyCode != null || dispatcherInstalled
    activeCapture = null
    pendingModifier = null
    suppressedKeyCode = null
    uninstallDispatcher()
    if (hadCapture) {
      refreshRows()
      showStatus("Keyboard capture cancelled.")
    }
  }

  internal fun isCaptureActive(): Boolean {
    requireEventDispatchThread()
    return activeCapture != null || suppressedKeyCode != null
  }

  internal fun isCaptureDispatcherInstalled(): Boolean {
    requireEventDispatchThread()
    return dispatcherInstalled
  }

  override fun addNotify() {
    super.addNotify()
    captureWindow = SwingUtilities.getWindowAncestor(this)
    captureWindow?.addWindowFocusListener(windowFocusListener)
    if (activeCapture != null || suppressedKeyCode != null) {
      installDispatcher()
    }
  }

  override fun removeNotify() {
    captureWindow?.removeWindowFocusListener(windowFocusListener)
    captureWindow = null
    activeCapture = null
    pendingModifier = null
    suppressedKeyCode = null
    uninstallDispatcher()
    super.removeNotify()
  }

  /**
   * Capture hook kept internal for deterministic headless tests. Runtime events arrive through the
   * temporary [KeyEventDispatcher], which is removed after capture or component disposal.
   */
  internal fun handleCaptureKey(event: KeyEvent): Boolean {
    requireEventDispatchThread()
    val suppressed = suppressedKeyCode
    if (suppressed != null) {
      if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == suppressed) {
        suppressedKeyCode = null
        uninstallDispatcher()
      }
      return true
    }

    val capture = activeCapture ?: return false
    return when (event.id) {
      KeyEvent.KEY_TYPED -> true
      KeyEvent.KEY_PRESSED -> {
        if (event.keyCode in MODIFIER_KEYS) {
          pendingModifier = event.keyCode
          true
        } else if (!capture.allowReserved && event.keyCode == KeyEvent.VK_TAB) {
          finishCapture()
          showStatus("Keyboard capture cancelled; Tab remains available for dialog navigation.")
          false
        } else if (!capture.allowReserved && event.keyCode == KeyEvent.VK_ESCAPE) {
          finishCapture(event.keyCode)
          showStatus("Keyboard capture cancelled.")
          true
        } else {
          editBinding(
              capture.binding.player,
              capture.binding.button,
              event.keyCode,
              capture.allowReserved,
          )
          finishCapture(event.keyCode)
          true
        }
      }
      KeyEvent.KEY_RELEASED -> {
        if (pendingModifier == event.keyCode) {
          editBinding(
              capture.binding.player,
              capture.binding.button,
              event.keyCode,
              capture.allowReserved,
          )
          finishCapture()
          true
        } else {
          true
        }
      }
      else -> false
    }
  }

  private fun createPlayerPanel(player: Int): JPanel {
    val panel = JPanel()
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.accessibleContext.accessibleName = "Player ${player + 1} keyboard mappings"

    panel.add(
        JPanel(GridLayout(1, COLUMN_COUNT, 6, 0)).apply {
          add(header("Button"))
          add(header("Current binding"))
          add(header("Capture"))
          add(header("Dialog key"))
          add(header("Clear"))
          add(header("Reset"))
        })

    BUTTON_ORDER.forEach { button ->
      val binding = Binding(player, button)
      val bindingLabel =
          JLabel("", SwingConstants.CENTER).apply {
            accessibleContext.accessibleName = "Current binding for ${binding.displayName}"
          }
      val capture =
          actionButton("Capture", "Capture ${binding.displayName} keyboard binding") {
            startCapture(binding, allowReserved = false)
          }
      val captureDialogKey =
          actionButton(
              "Dialog key…",
              "Capture dialog key for ${binding.displayName} keyboard binding",
          ) {
            startCapture(binding, allowReserved = true)
          }
      val clear =
          actionButton("Clear", "Clear ${binding.displayName} keyboard binding") {
            clearBinding(player, button)
          }
      val reset =
          actionButton("Reset", "Reset ${binding.displayName} keyboard binding") {
            resetBinding(player, button)
          }
      rows[binding] = Row(bindingLabel, capture, captureDialogKey)

      panel.add(
          JPanel(GridLayout(1, COLUMN_COUNT, 6, 0)).apply {
            accessibleContext.accessibleName = "${binding.displayName} mapping controls"
            add(
                JLabel(button.keyboardEditorDisplayName()).apply {
                  labelFor = capture
                  accessibleContext.accessibleName = "${binding.displayName} button"
                })
            add(bindingLabel)
            add(capture)
            add(captureDialogKey)
            add(clear)
            add(reset)
          })
    }
    return panel
  }

  private fun header(text: String) =
      JLabel(text, SwingConstants.CENTER).apply {
        accessibleContext.accessibleName = "$text column"
      }

  private fun actionButton(
      text: String,
      accessibleName: String,
      action: () -> Unit,
  ): JButton =
      JButton(text).apply {
        accessibleContext.accessibleName = accessibleName
        accessibleContext.accessibleDescription = accessibleName
        addActionListener { action() }
      }

  private fun startCapture(
      binding: Binding,
      allowReserved: Boolean,
  ) {
    requireEventDispatchThread()
    activeCapture = Capture(binding, allowReserved)
    pendingModifier = null
    suppressedKeyCode = null
    if (isDisplayable) {
      installDispatcher()
    }
    refreshRows()
    showStatus(
        if (allowReserved) {
          "Press one key for ${binding.displayName}; dialog navigation is temporarily suspended."
        } else {
          "Press one key for ${binding.displayName}. Tab navigates, Escape cancels, and Enter is reserved."
        })
  }

  private fun finishCapture(suppressUntilRelease: Int? = null) {
    activeCapture = null
    pendingModifier = null
    suppressedKeyCode = suppressUntilRelease
    refreshRows()
    if (suppressUntilRelease == null) {
      uninstallDispatcher()
    }
  }

  private fun refreshRows() {
    rows.forEach { (binding, row) ->
      val key = keyboard[binding.toPlayerButton()]
      row.currentBinding.text = key?.displayName() ?: "Unassigned"
      row.currentBinding.accessibleContext.accessibleDescription =
          "${binding.displayName}: ${row.currentBinding.text}"
      val capture = activeCapture
      row.capture.text =
          if (capture?.binding == binding && !capture.allowReserved) "Press a key…" else "Capture"
      row.captureDialogKey.text =
          if (capture?.binding == binding && capture.allowReserved) "Press a key…"
          else "Dialog key…"
    }
  }

  private fun showStatus(message: String) {
    status.text = message
    status.accessibleContext.accessibleDescription = message
  }

  private fun installDispatcher() {
    if (!dispatcherInstalled) {
      val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
      focusManager.addKeyEventDispatcher(captureDispatcher)
      focusManager.addPropertyChangeListener("focusOwner", focusOwnerListener)
      dispatcherInstalled = true
    }
  }

  private fun uninstallDispatcher() {
    if (dispatcherInstalled) {
      val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
      focusManager.removeKeyEventDispatcher(captureDispatcher)
      focusManager.removePropertyChangeListener("focusOwner", focusOwnerListener)
      dispatcherInstalled = false
    }
  }

  private fun Binding.toPlayerButton() = ControllerProperties.PlayerButton(player, button)

  private fun ControllerProperties.PlayerButton.toBinding() = Binding(player, button)

  private fun ApplicationSettings.KeyboardKey.displayName(): String = KeyEvent.getKeyText(code)

  private companion object {
    const val PLAYER_COUNT = 4
    const val COLUMN_COUNT = 6

    val BUTTON_ORDER =
        listOf(
            Button.UP,
            Button.DOWN,
            Button.LEFT,
            Button.RIGHT,
            Button.A,
            Button.B,
            Button.SELECT,
            Button.START,
        )

    val RESERVED_DIALOG_KEYS =
        setOf(
            KeyEvent.VK_ESCAPE,
            KeyEvent.VK_ENTER,
        )

    val UNAVAILABLE_GAMEPLAY_KEYS =
        setOf(
            KeyEvent.VK_TAB,
            KeyEvent.VK_BACK_SPACE,
        )

    val MODIFIER_KEYS =
        setOf(
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            KeyEvent.VK_ALT_GRAPH,
            KeyEvent.VK_META,
        )

    fun requireEventDispatchThread() {
      check(SwingUtilities.isEventDispatchThread()) {
        "KeyboardMappingEditor must be created and used on the Swing event dispatch thread"
      }
    }
  }
}

private fun Button.keyboardEditorDisplayName(): String =
    name.lowercase().replaceFirstChar { character -> character.titlecase() }
