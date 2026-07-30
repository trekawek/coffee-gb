package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.state.ComponentState

/**
 * In-process rewind entry that combines the paged machine snapshot with bounded serial-device
 * state. Host backend handles remain owned by the live endpoint and cannot enter [StateValue].
 */
internal class SessionSnapshot private constructor(
    val machine: MachineSnapshot,
    private val serialPeripheral: SerialPeripheralState,
    private val serialState: StateValue,
    private val serialRuntime: SerialRuntimeState,
    internal val serialModeledBytes: Long,
) {

  /** Exact incremental modeled retention for a collection of session snapshots. */
  internal class RetentionLedger {
    private val machine = MachineSnapshot.RetentionLedger()

    private var serialBytes = 0L

    val retainedBytes: Long
      get() = Math.addExact(machine.modeledRetainedBytes, serialBytes)

    fun add(snapshot: SessionSnapshot) {
      val updatedSerialBytes = Math.addExact(serialBytes, snapshot.serialModeledBytes)
      machine.add(snapshot.machine)
      serialBytes = updatedSerialBytes
    }

    fun remove(snapshot: SessionSnapshot) {
      val updatedSerialBytes = Math.subtractExact(serialBytes, snapshot.serialModeledBytes)
      check(updatedSerialBytes >= 0)
      machine.remove(snapshot.machine)
      serialBytes = updatedSerialBytes
    }
  }

  /**
   * Preflights the complete serial candidate, cancels live endpoint work, then restores machine
   * and endpoint as one rollback-protected operation.
   */
  fun restore(
      session: Session,
      effectiveCartridgePause: Boolean? = null,
  ) {
    val endpoint = session.serialEndpoint
    val currentPeripheral = DetachedStateAdapter.serialPeripheral(endpoint)
    if (currentPeripheral != serialPeripheral) {
      throw StateApplyException(
          "Internal rewind peripheral mismatch: expected $serialPeripheral, found $currentPeripheral")
    }
    val currentSerial = StateGraph.capture(endpoint.captureState())
    if ((serialState === NullState) != (currentSerial === NullState)) {
      throw StateApplyException("Internal rewind serial-state presence does not match target")
    }
    StateGraph.validateCompatible(serialState, currentSerial, "rewind serial")
    val candidateValue = StateGraph.restore(serialState)
    StateSemantics.validateForClock(candidateValue, session.gameboy.clockSpec)
    if (candidateValue != null && candidateValue !is ComponentState<*>) {
      throw StateApplyException("Internal rewind serial state has the wrong root type")
    }
    @Suppress("UNCHECKED_CAST")
    val candidate = candidateValue as ComponentState<SerialEndpoint>?
    DetachedStateAdapter.validateSerialRuntime(endpoint, serialRuntime)

    val rollbackMachine = MachineSnapshot.capture(session.gameboy)
    val rollbackRumble = session.gameboy.isRumbleActive
    val rollbackSerial = endpoint.captureState()
    val rollbackRuntime = DetachedStateAdapter.captureSerialRuntime(endpoint)
    try {
      // Cancellation precedes every live restore. Mobile restore also cancels defensively, but the
      // explicit boundary guarantees no queued backend completion races machine mutation.
      endpoint.disconnect()
      machine.restore(session.gameboy, synchronizeHostOutputs = false)
      endpoint.restoreState(candidate)
      DetachedStateAdapter.applySerialRuntime(endpoint, serialRuntime)
      effectiveCartridgePause?.let(session.gameboy::reanchorCartridgeRtcPause)
    } catch (failure: Throwable) {
      rollback(session, endpoint, rollbackMachine, rollbackSerial, rollbackRuntime, failure)
      throw StateApplyException("Internal session snapshot could not be applied atomically", failure)
    }
    session.gameboy.synchronizeRumbleOutput(rollbackRumble)
  }

  private fun rollback(
      session: Session,
      endpoint: SerialEndpoint,
      rollbackMachine: MachineSnapshot,
      rollbackSerial: ComponentState<SerialEndpoint>?,
      rollbackRuntime: SerialRuntimeState,
      original: Throwable,
  ) {
    try {
      rollbackMachine.restore(session.gameboy, synchronizeHostOutputs = false)
    } catch (rollbackFailure: Throwable) {
      original.addSuppressed(rollbackFailure)
    }
    try {
      endpoint.restoreState(rollbackSerial)
      DetachedStateAdapter.applySerialRuntime(endpoint, rollbackRuntime)
    } catch (rollbackFailure: Throwable) {
      original.addSuppressed(rollbackFailure)
    }
  }

  companion object {
    fun capture(
        session: Session,
        previous: SessionSnapshot? = null,
    ): SessionSnapshot {
      val endpoint = session.serialEndpoint
      val serial = StateGraph.capture(endpoint.captureState())
      val runtime = DetachedStateAdapter.captureSerialRuntime(endpoint)
      return SessionSnapshot(
          machine = MachineSnapshot.capture(session.gameboy, previous?.machine),
          serialPeripheral = DetachedStateAdapter.serialPeripheral(endpoint),
          serialState = serial,
          serialRuntime = runtime,
          serialModeledBytes = modeledSerialBytes(serial, runtime),
      )
    }

    fun retainedBytes(snapshots: Collection<SessionSnapshot>): Long {
      val machine = MachineSnapshot.retainedStats(snapshots.map(SessionSnapshot::machine))
      return snapshots.fold(machine.modeledRetainedBytes) { total, snapshot ->
        saturatingAdd(total, snapshot.serialModeledBytes)
      }
    }

    private fun modeledSerialBytes(
        state: StateValue,
        runtime: SerialRuntimeState,
    ): Long {
      val runtimeBytes =
          when (runtime) {
            NoSerialRuntimeState -> 0L
            is BarcodeBoyRuntimeState ->
                align(24L + (runtime.pendingSize?.toLong() ?: 0L) * Int.SIZE_BYTES)
          }
      return saturatingAdd(SESSION_SERIAL_SNAPSHOT_BYTES, saturatingAdd(modeled(state), runtimeBytes))
    }

    private fun modeled(value: StateValue): Long =
        when (value) {
          NullState -> 0L
          is Int32State, is BooleanState -> 16L
          is Int64State, is Float64State -> 24L
          is StringState -> align(24L + value.value.length * 2L)
          is EnumState -> 24L
          is RecordState ->
              value.fields.fold(align(24L + value.fields.size * 24L)) { total, field ->
                saturatingAdd(total, modeled(field.value))
              }
          is BytesState -> align(24L + value.size)
          is Int32ArrayState -> align(24L + value.size.toLong() * Int.SIZE_BYTES)
          is Int64ArrayState -> align(24L + value.size.toLong() * Long.SIZE_BYTES)
          is BooleanArrayState -> align(24L + value.size)
          is ObjectArrayState ->
              value.values.fold(align(24L + value.values.size * 8L)) { total, item ->
                saturatingAdd(total, modeled(item))
              }
          is ListState ->
              value.values.fold(align(24L + value.values.size * 8L)) { total, item ->
                saturatingAdd(total, modeled(item))
              }
          is Int32MapState ->
              value.entries.fold(align(24L + value.entries.size * 24L)) { total, entry ->
                saturatingAdd(total, modeled(entry.value))
              }
        }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun align(value: Long): Long = (value + 7) and -8L

    private const val SESSION_SERIAL_SNAPSHOT_BYTES = 64L
  }
}
