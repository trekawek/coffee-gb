package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugInterruptType
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import java.util.EnumSet
import java.util.concurrent.FutureTask
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SortOrder
import javax.swing.SwingUtilities
import javax.swing.RowSorter
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerBreakpointPanelTest {

  @Test
  fun `typed cards emit every supported condition without mutating authoritative rows`() {
    val saves = mutableListOf<DebuggerBreakpointSaveRequest>()
    val panel = onEdt {
      DebuggerBreakpointPanel(
          DebuggerBreakpointPanelCallbacks(onSave = saves::add),
      ).also { it.replace(emptyList(), capabilities(), currentProgramCounter = 0x100) }
    }
    val cases =
        listOf(
            EditorCase(DebuggerBreakpointEditorKind.PROGRAM_COUNTER, DebugPcCondition.at(0x150)) {
              panel.editor.addressField.text = "\$0150"
            },
            EditorCase(
                DebuggerBreakpointEditorKind.MEMORY_READ,
                DebugMemoryCondition(DebugMemoryAccess.READ, 0xc000, 0xc00f, 0xa0, 0xf0),
            ) {
              panel.editor.memoryAddressField.text = "C000-C00F"
              panel.editor.valueField.text = "A0"
              panel.editor.maskField.text = "F0"
            },
            EditorCase(
                DebuggerBreakpointEditorKind.MEMORY_WRITE,
                DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xd000, 0xd000),
            ) {
              panel.editor.memoryAddressField.text = "D000"
              panel.editor.valueField.text = ""
              panel.editor.maskField.text = ""
            },
            EditorCase(
                DebuggerBreakpointEditorKind.MEMORY_EXECUTE,
                DebugMemoryCondition(DebugMemoryAccess.EXECUTE, 0x200, 0x2ff),
            ) {
              panel.editor.memoryAddressField.text = "0200-02FF"
              panel.editor.valueField.text = ""
              panel.editor.maskField.text = ""
            },
            EditorCase(DebuggerBreakpointEditorKind.BASE_OPCODE, DebugOpcodeCondition.base(0x3e)) {
              panel.editor.opcodeField.text = "3E"
            },
            EditorCase(DebuggerBreakpointEditorKind.CB_OPCODE, DebugOpcodeCondition.cb(0x7c)) {
              panel.editor.opcodeField.text = "7C"
            },
            EditorCase(
                DebuggerBreakpointEditorKind.INTERRUPT,
                DebugInterruptCondition(DebugInterruptType.TIMER),
            ) {
              panel.editor.interruptCombo.selectedItem = DebugInterruptType.TIMER
            },
            EditorCase(
                DebuggerBreakpointEditorKind.PPU_STATE,
                DebugPpuCondition(12, 144, DebugPpuMode.VBLANK),
            ) {
              panel.editor.ppuFrameField.text = "12"
              panel.editor.ppuLyField.text = "144"
              panel.editor.ppuModeCombo.selectedItem = PpuModeChoice.VBLANK
            },
            EditorCase(
                DebuggerBreakpointEditorKind.SERIAL_START,
                DebugSerialCondition(DebugSerialCondition.Event.TRANSFER_STARTED, 0x81),
            ) {
              panel.editor.serialValueField.text = "81"
              panel.editor.serialMaskField.text = ""
            },
            EditorCase(
                DebuggerBreakpointEditorKind.SERIAL_COMPLETION,
                DebugSerialCondition(DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa0, 0xf0),
            ) {
              panel.editor.serialValueField.text = "A0"
              panel.editor.serialMaskField.text = "F0"
            },
            EditorCase(
                DebuggerBreakpointEditorKind.MASTER_TICK,
                DebugCounterCondition.atMasterTick(1234),
            ) {
              panel.editor.counterField.text = "1234"
            },
            EditorCase(
                DebuggerBreakpointEditorKind.FRAME_COUNTER,
                DebugCounterCondition.atFrame(16),
            ) {
              panel.editor.counterField.text = "0x10"
            },
        )

    onEdt {
      cases.forEach { case ->
        panel.editor.kindCombo.selectedItem = case.kind
        case.configure()
        assertEquals(case.expected, panel.editor.draft().parse().value)
        panel.saveButton.doClick()
        assertEquals(case.expected, saves.last().condition)
        assertNull(saves.last().replacedId)
      }

      assertEquals(0, panel.table.rowCount)
      assertEquals(12, saves.size)
      assertEquals("Breakpoint condition type", panel.editor.kindCombo.accessibleContext.accessibleName)
      assertSame(panel.editor.addressField, findLabel(panel, "Address/range:").labelFor)
      assertNotNull(
          panel
              .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
              .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F9, 0))
      )
    }
  }

  @Test
  fun `search sort edit duplicate remove and toggle preserve authoritative backend state`() {
    val saves = mutableListOf<DebuggerBreakpointSaveRequest>()
    val removals = mutableListOf<DebugBreakpoint>()
    val toggles = mutableListOf<Pair<DebugBreakpoint, Boolean>>()
    val panel = onEdt {
      DebuggerBreakpointPanel(
          DebuggerBreakpointPanelCallbacks(
              onSave = saves::add,
              onRemove = removals::add,
              onToggle = { breakpoint, enabled -> toggles += breakpoint to enabled },
          ))
    }
    val values =
        listOf(
            breakpoint(9, DebugPcCondition.at(0x150)),
            breakpoint(2, DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xc000, 0xc00f)),
            breakpoint(5, DebugInterruptCondition(DebugInterruptType.SERIAL), enabled = false),
        )

    onEdt {
      panel.replace(values, capabilities(), currentProgramCounter = 0x150)
      assertEquals(3, panel.table.rowCount)
      assertEquals(listOf(values[0]), panel.breakpointsAtProgramCounter(0x150))
      assertTrue(panel.breakpointsAtProgramCounter(0x151).isEmpty())

      panel.filterField.text = "write"
      assertEquals(1, panel.table.rowCount)
      assertEquals("1 of 3 breakpoints", panel.resultCountLabel.text)
      assertContains(panel.copyText(), "Write \$C000-\$C00F")
      assertFalse(panel.copyText().contains("Serial"))
      panel.clearFilterButton.doClick()

      panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(2, SortOrder.DESCENDING))
      assertEquals(9L, panel.table.getValueAt(0, 2))
      panel.table.setRowSelectionInterval(0, 0)
      val enterAction =
          panel.table.actionMap.get(
              panel.table
                  .getInputMap(JComponent.WHEN_FOCUSED)
                  .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)))
      enterAction.actionPerformed(null)
      assertEquals("\$0150", panel.editor.addressField.text)
      panel.editor.addressField.text = "\$0160"
      panel.saveButton.doClick()
      assertEquals(DebugBreakpointId(9), saves.single().replacedId)
      assertEquals(DebugPcCondition.at(0x160), saves.single().condition)
      assertEquals(DebugPcCondition.at(0x150), panel.tableModel.breakpointAt(0).condition())

      panel.duplicateButton.doClick()
      assertEquals("\$0150", panel.editor.addressField.text)
      panel.editor.addressField.text = "\$0170"
      panel.saveButton.doClick()
      assertNull(saves.last().replacedId)
      assertEquals(DebugPcCondition.at(0x170), saves.last().condition)

      panel.removeButton.doClick()
      assertEquals(values[0], removals.single())
      assertEquals(3, panel.table.rowCount)

      panel.tableModel.setValueAt(false, 0, 1)
      assertEquals(values[0] to false, toggles.single())
      assertEquals(true, panel.tableModel.getValueAt(0, 1))

      panel.commandFailed("Save failed")
      assertEquals("\$0170", panel.editor.addressField.text)
      panel.commandSucceeded("Saved", clearEditor = true)
      assertEquals("\$0100", panel.editor.addressField.text)
      assertEquals("Saved", panel.editorStatusLabel.text)
    }
  }

  @Test
  fun `last hit and F9 context update independently from authoritative row replacement`() {
    val toggledPcs = mutableListOf<Int>()
    val panel = onEdt {
      DebuggerBreakpointPanel(
          DebuggerBreakpointPanelCallbacks(onToggleAtCurrentPc = toggledPcs::add),
      )
    }
    val exactPc = breakpoint(4, DebugPcCondition.at(0x1234))
    val rangePc = breakpoint(6, DebugPcCondition(0x1200, 0x12ff))
    val hit = DebugBreakpointHit(exactPc, 99, snapshot(masterTick = 103), true)

    onEdt {
      panel.replace(listOf(exactPc, rangePc), capabilities(), hit, 0x1234)
      assertContains(panel.hitLabel.text, "breakpoint #4")
      assertContains(panel.hitLabel.text, "matched at tick 99")
      assertEquals("Last hit", panel.tableModel.getValueAt(0, 0))
      assertEquals(listOf(exactPc), panel.breakpointsAtProgramCounter(0x1234))
      assertFalse(rangePc in panel.breakpointsAtProgramCounter(0x1234))

      val actionName =
          panel
              .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
              .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F9, 0))
      panel.actionMap.get(actionName).actionPerformed(null)
      assertEquals(listOf(0x1234), toggledPcs)
      assertEquals(2, panel.table.rowCount)

      panel.updateExecutionContext(null, 0x4567)
      assertContains(panel.hitLabel.text, "No breakpoint hit")
      assertEquals("", panel.tableModel.getValueAt(0, 0))
      panel.toggleCurrentPcButton.doClick()
      assertEquals(listOf(0x1234, 0x4567), toggledPcs)
      assertEquals(2, panel.table.rowCount)
    }
  }

  @Test
  fun `synchronous callback status wins over pending editor status`() {
    lateinit var panel: DebuggerBreakpointPanel
    panel =
        onEdt {
          DebuggerBreakpointPanel(
              DebuggerBreakpointPanelCallbacks(
                  onSave = { panel.commandFailed("Breakpoint limit reached") },
                  onToggleAtCurrentPc = { panel.commandFailed("Toggle rejected") },
              ))
        }

    onEdt {
      panel.replace(emptyList(), capabilities(), null, 0x1234)
      panel.editor.addressField.text = "\$1234"
      panel.saveButton.doClick()
      assertEquals("Breakpoint limit reached", panel.editorStatusLabel.text)

      panel.toggleCurrentPcButton.doClick()
      assertEquals("Toggle rejected", panel.editorStatusLabel.text)
    }
  }

  @Test
  fun `exact PC lookup returns duplicate definitions in stable identifier order`() {
    val panel = onEdt { DebuggerBreakpointPanel() }
    val first = breakpoint(9, DebugPcCondition.at(0x1234))
    val second = breakpoint(2, DebugPcCondition.at(0x1234))
    val range = breakpoint(1, DebugPcCondition(0x1200, 0x12ff))

    onEdt {
      panel.replace(listOf(first, range, second), capabilities(), null, 0x1234)
      assertEquals(listOf(second, first), panel.breakpointsAtProgramCounter(0x1234))
    }
  }

  @Test
  fun `capability and busy gates suppress unsupported or premature commands`() {
    val saves = mutableListOf<DebuggerBreakpointSaveRequest>()
    val toggles = mutableListOf<Pair<DebugBreakpoint, Boolean>>()
    val panel = onEdt {
      DebuggerBreakpointPanel(
          DebuggerBreakpointPanelCallbacks(
              onSave = saves::add,
              onToggle = { breakpoint, enabled -> toggles += breakpoint to enabled },
          ))
    }
    val pc = breakpoint(1, DebugPcCondition.at(0x100))
    val ppu = breakpoint(2, DebugPpuCondition.atLy(42))

    onEdt {
      panel.replace(listOf(pc, ppu), capabilities(DebugBreakpointKind.PROGRAM_COUNTER), null, 0x100)
      assertEquals("Enter a breakpoint condition", panel.editorStatusLabel.text)
      assertTrue(panel.tableModel.isCellEditable(0, 1))
      assertFalse(panel.tableModel.isCellEditable(1, 1))
      assertEquals("Supported", panel.tableModel.getValueAt(0, 5))
      assertEquals("Unsupported in this session", panel.tableModel.getValueAt(1, 5))

      panel.editor.kindCombo.selectedItem = DebuggerBreakpointEditorKind.PPU_STATE
      assertFalse(panel.saveButton.isEnabled)
      assertFalse(panel.editor.ppuLyField.isEnabled)
      assertContains(panel.editorStatusLabel.text, "unsupported")
      panel.saveButton.doClick()
      assertTrue(saves.isEmpty())

      panel.setBusy(true)
      assertFalse(panel.table.isEnabled)
      assertFalse(panel.toggleCurrentPcButton.isEnabled)
      panel.tableModel.setValueAt(false, 0, 1)
      assertTrue(toggles.isEmpty())

      panel.setBusy(false)
      panel.editor.kindCombo.selectedItem = DebuggerBreakpointEditorKind.PROGRAM_COUNTER
      assertEquals("Enter a breakpoint condition", panel.editorStatusLabel.text)
      assertTrue(panel.saveButton.isEnabled)
      panel.clear()
      assertEquals(0, panel.table.rowCount)
      assertFalse(panel.saveButton.isEnabled)
      assertFalse(panel.toggleCurrentPcButton.isEnabled)
      assertContains(panel.hitLabel.text, "No breakpoint hit")
    }

    assertFailsWith<IllegalStateException> { panel.setBusy(false) }
  }

  private data class EditorCase(
      val kind: DebuggerBreakpointEditorKind,
      val expected: DebugBreakpointCondition,
      val configure: () -> Unit,
  )

  private fun breakpoint(
      id: Long,
      condition: DebugBreakpointCondition,
      enabled: Boolean = true,
  ): DebugBreakpoint = DebugBreakpoint(DebugBreakpointId(id), enabled, condition)

  private fun capabilities(vararg kinds: DebugBreakpointKind): DebugCapabilities {
    val supported =
        if (kinds.isEmpty()) EnumSet.allOf(DebugBreakpointKind::class.java)
        else EnumSet.copyOf(kinds.toList())
    return DebugCapabilities(
        true,
        true,
        true,
        false,
        true,
        true,
        true,
        4096,
        supported,
        128,
        emptySet(),
        0,
        0,
        DebugHistoryCapabilities.disabled(),
    )
  }

  private fun snapshot(masterTick: Long): DebugSnapshot =
      DebugSnapshot(
          1,
          7,
          masterTick,
          4,
          0,
          true,
          DebugRegisters(0x12, 0xb0, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xc000, 0x1234),
          DebugInterruptState(true, false, 0x01, 0x01, 0x01),
          DebugTimerState(0x1234, 0x10, 0x20, 0x04, false, 0),
          DebugPpuState(true, DebugPpuMode.OAM_SEARCH, 0, 0, 0x91, 0x82, 0, 0, 0, 0, 0),
          DebugApuState(true, 0, false, false, false, false, 0, 0, 0x80),
          DebugMapperState(
              "test",
              -1,
              -1,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
          ),
          DebugExecutionState(DebugCpuState.OPCODE_FETCH, 0, -1, 0, false, false, 7),
      )

  private fun findLabel(panel: DebuggerBreakpointPanel, text: String): javax.swing.JLabel =
      descendants(panel).filterIsInstance<javax.swing.JLabel>().first { it.text == text }

  private fun descendants(root: java.awt.Component): List<java.awt.Component> =
      buildList {
        fun visit(component: java.awt.Component) {
          add(component)
          (component as? java.awt.Container)?.components?.forEach(::visit)
        }
        visit(root)
      }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
