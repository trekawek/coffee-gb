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
import java.io.File
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
    val mainSerialEndpoint = Peer2PeerSerialEndpoint(null)
    val mainGameboy = Gameboy(cart)
    mainGameboy.init(localMainEventBus, mainSerialEndpoint, console)

    val localSecondaryEventBus = EventBus()
    val secondaryEventBus = eventBus.fork("secondary")
    funnel(
        localSecondaryEventBus,
        secondaryEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class))
    val secondarySerialEndpoint = Peer2PeerSerialEndpoint(mainSerialEndpoint)
    val secondaryGameboy = Gameboy(cart)
    secondaryGameboy.init(localSecondaryEventBus, secondarySerialEndpoint, console)

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

    val secondaryPressedButtons = mutableSetOf<Button>()
    val secondaryRelesedButtons = mutableSetOf<Button>()
    var secondaryFrame: Long
    secondaryEventBus.register<RemoteButtonStateEvent> {
      synchronized(this) {
        secondaryPressedButtons.addAll(it.pressedButtons)
        secondaryRelesedButtons.addAll(it.releasedButtons)
        secondaryPressedButtons.removeAll(it.releasedButtons.toSet())
        secondaryRelesedButtons.removeAll(it.pressedButtons.toSet())
        secondaryFrame = it.frame
      }
    }

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
                mainPressedButtons.forEach { localMainEventBus.post(ButtonPressEvent(it)) }
                mainReleasedButtons.forEach { localMainEventBus.post(ButtonReleaseEvent(it)) }
                secondaryPressedButtons.forEach {
                  localSecondaryEventBus.post(ButtonPressEvent(it))
                }
                secondaryRelesedButtons.forEach {
                  localSecondaryEventBus.post(ButtonReleaseEvent(it))
                }
                mainEventBus.post(
                    LocalButtonStateEvent(
                        frame,
                        mainPressedButtons.toList().sorted(),
                        mainReleasedButtons.toList().sorted()))

                mainPressedButtons.clear()
                mainReleasedButtons.clear()
                secondaryPressedButtons.clear()
                secondaryRelesedButtons.clear()

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
  }

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
