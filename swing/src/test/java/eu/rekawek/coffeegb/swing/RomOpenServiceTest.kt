package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomOpenServiceTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `successful controller start is the only point that records a recent ROM`() {
    val fixture = fixture()
    val source = romFile("success.gb", "SUCCESS")

    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()

    val load = fixture.loads.single()
    assertEquals(requestId, load.openRequestId)
    assertTrue(fixture.recents.recorded.isEmpty())
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Opened })

    fixture.eventBus.post(
        Controller.EmulationStartedEvent(
            "SUCCESS",
            load.image!!.origin(),
            requestId,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(source.toAbsolutePath().normalize()), fixture.recents.recorded)
    val opened = assertIs<RomOpenUpdate.Opened>(fixture.updates.last())
    assertEquals(requestId, opened.requestId)
    assertEquals(RomOrigin.Kind.DIRECT_FILE, opened.origin.kind())
    fixture.close()
  }

  @Test
  fun `typed preparation failure never reaches controller or recents`() {
    val fixture = fixture()
    val source = temporaryFolder.newFile("notes.txt").toPath()

    fixture.service.open(RomOpenRequest(source, RomOpenSource.DROP))
    fixture.worker.runAll()
    fixture.ui.runAll()

    val failed = assertIs<RomOpenUpdate.Failed>(fixture.updates.last())
    assertEquals(RomOpenFailureKind.UNSUPPORTED_TYPE, failed.failure.kind)
    assertTrue(fixture.loads.isEmpty())
    assertTrue(fixture.recents.recorded.isEmpty())
    fixture.close()
  }

  @Test
  fun `dropped directory is a typed failure and never reaches controller`() {
    val fixture = fixture()

    fixture.service.open(
        RomOpenRequest(
            temporaryFolder.root.toPath(),
            RomOpenSource.DROP,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(
        RomOpenFailureKind.NOT_A_FILE,
        assertIs<RomOpenUpdate.Failed>(fixture.updates.last()).failure.kind,
    )
    assertTrue(fixture.loads.isEmpty())
    fixture.close()
  }

  @Test
  fun `remote and multiple drop inputs are rejected without touching files`() {
    val fixture = fixture()

    fixture.service.open(
        RomOpenRequest(
            listOf(RomOpenInput.RemoteUrl("https://example.invalid/game.gb")),
            RomOpenSource.DROP,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()
    assertEquals(
        RomOpenFailureKind.REMOTE_URL,
        assertIs<RomOpenUpdate.Failed>(fixture.updates.last()).failure.kind,
    )

    fixture.service.open(
        RomOpenRequest(
            listOf(
                RomOpenInput.LocalPath(Path.of("first.gb")),
                RomOpenInput.LocalPath(Path.of("second.gb")),
            ),
            RomOpenSource.DROP,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()
    assertEquals(
        RomOpenFailureKind.MULTIPLE_INPUTS,
        assertIs<RomOpenUpdate.Failed>(fixture.updates.last()).failure.kind,
    )
    assertTrue(fixture.loads.isEmpty())
    fixture.close()
  }

  @Test
  fun `archive choice dispatches the exact selected immutable entry`() {
    val fixture = fixture()
    val archive = temporaryFolder.newFile("games.zip")
    val first = syntheticRom("FIRST", 0x31)
    val second = syntheticRom("SECOND", 0x32)
    writeZip(
        archive,
        "one/game.gb" to first,
        "two/game.gbc" to second,
    )

    val requestId =
        fixture.service.open(RomOpenRequest(archive.toPath(), RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    fixture.ui.runAll()

    val choice =
        fixture.updates
            .filterIsInstance<RomOpenUpdate.Progress>()
            .single { it.stage == RomOpenStage.AWAITING_ARCHIVE_SELECTION }
    assertEquals(2, choice.candidates.size)
    fixture.service.selectArchive(requestId, choice.candidates[1].token())
    fixture.worker.runAll()

    val image = fixture.loads.single().image!!
    assertEquals("two/game.gbc", image.origin().archiveEntry().orElseThrow())
    assertEquals(0x32.toByte(), image.bytes()[0x200])
    fixture.close()
  }

  @Test
  fun `queued callbacks from a completed older request cannot overwrite a newer request`() {
    val fixture = fixture()
    val first = romFile("first.gb", "FIRST")
    val second = romFile("second.gb", "SECOND")

    val firstId =
        fixture.service.open(RomOpenRequest(first, RomOpenSource.INITIAL_ARGUMENT))
    fixture.worker.runAll()
    val firstLoad = fixture.loads.single()
    fixture.eventBus.post(
        Controller.EmulationStartedEvent("FIRST", firstLoad.image!!.origin(), firstId))
    assertTrue(fixture.updates.isEmpty(), "UI executor is intentionally still queued")

    val secondId = fixture.service.open(RomOpenRequest(second, RomOpenSource.RECENT))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(first.toAbsolutePath().normalize()), fixture.recents.recorded)
    assertTrue(fixture.updates.all { it.requestId == secondId })
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Opened })
    fixture.close()
  }

  @Test
  fun `cancel after controller dispatch is correlated and completes on controller acknowledgement`() {
    val fixture = fixture()
    val source = romFile("cancel.gb", "CANCEL")
    val cancellations = mutableListOf<Controller.CancelRomOpenEvent>()
    fixture.eventBus.register<Controller.CancelRomOpenEvent> { cancellations += it }

    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.DROP))
    fixture.worker.runAll()
    fixture.service.cancel(requestId)
    fixture.worker.runAll()

    assertEquals(listOf(requestId), cancellations.map { it.openRequestId })
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Cancelled })
    fixture.eventBus.post(
        Controller.RomLoadingCancelledEvent(source.toFile(), requestId))
    fixture.worker.runAll()
    fixture.ui.runAll()
    assertIs<RomOpenUpdate.Cancelled>(fixture.updates.last())
    fixture.close()
  }

  @Test
  fun `persistence barrier exposes non-modal retry and matching cancel controls`() {
    val fixture = fixture()
    val source = romFile("persistence.gb", "PERSIST")
    val retries = mutableListOf<Controller.RetryRomReplacementEvent>()
    val cancellations = mutableListOf<Controller.CancelRomOpenEvent>()
    fixture.eventBus.register<Controller.RetryRomReplacementEvent> { retries += it }
    fixture.eventBus.register<Controller.CancelRomOpenEvent> { cancellations += it }

    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    fixture.eventBus.post(
        Controller.RomReplacementPersistenceFailedEvent(
            91,
            "old.sav",
            "disk full",
            openRequestId = requestId,
        ))
    fixture.ui.runAll()
    val barrier = assertIs<RomOpenUpdate.Progress>(fixture.updates.last())
    assertEquals(RomOpenStage.AWAITING_PERSISTENCE_DECISION, barrier.stage)

    fixture.service.retryPersistence(requestId)
    fixture.worker.runAll()
    assertEquals(listOf(91L), retries.map { it.requestId })

    fixture.eventBus.post(
        Controller.RomReplacementPersistenceFailedEvent(
            92,
            "old.sav",
            "still full",
            openRequestId = requestId,
        ))
    fixture.service.cancel(requestId)
    fixture.worker.runAll()
    assertEquals(listOf(requestId), cancellations.map { it.openRequestId })
    fixture.close()
  }

  @Test
  fun `production pipeline performs source work and controller dispatch off the EDT`() {
    val eventBus = EventBusImpl(null, "thread-test", false)
    val recents = FakeRecentStore()
    val source = romFile("thread.gb", "THREAD")
    val dispatched = CountDownLatch(1)
    val dispatchedOnEdt = AtomicBoolean(true)
    eventBus.register<Controller.LoadRomEvent> {
      dispatchedOnEdt.set(SwingUtilities.isEventDispatchThread())
      dispatched.countDown()
    }
    val service =
        RomOpenService(
            eventBus,
            recents,
            {},
            null,
            Executor { task -> SwingUtilities.invokeLater(task) },
        )

    SwingUtilities.invokeAndWait {
      service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    }

    assertTrue(dispatched.await(5, TimeUnit.SECONDS))
    assertFalse(dispatchedOnEdt.get())
    service.close()
    eventBus.close()
  }

  @Test
  fun `production observer callbacks return to the EDT`() {
    val eventBus = EventBusImpl(null, "ui-thread-test", false)
    val observed = CountDownLatch(1)
    val observedOnEdt = AtomicBoolean()
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            {
              if (it is RomOpenUpdate.Failed) {
                observedOnEdt.set(SwingUtilities.isEventDispatchThread())
                observed.countDown()
              }
            },
            null,
            Executor { task -> SwingUtilities.invokeLater(task) },
        )

    service.open(
        RomOpenRequest(
            listOf(RomOpenInput.RemoteUrl("https://example.invalid/game.gb")),
            RomOpenSource.DROP,
        ))

    assertTrue(observed.await(5, TimeUnit.SECONDS))
    assertTrue(observedOnEdt.get())
    service.close()
    eventBus.close()
  }

  private fun fixture(): Fixture {
    val eventBus = EventBusImpl(null, "test", false)
    val worker = QueuedExecutorService()
    val ui = QueuedExecutor()
    val recents = FakeRecentStore()
    val updates = mutableListOf<RomOpenUpdate>()
    val loads = mutableListOf<Controller.LoadRomEvent>()
    eventBus.register<Controller.LoadRomEvent> { loads += it }
    val service = RomOpenService(eventBus, recents, updates::add, worker, ui)
    return Fixture(eventBus, worker, ui, recents, updates, loads, service)
  }

  private fun romFile(name: String, title: String): Path =
      temporaryFolder.newFile(name).toPath().also {
        Files.write(it, syntheticRom(title, title.hashCode()))
      }

  private data class Fixture(
      val eventBus: EventBusImpl,
      val worker: QueuedExecutorService,
      val ui: QueuedExecutor,
      val recents: FakeRecentStore,
      val updates: MutableList<RomOpenUpdate>,
      val loads: MutableList<Controller.LoadRomEvent>,
      val service: RomOpenService,
  ) {
    fun close() {
      service.close()
      eventBus.close()
    }
  }

  private class FakeRecentStore : RomRecentStore {
    val recorded = mutableListOf<Path>()

    override fun getPaths(): List<Path> = recorded.toList()

    override fun recordSuccessfulOpen(path: Path) {
      recorded.remove(path)
      recorded.add(0, path)
    }

    override fun remove(path: Path) {
      recorded.remove(path)
    }
  }

  private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      tasks.addLast(command)
    }

    fun runAll() {
      while (tasks.isNotEmpty()) {
        tasks.removeFirst().run()
      }
    }
  }

  private class QueuedExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false

    override fun execute(command: Runnable) {
      check(!shutdown) { "executor is shut down" }
      tasks.addLast(command)
    }

    override fun shutdown() {
      shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown = true
      return tasks.toMutableList().also { tasks.clear() }
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

    fun runAll() {
      while (tasks.isNotEmpty()) {
        tasks.removeFirst().run()
      }
    }
  }

  private companion object {
    fun syntheticRom(title: String, marker: Int): ByteArray {
      val rom = ByteArray(0x8000)
      title.toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0x134)
      rom[0x147] = 0
      rom[0x148] = 0
      rom[0x149] = 0
      rom[0x200] = marker.toByte()
      var checksum = 0
      for (address in 0x134..0x14c) {
        checksum = (checksum - (rom[address].toInt() and 0xff) - 1) and 0xff
      }
      rom[0x14d] = checksum.toByte()
      return rom
    }

    fun writeZip(target: File, vararg entries: Pair<String, ByteArray>) {
      ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target.toPath()))).use { output ->
        entries.forEach { (name, bytes) ->
          output.putNextEntry(ZipEntry(name))
          output.write(bytes)
          output.closeEntry()
        }
      }
    }
  }
}
