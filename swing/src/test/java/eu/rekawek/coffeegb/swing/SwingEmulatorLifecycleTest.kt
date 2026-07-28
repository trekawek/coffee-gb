package eu.rekawek.coffeegb.swing

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SwingEmulatorLifecycleTest {

  @Test
  fun `final controller close always follows host output release`() {
    val released = AtomicBoolean()
    val closed = AtomicBoolean()

    closeControllerAfterLifecycleRelease(
        release = { released.set(true) },
        close = {
          assertTrue(released.get())
          closed.set(true)
        },
    )

    assertTrue(closed.get())
  }

  @Test
  fun `failed close keeps outputs released and retry releases idempotently again`() {
    val releases = AtomicInteger()
    val attempts = AtomicInteger()

    assertFailsWith<IOException> {
      closeControllerAfterLifecycleRelease(
          release = { releases.incrementAndGet() },
          close = {
            assertEquals(1, releases.get())
            attempts.incrementAndGet()
            throw IOException("retained close")
          },
      )
    }
    assertEquals(1, releases.get())

    closeControllerAfterLifecycleRelease(
        release = { releases.incrementAndGet() },
        close = {
          assertEquals(2, releases.get())
          attempts.incrementAndGet()
        },
    )
    assertEquals(2, attempts.get())
  }

  @Test
  fun `deferred close cannot block before host output release`() {
    val released = CountDownLatch(1)
    val closeEntered = CountDownLatch(1)
    val allowClose = CountDownLatch(1)
    val worker =
        Thread {
          closeControllerAfterLifecycleRelease(
              release = { released.countDown() },
              close = {
                closeEntered.countDown()
                allowClose.await()
              },
          )
        }
    worker.isDaemon = true
    worker.start()

    assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
    assertEquals(0, released.count)
    allowClose.countDown()
    worker.join(2_000)
  }

  @Test
  fun `controller callback waiting behind shutdown cannot install a replacement`() {
    val gate = ControllerLifecycleGate()
    val stopEntered = CountDownLatch(1)
    val allowStop = CountDownLatch(1)
    val callbackAttempted = CountDownLatch(1)
    val callbackReturned = CountDownLatch(1)
    val replacements = AtomicInteger()
    val transitioned = AtomicBoolean(true)
    val stopper =
        Thread {
          gate.stop(
              releaseControllerOwnership = {
                stopEntered.countDown()
                allowStop.await()
              },
              finishTeardown = {},
          )
        }
    val callback =
        Thread {
          callbackAttempted.countDown()
          transitioned.set(
              gate.transitionIfActive {
                replacements.incrementAndGet()
              })
          callbackReturned.countDown()
        }
    stopper.isDaemon = true
    callback.isDaemon = true

    stopper.start()
    assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
    callback.start()
    assertTrue(callbackAttempted.await(2, TimeUnit.SECONDS))
    assertEquals(
        1,
        callbackReturned.count,
        "the callback must wait for the shutdown ownership transaction",
    )

    allowStop.countDown()
    stopper.join(2_000)
    callback.join(2_000)
    assertEquals(0, replacements.get())
    assertTrue(!transitioned.get())
  }

  @Test
  fun `failed controller ownership release blocks transitions but permits stop retry`() {
    val gate = ControllerLifecycleGate()
    val releaseCalls = AtomicInteger()
    val teardownCalls = AtomicInteger()

    assertFailsWith<IOException> {
      gate.stop(
          releaseControllerOwnership = {
            releaseCalls.incrementAndGet()
            throw IOException("persistence barrier")
          },
          finishTeardown = { teardownCalls.incrementAndGet() },
      )
    }

    val transitions = AtomicInteger()
    assertTrue(!gate.transitionIfActive { transitions.incrementAndGet() })
    assertEquals(0, transitions.get())
    assertEquals(0, teardownCalls.get())

    assertTrue(
        gate.stop(
            releaseControllerOwnership = { releaseCalls.incrementAndGet() },
            finishTeardown = { teardownCalls.incrementAndGet() },
        ))
    assertEquals(2, releaseCalls.get())
    assertEquals(1, teardownCalls.get())
    assertTrue(!gate.transitionIfActive { transitions.incrementAndGet() })
  }

  @Test
  fun `peripheral teardown retry does not release controller ownership twice`() {
    val gate = ControllerLifecycleGate()
    val releaseCalls = AtomicInteger()
    val teardownCalls = AtomicInteger()

    assertFailsWith<IOException> {
      gate.stop(
          releaseControllerOwnership = { releaseCalls.incrementAndGet() },
          finishTeardown = {
            teardownCalls.incrementAndGet()
            throw IOException("interrupted peripheral teardown")
          },
      )
    }
    assertTrue(!gate.transitionIfActive {})

    assertTrue(
        gate.stop(
            releaseControllerOwnership = { releaseCalls.incrementAndGet() },
            finishTeardown = { teardownCalls.incrementAndGet() },
        ))
    assertEquals(1, releaseCalls.get())
    assertEquals(2, teardownCalls.get())
  }
}
