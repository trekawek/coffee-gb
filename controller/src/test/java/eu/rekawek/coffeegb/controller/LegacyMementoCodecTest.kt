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
import eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.InvalidObjectException
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Paths
import kotlin.test.assertFailsWith
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
  fun knownSessionMementoRoundTripsThroughAllowlist() {
    val eventBus = EventBusImpl()
    val session = Session(configuration(), eventBus, null, GpsReceiverSerialEndpoint())
    try {
      val serialized = session.saveToMemento().serializeSessionMemento()
      val restored = serialized.deserializeToSessionMemento()

      session.restoreFromMemento(restored)
    } finally {
      session.close()
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
    val eventBus = EventBusImpl()
    val session = Session(configuration(), eventBus, null)
    try {
      val serialized = session.saveToMemento().serializeSessionMemento()

      assertFailsWith<InvalidObjectException> { serialized.deserializeToGameboyMemento() }
    } finally {
      session.close()
    }
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

    assertFailsWith<InvalidClassException> { serialized.deserializeToGameboyMemento() }
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
      assertFailsWith<InvalidObjectException> { oversized.deserializeToGameboyMemento() }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun legacyStringLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() {
    val eventBus = EventBusImpl()
    val gps = GpsReceiverSerialEndpoint()
    val command =
        GpsReceiverSerialEndpoint::class.java.getDeclaredField("taipCommand").also {
          it.isAccessible = true
        }.get(gps) as StringBuilder
    val session = Session(configuration(), eventBus, null, gps)
    try {
      command.append("x".repeat(StateLimits.LEGACY_MAX_STRING_CHARS))
      session.saveToMemento().serializeSessionMemento().deserializeToSessionMemento()

      command.append('x')
      val oversized = session.saveToMemento().serializeSessionMemento()
      assertFailsWith<InvalidObjectException> { oversized.deserializeToSessionMemento() }
    } finally {
      session.close()
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

  private data class UnexpectedMemento(val command: String) : Memento<Gameboy>

  private class SerializableHandler : InvocationHandler, Serializable {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = null
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
