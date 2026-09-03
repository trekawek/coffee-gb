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

  @Test
  fun `system reload loading event keeps its following stop from exiting fullscreen`() {
    val replacementLoading = AtomicBoolean(false)
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
            // BasicController publishes this exact order for an in-place hardware-profile reload:
            // loading the replacement, stopping the old session, then starting the new one.
            dispatchSwingMutation { replacementLoading.set(true) }
            dispatchAcceptedRomLifecycle(openRequestId = null, accept = { true }) {
              if (shouldPresentStoppedSession(
                  managedOpenActive = false,
                  replacementLoading = replacementLoading.get(),
              )) {
                fullscreen.set(false)
                homeOpened.set(true)
              }
            }
            dispatchSwingMutation { replacementLoading.set(false) }
          }
      controllerThread.start()
      controllerThread.join(1_000)
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {}

    assertTrue(fullscreen.get())
    assertFalse(homeOpened.get())
  }

  @Test
  fun `terminal stop still exits fullscreen and reveals library`() {
    assertTrue(
        shouldPresentStoppedSession(
            managedOpenActive = false,
            replacementLoading = false,
        ))
    assertFalse(
        shouldPresentStoppedSession(
            managedOpenActive = true,
            replacementLoading = false,
        ))
    assertFalse(
        shouldPresentStoppedSession(
            managedOpenActive = false,
            replacementLoading = true,
        ))
  }

  @Test
  fun `cancelled replacement clears loading before its following terminal stop`() {
    val replacementLoading = AtomicBoolean(false)
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
            dispatchSwingMutation { replacementLoading.set(true) }
            // requestStop cancels every pending load/replacement before its terminal Stop.
            dispatchSwingMutation { replacementLoading.set(false) }
            dispatchAcceptedRomLifecycle(openRequestId = null, accept = { true }) {
              if (shouldPresentStoppedSession(
                  managedOpenActive = false,
                  replacementLoading = replacementLoading.get(),
              )) {
                fullscreen.set(false)
                homeOpened.set(true)
              }
            }
          }
      controllerThread.start()
      controllerThread.join(1_000)
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {}

    assertFalse(fullscreen.get())
    assertTrue(homeOpened.get())
  }
}
