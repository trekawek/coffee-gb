package eu.rekawek.coffeegb.controller.link

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.max
import kotlin.math.min

class StateHistory() {
  private val states = LinkedList<State>()

  private val patches = mutableListOf<Patch>()

  var debugEventBus: EventBus? = null

  @Synchronized
  fun addState(
      frame: Long,
      mainInput: Input,
      mainMemento: Memento<Session>?,
      peerMemento: Memento<Session>?,
  ) {
    states.add(State(frame, mainInput, mainMemento, peerMemento))
    LOG.atDebug().log("Adding state on frame {}; state size {}", frame, states.size)
    while (states.size > 60 * 5) {
      states.removeFirst()
    }
  }

  @Synchronized
  fun addSecondaryInput(
      frame: Long,
      secondaryInput: Input,
  ) {
    patches.add(Patch(frame, secondaryInput))
    LOG.atDebug().log("Adding patch on frame {}, patches size {}", frame, patches.size)
  }

  @Synchronized
  fun merge(mainConfig: GameboyConfiguration?, peerConfig: GameboyConfiguration?): Boolean {
    if (patches.isEmpty() || states.isEmpty()) {
      return false
    }
    val baseFrame = min(patches.first().frame, states.last().frame)
    val toFrame = max(states.last().frame, patches.last().frame)
    LOG.atDebug().log("Rebasing from $baseFrame to $toFrame")

    val mainInputs = states.groupBy { it.frame }.mapValues { it.value.first().mainInput }
    val peerInputs = patches.groupBy { it.frame }.mapValues { it.value.first().secondaryInput }

    if (baseFrame < states.first().frame) {
      throw IllegalStateException("No frame $baseFrame")
    }
    val baseState =
        states.firstOrNull { it.frame == baseFrame }
            ?: throw IllegalStateException("No frame $baseFrame")

    val mainLink = Peer2PeerSerialEndpoint()
    val peerLink = Peer2PeerSerialEndpoint()
    mainLink.init(peerLink)

    val mainEventBus = EventBusImpl(null, null, false)
    val peerEventBus = EventBusImpl(null, null, false)
    mainEventBus.register<Joypad.JoypadPressEvent> {
      debugEventBus?.post(GameboyJoypadPressEvent(it.button, it.tick, 0))
    }
    peerEventBus.register<Joypad.JoypadPressEvent> {
      debugEventBus?.post(GameboyJoypadPressEvent(it.button, it.tick, 1))
    }

    val mainSession = mainConfig?.let { Session(it, mainEventBus, null, mainLink) }
    val peerSession = peerConfig?.let { Session(it, peerEventBus, null, peerLink) }
    if (mainSession != null && baseState.mainMemento != null) {
      mainSession.restoreFromMemento(baseState.mainMemento)
    }
    if (peerSession != null && baseState.peerMemento != null) {
      peerSession.restoreFromMemento(baseState.peerMemento)
    }

    states.clear()
    patches.clear()

    for (i in (baseFrame..toFrame + 1)) {
      val mainInput = mainInputs[i] ?: Input(emptyList(), emptyList())
      states.add(State(i, mainInput, mainSession?.saveToMemento(), peerSession?.saveToMemento()))

      if (i <= toFrame) {
        mainInput.send(mainEventBus)
        val peerInput = peerInputs[i]
        if (peerInput != null) {
          LOG.atDebug().log("Sending secondary input {} on frame {}", peerInput, i)
          peerInput.send(peerEventBus)
        }

        repeat(TICKS_PER_FRAME) {
          mainSession?.gameboy?.tick()
          peerSession?.gameboy?.tick()
        }
      }
    }

    mainSession?.close()
    peerSession?.close()

    LOG.atDebug().log("Rebase from $baseFrame to $toFrame completed.")
    return true
  }

  fun getHead() = states.last()

  fun setPeerState(peerFrame: Long, peerState: Memento<Session>) {
    val index = states.indexOfFirst { it.frame == peerFrame }
    if (index == -1) {
      return
    }
    states[index] = states[index].copy(peerMemento = peerState)
  }

  data class State(
      val frame: Long,
      val mainInput: Input,
      val mainMemento: Memento<Session>?,
      val peerMemento: Memento<Session>?,
  )

  private data class Patch(
      val frame: Long,
      val secondaryInput: Input,
  )

  @VisibleForTesting
  internal data class GameboyJoypadPressEvent(
      val button: Button,
      val tick: Long,
      val gameboy: Int,
  ) : Event

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(StateHistory::class.java)
  }
}
