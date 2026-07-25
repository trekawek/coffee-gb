package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.gpu.DmgPixelFifo
import eu.rekawek.coffeegb.core.gpu.Gpu
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.GenericArrayType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * Immutable, service-free, in-process machine snapshot.
 *
 * This type is internal by design and has no byte encoding. It is captured/restored only by the
 * owning emulation thread at the documented frame safe point. Primitive arrays are split into
 * fixed 4 KiB payload pages. An incremental capture compares against the preceding snapshot and
 * copies only changed pages; equal pages retain the same immutable page identity. No array owned
 * by a page is exposed.
 *
 * The transitional capture source remains the complete audited memento graph so Phase 6 can
 * retire that plumbing independently. The memento is discarded after this immutable graph is
 * built and never enters rewind history, disk, or network state.
 */
internal class MachineSnapshot private constructor(
    private val root: SnapshotRecord,
    private val rtcRuntime: SnapshotRtcRuntime,
    private val hardware: GameboyType,
    private val dmgFifoRuntime: SnapshotDmgFifoRuntime?,
    internal val captureStats: CaptureStats,
) {

  /**
   * Reconstructs and validates the complete trusted internal candidate before mutation, then
   * retains the old raw machine/runtime state for unexpected live-restore rollback.
   */
  fun restore(
      gameboy: Gameboy,
      probe: ((ApplyStage) -> Unit)? = null,
  ) {
    if (gameboy.gameboyType != hardware) {
      throw StateApplyException(
          "Internal $hardware snapshot does not match ${gameboy.gameboyType} hardware")
    }
    val rollbackMemento = gameboy.saveToMemento()
    if (SnapshotGraph.ownershipSignature(root) !=
        SnapshotGraph.ownershipSignature(rollbackMemento)) {
      throw StateApplyException("Internal snapshot mapper/battery ownership does not match target")
    }
    val candidate =
        try {
          @Suppress("UNCHECKED_CAST")
          (SnapshotGraph.restoreRoot(root) as Memento<Gameboy>).also(StateSemantics::validate)
        } catch (failure: StateApplyException) {
          throw failure
        } catch (failure: Throwable) {
          throw StateApplyException("Internal machine snapshot could not be reconstructed", failure)
        }
    val candidateRtc = rtcRuntime.toCore()
    val candidateFifo = dmgFifoRuntime?.toCore()
    try {
      gameboy.validateRtcRuntimeState(candidateRtc)
      gameboy.validateDmgFifoRuntimeState(candidateFifo)
    } catch (failure: IllegalArgumentException) {
      throw StateApplyException("Internal machine runtime layout is incompatible", failure)
    }
    val rollbackRtc = gameboy.captureRtcRuntimeState()
    val rollbackFifo = gameboy.captureDmgFifoRuntimeState()
    try {
      probe?.invoke(ApplyStage.BEFORE_LIVE_MUTATION)
      gameboy.restoreFromMemento(candidate)
      probe?.invoke(ApplyStage.AFTER_MACHINE_MUTATION)
      gameboy.restoreDmgFifoRuntimeState(candidateFifo)
      gameboy.restoreRtcRuntimeState(candidateRtc)
    } catch (failure: Throwable) {
      try {
        gameboy.restoreFromMemento(rollbackMemento)
        gameboy.restoreDmgFifoRuntimeState(rollbackFifo)
        gameboy.restoreRtcRuntimeState(rollbackRtc)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Internal machine snapshot could not be applied atomically", failure)
    }
  }

  /**
   * Read-only structural-sharing probe for tests and the benchmark. Tokens are page objects, never
   * their private primitive arrays.
   */
  internal fun debugArrays(
      ownerClassName: String,
      fieldName: String,
  ): List<ArrayDebug> {
    val result = ArrayList<ArrayDebug>()
    root.visitRecords { record ->
      if (SnapshotGraph.recordClassName(record.typeId) == ownerClassName) {
        record.fields
            .filter { it.name == fieldName }
            .mapNotNull { it.value as? SnapshotPrimitiveArray }
            .forEach { array ->
              result +=
                  ArrayDebug(
                      array.size,
                      Collections.unmodifiableList(array.pages.map { it as Any }),
                  )
            }
      }
    }
    return Collections.unmodifiableList(result)
  }

  internal data class ArrayDebug(val size: Int, val pageTokens: List<Any>)

  internal data class CaptureStats(
      val copiedPages: Int,
      val copiedPageBytes: Long,
      val reusedPages: Int,
      val newValueNodes: Int,
  )

  internal data class RetainedStats(
      val snapshots: Int,
      val uniquePages: Int,
      val uniqueValueNodes: Int,
      val retainedPrimitiveBytes: Long,
      val modeledRetainedBytes: Long,
  )

  companion object {
    const val PAGE_BYTES = 4 * 1024

    fun capture(
        gameboy: Gameboy,
        previous: MachineSnapshot? = null,
    ): MachineSnapshot {
      val compatiblePrevious = previous?.takeIf { it.hardware == gameboy.gameboyType }
      val graph = SnapshotGraph.capture(gameboy.saveToMemento(), compatiblePrevious?.root)
      val rtc = gameboy.captureRtcRuntimeState()
      val fifo = gameboy.captureDmgFifoRuntimeState()
      return MachineSnapshot(
          graph.root,
          SnapshotRtcRuntime(
              rtc.primary()?.let { SnapshotMbc3Runtime(it.emulationPaused(), it.pauseStartedMillis()) },
              rtc.slot()?.let { SnapshotMbc3Runtime(it.emulationPaused(), it.pauseStartedMillis()) },
          ),
          gameboy.gameboyType,
          fifo?.let {
            SnapshotDmgFifoRuntime(
                it.timing().toSnapshot(),
                it.output().toSnapshot(),
            )
          },
          graph.stats,
      )
    }

    fun retainedStats(snapshots: Collection<MachineSnapshot>): RetainedStats {
      val pages = IdentityHashMap<SnapshotPage, Boolean>()
      val nodes = IdentityHashMap<SnapshotValue, Boolean>()
      var primitiveBytes = 0L
      var modeledBytes = 0L
      snapshots.forEach { snapshot ->
        modeledBytes += MACHINE_SNAPSHOT_SHALLOW_BYTES
        snapshot.root.visit { value ->
          if (nodes.put(value, true) == null) {
            modeledBytes += value.modeledBytes()
          }
          if (value is SnapshotPrimitiveArray) {
            value.pages.forEach { page ->
              if (pages.put(page, true) == null) {
                val retained = page.retainedBytes()
                primitiveBytes += retained
                modeledBytes += retained
              }
            }
          }
        }
      }
      return RetainedStats(
          snapshots.size,
          pages.size,
          nodes.size,
          primitiveBytes,
          modeledBytes,
      )
    }

    private fun eu.rekawek.coffeegb.core.gpu.DmgPixelFifo.RuntimeState.toSnapshot() =
        SnapshotDmgPixelRuntime(
            linePixels(),
            outCount(),
            firstEntry(),
            firstBgp(),
            firstObp0(),
            firstObp1(),
        )

    private const val MACHINE_SNAPSHOT_SHALLOW_BYTES = 40L
  }
}

private data class SnapshotMbc3Runtime(
    val emulationPaused: Boolean,
    val pauseStartedMillis: Long,
)

private data class SnapshotRtcRuntime(
    val primary: SnapshotMbc3Runtime?,
    val slot: SnapshotMbc3Runtime?,
) {
  fun toCore() =
      Gameboy.RtcRuntimeState(
          primary?.let { RealTimeClock.RuntimeState(it.emulationPaused, it.pauseStartedMillis) },
          slot?.let { RealTimeClock.RuntimeState(it.emulationPaused, it.pauseStartedMillis) },
      )
}

private data class SnapshotDmgPixelRuntime(
    val linePixels: Int,
    val outCount: Int,
    val firstEntry: Int,
    val firstBgp: Int,
    val firstObp0: Int,
    val firstObp1: Int,
) {
  fun toCore() =
      DmgPixelFifo.RuntimeState(
          linePixels,
          outCount,
          firstEntry,
          firstBgp,
          firstObp0,
          firstObp1,
      )
}

private data class SnapshotDmgFifoRuntime(
    val timing: SnapshotDmgPixelRuntime,
    val output: SnapshotDmgPixelRuntime,
) {
  fun toCore() = Gpu.DmgFifoRuntimeState(timing.toCore(), output.toCore())
}

private sealed interface SnapshotValue {
  fun modeledBytes(): Long
}

private data object SnapshotNull : SnapshotValue {
  override fun modeledBytes(): Long = 0
}

private data class SnapshotInt(val value: Int) : SnapshotValue {
  override fun modeledBytes(): Long = 16
}

private data class SnapshotLong(val value: Long) : SnapshotValue {
  override fun modeledBytes(): Long = 24
}

private data class SnapshotBoolean(val value: Boolean) : SnapshotValue {
  override fun modeledBytes(): Long = 16
}

private data class SnapshotDoubleBits(val bits: Long) : SnapshotValue {
  override fun modeledBytes(): Long = 24
}

private data class SnapshotString(val value: String) : SnapshotValue {
  override fun modeledBytes(): Long = align(24L + value.length * 2L)
}

private data class SnapshotEnum(val typeId: Int, val ordinal: Int) : SnapshotValue {
  override fun modeledBytes(): Long = 24
}

private data class SnapshotField(val name: String, val value: SnapshotValue)

private class SnapshotRecord(
    val typeId: Int,
    fields: Collection<SnapshotField>,
) : SnapshotValue {
  val fields: List<SnapshotField> = Collections.unmodifiableList(ArrayList(fields))

  override fun modeledBytes(): Long = align(24L + fields.size * 24L)

  fun visitRecords(visitor: (SnapshotRecord) -> Unit) {
    visit { if (it is SnapshotRecord) visitor(it) }
  }

  fun visit(visitor: (SnapshotValue) -> Unit) {
    val seen = IdentityHashMap<SnapshotValue, Boolean>()
    fun traverse(value: SnapshotValue) {
      if (seen.put(value, true) != null) return
      visitor(value)
      when (value) {
        is SnapshotRecord -> value.fields.forEach { traverse(it.value) }
        is SnapshotValues -> value.values.forEach(::traverse)
        is SnapshotIntMap -> value.entries.forEach { traverse(it.value) }
        else -> Unit
      }
    }
    traverse(this)
  }
}

private sealed class SnapshotValues(
    values: Collection<SnapshotValue>,
) : SnapshotValue {
  val values: List<SnapshotValue> = Collections.unmodifiableList(ArrayList(values))
  override fun modeledBytes(): Long = align(24L + values.size * 8L)
}

private class SnapshotObjectArray(values: Collection<SnapshotValue>) : SnapshotValues(values)

private class SnapshotList(values: Collection<SnapshotValue>) : SnapshotValues(values)

private data class SnapshotMapEntry(val key: Int, val value: SnapshotValue)

private class SnapshotIntMap(
    entries: Collection<SnapshotMapEntry>,
) : SnapshotValue {
  val entries: List<SnapshotMapEntry> =
      Collections.unmodifiableList(ArrayList(entries).also { it.sortBy(SnapshotMapEntry::key) })

  override fun modeledBytes(): Long = align(24L + entries.size * 24L)
}

private sealed class SnapshotPrimitiveArray(
    val size: Int,
    pages: Collection<SnapshotPage>,
) : SnapshotValue {
  val pages: List<SnapshotPage> = Collections.unmodifiableList(ArrayList(pages))
  override fun modeledBytes(): Long = align(24L + pages.size * 8L)
  abstract fun materialize(): Any
}

private class SnapshotBytes(size: Int, pages: Collection<SnapshotPage>) :
    SnapshotPrimitiveArray(size, pages) {
  override fun materialize(): ByteArray {
    val result = ByteArray(size)
    pages.forEachIndexed { index, page -> page.copyTo(result, index * bytePageElements(1)) }
    return result
  }
}

private class SnapshotInts(size: Int, pages: Collection<SnapshotPage>) :
    SnapshotPrimitiveArray(size, pages) {
  override fun materialize(): IntArray {
    val result = IntArray(size)
    pages.forEachIndexed { index, page -> page.copyTo(result, index * bytePageElements(4)) }
    return result
  }
}

private class SnapshotLongs(size: Int, pages: Collection<SnapshotPage>) :
    SnapshotPrimitiveArray(size, pages) {
  override fun materialize(): LongArray {
    val result = LongArray(size)
    pages.forEachIndexed { index, page -> page.copyTo(result, index * bytePageElements(8)) }
    return result
  }
}

private class SnapshotBooleans(size: Int, pages: Collection<SnapshotPage>) :
    SnapshotPrimitiveArray(size, pages) {
  override fun materialize(): BooleanArray {
    val result = BooleanArray(size)
    pages.forEachIndexed { index, page -> page.copyTo(result, index * bytePageElements(1)) }
    return result
  }
}

private enum class PageKind(val width: Int) {
  BYTE(1),
  INT(4),
  LONG(8),
  BOOLEAN(1),
}

private data class PageKey(val kind: PageKind, val length: Int, val hash: Int)

private sealed class SnapshotPage(
    val kind: PageKind,
    val length: Int,
    val hash: Int,
) {
  abstract fun matches(source: Any, offset: Int): Boolean
  abstract fun copyTo(target: Any, offset: Int)
  fun retainedBytes(): Long = align(16L + length.toLong() * kind.width)
}

private class BytePage(private val data: ByteArray, hash: Int) :
    SnapshotPage(PageKind.BYTE, data.size, hash) {
  override fun matches(source: Any, offset: Int): Boolean {
    source as ByteArray
    return data.indices.all { data[it] == source[offset + it] }
  }

  override fun copyTo(target: Any, offset: Int) {
    data.copyInto(target as ByteArray, offset)
  }
}

private class IntPage(private val data: IntArray, hash: Int) :
    SnapshotPage(PageKind.INT, data.size, hash) {
  override fun matches(source: Any, offset: Int): Boolean {
    source as IntArray
    return data.indices.all { data[it] == source[offset + it] }
  }

  override fun copyTo(target: Any, offset: Int) {
    data.copyInto(target as IntArray, offset)
  }
}

private class LongPage(private val data: LongArray, hash: Int) :
    SnapshotPage(PageKind.LONG, data.size, hash) {
  override fun matches(source: Any, offset: Int): Boolean {
    source as LongArray
    return data.indices.all { data[it] == source[offset + it] }
  }

  override fun copyTo(target: Any, offset: Int) {
    data.copyInto(target as LongArray, offset)
  }
}

private class BooleanPage(private val data: BooleanArray, hash: Int) :
    SnapshotPage(PageKind.BOOLEAN, data.size, hash) {
  override fun matches(source: Any, offset: Int): Boolean {
    source as BooleanArray
    return data.indices.all { data[it] == source[offset + it] }
  }

  override fun copyTo(target: Any, offset: Int) {
    data.copyInto(target as BooleanArray, offset)
  }
}

private class SnapshotPagePool(previous: SnapshotRecord?) {
  private val pages = HashMap<PageKey, MutableList<SnapshotPage>>()

  init {
    previous?.visit { value ->
      if (value is SnapshotPrimitiveArray) value.pages.forEach(::add)
    }
  }

  fun reuse(
      kind: PageKind,
      source: Any,
      offset: Int,
      length: Int,
      hash: Int,
      preferred: SnapshotPage?,
  ): SnapshotPage? {
    if (preferred != null &&
        preferred.kind == kind &&
        preferred.length == length &&
        preferred.hash == hash &&
        preferred.matches(source, offset)) {
      return preferred
    }
    return pages[PageKey(kind, length, hash)]?.firstOrNull { it.matches(source, offset) }
  }

  fun add(page: SnapshotPage) {
    val bucket = pages.getOrPut(PageKey(page.kind, page.length, page.hash), ::ArrayList)
    if (bucket.none { it === page }) bucket += page
  }
}

private object SnapshotGraph {
  private val recordIds by lazy {
    MementoTypeRegistry.recordClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }
  private val enumIds by lazy {
    MementoTypeRegistry.enumClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }

  data class Result(
      val root: SnapshotRecord,
      val stats: MachineSnapshot.CaptureStats,
  )

  fun capture(value: Any, previous: SnapshotRecord?): Result {
    val capture = Capture(previous)
    val root = capture.value(value, previous, 0) as? SnapshotRecord
        ?: error("Machine snapshot root is not a record")
    if (recordClassName(root.typeId) != GAMEBOY_ROOT) {
      error("Machine snapshot has the wrong root type")
    }
    return Result(root, capture.stats())
  }

  fun recordClassName(typeId: Int): String =
      MementoTypeRegistry.recordClassNames.getOrNull(typeId - 1)
          ?: error("Unknown snapshot record type ID $typeId")

  fun restoreRoot(root: SnapshotRecord): Any = Restore().value(root, null, 0)
      ?: throw StateApplyException("Internal machine snapshot root is null")

  fun ownershipSignature(root: SnapshotRecord): List<Int> {
    val signature = ArrayList<Int>()
    root.visitRecords { record ->
      if (isOwnershipRecord(recordClassName(record.typeId))) signature += record.typeId
    }
    return signature
  }

  fun ownershipSignature(root: Any): List<Int> {
    val signature = ArrayList<Int>()
    fun visit(value: Any?) {
      if (value == null) return
      when {
        value.javaClass.isRecord -> {
          val typeId = recordIds[value.javaClass]
              ?: throw StateApplyException("Unregistered internal record ${value.javaClass.name}")
          if (isOwnershipRecord(value.javaClass.name)) signature += typeId
          value.javaClass.recordComponents.forEach { component ->
            component.accessor.trySetAccessible()
            visit(component.accessor.invoke(value))
          }
        }
        value.javaClass.isArray && !value.javaClass.componentType.isPrimitive ->
            repeat(ReflectArray.getLength(value)) { visit(ReflectArray.get(value, it)) }
        value is Iterable<*> -> value.forEach(::visit)
        value is Map<*, *> ->
            value.entries.sortedBy { (it.key as? Int) ?: Int.MIN_VALUE }.forEach {
              visit(it.key)
              visit(it.value)
            }
      }
    }
    visit(root)
    return signature
  }

  private fun isOwnershipRecord(name: String): Boolean =
      name.startsWith("eu.rekawek.coffeegb.core.memory.cart.type.") ||
          name.startsWith("eu.rekawek.coffeegb.core.memory.cart.battery.")

  private class Restore {
    private var references = 0L

    fun value(
        value: SnapshotValue,
        expectedType: Type?,
        depth: Int,
    ): Any? {
      if (depth > StateLimits.LEGACY_MAX_DEPTH) {
        throw StateApplyException("Internal machine snapshot is too deep")
      }
      countValue()
      if (value === SnapshotNull) {
        if (expectedType.rawClass()?.isPrimitive == true) {
          throw StateApplyException("Null primitive in internal machine snapshot")
        }
        return null
      }
      val restored =
          when (value) {
            is SnapshotInt -> value.value
            is SnapshotLong -> value.value
            is SnapshotBoolean -> value.value
            is SnapshotDoubleBits -> Double.fromBits(value.bits)
            is SnapshotString -> value.value
            is SnapshotEnum -> restoreEnum(value)
            is SnapshotRecord -> restoreRecord(value, depth)
            is SnapshotPrimitiveArray -> value.materialize()
            is SnapshotObjectArray -> restoreArray(value, expectedType, depth)
            is SnapshotList -> restoreList(value, expectedType, depth)
            is SnapshotIntMap -> restoreMap(value, expectedType, depth)
            SnapshotNull -> null
          }
      requireExpected(restored, expectedType)
      return restored
    }

    private fun restoreEnum(value: SnapshotEnum): Any {
      val type =
          MementoTypeRegistry.enumClasses.getOrNull(value.typeId - 1)
              ?: throw StateApplyException("Unknown internal enum type ID ${value.typeId}")
      return type.enumConstants.getOrNull(value.ordinal)
          ?: throw StateApplyException("Invalid ${type.name} ordinal ${value.ordinal}")
    }

    private fun restoreRecord(
        value: SnapshotRecord,
        depth: Int,
    ): Any {
      val type =
          MementoTypeRegistry.recordClasses.getOrNull(value.typeId - 1)
              ?: throw StateApplyException("Unknown internal record type ID ${value.typeId}")
      val components = type.recordComponents
      if (components.size != value.fields.size ||
          components.indices.any { components[it].name != value.fields[it].name }) {
        throw StateApplyException("Invalid internal ${type.name} field inventory")
      }
      val arguments =
          components.indices
              .map { index ->
                this.value(value.fields[index].value, components[index].genericType, depth + 1)
              }
              .toTypedArray()
      val constructor = type.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
      constructor.trySetAccessible()
      try {
        return constructor.newInstance(*arguments)
      } catch (failure: InvocationTargetException) {
        throw StateApplyException("Invalid internal ${type.name} value", failure.targetException)
      } catch (failure: ReflectiveOperationException) {
        throw StateApplyException("Internal record ${type.name} could not be constructed", failure)
      }
    }

    private fun restoreArray(
        value: SnapshotObjectArray,
        expectedType: Type?,
        depth: Int,
    ): Any {
      val type = expectedType.rawClass()
      if (type?.isArray != true || type.componentType.isPrimitive) {
        throw StateApplyException("Unexpected internal object array")
      }
      return ReflectArray.newInstance(type.componentType, value.values.size).also { result ->
        value.values.forEachIndexed { index, item ->
          ReflectArray.set(result, index, this.value(item, type.componentType, depth + 1))
        }
      }
    }

    private fun restoreList(
        value: SnapshotList,
        expectedType: Type?,
        depth: Int,
    ): List<Any?> {
      val elementType = expectedType.typeArguments()?.singleOrNull()
      return ArrayList<Any?>(value.values.size).also { result ->
        value.values.forEach { result += this.value(it, elementType, depth + 1) }
      }
    }

    private fun restoreMap(
        value: SnapshotIntMap,
        expectedType: Type?,
        depth: Int,
    ): Map<Int, Any?> {
      val types = expectedType.typeArguments()
      val keyType = types?.getOrNull(0) ?: Int::class.javaObjectType
      if (keyType.rawClass() != Int::class.javaObjectType) {
        throw StateApplyException("Internal snapshot maps require integer keys")
      }
      val itemType = types?.getOrNull(1)
      return LinkedHashMap<Int, Any?>(value.entries.size).also { result ->
        value.entries.forEach { entry ->
          result[entry.key] = this.value(entry.value, itemType, depth + 1)
        }
      }
    }

    private fun requireExpected(value: Any?, expectedType: Type?) {
      if (value == null || expectedType == null) return
      val expected = expectedType.rawClass() ?: return
      val boxed =
          when (expected) {
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> expected
          }
      if (!boxed.isInstance(value)) {
        throw StateApplyException(
            "Expected internal ${expected.name}, received ${value.javaClass.name}")
      }
    }

    private fun countValue() {
      references++
      if (references > StateLimits.LEGACY_MAX_REFERENCES) {
        throw StateApplyException("Internal machine snapshot has too many values")
      }
    }
  }

  private class Capture(previous: SnapshotRecord?) {
    private val pool = SnapshotPagePool(previous)
    private var references = 0L
    private var copiedPages = 0
    private var copiedPageBytes = 0L
    private var reusedPages = 0
    private var newValueNodes = 0

    fun stats() =
        MachineSnapshot.CaptureStats(
            copiedPages,
            copiedPageBytes,
            reusedPages,
            newValueNodes,
        )

    fun value(
        source: Any?,
        previous: SnapshotValue?,
        depth: Int,
    ): SnapshotValue {
      check(depth <= StateLimits.LEGACY_MAX_DEPTH) { "Machine snapshot graph is too deep" }
      countValue()
      if (source == null) return SnapshotNull
      return when (source) {
        is Int -> reuseScalar(previous, SnapshotInt(source))
        is Long -> reuseScalar(previous, SnapshotLong(source))
        is Boolean -> reuseScalar(previous, SnapshotBoolean(source))
        is Double -> reuseScalar(previous, SnapshotDoubleBits(source.toBits()))
        is String -> reuseScalar(previous, SnapshotString(source))
        is ByteArray -> captureBytes(source, previous as? SnapshotBytes)
        is IntArray -> captureInts(source, previous as? SnapshotInts)
        is LongArray -> captureLongs(source, previous as? SnapshotLongs)
        is BooleanArray -> captureBooleans(source, previous as? SnapshotBooleans)
        is List<*> -> captureValues(source, previous as? SnapshotList, depth)
        is Map<*, *> -> captureMap(source, previous as? SnapshotIntMap, depth)
        is Enum<*> -> {
          val id =
              enumIds[source.javaClass]
                  ?: error("Unregistered snapshot enum ${source.javaClass.name}")
          reuseScalar(previous, SnapshotEnum(id, source.ordinal))
        }
        else ->
            if (source.javaClass.isArray) {
              captureObjectArray(source, previous as? SnapshotObjectArray, depth)
            } else {
              captureRecord(source, previous as? SnapshotRecord, depth)
            }
      }
    }

    private fun reuseScalar(previous: SnapshotValue?, candidate: SnapshotValue): SnapshotValue =
        if (previous == candidate) previous else candidate.also { newValueNodes++ }

    private fun captureBytes(source: ByteArray, previous: SnapshotBytes?): SnapshotValue =
        capturePrimitive(
            source.size,
            PageKind.BYTE,
            previous,
            { offset, length -> hash(source, offset, length) },
            { offset, length, hash -> BytePage(source.copyOfRange(offset, offset + length), hash) },
            { size, pages -> SnapshotBytes(size, pages) },
            source,
        )

    private fun captureInts(source: IntArray, previous: SnapshotInts?): SnapshotValue =
        capturePrimitive(
            source.size,
            PageKind.INT,
            previous,
            { offset, length -> hash(source, offset, length) },
            { offset, length, hash -> IntPage(source.copyOfRange(offset, offset + length), hash) },
            { size, pages -> SnapshotInts(size, pages) },
            source,
        )

    private fun captureLongs(source: LongArray, previous: SnapshotLongs?): SnapshotValue =
        capturePrimitive(
            source.size,
            PageKind.LONG,
            previous,
            { offset, length -> hash(source, offset, length) },
            { offset, length, hash -> LongPage(source.copyOfRange(offset, offset + length), hash) },
            { size, pages -> SnapshotLongs(size, pages) },
            source,
        )

    private fun captureBooleans(source: BooleanArray, previous: SnapshotBooleans?): SnapshotValue =
        capturePrimitive(
            source.size,
            PageKind.BOOLEAN,
            previous,
            { offset, length -> hash(source, offset, length) },
            { offset, length, hash ->
              BooleanPage(source.copyOfRange(offset, offset + length), hash)
            },
            { size, pages -> SnapshotBooleans(size, pages) },
            source,
        )

    private fun capturePrimitive(
        size: Int,
        kind: PageKind,
        previous: SnapshotPrimitiveArray?,
        pageHash: (Int, Int) -> Int,
        create: (Int, Int, Int) -> SnapshotPage,
        array: (Int, List<SnapshotPage>) -> SnapshotPrimitiveArray,
        source: Any,
    ): SnapshotValue {
      val pageElements = bytePageElements(kind.width)
      val pageCount = if (size == 0) 0 else (size - 1) / pageElements + 1
      val result = ArrayList<SnapshotPage>(pageCount)
      repeat(pageCount) { index ->
        val offset = index * pageElements
        val length = min(pageElements, size - offset)
        val hash = pageHash(offset, length)
        val reused =
            pool.reuse(
                kind,
                source,
                offset,
                length,
                hash,
                previous?.takeIf { it.size == size }?.pages?.getOrNull(index),
            )
        val page =
            reused
                ?: create(offset, length, hash).also {
                  copiedPages++
                  copiedPageBytes += length.toLong() * kind.width
                }
        if (reused != null) reusedPages++
        pool.add(page)
        result += page
      }
      if (previous != null &&
          previous.size == size &&
          previous.pages.size == result.size &&
          result.indices.all { result[it] === previous.pages[it] }) {
        return previous
      }
      newValueNodes++
      return array(size, result)
    }

    private fun captureObjectArray(
        source: Any,
        previous: SnapshotObjectArray?,
        depth: Int,
    ): SnapshotValue {
      val size = ReflectArray.getLength(source)
      val values =
          List(size) { index ->
            value(ReflectArray.get(source, index), previous?.values?.getOrNull(index), depth + 1)
          }
      if (previous != null &&
          previous.values.size == size &&
          values.indices.all { values[it] === previous.values[it] }) {
        return previous
      }
      newValueNodes++
      return SnapshotObjectArray(values)
    }

    private fun captureValues(
        source: List<*>,
        previous: SnapshotList?,
        depth: Int,
    ): SnapshotValue {
      val values =
          source.mapIndexed { index, item ->
            value(item, previous?.values?.getOrNull(index), depth + 1)
          }
      if (previous != null &&
          previous.values.size == values.size &&
          values.indices.all { values[it] === previous.values[it] }) {
        return previous
      }
      newValueNodes++
      return SnapshotList(values)
    }

    private fun captureMap(
        source: Map<*, *>,
        previous: SnapshotIntMap?,
        depth: Int,
    ): SnapshotValue {
      val sorted =
          source.entries
              .map { entry ->
                val key = entry.key as? Int ?: error("Snapshot map key is not an Int")
                key to entry.value
              }
              .sortedBy { it.first }
      val entries =
          sorted.mapIndexed { index, (key, item) ->
            val old = previous?.entries?.getOrNull(index)?.takeIf { it.key == key }
            SnapshotMapEntry(key, value(item, old?.value, depth + 1))
          }
      if (previous != null &&
          previous.entries.size == entries.size &&
          entries.indices.all {
            entries[it].key == previous.entries[it].key &&
                entries[it].value === previous.entries[it].value
          }) {
        return previous
      }
      newValueNodes++
      return SnapshotIntMap(entries)
    }

    private fun captureRecord(
        source: Any,
        previous: SnapshotRecord?,
        depth: Int,
    ): SnapshotValue {
      val type = source.javaClass
      val typeId = recordIds[type] ?: error("Unregistered snapshot record ${type.name}")
      val sameType = previous?.takeIf { it.typeId == typeId }
      val fields =
          type.recordComponents.mapIndexed { index, component ->
            component.accessor.trySetAccessible()
            val old =
                sameType
                    ?.fields
                    ?.getOrNull(index)
                    ?.takeIf { it.name == component.name }
                    ?.value
            SnapshotField(component.name, value(component.accessor.invoke(source), old, depth + 1))
          }
      if (sameType != null &&
          sameType.fields.size == fields.size &&
          fields.indices.all {
            fields[it].name == sameType.fields[it].name &&
                fields[it].value === sameType.fields[it].value
          }) {
        return sameType
      }
      newValueNodes++
      return SnapshotRecord(typeId, fields)
    }

    private fun countValue() {
      references++
      check(references <= StateLimits.LEGACY_MAX_REFERENCES) {
        "Machine snapshot graph has too many values"
      }
    }
  }

  private const val GAMEBOY_ROOT = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"
}

private fun hash(
    source: ByteArray,
    offset: Int,
    length: Int,
): Int {
  var result = 1
  repeat(length) { result = 31 * result + source[offset + it] }
  return result
}

private fun hash(
    source: IntArray,
    offset: Int,
    length: Int,
): Int {
  var result = 1
  repeat(length) { result = 31 * result + source[offset + it] }
  return result
}

private fun hash(
    source: LongArray,
    offset: Int,
    length: Int,
): Int {
  var result = 1
  repeat(length) {
    val value = source[offset + it]
    result = 31 * result + (value xor (value ushr 32)).toInt()
  }
  return result
}

private fun hash(
    source: BooleanArray,
    offset: Int,
    length: Int,
): Int {
  var result = 1
  repeat(length) { result = 31 * result + if (source[offset + it]) 1231 else 1237 }
  return result
}

private fun bytePageElements(width: Int): Int = MachineSnapshot.PAGE_BYTES / width

private fun align(value: Long): Long = (value + 7) and -8L

private fun Type?.rawClass(): Class<*>? =
    when (this) {
      is Class<*> -> this
      is ParameterizedType -> rawType as? Class<*>
      is GenericArrayType ->
          genericComponentType.rawClass()?.let { ReflectArray.newInstance(it, 0).javaClass }
      is WildcardType -> upperBounds.singleOrNull().rawClass()
      else -> null
    }

private fun Type?.typeArguments(): Array<Type>? =
    (this as? ParameterizedType)?.actualTypeArguments
