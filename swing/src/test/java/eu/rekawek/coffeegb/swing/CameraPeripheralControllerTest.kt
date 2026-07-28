package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource
import java.awt.image.BufferedImage
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class CameraPeripheralControllerTest {

  @Test
  fun `open and close stay on worker while publication and UI stay on EDT`() {
    val source = TestSource()
    val openOffEdt = AtomicBoolean()
    val closeOffEdt = AtomicBoolean()
    val opened = CountDownLatch(1)
    val sourceClosed = CountDownLatch(1)
    val states = Collections.synchronizedList(mutableListOf<CameraPeripheralUiState>())
    val publications = Collections.synchronizedList(mutableListOf<CameraSource?>())
    val callbacksOnEdt = AtomicBoolean(true)
    val controller =
        CameraPeripheralController(
            opener = {
              openOffEdt.set(!SwingUtilities.isEventDispatchThread())
              source
            },
            sourceCloser = {
              closeOffEdt.set(!SwingUtilities.isEventDispatchThread())
              sourceClosed.countDown()
            },
            publisher = {
              callbacksOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
              publications += it
            },
            stateConsumer = {
              callbacksOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
              states += it
              if (it == CameraPeripheralUiState.Enabled) opened.countDown()
            },
        )

    assertFailsWith<IllegalStateException> { controller.requestEnabled(true) }
    onEdt { controller.requestEnabled(true) }
    assertTrue(opened.await(3, TimeUnit.SECONDS))
    assertTrue(openOffEdt.get())
    assertSame(source, publications.last())

    onEdt { controller.requestEnabled(false) }
    assertTrue(sourceClosed.await(3, TimeUnit.SECONDS))
    assertTrue(closeOffEdt.get())
    assertTrue(callbacksOnEdt.get())
    assertEquals(
        listOf(
            CameraPeripheralUiState.Opening,
            CameraPeripheralUiState.Enabled,
            CameraPeripheralUiState.Disabled,
        ),
        states,
    )
    assertEquals(null, publications.last())

    onEdt { controller.close() }
    assertTrue(controller.awaitTermination(3, TimeUnit.SECONDS))
  }

  @Test
  fun `cancelled stale open is closed and only the newer result reaches the EDT`() {
    val first = TestSource()
    val second = TestSource()
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstClosed = CountDownLatch(1)
    val secondEnabled = CountDownLatch(1)
    val secondClosed = CountDownLatch(1)
    val opens = AtomicInteger()
    val publications = Collections.synchronizedList(mutableListOf<CameraSource?>())
    val states = Collections.synchronizedList(mutableListOf<CameraPeripheralUiState>())
    val controller =
        CameraPeripheralController(
            opener = {
              if (opens.getAndIncrement() == 0) {
                firstEntered.countDown()
                awaitIgnoringInterrupt(releaseFirst)
                first
              } else {
                second
              }
            },
            sourceCloser = {
              assertFalse(SwingUtilities.isEventDispatchThread())
              when (it) {
                first -> firstClosed.countDown()
                second -> secondClosed.countDown()
                else -> error("unexpected camera source")
              }
            },
            publisher = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              publications += it
            },
            stateConsumer = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              states += it
              if (it == CameraPeripheralUiState.Enabled) secondEnabled.countDown()
            },
        )

    onEdt { controller.requestEnabled(true) }
    assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
    onEdt {
      controller.requestEnabled(false)
      controller.requestEnabled(true)
    }
    releaseFirst.countDown()

    assertTrue(firstClosed.await(3, TimeUnit.SECONDS))
    assertTrue(secondEnabled.await(3, TimeUnit.SECONDS))
    assertFalse(publications.contains(first))
    assertSame(second, publications.last())
    assertEquals(
        listOf(
            CameraPeripheralUiState.Opening,
            CameraPeripheralUiState.Disabled,
            CameraPeripheralUiState.Opening,
            CameraPeripheralUiState.Enabled,
        ),
        states,
    )

    onEdt { controller.requestEnabled(false) }
    assertTrue(secondClosed.await(3, TimeUnit.SECONDS))
    onEdt { controller.close() }
    assertTrue(controller.awaitTermination(3, TimeUnit.SECONDS))
  }

  @Test
  fun `current open failure is reported on EDT without publishing a source`() {
    val failed = CountDownLatch(1)
    val states = Collections.synchronizedList(mutableListOf<CameraPeripheralUiState>())
    val publications = Collections.synchronizedList(mutableListOf<CameraSource?>())
    val controller =
        CameraPeripheralController<TestSource>(
            opener = {
              assertFalse(SwingUtilities.isEventDispatchThread())
              null
            },
            sourceCloser = { error("no source should be closed") },
            publisher = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              publications += it
            },
            stateConsumer = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              states += it
              if (it == CameraPeripheralUiState.OpenFailed) failed.countDown()
            },
        )

    onEdt { controller.requestEnabled(true) }
    assertTrue(failed.await(3, TimeUnit.SECONDS))
    assertEquals(
        listOf(CameraPeripheralUiState.Opening, CameraPeripheralUiState.OpenFailed),
        states,
    )
    assertEquals(listOf(null), publications)

    onEdt { controller.close() }
    assertTrue(controller.awaitTermination(3, TimeUnit.SECONDS))
  }

  @Test
  fun `dispose suppresses a queued completion and closes its claimed source off EDT`() {
    val source = TestSource()
    val queued = ConcurrentLinkedQueue<() -> Unit>()
    val completionQueued = CountDownLatch(1)
    val sourceClosed = CountDownLatch(1)
    val states = Collections.synchronizedList(mutableListOf<CameraPeripheralUiState>())
    val publications = Collections.synchronizedList(mutableListOf<CameraSource?>())
    val executor = Executors.newSingleThreadExecutor()
    val controller =
        CameraPeripheralController(
            opener = { source },
            sourceCloser = {
              assertFalse(SwingUtilities.isEventDispatchThread())
              sourceClosed.countDown()
            },
            publisher = { publications += it },
            stateConsumer = { states += it },
            executor = executor,
            uiDispatcher = {
              queued += it
              completionQueued.countDown()
            },
        )

    onEdt { controller.requestEnabled(true) }
    assertTrue(completionQueued.await(3, TimeUnit.SECONDS))
    onEdt { controller.close() }
    assertTrue(sourceClosed.await(3, TimeUnit.SECONDS))
    assertTrue(controller.awaitTermination(3, TimeUnit.SECONDS))
    onEdt {
      while (true) {
        val completion = queued.poll() ?: break
        completion()
      }
    }

    assertEquals(listOf(CameraPeripheralUiState.Opening), states)
    assertFalse(publications.contains(source))
    assertEquals(null, publications.last())
  }

  @Test
  fun `lifecycle close is safe off EDT and remains idempotent`() {
    val source = TestSource()
    val enabled = CountDownLatch(1)
    val detached = CountDownLatch(1)
    val closes = AtomicInteger()
    val controller =
        CameraPeripheralController(
            opener = { source },
            sourceCloser = { closes.incrementAndGet() },
            publisher = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              if (it == null) detached.countDown()
            },
            stateConsumer = {
              assertTrue(SwingUtilities.isEventDispatchThread())
              if (it == CameraPeripheralUiState.Enabled) enabled.countDown()
            },
        )

    onEdt { controller.requestEnabled(true) }
    assertTrue(enabled.await(3, TimeUnit.SECONDS))
    controller.close()
    controller.close()

    assertTrue(controller.awaitTermination(3, TimeUnit.SECONDS))
    assertTrue(detached.await(3, TimeUnit.SECONDS))
    assertEquals(1, closes.get())
  }

  @Test
  fun `bounded lifecycle timeout retries the same close and double success stays idempotent`() {
    val closes = AtomicInteger()
    val awaits = AtomicInteger()
    val shutdown =
        BoundedCameraShutdown(
            close = { closes.incrementAndGet() },
            awaitTermination = { timeout, unit ->
              assertTrue(timeout > 0)
              assertEquals(TimeUnit.NANOSECONDS, unit)
              awaits.incrementAndGet() > 1
            },
            edtOwnership = { false },
        )

    val timeout =
        assertFailsWith<IOException> { shutdown.closeAndAwait(25) }
    assertTrue(timeout.message!!.contains("exceeded"))
    shutdown.closeAndAwait(25)
    shutdown.closeAndAwait(25)

    assertEquals(1, closes.get())
    assertEquals(3, awaits.get())
  }

  @Test
  fun `bounded lifecycle resets its claim when starting close fails`() {
    val closes = AtomicInteger()
    val shutdown =
        BoundedCameraShutdown(
            close = {
              if (closes.incrementAndGet() == 1) {
                throw IOException("injected close failure")
              }
            },
            awaitTermination = { _, _ -> true },
            edtOwnership = { false },
        )

    assertFailsWith<IOException> { shutdown.closeAndAwait(25) }
    shutdown.closeAndAwait(25)

    assertEquals(2, closes.get())
  }

  private class TestSource : CameraSource {
    override fun getFrame(): BufferedImage? = null
  }

  private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
    while (true) {
      try {
        latch.await()
        return
      } catch (_: InterruptedException) {
        // Native camera open is not guaranteed to honor interruption; emulate that behavior.
      }
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
