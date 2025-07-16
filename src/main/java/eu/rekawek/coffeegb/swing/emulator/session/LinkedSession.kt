package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.gpu.Display.DmgFrameReadyEvent
import eu.rekawek.coffeegb.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.sound.Sound.SoundSampleEvent
import eu.rekawek.coffeegb.swing.emulator.TimingTicker
import eu.rekawek.coffeegb.swing.events.register
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*
import kotlin.reflect.KClass

class LinkedSession(
    private val eventBus: EventBus,
    rom: File,
    private val console: Console?,
) : Session {
  private val cart = Cartridge(rom)

  private var mainGameboy: Gameboy? = null

  private var secondaryGameboy: Gameboy? = null

  private var mainEventBus: EventBus? = null

  private var secondaryEventBus: EventBus? = null

  @Volatile private var doStop = false

  @Synchronized
  override fun start() {
    val localMainEventBus = EventBus()
    val mainEventBus = eventBus.fork("main")
    funnel(
        localMainEventBus,
        mainEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class, SoundSampleEvent::class))
    var mainSerialEndpoint = Peer2PeerSerialEndpoint()
    var mainGameboy = Gameboy(cart)
    mainGameboy.init(localMainEventBus, mainSerialEndpoint, console)

    val localSecondaryEventBus = EventBus()
    val secondaryEventBus = eventBus.fork("secondary")
    funnel(
        localSecondaryEventBus,
        secondaryEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class))
    var secondarySerialEndpoint = Peer2PeerSerialEndpoint()
    var secondaryGameboy = Gameboy(cart)
    secondaryGameboy.init(localSecondaryEventBus, secondarySerialEndpoint, console)

    secondarySerialEndpoint.init(mainSerialEndpoint)

    val mainPressedButtons = mutableSetOf<Button>()
    val mainReleasedButtons = mutableSetOf<Button>()
    mainEventBus.register<ButtonPressEvent> {
      synchronized(this) {
        mainPressedButtons.add(it.button)
        mainReleasedButtons.remove(it.button)
      }
    }
    mainEventBus.register<ButtonReleaseEvent> {
      synchronized(this) {
        mainPressedButtons.remove(it.button)
        mainReleasedButtons.add(it.button)
      }
    }

    val remoteButtonEvents = LinkedList<RemoteButtonStateEvent>()
    secondaryEventBus.register<RemoteButtonStateEvent> {
      synchronized(this) { remoteButtonEvents.addLast(it) }
    }

    val states = LinkedList<SavedState>()

    doStop = false
    val ticker = TimingTicker()
    Thread {
          var tick = 0
          var frame: Long = 0
          while (!doStop) {
            mainGameboy.tick()
            secondaryGameboy.tick()
            if (tick == 69905) {
              synchronized(this) {
                val pastEvents = remoteButtonEvents.filter { it.frame <= frame }
                remoteButtonEvents.removeAll(pastEvents.toSet())
                for (e in pastEvents) {
                  var stateIndex: Int = states.size
                  if (e.frame < frame) {
                    LOG.atInfo().log("Rewinding to frame ${e.frame}")

                    stateIndex = states.indexOfFirst { it.frame == e.frame }
                    if (stateIndex == -1) {
                      throw IllegalStateException(
                          "Desynchronized; can't find frame ${e.frame}; last frame is ${states.lastOrNull()?.frame}")
                    }
                    val state = states[stateIndex]

                    ObjectInputStream(ByteArrayInputStream(state.mainSnapshot)).use {
                      mainGameboy = it.readObject() as Gameboy
                      mainSerialEndpoint = it.readObject() as Peer2PeerSerialEndpoint
                    }
                    ObjectInputStream(ByteArrayInputStream(state.secondarySnapshot)).use {
                      secondaryGameboy = it.readObject() as Gameboy
                      secondarySerialEndpoint = it.readObject() as Peer2PeerSerialEndpoint
                    }
                    mainSerialEndpoint.init(secondarySerialEndpoint)
                    mainGameboy.init(localMainEventBus, mainSerialEndpoint, console)
                    secondaryGameboy.init(localSecondaryEventBus, secondarySerialEndpoint, null)
                  }

                  LOG.atInfo().log("Applying buttons ${e.pressedButtons}, ${e.releasedButtons}")
                  e.pressedButtons.forEach { localSecondaryEventBus.post(ButtonPressEvent(it)) }
                  e.releasedButtons.forEach { localSecondaryEventBus.post(ButtonReleaseEvent(it)) }

                  if (e.frame < frame) {
                    (stateIndex until states.size).forEach { i ->
                      val s = states[i]
                      LOG.atInfo().log("Applying state ${s.frame}")

                      s.mainPressedButtons.forEach { localMainEventBus.post(ButtonPressEvent(it)) }
                      s.mainReleasedButtons.forEach {
                        localMainEventBus.post(ButtonReleaseEvent(it))
                      }
                      repeat(69905) {
                        mainGameboy.tick()
                        secondaryGameboy.tick()
                      }
                      // TODO update past states
                    }
                  }
                }

                mainPressedButtons.forEach { localMainEventBus.post(ButtonPressEvent(it)) }
                mainReleasedButtons.forEach { localMainEventBus.post(ButtonReleaseEvent(it)) }

                val lastState = states.lastOrNull()
                if (lastState == null ||
                    mainPressedButtons != lastState.mainPressedButtons ||
                    mainReleasedButtons != lastState.mainReleasedButtons) {
                  mainEventBus.post(
                      LocalButtonStateEvent(
                          frame,
                          mainPressedButtons.toList().sorted(),
                          mainReleasedButtons.toList().sorted()))
                }

                val mainBos = ByteArrayOutputStream()
                val secondaryBos = ByteArrayOutputStream()
                ObjectOutputStream(mainBos).use {
                  it.writeObject(mainGameboy)
                  it.writeObject(mainSerialEndpoint)
                }
                ObjectOutputStream(secondaryBos).use {
                  it.writeObject(secondaryGameboy)
                  it.writeObject(secondarySerialEndpoint)
                }

                states.addLast(
                    SavedState(
                        frame,
                        mainBos.toByteArray(),
                        secondaryBos.toByteArray(),
                        mainPressedButtons.toSet(),
                        mainReleasedButtons.toSet()))
                if (states.size > 60) {
                  states.removeFirst()
                }

                mainPressedButtons.clear()
                mainReleasedButtons.clear()

                tick = 0
                frame++
              }
            }
            ticker.run()
            tick++
          }
        }
        .start()

    mainEventBus.post(Session.EmulationStartedEvent(cart.title))

    this.mainGameboy = mainGameboy
    this.mainEventBus = mainEventBus
    this.secondaryGameboy = secondaryGameboy
    this.secondaryEventBus = secondaryEventBus
  }

  @Synchronized
  override fun stop() {
    if (mainGameboy == null) {
      return
    }
    doStop = true

    cart.flushBattery()
    console?.setGameboy(null)
    mainEventBus?.post(Session.EmulationStoppedEvent())

    mainEventBus?.stop()
    secondaryEventBus?.stop()

    mainGameboy = null
    secondaryGameboy = null
    mainEventBus = null
    secondaryEventBus = null
  }

  @Synchronized
  override fun reset() {
    stop()
    start()
  }

  override fun getRomName(): String {
    return cart.title
  }

  private companion object {
    fun funnel(from: EventBus, to: EventBus, eventTypes: Set<KClass<out Event>>) {
      eventTypes.forEach { et -> from.register({ event -> to.post(event) }, et.java) }
    }

    val LOG: Logger = LoggerFactory.getLogger(LinkedSession::class.java)
  }

  data class SavedState(
      val frame: Long,
      val mainSnapshot: ByteArray,
      val secondarySnapshot: ByteArray,
      val mainPressedButtons: Set<Button>,
      val mainReleasedButtons: Set<Button>,
  )

  data class LocalButtonStateEvent(
      val frame: Long,
      val pressedButtons: List<Button>,
      val releasedButtons: List<Button>
  ) : Event

  data class RemoteButtonStateEvent(
      val frame: Long,
      val pressedButtons: List<Button>,
      val releasedButtons: List<Button>
  ) : Event
}
