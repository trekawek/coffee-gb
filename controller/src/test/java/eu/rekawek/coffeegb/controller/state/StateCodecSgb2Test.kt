package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class StateCodecSgb2Test {

  @Test
  fun sgb2UsesExplicitV2IdentityAndContinuesDeterministically() {
    val configuration = sgb2Configuration()
    StateCodecTestSupport.session(configuration).use { session ->
      repeat(9_123) { session.gameboy.tick() }
      val snapshot = StateCodec.encode(StateCodec.capture(session), StateCompression.DEFLATE)
      assertEquals(2, StateCodecTestSupport.readU16(snapshot, 4))
      val inspection = StateCodec.inspect(snapshot)
      assertEquals(2, inspection.formatVersion)
      assertEquals("sgb2", inspection.identities.single().identity!!.profile.canonicalProfileId)
      assertTrue(inspection.render().contains("profile=sgb2"))
      assertContentEquals(snapshot, StateCodec.encode(StateCodec.decode(snapshot), StateCompression.DEFLATE))

      repeat(7_777) { session.gameboy.tick() }
      val expected = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(snapshot, session)
      repeat(7_777) { session.gameboy.tick() }
      assertContentEquals(expected, StateCodec.encode(StateCodec.capture(session)))
    }
  }

  @Test
  fun sgbAndSgb2MismatchRejectsBeforeAnyLiveMutation() {
    val bytes =
        StateCodecTestSupport.session(sgb2Configuration()).use { source ->
          repeat(1_337) { source.gameboy.tick() }
          StateCodec.encode(StateCodec.capture(source))
        }
    val sgb =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
    StateCodecTestSupport.session(sgb).use { target ->
      val before = StateCodec.encode(StateCodec.capture(target))
      val stages = mutableListOf<ApplyStage>()
      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(bytes, target) { stages += it }
          }
      assertEquals(StateDecodeReason.HARDWARE_PROFILE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertContentEquals(before, StateCodec.encode(StateCodec.capture(target)))
    }
  }

  @Test
  fun v2ProfileIdRejectsCaseUnknownLengthTruncationAndChecksumCorruption() {
    val baseline =
        StateCodecTestSupport.session(sgb2Configuration()).use {
          StateCodec.encode(StateCodec.capture(it), StateCompression.NONE)
        }
    val sections = StateCodecTestSupport.sections(baseline)
    val identity = sections.single { it.id == 1 }
    val payload = sections.single { it.id == 2 }

    fun mutateIdentity(body: ByteArray, reason: StateDecodeReason) {
      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decode(
                StateCodecTestSupport.rawFile(
                    StateRootKind.SESSION,
                    listOf(identity.copy(body = body), payload),
                    formatVersion = 2,
                ))
          }
      assertEquals(reason, failure.reason)
    }

    mutateIdentity(identity.body.clone().also { it[52] = 'S'.code.toByte() },
        StateDecodeReason.MALFORMED_STRUCTURE)
    mutateIdentity(identity.body.clone().also { "sgb3".toByteArray().copyInto(it, 52) },
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH)
    mutateIdentity(identity.body.clone().also {
      StateCodecTestSupport.writeU16(it, 50, eu.rekawek.coffeegb.controller.StateLimits.PORTABLE_MAX_PROFILE_ID_BYTES + 1)
    }, StateDecodeReason.LIMIT_EXCEEDED)
    mutateIdentity(identity.body.copyOf(identity.body.size - 1), StateDecodeReason.TRUNCATED)

    val corrupt = baseline.clone().also { it[36] = (it[36].toInt() xor 1).toByte() }
    assertEquals(
        StateDecodeReason.CORRUPT_CHECKSUM,
        assertFailsWith<StateDecodeException> { StateCodec.decode(corrupt) }.reason,
    )
  }

  @Test
  fun releasedV1SgbIdentityAlwaysMeansSgb() {
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val current = StateCodec.capture(session)
      val legacy = current.asLegacyV1Sgb()
      val bytes = StateCodec.encode(legacy)
      assertEquals(1, StateCodecTestSupport.readU16(bytes, 4))
      assertEquals("sgb", StateCodec.decode(bytes).identities.single().identity!!.profile.canonicalProfileId)
      assertContentEquals(bytes, StateCodec.encode(StateCodec.decode(bytes)))
    }
  }

  @Test
  fun releasedV1SgbRtcPhaseKeepsItsLegacyFractionAndOneTickBoundary() {
    val configuration = sgbRtcConfiguration(HardwareProfileRegistry.SGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val conversionCases =
          listOf(
              0L to 0L,
              1L to 11L,
              131_072L to 1_476_563L, // exact half: nearest ties upward
              1_234_567L to 13_907_740L,
              2_097_152L to 23_625_000L,
          )
      for ((legacyPhase, exactPhase) in conversionCases) {
        val legacy = StateCodec.capture(session).withRtcPhase(legacyPhase).asLegacyV1Sgb()
        StateCodec.decodeAndApply(StateCodec.encode(legacy), session)
        assertEquals(
            exactPhase,
            StateCodec.capture(session).session().machine.root.record(RTC_STATE)
                .long("subSecondTicks"),
            "legacy phase $legacyPhase",
        )
      }

      val legacyPhase = LEGACY_RTC_PHASE_LIMIT - 1L
      val current = StateCodec.capture(session)
      val legacy =
          current
              .withRtcPhase(legacyPhase)
              .asLegacyV1Sgb()
      val bytes = StateCodec.encode(legacy, StateCompression.DEFLATE)

      // A decoded historical file remains a byte-exact v1 artifact. Conversion belongs only to
      // the target-aware apply preparation and must not alter inspection or migration re-encode.
      assertContentEquals(bytes, StateCodec.encode(StateCodec.decode(bytes), StateCompression.DEFLATE))

      StateCodec.decodeAndApply(bytes, session)
      val restored = StateCodec.capture(session).session().machine.root.record(RTC_STATE)
      assertEquals(ClockSpec.SGB.secondPhaseLimit() - 11L, restored.long("subSecondTicks"))
      assertEquals(0, restored.int("seconds"))

      // In the released domain 4,194,303 was one legacy tick before the next RTC second. The
      // converted exact-SGB phase must preserve that observable continuation.
      session.gameboy.tick()
      val continued = StateCodec.capture(session).session().machine.root.record(RTC_STATE)
      assertEquals(1, continued.int("seconds"))
      assertEquals(0L, continued.long("subSecondTicks"))
    }
  }

  @Test
  fun releasedV1SgbMachineRootUsesTheSamePreMutationRtcConversion() {
    val configuration = sgbRtcConfiguration(HardwareProfileRegistry.SGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val legacy =
          StateCodec.capture(configuration, session.gameboy)
              .withRtcPhase(LEGACY_RTC_PHASE_LIMIT - 1L)
              .asLegacyV1Sgb()
      val bytes = StateCodec.encode(legacy)
      val stages = mutableListOf<ApplyStage>()

      StateCodec.decodeAndApply(bytes, configuration, session.gameboy) { stages += it }

      assertEquals(listOf(ApplyStage.BEFORE_LIVE_MUTATION), stages)
      assertEquals(
          ClockSpec.SGB.secondPhaseLimit() - 11L,
          StateCodec.capture(configuration, session.gameboy).machine().root.record(RTC_STATE)
              .long("subSecondTicks"),
      )
      session.gameboy.tick()
      val continued = StateCodec.capture(configuration, session.gameboy).machine().root.record(RTC_STATE)
      assertEquals(1, continued.int("seconds"))
      assertEquals(0L, continued.long("subSecondTicks"))
    }
  }

  @Test
  fun newExactSgbCaptureUsesV2RatherThanAmbiguousV1PhaseSemantics() {
    StateCodecTestSupport.session(sgbRtcConfiguration(HardwareProfileRegistry.SGB)).use { session ->
      repeat(1_337) { session.gameboy.tick() }
      val file = StateCodec.capture(session)
      val bytes = StateCodec.encode(file)

      assertEquals(2, file.formatVersion)
      assertEquals(2, StateCodecTestSupport.readU16(bytes, 4))
      assertEquals("sgb", StateCodec.inspect(bytes).identities.single().identity!!.profile.canonicalProfileId)
      assertContentEquals(bytes, StateCodec.encode(StateCodec.decode(bytes)))

      val forcedV1 = StateFile(file.identities, file.root, file.diagnostics, 1)
      assertFailsWith<StateEncodeException> { StateCodec.encode(forcedV1) }
    }
  }

  @Test
  fun malformedReleasedV1SgbRtcPhaseRejectsBeforeMutation() {
    val configuration = sgbRtcConfiguration(HardwareProfileRegistry.SGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val before = StateCodec.encode(StateCodec.capture(session))
      val malformed =
          StateCodec.encode(
              StateCodec.capture(session)
                  .withRtcPhase(LEGACY_RTC_PHASE_LIMIT)
                  .asLegacyV1Sgb(),
          )
      val stages = mutableListOf<ApplyStage>()

      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(malformed, session) { stages += it }
          }

      assertEquals(StateDecodeReason.MALFORMED_STRUCTURE, failure.reason)
      assertTrue(stages.isEmpty())
      assertContentEquals(before, StateCodec.encode(StateCodec.capture(session)))
    }
  }

  @Test
  fun exactSgbFamilyV2PauseRestorePreservesZeroNonzeroAndBoundaryPhases() {
    for (profile in listOf(HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2)) {
      val limit = profile.clockSpec().secondPhaseLimit().toLong()
      for (phase in listOf(0L, 1_234_567L, limit - 1L)) {
        val time = VirtualTimeSource(120_000)
        StateCodecTestSupport.session(sgbRtcConfiguration(profile, time)).use { session ->
          val seeded = StateCodec.capture(session).withRtcPhase(phase)
          assertEquals(2, seeded.formatVersion)
          StateCodec.decodeAndApply(StateCodec.encode(seeded), session)
          session.gameboy.setCartridgeClockPaused(true)
          val paused = StateCodec.encode(StateCodec.capture(session), StateCompression.DEFLATE)

          // Diverge after the safe point, then record the exact continuation from the paused
          // phase. No wall time passes, so unpause changes only pause bookkeeping.
          session.gameboy.setCartridgeClockPaused(false)
          repeat(17) { session.gameboy.tick() }
          val expected = StateCodec.encode(StateCodec.capture(session))

          StateCodec.decodeAndApply(paused, session)
          assertTrue(StateCodec.capture(session).session().machine.rtcRuntime.primary!!.emulationPaused)
          assertEquals(
              phase,
              StateCodec.capture(session).session().machine.root.record(RTC_STATE)
                  .long("subSecondTicks"),
          )
          session.gameboy.setCartridgeClockPaused(false)
          repeat(17) { session.gameboy.tick() }
          assertContentEquals(expected, StateCodec.encode(StateCodec.capture(session)), profile.id())
        }
      }
    }
  }

  private fun sgb2Configuration(): Gameboy.GameboyConfiguration =
      StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
          .setHardwareProfile(HardwareProfileRegistry.SGB2)

  private fun sgbRtcConfiguration(
      profile: HardwareProfile,
      time: VirtualTimeSource = VirtualTimeSource(120_000),
  ): Gameboy.GameboyConfiguration {
    val rom =
        StateCodecTestSupport.rom(sgb = true).also {
          it[0x147] = 0x10
          it[0x149] = 0x03
        }
    return StateCodecTestSupport.configuration(rom, GameboyType.SGB)
        .setHardwareProfile(profile)
        .setRtcTimeSource(time)
  }

  private fun StateFile.asLegacyV1Sgb(): StateFile =
      StateFile(
          identities.map { entry ->
            StateIdentityEntry(
                entry.player,
                entry.identity?.let { identity ->
                  MachineIdentity(
                      identity.primaryRom,
                      identity.slotRom,
                      identity.profile.copy(explicitProfileId = null),
                  )
                },
            )
          },
          root,
          diagnostics,
          StateCodec.V1_FORMAT_VERSION,
      )

  private fun StateFile.withRtcPhase(phase: Long): StateFile {
    fun changed(machine: MachineState) =
        MachineState(
            machine.root.replaceRecordField(
                RTC_STATE,
                "subSecondTicks",
                Int64State(phase),
            ),
            machine.rtcRuntime,
            machine.hardware,
            machine.dmgFifoRuntime,
        )
    val changedRoot =
        when (val current = root) {
          is MachineStateRoot -> MachineStateRoot(changed(current.machine))
          is SessionStateRoot -> {
            val session = current.session
            SessionStateRoot(
                SessionState(
                    changed(session.machine),
                    session.serialPeripheral,
                    session.serialState,
                    session.serialRuntime,
                    session.heldButtons,
                ))
          }
          is LinkedSessionStateRoot -> error("This test helper does not mutate linked roots")
        }
    return StateFile(identities, changedRoot, diagnostics, formatVersion)
  }

  private fun StateFile.session(): SessionState =
      (root as SessionStateRoot).session

  private fun StateFile.machine(): MachineState =
      (root as MachineStateRoot).machine

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

  private companion object {
    const val LEGACY_RTC_PHASE_LIMIT = 4_194_304L
    const val RTC_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState"
  }
}
