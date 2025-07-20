package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.ConnectedGameboyStartedEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.WaitingForPeerEvent
import eu.rekawek.coffeegb.swing.emulator.session.Input
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession.LocalButtonStateEvent
import eu.rekawek.coffeegb.swing.events.register
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile

class Connection(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    mainEventBus: EventBus,
) : Runnable {

  private val eventBus: EventBus = mainEventBus.fork("connection")

  @Volatile private var doStop = false

  @Volatile private var romFile: File? = null

  init {
    eventBus.register<WaitingForPeerEvent> {
      romFile = it.romFile
      outputStream.write(0x01)
      outputStream.write(it.romName.padEnd(16).toByteArray(Charsets.US_ASCII))
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
    }
    eventBus.register<ConnectedGameboyStartedEvent> {
      outputStream.write(0x02)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
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
    eventBus.register<RequestRomEvent> {
      outputStream.write(0x04)
      outputStream.flush()
      LOG.atInfo().log("Sent {}", it)
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
              val title = inputStream.readNBytes(16).toString(Charsets.US_ASCII).trim()
              PeerLoadedGameEvent(title)
            }
            // peer is ready
            0x02 -> {
              PeerIsReadyEvent()
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
            // request rom
            0x04 -> {
              LOG.atInfo().log("Received remote command $command, sending ROM $romFile")

              val rom = romFile?.readBytes() ?: ByteArray(0)
              val buf = ByteBuffer.allocate(4)
              buf.putInt(rom.size)
              outputStream.write(0x05)
              outputStream.write(buf.array())
              outputStream.write(rom)
              outputStream.flush()

              null
            }

            // receiver rom
            0x05 -> {
              val buf = ByteBuffer.allocate(4)
              inputStream.read(buf.array())
              val size = buf.getInt()
              val rom = inputStream.readNBytes(size)
              ReceivedRomEvent(rom)
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
          is ReceivedRomEvent ->
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
    eventBus.stop()
    inputStream.close()
  }

  data class PeerLoadedGameEvent(val romName: String) : Event

  class PeerIsReadyEvent : Event

  class RequestRomEvent : Event

  data class ReceivedRomEvent(val rom: ByteArray) : Event

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
