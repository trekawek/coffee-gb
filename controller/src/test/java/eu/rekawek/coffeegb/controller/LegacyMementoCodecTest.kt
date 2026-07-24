package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.GameGeniePatch
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InvalidClassException
import java.io.InvalidObjectException
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.ObjectStreamConstants
import java.io.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.ArrayList
import java.util.HashMap
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LegacyMementoCodecTest {

  @Test
  fun knownGameboyMementoRoundTripsThroughAllowlist() {
    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      eventBus.post(AddPatches(listOf(GameGeniePatch(0x42, 0x1234, 0x24))))
      repeat(100) { gameboy.tick() }
      val serialized = gameboy.saveToMemento().serialize()
      val restored = serialized.deserializeToGameboyMemento()

      gameboy.restoreFromMemento(restored)
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun unrelatedSerializableClassIsRejected() {
    val serialized = rawSerialize(java.io.File("not-a-memento"))

    assertFailsWith<InvalidClassException> { serialized.deserializeToGameboyMemento() }
  }

  @Test
  fun applicationClassOutsideTheMementoAllowlistIsRejected() {
    val serialized = rawSerialize(UnexpectedMemento("do not instantiate arbitrary state"))

    assertFailsWith<InvalidClassException> { serialized.deserializeToGameboyMemento() }
  }

  @Test
  fun alteredAllowedClassShapeIsRejected() {
    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      val serialized = gameboy.saveToMemento().serialize()
      val field = "biosShadowMemento".toByteArray()
      val index = serialized.indexOf(field)
      assertTrue(index >= 0, "fixture should contain the root record descriptor")
      val altered = serialized.clone()
      altered[index] = 'x'.code.toByte()

      assertFailsWith<InvalidClassException> { altered.deserializeToGameboyMemento() }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun proxyMementoIsRejected() {
    @Suppress("UNCHECKED_CAST")
    val proxy =
        Proxy.newProxyInstance(
            Memento::class.java.classLoader,
            arrayOf(Memento::class.java),
            SerializableHandler(),
        ) as Memento<Gameboy>
    val serialized = LegacyMementoCodec.serializeGameboy(proxy)

    assertFailsWith<InvalidClassException> { serialized.deserializeToGameboyMemento() }
  }

  @Test
  fun wrongMementoRootTypeIsRejected() {
    val serialized = rawSerialize(ArrayList<Any>())

    assertFailsWith<InvalidObjectException> { serialized.deserializeToGameboyMemento() }
  }

  @Test
  fun oversizedLegacyByteStreamIsRejectedBeforeParsing() {
    val serialized = ByteArray(StateLimits.GAME_SNAPSHOT.decodedBytes + 1)
    serialized[0] = 0xac.toByte()
    serialized[1] = 0xed.toByte()
    serialized[2] = 0x00
    serialized[3] = 0x05

    val error =
        assertFailsWith<InvalidObjectException> { serialized.deserializeToGameboyMemento() }
    assertTrue(error.message!!.contains("exceeds"))
  }

  @Test
  fun oversizedLegacyArrayIsRejectedByGraphFilter() {
    val battery = MemoryBattery(ByteArray(StateLimits.LEGACY_MAX_ARRAY_LENGTH.toInt() + 1))
    val serialized = rawSerialize(battery.saveToMemento())

    assertFailsWith<IOException> { serialized.deserializeToGameboyMemento() }
  }

  @Test
  fun truncatedAllocationDeclarationsAreRejectedBeforeNativeDeserialization() {
    val longArray = rawSerialize(LongArray(0))
    putInt(
        longArray,
        longArray.size - Int.SIZE_BYTES,
        (StateLimits.LEGACY_MAX_ARRAY_BYTES / Long.SIZE_BYTES + 1).toInt(),
    )
    val arrayError =
        assertFailsWith<InvalidObjectException> { longArray.deserializeToGameboyMemento() }
    assertTrue(arrayError.message!!.contains("allocation bytes"))

    val longString =
        ByteBuffer.allocate(4 + 1 + Long.SIZE_BYTES)
            .putInt(0xaced0005.toInt())
            .put(0x7c)
            .putLong(StateLimits.LEGACY_MAX_STRING_BYTES + 1)
            .array()
    val stringError =
        assertFailsWith<InvalidObjectException> { longString.deserializeToGameboyMemento() }
    assertTrue(stringError.message!!.contains("encoded bytes"))

    val list = rawSerialize(ArrayList<Any>())
    val listBlock = list.indexOf(byteArrayOf(0x77, 0x04))
    assertTrue(listBlock >= 4)
    putInt(list, listBlock - Int.SIZE_BYTES, StateLimits.LEGACY_MAX_COLLECTION_ENTRIES + 1)
    val listError = assertFailsWith<InvalidObjectException> { list.deserializeToGameboyMemento() }
    assertTrue(listError.message!!.contains("ArrayList"))

    val map = rawSerialize(HashMap<Any, Any>())
    val mapBlock = map.indexOf(byteArrayOf(0x77, 0x08))
    assertTrue(mapBlock >= 0)
    putInt(map, mapBlock + 2 + Int.SIZE_BYTES, StateLimits.LEGACY_MAX_COLLECTION_ENTRIES + 1)
    val mapError = assertFailsWith<InvalidObjectException> { map.deserializeToGameboyMemento() }
    assertTrue(mapError.message!!.contains("HashMap"))
  }

  @Test
  fun preflightAcceptsExactContentDepthAndRejectsBoundaryPlusOne() {
    fun nestedArrays(depth: Int, leaf: Any? = null): Any {
      var nested: Any? = leaf
      repeat(depth) { nested = arrayOf(nested) }
      return checkNotNull(nested)
    }

    LegacySerializationPreflight.validate(
        rawSerialize(nestedArrays(StateLimits.LEGACY_MAX_DEPTH.toInt())),
        StateLimits.GAME_SNAPSHOT.decodedBytes,
    )
    assertFailsWith<InvalidObjectException> {
      LegacySerializationPreflight.validate(
          rawSerialize(nestedArrays(StateLimits.LEGACY_MAX_DEPTH.toInt() + 1)),
          StateLimits.GAME_SNAPSHOT.decodedBytes,
      )
    }

    val mementoType = MemoryBattery(ByteArray(0)).saveToMemento().javaClass
    val constructor = mementoType.declaredConstructors.single().also { it.isAccessible = true }
    val nullBufferMemento = constructor.newInstance(null)
    LegacySerializationPreflight.validate(
        rawSerialize(
            nestedArrays(
                StateLimits.LEGACY_MAX_DEPTH.toInt() - 1,
                nullBufferMemento,
            )),
        StateLimits.GAME_SNAPSHOT.decodedBytes,
    )
  }

  @Test
  fun preflightCountsCumulativeHandlesAcrossResets() {
    val excessiveHandles =
        rawStream {
          repeat(StateLimits.LEGACY_MAX_REFERENCES.toInt() + 1) { index ->
            writeByte(ObjectStreamConstants.TC_STRING.toInt())
            writeShort(0)
            if (index % 1_000 == 999) writeByte(ObjectStreamConstants.TC_RESET.toInt())
          }
        }
    val error =
        assertFailsWith<InvalidObjectException> {
          LegacySerializationPreflight.validate(
              excessiveHandles,
              StateLimits.GAME_SNAPSHOT.decodedBytes,
          )
        }
    assertTrue(error.message!!.contains("references"))
  }

  @Test
  fun preflightRejectsCyclicAndMismatchedClassDescriptors() {
    val byteArrayUid = ObjectStreamClass.lookup(ByteArray::class.java).serialVersionUID
    val cyclic =
        rawStream {
          writeByte(ObjectStreamConstants.TC_ARRAY.toInt())
          writeByte(ObjectStreamConstants.TC_CLASSDESC.toInt())
          writeUTF("[B")
          writeLong(byteArrayUid)
          writeByte(ObjectStreamConstants.SC_SERIALIZABLE.toInt())
          writeShort(0)
          writeByte(ObjectStreamConstants.TC_ENDBLOCKDATA.toInt())
          writeByte(ObjectStreamConstants.TC_REFERENCE.toInt())
          writeInt(ObjectStreamConstants.baseWireHandle)
        }
    val cycleError =
        assertFailsWith<InvalidClassException> {
          LegacySerializationPreflight.validate(cyclic, StateLimits.GAME_SNAPSHOT.decodedBytes)
        }
    assertTrue(cycleError.message!!.contains("Cyclic"))

    for (fieldCount in listOf(1, UShort.MAX_VALUE.toInt())) {
      val mismatched =
          rawStream {
            writeByte(ObjectStreamConstants.TC_ARRAY.toInt())
            writeByte(ObjectStreamConstants.TC_CLASSDESC.toInt())
            writeUTF("[B")
            writeLong(byteArrayUid)
            writeByte(ObjectStreamConstants.SC_SERIALIZABLE.toInt())
            writeShort(fieldCount)
          }
      val shapeError =
          assertFailsWith<InvalidClassException> {
            LegacySerializationPreflight.validate(
                mismatched,
                StateLimits.GAME_SNAPSHOT.decodedBytes,
            )
          }
      assertTrue(shapeError.message!!.contains("field count"))
    }
  }

  @Test
  fun preflightRejectsDecodedStringsPastLimitBeforeStringAllocation() {
    val overlong =
        rawStream {
          writeByte(ObjectStreamConstants.TC_LONGSTRING.toInt())
          writeLong(StateLimits.LEGACY_MAX_STRING_CHARS.toLong() + 1)
          repeat(StateLimits.LEGACY_MAX_STRING_CHARS + 1) { writeByte('x'.code) }
        }

    val error =
        assertFailsWith<InvalidObjectException> {
          LegacySerializationPreflight.validate(overlong, StateLimits.GAME_SNAPSHOT.decodedBytes)
        }
    assertTrue(error.message!!.contains("characters"))
  }

  @Test
  fun hashMapTableAndLogicalSizeBoundariesAreAccepted() {
    val map = HashMap<Int, Int>(StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES)
    repeat(StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) { map[it] = it }
    val serialized = rawSerialize(map)
    LegacySerializationPreflight.validate(serialized, StateLimits.GAME_SNAPSHOT.decodedBytes)

    val mapBlock = serialized.indexOf(byteArrayOf(0x77, 0x08))
    assertTrue(mapBlock >= 0)
    putInt(
        serialized,
        mapBlock + 2,
        StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES + 1,
    )
    val error =
        assertFailsWith<InvalidObjectException> {
          LegacySerializationPreflight.validate(serialized, StateLimits.GAME_SNAPSHOT.decodedBytes)
        }
    assertTrue(error.message!!.contains("capacity"))
  }

  @Test
  fun onlyAuditedArrayShapesAndCumulativeBytesAreAccepted() {
    assertEquals(
        StateLimits.LEGACY_MAX_ARRAY_BYTES,
        LegacyArrayShapes.allocationBytes(
            "[J",
            (StateLimits.LEGACY_MAX_ARRAY_BYTES / Long.SIZE_BYTES).toInt(),
        ),
    )
    assertFailsWith<InvalidObjectException> {
      LegacyArrayShapes.allocationBytes(
          "[J",
          (StateLimits.LEGACY_MAX_ARRAY_BYTES / Long.SIZE_BYTES + 1).toInt(),
      )
    }

    val boundary = cumulativeByteArrayStream(StateLimits.LEGACY_MAX_ARRAY_BYTES)
    LegacySerializationPreflight.validate(boundary, Int.MAX_VALUE)

    val boundaryPlusOne =
        ByteArrayOutputStream(boundary.size + 16).also { output ->
          output.write(boundary)
          DataOutputStream(output).use { stream ->
            stream.writeByte(ObjectStreamConstants.TC_ARRAY.toInt())
            stream.writeByte(ObjectStreamConstants.TC_REFERENCE.toInt())
            stream.writeInt(ObjectStreamConstants.baseWireHandle)
            stream.writeInt(1)
            stream.writeByte(0)
          }
        }.toByteArray()
    val error =
        assertFailsWith<InvalidObjectException> {
          LegacySerializationPreflight.validate(boundaryPlusOne, Int.MAX_VALUE)
        }
    assertTrue(error.message!!.contains("allocation bytes"))
    assertFailsWith<InvalidClassException> {
      rawSerialize(DoubleArray(0)).deserializeToGameboyMemento()
    }
    assertEquals(
        StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES.toLong() * Long.SIZE_BYTES,
        LegacyArrayShapes.allocationBytes(
            "[Ljava.util.Map\$Entry;",
            StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES,
        ),
    )
    assertFailsWith<InvalidObjectException> {
      LegacyArrayShapes.allocationBytes(
          "[Ljava.util.Map\$Entry;",
          StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES + 1,
      )
    }
  }

  @Test
  fun graphLimitsAcceptExactBoundariesAndRejectPlusOneAndOverflow() {
    fun allowed(
        depth: Long = 0,
        references: Long = 0,
        streamBytes: Long = 0,
        arrayLength: Long = -1,
        objectArray: Boolean = false,
    ) =
        LegacyMementoCodec.isWithinGraphLimits(
            depth,
            references,
            streamBytes,
            arrayLength,
            objectArray,
            StateLimits.GAME_SNAPSHOT.decodedBytes,
        )

    assertTrue(allowed(depth = StateLimits.LEGACY_MAX_DEPTH))
    assertTrue(allowed(references = StateLimits.LEGACY_MAX_REFERENCES))
    assertTrue(allowed(streamBytes = StateLimits.GAME_SNAPSHOT.decodedBytes.toLong()))
    assertTrue(allowed(arrayLength = StateLimits.LEGACY_MAX_ARRAY_LENGTH))
    assertTrue(
        allowed(
            arrayLength = StateLimits.LEGACY_MAX_COLLECTION_ENTRIES.toLong(),
            objectArray = true,
        ))

    assertFalse(allowed(depth = StateLimits.LEGACY_MAX_DEPTH + 1))
    assertFalse(allowed(references = StateLimits.LEGACY_MAX_REFERENCES + 1))
    assertFalse(allowed(streamBytes = StateLimits.GAME_SNAPSHOT.decodedBytes.toLong() + 1))
    assertFalse(allowed(arrayLength = StateLimits.LEGACY_MAX_ARRAY_LENGTH + 1))
    assertFalse(
        allowed(
            arrayLength = StateLimits.LEGACY_MAX_COLLECTION_ENTRIES.toLong() + 1,
            objectArray = true,
        ))
    assertFalse(allowed(depth = Long.MAX_VALUE))
    assertFalse(allowed(references = Long.MAX_VALUE))
    assertFalse(allowed(streamBytes = Long.MAX_VALUE))
    assertFalse(allowed(arrayLength = Long.MAX_VALUE))
  }

  @Test
  fun legacyCollectionLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() {
    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    val patch = GameGeniePatch(0x42, 0x1234, 0x24)
    try {
      eventBus.post(AddPatches(List(StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) { patch }))
      gameboy.saveToMemento().serialize().deserializeToGameboyMemento()

      eventBus.post(AddPatches(listOf(patch)))
      val oversized = gameboy.saveToMemento().serialize()
      assertFailsWith<IOException> { oversized.deserializeToGameboyMemento() }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun legacyStringLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() {
    fun longString(length: Int) =
        rawStream {
          writeByte(ObjectStreamConstants.TC_LONGSTRING.toInt())
          writeLong(length.toLong())
          repeat(length) { writeByte('x'.code) }
        }

    LegacySerializationPreflight.validate(
        longString(StateLimits.LEGACY_MAX_STRING_CHARS),
        StateLimits.GAME_SNAPSHOT.decodedBytes,
    )
    assertFailsWith<InvalidObjectException> {
      LegacySerializationPreflight.validate(
          longString(StateLimits.LEGACY_MAX_STRING_CHARS + 1),
          StateLimits.GAME_SNAPSHOT.decodedBytes,
      )
    }
  }

  @Test
  fun invalidHeaderAndTrailingDataAreRejected() {
    assertFailsWith<InvalidObjectException> {
      byteArrayOf(1, 2, 3, 4).deserializeToGameboyMemento()
    }

    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      val serialized = gameboy.saveToMemento().serialize() + 0x7f.toByte()
      assertFailsWith<IOException> { serialized.deserializeToGameboyMemento() }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)

  private fun rawSerialize(value: Any): ByteArray {
    val output = ByteArrayOutputStream()
    ObjectOutputStream(output).use { it.writeObject(value) }
    return output.toByteArray()
  }

  private fun cumulativeByteArrayStream(totalBytes: Long): ByteArray {
    require(totalBytes % 2L == 0L)
    val half = (totalBytes / 2L).toInt()
    val byteArrayUid = ObjectStreamClass.lookup(ByteArray::class.java).serialVersionUID
    return rawStream {
      writeByte(ObjectStreamConstants.TC_ARRAY.toInt())
      writeByte(ObjectStreamConstants.TC_CLASSDESC.toInt())
      writeUTF("[B")
      writeLong(byteArrayUid)
      writeByte(ObjectStreamConstants.SC_SERIALIZABLE.toInt())
      writeShort(0)
      writeByte(ObjectStreamConstants.TC_ENDBLOCKDATA.toInt())
      writeByte(ObjectStreamConstants.TC_NULL.toInt())
      writeInt(half)
      write(ByteArray(half))

      writeByte(ObjectStreamConstants.TC_ARRAY.toInt())
      writeByte(ObjectStreamConstants.TC_REFERENCE.toInt())
      writeInt(ObjectStreamConstants.baseWireHandle)
      writeInt(half)
      write(ByteArray(half))
    }
  }

  private fun rawStream(block: DataOutputStream.() -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use {
      it.writeShort(ObjectStreamConstants.STREAM_MAGIC.toInt())
      it.writeShort(ObjectStreamConstants.STREAM_VERSION.toInt())
      it.block()
    }
    return output.toByteArray()
  }

  private fun ByteArray.indexOf(needle: ByteArray): Int {
    for (start in 0..size - needle.size) {
      if (needle.indices.all { this[start + it] == needle[it] }) return start
    }
    return -1
  }

  private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
    ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).putInt(value)
  }

  private data class UnexpectedMemento(val command: String) : Memento<Gameboy>

  private class SerializableHandler : InvocationHandler, Serializable {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = null
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
