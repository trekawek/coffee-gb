package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.prefs.Preferences

/** Exact desktop-only identity retained for the most recently selected ROM in a container. */
internal data class DesktopRecentGameMetadata(
    val path: Path,
    val title: String,
    val playedAt: Instant,
    val origin: RomOrigin,
) {
  init {
    require(title.isNotBlank()) { "Recent-game title must not be blank" }
    require(title.length <= MAX_TITLE_CHARS) { "Recent-game title is unexpectedly long" }
    require(origin.kind() != RomOrigin.Kind.MEMORY) {
      "A desktop recent game must have a path-backed origin"
    }
    require(origin.containerPath().orElse(null) == normalizedRecentPath(path)) {
      "Recent-game path must match its exact ROM origin"
    }
  }

  val normalizedPath: Path
    get() = normalizedRecentPath(path)

  companion object {
    private const val MAX_TITLE_CHARS = 256

    fun fromSuccessfulOpen(
        path: Path,
        title: String,
        playedAt: Instant,
        origin: RomOrigin,
    ): DesktopRecentGameMetadata =
        DesktopRecentGameMetadata(
            normalizedRecentPath(path),
            title.trim().ifBlank { origin.displayName().trim().ifBlank { "UNTITLED ROM" } },
            playedAt,
            origin,
        )
  }
}

/** Small seam around Preferences so persistence behavior can be tested without user state. */
internal interface DesktopRecentMetadataNode {
  fun get(key: String): String?

  fun put(key: String, value: String)

  fun remove(key: String)

  fun flush()
}

private class JavaDesktopRecentMetadataNode(
    private val preferences: Preferences =
        Preferences.userNodeForPackage(DesktopRecentGameMetadataStore::class.java)
            .node("recent-game-metadata-v1"),
) : DesktopRecentMetadataNode {
  override fun get(key: String): String? = preferences.get(key, null)

  override fun put(key: String, value: String) {
    preferences.put(key, value)
  }

  override fun remove(key: String) {
    preferences.remove(key)
  }

  override fun flush() {
    preferences.flush()
  }
}

/**
 * Desktop-private sidecar for data that the legacy RecentRoms path list cannot represent.
 *
 * Records use two chunk slots and a final pointer as their commit marker. This keeps an interrupted
 * Preferences write from replacing the last complete origin, while allowing exact archive entry
 * names that are longer than a single Preferences value.
 */
internal class DesktopRecentGameMetadataStore(
    private val node: DesktopRecentMetadataNode = JavaDesktopRecentMetadataNode(),
) {
  @Synchronized
  fun read(path: Path): DesktopRecentGameMetadata? =
      runCatching {
            val normalized = normalizedRecentPath(path)
            val base = keyBase(normalized)
            val slot = node.get(currentKey(base))?.takeIf { it == SLOT_A || it == SLOT_B }
                ?: return@runCatching null
            val count = node.get(countKey(base, slot))?.toIntOrNull()
                ?.takeIf { it in 1..MAX_CHUNKS }
                ?: return@runCatching null
            val encoded = StringBuilder()
            for (index in 0 until count) {
              encoded.append(node.get(chunkKey(base, slot, index)) ?: return@runCatching null)
            }
            decode(encoded.toString()).takeIf { it.normalizedPath == normalized }
          }
          .getOrNull()

  /** Returns false when the platform Preferences backend rejects any part of the update. */
  @Synchronized
  fun record(
      metadata: DesktopRecentGameMetadata,
      recentPathToReplace: Path? = null,
  ): Boolean =
      runCatching {
            val normalized = metadata.normalizedPath
            val base = keyBase(normalized)
            val current = node.get(currentKey(base)).takeIf { it == SLOT_A || it == SLOT_B }
            val target = if (current == SLOT_A) SLOT_B else SLOT_A
            clearSlot(base, target)
            val chunks = encode(metadata).chunked(CHUNK_CHARS)
            require(chunks.size in 1..MAX_CHUNKS) { "Recent-game metadata exceeds its safe bound" }
            chunks.forEachIndexed { index, chunk ->
              node.put(chunkKey(base, target, index), chunk)
            }
            node.put(countKey(base, target), chunks.size.toString())
            // Persist the complete inactive slot before advancing the commit pointer.
            node.flush()
            node.put(currentKey(base), target)
            node.flush()
            current?.let { clearSlot(base, it) }
            recentPathToReplace
                ?.let(::normalizedRecentPath)
                ?.takeUnless { it == normalized }
                ?.let(::removeInternal)
            node.flush()
          }
          .isSuccess

  @Synchronized
  fun remove(path: Path): Boolean =
      runCatching {
            removeInternal(normalizedRecentPath(path))
            node.flush()
          }
          .isSuccess

  private fun removeInternal(path: Path) {
    val base = keyBase(path)
    node.remove(currentKey(base))
    clearSlot(base, SLOT_A)
    clearSlot(base, SLOT_B)
  }

  private fun clearSlot(base: String, slot: String) {
    val count =
        node.get(countKey(base, slot))?.toIntOrNull()?.coerceIn(0, MAX_CHUNKS)
            ?: MAX_CHUNKS
    repeat(count) { index -> node.remove(chunkKey(base, slot, index)) }
    node.remove(countKey(base, slot))
  }

  private fun encode(metadata: DesktopRecentGameMetadata): String {
    val entry = metadata.origin.archiveEntry().orElse("")
    return listOf(
            SCHEMA_VERSION,
            textEncoder.encodeToString(metadata.normalizedPath.toString().toByteArray(UTF_8)),
            textEncoder.encodeToString(metadata.title.toByteArray(UTF_8)),
            metadata.playedAt.toString(),
            metadata.origin.kind().name,
            textEncoder.encodeToString(entry.toByteArray(UTF_8)),
            metadata.origin.archiveEntryOccurrence().toString(),
        )
        .joinToString("\n")
  }

  private fun decode(value: String): DesktopRecentGameMetadata {
    val fields = value.split('\n')
    require(fields.size == FIELD_COUNT && fields[0] == SCHEMA_VERSION)
    val path = normalizedRecentPath(Path.of(decodeText(fields[1])))
    val title = decodeText(fields[2])
    val playedAt = Instant.parse(fields[3])
    val kind = RomOrigin.Kind.valueOf(fields[4])
    val occurrence = fields[6].toInt()
    val origin =
        when (kind) {
          RomOrigin.Kind.DIRECT_FILE -> {
            require(fields[5].isEmpty() && occurrence == 0)
            RomOrigin.directFile(path)
          }
          RomOrigin.Kind.ARCHIVE_ENTRY ->
              RomOrigin.archiveEntry(path, decodeText(fields[5]), occurrence, false)
          RomOrigin.Kind.MEMORY -> error("Pathless ROM origins are not recent desktop files")
        }
    return DesktopRecentGameMetadata(path, title, playedAt, origin)
  }

  private fun decodeText(value: String): String =
      String(textDecoder.decode(value), UTF_8)

  private fun keyBase(path: Path): String {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(path.toUri().normalize().toString().toByteArray(UTF_8))
    return digest.take(KEY_DIGEST_BYTES).joinToString("") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    }
  }

  private fun currentKey(base: String) = "$base.current"

  private fun countKey(base: String, slot: String) = "$base.$slot.count"

  private fun chunkKey(base: String, slot: String, index: Int) = "$base.$slot.$index"

  private companion object {
    const val SCHEMA_VERSION = "1"
    const val FIELD_COUNT = 7
    const val SLOT_A = "a"
    const val SLOT_B = "b"
    const val CHUNK_CHARS = 3_072
    const val MAX_CHUNKS = 64
    const val KEY_DIGEST_BYTES = 20
    val UTF_8 = StandardCharsets.UTF_8
    val textEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    val textDecoder: Base64.Decoder = Base64.getUrlDecoder()
  }
}

internal fun normalizedRecentPath(path: Path): Path = path.toAbsolutePath().normalize()
