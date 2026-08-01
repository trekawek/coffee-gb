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
  fun activeWireTransactionRoundTripsThroughThePortableCodec() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feedRaw(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      val file = StateCodec.capture(session)
      val root = (file.root as SessionStateRoot).session
      assertEquals(
          listOf(MOBILE_ENGINE_STATE, MOBILE_WIRE_ENDPOINT_STATE),
          recordNames(root.serialState).sorted(),
      )

      // Move the live endpoint back to its released record shape before applying the active-wire
      // candidate. One endpoint legitimately alternates between these additive record types.
      assertEquals(0x88, exchange(endpoint, 0x80))
      assertEquals(0x90, exchange(endpoint, 0))
      drainResponse(endpoint)
      assertTrue(
          endpoint.captureState() is
              MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState)

      StateCodec.decodeAndApply(StateCodec.encode(file), session)
      val wireState =
          endpoint.captureState()
              as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState
      assertEquals(2, wireState.wirePhaseId())
      assertEquals(0x88, exchange(endpoint, 0x80))
      assertEquals(0x90, exchange(endpoint, 0))
      drainResponse(endpoint)
    }
  }

  @Test
  fun completedAsyncResponseRetainsItsWireAckAcrossPortableRoundTrip() {
    val backend = DeterministicMobileAdapterBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feedRaw(endpoint, packet(0x28, "service.test".encodeToByteArray()))
      assertEquals(0x88, exchange(endpoint, 0x80))
      assertEquals(0xa8, exchange(endpoint, 0))
      assertEquals(0xd2, exchange(endpoint, 0))
      assertEquals(0xd2, exchange(endpoint, 0x4b))
      assertEquals(
          MobileAdapterBackendPort.CompletionResult.COMPLETED,
          backend.complete(backend.generation(), 0, byteArrayOf(127, 0, 0, 1)),
      )
      assertEquals(
          MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
          endpoint.pollBackendCompletion().outcome(),
      )

      val wireState =
          endpoint.captureState()
              as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState
      assertContentEquals(
          byteArrayOf(0x88.toByte(), 0xa8.toByte()),
          wireState.requestAcknowledgement(),
      )
      assertTrue(
          (wireState.engineState() as MobileAdapterEngine.MobileAdapterEngineState)
              .acknowledgement()
              .isEmpty())
      val file = StateCodec.capture(session)
      val root = (file.root as SessionStateRoot).session
      assertEquals(
          listOf(MOBILE_ENGINE_STATE, MOBILE_WIRE_ENDPOINT_STATE),
          recordNames(root.serialState).sorted(),
      )

      drainResponse(endpoint)
      assertTrue(
          endpoint.captureState() is
              MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState)
      StateCodec.decodeAndApply(StateCodec.encode(file), session)
      assertEquals(
          9,
          (endpoint.captureState()
                  as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
              .wirePhaseId(),
      )
      drainResponse(endpoint)
    }
  }

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
      val legacyEngine = checkNotNull(root.serialState.findRecord(MOBILE_ENGINE_STATE))
      assertEquals(
          listOf(
              "phaseId",
              "outcomeId",
              "errorId",
              "deviceId",
              "packetBuffer",
              "packetCount",
              "expectedPacketBytes",
              "configuration",
              "responsePacket",
              "acknowledgement",
              "idlePhaseUnits",
              "serialByteObserved",
              "pendingPacketSlots",
          ),
          legacyEngine.fields.map(StateField::name),
      )
      val encoded = StateCodec.encode(file)
      assertEquals(file, StateCodec.decode(encoded))

      feed(endpoint, begin.copyOfRange(6, begin.size))
      val expected = endpoint.snapshot()
      feed(endpoint, packet(0x23, byteArrayOf(127, 0, 0, 1, 0, 80)))
      assertTrue(endpoint.hasExternalIo())

      StateCodec.decodeAndApply(encoded, session)
      assertEquals(1, backend.cancellations)
      assertEquals(0, backend.occupiedRequestSlots())
      feed(endpoint, begin.copyOfRange(6, begin.size))
      assertEngineResultEquals(expected, endpoint.snapshot())
    }
  }

  @Test
  fun pendingLimitRoundTripsForDirectReservationAndBackendSubmission() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      assertTrue(endpoint.reservePendingPacketSlot())
      assertTrue(endpoint.reservePendingPacketSlot())
      assertTrue(!endpoint.reservePendingPacketSlot())
      val direct = endpoint.snapshot()
      assertTrue(direct.acknowledgement().isEmpty())
      StateCodec.decodeAndApply(StateCodec.encode(StateCodec.capture(session)), session)
      assertEngineResultEquals(direct, endpoint.snapshot())

      endpoint.completePendingPacketSlot()
      endpoint.completePendingPacketSlot()
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      assertTrue(endpoint.reservePendingPacketSlot())
      assertTrue(endpoint.reservePendingPacketSlot())
      feed(endpoint, packet(0x28, "fixture.test".encodeToByteArray()))
      val submitted = endpoint.snapshot()
      assertEquals(MobileAdapterEngine.Outcome.PENDING_LIMIT, submitted.outcome())
      assertEquals(MobileAdapterEngine.ErrorCode.PENDING_LIMIT, submitted.error())
      assertContentEquals(byteArrayOf(0x88.toByte(), 0xf2.toByte()), submitted.acknowledgement())
      assertTrue(submitted.responsePacket().isEmpty())
      StateCodec.decodeAndApply(StateCodec.encode(StateCodec.capture(session)), session)
      assertEngineResultEquals(submitted, endpoint.snapshot())
    }
  }

  @Test
  fun releasedAcklessBackendErrorEndpointStillRoundTripsThroughPortableCodec() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feed(endpoint, packet(0x28, "fixture.test".encodeToByteArray()))
      val current = endpoint.snapshot()
      assertEquals(MobileAdapterEngine.Outcome.BACKEND_ERROR, current.outcome())
      assertTrue(current.acknowledgement().isNotEmpty())

      val captured = StateCodec.capture(session)
      val impossibleResponseInvalid =
          captured.replaceMobileEngineField("errorId", Int32State(11))
      val before = session.captureDetachedState()
      assertFailsWith<StateDecodeException> {
        StateCodec.decodeAndApply(StateCodec.encode(impossibleResponseInvalid), session)
      }
      assertEquals(before, session.captureDetachedState())

      val released =
          captured.replaceMobileEngineField("acknowledgement", BytesState(byteArrayOf()))
      StateCodec.decodeAndApply(StateCodec.encode(released), session)

      assertEquals(MobileAdapterEngine.Outcome.BACKEND_ERROR, endpoint.snapshot().outcome())
      assertTrue(endpoint.snapshot().acknowledgement().isEmpty())
      assertContentEquals(current.responsePacket(), endpoint.snapshot().responsePacket())
      assertTrue(
          endpoint.captureState() is
              MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState)
    }
  }

  @Test
  fun openExternalIoWithAPartialPacketCapturesAsAServiceFreeDisconnectedMarker() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feed(endpoint, packet(0x23, byteArrayOf(127, 0, 0, 1, 0, 80)))
      assertEquals(
          MobileAdapterBackendPort.CompletionResult.COMPLETED,
          backend.complete(backend.generation(), 0, byteArrayOf(0)),
      )
      assertEquals(
          MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
          endpoint.pollBackendCompletion().outcome(),
      )
      drainResponse(endpoint)
      assertTrue(endpoint.hasExternalIo())
      val transfer = packet(0x15, byteArrayOf(0, 1, 2))
      feed(endpoint, transfer.copyOf(6))
      assertEquals(6, endpoint.snapshot().retainedBytes())

      val file = StateCodec.capture(session)
      val capturedSession = (file.root as SessionStateRoot).session
      val capturedEngine =
          checkNotNull(
              capturedSession.serialState.findRecord(MOBILE_NETWORK_ENGINE_STATE))
      assertEquals(
          listOf(MOBILE_NETWORK_ENGINE_STATE, MOBILE_NETWORK_ENDPOINT_STATE),
          recordNames(capturedSession.serialState).sorted(),
      )
      assertEquals(Int32State(23), capturedEngine.field("outcomeId"))
      assertEquals(Int32State(12), capturedEngine.field("errorId"))
      assertEquals(BytesState(byteArrayOf()), capturedEngine.field("responsePacket"))
      assertEquals(BytesState(byteArrayOf()), capturedEngine.field("acknowledgement"))
      assertEquals(Int32State(6), capturedEngine.field("packetCount"))
      assertEquals(Int32State(0), capturedEngine.field("pendingPacketSlots"))
      assertEquals(BooleanState(true), capturedEngine.field("externalIoAtCapture"))

      endpoint.disconnect()
      StateCodec.decodeAndApply(StateCodec.encode(file), session)

      assertEquals(2, backend.cancellations)
      assertEquals(0, backend.occupiedRequestSlots())
      assertEquals(
          MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
          endpoint.snapshot().outcome(),
      )
      assertEquals(
          MobileAdapterEngine.ErrorCode.EXTERNAL_IO_DISCONNECTED,
          endpoint.snapshot().error(),
      )
      assertEquals(6, endpoint.snapshot().retainedBytes())
      assertTrue(endpoint.snapshot().responsePacket().isEmpty())
      assertTrue(endpoint.snapshot().acknowledgement().isEmpty())

      val normalizedAgain = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(normalizedAgain, session)
      assertEquals(6, endpoint.snapshot().retainedBytes())
      assertEquals(
          MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
          endpoint.snapshot().outcome(),
      )

      feed(endpoint, transfer.copyOfRange(6, transfer.size))
      assertEquals(MobileAdapterEngine.Outcome.BACKEND_ERROR, endpoint.snapshot().outcome())
      assertEquals(
          MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID,
          endpoint.snapshot().error(),
      )
      assertEquals(0, backend.occupiedRequestSlots())
    }
  }

  @Test
  fun normalizedExternalReplyByteAppliesOverPureWireAndFinishesExactly() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feedRaw(endpoint, packet(0x23, byteArrayOf(127, 0, 0, 1, 0, 80)))
      assertEquals(0x88, exchange(endpoint, 0x80))
      assertEquals(0xa3, exchange(endpoint, 0))
      assertEquals(
          MobileAdapterBackendPort.CompletionResult.COMPLETED,
          backend.complete(backend.generation(), 0, byteArrayOf(0)),
      )
      assertEquals(
          MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
          endpoint.pollBackendCompletion().outcome(),
      )
      assertEquals(0xd2, exchange(endpoint, 0))
      assertEquals(0xd2, exchange(endpoint, 0x4b))
      endpoint.setSb(0x4b)
      endpoint.startSending()
      var reply = 0
      repeat(3) { reply = reply shl 1 or endpoint.sendBit() }

      val file = StateCodec.capture(session)
      val captured = (file.root as SessionStateRoot).session
      assertEquals(
          listOf(MOBILE_NETWORK_ENGINE_STATE, MOBILE_WIRE_ENDPOINT_STATE),
          recordNames(captured.serialState).sorted(),
      )
      val wire = checkNotNull(captured.serialState.findRecord(MOBILE_WIRE_ENDPOINT_STATE))
      assertEquals(Int32State(11), wire.field("wirePhaseId"))
      assertEquals(BytesState(byteArrayOf()), wire.field("requestAcknowledgement"))
      assertEquals(BytesState(byteArrayOf()), wire.field("responsePacket"))

      endpoint.disconnect()
      endpoint.setSb(0x99)
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      val pureTarget =
          endpoint.captureState()
              as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState
      assertTrue(pureTarget.engineState() is MobileAdapterEngine.MobileAdapterEngineState)

      StateCodec.decodeAndApply(StateCodec.encode(file), session)
      val recaptured =
          endpoint.captureState()
              as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState
      assertEquals(11, recaptured.wirePhaseId())
      assertTrue(recaptured.engineState() is MobileAdapterEngine.MobileAdapterEngineState)
      repeat(5) { reply = reply shl 1 or endpoint.sendBit() }
      assertEquals(0x99, reply)
      assertEquals(
          MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
          endpoint.snapshot().outcome(),
      )
      assertTrue(
          endpoint.captureState() is
              MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState)
    }
  }

  @Test
  fun pureWireCandidateAppliesOverNormalizedExternalWireTarget() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      endpoint.setSb(0x99)
      endpoint.startSending()
      var reply = 0
      repeat(3) { reply = reply shl 1 or endpoint.sendBit() }
      val pureFile = StateCodec.capture(session)

      endpoint.disconnect()
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feedRaw(endpoint, packet(0x23, byteArrayOf(127, 0, 0, 1, 0, 80)))
      exchange(endpoint, 0x80)
      exchange(endpoint, 0)
      assertEquals(
          MobileAdapterBackendPort.CompletionResult.COMPLETED,
          backend.complete(backend.generation(), 0, byteArrayOf(0)),
      )
      endpoint.pollBackendCompletion()
      exchange(endpoint, 0)
      exchange(endpoint, 0x4b)
      endpoint.setSb(0x4b)
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      val networkTarget =
          endpoint.captureState()
              as MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState
      assertEquals(11, networkTarget.wirePhaseId())
      assertTrue(
          networkTarget.engineState() is
              MobileAdapterEngine.MobileAdapterEngineNetworkState)

      StateCodec.decodeAndApply(StateCodec.encode(pureFile), session)
      repeat(5) { reply = reply shl 1 or endpoint.sendBit() }
      assertEquals(0xd2, reply)
      assertEquals(1, endpoint.snapshot().retainedBytes())
      assertTrue(!endpoint.hasExternalIo())
    }
  }

  @Test
  fun runtimeOnlyPendingWirePhaseRejectsBeforeMutation() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feedRaw(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      val file = StateCodec.capture(session)
      val corrupted =
          StateFile(
              file.identities,
              SessionStateRoot(
                  (file.root as SessionStateRoot).session.let { captured ->
                    SessionState(
                        captured.machine,
                        captured.serialPeripheral,
                        captured.serialState.replaceRecordField(
                            MOBILE_WIRE_ENDPOINT_STATE,
                            "wirePhaseId",
                            Int32State(10),
                        ),
                        captured.serialRuntime,
                        captured.heldButtons,
                    )
                  }),
              file.diagnostics,
              file.formatVersion,
          )
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
  fun malformedExternalIoMarkerRejectsBeforeEndpointOrBackendMutation() {
    val backend = TrackingBackend()
    val endpoint = endpoint(backend)
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feed(endpoint, packet(0x23, byteArrayOf(127, 0, 0, 1, 0, 80)))
      val file = StateCodec.capture(session)
      val before = session.captureDetachedState()
      val corruptions =
          listOf(
              "marker" to ("externalIoAtCapture" to BooleanState(false)),
              "phase" to ("phaseId" to Int32State(1)),
              "outcome" to ("outcomeId" to Int32State(1)),
              "error" to ("errorId" to Int32State(0)),
              "response" to
                  ("responsePacket" to
                      BytesState(packet(0x28 or 0x80, byteArrayOf(1, 2, 3, 4)))),
          )

      corruptions.forEach { (label, mutation) ->
        val stages = mutableListOf<ApplyStage>()
        val corrupted =
            file.replaceMobileEngineField(
                mutation.first,
                mutation.second,
                MOBILE_NETWORK_ENGINE_STATE,
            )

        val failure =
            assertFailsWith<StateDecodeException>(label) {
              StateCodec.decodeAndApply(StateCodec.encode(corrupted), session) { stages += it }
            }

        assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason, label)
        assertTrue(stages.isEmpty(), label)
        assertEquals(0, backend.cancellations, label)
        assertEquals(1, backend.occupiedRequestSlots(), label)
        assertEquals(before, session.captureDetachedState(), label)
      }

      val liveOwnership =
          file
              .replaceMobileEngineField(
                  "externalIoAtCapture",
                  BooleanState(false),
                  MOBILE_NETWORK_ENGINE_STATE,
              )
              .replaceMobileEngineField(
                  "outcomeId",
                  Int32State(19),
                  MOBILE_NETWORK_ENGINE_STATE,
              )
              .replaceMobileEngineField(
                  "errorId",
                  Int32State(0),
                  MOBILE_NETWORK_ENGINE_STATE,
              )
      val stages = mutableListOf<ApplyStage>()
      val failure =
          assertFailsWith<StateDecodeException>("live backend ownership") {
            StateCodec.decodeAndApply(StateCodec.encode(liveOwnership), session) { stages += it }
          }
      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertEquals(0, backend.cancellations)
      assertEquals(1, backend.occupiedRequestSlots())
      assertEquals(before, session.captureDetachedState())
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

      val missingEngine =
          file.replaceMobileEngineField(
              "engineState",
              NullState,
              MOBILE_ENDPOINT_STATE,
          )
      val stages = mutableListOf<ApplyStage>()
      val failure =
          assertFailsWith<StateDecodeException>("missing nested engine") {
            StateCodec.decodeAndApply(StateCodec.encode(missingEngine), session) { stages += it }
          }
      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertEquals(0, backend.cancellations)
      assertEquals(before, session.captureDetachedState())
    }
  }

  @Test
  fun impossibleServiceErrorPhaseRejectsBeforeMutation() {
    val endpoint = endpoint()
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      feed(endpoint, packet(0x10, "NINTENDO".encodeToByteArray()))
      feed(endpoint, packet(0x12, byteArrayOf(0, '1'.code.toByte())))
      assertEquals(MobileAdapterEngine.Outcome.SERVICE_ERROR, endpoint.snapshot().outcome())
      val file = StateCodec.capture(session)
      val corrupted = file.replaceMobileEngineField("phaseId", Int32State(3))
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
      ownerClass: String = MOBILE_ENGINE_STATE,
  ): StateFile {
    val session = (root as SessionStateRoot).session
    return StateFile(
        identities,
        SessionStateRoot(
            SessionState(
                session.machine,
                session.serialPeripheral,
                session.serialState.replaceRecordField(
                    ownerClass,
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
    feedRaw(endpoint, bytes)
    val result = endpoint.snapshot()
    if (result.acknowledgement().size != 2) return
    exchange(endpoint, 0x80)
    exchange(endpoint, 0)
    if (result.responsePacket().isNotEmpty()) drainResponse(endpoint)
  }

  private fun feedRaw(endpoint: MobileAdapterSerialEndpoint, bytes: ByteArray) {
    bytes.forEach { byte ->
      exchange(endpoint, byte.toInt() and 0xff)
    }
  }

  private fun drainResponse(endpoint: MobileAdapterSerialEndpoint) {
    var first = 0xd2
    repeat(8) {
      if (first == 0xd2) first = exchange(endpoint, 0x4b)
    }
    check(first == 0x99)
    val header = ByteArray(6)
    header[0] = first.toByte()
    for (index in 1 until header.size) header[index] = exchange(endpoint, 0x4b).toByte()
    val size = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
    repeat(size + 2) { exchange(endpoint, 0x4b) }
    exchange(endpoint, 0x80)
    exchange(endpoint, (header[2].toInt() and 0xff) xor 0x80)
  }

  private fun exchange(endpoint: MobileAdapterSerialEndpoint, outgoing: Int): Int {
    endpoint.setSb(outgoing)
    endpoint.startSending()
    var incoming = 0
    repeat(8) { incoming = (incoming shl 1) or endpoint.sendBit() }
    return incoming
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

  private fun StateValue.findRecord(ownerClass: String): RecordState? =
      when (this) {
        is RecordState ->
            if (StateTypeRegistry.recordClassNames[typeId - 1] == ownerClass) this
            else fields.asSequence().mapNotNull { it.value.findRecord(ownerClass) }.firstOrNull()
        is ObjectArrayState ->
            values.asSequence().mapNotNull { it.findRecord(ownerClass) }.firstOrNull()
        is ListState ->
            values.asSequence().mapNotNull { it.findRecord(ownerClass) }.firstOrNull()
        is Int32MapState ->
            entries.asSequence().mapNotNull { it.value.findRecord(ownerClass) }.firstOrNull()
        else -> null
      }

  private fun RecordState.field(name: String): StateValue = fields.single { it.name == name }.value

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
    const val MOBILE_NETWORK_ENGINE_STATE =
        "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineNetworkState"
    const val MOBILE_NETWORK_ENDPOINT_STATE =
        "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointNetworkState"
    const val MOBILE_WIRE_ENDPOINT_STATE =
        "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointWireState"
  }
}
