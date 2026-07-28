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
}
