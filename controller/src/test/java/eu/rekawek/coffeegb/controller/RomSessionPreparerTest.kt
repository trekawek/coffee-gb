package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.BatteryStore
import eu.rekawek.coffeegb.controller.state.FileStateStore
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore
import eu.rekawek.coffeegb.controller.state.SessionPersistence
import eu.rekawek.coffeegb.controller.state.StateStorageLayout
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class RomSessionPreparerTest {

  @Test
  fun reusesExactBootStateForRepeatedRom() {
    val cache = BootStateCache(2)
    val preparer = RomSessionPreparer(cache)

    val first =
        assertIs<PreparedSession.FromBootState>(
            preparer.prepare(FAST_FORWARD_PROPERTIES, LoadRomEvent(ROM)))
    val second =
        assertIs<PreparedSession.FromBootState>(
            preparer.prepare(FAST_FORWARD_PROPERTIES, LoadRomEvent(ROM)))

    assertSame(first.bootState, second.bootState)
    assertEquals(1, cache.size)
    assertEquals(1, cache.hitCount)

    val restored = second.materialize()
    try {
      assertEquals(0x0100, restored.cpu.registers.pc)
    } finally {
      restored.discardUnstarted()
    }
  }

  @Test
  fun suppliedDetachedStateSkipsBootAndRestoresDirectly() {
    val config =
        Controller.createGameboyConfig(PROPERTIES, Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)
    val source = config.build()
    source.addressSpace.setByte(0xc123, 0x5a)
    val state = DetachedStateAdapter.capture(source)
    source.discardUnstarted()

    val cache = BootStateCache(2)
    val prepared =
        assertIs<PreparedSession.FromDetachedState>(
            RomSessionPreparer(cache, runtimeWarmupCache = noopWarmupCache())
                .prepare(PROPERTIES, LoadRomEvent(ROM, state)))
    val restored = prepared.materialize()
    try {
      assertEquals(0x5a, restored.addressSpace.getByte(0xc123))
      assertEquals(0, cache.size)
    } finally {
      restored.discardUnstarted()
    }
  }

  @Test
  fun exactArchiveImagePreservesSelectedEntryAcrossControllerContract() {
    val container = Files.createTempFile("coffee-gb-selected-entry", ".zip")
    try {
      val bytes = ROM.readBytes()
      val origin = RomOrigin.archiveEntry(container, "nested/selected.GBC", false)
      val image = RomImage(origin, bytes)

      val prepared =
          RomSessionPreparer(BootStateCache(2), runtimeWarmupCache = noopWarmupCache())
              .prepare(PROPERTIES, LoadRomEvent(image))

      assertEquals(origin, prepared.config.rom.origin)
      assertEquals("nested/selected.GBC", prepared.config.rom.origin.archiveEntry().orElseThrow())
      assertEquals(bytes.toList(), prepared.config.rom.image.bytes().toList())
    } finally {
      Files.deleteIfExists(container)
    }
  }

  @Test
  fun pathlessRomUsesItsProvidedHostPersistenceStore() {
    val root = Files.createTempDirectory("coffee-gb-pathless-store")
    try {
      val image = RomImage.memory(ROM.readBytes(), "picked.gb")
      lateinit var stateStore: FileStateStore
      val store =
          RomPersistenceStore { _, hashes ->
            val layout = StateStorageLayout(root.resolve("games").resolve(hashes.primaryRom.hex()))
            val battery =
                BatteryStorage(
                    BatteryStorage.Source.managed(layout.batteryFile, root),
                    emptyList(),
                )
            stateStore = FileStateStore(layout)
            SessionPersistence(
                stateStore,
                BatteryStore { battery },
                null,
            )
          }

      val prepared =
          RomSessionPreparer(BootStateCache(2), runtimeWarmupCache = noopWarmupCache()).prepare(
              PROPERTIES,
              LoadRomEvent(image, persistenceStore = store),
          )

      assertNull(prepared.config.rom.file)
      assertSame(stateStore, prepared.stateStore)
      assertTrue(prepared.config.batteryStorage.targetPath().startsWith(root))
      assertTrue(prepared.config.batteryStorage.targetPath().fileName.toString() == "battery.sav")
    } finally {
      Files.walk(root).use { paths ->
        paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  @Test
  fun skipLoadWarmsExactlyOneHundredTwentyControllerFramesBeforeDeferring() {
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(2, executor)

    val prepared =
        RomSessionPreparer(BootStateCache(2), runtimeWarmupCache = cache)
            .prepare(PROPERTIES, LoadRomEvent(ROM))

    assertIs<PreparedSession.Deferred>(prepared)
    val call = assertIs<WarmupCall>(executor.calls.single())
    assertEquals(
        120 * prepared.config.clockSpec.controllerTicksPerFrame(),
        call.ticks,
    )
    assertEquals(BootstrapMode.SKIP, call.config.bootstrapMode)
  }

  @Test
  fun explicitRuntimeWarmupOverrideSkipsDisposableRun() {
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(2, executor)
    val properties = EmulatorProperties(ApplicationSettingsOverrides(runtimeWarmupEnabled = false))

    try {
      val prepared =
          RomSessionPreparer(BootStateCache(2), runtimeWarmupCache = cache)
              .prepare(properties, LoadRomEvent(ROM))

      assertIs<PreparedSession.Deferred>(prepared)
      assertTrue(executor.calls.isEmpty())
      assertEquals(0, cache.size)
    } finally {
      properties.close()
    }
  }

  @Test
  fun runtimeWarmupOnlyAcceptsFreshOrdinarySkipCartridges() {
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(8, executor)

    cache.warm(skipConfig()) {}
    cache.warm(skipConfig().setBootstrapMode(BootstrapMode.FAST_FORWARD)) {}
    cache.warm(skipConfig().setBootstrapMode(BootstrapMode.NORMAL)) {}
    cache.warm(skipConfig().setSlotRom(Rom(ROM))) {}
    cache.warm(skipConfig(exoticRtcImage())) {}
    cache.warm(skipConfig(nonStandardMapperImage())) {}

    assertEquals(1, executor.calls.size)
    assertEquals(1, cache.size)
  }

  @Test
  fun runtimeWarmupSharesExecutionShapeButSeparatesProfileMapperAndRamShapes() {
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(8, executor)

    cache.warm(skipConfig()) {}
    cache.warm(skipConfig(RomImage.memory(alteredRomBytes(), "same-shape.gb"))) {}
    cache.warm(skipConfig().setHardwareProfile(HardwareProfileRegistry.DMG)) {}
    cache.warm(skipConfig(mbc5Image())) {}
    cache.warm(skipConfig(RomImage.memory(largerRamBytes(), "larger-ram.gb"))) {}
    cache.warm(skipConfig().setMealybugDmgBlob(true)) {}
    cache.warm(skipConfig().setCodeBreakerRumble(true)) {}

    assertEquals(6, executor.calls.size)
    assertEquals(6, cache.size)
  }

  @Test
  fun runtimeWarmupUsesAccessOrderedBoundedCache() {
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(2, executor)
    val first = skipConfig()
    val second = skipConfig(mbc5Image())
    val third = skipConfig(RomImage.memory(largerRamBytes(), "larger-ram.gb"))

    cache.warm(first) {}
    cache.warm(second) {}
    cache.warm(first) {}
    cache.warm(third) {}
    cache.warm(second) {}

    assertEquals(4, executor.calls.size)
    assertEquals(2, cache.size)
    assertEquals(1, cache.hitCount)
  }

  @Test
  fun canceledOrFailedWarmupIsNotCachedAndDoesNotRejectTheRealLoad() {
    val canceled = RecordingWarmupExecutor(checkCancellation = true)
    val canceledCache = RuntimeWarmupCache(2, canceled)
    var cancellationChecks = 0

    assertFailsWith<CancellationException> {
      canceledCache.warm(skipConfig()) {
        if (++cancellationChecks == 2) {
          throw CancellationException("superseded")
        }
      }
    }
    assertEquals(0, canceledCache.size)
    canceled.checkCancellation = false
    canceledCache.warm(skipConfig()) {}
    assertEquals(2, canceled.calls.size)
    assertEquals(1, canceledCache.size)

    val canceledAfterRun = RecordingWarmupExecutor()
    val afterRunCache = RuntimeWarmupCache(2, canceledAfterRun)
    var afterRunChecks = 0
    assertFailsWith<CancellationException> {
      afterRunCache.warm(skipConfig()) {
        if (++afterRunChecks == 2) {
          throw CancellationException("superseded after warmup")
        }
      }
    }
    assertEquals(1, canceledAfterRun.calls.size)
    assertEquals(0, afterRunCache.size)

    val failed = RecordingWarmupExecutor(failure = IllegalStateException("throwaway failed"))
    val failedCache = RuntimeWarmupCache(2, failed)
    val preparer = RomSessionPreparer(BootStateCache(2), runtimeWarmupCache = failedCache)

    assertIs<PreparedSession.Deferred>(preparer.prepare(PROPERTIES, LoadRomEvent(ROM)))
    assertEquals(0, failedCache.size)
    failed.failure = null
    assertIs<PreparedSession.Deferred>(preparer.prepare(PROPERTIES, LoadRomEvent(ROM)))
    assertEquals(2, failed.calls.size)
    assertEquals(1, failedCache.size)
  }

  @Test
  fun concurrentSameShapeLoadWaitsForOneSuccessfulWarmup() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    val cache =
        RuntimeWarmupCache(
            2,
            RuntimeWarmupExecutor { _, _, _ ->
              calls.incrementAndGet()
              started.countDown()
              check(release.await(3, TimeUnit.SECONDS)) { "Timed out waiting to release warmup" }
            },
        )
    val owner = Thread { runWarmup(cache, failure) }
    val waiter = Thread { runWarmup(cache, failure) }

    owner.start()
    assertTrue(started.await(3, TimeUnit.SECONDS))
    waiter.start()
    release.countDown()
    owner.join(3_000)
    waiter.join(3_000)

    assertFalse(owner.isAlive)
    assertFalse(waiter.isAlive)
    assertNull(failure.get())
    assertEquals(1, calls.get())
    assertEquals(1, cache.size)
    assertEquals(1, cache.hitCount)
  }

  @Test
  fun runtimeWarmupUsesAServiceFreeCopyWithoutChangingThePreparedSession() {
    val liveSamples = AtomicInteger()
    val liveInput = PlayerInputSource {
      liveSamples.incrementAndGet()
      PlayerInputSource.RELEASED.sample()
    }
    val executor = RecordingWarmupExecutor()
    val cache = RuntimeWarmupCache(2, executor)
    val preparer =
        RomSessionPreparer(
            BootStateCache(2),
            configure = {
              it.setPlayerInputSource(liveInput)
                  .setBatteryData(byteArrayOf(0x5a))
                  .setSupportBatterySave(true)
            },
            runtimeWarmupCache = cache,
        )

    val prepared =
        assertIs<PreparedSession.Deferred>(preparer.prepare(PROPERTIES, LoadRomEvent(ROM)))
    val warmup = executor.calls.single().config

    assertEquals(BootstrapMode.SKIP, warmup.bootstrapMode)
    assertFalse(warmup.isSupportBatterySave)
    assertNull(warmup.batteryStorage)
    assertNull(warmup.slotBatteryStorage)
    assertIs<PlayerInputHub>(warmup.playerInputSource)
    assertNotSame(liveInput, warmup.playerInputSource)
    assertSame(liveInput, prepared.config.playerInputSource)
    assertTrue(prepared.config.isSupportBatterySave)
    assertEquals(BootstrapMode.SKIP, prepared.config.bootstrapMode)
    assertEquals(0, liveSamples.get())
  }

  private fun skipConfig(image: RomImage? = null): Gameboy.GameboyConfiguration =
      Controller.createGameboyConfig(PROPERTIES, image?.let(::Rom) ?: Rom(ROM))

  private fun exoticRtcImage(): RomImage =
      RomImage.memory(ROM.readBytes().also { it[0x147] = 0x0f }, "rtc.gb")

  private fun mbc5Image(): RomImage = RomImage.memory(mbc5Bytes(), "mbc5.gb")

  private fun mbc5Bytes(): ByteArray = ROM.readBytes().also { it[0x147] = 0x19 }

  private fun nonStandardMapperImage(): RomImage =
      RomImage.memory(ROM.readBytes().also { it[0x147] = 0x00 }, "oversized-rom.gb")

  private fun largerRamBytes(): ByteArray = ROM.readBytes().also { it[0x149] = 0x03 }

  private fun alteredRomBytes(): ByteArray =
      ROM.readBytes().also { it[0x200] = (it[0x200] + 1).toByte() }

  private fun noopWarmupCache(): RuntimeWarmupCache =
      RuntimeWarmupCache(1, RuntimeWarmupExecutor { _, _, _ -> })

  private fun runWarmup(cache: RuntimeWarmupCache, failure: AtomicReference<Throwable?>) {
    try {
      cache.warm(skipConfig()) {}
    } catch (error: Throwable) {
      failure.compareAndSet(null, error)
    }
  }

  private data class WarmupCall(val config: Gameboy.GameboyConfiguration, val ticks: Int)

  private class RecordingWarmupExecutor(
      var failure: Throwable? = null,
      var checkCancellation: Boolean = false,
  ) : RuntimeWarmupExecutor {
    val calls = mutableListOf<WarmupCall>()

    override fun warm(
        config: Gameboy.GameboyConfiguration,
        ticks: Int,
        ensureActive: () -> Unit,
    ) {
      calls += WarmupCall(config, ticks)
      if (checkCancellation) {
        ensureActive()
      }
      failure?.let { throw it }
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val PROPERTIES = EmulatorProperties()

    val FAST_FORWARD_PROPERTIES =
        EmulatorProperties().also {
          it.properties[EmulatorProperties.Key.BootstrapMode.propertyName] =
              BootstrapMode.FAST_FORWARD.name
        }
  }
}
