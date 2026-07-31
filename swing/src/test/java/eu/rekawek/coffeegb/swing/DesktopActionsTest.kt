package eu.rekawek.coffeegb.swing

import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Action
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopActionsTest {
  @Test
  fun `one command snapshot drives labels enablement selection and callbacks`() {
    val calls = mutableListOf<String>()
    val registry = registry(calls)

    registry.update(
        DesktopCommandPresentation(
            gameLoaded = true,
            pauseSupported = true,
            paused = true,
            stateCommandsAvailable = true,
            stateBrowserAvailable = true,
            stateSlot = 4,
            muted = true,
            commandBarVisible = false,
        ))

    assertEquals("Resume", registry[DesktopCommand.PAUSE].getValue(Action.NAME))
    assertEquals(true, registry[DesktopCommand.PAUSE].getValue(Action.SELECTED_KEY))
    assertEquals("Unmute", registry[DesktopCommand.MUTE].getValue(Action.NAME))
    assertTrue(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertFalse(
        registry[DesktopCommand.FULLSCREEN].getValue(Action.SELECTED_KEY) as Boolean)
    assertEquals(true, registry.stateSlotActions[4].getValue(Action.SELECTED_KEY))

    registry[DesktopCommand.PAUSE].actionPerformed(event())
    registry[DesktopCommand.SAVE_STATE].actionPerformed(event())
    registry[DesktopCommand.MUTE].actionPerformed(event())
    registry.stateSlotActions[7].actionPerformed(event())

    assertEquals(listOf("paused=false", "save=4", "muted=false", "slot=7"), calls)
    assertEquals(7, registry.current().stateSlot)
  }

  @Test
  fun `session activity disables only conflicting commands`() {
    val registry = registry(mutableListOf())
    registry.update(
        DesktopCommandPresentation(
            gameLoaded = true,
            sessionBusy = true,
            pauseSupported = true,
            stateCommandsAvailable = true,
            stateBrowserAvailable = true,
        ))

    assertFalse(registry[DesktopCommand.OPEN_ROM].isEnabled)
    assertFalse(registry[DesktopCommand.RESET].isEnabled)
    assertFalse(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertFalse(registry[DesktopCommand.NETPLAY].isEnabled)
    assertTrue(registry[DesktopCommand.QUIT].isEnabled.not())
  }

  @Test
  fun `gameplay bindings withdraw only matching unmodified application shortcuts`() {
    val shortcuts =
        DesktopShortcutRegistry(
            gameplayKeyCodes = listOf(KeyEvent.VK_SPACE, KeyEvent.VK_F11),
            platformMenuMask = InputEvent.CTRL_DOWN_MASK,
        )

    assertNull(shortcuts[DesktopCommand.PAUSE])
    assertNull(shortcuts[DesktopCommand.FULLSCREEN])
    assertEquals(
        "Inactive because this key is assigned to gameplay",
        shortcuts.shortcuts.getValue(DesktopCommand.PAUSE).inactiveReason,
    )
    assertEquals(KeyEvent.VK_O, shortcuts[DesktopCommand.OPEN_ROM]?.keyCode)
    assertTrue(
        shortcuts[DesktopCommand.OPEN_ROM]!!.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
    assertEquals(10, shortcuts.stateSlotShortcuts.size)
  }

  private fun registry(calls: MutableList<String>): DesktopActionRegistry =
      DesktopActionRegistry(
          DesktopCommandHandlers(
              openRom = { calls += "open" },
              closeGame = { calls += "close" },
              preferences = { calls += "preferences" },
              quit = { calls += "quit" },
              setPaused = { calls += "paused=$it" },
              reset = { calls += "reset" },
              saveState = { calls += "save=$it" },
              loadState = { calls += "load=$it" },
              manageStates = { calls += "states" },
              openSaveFolder = { calls += "folder" },
              netplay = { calls += "netplay" },
              setMuted = { calls += "muted=$it" },
              setFullscreen = { calls += "fullscreen=$it" },
              screenshot = { calls += "screenshot" },
              setCommandBarVisible = { calls += "bar=$it" },
              selectStateSlot = { calls += "slot=$it" },
          ))

  private fun event() = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "test")
}
