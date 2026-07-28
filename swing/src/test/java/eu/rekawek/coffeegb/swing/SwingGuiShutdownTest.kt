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

    retryAction.get().invoke()
    assertTrue(completionObserved.await(2, TimeUnit.SECONDS))
    assertEquals(2, attempts.get())
    assertEquals(1, failures.get())
    assertEquals(1, completions.get())

    coordinator.request()
    Thread.sleep(25)
    assertEquals(2, attempts.get(), "completion must be exactly once")
  }

  @Test
  fun `cancel after persistence failure leaves the coordinator reusable`() {
    val attempts = AtomicInteger()
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

    coordinator.request()
    assertTrue(completionObserved.await(2, TimeUnit.SECONDS))
    assertEquals(2, attempts.get())
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

    assertEquals(0, completions.get(), "late success must not dispose the retained window")
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
