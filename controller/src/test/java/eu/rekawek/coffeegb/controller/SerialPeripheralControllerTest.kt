package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralError
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatus
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatusEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.mobile.DeterministicMobileAdapterBackend
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class SerialPeripheralControllerTest {

  @Test
  fun committedHotSwapClearsPinnedSessionRewindButFailedPreparationPreservesIt() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val statuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<SerialPeripheralStatusEvent>(statuses::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)
    val providerFailure = AtomicReference<IOException?>(IOException("injected unavailable config"))
    val provider =
        Controller.MobileAdapterConfigurationProvider {
          providerFailure.get()?.let { throw it }
          MobileAdapterConfiguration.syntheticOffline()
        }
    val rewind = RewindManager()
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            rewind,
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory.DEFAULT,
            mobileAdapterConfigurationProvider = provider,
        )

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (rewind.historySize == 0 && System.nanoTime() < deadline) Thread.sleep(10)
      assertTrue(rewind.historySize > 0)

      eventBus.post(Controller.PauseEmulationEvent())
      Thread.sleep(100)
      frames.clear()
      assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
      val retainedHistory = rewind.historySize

      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      val unavailable = assertNotNull(statuses.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(SerialPeripheralStatus.UNAVAILABLE, unavailable.status)
      assertEquals(SerialPeripheralError.STORAGE_FAILED, unavailable.error)
      assertEquals(retainedHistory, rewind.historySize)

      providerFailure.set(null)
      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.DETACHED,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.ATTACHED,
      )
      assertEquals(0, rewind.historySize)
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun syntheticOfflineConfigurationIsExactFreshAndDefensivelyOwned() {
    val first = MobileAdapterConfiguration.syntheticOffline()
    val bytes = first.copyBytes()

    assertEquals(0x08, first.deviceId)
    assertEquals(256, bytes.size)
    assertContentEquals(byteArrayOf(0x4d, 0x41, 0x81.toByte()), bytes.copyOfRange(0, 3))
    assertTrue(bytes.copyOfRange(3, 128).all { it == 0.toByte() })
    assertContentEquals(ByteArray(128) { it.toByte() }, bytes.copyOfRange(128, 256))

    bytes.fill(0x55)
    assertEquals(0x4d, first.copyBytes()[0].toInt() and 0xff)
    assertContentEquals(
        MobileAdapterConfiguration.syntheticOffline().copyBytes(),
        first.copyBytes(),
    )
  }

  @Test
  fun statusFailuresAreTypedBoundedAndCannotCarryProviderDetails() {
    assertFailsWith<IllegalArgumentException> {
      SerialPeripheralStatusEvent(
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.UNAVAILABLE,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      SerialPeripheralStatusEvent(
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.ATTACHED,
          SerialPeripheralError.STORAGE_FAILED,
      )
    }

    assertEquals(
        setOf(
            "ENDPOINT_UNAVAILABLE",
            "CONFIGURATION_INVALID",
            "STORAGE_FAILED",
            "PORT_OWNED_BY_LINK",
        ),
        SerialPeripheralError.entries.mapTo(mutableSetOf()) { it.code },
    )
    SerialPeripheralError.entries.forEach { error ->
      assertTrue(error.userMessage.length < 160)
      assertFalse(error.userMessage.contains('/'))
      assertFalse(error.userMessage.contains('\\'))
      assertFalse(error.userMessage.contains('\n'))
    }
  }

  @Test
  fun presentationSubscriberFailuresCannotSplitCommittedSerialOwnership() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val statuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    val controller = BasicController(eventBus, EmulatorProperties(), null)
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
    eventBus.register<SerialPeripheralStatusEvent>(statuses::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent> {
      throw IllegalStateException("private selection subscriber detail")
    }
    eventBus.register<SerialPeripheralStatusEvent> {
      throw IllegalStateException("private status subscriber detail")
    }

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(
          SerialPeripheralSelection.PEER_TO_PEER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )

      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.DETACHED,
      )
      assertEquals(
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.ATTACHED,
      )

      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.PRINTER))
      assertStatus(
          statuses,
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.DETACHED,
      )
      assertEquals(
          SerialPeripheralSelection.PRINTER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PRINTER,
          SerialPeripheralStatus.ATTACHED,
      )
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun mobileReplacementStressDisconnectsEveryOwnerExactlyOnceAndClearsAllQueues() {
    val configuration = configuration()
    val bus = EventBusImpl()
    val endpoints = mutableListOf<TrackingMobileEndpoint>()
    val backendDisconnects = mutableListOf<AtomicInteger>()
    var current = trackingMobileEndpoint().also(endpoints::add)
    var currentBackendDisconnect = AtomicInteger().also(backendDisconnects::add)
    val session =
        Session(
            configuration,
            bus,
            null,
            current,
            serialEndpointDisconnect = { currentBackendDisconnect.incrementAndGet() },
        )

    try {
      repeat(10) { cycle ->
        current.retainWork(cycle.toLong())
        val next = trackingMobileEndpoint().also(endpoints::add)
        val nextBackendDisconnect = AtomicInteger().also(backendDisconnects::add)

        session.setSerialEndpoint(next) { nextBackendDisconnect.incrementAndGet() }

        current.assertFullyDisconnected()
        assertEquals(1, current.disconnects.get())
        assertEquals(1, currentBackendDisconnect.get())
        current = next
        currentBackendDisconnect = nextBackendDisconnect
      }

      current.retainWork(10)
    } finally {
      session.close()
    }

    endpoints.forEach { endpoint ->
      endpoint.assertFullyDisconnected()
      assertEquals(1, endpoint.disconnects.get())
    }
    backendDisconnects.forEach { assertEquals(1, it.get()) }
    assertSame(SerialEndpoint.NULL_ENDPOINT, session.serialEndpoint)
  }

  @Test
  fun tenCompleteMobileCyclesResetRestoreDetachAndReleaseEveryGeneration() {
    val bus = EventBusImpl()
    val session =
        Session(
            configuration(),
            bus,
            null,
            SerialEndpoint.NULL_ENDPOINT,
        )

    try {
      repeat(10) { cycle ->
        val backend = DeterministicMobileAdapterBackend()
        val endpoint =
            MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY,
                0x08,
                MobileAdapterConfiguration.syntheticOffline().copyBytes(),
                backend,
            )
        val backendDisconnects = AtomicInteger()
        session.setSerialEndpoint(endpoint) {
          backendDisconnects.incrementAndGet()
          backend.cancelAll()
        }

        assertEquals(
            MobileAdapterBackendPort.OfferResult.ACCEPTED,
            backend.offer(
                backend.generation(),
                MobileAdapterBackendPort.BackendRequest(
                    cycle.toLong(),
                    0x42,
                    byteArrayOf(1, 2, 3),
                )),
        )
        assertEquals(
            MobileAdapterBackendPort.CompletionResult.COMPLETED,
            backend.complete(backend.generation(), cycle.toLong(), byteArrayOf(4, 5, 6)),
        )
        assertTrue(endpoint.reservePendingPacketSlot())
        val reset = feedEndpoint(endpoint, packet(0x16, byteArrayOf()))
        assertEquals(MobileAdapterEngine.Outcome.SESSION_RESET, reset.outcome())
        assertEquals(0, reset.pendingPacketSlots())
        assertBackendEmpty(backend)

        val begin = packet(0x10, "NINTENDO".encodeToByteArray())
        feedEndpoint(endpoint, begin.copyOf(6))
        val saved = StateCodec.encode(StateCodec.capture(session))

        val dirtyGeneration = backend.generation()
        val dirtyRequestId = 100L + cycle
        assertEquals(
            MobileAdapterBackendPort.OfferResult.ACCEPTED,
            backend.offer(
                dirtyGeneration,
                MobileAdapterBackendPort.BackendRequest(
                    dirtyRequestId,
                    0x43,
                    byteArrayOf(7, 8),
                )),
        )
        assertTrue(endpoint.reservePendingPacketSlot())
        feedEndpoint(endpoint, begin.copyOfRange(6, begin.size))
        assertEquals(MobileAdapterEngine.Outcome.SESSION_STARTED, endpoint.snapshot().outcome())

        StateCodec.decodeAndApply(saved, session)
        assertContentEquals(saved, StateCodec.encode(StateCodec.capture(session)))
        assertBackendEmpty(backend)
        assertEquals(
            MobileAdapterBackendPort.CompletionResult.STALE_GENERATION,
            backend.complete(dirtyGeneration, dirtyRequestId, byteArrayOf(9)),
        )
        assertEquals(6, endpoint.snapshot().retainedBytes())
        assertEquals(0, endpoint.snapshot().pendingPacketSlots())
        val continued = feedEndpoint(endpoint, begin.copyOfRange(6, begin.size))
        assertEquals(MobileAdapterEngine.Outcome.SESSION_STARTED, continued.outcome())

        session.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT)
        assertEquals(1, backendDisconnects.get())
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome())
        assertEquals(0, endpoint.snapshot().retainedBytes())
        assertEquals(0, endpoint.snapshot().pendingPacketSlots())
        assertBackendEmpty(backend)
        assertSame(SerialEndpoint.NULL_ENDPOINT, session.serialEndpoint)
      }
    } finally {
      session.close()
      bus.close()
    }
  }

  @Test
  fun failedCoreHandoffRollsBackOldEndpointAndCleansOnlyTheCandidate() {
    val configuration = configuration()
    val old = CountingEndpoint()
    val candidate = CountingEndpoint()
    val oldBackendDisconnects = AtomicInteger()
    val candidateBackendDisconnects = AtomicInteger()
    val gameboy = RejectingGameboy(configuration, old)
    val session =
        Session(
            configuration,
            EventBusImpl(),
            null,
            old,
            prebuiltGameboy = gameboy,
            serialEndpointDisconnect = { oldBackendDisconnects.incrementAndGet() },
        )
    gameboy.rejected = candidate

    try {
      assertFailsWith<IllegalStateException> {
        session.setSerialEndpoint(candidate) { candidateBackendDisconnects.incrementAndGet() }
      }

      assertSame(old, session.serialEndpoint)
      assertSame(old, gameboy.installed)
      assertEquals(0, old.disconnects.get())
      assertEquals(0, oldBackendDisconnects.get())
      assertEquals(1, candidate.disconnects.get())
      assertEquals(1, candidateBackendDisconnects.get())
    } finally {
      session.close()
    }

    assertEquals(1, old.disconnects.get())
    assertEquals(1, oldBackendDisconnects.get())
    assertEquals(1, candidate.disconnects.get())
  }

  @Test
  fun exclusiveSelectionAndLifecycleStatusesSurviveTenMobileAttachResetDetachCyclesWithoutLeaks() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val statuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<EmulationStoppedEvent>(stopped::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
    eventBus.register<SerialPeripheralStatusEvent>(statuses::add)
    val controller = BasicController(eventBus, EmulatorProperties(), null)

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(
          SerialPeripheralSelection.PEER_TO_PEER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )
      eventBus.drainAsyncEvents()
      val controllerBus = basicControllerEventBus(controller)
      val controllerRegistrations = eventBusRegistrationCount(controllerBus)
      val sessionRegistrations = eventBusRegistrationCount(eventBusChildren(controllerBus).single())
      assertControllerEventOwnership(
          controller,
          controllerRegistrations,
          sessionRegistrations,
          activeSession = true,
      )

      var previous = SerialPeripheralSelection.PEER_TO_PEER
      repeat(10) {
        eventBus.post(
            Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
        assertStatus(statuses, previous, SerialPeripheralStatus.DETACHED)
        assertEquals(
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
        )
        assertStatus(
            statuses,
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            SerialPeripheralStatus.ATTACHED,
        )

        // Exercise the real controller/session reset, not only protocol command 0x16. The old
        // session bus and worker must be closed before the replacement becomes authoritative.
        eventBus.post(Controller.ResetEmulationEvent())
        assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertStatus(
            statuses,
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            SerialPeripheralStatus.DETACHED,
        )
        assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
        )
        assertStatus(
            statuses,
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            SerialPeripheralStatus.ATTACHED,
        )
        eventBus.drainAsyncEvents()
        assertControllerEventOwnership(
            controller,
            controllerRegistrations,
            sessionRegistrations,
            activeSession = true,
        )

        eventBus.post(Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.NONE))
        assertStatus(
            statuses,
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            SerialPeripheralStatus.DETACHED,
        )
        assertEquals(
            SerialPeripheralSelection.NONE,
            selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
        )
        assertStatus(
            statuses,
            SerialPeripheralSelection.NONE,
            SerialPeripheralStatus.ATTACHED,
        )
        previous = SerialPeripheralSelection.NONE
      }

      eventBus.post(Controller.SetPrinterEvent(false))
      assertNull(selections.poll(200, TimeUnit.MILLISECONDS))
      assertNull(statuses.poll(200, TimeUnit.MILLISECONDS))

      eventBus.post(Controller.SetBarcodeBoyEvent(true))
      assertStatus(statuses, SerialPeripheralSelection.NONE, SerialPeripheralStatus.DETACHED)
      assertEquals(
          SerialPeripheralSelection.BARCODE_BOY,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.BARCODE_BOY,
          SerialPeripheralStatus.ATTACHED,
      )
      eventBus.post(Controller.SetPrinterEvent(false))
      assertNull(selections.poll(200, TimeUnit.MILLISECONDS))
      assertNull(statuses.poll(200, TimeUnit.MILLISECONDS))

      eventBus.post(Controller.SetBarcodeBoyEvent(false))
      assertStatus(
          statuses,
          SerialPeripheralSelection.BARCODE_BOY,
          SerialPeripheralStatus.DETACHED,
      )
      assertEquals(
          SerialPeripheralSelection.PEER_TO_PEER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )

      eventBus.post(Controller.StopEmulationEvent())
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.DETACHED,
      )
      assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.drainAsyncEvents()
      assertControllerEventOwnership(
          controller,
          controllerRegistrations,
          sessionRegistrations,
          activeSession = false,
      )
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun freshControllerActivationReassertsDefaultPeerAfterPreviousLocalSelection() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val statuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
    eventBus.register<SerialPeripheralStatusEvent>(statuses::add)

    val previous = BasicController(eventBus, EmulatorProperties(), null)
    previous.startController()
    try {
      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      assertEquals(
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          SerialPeripheralStatus.DETACHED,
      )
    } finally {
      previous.close()
    }

    val fresh = BasicController(eventBus, EmulatorProperties(), null)
    fresh.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(
          SerialPeripheralSelection.PEER_TO_PEER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )
    } finally {
      fresh.close()
      eventBus.close()
    }
  }

  @Test
  fun providerAndCoreFailuresPublishOnlyTypedErrorsAndKeepOldSelectionAttached() {
    val secret = "/private/player/mobile.bin payload=deadbeef"
    val providerFailure = AtomicReference<Exception>(IOException(secret))
    val provider =
        Controller.MobileAdapterConfigurationProvider { throw providerFailure.get() }
    assertSelectionFailure(
        provider,
        SerialPeripheralError.STORAGE_FAILED,
        secret,
    )

    providerFailure.set(IllegalArgumentException(secret))
    assertSelectionFailure(
        provider,
        SerialPeripheralError.CONFIGURATION_INVALID,
        secret,
    )

    val coreFailurePreparer =
        SessionPreparer { properties, event ->
          val configuration =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          PreparedSession.Ready(configuration, MobileRejectingGameboy(configuration))
        }
    assertSelectionFailure(
        MobileAdapterConfigurationProviderForTests,
        SerialPeripheralError.ENDPOINT_UNAVAILABLE,
        secret,
        coreFailurePreparer,
    )
  }

  private fun assertSelectionFailure(
      provider: Controller.MobileAdapterConfigurationProvider,
      expectedError: SerialPeripheralError,
      forbiddenDetail: String,
      preparer: SessionPreparer? = null,
  ) {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val statuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
    eventBus.register<SerialPeripheralStatusEvent>(statuses::add)
    val controller =
        if (preparer == null) {
          BasicController(eventBus, EmulatorProperties(), null, RomSessionPreparer(), provider)
        } else {
          BasicController(eventBus, EmulatorProperties(), null, preparer, provider)
        }

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(
          SerialPeripheralSelection.PEER_TO_PEER,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.ATTACHED,
      )

      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      val failure = assertNotNull(statuses.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(SerialPeripheralSelection.MOBILE_ADAPTER_GB, failure.selection)
      assertEquals(SerialPeripheralStatus.UNAVAILABLE, failure.status)
      assertEquals(expectedError, failure.error)
      assertFalse(failure.toString().contains(forbiddenDetail))
      assertNull(selections.poll(200, TimeUnit.MILLISECONDS))

      // A failed preparation/handoff did not commit Mobile: the peer endpoint remains the owner.
      eventBus.post(Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.NONE))
      assertStatus(
          statuses,
          SerialPeripheralSelection.PEER_TO_PEER,
          SerialPeripheralStatus.DETACHED,
      )
      assertEquals(
          SerialPeripheralSelection.NONE,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      assertStatus(
          statuses,
          SerialPeripheralSelection.NONE,
          SerialPeripheralStatus.ATTACHED,
      )
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  private fun assertStatus(
      statuses: LinkedBlockingQueue<SerialPeripheralStatusEvent>,
      selection: SerialPeripheralSelection,
      status: SerialPeripheralStatus,
  ) {
    val event = assertNotNull(statuses.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertEquals(selection, event.selection)
    assertEquals(status, event.status)
    assertNull(event.error)
  }

  private fun configuration(): Gameboy.GameboyConfiguration =
      Controller.createGameboyConfig(EmulatorProperties(), Rom(ROM))
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP)

  private fun feedEndpoint(
      endpoint: MobileAdapterSerialEndpoint,
      bytes: ByteArray,
  ): MobileAdapterEngine.EngineResult {
    bytes.forEach { value ->
      endpoint.setSb(value.toInt() and 0xff)
      endpoint.startSending()
      repeat(8) { endpoint.sendBit() }
    }
    return endpoint.snapshot()
  }

  private fun assertBackendEmpty(backend: DeterministicMobileAdapterBackend) {
    assertEquals(0, backend.occupiedRequestSlots())
    assertEquals(0, backend.bufferedBytes())
    assertEquals(0, backend.pendingRequests())
    assertEquals(0, backend.completedResults())
  }

  private fun assertControllerEventOwnership(
      controller: BasicController,
      expectedControllerRegistrations: Int,
      expectedSessionRegistrations: Int,
      activeSession: Boolean,
  ) {
    val controllerBus = basicControllerEventBus(controller)
    val expectedChildren = if (activeSession) 1 else 0
    val expectedWorkers = if (activeSession) 2 else 1
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    // EmulationStoppedEvent is intentionally published before its session bus closes. Require a
    // quiet ownership window so that a transient teardown boundary cannot satisfy the leak proof.
    val stableDuration = TimeUnit.MILLISECONDS.toNanos(50)
    var stableSince = Long.MIN_VALUE
    while (System.nanoTime() < deadline) {
      val now = System.nanoTime()
      val atExpectedBoundary =
          controllerJobsAreIdle(controller) &&
              eventBusChildren(controllerBus).size == expectedChildren &&
              liveEventBusWorkers(controllerBus) == expectedWorkers
      if (!atExpectedBoundary) {
        stableSince = Long.MIN_VALUE
      } else {
        if (stableSince == Long.MIN_VALUE) stableSince = now
        if (now - stableSince >= stableDuration) break
      }
      Thread.sleep(1)
    }
    assertTrue(controllerJobsAreIdle(controller))
    assertEquals(expectedControllerRegistrations, eventBusRegistrationCount(controllerBus))
    val sessionChildren = eventBusChildren(controllerBus)
    assertEquals(expectedChildren, sessionChildren.size)
    if (activeSession) {
      assertEquals(
          expectedSessionRegistrations,
          eventBusRegistrationCount(sessionChildren.single()),
      )
    }
    // The controller bus owns one fixed worker; one active session owns exactly one additional
    // worker. A removed child has completed EventBus.closeBefore(), which waits for that worker
    // before detaching the child from this tree.
    assertEquals(expectedWorkers, liveEventBusWorkers(controllerBus))
  }

  private fun controllerJobsAreIdle(controller: BasicController): Boolean =
      listOf("loadJob", "replacementJob", "stopJob").all { fieldName ->
        val field = BasicController::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(controller) == null
      }

  private fun basicControllerEventBus(controller: BasicController): EventBusImpl {
    val field = BasicController::class.java.getDeclaredField("eventBus")
    field.isAccessible = true
    return field.get(controller) as EventBusImpl
  }

  @Suppress("UNCHECKED_CAST")
  private fun eventBusChildren(eventBus: EventBusImpl): List<EventBusImpl> {
    val field = EventBusImpl::class.java.getDeclaredField("children")
    field.isAccessible = true
    return (field.get(eventBus) as Collection<EventBusImpl>).toList()
  }

  private fun eventBusRegistrationCount(eventBus: EventBusImpl): Int {
    val field = EventBusImpl::class.java.getDeclaredField("registrations")
    field.isAccessible = true
    return (field.get(eventBus) as Collection<*>).size
  }

  private fun liveEventBusWorkers(eventBus: EventBusImpl): Int {
    val threadField = EventBusImpl::class.java.getDeclaredField("asyncThread")
    threadField.isAccessible = true
    return sequenceOf(eventBus)
        .plus(eventBusChildren(eventBus).asSequence())
        .count { (threadField.get(it) as Thread?)?.isAlive == true }
  }

  private fun packet(command: Int, data: ByteArray): ByteArray {
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

  private fun trackingMobileEndpoint(): TrackingMobileEndpoint {
    val backend = DeterministicMobileAdapterBackend()
    return TrackingMobileEndpoint(
        MobileAdapterSerialEndpoint(
            ClockSpec.LEGACY,
            0x08,
            MobileAdapterConfiguration.syntheticOffline().copyBytes(),
            backend,
        ),
        backend,
    )
  }

  private class TrackingMobileEndpoint(
      private val delegate: MobileAdapterSerialEndpoint,
      private val backend: DeterministicMobileAdapterBackend,
  ) : SerialEndpoint by delegate {
    val disconnects = AtomicInteger()

    override fun disconnect() {
      disconnects.incrementAndGet()
      delegate.disconnect()
    }

    fun retainWork(requestId: Long) {
      assertEquals(
          MobileAdapterBackendPort.OfferResult.ACCEPTED,
          backend.offer(
              backend.generation(),
              MobileAdapterBackendPort.BackendRequest(
                  requestId,
                  0x42,
                  byteArrayOf(1, 2, 3),
              )),
      )
      assertEquals(
          MobileAdapterBackendPort.CompletionResult.COMPLETED,
          backend.complete(backend.generation(), requestId, byteArrayOf(4, 5, 6)),
      )
      assertTrue(delegate.reservePendingPacketSlot())
      UNSUPPORTED_PACKET.forEach { value ->
        delegate.setSb(value.toInt() and 0xff)
        delegate.startSending()
        repeat(8) { delegate.sendBit() }
      }
      assertTrue(delegate.snapshot().acknowledgement().isNotEmpty())
      assertTrue(backend.occupiedRequestSlots() > 0)
      assertTrue(backend.bufferedBytes() > 0)
    }

    fun assertFullyDisconnected() {
      val snapshot = delegate.snapshot()
      assertEquals(MobileAdapterEngine.Phase.SLEEP, snapshot.phase())
      assertEquals(MobileAdapterEngine.Outcome.CANCELLED, snapshot.outcome())
      assertEquals(MobileAdapterEngine.ErrorCode.NONE, snapshot.error())
      assertEquals(0, snapshot.retainedBytes())
      assertEquals(0, snapshot.pendingPacketSlots())
      assertTrue(snapshot.responsePacket().isEmpty())
      assertTrue(snapshot.acknowledgement().isEmpty())
      assertEquals(0, backend.occupiedRequestSlots())
      assertEquals(0, backend.bufferedBytes())
      assertEquals(0, backend.pendingRequests())
      assertEquals(0, backend.completedResults())
    }
  }

  private open class CountingEndpoint : SerialEndpoint by SerialEndpoint.NULL_ENDPOINT {
    val disconnects = AtomicInteger()

    override fun disconnect() {
      disconnects.incrementAndGet()
    }
  }

  private class RejectingGameboy(
      configuration: Gameboy.GameboyConfiguration,
      initialEndpoint: SerialEndpoint,
  ) : Gameboy(configuration) {
    var installed: SerialEndpoint = initialEndpoint
      private set

    var rejected: SerialEndpoint? = null

    override fun setSerialEndpoint(serialEndpoint: SerialEndpoint) {
      if (serialEndpoint === rejected) {
        throw IllegalStateException("injected serial endpoint handoff failure")
      }
      super.setSerialEndpoint(serialEndpoint)
      installed = serialEndpoint
    }
  }

  private class MobileRejectingGameboy(configuration: Gameboy.GameboyConfiguration) :
      Gameboy(configuration) {
    override fun setSerialEndpoint(serialEndpoint: SerialEndpoint) {
      if (serialEndpoint is MobileAdapterSerialEndpoint) {
        throw IllegalStateException("/private/player/mobile.bin payload=deadbeef")
      }
      super.setSerialEndpoint(serialEndpoint)
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val MobileAdapterConfigurationProviderForTests =
        Controller.MobileAdapterConfigurationProvider {
          MobileAdapterConfiguration.syntheticOffline()
        }

    val UNSUPPORTED_PACKET =
        byteArrayOf(
            0x99.toByte(),
            0x66,
            0x7f,
            0,
            0,
            0,
            0,
            0x7f,
        )

    const val TIMEOUT_SECONDS = 10L
  }
}
