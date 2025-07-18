package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memento.Memento
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import java.io.File
import java.util.*
import kotlin.math.min
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class StateHistory(private val rom: File) {

  private val states = LinkedList<State>()

  private val patches = mutableListOf<Patch>()

  fun addState(
      frame: Long,
      mainInput: Input,
      mainMemento: Memento<Gameboy>,
      secondaryMemento: Memento<Gameboy>,
      mainLinkMemento: Memento<Peer2PeerSerialEndpoint>,
      secondaryLinkMemento: Memento<Peer2PeerSerialEndpoint>,
  ) {
    states.add(
        State(
            frame, mainInput, mainMemento, secondaryMemento, mainLinkMemento, secondaryLinkMemento))
    while (states.size > 60 * 5) {
      states.removeFirst()
    }
  }

  fun addSecondaryInput(
      frame: Long,
      secondaryInput: Input,
  ) {
    patches.add(Patch(frame, secondaryInput))
  }

  fun merge(): Boolean {
    if (patches.isEmpty() || states.isEmpty()) {
      return false
    }
    val baseFrame = min(patches.first().frame, states.last().frame)
    val toFrame = patches.last().frame
    LOG.atDebug().log("Rebasing from $baseFrame to $toFrame")

    val mainInputs = states.groupBy { it.frame }.mapValues { it.value.first().mainInput }
    val secondaryInputs = patches.groupBy { it.frame }.mapValues { it.value.first().secondaryInput }

    if (baseFrame < states.first().frame) {
      throw IllegalStateException("No frame $baseFrame")
    }
    val baseState =
        states.firstOrNull { it.frame == baseFrame }
            ?: throw IllegalStateException("No frame $baseFrame")

    val mainGameboy = Gameboy(Cartridge(rom))
    val secondaryGameboy = Gameboy(Cartridge(rom))
    val mainLink = Peer2PeerSerialEndpoint()
    val secondaryLink = Peer2PeerSerialEndpoint()
    mainLink.init(secondaryLink)

    val mainEventBus = EventBus()
    val secondaryEventBus = EventBus()

    mainGameboy.init(mainEventBus, mainLink, null)
    secondaryGameboy.init(secondaryEventBus, secondaryLink, null)

    mainGameboy.restoreFromMemento(baseState.mainMemento)
    secondaryGameboy.restoreFromMemento(baseState.secondaryMemento)
    mainLink.restoreFromMemento(baseState.mainLinkMemento)
    secondaryLink.restoreFromMemento(baseState.secondaryLinkMemento)

    states.clear()
    patches.clear()

    for (i in (baseFrame..toFrame + 1)) {
      val mainInput = mainInputs[i] ?: Input(emptyList(), emptyList())
      states.add(
          State(
              i,
              mainInput,
              mainGameboy.saveToMemento(),
              secondaryGameboy.saveToMemento(),
              mainLink.saveToMemento(),
              secondaryLink.saveToMemento()))

      if (i <= toFrame) {
        sendInput(mainInput, mainEventBus)
        val secondaryInput = secondaryInputs[i]
        if (secondaryInput != null) {
          LOG.atDebug().log("Sending secondary input $secondaryInput on frame $i")
          sendInput(secondaryInput, secondaryEventBus)
        }

        repeat(TICKS_PER_FRAME) {
          mainGameboy.tick()
          secondaryGameboy.tick()
        }
      }
    }
    LOG.atDebug().log("Rebase from $baseFrame to $toFrame completed.")
    return true
  }

  fun getHead() = states.last()

  private fun sendInput(
      input: Input,
      eventBus: EventBus,
  ) {
    input.pressedButtons.forEach { eventBus.post(ButtonPressEvent(it)) }
    input.releasedButtons.forEach { eventBus.post(ButtonReleaseEvent(it)) }
  }

  data class State(
      val frame: Long,
      val mainInput: Input,
      val mainMemento: Memento<Gameboy>,
      val secondaryMemento: Memento<Gameboy>,
      val mainLinkMemento: Memento<Peer2PeerSerialEndpoint>,
      val secondaryLinkMemento: Memento<Peer2PeerSerialEndpoint>,
  )

  private data class Patch(
      val frame: Long,
      val secondaryInput: Input,
  )

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(StateHistory::class.java)
  }
}
