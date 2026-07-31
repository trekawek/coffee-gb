package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DesktopPlaybackStateTest {

  @Test
  fun `shell follows only authoritative playback events from its active generation`() {
    val coordinator =
        DesktopUiCoordinator(
            DesktopPresentation(),
            render = {},
            edtCheck = { true },
        )
    val playback = DesktopPlaybackState(coordinator::paused)

    coordinator.opened("Tetris")
    playback.sessionStarted(42)
    playback.playbackChanged(Controller.SessionPlaybackStateEvent(42, paused = true))
    assertTrue(coordinator.current().commands.paused)

    // A desktop Resume request cannot claim the machine resumed while the debugger still owns it.
    playback.playbackChanged(Controller.SessionPlaybackStateEvent(42, paused = true))
    assertTrue(coordinator.current().commands.paused)

    playback.sessionStarted(43)
    coordinator.opened("Kirby's Dream Land")
    playback.playbackChanged(Controller.SessionPlaybackStateEvent(42, paused = false))
    assertFalse(coordinator.current().commands.paused)
    playback.playbackChanged(Controller.SessionPlaybackStateEvent(43, paused = true))
    assertTrue(coordinator.current().commands.paused)

    playback.sessionStopped()
    coordinator.stopped()
    playback.playbackChanged(Controller.SessionPlaybackStateEvent(43, paused = true))
    assertFalse(coordinator.current().commands.paused)
  }
}
