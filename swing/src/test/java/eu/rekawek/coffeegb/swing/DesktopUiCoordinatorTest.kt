package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopUiCoordinatorTest {
  @Test
  fun `replacement activity preserves the committed game until a new game opens`() {
    val rendered = mutableListOf<DesktopPresentation>()
    val coordinator =
        DesktopUiCoordinator(DesktopPresentation(), rendered::add, edtCheck = { true })

    coordinator.opened("Tetris")
    coordinator.opening("Pokemon.gbc", cancellable = true)

    val replacing = rendered.last()
    assertEquals("Tetris", replacing.gameTitle)
    assertTrue(replacing.commands.gameLoaded)
    assertTrue(replacing.commands.sessionBusy)
    assertTrue(replacing.task!!.cancellable)

    coordinator.openingFinished("The new game could not be opened")
    assertEquals("Tetris", coordinator.current().gameTitle)
    assertFalse(coordinator.current().commands.sessionBusy)
    assertNull(coordinator.current().task)
  }

  @Test
  fun `stop clears impossible game-only command state`() {
    val coordinator =
        DesktopUiCoordinator(
            DesktopPresentation(
                commands =
                    DesktopCommandPresentation(
                        gameLoaded = true,
                        paused = true,
                        pauseSupported = true,
                        stateCommandsAvailable = true,
                        fullscreen = true,
                    )),
            render = {},
            edtCheck = { true },
        )

    assertFalse(coordinator.current().commands.gameLoaded)
    coordinator.opened("Kirby's Dream Land")
    coordinator.pauseSupport(true)
    coordinator.paused(true)
    coordinator.stopped()

    val stopped = coordinator.current()
    assertNull(stopped.gameTitle)
    assertFalse(stopped.commands.gameLoaded)
    assertFalse(stopped.commands.paused)
    assertFalse(stopped.commands.pauseSupported)
    assertFalse(stopped.commands.fullscreen)
    assertEquals("Ready", stopped.persistentStatus)
  }

  @Test
  fun `feature summaries update without replacing unrelated shell state`() {
    val coordinator =
        DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })
    coordinator.opened("Alleyway")
    coordinator.stateAvailability(quick = true, browser = true)
    coordinator.muted(true)
    coordinator.commandBarVisible(false)
    coordinator.stateSlot(9)
    coordinator.netplaySummary("Netplay: Hosting")

    val state = coordinator.current()
    assertEquals("Alleyway", state.gameTitle)
    assertTrue(state.commands.stateCommandsAvailable)
    assertTrue(state.commands.muted)
    assertFalse(state.commands.commandBarVisible)
    assertEquals(9, state.commands.stateSlot)
    assertEquals("Netplay: Hosting", state.netplaySummary)
  }

  @Test
  fun `display settings publish full screen and exact one scale as one presentation`() {
    val coordinator = DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })
    coordinator.displaySettings(
        ApplicationSettings.Display(
            scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
            explicitScale = 1,
            fullscreen = true,
        ))

    assertTrue(coordinator.current().commands.fullscreen)
    assertTrue(coordinator.current().commands.exactWindowScaleOne)

    coordinator.displaySettings(
        ApplicationSettings.Display(
            scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
            explicitScale = 4,
        ))
    assertFalse(coordinator.current().commands.fullscreen)
    assertFalse(coordinator.current().commands.exactWindowScaleOne)
  }

  @Test
  fun `recovery notice survives ordinary lifecycle status until explicitly cleared`() {
    val coordinator = DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })

    coordinator.warning("Settings could not be saved", DesktopCommand.PREFERENCES)
    coordinator.opened("Tetris")
    coordinator.paused(true)
    coordinator.stopped()

    assertEquals("Settings could not be saved", coordinator.current().visibleStatus)
    assertEquals(DesktopCommand.PREFERENCES, coordinator.current().notice?.recoveryCommand)

    coordinator.clearNotice()
    assertEquals("Ready", coordinator.current().visibleStatus)
    assertNull(coordinator.current().notice)
  }
}
