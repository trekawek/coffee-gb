package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackPhase
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class InputRecordingWindowTest {
  @Test
  fun `idle transport enables tape selection and both recording modes`() {
    val presentation =
        DesktopCommandPresentation(
            gameLoaded = true,
            stateCommandsAvailable = true,
        )

    val empty = inputRecordingControlState(presentation, replaySelected = false)
    assertTrue(empty.ejectEnabled)
    assertFalse(empty.playPauseEnabled)
    assertFalse(empty.stopEnabled)
    assertTrue(empty.recordEnabled)
    assertTrue(empty.resetRecordEnabled)

    val loaded = inputRecordingControlState(presentation, replaySelected = true)
    assertTrue(loaded.playPauseEnabled)
  }

  @Test
  fun `recording and playback expose only their applicable transport controls`() {
    val recording =
        inputRecordingControlState(
            DesktopCommandPresentation(
                gameLoaded = true,
                inputRecordingPhase = ReplayRecordingPhase.RECORDING,
            ),
            replaySelected = true,
        )
    assertTrue(recording.stopEnabled)
    assertFalse(recording.ejectEnabled)
    assertFalse(recording.playPauseEnabled)
    assertFalse(recording.recordEnabled)
    assertFalse(recording.resetRecordEnabled)

    val playback =
        inputRecordingControlState(
            DesktopCommandPresentation(
                gameLoaded = true,
                inputPlaybackPhase = ReplayPlaybackPhase.PLAYING,
            ),
            replaySelected = true,
        )
    assertTrue(playback.playPauseEnabled)
    assertTrue(playback.stopEnabled)
    assertFalse(playback.ejectEnabled)
    assertFalse(playback.recordEnabled)
    assertFalse(playback.resetRecordEnabled)

    val completed =
        inputRecordingControlState(
            DesktopCommandPresentation(
                gameLoaded = true,
                inputPlaybackPhase = ReplayPlaybackPhase.COMPLETED,
                paused = true,
            ),
            replaySelected = true,
        )
    assertTrue(completed.stopEnabled)
    assertFalse(completed.playPauseEnabled)
  }

  @Test
  fun `paused ordinary game can reset and record but cannot record current state`() {
    val controls =
        inputRecordingControlState(
            DesktopCommandPresentation(
                gameLoaded = true,
                stateCommandsAvailable = true,
                paused = true,
            ),
            replaySelected = false,
        )

    assertFalse(controls.recordEnabled)
    assertTrue(controls.resetRecordEnabled)
  }
}
