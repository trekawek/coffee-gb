package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import java.awt.Dimension
import java.awt.Insets
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SwingGuiShutdownTest {
  @Test
  fun `JVM shutdown attempts every teardown step and preserves the first failure`() {
    val calls = mutableListOf<String>()

    val failure =
        assertFailsWith<IOException> {
          runDesktopJvmShutdownSteps(
              {
                calls += "quiesce"
                throw IOException("quiesce failed")
              },
              { calls += "controller" },
              {
                calls += "ROM service"
                throw IOException("ROM close failed")
              },
              { calls += "settings" },
          )
        }

    assertEquals(listOf("quiesce", "controller", "ROM service", "settings"), calls)
    assertEquals("quiesce failed", failure.message)
    assertEquals(1, failure.suppressed.size)
  }

  @Test
  fun `JVM shutdown participant replaces fallback and executes at most once`() {
    val fallbackCalls = AtomicInteger()
    val participantCalls = AtomicInteger()
    val failures = AtomicInteger()
    val coordinator =
        DesktopJvmShutdownCoordinator(
            fallback = { fallbackCalls.incrementAndGet() },
            timeoutMillis = 2_000,
            onFailure = { failures.incrementAndGet() },
        )
    assertTrue(coordinator.installParticipant { participantCalls.incrementAndGet() })

    coordinator.createHook().run()
    coordinator.createHook().run()

    assertEquals(0, fallbackCalls.get())
    assertEquals(1, participantCalls.get())
    assertEquals(0, failures.get())
  }

  @Test
  fun `completed desktop shutdown prevents a second JVM close`() {
    val calls = AtomicInteger()
    val coordinator =
        DesktopJvmShutdownCoordinator(
            fallback = { calls.incrementAndGet() },
            timeoutMillis = 2_000,
            onFailure = { throw AssertionError("unexpected JVM shutdown failure", it) },
        )
    coordinator.installParticipant { calls.incrementAndGet() }
    coordinator.markCompleted()

    coordinator.createHook().run()

    assertEquals(0, calls.get())
  }

  @Test
  fun `desktop watchdog exceeds every sequential component deadline plus scheduling margin`() {
    val sequentialComponentBudget =
        ROM_OPEN_QUIESCE_SHUTDOWN_BUDGET_MILLIS +
            CONTROLLER_SHUTDOWN_BUDGET_MILLIS +
            GAMEPAD_SHUTDOWN_BUDGET_MILLIS +
            AUDIO_SHUTDOWN_BUDGET_MILLIS +
            CAMERA_SHUTDOWN_BUDGET_MILLIS +
            ROM_OPEN_CLOSE_SHUTDOWN_BUDGET_MILLIS +
            SETTINGS_CLOSE_SHUTDOWN_BUDGET_MILLIS

    assertEquals(DESKTOP_SHUTDOWN_REQUIRED_BUDGET_MILLIS, sequentialComponentBudget)
    assertEquals(
        sequentialComponentBudget + DESKTOP_SHUTDOWN_SCHEDULING_MARGIN_MILLIS,
        DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
    )
    assertTrue(DESKTOP_SHUTDOWN_TIMEOUT_MILLIS > sequentialComponentBudget)
  }

  @Test
  fun `managed ROM lifecycle ignores stale IDs and uncorrelated old-session stops`() {
    val visibleId = 12L

    assertTrue(shouldApplyRomLifecycleEvent(visibleId, true) { it == visibleId })
    assertFalse(shouldApplyRomLifecycleEvent(11L, true) { it == visibleId })
    assertFalse(
        shouldApplyRomLifecycleEvent(null, true) { it == visibleId },
        "the old session's uncorrelated stop must not clear a managed replacement",
    )
    assertTrue(
        shouldApplyRomLifecycleEvent(null, false) { it == visibleId },
        "reset, profile restart, and ordinary stop events remain supported",
    )
  }

  @Test
  fun `minimum frame size includes content chrome and menu without display assumptions`() {
    assertEquals(
        Dimension(172, 181),
        minimumFrameSize(
            content = Dimension(160, 144),
            insets = Insets(20, 5, 7, 7),
            menuHeight = 10,
        ),
    )
    assertFailsWith<IllegalArgumentException> {
      minimumFrameSize(Dimension(160, 144), Insets(0, 0, 0, 0), -1)
    }
  }

  @Test
  fun `windowed explicit mode tracks full rotated or SGB content while fit and fullscreen do not`() {
    assertEquals(
        Dimension(960, 1_024),
        minimumDisplayContentSize(
            DisplayScaleMode.EXPLICIT_4X,
            Dimension(960, 1_024),
            windowed = true,
        ),
    )
    assertEquals(
        Dimension(160, 160),
        minimumDisplayContentSize(
            DisplayScaleMode.EXPLICIT_1X,
            Dimension(144, 160),
            windowed = true,
        ),
    )
    assertEquals(
        Dimension(160, 144),
        minimumDisplayContentSize(
            DisplayScaleMode.ASPECT_FIT,
            Dimension(256, 224),
            windowed = true,
        ),
    )
    assertEquals(
        Dimension(160, 144),
        minimumDisplayContentSize(
            DisplayScaleMode.EXPLICIT_4X,
            Dimension(1_024, 896),
            windowed = false,
        ),
    )
  }

  @Test
  fun `fullscreen SGB growth packs on exit only when restored content is too small`() {
    val sgbFourTimes = Dimension(1_024, 896)
    assertTrue(
        shouldPackExplicitWindow(
            DisplayScaleMode.EXPLICIT_4X,
            sgbFourTimes,
            currentContentSize = Dimension(320, 288),
            windowed = true,
        ))
    assertFalse(
        shouldPackExplicitWindow(
            DisplayScaleMode.EXPLICIT_4X,
            sgbFourTimes,
            currentContentSize = sgbFourTimes,
            windowed = true,
        ))
    assertFalse(
        shouldPackExplicitWindow(
            DisplayScaleMode.EXPLICIT_4X,
            sgbFourTimes,
            currentContentSize = Dimension(1_200, 1_000),
            windowed = true,
        ))
    assertFalse(
        shouldPackExplicitWindow(
            DisplayScaleMode.EXPLICIT_4X,
            sgbFourTimes,
            currentContentSize = Dimension(320, 288),
            windowed = false,
        ))
    assertFalse(
        shouldPackExplicitWindow(
            DisplayScaleMode.ASPECT_FIT,
            sgbFourTimes,
            currentContentSize = Dimension(320, 288),
            windowed = true,
        ))
  }

  @Test
  fun `cancelled quit keeps paused session input blocked with retry wording`() {
    val ui = pausedQuitRetryUi()

    assertTrue(ui.blocksInput)
    assertTrue(ui.title.contains("Paused"))
    assertTrue(ui.title.contains("close again to retry"))
  }

  @Test
  fun `desktop shutdown leaves the EDT on a daemon worker`() {
    val ran = CountDownLatch(1)
    var workerWasEdt = true
    lateinit var worker: Thread

    SwingUtilities.invokeAndWait {
      worker =
          launchDesktopShutdown {
            workerWasEdt = SwingUtilities.isEventDispatchThread()
            ran.countDown()
          }
    }

    assertTrue(ran.await(2, TimeUnit.SECONDS))
    worker.join(2_000)
    assertFalse(workerWasEdt)
    assertTrue(worker.isDaemon)
    assertFalse(worker.isAlive)
  }

  @Test
  fun `persistence failure keeps shutdown open and retry completes exactly once`() {
    val attempts = AtomicInteger()
    val failures = AtomicInteger()
    val commits = AtomicInteger()
    val completions = AtomicInteger()
    val failureObserved = CountDownLatch(1)
    val completionObserved = CountDownLatch(1)
    val retryAction = AtomicReference<() -> Unit>()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {
              if (attempts.incrementAndGet() == 1) {
                throw persistenceFailure()
              }
            },
            commit = { commits.incrementAndGet() },
            timeoutMillis = 2_000,
            onPersistenceFailure = { _, retry, _ ->
              failures.incrementAndGet()
              retryAction.set(retry)
              failureObserved.countDown()
            },
            onFailure = { throw AssertionError("unexpected shutdown failure", it) },
            onTimeout = { throw AssertionError("shutdown timed out") },
            onSuccess = {
              completions.incrementAndGet()
              completionObserved.countDown()
            },
        )

    coordinator.request()
    assertTrue(failureObserved.await(2, TimeUnit.SECONDS))
    assertEquals(0, completions.get(), "the failed attempt must not dispose or exit")
    assertEquals(0, commits.get(), "irreversible teardown must wait for emulator stop")

    retryAction.get().invoke()
    assertTrue(completionObserved.await(2, TimeUnit.SECONDS))
    assertEquals(2, attempts.get())
    assertEquals(1, failures.get())
    assertEquals(1, commits.get())
    assertEquals(1, completions.get())

    coordinator.request()
    Thread.sleep(25)
    assertEquals(2, attempts.get(), "completion must be exactly once")
  }

  @Test
  fun `cancel after persistence failure leaves the coordinator reusable`() {
    val attempts = AtomicInteger()
    val commits = AtomicInteger()
    val failureObserved = CountDownLatch(1)
    val completionObserved = CountDownLatch(1)
    val cancelAction = AtomicReference<() -> Unit>()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {
              if (attempts.incrementAndGet() == 1) {
                throw persistenceFailure()
              }
            },
            commit = { commits.incrementAndGet() },
            timeoutMillis = 2_000,
            onPersistenceFailure = { _, _, cancel ->
              cancelAction.set(cancel)
              failureObserved.countDown()
            },
            onFailure = { throw AssertionError("unexpected shutdown failure", it) },
            onTimeout = { throw AssertionError("shutdown timed out") },
            onSuccess = { completionObserved.countDown() },
        )

    coordinator.request()
    assertTrue(failureObserved.await(2, TimeUnit.SECONDS))
    cancelAction.get().invoke()
    assertFalse(completionObserved.await(50, TimeUnit.MILLISECONDS))
    assertEquals(0, commits.get())

    coordinator.request()
    assertTrue(completionObserved.await(2, TimeUnit.SECONDS))
    assertEquals(2, attempts.get())
    assertEquals(1, commits.get())
  }

  @Test
  fun `watchdog and interrupted persistence publish only one terminal decision`() {
    val entered = CountDownLatch(1)
    val terminalObserved = CountDownLatch(1)
    val persistenceNotifications = AtomicInteger()
    val timeoutNotifications = AtomicInteger()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {
              entered.countDown()
              try {
                Thread.sleep(10_000)
              } catch (_: InterruptedException) {
                throw persistenceFailure()
              }
            },
            timeoutMillis = 25,
            onPersistenceFailure = { _, _, _ ->
              persistenceNotifications.incrementAndGet()
              terminalObserved.countDown()
            },
            onFailure = { throw AssertionError("unexpected shutdown failure", it) },
            onTimeout = {
              timeoutNotifications.incrementAndGet()
              terminalObserved.countDown()
            },
            onSuccess = { throw AssertionError("interrupted shutdown unexpectedly completed") },
        )

    coordinator.request()
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    assertTrue(terminalObserved.await(2, TimeUnit.SECONDS))
    Thread.sleep(50)

    assertEquals(
        1,
        persistenceNotifications.get() + timeoutNotifications.get(),
        "watchdog and worker must not open two dialogs",
    )
  }

  @Test
  fun `late success after timeout cannot complete desktop exit`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerReturned = CountDownLatch(1)
    val timeoutObserved = CountDownLatch(1)
    val commits = AtomicInteger()
    val completions = AtomicInteger()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {
              entered.countDown()
              while (release.count != 0L) {
                try {
                  release.await()
                } catch (_: InterruptedException) {
                  // Model shutdown code that finishes after ignoring the watchdog interrupt.
                }
              }
              workerReturned.countDown()
            },
            commit = { commits.incrementAndGet() },
            timeoutMillis = 25,
            onPersistenceFailure = { _, _, _ ->
              throw AssertionError("unexpected persistence failure")
            },
            onFailure = { throw AssertionError("unexpected shutdown failure", it) },
            onTimeout = { timeoutObserved.countDown() },
            onSuccess = { completions.incrementAndGet() },
        )

    coordinator.request()
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    assertTrue(timeoutObserved.await(2, TimeUnit.SECONDS))
    assertFalse(
        coordinator.request(),
        "a repeated close during timeout unwind must not claim it started",
    )
    release.countDown()
    assertTrue(workerReturned.await(2, TimeUnit.SECONDS))
    Thread.sleep(50)

    assertEquals(0, commits.get(), "late success must not make ROM callbacks irreversible")
    assertEquals(0, completions.get(), "late success must not dispose the retained window")
  }

  @Test
  fun `watchdog can time out a blocked commit and reject its late success`() {
    val commitEntered = CountDownLatch(1)
    val releaseCommit = CountDownLatch(1)
    val commitReturned = CountDownLatch(1)
    val timeoutObserved = CountDownLatch(1)
    val timeouts = AtomicInteger()
    val failures = AtomicInteger()
    val completions = AtomicInteger()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {},
            commit = {
              commitEntered.countDown()
              while (releaseCommit.count != 0L) {
                try {
                  releaseCommit.await()
                } catch (_: InterruptedException) {
                  // Model a close implementation whose filesystem operation ignores interrupt.
                }
              }
              commitReturned.countDown()
            },
            timeoutMillis = 25,
            onPersistenceFailure = { _, _, _ ->
              throw AssertionError("unexpected persistence failure")
            },
            onFailure = { failures.incrementAndGet() },
            onTimeout = {
              timeouts.incrementAndGet()
              timeoutObserved.countDown()
            },
            onSuccess = { completions.incrementAndGet() },
        )

    assertTrue(coordinator.request())
    assertTrue(commitEntered.await(2, TimeUnit.SECONDS))
    assertTrue(timeoutObserved.await(2, TimeUnit.SECONDS))
    assertEquals(1, timeouts.get())
    assertEquals(0, failures.get())
    assertEquals(0, completions.get())

    releaseCommit.countDown()
    assertTrue(commitReturned.await(2, TimeUnit.SECONDS))
    Thread.sleep(50)

    assertEquals(1, timeouts.get())
    assertEquals(0, failures.get())
    assertEquals(0, completions.get(), "late commit must not dispose or exit")
  }

  @Test
  fun `generic shutdown failure skips commit and allows retained resources to resume`() {
    val failures = CountDownLatch(1)
    val commits = AtomicInteger()
    val completions = AtomicInteger()
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = { throw IOException("injected peripheral failure") },
            commit = { commits.incrementAndGet() },
            timeoutMillis = 2_000,
            onPersistenceFailure = { _, _, _ ->
              throw AssertionError("generic failure was classified as persistence")
            },
            onFailure = { failures.countDown() },
            onTimeout = { throw AssertionError("shutdown timed out") },
            onSuccess = { completions.incrementAndGet() },
        )

    assertTrue(coordinator.request())
    assertTrue(failures.await(2, TimeUnit.SECONDS))
    assertEquals(0, commits.get())
    assertEquals(0, completions.get())
  }

  @Test
  fun `camera commit waits for successful emulator stop and a failed close can be retried`() {
    val attempts = AtomicInteger()
    val cameraCloses = AtomicInteger()
    val failures = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val coordinator =
        DesktopShutdownCoordinator(
            shutdown = {
              if (attempts.incrementAndGet() == 1) {
                throw IOException("injected emulator stop failure")
              }
            },
            commit = { cameraCloses.incrementAndGet() },
            timeoutMillis = 2_000,
            onPersistenceFailure = { _, _, _ ->
              throw AssertionError("generic failure was classified as persistence")
            },
            onFailure = { failures.countDown() },
            onTimeout = { throw AssertionError("shutdown timed out") },
            onSuccess = { completed.countDown() },
        )

    assertTrue(coordinator.request())
    assertTrue(failures.await(2, TimeUnit.SECONDS))
    assertEquals(0, cameraCloses.get(), "a failed emulator stop must retain camera ownership")

    assertTrue(coordinator.request())
    assertTrue(completed.await(2, TimeUnit.SECONDS))
    assertEquals(1, cameraCloses.get())
    assertFalse(coordinator.request(), "successful close must reject double invocation")
    assertEquals(1, cameraCloses.get())
  }

  @Test
  fun `JVM participant skips camera after stop failure but still attempts independent cleanup`() {
    val cameraCloses = AtomicInteger()
    val laterCleanup = AtomicInteger()

    assertFailsWith<IOException> {
      runDesktopJvmShutdownSteps(
          {
            stopEmulatorBeforeCamera(
                stopEmulator = { throw IOException("injected emulator stop failure") },
                cameraAfterStop = { cameraCloses.incrementAndGet() },
            )
          },
          { laterCleanup.incrementAndGet() },
      )
    }

    assertEquals(0, cameraCloses.get())
    assertEquals(1, laterCleanup.get())
  }

  @Test
  fun `early JVM shutdown safely has no camera participant`() {
    val emulatorStops = AtomicInteger()

    stopEmulatorBeforeCamera(
        stopEmulator = { emulatorStops.incrementAndGet() },
        cameraAfterStop = null,
    )

    assertEquals(1, emulatorStops.get())
  }

  @Test
  fun `JVM camera wait is bounded and hook double invocation is idempotent`() {
    val cameraCloses = AtomicInteger()
    val failures = AtomicInteger()
    val camera =
        BoundedCameraShutdown(
            close = { cameraCloses.incrementAndGet() },
            awaitTermination = { _, _ -> false },
            edtOwnership = { false },
        )
    val coordinator =
        DesktopJvmShutdownCoordinator(
            fallback = { throw AssertionError("participant should replace fallback") },
            timeoutMillis = 2_000,
            onFailure = { failures.incrementAndGet() },
        )
    coordinator.installParticipant {
      runDesktopJvmShutdownSteps(
          {
            // Emulator stop succeeded; only now may camera ownership become irreversible.
            camera.closeAndAwait(25)
          })
    }

    coordinator.createHook().run()
    coordinator.createHook().run()

    assertEquals(1, cameraCloses.get())
    assertEquals(1, failures.get())
  }

  @Test
  fun `watchdog bounds a stalled desktop shutdown`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val timedOut = CountDownLatch(1)
    val worker =
        launchDesktopShutdown {
          entered.countDown()
          try {
            release.await()
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
          }
        }
    assertTrue(entered.await(2, TimeUnit.SECONDS))

    val watchdog = launchDesktopShutdownWatchdog(worker, 25) { timedOut.countDown() }

    assertTrue(timedOut.await(2, TimeUnit.SECONDS))
    release.countDown()
    worker.join(2_000)
    watchdog.join(2_000)
    assertFalse(worker.isAlive)
    assertFalse(watchdog.isAlive)
  }

  private fun persistenceFailure() =
      Controller.PersistenceBarrierException(
          requestId = 7,
          operation = Controller.PersistenceBarrierOperation.CLOSE,
          fileName = "game.sav",
          message = "injected persistence failure",
          cause = IOException("disk full"),
      )
}
