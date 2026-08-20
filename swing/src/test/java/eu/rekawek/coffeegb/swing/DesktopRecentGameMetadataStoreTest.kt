package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopRecentGameMetadataStoreTest {

  @Test
  fun `archive title played time entry and duplicate occurrence survive a round trip`() {
    val node = MapRecentMetadataNode()
    val store = DesktopRecentGameMetadataStore(node)
    val path = Path.of("archives", "collection.zip").toAbsolutePath().normalize()
    val entry = "nested/" + "long-name/".repeat(500) + "game.gb"
    val metadata =
        DesktopRecentGameMetadata(
            path,
            "THE ACTUAL TITLE",
            Instant.parse("2026-08-18T11:22:33.456Z"),
            RomOrigin.archiveEntry(path, entry, 2, false),
        )

    assertTrue(store.record(metadata))

    val restored = store.read(path)
    assertEquals(metadata.title, restored?.title)
    assertEquals(metadata.playedAt, restored?.playedAt)
    assertEquals(entry, restored?.origin?.archiveEntry()?.orElseThrow())
    assertEquals(2, restored?.origin?.archiveEntryOccurrence())
    assertTrue(node.values.keys.count { it.contains(".a.") } > 2, "long entries are chunked")
  }

  @Test
  fun `successful replacement commits new metadata and removes the old path record`() {
    val store = DesktopRecentGameMetadataStore(MapRecentMetadataNode())
    val oldPath = Path.of("old.gb").toAbsolutePath().normalize()
    val newPath = Path.of("new.gb").toAbsolutePath().normalize()
    val old =
        DesktopRecentGameMetadata(
            oldPath,
            "OLD",
            Instant.parse("2026-08-17T10:00:00Z"),
            RomOrigin.directFile(oldPath),
        )
    val replacement =
        DesktopRecentGameMetadata(
            newPath,
            "NEW",
            Instant.parse("2026-08-18T10:00:00Z"),
            RomOrigin.directFile(newPath),
        )
    assertTrue(store.record(old))

    assertTrue(store.record(replacement, oldPath))

    assertNull(store.read(oldPath))
    assertEquals(replacement, store.read(newPath))
  }

  @Test
  fun `interrupted inactive-slot write leaves the previous committed record readable`() {
    val node = MapRecentMetadataNode()
    val store = DesktopRecentGameMetadataStore(node)
    val path = Path.of("stable.gb").toAbsolutePath().normalize()
    val first =
        DesktopRecentGameMetadata(
            path,
            "FIRST",
            Instant.parse("2026-08-18T10:00:00Z"),
            RomOrigin.directFile(path),
        )
    assertTrue(store.record(first))
    node.failWhen = { key -> key.endsWith(".b.0") }

    val second = first.copy(title = "SECOND", playedAt = Instant.parse("2026-08-18T11:00:00Z"))
    assertFalse(store.record(second))

    assertEquals(first, store.read(path))
  }

  private class MapRecentMetadataNode : DesktopRecentMetadataNode {
    val values = mutableMapOf<String, String>()
    var failWhen: (String) -> Boolean = { false }

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
      if (failWhen(key)) error("synthetic Preferences failure")
      values[key] = value
    }

    override fun remove(key: String) {
      values.remove(key)
    }

    override fun flush() = Unit
  }
}
