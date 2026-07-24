package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.GameGeniePatch
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.FourPlayerAdapter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Paths
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class NetplayMementoCodecTest {

  @Test
  fun gameboyStateIsDeterministicAndRoundTrips() {
    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      eventBus.post(AddPatches(listOf(GameGeniePatch(0x42, 0x1234, 0x24))))
      repeat(100) { gameboy.tick() }
      val memento = gameboy.saveToMemento()
      val first = NetplayMementoCodec.encodeGameboy(memento)
      val second = NetplayMementoCodec.encodeGameboy(memento)

      assertContentEquals(first, second)
      val decoded = NetplayMementoCodec.decodeGameboy(first)
      assertContentEquals(first, NetplayMementoCodec.encodeGameboy(decoded))
      gameboy.restoreFromMemento(decoded)
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun fourPlayerSessionStateRoundTrips() {
    val eventBus = EventBusImpl()
    val adapter = FourPlayerAdapter()
    val endpoint = adapter.endpoint(0)
    val session = Session(configuration(), eventBus, null, endpoint)
    try {
      // Exercise a non-default shared-adapter phase, rather than proving only the zero state.
      val adapterState = endpoint.saveToMemento()
      val phaseType =
          adapterState.javaClass.recordComponents.single { it.name == "phase" }.type
      val transmission = phaseType.enumConstants.single { (it as Enum<*>).name == "TRANSMISSION" }
      @Suppress("UNCHECKED_CAST")
      endpoint.restoreFromMemento(
          replaceRecordComponent(adapterState, "phase", transmission) as Memento<SerialEndpoint>)
      val encoded = NetplayMementoCodec.encodeSession(session.saveToMemento())
      val decoded = NetplayMementoCodec.decodeSession(encoded)

      assertContentEquals(encoded, NetplayMementoCodec.encodeSession(decoded))
      session.restoreFromMemento(decoded)
      assertContentEquals(encoded, NetplayMementoCodec.encodeSession(session.saveToMemento()))
    } finally {
      session.close()
    }
  }

  @Test
  fun invalidEnvelopeIsRejectedBeforeGraphConstruction() {
    assertFailsWith<NetplayMementoCodec.DecodeException> {
      NetplayMementoCodec.decodeGameboy(byteArrayOf(1, 2, 3, 4))
    }

    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      val encoded = NetplayMementoCodec.encodeGameboy(gameboy.saveToMemento())
      val corrupt = encoded.clone().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2] + 1).toByte() }
      assertFailsWith<NetplayMementoCodec.DecodeException> {
        NetplayMementoCodec.decodeGameboy(corrupt)
      }
      assertFailsWith<NetplayMementoCodec.DecodeException> {
        NetplayMementoCodec.decodeGameboy(encoded.copyOf(encoded.size - 1))
      }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  @Test
  fun genericContainerShapeIsValidatedBeforeGraphDelivery() {
    val eventBus = EventBusImpl()
    val gameboy = configuration().build()
    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      val memento = gameboy.saveToMemento()
      val genie = recordComponent(memento, "genieMemento")!!
      val invalidGenie =
          replaceRecordComponent(genie, "patches", mapOf(1 to listOf(42)))
      @Suppress("UNCHECKED_CAST")
      val invalid =
          replaceRecordComponent(memento, "genieMemento", invalidGenie) as Memento<Gameboy>
      val encoded = NetplayMementoCodec.encodeGameboy(invalid)

      assertFailsWith<NetplayMementoCodec.DecodeException> {
        NetplayMementoCodec.decodeGameboy(encoded)
      }
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)

  private fun recordComponent(record: Any, name: String): Any? =
      record.javaClass.recordComponents.single { it.name == name }.accessor.let { accessor ->
        accessor.isAccessible = true
        accessor.invoke(record)
      }

  private fun replaceRecordComponent(record: Any, name: String, replacement: Any?): Any {
    val components = record.javaClass.recordComponents
    val constructor =
        record.javaClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray()).also {
          it.isAccessible = true
        }
    val values =
        components.map { component ->
          if (component.name == name) {
            replacement
          } else {
            component.accessor.let { accessor ->
              accessor.isAccessible = true
              accessor.invoke(record)
            }
          }
        }.toTypedArray()
    return constructor.newInstance(*values)
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
