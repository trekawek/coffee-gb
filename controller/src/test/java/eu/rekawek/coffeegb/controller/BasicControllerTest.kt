package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.Controller.HardwareProfileEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.state.BatteryStorageResolver
import eu.rekawek.coffeegb.controller.state.FileStateStore
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore
import eu.rekawek.coffeegb.controller.state.SessionPersistence
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateStorageLayout
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerTest {

  @Test
  fun benchmarkResumePolicyInterlockCoversPreArmAcceptedCanonicalRevokedMismatchAndFrozen() {
    assertTrue(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = false,
            benchmarkCoreFrozen = false,
            policyProcessed = false,
            policyAccepted = false,
            policyGenerationMatches = false,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "pre-arm resume must retain ordinary behavior",
    )
    assertFalse(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = false,
            policyProcessed = false,
            policyAccepted = false,
            policyGenerationMatches = true,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "an armed generation cannot resume before policy processing",
    )
    assertTrue(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = false,
            policyProcessed = true,
            policyAccepted = true,
            policyGenerationMatches = true,
            policyRequested = true,
            calendarEnabled = true,
        ),
        "matching enabled policy permits resume",
    )
    assertTrue(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = false,
            policyProcessed = true,
            policyAccepted = true,
            policyGenerationMatches = true,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "accepted canonical policy permits resume",
    )
    assertFalse(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = false,
            policyProcessed = true,
            policyAccepted = false,
            policyGenerationMatches = true,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "revoked or rejected policy cannot resume",
    )
    assertFalse(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = false,
            policyProcessed = true,
            policyAccepted = true,
            policyGenerationMatches = false,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "stale policy decisions cannot resume a newer generation",
    )
    assertFalse(
        Controller.benchmarkResumePolicyAllows(
            benchmarkPolicyEnabled = true,
            benchmarkArmed = true,
            benchmarkCoreFrozen = true,
            policyProcessed = true,
            policyAccepted = true,
            policyGenerationMatches = true,
            policyRequested = false,
            calendarEnabled = false,
        ),
        "frame-600 frozen core cannot resume",
    )
  }

  @Test
  fun closeDeadlineBoundsBlockedTimingThreadAndAllowsRetry() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val tickEntered = CountDownLatch(1)
    val releaseTick = CountDownLatch(1)
    val rom = namedRom("BLOCKED_CLOSE")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun tick(): Boolean {
                  tickEntered.countDown()
                  while (releaseTick.count != 0L) {
                    try {
                      releaseTick.await()
                    } catch (_: InterruptedException) {
                      // Model core/platform work that does not cooperate with interruption.
                    }
                  }
                  return false
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(eventBus, testProperties(), null, preparer, closeTimeoutMillis = 500)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(tickEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val closeStarted = System.nanoTime()
      val failure =
          assertFailsWith<Controller.PersistenceBarrierException> {
            controller.closeWithState()
          }
      val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)

      assertEquals(Controller.PersistenceBarrierOperation.CLOSE, failure.operation)
      assertTrue(elapsedMillis < 1_000, "close took ${elapsedMillis}ms")

      releaseTick.countDown()
      assertNotNull(controller.closeWithState())
    } finally {
      releaseTick.countDown()
      runCatching { controller.close() }
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun closeTimeoutRetainsOneWriterUntilItFinishes() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val writerEntered = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    val writerStarts = AtomicInteger()
    val concurrentWriters = AtomicInteger()
    val maxConcurrentWriters = AtomicInteger()
    val lastPublishedWriter = AtomicInteger()
    val completions = AtomicInteger()
    val capture =
        object : BatteryFlush {
          override fun persist(): BatteryPersistenceResult {
            val writer = writerStarts.incrementAndGet()
            val concurrent = concurrentWriters.incrementAndGet()
            maxConcurrentWriters.updateAndGet { previous -> maxOf(previous, concurrent) }
            writerEntered.countDown()
            try {
              while (releaseWriter.count != 0L) {
                try {
                  releaseWriter.await()
                } catch (_: InterruptedException) {
                  // A filesystem call may ignore interruption; retries still must not overlap it.
                }
              }
              lastPublishedWriter.set(writer)
              return BatteryPersistenceResult.Success(writer)
            } finally {
              concurrentWriters.decrementAndGet()
            }
          }

          override fun complete(result: BatteryPersistenceResult) {
            completions.incrementAndGet()
          }
        }
    val rom = namedRom("ONE_WRITER")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun prepareCartridgeFlush(): BatteryFlush = capture
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(eventBus, EmulatorProperties(), null, preparer, closeTimeoutMillis = 500)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertFailsWith<Controller.PersistenceBarrierException> { controller.closeWithState() }
      assertTrue(writerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertFailsWith<Controller.PersistenceBarrierException> { controller.closeWithState() }

      assertEquals(1, writerStarts.get())
      assertEquals(1, maxConcurrentWriters.get())
      assertEquals(0, completions.get())
      assertEquals(0, lastPublishedWriter.get())

      releaseWriter.countDown()
      assertNotNull(controller.closeWithState())
      assertEquals(1, writerStarts.get())
      assertEquals(1, maxConcurrentWriters.get())
      assertEquals(1, lastPublishedWriter.get())
      assertEquals(1, completions.get())
    } finally {
      releaseWriter.countDown()
      runCatching { controller.close() }
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun cancelledReplacementWriterFinishesBeforeNewestCloseCapturePublishes() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val firstWriterEntered = CountDownLatch(1)
    val releaseFirstWriter = CountDownLatch(1)
    val closeWriterPublished = CountDownLatch(1)
    val nextGeneration = AtomicInteger()
    val concurrentWriters = AtomicInteger()
    val maxConcurrentWriters = AtomicInteger()
    val publishedGenerations = CopyOnWriteArrayList<Int>()
    val oldRom = namedRom("ORDERED_OLD")
    val nextRom = namedRom("ORDERED_NEXT")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun prepareCartridgeFlush(): BatteryFlush {
                  val generation = nextGeneration.incrementAndGet()
                  return object : BatteryFlush {
                    override fun persist(): BatteryPersistenceResult {
                      val concurrent = concurrentWriters.incrementAndGet()
                      maxConcurrentWriters.updateAndGet { previous ->
                        maxOf(previous, concurrent)
                      }
                      try {
                        if (generation == 1) {
                          firstWriterEntered.countDown()
                          while (releaseFirstWriter.count != 0L) {
                            try {
                              releaseFirstWriter.await()
                            } catch (_: InterruptedException) {
                              // The cancelled replacement writer deliberately ignores interrupt.
                            }
                          }
                        }
                        publishedGenerations.add(generation)
                        if (generation == 2) {
                          closeWriterPublished.countDown()
                        }
                        return BatteryPersistenceResult.Success(generation)
                      } finally {
                        concurrentWriters.decrementAndGet()
                      }
                    }

                    override fun complete(result: BatteryPersistenceResult) {}
                  }
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(
            eventBus,
            testProperties(),
            null,
            preparer,
            closeTimeoutMillis = CLOSE_PERSISTENCE_TIMEOUT_MILLIS,
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(oldRom))
      assertEquals("ORDERED_OLD", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)

      eventBus.post(LoadRomEvent(nextRom))
      assertTrue(firstWriterEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertFailsWith<Controller.PersistenceBarrierException> {
        controller.closeWithState()
      }
      assertEquals(1, maxConcurrentWriters.get())
      assertTrue(publishedGenerations.isEmpty())

      releaseFirstWriter.countDown()
      assertTrue(closeWriterPublished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), publishedGenerations)
      assertEquals(1, maxConcurrentWriters.get())

      assertNotNull(controller.closeWithState())
      assertEquals(2, publishedGenerations.last())
    } finally {
      releaseFirstWriter.countDown()
      runCatching { controller.close() }
      eventBus.close()
      oldRom.delete()
      nextRom.delete()
    }
  }

  @Test
  fun cancelledStopWriterFinishesBeforeNewestCloseCapturePublishes() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val firstWriterEntered = CountDownLatch(1)
    val releaseFirstWriter = CountDownLatch(1)
    val closeWriterPublished = CountDownLatch(1)
    val nextGeneration = AtomicInteger()
    val concurrentWriters = AtomicInteger()
    val maxConcurrentWriters = AtomicInteger()
    val publishedGenerations = CopyOnWriteArrayList<Int>()
    val rom = namedRom("ORDERED_STOP")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun prepareCartridgeFlush(): BatteryFlush {
                  val generation = nextGeneration.incrementAndGet()
                  return object : BatteryFlush {
                    override fun persist(): BatteryPersistenceResult {
                      val concurrent = concurrentWriters.incrementAndGet()
                      maxConcurrentWriters.updateAndGet { previous ->
                        maxOf(previous, concurrent)
                      }
                      try {
                        if (generation == 1) {
                          firstWriterEntered.countDown()
                          while (releaseFirstWriter.count != 0L) {
                            try {
                              releaseFirstWriter.await()
                            } catch (_: InterruptedException) {
                              // The cancelled stop writer deliberately ignores interrupt.
                            }
                          }
                        }
                        publishedGenerations.add(generation)
                        if (generation == 2) {
                          closeWriterPublished.countDown()
                        }
                        return BatteryPersistenceResult.Success(generation)
                      } finally {
                        concurrentWriters.decrementAndGet()
                      }
                    }

                    override fun complete(result: BatteryPersistenceResult) {}
                  }
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(
            eventBus,
            testProperties(),
            null,
            preparer,
            closeTimeoutMillis = CLOSE_PERSISTENCE_TIMEOUT_MILLIS,
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertEquals("ORDERED_STOP", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)

      eventBus.post(Controller.StopEmulationEvent())
      assertTrue(firstWriterEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertFailsWith<Controller.PersistenceBarrierException> { controller.closeWithState() }
      assertEquals(1, maxConcurrentWriters.get())
      assertTrue(publishedGenerations.isEmpty())

      releaseFirstWriter.countDown()
      assertTrue(closeWriterPublished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), publishedGenerations)
      assertEquals(1, maxConcurrentWriters.get())

      assertNotNull(controller.closeWithState())
      assertEquals(2, publishedGenerations.last())
    } finally {
      releaseFirstWriter.countDown()
      runCatching { controller.close() }
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun finalClosePropagatesSessionBusTimeoutAndDefersMachineCleanupUntilRetry() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val subscriberEntered = CountDownLatch(1)
    val releaseSubscriber = CountDownLatch(1)
    val subscriberReturned = CountDownLatch(1)
    val cleanupCalls = AtomicInteger()
    val rom = namedRom("CLOSE_BUS_WAIT")
    val console = TrackingConsole()
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun init(
                    eventBus: EventBus,
                    serialEndpoint: SerialEndpoint,
                    infraredEndpoint: InfraredEndpoint,
                    console: Console?,
                ) {
                  super.init(eventBus, serialEndpoint, infraredEndpoint, console)
                  eventBus.register<FinalCloseEvent> {
                    subscriberEntered.countDown()
                    while (releaseSubscriber.count != 0L) {
                      try {
                        releaseSubscriber.await()
                      } catch (_: InterruptedException) {
                        // The final close caller owns the retry while this subscriber unwinds.
                      }
                    }
                    subscriberReturned.countDown()
                  }
                  eventBus.postAsync(FinalCloseEvent())
                }

                override fun closeAfterCartridgeFlushSilently() {
                  cleanupCalls.incrementAndGet()
                  super.closeAfterCartridgeFlushSilently()
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(eventBus, EmulatorProperties(), console, preparer, closeTimeoutMillis = 500)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val retained = assertNotNull(console.attachedDebugPort)
      assertTrue(subscriberEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val closeStarted = System.nanoTime()
      val failure =
          assertFailsWith<Controller.PersistenceBarrierException> {
            controller.closeWithState()
          }
      val closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)

      assertEquals(Controller.PersistenceBarrierOperation.CLOSE, failure.operation)
      assertTrue(closeMillis < 2_000, "close took ${closeMillis}ms")
      assertEquals(
          0,
          cleanupCalls.get(),
          "machine cleanup must wait until the session bus has actually stopped",
      )
      assertTrue(retained.isClosed)
      assertNull(console.attachedDebugPort)

      releaseSubscriber.countDown()
      assertTrue(subscriberReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(controller.closeWithState())
      assertEquals(1, cleanupCalls.get())
      assertNull(console.attachedDebugPort)
    } finally {
      releaseSubscriber.countDown()
      runCatching { controller.close() }
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun finalCloseRetainsMachineWhileInterruptIgnoringLoaderStillRuns() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val loaderEntered = CountDownLatch(1)
    val releaseLoader = CountDownLatch(1)
    val loaderReturned = CountDownLatch(1)
    val cleanupCalls = AtomicInteger()
    val oldRom = namedRom("LOADER_OLD")
    val nextRom = namedRom("LOADER_BLOCK")
    val console = TrackingConsole()
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          if (event.rom == nextRom) {
            loaderEntered.countDown()
            while (releaseLoader.count != 0L) {
              try {
                releaseLoader.await()
              } catch (_: InterruptedException) {
                // Model an archive/loader implementation blocked in non-cooperative I/O.
              }
            }
            loaderReturned.countDown()
          }
          val gameboy =
              object : Gameboy(config) {
                override fun closeAfterCartridgeFlushSilently() {
                  cleanupCalls.incrementAndGet()
                  super.closeAfterCartridgeFlushSilently()
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(eventBus, EmulatorProperties(), console, preparer, closeTimeoutMillis = 250)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(oldRom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val retained = assertNotNull(console.attachedDebugPort)
      eventBus.post(LoadRomEvent(nextRom))
      assertTrue(loaderEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertFailsWith<Controller.PersistenceBarrierException> { controller.closeWithState() }
      assertEquals(0, cleanupCalls.get())
      assertTrue(retained.isClosed)
      assertNull(console.attachedDebugPort)

      releaseLoader.countDown()
      assertTrue(loaderReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(controller.closeWithState())
      assertEquals(1, cleanupCalls.get())
      assertNull(console.attachedDebugPort)
    } finally {
      releaseLoader.countDown()
      runCatching { controller.close() }
      eventBus.close()
      oldRom.delete()
      nextRom.delete()
    }
  }

  @Test
  fun rumbleOnReplacementStopAndFinalCloseUsesSilentCoreCleanup() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val rumble = LinkedBlockingQueue<RumbleEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<RumbleEvent> { rumble.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    val cleanupCalls = AtomicInteger()
    val rumbleRequests = AtomicInteger()
    val firstRom = namedRom("RUMBLE_FIRST")
    val secondRom = namedRom("RUMBLE_SECOND")
    val console = TrackingConsole()
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setCodeBreakerRumble(true)
          val gameboy =
              object : Gameboy(config) {
                override fun tick(): Boolean {
                  if (rumbleRequests.getAndSet(0) != 0) {
                    addressSpace.setByte(0xfffe, 0x80)
                  }
                  return super.tick()
                }

                override fun closeAfterCartridgeFlushSilently() {
                  cleanupCalls.incrementAndGet()
                  super.closeAfterCartridgeFlushSilently()
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, testProperties(), console, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(firstRom))
      assertEquals("RUMBLE_FIRST", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      rumbleRequests.incrementAndGet()
      assertTrue(assertNotNull(rumble.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).on())

      eventBus.post(LoadRomEvent(secondRom))
      assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("RUMBLE_SECOND", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      awaitValue(cleanupCalls, 1)

      rumbleRequests.incrementAndGet()
      assertTrue(assertNotNull(rumble.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).on())
      eventBus.post(Controller.StopEmulationEvent())
      assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      awaitValue(cleanupCalls, 2)
      awaitCondition { console.attachedDebugPort == null }

      eventBus.post(LoadRomEvent(firstRom))
      assertEquals("RUMBLE_FIRST", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      rumbleRequests.incrementAndGet()
      assertTrue(assertNotNull(rumble.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).on())
      assertNotNull(controller.closeWithState())
      assertEquals(3, cleanupCalls.get())
      assertNull(console.attachedDebugPort)
      assertNull(failures.poll(250, TimeUnit.MILLISECONDS))
    } finally {
      runCatching { controller.close() }
      eventBus.close()
      firstRom.delete()
      secondRom.delete()
    }
  }

  @Test
  fun memoryOriginLoadsWithoutAdvertisingFilesystemSnapshots() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val support = LinkedBlockingQueue<Controller.SessionSnapshotSupportEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SessionSnapshotSupportEvent> { support.add(it) }
    val image = RomImage.memory(ROM.readBytes(), "memory-rom.gb")
    val controller = BasicController(eventBus, EmulatorProperties(), null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(image))

      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNull(assertNotNull(support.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).snapshotSupport)
      assertFalse(controller.snapshotAvailable(0))
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun persistedSgb2AndMgbSelectionsReloadTheRunningSessionWithExactProfile() {
    val eventBus = EventBusImpl()
    val properties = EmulatorProperties()
    val profileEvents = LinkedBlockingQueue<HardwareProfileEvent>()
    eventBus.register<HardwareProfileEvent> { profileEvents.add(it) }
    val sgbRom =
        Files.createTempFile("coffee-gb-sgb2-profile-reload", ".gb").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0
                bytes[0x146] = 0x03
              })
        }
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
        HardwareProfileRegistry.SGB.id()
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(sgbRom))
      assertEquals(
          HardwareProfileRegistry.SGB,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )

      properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
          HardwareProfileRegistry.SGB2.id()
      eventBus.post(Controller.UpdatedSystemMappingEvent())
      assertEquals(
          HardwareProfileRegistry.SGB2,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )

      properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
          HardwareProfileRegistry.MGB.id()
      eventBus.post(Controller.UpdatedSystemMappingEvent())
      assertEquals(
          HardwareProfileRegistry.MGB,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
    } finally {
      controller.close()
      eventBus.close()
      sgbRom.delete()
    }
  }

  @Test
  fun hostPersistenceStoreSurvivesPathlessProfileReloadsAndReset() {
    val directory = Files.createTempDirectory("coffee-gb-host-persistence-reload")
    val eventBus = EventBusImpl()
    val properties = testProperties()
    val profileEvents = LinkedBlockingQueue<HardwareProfileEvent>()
    val stateSessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<HardwareProfileEvent>(profileEvents::add)
    eventBus.register<StateUxSessionEvent>(stateSessions::add)
    eventBus.register<LoadRomFailedEvent>(failures::add)
    val image =
        RomImage.memory(
            ROM.readBytes().also { bytes ->
              bytes[0x143] = 0
              bytes[0x146] = 0x03
            },
            "pathless-profile-reload.gb",
        )
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
        HardwareProfileRegistry.SGB.id()
    val resolveCalls = AtomicInteger()
    val layout = StateStorageLayout(directory.resolve("games").resolve("pathless"))
    val persistenceStore =
        RomPersistenceStore { _, _ ->
          resolveCalls.incrementAndGet()
          SessionPersistence(FileStateStore(layout), null, null)
        }
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(
          LoadRomEvent(
              image,
              persistenceStore = persistenceStore,
              allowAutosaveResume = false,
          ))
      assertEquals(
          HardwareProfileRegistry.SGB,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
      assertTrue(assertNotNull(stateSessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).available)

      properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
          HardwareProfileRegistry.DMG.id()
      eventBus.post(Controller.UpdatedSystemMappingEvent())
      assertEquals(
          HardwareProfileRegistry.DMG,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
      assertTrue(assertNotNull(stateSessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).available)

      properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
          HardwareProfileRegistry.SGB2.id()
      eventBus.post(Controller.UpdatedSystemMappingEvent())
      assertEquals(
          HardwareProfileRegistry.SGB2,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
      assertTrue(assertNotNull(stateSessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).available)

      eventBus.post(Controller.ResetEmulationEvent())
      assertEquals(
          HardwareProfileRegistry.SGB2,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
      assertTrue(assertNotNull(stateSessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).available)

      assertEquals(4, resolveCalls.get())
      assertNull(failures.poll(250, TimeUnit.MILLISECONDS))
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      deleteTree(directory)
    }
  }

  @Test
  fun hardwareProfileEventReportsResolvedCgbCompatibilityNativeAndCgb0() {
    val nonColorRom =
        Files.createTempFile("coffee-gb-cgb-compat-profile", ".gb").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0
              })
        }
    val colorRom =
        Files.createTempFile("coffee-gb-cgb-native-profile", ".gbc").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0x80.toByte()
              })
        }
    val eventBus = EventBusImpl()
    val profileEvents = LinkedBlockingQueue<HardwareProfileEvent>()
    eventBus.register<HardwareProfileEvent> { profileEvents.add(it) }
    val properties = EmulatorProperties(HardwareProfileRegistry.CGB)
    properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = "SKIP"
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(nonColorRom))
      val compat = assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(HardwareProfileRegistry.CGB, compat.profile)
      assertEquals(true, compat.effectiveGbc)
      assertEquals(true, compat.effectiveDmgCompat)
      assertEquals(1, compat.effectiveSpeedMode)

      eventBus.post(LoadRomEvent(colorRom))
      val native = assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(HardwareProfileRegistry.CGB, native.profile)
      assertEquals(true, native.effectiveGbc)
      assertEquals(false, native.effectiveDmgCompat)
      assertEquals(1, native.effectiveSpeedMode)
    } finally {
      controller.close()
      eventBus.close()
    }

    val cgb0EventBus = EventBusImpl()
    val cgb0ProfileEvents = LinkedBlockingQueue<HardwareProfileEvent>()
    cgb0EventBus.register<HardwareProfileEvent> { cgb0ProfileEvents.add(it) }
    val cgb0Properties = EmulatorProperties(HardwareProfileRegistry.CGB0)
    cgb0Properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = "SKIP"
    val cgb0Controller = BasicController(cgb0EventBus, cgb0Properties, null)
    cgb0Controller.startController()
    try {
      cgb0EventBus.post(LoadRomEvent(colorRom))
      val cgb0 = assertNotNull(cgb0ProfileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(HardwareProfileRegistry.CGB0, cgb0.profile)
      assertEquals(true, cgb0.effectiveGbc)
      assertEquals(false, cgb0.effectiveDmgCompat)
      assertEquals(1, cgb0.effectiveSpeedMode)
    } finally {
      cgb0Controller.close()
      cgb0EventBus.close()
      nonColorRom.delete()
      colorRom.delete()
    }
  }

  @Test
  fun sgbBorderOptionUpdatesPortableCaptureIdentityAtFrameBoundary() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-sgb-border-option")
    val rom =
        directory.resolve("border.gb").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0
                bytes[0x146] = 0x03
              })
        }
    val properties = EmulatorProperties(HardwareProfileRegistry.SGB)
    properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = "SKIP"
    properties.properties[EmulatorProperties.Key.ShowSgbBorder.propertyName] = "false"
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(SgbDisplay.SetSgbBorder(true))
      eventBus.post(Controller.SaveSnapshotEvent(0))
      assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val identity =
          StateCodec.inspect(Files.readAllBytes(directory.resolve("border.sn0")))
              .identities
              .single()
              .identity
      assertNotNull(identity)
      assertEquals(HardwareProfileRegistry.SGB.id(), identity.profile.canonicalProfileId)
      assertTrue(identity.profile.displaySgbBorder)
    } finally {
      controller.close()
      eventBus.close()
      deleteTree(directory)
    }
  }

  @Test
  fun sgbBorderOptionCannotEnterCgbPortableCaptureIdentityAtFrameBoundary() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-cgb-border-option")
    val rom =
        directory.resolve("color.gbc").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0x80.toByte()
                bytes[0x146] = 0
              })
        }
    val properties = EmulatorProperties(HardwareProfileRegistry.CGB)
    properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = "SKIP"
    properties.properties[EmulatorProperties.Key.ShowSgbBorder.propertyName] = "true"
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(SgbDisplay.SetSgbBorder(true))
      eventBus.post(Controller.SaveSnapshotEvent(0))
      assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      val identity =
          StateCodec.inspect(Files.readAllBytes(directory.resolve("color.sn0")))
              .identities
              .single()
              .identity
      assertNotNull(identity)
      assertEquals(HardwareProfileRegistry.CGB.id(), identity.profile.canonicalProfileId)
      assertFalse(identity.profile.displaySgbBorder)
    } finally {
      controller.close()
      eventBus.close()
      deleteTree(directory)
    }
  }

  @Test
  fun preparedRewindSeedCommitsBeforeCandidateEventsAndFailedPreparationKeepsOldSession() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    val sourceEvents = LinkedBlockingQueue<PreparedSeedSourceEvent>()
    val rewind = TrackingRewindManager()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<LoadRomFailedEvent>(failures::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)
    eventBus.register<PreparedSeedSourceEvent> {
      rewind.sourceEventObservedAfterCommit =
          rewind.preparationCount.get() == 1 && rewind.hasPreparedSessionSeed
      sourceEvents.add(it)
    }
    val oldRom = namedRom("SEED_OLD")
    val failingRom = namedRom("SEED_FAIL")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                override fun init(
                    eventBus: EventBus,
                    serialEndpoint: SerialEndpoint,
                    infraredEndpoint: InfraredEndpoint,
                    console: Console?,
                ) {
                  super.init(eventBus, serialEndpoint, infraredEndpoint, console)
                  eventBus.post(PreparedSeedSourceEvent())
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            preparer,
            SnapshotManagerFactory.DEFAULT,
            rewind,
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(oldRom))
      assertNotNull(sourceEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("SEED_OLD", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      assertEquals(1, rewind.preparationCount.get())
      assertTrue(rewind.sourceEventObservedAfterCommit)

      rewind.failPreparation = true
      frames.clear()
      eventBus.post(LoadRomEvent(failingRom))

      assertEquals(failingRom, assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).rom)
      assertEquals(2, rewind.preparationCount.get())
      assertNull(
          sourceEvents.poll(300, TimeUnit.MILLISECONDS),
          "a discarded candidate must not publish its staged source event",
      )
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the old session must continue after candidate seed preparation fails",
      )
    } finally {
      controller.close()
      eventBus.close()
      oldRom.delete()
      failingRom.delete()
    }
  }

  @Test
  fun disabledRewindCreatesNoFrameCapturesAndCannotFreezeForwardEmulation() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }
    val rewind = RewindManager(enabled = false)
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            rewind,
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(0, rewind.captureCount)

      frames.clear()
      eventBus.post(Controller.RewindEvent(true))
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "a disabled rewind request must not stop normal frame progress",
      )
      assertEquals(0, rewind.captureCount)
      assertEquals(0, rewind.historySize)
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun failedSnapshotSaveEmitsOnlyFailureRetainsOldFileAndControllerLaterSaves() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<Controller.SnapshotSaveFailedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotSaveFailedEvent> { failures.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-controller-save-failure")
    val rom = directory.resolve("state-test.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    val persistence = ToggleFailWriter()
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory { configuration ->
              SnapshotManager.testing(
                  configuration,
                  LegacySnapshotMigrationPolicy.PRESERVE,
                  persistence = persistence,
              )
            },
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(Controller.SaveSnapshotEvent(0))
      assertEquals(0, assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)
      val file = directory.resolve("state-test.sn0")
      val prior = Files.readAllBytes(file)

      persistence.fail = true
      eventBus.post(Controller.SaveSnapshotEvent(0))
      val failure = assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(0, failure.slot)
      assertTrue(failure.message.contains("previous state remains recoverable"))
      assertNull(saved.poll(250, TimeUnit.MILLISECONDS), "failure must not post success")
      assertTrue(prior.contentEquals(Files.readAllBytes(file)))

      persistence.fail = false
      eventBus.post(Controller.SaveSnapshotEvent(1))
      assertEquals(
          1,
          assertNotNull(
                  saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "controller must keep processing after a persistence failure",
              )
              .slot,
      )
      assertEquals("CGBS", Files.readAllBytes(directory.resolve("state-test.sn1")).copyOf(4).toString(Charsets.US_ASCII))
    } finally {
      controller.close()
      eventBus.close()
      deleteTree(directory)
    }
  }

  @Test
  fun failedLoadDoesNotPreventLoadingAnotherRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    val controller = BasicController(eventBus, EmulatorProperties(), null)
    val invalidRom = Files.createTempFile("coffee-gb-invalid-rom", ".gbc").toFile()
    invalidRom.writeText("not a Game Boy ROM")

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertEquals("CPU_INSTRS", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)

      eventBus.post(LoadRomEvent(invalidRom))
      val failure = failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertNotNull(failure, "the failed load should be reported")
      assertEquals(invalidRom, failure.rom)

      eventBus.post(LoadRomEvent(ROM))
      assertEquals(
          "CPU_INSTRS",
          started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName,
          "the controller thread should keep processing load requests",
      )
    } finally {
      controller.close()
      eventBus.close()
      invalidRom.delete()
    }
  }

  @Test
  fun coreInitializationFailureLeavesOldSessionRunning() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }
    val failingRom = namedRom("INIT_FAILURE")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              if (event.rom == failingRom) {
                object : Gameboy(config) {
                  override fun init(
                      eventBus: EventBus,
                      serialEndpoint: SerialEndpoint,
                      infraredEndpoint: InfraredEndpoint,
                      console: Console?,
                  ) {
                    super.init(eventBus, serialEndpoint, infraredEndpoint, console)
                    throw IOException("injected core initialization failure")
                  }
                }
              } else {
                config.build()
              }
          PreparedSession.Ready(config, gameboy)
        }
    val console = TrackingConsole()
    val controller = BasicController(eventBus, EmulatorProperties(), console, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val oldPort = assertNotNull(console.attachedDebugPort)
      started.clear()
      stopped.clear()
      frames.clear()

      eventBus.post(LoadRomEvent(failingRom))

      assertEquals(
          failingRom,
          assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).rom,
      )
      assertNull(stopped.poll(300, TimeUnit.MILLISECONDS))
      assertNull(started.poll(300, TimeUnit.MILLISECONDS))
      assertSame(oldPort, console.attachedDebugPort)
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the old session must resume after candidate initialization fails",
      )
    } finally {
      controller.close()
      eventBus.close()
      failingRom.delete()
    }
  }

  @Test
  fun successfulReplacementKeepsConsoleAttachedToNewSession() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    val console = TrackingConsole()
    val nextRom = namedRom("CONSOLE_NEXT")
    val controller = BasicController(eventBus, EmulatorProperties(), console)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val oldPort = assertNotNull(console.attachedDebugPort)

      eventBus.post(LoadRomEvent(nextRom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertNotNull(console.attachedDebugPort)
      assertTrue(console.attachedDebugPort !== oldPort)
    } finally {
      controller.close()
      eventBus.close()
      nextRom.delete()
    }
  }

  @Test
  fun stalledOldSessionSubscriberDefersCleanupWithoutRollingBackReplacement() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    val oldSubscriberEntered = CountDownLatch(1)
    val releaseOldSubscriber = CountDownLatch(1)
    val oldSubscriberReturned = CountDownLatch(1)
    val oldDeliveries = AtomicInteger()
    val oldRom = namedRom("STALLED_OLD")
    val nextRom = namedRom("ACTIVE_NEXT")
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              if (event.rom == oldRom) {
                object : Gameboy(config) {
                  override fun init(
                      eventBus: EventBus,
                      serialEndpoint: SerialEndpoint,
                      infraredEndpoint: InfraredEndpoint,
                      console: Console?,
                  ) {
                    super.init(eventBus, serialEndpoint, infraredEndpoint, console)
                    eventBus.register<StalledSessionEvent> {
                      oldDeliveries.incrementAndGet()
                      oldSubscriberEntered.countDown()
                      while (releaseOldSubscriber.count != 0L) {
                        try {
                          releaseOldSubscriber.await()
                        } catch (_: InterruptedException) {
                          // Deliberately emulate an async presentation subscriber that ignores
                          // interruption. Replacement still owns a bounded commit boundary.
                        }
                      }
                      oldSubscriberReturned.countDown()
                    }
                    eventBus.postAsync(StalledSessionEvent())
                  }
                }
              } else {
                config.build()
              }
          PreparedSession.Ready(config, gameboy)
        }
    val console = TrackingConsole()
    val controller = BasicController(eventBus, EmulatorProperties(), console, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(oldRom))
      assertEquals("STALLED_OLD", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      assertTrue(oldSubscriberEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val oldPort = assertNotNull(console.attachedDebugPort)

      val replacementStarted = System.nanoTime()
      eventBus.post(LoadRomEvent(nextRom))
      assertEquals(
          "ACTIVE_NEXT",
          started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName,
          "the prepared candidate must activate after the bounded old-bus close attempt",
      )
      val replacementMillis =
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - replacementStarted)
      assertTrue(replacementMillis < 5_000, "replacement took ${replacementMillis}ms")
      assertNull(failures.poll(250, TimeUnit.MILLISECONDS))
      assertTrue(console.attachedDebugPort !== oldPort)

      eventBus.post(StalledSessionEvent())
      assertEquals(
          1,
          oldDeliveries.get(),
          "the stopping old bus must not deliver events after its close timeout",
      )

      releaseOldSubscriber.countDown()
      assertTrue(oldSubscriberReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    } finally {
      releaseOldSubscriber.countDown()
      runCatching { controller.close() }
      eventBus.close()
      oldRom.delete()
      nextRom.delete()
    }
  }

  @Test
  fun batterySaveEnablementChangesOnlyTheNextOpenedGame() {
    val directory = Files.createTempDirectory("coffee-gb-battery-setting")
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0)
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val configurations = CopyOnWriteArrayList<Gameboy.GameboyConfiguration>()
    val liveResolutions = AtomicInteger()
    val rom = namedRom("BATTERY_SETTING")
    eventBus.register<EmulationStartedEvent>(started::add)
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          configurations += config
          PreparedSession.Ready(config, config.build())
        }
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            preparer,
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory.DEFAULT,
            liveBatteryStorageResolver =
                LiveBatteryStorageResolver { saves, configuration, hashes ->
                  liveResolutions.incrementAndGet()
                  BatteryStorageResolver.resolve(saves, configuration, hashes)
                },
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(configurations.single().isSupportBatterySave)

      properties.updateApplicationSettings { settings ->
        settings.copy(saves = settings.saves.copy(batterySavesEnabled = false))
      }
      eventBus.post(Controller.UpdatedSavesSettingsEvent(properties.applicationSettings.saves))
      awaitValue(liveResolutions, 1)
      assertTrue(
          configurations[0].isSupportBatterySave,
          "disabling battery saves must not replace the active cartridge's construction policy",
      )

      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertFalse(configurations[1].isSupportBatterySave)

      properties.updateApplicationSettings { settings ->
        settings.copy(saves = settings.saves.copy(batterySavesEnabled = true))
      }
      eventBus.post(Controller.UpdatedSavesSettingsEvent(properties.applicationSettings.saves))
      awaitValue(liveResolutions, 2)
      assertFalse(
          configurations[1].isSupportBatterySave,
          "enabling battery saves must not retrofit persistence into the active cartridge",
      )

      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(configurations[2].isSupportBatterySave)
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      rom.delete()
      deleteTree(directory)
    }
  }

  @Test
  fun rejectedLiveBatteryDestinationRetainsThePriorTargetAndTimingThread() {
    val directory = Files.createTempDirectory("coffee-gb-battery-live-failure")
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0)
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    val resolverCalls = AtomicInteger()
    val liveStorageCalls = AtomicInteger()
    val priorStorage = BatteryStorage.direct(directory.resolve("prior.sav"))
    val rom = namedRom("BATTERY_FAILURE")
    lateinit var activeConfiguration: Gameboy.GameboyConfiguration
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<GbcFrameReadyEvent>(frames::add)
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setBatteryStorage(priorStorage, null)
          activeConfiguration = config
          val gameboy =
              object : Gameboy(config) {
                override fun setBatteryStorage(
                    primary: BatteryStorage?,
                    slot: BatteryStorage?,
                ) {
                  liveStorageCalls.incrementAndGet()
                  super.setBatteryStorage(primary, slot)
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            preparer,
            SnapshotManagerFactory.DEFAULT,
            RewindManager(enabled = false),
            StateWorkspaceFactory.DEFAULT,
            StateOperationWorkerFactory.DEFAULT,
            liveBatteryStorageResolver =
                LiveBatteryStorageResolver { _, _, _ ->
                  resolverCalls.incrementAndGet()
                  throw IllegalArgumentException("injected unsafe Saves root")
                },
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      frames.clear()

      eventBus.post(Controller.UpdatedSavesSettingsEvent(properties.applicationSettings.saves))
      awaitValue(resolverCalls, 1)

      assertSame(priorStorage, activeConfiguration.batteryStorage)
      assertEquals(
          0,
          liveStorageCalls.get(),
          "failed resolution must not touch the active cartridge destination",
      )
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "a rejected Saves update must not terminate the controller timing thread",
      )
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      rom.delete()
      deleteTree(directory)
    }
  }

  @Test
  fun rejectedSnapshotIsReportedAndControllerKeepsProcessingEvents() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<Controller.SnapshotLoadFailedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    val restored = LinkedBlockingQueue<Controller.SnapshotRestoredEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotLoadFailedEvent> { failures.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    eventBus.register<Controller.SnapshotRestoredEvent> { restored.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-controller-state")
    val rom = directory.resolve("state-test.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    directory.resolve("state-test.sn0").toFile().writeBytes(byteArrayOf(1, 2, 3, 4))
    val controller = BasicController(eventBus, EmulatorProperties(), null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(Controller.RestoreSnapshotEvent(0))
      assertEquals(0, assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)

      eventBus.post(Controller.SaveSnapshotEvent(1))
      assertEquals(
          1,
          assertNotNull(
                  saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "the controller thread should remain alive after a rejected snapshot",
              )
              .slot,
      )
      val portable = directory.resolve("state-test.sn1").toFile().readBytes()
      assertEquals("CGBS", portable.copyOf(4).toString(Charsets.US_ASCII))
      assertTrue(!LegacySnapshotImporter.hasJavaSerializationHeader(portable))

      eventBus.post(Controller.RestoreSnapshotEvent(1))
      assertEquals(1, assertNotNull(restored.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)
    } finally {
      controller.close()
      eventBus.close()
      deleteTree(directory)
    }
  }

  @Test
  fun pausesCurrentSessionWhileNextRomIsPrepared() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val loading = LinkedBlockingQueue<RomLoadingEvent>()
    val playback = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<RomLoadingEvent> { loading.add(it) }
    eventBus.register<Controller.SessionPlaybackStateEvent> { playback.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }

    val nextRom = namedRom("NEXT_GAME")
    val preparing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          if (event.rom == nextRom) {
            preparing.countDown()
            try {
              release.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      val initial = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("CPU_INSTRS", initial.romName)
      val runningInitial = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(initial.sessionGeneration, runningInitial.sessionGeneration)
      assertFalse(runningInitial.paused)
      loading.clear()
      playback.clear()
      frames.clear()

      eventBus.post(Controller.PauseEmulationEvent())
      val pausedInitial = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(initial.sessionGeneration, pausedInitial.sessionGeneration)
      assertTrue(pausedInitial.paused)
      playback.clear()

      eventBus.post(LoadRomEvent(nextRom))
      assertTrue(preparing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(nextRom, loading.poll(1, TimeUnit.SECONDS)?.rom)
      assertNull(stopped.poll(250, TimeUnit.MILLISECONDS), "the old session must stay active")
      frames.clear()
      assertNull(frames.poll(250, TimeUnit.MILLISECONDS), "the old session must be frozen")
      eventBus.post(Controller.ResumeEmulationEvent())
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "resume during loading must apply to the new session, not restart the old game",
      )

      release.countDown()
      val replacement = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("NEXT_GAME", replacement.romName)
      val replacementPlayback =
          generateSequence {
                playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
              }
              .first { it.sessionGeneration == replacement.sessionGeneration }
      assertFalse(
          replacementPlayback.paused,
          "a resume requested while loading must become the replacement's playback state",
      )
    } finally {
      release.countDown()
      controller.close()
      eventBus.close()
      nextRom.delete()
    }
  }

  @Test
  fun rapidLoadBurstStartsOnlyTheLatestRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }

    val firstRom = namedRom("FIRST_GAME")
    val middleRom = namedRom("MIDDLE_GAME")
    val lastRom = namedRom("LAST_GAME")
    val firstPreparationStarted = CountDownLatch(1)
    val neverReleaseFirst = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          if (event.rom == firstRom) {
            firstPreparationStarted.countDown()
            try {
              neverReleaseFirst.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(firstRom))
      assertTrue(firstPreparationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(LoadRomEvent(middleRom))
      eventBus.post(LoadRomEvent(lastRom))

      assertEquals("LAST_GAME", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      assertNull(started.poll(500, TimeUnit.MILLISECONDS), "superseded ROMs must never start")
      assertEquals(firstRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
      assertEquals(middleRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
    } finally {
      controller.close()
      eventBus.close()
      firstRom.delete()
      middleRom.delete()
      lastRom.delete()
    }
  }

  @Test
  fun correlatedLoadPublishesRequestIdentityAndIgnoresStaleCancellation() {
    val eventBus = EventBusImpl()
    val loading = LinkedBlockingQueue<RomLoadingEvent>()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    eventBus.register<RomLoadingEvent> { loading.add(it) }
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }
    val rom = namedRom("CORRELATED")
    val preparing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          preparing.countDown()
          release.await()
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, openRequestId = 42))
      assertTrue(preparing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(42, loading.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.openRequestId)

      eventBus.post(Controller.CancelRomOpenEvent(41))
      assertNull(
          cancelled.poll(250, TimeUnit.MILLISECONDS),
          "a stale request ID must not cancel the active preparation",
      )
      release.countDown()

      val committed = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(42, committed.openRequestId)
      assertEquals(rom.toPath().toAbsolutePath().normalize(), committed.origin?.containerPath()?.orElseThrow())
    } finally {
      release.countDown()
      controller.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun matchingOpenCancellationPublishesTheSameRequestIdentity() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }
    val rom = namedRom("CANCEL_ID")
    val preparing = CountDownLatch(1)
    val neverRelease = CountDownLatch(1)
    val preparer =
        SessionPreparer { _, _ ->
          preparing.countDown()
          try {
            neverRelease.await()
          } catch (_: InterruptedException) {
            throw CancellationException("cancelled")
          }
          throw AssertionError("cancelled preparation unexpectedly resumed")
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, openRequestId = 84))
      assertTrue(preparing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(Controller.CancelRomOpenEvent(84))

      assertEquals(
          84,
          assertNotNull(cancelled.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).openRequestId,
      )
      assertNull(started.poll(250, TimeUnit.MILLISECONDS))
    } finally {
      controller.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun batteryFlushRequestCompletesAtTheControllerSafePointWithoutBlockingCaller() {
    val eventBus = EventBusImpl()
    val completions = LinkedBlockingQueue<Controller.BatteryFlushCompletedEvent>()
    eventBus.register<Controller.BatteryFlushCompletedEvent> { completions.add(it) }
    val controller = BasicController(eventBus, testProperties(), null)

    controller.startController()
    try {
      eventBus.post(Controller.FlushBatteryEvent(73))

      val completion = assertNotNull(completions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(73, completion.requestId)
      assertTrue(completion.succeeded)
      assertEquals(0, completion.filesWritten)
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun benchmarkBootstrapCompletesBeforeProfileAndStartedEvents() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val profile = LinkedBlockingQueue<HardwareProfileEvent>()
    val bootstrapCalls = AtomicInteger()
    val bootstrapReadyAtProfile = AtomicBoolean()
    eventBus.register<EmulationStartedEvent> {
      assertTrue(bootstrapReadyAtProfile.get())
      started.add(it)
    }
    eventBus.register<HardwareProfileEvent> {
      assertTrue(bootstrapCalls.get() > 0)
      bootstrapReadyAtProfile.set(true)
      profile.add(it)
    }
    val rom = namedRom("BENCHMARK_BOOTSTRAP")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.PERFORMANCE)
          val gameboy =
              object : Gameboy(config) {
                private var ready = false

                override fun isBootstrapReady(): Boolean = ready

                override fun runTicksUntilStop(ticks: Int, stop: BooleanSupplier): Int {
                  bootstrapCalls.incrementAndGet()
                  ready = true
                  return 1
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      assertNotNull(profile.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(1, bootstrapCalls.get())
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun fastForwardBootstrapCompletesBeforeProfileAndStartedEvents() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val profile = LinkedBlockingQueue<HardwareProfileEvent>()
    val bootstrapCalls = AtomicInteger()
    val bootstrapReadyAtProfile = AtomicBoolean()
    eventBus.register<EmulationStartedEvent> {
      assertTrue(bootstrapReadyAtProfile.get())
      started.add(it)
    }
    eventBus.register<HardwareProfileEvent> {
      assertTrue(bootstrapCalls.get() > 0)
      bootstrapReadyAtProfile.set(true)
      profile.add(it)
    }
    val rom = namedRom("FAST_FORWARD_BOOTSTRAP")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(bootstrapMode = BootstrapMode.FAST_FORWARD),
        )
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          val gameboy =
              object : Gameboy(config) {
                private var ready = false

                override fun isBootstrapReady(): Boolean = ready

                override fun runTicksUntilStop(ticks: Int, stop: BooleanSupplier): Int {
                  bootstrapCalls.incrementAndGet()
                  ready = true
                  return 1
                }
              }
          config.setBootstrapMode(BootstrapMode.FAST_FORWARD)
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      assertNotNull(profile.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(1, bootstrapCalls.get())
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkBoundaryCarriesPerformanceEpochTelemetry() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val acknowledged = LinkedBlockingQueue<Controller.BenchmarkArmAcknowledgedEvent>()
    val boundaries = LinkedBlockingQueue<Controller.BenchmarkFrameBoundaryEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.BenchmarkArmAcknowledgedEvent>(acknowledged::add)
    eventBus.register<Controller.BenchmarkFrameBoundaryEvent>(boundaries::add)
    val rom = namedRom("BENCHMARK_EPOCH")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.PERFORMANCE)
          val gameboy =
              object : Gameboy(config) {
                override fun getPerformanceBulkSpanCount() = 11L

                override fun getPerformanceBulkTicks() = 22L

                override fun getPerformanceEpochCount() = 33L

                override fun getPerformanceEpochTicks() = 44L

                override fun getPerformanceEpochMaxTicks() = 55

                override fun getPerformanceEpochRasterFastTicks() = 66L

                override fun getPerformanceEpochMode2ReplayTicks() = 77L

                override fun getPerformanceEpochMode2BulkTicks() = 70L

                override fun getPerformanceEpochLcdOffTicks() = 71L
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      val session = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val sessionGeneration = assertNotNull(session.sessionGeneration)
      val benchmarkGeneration = 83L
      val token = "stage8-epoch-token-0001"
      eventBus.post(
          Controller.BenchmarkArmEvent(benchmarkGeneration, token, sessionGeneration))
      assertNotNull(acknowledged.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(
          Controller.BenchmarkPhysicalFrameBoundaryEvent(600L, benchmarkGeneration))

      val boundary = assertNotNull(boundaries.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(11L, boundary.performanceBulkSpans)
      assertEquals(22L, boundary.performanceBulkTicks)
      assertEquals(33L, boundary.performanceEpochCount)
      assertEquals(44L, boundary.performanceEpochTicks)
      assertEquals(55, boundary.performanceEpochMaxTicks)
      assertEquals(66L, boundary.performanceEpochRasterFastTicks)
      assertEquals(77L, boundary.performanceEpochMode2ReplayTicks)
      assertEquals(70L, boundary.performanceEpochMode2BulkTicks)
      assertEquals(71L, boundary.performanceEpochLcdOffTicks)

      val fourArgument = Controller.BenchmarkFrameBoundaryEvent(600L, false, false, 1)
      val sixArgument =
          Controller.BenchmarkFrameBoundaryEvent(600L, false, false, 1, 5L, 6L)
      assertEquals(0L, fourArgument.performanceEpochCount)
      assertEquals(0L, sixArgument.performanceEpochTicks)
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkPreArmResumeReleasesTheAnchorPause() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val playback = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
    val workStarts = AtomicInteger()
    val workCompletions = AtomicInteger()
    val workAborts = AtomicInteger()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.SessionPlaybackStateEvent>(playback::add)
    eventBus.register<Controller.PerformanceWorkStartedEvent> { workStarts.incrementAndGet() }
    eventBus.register<Controller.PerformanceWorkCompletedEvent> { workCompletions.incrementAndGet() }
    eventBus.register<Controller.PerformanceWorkAbortedEvent> { workAborts.incrementAndGet() }
    val rom = namedRom("BENCHMARK_PRE_ARM_RESUME")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val controller =
        BasicController(
            eventBus,
            properties,
            null,
            SessionPreparer { currentProperties, event ->
              val config =
                  Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                      .setBootstrapMode(BootstrapMode.SKIP)
                      .setExecutionMode(ExecutionMode.PERFORMANCE)
              PreparedSession.Ready(config, Gameboy(config))
            })

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      Thread.sleep(50)
      assertEquals(0, workStarts.get(), "paused PERFORMANCE session published a work start")
      assertEquals(0, workCompletions.get(), "paused PERFORMANCE session published a work end")
      assertEquals(0, workAborts.get(), "paused PERFORMANCE session published a work abort")
      // The benchmark session starts paused so the host can capture its anchor. Before ARM the
      // normal lifecycle command must still release that pause.
      eventBus.post(Controller.ResumeEmulationEvent())
      val resumed =
          generateSequence { playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
              .first { !it.paused }
      assertFalse(resumed.paused)
      // Pause at the next safe point so the just-released ordinary batch has a deterministic
      // terminal edge: every full PERFORMANCE start must complete, never abort.
      eventBus.post(Controller.PauseEmulationEvent())
      val pausedAgain =
          generateSequence { playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
              .first { it.paused }
      assertTrue(pausedAgain.paused)
      assertTrue(workStarts.get() > 0)
      assertEquals(workStarts.get(), workCompletions.get())
      assertEquals(0, workAborts.get())
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkPhysicalBoundaryStopsMeasuredPerformanceBatchWithoutOneMoreTick() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val acknowledged = LinkedBlockingQueue<Controller.BenchmarkArmAcknowledgedEvent>()
    val boundary = LinkedBlockingQueue<Controller.BenchmarkFrameBoundaryEvent>()
    val workEvents = LinkedBlockingQueue<String>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.BenchmarkArmAcknowledgedEvent>(acknowledged::add)
    eventBus.register<Controller.BenchmarkFrameBoundaryEvent>(boundary::add)
    eventBus.register<Controller.PerformanceWorkStartedEvent> { workEvents.add("start") }
    eventBus.register<Controller.PerformanceWorkCompletedEvent> { workEvents.add("complete") }
    eventBus.register<Controller.PerformanceWorkAbortedEvent> { workEvents.add("abort") }
    val executedTicks = AtomicInteger()
    val boundaryPosted = AtomicInteger()
    val generation = AtomicLong()
    val rom = namedRom("BENCHMARK_MEASURED_STOP")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.PERFORMANCE)
          val gameboy =
              object : Gameboy(config) {
                override fun runMeasuredTicksUntilStop(
                    ticks: Int,
                    stop: BooleanSupplier,
                ): Int {
                  var executed = 0
                  while (executed < ticks && !stop.asBoolean) {
                    executed++
                    executedTicks.incrementAndGet()
                    if (executed == 5 && boundaryPosted.compareAndSet(0, 1)) {
                      eventBus.post(
                          Controller.BenchmarkPhysicalFrameBoundaryEvent(
                              600L,
                              generation.get(),
                          ))
                    }
                  }
                  return executed
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      val session = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val sessionGeneration = assertNotNull(session.sessionGeneration)
      val benchmarkGeneration = 97L
      generation.set(benchmarkGeneration)
      eventBus.post(
          Controller.BenchmarkArmEvent(
              benchmarkGeneration,
              "stage8-measured-stop-0001",
              sessionGeneration,
          ))
      assertNotNull(acknowledged.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(Controller.ResumeEmulationEvent())
      Thread.sleep(100)
      assertEquals(0, executedTicks.get(), "armed benchmark resumed before policy processing")
      eventBus.post(
          Controller.BenchmarkSilentPcmPolicyEvent(
              false,
              benchmarkGeneration,
              sessionGeneration,
          ))
      eventBus.post(Controller.ResumeEmulationEvent())

      assertNotNull(
          boundary.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "measured batch did not reach the synchronous frame boundary",
      )
      assertEquals("start", workEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("abort", workEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(5, executedTicks.get())
      Thread.sleep(100)
      assertEquals(5, executedTicks.get(), "a post-boundary measured tick was executed")
      assertNull(workEvents.poll(), "frozen measured gate published another work span")
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun accuracyExecutionDoesNotPublishPerformanceWorkSpans() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val workStarts = AtomicInteger()
    val workCompletions = AtomicInteger()
    val workAborts = AtomicInteger()
    val tickEntered = CountDownLatch(1)
    val releaseTick = CountDownLatch(1)
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.PerformanceWorkStartedEvent> { workStarts.incrementAndGet() }
    eventBus.register<Controller.PerformanceWorkCompletedEvent> { workCompletions.incrementAndGet() }
    eventBus.register<Controller.PerformanceWorkAbortedEvent> { workAborts.incrementAndGet() }
    val rom = namedRom("ACCURACY_NO_PERFORMANCE_WORK")
    val properties = testProperties()
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.ACCURACY)
          val gameboy =
              object : Gameboy(config) {
                private var blocked = false

                override fun tick(): Boolean {
                  if (!blocked) {
                    blocked = true
                    tickEntered.countDown()
                    releaseTick.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  }
                  return false
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(tickEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(0, workStarts.get())
      assertEquals(0, workCompletions.get())
      assertEquals(0, workAborts.get())
    } finally {
      releaseTick.countDown()
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkSystemAudioViolationFreezesRelaxedMeasuredBatchSynchronously() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val acknowledged = LinkedBlockingQueue<Controller.BenchmarkArmAcknowledgedEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.BenchmarkArmAcknowledgedEvent>(acknowledged::add)
    val executedTicks = AtomicInteger()
    val measuredReturned = CountDownLatch(1)
    val generation = 99L
    val sessionGenerationRef = AtomicLong()
    val gameboyRef = AtomicReference<Gameboy>()
    val rom = namedRom("BENCHMARK_SYSTEM_AUDIO_VIOLATION")
    val properties = EmulatorProperties(
        ApplicationSettingsOverrides(
            benchmarkPolicyEnabled = true,
            executionMode = ExecutionMode.PERFORMANCE,
        ))
    val preparer = SessionPreparer { currentProperties, event ->
      val config = Controller.createGameboyConfig(currentProperties, Rom(event.rom))
          .setBootstrapMode(BootstrapMode.SKIP)
          .setExecutionMode(ExecutionMode.PERFORMANCE)
      val gameboy = object : Gameboy(config) {
        override fun runMeasuredTicksUntilStop(ticks: Int, stop: BooleanSupplier): Int {
          var executed = 0
          while (executed < ticks && !stop.asBoolean) {
            executed++
            executedTicks.incrementAndGet()
            if (executed == 5) {
              eventBus.post(Controller.BenchmarkSystemAudioViolationEvent(
                  generation,
                  sessionGenerationRef.get(),
                  Controller.BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY,
              ))
            }
          }
          measuredReturned.countDown()
          return executed
        }
      }
      gameboyRef.set(gameboy)
      PreparedSession.Ready(config, gameboy)
    }
    val controller = BasicController(eventBus, properties, null, preparer)
    try {
      controller.startController()
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      val session = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val sessionGeneration = assertNotNull(session.sessionGeneration)
      sessionGenerationRef.set(sessionGeneration)
      // The relaxed token is selected at ARM and is immutable for this generation.
      eventBus.post(Controller.BenchmarkArmEvent(
          generation, "system-audio-violation-01", sessionGeneration,
          Controller.BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY))
      assertNotNull(acknowledged.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(Controller.BenchmarkSilentPcmPolicyEvent(
          true, generation, sessionGeneration, true,
          Controller.BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY))
      eventBus.post(Controller.ResumeEmulationEvent())
      assertTrue(measuredReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(5, executedTicks.get(), "mute violation allowed a measured tick tail")
      val sound = gameboyRef.get().sound
      assertEquals(Sound.PerformanceSystemMutedAudioMode.OFF,
          sound.getPerformanceSystemMutedAudioMode())
      assertFalse(sound.isPerformanceSystemMutedAudioCalendarEnabled())
      // A stale generation and a non-owner post cannot mutate the already frozen generation.
      eventBus.post(Controller.BenchmarkSystemAudioViolationEvent(
          generation + 1L, sessionGeneration,
          Controller.BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY))
      val nonOwner = Thread {
        eventBus.post(Controller.BenchmarkSystemAudioViolationEvent(
            generation, sessionGeneration,
            Controller.BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY))
      }
      nonOwner.start()
      nonOwner.join(TIMEOUT_SECONDS * 1_000L)
      assertEquals(5, executedTicks.get())
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkRejectedAudioPolicyFreezesRunningCoreBeforeOrdinaryBatch() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val acknowledged = LinkedBlockingQueue<Controller.BenchmarkArmAcknowledgedEvent>()
    val playback = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.BenchmarkArmAcknowledgedEvent>(acknowledged::add)
    eventBus.register<Controller.SessionPlaybackStateEvent>(playback::add)
    val measuredEntered = CountDownLatch(1)
    val releaseMeasured = CountDownLatch(1)
    val measuredCalls = AtomicInteger()
    val ordinaryBatches = AtomicInteger()
    val rom = namedRom("BENCHMARK_POLICY_REVOKE")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.PERFORMANCE)
          val gameboy =
              object : Gameboy(config) {
                override fun runMeasuredTicksUntilStop(
                    ticks: Int,
                    stop: BooleanSupplier,
                ): Int {
                  measuredCalls.incrementAndGet()
                  measuredEntered.countDown()
                  releaseMeasured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  return 1
                }

                override fun runTicks(ticks: Int): Int {
                  ordinaryBatches.incrementAndGet()
                  return 0
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      val session = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val sessionGeneration = assertNotNull(session.sessionGeneration)
      val benchmarkGeneration = 98L
      eventBus.post(
          Controller.BenchmarkArmEvent(
              benchmarkGeneration,
              "stage8-policy-revoke-0001",
              sessionGeneration,
          ))
      assertNotNull(acknowledged.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(
          Controller.BenchmarkSilentPcmPolicyEvent(
              false,
              benchmarkGeneration,
              sessionGeneration,
          ))
      eventBus.post(Controller.ResumeEmulationEvent())
      assertTrue(
          measuredEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "accepted benchmark policy did not start the measured batch",
      )

      playback.clear()
      eventBus.post(
          Controller.BenchmarkSilentPcmPolicyEvent(
              false,
              benchmarkGeneration,
              sessionGeneration,
              accepted = false,
          ))
      releaseMeasured.countDown()

      val paused = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(paused.paused, "revocation was not an owner-thread terminal pause")
      assertEquals(1, measuredCalls.get())
      assertEquals(
          0,
          ordinaryBatches.get(),
          "revocation fell through to an ordinary runTicks batch",
      )
    } finally {
      releaseMeasured.countDown()
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  @Test
  fun benchmarkScenarioEndpointStopsCurrentPerformanceBatchWithoutOneMoreTick() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val completed = LinkedBlockingQueue<Controller.BenchmarkGameplayScenarioCompletedEvent>()
    eventBus.register<EmulationStartedEvent>(started::add)
    eventBus.register<Controller.BenchmarkGameplayScenarioCompletedEvent>(completed::add)
    val executedTicks = AtomicInteger()
    val endpointSession = AtomicLong()
    val endpointPosted = AtomicInteger()
    val rom = namedRom("BENCHMARK_ENDPOINT")
    val properties =
        EmulatorProperties(
            ApplicationSettingsOverrides(
                benchmarkPolicyEnabled = true,
                executionMode = ExecutionMode.PERFORMANCE,
            ))
    val preparer =
        SessionPreparer { currentProperties, event ->
          val config =
              Controller.createGameboyConfig(currentProperties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
                  .setExecutionMode(ExecutionMode.PERFORMANCE)
          val gameboy =
              object : Gameboy(config) {
                override fun runTicksUntilStop(ticks: Int, stop: BooleanSupplier): Int {
                  var executed = 0
                  while (executed < ticks && !stop.asBoolean) {
                    executed++
                    executedTicks.incrementAndGet()
                    if (executed == 5 && endpointPosted.compareAndSet(0, 1)) {
                      eventBus.post(
                          Controller.BenchmarkGameplayScenarioEndpointEvent(
                              endpointSession.get(),
                              313,
                          ))
                    }
                  }
                  return executed
                }
              }
          PreparedSession.Ready(config, gameboy)
        }
    val controller = BasicController(eventBus, properties, null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom = rom, allowAutosaveResume = false))
      val session = assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val generation = assertNotNull(session.sessionGeneration)
      endpointSession.set(generation)
      eventBus.post(Controller.BenchmarkGameplayScenarioStartEvent(generation, 313))
      eventBus.post(Controller.ResumeEmulationEvent())

      val evidence =
          assertNotNull(
              completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
              "ticks=${executedTicks.get()} endpointPosts=${endpointPosted.get()}",
          )
      assertEquals(generation, evidence.sessionGeneration)
      assertEquals(313, evidence.completedFrames)
      assertEquals(313, evidence.expectedFrames)
      assertTrue(evidence.completed)
      assertEquals(5, executedTicks.get())
      Thread.sleep(100)
      assertEquals(5, executedTicks.get(), "paused controller executed a post-endpoint tick")
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      rom.delete()
    }
  }

  private fun namedRom(title: String): File {
    val bytes = ROM.readBytes()
    for (address in 0x0134 until 0x0143) {
      bytes[address] = 0
    }
    title.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x0134, endIndex = title.length.coerceAtMost(15))
    return Files.createTempFile("coffee-gb-$title", ".gbc").toFile().also { it.writeBytes(bytes) }
  }

  /** Controller-only tests must not wait for a desktop resume decision after an autosave. */
  private fun testProperties(): EmulatorProperties =
      EmulatorProperties().also { properties ->
        properties.updateSettings { current ->
          current.copy(
              saves =
                  current.saves.copy(
                      resumePolicy =
                          eu.rekawek.coffeegb.controller.properties.ApplicationSettings.ResumePolicy
                              .NEVER,
                  ))
        }
      }

  private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun awaitValue(value: AtomicInteger, expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (value.get() != expected && System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertEquals(expected, value.get())
  }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (!condition() && System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertTrue(condition())
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    const val TIMEOUT_SECONDS = 10L
    // These tests intentionally keep a previous physical writer alive. The retry performs a
    // real autosave, so it needs CI scheduling headroom once that writer is released.
    const val CLOSE_PERSISTENCE_TIMEOUT_MILLIS = 2_000L
  }

  private class ToggleFailWriter : AtomicFileWriter() {
    @Volatile var fail = false

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (fail) {
        throw IOException("injected snapshot replacement failure")
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class TrackingRewindManager : RewindManager() {
    val preparationCount = AtomicInteger()
    @Volatile var failPreparation = false
    @Volatile var sourceEventObservedAfterCommit = false

    override fun prepareSessionSeed(session: Session): PreparedSessionSeed? {
      preparationCount.incrementAndGet()
      if (failPreparation) throw IOException("injected rewind seed failure")
      return super.prepareSessionSeed(session)
    }
  }

  private class TrackingConsole : Console() {
    @Volatile var attachedDebugPort: DebugPort? = null

    override fun setDebugPort(debugPort: DebugPort?) {
      attachedDebugPort = debugPort
    }
  }

  private class StalledSessionEvent : Event

  private class FinalCloseEvent : Event

  private class PreparedSeedSourceEvent : Event
}
