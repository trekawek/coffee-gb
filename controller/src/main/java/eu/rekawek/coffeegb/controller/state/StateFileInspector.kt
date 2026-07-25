package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import java.nio.file.Files
import java.nio.file.Path

/** Headless inspection facade and command-line utility for portable StateFile metadata. */
object StateFileInspector {
  fun inspect(bytes: ByteArray): StateFileInspection = StateCodec.inspect(bytes)

  @JvmStatic
  fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "usage: StateFileInspector <state-file>" }
    val path = Path.of(arguments[0])
    val declared = Files.size(path)
    if (declared !in 0..StateLimits.PORTABLE_MAX_FILE_BYTES.toLong()) {
      PortableBounds.limit(
          "Portable file $declared exceeds ${StateLimits.PORTABLE_MAX_FILE_BYTES}")
    }
    val writer =
        PortableWriter(
            StateLimits.PORTABLE_MAX_FILE_BYTES,
            declared.toInt(),
        )
    Files.newInputStream(path).use { input ->
      val scratch = ByteArray(8192)
      while (true) {
        val count = input.read(scratch)
        if (count < 0) break
        try {
          writer.writeBytes(scratch, 0, count)
        } catch (failure: StateEncodeException) {
          PortableBounds.limit(failure.message ?: "Portable file exceeds its size limit")
        }
      }
    }
    print(inspect(writer.toByteArray()).render())
  }
}
