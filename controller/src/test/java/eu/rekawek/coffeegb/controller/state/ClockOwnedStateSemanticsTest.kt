package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.cpu.InterruptManager
import eu.rekawek.coffeegb.core.cpu.SpeedMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.GpuRegister
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.sound.Sound
import eu.rekawek.coffeegb.core.state.ComponentState
import eu.rekawek.coffeegb.core.timer.Timer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ClockOwnedStateSemanticsTest {

  @Test
  fun frameSequencerRejectsAnImpossibleBlockedNonZeroStep() {
    session().use { session ->
      val root = session.captureDetachedState().machine.root
      val blockedStepZero =
          root
              .replaceRecordField(FRAME_SEQUENCER_STATE, "step", Int32State(0))
              .replaceRecordField(FRAME_SEQUENCER_STATE, "skipNextEdge", BooleanState(true))
      StateSemantics.validate(StateGraph.restore(blockedStepZero))

      val impossible =
          blockedStepZero.replaceRecordField(FRAME_SEQUENCER_STATE, "step", Int32State(1))
      assertFailsWith<StateApplyException> {
        StateSemantics.validate(StateGraph.restore(impossible))
      }
    }
  }

  @Test
  fun lcdcRejectsConflictCountdownsOutsideTheirEmittedBinaryDomain() {
    session().use { session ->
      val root = session.captureDetachedState().machine.root
      listOf("tileSelectGlitchTicks", "pendingTileSelectGlitchTicks").forEach { field ->
        val asserted = root.replaceRecordField(LCDC_STATE, field, Int32State(1))
        StateSemantics.validate(StateGraph.restore(asserted))

        val impossible = root.replaceRecordField(LCDC_STATE, field, Int32State(2))
        assertFailsWith<StateApplyException>(field) {
          StateSemantics.validate(StateGraph.restore(impossible))
        }
      }
    }
  }

  @Test
  fun gpuConflictArraysAcceptLegacyOrExactSharedLatchMirrorsOnly() {
    session().use { session ->
      val state = session.captureDetachedState().machine.root.record(GPU_REGISTER_VALUES_STATE)
      StateSemantics.validate(StateGraph.restore(state))

      val activeLegacy =
          state
              .replaceField("scxOldValue", Int32State(0x12))
              .replaceField("pendingScxOldValue", Int32State(0x34))
              .replaceField("wxJustChangedTicks", Int32State(2))
      StateSemantics.validate(StateGraph.restore(activeLegacy))

      val mix = state.intArray("mixValues").clone()
      val pending = state.intArray("pendingMixValues").clone()
      mix[GpuRegister.SCX.ordinal] = 0x12
      pending[GpuRegister.WX.ordinal] = 0
      val mirrored =
          state
              .replaceField("mixValues", Int32ArrayState(mix))
              .replaceField("pendingMixValues", Int32ArrayState(pending))
              .replaceField("scxOldValue", Int32State(0x12))
              .replaceField("wxJustChangedTicks", Int32State(2))
      StateSemantics.validate(StateGraph.restore(mirrored))

      val invalidStates = listOf(
          mirrored.replaceField("scxOldValue", Int32State(0x34)),
          mirrored.replaceField("wxJustChangedTicks", Int32State(1)),
          state.replaceField("mixValues", Int32ArrayState(
              state.intArray("mixValues").clone().also { it[GpuRegister.SCY.ordinal] = 0 })),
      )
      invalidStates.forEach { invalid ->
        assertFailsWith<StateApplyException> {
          StateSemantics.validate(StateGraph.restore(invalid))
        }
      }
    }
  }

  @Test
  fun customClockSoundBoundaryRestoresAndContinues() {
    val clock = ClockSpec(8_388_608, 60, 1)
    val capacity = Math.multiplyExact(clock.controllerTicksPerFrame(), 2)
    val index = capacity - 2
    val pending = IntArray(index)
    pending[index - 2] = 0x1234
    pending[index - 1] = -0x2345
    val sound = sound(clock)
    val candidate =
        record(sound.captureState())
            .replaceField("i", Int32State(index))
            .replaceField("buffer", Int32ArrayState(pending))

    val prepared = soundState(candidate)
    StateSemantics.validateForClock(prepared, clock)
    sound.restoreState(prepared)
    assertEquals(candidate, record(sound.captureState()))

    val bus = EventBusImpl()
    val emitted = mutableListOf<IntArray>()
    bus.register({ event -> emitted += event.buffer().clone() }, Sound.SoundSampleEvent::class.java)
    sound.init(bus)
    sound.tick()
    assertEquals(1, emitted.size)
    assertEquals(capacity, emitted.single().size)
    assertContentEquals(pending.takeLast(2).toIntArray(), emitted.single().sliceArray(index - 2 until index))
    bus.close()
  }

  @Test
  fun performanceSoundUsesCompactClockCapacityAndPhaseBounds() {
    val clock = ClockSpec.LEGACY
    val speedMode = SpeedMode(true)
    val sound = Sound(
        Timer(InterruptManager(true), speedMode), speedMode, true, clock, ExecutionMode.PERFORMANCE)
    val compactCapacity = 1_271 * 2
    val base = record(sound.captureState())
    val valid =
        base
            .replaceField("i", Int32State(compactCapacity - 2))
            .replaceField("buffer", Int32ArrayState(IntArray(compactCapacity)))
            .replaceField("performanceSamplePhase", Int32State(54))
            .replaceField("audioDecimation", Int32State(55))
    StateSemantics.validateForClock(soundState(valid), clock)

    assertFailsWith<StateApplyException> {
      StateSemantics.validateForClock(
          soundState(valid.replaceField("performanceSamplePhase", Int32State(55))), clock)
    }
    assertFailsWith<StateApplyException> {
      StateSemantics.validateForClock(
          soundState(valid.replaceField("buffer", Int32ArrayState(IntArray(compactCapacity + 2)))),
          clock)
    }
  }

  @Test
  fun customClockSoundRejectsIndicesAndFullBufferShapesBeforeMutation() {
    val clock = ClockSpec(8_388_608, 60, 1)
    val capacity = Math.multiplyExact(clock.controllerTicksPerFrame(), 2)
    val sound = sound(clock)
    val base = record(sound.captureState())
    val before = record(sound.captureState())
    val cases =
        listOf(
            "negative index" to
                base.replaceField("i", Int32State(-2)),
            "odd index" to
                base
                    .replaceField("i", Int32State(1))
                    .replaceField("buffer", Int32ArrayState(IntArray(1))),
            "index at capacity" to
                base
                    .replaceField("i", Int32State(capacity))
                    .replaceField("buffer", Int32ArrayState(IntArray(capacity))),
            "wrong full-buffer capacity" to
                base.replaceField("buffer", Int32ArrayState(IntArray(capacity - 2))),
        )

    cases.forEach { (label, candidate) ->
      val stages = mutableListOf<ApplyStage>()
      val component = soundState(candidate)
      if (label.startsWith("index at") || label.startsWith("wrong full")) {
        // Decode/shape validation deliberately has no target clock and therefore admits a bounded
        // possible prefix/full-buffer shape. Prepare must provide the target-specific rejection.
        StateSemantics.validate(component)
      }
      assertFailsWith<StateApplyException>(label) {
        StateSemantics.validateForClock(component, clock)
        stages += ApplyStage.BEFORE_LIVE_MUTATION
        sound.restoreState(component)
      }
      assertTrue(stages.isEmpty(), "$label reached component mutation")
      assertEquals(before, record(sound.captureState()), "$label changed the Sound component")
    }
  }

  @Test
  fun customClockRtcPhaseAboveLegacyCeilingRestoresAndContinues() {
    val clock = ClockSpec(8_388_608, 60, 1)
    val rtc = RealTimeClock(VirtualTimeSource(), clock)
    val candidate =
        record(rtc.captureState())
            .replaceField("subSecondTicks", Int64State(clock.ticksPerSecond() - 1))
    val prepared = rtcState(candidate)

    StateSemantics.validateForClock(prepared, clock)
    rtc.restoreState(prepared)
    assertEquals(candidate, record(rtc.captureState()))
    rtc.tick()
    val continued = record(rtc.captureState())
    assertEquals(1, continued.int("seconds"))
    assertEquals(0L, continued.long("subSecondTicks"))
  }

  @Test
  fun customClockRtcPhaseAtRateIsRejectedBeforeMutation() {
    val clock = ClockSpec(8_388_608, 60, 1)
    val rtc = RealTimeClock(VirtualTimeSource(), clock)
    val before = record(rtc.captureState())
    val candidate =
        before.replaceField("subSecondTicks", Int64State(clock.ticksPerSecond()))
    val component = rtcState(candidate)

    // The positive phase is profile-independently bounded. Only the target session knows that it
    // is one tick beyond this RTC oscillator's valid range.
    StateSemantics.validate(component)
    val stages = mutableListOf<ApplyStage>()
    assertFailsWith<StateApplyException> {
      StateSemantics.validateForClock(component, clock)
      stages += ApplyStage.BEFORE_LIVE_MUTATION
      rtc.restoreState(component)
    }
    assertTrue(stages.isEmpty())
    assertEquals(before, record(rtc.captureState()))
  }

  @Test
  fun rationalSgbRtcPhaseUsesTargetOwnedUnitsAndRejectsItsExactBoundary() {
    val clock = ClockSpec.SGB
    val rtc = RealTimeClock(VirtualTimeSource(), clock)
    val before = record(rtc.captureState())
    val lastWholeTick = clock.secondPhaseLimit() - clock.secondPhaseUnitsPerTick().toLong()
    val valid = before.replaceField("subSecondTicks", Int64State(lastWholeTick))
    val prepared = rtcState(valid)

    StateSemantics.validateForClock(prepared, clock)
    rtc.restoreState(prepared)
    rtc.tick()
    val continued = record(rtc.captureState())
    assertEquals(1, continued.int("seconds"))
    assertEquals(0L, continued.long("subSecondTicks"))

    val stable = record(rtc.captureState())
    val invalid = stable.replaceField("subSecondTicks", Int64State(clock.secondPhaseLimit().toLong()))
    assertFailsWith<StateApplyException> {
      StateSemantics.validateForClock(rtcState(invalid), clock)
    }
    assertEquals(stable, record(rtc.captureState()))
  }

  @Test
  fun legacyFullSoundBufferRemainsCompatibleAndWrongTargetShapeIsAtomic() {
    session().use { session ->
      val before = session.captureDetachedState()
      val sound = before.machine.root.record(SOUND_STATE)
      val index = sound.int("i")
      val legacyCapacity = Math.multiplyExact(ClockSpec.LEGACY.controllerTicksPerFrame(), 2)
      val legacyRoot =
          before.machine.root.replaceRecordField(
              SOUND_STATE,
              "buffer",
              Int32ArrayState(IntArray(legacyCapacity)),
          )
      val compatible = before.withMachineRoot(legacyRoot)
      val stages = mutableListOf<ApplyStage>()

      DetachedStateAdapter.apply(session.gameboy, compatible.machine) { stages += it }
      assertEquals(listOf(ApplyStage.BEFORE_LIVE_MUTATION), stages)
      assertEquals(index, session.captureDetachedState().machine.root.record(SOUND_STATE).int("i"))

      val stable = session.captureDetachedState()
      val invalid =
          stable.withMachineRoot(
              stable.machine.root.replaceRecordField(
                  SOUND_STATE,
                  "buffer",
                  Int32ArrayState(IntArray(legacyCapacity + 2)),
              ))
      val invalidStages = mutableListOf<ApplyStage>()
      assertFailsWith<StateApplyException> {
        DetachedStateAdapter.apply(session, invalid) { invalidStages += it }
      }
      assertTrue(invalidStages.isEmpty())
      assertEquals(stable, session.captureDetachedState())
    }

    session(withRtc = true).use { session ->
      val stable = session.captureDetachedState()
      val invalid =
          stable.withMachineRoot(
              stable.machine.root.replaceRecordField(
                  RTC_STATE,
                  "subSecondTicks",
                  Int64State(ClockSpec.LEGACY.ticksPerSecond()),
              ))
      val stages = mutableListOf<ApplyStage>()
      assertFailsWith<StateApplyException> {
        DetachedStateAdapter.apply(session, invalid) { stages += it }
      }
      assertTrue(stages.isEmpty())
      assertEquals(stable, session.captureDetachedState())
    }
  }

  private fun sound(clock: ClockSpec): Sound {
    val speedMode = SpeedMode(true)
    return Sound(Timer(InterruptManager(true), speedMode), speedMode, true, clock)
  }

  @Suppress("UNCHECKED_CAST")
  private fun soundState(record: RecordState): ComponentState<Sound> =
      StateGraph.restore(record) as ComponentState<Sound>

  @Suppress("UNCHECKED_CAST")
  private fun rtcState(record: RecordState): ComponentState<RealTimeClock> =
      StateGraph.restore(record) as ComponentState<RealTimeClock>

  private fun record(state: ComponentState<*>): RecordState =
      StateGraph.capture(state) as RecordState

  private fun RecordState.replaceField(name: String, replacement: StateValue): RecordState =
      RecordState(
          typeId,
          fields.map { field -> if (field.name == name) StateField(name, replacement) else field },
      )

  private fun RecordState.record(className: String): RecordState {
    var result: RecordState? = null
    fun visit(value: StateValue) {
      when (value) {
        is RecordState -> {
          if (StateTypeRegistry.recordClassNames[value.typeId - 1] == className) result = value
          value.fields.forEach { visit(it.value) }
        }
        is ObjectArrayState -> value.values.forEach(::visit)
        is ListState -> value.values.forEach(::visit)
        is Int32MapState -> value.entries.forEach { visit(it.value) }
        else -> Unit
      }
    }
    visit(this)
    return checkNotNull(result) { "No $className record" }
  }

  private fun RecordState.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): RecordState {
    fun replace(value: StateValue): StateValue =
        when (value) {
          is RecordState -> {
            val owner = StateTypeRegistry.recordClassNames[value.typeId - 1] == ownerClass
            RecordState(
                value.typeId,
                value.fields.map { field ->
                  StateField(
                      field.name,
                      if (owner && field.name == fieldName) replacement else replace(field.value),
                  )
                },
            )
          }
          is ObjectArrayState -> ObjectArrayState(value.values.map(::replace))
          is ListState -> ListState(value.values.map(::replace))
          is Int32MapState ->
              Int32MapState(value.entries.map { Int32MapEntry(it.key, replace(it.value)) })
          else -> value
        }
    return replace(this) as RecordState
  }

  private fun RecordState.int(name: String): Int =
      (fields.single { it.name == name }.value as Int32State).value

  private fun RecordState.long(name: String): Long =
      (fields.single { it.name == name }.value as Int64State).value

  private fun RecordState.intArray(name: String): IntArray =
      (fields.single { it.name == name }.value as Int32ArrayState).copyValue()

  private fun SessionState.withMachineRoot(root: RecordState): SessionState =
      SessionState(
          MachineState(root, machine.rtcRuntime, machine.hardware, machine.dmgFifoRuntime),
          serialPeripheral,
          serialState,
          serialRuntime,
          heldButtons,
      )

  private fun session(withRtc: Boolean = false): Session {
    val rom = ByteArray(0x8000)
    rom[0x100] = 0x18
    rom[0x101] = 0xfe.toByte()
    if (withRtc) {
      rom[0x147] = 0x10
      rom[0x149] = 0x03
    }
    val configuration =
        Gameboy.GameboyConfiguration(Rom(rom))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
    return Session(configuration, EventBusImpl(), null)
  }

  private companion object {
    const val FRAME_SEQUENCER_STATE =
        "eu.rekawek.coffeegb.core.sound.FrameSequencer\$FrameSequencerState"
    const val LCDC_STATE = "eu.rekawek.coffeegb.core.gpu.Lcdc\$LcdcState"
    const val GPU_REGISTER_VALUES_STATE =
        "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesState"
    const val SOUND_STATE = "eu.rekawek.coffeegb.core.sound.Sound\$SoundState"
    const val RTC_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState"
  }
}
