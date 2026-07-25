package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class StateCodecLinkedTest {

  @Test
  fun normalAndFourPlayerGroupsRoundTripAndContinueDeterministically() {
    listOf(LinkMode.NORMAL to 2, LinkMode.FOUR_PLAYER_ADAPTER to 4).forEach {
        (mode, players) ->
      configuredController(mode, players).use { fixture ->
        val file = StateCodec.capture(fixture.controller)
        val encoded = StateCodec.encode(file, StateCompression.DEFLATE)
        assertEquals(file, StateCodec.decode(encoded))
        assertEquals(List(4) { it < players }, file.identities.map { it.identity != null })

        StateCodec.decodeAndApply(encoded, fixture.controller)
        fixture.controller.runFrame()
        val expected = fixture.controller.captureDetachedState()
        repeat(3) { fixture.controller.runFrame() }
        val stages = mutableListOf<Pair<Int, ApplyStage>>()
        StateCodec.decodeAndApply(encoded, fixture.controller) { player, stage ->
          stages += player to stage
        }
        fixture.controller.runFrame()
        assertEquals(expected, fixture.controller.captureDetachedState(), mode.name)
        assertEquals(
            (0 until players).flatMap { player ->
              listOf(
                  player to ApplyStage.BEFORE_LIVE_MUTATION,
                  player to ApplyStage.AFTER_MACHINE_MUTATION,
              )
            },
            stages,
        )
      }
    }
  }

  @Test
  fun linkedIdentityAndOnePlayerMapperFailuresAreGroupAtomicBeforeMutation() {
    configuredController(LinkMode.NORMAL, 2).use { target ->
      val before = target.controller.captureDetachedState()
      val targetFile = StateCodec.capture(target.controller)
      val wrongIdentities =
          targetFile.identities.map { entry ->
            if (entry.player != 1) entry
            else {
              val identity = checkNotNull(entry.identity)
              entry.copy(
                  identity =
                      identity.copy(
                          primaryRom = RomIdentity(ByteArray(32) { 0x5a }),
                      ))
            }
          }
      val wrongIdentityBytes =
          StateCodec.encode(
              StateFile(wrongIdentities, targetFile.root),
          )
      val identityStages = mutableListOf<Pair<Int, ApplyStage>>()
      val identityFailure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(wrongIdentityBytes, target.controller) { player, stage ->
              identityStages += player to stage
            }
          }
      assertEquals(StateDecodeReason.ROM_MISMATCH, identityFailure.reason)
      assertTrue(identityStages.isEmpty())
      assertEquals(before, target.controller.captureDetachedState())

      val mbc2 = StateCodecTestSupport.rom(seed = 9).also { it[0x147] = 0x06 }
      configuredController(LinkMode.NORMAL, 2, mbc2).use { foreign ->
        val targetLinked = (targetFile.root as LinkedSessionStateRoot).linked
        val foreignLinked = (StateCodec.capture(foreign.controller).root as LinkedSessionStateRoot).linked
        val players =
            targetLinked.players.map { player ->
              if (player.player == 1) player.copy(session = foreignLinked.players[1].session)
              else player
            }
        val incompatible =
            StateFile(
                targetFile.identities,
                LinkedSessionStateRoot(
                    LinkedSessionState(
                        targetLinked.frame,
                        targetLinked.localPlayer,
                        targetLinked.topology,
                        players,
                    )),
            )
        val stages = mutableListOf<Pair<Int, ApplyStage>>()
        val failure =
            assertFailsWith<StateDecodeException> {
              StateCodec.decodeAndApply(
                  StateCodec.encode(incompatible),
                  target.controller,
              ) { player, stage -> stages += player to stage }
            }
        assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
        assertTrue(stages.isEmpty())
        assertEquals(before, target.controller.captureDetachedState())
      }
    }
  }

  private fun configuredController(
      mode: LinkMode,
      players: Int,
      romBytes: ByteArray = StateCodecTestSupport.rom(),
  ): Fixture {
    val file = Files.createTempFile("coffee-gb-state-codec-", ".gb").toFile()
    file.writeBytes(romBytes)
    val properties = EmulatorProperties()
    val configuration = Controller.createGameboyConfig(properties, Rom(romBytes))
    val bus = EventBusImpl()
    val controller =
        LinkedController(bus, properties, null, mode, localPlayer = 0).also {
          it.timingTicker.disabled = true
        }
    bus.post(LoadRomEvent(file))
    controller.runFrame()
    for (player in 1 until players) {
      bus.post(
          PeerLoadedGameEvent(
              romBytes,
              null,
              null,
              configuration.gameboyType,
              configuration.bootstrapMode,
              controller.currentFrame(),
              player = player,
          ))
      controller.runFrame()
    }
    assertEquals(players, controller.activeSessionCount())
    return Fixture(file, bus, controller)
  }

  private class Fixture(
      private val file: java.io.File,
      private val bus: EventBusImpl,
      val controller: LinkedController,
  ) : AutoCloseable {
    override fun close() {
      controller.close()
      bus.close()
      file.delete()
    }
  }
}
