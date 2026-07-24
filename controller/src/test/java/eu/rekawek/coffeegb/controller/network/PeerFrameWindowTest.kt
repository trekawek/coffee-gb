package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.StateLimits
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class PeerFrameWindowTest {

  @Test
  fun exactRollbackAndFutureBoundariesAreAccepted() {
    val window = PeerFrameWindow()
    window.validateStateFrame(1_000)
    window.accept(1_000)

    assertEquals(700, window.validateRuntimeFrame(700))
    assertEquals(1_120, window.validateRuntimeFrame(1_120))
    assertFailsWith<IOException> { window.validateRuntimeFrame(699) }
    assertFailsWith<IOException> { window.validateRuntimeFrame(1_121) }
  }

  @Test
  fun negativeAndExtremeLongFramesAreRejected() {
    assertFailsWith<IOException> { PeerFrameWindow().validateStateFrame(-1) }
    assertFailsWith<IOException> { PeerFrameWindow().validateStateFrame(Long.MIN_VALUE) }
    assertFailsWith<IOException> { PeerFrameWindow().validateStateFrame(Long.MAX_VALUE) }
    assertEquals(
        StateLimits.NETPLAY_MAX_FRAME,
        PeerFrameWindow().validateStateFrame(StateLimits.NETPLAY_MAX_FRAME),
    )
  }

  @Test
  fun rejectedFrameDoesNotAdvanceTheWindow() {
    val window = PeerFrameWindow()
    window.validateStateFrame(100)
    window.accept(100)

    assertFailsWith<IOException> { window.validateRuntimeFrame(221) }
    assertEquals(0, window.validateRuntimeFrame(0))
  }

  @Test
  fun runtimeAndCheckpointRequireASequencedState() {
    val window = PeerFrameWindow()
    assertFailsWith<IOException> { window.validateRuntimeFrame(0) }

    window.validateStateFrame(50)
    window.accept(50)
    assertFailsWith<IOException> { window.validateCheckpoint(50, emptyList()) }
    assertFailsWith<IOException> { window.validateCheckpoint(50, listOf(49, 50)) }
    assertEquals(50, window.validateCheckpoint(50, listOf(50, 50)))
  }
}
