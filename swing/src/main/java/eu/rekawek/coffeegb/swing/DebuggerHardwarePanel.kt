package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

/** Provenance shown beside every semantic hardware value. */
internal enum class DebuggerHardwareProvenance(val displayText: String) {
  CURRENT("CURRENT"),
  SAMPLED("SAMPLED"),
  TRACE("TRACE"),
  UNKNOWN("UNKNOWN"),
  NOT_EXPOSED("NOT EXPOSED"),
}

/** Complete navigation inventory for the Hardware & I/O window. */
internal enum class DebuggerHardwareSubsystem(
    val title: String,
    val description: String,
) {
  OVERVIEW(
      "Overview",
      "Snapshot identity, run state, and coherent peripheral-capture availability.",
  ),
  CPU_SPEED(
      "CPU & Speed",
      "Current CPU pipeline, execution position, and speed state.",
  ),
  INTERRUPTS(
      "Interrupts",
      "IME plus semantically decoded IF and IE interrupt lines.",
  ),
  TIMER(
      "Timer",
      "Divider and programmable timer registers, clock selection, and overflow state.",
  ),
  PPU_LCD(
      "PPU / LCD",
      "LCD controller registers, scan position, palettes, and graphics-bank state.",
  ),
  AUDIO_APU(
      "Audio / APU",
      "APU status, mixer routing, channel state, and captured sound registers.",
  ),
  JOYPAD(
      "Joypad",
      "JOYP selection, input lines, and Super Game Boy input transport availability.",
  ),
  SERIAL_IR(
      "Serial / Infrared",
      "Link-port and CGB infrared register availability and trace provenance.",
  ),
  DMA_HDMA(
      "DMA / HDMA",
      "OAM DMA and CGB VRAM DMA registers, transfer mode, and progress availability.",
  ),
  BANKING_SYSTEM(
      "Banking & System",
      "CGB compatibility, speed, memory-bank, boot-ROM, and undocumented registers.",
  ),
  MAPPER(
      "Mapper",
      "Cartridge mapper identity, bank selection, RAM, RTC, and rumble state.",
  );

  override fun toString(): String = title
}

internal data class DebuggerHardwareFieldView(
    val id: String,
    val name: String,
    val address: String,
    val rawValue: String,
    val decodedValue: String,
    val provenance: DebuggerHardwareProvenance,
)

private data class DebuggerHardwareFieldSpec(
    val id: String,
    val name: String,
    val address: String,
)

/**
 * EDT-only semantic Hardware & I/O renderer.
 *
 * The panel never reads the emulated bus. It renders only detached [DebugSnapshot] values and
 * same-identity prepared graphics/audio views. Missing backend seams remain visibly unavailable;
 * a register value is never synthesized from an absent capture.
 */
internal class DebuggerHardwarePanel(
    private val copyToClipboard: (String) -> Unit,
) : JPanel(BorderLayout(6, 6)) {
  internal val navigationTree: JTree
  internal val cardHost = JPanel(CardLayout())

  private val nodes = linkedMapOf<DebuggerHardwareSubsystem, DefaultMutableTreeNode>()
  private val cards = linkedMapOf<DebuggerHardwareSubsystem, DebuggerHardwareCard>()
  private val renderedFields =
      linkedMapOf<DebuggerHardwareSubsystem, List<DebuggerHardwareFieldView>>()
  private val fontScaler: DebuggerPeripheralFontScaler

  internal var selectedSubsystem: DebuggerHardwareSubsystem = DebuggerHardwareSubsystem.OVERVIEW
    private set

  init {
    requirePeripheralEdt("Hardware debugger panel construction")
    border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    getAccessibleContext().accessibleName = "Hardware and I/O debugger pane"
    getAccessibleContext().accessibleDescription = NO_SNAPSHOT_DESCRIPTION

    val root = DefaultMutableTreeNode("Hardware & I/O")
    DebuggerHardwareSubsystem.entries.forEach { subsystem ->
      val node = DefaultMutableTreeNode(subsystem)
      nodes[subsystem] = node
      root.add(node)
      val card =
          DebuggerHardwareCard(
              subsystem,
              hardwareFieldSpecs(subsystem),
          )
      cards[subsystem] = card
      cardHost.add(card, subsystem.name)
    }

    navigationTree =
        JTree(root).apply {
          isRootVisible = false
          showsRootHandles = false
          selectionModel.selectionMode =
              javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
          rowHeight = 0
          getAccessibleContext().accessibleName = "Hardware subsystem navigation"
          getAccessibleContext().accessibleDescription =
              "Select a hardware subsystem to inspect its current semantic values"
          cellRenderer =
              object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(
                    tree: JTree,
                    value: Any?,
                    selected: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): java.awt.Component {
                  val component =
                      super.getTreeCellRendererComponent(
                          tree,
                          value,
                          selected,
                          expanded,
                          leaf,
                          row,
                          hasFocus,
                      ) as JLabel
                  val subsystem = (value as? DefaultMutableTreeNode)?.userObject
                  if (subsystem is DebuggerHardwareSubsystem) {
                    component.text = subsystem.title
                    component.toolTipText = subsystem.description
                  }
                  return component
                }
              }
          addTreeSelectionListener(::showSelectedCard)
        }

    val navigationPane =
        JPanel(BorderLayout(3, 3)).apply {
          border = BorderFactory.createEmptyBorder(0, 0, 0, 2)
          val label = JLabel("Subsystems")
          label.labelFor = navigationTree
          label.accessibleContext.accessibleName = "Hardware subsystem navigation label"
          add(label, BorderLayout.NORTH)
          add(JScrollPane(navigationTree), BorderLayout.CENTER)
          minimumSize = Dimension(150, 120)
          preferredSize = Dimension(205, 520)
        }
    val split =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigationPane, cardHost).apply {
          resizeWeight = 0.2
          isContinuousLayout = true
          border = null
          dividerLocation = 205
          getAccessibleContext().accessibleName = "Hardware subsystem workspace"
        }
    add(split, BorderLayout.CENTER)

    installCopyBinding()
    fontScaler = DebuggerPeripheralFontScaler(this)
    selectSubsystem(DebuggerHardwareSubsystem.OVERVIEW)
    clear()
  }

  /** Replaces the complete displayed state from one coherent debugger snapshot. */
  fun render(
      snapshot: DebugSnapshot,
      graphics: DebuggerGraphicsPaneView? = null,
      audio: DebuggerAudioPaneView? = null,
      hardware: DebugHardwareInspection? = null,
      preserveGraphicsFields: Boolean = false,
  ) {
    requirePeripheralEdt("Hardware debugger rendering")
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    val graphicsState = captureState(identity, graphics?.identity)
    val audioState = captureState(identity, audio?.identity)

    replace(
        DebuggerHardwareSubsystem.OVERVIEW,
        overviewFields(snapshot, graphicsState, audioState),
        preserveGraphicsFields,
    )
    replace(DebuggerHardwareSubsystem.CPU_SPEED, cpuFields(snapshot))
    replace(DebuggerHardwareSubsystem.INTERRUPTS, interruptFields(snapshot))
    replace(DebuggerHardwareSubsystem.TIMER, timerFields(snapshot))
    replace(
        DebuggerHardwareSubsystem.PPU_LCD,
        ppuFields(snapshot, graphics, graphicsState, hardware?.system),
        preserveGraphicsFields,
    )
    replace(DebuggerHardwareSubsystem.AUDIO_APU, apuFields(snapshot, audio, audioState))
    replace(DebuggerHardwareSubsystem.JOYPAD, joypadFields(hardware?.joypad))
    replace(DebuggerHardwareSubsystem.SERIAL_IR, serialIrFields(hardware))
    replace(DebuggerHardwareSubsystem.DMA_HDMA, dmaFields(hardware))
    replace(
        DebuggerHardwareSubsystem.BANKING_SYSTEM,
        bankingFields(snapshot, hardware?.system, graphics, graphicsState),
        preserveGraphicsFields,
    )
    replace(DebuggerHardwareSubsystem.MAPPER, mapperFields(snapshot))

    val graphicsCaptureText =
        if (preserveGraphicsFields &&
            renderedFields.values.any { fields ->
              fields.any {
                it.id in GRAPHICS_DERIVED_FIELD_IDS &&
                    it.provenance == DebuggerHardwareProvenance.SAMPLED
              }
            }) {
          "sampled"
        } else {
          graphicsState.displayText.lowercase()
        }
    getAccessibleContext().accessibleDescription =
        "${identity.label}. Semantic Hardware and I/O values; " +
            "graphics capture $graphicsCaptureText, " +
            "audio capture ${audioState.displayText.lowercase()}, " +
            "hardware capture ${if (hardware == null) "not exposed" else "current"}."
  }

  /** Releases all rendered snapshot data without substituting zeroes. */
  fun clear() {
    requirePeripheralEdt("Hardware debugger clearing")
    renderedFields.clear()
    cards.values.forEach(DebuggerHardwareCard::clear)
    getAccessibleContext().accessibleDescription = NO_SNAPSHOT_DESCRIPTION
  }

  /** Returns the selected subsystem as a complete tab-separated semantic register report. */
  fun copyText(): String {
    requirePeripheralEdt("Hardware debugger copying")
    return cards.getValue(selectedSubsystem).copyText()
  }

  fun applyFontScale(scalePercent: Int) {
    requirePeripheralEdt("Hardware debugger font scaling")
    fontScaler.apply(scalePercent)
    revalidate()
    repaint()
  }

  internal fun resetFontScaleForThemeChange() = fontScaler.resetToBaseline()

  internal fun recaptureFontScaleBaseline() = fontScaler.recapture(this)

  internal fun displayedFields(
      subsystem: DebuggerHardwareSubsystem
  ): List<DebuggerHardwareFieldView> {
    requirePeripheralEdt("Hardware debugger field inspection")
    return cards.getValue(subsystem).displayedFields()
  }

  internal fun selectSubsystem(subsystem: DebuggerHardwareSubsystem) {
    requirePeripheralEdt("Hardware debugger subsystem selection")
    val node = nodes.getValue(subsystem)
    navigationTree.selectionPath = TreePath(node.path)
    navigationTree.scrollPathToVisible(TreePath(node.path))
  }

  private fun showSelectedCard(event: TreeSelectionEvent) {
    val node = event.path?.lastPathComponent as? DefaultMutableTreeNode ?: return
    val subsystem = node.userObject as? DebuggerHardwareSubsystem ?: return
    selectedSubsystem = subsystem
    (cardHost.layout as CardLayout).show(cardHost, subsystem.name)
    cards.getValue(subsystem).requestInitialFocus()
    getAccessibleContext().accessibleDescription =
        getAccessibleContext().accessibleDescription.orEmpty().substringBefore(" Selected subsystem:") +
            " Selected subsystem: ${subsystem.title}."
  }

  private fun installCopyBinding() {
    getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, peripheralMenuShortcutMask()),
            COPY_ACTION,
        )
    actionMap.put(
        COPY_ACTION,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent?) {
            val text = copyText()
            if (text.isNotBlank()) copyToClipboard(text)
          }
        },
    )
  }

  private fun replace(
      subsystem: DebuggerHardwareSubsystem,
      fields: List<DebuggerHardwareFieldView>,
      preserveGraphicsFields: Boolean = false,
  ) {
    val displayed =
        if (preserveGraphicsFields) preserveSampledGraphicsFields(subsystem, fields) else fields
    renderedFields[subsystem] = displayed
    cards.getValue(subsystem).replace(displayed)
  }

  private fun preserveSampledGraphicsFields(
      subsystem: DebuggerHardwareSubsystem,
      currentFields: List<DebuggerHardwareFieldView>,
  ): List<DebuggerHardwareFieldView> {
    val previous = renderedFields[subsystem]?.associateBy(DebuggerHardwareFieldView::id).orEmpty()
    return currentFields.map { current ->
      if (current.id !in GRAPHICS_DERIVED_FIELD_IDS ||
          current.provenance == DebuggerHardwareProvenance.CURRENT) {
        current
      } else {
        previous[current.id]?.let { sampled ->
          if (sampled.provenance == DebuggerHardwareProvenance.CURRENT) {
            sampled.copy(
                rawValue =
                    if (sampled.id == "overview.graphics") "SAMPLED" else sampled.rawValue,
                decodedValue =
                    "${sampled.decodedValue} Retained from the latest bounded-rate graphics sample.",
                provenance = DebuggerHardwareProvenance.SAMPLED,
            )
          } else {
            sampled
          }
        } ?: current
      }
    }
  }

  private fun overviewFields(
      snapshot: DebugSnapshot,
      graphicsState: DebuggerHardwareCaptureState,
      audioState: DebuggerHardwareCaptureState,
  ): List<DebuggerHardwareFieldView> {
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    return listOf(
        current(
            "overview.snapshot",
            identity.label,
            "Coherent owner-thread safe-point identity.",
        ),
        current(
            "overview.runState",
            stateIndicator(snapshot.paused, "PAUSED", "RUNNING"),
            if (snapshot.paused) "Emulation was paused at capture." else "Emulation was running at capture.",
        ),
        current(
            "overview.frame",
            snapshot.frame.toString(),
            "Controller frame ${snapshot.frame}, frame position ${snapshot.framePosition}, master tick ${snapshot.masterTick}.",
        ),
        captureField("overview.graphics", graphicsState, "graphics"),
        captureField("overview.audio", audioState, "audio"),
        DebuggerHardwareFieldView(
            "overview.legend",
            "Provenance legend",
            "UI",
            "CURRENT / SAMPLED / TRACE / UNKNOWN / NOT EXPOSED",
            "CURRENT is coherent snapshot data; SAMPLED is the latest coherent bounded-rate graphics capture; TRACE means transitions can be traced but current capture is off; UNKNOWN is an explicit backend value or stale view; NOT EXPOSED means no safe current field exists.",
            DebuggerHardwareProvenance.CURRENT,
        ),
    )
  }

  private fun cpuFields(snapshot: DebugSnapshot): List<DebuggerHardwareFieldView> {
    val execution = snapshot.execution
    val registers = snapshot.registers
    return listOf(
        current("cpu.state", execution.cpuState.name.replace('_', ' '), "Current CPU pipeline state."),
        current("cpu.pc", word(registers.pc), "Program counter."),
        current("cpu.sp", word(registers.sp), "Stack pointer."),
        current(
            "cpu.opcode",
            byteOrUnavailable(execution.opcode),
            if (execution.opcode >= 0) "Current base opcode latch." else "No base opcode is latched in this CPU state.",
        ),
        current(
            "cpu.extendedOpcode",
            byteOrUnavailable(execution.extendedOpcode),
            if (execution.extendedOpcode >= 0) "Current CB-prefixed opcode latch." else "No CB-prefixed opcode is latched.",
        ),
        current("cpu.machineCycle", execution.machineCycle.toString(), "Current instruction machine-cycle index."),
        current(
            "cpu.speed",
            stateIndicator(execution.doubleSpeed, "2x DOUBLE SPEED", "1x NORMAL SPEED"),
            "Current CPU clock mode; this does not expose the KEY1 prepare latch.",
        ),
        current(
            "cpu.haltBug",
            stateIndicator(execution.haltBug, "ACTIVE", "INACTIVE"),
            "HALT-bug execution latch.",
        ),
        current("cpu.retired", execution.retiredInstructions.toString(), "Instructions retired in this session."),
        notExposed(
            "cpu.key1",
            "NOT EXPOSED",
            "KEY1 bit 7 is reflected by the current speed row, but bit 0 (prepare switch) and the raw FF4D value are absent; no raw value is fabricated.",
        ),
    )
  }

  private fun interruptFields(snapshot: DebugSnapshot): List<DebuggerHardwareFieldView> {
    val interrupts = snapshot.interrupts
    val requests = interrupts.requestFlags and 0x1f
    val enables = interrupts.enableFlags and 0x1f
    val fields =
        mutableListOf(
            current(
                "irq.ime",
                stateIndicator(interrupts.ime, "ENABLED", "DISABLED"),
                "CPU interrupt-master-enable latch.",
            ),
            current(
                "irq.eiPending",
                stateIndicator(interrupts.imeEnablePending, "PENDING", "NOT PENDING"),
                "Delayed interrupt enable requested by EI.",
            ),
            current(
                "irq.if",
                byte(requests),
                "Stored IF low five bits: ${interruptNames(requests)}. CPU pull-up bits are not included by the debug DTO.",
            ),
            current(
                "irq.ie",
                byte(enables),
                "Stored IE low five bits: ${interruptNames(enables)}.",
            ),
            current(
                "irq.pending",
                byte(interrupts.pendingFlags and 0x1f),
                "Requested and enabled before IME and synchronizer timing: ${interruptNames(interrupts.pendingFlags)}.",
            ),
        )
    INTERRUPT_LINES.forEachIndexed { bit, name ->
      val requested = requests and (1 shl bit) != 0
      val enabled = enables and (1 shl bit) != 0
      val state =
          when {
            requested && enabled -> "PENDING"
            requested -> "REQUESTED, MASKED"
            enabled -> "ENABLED, CLEAR"
            else -> "CLEAR, MASKED"
          }
      fields +=
          current(
              "irq.line.$bit",
              "IF=${if (requested) 1 else 0}  IE=${if (enabled) 1 else 0}",
              "$name interrupt line: $state.",
          )
    }
    return fields
  }

  private fun timerFields(snapshot: DebugSnapshot): List<DebuggerHardwareFieldView> {
    val timer = snapshot.timer
    val tacSelect = timer.tac and 0x03
    val timerEnabled = timer.tac and 0x04 != 0
    val (dividerBit, nominalRate) = TIMER_CLOCKS[tacSelect]
    return listOf(
        current(
            "timer.div",
            byte(timer.dividerCounter ushr 8),
            "CPU-visible DIV high byte; complete internal divider is ${word(timer.dividerCounter)}.",
        ),
        current(
            "timer.internalDivider",
            word(timer.dividerCounter),
            "Complete 16-bit divider counter captured without resetting FF04.",
        ),
        current("timer.tima", byte(timer.tima), "Programmable timer counter."),
        current("timer.tma", byte(timer.tma), "Timer modulo reloaded after overflow."),
        current(
            "timer.tac",
            byte(timer.tac),
            "Timer ${if (timerEnabled) "enabled" else "disabled"}; selector $tacSelect uses divider bit $dividerBit, nominal $nominalRate Hz at normal speed.",
        ),
        current(
            "timer.overflow",
            stateIndicator(timer.overflowPending, "RELOAD PENDING", "IDLE"),
            if (timer.overflowPending) {
              "TIMA overflow is pending; ${timer.overflowDelayTicks} CPU clocks remain before reload."
            } else {
              "No TIMA overflow reload is pending."
            },
        ),
    )
  }

  private fun ppuFields(
      snapshot: DebugSnapshot,
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
      system: DebugHardwareInspection.System?,
  ): List<DebuggerHardwareFieldView> {
    val ppu = snapshot.ppu
    val fields =
        mutableListOf(
            current("ppu.lcdc", byte(ppu.lcdc), decodeLcdc(ppu.lcdc)),
            current("ppu.stat", byte(ppu.stat), decodeStat(ppu.stat, ppu.mode.name.replace('_', ' '))),
            current("ppu.scy", byte(ppu.scy), "Background vertical scroll offset ${ppu.scy}."),
            current("ppu.scx", byte(ppu.scx), "Background horizontal scroll offset ${ppu.scx}."),
            current(
                "ppu.ly",
                byte(ppu.line),
                "Current scanline ${ppu.line} of 153, dot ${ppu.dot}; PPU mode ${ppu.mode.name.replace('_', ' ')}.",
            ),
            current(
                "ppu.lyc",
                byte(ppu.lyc),
                "LY comparison line ${ppu.lyc}; current equality ${yesNo(ppu.line == ppu.lyc)}.",
            ),
            current("ppu.wy", byte(ppu.wy), "Window vertical origin ${ppu.wy}."),
            current(
                "ppu.wx",
                byte(ppu.wx),
                "Raw window X register ${ppu.wx}; nominal on-screen origin is WX minus 7.",
            ),
        )
    fields += graphicsRegisterField("ppu.bgp", "BGP", graphics, graphicsState)
    fields += graphicsRegisterField("ppu.obp0", "OBP0", graphics, graphicsState)
    fields += graphicsRegisterField("ppu.obp1", "OBP1", graphics, graphicsState)
    fields +=
        if (system != null) {
          systemByteField(
              "ppu.vbk",
              system.vbk,
              "VRAM banking is not implemented by this hardware mode.",
          ) { value -> "VRAM bank ${value and 1} selected." }
        } else {
          graphicsVramBankField("ppu.vbk", graphics, graphicsState)
        }
    fields += graphicsPaletteIndexField("ppu.bgpi", "Background", graphics, graphicsState)
    fields +=
        notExposed(
            "ppu.bgpd",
            if (graphicsState == DebuggerHardwareCaptureState.CURRENT) "CAPTURED PALETTE RAM" else "CAPTURE OFF",
            "The graphics view carries all palette RAM graphically, but not the CPU-visible indexed FF69 byte; no indexed value is inferred.",
        )
    fields += graphicsPaletteIndexField("ppu.obpi", "Object", graphics, graphicsState)
    fields +=
        notExposed(
            "ppu.obpd",
            if (graphicsState == DebuggerHardwareCaptureState.CURRENT) "CAPTURED PALETTE RAM" else "CAPTURE OFF",
            "The graphics view carries all palette RAM graphically, but not the CPU-visible indexed FF6B byte; no indexed value is inferred.",
        )
    fields +=
        if (system != null) {
          systemByteField(
              "ppu.opri",
              system.opri,
              "Object-priority control is not implemented by this hardware mode.",
          ) { value ->
            if (value and 1 != 0) "DMG-style object priority" else "CGB coordinate priority"
          }
        } else {
          notExposed(
              "ppu.opri",
              "NOT EXPOSED",
              "CGB object-priority mode FF6C is not present in DebugSnapshot or DebuggerGraphicsPaneView.",
          )
        }
    return fields
  }

  private fun apuFields(
      snapshot: DebugSnapshot,
      audio: DebuggerAudioPaneView?,
      audioState: DebuggerHardwareCaptureState,
  ): List<DebuggerHardwareFieldView> {
    val apu = snapshot.apu
    val currentAudio = audio.takeIf { audioState == DebuggerHardwareCaptureState.CURRENT }
    val fields =
        mutableListOf(
            current(
                "apu.enabled",
                stateIndicator(apu.enabled, "APU ON", "APU OFF"),
                "Global APU power state.",
            ),
            current(
                "apu.frameSequencer",
                if (apu.frameSequencerStep >= 0) apu.frameSequencerStep.toString() else "UNAVAILABLE",
                if (apu.frameSequencerStep >= 0) {
                  "Next frame-sequencer step ${apu.frameSequencerStep} of 7."
                } else {
                  "This backend cannot expose the frame-sequencer step."
                },
            ),
        )
    val channelStates =
        listOf(
            apu.channel1Enabled,
            apu.channel2Enabled,
            apu.channel3Enabled,
            apu.channel4Enabled,
        )
    channelStates.forEachIndexed { index, enabled ->
      val channel = index + 1
      val captured = currentAudio?.channelRows?.firstOrNull { it.channel == channel }
      fields +=
          current(
              "apu.channel.$channel",
              stateIndicator(enabled, "ENABLED", "DISABLED"),
              captured?.let {
                "${it.kind}; ${it.dacText}; ${it.outputText}; ${it.lengthText}; ${it.routingText}."
              } ?: "NR52 channel-$channel status bit; detailed channel capture is off.",
          )
    }

    APU_REGISTERS.forEach { register ->
      val id = apuRegisterId(register.address)
      val snapshotValue =
          when (register.address) {
            0xff24 -> apu.nr50
            0xff25 -> apu.nr51
            0xff26 -> apu.nr52
            else -> null
          }
      val captured =
          currentAudio?.registerRows?.firstOrNull {
            it.addressText.equals(word(register.address), ignoreCase = true)
          }
      fields +=
          when {
            register.reserved ->
                notExposed(
                    id,
                    "UNUSED ADDRESS",
                    "This address is reserved; the audio inspection placeholder is deliberately not presented as a hardware zero.",
                )
            captured != null ->
                current(
                    id,
                    captured.rawValueText,
                    "${captured.description} Captured value is the internal latch and may differ from masked CPU readback.",
                )
            snapshotValue != null ->
                current(
                    id,
                    byte(snapshotValue),
                    decodeGlobalApuRegister(register.address, snapshotValue, apu),
                )
            audioState == DebuggerHardwareCaptureState.STALE ->
                unknown(
                    id,
                    "STALE AUDIO CAPTURE",
                    "The prepared audio view belongs to another snapshot and was not applied.",
                )
            audioState == DebuggerHardwareCaptureState.ABSENT ->
                notExposed(
                    id,
                    "CAPTURE OFF",
                    "This channel register requires a coherent audio inspection; no value is substituted.",
                )
            else ->
                notExposed(
                    id,
                    "NOT EXPOSED",
                    "The matching audio view did not carry this register.",
                )
          }
    }
    WAVE_RAM_ADDRESSES.forEachIndexed { byteIndex, address ->
      val id = apuRegisterId(address)
      val high = currentAudio?.waveRows?.firstOrNull { it.index == byteIndex * 2 }
      val low = currentAudio?.waveRows?.firstOrNull { it.index == byteIndex * 2 + 1 }
      fields +=
          when {
            high != null && low != null -> {
              val value = ((high.decimalValue and 0x0f) shl 4) or (low.decimalValue and 0x0f)
              current(
                  id,
                  byte(value),
                  "Wave samples ${high.index} and ${low.index}: ${high.decimalValue}, ${low.decimalValue}; captured from channel-3 Wave RAM.",
              )
            }
            audioState == DebuggerHardwareCaptureState.STALE ->
                unknown(
                    id,
                    "STALE AUDIO CAPTURE",
                    "The prepared Wave RAM view belongs to another snapshot and was not applied.",
                )
            audioState == DebuggerHardwareCaptureState.ABSENT ->
                notExposed(
                    id,
                    "CAPTURE OFF",
                    "Wave RAM requires a coherent audio inspection; no value is substituted.",
                )
            else ->
                notExposed(
                    id,
                    "NOT EXPOSED",
                    "The matching audio view did not carry both nibbles for this Wave RAM byte.",
                )
          }
    }
    return fields
  }

  private fun joypadFields(
      joypad: DebugHardwareInspection.Joypad?
  ): List<DebuggerHardwareFieldView> {
    if (joypad == null) {
      return listOf(
          traceOnly(
              "joypad.joyp",
              "TRACE / CAPTURE OFF",
              "INPUT and generic memory traces can report transitions, but current JOYP selector and input-line state are absent from DebugSnapshot.",
          ),
          traceOnly(
              "joypad.selectors",
              "TRACE / CAPTURE OFF",
              "P14 direction-select and P15 button-select output latches are not captured.",
          ),
          traceOnly(
              "joypad.buttons",
              "TRACE / CAPTURE OFF",
              "Button-mask transitions are traceable; a current pressed-button mask is not available to this panel.",
          ),
          notExposed(
              "joypad.filtered",
              "NOT EXPOSED",
              "Filtered P10-P13 electrical input lines and filter history have no debug DTO field.",
          ),
          notExposed(
              "joypad.multiplayer",
              "NOT EXPOSED",
              "SGB multiplayer selection and packet-receiver state are not exposed through the debugger API.",
          ),
      )
    }
    val selectorText =
        buildList {
              if (joypad.joyp and 0x10 == 0) add("P14 directions selected")
              if (joypad.joyp and 0x20 == 0) add("P15 buttons selected")
            }
            .ifEmpty { listOf("neither input group selected") }
            .joinToString(", ")
    val pressed =
        DebugButton.entries
            .filterIndexed { bit, _ -> joypad.pressedButtonMask and (1 shl bit) != 0 }
            .joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            .ifEmpty { "none" }
    val multiplayer =
        if (joypad.sgbAvailable) {
          current(
              "joypad.multiplayer",
              "${joypad.sgbPlayerCount} players · P${joypad.sgbSelectedPlayer + 1}",
              if (joypad.sgbPacketTransferInProgress) {
                "ICD2 packet receive active at byte ${joypad.sgbPacketByteIndex} of 16."
              } else {
                "ICD2 packet receiver idle; logical player ${joypad.sgbSelectedPlayer + 1} selected."
              },
          )
        } else {
          current(
              "joypad.multiplayer",
              "NOT PRESENT",
              "The current hardware profile has no Super Game Boy ICD2 input transport.",
          )
        }
    return listOf(
        current(
            "joypad.joyp",
            byte(joypad.joyp),
            "$selectorText; active-low P10-P13=${(joypad.joyp and 0x0f).toString(2).padStart(4, '0')}.",
        ),
        current("joypad.selectors", byte(joypad.joyp and 0x30), selectorText),
        current(
            "joypad.buttons",
            byte(joypad.pressedButtonMask),
            "Effective current player-one input: $pressed.",
        ),
        current(
            "joypad.filtered",
            byte(joypad.filteredInputLines),
            "Filtered active-low P10-P13=${joypad.filteredInputLines.toString(2).padStart(4, '0')}.",
        ),
        multiplayer,
    )
  }

  private fun serialIrFields(
      hardware: DebugHardwareInspection?
  ): List<DebuggerHardwareFieldView> {
    if (hardware == null) {
      return listOf(
          traceOnly(
              "serial.sb",
              "TRACE / CAPTURE OFF",
              "Serial start, bit-shift, and completed-byte events can carry SB, but no current SB capture exists.",
          ),
          traceOnly(
              "serial.sc",
              "TRACE / CAPTURE OFF",
              "Generic memory trace can observe SC accesses; transfer-active, clock-source, and speed state are not captured.",
          ),
          notExposed(
              "serial.progress",
              "NOT EXPOSED",
              "Current bit count and serial clock phase are internal and absent from debugger DTOs.",
          ),
          traceOnly(
              "ir.rp",
              "TRACE / CAPTURE OFF",
              "Infrared signal transitions are traceable; RP mode/output and CPU-visible input are not captured.",
          ),
          notExposed(
              "ir.signal",
              "NOT EXPOSED",
              "Current received-light and serial pin-4 levels are not exposed; polling FF56 would have hardware side effects.",
          ),
      )
    }
    val serial = hardware.serial
    val infrared = hardware.infrared
    val transferActive = serial.sc and 0x80 != 0
    val serialClock = if (serial.sc and 0x01 != 0) "internal" else "external"
    val serialSpeed = if (serial.sc and 0x02 != 0) "fast" else "normal"
    val infraredFields =
        if (infrared.available) {
          listOf(
              current(
                  "ir.rp",
                  byte(infrared.rp),
                  "Mode ${(infrared.rp ushr 6) and 0x03}; LED output ${onOff(infrared.localOutput)}; sensor input is active-low.",
              ),
              current(
                  "ir.signal",
                  byte(
                      (if (infrared.localOutput) 0x01 else 0) or
                          (if (infrared.receivedLight) 0x02 else 0) or
                          (if (infrared.serialInputHigh) 0x10 else 0),
                  ),
                  "Received light ${yesNo(infrared.receivedLight)}; link pin 4 ${if (infrared.serialInputHigh) "high" else "low"}.",
              ),
          )
        } else {
          listOf(
              current(
                  "ir.rp",
                  "NOT PRESENT",
                  "The infrared register is unavailable in the current hardware mode.",
              ),
              current(
                  "ir.signal",
                  "NOT PRESENT",
                  "No infrared signals are exposed by the current hardware mode.",
              ),
          )
        }
    return listOf(
        current("serial.sb", byte(serial.sb), "Current shift/data register."),
        current(
            "serial.sc",
            byte(serial.sc),
            "${if (transferActive) "Transfer active" else "Transfer idle"}; $serialClock clock; $serialSpeed rate.",
        ),
        current(
            "serial.progress",
            "${serial.receivedBits} / 8 bits",
            "Clock phase ${serial.clockPhase}; clock signal ${if (serial.clockSignal) "high" else "low"}; HALT-wake delay ${serial.haltWakeDelay} clocks.",
        ),
    ) + infraredFields
  }

  private fun dmaFields(
      hardware: DebugHardwareInspection?
  ): List<DebuggerHardwareFieldView> {
    if (hardware == null) {
      return listOf(
          traceOnly(
              "dma.ff46",
              "TRACE / CAPTURE OFF",
              "OAM DMA start, progress, completion, and cancellation are traceable; current FF46/source state is not captured.",
          ),
          notExposed(
              "dma.oamState",
              "NOT EXPOSED",
              "Transfer-active, bytes copied, CPU-bus conflict, and PPU OAM ownership have no debug DTO fields.",
          ),
          traceOnly("hdma.ff51", "TRACE / CAPTURE OFF", "VRAM DMA source high writes and transfer events may be traced; current source is not captured."),
          traceOnly("hdma.ff52", "TRACE / CAPTURE OFF", "VRAM DMA source low writes and transfer events may be traced; current source is not captured."),
          traceOnly("hdma.ff53", "TRACE / CAPTURE OFF", "VRAM DMA destination high writes and transfer events may be traced; current destination is not captured."),
          traceOnly("hdma.ff54", "TRACE / CAPTURE OFF", "VRAM DMA destination low writes and transfer events may be traced; current destination is not captured."),
          traceOnly("hdma.ff55", "TRACE / CAPTURE OFF", "General/HBlank DMA start, progress, cancellation, and completion are traceable; current remaining blocks are not captured."),
          notExposed(
              "hdma.state",
              "NOT EXPOSED",
              "Current GDMA/HDMA mode, request arbitration, and block progress are not exposed.",
          ),
      )
    }
    val oam = hardware.oamDma
    val vram = hardware.vramDma
    val oamFields =
        listOf(
            current(
                "dma.ff46",
                byte(oam.dma),
                "Source page ${word(oam.sourceAddress)}; destination ${'$'}FE00-${'$'}FE9F.",
            ),
            current(
                "dma.oamState",
                if (oam.active) "ACTIVE" else "IDLE",
                "${oam.bytesTransferred} of 160 bytes transferred; OAM ${if (oam.oamBlocked) "owned by DMA" else "available"}; CPU clock ${if (oam.cpuClockPaused) "paused" else "running"}.",
            ),
        )
    if (!vram.available) {
      return oamFields +
          listOf(
              current("hdma.ff51", "NOT PRESENT", "VRAM DMA is unavailable on this hardware."),
              current("hdma.ff52", "NOT PRESENT", "VRAM DMA is unavailable on this hardware."),
              current("hdma.ff53", "NOT PRESENT", "VRAM DMA is unavailable on this hardware."),
              current("hdma.ff54", "NOT PRESENT", "VRAM DMA is unavailable on this hardware."),
              current("hdma.ff55", "NOT PRESENT", "VRAM DMA is unavailable on this hardware."),
              current("hdma.state", "NOT PRESENT", "No VRAM DMA engine exists on this hardware."),
          )
    }
    val sourceText = "Current source ${word(vram.sourceAddress)}."
    val destinationText = "Current destination ${word(vram.destinationAddress)}."
    val remainingBlocks = (vram.hdma5 and 0x7f) + 1
    return oamFields +
        listOf(
            current("hdma.ff51", byte(vram.hdma1), "Source-high latch. $sourceText"),
            current("hdma.ff52", byte(vram.hdma2), "Source-low latch. $sourceText"),
            current("hdma.ff53", byte(vram.hdma3), "Destination-high latch. $destinationText"),
            current("hdma.ff54", byte(vram.hdma4), "Destination-low latch. $destinationText"),
            current(
                "hdma.ff55",
                byte(vram.hdma5),
                if (vram.active) {
                  "$remainingBlocks blocks remain; ${if (vram.hblankMode) "HBlank" else "general-purpose"} transfer active."
                } else {
                  "Transfer inactive; retained length field represents $remainingBlocks blocks."
                },
            ),
            current(
                "hdma.state",
                if (vram.active) "ACTIVE" else "IDLE",
                "${if (vram.hblankMode) "HBlank" else "General-purpose"} mode; ${vram.currentBlockBytesTransferred} of 16 current-block bytes sampled; $sourceText $destinationText",
            ),
        )
  }

  private fun bankingFields(
      snapshot: DebugSnapshot,
      system: DebugHardwareInspection.System?,
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
  ): List<DebuggerHardwareFieldView> {
    if (system != null) {
      val modeText =
          when (system.hardwareMode) {
            DebugGraphicsHardwareMode.DMG -> "DMG"
            DebugGraphicsHardwareMode.CGB_COMPATIBILITY -> "CGB compatibility"
            DebugGraphicsHardwareMode.CGB_NATIVE -> "CGB native"
          }
      return listOf(
          current(
              "system.hardwareMode",
              modeText,
              "Authoritative hardware and compatibility mode from the coherent I/O capture.",
          ),
          systemByteField(
              "system.key0",
              system.key0,
              "KEY0 is not implemented by this hardware mode.",
          ) { "Readback is fixed; effective mode is $modeText." },
          systemByteField(
              "system.key1",
              system.key1,
              "KEY1 speed switching is not implemented by this hardware mode.",
          ) { value ->
            "${if (value and 0x80 != 0) "Double" else "Normal"} speed; switch ${if (value and 0x01 != 0) "prepared" else "not prepared"}."
          },
          systemByteField(
              "system.vbk",
              system.vbk,
              "VRAM banking is not implemented by this hardware mode.",
          ) { value -> "VRAM bank ${value and 1} selected." },
          systemByteField(
              "system.svbk",
              system.svbk,
              "Banked work RAM is not implemented by this hardware mode.",
          ) { value ->
            val bank = (value and 0x07).let { if (it == 0) 1 else it }
            "Work-RAM bank $bank selected."
          },
          current(
              "system.boot",
              byte(0xff),
              "FF50 readback is fixed; boot ROM is currently ${if (system.bootRomMapped) "mapped" else "unmapped"}.",
          ),
          systemByteField(
              "system.opri",
              system.opri,
              "Object-priority control is not implemented by this hardware mode.",
          ) { value ->
            if (value and 1 != 0) "DMG-style object priority" else "CGB coordinate priority"
          },
          systemByteField("system.ff72", system.ff72, "FF72 is not present.") {
            "Undocumented latch value."
          },
          systemByteField("system.ff73", system.ff73, "FF73 is not present.") {
            "Undocumented latch value."
          },
          systemByteField("system.ff74", system.ff74, "FF74 is not present.") {
            "Undocumented latch value; compatibility-mode reads may be fixed high."
          },
          systemByteField("system.ff75", system.ff75, "FF75 is not present.") {
            "Undocumented bits 4-6 latch; other bits read high."
          },
          systemByteField("system.pcm12", system.pcm12, "PCM12 is not present.") { value ->
            "Channel 1 output ${value and 0x0f}; channel 2 output ${(value ushr 4) and 0x0f}."
          },
          systemByteField("system.pcm34", system.pcm34, "PCM34 is not present.") { value ->
            "Channel 3 output ${value and 0x0f}; channel 4 output ${(value ushr 4) and 0x0f}."
          },
      )
    }
    val hardwareMode = graphicsHardwareModeField(graphics, graphicsState)
    return listOf(
        hardwareMode,
        notExposed(
            "system.key0",
            "NOT EXPOSED",
            "KEY0 compatibility latch is internal; graphics hardware mode may expose its effective result when captured.",
        ),
        notExposed(
            "system.key1",
            "NOT EXPOSED",
            "Current ${if (snapshot.execution.doubleSpeed) "double" else "normal"} speed is known, but KEY1 prepare bit and raw FF4D are not exposed.",
        ),
        graphicsVramBankField("system.vbk", graphics, graphicsState),
        notExposed("system.svbk", "NOT EXPOSED", "Selected CGB work-RAM bank is absent from DebugSnapshot."),
        notExposed(
            "system.boot",
            "NOT EXPOSED",
            "Boot-ROM mapping state cannot be inferred from FF50 readback and has no debug DTO field.",
        ),
        notExposed("system.opri", "NOT EXPOSED", "CGB object-priority mode is not captured."),
        notExposed("system.ff72", "NOT EXPOSED", "Undocumented CGB register FF72 is not captured."),
        notExposed("system.ff73", "NOT EXPOSED", "Undocumented CGB register FF73 is not captured."),
        notExposed("system.ff74", "NOT EXPOSED", "Undocumented CGB register FF74 is not captured."),
        notExposed("system.ff75", "NOT EXPOSED", "Undocumented CGB register FF75 is not captured."),
        notExposed(
            "system.pcm12",
            "NOT EXPOSED",
            "Channel outputs exist in audio capture, but CGB model availability and authoritative FF76 readback are not carried by this panel view.",
        ),
        notExposed(
            "system.pcm34",
            "NOT EXPOSED",
            "Channel outputs exist in audio capture, but CGB model availability and authoritative FF77 readback are not carried by this panel view.",
        ),
    )
  }

  private fun systemByteField(
      id: String,
      value: Int,
      unavailable: String,
      decode: (Int) -> String,
  ): DebuggerHardwareFieldView =
      if (value >= 0) current(id, byte(value), decode(value))
      else current(id, "NOT PRESENT", unavailable)

  private fun mapperFields(snapshot: DebugSnapshot): List<DebuggerHardwareFieldView> {
    val mapper = snapshot.mapper
    return listOf(
        current("mapper.id", mapper.mapperId, "Memory-controller implementation identifier."),
        if (mapper.romBank >= 0) {
          current("mapper.romBank", mapper.romBank.toString(), "Current mapper ROM bank.")
        } else {
          notExposed("mapper.romBank", "NOT EXPOSED", "This mapper does not expose its ROM bank through the safe debug view.")
        },
        if (mapper.ramBank >= 0) {
          current("mapper.ramBank", mapper.ramBank.toString(), "Current mapper RAM bank.")
        } else {
          notExposed("mapper.ramBank", "NOT EXPOSED", "This mapper does not expose its RAM bank through the safe debug view.")
        },
        featureField("mapper.ramEnabled", mapper.ramEnabled, "Cartridge RAM enable"),
        featureField("mapper.rtcSelected", mapper.rtcSelected, "RTC register selection"),
        featureField("mapper.rumbleEnabled", mapper.rumbleEnabled, "Mapper rumble output"),
    )
  }

  private fun featureField(
      id: String,
      feature: DebugFeatureState,
      name: String,
  ): DebuggerHardwareFieldView =
      when (feature) {
        DebugFeatureState.ENABLED -> current(id, stateIndicator(true, "ENABLED", "DISABLED"), "$name is enabled.")
        DebugFeatureState.DISABLED -> current(id, stateIndicator(false, "ENABLED", "DISABLED"), "$name is disabled.")
        DebugFeatureState.UNKNOWN ->
            unknown(id, "UNKNOWN", "$name is explicitly unknown in the safe mapper view.")
      }

  private fun graphicsRegisterField(
      id: String,
      registerName: String,
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
  ): DebuggerHardwareFieldView {
    if (graphicsState == DebuggerHardwareCaptureState.STALE) {
      return unknown(id, "STALE GRAPHICS CAPTURE", "The prepared graphics view belongs to another snapshot and was not applied.")
    }
    if (graphicsState == DebuggerHardwareCaptureState.ABSENT || graphics == null) {
      return notExposed(id, "CAPTURE OFF", "$registerName requires a coherent graphics capture; no value is substituted.")
    }
    val source =
        graphics.paletteRows.firstOrNull {
          it.group == "DMG" && it.sourceText.startsWith("$registerName ")
        }?.sourceText
    val raw = source?.removePrefix("$registerName ")?.takeIf(::isFormattedByte)
    return if (raw != null) {
      current(id, raw, "$registerName captured as the stored DMG palette mapping register.")
    } else {
      notExposed(id, "NOT EXPOSED", "The matching graphics view did not carry a structured $registerName value.")
    }
  }

  private fun graphicsVramBankField(
      id: String,
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
  ): DebuggerHardwareFieldView {
    if (graphicsState == DebuggerHardwareCaptureState.STALE) {
      return unknown(id, "STALE GRAPHICS CAPTURE", "The prepared graphics view belongs to another snapshot and was not applied.")
    }
    if (graphicsState == DebuggerHardwareCaptureState.ABSENT || graphics == null) {
      return notExposed(id, "CAPTURE OFF", "VBK requires a coherent graphics capture; no bank zero is assumed.")
    }
    val prefix = "CPU-selected VRAM bank "
    val bank =
        graphics.overviewText.lineSequence().firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 0..1 }
    return if (bank != null) {
      current(id, byte(bank), "CPU-selected VRAM bank $bank from the matching graphics capture.")
    } else {
      notExposed(id, "NOT EXPOSED", "The matching graphics view did not carry a usable VBK selection.")
    }
  }

  private fun graphicsPaletteIndexField(
      id: String,
      label: String,
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
  ): DebuggerHardwareFieldView {
    if (graphicsState == DebuggerHardwareCaptureState.STALE) {
      return unknown(id, "STALE GRAPHICS CAPTURE", "The prepared graphics view belongs to another snapshot and was not applied.")
    }
    if (graphicsState == DebuggerHardwareCaptureState.ABSENT || graphics == null) {
      return notExposed(id, "CAPTURE OFF", "$label palette index requires a coherent graphics capture.")
    }
    val prefix = "$label CGB palette index "
    val line = graphics.overviewText.lineSequence().firstOrNull { it.startsWith(prefix) }
    if (line == null || line.endsWith("unavailable", ignoreCase = true)) {
      return notExposed(id, "NOT AVAILABLE ON THIS HARDWARE", "$label CGB palette index is unavailable for the captured hardware mode.")
    }
    val raw = line.substringAfterLast("raw ", "").takeIf(::isFormattedByte)
    return if (raw != null) {
      current(id, raw, line)
    } else {
      notExposed(id, "NOT EXPOSED", "The matching graphics view did not carry a structured raw palette-index value.")
    }
  }

  private fun graphicsHardwareModeField(
      graphics: DebuggerGraphicsPaneView?,
      graphicsState: DebuggerHardwareCaptureState,
  ): DebuggerHardwareFieldView {
    if (graphicsState == DebuggerHardwareCaptureState.STALE) {
      return unknown("system.hardwareMode", "STALE GRAPHICS CAPTURE", "Hardware mode belongs to another snapshot.")
    }
    if (graphicsState == DebuggerHardwareCaptureState.ABSENT || graphics == null) {
      return notExposed("system.hardwareMode", "CAPTURE OFF", "Graphics hardware mode is not part of DebugSnapshot.")
    }
    val mode =
        graphics.overviewText.lineSequence().firstOrNull {
          it == "DMG" || it == "CGB native" || it == "CGB in DMG compatibility mode"
        }
    return if (mode != null) {
      current("system.hardwareMode", mode, "Effective graphics hardware and compatibility mode.")
    } else {
      notExposed("system.hardwareMode", "NOT EXPOSED", "The matching graphics view did not carry a recognized hardware mode.")
    }
  }

  private fun decodeLcdc(value: Int): String =
      listOf(
              "LCD ${onOff(value and 0x80 != 0)}",
              "window map ${if (value and 0x40 != 0) word(0x9c00) else word(0x9800)}",
              "window ${onOff(value and 0x20 != 0)}",
              "tile data ${if (value and 0x10 != 0) word(0x8000) + " unsigned" else word(0x8800) + " signed"}",
              "background map ${if (value and 0x08 != 0) word(0x9c00) else word(0x9800)}",
              "objects ${if (value and 0x04 != 0) "8x16" else "8x8"} ${onOff(value and 0x02 != 0)}",
              "BG/window priority-display bit ${setClear(value and 0x01 != 0)}",
          )
          .joinToString("; ") + "."

  private fun decodeStat(value: Int, mode: String): String =
      "LYC IRQ ${onOff(value and 0x40 != 0)}; OAM IRQ ${onOff(value and 0x20 != 0)}; " +
          "VBlank IRQ ${onOff(value and 0x10 != 0)}; HBlank IRQ ${onOff(value and 0x08 != 0)}; " +
          "LY equals LYC ${yesNo(value and 0x04 != 0)}; mode $mode."

  private fun decodeGlobalApuRegister(
      address: Int,
      value: Int,
      apu: eu.rekawek.coffeegb.core.debug.DebugApuState,
  ): String =
      when (address) {
        0xff24 ->
            "VIN left ${onOff(value and 0x80 != 0)}, left volume ${(value ushr 4) and 7} of 7; " +
                "VIN right ${onOff(value and 0x08 != 0)}, right volume ${value and 7} of 7."
        0xff25 ->
            (1..4).joinToString("; ") { channel ->
              val right = value and (1 shl (channel - 1)) != 0
              val left = value and (1 shl (channel + 3)) != 0
              "CH$channel ${routing(left, right)}"
            } + "."
        0xff26 ->
            "APU ${onOff(apu.enabled)}; channel status " +
                listOf(apu.channel1Enabled, apu.channel2Enabled, apu.channel3Enabled, apu.channel4Enabled)
                    .mapIndexed { index, enabled -> "CH${index + 1} ${onOff(enabled)}" }
                    .joinToString(", ") + "."
        else -> "Audio register ${byte(value)}."
      }

  private fun captureField(
      id: String,
      state: DebuggerHardwareCaptureState,
      kind: String,
  ): DebuggerHardwareFieldView =
      when (state) {
        DebuggerHardwareCaptureState.CURRENT ->
            current(id, "CURRENT", "Matching coherent $kind capture is available.")
        DebuggerHardwareCaptureState.STALE ->
            unknown(id, "STALE CAPTURE", "The supplied $kind view belongs to another snapshot and is ignored.")
        DebuggerHardwareCaptureState.ABSENT ->
            notExposed(id, "CAPTURE OFF", "No $kind inspection accompanied this snapshot.")
      }

  private fun captureState(
      identity: DebuggerSnapshotIdentity,
      capturedIdentity: DebuggerSnapshotIdentity?,
  ): DebuggerHardwareCaptureState =
      when {
        capturedIdentity == null -> DebuggerHardwareCaptureState.ABSENT
        capturedIdentity == identity -> DebuggerHardwareCaptureState.CURRENT
        else -> DebuggerHardwareCaptureState.STALE
      }

  private fun current(id: String, raw: String, decoded: String): DebuggerHardwareFieldView =
      field(id, raw, decoded, DebuggerHardwareProvenance.CURRENT)

  private fun traceOnly(id: String, raw: String, decoded: String): DebuggerHardwareFieldView =
      field(id, raw, decoded, DebuggerHardwareProvenance.TRACE)

  private fun unknown(id: String, raw: String, decoded: String): DebuggerHardwareFieldView =
      field(id, raw, decoded, DebuggerHardwareProvenance.UNKNOWN)

  private fun notExposed(id: String, raw: String, decoded: String): DebuggerHardwareFieldView =
      field(id, raw, decoded, DebuggerHardwareProvenance.NOT_EXPOSED)

  private fun field(
      id: String,
      raw: String,
      decoded: String,
      provenance: DebuggerHardwareProvenance,
  ): DebuggerHardwareFieldView {
    val spec = fieldSpecsById.getValue(id)
    return DebuggerHardwareFieldView(id, spec.name, spec.address, raw, decoded, provenance)
  }

  private fun byteOrUnavailable(value: Int): String = if (value >= 0) byte(value) else "NOT LATCHED"

  private fun interruptNames(mask: Int): String {
    val selected = INTERRUPT_LINES.filterIndexed { bit, _ -> mask and (1 shl bit) != 0 }
    return selected.ifEmpty { listOf("none") }.joinToString(", ")
  }

  private fun stateIndicator(value: Boolean, trueText: String, falseText: String): String =
      if (value) "● $trueText" else "○ $falseText"

  private fun byte(value: Int): String = DebuggerPresentation.formatByte(value and 0xff)

  private fun word(value: Int): String = DebuggerPresentation.formatWord(value and 0xffff)

  private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

  private fun onOff(value: Boolean): String = if (value) "on" else "off"

  private fun setClear(value: Boolean): String = if (value) "set" else "clear"

  private fun routing(left: Boolean, right: Boolean): String =
      when {
        left && right -> "left and right"
        left -> "left only"
        right -> "right only"
        else -> "not routed"
      }

  private fun isFormattedByte(value: String): Boolean =
      value.length == 3 && value[0] == '$' && value.drop(1).all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

  private companion object {
    const val COPY_ACTION = "debugger-hardware-copy"
    const val NO_SNAPSHOT_DESCRIPTION =
        "Hardware and I/O snapshot is not retained; no register values are displayed"
    val INTERRUPT_LINES = listOf("VBlank", "LCD status", "Timer", "Serial", "Joypad")
    val TIMER_CLOCKS =
        listOf(
            9 to 4_096,
            3 to 262_144,
            5 to 65_536,
            7 to 16_384,
        )
    val GRAPHICS_DERIVED_FIELD_IDS =
        setOf(
            "overview.graphics",
            "ppu.bgp",
            "ppu.obp0",
            "ppu.obp1",
            "ppu.vbk",
            "ppu.bgpi",
            "ppu.bgpd",
            "ppu.obpi",
            "ppu.obpd",
            "system.hardwareMode",
            "system.vbk",
        )
    val fieldSpecsById: Map<String, DebuggerHardwareFieldSpec> =
        DebuggerHardwareSubsystem.entries
            .flatMap(::hardwareFieldSpecs)
            .associateBy(DebuggerHardwareFieldSpec::id)
  }
}

private enum class DebuggerHardwareCaptureState(val displayText: String) {
  CURRENT("CURRENT"),
  STALE("UNKNOWN"),
  ABSENT("NOT EXPOSED"),
}

private class DebuggerHardwareCard(
    private val subsystem: DebuggerHardwareSubsystem,
    specs: List<DebuggerHardwareFieldSpec>,
) : JPanel(BorderLayout(4, 5)) {
  private val rows = linkedMapOf<String, DebuggerHardwareFieldComponent>()

  init {
    border = BorderFactory.createEmptyBorder(5, 7, 7, 7)
    getAccessibleContext().accessibleName = "${subsystem.title} hardware details"
    getAccessibleContext().accessibleDescription = subsystem.description

    val heading =
        JPanel(BorderLayout(3, 2)).apply {
          val title = JLabel(subsystem.title)
          title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 2f)
          title.accessibleContext.accessibleName = "${subsystem.title} heading"
          val description = JLabel(subsystem.description)
          description.accessibleContext.accessibleName = "${subsystem.title} description"
          add(title, BorderLayout.NORTH)
          add(description, BorderLayout.CENTER)
        }
    add(heading, BorderLayout.NORTH)

    val fieldPanel = JPanel(GridBagLayout())
    fieldPanel.accessibleContext.accessibleName = "${subsystem.title} semantic values"
    fieldPanel.add(
        DebuggerHardwareColumnHeader(),
        GridBagConstraints().apply {
          gridx = 0
          gridy = 0
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
        },
    )
    specs.forEachIndexed { index, spec ->
      val row = DebuggerHardwareFieldComponent(spec)
      rows[spec.id] = row
      fieldPanel.add(
          row,
          GridBagConstraints().apply {
            gridx = 0
            gridy = index + 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
          },
      )
    }
    fieldPanel.add(
        Box.createVerticalGlue(),
        GridBagConstraints().apply {
          gridx = 0
          gridy = specs.size + 1
          weightx = 1.0
          weighty = 1.0
          fill = GridBagConstraints.BOTH
        },
    )
    val scroll = JScrollPane(fieldPanel)
    scroll.border = BorderFactory.createEmptyBorder()
    scroll.accessibleContext.accessibleName = "${subsystem.title} register list"
    add(scroll, BorderLayout.CENTER)
  }

  fun replace(fields: List<DebuggerHardwareFieldView>) {
    val values = fields.associateBy(DebuggerHardwareFieldView::id)
    require(values.size == fields.size) { "Hardware field ids must be unique for ${subsystem.title}" }
    require(values.keys == rows.keys) {
      "Hardware fields for ${subsystem.title} do not match the fixed semantic inventory"
    }
    rows.forEach { (id, row) -> row.render(values.getValue(id)) }
    getAccessibleContext().accessibleDescription =
        "${subsystem.description} ${fields.count { it.provenance == DebuggerHardwareProvenance.CURRENT }} current values; " +
            "${fields.count { it.provenance == DebuggerHardwareProvenance.SAMPLED }} sampled values; " +
            "${fields.count { it.provenance != DebuggerHardwareProvenance.CURRENT && it.provenance != DebuggerHardwareProvenance.SAMPLED }} qualified unavailable values."
  }

  fun clear() {
    rows.values.forEach(DebuggerHardwareFieldComponent::clear)
    getAccessibleContext().accessibleDescription = "${subsystem.description} No snapshot is loaded."
  }

  fun displayedFields(): List<DebuggerHardwareFieldView> = rows.values.map { it.view }

  fun copyText(): String =
      buildString {
            append("Field\tAddress/source\tRaw value\tDecoded meaning\tProvenance")
            rows.values.forEach { row ->
              val field = row.view
              append('\n')
              append(
                  listOf(
                          field.name,
                          field.address,
                          field.rawValue,
                          field.decodedValue,
                          field.provenance.displayText,
                      )
                      .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
              )
            }
          }
          .trimEnd()

  fun requestInitialFocus() {
    // The tree remains the predictable keyboard navigation owner; cards contain no controls.
  }
}

private class DebuggerHardwareFieldComponent(
    private val spec: DebuggerHardwareFieldSpec,
) : JPanel(GridBagLayout()) {
  internal val nameLabel = JLabel(spec.name)
  internal val addressLabel = JLabel(spec.address)
  internal val rawValueLabel = JLabel()
  internal val decodedLabel = JLabel()
  internal val provenanceLabel = JLabel()

  var view: DebuggerHardwareFieldView = emptyHardwareField(spec)
    private set

  init {
    border = BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor())
    nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
    nameLabel.preferredSize = Dimension(FIELD_WIDTH, nameLabel.preferredSize.height)
    nameLabel.minimumSize = nameLabel.preferredSize
    addressLabel.font = Font(Font.MONOSPACED, Font.PLAIN, addressLabel.font.size)
    addressLabel.preferredSize = Dimension(ADDRESS_WIDTH, addressLabel.preferredSize.height)
    addressLabel.minimumSize = addressLabel.preferredSize
    rawValueLabel.font = Font(Font.MONOSPACED, Font.PLAIN, rawValueLabel.font.size)
    rawValueLabel.preferredSize = Dimension(RAW_VALUE_WIDTH, rawValueLabel.preferredSize.height)
    rawValueLabel.minimumSize = rawValueLabel.preferredSize
    provenanceLabel.horizontalAlignment = SwingConstants.CENTER
    provenanceLabel.isOpaque = true
    provenanceLabel.border = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(separatorColor()),
        BorderFactory.createEmptyBorder(2, 5, 2, 5),
    )
    provenanceLabel.preferredSize = Dimension(PROVENANCE_WIDTH, provenanceLabel.preferredSize.height + 4)
    provenanceLabel.minimumSize = provenanceLabel.preferredSize

    addCell(nameLabel, 0, 0.0, GridBagConstraints.NONE)
    addCell(rawValueLabel, 1, 0.0, GridBagConstraints.NONE)
    addCell(addressLabel, 2, 0.0, GridBagConstraints.NONE)
    addCell(decodedLabel, 3, 1.0, GridBagConstraints.HORIZONTAL)
    addCell(provenanceLabel, 4, 0.0, GridBagConstraints.NONE)
    render(view)
  }

  fun render(next: DebuggerHardwareFieldView) {
    require(next.id == spec.id && next.name == spec.name && next.address == spec.address) {
      "Hardware field metadata changed for ${spec.id}"
    }
    view = next
    rawValueLabel.text = next.rawValue
    decodedLabel.text = next.decodedValue
    provenanceLabel.text = next.provenance.displayText
    provenanceLabel.background = provenanceBackground(next.provenance)
    provenanceLabel.foreground = provenanceForeground(next.provenance)
    val description =
        "${next.name}, ${next.address}, raw ${next.rawValue}; ${next.decodedValue} Provenance ${next.provenance.displayText}."
    getAccessibleContext().accessibleName = "${next.name} read-only hardware value"
    getAccessibleContext().accessibleDescription = description
    nameLabel.accessibleContext.accessibleName = "${next.name} label"
    addressLabel.accessibleContext.accessibleName = "${next.name} address or source"
    rawValueLabel.accessibleContext.accessibleName = "${next.name} raw value"
    rawValueLabel.accessibleContext.accessibleDescription = next.rawValue
    decodedLabel.accessibleContext.accessibleName = "${next.name} decoded meaning"
    decodedLabel.accessibleContext.accessibleDescription = next.decodedValue
    provenanceLabel.accessibleContext.accessibleName = "${next.name} provenance"
    provenanceLabel.accessibleContext.accessibleDescription = next.provenance.displayText
  }

  fun clear() {
    render(emptyHardwareField(spec))
  }

  private fun addCell(
      component: JComponent,
      x: Int,
      weight: Double,
      fillMode: Int,
  ) {
    add(
        component,
        GridBagConstraints().apply {
          gridx = x
          gridy = 0
          weightx = weight
          fill = fillMode
          anchor = GridBagConstraints.WEST
          insets = Insets(5, if (x == 0) 3 else 7, 5, if (x == 4) 3 else 7)
        },
    )
  }

  private companion object {
    const val FIELD_WIDTH = 165
    const val RAW_VALUE_WIDTH = 165
    const val ADDRESS_WIDTH = 100
    const val PROVENANCE_WIDTH = 105
  }
}

private class DebuggerHardwareColumnHeader : JPanel(GridBagLayout()) {
  init {
    border = BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor())
    addHeader("Field", 0, FIELD_WIDTH, 0.0)
    addHeader("Raw value", 1, RAW_VALUE_WIDTH, 0.0)
    addHeader("Address / source", 2, ADDRESS_WIDTH, 0.0)
    addHeader("Decoded meaning", 3, null, 1.0)
    addHeader("Provenance", 4, PROVENANCE_WIDTH, 0.0)
    getAccessibleContext().accessibleName = "Hardware value column headings"
  }

  private fun addHeader(
      text: String,
      x: Int,
      width: Int?,
      weight: Double,
  ) {
    val label = JLabel(text)
    label.font = label.font.deriveFont(Font.BOLD)
    width?.let {
      label.preferredSize = Dimension(it, label.preferredSize.height)
      label.minimumSize = label.preferredSize
    }
    add(
        label,
        GridBagConstraints().apply {
          gridx = x
          gridy = 0
          weightx = weight
          fill = if (weight > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
          anchor = GridBagConstraints.WEST
          insets = Insets(3, if (x == 0) 3 else 7, 4, if (x == 4) 3 else 7)
        },
    )
  }

  private companion object {
    const val FIELD_WIDTH = 165
    const val RAW_VALUE_WIDTH = 165
    const val ADDRESS_WIDTH = 100
    const val PROVENANCE_WIDTH = 105
  }
}

private fun emptyHardwareField(spec: DebuggerHardwareFieldSpec): DebuggerHardwareFieldView =
    DebuggerHardwareFieldView(
        spec.id,
        spec.name,
        spec.address,
        "NO SNAPSHOT",
        "No coherent debug snapshot is loaded; no value is substituted.",
        DebuggerHardwareProvenance.UNKNOWN,
    )

private fun separatorColor(): Color =
    UIManager.getColor("Separator.foreground") ?: UIManager.getColor("controlShadow") ?: Color.GRAY

private fun provenanceBackground(provenance: DebuggerHardwareProvenance): Color {
  val base = UIManager.getColor("Panel.background") ?: Color.LIGHT_GRAY
  return when (provenance) {
    DebuggerHardwareProvenance.CURRENT -> UIManager.getColor("Table.selectionBackground") ?: base
    DebuggerHardwareProvenance.SAMPLED,
    DebuggerHardwareProvenance.TRACE -> UIManager.getColor("ToolTip.background") ?: base
    DebuggerHardwareProvenance.UNKNOWN,
    DebuggerHardwareProvenance.NOT_EXPOSED -> base
  }
}

private fun provenanceForeground(provenance: DebuggerHardwareProvenance): Color =
    if (provenance == DebuggerHardwareProvenance.CURRENT) {
      UIManager.getColor("Table.selectionForeground") ?: Color.WHITE
    } else {
      UIManager.getColor("Label.foreground") ?: Color.BLACK
    }

private data class DebuggerApuRegisterSpec(
    val address: Int,
    val name: String,
    val reserved: Boolean = false,
)

private val APU_REGISTERS =
    listOf(
        DebuggerApuRegisterSpec(0xff10, "NR10"),
        DebuggerApuRegisterSpec(0xff11, "NR11"),
        DebuggerApuRegisterSpec(0xff12, "NR12"),
        DebuggerApuRegisterSpec(0xff13, "NR13"),
        DebuggerApuRegisterSpec(0xff14, "NR14"),
        DebuggerApuRegisterSpec(0xff15, "Reserved", true),
        DebuggerApuRegisterSpec(0xff16, "NR21"),
        DebuggerApuRegisterSpec(0xff17, "NR22"),
        DebuggerApuRegisterSpec(0xff18, "NR23"),
        DebuggerApuRegisterSpec(0xff19, "NR24"),
        DebuggerApuRegisterSpec(0xff1a, "NR30"),
        DebuggerApuRegisterSpec(0xff1b, "NR31"),
        DebuggerApuRegisterSpec(0xff1c, "NR32"),
        DebuggerApuRegisterSpec(0xff1d, "NR33"),
        DebuggerApuRegisterSpec(0xff1e, "NR34"),
        DebuggerApuRegisterSpec(0xff1f, "Reserved", true),
        DebuggerApuRegisterSpec(0xff20, "NR41"),
        DebuggerApuRegisterSpec(0xff21, "NR42"),
        DebuggerApuRegisterSpec(0xff22, "NR43"),
        DebuggerApuRegisterSpec(0xff23, "NR44"),
        DebuggerApuRegisterSpec(0xff24, "NR50"),
        DebuggerApuRegisterSpec(0xff25, "NR51"),
        DebuggerApuRegisterSpec(0xff26, "NR52"),
        DebuggerApuRegisterSpec(0xff27, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff28, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff29, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2a, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2b, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2c, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2d, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2e, "Unmapped", true),
        DebuggerApuRegisterSpec(0xff2f, "Unmapped", true),
    )

private val WAVE_RAM_ADDRESSES = (0xff30..0xff3f).toList()

private fun apuRegisterId(address: Int): String = "apu.${address.toString(16)}"

private fun hardwareFieldSpecs(
    subsystem: DebuggerHardwareSubsystem
): List<DebuggerHardwareFieldSpec> =
    when (subsystem) {
      DebuggerHardwareSubsystem.OVERVIEW ->
          listOf(
              spec("overview.snapshot", "Snapshot", "DebugSnapshot"),
              spec("overview.runState", "Execution state", "DebugSnapshot"),
              spec("overview.frame", "Frame position", "Controller"),
              spec("overview.graphics", "Graphics capture", "Inspection"),
              spec("overview.audio", "Audio capture", "Inspection"),
              spec("overview.legend", "Provenance legend", "UI"),
          )
      DebuggerHardwareSubsystem.CPU_SPEED ->
          listOf(
              spec("cpu.state", "CPU state", "Pipeline"),
              spec("cpu.pc", "PC", "CPU"),
              spec("cpu.sp", "SP", "CPU"),
              spec("cpu.opcode", "Opcode", "CPU latch"),
              spec("cpu.extendedOpcode", "Extended opcode", "CPU latch"),
              spec("cpu.machineCycle", "Machine cycle", "CPU"),
              spec("cpu.speed", "Current speed", "CPU clock"),
              spec("cpu.haltBug", "HALT bug", "CPU latch"),
              spec("cpu.retired", "Retired instructions", "Session counter"),
              spec("cpu.key1", "KEY1", wordLiteral(0xff4d)),
          )
      DebuggerHardwareSubsystem.INTERRUPTS ->
          buildList {
            add(spec("irq.ime", "IME", "CPU latch"))
            add(spec("irq.eiPending", "Delayed EI", "CPU latch"))
            add(spec("irq.if", "IF", wordLiteral(0xff0f)))
            add(spec("irq.ie", "IE", wordLiteral(0xffff)))
            add(spec("irq.pending", "Enabled requests", "IF & IE"))
            listOf("VBlank", "LCD status", "Timer", "Serial", "Joypad")
                .forEachIndexed { bit, name ->
                  add(spec("irq.line.$bit", "$name line", "IF.$bit / IE.$bit"))
                }
          }
      DebuggerHardwareSubsystem.TIMER ->
          listOf(
              spec("timer.div", "DIV", wordLiteral(0xff04)),
              spec("timer.internalDivider", "Internal divider", "16-bit latch"),
              spec("timer.tima", "TIMA", wordLiteral(0xff05)),
              spec("timer.tma", "TMA", wordLiteral(0xff06)),
              spec("timer.tac", "TAC", wordLiteral(0xff07)),
              spec("timer.overflow", "Overflow pipeline", "Derived"),
          )
      DebuggerHardwareSubsystem.PPU_LCD ->
          listOf(
              spec("ppu.lcdc", "LCDC", wordLiteral(0xff40)),
              spec("ppu.stat", "STAT", wordLiteral(0xff41)),
              spec("ppu.scy", "SCY", wordLiteral(0xff42)),
              spec("ppu.scx", "SCX", wordLiteral(0xff43)),
              spec("ppu.ly", "LY", wordLiteral(0xff44)),
              spec("ppu.lyc", "LYC", wordLiteral(0xff45)),
              spec("ppu.wy", "WY", wordLiteral(0xff4a)),
              spec("ppu.wx", "WX", wordLiteral(0xff4b)),
              spec("ppu.bgp", "BGP", wordLiteral(0xff47)),
              spec("ppu.obp0", "OBP0", wordLiteral(0xff48)),
              spec("ppu.obp1", "OBP1", wordLiteral(0xff49)),
              spec("ppu.vbk", "VBK", wordLiteral(0xff4f)),
              spec("ppu.bgpi", "BGPI", wordLiteral(0xff68)),
              spec("ppu.bgpd", "BGPD", wordLiteral(0xff69)),
              spec("ppu.obpi", "OBPI", wordLiteral(0xff6a)),
              spec("ppu.obpd", "OBPD", wordLiteral(0xff6b)),
              spec("ppu.opri", "OPRI", wordLiteral(0xff6c)),
          )
      DebuggerHardwareSubsystem.AUDIO_APU ->
          buildList {
            add(spec("apu.enabled", "APU power", "NR52.7"))
            add(spec("apu.frameSequencer", "Frame sequencer", "APU internal"))
            (1..4).forEach { channel ->
              add(spec("apu.channel.$channel", "Channel $channel", "NR52.${channel - 1}"))
            }
            APU_REGISTERS.forEach { register ->
              add(spec(apuRegisterId(register.address), register.name, wordLiteral(register.address)))
            }
            WAVE_RAM_ADDRESSES.forEachIndexed { index, address ->
              add(spec(apuRegisterId(address), "Wave RAM ${index.toString(16).uppercase()}", wordLiteral(address)))
            }
          }
      DebuggerHardwareSubsystem.JOYPAD ->
          listOf(
              spec("joypad.joyp", "JOYP", wordLiteral(0xff00)),
              spec("joypad.selectors", "P14 / P15 selectors", "JOYP.4 / JOYP.5"),
              spec("joypad.buttons", "Pressed buttons", "Input capture"),
              spec("joypad.filtered", "P10-P13 filtered lines", "Joypad internal"),
              spec("joypad.multiplayer", "SGB multiplayer / packet", "SGB transport"),
          )
      DebuggerHardwareSubsystem.SERIAL_IR ->
          listOf(
              spec("serial.sb", "SB", wordLiteral(0xff01)),
              spec("serial.sc", "SC", wordLiteral(0xff02)),
              spec("serial.progress", "Serial transfer progress", "Serial internal"),
              spec("ir.rp", "RP", wordLiteral(0xff56)),
              spec("ir.signal", "Infrared signal", "IR internal"),
          )
      DebuggerHardwareSubsystem.DMA_HDMA ->
          listOf(
              spec("dma.ff46", "DMA", wordLiteral(0xff46)),
              spec("dma.oamState", "OAM DMA state", "DMA internal"),
              spec("hdma.ff51", "HDMA1", wordLiteral(0xff51)),
              spec("hdma.ff52", "HDMA2", wordLiteral(0xff52)),
              spec("hdma.ff53", "HDMA3", wordLiteral(0xff53)),
              spec("hdma.ff54", "HDMA4", wordLiteral(0xff54)),
              spec("hdma.ff55", "HDMA5", wordLiteral(0xff55)),
              spec("hdma.state", "VRAM DMA state", "HDMA internal"),
          )
      DebuggerHardwareSubsystem.BANKING_SYSTEM ->
          listOf(
              spec("system.hardwareMode", "Hardware mode", "Graphics capture"),
              spec("system.key0", "KEY0", wordLiteral(0xff4c)),
              spec("system.key1", "KEY1", wordLiteral(0xff4d)),
              spec("system.vbk", "VBK", wordLiteral(0xff4f)),
              spec("system.svbk", "SVBK", wordLiteral(0xff70)),
              spec("system.boot", "Boot ROM mapping", wordLiteral(0xff50)),
              spec("system.opri", "OPRI", wordLiteral(0xff6c)),
              spec("system.ff72", "Undocumented FF72", wordLiteral(0xff72)),
              spec("system.ff73", "Undocumented FF73", wordLiteral(0xff73)),
              spec("system.ff74", "Undocumented FF74", wordLiteral(0xff74)),
              spec("system.ff75", "Undocumented FF75", wordLiteral(0xff75)),
              spec("system.pcm12", "PCM12", wordLiteral(0xff76)),
              spec("system.pcm34", "PCM34", wordLiteral(0xff77)),
          )
      DebuggerHardwareSubsystem.MAPPER ->
          listOf(
              spec("mapper.id", "Mapper", "Cartridge"),
              spec("mapper.romBank", "ROM bank", "Mapper control"),
              spec("mapper.ramBank", "RAM bank", "Mapper control"),
              spec("mapper.ramEnabled", "RAM enabled", "Mapper control"),
              spec("mapper.rtcSelected", "RTC selected", "Mapper control"),
              spec("mapper.rumbleEnabled", "Rumble enabled", "Mapper control"),
          )
    }

private fun spec(id: String, name: String, address: String): DebuggerHardwareFieldSpec =
    DebuggerHardwareFieldSpec(id, name, address)

private fun wordLiteral(value: Int): String =
    "${'$'}${value.toString(16).padStart(4, '0').uppercase()}"
