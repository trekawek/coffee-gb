package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
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
      StateTypeRegistry.isAuditedStateType(value.javaClass) -> {
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
    val components = StateRecordIntrospection.components(record.javaClass)

    fun value(name: String): Any? {
      val component = components.singleOrNull { it.name == name }
          ?: throw StateApplyException("$path has no field $name")
      return try {
        component.value(record)
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
    fun byteArray(name: String): ByteArray = value(name) as ByteArray
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

    fun requiredRecordType(name: String, vararg allowed: String) {
      val value = value(name)
      require(value != null, "has no " + name + " record")
      if (value != null) {
        require(
            value.javaClass.name in allowed,
            "has incompatible " + name + " record " + value.javaClass.name,
        )
      }
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
      put("eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineState",
          clockConstrained(
              "Mobile Adapter parser, configuration, output, outcome, slots, and emulated idle timer are validated together.",
              { fields -> validateMobileAdapterEngine(fields, false) },
              ::validateMobileAdapterClock,
          ))
      put("eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointState",
          constrained("Mobile Adapter endpoint bit/byte latches and nested pure engine state are bounded.") {
            it.requiredRecordType("engineState", MOBILE_ADAPTER_ENGINE_STATE)
            it.range("sb", 0, 0xff)
            it.range("sendBitIndex", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineNetworkState",
          clockConstrained(
              "Additive Mobile Adapter network capture state validates the legacy parser fields plus its disconnected external-I/O marker.",
              { fields ->
                val externalIoAtCapture = fields.boolean("externalIoAtCapture")
                fields.require(
                    externalIoAtCapture,
                    "uses the additive network record without captured external I/O",
                )
                validateMobileAdapterEngine(fields, externalIoAtCapture)
              },
              ::validateMobileAdapterClock,
          ))
      put("eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointNetworkState",
          constrained("Additive Mobile Adapter endpoint state bounds its latches and nested network capture state.") {
            it.requiredRecordType("engineState", MOBILE_ADAPTER_NETWORK_ENGINE_STATE)
            it.range("sb", 0, 0xff)
            it.range("sendBitIndex", 0, 7)
          })
      put("eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointWireState",
          constrained("Additive Mobile Adapter wire state preserves a bounded ACK, poll, response, and sender-ACK transaction.") {
            validateMobileAdapterWireState(it)
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

  private fun validateMobileAdapterWireState(fields: RecordFields) {
    fields.requiredRecordType(
        "engineState",
        MOBILE_ADAPTER_ENGINE_STATE,
        MOBILE_ADAPTER_NETWORK_ENGINE_STATE,
    )
    fields.range("sb", 0, 0xff)
    fields.range("sendBitIndex", 0, 7)
    val byteTransferActive = fields.boolean("byteTransferActive")
    fields.require(
        byteTransferActive || fields.int("sendBitIndex") == 0,
        "has a nonzero bit index outside an active byte transfer",
    )
    fields.oneOf(
        "wirePhaseId",
        MOBILE_WIRE_RECEIVE_REQUEST,
        MOBILE_WIRE_REQUEST_ACK_DEVICE,
        MOBILE_WIRE_REQUEST_ACK_COMMAND,
        MOBILE_WIRE_RESPONSE_TURNAROUND,
        MOBILE_WIRE_RESPONSE_WAIT,
        MOBILE_WIRE_RESPONSE_STREAM,
        MOBILE_WIRE_RESPONSE_ACK_DEVICE,
        MOBILE_WIRE_RESPONSE_ACK_COMMAND,
        MOBILE_WIRE_RESPONSE_READY,
        MOBILE_WIRE_NORMALIZED_DISCONNECT,
    )
    fields.range("currentReply", 0, 0xff)
    fields.range("responseRetryCount", 0, MOBILE_MAX_RESPONSE_RETRIES)

    val phase = fields.int("wirePhaseId")
    val acknowledgement = fields.byteArray("requestAcknowledgement")
    val response = fields.byteArray("responsePacket")
    fields.require(response.size <= MOBILE_PACKET_BYTES, "has an oversized wire response packet")
    val responseByteIndex = fields.int("responseByteIndex")
    fields.require(
        responseByteIndex in 0..response.size,
        "has invalid responseByteIndex=$responseByteIndex",
    )
    val engineOutput = mobileAdapterEngineOutput(fields)
    if (phase == MOBILE_WIRE_NORMALIZED_DISCONNECT) {
      fields.require(
          byteTransferActive &&
              engineOutput.outcomeId in
                  setOf(
                      MOBILE_OUTCOME_IDLE_TIMEOUT_RESET,
                      MOBILE_OUTCOME_CANCELLED,
                      MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED,
                  ) &&
              engineOutput.packetCount == 0 &&
              engineOutput.acknowledgement.isEmpty() &&
              engineOutput.responsePacket.isEmpty() &&
              acknowledgement.isEmpty() &&
              response.isEmpty() &&
              responseByteIndex == 0 &&
              !fields.boolean("awaitingResponse") &&
              fields.int("responseRetryCount") == 0,
          "has an inconsistent normalized disconnect byte",
      )
      return
    }
    if (phase == MOBILE_WIRE_RECEIVE_REQUEST) {
      fields.require(
          byteTransferActive &&
              fields.int("currentReply") == MOBILE_IDLE_BYTE &&
              acknowledgement.isEmpty() &&
              response.isEmpty() &&
              responseByteIndex == 0 &&
              !fields.boolean("awaitingResponse") &&
              fields.int("responseRetryCount") == 0,
          "has an inconsistent in-flight request byte",
      )
      return
    }
    fields.require(
        acknowledgement.size == 2,
        "must retain exactly two request acknowledgement bytes",
    )

    fields.require(
        response.contentEquals(engineOutput.responsePacket),
        "has a response packet detached from its engine result",
    )
    fields.require(
        mobileWireAcknowledgementMatches(acknowledgement, engineOutput),
        "has a request acknowledgement detached from its engine result",
    )
    if (acknowledgement.size == 2) {
      fields.require(
          (acknowledgement[0].toInt() and 0xff) == (engineOutput.deviceId or 0x80),
          "has a wire acknowledgement for another device ID",
      )
    }

    val streamingOrResponseAck =
        phase == MOBILE_WIRE_RESPONSE_STREAM ||
            phase == MOBILE_WIRE_RESPONSE_ACK_DEVICE ||
            phase == MOBILE_WIRE_RESPONSE_ACK_COMMAND ||
            phase == MOBILE_WIRE_RESPONSE_READY
    fields.require(
        !streamingOrResponseAck || response.isNotEmpty(),
        "has a response phase without a packet",
    )
    if (phase == MOBILE_WIRE_RESPONSE_STREAM) {
      fields.require(responseByteIndex < response.size, "has an exhausted response cursor")
    }
    if (phase == MOBILE_WIRE_RESPONSE_ACK_DEVICE || phase == MOBILE_WIRE_RESPONSE_ACK_COMMAND) {
      fields.require(
          responseByteIndex == response.size,
          "starts the response acknowledgement before the packet ends",
      )
    } else if (
        phase == MOBILE_WIRE_REQUEST_ACK_DEVICE ||
            phase == MOBILE_WIRE_REQUEST_ACK_COMMAND ||
            phase == MOBILE_WIRE_RESPONSE_TURNAROUND ||
            phase == MOBILE_WIRE_RESPONSE_WAIT ||
            phase == MOBILE_WIRE_RESPONSE_READY
    ) {
      fields.require(responseByteIndex == 0, "has a response cursor before streaming begins")
    }

    if (phase <= MOBILE_WIRE_RESPONSE_WAIT) {
      fields.require(
          fields.int("responseRetryCount") == 0,
          "has a response retry count during request acknowledgement",
      )
    }

    val awaitingResponse = fields.boolean("awaitingResponse")
    fields.require(
        awaitingResponse == response.isNotEmpty(),
        "has inconsistent response-wait ownership",
    )
    fields.require(
        awaitingResponse || phase <= MOBILE_WIRE_REQUEST_ACK_COMMAND,
        "has a response phase when no response is expected",
    )
    if (phase == MOBILE_WIRE_RESPONSE_READY) {
      fields.require(
          !byteTransferActive,
          "is mid-byte in the response-ready phase",
      )
    }

    if (byteTransferActive) {
      val expectedReply =
          when (phase) {
            MOBILE_WIRE_REQUEST_ACK_DEVICE, MOBILE_WIRE_RESPONSE_ACK_DEVICE ->
                acknowledgement[0].toInt() and 0xff
            MOBILE_WIRE_REQUEST_ACK_COMMAND -> acknowledgement[1].toInt() and 0xff
            MOBILE_WIRE_RESPONSE_TURNAROUND, MOBILE_WIRE_RESPONSE_WAIT -> MOBILE_IDLE_BYTE
            MOBILE_WIRE_RESPONSE_STREAM -> response[responseByteIndex].toInt() and 0xff
            MOBILE_WIRE_RESPONSE_ACK_COMMAND -> 0
            else -> -1
          }
      fields.require(
          fields.int("currentReply") == expectedReply,
          "has an in-flight reply inconsistent with its wire phase",
      )
    }
  }

  private fun mobileAdapterEngineOutput(fields: RecordFields): MobileAdapterEngineOutput =
      when (val state = fields.value("engineState")) {
        is MobileAdapterEngine.MobileAdapterEngineState ->
            MobileAdapterEngineOutput(
                state.deviceId(),
                state.outcomeId(),
                state.packetCount(),
                state.acknowledgement(),
                state.responsePacket(),
            )
        is MobileAdapterEngine.MobileAdapterEngineNetworkState ->
            MobileAdapterEngineOutput(
                state.deviceId(),
                state.outcomeId(),
                state.packetCount(),
                state.acknowledgement(),
                state.responsePacket(),
            )
        else -> throw StateApplyException("Mobile Adapter wire state has no compatible engine")
      }

  private fun mobileWireAcknowledgementMatches(
      acknowledgement: ByteArray,
      engineOutput: MobileAdapterEngineOutput,
  ): Boolean {
    if (acknowledgement.contentEquals(engineOutput.acknowledgement)) return true
    if (engineOutput.acknowledgement.isNotEmpty() || engineOutput.responsePacket.isEmpty()) {
      return false
    }
    val expectedCommand =
        when (engineOutput.outcomeId) {
          MOBILE_OUTCOME_BACKEND_RESPONSE ->
              engineOutput.responsePacket.getOrNull(2)?.toInt()?.and(0xff) ?: -1
          MOBILE_OUTCOME_BACKEND_REMOTE_CLOSED -> 0x95
          MOBILE_OUTCOME_BACKEND_ERROR ->
              if (
                  engineOutput.responsePacket.size >= 8 &&
                      (engineOutput.responsePacket[2].toInt() and 0xff) == 0x6e
              ) {
                (engineOutput.responsePacket[6].toInt() and 0xff) xor 0x80
              } else {
                -1
              }
          else -> -1
        }
    return acknowledgement.size == 2 &&
        (acknowledgement[1].toInt() and 0xff) == expectedCommand
  }

  private data class MobileAdapterEngineOutput(
      val deviceId: Int,
      val outcomeId: Int,
      val packetCount: Int,
      val acknowledgement: ByteArray,
      val responsePacket: ByteArray,
  )

  private fun validateMobileAdapterEngine(
      fields: RecordFields,
      externalIoAtCapture: Boolean,
  ) {
    fields.oneOf(
        "phaseId",
        MOBILE_PHASE_SLEEP,
        MOBILE_PHASE_SESSION,
        MOBILE_PHASE_TELEPHONE,
        MOBILE_PHASE_INTERNET,
    )
    fields.range("outcomeId", MOBILE_OUTCOME_NEED_MORE, MOBILE_OUTCOME_SERVICE_ERROR)
    fields.require(
        fields.int("outcomeId") != MOBILE_OUTCOME_TIME_REGRESSION,
        "contains a transient time-regression outcome",
    )
    fields.range("errorId", MOBILE_ERROR_NONE, MOBILE_ERROR_EXTERNAL_IO_DISCONNECTED)
    fields.range("deviceId", 0, 0x7f)

    val packet = fields.byteArray("packetBuffer")
    fields.require(
        packet.size == MOBILE_PACKET_BYTES,
        "must own exactly $MOBILE_PACKET_BYTES parser bytes",
    )
    val packetCount = fields.int("packetCount")
    fields.require(packetCount in 0..packet.size, "has invalid packetCount=$packetCount")
    val expectedPacketBytes = fields.int("expectedPacketBytes")
    if (packetCount < MOBILE_HEADER_BYTES) {
      fields.require(
          expectedPacketBytes == -1,
          "has an expected packet size before the complete header",
      )
    } else {
      val declared = unsigned16(packet, 4)
      val expected = MOBILE_PACKET_OVERHEAD_BYTES + declared
      fields.require(declared <= MOBILE_PACKET_DATA_BYTES, "has an oversized retained packet")
      fields.require(expectedPacketBytes == expected, "has an inconsistent retained packet size")
      fields.require(packetCount < expected, "retains a complete packet instead of committing it")
    }
    if (packetCount >= 2) {
      fields.require(
          (packet[0].toInt() and 0xff) == 0x99 && (packet[1].toInt() and 0xff) == 0x66,
          "has invalid retained packet magic",
      )
    }
    if (packetCount >= 4) {
      fields.require(packet[3].toInt() == 0, "has a non-zero retained reserved byte")
    }
    fields.require(
        (packetCount until packet.size).all { packet[it].toInt() == 0 },
        "has stale bytes beyond the retained parser prefix",
    )

    val configuration = fields.byteArray("configuration")
    fields.require(
        configuration.size == MOBILE_CONFIGURATION_BYTES,
        "must own exactly $MOBILE_CONFIGURATION_BYTES configuration bytes",
    )
    val response = fields.byteArray("responsePacket")
    fields.require(response.size <= MOBILE_PACKET_BYTES, "has an oversized response packet")
    if (response.isNotEmpty()) validateMobileOutputPacket(fields, response)
    val acknowledgement = fields.byteArray("acknowledgement")
    fields.require(
        acknowledgement.isEmpty() || acknowledgement.size == 2,
        "has an acknowledgement other than zero or two bytes",
    )
    if (acknowledgement.size == 2) {
      fields.require(
          (acknowledgement[0].toInt() and 0xff) == (fields.int("deviceId") or 0x80),
          "has an acknowledgement for another device ID",
      )
    }
    fields.nonNegativeLong("idlePhaseUnits")
    if (!fields.boolean("serialByteObserved")) {
      fields.require(fields.long("idlePhaseUnits") == 0L, "has idle time without serial input")
    }
    fields.require(
        packetCount == 0 || fields.boolean("serialByteObserved"),
        "retains parser bytes without serial input ownership",
    )
    fields.range("pendingPacketSlots", 0, MOBILE_PENDING_PACKET_SLOTS)
    if (externalIoAtCapture) {
      fields.require(
          fields.int("outcomeId") == MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED &&
              fields.int("errorId") == MOBILE_ERROR_EXTERNAL_IO_DISCONNECTED &&
              response.isEmpty() &&
              acknowledgement.isEmpty(),
          "does not normalize captured external I/O to the disconnected marker",
      )
    }
    validateMobileOutcome(fields, response, acknowledgement, externalIoAtCapture)
  }

  private fun validateMobileAdapterClock(fields: RecordFields, clock: ClockSpec) {
    val boundary = Math.multiplyExact(3L, clock.secondPhaseLimit())
    fields.range("idlePhaseUnits", 0L, boundary)
    fields.require(
        fields.long("idlePhaseUnits") % clock.secondPhaseUnitsPerTick() == 0L,
        "has an idle timer not aligned to a master tick",
    )
    if (fields.int("outcomeId") == MOBILE_OUTCOME_IDLE_BOUNDARY_WAIT) {
      fields.require(
          fields.int("packetCount") > 0 && fields.long("idlePhaseUnits") == boundary,
          "has an inconsistent exact idle-boundary result",
      )
    }
  }

  private fun validateMobileOutcome(
      fields: RecordFields,
      response: ByteArray,
      acknowledgement: ByteArray,
      externalIoAtCapture: Boolean,
  ) {
    val outcome = fields.int("outcomeId")
    fields.require(
        outcome != MOBILE_OUTCOME_BACKEND_PENDING,
        "contains live Mobile Adapter backend ownership",
    )
    val expectedError =
        when (outcome) {
          MOBILE_OUTCOME_CHECKSUM_ERROR -> MOBILE_ERROR_CHECKSUM
          MOBILE_OUTCOME_UNSUPPORTED_COMMAND -> MOBILE_ERROR_UNSUPPORTED_COMMAND
          MOBILE_OUTCOME_MAGIC_ERROR -> MOBILE_ERROR_INVALID_MAGIC
          MOBILE_OUTCOME_RESERVED_ERROR -> MOBILE_ERROR_RESERVED_VALUE
          MOBILE_OUTCOME_LENGTH_LIMIT -> MOBILE_ERROR_LENGTH_LIMIT
          MOBILE_OUTCOME_BUFFER_LIMIT -> MOBILE_ERROR_BUFFER_LIMIT
          MOBILE_OUTCOME_PENDING_LIMIT -> MOBILE_ERROR_PENDING_LIMIT
          MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED -> MOBILE_ERROR_EXTERNAL_IO_DISCONNECTED
          else -> MOBILE_ERROR_NONE
        }
    val backendError =
        outcome == MOBILE_OUTCOME_BACKEND_ERROR &&
            fields.int("errorId") in
                MOBILE_ERROR_BACKEND_BUSY..MOBILE_ERROR_BACKEND_RESPONSE_INVALID
    fields.require(
        backendError || fields.int("errorId") == expectedError,
        "has inconsistent outcome/error IDs",
    )

    val expectedAck =
        when (outcome) {
          MOBILE_OUTCOME_SESSION_STARTED -> 0x90
          MOBILE_OUTCOME_SESSION_ENDED -> 0x91
          MOBILE_OUTCOME_TELEPHONE_DIALLED -> 0x92
          MOBILE_OUTCOME_TELEPHONE_HUNG_UP -> 0x93
          MOBILE_OUTCOME_SESSION_RESET -> 0x96
          MOBILE_OUTCOME_TELEPHONE_STATUS -> 0x97
          MOBILE_OUTCOME_CONFIG_READ, MOBILE_OUTCOME_CONFIG_READ_BOUNDARY -> 0x99
          MOBILE_OUTCOME_CONFIG_WRITE -> 0x9a
          MOBILE_OUTCOME_ISP_LOGGED_IN -> 0xa1
          MOBILE_OUTCOME_ISP_LOGGED_OUT -> 0xa2
          MOBILE_OUTCOME_SERVICE_ERROR ->
              if (response.size == 10) (response[6].toInt() and 0xff) xor 0x80 else -1
          MOBILE_OUTCOME_BACKEND_ERROR ->
              if (response.size == 10) (response[6].toInt() and 0xff) xor 0x80 else -1
          MOBILE_OUTCOME_CHECKSUM_ERROR -> 0xf1
          MOBILE_OUTCOME_UNSUPPORTED_COMMAND -> 0xf0
          else -> -1
        }
    val internalErrorAck =
        (outcome == MOBILE_OUTCOME_BACKEND_ERROR ||
            outcome == MOBILE_OUTCOME_PENDING_LIMIT) &&
            response.isEmpty() &&
            acknowledgement.size == 2 &&
            (acknowledgement[1].toInt() and 0xff) == 0xf2
    val releasedAcklessBackendError =
        outcome == MOBILE_OUTCOME_BACKEND_ERROR &&
            response.isNotEmpty() &&
            acknowledgement.isEmpty()
    if (outcome == MOBILE_OUTCOME_BACKEND_ERROR) {
      fields.require(
          !(fields.int("errorId") == MOBILE_ERROR_BACKEND_BUSY && !internalErrorAck) &&
              !(fields.int("errorId") == MOBILE_ERROR_BACKEND_RESPONSE_INVALID &&
                  internalErrorAck),
          "has an inconsistent backend error shape",
      )
    }
    if (!internalErrorAck && !releasedAcklessBackendError) {
      fields.require(
          (expectedAck == -1) == acknowledgement.isEmpty(),
          "has inconsistent outcome/acknowledgement presence",
      )
    }
    if (!internalErrorAck && !releasedAcklessBackendError && expectedAck != -1) {
      fields.require(
          (acknowledgement[1].toInt() and 0xff) == expectedAck,
          "has an acknowledgement inconsistent with its outcome",
      )
    }

    val expectsResponse =
        outcome == MOBILE_OUTCOME_SESSION_STARTED ||
            outcome == MOBILE_OUTCOME_SESSION_ENDED ||
            outcome == MOBILE_OUTCOME_SESSION_RESET ||
            outcome == MOBILE_OUTCOME_TELEPHONE_DIALLED ||
            outcome == MOBILE_OUTCOME_TELEPHONE_HUNG_UP ||
            outcome == MOBILE_OUTCOME_TELEPHONE_STATUS ||
            outcome == MOBILE_OUTCOME_CONFIG_READ ||
            outcome == MOBILE_OUTCOME_CONFIG_READ_BOUNDARY ||
            outcome == MOBILE_OUTCOME_CONFIG_WRITE ||
            outcome == MOBILE_OUTCOME_ISP_LOGGED_IN ||
            outcome == MOBILE_OUTCOME_ISP_LOGGED_OUT ||
            outcome == MOBILE_OUTCOME_SERVICE_ERROR ||
            outcome == MOBILE_OUTCOME_BACKEND_RESPONSE ||
            outcome == MOBILE_OUTCOME_BACKEND_ERROR ||
            outcome == MOBILE_OUTCOME_BACKEND_REMOTE_CLOSED
    fields.require(
        (expectsResponse && !internalErrorAck) == response.isNotEmpty(),
        "has inconsistent outcome/response presence",
    )
    if (expectsResponse && !internalErrorAck) {
      if (
          expectedAck != -1 &&
              outcome != MOBILE_OUTCOME_SERVICE_ERROR &&
              outcome != MOBILE_OUTCOME_BACKEND_ERROR
      ) {
        fields.require(
            (response[2].toInt() and 0xff) == expectedAck,
            "has a response command inconsistent with its outcome",
        )
      }
      val data = response.copyOfRange(6, response.size - 2)
      when (outcome) {
        MOBILE_OUTCOME_SESSION_STARTED ->
            fields.require(
                data.contentEquals(MOBILE_BEGIN_SESSION_DATA),
                "has an invalid begin-session response",
            )
        MOBILE_OUTCOME_SESSION_ENDED,
        MOBILE_OUTCOME_SESSION_RESET,
        MOBILE_OUTCOME_TELEPHONE_DIALLED,
        MOBILE_OUTCOME_TELEPHONE_HUNG_UP,
        MOBILE_OUTCOME_ISP_LOGGED_OUT ->
            fields.require(data.isEmpty(), "has data in an empty response")
        MOBILE_OUTCOME_TELEPHONE_STATUS -> {
          val phase = fields.int("phaseId")
          val expected =
              if (phase == MOBILE_PHASE_SESSION) byteArrayOf(0, 0x4d, 0)
              else byteArrayOf(4, 0x4d, 0)
          fields.require(
              phase != MOBILE_PHASE_SLEEP && data.contentEquals(expected),
              "has an invalid telephone-status response",
          )
        }
        MOBILE_OUTCOME_CONFIG_READ, MOBILE_OUTCOME_CONFIG_READ_BOUNDARY ->
            validateMobileConfigurationResponse(fields, outcome, data)
        MOBILE_OUTCOME_CONFIG_WRITE ->
            fields.require(data.size == 1, "has an invalid configuration-write response")
        MOBILE_OUTCOME_ISP_LOGGED_IN ->
            fields.require(
                data.size == 12 &&
                    (data[0].toInt() and 0xff) == 127 &&
                    data[1].toInt() == 0 &&
                    data[2].toInt() == 0 &&
                    data[3].toInt() == 1 &&
                    data.copyOfRange(4, data.size).all { it.toInt() == 0 },
                "has an invalid ISP-login response",
            )
        MOBILE_OUTCOME_SERVICE_ERROR -> {
          fields.require(
              (response[2].toInt() and 0xff) == 0x6e,
              "has an invalid service-error response command",
          )
          validateMobileServiceError(fields, data)
        }
        MOBILE_OUTCOME_BACKEND_RESPONSE ->
            validateMobileBackendResponse(fields, response[2].toInt() and 0xff, data)
        MOBILE_OUTCOME_BACKEND_ERROR -> validateMobileBackendError(fields, data)
        MOBILE_OUTCOME_BACKEND_REMOTE_CLOSED ->
            fields.require(
                (response[2].toInt() and 0xff) == 0x9f && data.isEmpty(),
                "has an invalid remote-close response",
            )
      }
    }

    val phase = fields.int("phaseId")
    if (outcome == MOBILE_OUTCOME_SESSION_STARTED || outcome == MOBILE_OUTCOME_SESSION_RESET) {
      fields.require(phase == MOBILE_PHASE_SESSION, "has a session result while asleep")
    }
    if (outcome == MOBILE_OUTCOME_TELEPHONE_DIALLED) {
      fields.require(phase == MOBILE_PHASE_TELEPHONE, "has a dial result in the wrong phase")
    }
    if (outcome == MOBILE_OUTCOME_TELEPHONE_HUNG_UP) {
      fields.require(phase == MOBILE_PHASE_SESSION, "has a hang-up result in the wrong phase")
    }
    if (outcome == MOBILE_OUTCOME_ISP_LOGGED_IN) {
      fields.require(phase == MOBILE_PHASE_INTERNET, "has an ISP-login result in the wrong phase")
    }
    if (outcome == MOBILE_OUTCOME_ISP_LOGGED_OUT) {
      fields.require(phase == MOBILE_PHASE_TELEPHONE, "has an ISP-logout result in the wrong phase")
    }
    if (
        outcome == MOBILE_OUTCOME_BACKEND_RESPONSE ||
            outcome == MOBILE_OUTCOME_BACKEND_REMOTE_CLOSED ||
            outcome == MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED
    ) {
      fields.require(
          phase == MOBILE_PHASE_SESSION || phase == MOBILE_PHASE_INTERNET,
          "has a backend result in the wrong phase",
      )
    }
    if (outcome == MOBILE_OUTCOME_SESSION_ENDED ||
        outcome == MOBILE_OUTCOME_IDLE_TIMEOUT_RESET ||
        outcome == MOBILE_OUTCOME_CANCELLED) {
      fields.require(phase == MOBILE_PHASE_SLEEP, "has a terminal result while in session")
    }
    if (outcome == MOBILE_OUTCOME_IDLE_TIMEOUT_RESET || outcome == MOBILE_OUTCOME_CANCELLED) {
      fields.require(!fields.boolean("serialByteObserved"), "cleanup retained serial idle ownership")
    }
    if (phase != MOBILE_PHASE_SLEEP) {
      fields.require(
          fields.boolean("serialByteObserved"),
          "has an active session without serial input ownership",
      )
    }
    val commandDerivedOutcome =
        outcome != MOBILE_OUTCOME_NEED_MORE &&
            outcome != MOBILE_OUTCOME_PENDING_LIMIT &&
            outcome != MOBILE_OUTCOME_IDLE_TIMEOUT_RESET &&
            outcome != MOBILE_OUTCOME_CANCELLED
    if (commandDerivedOutcome) {
      fields.require(
          fields.boolean("serialByteObserved"),
          "has a command-derived result without serial input ownership",
      )
    }
    if (outcome == MOBILE_OUTCOME_PENDING_LIMIT) {
      fields.require(
          fields.int("pendingPacketSlots") == MOBILE_PENDING_PACKET_SLOTS,
          "has pending-limit outcome without both slots occupied",
      )
    }
    if (outcome != MOBILE_OUTCOME_NEED_MORE &&
        outcome != MOBILE_OUTCOME_IDLE_BOUNDARY_WAIT &&
        outcome != MOBILE_OUTCOME_PENDING_LIMIT &&
        outcome != MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED) {
      fields.require(fields.int("packetCount") == 0, "completed result retained parser bytes")
    }
    if (externalIoAtCapture) {
      fields.require(
          phase == MOBILE_PHASE_SESSION || phase == MOBILE_PHASE_INTERNET,
          "captured external I/O outside a backend-capable phase",
      )
    }
  }

  private fun validateMobileServiceError(fields: RecordFields, data: ByteArray) {
    fields.require(data.size == 2, "has an invalid service error response")
    if (data.size != 2) return
    val command = data[0].toInt() and 0xff
    val error = data[1].toInt() and 0xff
    val valid =
        when (command) {
          0x12 -> error in 0x01..0x03
          0x13, 0x17, 0x21, 0x22 -> error == 0x01 || error == 0x02
          else -> false
        }
    fields.require(valid, "has an invalid service error code")
    if (!valid) return
    val phase = fields.int("phaseId")
    val phaseValid =
        when (command) {
          0x12 -> if (error == 0x01) phase != MOBILE_PHASE_SESSION else phase == MOBILE_PHASE_SESSION
          0x13 ->
              if (error == 0x01) {
                phase != MOBILE_PHASE_TELEPHONE && phase != MOBILE_PHASE_INTERNET
              } else {
                phase == MOBILE_PHASE_TELEPHONE || phase == MOBILE_PHASE_INTERNET
              }
          0x17 -> if (error == 0x01) phase == MOBILE_PHASE_SLEEP else phase != MOBILE_PHASE_SLEEP
          0x21 -> if (error == 0x01) phase != MOBILE_PHASE_TELEPHONE else phase == MOBILE_PHASE_TELEPHONE
          0x22 -> if (error == 0x01) phase != MOBILE_PHASE_INTERNET else phase == MOBILE_PHASE_INTERNET
          else -> false
        }
    fields.require(phaseValid, "has a service error in an impossible phase")
  }

  private fun validateMobileBackendResponse(
      fields: RecordFields,
      command: Int,
      data: ByteArray,
  ) {
    when (command) {
      0xa4, 0xa6 ->
          fields.require(
              data.size == 1 && (data[0].toInt() and 0xff) < 2,
              "has an invalid close completion",
          )
      0xa8 -> fields.require(data.size == 4, "has an invalid DNS completion")
      else -> fields.require(false, "retains a completion that requires a live connection")
    }
  }

  private fun validateMobileBackendError(fields: RecordFields, data: ByteArray) {
    fields.require(data.size == 2, "has an invalid backend error response")
    if (data.size != 2) return
    val command = data[0].toInt() and 0xff
    val error = data[1].toInt() and 0xff
    val valid =
        when (command) {
          0x15 -> error == 0x00 || error == 0x01
          0x23, 0x25 -> error == 0x00 || error == 0x01 || error == 0x03
          0x24, 0x26 -> error <= 0x02
          0x28 -> error == 0x01 || error == 0x02
          else -> false
        }
    fields.require(valid, "has an invalid backend error code")
    if (valid && fields.int("errorId") == MOBILE_ERROR_BACKEND_RESPONSE_INVALID) {
      val expected =
          when (command) {
            0x15, 0x24, 0x26 -> 0x00
            0x23, 0x25 -> 0x03
            0x28 -> 0x02
            else -> -1
          }
      fields.require(
          error == expected,
          "has a backend-response-invalid error with the wrong protocol code",
      )
    }
  }

  private fun validateMobileOutputPacket(fields: RecordFields, response: ByteArray) {
    fields.require(
        response.size >= MOBILE_PACKET_OVERHEAD_BYTES &&
            (response[0].toInt() and 0xff) == 0x99 &&
            (response[1].toInt() and 0xff) == 0x66 &&
            response[3].toInt() == 0,
        "has invalid response framing",
    )
    val length = unsigned16(response, 4)
    fields.require(
        length <= MOBILE_PACKET_DATA_BYTES &&
            response.size == MOBILE_PACKET_OVERHEAD_BYTES + length,
        "has invalid response length",
    )
    var checksum = 0
    for (index in 2 until 6 + length) {
      checksum = (checksum + (response[index].toInt() and 0xff)) and 0xffff
    }
    fields.require(unsigned16(response, 6 + length) == checksum, "has invalid response checksum")
  }

  private fun validateMobileConfigurationResponse(
      fields: RecordFields,
      outcome: Int,
      data: ByteArray,
  ) {
    fields.require(
        data.size in 1..MOBILE_CONFIGURATION_OPERATION_BYTES + 1,
        "has an invalid configuration response length",
    )
    val offset = data[0].toInt() and 0xff
    val requested = data.size - 1
    fields.require(
        offset + requested <= MOBILE_CONFIGURATION_BYTES,
        "has an out-of-bounds configuration response",
    )
    fields.require(
        (outcome == MOBILE_OUTCOME_CONFIG_READ_BOUNDARY) ==
            (requested == MOBILE_CONFIGURATION_OPERATION_BYTES),
        "has an inconsistent configuration boundary outcome",
    )
    // The response is an immutable snapshot of configuration bytes at command completion. A
    // controller may replace the live configuration before the packet is shifted out, so only
    // framing/range semantics can be validated against the current engine state here.
  }

  private fun unsigned16(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

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
    target["eu.rekawek.coffeegb.core.memory.cart.type.XploderGb\$XploderGbState"] =
        constrained("Xploder exposes byte ROM banking, sixteen RAM banks, and a fixed 128 KiB RAM image.") {
          it.recordType("batteryMemento", MEMORY_BATTERY_STATE, FILE_BATTERY_STATE)
          it.require(it.intArray("ram").size == 16 * 0x2000, "must have sixteen 8 KiB RAM banks")
          it.intValues("ram", 0, 0xff)
          it.range("selectedRomBank", 0, 0xff); it.range("selectedRamBank", 0, 0x0f)
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Vf001Zook\$Vf001ZookState"] =
        constrained("Zook's VF001 shift window and four-write bank-port phase are bounded with its nested MBC5 state.") {
          it.recordType("delegateMemento", MBC5_STATE)
          it.require(it.intArray("stream").size == 32, "must have a 32-byte shift window")
          it.intValues("stream", 0, 0xff)
          it.range("streamLength", 0, 32); it.range("bankPortRun", 0, 3)
          it.require(it.int("bankPortRun") <= it.int("streamLength"), "has a bank-port run longer than its stream")
        }
    target["eu.rekawek.coffeegb.core.memory.cart.type.Vf001General\$Vf001GeneralState"] =
        constrained("VF001 config, injection, replacement, and nested MBC5 state retain their protocol bounds.") {
          it.recordType("delegateMemento", MBC5_STATE)
          it.range("runningValue", 0, 0xff); it.range("cur6000", 0, 0xff)
          it.require(it.intArray("cur700x").size == 15, "must have fifteen config registers")
          it.intValues("cur700x", 0, 0xff)
          it.range("sequenceStartBank", 0, 0xff); it.range("sequenceStartAddress", 0, 0xffff)
          it.range("sequenceLength", 0, 4)
          it.require(it.intArray("sequence").size == 4, "must have a four-byte injection buffer")
          it.intValues("sequence", 0, 0xff)
          it.range("sequenceBytesLeft", 0, it.int("sequenceLength"))
          it.range("replacementStartAddress", 0, 0xffff)
          it.range("replacementSourceBank", 0, 0xff); it.range("selectedRomBank", 0, 0x1ff)
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
  private const val MOBILE_PACKET_DATA_BYTES = 254
  private const val MOBILE_PACKET_BYTES = 262
  private const val MOBILE_HEADER_BYTES = 6
  private const val MOBILE_PACKET_OVERHEAD_BYTES = 8
  private const val MOBILE_CONFIGURATION_BYTES = 256
  private const val MOBILE_CONFIGURATION_OPERATION_BYTES = 128
  private const val MOBILE_PENDING_PACKET_SLOTS = 2
  private const val MOBILE_IDLE_BYTE = 0xd2
  private const val MOBILE_WIRE_RECEIVE_REQUEST = 1
  private const val MOBILE_WIRE_REQUEST_ACK_DEVICE = 2
  private const val MOBILE_WIRE_REQUEST_ACK_COMMAND = 3
  private const val MOBILE_WIRE_RESPONSE_TURNAROUND = 4
  private const val MOBILE_WIRE_RESPONSE_WAIT = 5
  private const val MOBILE_WIRE_RESPONSE_STREAM = 6
  private const val MOBILE_WIRE_RESPONSE_ACK_DEVICE = 7
  private const val MOBILE_WIRE_RESPONSE_ACK_COMMAND = 8
  private const val MOBILE_WIRE_RESPONSE_READY = 9
  private const val MOBILE_WIRE_NORMALIZED_DISCONNECT = 11
  private const val MOBILE_MAX_RESPONSE_RETRIES = 4
  private const val MOBILE_PHASE_SLEEP = 1
  private const val MOBILE_PHASE_SESSION = 2
  private const val MOBILE_PHASE_TELEPHONE = 3
  private const val MOBILE_PHASE_INTERNET = 4
  private const val MOBILE_OUTCOME_NEED_MORE = 1
  private const val MOBILE_OUTCOME_SESSION_STARTED = 2
  private const val MOBILE_OUTCOME_SESSION_ENDED = 3
  private const val MOBILE_OUTCOME_SESSION_RESET = 4
  private const val MOBILE_OUTCOME_CHECKSUM_ERROR = 5
  private const val MOBILE_OUTCOME_IDLE_TIMEOUT_RESET = 6
  private const val MOBILE_OUTCOME_IDLE_BOUNDARY_WAIT = 7
  private const val MOBILE_OUTCOME_CONFIG_READ = 8
  private const val MOBILE_OUTCOME_CONFIG_READ_BOUNDARY = 9
  private const val MOBILE_OUTCOME_UNSUPPORTED_COMMAND = 10
  private const val MOBILE_OUTCOME_MAGIC_ERROR = 11
  private const val MOBILE_OUTCOME_RESERVED_ERROR = 12
  private const val MOBILE_OUTCOME_LENGTH_LIMIT = 13
  private const val MOBILE_OUTCOME_BUFFER_LIMIT = 14
  private const val MOBILE_OUTCOME_TIME_REGRESSION = 15
  private const val MOBILE_OUTCOME_CANCELLED = 16
  private const val MOBILE_OUTCOME_PENDING_LIMIT = 17
  private const val MOBILE_OUTCOME_CONFIG_WRITE = 18
  private const val MOBILE_OUTCOME_BACKEND_PENDING = 19
  private const val MOBILE_OUTCOME_BACKEND_RESPONSE = 20
  private const val MOBILE_OUTCOME_BACKEND_ERROR = 21
  private const val MOBILE_OUTCOME_BACKEND_REMOTE_CLOSED = 22
  private const val MOBILE_OUTCOME_EXTERNAL_IO_DISCONNECTED = 23
  private const val MOBILE_OUTCOME_TELEPHONE_DIALLED = 24
  private const val MOBILE_OUTCOME_TELEPHONE_HUNG_UP = 25
  private const val MOBILE_OUTCOME_TELEPHONE_STATUS = 26
  private const val MOBILE_OUTCOME_ISP_LOGGED_IN = 27
  private const val MOBILE_OUTCOME_ISP_LOGGED_OUT = 28
  private const val MOBILE_OUTCOME_SERVICE_ERROR = 29
  private const val MOBILE_ERROR_NONE = 0
  private const val MOBILE_ERROR_INVALID_MAGIC = 1
  private const val MOBILE_ERROR_RESERVED_VALUE = 2
  private const val MOBILE_ERROR_LENGTH_LIMIT = 3
  private const val MOBILE_ERROR_CHECKSUM = 4
  private const val MOBILE_ERROR_UNSUPPORTED_COMMAND = 5
  private const val MOBILE_ERROR_BUFFER_LIMIT = 6
  private const val MOBILE_ERROR_PENDING_LIMIT = 8
  private const val MOBILE_ERROR_BACKEND_BUSY = 9
  private const val MOBILE_ERROR_BACKEND_RESPONSE_INVALID = 11
  private const val MOBILE_ERROR_EXTERNAL_IO_DISCONNECTED = 12
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
  private const val MOBILE_ADAPTER_ENGINE_STATE =
      "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineState"
  private const val MOBILE_ADAPTER_NETWORK_ENGINE_STATE =
      "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineNetworkState"
  private val MOBILE_BEGIN_SESSION_DATA =
      byteArrayOf(0x4e, 0x49, 0x4e, 0x54, 0x45, 0x4e, 0x44, 0x4f)
  private val REGISTERED_PATCH_TYPES =
      setOf(
          "eu.rekawek.coffeegb.core.genie.Genie\$GameGeniePatchState",
          "eu.rekawek.coffeegb.core.genie.Genie\$GameSharkPatchState",
      )
  private val DELAYED_PPU_REGISTERS = setOf(0xff40, 0xff43, 0xff47, 0xff4b)
}
