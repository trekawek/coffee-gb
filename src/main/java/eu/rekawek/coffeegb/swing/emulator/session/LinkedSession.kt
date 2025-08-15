package eu.rekawek.coffeegb.swing.emulator.session

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.controller.Joypad
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.events.EventBusImpl
import eu.rekawek.coffeegb.gpu.Display.DmgFrameReadyEvent
import eu.rekawek.coffeegb.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.sound.Sound.SoundSampleEvent
import eu.rekawek.coffeegb.swing.emulator.TimingTicker
import eu.rekawek.coffeegb.swing.events.funnel
import eu.rekawek.coffeegb.swing.events.register
import java.lang.Thread.sleep
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LinkedSession(
    private val eventBus: EventBus,
    private val mainConfig: GameboyConfiguration,
    private val peerConfig: GameboyConfiguration,
    private val console: Console?,
) : Session {

  private var mainGameboy: Gameboy? = null

  private var secondaryGameboy: Gameboy? = null

  private var mainEventBus: EventBus? = null

  private var secondaryEventBus: EventBus? = null

  @VisibleForTesting internal var stateHistory: StateHistory? = null

  @Volatile private var doStop = false

  @Volatile private var isStopped = false

  @Volatile private var doPause = false

  @Synchronized
  @VisibleForTesting
  internal fun init(): Runnable {
    stateHistory = StateHistory(mainConfig, peerConfig)

    val localMainEventBus = EventBusImpl()
    val mainEventBus = eventBus.fork("main")
    funnel(
        localMainEventBus,
        mainEventBus,
        setOf(
            DmgFrameReadyEvent::class,
            GbcFrameReadyEvent::class,
            SoundSampleEvent::class,
            Joypad.JoypadPressEvent::class))
    val mainSerialEndpoint = Peer2PeerSerialEndpoint()
    val mainGameboy = mainConfig.build()
    mainGameboy.init(localMainEventBus, mainSerialEndpoint, console)

    val localSecondaryEventBus = EventBusImpl()
    val secondaryEventBus = eventBus.fork("secondary")
    funnel(
        localSecondaryEventBus,
        secondaryEventBus,
        setOf(DmgFrameReadyEvent::class, GbcFrameReadyEvent::class))
    val secondarySerialEndpoint = Peer2PeerSerialEndpoint()
    val secondaryGameboy = peerConfig.build()
    secondaryGameboy.init(localSecondaryEventBus, secondarySerialEndpoint, console)

    secondarySerialEndpoint.init(mainSerialEndpoint)

    val mainButtonMonitor = Object()
    val mainPressedButtons = mutableSetOf<Button>()
    val mainReleasedButtons = mutableSetOf<Button>()
    var lastInput = Input(emptyList(), emptyList())
    mainEventBus.register<ButtonPressEvent> {
      synchronized(mainButtonMonitor) {
        mainPressedButtons.add(it.button)
        mainReleasedButtons.remove(it.button)
      }
    }
    mainEventBus.register<ButtonReleaseEvent> {
      synchronized(mainButtonMonitor) {
        mainPressedButtons.remove(it.button)
        mainReleasedButtons.add(it.button)
      }
    }

    secondaryEventBus.register<RemoteButtonStateEvent> {
      stateHistory!!.addSecondaryInput(it.frame, it.input)
    }

    this.mainGameboy = mainGameboy
    this.mainEventBus = mainEventBus
    this.secondaryGameboy = secondaryGameboy
    this.secondaryEventBus = secondaryEventBus

    var tick = 0
    var frame: Long = 0
    var lastSync = TimeSource.Monotonic.markNow()

    return object : Runnable {
      override fun run() {
        if (doPause) {
          sleep(1L)
          return
        }
        if (tick == TICKS_PER_FRAME) {
          val mainInput =
              synchronized(mainButtonMonitor) {
                val input =
                    Input(
                        mainPressedButtons.toList().sorted(), mainReleasedButtons.toList().sorted())
                mainPressedButtons.clear()
                mainReleasedButtons.clear()
                input
              }
          frame++
          tick = 0

          val effectiveInput =
              if (mainInput != lastInput) {
                mainInput
              } else {
                Input(emptyList(), emptyList())
              }
          lastInput = mainInput

          if (stateHistory!!.merge()) {
            val head = stateHistory!!.getHead()
            mainGameboy.restoreFromMemento(head.mainMemento)
            secondaryGameboy.restoreFromMemento(head.secondaryMemento)
            mainSerialEndpoint.restoreFromMemento(head.mainLinkMemento)
            secondarySerialEndpoint.restoreFromMemento(head.secondaryLinkMemento)
            frame = head.frame
            LOG.atDebug().log("State merged to {}", frame)
          }

          stateHistory!!.addState(
              frame,
              effectiveInput,
              mainGameboy.saveToMemento(),
              secondaryGameboy.saveToMemento(),
              mainSerialEndpoint.saveToMemento(),
              secondarySerialEndpoint.saveToMemento())

          val now = TimeSource.Monotonic.markNow()
          if (!effectiveInput.isEmpty() || now - lastSync > 5.seconds) {
            eventBus.postAsync(LocalButtonStateEvent(frame, effectiveInput))
            effectiveInput.send(localMainEventBus)
            lastSync = now
          }
        }

        mainGameboy.tick()
        secondaryGameboy.tick()
        tick++
      }
    }
  }

  @Synchronized
  override fun start() {
    val tick = init()
    val timingTicker = TimingTicker()
    doStop = false
    isStopped = false
    Thread {
          while (!doStop) {
            tick.run()
            timingTicker.run()
          }
          isStopped = true
        }
        .start()
    mainEventBus?.post(Session.EmulationStartedEvent(mainConfig.rom.title))
  }

  @Synchronized
  override fun stop() {
    if (mainGameboy == null) {
      return
    }
    doStop = true
    while (!isStopped) {}
    doPause = false

    mainGameboy?.stop()
    secondaryGameboy?.stop()
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
