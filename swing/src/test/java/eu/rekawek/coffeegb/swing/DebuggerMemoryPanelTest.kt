package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.util.concurrent.FutureTask
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerMemoryPanelTest {

  @Test
  fun `bounded controls expose only safe memory ranges and publish coherent interests`() {
    val interests = mutableListOf<DebuggerMemoryInterest>()
    val panel =
        onEdt {
          DebuggerMemoryPanel(
              DebuggerMemoryPanelCallbacks(onInterestChanged = interests::add)
          )
        }

    onEdt {
      val initial = assertIs<DebuggerMemoryInterest.Absolute>(panel.currentInterest).request
      assertEquals(DebugAddressSpace.WORK_RAM, initial.addressSpace())
      assertEquals(0xc000, initial.address())
      assertEquals(0x80, initial.length())
      assertEquals(initial, panel.currentRequest)
      assertNull(panel.currentAnchoredRequest)
      assertTrue(interests.isEmpty(), "Construction should not synthesize a user change")

      val spaces = (0 until panel.addressSpaceCombo.itemCount).map(panel.addressSpaceCombo::getItemAt)
      assertEquals(
          listOf(
              DebugAddressSpace.SYSTEM_BUS,
              DebugAddressSpace.ROM,
              DebugAddressSpace.WORK_RAM,
              DebugAddressSpace.HIGH_RAM,
          ),
          spaces,
      )

      panel.lengthSpinner.intValue = 0x10
      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.SYSTEM_BUS
      panel.startSpinner.intValue = 0xfdf0
      assertEquals(0xff80, panel.startSpinner.model.nextValue)
      assertFalse(panel.startSpinner.isValueAllowed(0xfe00))
      val absolute = assertIs<DebuggerMemoryInterest.Absolute>(panel.currentInterest).request
      assertEquals(DebugAddressSpace.SYSTEM_BUS, absolute.addressSpace())
      assertEquals(0xfdf0, absolute.address())
      assertEquals(0x10, absolute.length())
      assertEquals(panel.currentInterest, interests.last())

      panel.followCombo.selectedItem = DebuggerMemoryFollow.PROGRAM_COUNTER
      val anchored = assertIs<DebuggerMemoryInterest.Anchored>(panel.currentInterest).request
      assertEquals(DebugInspectionAnchor.PROGRAM_COUNTER, anchored.anchor())
      assertEquals(0, anchored.offset())
      assertEquals(0x10, anchored.length())
      assertNull(panel.currentRequest)
      assertEquals(anchored, panel.currentAnchoredRequest)
      assertFalse(panel.addressSpaceCombo.isEnabled)
      assertFalse(panel.startSpinner.isEnabled)
      assertTrue(panel.lengthSpinner.isEnabled)

      panel.setFollowCapabilities(programCounter = false, stackPointer = true)
      assertEquals(DebuggerMemoryFollow.NONE, panel.followCombo.selectedItem)
      assertIs<DebuggerMemoryInterest.Absolute>(panel.currentInterest)
      assertTrue(panel.addressSpaceCombo.isEnabled)
      assertEquals(
          listOf(DebuggerMemoryFollow.NONE, DebuggerMemoryFollow.STACK_POINTER),
          (0 until panel.followCombo.itemCount).map(panel.followCombo::getItemAt),
      )

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.HIGH_RAM
      panel.lengthSpinner.intValue = 0x7f
      assertEquals(listOf(0xff80..0xff80), panel.startSpinner.allowedRanges)
      panel.setMaximumSampleLength(0x40)
      assertEquals(0x40, panel.currentRequest?.length())
      assertTrue(panel.startSpinner.allowedRanges.all { range -> range.last <= 0xffbf })
      assertFailsWith<IllegalArgumentException> { panel.setMaximumSampleLength(0x1001) }

      val actionButtons = descendants(panel).filterIsInstance<JButton>()
      assertTrue(actionButtons.none { button -> button.text in setOf("Read", "Refresh", "Pause") })
      assertEquals("Live memory inspector", panel.accessibleContext.accessibleName)
      assertContains(panel.memoryTable.accessibleContext.accessibleDescription, "delta marker")
    }
  }

  @Test
  fun `live blocks retain selection mark changed bytes and reject stale results`() {
    val panel = onEdt { DebuggerMemoryPanel() }

    onEdt {
      panel.lengthSpinner.intValue = 0x20
      val interest = panel.currentInterest
      val firstBytes = ByteArray(0x20) { index -> (0x20 + index).toByte() }
      val firstIdentity = DebuggerSnapshotIdentity(4, 10, 100)
      assertTrue(
          panel.render(
              firstIdentity,
              interest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, firstBytes),
          )
      )
      assertEquals(2, panel.memoryTable.rowCount)
      assertEquals("\$C000", panel.memoryTable.getValueAt(0, 0))
      val unchanged = assertIs<DebuggerMemoryByteCell>(panel.memoryTable.getValueAt(0, 2))
      assertFalse(unchanged.changed)
      assertContains(unchanged.accessibleText, "contains 21")

      panel.memoryTable.changeSelection(0, 2, false, false)
      val secondBytes = firstBytes.clone().also { bytes -> bytes[1] = 0x7f }
      val secondIdentity = DebuggerSnapshotIdentity(4, 11, 120)
      assertTrue(
          panel.render(
              secondIdentity,
              interest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, secondBytes),
          )
      )
      val changed = assertIs<DebuggerMemoryByteCell>(panel.memoryTable.getValueAt(0, 2))
      assertTrue(changed.changed)
      assertEquals(0x21, changed.previousValue)
      assertEquals(0x7f, changed.value)
      assertTrue(changed.toString().startsWith("Δ"))
      assertContains(changed.accessibleText, "changed from 21 to 7F")
      assertEquals(0, panel.memoryTable.selectedRow)
      assertEquals(2, panel.memoryTable.selectedColumn)
      assertContains(panel.statusLabel.text, secondIdentity.label)

      assertFalse(
          panel.render(
              firstIdentity,
              interest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, firstBytes),
          )
      )
      assertEquals(0x7f, (panel.memoryTable.getValueAt(0, 2) as DebuggerMemoryByteCell).value)

      panel.lengthSpinner.intValue = 0x10
      val newInterest = panel.currentInterest
      assertEquals(0, panel.memoryTable.rowCount)
      assertFalse(
          panel.render(
              DebuggerSnapshotIdentity(4, 12, 140),
              interest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, secondBytes),
          )
      )
      assertEquals(0, panel.memoryTable.rowCount)
      assertFailsWith<IllegalArgumentException> {
        panel.render(
            DebuggerSnapshotIdentity(4, 12, 140),
            newInterest,
            DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc001, ByteArray(0x10)),
        )
      }
    }
  }

  @Test
  fun `switching memory spaces restores each space start and selected position`() {
    val panel = onEdt { DebuggerMemoryPanel() }

    onEdt {
      panel.lengthSpinner.intValue = 0x10
      panel.startSpinner.intValue = 0xc120
      val workInterest = panel.currentInterest
      assertTrue(
          panel.render(
              DebuggerSnapshotIdentity(5, 1, 10),
              workInterest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc120, ByteArray(0x10)),
          )
      )
      panel.memoryTable.changeSelection(0, 3, false, false)

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.ROM
      assertEquals(0, panel.currentRequest?.address())
      assertEquals(0, panel.startSpinner.intValue)
      panel.startSpinner.intValue = 0x1234

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.HIGH_RAM
      assertEquals(0xff80, panel.currentRequest?.address())
      assertEquals(0xff80, panel.startSpinner.intValue)

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.WORK_RAM
      assertEquals(0xc120, panel.currentRequest?.address())
      assertEquals(0xc120, panel.startSpinner.intValue)
      assertTrue(
          panel.render(
              DebuggerSnapshotIdentity(5, 2, 20),
              panel.currentInterest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc120, ByteArray(0x10)),
          )
      )
      assertEquals(0, panel.memoryTable.selectedRow)
      assertEquals(3, panel.memoryTable.selectedColumn)

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.ROM
      assertEquals(0x1234, panel.currentRequest?.address())
      assertEquals(0x1234, panel.startSpinner.intValue)
    }
  }

  @Test
  fun `RAM bytes use compact hexadecimal headers and commit only double-click edits`() {
    val writes = mutableListOf<eu.rekawek.coffeegb.core.debug.DebugMemoryWrite>()
    val panel =
        onEdt {
          DebuggerMemoryPanel(DebuggerMemoryPanelCallbacks(onWriteByte = writes::add))
        }

    onEdt {
      panel.lengthSpinner.intValue = 0x10
      val renderedInterest = panel.currentInterest
      assertTrue(
          panel.render(
              DebuggerSnapshotIdentity(2, 1, 1),
              renderedInterest,
              DebugMemoryBlock(
                  DebugAddressSpace.WORK_RAM,
                  0xc000,
                  ByteArray(0x10) { 0x20.toByte() },
              ),
          )
      )
      assertEquals("0", panel.memoryTable.columnModel.getColumn(1).headerValue)
      assertEquals("F", panel.memoryTable.columnModel.getColumn(16).headerValue)
      assertEquals(64, panel.memoryTable.columnModel.getColumn(0).preferredWidth)
      assertEquals(38, panel.memoryTable.columnModel.getColumn(1).preferredWidth)
      assertEquals(120, panel.memoryTable.columnModel.getColumn(17).preferredWidth)
      assertFalse(panel.memoryTable.isCellEditable(0, 1))

      panel.setMemoryWritesEnabled(true)
      assertTrue(panel.memoryTable.isCellEditable(0, 1))
      val singleClick =
          MouseEvent(
              panel.memoryTable,
              MouseEvent.MOUSE_CLICKED,
              0,
              0,
              0,
              0,
              1,
              false,
          )
      assertFalse(panel.memoryTable.editCellAt(0, 1, singleClick))
      val doubleClick =
          MouseEvent(
              panel.memoryTable,
              MouseEvent.MOUSE_CLICKED,
              0,
              0,
              0,
              0,
              2,
              false,
          )
      assertTrue(panel.memoryTable.editCellAt(0, 1, doubleClick))
      val editor = assertIs<JTextField>(panel.memoryTable.editorComponent)
      assertEquals("20", editor.text)
      editor.text = "\$7f"
      assertTrue(panel.memoryTable.cellEditor.stopCellEditing())
      assertEquals(
          eu.rekawek.coffeegb.core.debug.DebugMemoryWrite(
              DebugAddressSpace.WORK_RAM,
              0xc000,
              0x7f,
          ),
          writes.single(),
      )

      panel.addressSpaceCombo.selectedItem = DebugAddressSpace.ROM
      val romInterest = panel.currentInterest
      assertTrue(
          panel.render(
              DebuggerSnapshotIdentity(2, 2, 2),
              romInterest,
              DebugMemoryBlock(DebugAddressSpace.ROM, 0xc000, ByteArray(0x10)),
          )
      )
      assertFalse(panel.memoryTable.isCellEditable(0, 1))
    }
  }

  @Test
  fun `not-sampled and clear states never leave old bytes presented as current`() {
    val panel = onEdt { DebuggerMemoryPanel() }

    onEdt {
      val interest = panel.currentInterest
      val sampled = DebuggerSnapshotIdentity(8, 20, 500)
      assertTrue(
          panel.render(
              sampled,
              interest,
              DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, ByteArray(0x80)),
          )
      )
      assertTrue(panel.memoryTable.rowCount > 0)

      val missing = DebuggerSnapshotIdentity(8, 21, 520)
      assertTrue(panel.showNotSampled(missing, interest, "range unavailable at this safe point"))
      assertEquals(0, panel.memoryTable.rowCount)
      assertContains(panel.statusLabel.text, missing.label)
      assertContains(panel.statusLabel.text, "range unavailable")
      assertContains(panel.accessibleContext.accessibleDescription, "not sampled")
      assertFailsWith<IllegalArgumentException> { panel.showNotSampled(missing, interest, " ") }

      panel.clear()
      assertEquals(0, panel.memoryTable.rowCount)
      assertEquals("No memory sample loaded", panel.statusLabel.text)
    }
  }

  @Test
  fun `state-changing panel operations require the event dispatch thread`() {
    val panel = onEdt { DebuggerMemoryPanel() }
    val identity = DebuggerSnapshotIdentity(1, 1, 1)

    assertFailsWith<IllegalStateException> { panel.clear() }
    assertFailsWith<IllegalStateException> { panel.setMaximumSampleLength(32) }
    assertFailsWith<IllegalStateException> {
      panel.setFollowCapabilities(programCounter = true, stackPointer = true)
    }
    assertFailsWith<IllegalStateException> {
      panel.showNotSampled(identity, panel.currentInterest, "not available")
    }
  }

  private fun descendants(component: Component): Sequence<Component> =
      sequence {
        yield(component)
        if (component is Container) {
          component.components.forEach { child -> yieldAll(descendants(child)) }
        }
      }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
