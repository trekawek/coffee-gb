package eu.rekawek.coffeegb.swing

import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.Action
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            loadableStateSlots = setOf(4),
            muted = true,
            commandBarVisible = false,
        ))

    assertEquals("Resume", registry[DesktopCommand.PAUSE].getValue(Action.NAME))
    assertEquals(true, registry[DesktopCommand.PAUSE].getValue(Action.SELECTED_KEY))
    assertEquals("Unmute", registry[DesktopCommand.MUTE].getValue(Action.NAME))
    assertTrue(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertTrue(registry[DesktopCommand.LOAD_STATE].isEnabled)
    assertFalse(
        registry[DesktopCommand.FULLSCREEN].getValue(Action.SELECTED_KEY) as Boolean)
    assertEquals(true, registry.stateSlotActions[4].getValue(Action.SELECTED_KEY))

    registry[DesktopCommand.PAUSE].actionPerformed(event())
    registry[DesktopCommand.SAVE_STATE].actionPerformed(event())
    registry[DesktopCommand.LOAD_STATE].actionPerformed(event())
    registry[DesktopCommand.MUTE].actionPerformed(event())
    registry.stateSlotActions[7].actionPerformed(event())

    assertEquals(
        listOf("paused=false", "save=4", "load=4", "muted=false", "slot=7"),
        calls,
    )
    assertEquals(7, registry.current().stateSlot)
    assertFalse(registry[DesktopCommand.LOAD_STATE].isEnabled)
  }

  @Test
  fun `selected slot gates only load while save and slot selection remain available`() {
    val registry = registry(mutableListOf())
    registry.update(
        DesktopCommandPresentation(
            gameLoaded = true,
            stateCommandsAvailable = true,
            stateSlot = 2,
            loadableStateSlots = setOf(4),
        ))

    assertTrue(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertFalse(registry[DesktopCommand.LOAD_STATE].isEnabled)
    assertTrue(registry.stateSlotActions.all { it.isEnabled })

    registry.stateSlotActions[4].actionPerformed(event())

    assertTrue(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertTrue(registry[DesktopCommand.LOAD_STATE].isEnabled)
    assertTrue(registry.stateSlotActions.all { it.isEnabled })
  }

  @Test
  fun `command presentation rejects loadable slots outside the stable range`() {
    assertFailsWith<IllegalArgumentException> {
      DesktopCommandPresentation(loadableStateSlots = setOf(-1))
    }
    assertFailsWith<IllegalArgumentException> {
      DesktopCommandPresentation(loadableStateSlots = setOf(10))
    }
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
            loadableStateSlots = setOf(0),
        ))

    assertFalse(registry[DesktopCommand.OPEN_ROM].isEnabled)
    assertFalse(registry[DesktopCommand.RESET].isEnabled)
    assertFalse(registry[DesktopCommand.SAVE_STATE].isEnabled)
    assertFalse(registry[DesktopCommand.LOAD_STATE].isEnabled)
    assertFalse(registry[DesktopCommand.NETPLAY].isEnabled)
    assertFalse(registry[DesktopCommand.FULLSCREEN].isEnabled)
    assertTrue(registry[DesktopCommand.QUIT].isEnabled.not())
  }

  @Test
  fun `Proposal 3 command opens the idle Library only when the desktop feature is enabled`() {
    val handlers = handlers(mutableListOf())
    val hidden = DesktopActionRegistry(handlers, proposal3MenuAvailable = false)
    val enabled = DesktopActionRegistry(handlers, proposal3MenuAvailable = true)
    val idle = DesktopCommandPresentation()

    hidden.update(idle)
    enabled.update(idle)

    assertFalse(hidden[DesktopCommand.OPEN_MENU].isEnabled)
    assertTrue(enabled[DesktopCommand.OPEN_MENU].isEnabled)
  }

  @Test
  fun `fullscreen remains actionable from the idle Library`() {
    val calls = mutableListOf<String>()
    val registry = registry(calls)
    registry.update(DesktopCommandPresentation())

    assertTrue(registry[DesktopCommand.FULLSCREEN].isEnabled)
    registry[DesktopCommand.FULLSCREEN].actionPerformed(event())

    assertEquals(listOf("fullscreen=true"), calls)
  }

  @Test
  fun `inline volume capability does not depend on preferences command enablement`() {
    val calls = mutableListOf<String>()
    val registry =
        DesktopActionRegistry(
            handlers(calls).copy(setAudioVolume = { calls += "volume=$it" }),
        )
    registry.update(
        DesktopCommandPresentation(
            sessionBusy = true,
            audioVolume = 37,
        ))

    assertFalse(registry[DesktopCommand.PREFERENCES].isEnabled)
    assertEquals(37, registry.audioVolume())

    registry.setAudioVolume(38)

    assertEquals(listOf("volume=38"), calls)
  }

  @Test
  fun `externally applied volume refreshes cached overlay value before the next adjustment`() {
    val calls = mutableListOf<String>()
    val registry =
        DesktopActionRegistry(
            handlers(calls).copy(setAudioVolume = { calls += "volume=$it" }),
        )

    registry.update(DesktopCommandPresentation(audioVolume = 37))
    // Mirrors a volume changed through the desktop Preferences dialog rather than the overlay.
    registry.update(DesktopCommandPresentation(audioVolume = 82))

    assertEquals(82, registry.audioVolume())
    registry.setAudioVolume(registry.audioVolume()!! - 5)

    assertEquals(listOf("volume=77"), calls)
  }

  @Test
  fun `recent-open capability reflects the host handler and session availability`() {
    val unsupported = DesktopActionRegistry(handlers(mutableListOf()))
    val supported =
        DesktopActionRegistry(
            handlers(mutableListOf()).copy(openRecentGame = {}),
        )

    assertFalse(unsupported.canOpenRecentGame())
    assertTrue(supported.canOpenRecentGame())

    supported.update(DesktopCommandPresentation(sessionBusy = true))
    assertFalse(supported.canOpenRecentGame())
  }

  @Test
  fun `portable ROM chooser reports cancellation without changing the native action`() {
    val calls = mutableListOf<String>()
    val registry =
        DesktopActionRegistry(
            handlers(calls).copy(
                openRomFromPortableMenu = {
                  calls += "portable-open"
                  false
                },
            ),
        )

    assertFalse(registry.openRomFromMenu())
    registry[DesktopCommand.OPEN_ROM].actionPerformed(event())

    assertEquals(listOf("portable-open", "open"), calls)
  }

  @Test
  fun `portable ROM browser reads its preferred directory and delegates the exact path`() {
    val calls = mutableListOf<String>()
    val preferred = Path.of("configured-roms")
    val selected = Path.of("configured-roms", "exact name.gb")
    val registry =
        DesktopActionRegistry(
            handlers(calls).copy(
                preferredRomDirectory = { preferred },
                openRomPathFromPortableMenu = { path ->
                  calls += "path=$path"
                  false
                },
            ),
        )

    assertEquals(preferred, registry.preferredRomDirectory())
    assertFalse(registry.openRomPathFromMenu(selected))
    assertEquals(listOf("path=$selected"), calls)

    registry.update(DesktopCommandPresentation(sessionBusy = true))
    assertFalse(registry.openRomPathFromMenu(Path.of("blocked.gb")))
    assertEquals(listOf("path=$selected"), calls)
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
      DesktopActionRegistry(handlers(calls))

  private fun handlers(calls: MutableList<String>): DesktopCommandHandlers =
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
          )

  private fun event() = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "test")
}
