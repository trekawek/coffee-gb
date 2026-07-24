package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.IOException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.GenericArrayType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
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
  abstract fun copyValue(): T
}

class BytesState(value: ByteArray) : PrimitiveArrayState<ByteArray>(StateKind.BYTES) {
  private val owned = value.clone()
  override fun copyValue(): ByteArray = owned.clone()
  override fun equals(other: Any?): Boolean = other is BytesState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class Int32ArrayState(value: IntArray) : PrimitiveArrayState<IntArray>(StateKind.INT32_ARRAY) {
  private val owned = value.clone()
  override fun copyValue(): IntArray = owned.clone()
  override fun equals(other: Any?): Boolean =
      other is Int32ArrayState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class Int64ArrayState(value: LongArray) : PrimitiveArrayState<LongArray>(StateKind.INT64_ARRAY) {
  private val owned = value.clone()
  override fun copyValue(): LongArray = owned.clone()
  override fun equals(other: Any?): Boolean =
      other is Int64ArrayState && owned.contentEquals(other.owned)
  override fun hashCode(): Int = owned.contentHashCode()
}

class BooleanArrayState(value: BooleanArray) :
    PrimitiveArrayState<BooleanArray>(StateKind.BOOLEAN_ARRAY) {
  private val owned = value.clone()
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
data class RtcRuntimeState(val emulationPaused: Boolean, val pauseStartedMillis: Long)

sealed interface SerialRuntimeState

data object NoSerialRuntimeState : SerialRuntimeState

class BarcodeBoyRuntimeState(
    val transferArmed: Boolean,
    pending: IntArray?,
) : SerialRuntimeState {
  private val ownedPending = pending?.clone()
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
    val rtcRuntime: RtcRuntimeState?,
) {
  fun recordCount(className: String): Int =
      StateGraph.countRecords(root, className)

  override fun equals(other: Any?): Boolean =
      other is MachineState && root == other.root && rtcRuntime == other.rtcRuntime
  override fun hashCode(): Int = 31 * root.hashCode() + (rtcRuntime?.hashCode() ?: 0)
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

/** Detached state owned by one controller Session, including physical held input. */
class SessionState internal constructor(
    val machine: MachineState,
    val serialPeripheral: SerialPeripheralState,
    val serialState: StateValue,
    val serialRuntime: SerialRuntimeState,
    heldButtons: Collection<HeldButtonState>,
) {
  val heldButtons: Set<HeldButtonState> =
      Collections.unmodifiableSet(linkedSetOf<HeldButtonState>().also { it.addAll(heldButtons) })

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

/**
 * Transitional adapter between the legacy in-memory mementos and the explicit immutable model.
 * This is deliberately not a byte codec; #322 owns the sectioned StateFile representation.
 */
internal object DetachedStateAdapter {

  fun capture(gameboy: Gameboy): MachineState =
      MachineState(
          StateGraph.captureRoot(gameboy.saveToMemento(), GAMEBOY_ROOT),
          gameboy.captureRtcRuntimeState()?.let {
            RtcRuntimeState(it.emulationPaused(), it.pauseStartedMillis())
          },
      )

  fun capture(session: Session): SessionState {
    val peripheral = serialPeripheral(session.serialEndpoint)
    val serial = StateGraph.capture(session.serialEndpoint.saveToMemento())
    return SessionState(
        capture(session.gameboy),
        peripheral,
        serial,
        captureSerialRuntime(session.serialEndpoint),
        session.heldButtons.map { HeldButtonState.valueOf(it.name) },
    )
  }

  fun apply(gameboy: Gameboy, state: MachineState) {
    val detached = StateGraph.restoreRoot(state.root, GAMEBOY_ROOT)
    @Suppress("UNCHECKED_CAST") val replacement = detached as Memento<Gameboy>
    val currentRtc = gameboy.captureRtcRuntimeState()
    if ((currentRtc == null) != (state.rtcRuntime == null)) {
      throw StateApplyException("Detached state RTC family does not match the running cartridge")
    }
    val rollback = gameboy.saveToMemento()
    try {
      gameboy.restoreFromMemento(replacement)
      gameboy.restoreRtcRuntimeState(state.rtcRuntime?.let {
        eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock.RuntimeState(
            it.emulationPaused, it.pauseStartedMillis)
      })
    } catch (failure: Throwable) {
      try {
        gameboy.restoreFromMemento(rollback)
        gameboy.restoreRtcRuntimeState(currentRtc)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Detached machine state could not be applied atomically", failure)
    }
  }

  fun apply(session: Session, state: SessionState) {
    val currentPeripheral = serialPeripheral(session.serialEndpoint)
    if (currentPeripheral != state.serialPeripheral) {
      throw StateApplyException(
          "Session peripheral mismatch: expected ${state.serialPeripheral}, found $currentPeripheral")
    }

    val machineValue = StateGraph.restoreRoot(state.machine.root, GAMEBOY_ROOT)
    val serialValue = StateGraph.restore(state.serialState)
    if (serialValue != null && serialValue !is Memento<*>) {
      throw StateApplyException("Session serial state has the wrong root type")
    }
    @Suppress("UNCHECKED_CAST") val machineMemento = machineValue as Memento<Gameboy>
    @Suppress("UNCHECKED_CAST") val serialMemento = serialValue as Memento<SerialEndpoint>?
    validateSerialRuntime(session.serialEndpoint, state.serialRuntime)
    val currentRtc = session.gameboy.captureRtcRuntimeState()
    if ((currentRtc == null) != (state.machine.rtcRuntime == null)) {
      throw StateApplyException("Detached state RTC family does not match the running cartridge")
    }

    // Complete reconstruction above is the validation boundary. No live subsystem is touched
    // until both graphs and the endpoint identity have been checked.
    val rollbackMachine = session.gameboy.saveToMemento()
    val rollbackRtc = currentRtc
    val rollbackSerial = session.serialEndpoint.saveToMemento()
    val rollbackSerialRuntime = captureSerialRuntime(session.serialEndpoint)
    val rollbackButtons = session.heldButtons
    try {
      session.gameboy.restoreFromMemento(machineMemento)
      session.gameboy.restoreRtcRuntimeState(state.machine.rtcRuntime?.let {
        eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock.RuntimeState(
            it.emulationPaused, it.pauseStartedMillis)
      })
      session.serialEndpoint.restoreFromMemento(serialMemento)
      applySerialRuntime(session.serialEndpoint, state.serialRuntime)
      session.heldButtons = state.heldButtons.map { Button.valueOf(it.name) }.toSet()
    } catch (failure: Throwable) {
      try {
        session.gameboy.restoreFromMemento(rollbackMachine)
        session.gameboy.restoreRtcRuntimeState(rollbackRtc)
        session.serialEndpoint.restoreFromMemento(rollbackSerial)
        applySerialRuntime(session.serialEndpoint, rollbackSerialRuntime)
        session.heldButtons = rollbackButtons
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Detached session state could not be applied atomically", failure)
    }
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
  }

  private fun applySerialRuntime(endpoint: SerialEndpoint, state: SerialRuntimeState) {
    validateSerialRuntime(endpoint, state)
    if (endpoint is BarcodeBoySerialEndpoint && state is BarcodeBoyRuntimeState) {
      endpoint.restoreRuntimeState(
          BarcodeBoySerialEndpoint.RuntimeState(state.transferArmed, state.copyPending()))
    }
  }

  private const val GAMEBOY_ROOT = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"
}

internal object StateGraph {
  private val recordIds by lazy {
    MementoTypeRegistry.recordClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }
  private val enumIds by lazy {
    MementoTypeRegistry.enumClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }

  fun captureRoot(value: Any, className: String): RecordState {
    val root = capture(value)
    if (root !is RecordState || recordClass(root).name != className) {
      throw StateCaptureException("Detached state has the wrong root type")
    }
    return root
  }

  fun capture(value: Any?): StateValue = Capture().value(value, 0)

  fun restoreRoot(value: RecordState, className: String): Any {
    if (recordClass(value).name != className) {
      throw StateApplyException("Detached state has the wrong root type")
    }
    return restore(value) ?: throw StateApplyException("Detached root state is null")
  }

  fun restore(value: StateValue): Any? = Restore().value(value, null, 0)

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
      MementoTypeRegistry.recordClasses.getOrNull(value.typeId - 1)
          ?: throw StateApplyException("Unknown detached record type ID ${value.typeId}")

  private class Capture {
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
          if (value.length > StateLimits.LEGACY_MAX_STRING_CHARS) {
            throw StateCaptureException("State string is too long")
          }
          StringState(value)
        }
        is ByteArray -> BytesState(value.also { checkArray(it.size) })
        is IntArray -> Int32ArrayState(value.also { checkArray(it.size) })
        is LongArray -> Int64ArrayState(value.also { checkArray(it.size) })
        is BooleanArray -> BooleanArrayState(value.also { checkArray(it.size) })
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
      val id = recordIds[type]
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
            is StringState -> value.value
            is EnumState -> restoreEnum(value)
            is RecordState -> restoreRecord(value, depth)
            is BytesState -> value.copyValue()
            is Int32ArrayState -> value.copyValue()
            is Int64ArrayState -> value.copyValue()
            is BooleanArrayState -> value.copyValue()
            is ObjectArrayState -> restoreArray(value, expectedType, depth)
            is ListState -> restoreList(value, expectedType, depth)
            is Int32MapState -> restoreMap(value, expectedType, depth)
            NullState -> null
          }
      requireExpected(restored, expectedType)
      return restored
    }

    private fun restoreEnum(value: EnumState): Any {
      val type = MementoTypeRegistry.enumClasses.getOrNull(value.typeId - 1)
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
  }

  private fun checkCaptureDepth(depth: Int) {
    if (depth > StateLimits.LEGACY_MAX_DEPTH) throw StateCaptureException("State is too deep")
  }

  private fun checkRestoreDepth(depth: Int) {
    if (depth > StateLimits.LEGACY_MAX_DEPTH) throw StateApplyException("State is too deep")
  }

  private fun checkArray(size: Int) {
    if (size < 0 || size.toLong() > StateLimits.LEGACY_MAX_ARRAY_LENGTH) {
      throw StateCaptureException("Invalid state array length $size")
    }
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
}
