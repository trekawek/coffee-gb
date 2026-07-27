package eu.rekawek.coffeegb.controller

import com.sun.management.ThreadMXBean
import eu.rekawek.coffeegb.controller.state.MachineSnapshot
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.state.ComponentState
import java.lang.management.ManagementFactory
import java.lang.reflect.Array as ReflectArray
import java.util.IdentityHashMap
import kotlin.system.measureNanoTime
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual, deterministic 300-entry rewind measurement.
 *
 * Run with:
 * `mvn -B -pl controller -am -Dtest=MachineSnapshotBenchmarkTest
 * -Dcoffeegb.rewind.benchmark=true test`
 *
 * Wall-clock and allocated-byte figures are observations, not CI thresholds. The retained-byte
 * model is deterministic: it counts aligned primitive arrays in the complete retained graph.
 */
class MachineSnapshotBenchmarkTest {

  @Test
  fun measureLegacyBaselineAndMachineSnapshots() {
    assumeTrue(java.lang.Boolean.getBoolean(BENCHMARK_PROPERTY))
    val rom =
        StateCodecTestSupport.rom(cgb = true).also {
          it[0x147] = 0x1b // MBC5 + RAM + battery
          it[0x149] = 0x03 // 32 KiB RAM
        }
    val baseline = measureLegacy(rom)
    println(
        "REWIND_BENCHMARK mode=legacy-baseline entries=${baseline.entries} " +
            "retainedPrimitiveBytes=${baseline.retainedPrimitiveBytes} " +
            "retainedPrimitiveArrays=${baseline.retainedPrimitiveArrays} " +
            "captureAllocatedBytes=${baseline.captureAllocatedBytes} " +
            "captureNanos=${baseline.captureNanos} restoreNanos=${baseline.restoreNanos}",
    )

    val snapshots = measureSnapshots(rom)
    println(
        "REWIND_BENCHMARK mode=machine-snapshot entries=${snapshots.entries} " +
            "retainedPrimitiveBytes=${snapshots.retainedPrimitiveBytes} " +
            "modeledRetainedBytes=${snapshots.modeledRetainedBytes} " +
            "uniquePages=${snapshots.uniquePages} uniqueValueNodes=${snapshots.uniqueValueNodes} " +
            "copiedPageBytes=${snapshots.copiedPageBytes} " +
            "identityVerifiedPayloadBytes=${snapshots.identityVerifiedPayloadBytes} " +
            "captureAllocatedBytes=${snapshots.captureAllocatedBytes} " +
            "captureNanos=${snapshots.captureNanos} restoreNanos=${snapshots.restoreNanos}",
    )
    check(snapshots.retainedPrimitiveBytes * 2 < baseline.retainedPrimitiveBytes) {
      "MachineSnapshot retained primitive bytes must be at least 50% below the baseline"
    }
    check(snapshots.identityVerifiedPayloadBytes > 0L) {
      "Incremental rewind capture must identity-verify source primitive payloads"
    }
  }

  private fun measureLegacy(rom: ByteArray): BenchmarkResult {
    StateCodecTestSupport.session(configuration(rom)).use { session ->
      val gameboy = session.gameboy
      gameboy.addressSpace.setByte(0x0000, 0x0a)
      val snapshots = ArrayList<ComponentState<Gameboy>>(RewindManager.CAPACITY)
      val allocation = threadAllocation()
      var allocatedBytes = 0L
      var captureNanos = 0L

      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL) { frame ->
        emulateProductionFrame(gameboy, frame)
        if (frame % RewindManager.RECORD_INTERVAL == 0) {
          val before = allocation?.current() ?: 0L
          captureNanos += measureNanoTime { snapshots += gameboy.captureState() }
          allocatedBytes += (allocation?.current() ?: before) - before
        }
      }

      val retained = PrimitiveArrayRetainedBytes.measure(snapshots)
      var restoreNanos = 0L
      snapshots.forEach { snapshot ->
        restoreNanos += measureNanoTime { gameboy.restoreState(snapshot) }
      }
      return BenchmarkResult(
          snapshots.size,
          retained.bytes,
          retained.arrays,
          retained.bytes,
          retained.arrays.toInt(),
          0,
          0,
          0,
          allocatedBytes,
          captureNanos,
          restoreNanos,
      )
    }
  }

  private fun measureSnapshots(rom: ByteArray): BenchmarkResult {
    StateCodecTestSupport.session(configuration(rom)).use { session ->
      val gameboy = session.gameboy
      gameboy.addressSpace.setByte(0x0000, 0x0a)
      val snapshots = ArrayList<MachineSnapshot>(RewindManager.CAPACITY)
      val allocation = threadAllocation()
      var allocatedBytes = 0L
      var captureNanos = 0L
      var copiedPageBytes = 0L
      var identityVerifiedPayloadBytes = 0L

      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL) { frame ->
        emulateProductionFrame(gameboy, frame)
        if (frame % RewindManager.RECORD_INTERVAL == 0) {
          val before = allocation?.current() ?: 0L
          captureNanos +=
              measureNanoTime {
                val snapshot = MachineSnapshot.capture(gameboy, snapshots.lastOrNull())
                copiedPageBytes += snapshot.captureStats.copiedPageBytes
                identityVerifiedPayloadBytes += snapshot.captureStats.identityVerifiedPayloadBytes
                snapshots += snapshot
              }
          allocatedBytes += (allocation?.current() ?: before) - before
        }
      }

      val retained = MachineSnapshot.retainedStats(snapshots)
      var restoreNanos = 0L
      snapshots.forEach { snapshot ->
        restoreNanos += measureNanoTime { snapshot.restore(gameboy) }
      }
      return BenchmarkResult(
          snapshots.size,
          retained.retainedPrimitiveBytes,
          retained.uniquePages.toLong(),
          retained.modeledRetainedBytes,
          retained.uniquePages,
          retained.uniqueValueNodes,
          copiedPageBytes,
          identityVerifiedPayloadBytes,
          allocatedBytes,
          captureNanos,
          restoreNanos,
      )
    }
  }

  private fun configuration(rom: ByteArray) =
      StateCodecTestSupport.configuration(rom, GameboyType.CGB).setSupportBatterySave(false)

  private fun emulateProductionFrame(
      gameboy: Gameboy,
      frame: Int,
  ) {
    repeat(Gameboy.TICKS_PER_FRAME) { gameboy.tick() }
    val value = frame and 0xff
    gameboy.addressSpace.setByte(0xc000 + (frame * 37 and 0xfff), value)
    gameboy.addressSpace.setByte(0xd000 + (frame * 53 and 0xfff), value xor 0x5a)
    gameboy.gpu.videoRam0.setByte(0x8000 + (frame * 29 and 0x1fff), value xor 0xa5)
    gameboy.gpu.videoRam1.setByte(0x8000 + (frame * 31 and 0x1fff), value xor 0x3c)
    gameboy.addressSpace.setByte(0xfe00 + (frame % 0xa0), value)
    gameboy.addressSpace.setByte(0x4000, frame ushr 5 and 0x03)
    gameboy.addressSpace.setByte(0xa000 + (frame * 43 and 0x1fff), value xor 0xc3)
  }

  private fun threadAllocation(): AllocationCounter? {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return null
    if (!bean.isThreadAllocatedMemorySupported) return null
    if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
    return AllocationCounter(bean, Thread.currentThread().id)
  }

  private data class AllocationCounter(
      val bean: ThreadMXBean,
      val threadId: Long,
  ) {
    fun current(): Long = bean.getThreadAllocatedBytes(threadId)
  }

  private object PrimitiveArrayRetainedBytes {
    fun measure(root: Any): Retained {
      val seen = IdentityHashMap<Any, Boolean>()
      var bytes = 0L
      var arrays = 0L

      fun visit(value: Any?) {
        if (value == null || value.javaClass.isEnum || value is String || value is Number || value is Boolean) {
          return
        }
        if (seen.put(value, true) != null) return
        val type = value.javaClass
        when {
          type.isArray -> {
            val component = type.componentType
            val length = ReflectArray.getLength(value)
            if (component.isPrimitive) {
              val width =
                  when (component) {
                    java.lang.Byte.TYPE, java.lang.Boolean.TYPE -> 1
                    java.lang.Short.TYPE, java.lang.Character.TYPE -> 2
                    java.lang.Integer.TYPE, java.lang.Float.TYPE -> 4
                    java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
                    else -> error("Unknown primitive $component")
                  }
              bytes += align(ARRAY_HEADER_BYTES + length.toLong() * width)
              arrays++
            } else {
              repeat(length) { visit(ReflectArray.get(value, it)) }
            }
          }
          value is Iterable<*> -> value.forEach(::visit)
          value is Map<*, *> -> value.forEach { (key, item) -> visit(key); visit(item) }
          type.isRecord ->
              type.recordComponents.forEach { component ->
                component.accessor.trySetAccessible()
                visit(component.accessor.invoke(value))
              }
        }
      }

      visit(root)
      return Retained(bytes, arrays)
    }

    private fun align(value: Long): Long = (value + 7) and -8L
  }

  private data class Retained(val bytes: Long, val arrays: Long)

  private data class BenchmarkResult(
      val entries: Int,
      val retainedPrimitiveBytes: Long,
      val retainedPrimitiveArrays: Long,
      val modeledRetainedBytes: Long,
      val uniquePages: Int,
      val uniqueValueNodes: Int,
      val copiedPageBytes: Long,
      val identityVerifiedPayloadBytes: Long,
      val captureAllocatedBytes: Long,
      val captureNanos: Long,
      val restoreNanos: Long,
  )

  companion object {
    private const val BENCHMARK_PROPERTY = "coffeegb.rewind.benchmark"
    private const val ARRAY_HEADER_BYTES = 16L
  }
}
