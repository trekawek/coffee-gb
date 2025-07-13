package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.ConnectedGameboyStartedEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.WaitingForPeerEvent
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession.LocalButtonStateEvent
import eu.rekawek.coffeegb.swing.events.register
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Connection(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val eventBus: EventBus,
) : Runnable {

  @Volatile private var doStop = false

  init {
    eventBus.register<WaitingForPeerEvent> {
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
      buf.put(it.pressedButtons.size.toByte())
      buf.put(it.releasedButtons.size.toByte())
      outputStream.write(buf.array())

      for (button in it.pressedButtons) {
        outputStream.write(button.ordinal)
      }
      for (button in it.releasedButtons) {
        outputStream.write(button.ordinal)
      }
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
      when (command) {
        // peer loaded game
        0x01 -> {
          val title = inputStream.readNBytes(16).toString(Charsets.US_ASCII).trim()
          val event = PeerLoadedGameEvent(title)
          LOG.atInfo().log("Received message: {}", event)
          eventBus.post(event)
        }
        // peer is ready
        0x02 -> {
          val event = PeerIsReadyEvent()
          LOG.atInfo().log("Received message: {}", event)
          eventBus.post(event)
        }
        // sync
        0x03 -> {
          val buf = ByteBuffer.allocate(10)
          if (inputStream.read(buf.array()) < 10) {
            return
          }
          val frame = buf.getLong()
          val pressedCount = buf.get()
          val releasedCount = buf.get()
          val pressed = readButtons(pressedCount.toInt())
          val released = readButtons(releasedCount.toInt())
          val event = LinkedSession.RemoteButtonStateEvent(frame, pressed, released)
          LOG.atInfo().log("Received message: {}", event)
          eventBus.post(event)
        }
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
    inputStream.close()
  }

  data class PeerLoadedGameEvent(val romName: String) : Event

  class PeerIsReadyEvent : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
  }
}
