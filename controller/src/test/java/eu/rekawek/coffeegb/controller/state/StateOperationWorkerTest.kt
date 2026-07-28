package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class StateOperationWorkerTest {
  @Test
  fun `production worker bounds queued requests and reports every rejection`() {
    val bus = EventBusImpl(null, null, false)
    val completed = LinkedBlockingQueue<StateWorkerCompletedEvent>()
    bus.register(completed::add, StateWorkerCompletedEvent::class.java)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val first = AtomicBoolean(true)
    val worker =
        StateOperationWorker(
            bus,
            externalActions =
                StateExternalActions {
                  if (first.compareAndSet(true, false)) {
                    started.countDown()
                    release.await()
                  }
                  false
                },
        )
    val context = context("bounded-worker")
    var closed = false
    try {
      worker.openFolder(context, 1)
      assertTrue(started.await(5, TimeUnit.SECONDS))
      (2L..41L).forEach { worker.openFolder(context, it) }

      val immediate = generateSequence { completed.poll() }.toList()
      assertEquals(8, immediate.size)
      assertTrue(immediate.all { it.result is StateWorkerResult.Failure })
      assertTrue(
          immediate.all {
            (it.result as StateWorkerResult.Failure).error.summary ==
                "State request could not be queued."
          })
      release.countDown()
      worker.close()
      closed = true
      assertEquals(33, completed.size, "close must await the in-flight and 32 admitted requests")
    } finally {
      release.countDown()
      if (!closed) worker.close()
      bus.close()
    }
  }

  @Test
  fun `rom switch autosave runs before queued ordinary work`() {
    val bus = EventBusImpl(null, null, false)
    val completed = LinkedBlockingQueue<StateWorkerCompletedEvent>()
    bus.register(completed::add, StateWorkerCompletedEvent::class.java)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val first = AtomicBoolean(true)
    val worker =
        StateOperationWorker(
            bus,
            externalActions =
                StateExternalActions {
                  if (first.compareAndSet(true, false)) {
                    started.countDown()
                    release.await()
                  }
                  false
                },
        )
    val context = context("priority-worker")
    val configuration = StateCodecTestSupport.configuration()
    val gameboy = configuration.build()
    gameboy.init(EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null)
    val state = StateCodec.capture(configuration, gameboy)
    try {
      worker.openFolder(context, 1)
      assertTrue(started.await(5, TimeUnit.SECONDS))
      worker.openFolder(context, 2)
      worker.save(
          context,
          3,
          StateWorkerPurpose.AUTOSAVE_ROM_SWITCH,
          StateRef.Autosave,
          state,
          null,
          null,
          null,
      )
      release.countDown()

      assertEquals(1, assertNotNull(completed.poll(5, TimeUnit.SECONDS)).requestId)
      assertEquals(3, assertNotNull(completed.poll(5, TimeUnit.SECONDS)).requestId)
      assertEquals(2, assertNotNull(completed.poll(5, TimeUnit.SECONDS)).requestId)
    } finally {
      release.countDown()
      worker.close()
      bus.close()
    }
  }

  private fun context(name: String): StateWorkerContext {
    val layout = StateStorageLayout(Files.createTempDirectory(name))
    val paths = StateStoragePaths(layout, layout.screenshotsDirectory, emptyList())
    val identity = StateIdentity.from(StateCodecTestSupport.configuration())
    return StateWorkerContext(
        1,
        StateWorkspace(paths),
        identity,
        identity.profile.canonicalProfileId,
    )
  }
}
