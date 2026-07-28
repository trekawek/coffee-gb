package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomReplacementPersistenceFailedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RomReplacementPersistenceBarrierTest {

  @Test
  fun failedBarrierKeepsOldSessionAndRetryCommitsReplacementOffTimingThread() {
    withFixture { fixture ->
      fixture.start()
      Files.createDirectory(fixture.oldSave)

      fixture.eventBus.post(LoadRomEvent(fixture.nextRom.toFile()))

      val observed = assertNotNull(fixture.failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("coffee-gb-controller", observed.threadName)
      assertEquals("old.sav", observed.event.fileName)
      assertNull(
          fixture.stopped.poll(300, TimeUnit.MILLISECONDS),
          "the old session must not close after a failed persistence barrier",
      )
      assertNull(
          fixture.started.poll(300, TimeUnit.MILLISECONDS),
          "the replacement must not start before persistence succeeds",
      )
      fixture.eventBus.post(Controller.RestoreSnapshotEvent(0))
      assertTrue(
          assertNotNull(
                  fixture.stateRestoreFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
              )
              .message
              .contains("replacement"),
      )

      Files.delete(fixture.oldSave)
      fixture.eventBus.post(Controller.RetryRomReplacementEvent(observed.event.requestId))

      assertNotNull(fixture.stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(
          "NEXT_GAME",
          assertNotNull(fixture.started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).romName,
      )
      assertTrue(Files.isRegularFile(fixture.oldSave))
    }
  }

  @Test
  fun cancellingFailedBarrierResumesExactOldSessionAndIgnoresStaleRetry() {
    withFixture { fixture ->
      fixture.start()
      Files.createDirectory(fixture.oldSave)
      fixture.frames.clear()

      fixture.eventBus.post(LoadRomEvent(fixture.nextRom.toFile()))
      val failure =
          assertNotNull(fixture.failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).event

      fixture.eventBus.post(Controller.CancelRomReplacementEvent(failure.requestId))

      assertEquals(
          fixture.nextRom.toFile(),
          assertNotNull(fixture.cancelled.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).rom,
      )
      assertNull(fixture.stopped.poll(300, TimeUnit.MILLISECONDS))
      assertNotNull(
          fixture.frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "cancelling the replacement must resume the old emulation",
      )

      fixture.eventBus.post(Controller.RetryRomReplacementEvent(failure.requestId))
      assertNull(
          fixture.started.poll(500, TimeUnit.MILLISECONDS),
          "a stale retry must not resurrect the cancelled replacement",
      )
    }
  }

  @Test
  fun failedStopBarrierCanBeRetriedWithoutClosingTheSessionEarly() {
    withFixture { fixture ->
      fixture.start()
      Files.createDirectory(fixture.oldSave)

      fixture.eventBus.post(Controller.StopEmulationEvent())

      val failure = assertNotNull(fixture.failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).event
      assertEquals(Controller.PersistenceBarrierOperation.STOP, failure.operation)
      assertNull(fixture.stopped.poll(300, TimeUnit.MILLISECONDS))

      Files.delete(fixture.oldSave)
      fixture.eventBus.post(Controller.RetryRomReplacementEvent(failure.requestId))

      assertNotNull(fixture.stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(Files.isRegularFile(fixture.oldSave))
    }
  }

  @Test
  fun cancellingFailedStopResumesTheOldSession() {
    withFixture { fixture ->
      fixture.start()
      Files.createDirectory(fixture.oldSave)
      fixture.frames.clear()
      fixture.eventBus.post(Controller.StopEmulationEvent())
      val failure =
          assertNotNull(fixture.failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).event

      fixture.eventBus.post(Controller.CancelRomReplacementEvent(failure.requestId))

      assertNull(fixture.stopped.poll(300, TimeUnit.MILLISECONDS))
      assertNotNull(fixture.frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }
  }

  @Test
  fun synchronousCloseFailureRetainsCaptureForASecondCloseAttempt() {
    withFixture { fixture ->
      fixture.start()
      Files.createDirectory(fixture.oldSave)

      val failure =
          assertFailsWith<Controller.PersistenceBarrierException> {
            fixture.controller.closeWithState()
          }

      assertEquals(Controller.PersistenceBarrierOperation.CLOSE, failure.operation)
      assertNull(fixture.stopped.poll(300, TimeUnit.MILLISECONDS))
      Files.delete(fixture.oldSave)

      assertNotNull(fixture.controller.closeWithState())
      assertNull(
          fixture.stopped.poll(300, TimeUnit.MILLISECONDS),
          "synchronous close must not invoke unbounded lifecycle subscribers",
      )
      assertTrue(Files.isRegularFile(fixture.oldSave))
    }
  }

  @Test
  fun closePersistenceDeadlineReturnsTypedTimeoutAndLeavesCaptureIncomplete() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = AtomicBoolean()
    val persistenceExecutor = Executors.newSingleThreadExecutor()
    val capture =
        object : BatteryFlush {
          override fun persist(): BatteryPersistenceResult {
            entered.countDown()
            try {
              release.await()
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
            }
            return BatteryPersistenceResult.Success(1)
          }

          override fun complete(result: BatteryPersistenceResult) {
            completed.set(true)
          }
        }

    try {
      val attempt = RetainedClosePersistence(capture, persistenceExecutor)
      val started = System.nanoTime()
      val result =
          attempt.await(
              "old.sav",
              50,
              TimeUnit.MILLISECONDS,
          ) { failure ->
            throw AssertionError("unexpected persistence failure", failure)
          }
      val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

      assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(result is BatteryPersistenceResult.Failure)
      val failure = result
      assertEquals(
          BatteryPersistenceResult.FailureKind.TIMED_OUT,
          failure.kind(),
      )
      assertEquals("old.sav", failure.fileName())
      assertTrue(elapsedMillis < 2_000, "timeout path took ${elapsedMillis}ms")
      assertTrue(!completed.get(), "timeout must not acknowledge the dirty generation")

      release.countDown()
      assertTrue(
          attempt.await("old.sav", 2, TimeUnit.SECONDS) { error ->
            throw AssertionError("unexpected persistence failure", error)
          } is BatteryPersistenceResult.Success)
    } finally {
      release.countDown()
      persistenceExecutor.shutdownNow()
    }
  }

  private fun withFixture(test: (Fixture) -> Unit) {
    val directory = Files.createTempDirectory("coffee-gb-replacement-barrier")
    val fixture = Fixture(directory)
    try {
      test(fixture)
    } finally {
      fixture.close()
      Files.walk(directory).sorted(Comparator.reverseOrder()).use { paths ->
        paths.forEach(Files::deleteIfExists)
      }
    }
  }

  private class Fixture(val directory: Path) {

    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    val failures = LinkedBlockingQueue<ObservedFailure>()
    val stateRestoreFailures = LinkedBlockingQueue<Controller.SnapshotLoadFailedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    val oldRom = directory.resolve("old.gb")
    val oldSave = directory.resolve("old.sav")
    val nextRom = directory.resolve("next.gb")
    private val properties =
        EmulatorProperties().also {
          it.properties[EmulatorProperties.Key.BootstrapMode.propertyName] =
              BootstrapMode.SKIP.name
        }
    val controller = BasicController(eventBus, properties, null)

    init {
      val oldBytes = ROM.readBytes()
      oldBytes[0x147] = 0x0f // MBC3 timer + battery; flush always captures RTC.
      oldBytes[0x149] = 0
      oldRom.toFile().writeBytes(oldBytes)
      val nextBytes = ROM.readBytes()
      (0x134..0x143).forEach { nextBytes[it] = 0 }
      "NEXT_GAME".toByteArray(Charsets.US_ASCII).copyInto(nextBytes, 0x134)
      nextRom.toFile().writeBytes(nextBytes)

      eventBus.register<EmulationStartedEvent> { started.add(it) }
      eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
      eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }
      eventBus.register<RomReplacementPersistenceFailedEvent> {
        failures.add(ObservedFailure(it, Thread.currentThread().name))
      }
      eventBus.register<Controller.SnapshotLoadFailedEvent> { stateRestoreFailures.add(it) }
      eventBus.register<GbcFrameReadyEvent> { frames.add(it) }
    }

    fun start() {
      controller.startController()
      eventBus.post(LoadRomEvent(oldRom.toFile()))
      assertEquals(
          "CPU_INSTRS",
          assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).romName,
      )
      started.clear()
      stopped.clear()
    }

    fun close() {
      if (Files.isDirectory(oldSave)) {
        Files.delete(oldSave)
      }
      controller.close()
      properties.close()
      eventBus.close()
    }
  }

  private data class ObservedFailure(
      val event: RomReplacementPersistenceFailedEvent,
      val threadName: String,
  )

  private companion object {
    const val TIMEOUT_SECONDS = 10L
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
