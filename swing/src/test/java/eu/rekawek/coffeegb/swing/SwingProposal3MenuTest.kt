package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.ui.menu.MenuKey
import eu.rekawek.coffeegb.ui.menu.MenuPageLayout
import eu.rekawek.coffeegb.ui.menu.MenuRoute
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import eu.rekawek.coffeegb.ui.menu.MenuWidgetType
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame
import java.io.IOException
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JRootPane
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingProposal3MenuTest {

  @Test
  fun `start and select chord opens the hidden menu while either button alone remains gameplay input`() {
    val bridge = FakeBridge()
    val releaseCount = AtomicInteger()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = { releaseCount.incrementAndGet() },
        )

    assertFalse(menu.updatePlayerButtons(EnumSet.of(Button.SELECT)))
    assertFalse(menu.updatePlayerButtons(emptySet()))

    assertFalse(menu.updatePlayerButtons(EnumSet.of(Button.START)))
    assertTrue(menu.updatePlayerButtons(EnumSet.of(Button.START, Button.SELECT)))
    javax.swing.SwingUtilities.invokeAndWait {}

    assertTrue(menu.visible())
    assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
    assertEquals(listOf(true), bridge.pauseTransitions)
    assertTrue(menu.updatePlayerButtons(emptySet()))
    javax.swing.SwingUtilities.invokeAndWait {}

    assertEquals(1, releaseCount.get())
  }

  @Test
  fun `desktop toggle opens hidden menu then resumes from a visible child route`() {
    val bridge = FakeBridge()
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = bridge,
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.toggleFromDesktop()
      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
      menu.openRouteForTest(MenuRoute.SETTINGS)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())

      menu.toggleFromDesktop()
    }

    assertFalse(menu.visible())
    assertFalse(bridge.pausedState)
    assertEquals(listOf(true, false), bridge.pauseTransitions)
  }

  @Test
  fun `desktop toggle resumes a game that was paused before the menu opened`() {
    val bridge = FakeBridge(initiallyPaused = true)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.SETTINGS)
      menu.toggleFromDesktop()
    }

    assertFalse(menu.visible())
    assertFalse(bridge.pausedState)
    assertEquals(listOf(false), bridge.pauseTransitions)
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
      assertEquals(
          listOf("open-rom", "recent-games", "settings", "fullscreen"),
          menu.visibleItemIdsForTest(),
      )
      assertEquals("open-rom", menu.focusedItemIdForTest())
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.RECENT_GAMES, menu.routeForTest())
    }

    assertTrue(bridge.pauseTransitions.isEmpty())
    assertEquals(0, captures)
  }

  @Test
  fun `desktop toggle cannot dismiss the idle Library because there is no paused game`() {
    val menu =
        SwingProposal3Menu(
            frameSink = {},
            commands = FakeBridge(gameLoaded = false),
            releaseGameplay = {},
        )

    javax.swing.SwingUtilities.invokeAndWait {
      menu.toggleFromDesktop()
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      menu.toggleFromDesktop()
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      assertTrue(menu.visible())
    }
  }

  @Test
  fun `idle Library root consumes back while child routes still return to it`() {
    val menu = newMenu(FakeBridge(gameLoaded = false))

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openFromDesktop()
      press(menu, MenuKey.B)
      assertTrue(menu.visible())
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())

      repeat(2) { press(menu, MenuKey.DOWN) }
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())
      press(menu, MenuKey.B)
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      assertTrue(menu.visible())
    }
  }

  @Test
  fun `library open rom uses the preferred directory and pages whole browser results`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-pages")
    try {
      Files.createDirectory(directory.resolve("A folder"))
      repeat(8) { index ->
        Files.writeString(directory.resolve("game-${index.toString().padStart(2, '0')}.gb"), "rom")
      }
      Files.writeString(directory.resolve("ignored.txt"), "not a rom")
      val bridge = FakeBridge(gameLoaded = false, preferredRomDirectory = directory)
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        assertEquals("open-rom", menu.focusedItemIdForTest())
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())
        assertEquals(MenuPageLayout.FULL_WIDTH_LIST, menu.presentationForTest().layout())
        assertEquals(listOf("LOADING..."), menu.presentationForTest().items().map { it.label() })
      }

      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        val firstPage = menu.presentationForTest()
        assertEquals(directory.toAbsolutePath().normalize().toString(), firstPage.context().removePrefix("1/2  "))
        assertEquals(0, firstPage.pagination().pageIndex())
        assertEquals(2, firstPage.pagination().pageCount())
        assertEquals(
            listOf("..", "A folder/", "game-00.gb", "game-01.gb", "game-02.gb", "game-03.gb", "game-04.gb"),
            firstPage.items().map { it.label() },
        )

        repeat(2) { press(menu, MenuKey.DOWN) }
        assertEquals("game-00.gb", focusedLabel(menu))
        press(menu, MenuKey.RIGHT)
        val secondPage = menu.presentationForTest()
        assertEquals(1, secondPage.pagination().pageIndex())
        assertEquals(listOf("game-05.gb", "game-06.gb", "game-07.gb"), secondPage.items().map { it.label() })
        assertEquals("game-07.gb", focusedLabel(menu))

        press(menu, MenuKey.LEFT)
        assertEquals(0, menu.presentationForTest().pagination().pageIndex())
        assertEquals("game-00.gb", focusedLabel(menu))
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
        assertTrue(menu.visible())
      }
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `browser vertical movement scrolls one row and never wraps at either end`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-scroll")
    try {
      repeat(12) { index ->
        Files.writeString(directory.resolve("game-${index.toString().padStart(2, '0')}.gb"), "rom")
      }
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = FakeBridge(gameLoaded = false, preferredRomDirectory = directory),
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        repeat(6) { press(menu, MenuKey.DOWN) }
        assertEquals("game-05.gb", focusedLabel(menu))
        assertEquals(6, menu.presentationForTest().focusedIndex())

        press(menu, MenuKey.DOWN)
        assertEquals(
            listOf("game-00.gb", "game-01.gb", "game-02.gb", "game-03.gb", "game-04.gb", "game-05.gb", "game-06.gb"),
            menu.presentationForTest().items().map { it.label() },
        )
        assertEquals("game-06.gb", focusedLabel(menu))
        assertEquals(6, menu.presentationForTest().focusedIndex())

        repeat(6) { press(menu, MenuKey.UP) }
        assertEquals("game-00.gb", focusedLabel(menu))
        assertEquals(0, menu.presentationForTest().focusedIndex())
        press(menu, MenuKey.UP)
        assertEquals("..", focusedLabel(menu))
        assertEquals("..", menu.presentationForTest().items().first().label())
        press(menu, MenuKey.UP)
        assertEquals("..", focusedLabel(menu))

        press(menu, MenuKey.RIGHT)
        assertEquals("game-06.gb", focusedLabel(menu))
        assertEquals(0, menu.presentationForTest().focusedIndex())
        repeat(5) { press(menu, MenuKey.DOWN) }
        assertEquals("game-11.gb", focusedLabel(menu))
        val finalRows = menu.presentationForTest().items().map { it.label() }
        press(menu, MenuKey.DOWN)
        assertEquals("game-11.gb", focusedLabel(menu))
        assertEquals(finalRows, menu.presentationForTest().items().map { it.label() })

        press(menu, MenuKey.B)
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      }
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `browser parent and directory rows navigate and restore the directory focus`() {
    val parent = Files.createTempDirectory("coffee-gb-menu-browser-navigation")
    try {
      val child = Files.createDirectory(parent.resolve("Child ROMs"))
      Files.writeString(child.resolve("inside.gb"), "rom")
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = FakeBridge(gameLoaded = false, preferredRomDirectory = child),
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals("..", focusedLabel(menu))
        press(menu, MenuKey.A)
        assertEquals("LOADING...", focusedLabel(menu))
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals(parent.toAbsolutePath().normalize().toString(), menu.presentationForTest().context())
        assertEquals("Child ROMs/", focusedLabel(menu))
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals(child.toAbsolutePath().normalize().toString(), menu.presentationForTest().context())
        assertEquals("..", focusedLabel(menu))
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      }
    } finally {
      deleteTree(parent)
    }
  }

  @Test
  fun `browser back opens the parent then closes from its starting directory`() {
    val parent = Files.createTempDirectory("coffee-gb-menu-browser-back")
    try {
      val child = Files.createDirectory(parent.resolve("Child ROMs"))
      Files.writeString(child.resolve("inside.gb"), "rom")
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = FakeBridge(gameLoaded = false, preferredRomDirectory = parent),
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        press(menu, MenuKey.DOWN)
        assertEquals("Child ROMs/", focusedLabel(menu))
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals(child.toAbsolutePath().normalize().toString(), menu.presentationForTest().context())
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())
        assertEquals("LOADING...", focusedLabel(menu))
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals(parent.toAbsolutePath().normalize().toString(), menu.presentationForTest().context())
        assertEquals("Child ROMs/", focusedLabel(menu))
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      }
    } finally {
      deleteTree(parent)
    }
  }

  @Test
  fun `rejected browser ROM keeps the running game paused and browser open`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-reject")
    try {
      val rom = Files.writeString(directory.resolve("selected.gb"), "rom")
      val bridge =
          FakeBridge(
              preferredRomDirectory = directory,
              romPathAccepted = false,
          )
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        moveToPauseItem(menu, "open-rom")
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        press(menu, MenuKey.DOWN)
        assertEquals("selected.gb", focusedLabel(menu))
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())
        assertTrue(menu.visible())
        assertEquals(listOf(rom), bridge.openedRomPaths)
        assertEquals(listOf(true), bridge.pauseTransitions)

        press(menu, MenuKey.B)
        assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
        assertEquals(listOf(true), bridge.pauseTransitions)
      }
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `accepted browser ROM opens the exact path and closes the idle overlay`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-accept")
    try {
      val rom = Files.writeString(directory.resolve("  Pokémon 日本 long filename.gbc"), "rom")
      val bridge =
          FakeBridge(
              gameLoaded = false,
              preferredRomDirectory = directory,
              romPathAccepted = true,
          )
      val worker = QueuedExecutor()
      val frames = mutableListOf<MenuArgbFrame?>()
      val menu =
          SwingProposal3Menu(
              frameSink = { frames += it },
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        press(menu, MenuKey.START)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        press(menu, MenuKey.DOWN)
        press(menu, MenuKey.A)
        assertFalse(menu.visible())
      }

      assertEquals(listOf(rom), bridge.openedRomPaths)
      assertTrue(bridge.invoked.isEmpty())
      assertTrue(frames.last() == null)
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `late accepted ROM confirmation cannot hide a replacement menu`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-reentrant")
    try {
      val rom = Files.writeString(directory.resolve("replacement.gb"), "rom")
      lateinit var menu: SwingProposal3Menu
      val bridge =
          FakeBridge(
              preferredRomDirectory = directory,
              romPathAccepted = true,
              duringRomPath = {
                // A modal replacement confirmation runs a nested EDT loop. Its input belongs to
                // the dialog and must not navigate the browser underneath it.
                assertTrue(menu.onKeyDown(MenuKey.B, false))
                assertTrue(menu.onKeyUp(MenuKey.B))
                menu.toggleFromDesktop()
                assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())
                assertTrue(menu.visible())

                // Model a lifecycle replacement while that nested loop is active.
                menu.closeForLifecycle()
                menu.openFromDesktop()
                assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
              },
          )
      val worker = QueuedExecutor()
      menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        moveToPauseItem(menu, "open-rom")
        press(menu, MenuKey.A)
      }
      worker.runNextAndFlushEdt()

      javax.swing.SwingUtilities.invokeAndWait {
        press(menu, MenuKey.DOWN)
        press(menu, MenuKey.A)

        assertEquals(listOf(rom), bridge.openedRomPaths)
        assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
        assertTrue(menu.visible())
        press(menu, MenuKey.B)
        assertFalse(menu.visible())
        assertEquals(listOf(true, false), bridge.pauseTransitions)
      }
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `late browser listing cannot reopen a browser closed with back`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-stale")
    try {
      Files.writeString(directory.resolve("late.gb"), "rom")
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = FakeBridge(gameLoaded = false, preferredRomDirectory = directory),
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())
        press(menu, MenuKey.B)
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      }

      worker.runNextAndFlushEdt()
      javax.swing.SwingUtilities.invokeAndWait {
        assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
        assertEquals(listOf("open-rom", "recent-games", "settings", "fullscreen"), menu.visibleItemIdsForTest())
      }
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `desktop toggle closes a loading file browser resumes and rejects its late listing`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-escape")
    try {
      Files.writeString(directory.resolve("late.gb"), "rom")
      val bridge = FakeBridge(preferredRomDirectory = directory)
      val worker = QueuedExecutor()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.toggleFromDesktop()
        moveToPauseItem(menu, "open-rom")
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())

        menu.toggleFromDesktop()
        assertFalse(menu.visible())
      }

      assertFalse(bridge.pausedState)
      assertEquals(listOf(true, false), bridge.pauseTransitions)
      worker.runNextAndFlushEdt()
      assertFalse(menu.visible())
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `desktop toggle cancels archive replacing a browser and preserves pause ownership`() {
    val directory = Files.createTempDirectory("coffee-gb-menu-browser-archive")
    try {
      val bridge = FakeBridge(preferredRomDirectory = directory)
      val worker = QueuedExecutor()
      val cancelled = AtomicInteger()
      val menu =
          SwingProposal3Menu(
              frameSink = {},
              commands = bridge,
              releaseGameplay = {},
              fileBrowserExecutor = worker,
          )

      javax.swing.SwingUtilities.invokeAndWait {
        menu.openFromDesktop()
        moveToPauseItem(menu, "open-rom")
        press(menu, MenuKey.A)
        assertEquals(MenuRoute.FILE_BROWSER, menu.routeForTest())

        menu.showArchiveSelection(
            requestId = 91,
            candidates =
                listOf(
                    archiveCandidate(61, "first.gb", "FIRST"),
                    archiveCandidate(62, "second.gbc", "SECOND"),
                ),
            onSelected = {},
            onCancelled = { cancelled.incrementAndGet() },
        )
        assertEquals(MenuRoute.CHOOSE_ROM, menu.routeForTest())
        menu.toggleFromDesktop()
      }

      assertFalse(menu.visible())
      assertEquals(1, cancelled.get())
      assertEquals(listOf(true, false), bridge.pauseTransitions)

      // The listing queued by the replaced browser must remain harmless after archive dismissal.
      worker.runNextAndFlushEdt()
      assertFalse(menu.visible())
    } finally {
      deleteTree(directory)
    }
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
  fun `pause root exposes the requested actions and fullscreen checkbox`() {
    val bridge = FakeBridge(initialFullscreen = true)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      assertEquals(
          listOf(
              "save-state",
              "load-state",
              "open-rom",
              "reset",
              "recent-games",
              "settings",
              "fullscreen",
          ),
          menu.visibleItemIdsForTest(),
      )
      assertEquals("save-state", menu.focusedItemIdForTest())
      assertEquals(
          listOf("D-PAD MOVE", "A CHOOSE", "B RESUME"),
          menu.presentationForTest().footerHints(),
      )
      val fullscreen =
          menu.presentationForTest().items().single { it.id() == "fullscreen" }
      assertEquals(MenuWidgetType.CHECKBOX, fullscreen.widgetType())
      assertTrue(fullscreen.checked())
      assertFalse(menu.visibleItemIdsForTest().contains("resume"))
    }
  }

  @Test
  fun `fullscreen toggles in place from both desktop roots`() {
    val pauseBridge = FakeBridge()
    val pauseMenu = newMenu(pauseBridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(pauseMenu, "fullscreen")
      press(pauseMenu, MenuKey.A)
      assertTrue(pauseBridge.fullscreen)
      assertTrue(
          pauseMenu.presentationForTest().items().single { it.id() == "fullscreen" }.checked())
      assertEquals(MenuRoute.PAUSE_CONSOLE, pauseMenu.routeForTest())
    }

    val libraryBridge = FakeBridge(gameLoaded = false)
    val libraryMenu = newMenu(libraryBridge)
    javax.swing.SwingUtilities.invokeAndWait {
      repeat(3) { press(libraryMenu, MenuKey.DOWN) }
      press(libraryMenu, MenuKey.A)
      assertTrue(libraryBridge.fullscreen)
      assertTrue(
          libraryMenu.presentationForTest().items().single { it.id() == "fullscreen" }.checked())
      assertEquals(MenuRoute.LIBRARY, libraryMenu.routeForTest())
    }
  }

  @Test
  fun `external fullscreen changes refresh a visible root without moving focus`() {
    val bridge = FakeBridge(initialFullscreen = true)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      moveToPauseItem(menu, "fullscreen")
      bridge.fullscreen = false
      menu.refreshDisplayPresentation()

      assertEquals(MenuRoute.PAUSE_CONSOLE, menu.routeForTest())
      assertEquals("fullscreen", menu.focusedItemIdForTest())
      assertFalse(
          menu.presentationForTest().items().single { it.id() == "fullscreen" }.checked())
    }
  }

  @Test
  fun `external fullscreen changes refresh a Library root beneath its child`() {
    val bridge = FakeBridge(gameLoaded = false)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      repeat(2) { press(menu, MenuKey.DOWN) }
      assertEquals("settings", menu.focusedItemIdForTest())
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())

      bridge.fullscreen = true
      menu.refreshDisplayPresentation()
      assertEquals(MenuRoute.SETTINGS, menu.routeForTest())

      press(menu, MenuKey.B)
      assertEquals(MenuRoute.LIBRARY, menu.routeForTest())
      assertEquals("settings", menu.focusedItemIdForTest())
      assertTrue(
          menu.presentationForTest().items().single { it.id() == "fullscreen" }.checked())
    }
  }

  @Test
  fun `root B resumes an already paused game`() {
    val bridge = FakeBridge(initiallyPaused = true)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      assertTrue(menu.visible())
      press(menu, MenuKey.B)
    }

    assertFalse(menu.visible())
    assertFalse(bridge.pausedState)
    assertEquals(listOf(false), bridge.pauseTransitions)
  }

  @Test
  fun `queued root B cannot resume after lifecycle teardown wins the EDT race`() {
    val bridge = FakeBridge()
    val menu = newMenu(bridge)
    val edtBlocked = CountDownLatch(1)
    val releaseEdt = CountDownLatch(1)
    javax.swing.SwingUtilities.invokeLater {
      edtBlocked.countDown()
      releaseEdt.await()
    }

    try {
      assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))
      menu.closeForLifecycle()
      physicalPress(menu, Button.B)
    } finally {
      releaseEdt.countDown()
    }
    javax.swing.SwingUtilities.invokeAndWait {}

    assertFalse(menu.visible())
    assertTrue(bridge.pausedState)
    assertEquals(listOf(true), bridge.pauseTransitions)
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
      assertTrue(menu.onKeyDown(MenuKey.B, false))
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
      moveToPauseItem(menu, "reset")
      press(menu, MenuKey.A)

      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      assertEquals("CONFIRM ACTION", menu.presentationForTest().context())
      assertEquals(listOf("confirm", "cancel"), menu.visibleItemIdsForTest())
      assertTrue(menu.presentationForTest().items().all { it.detail().isEmpty() })
      assertEquals("cancel", menu.focusedItemIdForTest())
      press(menu, MenuKey.UP)
      assertEquals("confirm", menu.focusedItemIdForTest())
      press(menu, MenuKey.DOWN)
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
      moveToPauseItem(menu, "reset")
      press(menu, MenuKey.A)
      assertEquals(MenuRoute.CONFIRM_ACTION, menu.routeForTest())
      press(menu, MenuKey.UP)
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
      assertEquals(
          "MODE",
          menu.presentationForTest().items().single { it.id() == "execution-mode" }.label(),
      )
    }
  }

  @Test
  fun `choice picker applies every settings family and restart choices return to game`() {
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
        val pickerItems = menu.presentationForTest().items()
        assertTrue(pickerItems.all { it.widgetType() == MenuWidgetType.CHECKBOX })
        assertTrue(pickerItems.all { it.detail().isEmpty() })
        assertEquals(1, pickerItems.count { it.checked() })
        // Move away from the committed option so this verifies A applies the selected option,
        // rather than merely returning with no change.
        press(menu, MenuKey.DOWN)
        press(menu, MenuKey.A)
        if (origin == MenuRoute.SYSTEM) {
          assertFalse(menu.visible())
          assertEquals(listOf(true, false), bridge.pauseTransitions)
        } else {
          assertEquals(origin, menu.routeForTest())
        }
      }
      assertEquals(settingId, settings.applied.single().first)
      assertTrue(settings.applied.single().second != settings.initialValue(settingId))
    }
  }

  @Test
  fun `changed system choice resumes a menu-owned pause before applying it`() {
    lateinit var bridge: FakeBridge
    val settings =
        FakeSettingsAccess { id, _ ->
          assertEquals(PortableMenuSettingId.DMG_GAMES, id)
          assertFalse(bridge.pausedState)
        }
    bridge = FakeBridge(settingsAccess = settings)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.SYSTEM)
      press(menu, MenuKey.A)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
    }

    assertFalse(menu.visible())
    assertEquals(listOf(true, false), bridge.pauseTransitions)
    assertEquals(
        PortableMenuSettingId.DMG_GAMES to "dmg",
        settings.applied.single(),
    )
  }

  @Test
  fun `changed system choice preserves a pause that predates the menu`() {
    lateinit var bridge: FakeBridge
    val settings = FakeSettingsAccess { _, _ -> assertTrue(bridge.pausedState) }
    bridge = FakeBridge(settingsAccess = settings, initiallyPaused = true)
    val menu = newMenu(bridge)

    javax.swing.SwingUtilities.invokeAndWait {
      menu.openRouteForTest(MenuRoute.SYSTEM)
      press(menu, MenuKey.A)
      press(menu, MenuKey.DOWN)
      press(menu, MenuKey.A)
    }

    assertFalse(menu.visible())
    assertTrue(bridge.pausedState)
    assertTrue(bridge.pauseTransitions.isEmpty())
  }

  @Test
  fun `idle and unchanged system choices stay in the menu`() {
    val idleSettings = FakeSettingsAccess()
    val idleBridge = FakeBridge(settingsAccess = idleSettings, gameLoaded = false)
    val idleMenu = newMenu(idleBridge)
    val unchangedSettings = FakeSettingsAccess()
    val unchangedBridge = FakeBridge(settingsAccess = unchangedSettings)
    val unchangedMenu = newMenu(unchangedBridge)

    javax.swing.SwingUtilities.invokeAndWait {
      idleMenu.openRouteForTest(MenuRoute.SYSTEM)
      press(idleMenu, MenuKey.A)
      press(idleMenu, MenuKey.DOWN)
      press(idleMenu, MenuKey.A)
      assertEquals(MenuRoute.SYSTEM, idleMenu.routeForTest())
      assertTrue(idleMenu.visible())

      unchangedMenu.openRouteForTest(MenuRoute.SYSTEM)
      press(unchangedMenu, MenuKey.A)
      press(unchangedMenu, MenuKey.A)
      assertEquals(MenuRoute.SYSTEM, unchangedMenu.routeForTest())
      assertTrue(unchangedMenu.visible())
    }

    assertTrue(idleBridge.pauseTransitions.isEmpty())
    assertEquals(listOf(true), unchangedBridge.pauseTransitions)
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
      moveToPauseItem(menu, "recent-games")
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
      moveToPauseItem(menu, "recent-games")
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
      moveToPauseItem(menu, "recent-games")
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

  private fun focusedLabel(menu: SwingProposal3Menu): String {
    val presentation = menu.presentationForTest()
    return presentation.items()[presentation.focusedIndex()].label()
  }

  private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach { path ->
        try {
          Files.deleteIfExists(path)
        } catch (failure: IOException) {
          throw AssertionError("Unable to clean test path $path", failure)
        }
      }
    }
  }

  private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      tasks.addLast(command)
    }

    fun runNextAndFlushEdt() {
      check(tasks.isNotEmpty()) { "No queued browser listing" }
      tasks.removeFirst().run()
      javax.swing.SwingUtilities.invokeAndWait {}
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

  private fun moveToPauseItem(menu: SwingProposal3Menu, itemId: String) {
    repeat(menu.visibleItemIdsForTest().size) {
      if (menu.focusedItemIdForTest() == itemId) return
      press(menu, MenuKey.DOWN)
    }
    error("Pause item $itemId was not reachable")
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
              DesktopCommand.FULLSCREEN,
      ),
      private val audioAvailable: Boolean = true,
      private val aboutAvailable: Boolean = true,
      private val settingsAccess: PortableMenuSettingsAccess? = null,
      private val recentAvailable: Boolean = true,
      private val playTimeNanos: Long = 0,
      private val gameLoaded: Boolean = true,
      initiallyPaused: Boolean = false,
      initialFullscreen: Boolean = false,
      private val preferredRomDirectory: Path? = null,
      private val romPathAccepted: Boolean = true,
      private val duringRomPath: ((Path) -> Unit)? = null,
  ) : PortableMenuCommandBridge {
    val invoked = mutableListOf<DesktopCommand>()
    val pauseTransitions = mutableListOf<Boolean>()
    var pausedState = initiallyPaused
    var fullscreen = initialFullscreen
    var muted = false
    var volume = 100
    var aboutOpened = false
    var preferencesOpened = 0
    var loadedSlot: Int? = null
    var savedSlot: Int? = null
    var recentCatalog: List<PortableMenuRecentGame> = emptyList()
    var openedRecentPath: Path? = null
    var openedRecentOrigin: RomOrigin? = null
    val openedRomPaths = mutableListOf<Path>()
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
                    paused = pausedState,
                    stateCommandsAvailable = gameLoaded,
                    stateBrowserAvailable = gameLoaded,
                    muted = muted,
                    audioVolume = volume,
                    fullscreen = fullscreen,
                ),
        )

    override fun isEnabled(command: DesktopCommand): Boolean = command in enabledCommands

    override fun invoke(command: DesktopCommand) {
      invoked += command
      if (command == DesktopCommand.MUTE) muted = !muted
      if (command == DesktopCommand.FULLSCREEN) fullscreen = !fullscreen
    }

    override fun preferredRomDirectory(): Path? = preferredRomDirectory

    override fun openRomPathFromMenu(path: Path): Boolean {
      openedRomPaths.add(path)
      duringRomPath?.invoke(path)
      return romPathAccepted
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
      pausedState = paused
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

  private class FakeSettingsAccess(
      private val beforeApply: (String, String) -> Unit = { _, _ -> },
  ) : PortableMenuSettingsAccess {
    val applied = mutableListOf<Pair<String, String>>()
    val toggled = mutableListOf<String>()
    private var current = initialSnapshot()

    override fun snapshot(): PortableMenuSettingsSnapshot = current

    override fun applyChoice(id: String, token: String) {
      require(current.choicesFor(id).any { it.token == token && it.enabled })
      beforeApply(id, token)
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
