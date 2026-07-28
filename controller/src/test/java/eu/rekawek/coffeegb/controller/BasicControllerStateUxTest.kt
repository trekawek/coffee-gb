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
import eu.rekawek.coffeegb.controller.state.StateLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationWorker
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRepository
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateStoragePaths
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerStateUxTest {

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
          StateLoadRequestEvent(
              11,
              stateSession.sessionId,
              StateEntryKey(StateRef.Slot(2)),
          ))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )

      eventBus.post(StateCatalogRequestEvent(20, stateSession.sessionId))
      eventBus.post(StateCatalogRequestEvent(21, stateSession.sessionId))
      stateExecutor.awaitQueued(2)
      stateExecutor.runNext()
      stateExecutor.runNext()
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

  private fun workspace(
      paths: StateStoragePaths,
      persistence: AtomicFileWriter,
  ): StateWorkspace =
      StateWorkspace(paths) { layout -> StateRepository(layout, persistence) }

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
