package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StateHistoryTest {

  @Test
  fun laterEarlierPlayerPacketCanRebaseFromRetainedHistory() {
    val history = StateHistory(LinkMode.FOUR_PLAYER_ADAPTER)
    val noInput = Input(emptyList(), emptyList())
    for (frame in 0L..10L) {
      history.addState(
          frame,
          List(4) { noInput },
          List(4) { null },
          List(4) { emptySet() },
      )
    }

    history.addSecondaryInput(1, 5, Input(listOf(Button.A), emptyList()))
    assertTrue(history.merge(List(4) { null }))

    // This arrives on another player's TCP stream after frame 5 was already replayed. Before the
    // fix, that replay discarded frame 4 and this merge threw "No frame 4".
    history.addSecondaryInput(2, 4, Input(listOf(Button.B), emptyList()))
    assertTrue(history.merge(List(4) { null }))
    assertEquals(12, history.getHead().frame)
  }

  @Test
  fun replayNeverSamplesExternalFourSlotServiceAndUsesHistoricalPrimaryInput() {
    val samples = AtomicInteger()
    val physical =
        PlayerInputSnapshot.of(
            listOf(setOf(Button.B), setOf(Button.A), emptySet(), setOf(Button.START)))
    val config =
        Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom()))
            .setGameboyType(GameboyType.SGB)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
            .setPlayerInputSource {
              samples.incrementAndGet()
              physical
            }
    val second =
        Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom()))
            .setGameboyType(GameboyType.SGB)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
    val links = StateHistory.createLinks(LinkMode.NORMAL)
    Session(
            config,
            EventBusImpl(null, null, false),
            null,
            links.serial[0],
            links.infrared[0],
        )
        .use { firstSession ->
          Session(
                  second,
                  EventBusImpl(null, null, false),
                  null,
                  links.serial[1],
                  links.infrared[1],
              )
              .use { secondSession ->
                val history = StateHistory(LinkMode.NORMAL)
                val replayed = mutableListOf<StateHistory.GameboyJoypadPressEvent>()
                history.debugEventBus =
                    EventBusImpl().also { debug ->
                      debug.register<StateHistory.GameboyJoypadPressEvent> { replayed += it }
                    }
                val noInput = Input(emptyList(), emptyList())
                repeat(2) { frame ->
                  history.addState(
                      frame.toLong(),
                      listOf(noInput, noInput),
                      listOf(
                          firstSession.captureDetachedState(),
                          secondSession.captureDetachedState(),
                      ),
                      listOf(emptySet(), emptySet()),
                  )
                }
                history.addSecondaryInput(0, 0, Input(listOf(Button.SELECT), emptyList()))
                assertTrue(history.merge(listOf(config, second)))
                assertEquals(
                    0,
                    samples.get(),
                    "rollback machines must use RELEASED rather than a desktop service",
                )
                assertEquals(
                    listOf(Button.SELECT to 0),
                    replayed.map { it.button to it.gameboy },
                    "replay must use historical P1, not the live source's physical P1",
                )
              }
        }
  }
}
