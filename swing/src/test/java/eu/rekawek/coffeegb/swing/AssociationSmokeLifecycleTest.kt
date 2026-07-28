package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AssociationSmokeLifecycleTest {

  @Test
  fun `configuration maps installed launch sources and requires an absolute marker`() {
    val directory = Files.createTempDirectory("association-config")
    val marker = directory.resolve("opened.marker")
    val rom = directory.resolve("expected.gb")
    val environment =
        mapOf(
            ASSOCIATION_SMOKE_MARKER_ENV to marker.toString(),
            ASSOCIATION_SMOKE_ROM_ENV to rom.toString(),
        )

    assertEquals(
        RomOpenSource.INITIAL_ARGUMENT,
        associationSmokeConfiguration(environment, "Linux")?.expectedSource,
    )
    assertEquals(
        RomOpenSource.INITIAL_ARGUMENT,
        associationSmokeConfiguration(environment, "Windows 11")?.expectedSource,
    )
    assertEquals(
        RomOpenSource.DESKTOP_OPEN_FILE,
        associationSmokeConfiguration(environment, "Mac OS X")?.expectedSource,
    )
    assertEquals(rom, associationSmokeConfiguration(environment, "Linux")?.expectedRom)
    assertNull(associationSmokeConfiguration(emptyMap(), "Linux"))
    assertFailsWith<IllegalArgumentException> {
      associationSmokeConfiguration(
          mapOf(
              ASSOCIATION_SMOKE_MARKER_ENV to "relative.marker",
              ASSOCIATION_SMOKE_ROM_ENV to rom.toString(),
          ),
          "Linux",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      associationSmokeConfiguration(
          mapOf(ASSOCIATION_SMOKE_MARKER_ENV to marker.toString()),
          "Linux",
      )
    }
    Files.delete(directory)
  }

  @Test
  fun `all installed ROM extensions record exact correlated evidence off the EDT`() {
    val directory = Files.createTempDirectory("association-extensions")

    for (extension in listOf("gb", "gbc", "rom")) {
      val rom = directory.resolve("Coffee GB association smoke.$extension")
      Files.write(rom, byteArrayOf(0))
      val marker = directory.resolve("opened-$extension.marker")
      val completed = CountDownLatch(1)
      val callbackWasEdt = AtomicBoolean(true)
      val probeWasEdt = AtomicBoolean(true)
      val failure = AtomicReference<Exception?>()
      val lifecycle =
          AssociationSmokeLifecycle(
              AssociationSmokeConfiguration(marker, rom, RomOpenSource.INITIAL_ARGUMENT),
              completed = {
                callbackWasEdt.set(SwingUtilities.isEventDispatchThread())
                completed.countDown()
              },
              failed = {
                failure.set(it)
                completed.countDown()
              },
              isRegularFile = {
                probeWasEdt.set(SwingUtilities.isEventDispatchThread())
                Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              },
          )

      lifecycle.observe(opened(rom, RomOpenSource.INITIAL_ARGUMENT))

      assertTrue(completed.await(2, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertFalse(callbackWasEdt.get())
      assertFalse(probeWasEdt.get())
      assertEquals(
          "Coffee GB association open OK\n" +
              "source=INITIAL_ARGUMENT\n" +
              "rom=$rom\n" +
              "origin=$rom\n" +
              "title=COFFEE-CI-SMOKE\n" +
              "pid=${ProcessHandle.current().pid()}\n",
          Files.readString(marker),
      )
      Files.delete(marker)
      Files.delete(rom)
    }
    Files.delete(directory)
  }

  @Test
  fun `non-regular expected ROM is ignored after an off-EDT probe`() {
    val directory = Files.createTempDirectory("association-non-regular")
    val rom = Files.createDirectory(directory.resolve("not-a-file.gb"))
    val marker = directory.resolve("opened.marker")
    val probeCompleted = CountDownLatch(1)
    val probeWasEdt = AtomicBoolean(true)
    val callbacks = AtomicInteger()
    val lifecycle =
        AssociationSmokeLifecycle(
            AssociationSmokeConfiguration(marker, rom, RomOpenSource.INITIAL_ARGUMENT),
            completed = { callbacks.incrementAndGet() },
            failed = { throw AssertionError("non-regular ROM unexpectedly failed", it) },
            isRegularFile = {
              probeWasEdt.set(SwingUtilities.isEventDispatchThread())
              val regular = Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              probeCompleted.countDown()
              regular
            },
        )

    lifecycle.observe(opened(rom, RomOpenSource.INITIAL_ARGUMENT))

    assertTrue(probeCompleted.await(2, TimeUnit.SECONDS))
    assertFalse(probeWasEdt.get())
    assertEquals(0, callbacks.get())
    assertFalse(Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    Files.delete(rom)
    Files.delete(directory)
  }

  @Test
  fun `failure cancellation and wrong source never create evidence`() {
    val directory = Files.createTempDirectory("association-filter")
    val marker = directory.resolve("opened.marker")
    val rom = directory.resolve("finder.gbc")
    val otherRom = directory.resolve("other.gbc")
    Files.write(rom, byteArrayOf(0))
    Files.write(otherRom, byteArrayOf(0))
    val callbacks = AtomicInteger()
    val completed = CountDownLatch(1)
    val lifecycle =
        AssociationSmokeLifecycle(
            AssociationSmokeConfiguration(marker, rom, RomOpenSource.DESKTOP_OPEN_FILE),
            completed = {
              callbacks.incrementAndGet()
              completed.countDown()
            },
            failed = { throw AssertionError("unexpected marker failure", it) },
        )
    lifecycle.observe(
        RomOpenUpdate.Failed(
            requestId = 1,
            source = RomOpenSource.DESKTOP_OPEN_FILE,
            path = rom,
            failure =
                RomOpenFailure(
                    RomOpenFailureKind.INVALID_HEADER,
                    "invalid",
                    "invalid",
                ),
        ))
    lifecycle.observe(
        RomOpenUpdate.Cancelled(
            requestId = 2,
            source = RomOpenSource.DESKTOP_OPEN_FILE,
        ))
    lifecycle.observe(opened(rom, RomOpenSource.INITIAL_ARGUMENT, requestId = 3))
    lifecycle.observe(
        opened(
            rom,
            RomOpenSource.DESKTOP_OPEN_FILE,
            requestId = 4,
            title = "NOT-THE-CI-ROM",
        ))
    lifecycle.observe(opened(otherRom, RomOpenSource.DESKTOP_OPEN_FILE, requestId = 5))
    lifecycle.observe(
        opened(
            rom,
            RomOpenSource.DESKTOP_OPEN_FILE,
            requestId = 6,
            origin = RomOrigin.directFile(otherRom),
        ))
    lifecycle.observe(
        opened(
            rom,
            RomOpenSource.DESKTOP_OPEN_FILE,
            requestId = 7,
            origin = RomOrigin.archiveEntry(otherRom, "finder.gbc"),
        ))

    assertFalse(Files.exists(marker))
    assertEquals(0, callbacks.get())

    lifecycle.observe(opened(rom, RomOpenSource.DESKTOP_OPEN_FILE, requestId = 8))
    assertTrue(completed.await(2, TimeUnit.SECONDS))
    assertEquals(1, callbacks.get())
    Files.delete(marker)
    Files.delete(otherRom)
    Files.delete(rom)
    Files.delete(directory)
  }

  @Test
  fun `duplicate terminal updates create one marker and request one close`() {
    val directory = Files.createTempDirectory("association-duplicate-update")
    val marker = directory.resolve("opened.marker")
    val rom = directory.resolve("game.rom")
    Files.write(rom, byteArrayOf(0))
    val completed = CountDownLatch(1)
    val callbacks = AtomicInteger()
    val lifecycle =
        AssociationSmokeLifecycle(
            AssociationSmokeConfiguration(marker, rom, RomOpenSource.INITIAL_ARGUMENT),
            completed = {
              callbacks.incrementAndGet()
              completed.countDown()
            },
            failed = { throw AssertionError("unexpected marker failure", it) },
        )
    val update = opened(rom, RomOpenSource.INITIAL_ARGUMENT)

    lifecycle.observe(update)
    lifecycle.observe(update.copy(requestId = 2))

    assertTrue(completed.await(2, TimeUnit.SECONDS))
    Thread.sleep(25)
    assertEquals(1, callbacks.get())
    assertEquals(6, Files.readAllLines(marker).size)
    Files.delete(marker)
    Files.delete(rom)
    Files.delete(directory)
  }

  @Test
  fun `existing and symbolic-link markers are never overwritten`() {
    val directory = Files.createTempDirectory("association-exclusive")
    val rom = directory.resolve("game.gb")
    Files.write(rom, byteArrayOf(0))
    val existing = directory.resolve("existing.marker")
    Files.writeString(existing, "owner-data\n")
    val existingFailure = CountDownLatch(1)
    val existingProblem = AtomicReference<Exception?>()
    AssociationSmokeLifecycle(
            AssociationSmokeConfiguration(existing, rom, RomOpenSource.INITIAL_ARGUMENT),
            completed = { throw AssertionError("existing marker unexpectedly succeeded") },
            failed = {
              existingProblem.set(it)
              existingFailure.countDown()
            },
        )
        .observe(opened(rom, RomOpenSource.INITIAL_ARGUMENT))

    assertTrue(existingFailure.await(2, TimeUnit.SECONDS))
    assertIs<IllegalStateException>(existingProblem.get())
    assertEquals("owner-data\n", Files.readString(existing))

    val linkTarget = directory.resolve("link-target.marker")
    val link = directory.resolve("linked.marker")
    Files.writeString(linkTarget, "link-owner-data\n")
    runCatching { Files.createSymbolicLink(link, linkTarget.fileName) }
        .onSuccess {
          val linkFailure = CountDownLatch(1)
          val linkProblem = AtomicReference<Exception?>()
          AssociationSmokeLifecycle(
                  AssociationSmokeConfiguration(link, rom, RomOpenSource.INITIAL_ARGUMENT),
                  completed = {
                    throw AssertionError("symbolic-link marker unexpectedly succeeded")
                  },
                  failed = {
                    linkProblem.set(it)
                    linkFailure.countDown()
                  },
              )
              .observe(opened(rom, RomOpenSource.INITIAL_ARGUMENT))

          assertTrue(linkFailure.await(2, TimeUnit.SECONDS))
          assertIs<IllegalStateException>(linkProblem.get())
          assertEquals("link-owner-data\n", Files.readString(linkTarget))
          Files.delete(link)
        }
    Files.delete(linkTarget)
    Files.delete(existing)
    Files.delete(rom)
    Files.delete(directory)
  }

  @Test
  fun `early desktop readiness retains macOS launch until evidence then bounded close`() {
    val directory = Files.createTempDirectory("association-early-ready")
    val marker = directory.resolve("opened.marker")
    val rom = directory.resolve("finder.gb")
    Files.write(rom, byteArrayOf(0))
    val closeRequested = CountDownLatch(1)
    val shutdownCompleted = CountDownLatch(1)
    val closeWasEdt = AtomicBoolean(false)
    val calls = mutableListOf<String>()
    lateinit var lifecycle: AssociationSmokeLifecycle
    val shutdown =
        DesktopShutdownCoordinator(
            shutdown = { synchronized(calls) { calls += "controller-and-state" } },
            commit = { synchronized(calls) { calls += "settings-camera-and-services" } },
            timeoutMillis = 2_000,
            onPersistenceFailure = { _, _, _ ->
              throw AssertionError("unexpected persistence failure")
            },
            onFailure = { throw AssertionError("unexpected close failure", it) },
            onTimeout = { throw AssertionError("unexpected close timeout") },
            onSuccess = {
              synchronized(calls) { calls += "normal-shutdown" }
              lifecycle.recordNormalShutdown {
                synchronized(calls) { calls += "closed" }
                shutdownCompleted.countDown()
              }
            },
        )

    dispatchDesktopStartupSmokeClose(associationSmokeConfigured = true) {
      closeRequested.countDown()
    }
    assertFalse(closeRequested.await(100, TimeUnit.MILLISECONDS))

    lifecycle =
        AssociationSmokeLifecycle(
            AssociationSmokeConfiguration(marker, rom, RomOpenSource.DESKTOP_OPEN_FILE),
            completed = {
              SwingUtilities.invokeLater {
                closeWasEdt.set(SwingUtilities.isEventDispatchThread())
                assertTrue(Files.isRegularFile(marker))
                closeRequested.countDown()
                shutdown.request()
              }
            },
            failed = { throw AssertionError("unexpected marker failure", it) },
        )
    lifecycle.observe(opened(rom, RomOpenSource.DESKTOP_OPEN_FILE))

    assertTrue(closeRequested.await(2, TimeUnit.SECONDS))
    assertTrue(shutdownCompleted.await(2, TimeUnit.SECONDS))
    assertTrue(closeWasEdt.get())
    assertEquals(
        listOf(
            "controller-and-state",
            "settings-camera-and-services",
            "normal-shutdown",
            "closed",
        ),
        synchronized(calls) { calls.toList() },
    )
    assertEquals(
        "Coffee GB association shutdown OK\n" +
            "pid=${ProcessHandle.current().pid()}\n",
        Files.readString(lifecycleConfiguration(marker, rom).shutdownMarker),
    )
    Files.delete(lifecycleConfiguration(marker, rom).shutdownMarker)
    Files.delete(marker)
    Files.delete(rom)
    Files.delete(directory)
  }

  private fun opened(
      path: Path,
      source: RomOpenSource,
      requestId: Long = 1,
      title: String = ASSOCIATION_SMOKE_ROM_TITLE,
      origin: RomOrigin = RomOrigin.directFile(path),
  ) =
      RomOpenUpdate.Opened(
          requestId = requestId,
          source = source,
          recentPath = path.toAbsolutePath().normalize(),
          origin = origin,
          title = title,
      )

  private fun lifecycleConfiguration(marker: Path, rom: Path) =
      AssociationSmokeConfiguration(marker, rom, RomOpenSource.DESKTOP_OPEN_FILE)
}
