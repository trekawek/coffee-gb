package eu.rekawek.coffeegb.swing

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * Writes Coffee GB's generated public-domain CI ROM outside the packaged payload.
 *
 * Native-package jobs invoke this class from Maven's authoritative app JAR, then ask the host
 * desktop shell to open the resulting file. Keeping fixture generation in the application makes
 * its exact bytes reviewable and prevents a downloaded or accidentally packaged test ROM.
 */
object PackageAssociationFixture {

  @JvmStatic
  fun main(args: Array<String>) {
    if (args.size != 1) {
      System.err.println("Usage: PackageAssociationFixture OUTPUT.gb")
      exitProcess(2)
    }
    try {
      write(Path.of(args.single()))
    } catch (failure: Exception) {
      System.err.println(
          "coffee-gb association fixture: " +
              (failure.message ?: failure.javaClass.simpleName),
      )
      exitProcess(2)
    }
  }

  internal fun write(output: Path) {
    val target = output.toAbsolutePath().normalize()
    val extension =
        target.fileName
            ?.toString()
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
    require(extension in setOf("gb", "gbc", "rom")) {
      "Association fixture must use the .gb, .gbc, or .rom extension"
    }
    val parent = requireNotNull(target.parent) { "Association fixture requires a parent directory" }
    val root = requireNotNull(parent.root) { "Association fixture requires an absolute root" }
    var cursor = root
    for (part in root.relativize(parent)) {
      cursor = cursor.resolve(part)
      require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
        "Association fixture path has a missing, non-directory, or symbolic-link parent"
      }
    }
    Files.newByteChannel(
            target,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
        .use { channel ->
          val bytes = java.nio.ByteBuffer.wrap(syntheticPackageRom())
          while (bytes.hasRemaining()) {
            channel.write(bytes)
          }
        }
  }
}
