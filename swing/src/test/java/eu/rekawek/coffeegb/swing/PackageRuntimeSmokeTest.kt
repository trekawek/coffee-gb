package eu.rekawek.coffeegb.swing

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
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

  @Test
  fun `association fixture is exact bounded and create-only`() {
    val directory = createTempDirectory("coffee-gb-association-fixture")
    try {
      val fixture = directory.resolve("Coffee GB association smoke.GB")

      PackageAssociationFixture.write(fixture)

      assertContentEquals(syntheticPackageRom(), Files.readAllBytes(fixture))
      assertTrue(Files.size(fixture) == 0x8000L)
      assertFails { PackageAssociationFixture.write(fixture) }
      assertFails { PackageAssociationFixture.write(directory.resolve("fixture.zip")) }
      val realParent = Files.createDirectory(directory.resolve("real-parent"))
      val linkedParent = directory.resolve("linked-parent")
      runCatching { Files.createSymbolicLink(linkedParent, realParent) }
          .onSuccess {
            assertFails {
              PackageAssociationFixture.write(linkedParent.resolve("through-link.gb"))
            }
          }
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
