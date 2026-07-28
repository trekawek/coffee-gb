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
}
