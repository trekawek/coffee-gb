package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateCodecTest {

  @Test
  fun sessionEncodingIsDeterministicInBothModesAndRestoresAtomically() {
    val endpoint = BarcodeBoySerialEndpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      endpoint.scan("4901234567894")
      session.heldButtons = setOf(Button.A, Button.RIGHT)
      repeat(12_345) { session.gameboy.tick() }
      val file =
          StateCodec.capture(
              session,
              StateDiagnosticMetadata("test-core", "deterministic-build"),
          )

      StateCompression.entries.forEach { compression ->
        val first = StateCodec.encode(file, compression)
        val second = StateCodec.encode(file, compression)
        assertContentEquals(first, second, compression.name)
        assertEquals(file, StateCodec.decode(first), compression.name)

        val inspection = StateFileInspector.inspect(first)
        assertEquals(StateRootKind.SESSION, inspection.rootKind)
        assertEquals(compression, inspection.compression)
        assertTrue(inspection.checksumValid)
        assertEquals(listOf(1, 2, 3), inspection.sections.map { it.id })
        assertEquals(file.diagnostics, inspection.diagnostics)
        assertTrue(inspection.render().contains("magic=CGBS format=1"))
        assertTrue(inspection.render().contains("core=test-core build=deterministic-build"))

        StateCodec.decodeAndApply(first, session)
        repeat(256) { session.gameboy.tick() }
        val expectedContinuation = session.captureDetachedState()
        repeat(777) { session.gameboy.tick() }
        session.heldButtons = setOf(Button.B)
        endpoint.scan("4006381333931")
        assertNotEquals(file.root, StateCodec.capture(session).root)
        val stages = mutableListOf<ApplyStage>()
        StateCodec.decodeAndApply(first, session) { stages += it }
        assertEquals(
            listOf(ApplyStage.BEFORE_LIVE_MUTATION, ApplyStage.AFTER_MACHINE_MUTATION),
            stages,
        )
        repeat(256) { session.gameboy.tick() }
        val actualContinuation = session.captureDetachedState()
        assertEquals(
            expectedContinuation.machine.root,
            actualContinuation.machine.root,
            firstDifference(expectedContinuation.machine.root, actualContinuation.machine.root),
        )
        assertEquals(expectedContinuation, actualContinuation)
      }
    }
  }

  @Test
  fun machineRootRoundTripsWithoutAHostServiceOrRomPayload() {
    val configuration = StateCodecTestSupport.configuration()
    val gameboy = configuration.build()
    try {
      gameboy.init(
          eu.rekawek.coffeegb.core.events.EventBusImpl(),
          SerialEndpoint.NULL_ENDPOINT,
          null,
      )
      repeat(9_001) { gameboy.tick() }
      val file = StateCodec.capture(configuration, gameboy)
      val bytes = StateCodec.encode(file)
      val decoded = StateCodec.decode(bytes)
      assertEquals(file, decoded)
      repeat(123) { gameboy.tick() }
      StateCodec.applyDecoded(decoded, configuration, gameboy)
      assertEquals(file.root, StateCodec.capture(configuration, gameboy).root)
    } finally {
      gameboy.stop()
      gameboy.close()
    }
  }

  @Test
  fun decodedMachineCompatibilityIsClassifiedWithoutLiveMutation() {
    val configuration = StateCodecTestSupport.configuration()
    val gameboy = configuration.build()
    try {
      gameboy.init(
          eu.rekawek.coffeegb.core.events.EventBusImpl(),
          SerialEndpoint.NULL_ENDPOINT,
          null,
      )
      val file = StateCodec.capture(configuration, gameboy)
      val before = StateCodec.capture(configuration, gameboy)

      val compatible = StateCodec.classifyCompatibility(file, configuration)
      assertEquals(StateCompatibilityStatus.COMPATIBLE, compatible.status)
      assertTrue(compatible.isCompatible)
      assertNull(compatible.reason)

      val wrongRom =
          StateCodec.classifyCompatibility(
              file,
              StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = 7)),
          )
      assertEquals(StateCompatibilityStatus.ROM_MISMATCH, wrongRom.status)
      assertEquals(StateDecodeReason.ROM_MISMATCH, wrongRom.reason)
      assertTrue(!wrongRom.isCompatible)

      val wrongProfileConfiguration =
          StateCodecTestSupport.configuration().setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
      val wrongProfile = StateCodec.classifyCompatibility(file, wrongProfileConfiguration)
      assertEquals(StateCompatibilityStatus.HARDWARE_PROFILE_MISMATCH, wrongProfile.status)
      assertEquals(StateDecodeReason.HARDWARE_PROFILE_MISMATCH, wrongProfile.reason)

      val wrongRoot =
          StateCodec.classifyCompatibility(
              file,
              StateRootKind.SESSION,
              file.identities,
          )
      assertEquals(StateCompatibilityStatus.ROOT_MISMATCH, wrongRoot.status)
      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, wrongRoot.reason)

      assertEquals(before, StateCodec.capture(configuration, gameboy))
    } finally {
      gameboy.stop()
      gameboy.close()
    }
  }

  @Test
  fun identityProfilesCoverDmgCgbSgbAndDatelSlotWithoutRomBytes() {
    val configurations =
        listOf(
            StateCodecTestSupport.configuration(hardware = GameboyType.DMG),
            StateCodecTestSupport.configuration(StateCodecTestSupport.rom(cgb = true), GameboyType.CGB)
                .setCgb0Revision(true)
                .setMealybugDmgBlob(true)
                .setCodeBreakerRumble(true),
            StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
                .setDisplaySgbBorder(false),
            StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
                .setHardwareProfile(HardwareProfileRegistry.SGB2),
            StateCodecTestSupport.configuration(StateCodecTestSupport.datelRom(), GameboyType.CGB)
                .setSlotRom(Rom(StateCodecTestSupport.rom(seed = 3, cgb = true))),
            StateCodecTestSupport.configuration(StateCodecTestSupport.datelRom(), GameboyType.CGB)
                .setSlotRom(Rom(StateCodecTestSupport.datelRom())),
        )
    configurations.forEach { configuration ->
      StateCodecTestSupport.session(configuration).use { session ->
        val encoded = StateCodec.encode(StateCodec.capture(session))
        val identity = assertNotNull(StateCodec.decode(encoded).identities.single().identity)
        assertEquals(configuration.hardwareProfile.id(), identity.profile.canonicalProfileId)
        assertEquals(MachineHardwareState.valueOf(configuration.gameboyType.name), identity.profile.hardware)
        assertEquals(
            configuration.gameboyType == GameboyType.CGB && configuration.isCgb0Revision,
            identity.profile.cgb0Revision,
        )
        assertEquals(
            configuration.gameboyType != GameboyType.CGB && configuration.isMealybugDmgBlob,
            identity.profile.mealybugDmgBlob,
        )
        assertEquals(configuration.isCodeBreakerRumble, identity.profile.codeBreakerRumble)
        assertEquals(
            configuration.gameboyType == GameboyType.SGB && configuration.isDisplaySgbBorder,
            identity.profile.displaySgbBorder,
        )
        if (configuration.slotRom == null) assertNull(identity.slotRom)
        else assertEquals(StateIdentity.hash(configuration.slotRom), identity.slotRom)
        // The public identity carries only the digest; StateCodec never accepts ROM contents as a
        // section value. Mapper mementos contain mutable controller state, not cartridge ROM.
        assertEquals(32, identity.primaryRom.copyBytes().size)
        assertTrue(StateCodec.inspect(encoded).render().contains("profile=${configuration.hardwareProfile.id()}"))
      }
    }
  }

  @Test
  fun everyProfileMismatchAndRomIdentityMismatchRejectBeforeMutation() {
    val baseBytes = StateCodecTestSupport.rom(cgb = true)
    val cgbSources =
        listOf<Pair<String, (Gameboy.GameboyConfiguration) -> Unit>>(
            "cgb0" to { it.setCgb0Revision(true) },
            "codebreaker" to { it.setCodeBreakerRumble(true) },
            "bootstrap" to { it.setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD) },
        )
    cgbSources.forEach { (label, configure) ->
      val sourceConfig = StateCodecTestSupport.configuration(baseBytes, GameboyType.CGB)
      configure(sourceConfig)
      val targetConfig = StateCodecTestSupport.configuration(baseBytes, GameboyType.CGB)
      assertPreMutationRejection(
          sourceConfig,
          targetConfig,
          StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
          label,
      )
    }
    assertPreMutationRejection(
        StateCodecTestSupport.configuration(hardware = GameboyType.DMG)
            .setMealybugDmgBlob(true),
        StateCodecTestSupport.configuration(hardware = GameboyType.DMG),
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
        "mealybug",
    )
    assertPreMutationRejection(
        StateCodecTestSupport.configuration(
                StateCodecTestSupport.rom(sgb = true),
                GameboyType.SGB,
            )
            .setDisplaySgbBorder(false),
        StateCodecTestSupport.configuration(
            StateCodecTestSupport.rom(sgb = true),
            GameboyType.SGB,
        ),
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
        "SGB border",
    )
    assertPreMutationRejection(
        StateCodecTestSupport.configuration(baseBytes, GameboyType.CGB),
        StateCodecTestSupport.configuration(baseBytes, GameboyType.DMG),
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
        "hardware",
    )
    assertPreMutationRejection(
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = 1)),
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = 2)),
        StateDecodeReason.ROM_MISMATCH,
        "primary ROM",
    )

    val datel = StateCodecTestSupport.datelRom()
    val sourceSlot =
        StateCodecTestSupport.configuration(datel, GameboyType.CGB)
            .setSlotRom(Rom(StateCodecTestSupport.rom(seed = 1)))
    val absentSlot = StateCodecTestSupport.configuration(datel, GameboyType.CGB)
    assertPreMutationRejection(
        sourceSlot,
        absentSlot,
        StateDecodeReason.SLOT_ROM_MISMATCH,
        "slot presence",
    )
    val otherSlot =
        StateCodecTestSupport.configuration(datel, GameboyType.CGB)
            .setSlotRom(Rom(StateCodecTestSupport.rom(seed = 2)))
    assertPreMutationRejection(
        sourceSlot,
        otherSlot,
        StateDecodeReason.SLOT_ROM_MISMATCH,
        "slot hash",
    )
  }

  @Test
  fun targetPeripheralMismatchIsTypedAndAtomic() {
    val configuration = StateCodecTestSupport.configuration()
    StateCodecTestSupport.session(configuration, ByteReceivingSerialEndpoint { }).use { source ->
      StateCodecTestSupport.session(configuration).use { target ->
        val encoded = StateCodec.encode(StateCodec.capture(source))
        val before = target.captureDetachedState()
        val stages = mutableListOf<ApplyStage>()
        val failure =
            assertFailsWith<StateDecodeException> {
              StateCodec.decodeAndApply(encoded, target) { stages += it }
            }
        assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
        assertTrue(stages.isEmpty())
        assertEquals(before, target.captureDetachedState())
      }
    }
  }

  @Test
  fun everyStandaloneSerialIdentityAndBothRtcLocationsRoundTrip() {
    val endpoints =
        listOf(
            SerialEndpoint.NULL_ENDPOINT,
            ByteReceivingSerialEndpoint { },
            Peer2PeerSerialEndpoint(),
            GameboyPrinterSerialEndpoint { _, _, _, _, _, _ -> },
            GpsReceiverSerialEndpoint(),
            BarcodeBoySerialEndpoint().also { it.scan("4901234567894") },
            MobileAdapterSerialEndpoint(ClockSpec.LEGACY, 0x08, ByteArray(256)),
        )
    endpoints.forEach { endpoint ->
      StateCodecTestSupport.session(endpoint = endpoint).use { session ->
        session.heldButtons = Button.entries.toSet()
        val file = StateCodec.capture(session)
        assertEquals(file, StateCodec.decode(StateCodec.encode(file)))
      }
    }

    val mbc3 = StateCodecTestSupport.rom(seed = 4).also { it[0x147] = 0x0f }
    StateCodecTestSupport.session(StateCodecTestSupport.configuration(mbc3)).use { session ->
      val decoded = StateCodec.decode(StateCodec.encode(StateCodec.capture(session)))
      val runtime = (decoded.root as SessionStateRoot).session.machine.rtcRuntime
      assertNotNull(runtime.primary)
      assertNull(runtime.slot)
    }
    val slotMbc3 = StateCodecTestSupport.rom(seed = 5).also { it[0x147] = 0x0f }
    val datel =
        StateCodecTestSupport.configuration(StateCodecTestSupport.datelRom(), GameboyType.CGB)
            .setSlotRom(Rom(slotMbc3))
    StateCodecTestSupport.session(datel).use { session ->
      val decoded = StateCodec.decode(StateCodec.encode(StateCodec.capture(session)))
      val runtime = (decoded.root as SessionStateRoot).session.machine.rtcRuntime
      assertNull(runtime.primary)
      assertNotNull(runtime.slot)
    }
  }

  @Test
  fun corruptTruncatedOversizedAndSemanticInputsNeverReachLiveMutation() {
    StateCodecTestSupport.session().use { session ->
      val file = StateCodec.capture(session)
      val baseline = StateCodec.encode(file)
      val corrupt = baseline.clone().also { it[36] = (it[36].toInt() xor 1).toByte() }
      val oversized =
          baseline.clone().also {
            StateCodecTestSupport.writeLong(
                it,
                28,
                StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES.toLong() + 1,
            )
          }
      assertRejectedWithoutMutation(session, corrupt, StateDecodeReason.CORRUPT_CHECKSUM)
      assertRejectedWithoutMutation(
          session,
          baseline.copyOf(baseline.size - 1),
          StateDecodeReason.TRUNCATED,
      )
      assertRejectedWithoutMutation(session, oversized, StateDecodeReason.LIMIT_EXCEEDED)

      val root = (file.root as SessionStateRoot).session
      val invalidRoot =
          root.machine.root.replaceRecordField(
              "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState",
              "i",
              Int32State(-1),
          )
      val invalidSession =
          SessionState(
              MachineState(
                  invalidRoot,
                  root.machine.rtcRuntime,
                  root.machine.hardware,
                  root.machine.dmgFifoRuntime,
              ),
              root.serialPeripheral,
              root.serialState,
              root.serialRuntime,
              root.heldButtons,
          )
      val invalid =
          StateCodec.encode(
              StateFile(file.identities, SessionStateRoot(invalidSession)),
          )
      val beforePreflight = session.captureDetachedState()
      val preflightFailure =
          assertFailsWith<StateDecodeException> {
            StateCodec.validateDecodedForApply(
                StateCodec.decode(invalid),
                session,
                StateIdentity.from(session.config),
            )
          }
      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, preflightFailure.reason)
      assertEquals(beforePreflight, session.captureDetachedState())
      assertRejectedWithoutMutation(session, invalid, StateDecodeReason.TARGET_STATE_MISMATCH)
    }
  }

  private fun assertPreMutationRejection(
      sourceConfiguration: Gameboy.GameboyConfiguration,
      targetConfiguration: Gameboy.GameboyConfiguration,
      reason: StateDecodeReason,
      label: String,
  ) {
    StateCodecTestSupport.session(sourceConfiguration).use { source ->
      StateCodecTestSupport.session(targetConfiguration).use { target ->
        val encoded = StateCodec.encode(StateCodec.capture(source))
        val before = target.captureDetachedState()
        val stages = mutableListOf<ApplyStage>()
        val failure =
            assertFailsWith<StateDecodeException>(label) {
              StateCodec.decodeAndApply(encoded, target) { stages += it }
            }
        assertEquals(reason, failure.reason, label)
        assertTrue(stages.isEmpty(), label)
        assertEquals(before, target.captureDetachedState(), label)
      }
    }
  }

  private fun assertRejectedWithoutMutation(
      session: Session,
      bytes: ByteArray,
      reason: StateDecodeReason,
  ) {
    val before = session.captureDetachedState()
    val stages = mutableListOf<ApplyStage>()
    val failure =
        assertFailsWith<StateDecodeException> {
          StateCodec.decodeAndApply(bytes, session) { stages += it }
        }
    assertEquals(reason, failure.reason)
    assertTrue(stages.isEmpty())
    assertEquals(before, session.captureDetachedState())
  }

  private fun RecordState.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): RecordState {
    fun replace(value: StateValue): StateValue =
        when (value) {
          is RecordState ->
              RecordState(
                  value.typeId,
                  value.fields.map { field ->
                    val owner = StateTypeRegistry.recordClassNames[value.typeId - 1] == ownerClass
                    StateField(
                        field.name,
                        if (owner && field.name == fieldName) replacement
                        else replace(field.value),
                    )
                  },
              )
          is ObjectArrayState -> ObjectArrayState(value.values.map(::replace))
          is ListState -> ListState(value.values.map(::replace))
          is Int32MapState ->
              Int32MapState(value.entries.map { Int32MapEntry(it.key, replace(it.value)) })
          else -> value
        }
    return replace(this) as RecordState
  }

  private fun firstDifference(
      expected: StateValue,
      actual: StateValue,
      path: String = "root",
  ): String {
    if (expected == actual) return "states are equal"
    if (expected::class != actual::class) return "$path: ${expected::class} != ${actual::class}"
    return when {
      expected is RecordState && actual is RecordState ->
          expected.fields.indices.firstNotNullOfOrNull { index ->
            val left = expected.fields[index]
            val right = actual.fields.getOrNull(index)
                ?: return@firstNotNullOfOrNull "$path: missing field"
            if (left.value == right.value) null
            else firstDifference(left.value, right.value, "$path.${left.name}")
          } ?: "$path: record metadata differs"
      expected is ObjectArrayState && actual is ObjectArrayState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: object-array metadata differs"
      expected is ListState && actual is ListState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: list metadata differs"
      else -> "$path: $expected != $actual"
    }
  }
}
