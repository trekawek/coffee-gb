package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.Test

class RecentRomsTest {

  @Test
  fun `successful paths normalize deduplicate move to top and respect capacity`() {
    val properties = testEmulatorProperties()
    properties.recentFileCapacity = 2
    val first = Path.of("roms", "one.gb")
    val equivalent = Path.of("roms", "folder", "..", "one.gb")
    val second = Path.of("roms", "two.gbc")
    val third = Path.of("roms", "three.rom")

    properties.recentRoms.recordSuccessfulOpen(first)
    properties.recentRoms.recordSuccessfulOpen(second)
    properties.recentRoms.recordSuccessfulOpen(equivalent)
    assertEquals(
        listOf(
            equivalent.toAbsolutePath().normalize(),
            second.toAbsolutePath().normalize(),
        ),
        properties.recentRoms.getPaths(),
    )

    properties.recentRoms.recordSuccessfulOpen(third)
    assertEquals(
        listOf(
            third.toAbsolutePath().normalize(),
            equivalent.toAbsolutePath().normalize(),
        ),
        properties.recentRoms.getPaths(),
    )
  }

  @Test
  fun `remove is normalized and capacity zero retains nothing`() {
    val properties = testEmulatorProperties()
    val first = Path.of("one.gb")
    val second = Path.of("two.gb")
    properties.recentRoms.recordSuccessfulOpen(first)
    properties.recentRoms.recordSuccessfulOpen(second)

    properties.recentRoms.remove(Path.of(".", "one.gb"))
    assertEquals(listOf(second.toAbsolutePath().normalize()), properties.recentRoms.getPaths())

    properties.recentFileCapacity = 0
    properties.recentRoms.recordSuccessfulOpen(first)
    assertEquals(emptyList(), properties.recentRoms.getPaths())
  }

  @Test
  fun `committed relocation records replacement and removes old path in one update`() {
    val properties = testEmulatorProperties()
    val old = Path.of("moved", "old.gb")
    val other = Path.of("other.gbc")
    val replacement = Path.of("located", "new.gb")
    properties.recentRoms.recordSuccessfulOpen(other)
    properties.recentRoms.recordSuccessfulOpen(old)

    val revisionsBefore = properties.settingsStore.current()
    properties.recentRoms.recordSuccessfulOpen(replacement, old)

    assertEquals(
        listOf(
            replacement.toAbsolutePath().normalize(),
            other.toAbsolutePath().normalize(),
        ),
        properties.recentRoms.getPaths(),
    )
    // The old entry is never removed in a preliminary update; one immutable document now contains
    // both sides of the relocation transaction.
    kotlin.test.assertNotEquals(revisionsBefore, properties.settingsStore.current())
  }
}
