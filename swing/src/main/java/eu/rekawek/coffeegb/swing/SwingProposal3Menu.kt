package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.swing.io.DesktopMenuInputCapture
import eu.rekawek.coffeegb.swing.io.DesktopMenuKeyboardInput
import eu.rekawek.coffeegb.ui.menu.MenuController
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec
import eu.rekawek.coffeegb.ui.menu.PauseMenuSnapshot
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
    private val capturePausePreview: () -> MenuPreview = { MenuPreview.empty() },
) : DesktopMenuInputCapture, DesktopMenuKeyboardInput, DesktopArchiveSelectionHost {

  private enum class StateMenuMode { SAVE, LOAD }

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

            override fun onItemAdjusted(route: MenuRoute, id: String, direction: Int) {
              handleItemAdjusted(route, id, direction)
            }

            override fun onHeaderSelected(route: MenuRoute) {
              // Proposal 3 uses B for back navigation and exposes actions only as menu rows.
            }

            override fun onBackIntercepted(route: MenuRoute) {
              if (route == MenuRoute.CHOOSE_ROM) {
                cancelArchiveSelection()
              } else if (route == MenuRoute.OPTION_PICKER) {
                cancelChoiceSelection()
              }
            }
          })

  private val gamepadHeld = EnumSet.noneOf(Button::class.java)

  @Volatile private var opening = false

  @Volatile private var chordLatched = false

  private var pauseOwnedByMenu = false

  /** Captured before root pause stops the live frame source; retained through child routes. */
  private var pauseSnapshot: PauseMenuSnapshot? = null

  private var pendingConfirmation: DesktopCommand? = null

  private var pendingArchiveSelection: PendingArchiveSelection? = null

  /** The choice page is transient; never let a stale selection survive lifecycle teardown. */
  private data class PendingChoice(
      val settingId: String,
      val originRoute: MenuRoute,
  )

  private var pendingChoice: PendingChoice? = null

  private var selectedArchiveItemId: String? = null

  private var stateMenuMode = StateMenuMode.SAVE
  private var stateFocusedItemId: String? = null
  private var recentFocusedItemId: String? = null

  private var renderedVisible = false

  init {
    // Catalog delivery is asynchronous.  The registry publishes only detached data; rebuild
    // the visible state page when it arrives, while ignoring updates for other routes.
    commands.addStateCatalogListener {
      runOnEdt {
        if (controller.visible() && controller.route() == MenuRoute.SAVE_STATES) {
          controller.setPage(pageFor(MenuRoute.SAVE_STATES, commands.menuState()))
        }
      }
    }
    commands.addRecentGamesListener {
      runOnEdt {
        if (controller.visible() && controller.route() == MenuRoute.RECENT_GAMES) {
          controller.setPage(pageFor(MenuRoute.RECENT_GAMES, commands.menuState()))
        }
      }
    }
  }

  override fun visible(): Boolean = controller.visible() || opening

  /** Opens the menu from a desktop command or test seam on the EDT. */
  internal fun openFromDesktop() {
    runOnEdt(::openOnEdt)
  }

  /** Hides the menu during ROM/controller lifecycle transitions without resuming a new session. */
  internal fun closeForLifecycle() {
    runOnEdt {
      pauseOwnedByMenu = false
      pauseSnapshot = null
      pendingConfirmation = null
      pendingArchiveSelection = null
      pendingChoice = null
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
    if (opening) return true
    // An activation can hide the menu synchronously (for example OPEN ROM).  Let the portable
    // controller release its captured edge even after that transition so Start/A never leaks
    // into the game or reports a spurious unhandled key-up to the host.
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

  internal fun visibleItemIdsForTest(): List<String> {
    if (!controller.visible()) return emptyList()
    return controller.presentation().items().map { it.id() }
  }

  internal fun presentationForTest(): MenuPresentation = controller.presentation()

  internal fun openRouteForTest(route: MenuRoute) {
    openRoute(route)
  }

  private fun openOnEdt() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu must open on the EDT" }
    if (opening || controller.visible()) return
    val current = commands.menuState()
    if (current.commands.sessionBusy) return

    if (!current.commands.gameLoaded) {
      // The desktop's idle surface follows Android: the portable Library, rather than a paused
      // console, is the entry point when no ROM is active. There is nothing to capture or pause.
      pauseSnapshot = null
      controller.setPage(pageFor(MenuRoute.LIBRARY, current))
      controller.show(MenuRoute.LIBRARY)
      return
    }

    val title = checkNotNull(current.gameTitle) { "Loaded session has no ROM title" }
    // Freeze the actual display before requesting pause; a child route must reuse this snapshot.
    pauseSnapshot =
        PauseMenuSnapshot(
            title,
            current.playTimeNanos,
            current.batterySaveActive,
            capturePausePreview(),
        )
    controller.setPage(pageFor(MenuRoute.PAUSE_CONSOLE, current))
    pauseOwnedByMenu = current.commands.pauseSupported && !current.commands.paused
    if (pauseOwnedByMenu) {
      commands.setPaused(true)
    }
    controller.show(MenuRoute.PAUSE_CONSOLE)
    releaseGameplaySoon()
  }

  private fun openStateRoute(mode: StateMenuMode) {
    runOnEdt {
      // Physical controller input is delivered by the gamepad poller.  Keep the detached state
      // catalog and all Swing-owned route state on the EDT, and discard an activation that became
      // stale while it was queued.
      if (!controller.visible() || controller.route() != MenuRoute.PAUSE_CONSOLE) return@runOnEdt
      stateMenuMode = mode
      stateFocusedItemId = "slot-0"
      commands.refreshStateCatalog()
      val current = commands.menuState()
      controller.setPage(pageFor(MenuRoute.SAVE_STATES, current))
      controller.push(MenuRoute.SAVE_STATES)
    }
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

    fun item(
        id: String,
        label: String,
        detail: String,
        isEnabled: Boolean,
        adjustable: Boolean = false,
        progress: Int = -1,
    ) = MenuPageSpec.Item(id, label, detail, isEnabled, null, adjustable, progress)

    fun page(
        context: String,
        sideHeading: String,
        sideLines: List<String>,
        items: List<MenuPageSpec.Item>,
        preferredFocus: String? = null,
        headerAction: String = "",
        preview: MenuPreview = MenuPreview.empty(),
        footerHints: List<String> = listOf("D-PAD MOVE", "A CHOOSE", "B BACK"),
        columns: Int = 1,
    ) = MenuPageSpec(
        route,
        "COFFEE GB",
        context,
        headerAction,
        sideHeading,
        sideLines,
        items,
        columns,
        footerHints,
        preferredFocus ?: items.firstOrNull { it.enabled() }?.id() ?: items.first().id(),
        preview,
    )

    fun unavailablePage(context: String, id: String) =
        page(
            context,
            "",
            emptyList(),
            listOf(item(id, "NOT AVAILABLE", true)),
            preferredFocus = id,
            footerHints = listOf("", "", "B BACK"),
        )

    fun hasEnabledNonBack(items: List<MenuPageSpec.Item>): Boolean =
        items.any { it.id() != "back" && it.enabled() }

    fun peripheralsItems(snapshot: PortableMenuSettingsSnapshot?): List<MenuPageSpec.Item> =
        if (snapshot == null) {
          listOf(item("peripherals-status", "NOT AVAILABLE", true))
        } else {
          listOf(
              item(
                  PortableMenuSettingId.CAMERA,
                  "CAMERA",
                  snapshot.displayValue(PortableMenuSettingId.CAMERA)?.uppercase().orEmpty(),
                  true,
              ),
              item(
                  PortableMenuSettingId.GAMEPAD,
                  "GAMEPAD",
                  snapshot.displayValue(PortableMenuSettingId.GAMEPAD)?.uppercase().orEmpty(),
                  true,
              ),
          )
        }

    val state = presentation.commands
    val stateAvailable = state.stateCommandsAvailable && !state.sessionBusy
    val inlineAudioAvailable = commands.audioVolume() != null || enabled(DesktopCommand.MUTE)
    val settings = commands.settingsSnapshot()
    val printerHasPaper = printer?.hasPaper() == true
    return when (route) {
      MenuRoute.PAUSE_CONSOLE -> {
        val snapshot = requireNotNull(pauseSnapshot) {
          "Pause menu must capture its immutable snapshot before building the root page"
        }
        page(
            "",
            snapshot.romTitle(),
            listOf(
                "PLAY TIME",
                snapshot.formattedPlayTime(),
                if (snapshot.batterySaveActive()) "BATTERY SAVE ACTIVE" else "NO BATTERY SAVE",
            ),
            listOf(
                item("resume", "RESUME", enabled(DesktopCommand.PAUSE)),
                item("save-state", "SAVE STATE", stateAvailable),
                item("load-state", "LOAD STATE", stateAvailable),
                item("open-rom", "OPEN ROM", enabled(DesktopCommand.OPEN_ROM)),
              item("reset", "RESET GAME", enabled(DesktopCommand.RESET)),
              item("settings", "SETTINGS", settings != null || inlineAudioAvailable),
                item("recent-games", "RECENT GAMES", commands.canOpenRecentGame()),
            ),
            preview = snapshot.preview(),
        )
      }

      MenuRoute.SAVE_STATES -> {
        val catalog = commands.stateSlots()
        val slots =
            (0..9).map { slot ->
              // "USED" is presentation metadata only: the shared compositor turns it into the
              // occupied-slot seal without showing legacy status text in the state list.
              MenuPageSpec.Item(
                  "slot-$slot",
                  "SLOT $slot",
                  if (catalog.firstOrNull { it.index == slot }?.loadable == true) "USED" else "",
                  true,
              )
            }
        val mode = if (stateMenuMode == StateMenuMode.SAVE) "SAVE" else "LOAD"
        val focused = stateFocusedItemId ?: "slot-0"
        val focusedSlot =
            catalog.firstOrNull { "slot-${it.index}" == focused }
        val preview =
            focusedSlot?.preview ?: MenuPreview.empty()
        val savedAt = portableStateSavedAt(focusedSlot?.savedAt)
        page(
            "${mode} STATES",
            "",
            if (savedAt == null) emptyList() else listOf(savedAt),
            slots,
            preferredFocus = focused,
            headerAction = "",
            preview = preview,
            footerHints = listOf("D-PAD MOVE", "A $mode", "B BACK"),
        )
      }

      MenuRoute.RECENT_GAMES -> {
        val games = commands.recentGames()
        val recentGames =
            games.mapIndexed { index, game ->
              MenuPageSpec.RecentGame(
                  "recent:$index",
                  (if (game.active) "CURRENT / ${game.label}" else game.label).uppercase(),
                  recentLastPlayed(game),
                  commands.canOpenRecentGame(),
                  recentPreview(game),
              )
            }
        return MenuPageSpec.recentGames(recentGames, recentFocusedItemId)
      }

      MenuRoute.SETTINGS -> {
        // Older host bridges only know about Audio. Production Swing advertises the complete
        // typed settings capability and therefore has exactly these four rows.
        if (settings == null) {
          val settingsItems =
              if (inlineAudioAvailable) listOf(item("audio", "AUDIO", true))
              else listOf(item("settings-status", "NOT AVAILABLE", true))
          if (inlineAudioAvailable) {
            page("SETTINGS", "", emptyList(), settingsItems, preferredFocus = "audio")
          } else {
            unavailablePage("SETTINGS", "settings-status")
          }
        } else {
          page(
              "SETTINGS",
              "",
              emptyList(),
              listOf(
                  item("system", "SYSTEM", true),
                  item("display", "DISPLAY", true),
                  item("audio", "AUDIO", inlineAudioAvailable),
                  item("peripherals", "PERIPHERALS", true),
              ),
              preferredFocus = "system",
          )
        }
      }

      MenuRoute.AUDIO ->
          run {
            val volume = commands.audioVolume()
            val audioItems =
                buildList {
                  if (volume != null) {
                    add(item("volume", "VOLUME", "$volume%", true, adjustable = true, progress = volume))
                  }
                  add(
                      item(
                          "mute-audio",
                          "MUTE",
                          if (state.muted) "ON" else "OFF",
                          enabled(DesktopCommand.MUTE),
                      ))
                }
            if (audioItems.any { it.enabled() }) {
              page(
                  "AUDIO",
                  "",
                  emptyList(),
                  audioItems,
                  preferredFocus = if (volume != null) "volume" else "mute-audio",
              )
            } else {
              unavailablePage("AUDIO", "audio-status")
            }
          }

      MenuRoute.TOUCH_CONTROLS ->
          page(
              "CONTROLS",
              "",
              emptyList(),
              // Swing has no touch layout editor. Keep a restored legacy route safe and honest.
              listOf(item("unavailable", "NOT AVAILABLE", true)),
              preferredFocus = "unavailable",
              footerHints = listOf("", "", "B BACK"),
          )

      MenuRoute.CONTROLLER_MAPPING ->
          page(
              "CONTROLLER MAPPING",
              "",
              emptyList(),
              // Mapping is omitted until it can be completed inside the overlay. A stale route
              // must never expose placeholder axes or hand control to another UI surface.
              listOf(item("unavailable", "NOT AVAILABLE", true)),
              preferredFocus = "unavailable",
              footerHints = listOf("", "", "B BACK"),
          )

      MenuRoute.OPTIONAL_DEVICES ->
          if (settings == null) {
            // Compatibility for old host bridges that predate the typed settings port. The
            // restored route is intentionally inert.
            page(
                "OPTIONAL DEVICES",
                "",
                emptyList(),
                listOf(item("peripherals-status", "NOT AVAILABLE", true)),
                preferredFocus = "peripherals-status",
                footerHints = listOf("", "", "B BACK"),
            )
          } else {
            page(
                "PERIPHERALS",
                "",
                emptyList(),
                peripheralsItems(settings),
                preferredFocus = peripheralsItems(settings).firstOrNull()?.id(),
            )
          }

      MenuRoute.PRINTER_PAPER -> {
        val currentPrinter = printer
        val currentPreview =
            if (currentPrinter != null && printerHasPaper) {
              currentPrinter.paperPreview()
            } else {
              MenuPreview.empty()
            }
        val paperAvailable = currentPreview.state() == MenuPreview.State.READY
        val paperItems =
            if (paperAvailable) {
              listOf(
                  item("clear-paper", "CLEAR PAPER", true),
                  item("export-share-paper", "EXPORT & SHARE", true),
              )
            } else {
              // Back rows are removed by the shared menu model. Keep the refreshed page valid
              // when the paper disappears, while leaving navigation to the global B action.
              listOf(item("no-paper", "NO PAPER", true))
            }
        page(
            "PRINTER PAPER",
            "PRINTER ROLL",
            if (paperAvailable) {
              listOf("LAST PRINT  READY", "PAPER  248 PX", "EXPORT IS NATIVE")
            } else {
              emptyList()
            },
            paperItems,
            preferredFocus = if (paperAvailable) "export-share-paper" else "no-paper",
            preview = currentPreview,
            footerHints =
                if (paperAvailable) listOf("D-PAD MOVE", "A CHOOSE", "B BACK")
                else listOf("", "", "B BACK"),
        )
      }

      MenuRoute.DATA_MEDIA -> {
        val dataItems =
            listOf(
                item("import-battery", "IMPORT BATTERY SAVE", false),
                item("export-battery", "EXPORT BATTERY SAVE", false),
                item("import-state-0", "IMPORT STATE SLOT 0", enabled(DesktopCommand.MANAGE_STATES)),
                item("export-state-0", "EXPORT STATE SLOT 0", enabled(DesktopCommand.MANAGE_STATES)),
                item("export-screenshot", "EXPORT NATIVE SCREENSHOT", enabled(DesktopCommand.SCREENSHOT)),
                item("preview-printer-paper", "PRINTER PAPER", printerHasPaper),
                item("back", "BACK", true),
            )
        if (hasEnabledNonBack(dataItems)) {
          page(
              "DATA & MEDIA",
              "DATA DECK",
              listOf("BATTERY SAVE  READY", "STATE SLOT 0  READY", "SCREENSHOT  PNG"),
              dataItems,
              preferredFocus = "export-screenshot",
          )
        } else {
          unavailablePage("DATA & MEDIA", "data-status")
        }
      }

      MenuRoute.LIBRARY -> {
        val libraryItems =
            listOf(
                item("recent-games", "RECENT GAMES", commands.canOpenRecentGame()),
                item("open-rom", "OPEN ROM", enabled(DesktopCommand.OPEN_ROM)),
                item("settings", "SETTINGS", settings != null || inlineAudioAvailable),
            )
        if (hasEnabledNonBack(libraryItems)) {
          page(
              "LIBRARY",
              "",
              emptyList(),
              libraryItems,
              preferredFocus = libraryItems.firstOrNull { it.enabled() }?.id(),
              headerAction = "",
          )
        } else {
          unavailablePage("LIBRARY", "library-status")
        }
      }

      MenuRoute.SYSTEM -> {
        val systemItems =
            listOf(
                item(
                    PortableMenuSettingId.DMG_GAMES,
                    "DMG GAMES",
                    settings?.displayValue(PortableMenuSettingId.DMG_GAMES)?.uppercase().orEmpty(),
                    settings != null,
                ),
                item(
                    PortableMenuSettingId.CGB_GAMES,
                    "CGB GAMES",
                    settings?.displayValue(PortableMenuSettingId.CGB_GAMES)?.uppercase().orEmpty(),
                    settings != null,
                ),
                item(
                    PortableMenuSettingId.BOOTSTRAP,
                    "BOOTSTRAP",
                    settings?.displayValue(PortableMenuSettingId.BOOTSTRAP)?.uppercase().orEmpty(),
                    settings != null,
                ),
            )
        if (settings != null) {
          page(
              "SYSTEM",
              "",
              emptyList(),
              systemItems,
              preferredFocus = "dmg-games",
          )
        } else {
          unavailablePage("SYSTEM", "system-status")
        }
      }

      // DISPLAY and OPTION_PICKER are supplied by the portable route model. Keep all dynamic
      // values host-owned so this Swing renderer never needs a native settings surface.
      MenuRoute.DISPLAY -> {
        val displayItems =
            listOf(
                item(
                    PortableMenuSettingId.SGB_BORDER,
                    "SGB BORDER",
                    settings?.displayValue(PortableMenuSettingId.SGB_BORDER)?.uppercase().orEmpty(),
                    settings != null && PortableMenuSettingId.SGB_BORDER in settings.toggleIds,
                ),
                item(
                    PortableMenuSettingId.DMG_COLORS,
                    "DMG COLORS",
                    settings?.displayValue(PortableMenuSettingId.DMG_COLORS)?.uppercase().orEmpty(),
                    settings != null,
                ),
        )
        if (settings != null) {
          page("DISPLAY", "", emptyList(), displayItems, preferredFocus = "sgb-border")
        } else {
          unavailablePage("DISPLAY", "display-status")
        }
      }

      MenuRoute.OPTION_PICKER -> {
        val pending = pendingChoice
        val pickerSettings = settings
        if (pending == null || pickerSettings == null) {
          unavailablePage("CHOOSE", "choice-status")
        } else {
          val committed = pickerSettings.value(pending.settingId)
          val options = pickerSettings.choicesFor(pending.settingId)
          val choices =
              options.map { choice ->
                item(
                    "choice:${choice.token}",
                    choice.label.uppercase(),
                    if (choice.token == committed) "SELECTED" else "",
                    choice.enabled,
                )
              }
          page(
              choiceContextLabel(pending.settingId),
              "",
              emptyList(),
              choices,
              preferredFocus = choices.firstOrNull { it.id() == "choice:$committed" }?.id(),
          )
        }
      }

      MenuRoute.ABOUT -> {
        val aboutItems =
            listOf(
                item("privacy-notices", "PRIVACY & NOTICES", commands.canOpenAbout()),
                item("network", "NO NETWORK ACCESS", false),
                item("storage", "NO BROAD STORAGE ACCESS", false),
                item("live-camera", "CAMERA ONLY WHEN ENABLED", false),
                item("source-notices", "SOURCE & THIRD-PARTY NOTICES", false),
            )
        if (hasEnabledNonBack(aboutItems)) {
          page(
              "ABOUT",
              "COFFEE GB",
              listOf("GAME BOY EMULATOR", "MIT LICENSE", "NO NETWORK"),
              aboutItems,
              preferredFocus = "privacy-notices",
          )
        } else {
          unavailablePage("ABOUT", "about-status")
        }
      }

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
            listOf("UNSAVED PROGRESS MAY BE LOST"),
            listOf(
                MenuPageSpec.Item("cancel", "CANCEL", "RETURN", true),
                MenuPageSpec.Item("confirm", "CONFIRM", actionLabel, true),
            ),
            columns = 2,
        )
      }
    }
  }

  private fun handleItem(route: MenuRoute, id: String, secondary: Boolean) {
    when (route) {
      MenuRoute.PAUSE_CONSOLE ->
          when (id) {
            "resume" -> runOnEdt(::resumeAndHide)
            "save-state" -> openStateRoute(StateMenuMode.SAVE)
            "load-state" -> openStateRoute(StateMenuMode.LOAD)
            "open-rom" -> runNativeRomChooser()
            "reset" -> openConfirmation(DesktopCommand.RESET)
            "settings" -> openRoute(MenuRoute.SETTINGS)
            "recent-games" ->
                if (commands.canOpenRecentGame()) openRoute(MenuRoute.RECENT_GAMES)
          }
      MenuRoute.SAVE_STATES ->
          if (id.startsWith("slot-")) {
            val slot = id.removePrefix("slot-").toIntOrNull() ?: return
            runOnEdt {
              if (!controller.visible() || controller.route() != MenuRoute.SAVE_STATES) {
                return@runOnEdt
              }
              stateFocusedItemId = id
              if (stateMenuMode == StateMenuMode.LOAD) {
                // The detached catalog is authoritative for the on-screen ten-slot page.  Do not
                // fall back to the desktop toolbar's separately selected slot while the catalog is
                // loading or when this focused slot is empty.
                if (commands.stateSlots().firstOrNull { it.index == slot }?.loadable == true) {
                  hideAndResume()
                  commands.loadState(slot)
                }
              } else {
                commands.saveState(slot)
              }
            }
          }
      MenuRoute.RECENT_GAMES ->
          if (id.startsWith("recent:")) {
            runOnEdt {
              if (!controller.visible() || controller.route() != MenuRoute.RECENT_GAMES) {
                return@runOnEdt
              }
              val index = id.removePrefix("recent:").toIntOrNull() ?: return@runOnEdt
              val game = commands.recentGames().getOrNull(index) ?: return@runOnEdt
              if (!commands.canOpenRecentGame()) return@runOnEdt
              recentFocusedItemId = id
              hideAndResume()
              commands.openRecentGame(game)
            }
          }
      MenuRoute.SETTINGS ->
          when (id) {
            "system" -> openRoute(MenuRoute.SYSTEM)
            "display" -> openRoute(MenuRoute.DISPLAY)
            "audio" -> openRoute(MenuRoute.AUDIO)
            "peripherals" -> openRoute(MenuRoute.OPTIONAL_DEVICES)
          }
      MenuRoute.SYSTEM ->
          if (id in
              setOf(
                  PortableMenuSettingId.DMG_GAMES,
                  PortableMenuSettingId.CGB_GAMES,
                  PortableMenuSettingId.BOOTSTRAP,
              )) {
            openChoice(id, MenuRoute.SYSTEM)
          }
      MenuRoute.DISPLAY ->
          when (id) {
            PortableMenuSettingId.SGB_BORDER ->
                runSettingsToggleInPlace(PortableMenuSettingId.SGB_BORDER, MenuRoute.DISPLAY)
            PortableMenuSettingId.DMG_COLORS -> openChoice(id, MenuRoute.DISPLAY)
          }
      MenuRoute.OPTIONAL_DEVICES ->
          when (id) {
            PortableMenuSettingId.CAMERA -> openChoice(id, MenuRoute.OPTIONAL_DEVICES)
            PortableMenuSettingId.GAMEPAD -> openChoice(id, MenuRoute.OPTIONAL_DEVICES)
          }
      MenuRoute.OPTION_PICKER ->
          if (id.startsWith("choice:")) {
            runOnEdt {
              val pending = pendingChoice ?: return@runOnEdt
              val token = id.removePrefix("choice:")
              if (commands.settingsSnapshot()?.choicesFor(pending.settingId)?.any {
                    it.token == token && it.enabled
                  } == true) {
                commands.applySettingsChoice(pending.settingId, token)
                pendingChoice = null
                controller.setBackIntercepted(false)
                controller.setPage(pageFor(pending.originRoute, commands.menuState()))
                controller.back()
              }
            }
          }
      MenuRoute.AUDIO ->
          when (id) {
            // Mute is a session action, so apply it in place and keep the settings overlay open.
            // This also lets the row immediately reflect the new state.
            "mute-audio" -> runCommandInPlace(DesktopCommand.MUTE, MenuRoute.AUDIO)
          }
      // Legacy/restored control routes deliberately have no activation. B navigation is handled
      // centrally by MenuController.
      MenuRoute.TOUCH_CONTROLS, MenuRoute.CONTROLLER_MAPPING -> Unit
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
            "recent-games" -> if (commands.canOpenRecentGame()) openRoute(MenuRoute.RECENT_GAMES)
            "open-rom" -> runNativeRomChooser()
            "settings" -> openRoute(MenuRoute.SETTINGS)
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

  private fun handleItemAdjusted(route: MenuRoute, id: String, direction: Int) {
    if (route != MenuRoute.AUDIO || id != "volume" || direction !in -1..1) return
    runOnEdt {
      if (!controller.visible() || controller.route() != MenuRoute.AUDIO) return@runOnEdt
      val current = commands.audioVolume() ?: return@runOnEdt
      commands.setAudioVolume((current + direction * 5).coerceIn(0, 100))
      controller.setPage(pageFor(MenuRoute.AUDIO, commands.menuState()))
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

  private fun cancelChoiceSelection() {
    runOnEdt {
      if (pendingChoice == null) return@runOnEdt
      pendingChoice = null
      controller.setBackIntercepted(false)
      controller.back()
    }
  }

  private fun choiceContextLabel(settingId: String): String =
      when (settingId) {
        PortableMenuSettingId.DMG_GAMES -> "DMG GAMES"
        PortableMenuSettingId.CGB_GAMES -> "CGB GAMES"
        PortableMenuSettingId.BOOTSTRAP -> "BOOTSTRAP"
        PortableMenuSettingId.DMG_COLORS -> "DMG COLORS"
        PortableMenuSettingId.CAMERA -> "CAMERA"
        PortableMenuSettingId.GAMEPAD -> "GAMEPAD"
        else -> "CHOOSE"
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

  /** Applies a session setting without handing control to a native desktop surface. */
  private fun runCommandInPlace(command: DesktopCommand, route: MenuRoute) {
    runOnEdt {
      if (!controller.visible() || controller.route() != route || !commands.isEnabled(command)) {
        return@runOnEdt
      }
      commands.invoke(command)
      // Command handlers publish their new presentation synchronously on the EDT in production.
      // Rebuilding the page here keeps the value shown in the row in sync even when a host bridge
      // does not emit a separate menu repaint event.
      if (controller.visible() && controller.route() == route) {
        controller.setPage(pageFor(route, commands.menuState()))
      }
    }
  }

  private fun openChoice(settingId: String, originRoute: MenuRoute) {
    runOnEdt {
      if (!controller.visible()) return@runOnEdt
      val settings = commands.settingsSnapshot() ?: return@runOnEdt
      if (settings.choicesFor(settingId).isEmpty()) return@runOnEdt
      pendingChoice = PendingChoice(settingId, originRoute)
      val current = commands.menuState()
      controller.setPage(pageFor(MenuRoute.OPTION_PICKER, current))
      controller.setBackIntercepted(true)
      controller.push(MenuRoute.OPTION_PICKER)
    }
  }

  private fun runSettingsToggleInPlace(settingId: String, route: MenuRoute) {
    runOnEdt {
      if (!controller.visible() || controller.route() != route) return@runOnEdt
      val settings = commands.settingsSnapshot() ?: return@runOnEdt
      if (settingId !in settings.toggleIds) return@runOnEdt
      commands.toggleSettings(settingId)
      if (controller.visible() && controller.route() == route) {
        controller.setPage(pageFor(route, commands.menuState()))
      }
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
      if (route == MenuRoute.OPTION_PICKER) {
        pendingChoice = null
        controller.setBackIntercepted(false)
      }
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
    if (presentation.visible() &&
        (presentation.route() == MenuRoute.SAVE_STATES ||
            presentation.route() == MenuRoute.RECENT_GAMES) &&
        !SwingUtilities.isEventDispatchThread()) {
      // MenuController is thread-safe, but the detached catalog and mode/focus fields are Swing
      // presentation state. Physical gamepad edges originate on the poller, so coalesce these
      // catalog-backed page repaints onto the EDT before reading that state.
      runOnEdt {
        if (controller.visible() &&
            (controller.route() == MenuRoute.SAVE_STATES ||
                controller.route() == MenuRoute.RECENT_GAMES)) {
          render(controller.presentation())
        }
      }
      return
    }
    if (presentation.visible() && presentation.route() == MenuRoute.SAVE_STATES) {
      val focusedId = presentation.items().getOrNull(presentation.focusedIndex())?.id()
      if (focusedId != null) stateFocusedItemId = focusedId
      val desiredPreview =
          commands.stateSlots().firstOrNull { "slot-${it.index}" == focusedId }?.preview
              ?: MenuPreview.empty()
      val focusedSlot =
          commands.stateSlots().firstOrNull { "slot-${it.index}" == focusedId }
      val desiredSideLines = portableStateSavedAt(focusedSlot?.savedAt)
          ?.let { listOf(it) }
          ?: emptyList()
      if (presentation.preview() !== desiredPreview || presentation.sideLines() != desiredSideLines) {
        controller.setPage(pageFor(MenuRoute.SAVE_STATES, commands.menuState()))
        return
      }
    }
    if (presentation.visible() && presentation.route() == MenuRoute.RECENT_GAMES) {
      val focusedId = presentation.items().getOrNull(presentation.focusedIndex())?.id()
      if (focusedId?.startsWith("recent:") == true) recentFocusedItemId = focusedId
      val selected =
          focusedId?.removePrefix("recent:")?.toIntOrNull()?.let { commands.recentGames().getOrNull(it) }
      val desiredPreview = selected?.let(::recentPreview) ?: MenuPreview.empty()
      val desiredSideLines =
          if (selected == null) emptyList()
          else listOf("LAST PLAYED: ${recentLastPlayed(selected)}")
      if (presentation.preview() !== desiredPreview || presentation.sideLines() != desiredSideLines) {
        controller.setPage(pageFor(MenuRoute.RECENT_GAMES, commands.menuState()))
        return
      }
    }
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
      // A hidden setPage notification occurs while opening. Only discard the immutable capture
      // after an actual visible-to-hidden transition, not during root-page preparation.
      pauseSnapshot = null
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

  private fun recentPreview(game: PortableMenuRecentGame): MenuPreview {
    if (!game.active) return game.preview
    val captured = pauseSnapshot?.preview() ?: return game.preview
    return if (captured.state() == MenuPreview.State.READY) captured else game.preview
  }

  private fun recentLastPlayed(game: PortableMenuRecentGame): String {
    if (!game.active) return game.lastPlayed
    val playTime = pauseSnapshot?.formattedPlayTime()
    return if (playTime == null) "JUST NOW" else "JUST NOW / $playTime"
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

  /** Typed in-screen settings access; null is retained for older/test Audio-only bridges. */
  fun settingsSnapshot(): PortableMenuSettingsSnapshot? = null

  fun applySettingsChoice(id: String, token: String) = Unit

  fun toggleSettings(id: String) = Unit

  /** Optional live audio controls; null means the host cannot edit audio in this overlay. */
  fun audioVolume(): Int? = null

  fun setAudioVolume(volume: Int) = Unit

  fun canSaveState(slot: Int): Boolean

  fun canLoadState(slot: Int): Boolean

  fun saveState(slot: Int)

  fun loadState(slot: Int)

  fun stateSlots(): List<PortableMenuStateSlot> = emptyList()

  fun refreshStateCatalog() = Unit

  /** Called on the EDT when a detached catalog/thumbnail snapshot is replaced. */
  fun addStateCatalogListener(listener: () -> Unit) = Unit

  fun recentGames(): List<PortableMenuRecentGame> = emptyList()

  fun canOpenRecentGame(): Boolean = false

  fun openRecentGame(game: PortableMenuRecentGame) = Unit

  /** Called on the EDT when the detached recent-game thumbnail catalog is replaced. */
  fun addRecentGamesListener(listener: () -> Unit) = Unit
}

/** The printer keeps its existing modeless Swing window and native export chooser. */
internal interface PortableMenuPrinterBridge {
  fun hasPaper(): Boolean

  fun paperPreview(): MenuPreview

  fun open()

  fun clear()

  fun export()
}
