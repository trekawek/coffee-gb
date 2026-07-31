package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerHardwarePanelTest {

  @Test
  fun `panel decodes coherent scalar and peripheral values with explicit provenance`() {
    val snapshot = snapshot()
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    val graphics = graphicsView(identity)
    val audio = audioView(identity)
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      panel.render(snapshot, graphics, audio)

      val interrupt = field(panel, DebuggerHardwareSubsystem.INTERRUPTS, "irq.if")
      assertEquals("\$15", interrupt.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, interrupt.provenance)
      assertContains(interrupt.decodedValue, "VBlank")
      assertContains(interrupt.decodedValue, "Timer")

      val timer = field(panel, DebuggerHardwareSubsystem.TIMER, "timer.tac")
      assertEquals("\$FD", timer.rawValue)
      assertContains(timer.decodedValue, "enabled")
      assertContains(timer.decodedValue, "divider bit 3")
      assertContains(
          field(panel, DebuggerHardwareSubsystem.TIMER, "timer.overflow").decodedValue,
          "2 CPU clocks",
      )

      val lcdc = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.lcdc")
      assertEquals("\$F3", lcdc.rawValue)
      assertContains(lcdc.decodedValue, "LCD on")
      assertContains(lcdc.decodedValue, "\$9C00")
      assertEquals(
          "\$01",
          field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk").rawValue,
      )
      assertEquals(
          "\$85",
          field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.bgpi").rawValue,
      )
      assertEquals(
          "\$E4",
          field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.bgp").rawValue,
      )

      val nr10 = field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff10")
      assertEquals("\$6D", nr10.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, nr10.provenance)
      assertContains(nr10.decodedValue, "Sweep pace 6")
      assertContains(nr10.decodedValue, "internal latch")
      assertEquals(
          "\$FA",
          field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff24").rawValue,
      )
      assertEquals(
          "UNUSED ADDRESS",
          field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff27").rawValue,
      )
      assertEquals(
          "\$01",
          field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff30").rawValue,
      )

      assertContains(
          field(panel, DebuggerHardwareSubsystem.CPU_SPEED, "cpu.speed").rawValue,
          "2x DOUBLE SPEED",
      )
      assertEquals(
          DebuggerHardwareProvenance.NOT_EXPOSED,
          field(panel, DebuggerHardwareSubsystem.MAPPER, "mapper.ramBank").provenance,
      )
      assertEquals(
          DebuggerHardwareProvenance.UNKNOWN,
          field(panel, DebuggerHardwareSubsystem.MAPPER, "mapper.rtcSelected").provenance,
      )
      assertContains(panel.accessibleContext.accessibleDescription, identity.label)
    }
  }

  @Test
  fun `unavailable subsystems say trace capture off or not exposed and never invent zero`() {
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      panel.render(snapshot())

      listOf(
              DebuggerHardwareSubsystem.JOYPAD to "joypad.joyp",
              DebuggerHardwareSubsystem.SERIAL_IR to "serial.sb",
              DebuggerHardwareSubsystem.SERIAL_IR to "serial.sc",
              DebuggerHardwareSubsystem.DMA_HDMA to "dma.ff46",
              DebuggerHardwareSubsystem.DMA_HDMA to "hdma.ff55",
          )
          .forEach { (subsystem, id) ->
            val value = field(panel, subsystem, id)
            assertEquals(DebuggerHardwareProvenance.TRACE, value.provenance)
            assertEquals("TRACE / CAPTURE OFF", value.rawValue)
            assertFalse(value.rawValue == "\$00")
          }

      listOf("system.svbk", "system.boot", "system.opri", "system.ff72")
          .forEach { id ->
            val value = field(panel, DebuggerHardwareSubsystem.BANKING_SYSTEM, id)
            assertEquals(DebuggerHardwareProvenance.NOT_EXPOSED, value.provenance)
            assertEquals("NOT EXPOSED", value.rawValue)
            assertFalse(value.rawValue == "\$00")
          }

      assertEquals(
          "CAPTURE OFF",
          field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk").rawValue,
      )
      assertEquals(
          "CAPTURE OFF",
          field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff10").rawValue,
      )
      assertEquals(
          "\$FA",
          field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff24").rawValue,
      )
    }
  }

  @Test
  fun `stale prepared views are rejected instead of mixed with the current snapshot`() {
    val snapshot = snapshot()
    val staleIdentity = DebuggerSnapshotIdentity(7, 12, 251)
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      panel.render(snapshot, graphicsView(staleIdentity), audioView(staleIdentity))

      val vbk = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk")
      assertEquals(DebuggerHardwareProvenance.UNKNOWN, vbk.provenance)
      assertEquals("STALE GRAPHICS CAPTURE", vbk.rawValue)
      assertFalse(vbk.rawValue == "\$01")

      val nr10 = field(panel, DebuggerHardwareSubsystem.AUDIO_APU, "apu.ff10")
      assertEquals(DebuggerHardwareProvenance.UNKNOWN, nr10.provenance)
      assertEquals("STALE AUDIO CAPTURE", nr10.rawValue)
      assertFalse(nr10.rawValue == "\$6D")
    }
  }

  @Test
  fun `coherent hardware capture replaces trace placeholders with current semantic values`() {
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      panel.render(snapshot(), hardware = hardwareInspection())

      assertEquals(
          "\$EF",
          field(panel, DebuggerHardwareSubsystem.JOYPAD, "joypad.joyp").rawValue,
      )
      assertEquals(
          "\$A5",
          field(panel, DebuggerHardwareSubsystem.SERIAL_IR, "serial.sb").rawValue,
      )
      assertEquals(
          "\$FF",
          field(panel, DebuggerHardwareSubsystem.SERIAL_IR, "ir.rp").rawValue,
      )
      assertEquals(
          "\$9A",
          field(panel, DebuggerHardwareSubsystem.DMA_HDMA, "dma.ff46").rawValue,
      )
      assertEquals(
          "\$12",
          field(panel, DebuggerHardwareSubsystem.DMA_HDMA, "hdma.ff51").rawValue,
      )
      val vbk = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk")
      assertEquals("\$FF", vbk.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, vbk.provenance)
      assertContains(vbk.decodedValue, "bank 1")
      val opri = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.opri")
      assertEquals("\$FF", opri.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, opri.provenance)
      assertContains(opri.decodedValue, "DMG-style")
      val svbk = field(panel, DebuggerHardwareSubsystem.BANKING_SYSTEM, "system.svbk")
      assertEquals("\$FD", svbk.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, svbk.provenance)
      assertContains(svbk.decodedValue, "bank 5")
      val boot = field(panel, DebuggerHardwareSubsystem.BANKING_SYSTEM, "system.boot")
      assertEquals("\$FF", boot.rawValue)
      assertContains(boot.decodedValue, "unmapped")
      assertTrue(
          panel.displayedFields(DebuggerHardwareSubsystem.JOYPAD).all {
            it.provenance == DebuggerHardwareProvenance.CURRENT
          }
      )
    }
  }

  @Test
  fun `throttled frames retain sampled graphics fields while scalar values advance`() {
    val first = snapshot()
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      panel.render(first, graphicsView(DebuggerSnapshotIdentity.from(first)))

      val next = snapshot(sequence = 12, masterTick = 300, line = 43)
      panel.render(next, preserveGraphicsFields = true)

      val vbk = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk")
      assertEquals("\$01", vbk.rawValue)
      assertEquals(DebuggerHardwareProvenance.SAMPLED, vbk.provenance)
      assertContains(vbk.decodedValue, "latest bounded-rate graphics sample")
      assertEquals(
          "SAMPLED",
          field(panel, DebuggerHardwareSubsystem.OVERVIEW, "overview.graphics").rawValue,
      )

      val line = field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.ly")
      assertEquals("\$2B", line.rawValue)
      assertEquals(DebuggerHardwareProvenance.CURRENT, line.provenance)
      assertContains(
          field(panel, DebuggerHardwareSubsystem.OVERVIEW, "overview.snapshot").rawValue,
          "snapshot 12",
      )
      assertContains(panel.accessibleContext.accessibleDescription, "graphics capture sampled")

      panel.render(snapshot(sequence = 13, masterTick = 350, line = 44))
      assertEquals(
          "CAPTURE OFF",
          field(panel, DebuggerHardwareSubsystem.PPU_LCD, "ppu.vbk").rawValue,
      )
    }
  }

  @Test
  fun `tree exposes every subsystem and cards use accessible read only indicators`() {
    val panel = onEdt { DebuggerHardwarePanel {} }

    onEdt {
      assertEquals(DebuggerHardwareSubsystem.entries.size, panel.navigationTree.rowCount)
      val titles =
          (0 until panel.navigationTree.rowCount).map { row ->
            val node =
                panel.navigationTree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
            (node.userObject as DebuggerHardwareSubsystem).title
          }
      assertEquals(DebuggerHardwareSubsystem.entries.map { it.title }, titles)

      DebuggerHardwareSubsystem.entries.forEach { subsystem ->
        panel.selectSubsystem(subsystem)
        assertEquals(subsystem, panel.selectedSubsystem)
        assertTrue(panel.displayedFields(subsystem).isNotEmpty())
      }

      assertEquals("Hardware and I/O debugger pane", panel.accessibleContext.accessibleName)
      assertEquals(
          "Hardware subsystem navigation",
          panel.navigationTree.accessibleContext.accessibleName,
      )
      val components = descendants(panel)
      assertTrue(components.none { it is JCheckBox })
      assertTrue(components.none { it is JTextField })
      assertTrue(components.none { it is JTextArea })
      val rawLabel =
          components.filterIsInstance<JLabel>().firstOrNull {
            it.accessibleContext.accessibleName == "IF raw value"
          }
      assertNotNull(rawLabel)
      assertTrue(rawLabel.isEnabled)
    }
  }

  @Test
  fun `copy font clear and EDT contracts support central integration`() {
    val copied = AtomicReference<String>()
    val panel = onEdt { DebuggerHardwarePanel(copied::set) }
    val snapshot = snapshot()

    onEdt {
      panel.render(snapshot)
      panel.selectSubsystem(DebuggerHardwareSubsystem.INTERRUPTS)
      val report = panel.copyText()
      assertContains(report, "Field\tAddress/source\tRaw value\tDecoded meaning\tProvenance")
      assertContains(report, "IF\t\$FF0F\t\$15")
      assertContains(report, "CURRENT")

      val rawLabel =
          descendants(panel).filterIsInstance<JLabel>().first {
            it.accessibleContext.accessibleName == "IF raw value"
          }
      val originalSize = rawLabel.font.size2D
      panel.applyFontScale(175)
      assertTrue(rawLabel.font.size2D > originalSize)

      val key = KeyStroke.getKeyStroke(KeyEvent.VK_C, peripheralMenuShortcutMask())
      val actionKey =
          assertNotNull(
              panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key)
          )
      assertNotNull(panel.actionMap.get(actionKey)).actionPerformed(null)
      assertEquals(report, copied.get())

      panel.clear()
      panel.displayedFields(DebuggerHardwareSubsystem.INTERRUPTS).forEach { field ->
        assertEquals("NO SNAPSHOT", field.rawValue)
        assertEquals(DebuggerHardwareProvenance.UNKNOWN, field.provenance)
      }
      assertContains(panel.accessibleContext.accessibleDescription, "not retained")
    }

    assertFailsWith<IllegalStateException> { panel.render(snapshot) }
    assertFailsWith<IllegalStateException> { panel.clear() }
    assertFailsWith<IllegalStateException> { panel.copyText() }
    assertFailsWith<IllegalStateException> { panel.applyFontScale(125) }
  }

  private fun field(
      panel: DebuggerHardwarePanel,
      subsystem: DebuggerHardwareSubsystem,
      id: String,
  ): DebuggerHardwareFieldView =
      panel.displayedFields(subsystem).first { it.id == id }

  private fun snapshot(
      sequence: Long = 11,
      masterTick: Long = 250,
      line: Int = 42,
  ): DebugSnapshot =
      DebugSnapshot(
          7,
          sequence,
          masterTick,
          2,
          12,
          false,
          DebugRegisters(0x12, 0xb0, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xc123, 0x0150),
          DebugInterruptState(true, true, 0x15, 0x05, 0x05),
          DebugTimerState(0x1234, 0xfe, 0x80, 0xfd, true, 2),
          DebugPpuState(
              true,
              DebugPpuMode.PIXEL_TRANSFER,
              line,
              123,
              0xf3,
              0xee,
              0x12,
              0x34,
              0x2a,
              0x40,
              0x50,
          ),
          DebugApuState(true, 5, true, false, true, false, 0xfa, 0x35, 0xf5),
          DebugMapperState(
              "Mbc5",
              7,
              -1,
              DebugFeatureState.ENABLED,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.DISABLED,
          ),
          DebugExecutionState(
              DebugCpuState.EXECUTING,
              0xcb,
              0x11,
              2,
              true,
              true,
              1_234,
          ),
      )

  private fun graphicsView(identity: DebuggerSnapshotIdentity): DebuggerGraphicsPaneView =
      DebuggerGraphicsPaneView(
          identity = identity,
          overviewText =
              "Snapshot: ${identity.label}\n" +
                  "CGB native\n" +
                  "CPU-selected VRAM bank 1\n" +
                  "LCDC captured\n" +
                  "Background map captured\n" +
                  "Window map captured\n" +
                  "Objects captured\n" +
                  "Background CGB palette index 5 of 63, auto-increment yes, raw \$85\n" +
                  "Object CGB palette index 2 of 63, auto-increment no, raw \$02",
          accessibilityText = "Matching CGB graphics capture",
          tileRows = emptyList(),
          backgroundMapRows = emptyList(),
          windowMapRows = emptyList(),
          objectRows = emptyList(),
          paletteRows =
              listOf(
                  paletteRow("Background", "BGP \$E4"),
                  paletteRow("Object 0", "OBP0 \$D2"),
                  paletteRow("Object 1", "OBP1 \$1B"),
              ),
      )

  private fun hardwareInspection(): DebugHardwareInspection =
      DebugHardwareInspection(
          DebugHardwareInspection.Joypad(0xef, 0x11, 0x0e, true, 2, 1, true, 4),
          DebugHardwareInspection.Serial(0xa5, 0xfd, 3, 24, true, 2),
          DebugHardwareInspection.Infrared(true, 0xff, true, false, true),
          DebugHardwareInspection.OamDma(0x9a, true, 0x9a00, 23, true, false),
          DebugHardwareInspection.VramDma(
              true,
              0x12,
              0x30,
              0x04,
              0x50,
              0x02,
              true,
              true,
              0x1230,
              0x8450,
              7,
          ),
          DebugHardwareInspection.System(
              DebugGraphicsHardwareMode.CGB_NATIVE,
              0xff,
              0xfe,
              0xff,
              0xfd,
              false,
              0xff,
              0x12,
              0x34,
              0x56,
              0xff,
              0x21,
              0x43,
          ),
      )

  private fun paletteRow(label: String, source: String): DebuggerPaletteTableRow =
      DebuggerPaletteTableRow(
          group = "DMG",
          palette = label,
          sourceText = source,
          colorIndex = 0,
          rawValueText = "DMG shade 0",
          componentText = "R 31, G 31, B 31",
          hexColor = "#FFFFFF",
          rgb888 = 0xffffff,
          colorName = "white",
          accessibilityText = "$label palette",
      )

  private fun audioView(identity: DebuggerSnapshotIdentity): DebuggerAudioPaneView =
      DebuggerAudioPaneView(
          identity = identity,
          overviewText = "Snapshot: ${identity.label}\nAPU on",
          accessibilityText = "Matching audio capture",
          channelRows =
              listOf(
                  channelRow(1, "Square with sweep", "enabled", "15 of 15"),
                  channelRow(2, "Square", "disabled", "0 of 15"),
                  channelRow(3, "Wave", "enabled", "9 of 15"),
                  channelRow(4, "Noise", "disabled", "0 of 15"),
              ),
          registerRows =
              listOf(
                  DebuggerAudioRegisterTableRow(
                      "Channel 1",
                      "NR10",
                      "\$FF10",
                      "\$6D",
                      "Sweep pace 6, direction decrease, shift 5.",
                  ),
                  DebuggerAudioRegisterTableRow(
                      "Global",
                      "NR50",
                      "\$FF24",
                      "\$FA",
                      "Left and right mixer volume.",
                  ),
                  DebuggerAudioRegisterTableRow(
                      "Global",
                      "NR51",
                      "\$FF25",
                      "\$35",
                      "Channel routing.",
                  ),
                  DebuggerAudioRegisterTableRow(
                      "Global",
                      "NR52",
                      "\$FF26",
                      "\$F5",
                      "APU and channel status.",
                  ),
              ),
          waveRows =
              (0 until 32).map { index ->
                DebuggerWaveSampleTableRow(
                    index,
                    "\$${(index and 0x0f).toString(16).uppercase()}",
                    index and 0x0f,
                    "${index and 0x0f} of 15",
                    "Wave sample $index",
                )
              },
      )

  private fun channelRow(
      channel: Int,
      kind: String,
      status: String,
      output: String,
  ): DebuggerAudioChannelTableRow =
      DebuggerAudioChannelTableRow(
          channel,
          kind,
          status,
          "DAC enabled",
          output,
          "length enabled",
          "left and right",
          "captured details",
          "Channel $channel $kind",
      )

  private fun descendants(component: Component): List<Component> =
      buildList {
        add(component)
        if (component is Container) {
          component.components.forEach { child -> addAll(descendants(child)) }
        }
      }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
