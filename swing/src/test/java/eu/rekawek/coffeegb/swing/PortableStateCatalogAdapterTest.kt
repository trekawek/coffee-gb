package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateBrowserCatalog
import eu.rekawek.coffeegb.controller.state.StateBrowserEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus
import eu.rekawek.coffeegb.controller.state.StateEntryKey
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateMetadata
import eu.rekawek.coffeegb.controller.state.StateRef
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PortableStateCatalogAdapterTest {
  @Test
  fun `startup catalog includes preserved sidecars without overriding managed entries`() {
    val hash = "a".repeat(64)
    val savedAt = Instant.parse("2026-09-01T12:00:00Z")
    val managedRef = StateRef.Slot(1)
    val corruptRef = StateRef.Slot(2)
    val preview = StateImage(1, 1, intArrayOf(0x123456))
    val catalog =
        StateBrowserCatalog(
            listOf(
                StateBrowserEntry(StateEntryKey(StateRef.Slot(0)), null, null),
                StateBrowserEntry(
                    StateEntryKey(managedRef),
                    StateCatalogEntry(
                        managedRef,
                        StateCatalogStatus.AVAILABLE,
                        null,
                        null,
                        hash,
                        StateMetadata(managedRef, null, savedAt, null, 128, hash, null),
                        null,
                        null,
                        null,
                    ),
                    preview,
                ),
                StateBrowserEntry(
                    StateEntryKey(corruptRef),
                    StateCatalogEntry(
                        corruptRef,
                        StateCatalogStatus.CORRUPT,
                        "Invalid state",
                        null,
                        hash,
                        null,
                        null,
                        null,
                        null,
                    ),
                    null,
                ),
                StateBrowserEntry(StateEntryKey(StateRef.Slot(9)), null, null),
            ),
            false,
            null,
            emptyList(),
        )

    val slots = portableMenuStateSlots(catalog, setOf(0, 2, 9))

    assertEquals(listOf(0, 1, 2, 9), slots.map { it.index })
    assertTrue(slots.single { it.index == 0 }.loadable)
    assertTrue(slots.single { it.index == 1 }.loadable)
    assertFalse(
        slots.single { it.index == 2 }.loadable,
        "a corrupt managed state remains authoritative over a legacy sidecar",
    )
    assertTrue(slots.single { it.index == 9 }.loadable)
    assertEquals(savedAt, slots.single { it.index == 1 }.savedAt)
    assertContentEquals(
        intArrayOf(0xff123456.toInt()),
        slots.single { it.index == 1 }.preview.copyPixels(),
    )
    assertNull(slots.single { it.index == 0 }.savedAt)
  }
}
