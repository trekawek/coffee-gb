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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
  fun `committed controller start wins cancellation queued before lifecycle claim`() {
    val fixture = fixture()
    val source = romFile("committed-before-cancel.gb", "COMMITTED")
    val controllerCancellations = mutableListOf<Long>()
    fixture.eventBus.register<Controller.CancelRomOpenEvent> {
      controllerCancellations += it.openRequestId
    }

    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    val load = fixture.loads.single()

    // Queue the correlated ownership acknowledgement, then request cancellation before its
    // lifecycle worker runs. The acknowledgement was posted first and therefore owns the result.
    fixture.eventBus.post(
        Controller.EmulationStartedEvent(
            "COMMITTED",
            load.image!!.origin(),
            requestId,
        ))
    fixture.service.cancel(requestId)
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(source.toAbsolutePath().normalize()), fixture.recents.recorded)
    assertEquals(requestId, assertIs<RomOpenUpdate.Opened>(fixture.updates.last()).requestId)
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Cancelled })
    assertEquals(
        listOf(requestId),
        controllerCancellations,
        "the already-queued cancellation is harmless once the controller committed",
    )
    fixture.close()
  }

  @Test
  fun `controller progress cannot overwrite cancellation while terminal acknowledgement still wins`() {
    val fixture = fixture()
    val source = romFile("cancel-progress.gb", "CANCEL")
    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    fixture.ui.runAll()
    val updatesBeforeCancel = fixture.updates.toList()

    // Queue one progress delivery before cancellation and deliver another controller progress
    // event afterwards. Neither may restore progress controls while cancellation awaits its ack.
    fixture.eventBus.post(Controller.RomLoadingEvent(source.toFile(), requestId))
    fixture.service.cancel(requestId)
    fixture.eventBus.post(
        Controller.RomReplacementPersistenceFailedEvent(
            91L,
            "cancel-progress.sav",
            "synthetic persistence barrier",
            openRequestId = requestId,
        ))
    fixture.ui.runAll()

    assertEquals(updatesBeforeCancel, fixture.updates)

    fixture.worker.runAll()
    fixture.eventBus.post(Controller.RomLoadingCancelledEvent(source.toFile(), requestId))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(requestId, assertIs<RomOpenUpdate.Cancelled>(fixture.updates.last()).requestId)
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
            listOf(
                RomOpenInput.RemoteUrl(
                    "https://alice:secret@example.invalid/game.gb?token=private#fragment")),
            RomOpenSource.DROP,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()
    val remoteFailure = assertIs<RomOpenUpdate.Failed>(fixture.updates.last()).failure
    assertEquals(RomOpenFailureKind.REMOTE_URL, remoteFailure.kind)
    assertFalse(remoteFailure.technicalDetails.contains("alice"))
    assertFalse(remoteFailure.technicalDetails.contains("secret"))
    assertFalse(remoteFailure.technicalDetails.contains("token"))
    assertFalse(remoteFailure.technicalDetails.contains("fragment"))

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
  fun `request snapshots and bounds a caller owned input list before worker dispatch`() {
    val fixture = fixture()
    val first = romFile("owned-first.gb", "FIRST")
    val replacement = romFile("owned-replacement.gb", "REPLACEMENT")
    val inputs = mutableListOf<RomOpenInput>(RomOpenInput.LocalPath(first))

    fixture.service.open(RomOpenRequest(inputs, RomOpenSource.DESKTOP_OPEN_FILE))
    inputs.clear()
    repeat(100) { inputs += RomOpenInput.LocalPath(replacement) }
    fixture.worker.runAll()

    assertEquals(first, fixture.loads.single().image!!.origin().containerPath().orElseThrow())
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

    assertTrue(
        fixture.recents.recorded.isEmpty(),
        "a success callback superseded before its lifecycle worker claims ownership is stale",
    )
    assertTrue(fixture.updates.all { it.requestId == secondId })
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Opened })
    fixture.close()
  }

  @Test
  fun `out of order UI execution cannot regress a request to an older progress stage`() {
    val eventBus = EventBusImpl(null, "progress-order", false)
    val worker = QueuedExecutorService()
    val ui = ReverseQueuedExecutor()
    val updates = mutableListOf<RomOpenUpdate>()
    val archive = temporaryFolder.newFile("progress.zip")
    writeZip(
        archive,
        "one.gb" to syntheticRom("ONE", 0x41),
        "two.gb" to syntheticRom("TWO", 0x42),
    )
    val service =
        RomOpenService(eventBus, FakeRecentStore(), updates::add, worker, ui)

    service.open(RomOpenRequest(archive.toPath(), RomOpenSource.DROP))
    worker.runAll()
    ui.runNewestFirst()

    val delivered = updates.filterIsInstance<RomOpenUpdate.Progress>()
    assertEquals(1, delivered.size)
    assertEquals(RomOpenStage.AWAITING_ARCHIVE_SELECTION, delivered.single().stage)
    service.close()
    eventBus.close()
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

  @Test
  fun `close from the EDT posts matching controller cancellation off the EDT`() {
    val eventBus = EventBusImpl(null, "close-thread-test", false)
    val source = romFile("close-thread.gb", "CLOSE")
    val loaded = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val cancelWasEdt = AtomicBoolean(true)
    eventBus.register<Controller.LoadRomEvent> { loaded.countDown() }
    eventBus.register<Controller.CancelRomOpenEvent> {
      cancelWasEdt.set(SwingUtilities.isEventDispatchThread())
      cancelled.countDown()
    }
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            {},
            null,
            Executor { task -> SwingUtilities.invokeLater(task) },
        )

    service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    assertTrue(loaded.await(5, TimeUnit.SECONDS))
    SwingUtilities.invokeAndWait(service::close)

    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
    assertFalse(cancelWasEdt.get())
    eventBus.close()
  }

  @Test
  fun `close cannot overtake an in flight controller load dispatch`() {
    val eventBus = EventBusImpl(null, "close-dispatch-order", false)
    val source = romFile("close-order.gb", "ORDER")
    val loadEntered = CountDownLatch(1)
    val releaseLoad = CountDownLatch(1)
    val cancelObserved = CountDownLatch(1)
    val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    eventBus.register<Controller.LoadRomEvent> {
      loadEntered.countDown()
      while (releaseLoad.count != 0L) {
        try {
          releaseLoad.await()
        } catch (_: InterruptedException) {
          // Future.cancel interrupts the service worker. Keep this synthetic subscriber blocked
          // until the test releases it so cancellation ordering is observable.
        }
      }
      order += "load-returned"
    }
    eventBus.register<Controller.CancelRomOpenEvent> {
      order += "cancel"
      cancelObserved.countDown()
    }
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            {},
            null,
            Executor { task -> task.run() },
        )
    service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    assertTrue(loadEntered.await(5, TimeUnit.SECONDS))
    val closeFailure = AtomicReference<Throwable?>()
    val closer =
        Thread {
          runCatching(service::close).onFailure(closeFailure::set)
        }
    closer.start()

    assertFalse(cancelObserved.await(50, TimeUnit.MILLISECONDS))
    releaseLoad.countDown()
    closer.join(5_000)

    assertFalse(closer.isAlive)
    assertEquals(null, closeFailure.get())
    assertEquals(listOf("load-returned", "cancel"), order)
    eventBus.close()
  }

  @Test
  fun `close removes an archive snapshot waiting for user selection`() {
    val eventBus = EventBusImpl(null, "close-snapshot-test", false)
    val archive = temporaryFolder.newFile("close-snapshot.zip")
    writeZip(
        archive,
        "one.gb" to syntheticRom("ONE", 0x51),
        "two.gb" to syntheticRom("TWO", 0x52),
    )
    val awaitingSelection = CountDownLatch(1)
    val before = temporarySnapshotCount()
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            {
              if (it is RomOpenUpdate.Progress &&
                  it.stage == RomOpenStage.AWAITING_ARCHIVE_SELECTION) {
                awaitingSelection.countDown()
              }
            },
            null,
            Executor { task -> task.run() },
        )

    service.open(RomOpenRequest(archive.toPath(), RomOpenSource.CHOOSER))
    assertTrue(awaitingSelection.await(5, TimeUnit.SECONDS))
    assertEquals(before + 1, temporarySnapshotCount())
    service.close()

    assertEquals(before, temporarySnapshotCount())
    eventBus.close()
  }

  @Test
  fun `quiesce drains exact controller cancellation and can be resumed without closing service`() {
    val eventBus = EventBusImpl(null, "quiesce-test", false)
    val updates = CopyOnWriteArrayList<RomOpenUpdate>()
    val firstLoaded = CountDownLatch(1)
    val resumedLoaded = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val loadCount = java.util.concurrent.atomic.AtomicInteger()
    eventBus.register<Controller.LoadRomEvent> {
      if (loadCount.incrementAndGet() == 1) firstLoaded.countDown()
      else resumedLoaded.countDown()
    }
    eventBus.register<Controller.CancelRomOpenEvent> { cancelled.countDown() }
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            updates::add,
            null,
            Executor { task -> task.run() },
        )
    val first = romFile("quiesce-first.gb", "FIRST")
    val second = romFile("quiesce-second.gb", "SECOND")

    val firstId = service.open(RomOpenRequest(first, RomOpenSource.CHOOSER))
    assertTrue(firstLoaded.await(5, TimeUnit.SECONDS))
    service.quiesce()

    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
    assertTrue(service.isQuiesced())
    val blockedId = service.open(RomOpenRequest(second, RomOpenSource.DESKTOP_OPEN_FILE))
    val blocked =
        assertIs<RomOpenUpdate.Failed>(
            updates.single { it.requestId == blockedId })
    assertEquals(RomOpenFailureKind.SHUTTING_DOWN, blocked.failure.kind)
    assertEquals(1, loadCount.get())
    assertTrue(updates.none { it.requestId == firstId && it is RomOpenUpdate.Opened })

    service.resume()
    assertFalse(service.isQuiesced())
    service.open(RomOpenRequest(second, RomOpenSource.RECENT))
    assertTrue(resumedLoaded.await(5, TimeUnit.SECONDS))

    service.close()
    eventBus.close()
  }

  @Test
  fun `quiesce drains an archive snapshot waiting for selection without closing worker`() {
    val eventBus = EventBusImpl(null, "quiesce-snapshot-test", false)
    val archive = temporaryFolder.newFile("quiesce-snapshot.zip")
    writeZip(
        archive,
        "one.gb" to syntheticRom("ONE", 0x61),
        "two.gb" to syntheticRom("TWO", 0x62),
    )
    val awaitingSelection = CountDownLatch(1)
    val before = temporarySnapshotCount()
    val service =
        RomOpenService(
            eventBus,
            FakeRecentStore(),
            {
              if (it is RomOpenUpdate.Progress &&
                  it.stage == RomOpenStage.AWAITING_ARCHIVE_SELECTION) {
                awaitingSelection.countDown()
              }
            },
            null,
            Executor { task -> task.run() },
        )

    service.open(RomOpenRequest(archive.toPath(), RomOpenSource.DROP))
    assertTrue(awaitingSelection.await(5, TimeUnit.SECONDS))
    assertEquals(before + 1, temporarySnapshotCount())

    service.quiesce()

    assertEquals(before, temporarySnapshotCount())
    assertTrue(service.isQuiesced())
    service.resume()
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

  private fun temporarySnapshotCount(): Int =
      File(System.getProperty("java.io.tmpdir"))
          .list { _, name ->
            name.startsWith("coffee-gb-rom-snapshot-") && name.endsWith(".zip")
          }
          ?.size
          ?: 0

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

  private class ReverseQueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      tasks.addLast(command)
    }

    fun runNewestFirst() {
      while (tasks.isNotEmpty()) {
        tasks.removeLast().run()
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
