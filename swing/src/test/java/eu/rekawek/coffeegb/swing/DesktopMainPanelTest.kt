package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateImage
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ComponentEvent
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DesktopMainPanelTest {

  @Test
  fun `dynamic pause command is a plain menu item that follows the shared action label`() {
    onEdt {
      val actions = actions()
      val item = pauseResumeMenuItem(actions[DesktopCommand.PAUSE])

      assertEquals(JMenuItem::class.java, item.javaClass)
      assertEquals("Pause", item.text)

      actions.update(
          DesktopCommandPresentation(
              gameLoaded = true,
              pauseSupported = true,
              paused = true,
          ))

      assertEquals("Resume", item.text)
      assertTrue(item.isEnabled)
    }
  }

  @Test
  fun `state slot selector mirrors command availability and ignores disabled changes`() {
    onEdt {
      val selections = mutableListOf<Int>()
      val actions = actions(selections::add)
      val panel = panel(actions)
      val selector =
          descendants(panel).filterIsInstance<JComboBox<*>>().single {
            it.accessibleContext.accessibleName == "Current state slot"
          }

      assertFalse(selector.isEnabled)
      selector.selectedIndex = 3
      assertTrue(selections.isEmpty())

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      stateCommandsAvailable = true,
                      stateSlot = 2,
                  ),
          ))
      assertTrue(selector.isEnabled)
      assertEquals(2, selector.selectedIndex)

      selector.selectedIndex = 4
      assertEquals(listOf(4), selections)

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      sessionBusy = true,
                      stateCommandsAvailable = true,
                      stateSlot = 4,
                  ),
          ))
      assertFalse(selector.isEnabled)
      selector.selectedIndex = 5
      assertEquals(listOf(4), selections)
    }
  }

  @Test
  fun `first open shows its cancellable task outside the home and game cards`() {
    onEdt {
      var cancellations = 0
      val actions = actions()
      val panel = panel(actions, onCancel = { cancellations++ })

      panel.render(
          DesktopPresentation(
              task = DesktopSessionTask("Opening Tetris.gb…", cancellable = true),
              commands = DesktopCommandPresentation(sessionBusy = true),
              persistentStatus = "Opening Tetris.gb",
          ))

      val components = descendants(panel)
      assertTrue(
          components.filterIsInstance<JLabel>().any {
            it.text == "Opening Tetris.gb…" && it.isVisible
          })
      val cancel =
          components.filterIsInstance<AbstractButton>().single {
            it.text == "Cancel" && it.isVisible
          }
      cancel.doClick()
      assertEquals(1, cancellations)
      assertEquals("Opening Tetris.gb", panel.accessibleContext.accessibleDescription)
    }
  }

  @Test
  fun `home uses the shared open action for every configured recent entry`() {
    onEdt {
      val actions = actions()
      val panel = panel(actions)
      panel.updateRecentRoms((0..7).map { Path.of("/games/game-$it.gb") })

      val home = descendants(panel).filterIsInstance<DesktopHomePanel>().single()
      val buttons = descendants(home).filterIsInstance<AbstractButton>()
      assertEquals(1, buttons.count { it.action === actions[DesktopCommand.OPEN_ROM] })
      assertEquals(
          8,
          buttons.count {
            it.accessibleContext.accessibleName?.startsWith("Open recent ROM") == true
          },
      )
      assertTrue(buttons.all { !it.isFocusable })
      assertTrue(descendants(home).filterIsInstance<JLabel>().isEmpty())
    }
  }

  @Test
  fun `clicking a recent-game thumbnail opens that game`() {
    onEdt {
      val opened = mutableListOf<Path>()
      val panel = panel(actions(), onOpenRecent = opened::add)
      val path = Path.of("/games/tetris.gb")
      panel.updateRecentGames(
          listOf(
              DesktopRecentGame(
                  path,
                  StateImage(1, 1, intArrayOf(0x112233)),
              )))

      val preview =
          descendants(panel).filterIsInstance<AbstractButton>().single {
            it.accessibleContext.accessibleName == "Open recent ROM tetris.gb"
          }
      assertTrue(preview.icon != null)
      preview.doClick()
      assertEquals(listOf(path), opened)
    }
  }

  @Test
  fun `command bar is visible only for windowed play`() {
    onEdt {
      val panel = panel(actions())
      val commandBar =
          descendants(panel).filterIsInstance<JPanel>().single {
            it.accessibleContext.accessibleName == "Game commands"
          }

      panel.render(DesktopPresentation())
      assertFalse(commandBar.isVisible)

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      commandBarVisible = true,
                  ),
          ))
      assertTrue(commandBar.isVisible)

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      fullscreen = true,
                      commandBarVisible = true,
                  ),
          ))
      assertFalse(commandBar.isVisible)
    }
  }

  @Test
  fun `game command controls never retain keyboard focus`() {
    onEdt {
      val panel = panel(actions())
      val commandBar =
          descendants(panel).filterIsInstance<JPanel>().single {
            it.accessibleContext.accessibleName == "Game commands"
          }

      val controls =
          descendants(commandBar).filter { it is AbstractButton || it is JComboBox<*> }
      assertTrue(controls.isNotEmpty())
      assertTrue(
          controls.all { !it.isFocusable },
          "Game controls must not steal mapped emulator keys after a click",
      )
    }
  }

  @Test
  fun `text status remains visible when full screen hides command chrome`() {
    onEdt {
      val actions = actions()
      val panel = panel(actions)
      val statusBar = descendants(panel).filterIsInstance<DesktopStatusBar>().single()

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      paused = true,
                      muted = true,
                      fullscreen = true,
                      stateSlot = 4,
                  ),
              netplaySummary = "Netplay: Connected",
              persistentStatus = "Camera 2 could not be opened.",
              statusRecoveryCommand = DesktopCommand.PREFERENCES,
          ))

      assertTrue(statusBar.isVisible)
      assertEquals("Camera 2 could not be opened.", statusBar.message.text)
      assertEquals(
          "Paused  ·  Muted  ·  Slot 4  ·  Netplay: Connected",
          statusBar.session.text,
      )
      assertTrue(statusBar.recovery.isVisible)
      assertSame(actions[DesktopCommand.PREFERENCES], statusBar.recovery.action)
      assertFalse(statusBar.accessibleContext.accessibleDescription.isNullOrBlank())
    }
  }

  @Test
  fun `status reports display-thread FPS instead of an emulated frame cadence`() {
    onEdt {
      val panel = panel(actions())
      val statusBar = descendants(panel).filterIsInstance<DesktopStatusBar>().single()

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands = DesktopCommandPresentation(gameLoaded = true),
              presentedFramesPerSecond = 29.7,
          ))

      assertTrue(statusBar.session.text.contains("FPS 29.7"))
    }
  }

  @Test
  fun `exact one scale packs to the raster and reveals commands only after a wide resize`() {
    onEdt {
      val surface = JPanel().apply {
        preferredSize = Dimension(160, 144)
        minimumSize = Dimension(160, 144)
      }
      val panel =
          DesktopMainPanel(
              gameSurface = surface,
              actions = actions(),
              onOpenRecent = {},
              onCancelTask = {},
              initialTokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM),
          )
      val commandBar =
          descendants(panel).filterIsInstance<JPanel>().single {
            it.accessibleContext.accessibleName == "Game commands"
          }

      panel.render(
          DesktopPresentation(
              gameTitle = "Tetris",
              commands =
                  DesktopCommandPresentation(
                      gameLoaded = true,
                      commandBarVisible = true,
                      exactWindowScaleOne = true,
                  ),
          ))

      assertFalse(commandBar.isVisible)
      assertTrue(panel.preferredSize.width < 560, "Home must not inflate exact 1× packing")
      panel.setSize(800, 600)
      panel.componentListeners.forEach {
        it.componentResized(ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED))
      }
      assertTrue(commandBar.isVisible)
    }
  }

  private fun panel(
      actions: DesktopActionRegistry,
      onOpenRecent: (Path) -> Unit = {},
      onCancel: () -> Unit = {},
  ) =
      DesktopMainPanel(
          gameSurface = JPanel(),
          actions = actions,
          onOpenRecent = onOpenRecent,
          onCancelTask = onCancel,
          initialTokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM),
      )

  private fun actions(
      onSelectStateSlot: (Int) -> Unit = {},
  ): DesktopActionRegistry =
      DesktopActionRegistry(
          DesktopCommandHandlers(
              openRom = {},
              closeGame = {},
              preferences = {},
              quit = {},
              setPaused = {},
              reset = {},
              saveState = {},
              loadState = {},
              manageStates = {},
              openSaveFolder = {},
              netplay = {},
              setMuted = {},
              setFullscreen = {},
              screenshot = {},
              setCommandBarVisible = {},
              selectStateSlot = onSelectStateSlot,
          ))

  private fun descendants(component: Component): List<Component> =
      buildList {
        add(component)
        if (component is Container) {
          component.components.forEach { addAll(descendants(it)) }
        }
      }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
