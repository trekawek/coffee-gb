package eu.rekawek.coffeegb.controller.state

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual fixture updater; skipped unless the documented opt-in system property is supplied. */
class StateFileGoldenFixtureUpdaterTest {
  @Test
  fun updateFixtureOnlyWhenExplicitlyRequested() {
    assumeTrue(java.lang.Boolean.getBoolean("stateFile.updateGolden"))
    val directory = Path.of("src/test/resources/state-file-v1")
    Files.createDirectories(directory)
    Files.write(directory.resolve("session-barcode-deflate.cgbstate"), StateFileGoldenFixture.create())
  }
}
