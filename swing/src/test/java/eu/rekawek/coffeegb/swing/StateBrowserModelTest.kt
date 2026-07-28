package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateBrowserEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata
import eu.rekawek.coffeegb.controller.state.StateEntryKey
import eu.rekawek.coffeegb.controller.state.StateFileInspection
import eu.rekawek.coffeegb.controller.state.StateMetadata
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRootKind
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.JLabel
import javax.swing.plaf.basic.BasicHTML
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

  @Test
  fun hostileTimestampAndDiagnosticsAreBoundedBeforeTableProjection() {
    val ref = StateRef.Slot(3)
    val hostile = "build\u0000\n" + "x".repeat(10_000)
    val inspection =
        StateFileInspection(
            formatVersion = 2,
            rootKind = StateRootKind.MACHINE,
            compression = StateCompression.NONE,
            encodedPayloadLength = 1,
            decodedPayloadLength = 1,
            checksumValid = true,
            identities = emptyList(),
            sections = emptyList(),
            diagnostics = StateDiagnosticMetadata(hostile, hostile),
        )
    val metadata =
        StateMetadata(
            ref,
            null,
            Instant.MAX,
            null,
            1,
            "0".repeat(64),
            null,
        )
    val entry =
        StateBrowserEntry(
            StateEntryKey(ref),
            StateCatalogEntry(
                ref,
                StateCatalogStatus.AVAILABLE,
                hostile,
                inspection,
                "0".repeat(64),
                metadata,
                null,
                null,
                null,
            ),
            null,
        )
    val model = StateBrowserTableModel().also { it.entries = listOf(entry) }

    assertTrue(model.getValueAt(0, 1).toString().length <= 80)
    assertTrue(model.getValueAt(0, 5).toString().length <= 170)
    val details = model.details(entry)
    assertFalse(details.contains('\u0000'))
    assertTrue(details.length < 1_500)
  }

  @Test
  fun hostileHtmlStateTextIsRenderedLiterallyInTableTooltipAndStatus() {
    val hostile = "<html><img src='file:/private/secret.png'>state"
    val ref = StateRef.Slot(2)
    val metadata =
        StateMetadata(
            ref,
            hostile,
            Instant.parse("2026-07-28T02:03:04Z"),
            null,
            1,
            "0".repeat(64),
            null,
        )
    val entry =
        StateBrowserEntry(
            StateEntryKey(ref),
            StateCatalogEntry(
                ref,
                StateCatalogStatus.CORRUPT,
                hostile,
                null,
                "0".repeat(64),
                metadata,
                null,
                null,
                null,
            ),
            null,
        )
    val model = StateBrowserTableModel().also { it.entries = listOf(entry) }
    val table = StateBrowserTable(model).also { it.setSize(800, 100) }

    val rendered = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0) as JLabel
    assertEquals(hostile, rendered.text)
    assertTrue(rendered.getClientProperty("html.disable") == true)
    assertNull(rendered.getClientProperty(BasicHTML.propertyKey))

    val mouse =
        MouseEvent(
            table,
            MouseEvent.MOUSE_MOVED,
            0,
            0,
            1,
            1,
            0,
            false,
        )
    assertEquals(hostile, table.getToolTipText(mouse))
    val tooltip = table.createToolTip().also { it.tipText = hostile }
    assertTrue(tooltip.getClientProperty("html.disable") == true)
    assertNull(tooltip.getClientProperty(BasicHTML.propertyKey))

    val status = literalSwingLabel(hostile)
    assertEquals(hostile, status.text)
    assertTrue(status.getClientProperty("html.disable") == true)
    assertNull(status.getClientProperty(BasicHTML.propertyKey))
  }
}
