package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.WaitingForPeerEvent
import eu.rekawek.coffeegb.swing.emulator.session.Input
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession.LocalButtonStateEvent
import eu.rekawek.coffeegb.swing.events.register
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile

class Connection(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    mainEventBus: EventBus,
) : Runnable, AutoCloseable {

  private val eventBus: EventBus = mainEventBus.fork("connection")

  @Volatile private var doStop = false

  init {
    eventBus.register<WaitingForPeerEvent> {
      outputStream.write(0x01)

      val buf = ByteBuffer.allocate(8)
      buf.putInt(it.romFile.size)
      buf.putInt(it.batteryFile?.size ?: 0)
      buf.rewind()

      outputStream.write(buf.array())
      outputStream.write(it.romFile)
      if (it.batteryFile != null) {
        outputStream.write(it.batteryFile)
      }
    }
    eventBus.register<LocalButtonStateEvent> {
      outputStream.write(0x03)
      val buf = ByteBuffer.allocate(10)
      buf.putLong(it.frame)
      buf.put(it.input.pressedButtons.size.toByte())
      buf.put(it.input.releasedButtons.size.toByte())
      outputStream.write(buf.array())

      for (button in it.input.pressedButtons) {
        outputStream.write(button.ordinal)
      }
      for (button in it.input.releasedButtons) {
        outputStream.write(button.ordinal)
      }
      outputStream.flush()
      LOG.atDebug().log("Sent {}", it)
    }
    eventBus.register<RequestResetEvent> {
      outputStream.write(0x06)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
    }
    eventBus.register<RequestStopEvent> {
      outputStream.write(0x07)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
    }
    eventBus.register<RequestPauseEvent> {
      outputStream.write(0x08)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
    }
    eventBus.register<RequestResumeEvent> {
      outputStream.write(0x09)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
    }
  }

  override fun run() {
    while (!doStop) {
      val command = inputStream.read()
      if (command == -1) {
        return
      }
      val event: Event? =
          when (command) {
            // peer loaded game
            0x01 -> {
              val buf = ByteBuffer.allocate(8)
              inputStream.read(buf.array())

              val romSize = buf.getInt()
              val batterySize = buf.getInt()

              val rom = inputStream.readNBytes(romSize)
              val battery =
                  if (batterySize > 0) {
                    inputStream.readNBytes(batterySize)
                  } else {
                    null
                  }

              PeerLoadedGameEvent(rom, battery)
            }
            // sync
            0x03 -> {
              val buf = ByteBuffer.allocate(10)
              inputStream.read(buf.array())

              val frame = buf.getLong()
              val pressedCount = buf.get()
              val releasedCount = buf.get()
              val pressed = readButtons(pressedCount.toInt())
              val released = readButtons(releasedCount.toInt())
              LinkedSession.RemoteButtonStateEvent(frame, Input(pressed, released))
            }

            // reset
            0x06 -> {
              ReceivedRemoteResetEvent()
            }

            // stop
            0x07 -> {
              ReceivedRemoteStopEvent()
            }

            // pause
            0x08 -> {
              ReceivedRemotePauseEvent()
            }

            // stop
            0x09 -> {
              ReceivedRemoteResumeEvent()
            }

            else -> {
              LOG.atWarn().log("Received remote unknown command $command")
              null
            }
          }
      if (event != null) {
        when (event) {
          is PeerLoadedGameEvent ->
              LOG.atInfo()
                  .log("Received remote command $command, posting event: ${event.javaClass}")
          is LinkedSession.RemoteButtonStateEvent ->
              LOG.atDebug().log("Received remote command $command, posting event: $event")
          else -> LOG.atInfo().log("Received remote command $command, posting event: $event")
        }

        eventBus.post(event)
      }
    }
  }

  private fun readButtons(count: Int): List<Button> {
    return (0 until count).map {
      val buttonId = inputStream.read()
      Button.entries[buttonId]
    }
  }

  fun stop() {
    doStop = true
  }

  override fun close() {
    eventBus.stop()
    inputStream.close()
    outputStream.close()
  }

  data class PeerLoadedGameEvent(val rom: ByteArray, val battery: ByteArray?) : Event

  class RequestResetEvent : Event

  class RequestStopEvent : Event

  class RequestPauseEvent : Event

  class RequestResumeEvent : Event

  class ReceivedRemoteResetEvent : Event

  class ReceivedRemoteStopEvent : Event

  class ReceivedRemotePauseEvent : Event

  class ReceivedRemoteResumeEvent : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
  }
}
