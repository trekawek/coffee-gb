package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateBrowserEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata
import eu.rekawek.coffeegb.controller.state.StateEntryKey
import eu.rekawek.coffeegb.controller.state.StateFileInspection
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRootKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StateBrowserModelTest {

  @Test
  fun stableEmptySlotAndCorruptFallbackExposeStatusAndDisabledReason() {
    val empty =
        StateBrowserEntry(
            StateEntryKey(StateRef.Slot(0)),
            null,
            null,
        )
    val corruptRef = StateRef.Slot(4)
    val corrupt =
        StateBrowserEntry(
            StateEntryKey(corruptRef, sourceIndex = 2),
            StateCatalogEntry(
                corruptRef,
                StateCatalogStatus.CORRUPT,
                "Checksum does not match",
                null,
                null,
                null,
                null,
                null,
                null,
            ),
            null,
        )
    val model = StateBrowserTableModel()
    model.entries = listOf(empty, corrupt)

    assertEquals(2, model.rowCount)
    assertEquals("Slot 0", model.getValueAt(0, 0))
    assertEquals("Empty", model.getValueAt(0, 6))
    assertTrue(empty.isEmpty)
    assertFalse(empty.canLoad)
    assertEquals("No state is saved in this slot.", empty.disabledReason)

    assertEquals("Slot 4", model.getValueAt(1, 0))
    assertEquals("Corrupt", model.getValueAt(1, 6))
    assertEquals("Previous 2", model.getValueAt(1, 7))
    assertFalse(corrupt.canLoad)
    assertEquals("Checksum does not match", corrupt.disabledReason)
    assertTrue(model.details(corrupt).contains("Checksum does not match"))
  }

  @Test
  fun formatColumnAndDetailsExposeCoreVersionWhenRecorded() {
    val ref = StateRef.Slot(1)
    val inspection =
        StateFileInspection(
            formatVersion = 2,
            rootKind = StateRootKind.MACHINE,
            compression = StateCompression.DEFLATE,
            encodedPayloadLength = 12,
            decodedPayloadLength = 34,
            checksumValid = true,
            identities = emptyList(),
            sections = emptyList(),
            diagnostics = StateDiagnosticMetadata("1.7.15", "desktop"),
        )
    val entry =
        StateBrowserEntry(
            StateEntryKey(ref),
            StateCatalogEntry(
                ref,
                StateCatalogStatus.AVAILABLE,
                null,
                inspection,
                "0".repeat(64),
                null,
                null,
                null,
                null,
            ),
            null,
        )
    val model = StateBrowserTableModel()
    model.entries = listOf(entry)

    assertEquals("v2 / 1.7.15", model.getValueAt(0, 5))
    assertTrue(model.details(entry).contains("Core: 1.7.15; build: desktop"))
  }
}
