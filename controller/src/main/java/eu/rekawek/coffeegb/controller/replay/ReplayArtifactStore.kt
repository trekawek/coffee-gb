package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.state.ExclusiveFileWriter
import eu.rekawek.coffeegb.controller.state.ExclusiveWriteRecovery
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ReplayArtifactResult(
    val path: Path,
    val size: Int,
    val recovery: ExclusiveWriteRecovery,
)

/** Writes one complete, bounded CGBR artifact without overwriting an existing recording. */
class ReplayArtifactStore(
    private val directory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
  fun save(replay: ReplayFile): ReplayArtifactResult {
    val encoded = ReplayCodec.encode(replay)
    val written =
        ExclusiveFileWriter.collisionSafe(
            directory,
            "coffee-gb-${FILE_TIME.format(clock.instant())}",
            "cgbreplay",
            encoded,
        )
    return ReplayArtifactResult(written.path, encoded.size, written.recovery)
  }

  private companion object {
    val FILE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
  }
}
