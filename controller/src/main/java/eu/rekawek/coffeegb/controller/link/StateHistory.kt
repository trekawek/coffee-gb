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
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.ir.Peer2PeerInfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.serial.FourPlayerAdapter
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class StateHistory(private val mode: LinkMode = LinkMode.NORMAL) {
  private val states = LinkedList<State>()

  private val patches = mutableListOf<Patch>()

  var debugEventBus: EventBus? = null

  @Synchronized
  fun addState(
      frame: Long,
      inputs: List<Input>,
      mementos: List<Memento<Session>?>,
      buttons: List<Set<Button>>,
  ) {
    require(inputs.size == mode.playerCount)
    require(mementos.size == mode.playerCount)
    require(buttons.size == mode.playerCount)
    states.add(State(frame, inputs, mementos, buttons))
    LOG.atDebug().log("Adding state on frame {}; state size {}", frame, states.size)
    while (states.size > 60 * 5) {
      states.removeFirst()
    }
  }

  @Synchronized
  fun addSecondaryInput(player: Int, frame: Long, input: Input) {
    require(player in 0 until mode.playerCount)
    patches.add(Patch(player, frame, input))
    LOG.atDebug().log(
        "Adding player {} patch on frame {}, patches size {}", player + 1, frame, patches.size)
  }

  @Synchronized
  fun merge(configs: List<GameboyConfiguration?>): Boolean {
    require(configs.size == mode.playerCount)
    if (patches.isEmpty() || states.isEmpty()) {
      return false
    }
    val baseFrame = min(patches.minOf { it.frame }, states.last().frame)
    val toFrame = max(states.last().frame, patches.maxOf { it.frame })
    LOG.atDebug().log("Rebasing from $baseFrame to $toFrame")

    // Keep every player's already-applied input. A later packet can arrive out of order and force
    // a rebase before an earlier remote patch; retaining all inputs prevents that earlier patch
    // from disappearing during the second replay.
    val inputsByFrame =
        states.associate { state -> state.frame to state.inputs.toMutableList() }.toMutableMap()
    for (patch in patches) {
      val frameInputs =
          inputsByFrame.getOrPut(patch.frame) { emptyInputs().toMutableList() }
      frameInputs[patch.player] = patch.input
    }

    if (baseFrame < states.first().frame) {
      throw IllegalStateException("No frame $baseFrame")
    }
    val baseState =
        states.firstOrNull { it.frame == baseFrame }
            ?: throw IllegalStateException("No frame $baseFrame")

    val links = createLinks(mode)
    val sessions =
        configs.mapIndexed { player, config ->
          val eventBus = EventBusImpl(null, null, false)
          eventBus.register<Joypad.JoypadPressEvent> {
            debugEventBus?.post(GameboyJoypadPressEvent(it.button, it.tick, player))
          }
          config?.let {
            Session(
                if (baseState.mementos[player] != null) it.forRestore() else it,
                eventBus,
                null,
                links.serial[player],
                links.infrared[player],
            )
          }
        }

    for (player in sessions.indices) {
      val session = sessions[player]
      val memento = baseState.mementos[player]
      if (session != null && memento != null) {
        session.restoreFromMemento(memento)
        session.heldButtons = baseState.buttons[player]
      }
    }

    states.clear()
    patches.clear()

    for (frame in baseFrame..toFrame + 1) {
      val inputs = inputsByFrame[frame]?.toList() ?: emptyInputs()
      states.add(
          State(
              frame,
              inputs,
              sessions.map { it?.saveToMemento() },
              sessions.map { it?.heldButtons ?: emptySet() },
          ))

      if (frame <= toFrame) {
        for (player in sessions.indices) {
          inputs[player].send(sessions[player]?.eventBus ?: continue)
        }
        repeat(TICKS_PER_FRAME) { sessions.forEach { it?.gameboy?.tick() } }
      }
    }

    sessions.forEach { it?.close() }

    LOG.atDebug().log("Rebase from $baseFrame to $toFrame completed.")
    return true
  }

  fun getHead() = states.last()

  fun setPlayerState(
      player: Int,
      frame: Long,
      state: Memento<Session>,
      buttons: Set<Button>,
  ) {
    val index = states.indexOfFirst { it.frame == frame }
    if (index == -1) {
      return
    }
    val mementos = states[index].mementos.toMutableList().also { it[player] = state }
    val heldButtons = states[index].buttons.toMutableList().also { it[player] = buttons }
    states[index] = states[index].copy(mementos = mementos, buttons = heldButtons)
  }

  private fun emptyInputs() = List(mode.playerCount) { Input(emptyList(), emptyList()) }

  data class State(
      val frame: Long,
      val inputs: List<Input>,
      val mementos: List<Memento<Session>?>,
      val buttons: List<Set<Button>>,
  )

  private data class Patch(val player: Int, val frame: Long, val input: Input)

  @VisibleForTesting
  internal data class GameboyJoypadPressEvent(
      val button: Button,
      val tick: Long,
      val gameboy: Int,
  ) : Event

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(StateHistory::class.java)

    internal fun createLinks(mode: LinkMode): Links {
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        val adapter = FourPlayerAdapter()
        return Links(
            List(mode.playerCount) { adapter.endpoint(it) },
            List(mode.playerCount) { InfraredEndpoint.NULL_ENDPOINT },
        )
      }

      val firstSerial = Peer2PeerSerialEndpoint()
      val secondSerial = Peer2PeerSerialEndpoint()
      firstSerial.init(secondSerial)
      val firstInfrared = Peer2PeerInfraredEndpoint()
      val secondInfrared = Peer2PeerInfraredEndpoint()
      firstInfrared.init(secondInfrared)
      return Links(listOf(firstSerial, secondSerial), listOf(firstInfrared, secondInfrared))
    }
  }

  internal data class Links(
      val serial: List<SerialEndpoint>,
      val infrared: List<InfraredEndpoint>,
  )
}
