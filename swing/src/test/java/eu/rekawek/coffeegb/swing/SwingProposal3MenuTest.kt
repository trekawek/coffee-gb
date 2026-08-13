package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuRoute
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JRootPane
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingProposal3MenuTest {

  @Test
  fun `start and select controller chord captures gameplay and neutral rearm is explicit`() {
    val bridge = FakeBridge()
    val releaseCount = AtomicInteger()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = { releaseCount.incrementAndGet() },
        )

    assertTrue(menu.updatePlayerButtons(EnumSet.of(Button.START, Button.SELECT)))
    assertTrue(menu.visible())
    repeat(2) { javax.swing.SwingUtilities.invokeAndWait {} }
    assertTrue(releaseCount.get() >= 1)
  }

  @Test
  fun `root pause retains its frozen frame through hidden page setup rendering`() {
    val bridge = FakeBridge()
    val frames = mutableListOf<MenuArgbFrame?>()
    var captures = 0
    val gameColor = 0xff2864d2.toInt()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
            capturePausePreview = {
              captures++
              MenuPreview.ready(160, 144, IntArray(160 * 144) { gameColor })
            },
        )

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }

    assertEquals(1, captures)
    val pause = frames.filterNotNull().last()
    // 160x144 aspect-fits as a 264x238 image centered in the 340x238 pause preview.
    assertEquals(gameColor, pause.copyPixels()[140 * pause.width() + 78])
  }

  @Test
  fun `open rom row delegates to desktop native chooser command boundary`() {
    val bridge = FakeBridge()
    val frames = mutableListOf<MenuArgbFrame?>()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(3) { press(menu, MenuKey.DOWN) }
      assertEquals("open-rom", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.START, false))
      assertTrue(menu.onKeyUp(MenuKey.START))
    }

    assertEquals(listOf(DesktopCommand.OPEN_ROM), bridge.invoked)
    assertTrue(frames.any { it != null })
    assertTrue(frames.last() == null)
  }

  @Test
  fun `opening Proposal 3 clears drag feedback before its first frame`() {
    val root = JRootPane()
    root.setSize(640, 480)
    lateinit var menu: SwingProposal3Menu
    val feedback = RomDropFeedback(root) { !menu.visible() }
    val label =
        root.layeredPane.components
            .filterIsInstance<javax.swing.JLabel>()
            .single { it.name == "romDropFeedback" }
    val visibility = mutableListOf<Boolean>()
    var feedbackVisibleAtFirstFrame: Boolean? = null
    menu =
        SwingProposal3Menu(
            frameSink = { frame ->
              if (frame != null && feedbackVisibleAtFirstFrame == null) {
                feedbackVisibleAtFirstFrame = label.isVisible
              }
            },
            commands = FakeBridge(),
            releaseGameplay = {},
            onVisibilityChanged = { visible ->
              visibility += visible
              if (visible) feedback.update(false)
            },
        )

    javax.swing.SwingUtilities.invokeAndWait {
      feedback.update(true)
      assertTrue(label.isVisible)
      menu.openFromDesktop()
      assertFalse(label.isVisible)
      assertTrue(menu.onKeyDown(MenuKey.A, false))
    }

    assertEquals(false, feedbackVisibleAtFirstFrame)
    assertEquals(listOf(true, false), visibility)
    feedback.close()
  }

  @Test
  fun `archive candidates are rendered as dynamic rows and open selected returns the candidate`() {
    val bridge = FakeBridge()
    val selected = mutableListOf<RomSourceSnapshot.ArchiveCandidate>()
    val cancelled = AtomicInteger()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )
    val candidates =
        listOf(
            archiveCandidate(41, "games/first.gb", "FIRST GAME"),
            archiveCandidate(42, "games/second.gbc", "SECOND GAME"),
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.showArchiveSelection(
          requestId = 17,
          candidates = candidates,
          onSelected = selected::add,
          onCancelled = { cancelled.incrementAndGet() },
      )
      assertEquals(MenuRoute.CHOOSE_ROM, menu.routeForTest())
      assertEquals("archive:41", menu.focusedItemIdForTest())

      press(menu, MenuKey.DOWN)
      assertEquals("archive:42", menu.focusedItemIdForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("open-selected", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.A, false))
    }

    assertEquals(listOf(candidates[1]), selected)
    assertEquals(0, cancelled.get())
    assertFalse(menu.visible())
    assertEquals(listOf(true, false), bridge.pauseTransitions)
  }

  @Test
  fun `archive B cancels the request and does not select a candidate`() {
    val bridge = FakeBridge()
    val selected = mutableListOf<RomSourceSnapshot.ArchiveCandidate>()
    val cancelled = AtomicInteger()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.showArchiveSelection(
          requestId = 23,
          candidates =
              listOf(
                  archiveCandidate(51, "first.gb", "FIRST"),
                  archiveCandidate(52, "second.gbc", "SECOND"),
              ),
          onSelected = selected::add,
          onCancelled = { cancelled.incrementAndGet() },
      )
      assertTrue(menu.onKeyDown(MenuKey.B, false))
    }

    assertTrue(selected.isEmpty())
    assertEquals(1, cancelled.get())
    assertFalse(menu.visible())
    assertEquals(listOf(true, false), bridge.pauseTransitions)
  }

  @Test
  fun `newer archive request closes the stale screen but an older callback cannot close it`() {
    val bridge = FakeBridge()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )
    val candidates =
        listOf(
            archiveCandidate(61, "first.gb", "FIRST"),
            archiveCandidate(62, "second.gbc", "SECOND"),
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.showArchiveSelection(17, candidates, onSelected = {}, onCancelled = {})
      menu.showArchiveSelection(18, candidates, onSelected = {}, onCancelled = {})
      assertEquals(MenuRoute.CHOOSE_ROM, menu.routeForTest())

      menu.closeArchiveSelection(17)
      assertEquals(MenuRoute.CHOOSE_ROM, menu.routeForTest())
      menu.closeArchiveSelection(18)
    }

    assertFalse(menu.visible())
  }

  @Test
  fun `reset opens the onscreen confirmation and back returns to pause without invoking`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 4)
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }

    assertTrue(bridge.invoked.isEmpty())
    assertEquals(listOf(true), bridge.pauseTransitions)
  }

  @Test
  fun `stop opens the onscreen confirmation and cancel returns to pause without invoking`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 6)
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      assertEquals("cancel", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }

    assertTrue(bridge.invoked.isEmpty())
    assertEquals(listOf(true), bridge.pauseTransitions)
  }

  @Test
  fun `confirming reset invokes reset and hides while resuming`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 4)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("confirm", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.A, false))
      menu.onKeyUp(MenuKey.A)
    }

    assertEquals(listOf(DesktopCommand.RESET), bridge.invoked)
    assertEquals(listOf(true, false), bridge.pauseTransitions)
    assertFalse(menu.visible())
  }

  @Test
  fun `confirming stop invokes close game and hides while resuming`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 6)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("confirm", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.A, false))
      menu.onKeyUp(MenuKey.A)
    }

    assertEquals(listOf(DesktopCommand.CLOSE_GAME), bridge.invoked)
    assertEquals(listOf(true, false), bridge.pauseTransitions)
    assertFalse(menu.visible())
  }

  @Test
  fun `settings reaches about with privacy notices as the visible enabled selection`() {
    val bridge = FakeBridge()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
    }
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())

      repeat(8) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.ABOUT, menu.routeForTest())
      assertEquals("privacy-notices", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.A, false))
      menu.onKeyUp(MenuKey.A)
    }

    assertTrue(bridge.aboutOpened)
    assertFalse(menu.visible())
  }

  @Test
  fun `about back navigation returns through settings without a hidden back row`() {
    val bridge = FakeBridge()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      repeat(8) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.ABOUT, menu.routeForTest())
      assertEquals("privacy-notices", menu.focusedItemIdForTest())

      press(menu, MenuKey.B)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }
  }

  @Test
  fun `select is inert while save states remain visible`() {
    val bridge = FakeBridge()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SAVE_STATES, menu.routeForTest())
      assertEquals("slot-0", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.SELECT, false))
      assertTrue(menu.onKeyUp(MenuKey.SELECT))
    }

    assertEquals(null, bridge.loadedSlot)
    assertTrue(menu.visible())
  }

  @Test
  fun `save page has four stable rows and A or Start saves directly while B returns`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SAVE_STATES, menu.routeForTest())
      assertEquals("slot-0", menu.focusedItemIdForTest())

      press(menu, MenuKey.A)
      assertEquals(0, bridge.savedSlot)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.START)
      assertEquals(1, bridge.savedSlot)

      repeat(2) { press(menu, MenuKey.DOWN) }
      assertEquals("slot-3", menu.focusedItemIdForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("slot-0", menu.focusedItemIdForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }
  }

  @Test
  fun `physical controller state routes and actions are marshalled to the EDT`() {
    val saveBridge = FakeBridge()
    val saveMenu = newMenu(saveBridge)

    physicalPress(saveMenu, Button.DOWN)
    physicalPress(saveMenu, Button.A)
    javax.swing.SwingUtilities.invokeAndWait {}

    assertEquals(MenuRoute.SAVE_STATES, saveMenu.routeForTest())
    assertEquals(true, saveBridge.catalogRefreshOnEdt)

    physicalPress(saveMenu, Button.A)
    javax.swing.SwingUtilities.invokeAndWait {}

    assertEquals(0, saveBridge.savedSlot)
    assertEquals(true, saveBridge.saveOnEdt)

    val loadBridge =
        FakeBridge().also {
          it.stateCatalog = listOf(PortableMenuStateSlot(0, true, MenuPreview.empty()))
        }
    val loadMenu = newMenu(loadBridge)

    physicalPress(loadMenu, Button.DOWN)
    physicalPress(loadMenu, Button.DOWN)
    physicalPress(loadMenu, Button.A)
    javax.swing.SwingUtilities.invokeAndWait {}

    assertEquals(MenuRoute.SAVE_STATES, loadMenu.routeForTest())
    assertEquals(true, loadBridge.catalogRefreshOnEdt)

    physicalPress(loadMenu, Button.A)
    javax.swing.SwingUtilities.invokeAndWait {}

    assertEquals(0, loadBridge.loadedSlot)
    assertEquals(true, loadBridge.loadOnEdt)
  }

  @Test
  fun `load page uses persisted preview and empty slot is a safe no-op`() {
    val red = 0xffc02020.toInt()
    val blue = 0xff2060c0.toInt()
    val bridge = FakeBridge().also {
      it.stateCatalog =
          listOf(
              PortableMenuStateSlot(0, true, MenuPreview.ready(1, 1, intArrayOf(red))),
              PortableMenuStateSlot(1, true, MenuPreview.ready(1, 1, intArrayOf(blue))),
          )
    }
    val frames = mutableListOf<MenuArgbFrame?>()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SAVE_STATES, menu.routeForTest())
      assertTrue(frames.filterNotNull().last().copyPixels().contains(red))

      press(menu, MenuKey.DOWN)
      assertEquals("slot-1", menu.focusedItemIdForTest())
      assertTrue(frames.filterNotNull().last().copyPixels().contains(blue))
      press(menu, MenuKey.A)
      assertEquals(1, bridge.loadedSlot)
      assertFalse(menu.visible())
    }

    bridge.loadedSlot = null
    val emptyMenu = newMenu(bridge)
    javax.swing.SwingUtilities.invokeAndWait {
      press(emptyMenu, MenuKey.DOWN)
      press(emptyMenu, MenuKey.DOWN)
      press(emptyMenu, MenuKey.A)
      assertEquals(MenuRoute.SAVE_STATES, emptyMenu.routeForTest())
      repeat(2) { press(emptyMenu, MenuKey.DOWN) }
      press(emptyMenu, MenuKey.A)
      assertEquals(null, bridge.loadedSlot)
      assertTrue(emptyMenu.visible())
    }
  }

  @Test
  fun `printer route renders the bridge paper preview and keeps export native`() {
    val bridge = FakeBridge()
    val printer = FakePrinter(MenuPreview.ready(1, 1, intArrayOf(0xffd02020.toInt())))
    val frames = mutableListOf<MenuArgbFrame?>()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
            printer = printer,
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      repeat(3) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      repeat(3) { press(menu, MenuKey.DOWN) }
      assertEquals("preview-printer-paper", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.PRINTER_PAPER, menu.routeForTest())
      assertEquals("export-share-paper", menu.focusedItemIdForTest())
    }

    val frame = frames.filterNotNull().last()
    assertEquals(0xffd02020.toInt(), frame.copyPixels()[345 * frame.width() + 642])

    javax.swing.SwingUtilities.invokeAndWait {
      assertTrue(menu.onKeyDown(MenuKey.A, false))
    }
    assertEquals(1, printer.exportCount)
    assertFalse(menu.visible())
  }

  @Test
  fun `printer route refreshes to visible back after paper disappears`() {
    val bridge = FakeBridge()
    val printer = FakePrinter(MenuPreview.ready(1, 1, intArrayOf(0xffd02020.toInt())))
    val menu = newMenu(bridge, printer)

    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      repeat(3) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      repeat(3) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.PRINTER_PAPER, menu.routeForTest())

      printer.preview = MenuPreview.empty()
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.OPTIONAL_DEVICES, menu.routeForTest())
    }

    assertEquals(0, printer.exportCount)
    assertEquals(0, printer.clearCount)
  }

  private fun newMenu(
      bridge: FakeBridge,
      printer: PortableMenuPrinterBridge? = null,
  ): SwingProposal3Menu {
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
            printer = printer,
        )
    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    return menu
  }

  private fun moveToPauseItem(menu: SwingProposal3Menu, downPresses: Int) {
    repeat(downPresses) { press(menu, MenuKey.DOWN) }
  }

  private fun press(menu: SwingProposal3Menu, key: MenuKey) {
    assertTrue(menu.onKeyDown(key, false))
    assertTrue(menu.onKeyUp(key))
  }

  private fun physicalPress(menu: SwingProposal3Menu, button: Button) {
    assertTrue(menu.updatePlayerButtons(EnumSet.of(button)))
    assertTrue(menu.updatePlayerButtons(emptySet()))
  }

  private fun archiveCandidate(
      token: Long,
      entryName: String,
      title: String,
  ): RomSourceSnapshot.ArchiveCandidate =
      RomSourceSnapshot.ArchiveCandidate(token, entryName, 0, 32 * 1024, title)

  private class FakeBridge : PortableMenuCommandBridge {
    val invoked = mutableListOf<DesktopCommand>()
    val pauseTransitions = mutableListOf<Boolean>()
    var aboutOpened = false
    var loadedSlot: Int? = null
    var savedSlot: Int? = null
    var stateCatalog: List<PortableMenuStateSlot> = emptyList()
    var catalogRefreshOnEdt: Boolean? = null
    var saveOnEdt: Boolean? = null
    var loadOnEdt: Boolean? = null
    private val enabled =
        setOf(
            DesktopCommand.OPEN_ROM,
            DesktopCommand.PAUSE,
            DesktopCommand.RESET,
            DesktopCommand.SAVE_STATE,
            DesktopCommand.LOAD_STATE,
            DesktopCommand.PREFERENCES,
            DesktopCommand.CLOSE_GAME,
        )

    override fun menuState(): DesktopPresentation =
        DesktopPresentation(
            gameTitle = "TEST GAME",
            commands =
                DesktopCommandPresentation(
                    gameLoaded = true,
                    pauseSupported = true,
                    stateCommandsAvailable = true,
                    stateBrowserAvailable = true,
                ),
        )

    override fun isEnabled(command: DesktopCommand): Boolean = command in enabled

    override fun invoke(command: DesktopCommand) {
      invoked += command
    }

    override fun canOpenAbout(): Boolean = true

    override fun openAbout() {
      aboutOpened = true
    }

    override fun setPaused(paused: Boolean) {
      pauseTransitions += paused
    }

    override fun openPreferences(category: PreferencesCategory) = Unit

    override fun canSaveState(slot: Int): Boolean = true

    override fun canLoadState(slot: Int): Boolean = true

    override fun saveState(slot: Int) {
      saveOnEdt = javax.swing.SwingUtilities.isEventDispatchThread()
      savedSlot = slot
    }

    override fun loadState(slot: Int) {
      loadOnEdt = javax.swing.SwingUtilities.isEventDispatchThread()
      loadedSlot = slot
    }

    override fun stateSlots(): List<PortableMenuStateSlot> {
      check(javax.swing.SwingUtilities.isEventDispatchThread()) {
        "State catalog snapshots must be read on the EDT"
      }
      return stateCatalog
    }

    override fun refreshStateCatalog() {
      catalogRefreshOnEdt = javax.swing.SwingUtilities.isEventDispatchThread()
      check(catalogRefreshOnEdt == true) { "State catalog refresh must run on the EDT" }
    }
  }

  private class FakePrinter(initialPreview: MenuPreview) : PortableMenuPrinterBridge {
    var preview = initialPreview
    var clearCount = 0
    var exportCount = 0

    override fun hasPaper(): Boolean = preview.state() == MenuPreview.State.READY

    override fun paperPreview(): MenuPreview = preview

    override fun open() = Unit

    override fun clear() {
      clearCount++
      preview = MenuPreview.empty()
    }

    override fun export() {
      exportCount++
    }
  }
}
