package eu.rekawek.coffeegb.swing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingEdtTest {

  @Test
  fun `controller callback mutation is transferred to the EDT`() {
    val item = AtomicReference<JMenuItem>()
    SwingUtilities.invokeAndWait {
      item.set(JMenuItem("ROM action").apply { isEnabled = false })
    }
    val changed = CountDownLatch(1)
    val changedOnEdt = AtomicBoolean()
    item.get().addPropertyChangeListener("enabled") {
      changedOnEdt.set(SwingUtilities.isEventDispatchThread())
      changed.countDown()
    }

    val controllerThread =
        Thread {
          dispatchSwingMutation { item.get().isEnabled = true }
        }
    controllerThread.start()
    controllerThread.join(1_000)

    assertTrue(changed.await(2, TimeUnit.SECONDS))
    assertTrue(changedOnEdt.get())
  }

  @Test
  fun `queued lifecycle callback is revalidated after a newer request becomes visible`() {
    val item = AtomicReference<JMenuItem>()
    SwingUtilities.invokeAndWait { item.set(JMenuItem("new request")) }
    val edtEntered = CountDownLatch(1)
    val releaseEdt = CountDownLatch(1)
    SwingUtilities.invokeLater {
      edtEntered.countDown()
      releaseEdt.await()
    }
    try {
      assertTrue(edtEntered.await(2, TimeUnit.SECONDS))

      val visibleRequest = AtomicLong(1)
      val controllerThread =
          Thread {
            dispatchAcceptedRomLifecycle(
                openRequestId = 1,
                accept = { it == visibleRequest.get() },
            ) {
              item.get().text = "stale request"
            }
          }
      controllerThread.start()
      controllerThread.join(1_000)
      visibleRequest.set(2)
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {}

    assertEquals("new request", item.get().text)
  }

  @Test
  fun `managed replacement stop stays rejected after the request commits before EDT delivery`() {
    val managedOpenActive = AtomicBoolean(true)
    val fullscreen = AtomicBoolean(true)
    val homeOpened = AtomicBoolean(false)
    val edtEntered = CountDownLatch(1)
    val releaseEdt = CountDownLatch(1)
    SwingUtilities.invokeLater {
      edtEntered.countDown()
      releaseEdt.await()
    }
    try {
      assertTrue(edtEntered.await(2, TimeUnit.SECONDS))
      val controllerThread =
          Thread {
            dispatchAcceptedRomLifecycle(
                openRequestId = null,
                accept = { !managedOpenActive.get() },
            ) {
              fullscreen.set(false)
              homeOpened.set(true)
            }
          }
      controllerThread.start()
      controllerThread.join(1_000)

      // The successful replacement clears its active request before Swing drains the old Stop.
      managedOpenActive.set(false)
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {}

    assertTrue(fullscreen.get())
    assertFalse(homeOpened.get())
  }

  @Test
  fun `ordinary stop is applied only if no managed open appears before EDT delivery`() {
    val managedOpenActive = AtomicBoolean(false)
    val stopCount = AtomicLong()
    val edtEntered = CountDownLatch(1)
    val releaseEdt = CountDownLatch(1)
    SwingUtilities.invokeLater {
      edtEntered.countDown()
      releaseEdt.await()
    }
    try {
      assertTrue(edtEntered.await(2, TimeUnit.SECONDS))
      val controllerThread =
          Thread {
            dispatchAcceptedRomLifecycle(
                openRequestId = null,
                accept = { !managedOpenActive.get() },
            ) {
              stopCount.incrementAndGet()
            }
          }
      controllerThread.start()
      controllerThread.join(1_000)
      managedOpenActive.set(true)
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {}
    assertEquals(0, stopCount.get())

    managedOpenActive.set(false)
    dispatchAcceptedRomLifecycle(
        openRequestId = null,
        accept = { !managedOpenActive.get() },
    ) {
      stopCount.incrementAndGet()
    }
    SwingUtilities.invokeAndWait {}
    assertEquals(1, stopCount.get())
  }
}
