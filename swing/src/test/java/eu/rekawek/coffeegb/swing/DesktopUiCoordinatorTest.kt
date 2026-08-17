package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopUiCoordinatorTest {
  @Test
  fun `pause support announced before start is staged until the game opens`() {
    val coordinator =
        DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })

    coordinator.pauseSupport(true)
    assertFalse(coordinator.current().commands.pauseSupported)

    coordinator.opened("Tetris")
    assertTrue(coordinator.current().commands.pauseSupported)

    coordinator.stopped()
    coordinator.opened("Alleyway")
    assertFalse(coordinator.current().commands.pauseSupported)
  }

  @Test
  fun `replacement activity preserves the committed game until a new game opens`() {
    val rendered = mutableListOf<DesktopPresentation>()
    val coordinator =
        DesktopUiCoordinator(DesktopPresentation(), rendered::add, edtCheck = { true })

    coordinator.opened("Tetris")
    coordinator.opening("Pokemon.gbc")
    assertNull(coordinator.current().task)
    assertEquals("Opening Pokemon.gbc", coordinator.current().persistentStatus)
    coordinator.openingProgress("Preparing Pokemon.gbc…")

    val replacing = rendered.last()
    assertEquals("Tetris", replacing.gameTitle)
    assertTrue(replacing.commands.gameLoaded)
    assertTrue(replacing.commands.sessionBusy)
    assertNull(replacing.task)
    assertEquals("Preparing Pokemon.gbc", replacing.persistentStatus)

    coordinator.openingFinished("The new game could not be opened")
    assertEquals("Tetris", coordinator.current().gameTitle)
    assertFalse(coordinator.current().commands.sessionBusy)
    assertNull(coordinator.current().task)
  }

  @Test
  fun `late mapper metadata cannot overwrite the active session`() {
    val coordinator = DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })

    coordinator.opened("Tetris", sessionGeneration = 11)
    coordinator.sessionMetadata(batterySaveActive = true, sessionGeneration = 11)
    coordinator.opened("Kirby", sessionGeneration = 12)
    coordinator.sessionMetadata(batterySaveActive = true, sessionGeneration = 11)

    assertEquals("Kirby", coordinator.current().gameTitle)
    assertFalse(coordinator.current().batterySaveActive)
    assertEquals(12, coordinator.current().sessionGeneration)
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
    coordinator.stateSlotLoadAvailability(3, true)
    coordinator.muted(true)
    coordinator.commandBarVisible(false)
    coordinator.stateSlot(9)
    coordinator.netplaySummary("Netplay: Hosting")

    val state = coordinator.current()
    assertEquals("Alleyway", state.gameTitle)
    assertTrue(state.commands.stateCommandsAvailable)
    assertEquals(setOf(3), state.commands.loadableStateSlots)
    assertTrue(state.commands.muted)
    assertFalse(state.commands.commandBarVisible)
    assertEquals(9, state.commands.stateSlot)
    assertEquals("Netplay: Hosting", state.netplaySummary)
  }

  @Test
  fun `presentation FPS clears on pause and session stop`() {
    val coordinator =
        DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })
    coordinator.opened("Alleyway")
    coordinator.presentedFramesPerSecond(59.7)
    assertEquals(59.7, coordinator.current().presentedFramesPerSecond)

    coordinator.paused(true)
    assertNull(coordinator.current().presentedFramesPerSecond)
    coordinator.presentedFramesPerSecond(29.7)
    coordinator.stopped()
    assertNull(coordinator.current().presentedFramesPerSecond)
  }

  @Test
  fun `slot load availability is removed when state commands or the session stop`() {
    val coordinator = DesktopUiCoordinator(DesktopPresentation(), render = {}, edtCheck = { true })
    coordinator.opened("Tetris")
    coordinator.stateAvailability(quick = true, browser = true)
    coordinator.stateSlotLoadAvailability(2, true)
    assertEquals(setOf(2), coordinator.current().commands.loadableStateSlots)

    coordinator.stateAvailability(quick = false, browser = true)
    assertTrue(coordinator.current().commands.loadableStateSlots.isEmpty())

    coordinator.stateAvailability(quick = true, browser = true)
    coordinator.stateSlotLoadAvailability(4, true)
    coordinator.stopped()
    assertTrue(coordinator.current().commands.loadableStateSlots.isEmpty())
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
