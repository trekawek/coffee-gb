package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.swing.io.DesktopMenuInputCapture
import eu.rekawek.coffeegb.swing.io.DesktopMenuKeyboardInput
import eu.rekawek.coffeegb.ui.menu.MenuController
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec
import eu.rekawek.coffeegb.ui.menu.MenuPresentation
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import eu.rekawek.coffeegb.ui.menu.MenuRoute
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame
import eu.rekawek.coffeegb.ui.menu.artwork.Proposal3MenuCompositor
import java.util.EnumSet
import javax.swing.SwingUtilities

/**
 * Swing host for the complete Proposal 3 route tree.
 *
 * <p>Each route is still rendered by the portable compositor. This host only supplies route
 * state, controller capture, and a command bridge to existing desktop actions. Unsupported
 * operations remain disabled; the filesystem picker and printer export chooser stay native Swing
 * dialogs. Once a native picker returns a multi-ROM archive, its bounded candidates are rendered
 * by the portable CHOOSE_ROM page.</p>
 */
internal class SwingProposal3Menu(
    private val frameSink: (MenuArgbFrame?) -> Unit,
    private val commands: PortableMenuCommandBridge,
    private val releaseGameplay: () -> Unit,
    private val printer: PortableMenuPrinterBridge? = null,
    private val onVisibilityChanged: (Boolean) -> Unit = {},
) : DesktopMenuInputCapture, DesktopMenuKeyboardInput, DesktopArchiveSelectionHost {

  private data class PendingArchiveSelection(
      val requestId: Long,
      val candidates: List<RomSourceSnapshot.ArchiveCandidate>,
      val byItemId: Map<String, RomSourceSnapshot.ArchiveCandidate>,
      val onSelected: (RomSourceSnapshot.ArchiveCandidate) -> Unit,
      val onCancelled: () -> Unit,
  )

  private val compositor = Proposal3MenuCompositor()
  private val controller =
      MenuController(
          object : MenuController.Listener {
            override fun onPresentation(presentation: MenuPresentation) {
              render(presentation)
            }

            override fun onItemSelected(
                route: MenuRoute,
                id: String,
                secondary: Boolean,
            ) {
              handleItem(route, id, secondary)
            }

            override fun onHeaderSelected(route: MenuRoute) {
              if (route == MenuRoute.PAUSE_CONSOLE) {
                openRoute(MenuRoute.LIBRARY)
              } else if (route == MenuRoute.LIBRARY) {
                runNativeRomChooser()
              }
            }

            override fun onBackIntercepted(route: MenuRoute) {
              if (route == MenuRoute.CHOOSE_ROM) {
                cancelArchiveSelection()
              }
            }
          })

  private val gamepadHeld = EnumSet.noneOf(Button::class.java)

  @Volatile private var opening = false

  @Volatile private var chordLatched = false

  private var pauseOwnedByMenu = false

  private var pendingConfirmation: DesktopCommand? = null

  private var pendingArchiveSelection: PendingArchiveSelection? = null

  private var selectedArchiveItemId: String? = null

  private var renderedVisible = false

  override fun visible(): Boolean = controller.visible() || opening

  /** Opens the menu from a desktop command or test seam on the EDT. */
  internal fun openFromDesktop() {
    runOnEdt(::openOnEdt)
  }

  /** Hides the menu during ROM/controller lifecycle transitions without resuming a new session. */
  internal fun closeForLifecycle() {
    runOnEdt {
      pauseOwnedByMenu = false
      pendingConfirmation = null
      pendingArchiveSelection = null
      selectedArchiveItemId = null
      controller.setBackIntercepted(false)
      opening = false
      if (controller.visible()) controller.hide()
      else frameSink(null)
      releaseGameplaySoon()
    }
  }

  override fun updatePlayerButtons(buttons: Collection<Button>): Boolean {
    val current = EnumSet.noneOf(Button::class.java)
    current.addAll(buttons)
    val wasVisible = visible()

    if (!wasVisible) {
      val startAndSelect =
          current.contains(Button.START) && current.contains(Button.SELECT)
      if (!startAndSelect) {
        chordLatched = false
      } else if (!chordLatched) {
        chordLatched = true
        opening = true
        SwingUtilities.invokeLater {
          opening = false
          openOnEdt()
        }
        gamepadHeld.clear()
        gamepadHeld.addAll(current)
        return true
      }
      gamepadHeld.clear()
      gamepadHeld.addAll(current)
      return false
    }

    val previous = EnumSet.noneOf(Button::class.java)
    previous.addAll(gamepadHeld)
    gamepadHeld.clear()
    gamepadHeld.addAll(current)
    refreshPrinterPageBeforeInput()

    // Physical polling is edge-triggered here. Holding a controller button across polls never
    // repeats the semantic action, while releasing it always clears MenuController's capture.
    for (button in Button.values()) {
      val becamePressed = current.contains(button) && !previous.contains(button)
      val becameReleased = !current.contains(button) && previous.contains(button)
      if (becamePressed) {
        controller.onKeyDown(button.toMenuKey(), false)
      }
      if (becameReleased) {
        controller.onKeyUp(button.toMenuKey())
      }
      if (!visible()) break
    }
    return true
  }

  override fun onKeyDown(key: MenuKey, repeat: Boolean): Boolean {
    if (!visible()) return false
    if (opening) return true
    refreshPrinterPageBeforeInput()
    return controller.onKeyDown(key, repeat)
  }

  override fun onKeyUp(key: MenuKey): Boolean {
    if (!visible()) return false
    if (opening) return true
    return controller.onKeyUp(key)
  }

  override fun showArchiveSelection(
      requestId: Long,
      candidates: List<RomSourceSnapshot.ArchiveCandidate>,
      onSelected: (RomSourceSnapshot.ArchiveCandidate) -> Unit,
      onCancelled: () -> Unit,
  ) {
    require(candidates.size > 1) { "Archive selection requires multiple candidates" }
    runOnEdt {
      pendingArchiveSelection?.let { stale ->
        pendingArchiveSelection = null
        selectedArchiveItemId = null
        stale.onCancelled()
      }

      val copiedCandidates = candidates.toList()
      val byItemId =
          copiedCandidates.associateBy { archiveItemId(it) }
      check(byItemId.size == copiedCandidates.size) {
        "Archive candidates must have unique selection tokens"
      }
      pendingArchiveSelection =
          PendingArchiveSelection(
              requestId,
              copiedCandidates,
              byItemId,
              onSelected,
              onCancelled,
          )
      selectedArchiveItemId = archiveItemId(copiedCandidates.first())
      controller.setBackIntercepted(true)

      val current = commands.menuState()
      pauseOwnedByMenu = current.commands.pauseSupported && !current.commands.paused
      if (pauseOwnedByMenu) {
        commands.setPaused(true)
      }
      controller.setPage(pageFor(MenuRoute.CHOOSE_ROM, current))
      controller.show(MenuRoute.CHOOSE_ROM)
      releaseGameplaySoon()
    }
  }

  override fun closeArchiveSelection(requestId: Long) {
    runOnEdt {
      val pending = pendingArchiveSelection ?: return@runOnEdt
      // Request IDs are monotonic. A newer request supersedes an older visible chooser, while a
      // late terminal update from an older request must not close the newer chooser.
      if (pending.requestId > requestId) return@runOnEdt
      pendingArchiveSelection = null
      selectedArchiveItemId = null
      controller.setBackIntercepted(false)
      hideAndResume()
    }
  }

  // Package-private seams keep route/action tests on the semantic controller rather than on
  // raster pixels. Production callers only need the input-capture interfaces above.
  internal fun routeForTest(): MenuRoute? = controller.route().takeIf { controller.visible() }

  internal fun focusedItemIdForTest(): String? {
    if (!controller.visible()) return null
    val presentation = controller.presentation()
    return presentation.items().getOrNull(presentation.focusedIndex())?.id()
  }

  private fun openOnEdt() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu must open on the EDT" }
    if (opening || controller.visible()) return
    val current = commands.menuState()
    if (!current.commands.gameLoaded || current.commands.sessionBusy) return

    controller.setPage(pageFor(MenuRoute.PAUSE_CONSOLE, current))
    pauseOwnedByMenu = current.commands.pauseSupported && !current.commands.paused
    if (pauseOwnedByMenu) {
      commands.setPaused(true)
    }
    controller.show(MenuRoute.PAUSE_CONSOLE)
    releaseGameplaySoon()
  }

  private fun pageFor(
      route: MenuRoute,
      presentation: DesktopPresentation,
      confirmationAction: DesktopCommand? = null,
  ): MenuPageSpec {
    fun enabled(command: DesktopCommand): Boolean = commands.isEnabled(command)
    fun item(
        id: String,
        label: String,
        isEnabled: Boolean,
        secondaryId: String? = null,
        adjustable: Boolean = false,
        progress: Int = -1,
    ) = MenuPageSpec.Item(id, label, "", isEnabled, secondaryId, adjustable, progress)

    fun page(
        context: String,
        sideHeading: String,
        sideLines: List<String>,
        items: List<MenuPageSpec.Item>,
        preferredFocus: String? = null,
        headerAction: String = "",
        preview: MenuPreview = MenuPreview.empty(),
    ) = MenuPageSpec(
        route,
        "COFFEE GB",
        context,
        headerAction,
        sideHeading,
        sideLines,
        items,
        1,
        listOf("D-PAD MOVE", "[A] OK", "[B] BACK"),
        preferredFocus ?: items.firstOrNull { it.enabled() }?.id() ?: items.first().id(),
        preview,
    )

    val state = presentation.commands
    val stateAvailable = state.stateCommandsAvailable && !state.sessionBusy
    val printerHasPaper = printer?.hasPaper() == true
    return when (route) {
      MenuRoute.PAUSE_CONSOLE ->
          page(
              "PAUSED",
              "CURRENT GAME",
              listOf(
                  if (presentation.gameTitle == null) "READY" else "PLAYING",
                  "PAUSED",
                  "CONTROLLER MENU",
              ),
              listOf(
                  item("resume", "RESUME", enabled(DesktopCommand.PAUSE)),
                  item("save-state", "SAVE STATE", stateAvailable),
                  item("load-state", "LOAD STATE", stateAvailable),
                  item("reset", "RESET GAME", enabled(DesktopCommand.RESET)),
                  item("settings", "SETTINGS", enabled(DesktopCommand.PREFERENCES)),
                  item("stop", "STOP GAME", enabled(DesktopCommand.CLOSE_GAME)),
              ),
              headerAction = if (enabled(DesktopCommand.OPEN_ROM)) "OPEN ROM" else "",
          )

      MenuRoute.SAVE_STATES -> {
        val slots =
            (0..3).map { slot ->
              val load = commands.canLoadState(slot)
              item(
                  "slot-$slot",
                  "SLOT $slot",
                  commands.canSaveState(slot),
                  secondaryId = "load-slot-$slot".takeIf { load },
              )
            }
        page(
            "SAVE STATES",
            "STATE BANK",
            listOf("SLOT 0 READY", "SLOT 1 EMPTY", "SLOT 2 EMPTY"),
            slots +
                item("manage-states", "MANAGE STATES", enabled(DesktopCommand.MANAGE_STATES)) +
                item("back", "BACK", true),
            preferredFocus = slots.firstOrNull { it.enabled() }?.id() ?: "manage-states",
        )
      }

      MenuRoute.SETTINGS ->
          page(
              "SETTINGS",
              "SETTINGS HUB",
              listOf("AUDIO + INPUT", "DEVICES + DATA", "SYSTEM PROFILE"),
              listOf(
                  item("audio", "AUDIO", true),
                  item("touch-controls", "TOUCH CONTROLS", true),
                  item("controller-mapping", "CONTROLLER MAPPING", true),
                  item("optional-devices", "OPTIONAL DEVICES", true),
                  item("video", "VIDEO", enabled(DesktopCommand.PREFERENCES)),
                  item("system-profile", "SYSTEM PROFILE", true),
                  item("rewind-save", "REWIND & SAVE", enabled(DesktopCommand.PREFERENCES)),
                  item("data-media", "DATA & MEDIA", true),
                  item("about", "ABOUT", true),
                  item("back", "BACK", true),
              ),
          )

      MenuRoute.AUDIO ->
          page(
              "AUDIO",
              "AUDIO MIX",
              listOf("NO LIVE PREVIEW", "VOLUME  75%", "EMULATED AUDIO  ON"),
              listOf(
                  item("volume", "VOLUME", false),
                  item("mute-audio", "MUTE", enabled(DesktopCommand.MUTE)),
                  item("emulated-audio", "EMULATED AUDIO", false),
                  item("save-audio", "SAVE", enabled(DesktopCommand.PREFERENCES)),
                  item("cancel-audio", "CANCEL", true),
              ),
              preferredFocus = "mute-audio",
          )

      MenuRoute.TOUCH_CONTROLS ->
          page(
              "TOUCH CONTROLS",
              "INPUT DECK",
              listOf("SKIN  CLASSIC", "HAPTICS  ON", "LAYOUT  SAVED"),
              listOf(
                  item("haptics", "HAPTIC FEEDBACK", false),
                  item("button-opacity", "BUTTON OPACITY", false),
                  item("reset-touch", "RESET DEFAULTS", false),
                  item("save-touch", "SAVE", enabled(DesktopCommand.PREFERENCES)),
                  item("cancel-touch", "CANCEL", true),
              ),
              preferredFocus = "save-touch",
          )

      MenuRoute.CONTROLLER_MAPPING ->
          page(
              "CONTROLLER MAPPING",
              "INPUT MAP",
              listOf("DEVICE  BLUETOOTH", "PROFILE  DEFAULT", "A + B TO CANCEL"),
              listOf(
                  item("map-a", "A", enabled(DesktopCommand.PREFERENCES)),
                  item("map-b", "B", enabled(DesktopCommand.PREFERENCES)),
                  item("map-start", "START", enabled(DesktopCommand.PREFERENCES)),
                  item("map-select", "SELECT", enabled(DesktopCommand.PREFERENCES)),
                  item("map-up", "UP", enabled(DesktopCommand.PREFERENCES)),
                  item("map-down", "DOWN", enabled(DesktopCommand.PREFERENCES)),
                  item("map-left", "LEFT", enabled(DesktopCommand.PREFERENCES)),
                  item("map-right", "RIGHT", enabled(DesktopCommand.PREFERENCES)),
                  item("invert-x", "HORIZONTAL AXIS", enabled(DesktopCommand.PREFERENCES)),
                  item("invert-y", "VERTICAL AXIS", enabled(DesktopCommand.PREFERENCES)),
                  item("reset-controller", "RESET MAPPINGS", enabled(DesktopCommand.PREFERENCES)),
                  item("back", "BACK", true),
              ),
              preferredFocus = "map-a",
          )

      MenuRoute.OPTIONAL_DEVICES ->
          page(
              "OPTIONAL DEVICES",
              "ACCESSORIES",
              listOf("RUMBLE  READY", "CAMERA  PERMISSION", "PRINTER  READY"),
              listOf(
                  item("rumble", "RUMBLE", false),
                  item("live-camera", "LIVE CAMERA", enabled(DesktopCommand.PREFERENCES)),
                  item("game-boy-printer", "GAME BOY PRINTER", printer != null),
                  item("calibrate-tilt", "CALIBRATE TILT", enabled(DesktopCommand.PREFERENCES)),
                  item("preview-printer-paper", "PREVIEW PRINTER PAPER", printerHasPaper),
                  item("export-share-paper", "EXPORT & SHARE PAPER", printerHasPaper),
                  item("save-devices", "SAVE", enabled(DesktopCommand.PREFERENCES)),
                  item("cancel-devices", "CANCEL", true),
              ),
              preferredFocus = "live-camera",
          )

      MenuRoute.PRINTER_PAPER -> {
        val currentPrinter = printer
        val currentPreview =
            if (currentPrinter != null && printerHasPaper) {
              currentPrinter.paperPreview()
            } else {
              MenuPreview.empty()
            }
        val paperAvailable = currentPreview.state() == MenuPreview.State.READY
        page(
            "PRINTER PAPER",
            "PRINTER ROLL",
            listOf("LAST PRINT  READY", "PAPER  248 PX", "EXPORT IS NATIVE"),
            listOf(
                item("clear-paper", "CLEAR PAPER", paperAvailable),
                item("export-share-paper", "EXPORT & SHARE", paperAvailable),
                item("back", "BACK", true),
            ),
            preferredFocus = if (paperAvailable) "export-share-paper" else "back",
            preview = currentPreview,
        )
      }

      MenuRoute.DATA_MEDIA ->
          page(
              "DATA & MEDIA",
              "DATA DECK",
              listOf("BATTERY SAVE  READY", "STATE SLOT 0  READY", "SCREENSHOT  PNG"),
              listOf(
                  item("import-battery", "IMPORT BATTERY SAVE", false),
                  item("export-battery", "EXPORT BATTERY SAVE", false),
                  item("import-state-0", "IMPORT STATE SLOT 0", enabled(DesktopCommand.MANAGE_STATES)),
                  item("export-state-0", "EXPORT STATE SLOT 0", enabled(DesktopCommand.MANAGE_STATES)),
                  item("export-screenshot", "EXPORT NATIVE SCREENSHOT", enabled(DesktopCommand.SCREENSHOT)),
                  item("preview-printer-paper", "PRINTER PAPER", printerHasPaper),
                  item("back", "BACK", true),
              ),
              preferredFocus = "export-screenshot",
          )

      MenuRoute.LIBRARY ->
          page(
              "LIBRARY",
              "RECENT ROMS",
              listOf("LAST OPENED  TODAY", "DOCUMENT PICKER  NATIVE", "ZIP  MULTI-SELECT"),
              listOf(
                  item("recent-rom", "RECENT ROM", false),
                  item("open-rom", "OPEN ROM", enabled(DesktopCommand.OPEN_ROM)),
                  item("choose-rom", "CHOOSE ROM", enabled(DesktopCommand.OPEN_ROM)),
                  item("clear-recent", "CLEAR RECENTS", false),
                  item("back", "BACK", true),
              ),
              preferredFocus = "open-rom",
              headerAction = if (enabled(DesktopCommand.OPEN_ROM)) "OPEN ROM" else "",
          )

      MenuRoute.SYSTEM ->
          page(
              "SYSTEM",
              "SYSTEM PROFILE",
              listOf("VIDEO  RASTER SKIN", "PROFILE  AUTO", "REWIND  DISABLED"),
              listOf(
                  item("video-status", "VIDEO", enabled(DesktopCommand.PREFERENCES)),
                  item("profile-status", "SYSTEM PROFILE", false),
                  item("rewind-save-status", "REWIND & SAVE", enabled(DesktopCommand.PREFERENCES)),
                  item("back", "BACK", true),
              ),
              preferredFocus = "video-status",
          )

      MenuRoute.ABOUT ->
          page(
              "ABOUT",
              "COFFEE GB",
              listOf("GAME BOY EMULATOR", "MIT LICENSE", "NO NETWORK"),
              listOf(
                  item("privacy-notices", "PRIVACY & NOTICES", commands.canOpenAbout()),
                  item("network", "NO NETWORK ACCESS", false),
                  item("storage", "NO BROAD STORAGE ACCESS", false),
                  item("live-camera", "CAMERA ONLY WHEN ENABLED", false),
                  item("source-notices", "SOURCE & THIRD-PARTY NOTICES", false),
              ),
              preferredFocus = "privacy-notices",
          )

      MenuRoute.CHOOSE_ROM -> {
        val archive =
            requireNotNull(pendingArchiveSelection) {
              "${route.label()} requires an active archive selection"
            }
        val candidateItems =
            archive.candidates.map { candidate ->
              item(
                  archiveItemId(candidate),
                  archiveCandidateLabel(candidate),
                  true,
              )
            }
        page(
            "CHOOSE ROM",
            "ZIP CONTENTS",
            listOf(
                "${archive.candidates.size} ROMS FOUND",
                "SELECT ONE TO OPEN",
                "B BACK TO LIBRARY",
            ),
            candidateItems +
                item("open-selected", "OPEN SELECTED", true) +
                item("cancel", "CANCEL", true),
            preferredFocus = candidateItems.first().id(),
        )
      }

      MenuRoute.CONFIRM_ACTION -> {
        val action = confirmationAction ?: pendingConfirmation
        requireNotNull(action) { "Confirmation route requires a pending action" }
        val actionLabel = action.confirmationLabel()
        page(
            "CONFIRM ACTION",
            actionLabel,
            listOf("UNSAVED PROGRESS MAY BE LOST", "A CONFIRM", "B CANCEL"),
            listOf(
                MenuPageSpec.Item("cancel", "CANCEL", "RETURN", true),
                MenuPageSpec.Item("confirm", "CONFIRM", actionLabel, true),
            ),
        )
      }
    }
  }

  private fun handleItem(route: MenuRoute, id: String, secondary: Boolean) {
    when (route) {
      MenuRoute.PAUSE_CONSOLE ->
          when (id) {
            "resume" -> runOnEdt(::resumeAndHide)
            "save-state", "load-state" -> openRoute(MenuRoute.SAVE_STATES)
            "reset" -> openConfirmation(DesktopCommand.RESET)
            "settings" -> openRoute(MenuRoute.SETTINGS)
            "stop" -> openConfirmation(DesktopCommand.CLOSE_GAME)
          }
      MenuRoute.SAVE_STATES ->
          when {
            id.startsWith("slot-") || id.startsWith("load-slot-") -> {
              val slot =
                  (if (id.startsWith("load-slot-")) {
                    id.removePrefix("load-slot-")
                  } else {
                    id.removePrefix("slot-")
                  }).toIntOrNull() ?: return
              if (secondary || id.startsWith("load-slot-")) {
                runOnEdt {
                  hideAndResume()
                  commands.loadState(slot)
                }
              } else {
                runOnEdt { commands.saveState(slot) }
              }
            }
            id == "manage-states" -> runCommandAndHide(DesktopCommand.MANAGE_STATES)
            id == "back" -> back()
          }
      MenuRoute.SETTINGS ->
          when (id) {
            "audio" -> openRoute(MenuRoute.AUDIO)
            "touch-controls" -> openRoute(MenuRoute.TOUCH_CONTROLS)
            "controller-mapping" -> openRoute(MenuRoute.CONTROLLER_MAPPING)
            "optional-devices" -> openRoute(MenuRoute.OPTIONAL_DEVICES)
            "video" -> runPreferencesAndHide(PreferencesCategory.DISPLAY)
            "system-profile" -> openRoute(MenuRoute.SYSTEM)
            "rewind-save" -> runPreferencesAndHide(PreferencesCategory.SAVES_AND_REWIND)
            "data-media" -> openRoute(MenuRoute.DATA_MEDIA)
            "about" -> openRoute(MenuRoute.ABOUT)
            "back" -> back()
          }
      MenuRoute.AUDIO ->
          when (id) {
            "mute-audio" -> runCommandAndHide(DesktopCommand.MUTE)
            "save-audio" -> runPreferencesAndHide(PreferencesCategory.AUDIO)
            "cancel-audio" -> back()
          }
      MenuRoute.TOUCH_CONTROLS ->
          when (id) {
            "save-touch" -> runPreferencesAndHide(PreferencesCategory.CONTROLS)
            "cancel-touch" -> back()
          }
      MenuRoute.CONTROLLER_MAPPING ->
          when {
            id.startsWith("map-") || id == "invert-x" || id == "invert-y" ||
                id == "reset-controller" -> runPreferencesAndHide(PreferencesCategory.CONTROLS)
            id == "back" -> back()
          }
      MenuRoute.OPTIONAL_DEVICES ->
          when (id) {
            "live-camera", "calibrate-tilt", "save-devices" ->
                runPreferencesAndHide(PreferencesCategory.PERIPHERALS)
            "game-boy-printer" -> runPrinterAndHide { it.open() }
            "preview-printer-paper" -> openRoute(MenuRoute.PRINTER_PAPER)
            "export-share-paper" -> openRoute(MenuRoute.PRINTER_PAPER)
            "cancel-devices" -> back()
          }
      MenuRoute.PRINTER_PAPER ->
          when (id) {
            "clear-paper" ->
                if (printer?.hasPaper() == true) runPrinterAndHide { it.clear() }
            "export-share-paper" ->
                if (printer?.hasPaper() == true) runPrinterAndHide { it.export() }
            "back" -> back()
          }
      MenuRoute.DATA_MEDIA ->
          when (id) {
            "import-state-0", "export-state-0" -> runCommandAndHide(DesktopCommand.MANAGE_STATES)
            "export-screenshot" -> runCommandAndHide(DesktopCommand.SCREENSHOT)
            "preview-printer-paper" -> openRoute(MenuRoute.PRINTER_PAPER)
            "back" -> back()
          }
      MenuRoute.LIBRARY ->
          when (id) {
            "open-rom", "choose-rom" -> runNativeRomChooser()
            "back" -> back()
          }
      MenuRoute.SYSTEM ->
          when (id) {
            "video-status" -> runPreferencesAndHide(PreferencesCategory.DISPLAY)
            "rewind-save-status" -> runPreferencesAndHide(PreferencesCategory.SAVES_AND_REWIND)
            "back" -> back()
          }
      MenuRoute.ABOUT ->
          if (id == "privacy-notices") runAboutAndHide()
      MenuRoute.CONFIRM_ACTION ->
          when (id) {
            "cancel" -> back()
            "confirm" -> confirmPendingAction()
          }
      MenuRoute.CHOOSE_ROM ->
          when {
            id.startsWith("archive:") -> selectedArchiveItemId = id
            id == "open-selected" -> openSelectedArchiveCandidate()
            id == "cancel" -> cancelArchiveSelection()
          }
    }
  }

  private fun openSelectedArchiveCandidate() {
    runOnEdt {
      val pending = pendingArchiveSelection ?: return@runOnEdt
      val itemId = selectedArchiveItemId ?: archiveItemId(pending.candidates.first())
      val candidate = pending.byItemId[itemId] ?: return@runOnEdt
      pendingArchiveSelection = null
      selectedArchiveItemId = null
      controller.setBackIntercepted(false)
      hideAndResume()
      pending.onSelected(candidate)
    }
  }

  private fun cancelArchiveSelection() {
    runOnEdt {
      val pending = pendingArchiveSelection ?: return@runOnEdt
      pendingArchiveSelection = null
      selectedArchiveItemId = null
      controller.setBackIntercepted(false)
      hideAndResume()
      pending.onCancelled()
    }
  }

  private fun openConfirmation(command: DesktopCommand) {
    runOnEdt {
      if (!controller.visible() || !commands.isEnabled(command)) return@runOnEdt
      pendingConfirmation = command
      val current = commands.menuState()
      controller.setPage(pageFor(MenuRoute.CONFIRM_ACTION, current, command))
      controller.push(MenuRoute.CONFIRM_ACTION)
    }
  }

  private fun confirmPendingAction() {
    val command = pendingConfirmation ?: return
    pendingConfirmation = null
    runCommandAndHide(command)
  }

  private fun runCommandAndHide(command: DesktopCommand) {
    runOnEdt {
      hideAndResume()
      commands.invoke(command)
    }
  }

  private fun runPreferencesAndHide(category: PreferencesCategory) {
    runOnEdt {
      hideAndResume()
      commands.openPreferences(category)
    }
  }

  private fun runAboutAndHide() {
    runOnEdt {
      if (!commands.canOpenAbout()) return@runOnEdt
      hideAndResume()
      commands.openAbout()
    }
  }

  private fun runPrinterAndHide(action: (PortableMenuPrinterBridge) -> Unit) {
    runOnEdt {
      val printer = printer ?: return@runOnEdt
      hideAndResume()
      action(printer)
    }
  }

  private fun runNativeRomChooser() {
    runCommandAndHide(DesktopCommand.OPEN_ROM)
  }

  private fun openRoute(route: MenuRoute) {
    runOnEdt {
      if (!controller.visible()) return@runOnEdt
      if (route == MenuRoute.PRINTER_PAPER && printer?.hasPaper() != true) {
        return@runOnEdt
      }
      val current = commands.menuState()
      controller.setPage(pageFor(route, current))
      controller.push(route)
    }
  }

  /** Rebinds the live printer snapshot before input can act on a stale paper page. */
  private fun refreshPrinterPageBeforeInput() {
    if (!controller.visible() || controller.route() != MenuRoute.PRINTER_PAPER) return
    controller.setPage(pageFor(MenuRoute.PRINTER_PAPER, commands.menuState()))
  }

  private fun back() {
    runOnEdt {
      val route = controller.route()
      controller.back()
      if (route == MenuRoute.CONFIRM_ACTION) pendingConfirmation = null
    }
  }

  private fun resumeAndHide() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    val shouldResume = pauseOwnedByMenu || commands.menuState().commands.paused
    pauseOwnedByMenu = false
    if (controller.visible()) controller.hide()
    if (shouldResume && commands.isEnabled(DesktopCommand.PAUSE)) {
      commands.setPaused(false)
    }
  }

  private fun hideAndResume() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    val shouldResume = pauseOwnedByMenu
    pauseOwnedByMenu = false
    if (controller.visible()) controller.hide()
    if (shouldResume && commands.isEnabled(DesktopCommand.PAUSE)) {
      commands.setPaused(false)
    }
  }

  private fun render(presentation: MenuPresentation) {
    if (presentation.visible() && presentation.route() == MenuRoute.CHOOSE_ROM) {
      val pending = pendingArchiveSelection
      val focusedId =
          presentation.items().getOrNull(presentation.focusedIndex())?.id()
      if (pending != null && focusedId != null && pending.byItemId.containsKey(focusedId)) {
        selectedArchiveItemId = focusedId
      }
    }
    if (presentation.visible()) {
      val frame =
          compositor.compose(presentation).orElseThrow {
            IllegalStateException("Visible Proposal 3 presentation did not compose")
          }
      if (!renderedVisible) {
        renderedVisible = true
        onVisibilityChanged(true)
      }
      frameSink(frame)
      return
    }

    frameSink(null)
    if (renderedVisible) {
      renderedVisible = false
      onVisibilityChanged(false)
      releaseGameplaySoon()
      if (pauseOwnedByMenu) {
        pauseOwnedByMenu = false
        runOnEdt {
          if (commands.isEnabled(DesktopCommand.PAUSE)) commands.setPaused(false)
        }
      }
    }
  }

  private fun releaseGameplaySoon() {
    SwingUtilities.invokeLater(releaseGameplay)
  }

  private fun runOnEdt(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
  }

  private fun Button.toMenuKey(): MenuKey =
      when (this) {
        Button.RIGHT -> MenuKey.RIGHT
        Button.LEFT -> MenuKey.LEFT
        Button.UP -> MenuKey.UP
        Button.DOWN -> MenuKey.DOWN
        Button.A -> MenuKey.A
        Button.B -> MenuKey.B
        Button.SELECT -> MenuKey.SELECT
        Button.START -> MenuKey.START
      }

  private fun archiveItemId(candidate: RomSourceSnapshot.ArchiveCandidate): String =
      "archive:${candidate.token()}"

  private fun DesktopCommand.confirmationLabel(): String =
      when (this) {
        DesktopCommand.RESET -> "RESET GAME"
        DesktopCommand.CLOSE_GAME -> "STOP GAME"
        else -> error("Unsupported confirmation command: $this")
      }
}

/**
 * Host seam for archive entries discovered by [RomOpenService]. The host owns only presentation;
 * the callbacks return to [DesktopRomOpen], which revalidates request ownership before touching
 * the service.
 */
internal interface DesktopArchiveSelectionHost {
  fun showArchiveSelection(
      requestId: Long,
      candidates: List<RomSourceSnapshot.ArchiveCandidate>,
      onSelected: (RomSourceSnapshot.ArchiveCandidate) -> Unit,
      onCancelled: () -> Unit,
  )

  fun closeArchiveSelection(requestId: Long)
}

/** Command boundary keeps the native Swing action implementations outside the portable model. */
internal interface PortableMenuCommandBridge {
  fun menuState(): DesktopPresentation

  fun isEnabled(command: DesktopCommand): Boolean

  fun invoke(command: DesktopCommand)

  fun canOpenAbout(): Boolean

  fun openAbout()

  fun setPaused(paused: Boolean)

  fun openPreferences(category: PreferencesCategory)

  fun canSaveState(slot: Int): Boolean

  fun canLoadState(slot: Int): Boolean

  fun saveState(slot: Int)

  fun loadState(slot: Int)
}

/** The printer keeps its existing modeless Swing window and native export chooser. */
internal interface PortableMenuPrinterBridge {
  fun hasPaper(): Boolean

  fun paperPreview(): MenuPreview

  fun open()

  fun clear()

  fun export()
}
