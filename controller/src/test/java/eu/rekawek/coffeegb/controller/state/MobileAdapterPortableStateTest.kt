package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.serial.mobile.DeterministicMobileAdapterBackend
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterPortableStateTest {

  @Test
  fun partialPacketRoundTripsWithoutAHostHandleAndContinuesExactly() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      val begin = packet(0x10, "NINTENDO".encodeToByteArray())
      feed(endpoint, begin.copyOf(6))
      endpoint.reservePendingPacketSlot()
      repeat(42) { endpoint.tick() }

      val file = StateCodec.capture(session)
      val root = (file.root as SessionStateRoot).session
      assertEquals(SerialPeripheralState.MOBILE_ADAPTER_GB, root.serialPeripheral)
      assertEquals(NoSerialRuntimeState, root.serialRuntime)
      assertEquals(
          listOf(MOBILE_ENGINE_STATE, MOBILE_ENDPOINT_STATE),
          recordNames(root.serialState).sorted(),
      )
      val encoded = StateCodec.encode(file)
      assertEquals(file, StateCodec.decode(encoded))

      feed(endpoint, begin.copyOfRange(6, begin.size))
      val expected = endpoint.snapshot()
      assertEquals(
          MobileAdapterBackendPort.OfferResult.ACCEPTED,
          backend.offer(
              backend.generation(),
              MobileAdapterBackendPort.BackendRequest(7, 0x42, byteArrayOf(1, 2, 3))),
      )

      StateCodec.decodeAndApply(encoded, session)
      assertEquals(1, backend.cancellations)
      assertEquals(0, backend.occupiedRequestSlots())
      feed(endpoint, begin.copyOfRange(6, begin.size))
      assertEngineResultEquals(expected, endpoint.snapshot())
    }
  }

  @Test
  fun variableOutputLengthsAndRepresentativeTerminalOutcomesRestoreAcrossLiveShapes() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      val empty = StateCodec.encode(StateCodec.capture(session))

      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      val nonEmpty = StateCodec.encode(StateCodec.capture(session))
      assertTrue(endpoint.snapshot().responsePacket().isNotEmpty())
      StateCodec.decodeAndApply(empty, session)
      assertTrue(endpoint.snapshot().responsePacket().isEmpty())
      StateCodec.decodeAndApply(nonEmpty, session)
      assertTrue(endpoint.snapshot().responsePacket().isNotEmpty())

      feed(endpoint, packet(0x19, byteArrayOf(0, 1)))
      endpoint.replaceConfiguration(ByteArray(256) { 0x5a })
      val historicalConfigurationResponse = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(historicalConfigurationResponse, session)
      assertEquals(MobileAdapterEngine.Outcome.CONFIG_READ, endpoint.snapshot().outcome())
      assertEquals(0x5a, endpoint.configurationCopy()[0].toInt() and 0xff)

      feed(endpoint, packet(0x11, byteArrayOf()))
      assertTrue(endpoint.reservePendingPacketSlot())
      val terminalWithReservedSlot = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(terminalWithReservedSlot, session)
      assertEquals(MobileAdapterEngine.Outcome.SESSION_ENDED, endpoint.snapshot().outcome())
      assertEquals(1, endpoint.snapshot().pendingPacketSlots())
    }
  }

  @Test
  fun semanticCorruptionRejectsBeforeMachineEndpointOrBackendMutation() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, byteArrayOf(0x99.toByte(), 0x66, 0x10, 0, 0, 8))
      val file = StateCodec.capture(session)
      val root = (file.root as SessionStateRoot).session
      val before = session.captureDetachedState()
      val corruptions =
          listOf(
              "packet count" to
                  ("packetCount" to Int32State(MobileAdapterEngine.MAX_PACKET_BYTES + 1)),
              "serial ownership" to ("serialByteObserved" to BooleanState(false)),
          )
      corruptions.forEach { (label, mutation) ->
        val corrupted = file.replaceMobileEngineField(mutation.first, mutation.second)
        val stages = mutableListOf<ApplyStage>()

        val failure =
            assertFailsWith<StateDecodeException>(label) {
              StateCodec.decodeAndApply(StateCodec.encode(corrupted), session) { stages += it }
            }

        assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason, label)
        assertTrue(stages.isEmpty(), label)
        assertEquals(0, backend.cancellations, label)
        assertEquals(before, session.captureDetachedState(), label)
      }
    }
  }

  @Test
  fun rationalClockRejectsAnUnalignedIdlePhaseBeforeMutation() {
    val configuration =
        StateCodecTestSupport.configuration(
            bytes = StateCodecTestSupport.rom(sgb = true),
            hardware = GameboyType.SGB,
        )
    val endpoint = endpoint(clockSpec = configuration.clockSpec)
    StateCodecTestSupport.session(configuration, endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()).copyOf(6))
      val file = StateCodec.capture(session)
      val corrupted = file.replaceMobileEngineField("idlePhaseUnits", Int64State(1))
      val before = session.captureDetachedState()
      val stages = mutableListOf<ApplyStage>()

      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(StateCodec.encode(corrupted), session) { stages += it }
          }

      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertEquals(before, session.captureDetachedState())
    }
  }

  @Test
  fun activeSessionWithoutSerialOwnershipRejectsBeforeBackendMutation() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      assertTrue(endpoint.reservePendingPacketSlot())
      assertTrue(endpoint.reservePendingPacketSlot())
      assertTrue(!endpoint.reservePendingPacketSlot())
      endpoint.completePendingPacketSlot()
      assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, endpoint.snapshot().outcome())
      val file = StateCodec.capture(session)
      val corrupted =
          file.replaceMobileEngineField("serialByteObserved", BooleanState(false))
      val before = session.captureDetachedState()
      val stages = mutableListOf<ApplyStage>()

      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(StateCodec.encode(corrupted), session) { stages += it }
          }

      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertEquals(0, backend.cancellations)
      assertEquals(before, session.captureDetachedState())
    }
  }

  private fun endpoint(
      backend: MobileAdapterBackendPort = MobileAdapterBackendPort.DISCONNECTED,
      clockSpec: ClockSpec = ClockSpec.LEGACY,
  ) =
      MobileAdapterSerialEndpoint(
          clockSpec,
          DEVICE_ID,
          configuration(),
          backend,
      )

  private fun StateFile.replaceMobileEngineField(
      fieldName: String,
      replacement: StateValue,
  ): StateFile {
    val session = (root as SessionStateRoot).session
    return StateFile(
        identities,
        SessionStateRoot(
            SessionState(
                session.machine,
                session.serialPeripheral,
                session.serialState.replaceRecordField(
                    MOBILE_ENGINE_STATE,
                    fieldName,
                    replacement,
                ),
                session.serialRuntime,
                session.heldButtons,
            )),
        diagnostics,
        formatVersion,
    )
  }

  private fun feed(endpoint: MobileAdapterSerialEndpoint, bytes: ByteArray) {
    bytes.forEach { byte ->
      endpoint.setSb(byte.toInt() and 0xff)
      endpoint.startSending()
      repeat(8) { endpoint.sendBit() }
    }
  }

  private fun packet(command: Int, data: ByteArray): ByteArray {
    require(data.size <= MobileAdapterEngine.MAX_PACKET_DATA_BYTES)
    val bytes = ByteArray(8 + data.size)
    bytes[0] = 0x99.toByte()
    bytes[1] = 0x66
    bytes[2] = command.toByte()
    bytes[4] = (data.size ushr 8).toByte()
    bytes[5] = data.size.toByte()
    data.copyInto(bytes, 6)
    var checksum = 0
    for (index in 2 until 6 + data.size) {
      checksum = (checksum + (bytes[index].toInt() and 0xff)) and 0xffff
    }
    bytes[6 + data.size] = (checksum ushr 8).toByte()
    bytes[7 + data.size] = checksum.toByte()
    return bytes
  }

  private fun configuration() =
      ByteArray(MobileAdapterEngine.CONFIGURATION_BYTES).also { bytes ->
        bytes[0] = 0x4d
        bytes[1] = 0x41
        bytes[2] = 0x81.toByte()
        repeat(128) { bytes[128 + it] = it.toByte() }
      }

  private fun recordNames(value: StateValue): List<String> =
      when (value) {
        is RecordState ->
            listOf(StateTypeRegistry.recordClassNames[value.typeId - 1]) +
                value.fields.flatMap { recordNames(it.value) }
        is ObjectArrayState -> value.values.flatMap(::recordNames)
        is ListState -> value.values.flatMap(::recordNames)
        is Int32MapState -> value.entries.flatMap { recordNames(it.value) }
        else -> emptyList()
      }

  private fun StateValue.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): StateValue =
      when (this) {
        is RecordState ->
            RecordState(
                typeId,
                fields.map { field ->
                  val owner = StateTypeRegistry.recordClassNames[typeId - 1] == ownerClass
                  StateField(
                      field.name,
                      if (owner && field.name == fieldName) replacement
                      else field.value.replaceRecordField(ownerClass, fieldName, replacement),
                  )
                },
            )
        is ObjectArrayState ->
            ObjectArrayState(values.map { it.replaceRecordField(ownerClass, fieldName, replacement) })
        is ListState ->
            ListState(values.map { it.replaceRecordField(ownerClass, fieldName, replacement) })
        is Int32MapState ->
            Int32MapState(
                entries.map {
                  Int32MapEntry(
                      it.key,
                      it.value.replaceRecordField(ownerClass, fieldName, replacement),
                  )
                })
        else -> this
      }

  private fun assertEngineResultEquals(
      expected: MobileAdapterEngine.EngineResult,
      actual: MobileAdapterEngine.EngineResult,
  ) {
    assertEquals(expected.phase(), actual.phase())
    assertEquals(expected.outcome(), actual.outcome())
    assertEquals(expected.error(), actual.error())
    assertContentEquals(expected.responsePacket(), actual.responsePacket())
    assertContentEquals(expected.acknowledgement(), actual.acknowledgement())
    assertEquals(expected.retainedBytes(), actual.retainedBytes())
    assertEquals(expected.pendingPacketSlots(), actual.pendingPacketSlots())
  }

  private class TrackingBackend(
      private val delegate: DeterministicMobileAdapterBackend =
          DeterministicMobileAdapterBackend(),
  ) : MobileAdapterBackendPort by delegate {
    var cancellations = 0
      private set

    override fun cancelAll() {
      cancellations++
      delegate.cancelAll()
    }
  }

  private companion object {
    const val DEVICE_ID = 0x08
    const val MOBILE_ENGINE_STATE =
        "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineState"
    const val MOBILE_ENDPOINT_STATE =
        "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointState"
  }
}
