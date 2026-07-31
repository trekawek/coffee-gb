package eu.rekawek.coffeegb.swing

import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.KeyStroke

/** Commands shared by the menu bar, game command bar, dialogs, and keyboard shortcuts. */
internal enum class DesktopCommand {
  OPEN_ROM,
  CLOSE_GAME,
  PREFERENCES,
  QUIT,
  PAUSE,
  RESET,
  SAVE_STATE,
  LOAD_STATE,
  MANAGE_STATES,
  OPEN_SAVE_FOLDER,
  NETPLAY,
  MUTE,
  FULLSCREEN,
  SCREENSHOT,
  SHOW_COMMAND_BAR,
}

internal data class DesktopCommandPresentation(
    val gameLoaded: Boolean = false,
    val sessionBusy: Boolean = false,
    val pauseSupported: Boolean = false,
    val paused: Boolean = false,
    val stateCommandsAvailable: Boolean = false,
    val stateBrowserAvailable: Boolean = false,
    val stateSlot: Int = 0,
    val muted: Boolean = false,
    val fullscreen: Boolean = false,
    val commandBarVisible: Boolean = true,
    val exactWindowScaleOne: Boolean = false,
) {
  init {
    require(stateSlot in 0..9)
  }
}

internal data class DesktopCommandHandlers(
    val openRom: () -> Unit,
    val closeGame: () -> Unit,
    val preferences: () -> Unit,
    val quit: () -> Unit,
    val setPaused: (Boolean) -> Unit,
    val reset: () -> Unit,
    val saveState: (Int) -> Unit,
    val loadState: (Int) -> Unit,
    val manageStates: () -> Unit,
    val openSaveFolder: () -> Unit,
    val netplay: () -> Unit,
    val setMuted: (Boolean) -> Unit,
    val setFullscreen: (Boolean) -> Unit,
    val screenshot: () -> Unit,
    val setCommandBarVisible: (Boolean) -> Unit,
    val selectStateSlot: (Int) -> Unit,
)

/**
 * Creates every general desktop action exactly once and applies one immutable command snapshot.
 * Swing views receive [Action] instances and never attach a second controller listener.
 */
internal class DesktopActionRegistry(
    private val handlers: DesktopCommandHandlers,
) {
  private var presentation = DesktopCommandPresentation()
  private var appliedShortcuts: DesktopShortcutRegistry? = null
  private val actions =
      DesktopCommand.entries.associateWith { command ->
        DesktopAction(commandMetadata(command)) { invoke(command) }
      }
  val stateSlotActions: List<Action> =
      (0..9).map { slot ->
        DesktopAction(
            DesktopActionMetadata("Slot $slot", "Use state slot $slot"),
        ) {
          handlers.selectStateSlot(slot)
          update(presentation.copy(stateSlot = slot))
        }
      }

  init {
    update(presentation)
  }

  operator fun get(command: DesktopCommand): Action = checkNotNull(actions[command])

  fun applyShortcuts(shortcutRegistry: DesktopShortcutRegistry) {
    appliedShortcuts = shortcutRegistry
    actions.forEach { (command, action) ->
      action.putValue(Action.ACCELERATOR_KEY, shortcutRegistry[command])
    }
    stateSlotActions.forEachIndexed { index, action ->
      action.putValue(Action.ACCELERATOR_KEY, shortcutRegistry.stateSlotShortcuts[index])
    }
  }

  fun update(next: DesktopCommandPresentation) {
    presentation = next
    actions.forEach { (command, action) ->
      action.isEnabled = enabled(command, next)
      action.putValue(Action.SELECTED_KEY, selected(command, next))
    }
    actions.getValue(DesktopCommand.PAUSE).putValue(
        Action.NAME,
        if (next.paused) "Resume" else "Pause",
    )
    actions.getValue(DesktopCommand.MUTE).putValue(
        Action.NAME,
        if (next.muted) "Unmute" else "Mute",
    )
    stateSlotActions.forEachIndexed { slot, action ->
      action.isEnabled = next.stateCommandsAvailable && !next.sessionBusy
      action.putValue(Action.SELECTED_KEY, slot == next.stateSlot)
    }
  }

  fun current(): DesktopCommandPresentation = presentation

  /** The resolved shortcut, including a gameplay-conflict explanation, for Help surfaces. */
  fun shortcut(command: DesktopCommand): DesktopShortcut? =
      appliedShortcuts?.shortcuts?.get(command)

  fun stateSlotShortcuts(): List<KeyStroke> =
      appliedShortcuts?.stateSlotShortcuts.orEmpty()

  private fun invoke(command: DesktopCommand) {
    when (command) {
      DesktopCommand.OPEN_ROM -> handlers.openRom()
      DesktopCommand.CLOSE_GAME -> handlers.closeGame()
      DesktopCommand.PREFERENCES -> handlers.preferences()
      DesktopCommand.QUIT -> handlers.quit()
      DesktopCommand.PAUSE -> handlers.setPaused(!presentation.paused)
      DesktopCommand.RESET -> handlers.reset()
      DesktopCommand.SAVE_STATE -> handlers.saveState(presentation.stateSlot)
      DesktopCommand.LOAD_STATE -> handlers.loadState(presentation.stateSlot)
      DesktopCommand.MANAGE_STATES -> handlers.manageStates()
      DesktopCommand.OPEN_SAVE_FOLDER -> handlers.openSaveFolder()
      DesktopCommand.NETPLAY -> handlers.netplay()
      DesktopCommand.MUTE -> handlers.setMuted(!presentation.muted)
      DesktopCommand.FULLSCREEN -> handlers.setFullscreen(!presentation.fullscreen)
      DesktopCommand.SCREENSHOT -> handlers.screenshot()
      DesktopCommand.SHOW_COMMAND_BAR ->
          handlers.setCommandBarVisible(!presentation.commandBarVisible)
    }
  }

  private fun enabled(command: DesktopCommand, state: DesktopCommandPresentation): Boolean =
      when (command) {
        DesktopCommand.OPEN_ROM,
        DesktopCommand.PREFERENCES,
        DesktopCommand.QUIT,
        DesktopCommand.NETPLAY,
        DesktopCommand.MUTE,
        DesktopCommand.SHOW_COMMAND_BAR -> !state.sessionBusy
        DesktopCommand.CLOSE_GAME,
        DesktopCommand.RESET -> state.gameLoaded && !state.sessionBusy
        DesktopCommand.PAUSE ->
            state.gameLoaded && state.pauseSupported && !state.sessionBusy
        DesktopCommand.SAVE_STATE,
        DesktopCommand.LOAD_STATE -> state.stateCommandsAvailable && !state.sessionBusy
        DesktopCommand.MANAGE_STATES,
        DesktopCommand.OPEN_SAVE_FOLDER,
        DesktopCommand.SCREENSHOT -> state.stateBrowserAvailable && !state.sessionBusy
        DesktopCommand.FULLSCREEN -> state.gameLoaded && !state.sessionBusy
      }

  private fun selected(command: DesktopCommand, state: DesktopCommandPresentation): Boolean =
      when (command) {
        DesktopCommand.PAUSE -> state.paused
        DesktopCommand.MUTE -> state.muted
        DesktopCommand.FULLSCREEN -> state.fullscreen
        DesktopCommand.SHOW_COMMAND_BAR -> state.commandBarVisible
        else -> false
      }
}

private data class DesktopActionMetadata(
    val name: String,
    val description: String,
)

private class DesktopAction(
    metadata: DesktopActionMetadata,
    private val invoke: () -> Unit,
) : AbstractAction(metadata.name) {
  init {
    putValue(Action.SHORT_DESCRIPTION, metadata.description)
    putValue(Action.LONG_DESCRIPTION, metadata.description)
  }

  override fun actionPerformed(event: ActionEvent) = invoke()
}

private fun commandMetadata(command: DesktopCommand): DesktopActionMetadata =
    when (command) {
      DesktopCommand.OPEN_ROM ->
          DesktopActionMetadata("Open ROM…", "Open a Game Boy ROM or supported archive")
      DesktopCommand.CLOSE_GAME ->
          DesktopActionMetadata("Close Game", "Close the current game")
      DesktopCommand.PREFERENCES ->
          DesktopActionMetadata("Preferences…", "Open Coffee GB preferences")
      DesktopCommand.QUIT -> DesktopActionMetadata("Quit Coffee GB", "Quit Coffee GB")
      DesktopCommand.PAUSE -> DesktopActionMetadata("Pause", "Pause or resume emulation")
      DesktopCommand.RESET -> DesktopActionMetadata("Reset", "Reset the current game")
      DesktopCommand.SAVE_STATE ->
          DesktopActionMetadata("Save", "Save the current state slot")
      DesktopCommand.LOAD_STATE ->
          DesktopActionMetadata("Load", "Load the current state slot")
      DesktopCommand.MANAGE_STATES ->
          DesktopActionMetadata("Manage States…", "Open saved-state management")
      DesktopCommand.OPEN_SAVE_FOLDER ->
          DesktopActionMetadata("Open Save Folder", "Open this game's save folder")
      DesktopCommand.NETPLAY ->
          DesktopActionMetadata("Netplay…", "Host, join, or inspect a netplay session")
      DesktopCommand.MUTE -> DesktopActionMetadata("Mute", "Mute or unmute all audio")
      DesktopCommand.FULLSCREEN ->
          DesktopActionMetadata("Full Screen", "Enter or leave full screen")
      DesktopCommand.SCREENSHOT ->
          DesktopActionMetadata("Screenshot", "Save a screenshot of the current game")
      DesktopCommand.SHOW_COMMAND_BAR ->
          DesktopActionMetadata("Show Command Bar", "Show the game command bar in a window")
    }

internal data class DesktopShortcut(
    val command: DesktopCommand,
    val keyStroke: KeyStroke?,
    val inactiveReason: String? = null,
    /** The configured default before gameplay-conflict withdrawal. */
    val proposedKeyStroke: KeyStroke? = keyStroke,
)

/** Resolves application shortcuts once, including the gameplay-binding-wins rule. */
internal class DesktopShortcutRegistry(
    gameplayKeyCodes: Collection<Int>,
    platformMenuMask: Int = defaultPlatformMenuMask(),
) {
  val shortcuts: Map<DesktopCommand, DesktopShortcut>
  val stateSlotShortcuts: List<KeyStroke> =
      (0..9).map { slot -> KeyStroke.getKeyStroke(KeyEvent.VK_0 + slot, platformMenuMask) }

  init {
    val bound = gameplayKeyCodes.toSet()
    val proposed =
        mapOf(
            DesktopCommand.OPEN_ROM to KeyStroke.getKeyStroke(KeyEvent.VK_O, platformMenuMask),
            DesktopCommand.PREFERENCES to
                KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, platformMenuMask),
            DesktopCommand.QUIT to KeyStroke.getKeyStroke(KeyEvent.VK_Q, platformMenuMask),
            DesktopCommand.PAUSE to KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            DesktopCommand.RESET to KeyStroke.getKeyStroke(KeyEvent.VK_R, platformMenuMask),
            DesktopCommand.SAVE_STATE to KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            DesktopCommand.LOAD_STATE to KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0),
            DesktopCommand.FULLSCREEN to KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0),
            DesktopCommand.SCREENSHOT to KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0),
        )
    val activeKeys = mutableSetOf<KeyStroke>()
    shortcuts =
        proposed.mapValues { (command, keyStroke) ->
          val unmodified = keyStroke.modifiers == 0
          val conflict = unmodified && keyStroke.keyCode in bound
          val active = keyStroke.takeUnless { conflict }
          check(active == null || activeKeys.add(active)) {
            "Duplicate desktop shortcut for $command: $active"
          }
          DesktopShortcut(
              command,
              active,
              if (conflict) "Inactive because this key is assigned to gameplay" else null,
              proposedKeyStroke = keyStroke,
          )
        }
  }

  operator fun get(command: DesktopCommand): KeyStroke? = shortcuts[command]?.keyStroke
}

private fun defaultPlatformMenuMask(): Int =
    runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
        .getOrDefault(
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
              InputEvent.META_DOWN_MASK
            } else {
              InputEvent.CTRL_DOWN_MASK
            })
