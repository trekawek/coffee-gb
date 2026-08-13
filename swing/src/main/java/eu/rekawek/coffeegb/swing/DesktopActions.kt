package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.ui.menu.PlayTimeTracker
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.KeyStroke

/** Commands shared by the menu bar, game command bar, dialogs, and keyboard shortcuts. */
internal enum class DesktopCommand {
  OPEN_ROM,
  CLOSE_GAME,
  PREFERENCES,
  QUIT,
  OPEN_MENU,
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
    val loadableStateSlots: Set<Int> = emptySet(),
    val muted: Boolean = false,
    val fullscreen: Boolean = false,
    val commandBarVisible: Boolean = true,
    val exactWindowScaleOne: Boolean = false,
) {
  init {
    require(stateSlot in 0..9)
    require(loadableStateSlots.all { it in 0..9 })
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
    val preferencesForCategory: ((PreferencesCategory) -> Unit)? = null,
    val openMenu: () -> Unit = {},
    val openAbout: (() -> Unit)? = null,
)

/** Detached quick-state data consumed by the portable menu renderer. */
internal data class PortableMenuStateSlot(
  val index: Int,
  val loadable: Boolean,
  val preview: MenuPreview = MenuPreview.empty(),
  /** Authoritative managed-state metadata; null means the slot has no trustworthy saved time. */
  val savedAt: Instant? = null,
)

private val PORTABLE_STATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Formats only the persisted instant supplied by the state catalog. */
internal fun portableStateSavedAt(instant: Instant?): String? {
  if (instant == null) return null
  return try {
    "SAVED ${PORTABLE_STATE_TIME_FORMAT.format(instant)}"
  } catch (_: RuntimeException) {
    // A malformed/unsupported metadata instant must never become a misleading UI date.
    null
  }
}

/**
 * Creates every general desktop action exactly once and applies one immutable command snapshot.
 * Swing views receive [Action] instances and never attach a second controller listener.
 */
internal class DesktopActionRegistry(
    private val handlers: DesktopCommandHandlers,
    private val proposal3MenuAvailable: Boolean = false,
    private val stateCatalogProvider: () -> List<PortableMenuStateSlot> = { emptyList() },
    private val stateCatalogRefresh: () -> Unit = {},
) : PortableMenuCommandBridge {
  private var portableStateSlots: List<PortableMenuStateSlot> = emptyList()
  private var portableStateSlotsPublished = false
  private val stateCatalogListeners = mutableListOf<() -> Unit>()
  private var presentation = DesktopCommandPresentation()
  private var menuPresentation = DesktopPresentation()
  private val playTime = PlayTimeTracker()
  private var timedSessionGeneration: Long? = null
  private var timedGameTitle: String? = null
  private var appliedShortcuts: DesktopShortcutRegistry? = null
  private val actions =
      DesktopCommand.entries.associateWith { command ->
        DesktopAction(commandMetadata(command)) { invokeCommand(command) }
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
    updatePresentation(menuPresentation)
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
    updatePresentation(menuPresentation.copy(commands = next))
  }

  /** Receives the full authoritative shell snapshot, including session-only menu metadata. */
  fun updatePresentation(next: DesktopPresentation) {
    updatePlayTime(next)
    menuPresentation = next
    presentation = next.commands
    actions.forEach { (command, action) ->
      action.isEnabled = enabled(command, presentation)
      action.putValue(Action.SELECTED_KEY, selected(command, presentation))
    }
    actions.getValue(DesktopCommand.PAUSE).putValue(
        Action.NAME,
        if (presentation.paused) "Resume" else "Pause",
    )
    actions.getValue(DesktopCommand.MUTE).putValue(
        Action.NAME,
        if (presentation.muted) "Unmute" else "Mute",
    )
    stateSlotActions.forEachIndexed { slot, action ->
      action.isEnabled = presentation.stateCommandsAvailable && !presentation.sessionBusy
      action.putValue(Action.SELECTED_KEY, slot == presentation.stateSlot)
    }
  }

  override fun menuState(): DesktopPresentation =
      menuPresentation.copy(commands = presentation, playTimeNanos = playTime.elapsedNanos())

  override fun isEnabled(command: DesktopCommand): Boolean = actions.getValue(command).isEnabled

  override fun invoke(command: DesktopCommand) {
    val action = actions.getValue(command)
    if (action.isEnabled) {
      action.actionPerformed(
          ActionEvent(this, ActionEvent.ACTION_PERFORMED, "portable-menu"),
      )
    }
  }

  override fun canOpenAbout(): Boolean = handlers.openAbout != null

  override fun openAbout() {
    handlers.openAbout?.invoke()
  }

  override fun setPaused(paused: Boolean) {
    if (presentation.paused != paused && isEnabled(DesktopCommand.PAUSE)) {
      handlers.setPaused(paused)
    }
  }

  override fun openPreferences(category: PreferencesCategory) {
    if (!isEnabled(DesktopCommand.PREFERENCES)) return
    handlers.preferencesForCategory?.invoke(category) ?: handlers.preferences()
  }

  override fun canSaveState(slot: Int): Boolean =
      slot in 0..9 && presentation.stateCommandsAvailable && !presentation.sessionBusy

  override fun canLoadState(slot: Int): Boolean =
      canSaveState(slot) &&
          (stateSlots().firstOrNull { it.index == slot }?.loadable
              ?: (slot in presentation.loadableStateSlots))

  override fun saveState(slot: Int) {
    if (canSaveState(slot)) handlers.saveState(slot)
  }

  override fun loadState(slot: Int) {
    if (canLoadState(slot)) handlers.loadState(slot)
  }

  override fun stateSlots(): List<PortableMenuStateSlot> =
      if (portableStateSlotsPublished) portableStateSlots else stateCatalogProvider().toList()

  override fun refreshStateCatalog() = stateCatalogRefresh()

  override fun addStateCatalogListener(listener: () -> Unit) {
    stateCatalogListeners += listener
  }

  fun updatePortableStateSlots(slots: List<PortableMenuStateSlot>) {
    portableStateSlots = slots.toList()
    portableStateSlotsPublished = true
    stateCatalogListeners.toList().forEach { it() }
  }

  fun commandPresentation(): DesktopCommandPresentation = presentation

  fun current(): DesktopCommandPresentation = presentation

  /** The resolved shortcut, including a gameplay-conflict explanation, for Help surfaces. */
  fun shortcut(command: DesktopCommand): DesktopShortcut? =
      appliedShortcuts?.shortcuts?.get(command)

  fun stateSlotShortcuts(): List<KeyStroke> =
      appliedShortcuts?.stateSlotShortcuts.orEmpty()

  private fun updatePlayTime(next: DesktopPresentation) {
    val title = next.gameTitle
    if (title == null) {
      playTime.clear()
      timedSessionGeneration = null
      timedGameTitle = null
      return
    }
    val sessionChanged =
        timedGameTitle != title ||
            (next.sessionGeneration != null && next.sessionGeneration != timedSessionGeneration)
    if (sessionChanged) {
      playTime.start()
      timedSessionGeneration = next.sessionGeneration
      timedGameTitle = title
    }
    // Paused is emitted by the controller, so it is the source of truth for elapsed play time.
    playTime.setRunning(!next.commands.paused)
  }

  private fun invokeCommand(command: DesktopCommand) {
    when (command) {
      DesktopCommand.OPEN_ROM -> handlers.openRom()
      DesktopCommand.CLOSE_GAME -> handlers.closeGame()
      DesktopCommand.PREFERENCES -> handlers.preferences()
      DesktopCommand.QUIT -> handlers.quit()
      DesktopCommand.OPEN_MENU -> handlers.openMenu()
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
        DesktopCommand.OPEN_MENU ->
            proposal3MenuAvailable && state.gameLoaded && !state.sessionBusy
        DesktopCommand.CLOSE_GAME,
        DesktopCommand.RESET -> state.gameLoaded && !state.sessionBusy
      DesktopCommand.PAUSE ->
            state.gameLoaded && state.pauseSupported && !state.sessionBusy
        DesktopCommand.SAVE_STATE -> state.stateCommandsAvailable && !state.sessionBusy
        DesktopCommand.LOAD_STATE ->
            state.stateCommandsAvailable &&
                state.stateSlot in state.loadableStateSlots &&
                !state.sessionBusy
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
      DesktopCommand.OPEN_MENU ->
          DesktopActionMetadata("On-screen Menu", "Open the controller-friendly Proposal 3 menu")
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
