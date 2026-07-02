package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.joypad.Button
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The wire protocol of the netplay link. Messages are assembled in memory and written with
 * a single write+flush, so every message leaves as one TCP segment (the sockets run with
 * TCP_NODELAY; unbuffered byte-wise writes would emit a segment per byte). Reads go through
 * a buffered stream and use readFully - a plain read() may return a partial header on a
 * fragmented connection, which would desynchronize the protocol. Large payloads (ROM,
 * battery save, snapshot) are deflate-compressed.
 */
class Connection(
    inputStream: InputStream,
    outputStream: OutputStream,
    mainEventBus: EventBus,
    private val server: Boolean,
) : Runnable, AutoCloseable {

  private val input = DataInputStream(BufferedInputStream(inputStream))

  private val output = DataOutputStream(BufferedOutputStream(outputStream))

  private val eventBus: EventBus = mainEventBus.fork("connection")

  @Volatile private var doStop = false

  init {
    // the handshake must be on the wire before any registered handler can write a
    // message, otherwise an event arriving between construction and run() would put
    // its bytes in front of the handshake and desynchronize the peer
    handshake()
    eventBus.register<LinkedController.LocalRomLoadedEvent> {
      val rom = deflate(it.romFile)
      val battery = it.batteryFile?.let(::deflate)
      val snapshot = it.snapshot?.let(::deflate)

      val buf = ByteBuffer.allocate(1 + 22 + 3 * 4 + rom.size + (battery?.size ?: 0) + (snapshot?.size ?: 0))
      buf.put(0x01)
      buf.putLong(it.frame)
      buf.put(it.gameboyType.ordinal.toByte())
      buf.put(it.bootstrapMode.ordinal.toByte())
      buf.putInt(it.romFile.size)
      buf.putInt(it.batteryFile?.size ?: 0)
      buf.putInt(it.snapshot?.size ?: 0)
      buf.putInt(rom.size)
      buf.putInt(battery?.size ?: 0)
      buf.putInt(snapshot?.size ?: 0)
      buf.put(rom)
      battery?.let(buf::put)
      snapshot?.let(buf::put)
      sendMessage(buf)
      LOG.atInfo().log(
          "Sent rom ({} -> {} bytes compressed)", it.romFile.size, rom.size)
    }
    eventBus.register<LinkedController.LocalButtonStateEvent> {
      val buf = ByteBuffer.allocate(
          1 + 10 + it.input.pressedButtons.size + it.input.releasedButtons.size)
      buf.put(0x03)
      buf.putLong(it.frame)
      buf.put(it.input.pressedButtons.size.toByte())
      buf.put(it.input.releasedButtons.size.toByte())
      for (button in it.input.pressedButtons) {
        buf.put(button.ordinal.toByte())
      }
      for (button in it.input.releasedButtons) {
        buf.put(button.ordinal.toByte())
      }
      sendMessage(buf)
      LOG.atDebug().log("Sent {}", it)
    }
    eventBus.register<RequestResetEvent> {
      val buf = ByteBuffer.allocate(9)
      buf.put(0x06)
      buf.putLong(it.frame)
      sendMessage(buf)
      LOG.atInfo().log("Sent {}", it)
    }
    eventBus.register<RequestStopEvent> {
      val buf = ByteBuffer.allocate(9)
      buf.put(0x07)
      buf.putLong(it.frame)
      sendMessage(buf)
      LOG.atInfo().log("Sent {}", it)
    }
  }

  /**
   * Event handlers run on different event-bus threads; the whole message goes out
   * atomically and in one flush.
   */
  @Synchronized
  private fun sendMessage(buf: ByteBuffer) {
    output.write(buf.array(), 0, buf.position())
    output.flush()
  }

  override fun run() {
    while (!doStop) {
      val command =
          try {
            input.read()
          } catch (e: IOException) {
            if (doStop) -1 else throw e
          }
      if (command == -1) {
        return
      }
      val event: Event? =
          when (command) {
            // peer loaded game
            0x01 -> {
              val header = ByteArray(22 + 3 * 4)
              input.readFully(header)
              val buf = ByteBuffer.wrap(header)

              val frame = buf.getLong()
              val gameboyType = GameboyType.entries[buf.get().toInt()]
              val bootstrapMode = BootstrapMode.entries[buf.get().toInt()]
              val romSize = buf.getInt()
              val batterySize = buf.getInt()
              val snapshotSize = buf.getInt()
              val romCompressed = buf.getInt()
              val batteryCompressed = buf.getInt()
              val snapshotCompressed = buf.getInt()

              val rom = inflate(readPayload(romCompressed), romSize)!!
              val battery = inflate(readPayload(batteryCompressed), batterySize)
              val snapshot = inflate(readPayload(snapshotCompressed), snapshotSize)

              PeerLoadedGameEvent(rom, battery, snapshot, gameboyType, bootstrapMode, frame)
            }
            // sync
            0x03 -> {
              val header = ByteArray(10)
              input.readFully(header)
              val buf = ByteBuffer.wrap(header)

              val frame = buf.getLong()
              val pressedCount = buf.get().toInt()
              val releasedCount = buf.get().toInt()
              val buttons = ByteArray(pressedCount + releasedCount)
              input.readFully(buttons)
              val pressed = (0 until pressedCount).map { Button.entries[buttons[it].toInt()] }
              val released =
                  (0 until releasedCount).map { Button.entries[buttons[pressedCount + it].toInt()] }
              LinkedController.RemoteButtonStateEvent(frame, Input(pressed, released))
            }

            // reset
            0x06 -> {
              ReceivedRemoteResetEvent(readFrame())
            }

            // stop
            0x07 -> {
              ReceivedRemoteStopEvent(readFrame())
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
          is LinkedController.RemoteButtonStateEvent ->
              LOG.atDebug().log("Received remote command $command, posting event: $event")
          else -> LOG.atInfo().log("Received remote command $command, posting event: $event")
        }

        eventBus.post(event)
      }
    }
  }

  private fun readFrame(): Long {
    val buf = ByteArray(8)
    input.readFully(buf)
    return ByteBuffer.wrap(buf).getLong()
  }

  private fun readPayload(size: Int): ByteArray? {
    if (size == 0) {
      return null
    }
    val payload = ByteArray(size)
    input.readFully(payload)
    return payload
  }

  fun stop() {
    doStop = true
  }

  override fun close() {
    eventBus.close()
    input.close()
    output.close()
  }

  private fun handshake() {
    val buf = ByteArray(PROTOCOL_NAME.length + 1)
    if (server) {
      PROTOCOL_NAME.toByteArray().copyInto(buf)
      buf[PROTOCOL_NAME.length] = PROTOCOL_VERSION
      output.write(buf)
      output.flush()
      LOG.atInfo().log("Sent protocol name {} and version {}", PROTOCOL_NAME, PROTOCOL_VERSION)
    } else {
      input.readFully(buf)
      val receivedProtocolName = String(buf, 0, PROTOCOL_NAME.length)
      if (receivedProtocolName != PROTOCOL_NAME) {
        throw IOException(
            "Protocol mismatch: expected $PROTOCOL_NAME, received $receivedProtocolName"
        )
      }
      val receivedProtocolVersion = buf[PROTOCOL_NAME.length]
      if (receivedProtocolVersion != PROTOCOL_VERSION) {
        throw IOException(
            "Protocol mismatch: expected $PROTOCOL_VERSION, received $receivedProtocolVersion"
        )
      }
      LOG.atInfo()
          .log(
              "Received protocol name {} and version {}",
              receivedProtocolName,
              receivedProtocolVersion,
          )
    }
  }

  data class PeerLoadedGameEvent(
      val rom: ByteArray,
      val battery: ByteArray?,
      val snapshot: ByteArray?,
      val gameboyType: GameboyType,
      val bootstrapMode: BootstrapMode,
      val frame: Long,
  ) : Event

  data class RequestResetEvent(val frame: Long) : Event

  data class RequestStopEvent(val frame: Long) : Event

  data class ReceivedRemoteResetEvent(val frame: Long) : Event

  data class ReceivedRemoteStopEvent(val frame: Long) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
    private const val PROTOCOL_NAME: String = "CoffeeGB NETPLAY"
    private const val PROTOCOL_VERSION: Byte = 0x03

    fun deflate(data: ByteArray): ByteArray {
      val deflater = Deflater(Deflater.BEST_SPEED)
      deflater.setInput(data)
      deflater.finish()
      val out = ByteArrayOutputStream(data.size / 2 + 64)
      val buffer = ByteArray(64 * 1024)
      while (!deflater.finished()) {
        val n = deflater.deflate(buffer)
        out.write(buffer, 0, n)
      }
      deflater.end()
      return out.toByteArray()
    }

    fun inflate(data: ByteArray?, originalSize: Int): ByteArray? {
      if (data == null) {
        return null
      }
      val inflater = Inflater()
      inflater.setInput(data)
      val result = ByteArray(originalSize)
      var offset = 0
      while (offset < originalSize && !inflater.finished()) {
        val n = inflater.inflate(result, offset, originalSize - offset)
        if (n == 0 && inflater.needsInput()) {
          throw IOException("Truncated compressed payload")
        }
        offset += n
      }
      inflater.end()
      if (offset != originalSize) {
        throw IOException("Corrupted compressed payload: expected $originalSize, got $offset")
      }
      return result
    }
  }
}
