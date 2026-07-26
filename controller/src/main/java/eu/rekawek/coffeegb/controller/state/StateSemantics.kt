package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.sgb.Commands
import java.lang.reflect.Array as ReflectArray
import java.util.IdentityHashMap

/**
 * Semantic preflight for reconstructed detached values.
 *
 * [StateGraph] proves that a graph uses the registered schema and matches target-owned invariant
 * dimensions. This second layer audits relationships inside each admitted record before any live
 * subsystem is touched. Every registered record has an explicit policy: either executable
 * constraints or a reviewable explanation of why its captured values need no relationship check.
 * Phase-2 decoders can call the profile-independent validator after graph reconstruction. Apply
 * preparation additionally calls [validateForClock] so dimensions and phases derived from the
 * target session clock are rejected before any live component is touched.
 */
internal object StateSemantics {

  internal val policyAudit: Map<String, String> by lazy {
    policies.mapValues { it.value.rationale }
  }

  fun validate(value: Any?) {
    verifyPolicyInventory()
    visit(value, "state", IdentityHashMap(), null)
  }

  /** Completes semantic preflight with bounds owned by the exact target session clock. */
  fun validateForClock(value: Any?, clockSpec: ClockSpec) {
    verifyPolicyInventory()
    visit(value, "state", IdentityHashMap(), clockSpec)
  }

  fun validateBarcodeRuntime(state: BarcodeBoyRuntimeState) {
    val pendingSize = state.pendingSize
    if (pendingSize != null && pendingSize != BARCODE_FRAME_SIZE) {
      throw StateApplyException(
          "Barcode runtime has pending frame length $pendingSize, expected $BARCODE_FRAME_SIZE")
    }
    if (pendingSize != null) {
      val pending = checkNotNull(state.copyPending())
      pending.forEachIndexed { index, value ->
        if (value !in 0..0xff) {
          throw StateApplyException("Barcode runtime has invalid pending[$index]=$value")
        }
      }
    }
  }

  private fun visit(
      value: Any?,
      path: String,
      visited: IdentityHashMap<Any, Boolean>,
      clockSpec: ClockSpec?,
  ) {
    if (value == null || value.javaClass.isPrimitive || value is String || value is Enum<*>) return
    if (visited.put(value, true) != null) return
    when {
      value.javaClass.isArray -> {
        if (!value.javaClass.componentType.isPrimitive) {
          repeat(ReflectArray.getLength(value)) { index ->
            visit(ReflectArray.get(value, index), "$path[$index]", visited, clockSpec)
          }
        }
      }
      value is Iterable<*> ->
          value.forEachIndexed { index, item -> visit(item, "$path[$index]", visited, clockSpec) }
      value is Map<*, *> ->
          value.forEach { (key, item) -> visit(item, "$path[$key]", visited, clockSpec) }
      value.javaClass.isRecord -> {
        val type = value.javaClass.name
        val policy = policies[type]
            ?: throw StateApplyException("No semantic state policy for $type")
        val fields = RecordFields(value, path)
        policy.validate(fields)
        if (clockSpec != null) policy.validateForClock?.invoke(fields, clockSpec)
        fields.components.forEach { component ->
          visit(fields.value(component.name), "$path.${component.name}", visited, clockSpec)
        }
      }
    }
  }

  private class RecordFields(
      private val record: Any,
      private val path: String,
  ) {
    val components = record.javaClass.recordComponents.toList()

    fun value(name: String): Any? {
      val component = components.singleOrNull { it.name == name }
          ?: throw StateApplyException("$path has no field $name")
      component.accessor.trySetAccessible()
      return try {
        component.accessor.invoke(record)
      } catch (failure: ReflectiveOperationException) {
        throw StateApplyException("Could not inspect $path.$name", failure)
      }
    }

    fun int(name: String): Int = value(name) as Int
    fun long(name: String): Long = value(name) as Long
    fun double(name: String): Double = value(name) as Double
    fun boolean(name: String): Boolean = value(name) as Boolean
    fun string(name: String): String = value(name) as String
    fun enumName(name: String): String = (value(name) as Enum<*>).name
    fun intArray(name: String): IntArray = value(name) as IntArray
    fun longArray(name: String): LongArray = value(name) as LongArray
    fun objectArray(name: String): Array<*> = value(name) as Array<*>
    fun list(name: String): List<*> = value(name) as List<*>
    fun map(name: String): Map<*, *> = value(name) as Map<*, *>

    fun require(condition: Boolean, message: String) {
      if (!condition) throw StateApplyException("$path $message")
    }

    fun range(name: String, minimum: Int, maximum: Int) {
      val value = int(name)
      require(value in minimum..maximum, "has invalid $name=$value (expected $minimum..$maximum)")
    }

    fun range(name: String, minimum: Long, maximum: Long) {
      val value = long(name)
      require(value in minimum..maximum, "has invalid $name=$value (expected $minimum..$maximum)")
    }

    fun oneOf(name: String, vararg allowed: Int) {
      val value = int(name)
      require(value in allowed, "has invalid $name=$value (expected ${allowed.joinToString()})")
    }

    fun nonNegative(name: String) {
      val value = int(name)
      require(value >= 0, "has negative $name=$value")
    }

    fun nonNegativeLong(name: String) {
      val value = long(name)
      require(value >= 0, "has negative $name=$value")
    }

    fun intValues(name: String, minimum: Int, maximum: Int) {
      intArray(name).forEachIndexed { index, value ->
        require(value in minimum..maximum,
            "has invalid $name[$index]=$value (expected $minimum..$maximum)")
      }
    }

    fun recordType(name: String, vararg allowed: String) {
      val value = value(name) ?: return
      require(value.javaClass.name in allowed,
          "has incompatible $name record ${value.javaClass.name}")
    }

    fun recordElements(name: String, expected: String) {
      list(name).forEachIndexed { index, value ->
        require(value != null && value.javaClass.name == expected,
            "has incompatible $name[$index] record ${value?.javaClass?.name}")
      }
    }
  }

  private data class Policy(
      val rationale: String,
      val validate: (RecordFields) -> Unit,
      val validateForClock: ((RecordFields, ClockSpec) -> Unit)? = null,
  )

  private fun constrained(rationale: String, validate: (RecordFields) -> Unit) =
      Policy(rationale, validate)

  private fun clockConstrained(
      rationale: String,
      validate: (RecordFields) -> Unit,
      validateForClock: (RecordFields, ClockSpec) -> Unit,
  ) = Policy(rationale, validate, validateForClock)

  private fun pass(rationale: String) = Policy(rationale, {})

  private val policies: Map<String, Policy> by lazy {
    buildMap {
      // Audio and timer phase/counter relationships.
      put("eu.rekawek.coffeegb.core.sound.FrameSequencer\$FrameSequencerState",
          constrained("The 8-step frame-sequencer index is bounded.") { it.range("step", 0, 7) })
      put("eu.rekawek.coffeegb.core.sound.FrequencySweep\$FrequencySweepState",
          constrained("Sweep register widths and pending pipeline counters are bounded.") {
            it.range("period", 0, 7); it.range("shift", 0, 7); it.range("timer", 0, 8)
            it.range("shadowFreq", 0, 0x7ff); it.nonNegative("calculationDelay")
            it.nonNegative("restartHold")
          })
      put("eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeState",
          constrained("NR register latches are bytes; the nested length counter is validated separately.") {
            listOf("nr0", "nr1", "nr2", "nr3", "nr4").forEach { name -> it.range(name, 0, 0xff) }
          })
      put("eu.rekawek.coffeegb.core.sound.Lfsr\$LfsrState",
          constrained("The noise LFSR is a 15-bit register.") { it.range("lfsr", 0, 0x7fff) })
      put("eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterState",
          constrained("Noise register/counter widths, reload alignment, and countdowns are bounded.") {
            it.range("nr43", 0, 0xff); it.range("counter", 0, 0x3fff)
            it.nonNegative("counterCountdown"); it.range("alignment", 0, 3)
          })
      put("eu.rekawek.coffeegb.core.sound.VolumeEnvelope\$VolumeEnvelopeState",
          constrained("Envelope volume, direction, sweep, and reload timer have hardware bounds.") {
            it.range("initialVolume", 0, 15); it.oneOf("envelopeDirection", -1, 0, 1)
            it.range("sweep", 0, 7); it.range("volume", 0, 15); it.range("timer", 0, 8)
          })
      put("eu.rekawek.coffeegb.core.sound.SoundMode1\$SoundMode1State",
          constrained("Pulse-channel waveform and delayed-clock indices are bounded.") {
            it.range("i", 0, 7); it.nonNegative("freqDivider"); it.nonNegative("justReloadedTicks")
          })
      put("eu.rekawek.coffeegb.core.sound.SoundMode2\$SoundMode2State",
          constrained("Pulse-channel waveform and delayed-clock indices are bounded.") {
            it.range("i", 0, 7); it.nonNegative("freqDivider"); it.nonNegative("justReloadedTicks")
          })
      put("eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3State",
          constrained("Wave-channel sample index and last physical wave-RAM read address are bounded.") {
            it.range("i", 0, 31); it.nonNegative("freqDivider")
            val lastReadAddress = it.int("lastReadAddr")
            it.require(lastReadAddress == 0 || lastReadAddress in 0xff30..0xff3f,
                "has invalid lastReadAddr=$lastReadAddress")
          })
      put("eu.rekawek.coffeegb.core.sound.LengthCounter\$LengthCounterState",
          constrained("All channel length counters are in the shared 0..256 envelope.") {
            it.range("length", 0, 256)
          })
      put("eu.rekawek.coffeegb.core.sound.Sound\$SoundState",
          clockConstrained(
              "APU collection sizes and target-clock sample capacity, write index, and sequencer phases are validated together.",
              {
                it.require(it.objectArray("allModeMementos").size == 4, "must contain four sound modes")
                it.require(it.intArray("channels").size == 4, "must contain four channel outputs")
                it.require((it.value("overriddenEnabled") as BooleanArray).size == 4,
                    "must contain four channel overrides")
                val index = it.int("i")
                val samples = it.intArray("buffer")
                it.require(index >= 0 && index % 2 == 0,
                    "has invalid stereo sample index $index")
                it.require(
                    samples.size == index || (samples.size > index && samples.size % 2 == 0),
                    "buffer length ${samples.size} is neither the pending prefix nor a bounded stereo full buffer")
                it.range("pendingFrameSequencerStep", -1, 7)
                it.range("frameSequencerClockPhase", 0, 3)
              },
              { fields, clock ->
                val capacity = Math.multiplyExact(clock.controllerTicksPerFrame(), 2)
                val index = fields.int("i")
                val samples = fields.intArray("buffer")
                fields.require(index < capacity,
                    "has stereo sample index $index outside target-clock capacity $capacity")
                fields.require(samples.size == index || samples.size == capacity,
                    "buffer length ${samples.size} does not match index $index or target-clock capacity $capacity")
              },
          ))
      put("eu.rekawek.coffeegb.core.timer.Timer\$TimerState",
          constrained("Divider/timer registers and delayed edge counters are bounded; MAX_VALUE is a documented sentinel.") {
            it.range("div", 0, 0xffff)
            listOf("tac", "tma", "tima").forEach { name -> it.range(name, 0, 0xff) }
            it.nonNegative("ticksSinceOverflow"); it.nonNegative("haltWakeDelay")
            it.nonNegative("ticksSinceDivReset")
          })
      put("eu.rekawek.coffeegb.core.memory.GbcRam\$GbcRamState",
          constrained("The CGB work-RAM bank selector is a three-bit register.") {
            it.range("svbk", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters\$UndocumentedGbcRegistersState",
          constrained("The FF6C compatibility register is byte-sized.") { it.range("xff6c", 0, 0xff) })
      put("eu.rekawek.coffeegb.core.Gameboy\$GameboyState",
          constrained("Root LCD-off and speed-switch countdowns cannot be negative.") {
            it.nonNegative("lcdOffTicks"); it.nonNegative("speedSwitchTailTicks")
            it.recordType("displayMemento", DISPLAY_STATE)
          })

      // CPU and controller parser indices.
      put("eu.rekawek.coffeegb.core.cpu.Registers\$RegistersState",
          constrained("8-bit registers/flags and 16-bit SP/PC are range checked.") {
            listOf("a", "b", "c", "d", "e", "h", "l").forEach { name -> it.range(name, 0, 0xff) }
            it.range("sp", 0, 0xffff); it.range("pc", 0, 0xffff); it.range("flags", 0, 0xf0)
            it.require((it.int("flags") and 0x0f) == 0, "has non-zero reserved flag bits")
          })
      put("eu.rekawek.coffeegb.core.cpu.Cpu\$CpuState",
          constrained("Opcode, operand, micro-op, and clock-phase indices are bounded before opcode reconstruction.") {
            it.range("opcode1", 0, 0xff); it.range("opcode2", 0, 0xff)
            val operand = it.intArray("operand")
            it.require(operand.size == 2, "must have a two-byte operand latch")
            it.range("operandIndex", 0, operand.size); it.range("opIndex", 0, MAX_CPU_OPS)
            it.range("clockCycle", -1, 4); it.nonNegative("haltEntrySampleTicks")
            it.nonNegative("haltedCpuCycles"); it.nonNegative("speedSwitchTicks")
          })
      put("eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptManagerState",
          constrained("Interrupt registers, delayed-enable sentinel, source masks, and read-mask timers are bounded.") {
            listOf("interruptFlag", "interruptEnabled").forEach { name -> it.range(name, 0, 0xff) }
            it.range("pendingEnableInterrupts", -1, 1)
            listOf("haltBlockedInterrupts", "cpuBlockedInterrupts", "cpuPhasedPpuInterrupts",
                "cpuPhasedMode2Interrupts", "cpuFirstLineMode2Interrupts",
                "cpuInstructionBlockedInterrupts", "cpuReadInterruptPreview")
                .forEach { name -> it.range(name, 0, 0x1f) }
            it.nonNegative("maskMode0LcdcReadTicks")
          })
      put("eu.rekawek.coffeegb.core.joypad.Joypad\$JoypadState",
          constrained("SGB packet and multiplayer controller indices are checked against owned buffers.") {
            it.require((it.int("p1") and 0xcf) == 0, "has invalid JOYP selector bits")
            val control = it.int("players")
            val current = it.int("currentPlayer")
            it.range("players", 0, 3)
            it.require(when (control) {
              0 -> current == 0
              1 -> current in 0..1
              2 -> current == 0 || current == 2
              3 -> current in 0..3
              else -> false
            }, "has an invalid selected player for MLT_REQ control $control")
            it.range("inputHistory", 0, 0xffff)
            it.range("filteredInputLines", 0, 0x0f)
            it.range("pendingTransferBit", -1, 1); it.range("currentByte", 0, 0xff)
            it.range("currentByteIndex", 0, 7)
            val packet = it.intArray("currentPacket")
            it.require(packet.size == 16, "must own exactly one 16-byte ICD2 packet")
            packet.forEachIndexed { index, value ->
              it.require(value in 0..0xff, "has invalid packet byte $index=$value")
            }
            it.range("currentPacketIndex", 0, packet.size)
            val active = it.boolean("transferInProgress")
            val ready = it.boolean("transferReadyForData")
            val pending = it.int("pendingTransferBit")
            it.require(active || (!ready && pending == -1),
                "has receiver data while no transfer is active")
            it.require(pending == -1 || ready,
                "has a pending receiver bit before the start pulse")
          })

      // SGB, IR, and display/parser schedules.
      put("eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState",
          constrained("Transfer command packet length/code and optional 4 KiB VRAM payload are coherent.") {
            val packet = it.intArray("packet")
            val parsed = Commands.parse(packet)
            it.require(parsed.command() is Commands.TransferCommand,
                "does not encode a valid SGB transfer command: ${parsed.reason()}")
            (it.value("dataTransfer") as IntArray?)?.let { data ->
              val violation =
                  Commands.validateTransferData(parsed.command() as Commands.TransferCommand, data)
              it.require(violation == null, "has invalid transfer payload: $violation")
            }
          })
      put("eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyState",
          constrained("Multipacket assembly indices and practical delayed-transfer state are coherent.") {
            val packets = it.objectArray("multipacket")
            packets.forEachIndexed { index, row ->
              it.require(row is IntArray && row.size == 16,
                  "has invalid multipacket row $index")
              (row as IntArray).forEachIndexed { byteIndex, value ->
                it.require(value in 0..0xff,
                    "has invalid multipacket[$index][$byteIndex]=$value")
              }
            }
            it.range("multipacketLength", 0, packets.size)
            it.range("multipacketIndex", 0, packets.size)
            val length = it.int("multipacketLength")
            val index = it.int("multipacketIndex")
            if (length == 0) {
              it.require(index == 0, "has an idle collector with nonzero packet index")
            } else {
              it.require(index in 1 until length,
                  "has multipacket index outside the active packet count")
              val first = packets[0] as IntArray
              it.require((first[0] and 7) == length,
                  "has a first-packet count inconsistent with collector length")
            }
            it.recordType("waitingTransferCommandMemento", TRANSFER_COMMAND_STATE)
            val waiting = it.value("waitingTransferCommandMemento")
            val countdown = it.int("transferCountdown")
            it.require(if (waiting == null) countdown == 0 else countdown in 1..3,
                "has a delayed transfer/countdown presence mismatch")
            if (waiting != null) {
              val transfer = RecordFields(waiting, "superGameboy.waitingTransferCommandMemento")
              val parsed = Commands.parse(transfer.intArray("packet"))
              val command = parsed.command() as? Commands.TransferCommand
              it.require(
                  parsed.disposition() == Commands.Disposition.PRACTICAL &&
                      command != null && Commands.isPracticalTransferCommand(command),
                  "has an unsupported delayed transfer command",
              )
              it.require(
                  transfer.value("dataTransfer") == null,
                  "has a delayed transfer with an already committed payload",
              )
            }
          })
      put("eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayState",
          constrained("SGB palette rows, palette IDs, attribute rows, and fade phase are bounded.") {
            checkRows(it, "palettes", 4, nullable = false)
            checkRows(it, "systemPalettes", 4, nullable = true)
            checkRows(it, "attributeFiles", 20 * 18, nullable = false, values = 0..3)
            it.intValues("paletteMap", 0, 3)
            val packedFade = it.int("borderFade")
            it.require(packedFade >= 0 && (packedFade and SGB_DISPLAY_STATE_ALLOWED_BITS.inv()) == 0 &&
                (packedFade and SGB_DISPLAY_FADE_MASK) in 0..32,
                "has invalid packed border fade/palette-priority state $packedFade")
          })
      put("eu.rekawek.coffeegb.core.sgb.Background\$BackgroundState",
          constrained("The border animation owns one fully committed, render-safe PCT_TRN picture.") {
            it.range("borderAnimation", 0, 105)
            val animation = it.int("borderAnimation")
            val pending = it.value("pendingPictureMemento")
            it.require(pending != null || animation == 0,
                "has an active border animation without a pending picture")
            pending?.let { pendingValue ->
              it.recordType("pendingPictureMemento", TRANSFER_COMMAND_STATE)
              val transfer = RecordFields(pendingValue, "background.pendingPictureMemento")
              val parsed = Commands.parse(transfer.intArray("packet"))
              val picture = parsed.command() as? Commands.PctTrnCmd
              it.require(picture != null && parsed.disposition() == Commands.Disposition.PRACTICAL,
                  "pending picture is not a PCT_TRN command")
              val data = transfer.value("dataTransfer") as? IntArray
              it.require(data != null, "pending picture has no committed VRAM payload")
              if (picture != null && data != null) {
                val violation = Commands.validateTransferCommitData(picture, data)
                it.require(violation == null, "has invalid pending picture payload: $violation")
              }
            }
          })
      put("eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerState",
          constrained("Pulse schedule index/remaining duration are coherent with armed/running state.") {
            val schedule = it.intArray("schedule")
            it.require(schedule.all { duration -> duration > 0 }, "contains a non-positive pulse duration")
            it.require(schedule.isEmpty() || schedule.size == FULL_CHANGER_SCHEDULE_SIZE,
                "has invalid pulse schedule length ${schedule.size}")
            val armed = it.value("armed") as Boolean
            val running = it.value("running") as Boolean
            it.require(!(armed && running), "cannot be both armed and running")
            val index = it.int("index")
            if (schedule.isEmpty()) {
              it.require(!armed && !running && index == 0 && it.int("remaining") == 0,
                  "has non-idle state without a pulse schedule")
            } else {
              it.range("index", 0, schedule.size)
              if (running) {
                it.require(index < schedule.size && it.int("remaining") > 0,
                    "has an invalid running pulse position")
              } else if (!armed) {
                it.require(index == schedule.size && it.int("remaining") <= 0,
                    "has an invalid completed pulse position")
              }
            }
          })

      // DMA/PPU queues, phase indices, and delayed-write collections.
      put("eu.rekawek.coffeegb.core.memory.Dma\$DmaState",
          constrained("OAM DMA address/clock phases and byte latches are bounded; -1 write byte is a sentinel.") {
            it.range("from", 0, 0xffff); it.nonNegative("ticks"); it.nonNegative("transferClocks")
            it.nonNegative("pauseEntryClocks"); it.range("currentByte", 0, 0xff)
            it.range("regValue", 0, 0xff); it.range("pendingInterruptWriteByte", -1, 0xff)
            it.range("pendingInterruptWriteValue", 0, 0xff)
          })
      put("eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState",
          constrained("HDMA block length, signed source progress, request ages, and line phases are audited.") {
            it.range("length", 0, 0x7f); it.range("tick", -8, 31)
            it.range("src", 0, 0xffff); it.range("dst", 0, 0xffff)
            // This cumulative diagnostic/arbitration counter intentionally wraps as an Int
            // across repeated HDMA5 starts. It is neither an allocation size nor an array cursor.
            it.int("sourceBytesTransferred")
            listOf("hblankRequestAge", "nextHblankRequestAge").forEach { name -> it.nonNegative(name) }
            it.range("gpuLine", 0, 153); it.range("gpuTicksInLine", 0, 455)
          })
      put("eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueState",
          constrained("Circular-queue size and offset are checked against capacity.") {
            val capacity = it.intArray("array").size
            it.require(capacity > 0, "has zero queue capacity")
            it.range("size", 0, capacity); it.range("offset", 0, capacity - 1)
          })
      put("eu.rekawek.coffeegb.core.gpu.Display\$DisplayState",
          constrained("The display write cursor cannot leave the owned scanout buffer.") {
            val buffer = it.intArray("buffer")
            it.range("i", 0, buffer.size)
            (it.value("lastFrame") as IntArray?)?.let { lastFrame ->
              it.require(lastFrame.size == buffer.size,
                  "has last-frame length ${lastFrame.size}, expected ${buffer.size}")
            }
          })
      put("eu.rekawek.coffeegb.core.gpu.VRamTransfer\$VRamTransferState",
          constrained("The SGB frame pixel cursor covers exactly one 160x144 source frame.") {
            it.range("i", 0, 160 * 144)
          })
      put("eu.rekawek.coffeegb.core.gpu.SpriteFifo\$SpriteFifoState",
          constrained("Object FIFO head/size and rewind underflow are bounded by its eight slots.") {
            it.range("head", 0, 7); it.range("size", 0, 8); it.nonNegative("underflow")
          })
      put("eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState",
          constrained("Legacy-null or present delay-line arrays must be paired and cursor-bounded.") {
            checkDelayLine(it)
          })
      put("eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState",
          constrained("CGB delay lines and optional cleared-FIFO triplet have coherent presence and indices.") {
            checkDelayLine(it)
            val cleared = listOf("clearedPixels", "clearedPalettes", "clearedPriorities").map(it::value)
            it.require(cleared.all { value -> value == null } || cleared.all { value -> value != null },
                "has a partially present cleared-FIFO triplet")
            listOf("clearedPixels", "clearedPalettes", "clearedPriorities")
                .forEach { name -> it.recordType(name, INT_QUEUE_STATE) }
            it.range("linePixels", 0, 160)
          })
      put("eu.rekawek.coffeegb.core.gpu.ColorPalette\$ColorPaletteState",
          constrained("The CGB palette byte-address register is six bits.") { it.range("index", 0, 63) })
      put("eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState",
          constrained("LCD line/dot and delayed-write phases are bounded.") {
            it.nonNegative("displayEnabledDelay"); it.range("line", 0, 153); it.range("ticksInLine", -1, 455)
            val lastWrite = it.int("lastCpuVramWriteTick")
            it.require(lastWrite == Int.MIN_VALUE || lastWrite in -1..455,
                "has invalid lastCpuVramWriteTick=$lastWrite")
            it.recordType("pixelMachineMemento", PIXEL_TRANSFER_STATE)
            if (it.value("pendingPpuWrites") != null) {
              it.recordElements("pendingPpuWrites", PENDING_PPU_WRITE)
            }
            (it.value("cpuVisiblePpuRegisters") as IntArray?)?.let { registers ->
              it.require(registers.size == CPU_VISIBLE_PPU_REGISTERS,
                  "has ${registers.size} CPU-visible PPU registers")
              it.require(registers.all { value -> value in -1..0xff },
                  "has an invalid CPU-visible PPU register")
            }
          })
      put("eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$OamSearchState",
          constrained("Mode-2 OAM reader and selected-sprite indices match their fixed arrays.") {
            it.range("spritePosIndex", 0, it.objectArray("sprites").size)
            it.range("i", 0, it.intArray("oamReaderY").size)
            it.range("spriteHeight", 0, 16); it.range("previousOamSpriteHeight", 0, 16)
            it.nonNegative("oamReaderSourceChangeTicks")
          })
      put("eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState",
          constrained("Mode-3 sprite/fetch/window indices and nullable legacy lists are coherent.") {
            val spriteOrder = it.intArray("spriteOrder")
            it.range("spriteCount", 0, spriteOrder.size)
            it.require(spriteOrder.all { index -> index in 0..9 },
                "has a sprite-order index outside the ten OAM slots")
            it.range("spriteHead", 0, it.int("spriteCount")); it.range("objStep", -1, 5)
            it.range("position", -16, 160); it.range("objAttributesValue", -1, 0xff)
            it.range("objRefreshAttrsValue", -1, 0xff); it.range("objRefreshPops", 0, 8)
            it.nonNegative("entryTicks"); it.nonNegative("windowPendingTicks")
            it.recordType("fifoMemento", DMG_FIFO_STATE, COLOR_FIFO_STATE)
            if (it.value("pendingWindowDisplayWrites") != null) {
              it.recordElements("pendingWindowDisplayWrites", DELAYED_WINDOW_WRITE)
            }
            if (it.value("pendingWindowXWrites") != null) {
              it.recordElements("pendingWindowXWrites", DELAYED_WINDOW_WRITE)
            }
          })
      put("eu.rekawek.coffeegb.core.gpu.Fetcher\$FetcherState",
          constrained("Fetcher state and attribute sentinel are bounded to the seven-stage machine.") {
            it.range("state", 0, 6); it.range("tileAttributesValue", -1, 0xff)
            it.nonNegative("data2Delay")
          })
      put("eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWriteState",
          constrained("Pending PPU writes retain a 16-bit address and non-negative dot delay.") {
            val address = it.int("address")
            it.require(address in DELAYED_PPU_REGISTERS,
                "has unsupported delayed PPU address ${address.toString(16)}")
            it.range("value", 0, 0xff); it.range("mask", 0, 0xff)
            it.require(it.int("mask") == when (address) {
              0xff40 -> 0x20
              0xff43 -> 0x07
              else -> 0xff
            }, "has an invalid mask for its delayed PPU register")
            it.range("remainingDots", 0, 4)
          })
      put("eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWriteState",
          constrained("Delayed window-register writes carry a byte and non-negative dot delay.") {
            it.range("value", 0, 0xff); it.range("remainingDots", 0, 4)
          })

      put("eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesState",
          constrained("GPU register arrays, nullable legacy mix arrays, and old-value sentinels are coherent.") {
            val values = it.intArray("values")
            it.intValues("values", 0, 0xff)
            val mix = it.value("mixValues") as IntArray?
            val pending = it.value("pendingMixValues") as IntArray?
            it.require((mix == null) == (pending == null), "has only one palette-mix array")
            listOfNotNull(mix, pending).forEach { array ->
              it.require(array.size == values.size, "has a palette-mix length different from the register array")
              it.require(array.all { value -> value in -1..0xff }, "has an invalid palette-mix value")
            }
            it.range("wxJustChangedTicks", 0, 2)
            it.range("scxOldValue", -1, 0xff); it.range("pendingScxOldValue", -1, 0xff)
          })
      put("eu.rekawek.coffeegb.core.gpu.Lcdc\$LcdcState",
          constrained("LCDC latches are bytes or the documented -1 mix sentinel; glitch timers are non-negative.") {
            it.range("value", 0, 0xff); it.range("mixValue", -1, 0xff)
            it.range("pendingMixValue", -1, 0xff); it.nonNegative("tileSelectGlitchTicks")
            it.nonNegative("pendingTileSelectGlitchTicks")
          })
      put("eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase\$HBlankPhaseState",
          constrained("HBlank elapsed-dot count cannot be negative.") { it.nonNegative("ticks") })
      put("eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase\$VBlankPhaseState",
          constrained("VBlank elapsed-dot count cannot be negative.") { it.nonNegative("ticks") })
      put("eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$SpritePosition\$SpritePositionState",
          constrained("Enabled sprite entries retain byte coordinates and an address inside OAM.") {
            it.range("x", 0, 0xff); it.range("y", 0, 0xff)
            if (it.value("enabled") as Boolean) it.range("address", 0xfe00, 0xfe9c)
          })

      // RTC and mapper command-state indices.
      put("eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState",
          clockConstrained(
              "Live and latched RTC registers are hardware-bounded; subsecond phase is bounded by the target clock.",
              {
                listOf("seconds", "minutes", "latchedSeconds", "latchedMinutes").forEach { name -> it.range(name, 0, 0x3f) }
                listOf("hours", "latchedHours").forEach { name -> it.range(name, 0, 0x1f) }
                listOf("days", "latchedDays").forEach { name -> it.range(name, 0, 511) }
                it.nonNegativeLong("subSecondTicks")
              },
              { fields, clock ->
                fields.range("subSecondTicks", 0L, clock.secondPhaseLimit() - 1L)
              },
          ))
      put("eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6State",
          constrained("AMD flash unlock/erase transaction state is one of six stages.") {
            it.range("flashCommandState", 0, 5)
            listOf("ramBankA", "ramBankB", "romBankA", "romBankB")
                .forEach { name -> it.range(name, 0, 0xff) }
            if (it.value("flashProgramMode") as Boolean) {
              it.require(it.int("flashCommandState") == 2,
                  "has program mode without the completed unlock prefix")
            }
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromState",
          constrained("EEPROM serial phase, bit count, address sentinel, and output bit are coherent.") {
            val state = it.enumName("state")
            val maxBits = when (state) { "COMMAND" -> 9; "READING" -> 16; "WRITING" -> 15; else -> 17 }
            it.range("bitsRead", 0, maxBits); it.range("address", -1, 127); it.range("doBit", 0, 1)
            it.range("command", 0, 0x3ff); it.range("writeValue", 0, 0xffff)
            it.intValues("eeprom", 0, 0xff)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.Mbc7\$Mbc7State",
          constrained("Accelerometer latch, ROM bank, finite input, and EEPROM root are validated.") {
            it.range("selectedRomBank", 0, 0x1ff); it.range("latchState", 0, 2)
            it.require(it.double("x").isFinite() && it.double("y").isFinite(),
                "has a non-finite accelerometer input")
            it.recordType("eepromMemento", MBC7_EEPROM_STATE)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3State",
          constrained("HuC3 command address/flags/read latches are byte-sized.") {
            it.range("romBank", 0, 0x7f); it.range("ramBank", 0, 0x0f); it.range("mode", 0, 0x0f)
            it.range("accessIndex", 0, 0xff); it.range("accessFlags", 0, 0xff); it.range("readValue", 0, 0xff)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart\$DuzMulticartState",
          constrained("DÜZ register, ROM, and RAM selectors retain their hardware widths.") {
            it.range("regIndex", 0, 0xff); it.range("selectedBank", 1, 0x7f)
            it.range("selectedRamBank", 0, 0xff); it.nonNegative("baseBank")
            it.intValues("regs", 0, 0xff)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc\$SachenState",
          constrained("Sachen lock and boot-logo transition machines have finite stages.") {
            listOf("unmaskedBank", "mask", "base").forEach { name -> it.range(name, 0, 0xff) }
            it.range("lockState", 0, 2); it.range("transition", 0, 0x30)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart\$SlMulticartState",
          constrained("SL configuration command is either a byte command or the idle sentinel 0x100.") {
            val command = it.int("configCommand")
            it.require(command in 0..0xff || command == 0x100, "has invalid configCommand=$command")
            it.range("baseRomBank", 0, 0x3ff); it.range("selectedRomBank", 0, 0xff)
            val romMask = it.int("romBankMask")
            it.require(romMask in 1..0xff && (romMask and (romMask + 1)) == 0,
                "has invalid ROM bank mask $romMask")
            it.range("zeroRemap", 0, 1); it.range("baseRamBank", 0, 0x0f)
            it.range("selectedRamBank", 0, 0x0f); it.oneOf("ramBankMask", 0, 3)
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5State",
          constrained("TAMA5 command selector and nibble register/page values are bounded.") {
            it.range("selectedReg", 0, 15)
            listOf("registers", "rtcTimerPage", "rtcAlarmPage", "rtcFreePage0", "rtcFreePage1")
                .forEach { name ->
                  it.require(it.intArray(name).all { value -> value in 0..15 }, "$name contains a non-nibble value")
                }
          })
      put("eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelState",
          constrained("Outer Datel flash transaction state is a four-stage JEDEC sequence.") {
            it.range("flashCycle", 0, 3)
          })

      // Mapper bank/mode registers that can become array indices or table selectors.
      addMapperPolicies(this)

      // Serial parser/framing indices.
      put("eu.rekawek.coffeegb.core.genie.Genie\$GenieState",
          constrained("Cheat map values and elements are non-null registered patch records.") {
            it.map("patches").forEach { (key, value) ->
              it.require(key is Int && key in 0..0xffff, "has invalid patch-map key $key")
              val patches = value as? List<*>
              it.require(patches != null, "has null or non-list patch-map value at $key")
              checkNotNull(patches).forEachIndexed { index, patch ->
                it.require(
                    patch != null && patch.javaClass.name in REGISTERED_PATCH_TYPES,
                    "has invalid patch at $key[$index]")
              }
            }
          })
      put("eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint\$Peer2PeerSerialEndpointState",
          constrained("Link-cable shift index and pending-bit count cannot underflow.") {
            it.range("sb", 0, 0xff); it.nonNegative("bitsReceived"); it.range("bitIndex", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint\$ByteReceivingSerialEndpointState",
          constrained("Byte-receiver byte and bit count are bounded.") {
            it.range("sb", 0, 0xff); it.range("bits", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyState",
          constrained("Barcode protocol phase and exact scan-frame cursors are validated together.") {
            it.range("handshakeByte", 0, 3); it.range("sendBitIndex", 0, 7)
            it.range("recvBitIndex", 0, 7); it.range("clockDivider", 0, 511)
            val data = it.value("data") as IntArray?
            val sending = it.enumName("state") == "SENDING"
            it.require(sending == (data != null), "has scan data inconsistent with protocol state")
            if (data == null) it.require(it.int("dataByte") == 0, "has a data cursor without scan data")
            else {
              it.require(data.size == BARCODE_FRAME_SIZE,
                  "has barcode frame length ${data.size}, expected $BARCODE_FRAME_SIZE")
              it.intValues("data", 0, 0xff); it.range("dataByte", 0, data.lastIndex)
            }
          })
      put("eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint\$PrinterState",
          constrained("Printer parser, command/image cursors, compression run, and bit phase are bounded.") {
            it.range("state", 0, 10); it.nonNegative("lengthLeft")
            it.range("commandLength", 0, it.intArray("commandData").size)
            it.range("imageOffset", 0, it.intArray("image").size)
            it.nonNegative("compressionRunLength"); it.nonNegative("printCountdown")
            listOf("commandId", "status", "byteToSend", "currentReply", "sb")
                .forEach { name -> it.range(name, 0, 0xff) }
            it.range("checksum", 0, 0xffff); it.range("compressionRunLength", 0, 128)
            it.range("sendBits", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint\$GpsReceiverState",
          constrained("UART transmit/receive phases, queue bytes, timers, and bounded TAIP parser are checked.") {
            it.range("startupBeacons", 0, 2)
            it.require(it.intArray("outputBytes").all { value -> value in 0..0xff }, "output queue contains a non-byte")
            it.range("outputByte", -1, 0xff); it.range("outputBit", -1, 10)
            it.nonNegative("outputTicksRemaining"); it.nonNegative("outputDelayTicks")
            it.range("receiveBit", -1, 9); it.range("receiveByte", 0, 0xff)
            it.range("receiveOnes", 0, 8)
            it.require(it.string("taipCommand").length <= 64, "has an oversized TAIP command")
          })
      put("eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortState",
          constrained("Serial byte/register and receive/clock phases are bounded.") {
            it.range("sb", 0, 0xff); it.range("sc", 0, 0xff)
            it.nonNegative("serialClocks"); it.range("receivedBits", 0, 7); it.nonNegative("haltWakeDelay")
          })
      put("eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$AdapterState",
          constrained("DMG-07 player arrays, packet/bit cursors, rate/size, and delayed clock are coherent.") {
            val players = 4
            listOf("sb", "pendingBits", "consecutiveFf").forEach { name ->
              it.require(it.intArray(name).size == players, "$name must have four player entries")
            }
            it.require(it.intArray("sb").all { value -> value in 0..0xff }, "has an invalid player byte")
            it.require((it.value("transferArmed") as BooleanArray).size == players,
                "transferArmed must have four entries")
            it.require((it.value("connected") as BooleanArray).size == players,
                "connected must have four entries")
            checkRows(it, "replies", 16, nullable = false)
            it.require(it.intArray("transmissionBuffer").size == 16, "must have a 16-byte transmission buffer")
            it.range("size", 1, 4); it.range("packetByte", 0, 15); it.range("bit", 0, 7)
            it.nonNegative("ticksUntilBit"); it.range("rate", 0, 0xff)
            it.require(it.intArray("pendingBits").all { bit -> bit in -1..1 }, "has an invalid pending bit")
            it.require(it.intArray("consecutiveFf").all { count -> count >= 0 }, "has a negative FF counter")
          })

      put("eu.rekawek.coffeegb.core.genie.Genie\$GameGeniePatchState",
          constrained("Game Genie addresses/data are fixed-width; -1 is the no-old-value sentinel.") {
            it.range("newData", 0, 0xff); it.range("address", 0, 0xffff)
            it.range("oldData", -1, 0xff)
          })
      put("eu.rekawek.coffeegb.core.genie.Genie\$GameSharkPatchState",
          constrained("GameShark mode/bank/data are bytes and the destination is a 16-bit address.") {
            it.range("mode", 0, 0xff); it.range("bank", 0, 0xff)
            it.range("address", 0, 0xffff); it.range("data", 0, 0xff)
          })

      // Explicitly audited records with no scalar relationship beyond schema, target dimensions,
      // enum validity, or validators on their nested records.
      addPassThroughPolicies(this)
    }
  }

  private fun checkRows(
      fields: RecordFields,
      name: String,
      expectedLength: Int,
      nullable: Boolean,
      values: IntRange? = null,
  ) {
    fields.objectArray(name).forEachIndexed { index, row ->
      fields.require(nullable || row != null, "$name[$index] is unexpectedly null")
      if (row != null) {
        fields.require(row is IntArray && row.size == expectedLength, "$name[$index] has invalid row length")
        if (row is IntArray && values != null) {
          fields.require(row.all { value -> value in values },
              "$name[$index] contains a value outside ${values.first}..${values.last}")
        }
      }
    }
  }

  private fun checkDelayLine(fields: RecordFields) {
    val entries = fields.value("delayEntry") as IntArray?
    val stamps = fields.value("delayStamp") as LongArray?
    fields.require((entries == null) == (stamps == null), "has only one delay-line array")
    if (entries != null && stamps != null) {
      fields.require(entries.size == stamps.size, "has mismatched delay-line lengths")
      fields.require(entries.isNotEmpty(), "has an empty delay line")
      fields.range("delayHead", 0, entries.lastIndex)
      fields.range("delaySize", 0, entries.size)
    } else {
      fields.require(fields.int("delayHead") == 0 && fields.int("delaySize") == 0,
          "has delay cursors without legacy delay-line arrays")
    }
  }

  private fun addMapperPolicies(target: MutableMap<String, Policy>) {
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mbc1\$Mbc1State"] =
        constrained("MBC1 bank/model registers and cached bank sentinels retain their hardware widths.") {
          it.range("selectedRamBank", 0, 3); it.range("selectedRomBank", 0, 0x7f)
          it.range("memoryModel", 0, 1)
          it.range("cachedRomBankFor0x0000", -1, 0x7f)
          it.range("cachedRomBankFor0x4000", -1, 0x7f)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.BungEms\$BungEmsState"] =
        constrained("Bung/EMS ROM latches are bytes, its high bit is binary, and RAM has four banks.") {
          listOf("romBankLow", "romBankMask", "romBankLatch").forEach { name -> it.range(name, 0, 0xff) }
          it.range("romBankHigh", 0, 1); it.range("selectedRamBank", 0, 3)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mbc2\$Mbc2State"] =
        constrained("MBC2's selected ROM bank is the 4-bit value or audited 5-bit compatibility value.") {
          it.range("selectedRomBank", 1, 0x1f)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.BasicRom\$BasicRomState"] =
        constrained("Plain-ROM battery state, when present, must be a registered battery state.") {
          it.recordType("batteryMemento", MEMORY_BATTERY_STATE, FILE_BATTERY_STATE)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5State"] =
        constrained("MBC5 exposes a nine-bit ROM bank and four-bit RAM/rumble register.") {
          it.range("selectedRamBank", 0, 0x0f); it.range("selectedRomBank", 0, 0x1ff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera\$PocketCameraState"] =
        constrained("Pocket Camera ROM/RAM selectors and camera register bytes are bounded.") {
          it.range("romBank", 0, 0x3f); it.range("ramBank", 0, 0x0f)
          it.intValues("cameraRegisters", 0, 0xff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart\$BhgosMulticartState"] =
        constrained("GBOS RAM-bank indexing and non-negative block-selection progress are bounded.") {
          it.range("selectedRomBank", 1, 0xff); it.range("selectedRamBank", 0, 3)
          it.nonNegative("baseRomBank"); it.nonNegative("blockSelectWrites")
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2\$MakonNtOld2State"] =
        constrained("Makon bank latches and contiguous low-bit game mask are coherent.") {
          it.range("selectedRomBank", 1, 0xff); it.range("mappedRomBank", 0, 0xff)
          it.range("baseRomBank", 0, 0x7e)
          val mask = it.int("gameRomBankMask")
          it.require(mask >= 0 && (mask and (mask + 1)) == 0, "has invalid game ROM mask $mask")
          it.require(it.int("mappedRomBank") <= mask, "has mapped bank outside the selected game mask")
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Sintax\$SintaxState"] =
        constrained("Sintax table mode, bank byte, XOR bytes, and nested MBC5 root are validated.") {
          it.recordType("delegateMemento", MBC5_STATE); it.range("mode", 0, 15)
          it.range("bankNo", 0, 0xff); it.range("romBankXor", 0, 0xff)
          it.intValues("xorValues", 0, 0xff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree\$WisdomTreeState"] =
        constrained("Wisdom Tree's address-selected 32 KiB bank cannot be negative or overflow its product.") {
          it.range("bank", 0, 0x7fff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart\$Mani32kState"] =
        constrained("The Mani block selector originates from an unsigned cartridge write.") {
          it.range("block", 0, 0xff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Huc1\$Huc1State"] =
        constrained("HuC1 ROM/RAM selectors retain their six-bit and three-bit widths.") {
          it.range("romBank", 0, 0x3f); it.range("ramBank", 0, 7)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3State"] =
        constrained("MBC3 ROM and RAM/RTC selectors originate from seven-bit and byte registers.") {
          it.range("selectedRomBank", 0, 0x7f); it.range("selectedRamBank", 0, 0xff)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Bbd\$BbdState"] =
        constrained("BBD data/bank permutation selectors index eight-entry tables.") {
          it.recordType("delegateMemento", MBC5_STATE)
          it.range("dataSwapMode", 0, 7); it.range("bankSwapMode", 0, 7)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Mmm01\$Mmm01State"] =
        constrained("MMM01 bank fields and masks retain their documented hardware bit widths.") {
          it.range("romBankLow", 0, 0x1f); it.range("romBankMid", 0, 3)
          it.range("romBankHigh", 0, 3); it.range("romBankMask", 0, 0x0f)
          it.range("ramBankLow", 0, 3); it.range("ramBankHigh", 0, 3)
          it.range("ramBankMask", 0, 3)
        }
  }

  private fun addPassThroughPolicies(target: MutableMap<String, Policy>) {
    val valueOnly =
        setOf(
            "eu.rekawek.coffeegb.core.sound.SoundMode4\$SoundMode4State",
            "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeState",
            "eu.rekawek.coffeegb.core.memory.BiosShadow\$BiosShadowState",
            "eu.rekawek.coffeegb.core.memory.Ram\$RamState",
            "eu.rekawek.coffeegb.core.memory.Mmu\$MmuState",
            "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryState",
            "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryState",
            "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeState",
            "eu.rekawek.coffeegb.core.memory.OamEchoRam\$OamEchoRamState",
            "eu.rekawek.coffeegb.core.gpu.StatRegister\$StatRegisterState",
            "eu.rekawek.coffeegb.core.ir.InfraredPort\$InfraredPortState",
            "eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble\$CodeBreakerRumbleState",
        )
    valueOnly.forEach { name ->
      target[name] = pass("Fields are direct value/latch state; array dimensions, enum tags, nested records, and nullability are validated elsewhere. Signed clocks and -1 sentinels are intentionally unconstrained.")
    }

  }

  private fun verifyPolicyInventory() {
    val registered = StateTypeRegistry.recordClassNames.toSet()
    val missing = registered - policies.keys
    val extra = policies.keys - registered
    if (missing.isNotEmpty() || extra.isNotEmpty()) {
      throw StateApplyException("Semantic policy inventory mismatch; missing=$missing extra=$extra")
    }
  }

  private const val MAX_CPU_OPS = 64
  private const val SGB_DISPLAY_FADE_MASK = 0xff
  private const val SGB_DISPLAY_STATE_ALLOWED_BITS = 0x1ff
  private const val FULL_CHANGER_SCHEDULE_SIZE = 36
  private const val BARCODE_FRAME_SIZE = 30
  private const val CPU_VISIBLE_PPU_REGISTERS = 12
  private const val DISPLAY_STATE = "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState"
  private const val PIXEL_TRANSFER_STATE =
      "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState"
  private const val DMG_FIFO_STATE = "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState"
  private const val COLOR_FIFO_STATE =
      "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState"
  private const val INT_QUEUE_STATE = "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueState"
  private const val PENDING_PPU_WRITE = "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWriteState"
  private const val DELAYED_WINDOW_WRITE =
      "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWriteState"
  private const val TRANSFER_COMMAND_STATE =
      "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState"
  private const val MEMORY_BATTERY_STATE =
      "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryState"
  private const val FILE_BATTERY_STATE =
      "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryState"
  private const val MBC5_STATE = "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5State"
  private const val MBC7_EEPROM_STATE =
      "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromState"
  private val REGISTERED_PATCH_TYPES =
      setOf(
          "eu.rekawek.coffeegb.core.genie.Genie\$GameGeniePatchState",
          "eu.rekawek.coffeegb.core.genie.Genie\$GameSharkPatchState",
      )
  private val DELAYED_PPU_REGISTERS = setOf(0xff40, 0xff43, 0xff47, 0xff4b)
}
