package eu.rekawek.coffeegb.swing.session

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.joypad.Button
import eu.rekawek.coffeegb.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.joypad.Joypad
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.events.EventBusImpl
import eu.rekawek.coffeegb.gpu.Display.DmgFrameReadyEvent
import eu.rekawek.coffeegb.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.sound.Sound.SoundSampleEvent
import eu.rekawek.coffeegb.swing.events.funnel
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.network.Connection
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.swing.session.Session.Companion.createGameboyConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Thread.sleep
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LinkedSession(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Session {

  private val eventBus = parentEventBus.fork("session")

  private var mainConfig: GameboyConfiguration? = null

  private var peerConfig: GameboyConfiguration? = null

  private var mainGameboy: Gameboy? = null

  private var secondaryGameboy: Gameboy? = null

  private var mainEventBus: EventBus? = null

  private var secondaryEventBus: EventBus? = null

  @VisibleForTesting internal var stateHistory: StateHistory? = null

  @Volatile private var doStop = false

  @Volatile private var isStopped = false

  @Volatile private var doPause = false

  init {
    eventBus.register<Session.LoadRomEvent> {
      stop()

      val romBuffer = it.rom.toPath().readBytes()
      val saveFile = Cartridge.getSaveName(it.rom)
      val batteryBuffer =
          if (saveFile.exists()) {
            saveFile.toPath().readBytes()
          } else {
            null
          }
      val mainConfig = createGameboyConfig(properties, Rom(it.rom))
      eventBus.post(
          Session.WaitingForPeerEvent(
              romBuffer, batteryBuffer, mainConfig.gameboyType, mainConfig.bootstrapMode))
      start(mainConfig, null)
    }

    eventBus.register<PeerLoadedGameEvent> {
      val peerConfig =
          createGameboyConfig(properties, Rom(it.rom))
              .setGameboyType(it.gameboyType)
              .setBootstrapMode(it.bootstrapMode)
              .setBatteryData(it.battery)
      start(null, peerConfig)
    }

    eventBus.register<Session.StartEmulationEvent> { start(null, null) }
    eventBus.register<Session.PauseEmulationEvent> {
      pause()
      eventBus.post(Connection.RequestPauseEvent())
    }
    eventBus.register<Session.ResumeEmulationEvent> {
      resume()
      eventBus.post(Connection.RequestResumeEvent())
    }
    eventBus.register<Session.ResetEmulationEvent> {
      reset()
      eventBus.post(Connection.RequestResetEvent())
    }
    eventBus.register<Session.StopEmulationEvent> {
      stop()
      eventBus.post(Connection.RequestStopEvent())
    }
    eventBus.register<Connection.ReceivedRemoteResetEvent> { reset() }
    eventBus.register<Connection.ReceivedRemoteStopEvent> { stop() }
    eventBus.register<Connection.ReceivedRemotePauseEvent> { pause() }
    eventBus.register<Connection.ReceivedRemoteResumeEvent> { resume() }
  }

  @Synchronized
  @VisibleForTesting
  internal fun init(mainConfig: GameboyConfiguration, peerConfig: GameboyConfiguration): Runnable {
    stateHistory = StateHistory(mainConfig, peerConfig)

    val localMainEventBus = EventBusImpl(null, null, false)
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

    val localSecondaryEventBus = EventBusImpl(null, null, false)
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
  fun start(mainConfig: GameboyConfiguration?, peerConfig: GameboyConfiguration?) {
    if (mainConfig != null) {
      this.mainConfig = mainConfig
    }
    if (peerConfig != null) {
      this.peerConfig = peerConfig
    }
    if (this.mainConfig == null || this.peerConfig == null) {
      return
    }

    val tick = init(this.mainConfig!!, this.peerConfig!!)
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
    mainEventBus?.post(Session.EmulationStartedEvent(this.mainConfig!!.rom.title))
    mainEventBus?.post(Session.SessionPauseSupportEvent(true))
    mainEventBus?.post(Session.SessionSnapshotSupportEvent(null))
  }

  @Synchronized
  fun stop() {
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

    mainEventBus?.close()
    secondaryEventBus?.close()

    mainGameboy = null
    secondaryGameboy = null
    mainEventBus = null
    secondaryEventBus = null
  }

  @Synchronized
  fun reset() {
    stop()
    start(null, null)
  }

  fun pause() {
    doPause = true
  }

  fun resume() {
    doPause = false
  }

  override fun close() {
    stop()
    eventBus.close()
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
