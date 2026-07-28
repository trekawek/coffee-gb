package eu.rekawek.coffeegb.controller.state

import java.nio.file.Path

/**
 * Collision-safe filesystem layout below one already-resolved game directory.
 *
 * Every component below [gameDirectory] is fixed, a bounded decimal slot, or a canonical UUID.
 * Display labels and ROM names never become path components.
 */
class StateStorageLayout(gameDirectory: Path) {
  val gameDirectory: Path = gameDirectory.toAbsolutePath().normalize()

  val statesDirectory: Path = contained(this.gameDirectory.resolve(STATES_DIRECTORY))

  val slotsDirectory: Path = contained(statesDirectory.resolve(SLOTS_DIRECTORY))

  val namedDirectory: Path = contained(statesDirectory.resolve(NAMED_DIRECTORY))

  val autosaveDirectory: Path = contained(statesDirectory.resolve(AUTOSAVE_DIRECTORY))

  val screenshotsDirectory: Path = contained(this.gameDirectory.resolve(SCREENSHOTS_DIRECTORY))

  init {
    require(this.gameDirectory.fileName != null && this.gameDirectory.parent != null) {
      "Game storage directory must have a name and parent"
    }
  }

  fun directory(ref: StateRef): Path =
      when (ref) {
        is StateRef.Slot -> slotsDirectory.resolve(ref.index.toString())
        is StateRef.Named -> namedDirectory.resolve(ref.id.toString())
        StateRef.Autosave -> autosaveDirectory
      }.let(::contained)

  fun stateFile(ref: StateRef): Path = contained(directory(ref).resolve(STATE_FILE))

  fun metadataFile(ref: StateRef): Path = contained(directory(ref).resolve(METADATA_FILE))

  fun thumbnailFile(ref: StateRef, stateSha256: String): Path {
    require(isSha256(stateSha256)) { "Thumbnail state hash must be 64 lowercase hex digits" }
    return contained(directory(ref).resolve("thumbnail-$stateSha256.png"))
  }

  fun parseNamedDirectoryName(value: String): StateRef.Named? =
      try {
        val ref = StateRef.parseStorageKey("named:$value") as StateRef.Named
        ref.takeIf { it.id.toString() == value }
      } catch (_: IllegalArgumentException) {
        null
      }

  private fun contained(path: Path): Path =
      path.toAbsolutePath().normalize().also {
        require(it.startsWith(gameDirectory)) { "State storage path escapes the game directory" }
      }

  companion object {
    const val STATES_DIRECTORY = "states"
    const val SLOTS_DIRECTORY = "slots"
    const val NAMED_DIRECTORY = "named"
    const val AUTOSAVE_DIRECTORY = "autosave"
    const val SCREENSHOTS_DIRECTORY = "screenshots"
    const val STATE_FILE = "state.cgbstate"
    const val METADATA_FILE = "metadata.properties"

    internal fun isSha256(value: String): Boolean =
        value.length == RomIdentity.SHA256_BYTES * 2 &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
  }
}
