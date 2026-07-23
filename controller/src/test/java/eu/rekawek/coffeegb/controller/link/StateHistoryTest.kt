package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.core.joypad.Button
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
}
