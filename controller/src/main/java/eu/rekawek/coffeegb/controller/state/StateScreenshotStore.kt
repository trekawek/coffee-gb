package eu.rekawek.coffeegb.controller.state

import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Collision-safe screenshot writer whose metadata never includes ROM names, paths, or hashes. */
class StateScreenshotStore(
    private val directory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
  fun save(
      image: StateImage,
      hardwareProfileId: String,
  ): StateScreenshotResult {
    require(hardwareProfileId.matches(PROFILE_ID)) {
      "Hardware profile ID is not safe screenshot metadata"
    }
    val capturedAt = clock.instant()
    val stamp = FILE_TIME.format(capturedAt)
    val png =
        StatePngCodec.encode(
            image,
            mapOf(
                "Captured At" to capturedAt.toString(),
                "Hardware Profile" to hardwareProfileId,
                "Software" to "Coffee GB",
            ),
        )
    val path =
        ExclusiveFileWriter.collisionSafe(
            directory,
            "coffee-gb-$stamp",
            "png",
            png,
        )
    return StateScreenshotResult(path, png.size)
  }

  private companion object {
    val FILE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
    val PROFILE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  }
}
