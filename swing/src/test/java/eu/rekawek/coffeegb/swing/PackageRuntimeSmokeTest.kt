package eu.rekawek.coffeegb.swing

import kotlin.test.assertTrue
import org.junit.Test

class PackageRuntimeSmokeTest {

  @Test
  fun `generated fixture exercises video audio input and portable state`() {
    val result = PackageRuntimeSmoke.run()

    assertTrue(result.ticks in 1..250_000)
    assertTrue(result.videoFrames > 0)
    assertTrue(result.audioBuffers > 0)
    assertTrue(result.stateBytes > 0)
  }
}
