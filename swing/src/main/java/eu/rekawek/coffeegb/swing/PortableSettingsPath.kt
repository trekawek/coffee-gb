package eu.rekawek.coffeegb.swing

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal class PortableSettingsLocationException(message: String) : IllegalStateException(message)

/** Locate the launch artifact, never the process working directory. */
internal fun portableSettingsPath(
    codeSource: URI? = SwingGui::class.java.protectionDomain?.codeSource?.location?.toURI(),
    packagedLauncher: String? = System.getProperty("jpackage.app-path"),
    temporaryDirectory: String? = System.getProperty("java.io.tmpdir"),
): Path {
  val launcher = packagedLauncher?.takeIf { it.isNotBlank() }
  val artifact =
      try {
        if (launcher != null) Path.of(launcher).toAbsolutePath().normalize()
        else codeSource?.let { Path.of(it).toAbsolutePath().normalize() }
      } catch (_: Exception) {
        null
      }
          ?: throw PortableSettingsLocationException(
              "Cannot locate the application for --portable; launch the JAR from a file.")

  if (!Files.isRegularFile(artifact)) {
    throw PortableSettingsLocationException(
        "Cannot use --portable without a JAR or packaged launcher file (found $artifact).")
  }
  // The single-file Windows SFX extracts the jpackage launcher into a temporary directory.
  // Writing there would appear to work but lose settings when the extraction is removed.
  if (launcher != null && temporaryDirectory != null) {
    val temporaryRoot = Path.of(temporaryDirectory).toAbsolutePath().normalize()
    if (artifact.startsWith(temporaryRoot)) {
      throw PortableSettingsLocationException(
          "Cannot use --portable with a launcher in the temporary directory; " +
              "use the JAR or an unpacked application image instead.")
    }
  }
  val directory = artifact.parent
      ?: throw PortableSettingsLocationException("Cannot locate the application directory for --portable.")
  return directory.resolve("coffeegb.properties")
}
