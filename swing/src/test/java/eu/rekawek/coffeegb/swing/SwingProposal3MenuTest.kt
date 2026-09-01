package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuRoute
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import eu.rekawek.coffeegb.ui.menu.MenuWidgetType
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame
import java.time.Instant
import java.nio.file.Path
import java.nio.file.Paths
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
    // The frozen 160x144 frame aspect-fits inside the common 352x340 picture aperture.
    assertEquals(gameColor, pause.copyPixels()[310 * pause.width() + 206])
  }

  @Test
  fun `idle desktop opens the Library without pausing or capturing a game frame`() {
    val bridge = FakeBridge(gameLoaded = false)
    var captures = 0
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
            capturePausePreview = {
              captures++
              MenuPreview.empty()
            },
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      assertEquals(listOf("recent-games", "open-rom", "settings"), menu.visibleItemIdsForTest())
      assertEquals("recent-games", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.RECENT_GAMES, menu.routeForTest())
    }

    assertTrue(bridge.pauseTransitions.isEmpty())
    assertEquals(0, captures)
  }

  @Test
  fun `library open rom row delegates to desktop native chooser command boundary`() {
    val bridge = FakeBridge(gameLoaded = false)
    val frames = mutableListOf<MenuArgbFrame?>()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      press(menu, MenuKey.DOWN)
      assertEquals("open-rom", menu.focusedItemIdForTest())
      assertTrue(menu.onKeyDown(MenuKey.START, false))
      assertTrue(menu.onKeyUp(MenuKey.START))
    }

    assertEquals(listOf(DesktopCommand.OPEN_ROM), bridge.invoked)
    assertTrue(frames.any { it != null })
    assertTrue(frames.last() == null)
  }

  @Test
  fun `library settings row opens the same settings route as pause menu`() {
    val menu = newMenu(FakeBridge(gameLoaded = false))

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      repeat(2) { press(menu, MenuKey.DOWN) }
      assertEquals("settings", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())
    }
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
  fun `reset confirmation uses common vertical list and cancel returns without invoking`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 4)
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      assertEquals("cancel", menu.focusedItemIdForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("confirm", menu.focusedItemIdForTest())
      press(menu, MenuKey.UP)
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
      press(menu, MenuKey.UP)
      assertEquals("cancel", menu.focusedItemIdForTest())
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
  fun `settings exposes only live audio controls and keeps changes in overlay`() {
    val bridge = FakeBridge()
    val menu = SwingProposal3Menu(frameSink = {}, commands = bridge, releaseGameplay = {})

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())
      assertEquals("audio", menu.focusedItemIdForTest())
      assertEquals(listOf("audio"), menu.visibleItemIdsForTest())

      press(menu, MenuKey.A)
      assertEquals(MenuRoute.AUDIO, menu.routeForTest())
      assertEquals("volume", menu.focusedItemIdForTest())
      assertEquals(listOf("volume", "mute-audio"), menu.visibleItemIdsForTest())
      assertEquals(
          listOf(MenuWidgetType.SLIDER, MenuWidgetType.CHECKBOX),
          menu.presentationForTest().items().map { it.widgetType() },
      )
      val initialMute = menu.presentationForTest().items().single { it.id() == "mute-audio" }
      assertFalse(initialMute.checked())
      assertEquals("", initialMute.detail())
      press(menu, MenuKey.LEFT)
      assertEquals(95, bridge.volume)
      assertEquals(MenuRoute.AUDIO, menu.routeForTest())
      press(menu, MenuKey.DOWN)
      assertEquals("mute-audio", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertTrue(bridge.muted)
      val toggledMute = menu.presentationForTest().items().single { it.id() == "mute-audio" }
      assertTrue(toggledMute.checked())
      assertEquals("", toggledMute.detail())
      assertEquals(MenuRoute.AUDIO, menu.routeForTest())
      assertTrue(menu.visible())
    }

    assertEquals(listOf(DesktopCommand.MUTE), bridge.invoked)
    assertEquals(0, bridge.preferencesOpened)
  }

  @Test
  fun `settings root exposes the four desktop sections and system values use committed details`() {
    val bridge = FakeBridge(settingsAccess = FakeSettingsAccess())
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())
      assertEquals(listOf("system", "display", "audio", "peripherals"), menu.visibleItemIdsForTest())

      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SYSTEM, menu.routeForTest())
      assertEquals(
          listOf("dmg-games", "cgb-games", "bootstrap", "execution-mode"),
          menu.visibleItemIdsForTest(),
      )
      assertTrue(
          menu.presentationForTest().items().all {
            it.widgetType() == MenuWidgetType.DROPDOWN
          })
    }
  }

  @Test
  fun `generic choice picker applies every settings family and returns to its origin`() {
    val choiceCases =
        listOf(
            MenuRoute.SYSTEM to "dmg-games",
            MenuRoute.SYSTEM to "cgb-games",
            MenuRoute.SYSTEM to "bootstrap",
            MenuRoute.SYSTEM to "execution-mode",
            MenuRoute.DISPLAY to "dmg-colors",
            MenuRoute.OPTIONAL_DEVICES to "camera",
            MenuRoute.OPTIONAL_DEVICES to "gamepad",
        )

    choiceCases.forEach { (origin, settingId) ->
      val settings = FakeSettingsAccess()
      val bridge = FakeBridge(settingsAccess = settings)
      val menu = newMenu(bridge)
      javax.swing.SwingUtilities.invokeAndWait {
        menu.openRouteForTest(origin)
        while (menu.focusedItemIdForTest() != settingId) press(menu, MenuKey.DOWN)
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.OPTION_PICKER, menu.routeForTest())
        assertTrue(menu.visibleItemIdsForTest().all { it.startsWith("choice:") })
        // Move away from the committed option so this verifies A applies the selected option,
        // rather than merely returning with no change.
        press(menu, MenuKey.DOWN)
        press(menu, MenuKey.A)
        assertEquals(origin, menu.routeForTest())
      }
      assertEquals(settingId, settings.applied.single().first)
      assertTrue(settings.applied.single().second != settings.initialValue(settingId))
    }
  }

  @Test
  fun `display checkbox toggles in place and picker B cancels without persistence`() {
    val settings = FakeSettingsAccess()
    val bridge = FakeBridge(settingsAccess = settings)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.DISPLAY)
      val initialBorder = menu.presentationForTest().items().single { it.id() == "sgb-border" }
      assertFalse(initialBorder.checked())
      assertEquals("", initialBorder.detail())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.DISPLAY, menu.routeForTest())
      assertEquals(listOf("sgb-border"), settings.toggled)
      val toggledBorder = menu.presentationForTest().items().single { it.id() == "sgb-border" }
      assertTrue(toggledBorder.checked())
      assertEquals("", toggledBorder.detail())

      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.OPTION_PICKER, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.DISPLAY, menu.routeForTest())
    }

    assertTrue(settings.applied.isEmpty())
  }

  @Test
  fun `choice picker preserves unavailable current gamepad and cancellation across lifecycle`() {
    val settings = FakeSettingsAccess()
    val bridge = FakeBridge(settingsAccess = settings)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.OPTIONAL_DEVICES)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertTrue(menu.visibleItemIdsForTest().contains("choice:missing-device"))
      assertEquals("choice:off", menu.focusedItemIdForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.OPTIONAL_DEVICES, menu.routeForTest())
      menu.closeForLifecycle()
      menu.openFromDesktop()
      menu.openRouteForTest(MenuRoute.OPTIONAL_DEVICES)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      press(menu, MenuKey.B)
    }

    assertTrue(settings.applied.isEmpty())
  }

  @Test
  fun `restored legacy control routes are inert and cannot open preferences`() {
    val bridge = FakeBridge()
    val menu = SwingProposal3Menu(frameSink = {}, commands = bridge, releaseGameplay = {})

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.TOUCH_CONTROLS)
      assertEquals(MenuRoute.TOUCH_CONTROLS, menu.routeForTest())
      assertEquals(listOf("unavailable"), menu.visibleItemIdsForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.TOUCH_CONTROLS, menu.routeForTest())
      assertTrue(menu.visible())
      press(menu, MenuKey.B)

      menu.openRouteForTest(MenuRoute.CONTROLLER_MAPPING)
      assertEquals(MenuRoute.CONTROLLER_MAPPING, menu.routeForTest())
      assertEquals(listOf("unavailable"), menu.visibleItemIdsForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.CONTROLLER_MAPPING, menu.routeForTest())
      assertTrue(menu.visible())
    }

    assertEquals(0, bridge.preferencesOpened)
  }

  @Test
  fun `unavailable legacy routes remain valid B-only status pages`() {
    val routes =
        listOf(
            MenuRoute.SETTINGS to "settings-status",
            MenuRoute.AUDIO to "audio-status",
            MenuRoute.DATA_MEDIA to "data-status",
            MenuRoute.LIBRARY to "library-status",
            MenuRoute.SYSTEM to "system-status",
            MenuRoute.ABOUT to "about-status",
        )

    for ((route, statusId) in routes) {
      val bridge =
          FakeBridge(
              setOf(DesktopCommand.PAUSE, DesktopCommand.CLOSE_GAME),
              audioAvailable = false,
              aboutAvailable = false,
              recentAvailable = false,
          )
      val menu = newMenu(bridge)
      javax.swing.SwingUtilities.invokeAndWait {
        menu.openRouteForTest(route)
        assertEquals(route, menu.routeForTest())
        assertEquals(listOf(statusId), menu.visibleItemIdsForTest())
        assertEquals(statusId, menu.focusedItemIdForTest())

        // The status row is deliberately inert; B is the only available action.
        press(menu, MenuKey.A)
        assertEquals(route, menu.routeForTest())
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
      }
    }
  }

  @Test
  fun `audio back navigation returns through settings without a back row`() {
    val bridge = FakeBridge()
    val menu = SwingProposal3Menu(frameSink = {}, commands = bridge, releaseGameplay = {})

    javax.swing.SwingUtilities.invokeAndWait { menu.openFromDesktop() }
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(5) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.AUDIO, menu.routeForTest())

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
  fun `save page exposes ten slots and A or Start saves directly while B returns`() {
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

      repeat(8) { press(menu, MenuKey.DOWN) }
      assertEquals("slot-9", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertEquals(9, bridge.savedSlot)
      press(menu, MenuKey.DOWN)
      assertEquals("slot-0", menu.focusedItemIdForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }
  }

  @Test
  fun `load page reaches a persisted slot nine`() {
    val bridge =
        FakeBridge().also {
          it.stateCatalog = listOf(PortableMenuStateSlot(9, true, MenuPreview.empty()))
        }
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SAVE_STATES, menu.routeForTest())

      repeat(9) { press(menu, MenuKey.DOWN) }
      assertEquals("slot-9", menu.focusedItemIdForTest())
      assertEquals(
          "SAVED",
          menu.presentationForTest().items().single { it.id() == "slot-9" }.detail(),
      )
      press(menu, MenuKey.A)
    }

    assertEquals(9, bridge.loadedSlot)
    assertFalse(menu.visible())
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
  fun `recent games page follows focus preview and opens selected path`() {
    val firstPath = Paths.get("/games/first.gb")
    val secondPath = Paths.get("/games/second.gbc")
    val red = 0xffc02020.toInt()
    val blue = 0xff2060c0.toInt()
    val bridge =
        FakeBridge().also {
          it.recentCatalog =
              listOf(
                  PortableMenuRecentGame(
                      firstPath,
                      "FIRST.GB",
                      MenuPreview.ready(1, 1, intArrayOf(red)),
                      "2026-08-17 20:15",
                  ),
                  PortableMenuRecentGame(
                      secondPath,
                      "SECOND.GBC",
                      MenuPreview.ready(1, 1, intArrayOf(blue)),
                      "2026-08-16 09:30",
                      RomOrigin.directFile(secondPath),
                  ),
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
      moveToPauseItem(menu, 6)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.RECENT_GAMES, menu.routeForTest())
      assertEquals(listOf("recent:0", "recent:1"), menu.visibleItemIdsForTest())
      assertEquals(listOf("LAST PLAYED: 2026-08-17 20:15"), menu.presentationForTest().sideLines())
      val firstFrame = frames.filterNotNull().last()
      assertTrue(firstFrame.copyPixels().contains(red))

      press(menu, MenuKey.DOWN)
      assertEquals("recent:1", menu.focusedItemIdForTest())
      assertEquals(listOf("LAST PLAYED: 2026-08-16 09:30"), menu.presentationForTest().sideLines())
      val secondFrame = frames.filterNotNull().last()
      assertTrue(secondFrame.copyPixels().contains(blue))
      press(menu, MenuKey.A)
    }

    assertEquals(secondPath, bridge.openedRecentPath)
    assertEquals(RomOrigin.directFile(secondPath), bridge.openedRecentOrigin)
    assertEquals(listOf(true, false), bridge.pauseTransitions)
    assertFalse(menu.visible())
  }

  @Test
  fun `empty recent games page is inert and returns with B`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, 6)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.RECENT_GAMES, menu.routeForTest())
      assertEquals(listOf("recent-games-status"), menu.visibleItemIdsForTest())
      press(menu, MenuKey.A)
      assertEquals(null, bridge.openedRecentPath)
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }
  }

  @Test
  fun `active recent game uses the captured pause frame and current play time`() {
    val stored = 0xff8a4a24.toInt()
    val live = 0xff2a77cc.toInt()
    val bridge =
        FakeBridge(playTimeNanos = 83_000_000_000L).also {
          it.recentCatalog =
              listOf(
                  PortableMenuRecentGame(
                      Paths.get("/games/active.gb"),
                      "ACTIVE",
                      MenuPreview.ready(1, 1, intArrayOf(stored)),
                      "2026-08-17 08:00",
                      active = true,
                  ))
        }
    val frames = mutableListOf<MenuArgbFrame?>()
    val menu =
        SwingProposal3Menu(
            frameSink = { frames += it },
            commands = bridge,
            releaseGameplay = {},
            capturePausePreview = { MenuPreview.ready(1, 1, intArrayOf(live)) },
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      moveToPauseItem(menu, 6)
      press(menu, MenuKey.A)

      assertEquals("CURRENT / ACTIVE", menu.presentationForTest().items().first().label())
      assertEquals(listOf("LAST PLAYED: JUST NOW / 01:23"), menu.presentationForTest().sideLines())
      val frame = frames.filterNotNull().last()
      assertTrue(frame.copyPixels().contains(live))
      assertFalse(frame.copyPixels().contains(stored))
    }
  }

  @Test
  fun `legacy host without recent-open capability cannot enter or hide through recent games`() {
    val bridge =
        FakeBridge(recentAvailable = false).also {
          it.recentCatalog =
              listOf(PortableMenuRecentGame(Paths.get("/games/unavailable.gb"), "UNAVAILABLE"))
        }
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      assertFalse(
          menu.presentationForTest().items().single { it.id() == "recent-games" }.enabled())
      menu.openRouteForTest(MenuRoute.RECENT_GAMES)
      assertFalse(
          menu.presentationForTest().items().single { it.id() == "recent:0" }.enabled())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.RECENT_GAMES, menu.routeForTest())
      assertTrue(menu.visible())
      assertEquals(null, bridge.openedRecentPath)
    }
  }

  @Test
  fun `state date follows focus even when slots share the empty preview`() {
    val bridge =
        FakeBridge().also {
          it.stateCatalog =
              listOf(
                  PortableMenuStateSlot(
                      0,
                      true,
                      MenuPreview.empty(),
                      Instant.parse("2026-08-13T12:34:00Z"),
                  ),
                  PortableMenuStateSlot(1, false, MenuPreview.empty(), null),
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
      press(menu, MenuKey.A)
      val dated = frames.filterNotNull().last()
      press(menu, MenuKey.DOWN)
      val empty = frames.filterNotNull().last()
      assertFalse(savedDatePixels(dated).contentEquals(savedDatePixels(empty)))
    }
  }

  @Test
  fun `peripherals route contains only camera and player one gamepad`() {
    val settings = FakeSettingsAccess()
    val bridge = FakeBridge(settingsAccess = settings)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.OPTIONAL_DEVICES)
      assertEquals(listOf("camera", "gamepad"), menu.visibleItemIdsForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.OPTION_PICKER, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.OPTIONAL_DEVICES, menu.routeForTest())
      assertEquals(0, bridge.preferencesOpened)
    }
  }

  @Test
  fun `legacy optional devices route is inert when the typed settings bridge is absent`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.OPTIONAL_DEVICES)
      assertEquals(listOf("peripherals-status"), menu.visibleItemIdsForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.OPTIONAL_DEVICES, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }

    assertEquals(0, bridge.preferencesOpened)
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
      menu.openRouteForTest(MenuRoute.PRINTER_PAPER)
      assertEquals(MenuRoute.PRINTER_PAPER, menu.routeForTest())
      assertEquals("export-share-paper", menu.focusedItemIdForTest())
    }

    val frame = frames.filterNotNull().last()
    assertEquals(0xffd02020.toInt(), frame.copyPixels()[310 * frame.width() + 206])

    javax.swing.SwingUtilities.invokeAndWait { assertTrue(menu.onKeyDown(MenuKey.A, false)) }
    assertEquals(1, printer.exportCount)
    assertFalse(menu.visible())
  }

  @Test
  fun `printer route refreshes safely after paper disappears`() {
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
      menu.openRouteForTest(MenuRoute.PRINTER_PAPER)
      assertEquals(MenuRoute.PRINTER_PAPER, menu.routeForTest())

      printer.preview = MenuPreview.empty()
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.PRINTER_PAPER, menu.routeForTest())
      assertEquals(listOf("no-paper"), menu.visibleItemIdsForTest())
      val emptyFrame = frames.filterNotNull().last()
      assertTrue(emptyFrame.copyPixels()[310 * emptyFrame.width() + 206] != 0xffd02020.toInt())
      press(menu, MenuKey.B)

      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    }

    assertEquals(0, printer.exportCount)
    assertEquals(0, printer.clearCount)
  }

  private fun savedDatePixels(frame: MenuArgbFrame): IntArray {
    val pixels = frame.copyPixels()
    return IntArray(352 * 44) { offset ->
      val x = 30 + offset % 352
      val y = 505 + offset / 352
      pixels[y * frame.width() + x]
    }
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

  private class FakeBridge(
      private val enabledCommands: Set<DesktopCommand> =
          setOf(
              DesktopCommand.OPEN_ROM,
              DesktopCommand.PAUSE,
              DesktopCommand.RESET,
              DesktopCommand.SAVE_STATE,
              DesktopCommand.LOAD_STATE,
              DesktopCommand.PREFERENCES,
              DesktopCommand.CLOSE_GAME,
              DesktopCommand.MUTE,
      ),
      private val audioAvailable: Boolean = true,
      private val aboutAvailable: Boolean = true,
      private val settingsAccess: PortableMenuSettingsAccess? = null,
      private val recentAvailable: Boolean = true,
      private val playTimeNanos: Long = 0,
      private val gameLoaded: Boolean = true,
  ) : PortableMenuCommandBridge {
    val invoked = mutableListOf<DesktopCommand>()
    val pauseTransitions = mutableListOf<Boolean>()
    var muted = false
    var volume = 100
    var aboutOpened = false
    var preferencesOpened = 0
    var loadedSlot: Int? = null
    var savedSlot: Int? = null
    var recentCatalog: List<PortableMenuRecentGame> = emptyList()
    var openedRecentPath: Path? = null
    var openedRecentOrigin: RomOrigin? = null
    var stateCatalog: List<PortableMenuStateSlot> = emptyList()
    var catalogRefreshOnEdt: Boolean? = null
    var saveOnEdt: Boolean? = null
    var loadOnEdt: Boolean? = null
    override fun menuState(): DesktopPresentation =
        DesktopPresentation(
            gameTitle = "TEST GAME".takeIf { gameLoaded },
            playTimeNanos = playTimeNanos,
            commands =
                DesktopCommandPresentation(
                    gameLoaded = gameLoaded,
                    pauseSupported = gameLoaded,
                    stateCommandsAvailable = gameLoaded,
                    stateBrowserAvailable = gameLoaded,
                    muted = muted,
                    audioVolume = volume,
                ),
        )

    override fun isEnabled(command: DesktopCommand): Boolean = command in enabledCommands

    override fun invoke(command: DesktopCommand) {
      invoked += command
      if (command == DesktopCommand.MUTE) muted = !muted
    }

    override fun audioVolume(): Int? = volume.takeIf { audioAvailable }

    override fun setAudioVolume(volume: Int) {
      this.volume = volume
    }

    override fun settingsSnapshot(): PortableMenuSettingsSnapshot? = settingsAccess?.snapshot()

    override fun applySettingsChoice(id: String, token: String) {
      settingsAccess?.applyChoice(id, token)
    }

    override fun toggleSettings(id: String) {
      settingsAccess?.toggle(id)
    }

    override fun canOpenAbout(): Boolean = aboutAvailable

    override fun openAbout() {
      aboutOpened = true
    }

    override fun setPaused(paused: Boolean) {
      pauseTransitions += paused
    }

    override fun openPreferences(category: PreferencesCategory) {
      preferencesOpened++
    }

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

    override fun recentGames(): List<PortableMenuRecentGame> {
      check(javax.swing.SwingUtilities.isEventDispatchThread()) {
        "Recent-game snapshots must be read on the EDT"
      }
      return recentCatalog
    }

    override fun canOpenRecentGame(): Boolean = recentAvailable

    override fun openRecentGame(game: PortableMenuRecentGame) {
      check(javax.swing.SwingUtilities.isEventDispatchThread()) {
        "Recent-game activation must run on the EDT"
      }
      openedRecentPath = game.path
      openedRecentOrigin = game.origin
    }

    override fun refreshStateCatalog() {
      catalogRefreshOnEdt = javax.swing.SwingUtilities.isEventDispatchThread()
      check(catalogRefreshOnEdt == true) { "State catalog refresh must run on the EDT" }
    }
  }

  private class FakeSettingsAccess : PortableMenuSettingsAccess {
    val applied = mutableListOf<Pair<String, String>>()
    val toggled = mutableListOf<String>()
    private var current = initialSnapshot()

    override fun snapshot(): PortableMenuSettingsSnapshot = current

    override fun applyChoice(id: String, token: String) {
      require(current.choicesFor(id).any { it.token == token && it.enabled })
      applied += id to token
      current =
          current.copy(
              values = current.values + (id to token),
              displayValues = current.displayValues + (id to token.uppercase()),
          )
    }

    override fun toggle(id: String) {
      require(id in current.toggleIds)
      toggled += id
      val value = if (current.value(id) == "on") "off" else "on"
      current = current.copy(values = current.values + (id to value))
    }

    fun initialValue(id: String): String = initialSnapshot().value(id)!!

    private companion object {
      fun initialSnapshot() =
          PortableMenuSettingsSnapshot(
              values =
                  mapOf(
                      "dmg-games" to "auto",
                      "cgb-games" to "auto",
                      "bootstrap" to "skip",
                      "execution-mode" to "accuracy",
                      "sgb-border" to "off",
                      "dmg-colors" to "green",
                      "camera" to "off",
                      "gamepad" to "missing-device",
                  ),
              choices =
                  mapOf(
                      "dmg-games" to choices("auto", "dmg", "cgb", "sgb"),
                      "cgb-games" to choices("auto", "cgb"),
                      "bootstrap" to choices("skip", "fast-forward", "full"),
                      "execution-mode" to choices("accuracy", "performance"),
                      "dmg-colors" to choices("green", "grey"),
                      "camera" to choices("off", "camera-0"),
                      "gamepad" to
                          choices("off", "auto", "device-a") +
                              PortableMenuSettingChoice("missing-device", "MISSING DEVICE", false),
                  ),
              toggleIds = setOf("sgb-border"),
              displayValues =
                  mapOf(
                      "dmg-games" to "AUTO",
                      "cgb-games" to "AUTO",
                      "bootstrap" to "SKIP",
                      "execution-mode" to "ACCURACY",
                      "sgb-border" to "OFF",
                      "dmg-colors" to "GREEN",
                      "camera" to "OFF",
                      "gamepad" to "UNAVAILABLE MISSING",
                  ),
          )

      fun choices(vararg tokens: String): List<PortableMenuSettingChoice> =
          tokens.map { PortableMenuSettingChoice(it, it.uppercase()) }
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
