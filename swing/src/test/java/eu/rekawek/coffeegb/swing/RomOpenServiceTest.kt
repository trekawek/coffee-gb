package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomOpenServiceTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `a desktop open reaches a live controller after inspecting the ROM`() {
    val eventBus = EventBusImpl()
    val properties = EmulatorProperties()
    val controller = BasicController(eventBus, properties, null)
    val started = CountDownLatch(1)
    val updates = CopyOnWriteArrayList<RomOpenUpdate>()
    val worker = Executors.newSingleThreadExecutor()
    val service =
        RomOpenService(
            eventBus,
            object : RomRecentStore {
              override fun getPaths(): List<Path> = emptyList()

              override fun recordSuccessfulOpen(path: Path, recentPathToReplace: Path?) = Unit

              override fun remove(path: Path) = Unit
            },
            updates::add,
            worker,
            Executor { task -> task.run() },
        )
    eventBus.register<Controller.EmulationStartedEvent> { started.countDown() }
    controller.startController()

    try {
      service.open(RomOpenRequest(romFile("controller-handoff.gb", "HANDOFF"), RomOpenSource.RECENT))

      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertTrue(updates.any { it is RomOpenUpdate.Progress && it.stage == RomOpenStage.PREPARING_CORE })
    } finally {
      service.close()
      controller.close()
      properties.close()
      eventBus.close()
    }
  }

  @Test
  fun `a desktop open reaches the Swing emulator controller after inspecting the ROM`() {
    val eventBus = EventBusImpl()
    val properties = EmulatorProperties()
    val emulatorRef = AtomicReference<SwingEmulator>()
    SwingUtilities.invokeAndWait { emulatorRef.set(SwingEmulator(eventBus, null, properties)) }
    val emulator = emulatorRef.get()
    val started = LinkedBlockingQueue<String>()
    val opened = LinkedBlockingQueue<RomOpenUpdate.Opened>()
    val updates = CopyOnWriteArrayList<RomOpenUpdate>()
    val worker = Executors.newSingleThreadExecutor()
    val service =
        RomOpenService(
            eventBus,
            object : RomRecentStore {
              override fun getPaths(): List<Path> = emptyList()

              override fun recordSuccessfulOpen(path: Path, recentPathToReplace: Path?) = Unit

              override fun remove(path: Path) = Unit
            },
            { update ->
              updates += update
              (update as? RomOpenUpdate.Opened)?.let(opened::add)
            },
            worker,
            Executor { task -> task.run() },
        )
    eventBus.register<Controller.EmulationStartedEvent> { started.add(it.romName) }

    try {
      service.open(RomOpenRequest(romFile("swing-first.gb", "SWING_FIRST"), RomOpenSource.RECENT))
      assertEquals("SWING_FIRST", started.poll(5, TimeUnit.SECONDS))
      assertEquals("SWING_FIRST", opened.poll(5, TimeUnit.SECONDS)?.title)

      service.open(RomOpenRequest(romFile("swing-next.gb", "SWING_NEXT"), RomOpenSource.RECENT))

      assertEquals("SWING_NEXT", started.poll(5, TimeUnit.SECONDS))
      assertEquals("SWING_NEXT", opened.poll(5, TimeUnit.SECONDS)?.title)
      assertTrue(updates.any { it is RomOpenUpdate.Progress && it.stage == RomOpenStage.PREPARING_CORE })
    } finally {
      service.close()
      emulator.stop()
      properties.close()
      eventBus.close()
    }
  }

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
  fun `successful located replacement atomically replaces the missing recent`() {
    val fixture = fixture()
    val missing = Path.of("missing", "old-game.gb").toAbsolutePath().normalize()
    val replacement = romFile("located-game.gb", "LOCATED").toAbsolutePath().normalize()
    fixture.recents.recorded.add(missing)

    val requestId =
        fixture.service.open(
            RomOpenRequest(
                replacement,
                RomOpenSource.CHOOSER,
                recentPathToReplace = missing,
            ))
    fixture.worker.runAll()

    assertEquals(listOf(missing), fixture.recents.recorded)
    val load = fixture.loads.single()
    fixture.eventBus.post(
        Controller.EmulationStartedEvent("LOCATED", load.image!!.origin(), requestId))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(replacement), fixture.recents.recorded)
    fixture.close()
  }

  @Test
  fun `invalid located replacement leaves the missing recent unchanged`() {
    val fixture = fixture()
    val missing = Path.of("missing", "old-game.gb").toAbsolutePath().normalize()
    fixture.recents.recorded.add(missing)

    fixture.service.open(
        RomOpenRequest(
            temporaryFolder.root.toPath().resolve("not-there.gb"),
            RomOpenSource.CHOOSER,
            recentPathToReplace = missing,
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(missing), fixture.recents.recorded)
    assertIs<RomOpenUpdate.Failed>(fixture.updates.last())
    fixture.close()
  }

  @Test
  fun `cancelled located replacement leaves the missing recent unchanged`() {
    val fixture = fixture()
    val missing = Path.of("missing", "old-game.gb").toAbsolutePath().normalize()
    val replacement = romFile("cancelled-location.gb", "CANCELLED")
    fixture.recents.recorded.add(missing)

    val requestId =
        fixture.service.open(
            RomOpenRequest(
                replacement,
                RomOpenSource.CHOOSER,
                recentPathToReplace = missing,
            ))
    fixture.service.cancel(requestId)
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(missing), fixture.recents.recorded)
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Opened })
    fixture.close()
  }

  @Test
  fun `superseded located replacement leaves the missing recent unchanged`() {
    val fixture = fixture()
    val missing = Path.of("missing", "old-game.gb").toAbsolutePath().normalize()
    val replacement = romFile("superseded-location.gb", "SUPERSEDED")
    val newer = romFile("newer-request.gb", "NEWER")
    fixture.recents.recorded.add(missing)

    fixture.service.open(
        RomOpenRequest(
            replacement,
            RomOpenSource.CHOOSER,
            recentPathToReplace = missing,
        ))
    fixture.service.open(RomOpenRequest(newer, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(missing), fixture.recents.recorded)
    assertTrue(fixture.updates.none { it is RomOpenUpdate.Opened })
    fixture.close()
  }

  @Test
  fun `committed controller start synchronously wins a later cancellation`() {
    val fixture = fixture()
    val source = romFile("committed-before-cancel.gb", "COMMITTED")
    val controllerCancellations = mutableListOf<Long>()
    fixture.eventBus.register<Controller.CancelRomOpenEvent> {
      controllerCancellations += it.openRequestId
    }

    val requestId = fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    val load = fixture.loads.single()

    // The correlated ownership acknowledgement claims the operation synchronously. Preference
    // work may still be queued, but a later cancellation can no longer target committed state.
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
    assertTrue(controllerCancellations.isEmpty())
    fixture.close()
  }

  @Test
  fun `basic and linked controller transitions abandon each uncommitted open generation`() {
    val fixture = fixture()
    val cancellations = mutableListOf<Long>()
    fixture.eventBus.register<Controller.CancelRomOpenEvent> {
      cancellations += it.openRequestId
    }

    listOf("basic-to-linked", "linked-to-basic").forEach { direction ->
      val source = romFile("$direction.gb", direction.uppercase())
      val requestId =
          fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
      fixture.worker.runAll()
      assertTrue(fixture.service.hasActiveRequest())

      fixture.eventBus.post(ControllerOwnershipChangingEvent())

      assertFalse(fixture.service.hasActiveRequest())
      assertEquals(requestId, cancellations.last())
      fixture.eventBus.post(
          Controller.EmulationStartedEvent(
              direction,
              fixture.loads.last().image!!.origin(),
              requestId,
          ))
      fixture.worker.runAll()
      fixture.ui.runAll()
      assertTrue(fixture.recents.recorded.none { it == source.toAbsolutePath().normalize() })
      assertTrue(fixture.updates.none { it.requestId == requestId && it is RomOpenUpdate.Opened })
    }
    fixture.close()
  }

  @Test
  fun `controller transition cannot abandon a success already committed by controller`() {
    val fixture = fixture()
    val source = romFile("committed-before-transition.gb", "COMMITTED")
    val cancellations = mutableListOf<Long>()
    fixture.eventBus.register<Controller.CancelRomOpenEvent> {
      cancellations += it.openRequestId
    }
    val requestId =
        fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()

    fixture.eventBus.post(
        Controller.EmulationStartedEvent(
            "COMMITTED",
            fixture.loads.single().image!!.origin(),
            requestId,
        ))
    assertFalse(fixture.service.hasActiveRequest())
    fixture.eventBus.post(ControllerOwnershipChangingEvent())
    fixture.worker.runAll()
    fixture.ui.runAll()

    assertEquals(listOf(source.toAbsolutePath().normalize()), fixture.recents.recorded)
    assertTrue(cancellations.isEmpty())
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
  fun `invalid-header homebrew reaches the controller from raw zip and seven-z sources`() {
    val bytes = ByteArray(0x8000).also { it[0x200] = 0x42 }
    val raw = temporaryFolder.newFile("headerless-homebrew.gb").toPath()
    Files.write(raw, bytes)
    val zip = temporaryFolder.newFile("headerless-homebrew.zip")
    writeZip(zip, "homebrew/headerless.gb" to bytes)
    val sevenZ = temporaryFolder.newFile("headerless-homebrew.7z")
    writeSevenZ(sevenZ, "homebrew/headerless.gb" to bytes)

    listOf(raw, zip.toPath(), sevenZ.toPath()).forEach { source ->
      val fixture = fixture()
      fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
      fixture.worker.runAll()
      fixture.ui.runAll()

      assertEquals(1, fixture.loads.size, source.toString())
      assertTrue(fixture.loads.single().image!!.bytes().contentEquals(bytes), source.toString())
      assertTrue(fixture.updates.none { it is RomOpenUpdate.Failed }, source.toString())
      fixture.close()
    }
  }

  @Test
  fun `controller startup details redact unrelated absolute paths`() {
    val fixture = fixture()
    val source = romFile("redacted-controller-path.gb", "REDACT")
    val requestId =
        fixture.service.open(RomOpenRequest(source, RomOpenSource.CHOOSER))
    fixture.worker.runAll()
    val unrelated =
        Path.of(System.getProperty("user.home"))
            .resolve("private-slot-roms")
            .resolve("secret-datel.gbc")
            .toAbsolutePath()
            .normalize()

    fixture.eventBus.post(
        Controller.LoadRomFailedEvent(
            source.toFile(),
            "slot ROM failed",
            requestId,
            technicalDetails =
                "Unable to load $unrelated and /opt/Secret Folder/slot.gbc",
        ))
    fixture.worker.runAll()
    fixture.ui.runAll()

    val details =
        assertIs<RomOpenUpdate.Failed>(fixture.updates.last()).failure.technicalDetails
    assertFalse(details.contains(System.getProperty("user.home")))
    assertFalse(details.contains("Secret Folder"))
    assertFalse(details.contains("slot.gbc"))
    assertTrue(details.contains("<redacted-"))
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
  fun `queued success callback cannot overwrite a newer request but still records committed recent`() {
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
            name.startsWith("coffee-gb-rom-snapshot-") &&
                (name.endsWith(".zip") || name.endsWith(".7z"))
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

    override fun recordSuccessfulOpen(path: Path, recentPathToReplace: Path?) {
      recentPathToReplace?.let(recorded::remove)
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

    fun writeSevenZ(target: File, vararg entries: Pair<String, ByteArray>) {
      SevenZOutputFile(target).use { output ->
        output.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2)))
        entries.forEach { (name, bytes) ->
          val entry = SevenZArchiveEntry().apply {
            this.name = name
            size = bytes.size.toLong()
          }
          output.putArchiveEntry(entry)
          output.write(bytes)
          output.closeArchiveEntry()
        }
      }
    }
  }
}
