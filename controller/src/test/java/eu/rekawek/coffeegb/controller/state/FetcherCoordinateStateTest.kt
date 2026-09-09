package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.gpu.Fetcher
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class FetcherCoordinateStateTest {
  @Test
  fun historicalFetcherPrefixKeepsItsWireBytesAndGetsDeterministicImportDefaults() {
    session().use { source ->
      seek(source.gameboy)
      val current = assertIs<RecordState>(StateGraph.capture(fetcher(source.gameboy).captureState()))
      assertEquals(listOf("tileMapX", "xBasePosition", "xBaseObjectFetch"), current.fields.takeLast(3).map(StateField::name))
      assertTrue((current.field("xBasePosition") as Int32State).value > 0)
      val historical = RecordState(current.typeId, current.fields.dropLast(3))
      assertEquals(13, historical.fields.size)
      val bytes = encode(historical)
      val decoded = assertIs<RecordState>(decode(bytes))
      assertEquals(13, decoded.fields.size)
      assertContentEquals(bytes, encode(decoded))
      StateGraph.validateCompatible(decoded, current, "old-fetcher")
      val restored = StateGraph.restore(decoded)
      StateSemantics.validate(restored)
      val normalized = assertIs<RecordState>(StateGraph.capture(restored))
      assertEquals(Int32State(0), normalized.field("tileMapX"))
      assertEquals(Int32State(0), normalized.field("xBasePosition"))
      assertEquals(BooleanState(false), normalized.field("xBaseObjectFetch"))
      assertEquals(historical, normalized)
      assertContentEquals(bytes, encode(normalized))
    }
  }

  @Test
  fun releasedJavaFetcherRecordInventoryIsUnchangedAndImportsThroughTheSamePrefix() {
    session().use { source ->
      seek(source.gameboy)
      val current = fetcher(source.gameboy).captureState()
      val legacyType = StateTypeRegistry.legacyRecordClasses.single { it.name == LEGACY }
      val components = StateRecordIntrospection.components(legacyType)
      assertEquals(13, components.size)
      assertEquals("data2TileSelectGlitch", components.last().name)
      val values = StateRecordIntrospection.components(current.javaClass).take(13).map { it.value(current) }
      val ctor = legacyType.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
      ctor.isAccessible = true
      val legacy = ctor.newInstance(*values.toTypedArray())
      val imported = StateGraph.captureLegacyRoot(legacy, LEGACY)
      val restored = StateGraph.restore(imported)
      StateSemantics.validate(restored)
      val graph = assertIs<RecordState>(StateGraph.capture(restored))
      assertEquals(Int32State(0), graph.field("tileMapX"))
      assertEquals(Int32State(0), graph.field("xBasePosition"))
      assertEquals(BooleanState(false), graph.field("xBaseObjectFetch"))
      // The lost historical fields cannot be recovered from an unrelated live target.
      assertTrue((assertIs<RecordState>(StateGraph.capture(current)).field("xBasePosition") as Int32State).value > 0)
    }
  }

  @Test
  fun newPortableWholeMachineStatesResumeTheirLatchedMapCoordinates() {
    for (hardware in listOf(GameboyType.DMG, GameboyType.CGB)) {
      session(hardware).use { source -> session(hardware).use { target ->
        seek(source.gameboy)
        val bytes = StateCodec.encode(StateCodec.capture(source))
        assertContentEquals(bytes, StateCodec.encode(StateCodec.decode(bytes)))
        StateCodec.decodeAndApply(bytes, source)
        StateCodec.decodeAndApply(bytes, target)
        repeat(54) {
          assertEquals(source.gameboy.tick(), target.gameboy.tick())
          assertEquals(source.captureDetachedState().machine, target.captureDetachedState().machine)
        }
      } }
    }
  }

  @Test
  fun malformedCoordinateSuffixIsRejectedBeforeItCanReachTheLiveMachine() {
    session().use { source ->
      seek(source.gameboy)
      val current = assertIs<RecordState>(StateGraph.capture(fetcher(source.gameboy).captureState()))
      for ((name, invalid) in listOf("tileMapX" to -1, "tileMapX" to 32, "xBasePosition" to -17, "xBasePosition" to 161)) {
        val bad = RecordState(current.typeId, current.fields.map { if (it.name == name) StateField(name, Int32State(invalid)) else it })
        assertFailsWith<StateApplyException> { StateSemantics.validate(StateGraph.restore(bad)) }
      }
      assertEquals(current, StateGraph.capture(fetcher(source.gameboy).captureState()))
    }
  }

  private fun session(hardware: GameboyType = GameboyType.DMG): Session =
      StateCodecTestSupport.session(StateCodecTestSupport.configuration(
          StateCodecTestSupport.rom(cgb = hardware == GameboyType.CGB), hardware).setRtcTimeSource { 0L })

  private fun seek(gameboy: Gameboy) {
    gameboy.gpu.setByte(0xff43, 3)
    repeat(70_224) {
      if (gameboy.gpu.line == 1 && gameboy.gpu.ticksInLine == 129) return
      gameboy.tick()
    }
    error("fetch phase fixture was not reached")
  }

  private fun fetcher(gameboy: Gameboy): Fetcher {
    fun field(value: Any, name: String): Any = value.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(value) }
    return field(field(gameboy.gpu, "pixelTransferPhase"), "fetcher") as Fetcher
  }

  private fun RecordState.field(name: String): StateValue = fields.single { it.name == name }.value
  private fun encode(value: StateValue): ByteArray = PortableWriter(4096).also { StateValueCodec.Encoder(it).write(value) }.toByteArray()
  private fun decode(bytes: ByteArray): StateValue = PortableReader(bytes).let { StateValueCodec.Decoder(it).read() }

  private companion object { const val LEGACY = "eu.rekawek.coffeegb.core.gpu.Fetcher\$FetcherMemento" }
}
