package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.gpu.DmgPixelFifo
import eu.rekawek.coffeegb.core.gpu.Gpu
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.state.ComponentState
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.IOException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.GenericArrayType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Fixed-width, service-free value kinds used by the Phase-1 detached state seam. */
enum class StateKind {
  NULL,
  INT32,
  INT64,
  BOOLEAN,
  FLOAT64,
  STRING,
  ENUM,
  RECORD,
  BYTES,
  INT32_ARRAY,
  INT64_ARRAY,
  BOOLEAN_ARRAY,
  OBJECT_ARRAY,
  LIST,
  INT32_MAP,
}

/**
 * Immutable value in a detached machine snapshot.
 *
 * Values contain only fixed-width JVM primitives, explicit audited type IDs, strings, and
 * recursively owned immutable containers. Threads, callbacks, event buses, streams, clocks,
 * displays, and other host services cannot be represented by this model.
 */
sealed interface StateValue {
  val kind: StateKind
}

data object NullState : StateValue {
  override val kind = StateKind.NULL
}

data class Int32State(val value: Int) : StateValue {
  override val kind = StateKind.INT32
}

data class Int64State(val value: Long) : StateValue {
  override val kind = StateKind.INT64
}

data class BooleanState(val value: Boolean) : StateValue {
  override val kind = StateKind.BOOLEAN
}

data class Float64State(val value: Double) : StateValue {
  override val kind = StateKind.FLOAT64
}

data class StringState(val value: String) : StateValue {
  init {
    DetachedValueBounds.requireString(value) { message -> throw IllegalArgumentException(message) }
  }

  override val kind = StateKind.STRING
}

data class EnumState(val typeId: Int, val ordinal: Int) : StateValue {
  override val kind = StateKind.ENUM
}

data class StateField(val name: String, val value: StateValue)

class RecordState(val typeId: Int, fields: Collection<StateField>) : StateValue {
  override val kind = StateKind.RECORD
  val fields: List<StateField> = Collections.unmodifiableList(ArrayList(fields))

  override fun equals(other: Any?): Boolean =
      other is RecordState && typeId == other.typeId && fields == other.fields

  override fun hashCode(): Int = 31 * typeId + fields.hashCode()

  override fun toString(): String = "RecordState(typeId=$typeId, fields=$fields)"
}

sealed class PrimitiveArrayState<T>(
    final override val kind: StateKind,
) : StateValue {
  abstract val size: Int
  abstract fun copyValue(): T
}

class BytesState(value: ByteArray) : PrimitiveArrayState<ByteArray>(StateKind.BYTES) {
  init {
    DetachedValueBounds.requireArray(value.size.toLong(), Byte.SIZE_BYTES.toLong()) { message ->
      throw IllegalArgumentException(message)
    }
  }

  private val owned = value.clone()
  override val size: Int get() = owned.size
  override fun copyValue(): ByteArray = owned.clone()
  override fun equals(other: Any?): Boolean = other is BytesState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class Int32ArrayState(value: IntArray) : PrimitiveArrayState<IntArray>(StateKind.INT32_ARRAY) {
  init {
    DetachedValueBounds.requireArray(value.size.toLong(), Int.SIZE_BYTES.toLong()) { message ->
      throw IllegalArgumentException(message)
    }
  }

  private val owned = value.clone()
  override val size: Int get() = owned.size
  override fun copyValue(): IntArray = owned.clone()
  override fun equals(other: Any?): Boolean =
      other is Int32ArrayState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class Int64ArrayState(value: LongArray) : PrimitiveArrayState<LongArray>(StateKind.INT64_ARRAY) {
  init {
    DetachedValueBounds.requireArray(value.size.toLong(), Long.SIZE_BYTES.toLong()) { message ->
      throw IllegalArgumentException(message)
    }
  }

  private val owned = value.clone()
  override val size: Int get() = owned.size
  override fun copyValue(): LongArray = owned.clone()
  override fun equals(other: Any?): Boolean =
      other is Int64ArrayState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class BooleanArrayState(value: BooleanArray) :
    PrimitiveArrayState<BooleanArray>(StateKind.BOOLEAN_ARRAY) {
  init {
    DetachedValueBounds.requireArray(value.size.toLong(), 1) { message ->
      throw IllegalArgumentException(message)
    }
  }

  private val owned = value.clone()
  override val size: Int get() = owned.size
  override fun copyValue(): BooleanArray = owned.clone()
  override fun equals(other: Any?): Boolean =
      other is BooleanArrayState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

sealed class ValuesState(
    final override val kind: StateKind,
    values: Collection<StateValue>,
) : StateValue {
  val values: List<StateValue> = Collections.unmodifiableList(ArrayList(values))

  protected fun valuesEqual(other: ValuesState): Boolean = values == other.values
  protected fun valuesHash(): Int = values.hashCode()
}

class ObjectArrayState(values: Collection<StateValue>) :
    ValuesState(StateKind.OBJECT_ARRAY, values) {
  override fun equals(other: Any?): Boolean = other is ObjectArrayState && valuesEqual(other)
  override fun hashCode(): Int = valuesHash()
}

class ListState(values: Collection<StateValue>) : ValuesState(StateKind.LIST, values) {
  override fun equals(other: Any?): Boolean = other is ListState && valuesEqual(other)
  override fun hashCode(): Int = valuesHash()
}

data class Int32MapEntry(val key: Int, val value: StateValue)

class Int32MapState(entries: Collection<Int32MapEntry>) : StateValue {
  override val kind = StateKind.INT32_MAP
  val entries: List<Int32MapEntry> =
      Collections.unmodifiableList(ArrayList(entries).also { it.sortBy(Int32MapEntry::key) })

  override fun equals(other: Any?): Boolean = other is Int32MapState && entries == other.entries
  override fun hashCode(): Int = entries.hashCode()
}

/** MBC3 pause bookkeeping kept outside the pinned legacy Java-serialization shape. */
data class Mbc3RtcRuntimeState(val emulationPaused: Boolean, val pauseStartedMillis: Long)

/** Explicit physical cartridge locations; neither location retains its TimeSource service. */
data class CartridgeRtcRuntimeState(
    val primary: Mbc3RtcRuntimeState?,
    val slot: Mbc3RtcRuntimeState?,
)

/** First-pixel and window-rewind bookkeeping omitted by the pinned legacy DMG FIFO record. */
data class DmgPixelFifoRuntimeState(
    val linePixels: Int,
    val outCount: Int,
    val firstEntry: Int,
    val firstBgp: Int,
    val firstObp0: Int,
    val firstObp1: Int,
)

/** Explicit timing-skeleton and pixel-producing FIFO ownership. Null on native CGB hardware. */
data class DmgFifoRuntimeState(
    val timing: DmgPixelFifoRuntimeState,
    val output: DmgPixelFifoRuntimeState,
)

enum class MachineHardwareState {
  DMG,
  CGB,
  SGB,
}

sealed interface SerialRuntimeState

data object NoSerialRuntimeState : SerialRuntimeState

class BarcodeBoyRuntimeState(
    val transferArmed: Boolean,
    pending: IntArray?,
) : SerialRuntimeState {
  init {
    pending?.let {
      DetachedValueBounds.requireArray(it.size.toLong(), Int.SIZE_BYTES.toLong()) { message ->
        throw IllegalArgumentException(message)
      }
    }
  }

  private val ownedPending = pending?.clone()
  val pendingSize: Int? get() = ownedPending?.size
  fun copyPending(): IntArray? = ownedPending?.clone()

  override fun equals(other: Any?): Boolean =
      other is BarcodeBoyRuntimeState &&
          transferArmed == other.transferArmed &&
          when {
            ownedPending == null -> other.ownedPending == null
            other.ownedPending == null -> false
            else -> ownedPending.contentEquals(other.ownedPending)
          }

  override fun hashCode(): Int = 31 * transferArmed.hashCode() + (ownedPending?.contentHashCode() ?: 0)
}

/** One complete, deeply owned Game Boy machine state. */
class MachineState internal constructor(
    val root: RecordState,
    val rtcRuntime: CartridgeRtcRuntimeState,
    val hardware: MachineHardwareState,
    val dmgFifoRuntime: DmgFifoRuntimeState?,
) {
  fun recordCount(className: String): Int =
      StateGraph.countRecords(root, className)

  override fun equals(other: Any?): Boolean =
      other is MachineState &&
          root == other.root &&
          rtcRuntime == other.rtcRuntime &&
          hardware == other.hardware &&
          dmgFifoRuntime == other.dmgFifoRuntime
  override fun hashCode(): Int =
      arrayOf(root, rtcRuntime, hardware, dmgFifoRuntime).contentHashCode()
}

enum class HeldButtonState {
  RIGHT,
  LEFT,
  UP,
  DOWN,
  A,
  B,
  SELECT,
  START,
}

enum class SerialPeripheralState {
  NONE,
  BYTE_RECEIVER,
  PEER_TO_PEER,
  PRINTER,
  GPS_RECEIVER,
  BARCODE_BOY,
  FOUR_PLAYER_ADAPTER,
}

/** Detached state owned by one controller Session, including event/protocol-owned P1 input. */
class SessionState internal constructor(
    val machine: MachineState,
    val serialPeripheral: SerialPeripheralState,
    val serialState: StateValue,
    val serialRuntime: SerialRuntimeState,
    heldButtons: Collection<HeldButtonState>,
) {
  val heldButtons: List<HeldButtonState> = Collections.unmodifiableList(ArrayList(heldButtons))

  override fun equals(other: Any?): Boolean =
      other is SessionState &&
          machine == other.machine &&
          serialPeripheral == other.serialPeripheral &&
          serialState == other.serialState &&
          serialRuntime == other.serialRuntime &&
          heldButtons == other.heldButtons

  override fun hashCode(): Int =
      arrayOf(machine, serialPeripheral, serialState, serialRuntime, heldButtons).contentHashCode()
}

enum class LinkedTopologyState {
  NORMAL,
  FOUR_PLAYER_ADAPTER,
}

data class LinkedPlayerState(val player: Int, val session: SessionState?)

/** Service-free topology/checkpoint DTO for linked-session ownership. */
class LinkedSessionState(
    val frame: Long,
    val localPlayer: Int,
    val topology: LinkedTopologyState,
    players: Collection<LinkedPlayerState>,
) {
  val players: List<LinkedPlayerState> = Collections.unmodifiableList(ArrayList(players))

  override fun equals(other: Any?): Boolean =
      other is LinkedSessionState &&
          frame == other.frame &&
          localPlayer == other.localPlayer &&
          topology == other.topology &&
          players == other.players

  override fun hashCode(): Int =
      arrayOf(frame, localPlayer, topology, players).contentHashCode()
}

class StateCaptureException(message: String, cause: Throwable? = null) : IOException(message, cause)

class StateApplyException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal enum class ApplyStage {
  BEFORE_LIVE_MUTATION,
  AFTER_MACHINE_MUTATION,
}

internal data class PreparedMachineState(
    val componentState: ComponentState<Gameboy>,
    val rtcRuntime: Gameboy.RtcRuntimeState,
    val dmgFifoRuntime: Gpu.DmgFifoRuntimeState?,
)

internal data class PreparedSessionState(
    val machine: PreparedMachineState,
    val serialState: ComponentState<SerialEndpoint>?,
    val serialRuntime: SerialRuntimeState,
    val heldButtons: Set<Button>,
)

/**
 * Adapter between explicit component states and the immutable detached model.
 * Apply first checks target-dependent structure and explicit nullability, reconstructs the full
 * candidate, and runs every registered record's semantic policy before the first live mutation.
 * Rollback remains the guard for unexpected live-component failures.
 * This adapter deliberately remains independent of bytes; [StateCodec] wraps it with StateFile v1.
 */
internal object DetachedStateAdapter {

  fun capture(gameboy: Gameboy): MachineState =
      MachineState(
          StateGraph.captureRoot(gameboy.captureState(), GAMEBOY_ROOT),
          gameboy.captureRtcRuntimeState().toDetached(),
          gameboy.hardwareProfile.toMachineHardware(),
          gameboy.captureDmgFifoRuntimeState().toDetached(),
      )

  /**
   * Imports a strict-reader legacy root through target-aware structural preflight and rollback.
   *
   * Historical Java files do not own Phase-1 runtime supplements, so the current machine's RTC
   * pause and DMG FIFO supplements remain attached. The released monotonic FIFO delay count is
   * explicitly normalized to current ring occupancy; the complete candidate then passes current
   * record, nullability, array, and semantic validation before mutation. Unexpected legacy restore
   * failures roll the whole machine back.
   */
  fun applyLegacyState(
      gameboy: Gameboy,
      legacyState: Memento<Gameboy>,
      probeAfterLegacyMutation: (() -> Unit)? = null,
  ) {
    val current = capture(gameboy)
    val legacyRoot =
        normalizeLegacyFifoOccupancy(
            StateGraph.captureLegacyRoot(legacyState, LEGACY_GAMEBOY_ROOT)) as RecordState
    StateGraph.validateCompatible(legacyRoot, current.root, "legacy machine")
    val normalized = StateGraph.restoreRoot(legacyRoot, GAMEBOY_ROOT)
    StateSemantics.validateForClock(normalized, gameboy.clockSpec)
    @Suppress("UNCHECKED_CAST")
    val componentState = normalized as ComponentState<Gameboy>
    val rollback = prepare(gameboy, current)
    val candidate =
        PreparedMachineState(
            componentState,
            current.rtcRuntime.toCore(),
            current.dmgFifoRuntime.toCore(),
        )
    try {
      gameboy.restoreState(candidate.componentState)
      probeAfterLegacyMutation?.invoke()
      gameboy.restoreDmgFifoRuntimeState(candidate.dmgFifoRuntime)
      gameboy.restoreRtcRuntimeState(candidate.rtcRuntime)
    } catch (failure: Throwable) {
      try {
        commit(gameboy, rollback)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Legacy machine state could not be applied atomically", failure)
    }
  }

  /**
   * Released 1.7.13/1.7.14 files can contain the old monotonically incremented FIFO delay count.
   * Only the last physical ringful was retained, so rebase the head to the oldest retained entry
   * and cap that historical count before applying or migrating it. Negative and otherwise
   * malformed values are left untouched for current semantic validation to reject.
   */
  private fun normalizeLegacyFifoOccupancy(value: StateValue): StateValue =
      when (value) {
        is RecordState -> {
          val fields =
              value.fields
                  .map { StateField(it.name, normalizeLegacyFifoOccupancy(it.value)) }
                  .toMutableList()
          if (value.typeId in LEGACY_FIFO_RECORD_IDS) {
            normalizeLegacyFifoRecord(fields)
          }
          RecordState(value.typeId, fields)
        }
        is ObjectArrayState -> ObjectArrayState(value.values.map(::normalizeLegacyFifoOccupancy))
        is ListState -> ListState(value.values.map(::normalizeLegacyFifoOccupancy))
        is Int32MapState ->
            Int32MapState(
                value.entries.map {
                  Int32MapEntry(it.key, normalizeLegacyFifoOccupancy(it.value))
                })
        else -> value
      }

  private fun normalizeLegacyFifoRecord(fields: MutableList<StateField>) {
    val entries = fields.valueOrNull("delayEntry") as? Int32ArrayState ?: return
    val stamps = fields.valueOrNull("delayStamp") as? Int64ArrayState ?: return
    val headIndex = fields.indexOfFirst { it.name == "delayHead" }
    val sizeIndex = fields.indexOfFirst { it.name == "delaySize" }
    if (headIndex < 0 || sizeIndex < 0) return
    val head = (fields[headIndex].value as? Int32State)?.value ?: return
    val size = (fields[sizeIndex].value as? Int32State)?.value ?: return
    val capacity = entries.size
    if (capacity <= 0 || stamps.size != capacity || head !in 0 until capacity || size <= capacity) {
      return
    }

    // The old counter could grow past the physical ring while writes continued modulo capacity.
    // Its last `capacity` logical entries therefore begin S-C slots after the original head.
    // Compute in Long so an otherwise valid positive legacy counter cannot overflow the rebase.
    val normalizedHead =
        Math.floorMod(
                head.toLong() + size.toLong() - capacity.toLong(),
                capacity.toLong(),
            )
            .toInt()
    fields[headIndex] = StateField("delayHead", Int32State(normalizedHead))
    fields[sizeIndex] = StateField("delaySize", Int32State(capacity))
  }

  private fun List<StateField>.valueOrNull(name: String): StateValue? =
      singleOrNull { it.name == name }?.value

  fun capture(session: Session): SessionState {
    val peripheral = serialPeripheral(session.serialEndpoint)
    val serial = StateGraph.capture(session.serialEndpoint.captureState())
    return SessionState(
        capture(session.gameboy),
        peripheral,
        serial,
        captureSerialRuntime(session.serialEndpoint),
        session.heldButtons.map { HeldButtonState.valueOf(it.name) }.sortedBy { it.ordinal },
    )
  }

  fun apply(
      gameboy: Gameboy,
      state: MachineState,
      probe: ((ApplyStage) -> Unit)? = null,
  ) {
    val prepared = prepare(gameboy, state)
    val rollback = prepare(gameboy, capture(gameboy))
    try {
      probe?.invoke(ApplyStage.BEFORE_LIVE_MUTATION)
      commit(gameboy, prepared)
    } catch (failure: Throwable) {
      try {
        commit(gameboy, rollback)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Detached machine state could not be applied atomically", failure)
    }
  }

  fun apply(
      session: Session,
      state: SessionState,
      probe: ((ApplyStage) -> Unit)? = null,
  ) {
    val prepared = prepare(session, state)
    val rollback = prepare(session, capture(session))
    try {
      probe?.invoke(ApplyStage.BEFORE_LIVE_MUTATION)
      commit(session, prepared, probe)
    } catch (failure: Throwable) {
      try {
        commit(session, rollback)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Detached session state could not be applied atomically", failure)
    }
  }

  /**
   * Performs target-dependent mapper, array-layout, hardware, and runtime preflight without
   * reconstructing the candidate record graph. Network readers use this against an isolated probe;
   * the owning controller still performs the complete prepare step at its frame safe point.
   */
  internal fun validateTarget(gameboy: Gameboy, state: MachineState) {
    if (state.hardware != gameboy.hardwareProfile.toMachineHardware()) {
      throw StateApplyException(
          "Detached ${state.hardware} state does not match ${gameboy.hardwareProfile.id()} profile")
    }
    val current = StateGraph.captureRoot(gameboy.captureState(), GAMEBOY_ROOT)
    StateGraph.validateCompatible(state.root, current, "machine")
    try {
      gameboy.validateRtcRuntimeState(state.rtcRuntime.toCore())
      gameboy.validateDmgFifoRuntimeState(state.dmgFifoRuntime.toCore())
    } catch (failure: IllegalArgumentException) {
      throw StateApplyException("Detached machine runtime layout is incompatible", failure)
    }
  }

  private fun eu.rekawek.coffeegb.core.hardware.HardwareProfile.toMachineHardware() =
      when (family()) {
        eu.rekawek.coffeegb.core.hardware.HardwareProfile.Family.DMG -> MachineHardwareState.DMG
        eu.rekawek.coffeegb.core.hardware.HardwareProfile.Family.CGB -> MachineHardwareState.CGB
        eu.rekawek.coffeegb.core.hardware.HardwareProfile.Family.SGB -> MachineHardwareState.SGB
      }

  /** Target-dependent session preflight that does not reconstruct or apply candidate state. */
  internal fun validateTarget(session: Session, state: SessionState) {
    val currentPeripheral = serialPeripheral(session.serialEndpoint)
    if (currentPeripheral != state.serialPeripheral) {
      throw StateApplyException(
          "Session peripheral mismatch: expected ${state.serialPeripheral}, found $currentPeripheral")
    }
    validateTarget(session.gameboy, state.machine)
    val currentSerialState = StateGraph.capture(session.serialEndpoint.captureState())
    if ((state.serialState === NullState) != (currentSerialState === NullState)) {
      throw StateApplyException("Detached serial state presence does not match the endpoint")
    }
    StateGraph.validateCompatible(state.serialState, currentSerialState, "serial")
    validateSerialRuntime(session.serialEndpoint, state.serialRuntime)
    if (state.heldButtons.distinct().size != state.heldButtons.size) {
      throw StateApplyException("Detached held-button state contains duplicates")
    }
  }

  internal fun prepare(session: Session, state: SessionState): PreparedSessionState {
    validateTarget(session, state)
    val machine = reconstructMachine(state.machine, session.gameboy.clockSpec)
    val serialValue = StateGraph.restore(state.serialState)
    StateSemantics.validate(serialValue)
    if (serialValue != null && serialValue !is ComponentState<*>) {
      throw StateApplyException("Session serial state has the wrong root type")
    }
    @Suppress("UNCHECKED_CAST") val serialState = serialValue as ComponentState<SerialEndpoint>?
    val heldButtons = state.heldButtons.map { Button.valueOf(it.name) }.toSet()
    return PreparedSessionState(machine, serialState, state.serialRuntime, heldButtons)
  }

  internal fun commit(
      session: Session,
      prepared: PreparedSessionState,
      probe: ((ApplyStage) -> Unit)? = null,
  ) {
    commit(session.gameboy, prepared.machine)
    probe?.invoke(ApplyStage.AFTER_MACHINE_MUTATION)
    session.serialEndpoint.restoreState(prepared.serialState)
    applySerialRuntime(session.serialEndpoint, prepared.serialRuntime)
    session.heldButtons = prepared.heldButtons
  }

  private fun prepare(gameboy: Gameboy, state: MachineState): PreparedMachineState {
    validateTarget(gameboy, state)
    return reconstructMachine(state, gameboy.clockSpec)
  }

  private fun reconstructMachine(
      state: MachineState,
      clockSpec: eu.rekawek.coffeegb.core.hardware.ClockSpec,
  ): PreparedMachineState {
    val detached = StateGraph.restoreRoot(state.root, GAMEBOY_ROOT)
    StateSemantics.validateForClock(detached, clockSpec)
    @Suppress("UNCHECKED_CAST") val componentState = detached as ComponentState<Gameboy>
    val rtcRuntime = state.rtcRuntime.toCore()
    val dmgFifoRuntime = state.dmgFifoRuntime.toCore()
    return PreparedMachineState(componentState, rtcRuntime, dmgFifoRuntime)
  }

  private fun commit(gameboy: Gameboy, prepared: PreparedMachineState) {
    gameboy.restoreState(prepared.componentState)
    gameboy.restoreDmgFifoRuntimeState(prepared.dmgFifoRuntime)
    gameboy.restoreRtcRuntimeState(prepared.rtcRuntime)
  }

  private fun serialPeripheral(endpoint: SerialEndpoint): SerialPeripheralState =
      when (endpoint.javaClass.name) {
        "eu.rekawek.coffeegb.core.serial.SerialEndpoint\$1" -> SerialPeripheralState.NONE
        "eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint" ->
            SerialPeripheralState.BYTE_RECEIVER
        "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint" ->
            SerialPeripheralState.PEER_TO_PEER
        "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint" ->
            SerialPeripheralState.PRINTER
        "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint" ->
            SerialPeripheralState.GPS_RECEIVER
        "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint" ->
            SerialPeripheralState.BARCODE_BOY
        "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$Endpoint" ->
            SerialPeripheralState.FOUR_PLAYER_ADAPTER
        else ->
            throw StateCaptureException(
              "Unsupported serial endpoint ${endpoint.javaClass.name}; state was not omitted")
      }

  private fun captureSerialRuntime(endpoint: SerialEndpoint): SerialRuntimeState =
      if (endpoint is BarcodeBoySerialEndpoint) {
        endpoint.captureRuntimeState().let {
          BarcodeBoyRuntimeState(it.transferArmed(), it.copyPending())
        }
      } else {
        NoSerialRuntimeState
      }

  private fun validateSerialRuntime(endpoint: SerialEndpoint, state: SerialRuntimeState) {
    val valid =
        when (state) {
          is BarcodeBoyRuntimeState -> endpoint is BarcodeBoySerialEndpoint
          NoSerialRuntimeState -> endpoint !is BarcodeBoySerialEndpoint
        }
    if (!valid) throw StateApplyException("Detached serial runtime state does not match the endpoint")
    if (state is BarcodeBoyRuntimeState) {
      StateSemantics.validateBarcodeRuntime(state)
    }
  }

  private fun applySerialRuntime(endpoint: SerialEndpoint, state: SerialRuntimeState) {
    validateSerialRuntime(endpoint, state)
    if (endpoint is BarcodeBoySerialEndpoint && state is BarcodeBoyRuntimeState) {
      endpoint.restoreRuntimeState(
          BarcodeBoySerialEndpoint.RuntimeState(state.transferArmed, state.copyPending()))
    }
  }

  private val LEGACY_FIFO_RECORD_IDS by lazy {
    listOf(
            "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState",
            "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState",
        )
        .map { className ->
          StateTypeRegistry.recordClassNames.indexOf(className).plus(1).also { id ->
            check(id > 0) { "State registry has no $className" }
          }
        }
        .toSet()
  }

  private const val GAMEBOY_ROOT = "eu.rekawek.coffeegb.core.Gameboy\$GameboyState"
  private const val LEGACY_GAMEBOY_ROOT = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"

  private fun Gameboy.RtcRuntimeState.toDetached(): CartridgeRtcRuntimeState =
      CartridgeRtcRuntimeState(primary.toDetached(), slot.toDetached())

  private fun eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock.RuntimeState?.toDetached():
      Mbc3RtcRuntimeState? =
      this?.let { Mbc3RtcRuntimeState(it.emulationPaused(), it.pauseStartedMillis()) }

  private fun CartridgeRtcRuntimeState.toCore(): Gameboy.RtcRuntimeState =
      Gameboy.RtcRuntimeState(primary.toCore(), slot.toCore())

  private fun Mbc3RtcRuntimeState?.toCore():
      eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock.RuntimeState? =
      this?.let {
        eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock.RuntimeState(
            it.emulationPaused, it.pauseStartedMillis)
      }

  private fun Gpu.DmgFifoRuntimeState?.toDetached(): DmgFifoRuntimeState? =
      this?.let {
        DmgFifoRuntimeState(it.timing().toDetached(), it.output().toDetached())
      }

  private fun DmgPixelFifo.RuntimeState.toDetached(): DmgPixelFifoRuntimeState =
      DmgPixelFifoRuntimeState(
          linePixels(), outCount(), firstEntry(), firstBgp(), firstObp0(), firstObp1())

  private fun DmgFifoRuntimeState?.toCore(): Gpu.DmgFifoRuntimeState? =
      this?.let { Gpu.DmgFifoRuntimeState(it.timing.toCore(), it.output.toCore()) }

  private fun DmgPixelFifoRuntimeState.toCore(): DmgPixelFifo.RuntimeState =
      DmgPixelFifo.RuntimeState(
          linePixels, outCount, firstEntry, firstBgp, firstObp0, firstObp1)
}

internal object StateGraph {
  private val recordIds by lazy {
    StateTypeRegistry.recordClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }
  private val legacyRecordIds by lazy {
    StateTypeRegistry.legacyRecordClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }
  private val enumIds by lazy {
    StateTypeRegistry.enumClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }

  fun captureRoot(value: Any, className: String): RecordState {
    val root = capture(value)
    if (root !is RecordState || recordClass(root).name != className) {
      throw StateCaptureException("Detached state has the wrong root type")
    }
    return root
  }

  fun captureLegacyRoot(value: Any, className: String): RecordState {
    if (value.javaClass.name != className) {
      throw StateCaptureException("Legacy state has the wrong root type")
    }
    val root = Capture(legacyRecordIds).value(value, 0)
    if (root !is RecordState) {
      throw StateCaptureException("Legacy state has the wrong root shape")
    }
    return root
  }

  fun capture(value: Any?): StateValue = Capture(recordIds).value(value, 0)

  fun restoreRoot(value: RecordState, className: String): Any {
    if (recordClass(value).name != className) {
      throw StateApplyException("Detached state has the wrong root type")
    }
    return restore(value) ?: throw StateApplyException("Detached root state is null")
  }

  fun restore(value: StateValue): Any? = Restore().value(value, null, 0)

  /** Validates target-dependent restore preconditions without touching the live target. */
  fun validateCompatible(candidate: StateValue, target: StateValue, path: String) {
    Compatibility().value(candidate, target, path, null, null)
  }

  fun countRecords(value: StateValue, className: String): Int {
    var count = 0
    fun visit(current: StateValue) {
      when (current) {
        is RecordState -> {
          if (recordClass(current).name == className) count++
          current.fields.forEach { visit(it.value) }
        }
        is ObjectArrayState -> current.values.forEach(::visit)
        is ListState -> current.values.forEach(::visit)
        is Int32MapState -> current.entries.forEach { visit(it.value) }
        else -> Unit
      }
    }
    visit(value)
    return count
  }

  private fun recordClass(value: RecordState): Class<*> =
      StateTypeRegistry.recordClasses.getOrNull(value.typeId - 1)
          ?: throw StateApplyException("Unknown detached record type ID ${value.typeId}")

  private class Capture(private val admittedRecordIds: Map<Class<*>, Int>) {
    private var references = 0L

    fun value(value: Any?, depth: Int): StateValue {
      checkCaptureDepth(depth)
      if (value == null) return NullState
      countReference()
      return when (value) {
        is Int -> Int32State(value)
        is Long -> Int64State(value)
        is Boolean -> BooleanState(value)
        is Double -> Float64State(value)
        is String -> {
          DetachedValueBounds.requireString(value) { message -> throw StateCaptureException(message) }
          StringState(value)
        }
        is ByteArray -> {
          checkArray(value.size, Byte.SIZE_BYTES)
          BytesState(value)
        }
        is IntArray -> {
          checkArray(value.size, Int.SIZE_BYTES)
          Int32ArrayState(value)
        }
        is LongArray -> {
          checkArray(value.size, Long.SIZE_BYTES)
          Int64ArrayState(value)
        }
        is BooleanArray -> {
          checkArray(value.size, 1)
          BooleanArrayState(value)
        }
        is List<*> -> {
          checkCaptureCollection(value.size)
          ListState(value.map { this.value(it, depth + 1) })
        }
        is Map<*, *> -> captureMap(value, depth)
        is Enum<*> -> {
          val id = enumIds[value.javaClass]
              ?: throw StateCaptureException("Unregistered state enum ${value.javaClass.name}")
          EnumState(id, value.ordinal)
        }
        else ->
            if (value.javaClass.isArray) captureArray(value, depth)
            else captureRecord(value, depth)
      }
    }

    private fun captureArray(value: Any, depth: Int): StateValue {
      val size = ReflectArray.getLength(value)
      checkCaptureCollection(size)
      return ObjectArrayState(List(size) { this.value(ReflectArray.get(value, it), depth + 1) })
    }

    private fun captureMap(value: Map<*, *>, depth: Int): StateValue {
      checkCaptureCollection(value.size)
      val entries = value.entries.map { entry ->
        val key = entry.key as? Int
            ?: throw StateCaptureException("Only integer state-map keys are supported")
        Int32MapEntry(key, this.value(entry.value, depth + 1))
      }
      if (entries.map(Int32MapEntry::key).distinct().size != entries.size) {
        throw StateCaptureException("Duplicate state-map key")
      }
      return Int32MapState(entries)
    }

    private fun captureRecord(value: Any, depth: Int): StateValue {
      val type = value.javaClass
      val id = admittedRecordIds[type]
          ?: throw StateCaptureException("Unregistered state record ${type.name}")
      val fields = type.recordComponents.map { component ->
        component.accessor.trySetAccessible()
        val componentValue =
            try {
              component.accessor.invoke(value)
            } catch (failure: ReflectiveOperationException) {
              throw StateCaptureException("State field ${type.name}.${component.name} failed", failure)
            }
        StateField(component.name, this.value(componentValue, depth + 1))
      }
      return RecordState(id, fields)
    }

    private fun countReference() {
      references++
      if (references > StateLimits.LEGACY_MAX_REFERENCES) {
        throw StateCaptureException("State has too many references")
      }
    }
  }

  private class Restore {
    private var references = 0L

    fun value(value: StateValue, expectedType: Type?, depth: Int): Any? {
      checkRestoreDepth(depth)
      if (value === NullState) {
        if (expectedType.rawClass()?.isPrimitive == true) {
          throw StateApplyException("Null primitive state value")
        }
        return null
      }
      countReference()
      val restored =
          when (value) {
            is Int32State -> value.value
            is Int64State -> value.value
            is BooleanState -> value.value
            is Float64State -> value.value
            is StringState -> {
              DetachedValueBounds.requireString(value.value) { message ->
                throw StateApplyException(message)
              }
              value.value
            }
            is EnumState -> restoreEnum(value)
            is RecordState -> restoreRecord(value, depth)
            is BytesState -> checkedCopy(value, Byte.SIZE_BYTES)
            is Int32ArrayState -> checkedCopy(value, Int.SIZE_BYTES)
            is Int64ArrayState -> checkedCopy(value, Long.SIZE_BYTES)
            is BooleanArrayState -> checkedCopy(value, 1)
            is ObjectArrayState -> restoreArray(value, expectedType, depth)
            is ListState -> restoreList(value, expectedType, depth)
            is Int32MapState -> restoreMap(value, expectedType, depth)
            NullState -> null
          }
      requireExpected(restored, expectedType)
      return restored
    }

    private fun restoreEnum(value: EnumState): Any {
      val type = StateTypeRegistry.enumClasses.getOrNull(value.typeId - 1)
          ?: throw StateApplyException("Unknown detached enum type ID ${value.typeId}")
      return type.enumConstants.getOrNull(value.ordinal)
          ?: throw StateApplyException("Invalid ${type.name} ordinal ${value.ordinal}")
    }

    private fun restoreRecord(value: RecordState, depth: Int): Any {
      val type = recordClass(value)
      val components = type.recordComponents
      if (value.fields.size != components.size ||
          value.fields.indices.any { value.fields[it].name != components[it].name }) {
        throw StateApplyException("Invalid ${type.name} field inventory")
      }
      val args =
          components.indices.map { index ->
            this.value(value.fields[index].value, components[index].genericType, depth + 1)
          }.toTypedArray()
      val constructor = type.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
      constructor.trySetAccessible()
      try {
        return constructor.newInstance(*args)
      } catch (failure: InvocationTargetException) {
        throw StateApplyException("Invalid ${type.name} value", failure.targetException)
      } catch (failure: ReflectiveOperationException) {
        throw StateApplyException("State record ${type.name} could not be constructed", failure)
      }
    }

    private fun restoreArray(value: ObjectArrayState, expectedType: Type?, depth: Int): Any {
      val type = expectedType.rawClass()
      if (type?.isArray != true || type.componentType.isPrimitive) {
        throw StateApplyException("Unexpected detached object array")
      }
      checkRestoreCollection(value.values.size)
      return ReflectArray.newInstance(type.componentType, value.values.size).also { result ->
        value.values.forEachIndexed { index, item ->
          ReflectArray.set(result, index, this.value(item, type.componentType, depth + 1))
        }
      }
    }

    private fun restoreList(value: ListState, expectedType: Type?, depth: Int): List<Any?> {
      checkRestoreCollection(value.values.size)
      val elementType = expectedType.typeArguments()?.singleOrNull()
      return ArrayList<Any?>(value.values.size).also { result ->
        value.values.forEach { result += this.value(it, elementType, depth + 1) }
      }
    }

    private fun restoreMap(value: Int32MapState, expectedType: Type?, depth: Int): Map<Int, Any?> {
      checkRestoreCollection(value.entries.size)
      val arguments = expectedType.typeArguments()
      val keyType = arguments?.getOrNull(0) ?: Int::class.javaObjectType
      val entryType = arguments?.getOrNull(1)
      if (keyType.rawClass() != Int::class.javaObjectType) {
        throw StateApplyException("Detached state maps require integer keys")
      }
      var previous: Int? = null
      return LinkedHashMap<Int, Any?>().also { result ->
        value.entries.forEach { entry ->
          if (previous != null && entry.key <= checkNotNull(previous)) {
            throw StateApplyException("Detached state-map keys are not strictly ordered")
          }
          previous = entry.key
          result[entry.key] = this.value(entry.value, entryType, depth + 1)
        }
      }
    }

    private fun countReference() {
      references++
      if (references > StateLimits.LEGACY_MAX_REFERENCES) {
        throw StateApplyException("State has too many references")
      }
    }

    private fun <T> checkedCopy(value: PrimitiveArrayState<T>, width: Int): T {
      DetachedValueBounds.requireArray(value.size.toLong(), width.toLong()) { message ->
        throw StateApplyException(message)
      }
      return value.copyValue()
    }
  }

  private class Compatibility {
    fun value(
        candidate: StateValue,
        target: StateValue,
        path: String,
        owner: String?,
        field: String?,
        element: Boolean = false,
    ) {
      if (candidate === NullState || target === NullState) {
        if (candidate !== target && !isAuditedNullable(owner, field, element)) {
          throw StateApplyException("$path has incompatible state presence")
        }
        return
      }
      if (candidate.kind != target.kind) {
        throw StateApplyException("$path has ${candidate.kind}, expected ${target.kind}")
      }
      when {
        candidate is RecordState && target is RecordState -> {
          if (candidate.typeId != target.typeId) {
            throw StateApplyException(
                "$path has ${recordClass(candidate).name}, expected ${recordClass(target).name}")
          }
          val type = recordClass(target).name
          if (candidate.fields.size != target.fields.size) {
            throw StateApplyException("$path has an incompatible field count")
          }
          candidate.fields.indices.forEach { index ->
            val left = candidate.fields[index]
            val right = target.fields[index]
            if (left.name != right.name) {
              throw StateApplyException("$path has an incompatible field order")
            }
            value(left.value, right.value, "$path.${left.name}", type, left.name, false)
          }
        }
        candidate is PrimitiveArrayState<*> && target is PrimitiveArrayState<*> -> {
          if (!isVariableArray(owner, field) && candidate.size != target.size) {
            throw StateApplyException(
                "$path has length ${candidate.size}, expected invariant length ${target.size}")
          }
        }
        candidate is ObjectArrayState && target is ObjectArrayState -> {
          if (candidate.values.size != target.values.size) {
            throw StateApplyException(
                "$path has length ${candidate.values.size}, expected invariant length ${target.values.size}")
          }
          candidate.values.indices.forEach { index ->
            value(candidate.values[index], target.values[index], "$path[$index]", owner, field, true)
          }
        }
      }
    }
  }

  private fun checkCaptureDepth(depth: Int) {
    if (depth > StateLimits.LEGACY_MAX_DEPTH) throw StateCaptureException("State is too deep")
  }

  private fun checkRestoreDepth(depth: Int) {
    if (depth > StateLimits.LEGACY_MAX_DEPTH) throw StateApplyException("State is too deep")
  }

  private fun checkArray(size: Int, width: Int) =
      DetachedValueBounds.requireArray(size.toLong(), width.toLong()) { message ->
        throw StateCaptureException(message)
      }

  private fun checkCaptureCollection(size: Int) {
    if (size < 0 || size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
      throw StateCaptureException("Invalid state collection length $size")
    }
  }

  private fun checkRestoreCollection(size: Int) {
    if (size < 0 || size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
      throw StateApplyException("Invalid state collection length $size")
    }
  }

  private fun requireExpected(value: Any?, expectedType: Type?) {
    if (value == null || expectedType == null) return
    val expectedClass = expectedType.rawClass() ?: return
    val boxed =
        when (expectedClass) {
          Int::class.javaPrimitiveType -> Int::class.javaObjectType
          Long::class.javaPrimitiveType -> Long::class.javaObjectType
          Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
          Double::class.javaPrimitiveType -> Double::class.javaObjectType
          else -> expectedClass
        }
    if (!boxed.isInstance(value)) {
      throw StateApplyException(
          "Expected ${expectedClass.name}, received ${value.javaClass.name}")
    }
  }

  private fun Type?.rawClass(): Class<*>? =
      when (this) {
        is Class<*> -> this
        is ParameterizedType -> rawType as? Class<*>
        is GenericArrayType ->
            genericComponentType.rawClass()?.let { ReflectArray.newInstance(it, 0).javaClass }
        is WildcardType -> upperBounds.singleOrNull().rawClass()
        else -> null
      }

  private fun Type?.typeArguments(): Array<Type>? =
      (this as? ParameterizedType)?.actualTypeArguments

  private fun isVariableArray(owner: String?, field: String?): Boolean =
      owner to field in VARIABLE_ARRAY_FIELDS

  private fun isAuditedNullable(owner: String?, field: String?, element: Boolean): Boolean =
      owner to field in if (element) AUDITED_NULLABLE_ELEMENTS else AUDITED_NULLABLE_FIELDS

  private val VARIABLE_ARRAY_FIELDS =
      setOf(
          "eu.rekawek.coffeegb.core.sound.Sound\$SoundState" to "buffer",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyState" to "data",
          "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerState" to "schedule",
          "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState" to
              "dataTransfer",
      )

  /**
   * Exact nullable locations in the pinned legacy graph. Nullability is a field contract, not a
   * consequence of the value kind: primitive arrays, enums, collections, and records all default
   * to required unless their precise owner/field pair is listed here.
   */
  private val AUDITED_NULLABLE_FIELDS =
      setOf(
          // Transitional legacy root display copy; new captures use only GpuState's display.
          "eu.rekawek.coffeegb.core.Gameboy\$GameboyState" to "displayMemento",
          // No interrupt has been selected while the CPU is outside interrupt entry.
          "eu.rekawek.coffeegb.core.cpu.Cpu\$CpuState" to "requestedIrq",
          // Old GPU snapshots predate these caches and dot-machine additions.
          "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState" to "lastFrame",
          "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesState" to
              "mixValues",
          "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesState" to
              "pendingMixValues",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState" to "pixelMachineMemento",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState" to "pendingPpuWrites",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState" to "cpuVisiblePpuRegisters",
          "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState" to "delayEntry",
          "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState" to "delayStamp",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState" to "delayEntry",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState" to "delayStamp",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState" to
              "clearedPixels",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState" to
              "clearedPalettes",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState" to
              "clearedPriorities",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState" to
              "fifoMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState" to
              "pendingWindowDisplayWrites",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState" to
              "pendingWindowXWrites",
          // Added after the original historical HDMA record; absence selects the legacy derivation.
          // GPU mode is also absent until the first PPU mode publication.
          "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState" to "gpuMode",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState" to "cpuRequestArbitration",
          // Barcode data exists only while a scan is actively streaming.
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyState" to
              "data",
          // These SGB operations are genuinely optional behavior state, not hardware identity.
          "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyState" to
              "waitingTransferCommandMemento",
          "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundState" to
              "pendingPictureMemento",
          "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState" to
              "dataTransfer",
      )

  private val AUDITED_NULLABLE_ELEMENTS =
      setOf(
          // Historical SGB snapshots can contain unavailable PAL_TRN entries.
          "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayState" to "systemPalettes",
      )
}

/** Checked allocation arithmetic shared by capture, restore, and the future Phase-2 decoder. */
internal object DetachedValueBounds {
  fun checkedArrayBytesForApply(elements: Long, width: Long): Long =
      requireArray(elements, width) { message -> throw StateApplyException(message) }

  fun checkStringMetricsForApply(chars: Long, encodedBytes: Long) {
    requireStringMetrics(chars, encodedBytes) { message -> throw StateApplyException(message) }
  }

  fun requireArray(elements: Long, width: Long, fail: (String) -> Nothing): Long {
    if (elements < 0 || elements > StateLimits.LEGACY_MAX_ARRAY_LENGTH) {
      fail("State array length $elements exceeds ${StateLimits.LEGACY_MAX_ARRAY_LENGTH}")
    }
    if (width <= 0) fail("State array element width must be positive")
    val bytes =
        try {
          Math.multiplyExact(elements, width)
        } catch (_: ArithmeticException) {
          fail("State array allocation byte count overflows")
        }
    if (bytes > StateLimits.LEGACY_MAX_ARRAY_BYTES) {
      fail("State array allocation $bytes exceeds ${StateLimits.LEGACY_MAX_ARRAY_BYTES} bytes")
    }
    return bytes
  }

  fun requireString(value: String, fail: (String) -> Nothing) {
    if (value.length.toLong() > StateLimits.LEGACY_MAX_STRING_CHARS) {
      fail("State string exceeds ${StateLimits.LEGACY_MAX_STRING_CHARS} characters")
    }
    val encodedBytes = value.toByteArray(StandardCharsets.UTF_8).size.toLong()
    requireStringMetrics(value.length.toLong(), encodedBytes, fail)
  }

  private fun requireStringMetrics(chars: Long, encodedBytes: Long, fail: (String) -> Nothing) {
    if (chars < 0 || chars > StateLimits.LEGACY_MAX_STRING_CHARS) {
      fail("State string character count $chars exceeds ${StateLimits.LEGACY_MAX_STRING_CHARS}")
    }
    if (encodedBytes < 0 || encodedBytes > StateLimits.LEGACY_MAX_STRING_BYTES) {
      fail("State string encoding $encodedBytes exceeds ${StateLimits.LEGACY_MAX_STRING_BYTES} bytes")
    }
  }
}
