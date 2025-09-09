package eu.rekawek.coffeegb.controller.controller

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.controller.controller.Controller.Companion.createGameboyConfig
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.Connection
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import java.lang.Thread.sleep
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LinkedController(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Controller {

  private val eventBus = parentEventBus.fork("session")

  private var mainSession: Session? = null

  private var peerSession: Session? = null

  private var mainConfig: GameboyConfiguration? = null

  private var peerConfig: GameboyConfiguration? = null

  @VisibleForTesting internal var stateHistory: StateHistory? = null

  @Volatile private var doStop = false

  @Volatile private var isStopped = false

  @Volatile private var doPause = false

  init {
    eventBus.register<Controller.LoadRomEvent> {
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
          Controller.WaitingForPeerEvent(
              romBuffer, batteryBuffer, mainConfig.gameboyType, mainConfig.bootstrapMode))
      start(mainConfig, null)
    }

    eventBus.register<PeerLoadedGameEvent> {
      stop()

      val peerConfig =
          createGameboyConfig(properties, Rom(it.rom))
              .setGameboyType(it.gameboyType)
              .setBootstrapMode(it.bootstrapMode)
              .setBatteryData(it.battery)
      start(null, peerConfig)
    }

    eventBus.register<Controller.PauseEmulationEvent> {
      pause()
      eventBus.post(Connection.RequestPauseEvent())
    }
    eventBus.register<Controller.ResumeEmulationEvent> {
      resume()
      eventBus.post(Connection.RequestResumeEvent())
    }
    eventBus.register<Controller.ResetEmulationEvent> {
      reset()
      eventBus.post(Connection.RequestResetEvent())
    }
    eventBus.register<Controller.StopEmulationEvent> {
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

    val mainSerialEndpoint = Peer2PeerSerialEndpoint()
    val peerSerialEndpoint = Peer2PeerSerialEndpoint()
    peerSerialEndpoint.init(mainSerialEndpoint)

    val mainSession = Session(mainConfig, eventBus.fork("main"), console, mainSerialEndpoint)
    val peerSession = Session(peerConfig, EventBusImpl(null, null, false), null, peerSerialEndpoint)

    val mainButtonMonitor = Object()
    val mainPressedButtons = mutableSetOf<Button>()
    val mainReleasedButtons = mutableSetOf<Button>()
    var lastInput = Input(emptyList(), emptyList())
    mainSession.eventBus.register<eu.rekawek.coffeegb.core.joypad.ButtonPressEvent> {
      synchronized(mainButtonMonitor) {
        mainPressedButtons.add(it.button)
        mainReleasedButtons.remove(it.button)
      }
    }
    mainSession.eventBus.register<eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent> {
      synchronized(mainButtonMonitor) {
        mainPressedButtons.remove(it.button)
        mainReleasedButtons.add(it.button)
      }
    }
    mainSession.eventBus.register<RemoteButtonStateEvent> {
      stateHistory!!.addSecondaryInput(it.frame, it.input)
    }

    this.mainSession = mainSession
    this.peerSession = peerSession

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
            mainSession.restoreFromMemento(head.mainMemento)
            peerSession.restoreFromMemento(head.peerMemento)
            frame = head.frame
            LOG.atDebug().log("State merged to {}", frame)
          }

          stateHistory!!.addState(
              frame, effectiveInput, mainSession.saveToMemento(), peerSession.saveToMemento())

          val now = TimeSource.Monotonic.markNow()
          if (!effectiveInput.isEmpty() || now - lastSync > 5.seconds) {
            eventBus.postAsync(LocalButtonStateEvent(frame, effectiveInput))
            effectiveInput.send(mainSession.eventBus)
            lastSync = now
          }
        }

        mainSession.gameboy.tick()
        peerSession.gameboy.tick()
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

    mainSession?.eventBus?.post(Controller.GameboyTypeEvent(this.mainConfig!!.gameboyType))
    mainSession?.eventBus?.post(Controller.SessionPauseSupportEvent(true))
    mainSession?.eventBus?.post(Controller.SessionSnapshotSupportEvent(null))
    mainSession?.eventBus?.post(Controller.EmulationStartedEvent(this.mainConfig!!.rom.title))

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
  }

  @Synchronized
  fun stop() {
    if (mainSession == null) {
      return
    }
    doStop = true
    while (!isStopped) {}
    doPause = false

    mainSession?.eventBus?.post(Controller.EmulationStoppedEvent())

    mainSession?.close()
    peerSession?.close()

    mainSession = null
    peerSession = null
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
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)
  }
}
