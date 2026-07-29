package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomReplacementPersistenceFailedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCatalogReadyEvent
import eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent
import eu.rekawek.coffeegb.controller.state.StateDeleteRequestEvent
import eu.rekawek.coffeegb.controller.state.StateEntryKey
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent
import eu.rekawek.coffeegb.controller.state.StateLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationWorker
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateGraph
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRepository
import eu.rekawek.coffeegb.controller.state.StateSaveMetadata
import eu.rekawek.coffeegb.controller.state.StateStorageLayout
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateStoragePaths
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerStateUxTest {

  @Test
  fun managedSessionStateRestoresPartialMobileAndReleasedMachineRootsCancelOnlyOnSuccess() {
    val directory = Files.createTempDirectory("controller-mobile-managed-state")
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
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val selections =
        LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
    val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
    val failed = LinkedBlockingQueue<StateOperationFailedEvent>()
    val snapshotSaved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    val snapshotRestored = LinkedBlockingQueue<Controller.SnapshotRestoredEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
    eventBus.register<StateOperationCompletedEvent>(completed::add)
    eventBus.register<StateOperationFailedEvent>(failed::add)
    eventBus.register<Controller.SnapshotSavedEvent>(snapshotSaved::add)
    eventBus.register<Controller.SnapshotRestoredEvent>(snapshotRestored::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)
    val mobileEndpoint = AtomicReference<MobileAdapterSerialEndpoint>()
    val preparer =
        SessionPreparer { emulatorProperties, event ->
          val configuration =
              Controller.createGameboyConfig(emulatorProperties, Rom(event.rom))
                  .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(configuration) {
                override fun init(
                    bus: EventBus,
                    serialEndpoint: SerialEndpoint,
                    infraredEndpoint: InfraredEndpoint,
                    console: Console?,
                ) {
                  if (serialEndpoint is MobileAdapterSerialEndpoint) {
                    mobileEndpoint.set(serialEndpoint)
                  }
                  super.init(bus, serialEndpoint, infraredEndpoint, console)
                }
              }
          PreparedSession.Ready(configuration, gameboy)
        }
    val stateExecutor = ManualExecutorService()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            preparer,
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
            mobileAdapterConfigurationProvider =
                Controller.MobileAdapterConfigurationProvider {
                  Controller.MobileAdapterConfiguration.syntheticOffline()
                },
        )

    controller.startController()
    try {
      eventBus.post(
          Controller.SetSerialPeripheralEvent(
              Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      assertEquals(
          Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          selections.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.selection,
      )
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val endpoint = assertNotNull(mobileEndpoint.get())

      eventBus.post(Controller.PauseEmulationEvent())
      Thread.sleep(100)
      frames.clear()
      assertNull(frames.poll(200, TimeUnit.MILLISECONDS))

      val begin = mobilePacket(0x10, "NINTENDO".encodeToByteArray())
      feedMobile(endpoint, begin.copyOf(6))
      val slot = StateRef.Slot(3)
      eventBus.post(StateSaveRequestEvent(1, stateSession.sessionId, slot, null, null))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.SAVE,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )

      val repository =
          StateRepository(StateStorageLayout(assertNotNull(stateSession.gameDirectory)))
      val savedSession = repository.read(slot).state
      val sessionRoot = savedSession.root as SessionStateRoot
      val endpointRecord = sessionRoot.session.serialState as RecordState
      val engineRecord = endpointRecord.fields.single { it.name == "engineState" }.value as RecordState
      assertEquals(
          6,
          (engineRecord.fields.single { it.name == "packetCount" }.value
                  as eu.rekawek.coffeegb.controller.state.Int32State)
              .value,
      )

      endpoint.disconnect()
      eventBus.post(StateLoadRefRequestEvent(2, stateSession.sessionId, slot))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      assertEquals(6, endpoint.snapshot().retainedBytes())
      feedMobile(endpoint, begin.copyOfRange(6, begin.size))
      assertEquals(MobileAdapterEngine.Outcome.SESSION_STARTED, endpoint.snapshot().outcome())

      val releasedMachine =
          eu.rekawek.coffeegb.controller.state.StateFile(
              savedSession.identities,
              MachineStateRoot(sessionRoot.session.machine),
              savedSession.diagnostics,
              savedSession.formatVersion,
          )
      val incompatibleIdentity = releasedMachine.identities.single().identity!!
      val incompatibleRomHash = incompatibleIdentity.primaryRom.copyBytes()
      incompatibleRomHash[0] = (incompatibleRomHash[0].toInt() xor 0xff).toByte()
      val incompatibleMachine =
          eu.rekawek.coffeegb.controller.state.StateFile(
              listOf(
                  releasedMachine.identities.single().copy(
                      identity =
                          incompatibleIdentity.copy(
                              primaryRom =
                                  eu.rekawek.coffeegb.controller.state.RomIdentity(
                                      incompatibleRomHash)))),
              releasedMachine.root,
              releasedMachine.diagnostics,
              releasedMachine.formatVersion,
          )
      repository.save(
          slot,
          StateCodec.encode(incompatibleMachine),
          StateSaveMetadata(savedAt = Instant.EPOCH),
      )
      val beforeInvalid = StateGraph.capture(endpoint.captureState())
      eventBus.post(StateLoadRefRequestEvent(3, stateSession.sessionId, slot))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(failed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      assertEquals(beforeInvalid, StateGraph.capture(endpoint.captureState()))

      repository.save(
          slot,
          StateCodec.encode(releasedMachine),
          StateSaveMetadata(savedAt = Instant.EPOCH.plusSeconds(1)),
      )
      eventBus.post(StateLoadRefRequestEvent(4, stateSession.sessionId, slot))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome())
      assertEquals(0, endpoint.snapshot().retainedBytes())
      assertTrue(endpoint.snapshot().responsePacket().isEmpty())

      // Public direct snapshot events now own the same session timeline.
      feedMobile(endpoint, begin.copyOf(6))
      eventBus.post(Controller.SaveSnapshotEvent(4))
      assertEquals(
          4,
          assertNotNull(snapshotSaved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot,
      )
      val directSidecar = directory.resolve("game.sn4")
      assertEquals(
          eu.rekawek.coffeegb.controller.state.StateRootKind.SESSION,
          StateCodec.inspect(Files.readAllBytes(directSidecar)).rootKind,
      )
      endpoint.disconnect()
      eventBus.post(Controller.RestoreSnapshotEvent(4))
      assertEquals(
          4,
          assertNotNull(snapshotRestored.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot,
      )
      assertEquals(6, endpoint.snapshot().retainedBytes())

      // The managed quick-load fallback reads a portable sidecar off-thread, then applies its
      // session root at the controller frame boundary.
      endpoint.disconnect()
      feedMobile(endpoint, begin.copyOf(2))
      eventBus.post(Controller.SaveSnapshotEvent(5))
      assertEquals(
          5,
          assertNotNull(snapshotSaved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot,
      )
      endpoint.disconnect()
      eventBus.post(StateLoadRefRequestEvent(5, stateSession.sessionId, StateRef.Slot(5)))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      assertEquals(2, endpoint.snapshot().retainedBytes())

      // Released machine-root sidecars remain readable, but deliberately reset Mobile because
      // they cannot describe an endpoint continuation.
      Files.write(directory.resolve("game.sn6"), StateCodec.encode(releasedMachine))
      eventBus.post(StateLoadRefRequestEvent(6, stateSession.sessionId, StateRef.Slot(6)))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome())
      assertEquals(0, endpoint.snapshot().retainedBytes())
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun closeAutosaveWaiverRequiresTheExactCompletedFailure() {
    val directory = Files.createTempDirectory("controller-autosave-waiver")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    val persistence = ToggleFailWriter().also { it.fail = true }
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory { paths -> workspace(paths, persistence) },
            StateOperationWorkerFactory.DEFAULT,
        )
    var closed = false
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val failed =
          assertFailsWith<Controller.PersistenceBarrierException> {
            controller.closeWithState()
          }
      assertTrue(failed.closeAutosaveWaivable)
      assertTrue(!controller.waiveCloseAutosave(failed.requestId + 1))
      assertTrue(controller.waiveCloseAutosave(failed.requestId))
      assertTrue(!controller.waiveCloseAutosave(failed.requestId))

      assertNotNull(controller.closeWithState())
      closed = true
      assertTrue(
          Files.notExists(
              assertNotNull(stateSession.gameDirectory)
                  .resolve("states")
                  .resolve("autosave")
                  .resolve("state.cgbstate")))
    } finally {
      persistence.fail = false
      if (!closed) {
        runCatching { controller.close() }
      }
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun closeAutosaveIsBoundedRetainedAndRetryableWithoutDesktopPreflight() {
    val directory = Files.createTempDirectory("controller-autosave-close")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    val persistence = ToggleFailWriter()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory { paths -> workspace(paths, persistence) },
            StateOperationWorkerFactory.DEFAULT,
        )
    var closed = false
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val gameDirectory =
          assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).gameDirectory

      persistence.fail = true
      val failure =
          assertFailsWith<Controller.PersistenceBarrierException> {
            controller.closeWithState()
          }
      assertEquals(Controller.PersistenceBarrierOperation.CLOSE, failure.operation)
      assertEquals("autosave state", failure.fileName)
      assertTrue(failure.closeAutosaveWaivable)
      assertTrue(!controller.waiveCloseAutosave(failure.requestId + 1))

      persistence.fail = false
      assertNotNull(controller.closeWithState())
      closed = true
      assertTrue(
          Files.isRegularFile(
              assertNotNull(gameDirectory)
                  .resolve("states")
                  .resolve("autosave")
                  .resolve("state.cgbstate")))
    } finally {
      persistence.fail = false
      if (!closed) {
        runCatching { controller.close() }
      }
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun inFlightCloseAutosaveCannotBeWaived() {
    val directory = Files.createTempDirectory("controller-autosave-in-flight")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    val persistence = BlockingWriter()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory { paths -> workspace(paths, persistence) },
            StateOperationWorkerFactory.DEFAULT,
            closeTimeoutMillis = 300,
        )
    var closed = false
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val failure =
          assertFailsWith<Controller.PersistenceBarrierException> {
            controller.closeWithState()
          }
      assertTrue(persistence.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(!failure.closeAutosaveWaivable)
      assertTrue(!controller.waiveCloseAutosave(failure.requestId))

      persistence.release.countDown()
      assertNotNull(controller.closeWithState())
      closed = true
    } finally {
      persistence.release.countDown()
      if (!closed) runCatching { controller.close() }
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun queuedDeleteCannotRunAfterTerminalCloseAutosave() {
    val directory = Files.createTempDirectory("controller-terminal-autosave")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    val stateExecutor = ManualExecutorService(runQueuedOnShutdown = true)
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
        )
    var closed = false
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val autosave =
          assertNotNull(stateSession.gameDirectory)
              .resolve("states")
              .resolve("autosave")
              .resolve("state.cgbstate")

      eventBus.post(
          StateDeleteRequestEvent(
              requestId = 70,
              expectedSessionId = stateSession.sessionId,
              key = StateEntryKey(StateRef.Autosave),
          ))
      stateExecutor.awaitQueued(1)

      assertNotNull(controller.closeWithState())
      closed = true
      val unavailable = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(stateSession.sessionId, unavailable.sessionId)
      assertTrue(!unavailable.available)
      assertTrue(
          Files.isRegularFile(autosave),
          "the exact terminal autosave must be the last state mutation during close",
      )
    } finally {
      if (!closed) runCatching { controller.close() }
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun romSwitchAutosaveUsesCorrelatedRetryAndCancelBarrier() {
    val directory = Files.createTempDirectory("controller-autosave-switch")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val persistenceFailures =
        LinkedBlockingQueue<RomReplacementPersistenceFailedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<RomReplacementPersistenceFailedEvent>(persistenceFailures::add)
    eventBus.register<RomLoadingCancelledEvent>(cancelled::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)

    val stateExecutor = ManualExecutorService()
    val persistence = ToggleFailWriter()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory { paths -> workspace(paths, persistence) },
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
        )

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      persistence.fail = true
      frames.clear()
      eventBus.post(Controller.LoadRomEvent(rom, openRequestId = 100))
      stateExecutor.awaitQueued(1)
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "the old session must stay paused while ROM-switch autosave is queued",
      )
      stateExecutor.runNext()
      val retryable =
          assertNotNull(persistenceFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(100, retryable.openRequestId)
      assertEquals(
          Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
          retryable.operation,
      )
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "a retryable autosave failure must retain the pause owner",
      )

      persistence.fail = false
      eventBus.post(Controller.RetryRomReplacementEvent(retryable.requestId))
      stateExecutor.awaitQueued(1)
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "retrying the same immutable autosave must remain paused",
      )
      stateExecutor.runNext()
      val reopened = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(100, reopened.openRequestId)

      eventBus.post(Controller.PauseEmulationEvent())
      Thread.sleep(100)
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "the replacement session must acknowledge the user's pause before cancellation testing",
      )
      persistence.fail = true
      frames.clear()
      eventBus.post(Controller.LoadRomEvent(rom, openRequestId = 101))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val cancellable =
          assertNotNull(persistenceFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(101, cancellable.openRequestId)
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "the failed replacement remains paused until the exact request is cancelled",
      )
      eventBus.post(Controller.CancelRomReplacementEvent(cancellable.requestId))
      assertEquals(
          101,
          assertNotNull(cancelled.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).openRequestId,
      )
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "cancelling must restore the exact paused state, not force the game to run",
      )
      eventBus.post(Controller.ResumeEmulationEvent())
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the retained game must still resume when the user requests it",
      )
    } finally {
      persistence.fail = false
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun resumeScanOwnsPauseUntilItsWorkerResultIsApplied() {
    val directory = Files.createTempDirectory("controller-resume-pause")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        resumePolicy = ApplicationSettings.ResumePolicy.ALWAYS,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)
    val stateExecutor = ManualExecutorService()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
        )

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      stateExecutor.awaitQueued(1)
      // Drain any frame that was already posted while the new session was being activated.
      Thread.sleep(100)
      frames.clear()
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "the game must not advance while resume discovery is unresolved",
      )

      stateExecutor.runNext()
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "a completed scan with no autosave must restore the user's running state",
      )
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun workerResultsApplyAtFramesSuppressStaleRequestsAndPreserveSessionOnFailure() {
    val directory = Files.createTempDirectory("controller-state-ux")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
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
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
    val failed = LinkedBlockingQueue<StateOperationFailedEvent>()
    val catalogs = LinkedBlockingQueue<StateCatalogReadyEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<StateOperationCompletedEvent>(completed::add)
    eventBus.register<StateOperationFailedEvent>(failed::add)
    eventBus.register<StateCatalogReadyEvent>(catalogs::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)

    val stateExecutor = ManualExecutorService()
    val persistence = ToggleFailWriter()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory { paths ->
              workspace(paths, persistence)
            },
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
        )

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(stateSession.available)
      val gameDirectory = assertNotNull(stateSession.gameDirectory)

      eventBus.post(StateCatalogRequestEvent(9, stateSession.sessionId + 1))
      val staleAdmission = assertNotNull(failed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(9, staleAdmission.requestId)
      assertEquals(stateSession.sessionId + 1, staleAdmission.sessionId)
      assertEquals(StateOperation.CATALOG, staleAdmission.operation)
      assertEquals(0, stateExecutor.queuedCount(), "stale session work must not reach the worker")

      val image = StateImage(2, 2, intArrayOf(0, 1, 2, 3))
      eventBus.post(
          StateSaveRequestEvent(
              10,
              stateSession.sessionId,
              StateRef.Slot(2),
              "test slot",
              image,
          ))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.SAVE,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      val statePath =
          gameDirectory.resolve("states").resolve("slots").resolve("2").resolve("state.cgbstate")
      val firstValidState = Files.readAllBytes(statePath)
      assertTrue(firstValidState.isNotEmpty())

      eventBus.post(
          StateLoadRefRequestEvent(
              11,
              stateSession.sessionId,
              StateRef.Slot(2),
          ))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )

      eventBus.post(StateCatalogRequestEvent(20, stateSession.sessionId))
      eventBus.post(
          StateLoadRefRequestEvent(
              22,
              stateSession.sessionId,
              StateRef.Slot(2),
          ))
      eventBus.post(StateCatalogRequestEvent(21, stateSession.sessionId))
      stateExecutor.awaitQueued(3)
      stateExecutor.runNext()
      stateExecutor.runNext()
      stateExecutor.runNext()
      val independentLoad = assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(22, independentLoad.requestId)
      assertEquals(StateOperation.LOAD, independentLoad.operation)
      assertEquals(StateRef.Slot(2), independentLoad.ref)
      assertEquals(21, assertNotNull(catalogs.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).requestId)
      assertNull(catalogs.poll(250, TimeUnit.MILLISECONDS), "stale catalog result must be ignored")

      persistence.fail = true
      frames.clear()
      eventBus.post(
          StateSaveRequestEvent(
              30,
              stateSession.sessionId,
              StateRef.Slot(2),
              "replacement",
              image,
          ))
      stateExecutor.runNext()
      val writeFailure = assertNotNull(failed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(StateOperation.SAVE, writeFailure.operation)
      assertContentEquals(firstValidState, Files.readAllBytes(statePath))
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "an injected state write failure must not stop the active game",
      )

      persistence.fail = false
      Files.write(statePath, byteArrayOf(1, 2, 3, 4))
      frames.clear()
      eventBus.post(
          StateLoadRequestEvent(
              40,
              stateSession.sessionId,
              StateEntryKey(StateRef.Slot(2)),
          ))
      stateExecutor.runNext()
      val loadFailure = assertNotNull(failed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(StateOperation.LOAD, loadFailure.operation)
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "a corrupt state must be rejected without replacing the active session",
      )
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun managedQuickLoadUsesLegacySidecarOnlyWhenEveryManagedSourceIsEmpty() {
    val directory = Files.createTempDirectory("controller-quick-load-legacy")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val legacySaved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    val legacyRestored = LinkedBlockingQueue<Controller.SnapshotRestoredEvent>()
    val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
    val failed = LinkedBlockingQueue<StateOperationFailedEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<Controller.SnapshotSavedEvent>(legacySaved::add)
    eventBus.register<Controller.SnapshotRestoredEvent>(legacyRestored::add)
    eventBus.register<StateOperationCompletedEvent>(completed::add)
    eventBus.register<StateOperationFailedEvent>(failed::add)
    val stateExecutor = ManualExecutorService()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory { bus ->
              StateOperationWorker(bus, executor = stateExecutor)
            },
        )

    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val slot = StateRef.Slot(6)
      val managedState =
          assertNotNull(stateSession.gameDirectory)
              .resolve("states")
              .resolve("slots")
              .resolve(slot.index.toString())
              .resolve("state.cgbstate")

      eventBus.post(Controller.SaveSnapshotEvent(slot.index))
      assertEquals(
          slot.index,
          assertNotNull(legacySaved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot,
      )
      val legacySidecar = directory.resolve("game.sn${slot.index}")
      val legacyBytes = Files.readAllBytes(legacySidecar)
      assertFalse(Files.exists(managedState))

      eventBus.post(StateLoadRefRequestEvent(100, stateSession.sessionId, slot))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val legacyLoad = assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(100, legacyLoad.requestId)
      assertEquals(StateOperation.LOAD, legacyLoad.operation)
      assertEquals(slot, legacyLoad.ref)
      assertTrue(legacyLoad.message.contains("Legacy state loaded"))
      assertNull(
          legacyRestored.poll(250, TimeUnit.MILLISECONDS),
          "the compatibility path must report through managed-state completion events",
      )
      assertContentEquals(legacyBytes, Files.readAllBytes(legacySidecar))

      Files.createDirectories(managedState.parent)
      Files.write(managedState, byteArrayOf(1, 2, 3, 4))
      eventBus.post(StateLoadRefRequestEvent(101, stateSession.sessionId, slot))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val corruptManaged = assertNotNull(failed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(101, corruptManaged.requestId)
      assertEquals(StateOperation.LOAD, corruptManaged.operation)
      assertNull(
          completed.poll(250, TimeUnit.MILLISECONDS),
          "a present corrupt managed slot must not fall through to the legacy sidecar",
      )
      assertContentEquals(legacyBytes, Files.readAllBytes(legacySidecar))
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun compatibilitySidecarIsImportedOnStateWorkerAndAppliedAtControllerFrameBoundary() {
    val directory = Files.createTempDirectory("controller-quick-load-threading")
    val rom = directory.resolve("game.gbc").toFile().also { it.writeBytes(ROM.readBytes()) }
    val sidecar = directory.resolve("game.sn4")
    val sidecarBytes =
        Paths.get(
                "src/test/resources/legacy",
                "coffee-gb-1.7.14-cpu-instrs.sn",
            )
            .toFile()
            .readBytes()
    Files.write(sidecar, sidecarBytes)
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<StateOperationCompletedEvent>(completed::add)
    val importThread = AtomicReference<Thread?>()
    val applyThread = AtomicReference<Thread?>()
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory { configuration ->
              SnapshotManager.testing(
                  configuration,
                  LegacySnapshotMigrationPolicy.PRESERVE,
                  legacyApplyProbe = { applyThread.compareAndSet(null, Thread.currentThread()) },
              )
            },
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory.DEFAULT,
        )

    LegacySnapshotImporter.importObserver = {
      importThread.compareAndSet(null, Thread.currentThread())
    }
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val stateSession = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(StateLoadRefRequestEvent(200, stateSession.sessionId, StateRef.Slot(4)))

      val loaded = assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(200, loaded.requestId)
      assertEquals("coffee-gb-state-worker", assertNotNull(importThread.get()).name)
      assertEquals("coffee-gb-controller", assertNotNull(applyThread.get()).name)
      assertTrue(importThread.get() !== applyThread.get())
      assertContentEquals(sidecarBytes, Files.readAllBytes(sidecar))
    } finally {
      LegacySnapshotImporter.importObserver = null
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  private fun workspace(
      paths: StateStoragePaths,
      persistence: AtomicFileWriter,
  ): StateWorkspace =
      StateWorkspace(paths) { layout -> StateRepository(layout, persistence) }

  private fun feedMobile(endpoint: MobileAdapterSerialEndpoint, bytes: ByteArray) {
    bytes.forEach { byte ->
      endpoint.setSb(byte.toInt() and 0xff)
      endpoint.startSending()
      repeat(8) { endpoint.sendBit() }
    }
  }

  private fun mobilePacket(command: Int, data: ByteArray): ByteArray {
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

  private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    const val TIMEOUT_SECONDS = 10L
  }

  private class ToggleFailWriter : AtomicFileWriter() {
    @Volatile var fail = false

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (fail) throw IOException("injected managed-state write failure")
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class BlockingWriter : AtomicFileWriter() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun write(target: Path, intendedBytes: ByteArray) {
      entered.countDown()
      while (release.count != 0L) {
        try {
          release.await()
        } catch (_: InterruptedException) {
          // Model a filesystem call that remains physically in flight after the deadline.
        }
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class ManualExecutorService(
      private val runQueuedOnShutdown: Boolean = false,
  ) : AbstractExecutorService() {
    private val tasks = LinkedBlockingQueue<Runnable>()
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) {
      check(!shutdown)
      tasks.add(command)
    }

    fun awaitQueued(count: Int) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (tasks.size < count && System.nanoTime() < deadline) {
        Thread.yield()
      }
      assertTrue(tasks.size >= count, "expected $count queued state tasks, found ${tasks.size}")
    }

    fun runNext() {
      assertNotNull(tasks.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).run()
    }

    fun queuedCount(): Int = tasks.size

    override fun shutdown() {
      shutdown = true
      if (runQueuedOnShutdown) {
        while (true) {
          val task = tasks.poll() ?: break
          task.run()
        }
      }
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown = true
      return mutableListOf<Runnable>().also(tasks::drainTo)
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }
}
