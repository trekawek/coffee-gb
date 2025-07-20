package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
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
import eu.rekawek.coffeegb.swing.events.funnel
import eu.rekawek.coffeegb.swing.events.register
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Thread.sleep

class LinkedSession(
    private val eventBus: EventBus,
    private val rom: File,
    private val console: Console?,
) : Session {
  private val cart = Cartridge(rom)

  private var mainGameboy: Gameboy? = null

  private var secondaryGameboy: Gameboy? = null

  private var mainEventBus: EventBus? = null

  private var secondaryEventBus: EventBus? = null

  @Volatile private var doStop = false

  @Volatile private var doPause = false

  @Synchronized
  override fun start() {
    val stateHistory = StateHistory(rom)

    val localMainEventBus = EventBus()
    val mainEventBus = eventBus.fork("main")
    funnel(
        localMainEventBus,
        mainEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class, SoundSampleEvent::class))
    var mainSerialEndpoint = Peer2PeerSerialEndpoint()
    var mainGameboy = Gameboy(Cartridge(rom))
    mainGameboy.init(localMainEventBus, mainSerialEndpoint, console)

    val localSecondaryEventBus = EventBus()
    val secondaryEventBus = eventBus.fork("secondary")
    funnel(
        localSecondaryEventBus,
        secondaryEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class))
    var secondarySerialEndpoint = Peer2PeerSerialEndpoint()
    var secondaryGameboy = Gameboy(Cartridge(rom))
    secondaryGameboy.init(localSecondaryEventBus, secondarySerialEndpoint, console)

    secondarySerialEndpoint.init(mainSerialEndpoint)

    val mainPressedButtons = mutableSetOf<Button>()
    val mainReleasedButtons = mutableSetOf<Button>()
    var mainInput = Input(emptyList(), emptyList())
    var lastInput = mainInput
    mainEventBus.register<ButtonPressEvent> {
      synchronized(this) {
        mainPressedButtons.add(it.button)
        mainReleasedButtons.remove(it.button)
        mainInput =
            Input(mainPressedButtons.toList().sorted(), mainReleasedButtons.toList().sorted())
      }
    }
    mainEventBus.register<ButtonReleaseEvent> {
      synchronized(this) {
        mainPressedButtons.remove(it.button)
        mainReleasedButtons.add(it.button)
        mainInput =
            Input(mainPressedButtons.toList().sorted(), mainReleasedButtons.toList().sorted())
      }
    }

    secondaryEventBus.register<RemoteButtonStateEvent> {
      synchronized(this) { stateHistory.addSecondaryInput(it.frame, it.input) }
    }

    doStop = false
    val ticker = TimingTicker()
    Thread {
          var tick = 0
          var frame: Long = 0
          while (!doStop) {
            if (doPause) {
              sleep(1L)
              continue
            }
            if (tick == TICKS_PER_FRAME) {
              synchronized(this) {
                frame++
                tick = 0

                if (stateHistory.merge()) {
                  val head = stateHistory.getHead()
                  mainGameboy.restoreFromMemento(head.mainMemento)
                  secondaryGameboy.restoreFromMemento(head.secondaryMemento)
                  mainSerialEndpoint.restoreFromMemento(head.mainLinkMemento)
                  secondarySerialEndpoint.restoreFromMemento(head.secondaryLinkMemento)
                  frame = head.frame
                  LOG.atDebug().log("State merged to {}", frame)
                } else {
                  stateHistory.addState(
                      frame,
                      mainInput,
                      mainGameboy.saveToMemento(),
                      secondaryGameboy.saveToMemento(),
                      mainSerialEndpoint.saveToMemento(),
                      secondarySerialEndpoint.saveToMemento())
                }

                if (mainInput != lastInput) {
                  eventBus.post(LocalButtonStateEvent(frame, mainInput))
                  lastInput = mainInput
                }

                mainPressedButtons.forEach { localMainEventBus.post(ButtonPressEvent(it)) }
                mainReleasedButtons.forEach { localMainEventBus.post(ButtonReleaseEvent(it)) }

                mainPressedButtons.clear()
                mainReleasedButtons.clear()
              }
            }
            mainGameboy.tick()
            secondaryGameboy.tick()
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
    doPause = false

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

  override fun shutDown() {
    stop()
    eventBus.stop()
  }

  override fun pause() {
    doPause = true
  }

  override fun resume() {
    doPause = false
  }

  data class LocalButtonStateEvent(
      val frame: Long,
      val input: Input,
  ) : Event

  data class RemoteButtonStateEvent(
      val frame: Long,
      val input: Input,
  ) : Event

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(LinkedSession::class.java)
  }
}
