package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.TurboFileSerialEndpoint
import java.nio.file.Files
import java.nio.file.Path

/** Durable device flash, independent of any one game cartridge's battery file. */
internal class TurboFileStorage(
    private val path: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter.system(),
) {
  fun open(advance: Boolean, failed: () -> Unit): TurboFileSerialEndpoint {
    val endpoint = TurboFileSerialEndpoint(advance) { data ->
      try {
        Files.createDirectories(path.toAbsolutePath().parent)
        writer.writeOwnerOnly(path, data)
      } catch (failure: Exception) {
        failed()
        throw IllegalStateException("Turbo File storage write failed", failure)
      }
    }
    if (writer.exists(path)) {
      val data = writer.read(path) {
        require(Files.size(it) == TurboFileSerialEndpoint.STORAGE_BYTES.toLong()) {
          "Turbo File storage must contain exactly 2 MiB"
        }
        Files.readAllBytes(it)
      }
      endpoint.loadStorage(data)
    }
    return endpoint
  }
}
