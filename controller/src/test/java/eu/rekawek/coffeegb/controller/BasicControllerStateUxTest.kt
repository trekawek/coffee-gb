package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomReplacementPersistenceFailedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCatalogReadyEvent
import eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerStateUxTest {

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
      eventBus.post(Controller.LoadRomEvent(rom, openRequestId = 100))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val retryable =
          assertNotNull(persistenceFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(100, retryable.openRequestId)
      assertEquals(
          Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
          retryable.operation,
      )
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "failed autosave must retain and resume the active game",
      )

      persistence.fail = false
      eventBus.post(Controller.RetryRomReplacementEvent(retryable.requestId))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val reopened = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(100, reopened.openRequestId)

      persistence.fail = true
      eventBus.post(Controller.LoadRomEvent(rom, openRequestId = 101))
      stateExecutor.awaitQueued(1)
      stateExecutor.runNext()
      val cancellable =
          assertNotNull(persistenceFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(101, cancellable.openRequestId)
      eventBus.post(Controller.CancelRomReplacementEvent(cancellable.requestId))
      assertEquals(
          101,
          assertNotNull(cancelled.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).openRequestId,
      )
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "cancelled autosave barrier must retain the active game",
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

      val image = StateImage(2, 2, intArrayOf(0, 1, 2, 3))
      eventBus.post(StateSaveRequestEvent(10, StateRef.Slot(2), "test slot", image))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.SAVE,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )
      val statePath =
          gameDirectory.resolve("states").resolve("slots").resolve("2").resolve("state.cgbstate")
      val firstValidState = Files.readAllBytes(statePath)
      assertTrue(firstValidState.isNotEmpty())

      eventBus.post(StateLoadRequestEvent(11, StateEntryKey(StateRef.Slot(2))))
      stateExecutor.runNext()
      assertEquals(
          StateOperation.LOAD,
          assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).operation,
      )

      eventBus.post(StateCatalogRequestEvent(20))
      eventBus.post(StateCatalogRequestEvent(21))
      stateExecutor.awaitQueued(2)
      stateExecutor.runNext()
      stateExecutor.runNext()
      assertEquals(21, assertNotNull(catalogs.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).requestId)
      assertNull(catalogs.poll(250, TimeUnit.MILLISECONDS), "stale catalog result must be ignored")

      persistence.fail = true
      frames.clear()
      eventBus.post(StateSaveRequestEvent(30, StateRef.Slot(2), "replacement", image))
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
      eventBus.post(StateLoadRequestEvent(40, StateEntryKey(StateRef.Slot(2))))
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

  private class ManualExecutorService : AbstractExecutorService() {
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
}
