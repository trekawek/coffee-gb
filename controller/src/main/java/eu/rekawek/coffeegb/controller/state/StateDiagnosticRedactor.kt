package eu.rekawek.coffeegb.controller.state

import java.nio.file.Path

/** Bounded diagnostic projection that keeps host paths out of user-visible and copyable text. */
internal object StateDiagnosticRedactor {
  fun redact(
      value: String,
      sensitivePaths: Collection<Path> = emptyList(),
      maximumChars: Int = DEFAULT_MAXIMUM_CHARS,
  ): String {
    require(maximumChars > 0)
    var redacted = value
    val explicit =
        (sensitivePaths.map { it.toAbsolutePath().normalize().toString() } +
                listOfNotNull(System.getProperty("user.home")))
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
    explicit.forEach { path ->
      redacted = redacted.replace(path, PATH_PLACEHOLDER, ignoreCase = isWindowsPath(path))
    }
    // Replacing an explicit root must also hide the remaining child path. A Windows child starts
    // with a backslash, which is neither a drive-qualified nor a UNC path after root replacement.
    redacted = PATH_PLACEHOLDER_DESCENDANT.replace(redacted, PATH_PLACEHOLDER)
    redacted = WINDOWS_ABSOLUTE_PATH.replace(redacted, PATH_PLACEHOLDER)
    redacted = UNIX_ABSOLUTE_PATH.replace(redacted, PATH_PLACEHOLDER)
    return redacted
        .replace(CONTROL_CHARACTERS, " ")
        .replace(REPEATED_WHITESPACE, " ")
        .trim()
        .take(maximumChars)
  }

  private fun isWindowsPath(path: String): Boolean =
      path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/')

  private const val PATH_PLACEHOLDER = "<path>"
  private const val DEFAULT_MAXIMUM_CHARS = 900
  private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f]+")
  private val REPEATED_WHITESPACE = Regex(" {2,}")
  private val PATH_PLACEHOLDER_DESCENDANT =
      Regex("""<path>(?:[\\/][^\r\n\t,;]*)+""")
  private val WINDOWS_ABSOLUTE_PATH =
      Regex("""(?i)(?<![A-Za-z0-9])(?:[A-Z]:[\\/]|\\\\)[^\r\n\t,;]*""")
  private val UNIX_ABSOLUTE_PATH =
      Regex("""(?<![A-Za-z0-9.])/[^\r\n\t,;:]*""")
}
