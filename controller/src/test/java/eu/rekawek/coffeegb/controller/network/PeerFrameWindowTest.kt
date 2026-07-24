package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.StateLimits
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class PeerFrameWindowTest {

  @Test
  fun exactRollbackAndFutureBoundariesAreAccepted() {
    assertEquals(700, PeerFrameWindow.validateRuntimeFrame(700, 1_000))
    assertEquals(1_120, PeerFrameWindow.validateRuntimeFrame(1_120, 1_000))
    assertFailsWith<IOException> { PeerFrameWindow.validateRuntimeFrame(699, 1_000) }
    assertFailsWith<IOException> { PeerFrameWindow.validateRuntimeFrame(1_121, 1_000) }
  }

  @Test
  fun negativeAndExtremeLongFramesAreRejected() {
    assertFailsWith<IOException> { PeerFrameWindow.validateRuntimeFrame(-1, 0) }
    assertFailsWith<IOException> { PeerFrameWindow.validateRuntimeFrame(Long.MIN_VALUE, 0) }
    assertFailsWith<IOException> { PeerFrameWindow.validateRuntimeFrame(Long.MAX_VALUE, 0) }
    assertEquals(
        StateLimits.NETPLAY_MAX_FRAME,
        PeerFrameWindow.validateAbsolute(StateLimits.NETPLAY_MAX_FRAME),
    )
  }

  @Test
  fun peerBurstsCannotRatchetTheAuthoritativeWindow() {
    assertEquals(1_120, PeerFrameWindow.validateRuntimeFrame(1_120, 1_000))
    repeat(511) { index ->
      val ratcheted = 1_240L + index * StateLimits.NETPLAY_FUTURE_FRAMES
      assertFailsWith<IOException> {
        PeerFrameWindow.validateRuntimeFrame(ratcheted, 1_000)
      }
    }
  }

  @Test
  fun checkpointRequiresMatchingStatesAndRuntimeHeadroom() {
    assertEquals(50, PeerFrameWindow.validateCheckpoint(50, emptyList()))
    assertFailsWith<IOException> { PeerFrameWindow.validateCheckpoint(50, listOf(49, 50)) }
    assertEquals(50, PeerFrameWindow.validateCheckpoint(50, listOf(50, 50)))
    assertEquals(
        StateLimits.NETPLAY_MAX_REBASE_FRAME,
        PeerFrameWindow.validateCheckpoint(
            StateLimits.NETPLAY_MAX_REBASE_FRAME,
            listOf(StateLimits.NETPLAY_MAX_REBASE_FRAME),
        ),
    )
    assertFailsWith<IOException> {
      PeerFrameWindow.validateCheckpoint(
          StateLimits.NETPLAY_MAX_REBASE_FRAME + 1,
          listOf(StateLimits.NETPLAY_MAX_REBASE_FRAME + 1),
      )
    }
  }
}
