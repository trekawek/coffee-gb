package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.swing.io.DesktopMenuInputCapture
import eu.rekawek.coffeegb.swing.io.DesktopMenuKeyboardInput
import eu.rekawek.coffeegb.ui.menu.MenuController
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuPageLayout
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec
import eu.rekawek.coffeegb.ui.menu.MenuPagination
import eu.rekawek.coffeegb.ui.menu.PauseMenuSnapshot
import eu.rekawek.coffeegb.ui.menu.MenuPresentation
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import eu.rekawek.coffeegb.ui.menu.MenuRoute
import eu.rekawek.coffeegb.ui.menu.MenuWidgetType
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame
import eu.rekawek.coffeegb.ui.menu.artwork.Proposal3MenuCompositor
import java.util.EnumSet
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val FILE_BROWSER_PAGE_SIZE = 7
private const val FILE_BROWSER_MARQUEE_FRAME_MILLIS = 50
private val SESSION_RESTART_SETTING_IDS =
    setOf(
        PortableMenuSettingId.DMG_GAMES,
        PortableMenuSettingId.CGB_GAMES,
        PortableMenuSettingId.BOOTSTRAP,
        PortableMenuSettingId.EXECUTION_MODE,
    )
private val FILE_BROWSER_EXECUTOR =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "coffee-gb-rom-browser").apply { isDaemon = true }
    }

/**
 * Swing host for the complete Proposal 3 route tree.
 *
 * <p>Each route is still rendered by the portable compositor. This host only supplies route
 * state, controller capture, and a command bridge to existing desktop actions. Unsupported
 * operations remain disabled. Open ROM uses a full-width, asynchronous filesystem page here while
 * the desktop File menu and printer export keep their native Swing dialogs. Multi-ROM archives
 * continue through the portable CHOOSE_ROM page.</p>
 */
internal class SwingProposal3Menu(
    private val frameSink: (MenuArgbFrame?) -> Unit,
    private val commands: PortableMenuCommandBridge,
    private val releaseGameplay: () -> Unit,
    private val printer: PortableMenuPrinterBridge? = null,
    private val onVisibilityChanged: (Boolean) -> Unit = {},
    private val capturePausePreview: () -> MenuPreview = { MenuPreview.empty() },
    private val romFileBrowser: DesktopRomFileBrowser = DesktopRomFileBrowser(),
    private val fileBrowserExecutor: Executor = FILE_BROWSER_EXECUTOR,
) : DesktopMenuInputCapture, DesktopMenuKeyboardInput, DesktopArchiveSelectionHost {

  private enum class StateMenuMode { SAVE, LOAD }

  private data class PendingArchiveSelection(
      val requestId: Long,
      val candidates: List<RomSourceSnapshot.ArchiveCandidate>,
      val byItemId: Map<String, RomSourceSnapshot.ArchiveCandidate>,
      val onSelected: (RomSourceSnapshot.ArchiveCandidate) -> Unit,
      val onCancelled: () -> Unit,
  )

  private data class FileBrowserRow(
      val id: String,
      val label: String,
      val entry: DesktopRomFileBrowser.Entry?,
  )

  private data class FileBrowserState(
      val requestId: Long,
      val initialLocation: Path,
      val location: Path?,
      val rows: List<FileBrowserRow>,
      val viewportStart: Int,
      val focusedIndex: Int,
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

            override fun onPageRequested(route: MenuRoute, targetIndex: Int) {
              if (route == MenuRoute.FILE_BROWSER) changeFileBrowserPage(targetIndex)
            }

            override fun onListRowRequested(route: MenuRoute, direction: Int) {
              if (route == MenuRoute.FILE_BROWSER) moveFileBrowserRow(direction)
            }

            override fun onHeaderSelected(route: MenuRoute) {
              // Proposal 3 uses B for back navigation and exposes actions only as menu rows.
            }

            override fun onBackIntercepted(route: MenuRoute) {
              if (route == MenuRoute.CHOOSE_ROM) {
                cancelArchiveSelection()
              } else if (route == MenuRoute.FILE_BROWSER) {
                handleFileBrowserBack()
              } else if (route == MenuRoute.OPTION_PICKER) {
                cancelChoiceSelection()
              } else if (route == MenuRoute.PAUSE_CONSOLE) {
                val expectedMenuEpoch = menuEpoch
                runOnEdt { resumePauseRootIfCurrent(expectedMenuEpoch) }
              }
            }
          })

  private val gamepadHeld = EnumSet.noneOf(Button::class.java)

  /** Invalidates physical-controller actions queued for an older root or emulator session. */
  @Volatile private var menuEpoch = 0L

  /** Gates controller/keyboard input while a ROM chooser or replacement dialog owns the EDT. */
  private val romDialogOpen = AtomicBoolean()

  private var pauseOwnedByMenu = false

  /** Captured before root pause stops the live frame source; retained through child routes. */
  private var pauseSnapshot: PauseMenuSnapshot? = null

  private var pendingConfirmation: DesktopCommand? = null

  private var pendingArchiveSelection: PendingArchiveSelection? = null

  private var fileBrowserState: FileBrowserState? = null
  private var fileBrowserRequestId = 0L

  private val fileBrowserMarqueeTimer =
      Timer(FILE_BROWSER_MARQUEE_FRAME_MILLIS) {
        val presentation = controller.presentation()
        if (presentation.visible() && presentation.route() == MenuRoute.FILE_BROWSER) {
          render(presentation)
        } else {
          (it.source as Timer).stop()
        }
      }.apply {
        isRepeats = true
        isCoalesce = true
      }

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

  override fun visible(): Boolean = controller.visible()

  /** Opens the menu from a desktop command or test seam on the EDT. */
  internal fun openFromDesktop() {
    runOnEdt(::openOnEdt)
  }

  /** Opens a hidden overlay, or resumes and dismisses any visible paused-game route. */
  internal fun toggleFromDesktop() {
    runOnEdt {
      // A chooser/confirmation can run a nested EDT loop. A main-window Escape queued just before
      // that dialog took focus must not dismiss or resume the overlay behind its modal owner.
      if (romDialogOpen.get()) return@runOnEdt
      if (!controller.visible()) {
        openOnEdt()
        return@runOnEdt
      }
      val current = commands.menuState().commands
      if (!current.gameLoaded || (!current.paused && !pauseOwnedByMenu)) return@runOnEdt
      dismissAllRoutesAndResume()
    }
  }

  /** Hides the menu during ROM/controller lifecycle transitions without resuming a new session. */
  internal fun closeForLifecycle() {
    runOnEdt {
      menuEpoch++
      pauseOwnedByMenu = false
      pauseSnapshot = null
      pendingConfirmation = null
      pendingArchiveSelection = null
      fileBrowserRequestId++
      fileBrowserState = null
      fileBrowserMarqueeTimer.stop()
      pendingChoice = null
      selectedArchiveItemId = null
      controller.setRootDismissAllowed(true)
      controller.setBackIntercepted(false)
      if (controller.visible()) controller.hide()
      else frameSink(null)
      releaseGameplaySoon()
    }
  }

  /** Rebinds fullscreen state after native Screen-menu, shortcut, or Preferences changes. */
  internal fun refreshDisplayPresentation() {
    runOnEdt {
      if (!controller.visible()) return@runOnEdt
      val rootRoute = controller.snapshot().frames().firstOrNull()?.route() ?: return@runOnEdt
      if (rootRoute == MenuRoute.PAUSE_CONSOLE || rootRoute == MenuRoute.LIBRARY) {
        // setPage also replaces a matching ancestor frame, so a child route can stay open while
        // its root checkbox is refreshed for the next B navigation.
        controller.setPage(pageFor(rootRoute, commands.menuState()))
      }
    }
  }

  override fun updatePlayerButtons(buttons: Collection<Button>): Boolean {
    val current = EnumSet.noneOf(Button::class.java)
    current.addAll(buttons)
    if (romDialogOpen.get()) {
      // The ROM dialog owns input while its modal event loop is active. Keep the physical snapshot
      // current so a held button cannot become a fresh menu edge when the dialog closes.
      // Releases are safe and must still clear the activation that opened the chooser.
      val previous = EnumSet.noneOf(Button::class.java)
      previous.addAll(gamepadHeld)
      gamepadHeld.clear()
      gamepadHeld.addAll(current)
      for (button in Button.values()) {
        if (!current.contains(button) && previous.contains(button)) {
          controller.onKeyUp(button.toMenuKey())
        }
      }
      return true
    }
    if (!visible()) {
      val menuChordPressed =
          current.contains(Button.START) &&
              current.contains(Button.SELECT) &&
              !(gamepadHeld.contains(Button.START) && gamepadHeld.contains(Button.SELECT))
      gamepadHeld.clear()
      gamepadHeld.addAll(current)
      if (menuChordPressed) {
        runOnEdt(::openOnEdt)
        return true
      }
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
      if (!visible() || romDialogOpen.get()) break
    }
    return true
  }

  override fun onKeyDown(key: MenuKey, repeat: Boolean): Boolean {
    if (romDialogOpen.get()) return true
    if (!visible()) return false
    refreshPrinterPageBeforeInput()
    return controller.onKeyDown(key, repeat)
  }

  override fun onKeyUp(key: MenuKey): Boolean {
    if (romDialogOpen.get()) {
      controller.onKeyUp(key)
      return true
    }
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
      // An archive request may finish while the user has reopened this overlay and is browsing
      // for another ROM. Preserve that visible menu's pause ownership; recomputing it from the
      // already-paused presentation would strand the game paused when the archive page closes.
      val overlayAlreadyVisible = controller.visible()
      fileBrowserRequestId++
      fileBrowserState = null
      fileBrowserMarqueeTimer.stop()
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
      if (!overlayAlreadyVisible) {
        pauseOwnedByMenu = current.commands.pauseSupported && !current.commands.paused
        if (pauseOwnedByMenu) {
          commands.setPaused(true)
        }
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
    if (controller.visible()) return
    val current = commands.menuState()
    if (current.commands.sessionBusy) return
    menuEpoch++

    if (!current.commands.gameLoaded) {
      // The desktop's idle surface follows Android: the portable Library, rather than a paused
      // console, is the entry point when no ROM is active. There is nothing to capture or pause.
      pauseSnapshot = null
      controller.setRootDismissAllowed(false)
      controller.setBackIntercepted(false)
      controller.setPage(pageFor(MenuRoute.LIBRARY, current))
      controller.show(MenuRoute.LIBRARY)
      return
    }

    controller.setRootDismissAllowed(true)
    controller.setBackIntercepted(false)

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
        widgetType: MenuWidgetType = MenuWidgetType.BUTTON,
        progress: Int = -1,
    ) = MenuPageSpec.Item(id, label, "", isEnabled, secondaryId, widgetType, progress)

    fun item(
        id: String,
        label: String,
        detail: String,
        isEnabled: Boolean,
        widgetType: MenuWidgetType = MenuWidgetType.BUTTON,
        progress: Int = -1,
    ) = MenuPageSpec.Item(id, label, detail, isEnabled, null, widgetType, progress)

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
                  widgetType = MenuWidgetType.DROPDOWN,
              ),
              item(
                  PortableMenuSettingId.GAMEPAD,
                  "GAMEPAD",
                  snapshot.displayValue(PortableMenuSettingId.GAMEPAD)?.uppercase().orEmpty(),
                  true,
                  widgetType = MenuWidgetType.DROPDOWN,
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
                item("save-state", "SAVE STATE", stateAvailable),
                item("load-state", "LOAD STATE", stateAvailable),
                item("open-rom", "OPEN ROM", enabled(DesktopCommand.OPEN_ROM)),
                item("reset", "RESET GAME", enabled(DesktopCommand.RESET)),
                item("recent-games", "RECENT GAMES", commands.canOpenRecentGame()),
                item("settings", "SETTINGS", settings != null || inlineAudioAvailable),
                MenuPageSpec.Item.checkbox(
                    "fullscreen",
                    "FULL SCREEN",
                    state.fullscreen,
                    enabled(DesktopCommand.FULLSCREEN),
                ),
            ),
            preview = snapshot.preview(),
            footerHints = listOf("D-PAD MOVE", "A CHOOSE", "B RESUME"),
        )
      }

      MenuRoute.SAVE_STATES -> {
        val catalog = commands.stateSlots()
        val slots =
            (0..9).map { slot ->
              // The shared button detail identifies occupied slots without a state-specific skin.
              MenuPageSpec.Item.button(
                  "slot-$slot",
                  "SLOT $slot",
                  if (catalog.firstOrNull { it.index == slot }?.loadable == true) "SAVED" else "",
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
                    add(
                        item(
                            "volume",
                            "VOLUME",
                            "$volume%",
                            true,
                            widgetType = MenuWidgetType.SLIDER,
                            progress = volume,
                        ))
                  }
                  add(
                      MenuPageSpec.Item.checkbox(
                          "mute-audio",
                          "MUTE",
                          state.muted,
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
                item("input-recording", "INPUT RECORDING", enabled(DesktopCommand.INPUT_RECORDING)),
                item("preview-printer-paper", "PRINTER PAPER", printerHasPaper),
                item("back", "BACK", true),
            )
        if (hasEnabledNonBack(dataItems)) {
          page(
              "DATA & MEDIA",
              "DATA DECK",
              listOf("BATTERY SAVE  READY", "STATE SLOT 0  READY", "INPUT RECORDING"),
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
                item("open-rom", "OPEN ROM", enabled(DesktopCommand.OPEN_ROM)),
                item("recent-games", "RECENT GAMES", commands.canOpenRecentGame()),
                item("settings", "SETTINGS", settings != null || inlineAudioAvailable),
                MenuPageSpec.Item.checkbox(
                    "fullscreen",
                    "FULL SCREEN",
                    state.fullscreen,
                    enabled(DesktopCommand.FULLSCREEN),
                ),
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
                    widgetType = MenuWidgetType.DROPDOWN,
                ),
                item(
                    PortableMenuSettingId.CGB_GAMES,
                    "CGB GAMES",
                    settings?.displayValue(PortableMenuSettingId.CGB_GAMES)?.uppercase().orEmpty(),
                    settings != null,
                    widgetType = MenuWidgetType.DROPDOWN,
                ),
                item(
                    PortableMenuSettingId.BOOTSTRAP,
                    "BOOTSTRAP",
                    settings?.displayValue(PortableMenuSettingId.BOOTSTRAP)?.uppercase().orEmpty(),
                    settings != null,
                    widgetType = MenuWidgetType.DROPDOWN,
                ),
                item(
                    PortableMenuSettingId.EXECUTION_MODE,
                    "MODE",
                    settings
                        ?.displayValue(PortableMenuSettingId.EXECUTION_MODE)
                        ?.uppercase()
                        .orEmpty(),
                    settings != null,
                    widgetType = MenuWidgetType.DROPDOWN,
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
                MenuPageSpec.Item.checkbox(
                    PortableMenuSettingId.SGB_BORDER,
                    "SGB BORDER",
                    settings?.value(PortableMenuSettingId.SGB_BORDER) == "on",
                    settings != null && PortableMenuSettingId.SGB_BORDER in settings.toggleIds,
                ),
                item(
                    PortableMenuSettingId.DMG_COLORS,
                    "DMG COLORS",
                    settings?.displayValue(PortableMenuSettingId.DMG_COLORS)?.uppercase().orEmpty(),
                    settings != null,
                    widgetType = MenuWidgetType.DROPDOWN,
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
                MenuPageSpec.Item.checkbox(
                    "choice:${choice.token}",
                    choice.label.uppercase(),
                    choice.token == committed,
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

      MenuRoute.FILE_BROWSER -> fileBrowserPage()

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
                MenuPageSpec.Item.button("confirm", "CONFIRM", "", true),
                MenuPageSpec.Item.button("cancel", "CANCEL", "", true),
            ),
            preferredFocus = "cancel",
            columns = 1,
        )
      }
    }
  }

  private fun handleItem(route: MenuRoute, id: String, secondary: Boolean) {
    when (route) {
      MenuRoute.PAUSE_CONSOLE ->
          when (id) {
            "save-state" -> openStateRoute(StateMenuMode.SAVE)
            "load-state" -> openStateRoute(StateMenuMode.LOAD)
            "open-rom" -> openFileBrowser()
            "reset" -> openConfirmation(DesktopCommand.RESET)
            "recent-games" ->
                if (commands.canOpenRecentGame()) openRoute(MenuRoute.RECENT_GAMES)
            "settings" -> openRoute(MenuRoute.SETTINGS)
            "fullscreen" ->
                runCommandInPlace(DesktopCommand.FULLSCREEN, MenuRoute.PAUSE_CONSOLE)
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
          if (id in SESSION_RESTART_SETTING_IDS) {
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
              val settings = commands.settingsSnapshot()
              if (settings?.choicesFor(pending.settingId)?.any {
                    it.token == token && it.enabled
                  } == true) {
                val closeForSessionRestart =
                    commands.menuState().commands.gameLoaded &&
                        pending.settingId in SESSION_RESTART_SETTING_IDS &&
                        settings.value(pending.settingId) != token
                pendingChoice = null
                controller.setBackIntercepted(false)
                if (closeForSessionRestart) {
                  // Resume first so BasicController observes the menu-owned intent before the
                  // settings adapter queues UpdatedSystemMappingEvent. A pause which predated the
                  // menu is not owned here, so hideAndResume leaves that state intact.
                  hideAndResume()
                  commands.applySettingsChoice(pending.settingId, token)
                } else {
                  commands.applySettingsChoice(pending.settingId, token)
                  controller.setPage(pageFor(pending.originRoute, commands.menuState()))
                  controller.back()
                }
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
            "input-recording" -> runCommandAndHide(DesktopCommand.INPUT_RECORDING)
            "preview-printer-paper" -> openRoute(MenuRoute.PRINTER_PAPER)
            "back" -> back()
          }
      MenuRoute.LIBRARY ->
          when (id) {
            "open-rom" -> openFileBrowser()
            "recent-games" -> if (commands.canOpenRecentGame()) openRoute(MenuRoute.RECENT_GAMES)
            "settings" -> openRoute(MenuRoute.SETTINGS)
            "fullscreen" -> runCommandInPlace(DesktopCommand.FULLSCREEN, MenuRoute.LIBRARY)
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
      MenuRoute.FILE_BROWSER -> activateFileBrowserItem(id)
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
        PortableMenuSettingId.EXECUTION_MODE -> "MODE"
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

  private fun openFileBrowser() {
    runOnEdt {
      val origin = controller.route()
      if (origin != MenuRoute.PAUSE_CONSOLE && origin != MenuRoute.LIBRARY) return@runOnEdt
      if (!commands.isEnabled(DesktopCommand.OPEN_ROM)) return@runOnEdt
      val location = romFileBrowser.initialLocation(commands.preferredRomDirectory())
      val requestId = ++fileBrowserRequestId
      fileBrowserState = loadingFileBrowserState(requestId, location, location)
      controller.setBackIntercepted(true)
      controller.setPage(fileBrowserPage())
      controller.push(MenuRoute.FILE_BROWSER)
      submitFileBrowserListing(requestId, location, null, menuEpoch)
    }
  }

  private fun requestFileBrowserLocation(location: Path?, restorePath: Path?) {
    check(SwingUtilities.isEventDispatchThread()) { "File browser navigation must run on the EDT" }
    if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return
    val initialLocation = fileBrowserState?.initialLocation ?: return
    val requestId = ++fileBrowserRequestId
    fileBrowserState = loadingFileBrowserState(requestId, initialLocation, location)
    val page = fileBrowserPage()
    controller.setPageAndFocus(page, requireNotNull(fileBrowserState).rows.single().id)
    submitFileBrowserListing(requestId, location, restorePath, menuEpoch)
  }

  private fun submitFileBrowserListing(
      requestId: Long,
      location: Path?,
      restorePath: Path?,
      expectedMenuEpoch: Long,
  ) {
    fileBrowserExecutor.execute {
      val listing = romFileBrowser.list(location)
      SwingUtilities.invokeLater {
        if (requestId != fileBrowserRequestId || expectedMenuEpoch != menuEpoch) return@invokeLater
        if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@invokeLater
        applyFileBrowserListing(requestId, listing, restorePath)
      }
    }
  }

  private fun applyFileBrowserListing(
      requestId: Long,
      listing: DesktopRomFileBrowser.Listing,
      restorePath: Path?,
  ) {
    val initialLocation = fileBrowserState?.initialLocation ?: return
    val rows = mutableListOf<FileBrowserRow>()
    listing.entries.forEachIndexed { index, entry ->
      val suffix =
          if (entry.kind == DesktopRomFileBrowser.EntryKind.DIRECTORY &&
              !entry.label.endsWith("/") &&
              !entry.label.endsWith("\\")) {
            "/"
          } else {
            ""
          }
      rows += FileBrowserRow("browser-entry:$requestId:$index", entry.label + suffix, entry)
    }
    listing.errorMessage?.let { message ->
      rows += FileBrowserRow("browser-retry:$requestId", "RETRY: $message", null)
    }
    if (listing.truncated) {
      rows +=
          FileBrowserRow(
              "browser-truncated:$requestId",
              "DIRECTORY TOO LARGE - FIRST ${DesktopRomFileBrowser.DEFAULT_MAX_ENTRIES} ITEMS",
              null,
          )
    }
    if (rows.isEmpty()) {
      rows += FileBrowserRow("browser-retry:$requestId", "NO FILESYSTEM ROOTS - RETRY", null)
    }

    val restoredIndex =
        restorePath?.let { wanted ->
          rows.indexOfFirst { row -> row.entry?.path == wanted }
        }?.takeIf { it >= 0 }
    val focusedIndex = restoredIndex ?: 0
    val viewportStart = (focusedIndex / FILE_BROWSER_PAGE_SIZE) * FILE_BROWSER_PAGE_SIZE
    fileBrowserState =
        FileBrowserState(
            requestId,
            initialLocation,
            listing.location,
            rows.toList(),
            viewportStart,
            focusedIndex,
        )
    val page = fileBrowserPage()
    controller.setPageAndFocus(page, rows[focusedIndex].id)
  }

  private fun loadingFileBrowserState(
      requestId: Long,
      initialLocation: Path,
      location: Path?,
  ): FileBrowserState {
    val loading = FileBrowserRow("browser-loading:$requestId", "LOADING...", null)
    return FileBrowserState(requestId, initialLocation, location, listOf(loading), 0, 0)
  }

  private fun fileBrowserPage(): MenuPageSpec {
    val state = requireNotNull(fileBrowserState) {
      "${MenuRoute.FILE_BROWSER.label()} requires an active filesystem listing"
    }
    val pageCount = maxOf(1, (state.rows.size + FILE_BROWSER_PAGE_SIZE - 1) / FILE_BROWSER_PAGE_SIZE)
    val focusedIndex = state.focusedIndex.coerceIn(0, state.rows.lastIndex)
    val pageIndex = (focusedIndex / FILE_BROWSER_PAGE_SIZE).coerceIn(0, pageCount - 1)
    val first = state.viewportStart.coerceIn(0, state.rows.lastIndex)
    val visibleRows = state.rows.drop(first).take(FILE_BROWSER_PAGE_SIZE)
    val preferred =
        state.rows[focusedIndex].id.takeIf { wanted -> visibleRows.any { it.id == wanted } }
            ?: visibleRows.first().id
    val location = state.location?.toString() ?: "FILESYSTEM ROOTS"
    val pagePrefix = if (pageCount > 1) "${pageIndex + 1}/$pageCount  " else ""
    return MenuPageSpec(
        MenuRoute.FILE_BROWSER,
        "COFFEE GB",
        pagePrefix + location,
        "",
        "",
        emptyList(),
        visibleRows.map { row -> MenuPageSpec.Item.button(row.id, row.label, "", true) },
        1,
        listOf("L/R PAGE", "A OPEN", "B BACK"),
        preferred,
        MenuPreview.empty(),
        MenuPageLayout.FULL_WIDTH_LIST,
        MenuPagination(pageIndex, pageCount),
    )
  }

  private fun changeFileBrowserPage(targetIndex: Int) {
    runOnEdt {
      val state = fileBrowserState ?: return@runOnEdt
      if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@runOnEdt
      val pageCount = maxOf(1, (state.rows.size + FILE_BROWSER_PAGE_SIZE - 1) / FILE_BROWSER_PAGE_SIZE)
      val currentPage = (state.focusedIndex / FILE_BROWSER_PAGE_SIZE).coerceIn(0, pageCount - 1)
      if (targetIndex !in 0 until pageCount || targetIndex == currentPage) return@runOnEdt
      val direction = if (targetIndex > currentPage) 1 else -1
      val targetFocus =
          (state.focusedIndex + direction * FILE_BROWSER_PAGE_SIZE)
              .coerceIn(0, state.rows.lastIndex)
      val finalPageStart = ((state.rows.size - 1) / FILE_BROWSER_PAGE_SIZE) * FILE_BROWSER_PAGE_SIZE
      var targetViewport =
          (state.viewportStart + direction * FILE_BROWSER_PAGE_SIZE)
              .coerceIn(0, finalPageStart)
      if (targetFocus < targetViewport) {
        targetViewport = targetFocus
      } else if (targetFocus >= targetViewport + FILE_BROWSER_PAGE_SIZE) {
        targetViewport = targetFocus - FILE_BROWSER_PAGE_SIZE + 1
      }
      updateFileBrowserViewport(state, targetViewport, targetFocus)
    }
  }

  private fun moveFileBrowserRow(direction: Int) {
    runOnEdt {
      if (direction != -1 && direction != 1) return@runOnEdt
      val state = fileBrowserState ?: return@runOnEdt
      if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@runOnEdt
      val targetFocus = state.focusedIndex + direction
      if (targetFocus !in state.rows.indices) return@runOnEdt
      val targetViewport =
          when {
            targetFocus < state.viewportStart -> targetFocus
            targetFocus >= state.viewportStart + FILE_BROWSER_PAGE_SIZE ->
                targetFocus - FILE_BROWSER_PAGE_SIZE + 1
            else -> state.viewportStart
          }
      updateFileBrowserViewport(state, targetViewport, targetFocus)
    }
  }

  private fun updateFileBrowserViewport(
      state: FileBrowserState,
      viewportStart: Int,
      focusedIndex: Int,
  ) {
    fileBrowserState = state.copy(viewportStart = viewportStart, focusedIndex = focusedIndex)
    val page = fileBrowserPage()
    controller.setPageAndFocus(page, state.rows[focusedIndex].id)
  }

  private fun activateFileBrowserItem(id: String) {
    runOnEdt {
      val state = fileBrowserState ?: return@runOnEdt
      if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@runOnEdt
      if (id.startsWith("browser-retry:")) {
        requestFileBrowserLocation(state.location, null)
        return@runOnEdt
      }
      val entry = state.rows.firstOrNull { it.id == id }?.entry ?: return@runOnEdt
      when (entry.kind) {
        DesktopRomFileBrowser.EntryKind.PARENT ->
            requestFileBrowserLocation(entry.path, state.location)
        DesktopRomFileBrowser.EntryKind.DIRECTORY ->
            requestFileBrowserLocation(entry.path, null)
        DesktopRomFileBrowser.EntryKind.ROM -> {
          val path = entry.path ?: return@runOnEdt
          if (!romDialogOpen.compareAndSet(false, true)) return@runOnEdt
          val expectedRequestId = state.requestId
          val expectedMenuEpoch = menuEpoch
          val accepted =
              try {
                commands.openRomPathFromMenu(path)
              } finally {
                romDialogOpen.set(false)
              }
          val sameBrowser =
              expectedMenuEpoch == menuEpoch &&
                  expectedRequestId == fileBrowserRequestId &&
                  fileBrowserState?.requestId == expectedRequestId &&
                  controller.visible() &&
                  controller.route() == MenuRoute.FILE_BROWSER
          if (accepted && sameBrowser) {
            fileBrowserRequestId++
            fileBrowserState = null
            fileBrowserMarqueeTimer.stop()
            controller.setBackIntercepted(false)
            hideAndResume()
          }
        }
      }
    }
  }

  private fun handleFileBrowserBack() {
    runOnEdt {
      val state = fileBrowserState ?: return@runOnEdt
      if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@runOnEdt
      val location = state.location
      if (location == null || location == state.initialLocation) {
        closeFileBrowser()
      } else {
        requestFileBrowserLocation(location.parent, location)
      }
    }
  }

  private fun closeFileBrowser() {
    runOnEdt {
      if (!controller.visible() || controller.route() != MenuRoute.FILE_BROWSER) return@runOnEdt
      fileBrowserRequestId++
      fileBrowserState = null
      fileBrowserMarqueeTimer.stop()
      controller.setBackIntercepted(false)
      controller.back()
    }
  }

  private fun runNativeRomChooser() {
    if (!romDialogOpen.compareAndSet(false, true)) return
    // Polling and global keyboard presses stay consumed until the nested EDT loop returns;
    // release edges still clear the activation that opened the chooser.
    runOnEdt {
      try {
        // Keep the frozen overlay and its pause ownership behind JFileChooser. Cancelling therefore
        // needs no reconstruction, while an approved document hands off to the ROM lifecycle only
        // after the modal window has closed.
        if (commands.openRomFromMenu()) {
          hideAndResume()
        }
      } finally {
        romDialogOpen.set(false)
      }
    }
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

  private fun resumePauseRootIfCurrent(expectedMenuEpoch: Long) {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    if (menuEpoch == expectedMenuEpoch &&
        controller.visible() &&
        controller.route() == MenuRoute.PAUSE_CONSOLE) {
      resumeAndHide()
    }
  }

  private fun resumeAndHide() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    menuEpoch++
    val shouldResume = pauseOwnedByMenu || commands.menuState().commands.paused
    pauseOwnedByMenu = false
    if (controller.visible()) controller.hide()
    if (shouldResume) {
      commands.resumeFromMenu()
    }
  }

  private fun hideAndResume() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    menuEpoch++
    val shouldResume = pauseOwnedByMenu
    pauseOwnedByMenu = false
    if (controller.visible()) controller.hide()
    if (shouldResume) {
      commands.resumeFromMenu()
    }
  }

  private fun dismissAllRoutesAndResume() {
    check(SwingUtilities.isEventDispatchThread()) { "Portable menu action must run on the EDT" }
    val cancelledArchive = pendingArchiveSelection
    pendingArchiveSelection = null
    selectedArchiveItemId = null
    pendingConfirmation = null
    pendingChoice = null
    fileBrowserRequestId++
    fileBrowserState = null
    fileBrowserMarqueeTimer.stop()
    controller.setRootDismissAllowed(true)
    controller.setBackIntercepted(false)
    resumeAndHide()
    cancelledArchive?.onCancelled()
  }

  private fun render(presentation: MenuPresentation) {
    if ((presentation.route() == MenuRoute.FILE_BROWSER || fileBrowserMarqueeTimer.isRunning) &&
        !SwingUtilities.isEventDispatchThread()) {
      runOnEdt { render(controller.presentation()) }
      return
    }
    if (presentation.visible() && presentation.route() == MenuRoute.FILE_BROWSER) {
      if (!fileBrowserMarqueeTimer.isRunning) fileBrowserMarqueeTimer.start()
    } else if (fileBrowserMarqueeTimer.isRunning) {
      fileBrowserMarqueeTimer.stop()
    }
    controller.setRootBackIntercepted(
        presentation.visible() && presentation.route() == MenuRoute.PAUSE_CONSOLE)
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
        runOnEdt(commands::resumeFromMenu)
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

  /** Returns false when the native ROM chooser was dismissed without selecting a file. */
  fun openRomFromMenu(): Boolean {
    invoke(DesktopCommand.OPEN_ROM)
    return true
  }

  /** Latest configured browser start; null lets the desktop host choose a safe fallback. */
  fun preferredRomDirectory(): java.nio.file.Path? = null

  /** Opens the exact path represented by a browser row; false means confirmation was rejected. */
  fun openRomPathFromMenu(path: java.nio.file.Path): Boolean = false

  fun canOpenAbout(): Boolean

  fun openAbout()

  fun setPaused(paused: Boolean)

  /** Resumes a retained game while dismissing the overlay, even during a busy ROM-open handoff. */
  fun resumeFromMenu() = setPaused(false)

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
