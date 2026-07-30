package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.BarcodeBoyRuntimeState
import eu.rekawek.coffeegb.controller.state.BooleanArrayState
import eu.rekawek.coffeegb.controller.state.BooleanState
import eu.rekawek.coffeegb.controller.state.BytesState
import eu.rekawek.coffeegb.controller.state.DmgFifoRuntimeState
import eu.rekawek.coffeegb.controller.state.DmgPixelFifoRuntimeState
import eu.rekawek.coffeegb.controller.state.EnumState
import eu.rekawek.coffeegb.controller.state.Float64State
import eu.rekawek.coffeegb.controller.state.HeldButtonState
import eu.rekawek.coffeegb.controller.state.Int32ArrayState
import eu.rekawek.coffeegb.controller.state.Int32MapState
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.Int64ArrayState
import eu.rekawek.coffeegb.controller.state.Int64State
import eu.rekawek.coffeegb.controller.state.ListState
import eu.rekawek.coffeegb.controller.state.MachineHardwareState
import eu.rekawek.coffeegb.controller.state.Mbc3RtcRuntimeState
import eu.rekawek.coffeegb.controller.state.NoSerialRuntimeState
import eu.rekawek.coffeegb.controller.state.NullState
import eu.rekawek.coffeegb.controller.state.ObjectArrayState
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SerialPeripheralState
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateField
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateValue
import eu.rekawek.coffeegb.controller.state.StringState
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical v1 replay-checkpoint hashing, independent of JVM serialization and host services. */
object ReplayStateHasher {

  private const val HASH_SCHEMA_VERSION = 1
  private const val HASH_DOMAIN_PREFIX = "coffee-gb/replay-state"

  private val CPU_FIELDS =
      listOf(
          "cpuMemento",
          "interruptManagerMemento",
          "timerMemento",
          "speedModeMemento",
          "speedSwitchTailTicks",
          "speedSwitchClockPhaseShifted",
      )

  private val MEMORY_FIELDS =
      listOf(
          "biosShadowMemento",
          "mmuMemento",
          "oamRamMemento",
          "dmaMemento",
          "hdmaMemento",
          "genieMemento",
      )

  private val PPU_FIELDS =
      listOf(
          "gpuMemento",
          "statRegisterMemento",
          "displayMemento",
          "superGameboyMemento",
          "backgroundMemento",
          "vRamTransferMemento",
          "sgbDisplayMemento",
          "requestScreenRefresh",
          "lcdDisabled",
          "lcdOffTicks",
          "blankCgbBootTilePending",
          "clearBootTilemapPending",
          "clearCgbBootOamShadowPending",
      )

  private val APU_FIELDS = listOf("soundMemento")

  private val MAPPER_FIELDS = listOf("cartridgeMemento", "codeBreakerRumbleMemento")

  private val SERIAL_FIELDS = listOf("serialPortMemento", "infraredPortMemento")

  private val INPUT_FIELDS = listOf("joypadMemento")

  /** Captures one detached StateFile-v2 session plus its replay-visible physical input latch. */
  fun hash(session: Session): ReplayStateHashes =
      hash(StateCodec.captureVersion2(session), session.gameboy.sampledPlayerInput)

  /**
   * Hashes a detached session. The full digest is the canonical, uncompressed StateFile-v2 byte
   * stream with diagnostics removed; subsystem digests use the explicit walker below.
   */
  fun hash(file: StateFile): ReplayStateHashes = hash(file, PlayerInputSnapshot.released())

  private fun hash(
      file: StateFile,
      sampledInput: PlayerInputSnapshot,
  ): ReplayStateHashes {
    val session =
        (file.root as? SessionStateRoot)?.session
            ?: incompatible(
                ReplayCompatibilityReason.UNSUPPORTED_STATE_ROOT,
                "Replay checkpoints require a detached session root",
            )
    val canonical =
        StateFile(
            file.identities,
            file.root,
            diagnostics = null,
            formatVersion = StateCodec.LATEST_FORMAT_VERSION,
        )
    val canonicalState = StateCodec.encode(canonical, StateCompression.NONE)
    val full =
        CanonicalHasher("full").run {
          namedBytes("stateFileV2", canonicalState)
          writePhysicalInput(sampledInput)
          finish()
        }
    val root = session.machine.root

    return ReplayStateHashes(
        full,
        hashFields("cpu", root, CPU_FIELDS),
        hashFields("memory", root, MEMORY_FIELDS),
        hashFields("ppu", root, PPU_FIELDS) { writeDmgFifo(session.machine.dmgFifoRuntime) },
        hashFields("apu", root, APU_FIELDS),
        hashFields("mapper", root, MAPPER_FIELDS) {
          namedInt("hardware", hardwareId(session.machine.hardware))
          namedRtc("primaryRtcRuntime", session.machine.rtcRuntime.primary)
          namedRtc("slotRtcRuntime", session.machine.rtcRuntime.slot)
        },
        hashFields("serial", root, SERIAL_FIELDS) {
          namedInt("serialPeripheral", serialPeripheralId(session.serialPeripheral))
          namedState("serialState", session.serialState)
          writeSerialRuntime(session)
        },
        hashFields("input", root, INPUT_FIELDS) {
          writeHeldButtons(session.heldButtons)
          writePhysicalInput(sampledInput)
        },
    )
  }

  private fun hashFields(
      domain: String,
      root: RecordState,
      fieldNames: List<String>,
      supplement: CanonicalHasher.() -> Unit = {},
  ): ByteArray =
      CanonicalHasher(domain).run {
        fieldNames.forEach { name -> namedState(name, root.requiredField(name)) }
        supplement()
        finish()
      }

  private fun CanonicalHasher.writeDmgFifo(runtime: DmgFifoRuntimeState?) {
    namedBoolean("dmgFifoPresent", runtime != null)
    runtime ?: return
    namedDmgPixelFifo("dmgFifoTiming", runtime.timing)
    namedDmgPixelFifo("dmgFifoOutput", runtime.output)
  }

  private fun CanonicalHasher.namedDmgPixelFifo(
      name: String,
      runtime: DmgPixelFifoRuntimeState,
  ) {
    string(name)
    int(runtime.linePixels)
    int(runtime.outCount)
    int(runtime.firstEntry)
    int(runtime.firstBgp)
    int(runtime.firstObp0)
    int(runtime.firstObp1)
  }

  private fun CanonicalHasher.namedRtc(name: String, runtime: Mbc3RtcRuntimeState?) {
    string(name)
    boolean(runtime != null)
    runtime ?: return
    boolean(runtime.emulationPaused)
    long(runtime.pauseStartedMillis)
  }

  private fun CanonicalHasher.writeSerialRuntime(session: SessionState) {
    string("serialRuntime")
    when (val runtime = session.serialRuntime) {
      NoSerialRuntimeState -> byte(0)
      is BarcodeBoyRuntimeState -> {
        byte(1)
        boolean(runtime.transferArmed)
        val pending = runtime.copyPending()
        boolean(pending != null)
        pending?.let {
          int(it.size)
          it.forEach(::int)
        }
      }
    }
  }

  private fun CanonicalHasher.writeHeldButtons(buttons: List<HeldButtonState>) {
    string("heldButtons")
    val ids = buttons.map(::heldButtonId).sorted()
    int(ids.size)
    ids.forEach(::byte)
  }

  private fun CanonicalHasher.writePhysicalInput(input: PlayerInputSnapshot) {
    string("physicalInputP1P4")
    int(input.players().size)
    input.players().forEach { buttons -> byte(JoypadButtonMask.fromButtons(buttons)) }
  }

  private fun RecordState.requiredField(name: String): StateValue {
    val matches = fields.filter { it.name == name }
    if (matches.size != 1) {
      incompatible(
          ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
          "Detached Game Boy root must contain exactly one $name field",
      )
    }
    return matches.single().value
  }

  private fun hardwareId(value: MachineHardwareState): Int =
      when (value) {
        MachineHardwareState.DMG -> 1
        MachineHardwareState.CGB -> 2
        MachineHardwareState.SGB -> 3
      }

  private fun serialPeripheralId(value: SerialPeripheralState): Int =
      when (value) {
        SerialPeripheralState.NONE -> 0
        SerialPeripheralState.BYTE_RECEIVER -> 1
        SerialPeripheralState.PEER_TO_PEER -> 2
        SerialPeripheralState.PRINTER -> 3
        SerialPeripheralState.GPS_RECEIVER -> 4
        SerialPeripheralState.BARCODE_BOY -> 5
        SerialPeripheralState.FOUR_PLAYER_ADAPTER -> 6
        SerialPeripheralState.MOBILE_ADAPTER_GB -> 7
      }

  private fun heldButtonId(value: HeldButtonState): Int =
      when (value) {
        HeldButtonState.RIGHT -> 0
        HeldButtonState.LEFT -> 1
        HeldButtonState.UP -> 2
        HeldButtonState.DOWN -> 3
        HeldButtonState.A -> 4
        HeldButtonState.B -> 5
        HeldButtonState.SELECT -> 6
        HeldButtonState.START -> 7
      }

  /** Explicit canonical encoding for every detached [StateValue] kind. */
  private class CanonicalHasher(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
      string(HASH_DOMAIN_PREFIX)
      int(HASH_SCHEMA_VERSION)
      string(domain)
    }

    fun namedState(name: String, value: StateValue) {
      string(name)
      state(value)
    }

    fun namedInt(name: String, value: Int) {
      string(name)
      int(value)
    }

    fun namedBoolean(name: String, value: Boolean) {
      string(name)
      boolean(value)
    }

    fun namedBytes(name: String, value: ByteArray) {
      string(name)
      bytes(value)
    }

    fun state(value: StateValue) {
      when (value) {
        NullState -> byte(0)
        is Int32State -> {
          byte(1)
          int(value.value)
        }
        is Int64State -> {
          byte(2)
          long(value.value)
        }
        is BooleanState -> {
          byte(3)
          boolean(value.value)
        }
        is Float64State -> {
          byte(4)
          long(java.lang.Double.doubleToRawLongBits(value.value))
        }
        is StringState -> {
          byte(5)
          string(value.value)
        }
        is EnumState -> {
          byte(6)
          int(value.typeId)
          int(value.ordinal)
        }
        is RecordState -> {
          byte(7)
          int(value.typeId)
          val fields = value.fields.sortedBy(StateField::name)
          int(fields.size)
          fields.forEach {
            string(it.name)
            state(it.value)
          }
        }
        is BytesState -> {
          byte(8)
          bytes(value.copyValue())
        }
        is Int32ArrayState -> {
          byte(9)
          val values = value.copyValue()
          int(values.size)
          values.forEach(::int)
        }
        is Int64ArrayState -> {
          byte(10)
          val values = value.copyValue()
          int(values.size)
          values.forEach(::long)
        }
        is BooleanArrayState -> {
          byte(11)
          val values = value.copyValue()
          int(values.size)
          values.forEach(::boolean)
        }
        is ObjectArrayState -> {
          byte(12)
          states(value.values)
        }
        is ListState -> {
          byte(13)
          states(value.values)
        }
        is Int32MapState -> {
          byte(14)
          val entries = value.entries.sortedBy { it.key }
          int(entries.size)
          entries.forEach {
            int(it.key)
            state(it.value)
          }
        }
      }
    }

    private fun states(values: List<StateValue>) {
      int(values.size)
      values.forEach(::state)
    }

    fun string(value: String) = bytes(value.toByteArray(StandardCharsets.UTF_8))

    fun bytes(value: ByteArray) {
      int(value.size)
      digest.update(value)
    }

    fun boolean(value: Boolean) = byte(if (value) 1 else 0)

    fun byte(value: Int) {
      digest.update(value.toByte())
    }

    fun int(value: Int) {
      for (shift in 24 downTo 0 step 8) byte(value ushr shift)
    }

    fun long(value: Long) {
      for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt())
    }

    fun finish(): ByteArray = digest.digest()
  }

  private fun incompatible(reason: ReplayCompatibilityReason, message: String): Nothing =
      throw ReplayCompatibilityException(reason, message)
}
