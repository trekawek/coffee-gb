package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
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

    data class Reserved(
        val binding: Binding,
        val keyCode: Int,
    ) : EditResult

    data class Unsupported(
        val binding: Binding,
        val keyCode: Int,
    ) : EditResult
  }

  private data class Capture(val binding: Binding)

  private data class Row(
      val currentBinding: JLabel,
      val capture: JButton,
      val card: JPanel,
  )

  internal data class PadPosition(val column: Int, val row: Int)

  private val keyboard =
      initialInput.keyboard.toMutableMap().also { initialInput.toPlayerMapping() }
  private val gamepads = initialInput.gamepads.toMap()
  private val gamepadTunings = initialInput.gamepadTunings.toMap()
  private val defaultKeyboard =
      defaultInput.keyboard.toMap().also { defaultInput.toPlayerMapping() }
  private val rows = linkedMapOf<Button, Row>()
  private val status = JLabel("Choose Capture, then press one key.")
  private var selectedPlayerIndex = 0

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

    add(createPlayerPanel(), BorderLayout.CENTER)

    val footer = JPanel(BorderLayout(8, 0))
    status.accessibleContext.accessibleName = "Keyboard mapping status"
    status.accessibleContext.accessibleDescription = status.text
    footer.add(status, BorderLayout.CENTER)
    footer.add(
        JButton("Reset keyboard defaults").apply {
          getAccessibleContext().accessibleName = "Restore all keyboard defaults"
          getAccessibleContext().accessibleDescription =
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
   * Conflicting key assignments are resolved atomically while editing, so this method normally
   * cannot fail. The explicit validation is retained as a boundary check for a Preferences Apply
   * handler.
   */
  fun validatedDraft(): ApplicationSettings.Input {
    requireEventDispatchThread()
    return ApplicationSettings.Input(keyboard.toMap(), gamepads, gamepadTunings).also {
      it.toPlayerMapping()
    }
  }

  fun currentBinding(
      player: Int,
      button: Button,
  ): ApplicationSettings.KeyboardKey? {
    requireEventDispatchThread()
    return keyboard[ControllerProperties.PlayerButton(player, button)]
  }

  /** Selects the player represented by the reusable pad controls. */
  internal fun selectPlayer(player: Int) {
    requireEventDispatchThread()
    require(player in 0 until PLAYER_COUNT) { "Logical player index must be in 0..3" }
    if (selectedPlayerIndex == player) return
    cancelCapture()
    selectedPlayerIndex = player
    refreshRows()
    getAccessibleContext().accessibleDescription =
        "Keyboard controls for Player ${player + 1}. Changes remain a draft until Preferences is saved."
    showStatus("Editing keyboard mappings for Player ${player + 1}.")
  }

  internal val selectedPlayer: Int
    get() = selectedPlayerIndex

  /**
   * Attempts to assign one Java AWT key code.
   *
   * Escape and Enter are valid bindings. Tab remains reserved for focus navigation and Backspace
   * remains reserved for Rewind.
   */
  fun editBinding(
      player: Int,
      button: Button,
      keyCode: Int,
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
    val key =
        try {
          DesktopKeyboardKeyAdapter.fromKeyCode(keyCode)
        } catch (_: IllegalArgumentException) {
          return EditResult.Unsupported(binding, keyCode).also {
            showStatus("That key cannot be stored as a keyboard binding.")
          }
        }
    val target = binding.toPlayerButton()
    val previousTargetKey = keyboard[target]
    val conflict =
        keyboard.entries.firstOrNull { (candidate, candidateKey) ->
          candidate != target && candidateKey == key
        }
    if (conflict != null) {
      val existing = conflict.key.toBinding()
      keyboard.remove(conflict.key)
      if (previousTargetKey != null) {
        keyboard[conflict.key] = previousTargetKey
      }
      keyboard[target] = key
      refreshRows()
      showStatus(
          buildString {
            append("${binding.displayName} is now ${key.displayName()}; ")
            append("${existing.displayName} is now ")
            append(previousTargetKey?.displayName() ?: "unassigned")
            append('.')
          })
      return EditResult.Applied(binding, key)
    }

    keyboard[target] = key
    refreshRows()
    showStatus("${binding.displayName} is now ${key.displayName()}.")
    return EditResult.Applied(binding, key)
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

  /** Returns the actual GridBag position of one binding card without relying on pixel bounds. */
  internal fun padPosition(
      player: Int,
      button: Button,
  ): PadPosition {
    requireEventDispatchThread()
    require(player in 0 until PLAYER_COUNT) { "Logical player index must be in 0..3" }
    val card = checkNotNull(rows[button]).card
    val layout = card.parent.layout as GridBagLayout
    val constraints = layout.getConstraints(card)
    return PadPosition(constraints.gridx, constraints.gridy)
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
        } else {
          editBinding(
              capture.binding.player,
              capture.binding.button,
              event.keyCode,
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

  private fun createPlayerPanel(): JPanel {
    val panel = JPanel(GridBagLayout())
    panel.accessibleContext.accessibleName = "Selected player keyboard mappings"
    panel.accessibleContext.accessibleDescription =
        "A Game Boy-shaped arrangement of the eight keyboard bindings for the selected player."
    panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

    PAD_FOCUS_ORDER.forEach { button ->
      val bindingLabel =
          JLabel("", SwingConstants.CENTER).apply {
            getAccessibleContext().accessibleName = "Current binding"
          }
      val capture =
          actionButton("Capture", "Capture keyboard binding") {
            startCapture(Binding(selectedPlayerIndex, button))
          }
      val card =
          JPanel(BorderLayout(4, 4)).apply {
            getAccessibleContext().accessibleName = "Keyboard mapping controls"
            border = BorderFactory.createTitledBorder(button.keyboardEditorDisplayName())
            add(
                JLabel("Current key", SwingConstants.CENTER).apply {
                  labelFor = capture
                  getAccessibleContext().accessibleName = "Selected player button"
                },
                BorderLayout.NORTH,
            )
            add(bindingLabel, BorderLayout.CENTER)
            add(capture, BorderLayout.SOUTH)
          }
      rows[button] = Row(bindingLabel, capture, card)

      val position = PAD_POSITIONS.getValue(button)
      panel.add(
          card,
          GridBagConstraints().apply {
            gridx = position.column
            gridy = position.row
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(6, 6, 6, 6)
          },
      )
    }
    panel.add(
        JPanel(),
        GridBagConstraints().apply {
          gridx = 0
          gridy = PAD_ROW_COUNT
          gridwidth = PAD_COLUMN_COUNT
          weightx = 1.0
          weighty = 1.0
          fill = GridBagConstraints.BOTH
        },
    )
    return panel
  }

  private fun actionButton(
      text: String,
      accessibleName: String,
      action: () -> Unit,
  ): JButton =
      JButton(text).apply {
        getAccessibleContext().accessibleName = accessibleName
        getAccessibleContext().accessibleDescription = accessibleName
        addActionListener { action() }
      }

  private fun startCapture(binding: Binding) {
    requireEventDispatchThread()
    activeCapture = Capture(binding)
    pendingModifier = null
    suppressedKeyCode = null
    if (isDisplayable) {
      installDispatcher()
    }
    refreshRows()
    showStatus(
        "Press one key for ${binding.displayName}; dialog navigation is temporarily suspended.")
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
    rows.forEach { (button, row) ->
      val binding = Binding(selectedPlayerIndex, button)
      val key = keyboard[binding.toPlayerButton()]
      row.currentBinding.text = key?.displayName() ?: "Unassigned"
      row.currentBinding.accessibleContext.accessibleName =
          "Current binding for ${binding.displayName}"
      row.currentBinding.accessibleContext.accessibleDescription =
          "${binding.displayName}: ${row.currentBinding.text}"
      row.capture.accessibleContext.accessibleName =
          "Capture ${binding.displayName} keyboard binding"
      row.capture.accessibleContext.accessibleDescription =
          row.capture.accessibleContext.accessibleName
      row.card.accessibleContext.accessibleName = "${binding.displayName} mapping controls"
      val capture = activeCapture
      row.capture.text =
          if (capture?.binding == binding) "Press a key…" else "Capture"
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

  private fun ApplicationSettings.KeyboardKey.displayName(): String =
      KeyEvent.getKeyText(DesktopKeyboardKeyAdapter.keyCode(this))

  private companion object {
    const val PLAYER_COUNT = 4
    const val PAD_COLUMN_COUNT = 7
    const val PAD_ROW_COUNT = 4

    val PAD_FOCUS_ORDER =
        listOf(
            Button.UP,
            Button.LEFT,
            Button.RIGHT,
            Button.DOWN,
            Button.SELECT,
            Button.START,
            Button.B,
            Button.A,
        )

    val PAD_POSITIONS =
        mapOf(
            Button.UP to PadPosition(1, 0),
            Button.LEFT to PadPosition(0, 1),
            Button.RIGHT to PadPosition(2, 1),
            Button.DOWN to PadPosition(1, 2),
            Button.SELECT to PadPosition(3, 3),
            Button.START to PadPosition(4, 3),
            Button.B to PadPosition(5, 2),
            Button.A to PadPosition(6, 1),
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
