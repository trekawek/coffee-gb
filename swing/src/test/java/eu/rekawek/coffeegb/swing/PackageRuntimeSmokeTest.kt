package eu.rekawek.coffeegb.swing

import kotlin.test.assertTrue
import org.junit.Test

class PackageRuntimeSmokeTest {

  @Test
  fun `generated 7z exercises packaged extraction video audio input and portable state`() {
    val result = PackageRuntimeSmoke.run()

    assertTrue(result.ticks in 1..250_000)
    assertTrue(result.videoFrames > 0)
    assertTrue(result.audioBuffers > 0)
    assertTrue(result.stateBytes > 0)
    assertTrue(result.nativeTarget == "portable")
  }
}
