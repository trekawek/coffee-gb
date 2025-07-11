package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.ConnectedGameboyStartedEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.WaitingForPeerEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.io.network.ButtonSender.SendSyncMessage
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
    eventBus.register<SendSyncMessage> {
      outputStream.write(0x03)
      val buf = ByteBuffer.allocate(8)
      buf.putInt(it.ticks)
      buf.putInt(it.events.size)
      outputStream.write(buf.array())

      val buttonBuffer = ByteBuffer.allocate(6)
      for (button in it.events) {
        buttonBuffer.putInt(button.tick)
        buttonBuffer.put(button.buttonPressed?.ordinal?.toByte() ?: -1)
        buttonBuffer.put(button.buttonReleased?.ordinal?.toByte() ?: -1)
        outputStream.write(buttonBuffer.array())
        buttonBuffer.rewind()
      }
      outputStream.flush()
      LOG.atDebug().log("Sent {}", it)
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
          val buf = ByteBuffer.allocate(8)
          if (inputStream.read(buf.array()) < 8) {
            return
          }
          val ticks = buf.getInt()
          val count = buf.getInt()
          val buttonBuffer = ByteBuffer.allocate(6)
          val buttons =
              (0 until count).map {
                if (inputStream.read(buttonBuffer.array()) < 6) {
                  return
                }
                val tick = buttonBuffer.getInt()
                val pressed = buttonBuffer.get()
                val released = buttonBuffer.get()
                buttonBuffer.rewind()
                ButtonEvent(tick, getButton(pressed), getButton(released))
              }
          val event = PeerButtonEvents(ticks, buttons)
          LOG.atDebug().log("Received message: {}", event)
          eventBus.post(event)
        }
      }
    }
  }

  private fun getButton(i: Byte): Button? {
    return if (i == (-1).toByte()) {
      null
    } else {
      Button.entries[i.toInt()]
    }
  }

  fun stop() {
    doStop = true
    inputStream.close()
  }

  data class PeerLoadedGameEvent(val romName: String) : Event

  class PeerIsReadyEvent : Event

  data class ButtonEvent(val tick: Int, val buttonPressed: Button?, val buttonReleased: Button?)

  data class PeerButtonEvents(val ticks: Int, val buttonEvents: List<ButtonEvent>) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
  }
}
