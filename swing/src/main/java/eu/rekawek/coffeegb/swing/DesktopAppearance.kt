package eu.rekawek.coffeegb.swing

/** User-facing desktop appearance choices. Persistence is deliberately owned elsewhere. */
internal enum class DesktopAppearance(
    val displayName: String,
) {
  LIGHT("Light"),
  DARK("Dark"),
  SYSTEM("System look and feel"),
}
