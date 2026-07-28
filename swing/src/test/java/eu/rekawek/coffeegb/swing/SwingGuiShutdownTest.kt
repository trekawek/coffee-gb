package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import java.awt.Dimension
import java.awt.Insets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
