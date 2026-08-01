package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterDisconnectReason
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterNetworkPhase
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterNetworkStatusEvent
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterStateBoundary
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterStateBoundaryEvent
import eu.rekawek.coffeegb.controller.Controller.MobileAdapterStateBoundaryImpact
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralError
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatus
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatusEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterDestinationPolicy
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterDestinationRule
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkBackend
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterRuntimeAuthorization
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterTransportProtocol
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterTransportTarget
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationWorker
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRepository
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.BooleanState
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/** Controller-level ownership boundaries for the real asynchronous Mobile Adapter backend. */
class MobileAdapterControllerLifecycleTest {

  @Test
  fun `newer policy stays fail closed when the core rejects its prepared endpoint`() {
    udpFixture().use { server ->
      val oldBackend = backend(server.localPort, 1)
      val candidateBackend = backend(server.localPort, 2)
      val supplied = AtomicReference(configuration(1, oldBackend))
      fixture(Controller.MobileAdapterConfigurationProvider { supplied.get() }).use { fixture ->
        val oldEndpoint = fixture.openLiveUdp(oldBackend)
        val oldGeneration = oldBackend.generation()
        fixture.gameboy.get().rejectNextMobileHandoff.set(true)
        supplied.set(configuration(2, candidateBackend))
        fixture.clearPresentationQueues()

        fixture.eventBus.post(Controller.RefreshMobileAdapterConfigurationEvent(2))

        val unavailable =
            fixture.await(fixture.serialStatuses) {
              it.selection == SerialPeripheralSelection.MOBILE_ADAPTER_GB &&
                  it.status == SerialPeripheralStatus.UNAVAILABLE
            }
        assertEquals(SerialPeripheralError.ENDPOINT_UNAVAILABLE, unavailable.error)
        fixture.await(fixture.networkStatuses) {
          it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
              it.disconnectReason == MobileAdapterDisconnectReason.POLICY_CHANGED
        }
        assertSame(oldEndpoint, fixture.endpoint.get())
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, oldEndpoint.snapshot().outcome())
        assertFalse(oldEndpoint.hasExternalIo())
        assertEquals(
            MobileAdapterBackendPort.OfferResult.UNAVAILABLE,
            oldBackend.offer(
                oldGeneration,
                MobileAdapterBackendPort.BackendRequest(
                    90,
                    DNS_QUERY,
                    "game.service".encodeToByteArray(),
                ),
            ),
        )
        fixture.awaitCondition { !oldBackend.hasExternalWork() }
        assertTrue(candidateBackend.awaitTermination(2_000))
        assertFalse(oldBackend.isClosed(), "the rolled-back endpoint remains the session owner")
      }
      assertTrue(oldBackend.awaitTermination(2_000))
    }
  }

  @Test
  fun `save normalizes host ownership without changing the live connection and load discloses disconnect`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      fixture(Controller.MobileAdapterConfigurationProvider { configuration(1, backend) }).use {
          fixture ->
        val endpoint = fixture.openLiveUdp(backend)
        val liveGeneration = backend.generation()
        fixture.clearPresentationQueues()

        fixture.eventBus.post(Controller.SaveSnapshotEvent(0))
        assertEquals(0, fixture.await(fixture.snapshotSaved) { true }.slot)
        val saveBoundary =
            fixture.await(fixture.stateBoundaries) {
              it.boundary == MobileAdapterStateBoundary.SAVE
            }
        assertEquals(
            MobileAdapterStateBoundaryImpact.SAVED_WITH_NON_RESTORABLE_IO,
            saveBoundary.impact,
        )
        assertSame(liveGeneration, backend.generation())
        assertTrue(endpoint.hasExternalIo())
        assertTrue(backend.hasExternalWork())
        assertSavedExternalIoMarker(fixture.directory.resolve("game.sn0"))

        fixture.clearPresentationQueues()
        fixture.eventBus.post(Controller.RestoreSnapshotEvent(0))
        assertEquals(0, fixture.await(fixture.snapshotRestored) { true }.slot)
        fixture.await(fixture.stateBoundaries) {
          it.boundary == MobileAdapterStateBoundary.LOAD
        }
        fixture.await(fixture.networkStatuses) {
          it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
              it.disconnectReason == MobileAdapterDisconnectReason.STATE_LOAD
        }
        assertEquals(
            MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
            endpoint.snapshot().outcome(),
        )
        assertFalse(endpoint.hasExternalIo())
        assertTrue(backend.generation() !== liveGeneration)
        fixture.awaitCondition { !backend.hasExternalWork() }
      }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `save safety disclosure precedes ordinary success presentation for both state paths`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      fixture(Controller.MobileAdapterConfigurationProvider { configuration(1, backend) }).use {
          fixture ->
        fixture.openLiveUdp(backend)
        val presentationOrder = LinkedBlockingQueue<String>()
        fixture.eventBus.register<MobileAdapterStateBoundaryEvent> {
          if (it.boundary == MobileAdapterStateBoundary.SAVE) presentationOrder.add("boundary")
        }
        fixture.eventBus.register<Controller.SnapshotSavedEvent> {
          presentationOrder.add("snapshot-success")
          throw IllegalStateException("injected snapshot-success subscriber failure")
        }
        fixture.eventBus.register<StateOperationCompletedEvent> {
          if (it.operation == StateOperation.SAVE) presentationOrder.add("managed-success")
        }

        fixture.eventBus.post(Controller.SaveSnapshotEvent(2))
        assertEquals("boundary", presentationOrder.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("snapshot-success", presentationOrder.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertNull(fixture.snapshotSaveFailed.poll(200, TimeUnit.MILLISECONDS))

        fixture.eventBus.post(
            StateSaveRequestEvent(
                7001,
                fixture.stateSessionId,
                StateRef.Slot(3),
                label = null,
                thumbnail = null,
            ))
        assertEquals("boundary", presentationOrder.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("managed-success", presentationOrder.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `stale durable save still discloses external IO when the newer same-ref save fails`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      val persistence = ToggleFailWriter()
      val stateExecutor = ManualExecutorService()
      fixture(
              Controller.MobileAdapterConfigurationProvider { configuration(1, backend) },
              stateWorkspaceFactory =
                  StateWorkspaceFactory { paths ->
                    StateWorkspace(paths) { layout -> StateRepository(layout, persistence) }
                  },
              stateOperationWorkerFactory =
                  StateOperationWorkerFactory { bus ->
                    StateOperationWorker(bus, executor = stateExecutor)
                  },
          )
          .use { fixture ->
            val endpoint = fixture.openLiveUdp(backend)
            val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
            val failed = LinkedBlockingQueue<StateOperationFailedEvent>()
            fixture.eventBus.register<StateOperationCompletedEvent>(completed::add)
            fixture.eventBus.register<StateOperationFailedEvent>(failed::add)
            fixture.clearPresentationQueues()
            val ref = StateRef.Slot(4)

            fixture.eventBus.post(
                StateSaveRequestEvent(7101, fixture.stateSessionId, ref, null, null))
            stateExecutor.awaitQueued(1)

            fixture.eventBus.post(Controller.CancelMobileAdapterNetworkEvent)
            fixture.await(fixture.networkStatuses) {
              it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
                  it.disconnectReason == MobileAdapterDisconnectReason.USER_CANCELLED
            }
            assertFalse(endpoint.hasExternalIo())

            fixture.eventBus.post(
                StateSaveRequestEvent(7102, fixture.stateSessionId, ref, null, null))
            stateExecutor.awaitQueued(2)

            stateExecutor.runNext()
            val boundary =
                fixture.await(fixture.stateBoundaries) {
                  it.boundary == MobileAdapterStateBoundary.SAVE
                }
            assertEquals(
                MobileAdapterStateBoundaryImpact.SAVED_WITH_NON_RESTORABLE_IO,
                boundary.impact,
            )
            assertNull(
                completed.poll(200, TimeUnit.MILLISECONDS),
                "ordinary success for the older request must remain stale-suppressed",
            )

            persistence.fail = true
            stateExecutor.runNext()
            val newerFailure = fixture.await(failed) { it.requestId == 7102L }
            assertEquals(StateOperation.SAVE, newerFailure.operation)
            assertNull(completed.poll(200, TimeUnit.MILLISECONDS))
            // The test writer only fails the requested manual save. Let mandatory close autosave
            // finish normally when the fixture tears down the live controller.
            persistence.fail = false
          }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `failed state load after endpoint mutation still discloses lost host ownership`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      fixture(Controller.MobileAdapterConfigurationProvider { configuration(1, backend) }).use {
          fixture ->
        val endpoint = fixture.openLiveUdp(backend)
        fixture.eventBus.post(Controller.SaveSnapshotEvent(1))
        fixture.await(fixture.snapshotSaved) { it.slot == 1 }
        fixture.await(fixture.stateBoundaries) {
          it.boundary == MobileAdapterStateBoundary.SAVE
        }
        val liveGeneration = backend.generation()
        fixture.gameboy.get().failAfterNextPressedButtonMutation.set(true)
        fixture.clearPresentationQueues()

        fixture.eventBus.post(Controller.RestoreSnapshotEvent(1))

        assertEquals(1, fixture.await(fixture.snapshotLoadFailed) { true }.slot)
        assertNull(
            fixture.snapshotRestored.poll(200, TimeUnit.MILLISECONDS),
            "a rolled-back state load must not publish success",
        )
        fixture.await(fixture.stateBoundaries) {
          it.boundary == MobileAdapterStateBoundary.LOAD
        }
        fixture.await(fixture.networkStatuses) {
          it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
              it.disconnectReason == MobileAdapterDisconnectReason.STATE_LOAD
        }
        assertTrue(backend.generation() !== liveGeneration)
        assertEquals(
            MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
            endpoint.snapshot().outcome(),
        )
        assertFalse(endpoint.hasExternalIo())
        fixture.awaitCondition { !backend.hasExternalWork() }
      }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `rewind immediately cancels live host ownership without closing the attached backend`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      val rewind = RewindManager(enabled = true)
      fixture(
              Controller.MobileAdapterConfigurationProvider { configuration(1, backend) },
              rewind,
          )
          .use { fixture ->
            val endpoint = fixture.openLiveUdp(backend)
            val liveGeneration = backend.generation()
            rewind.clear()
            fixture.clearPresentationQueues()

            fixture.eventBus.post(Controller.RewindEvent(true))

            fixture.await(fixture.stateBoundaries) {
              it.boundary == MobileAdapterStateBoundary.REWIND
            }
            fixture.await(fixture.networkStatuses) {
              it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
                  it.disconnectReason == MobileAdapterDisconnectReason.REWIND
            }
            assertTrue(backend.generation() !== liveGeneration)
            assertFalse(endpoint.hasExternalIo())
            fixture.awaitCondition { !backend.hasExternalWork() }
            assertFalse(backend.isClosed())
            fixture.eventBus.post(Controller.RewindEvent(false))
          }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `completed external IO starts a fresh rewind history before later rewind`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      val rewind = RewindManager(enabled = true)
      fixture(
              Controller.MobileAdapterConfigurationProvider { configuration(1, backend) },
              rewind,
          )
          .use { fixture ->
            fixture.awaitCondition { rewind.historySize > 0 }
            val preIoHistory = rewind.historySize
            val endpoint = fixture.openLiveUdp(backend)

            fixture.awaitCondition { rewind.historySize == 0 }
            assertTrue(preIoHistory > 0)

            feedMobile(endpoint, packet(UDP_CLOSE, byteArrayOf(0)))
            fixture.awaitCondition {
              endpoint.snapshot().outcome() == MobileAdapterEngine.Outcome.BACKEND_RESPONSE &&
                  !endpoint.hasExternalIo() &&
                  !backend.hasExternalWork()
            }

            fixture.eventBus.post(Controller.ResumeEmulationEvent())
            fixture.awaitCondition { rewind.historySize > 0 }
            fixture.eventBus.post(Controller.PauseEmulationEvent())
            fixture.clearPresentationQueues()

            fixture.eventBus.post(Controller.RewindEvent(true))
            fixture.awaitCondition { rewind.historySize == 0 }
            assertNull(
                fixture.stateBoundaries.poll(200, TimeUnit.MILLISECONDS),
                "rewinding the fresh post-I/O history must not report stale live ownership",
            )
            fixture.eventBus.post(Controller.RewindEvent(false))
          }
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `reset cancels and joins the old backend before the replacement session owns Mobile`() {
    udpFixture().use { server ->
      val oldBackend = backend(server.localPort, 1)
      val replacementBackend = backend(server.localPort, 2)
      val configurations =
          ArrayDeque(
              listOf(
                  configuration(1, oldBackend),
                  configuration(2, replacementBackend),
              ))
      val provider =
          Controller.MobileAdapterConfigurationProvider {
            synchronized(configurations) {
              check(configurations.isNotEmpty()) { "unexpected Mobile configuration load" }
              configurations.removeFirst()
            }
          }
      fixture(provider).use { fixture ->
        val oldEndpoint = fixture.openLiveUdp(oldBackend)
        fixture.clearPresentationQueues()

        fixture.eventBus.post(Controller.ResetEmulationEvent())

        fixture.await(fixture.stateBoundaries) {
          it.boundary == MobileAdapterStateBoundary.RESET
        }
        fixture.await(fixture.networkStatuses) {
          it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
              it.disconnectReason == MobileAdapterDisconnectReason.PROTOCOL_RESET
        }
        fixture.await(fixture.started) { true }
        assertTrue(oldBackend.awaitTermination(2_000))
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, oldEndpoint.snapshot().outcome())
        fixture.awaitCondition { fixture.endpoint.get() !== oldEndpoint }
        assertFalse(replacementBackend.isClosed())
      }
      assertTrue(replacementBackend.awaitTermination(2_000))
    }
  }

  @Test
  fun `detaching Mobile cancels its engine and joins its backend`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      fixture(Controller.MobileAdapterConfigurationProvider { configuration(1, backend) }).use {
          fixture ->
        val endpoint = fixture.openLiveUdp(backend)
        val liveGeneration = backend.generation()
        fixture.clearPresentationQueues()

        fixture.eventBus.post(
            Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.NONE))

        fixture.await(fixture.serialStatuses) {
          it.selection == SerialPeripheralSelection.MOBILE_ADAPTER_GB &&
              it.status == SerialPeripheralStatus.DETACHED
        }
        fixture.await(fixture.serialStatuses) {
          it.selection == SerialPeripheralSelection.NONE &&
              it.status == SerialPeripheralStatus.ATTACHED
        }
        assertTrue(backend.awaitTermination(2_000))
        assertTrue(backend.generation() !== liveGeneration)
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome())
        assertFalse(endpoint.hasExternalIo())
        assertFalse(backend.hasExternalWork())
      }
    }
  }

  @Test
  fun `shutdown publishes its terminal reason and joins live host work`() {
    udpFixture().use { server ->
      val backend = backend(server.localPort, 1)
      fixture(Controller.MobileAdapterConfigurationProvider { configuration(1, backend) }).use {
          fixture ->
        val endpoint = fixture.openLiveUdp(backend)
        val liveGeneration = backend.generation()
        fixture.clearPresentationQueues()

        assertNotNull(fixture.controller.closeWithState())
        fixture.controllerClosed = true

        fixture.await(fixture.networkStatuses) {
          it.phase == MobileAdapterNetworkPhase.DISCONNECTED &&
              it.disconnectReason == MobileAdapterDisconnectReason.SHUTDOWN
        }
        assertTrue(backend.awaitTermination(2_000))
        assertTrue(backend.generation() !== liveGeneration)
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome())
        assertFalse(backend.hasExternalWork())
      }
    }
  }

  private fun fixture(
      provider: Controller.MobileAdapterConfigurationProvider,
      rewind: RewindManager = RewindManager(enabled = false),
      stateWorkspaceFactory: StateWorkspaceFactory = StateWorkspaceFactory.DEFAULT,
      stateOperationWorkerFactory: StateOperationWorkerFactory =
          StateOperationWorkerFactory.DEFAULT,
  ): Fixture {
    val directory = Files.createTempDirectory("coffee-gb-mobile-controller-lifecycle")
    val rom = directory.resolve("game.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val gameboy = AtomicReference<CapturingGameboy>()
    val endpoint = AtomicReference<MobileAdapterSerialEndpoint>()
    val preparer =
        SessionPreparer { emulatorProperties, event ->
          val configuration =
              Controller.createGameboyConfig(emulatorProperties, Rom(event.rom))
                  .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          val candidate = CapturingGameboy(configuration, endpoint)
          gameboy.set(candidate)
          PreparedSession.Ready(configuration, candidate)
        }
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            preparer,
            SnapshotManagerFactory.DEFAULT,
            rewind,
            stateWorkspaceFactory,
            stateOperationWorkerFactory,
            mobileAdapterConfigurationProvider = provider,
        )
    val fixture =
        Fixture(directory, properties, eventBus, controller, gameboy, endpoint)
    controller.startController()
    try {
      eventBus.post(
          Controller.SetSerialPeripheralEvent(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      fixture.await(fixture.selections) {
        it.selection == SerialPeripheralSelection.MOBILE_ADAPTER_GB
      }
      eventBus.post(Controller.LoadRomEvent(rom))
      fixture.await(fixture.started) { true }
      fixture.awaitCondition { endpoint.get() != null }
      fixture.awaitCondition { fixture.stateSessionId >= 0 }
      eventBus.post(Controller.PauseEmulationEvent())
      Thread.sleep(100)
      fixture.clearPresentationQueues()
      return fixture
    } catch (failure: Throwable) {
      fixture.close()
      throw failure
    }
  }

  private fun backend(targetPort: Int, revision: Long): MobileAdapterNetworkBackend =
      MobileAdapterNetworkBackend(
          MobileAdapterDestinationPolicy(
              revision,
              resolver = null,
              rules =
                  listOf(
                      MobileAdapterDestinationRule(
                          "game.service",
                          MobileAdapterTransportTarget.parse("127.0.0.1"),
                          MobileAdapterTransportProtocol.UDP,
                          53,
                          targetPort,
                      )),
          ),
          MobileAdapterRuntimeAuthorization(
              networkConsent = true,
              privateLocalDevelopment = true,
          ),
      )

  private fun configuration(
      revision: Long,
      backend: MobileAdapterNetworkBackend,
  ): MobileAdapterConfiguration {
    val offline = MobileAdapterConfiguration.syntheticOffline()
    return MobileAdapterConfiguration(
        offline.deviceId,
        offline.copyBytes(),
        policyRevision = revision,
        networkBackend = backend,
        runtimeNetworkConsent = true,
        runtimePrivateLocalDevelopment = true,
    )
  }

  private fun assertSavedExternalIoMarker(path: Path) {
    val state = StateCodec.decode(Files.readAllBytes(path))
    val root = state.root as SessionStateRoot
    val endpoint = root.session.serialState as RecordState
    val engine = endpoint.fields.single { it.name == "engineState" }.value as RecordState
    assertEquals(
        MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED.id(),
        (engine.fields.single { it.name == "outcomeId" }.value as Int32State).value,
    )
    assertTrue(
        (engine.fields.single { it.name == "externalIoAtCapture" }.value as BooleanState).value)
  }

  private fun feedMobile(endpoint: MobileAdapterSerialEndpoint, bytes: ByteArray) {
    bytes.forEach { byte -> exchangeMobileByte(endpoint, byte.toInt() and 0xff) }
  }

  private fun completeMobileTransaction(
      endpoint: MobileAdapterSerialEndpoint,
      request: ByteArray,
  ) {
    val command = request[2].toInt() and 0xff
    request.forEach { byte ->
      assertEquals(0xd2, exchangeMobileByte(endpoint, byte.toInt() and 0xff))
    }
    assertEquals(0x88, exchangeMobileByte(endpoint, 0x80))
    assertEquals(command xor 0x80, exchangeMobileByte(endpoint, 0))

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    var first = 0xd2
    while (first == 0xd2 && System.nanoTime() < deadline) {
      endpoint.pollBackendCompletion()
      first = exchangeMobileByte(endpoint, 0x4b)
      if (first == 0xd2) Thread.sleep(2)
    }
    assertEquals(0x99, first, "Mobile Adapter response timed out")

    val header = ByteArray(6)
    header[0] = first.toByte()
    for (index in 1 until header.size) {
      header[index] = exchangeMobileByte(endpoint, 0x4b).toByte()
    }
    val responseCommand = header[2].toInt() and 0xff
    val dataSize = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
    repeat(dataSize + 2) { exchangeMobileByte(endpoint, 0x4b) }
    assertEquals(0x88, exchangeMobileByte(endpoint, 0x80))
    assertEquals(0, exchangeMobileByte(endpoint, responseCommand xor 0x80))
  }

  private fun exchangeMobileByte(
      endpoint: MobileAdapterSerialEndpoint,
      outgoing: Int,
  ): Int {
    endpoint.setSb(outgoing)
    endpoint.startSending()
    var incoming = 0
    repeat(8) { incoming = incoming shl 1 or endpoint.sendBit() }
    return incoming
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

  private fun udpFixture(): DatagramSocket =
      DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

  private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private inner class Fixture(
      val directory: Path,
      val properties: EmulatorProperties,
      val eventBus: EventBusImpl,
      val controller: BasicController,
      val gameboy: AtomicReference<CapturingGameboy>,
      val endpoint: AtomicReference<MobileAdapterSerialEndpoint>,
  ) : AutoCloseable {
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val serialStatuses = LinkedBlockingQueue<SerialPeripheralStatusEvent>()
    val networkStatuses = LinkedBlockingQueue<MobileAdapterNetworkStatusEvent>()
    val stateBoundaries = LinkedBlockingQueue<MobileAdapterStateBoundaryEvent>()
    val snapshotSaved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    val snapshotSaveFailed = LinkedBlockingQueue<Controller.SnapshotSaveFailedEvent>()
    val snapshotRestored = LinkedBlockingQueue<Controller.SnapshotRestoredEvent>()
    val snapshotLoadFailed = LinkedBlockingQueue<Controller.SnapshotLoadFailedEvent>()
    @Volatile var stateSessionId = -1L
    var controllerClosed = false

    init {
      eventBus.register<Controller.EmulationStartedEvent>(started::add)
      eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
      eventBus.register<SerialPeripheralStatusEvent>(serialStatuses::add)
      eventBus.register<MobileAdapterNetworkStatusEvent>(networkStatuses::add)
      eventBus.register<MobileAdapterStateBoundaryEvent>(stateBoundaries::add)
      eventBus.register<Controller.SnapshotSavedEvent>(snapshotSaved::add)
      eventBus.register<Controller.SnapshotSaveFailedEvent>(snapshotSaveFailed::add)
      eventBus.register<Controller.SnapshotRestoredEvent>(snapshotRestored::add)
      eventBus.register<Controller.SnapshotLoadFailedEvent>(snapshotLoadFailed::add)
      eventBus.register<StateUxSessionEvent> { stateSessionId = it.sessionId }
    }

    fun openLiveUdp(backend: MobileAdapterNetworkBackend): MobileAdapterSerialEndpoint {
      val endpoint = assertNotNull(endpoint.get())
      completeMobileTransaction(
          endpoint,
          packet(BEGIN_SESSION, "NINTENDO".encodeToByteArray()),
      )
      completeMobileTransaction(
          endpoint,
          packet(UDP_OPEN, byteArrayOf(127, 0, 0, 1, 0, 53)),
      )
      awaitCondition {
        endpoint.snapshot().outcome() == MobileAdapterEngine.Outcome.BACKEND_RESPONSE &&
            endpoint.hasExternalIo() &&
            backend.hasExternalWork()
      }
      return endpoint
    }

    fun clearPresentationQueues() {
      selections.clear()
      serialStatuses.clear()
      networkStatuses.clear()
      stateBoundaries.clear()
      snapshotSaved.clear()
      snapshotSaveFailed.clear()
      snapshotRestored.clear()
      snapshotLoadFailed.clear()
    }

    fun <T> await(queue: LinkedBlockingQueue<T>, predicate: (T) -> Boolean): T {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (System.nanoTime() < deadline) {
        val value = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
        if (predicate(value)) return value
      }
      throw AssertionError("event timed out")
    }

    fun awaitCondition(predicate: () -> Boolean) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (!predicate()) {
        if (System.nanoTime() >= deadline) throw AssertionError("condition timed out")
        Thread.sleep(2)
      }
    }

    override fun close() {
      if (!controllerClosed) runCatching { controller.close() }
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  private class CapturingGameboy(
      configuration: Gameboy.GameboyConfiguration,
      private val endpoint: AtomicReference<MobileAdapterSerialEndpoint>,
  ) : Gameboy(configuration) {
    val rejectNextMobileHandoff = AtomicBoolean()
    val failAfterNextPressedButtonMutation = AtomicBoolean()

    override fun init(
        eventBus: EventBus,
        serialEndpoint: SerialEndpoint,
        infraredEndpoint: InfraredEndpoint,
        console: Console?,
    ) {
      super.init(eventBus, serialEndpoint, infraredEndpoint, console)
      if (serialEndpoint is MobileAdapterSerialEndpoint) endpoint.set(serialEndpoint)
    }

    override fun setSerialEndpoint(serialEndpoint: SerialEndpoint) {
      if (serialEndpoint is MobileAdapterSerialEndpoint &&
          rejectNextMobileHandoff.compareAndSet(true, false)) {
        throw IllegalStateException("injected Mobile endpoint handoff failure")
      }
      super.setSerialEndpoint(serialEndpoint)
      if (serialEndpoint is MobileAdapterSerialEndpoint) endpoint.set(serialEndpoint)
    }

    override fun setPressedButtons(pressed: MutableCollection<Button>?) {
      super.setPressedButtons(pressed)
      if (failAfterNextPressedButtonMutation.compareAndSet(true, false)) {
        throw IllegalStateException("injected post-endpoint state apply failure")
      }
    }
  }

  private class ToggleFailWriter : AtomicFileWriter() {
    @Volatile var fail = false

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (fail) throw IOException("injected managed-state write failure")
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class ManualExecutorService : AbstractExecutorService() {
    private val tasks = LinkedBlockingQueue<Runnable>()
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) {
      check(!shutdown)
      tasks.add(command)
    }

    fun awaitQueued(count: Int) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (tasks.size < count && System.nanoTime() < deadline) Thread.yield()
      assertTrue(tasks.size >= count, "expected $count queued state tasks, found ${tasks.size}")
    }

    fun runNext() {
      assertNotNull(tasks.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).run()
    }

    override fun shutdown() {
      shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown = true
      return mutableListOf<Runnable>().also(tasks::drainTo)
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    const val TIMEOUT_SECONDS = 10L
    const val BEGIN_SESSION = 0x10
    const val UDP_OPEN = 0x25
    const val UDP_CLOSE = 0x26
    const val DNS_QUERY = 0x28
  }
}
