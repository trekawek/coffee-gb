package eu.rekawek.coffeegb.swing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingGuiShutdownTest {
  @Test
  fun `desktop shutdown leaves the EDT and keeps the process alive until teardown completes`() {
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
    assertFalse(worker.isDaemon)
    assertFalse(worker.isAlive)
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
}
