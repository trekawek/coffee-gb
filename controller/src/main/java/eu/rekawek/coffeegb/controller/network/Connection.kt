package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
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

/** One netplay socket. On a four-player host, three instances share the root event bus. */
class Connection(
    inputStream: InputStream,
    outputStream: OutputStream,
    mainEventBus: EventBus,
    private val server: Boolean,
    requestedMode: LinkMode = LinkMode.NORMAL,
    assignedPlayer: Int = 1,
) : Runnable, AutoCloseable {

  private val input = DataInputStream(BufferedInputStream(inputStream))

  private val output = DataOutputStream(BufferedOutputStream(outputStream))

  private val eventBus: EventBus = mainEventBus.fork("connection-$assignedPlayer")

  val mode: LinkMode

  /** The player at the remote end when hosting, or this application's player when connected. */
  val player: Int

  @Volatile private var doStop = false

  private var sessionActive = server

  private val pendingEvents = mutableListOf<Event>()

  private val pendingCheckpointStates = mutableListOf<PeerLoadedGameEvent>()

  init {
    val handshake = handshake(requestedMode, assignedPlayer)
    mode = handshake.mode
    player = handshake.player

    eventBus.register<LinkedController.LocalRomLoadedEvent> {
      // Four-player host state is sent as an atomic SessionStateReadyEvent checkpoint. Sending the
      // ordinary host ROM event as well would let clients run before the adapter memento arrives.
      if (shouldSendLocal(it.player) && (!server || mode != LinkMode.FOUR_PLAYER_ADAPTER)) {
        sendRom(it)
      }
    }
    eventBus.register<LinkedController.LocalButtonStateEvent> {
      if (shouldSendLocal(it.player)) sendButtons(it)
    }
    eventBus.register<RequestResetEvent> {
      if (shouldSendLocal(it.player)) sendFrameCommand(RESET, it.frame, it.player)
    }
    eventBus.register<RequestStopEvent> {
      if (shouldSendLocal(it.player)) sendFrameCommand(STOP, it.frame, it.player)
    }

    // Only host-side connections see relay events. The originating player's connection is
    // skipped; every other client receives the exact same player-labelled message.
    eventBus.register<RelayRomEvent> {
      if (server && player != it.sourcePlayer) sendRom(it.event)
    }
    eventBus.register<RelayButtonsEvent> {
      if (server && player != it.sourcePlayer) sendButtons(it.event)
    }
    eventBus.register<RelayResetEvent> {
      if (server && player != it.sourcePlayer) {
        sendFrameCommand(RESET, it.event.frame, it.event.player)
      }
    }
    eventBus.register<RelayStopEvent> {
      if (server && player != it.sourcePlayer) {
        sendFrameCommand(STOP, it.event.frame, it.event.player)
      }
    }
    eventBus.register<LinkedController.SessionStateReadyEvent> {
      if (server && mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        it.states.forEach(::sendRom)
        sendSynchronization(it.frame)
      }
    }
  }

  private fun shouldSendLocal(eventPlayer: Int): Boolean =
      if (server) eventPlayer == 0 else eventPlayer == player

  private fun sendRom(event: LinkedController.LocalRomLoadedEvent) {
    val rom = deflate(event.romFile)
    val battery = event.batteryFile?.let(::deflate)
    val snapshot = event.snapshot?.let(::deflate)
    val sessionSnapshot = event.sessionSnapshot?.let(::deflate)
    val heldButtons = event.heldButtons.sorted()
    val buf =
        ByteBuffer.allocate(
            1 + ROM_HEADER_SIZE + heldButtons.size + rom.size + (battery?.size ?: 0) +
                (snapshot?.size ?: 0) + (sessionSnapshot?.size ?: 0))
    buf.put(ROM)
    buf.put(event.player.toByte())
    buf.putLong(event.frame)
    buf.put(event.gameboyType.ordinal.toByte())
    buf.put(event.bootstrapMode.ordinal.toByte())
    buf.put(if (event.cgb0Revision) 1.toByte() else 0.toByte())
    buf.put(heldButtons.size.toByte())
    buf.putInt(event.romFile.size)
    buf.putInt(event.batteryFile?.size ?: 0)
    buf.putInt(event.snapshot?.size ?: 0)
    buf.putInt(event.sessionSnapshot?.size ?: 0)
    buf.putInt(rom.size)
    buf.putInt(battery?.size ?: 0)
    buf.putInt(snapshot?.size ?: 0)
    buf.putInt(sessionSnapshot?.size ?: 0)
    heldButtons.forEach { buf.put(it.ordinal.toByte()) }
    buf.put(rom)
    battery?.let(buf::put)
    snapshot?.let(buf::put)
    sessionSnapshot?.let(buf::put)
    sendMessage(buf)
    LOG.atInfo().log("Sent player {} ROM ({} -> {} bytes compressed)", event.player + 1,
        event.romFile.size, rom.size)
  }

  private fun sendButtons(event: LinkedController.LocalButtonStateEvent) {
    val buf =
        ByteBuffer.allocate(
            1 + 11 + event.input.pressedButtons.size + event.input.releasedButtons.size)
    buf.put(INPUT)
    buf.put(event.player.toByte())
    buf.putLong(event.frame)
    buf.put(event.input.pressedButtons.size.toByte())
    buf.put(event.input.releasedButtons.size.toByte())
    event.input.pressedButtons.forEach { buf.put(it.ordinal.toByte()) }
    event.input.releasedButtons.forEach { buf.put(it.ordinal.toByte()) }
    sendMessage(buf)
    LOG.atDebug().log("Sent {}", event)
  }

  private fun sendFrameCommand(command: Byte, frame: Long, eventPlayer: Int) {
    val buf = ByteBuffer.allocate(10)
    buf.put(command)
    buf.put(eventPlayer.toByte())
    buf.putLong(frame)
    sendMessage(buf)
  }

  private fun sendSynchronization(frame: Long) {
    val buf = ByteBuffer.allocate(9)
    buf.put(SYNCHRONIZE)
    buf.putLong(frame)
    sendMessage(buf)
  }

  @Synchronized
  private fun sendMessage(buf: ByteBuffer) {
    if (doStop) return
    output.write(buf.array(), 0, buf.position())
    output.flush()
  }

  /** Releases a client after the host has accepted its physical link port. */
  fun startSession() {
    check(server)
    val buf = ByteBuffer.allocate(1)
    buf.put(START)
    sendMessage(buf)
  }

  override fun run() {
    while (!doStop) {
      val command =
          try {
            input.read()
          } catch (e: IOException) {
            if (doStop) -1 else throw e
          }
      if (command == -1) return

      when (command.toByte()) {
        ROM -> receiveRom()
        INPUT -> receiveButtons()
        RESET -> receiveReset()
        STOP -> receiveStop()
        SYNCHRONIZE -> {
          if (!server) {
            val frame = input.readLong()
            val states = pendingCheckpointStates.toList()
            pendingCheckpointStates.clear()
            deliver(SessionCheckpointEvent(frame, states))
          } else {
            throw IOException("Client sent a server-only synchronization command")
          }
        }
        START -> {
          if (!server) {
            sessionActive = true
            eventBus.post(ConnectionController.ClientConnectedToServerEvent(mode, player))
            // The host may have sent its ROM before START. The connection is already listening at
            // that point, but the LinkedController is created by the event above; deliver cached
            // state only after that synchronous transition has completed.
            pendingEvents.forEach(eventBus::post)
            pendingEvents.clear()
          }
        }
        else -> LOG.atWarn().log("Received remote unknown command $command")
      }
    }
  }

  private fun receiveRom() {
    val header = ByteArray(ROM_HEADER_SIZE)
    input.readFully(header)
    val buf = ByteBuffer.wrap(header)
    val wirePlayer = buf.get().toInt()
    val eventPlayer = receivedPlayer(wirePlayer)
    val frame = buf.getLong()
    val gameboyType = GameboyType.entries[buf.get().toInt()]
    val bootstrapMode = BootstrapMode.entries[buf.get().toInt()]
    val cgb0Revision = buf.get().toInt() != 0
    val heldCount = buf.get().toInt() and 0xff
    if (heldCount > Button.entries.size) throw IOException("Invalid held button count $heldCount")
    val romSize = buf.getInt()
    val batterySize = buf.getInt()
    val snapshotSize = buf.getInt()
    val sessionSnapshotSize = buf.getInt()
    val romCompressed = buf.getInt()
    val batteryCompressed = buf.getInt()
    val snapshotCompressed = buf.getInt()
    val sessionSnapshotCompressed = buf.getInt()
    val heldButtons = ByteArray(heldCount).also(input::readFully).map {
      val ordinal = it.toInt() and 0xff
      if (ordinal !in Button.entries.indices) throw IOException("Invalid held button $ordinal")
      Button.entries[ordinal]
    }.toSet()
    val event =
        PeerLoadedGameEvent(
            rom = inflate(readPayload(romCompressed), romSize)!!,
            battery = inflate(readPayload(batteryCompressed), batterySize),
            snapshot = inflate(readPayload(snapshotCompressed), snapshotSize),
            gameboyType = gameboyType,
            bootstrapMode = bootstrapMode,
            frame = frame,
            cgb0Revision = cgb0Revision,
            player = eventPlayer,
            sessionSnapshot =
                inflate(readPayload(sessionSnapshotCompressed), sessionSnapshotSize),
            heldButtons = heldButtons,
        )
    if (!server && mode == LinkMode.FOUR_PLAYER_ADAPTER && event.sessionSnapshot != null) {
      pendingCheckpointStates += event
    } else {
      deliver(event)
    }
    if (server && mode != LinkMode.FOUR_PLAYER_ADAPTER) {
      eventBus.post(
          RelayRomEvent(
              eventPlayer,
              LinkedController.LocalRomLoadedEvent(
                  event.rom,
                  event.battery,
                  event.snapshot,
                  event.gameboyType,
                  event.bootstrapMode,
                  event.frame,
                  event.cgb0Revision,
                  event.player,
                  event.sessionSnapshot,
                  event.heldButtons,
              )))
    }
    LOG.atInfo().log("Received player {} ROM", eventPlayer + 1)
  }

  private fun receiveButtons() {
    val header = ByteArray(11)
    input.readFully(header)
    val buf = ByteBuffer.wrap(header)
    val eventPlayer = receivedPlayer(buf.get().toInt())
    val frame = buf.getLong()
    val pressedCount = buf.get().toInt()
    val releasedCount = buf.get().toInt()
    val buttons = ByteArray(pressedCount + releasedCount)
    input.readFully(buttons)
    val pressed = (0 until pressedCount).map { Button.entries[buttons[it].toInt()] }
    val released =
        (0 until releasedCount).map { Button.entries[buttons[pressedCount + it].toInt()] }
    val event = LinkedController.RemoteButtonStateEvent(frame, Input(pressed, released), eventPlayer)
    deliver(event)
    if (server) {
      eventBus.post(
          RelayButtonsEvent(
              eventPlayer,
              LinkedController.LocalButtonStateEvent(event.frame, event.input, event.player),
          ))
    }
  }

  private fun receiveReset() {
    receiveFrameEvent(RESET)
  }

  private fun receiveStop() {
    receiveFrameEvent(STOP)
  }

  private fun receiveFrameEvent(command: Byte) {
    val (eventPlayer, frame) = readPlayerAndFrame()
    if (command == RESET) {
      val event = ReceivedRemoteResetEvent(frame, eventPlayer)
      deliver(event)
      if (server) eventBus.post(RelayResetEvent(eventPlayer, event))
    } else {
      val event = ReceivedRemoteStopEvent(frame, eventPlayer)
      deliver(event)
      if (server && mode != LinkMode.FOUR_PLAYER_ADAPTER) {
        eventBus.post(RelayStopEvent(eventPlayer, event))
      }
    }
  }

  private fun readPlayerAndFrame(): Pair<Int, Long> {
    val payload = ByteArray(9)
    input.readFully(payload)
    val buf = ByteBuffer.wrap(payload)
    return receivedPlayer(buf.get().toInt()) to buf.getLong()
  }

  private fun receivedPlayer(wirePlayer: Int): Int {
    if (server) return player
    if (wirePlayer !in 0 until mode.playerCount) {
      throw IOException("Invalid player $wirePlayer for $mode")
    }
    return wirePlayer
  }

  private fun deliver(event: Event) {
    if (sessionActive) {
      eventBus.post(event)
    } else {
      pendingEvents += event
    }
  }

  private fun readPayload(size: Int): ByteArray? {
    if (size == 0) return null
    if (size < 0 || size > MAX_COMPRESSED_PAYLOAD) {
      throw IOException("Invalid compressed payload size: $size")
    }
    return ByteArray(size).also(input::readFully)
  }

  fun stop() {
    doStop = true
  }

  override fun close() {
    doStop = true
    eventBus.close()
    input.close()
    output.close()
  }

  private fun handshake(requestedMode: LinkMode, assignedPlayer: Int): Handshake {
    val buf = ByteArray(PROTOCOL_NAME.length + 3)
    if (server) {
      require(assignedPlayer in 1 until requestedMode.playerCount)
      PROTOCOL_NAME.toByteArray().copyInto(buf)
      buf[PROTOCOL_NAME.length] = PROTOCOL_VERSION
      buf[PROTOCOL_NAME.length + 1] = requestedMode.ordinal.toByte()
      buf[PROTOCOL_NAME.length + 2] = assignedPlayer.toByte()
      output.write(buf)
      output.flush()
      return Handshake(requestedMode, assignedPlayer)
    }

    input.readFully(buf)
    val receivedProtocolName = String(buf, 0, PROTOCOL_NAME.length)
    if (receivedProtocolName != PROTOCOL_NAME) {
      throw IOException("Protocol mismatch: expected $PROTOCOL_NAME, received $receivedProtocolName")
    }
    val receivedVersion = buf[PROTOCOL_NAME.length]
    if (receivedVersion != PROTOCOL_VERSION) {
      throw IOException("Protocol mismatch: expected $PROTOCOL_VERSION, received $receivedVersion")
    }
    val modeOrdinal = buf[PROTOCOL_NAME.length + 1].toInt() and 0xff
    if (modeOrdinal == REJECTION_MARKER) {
      val reasonCode = buf[PROTOCOL_NAME.length + 2].toInt() and 0xff
      val reason =
          RejectionReason.entries.firstOrNull { it.wireCode == reasonCode }
              ?: throw IOException("Server rejected the connection with unknown reason $reasonCode")
      throw ConnectionRejectedException(reason)
    }
    if (modeOrdinal !in LinkMode.entries.indices) throw IOException("Invalid link mode $modeOrdinal")
    val receivedMode = LinkMode.entries[modeOrdinal]
    val receivedPlayer = buf[PROTOCOL_NAME.length + 2].toInt()
    if (receivedPlayer !in 1 until receivedMode.playerCount) {
      throw IOException("Invalid assigned player $receivedPlayer for $receivedMode")
    }
    LOG.atInfo().log("Connected as player {} in {} mode", receivedPlayer + 1, receivedMode)
    return Handshake(receivedMode, receivedPlayer)
  }

  private data class Handshake(val mode: LinkMode, val player: Int)

  internal enum class RejectionReason(
      val wireCode: Int,
      val userMessage: String,
  ) {
    SERVER_FULL(1, "The netplay server is already full."),
  }

  internal class ConnectionRejectedException(val reason: RejectionReason) :
      IOException(reason.userMessage)

  data class PeerLoadedGameEvent(
      val rom: ByteArray,
      val battery: ByteArray?,
      val snapshot: ByteArray?,
      val gameboyType: GameboyType,
      val bootstrapMode: BootstrapMode,
      val frame: Long,
      val cgb0Revision: Boolean = false,
      val player: Int = 1,
      val sessionSnapshot: ByteArray? = null,
      val heldButtons: Set<Button> = emptySet(),
  ) : Event

  data class SessionCheckpointEvent(
      val frame: Long,
      val states: List<PeerLoadedGameEvent>,
  ) : Event

  data class RequestResetEvent(val frame: Long, val player: Int = 0) : Event

  data class RequestStopEvent(val frame: Long, val player: Int = 0) : Event

  data class ReceivedRemoteResetEvent(val frame: Long, val player: Int = 1) : Event

  data class ReceivedRemoteStopEvent(val frame: Long, val player: Int = 1) : Event

  private data class RelayRomEvent(
      val sourcePlayer: Int,
      val event: LinkedController.LocalRomLoadedEvent,
  ) : Event

  private data class RelayButtonsEvent(
      val sourcePlayer: Int,
      val event: LinkedController.LocalButtonStateEvent,
  ) : Event

  private data class RelayResetEvent(
      val sourcePlayer: Int,
      val event: ReceivedRemoteResetEvent,
  ) : Event

  private data class RelayStopEvent(
      val sourcePlayer: Int,
      val event: ReceivedRemoteStopEvent,
  ) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
    private const val PROTOCOL_NAME = "CoffeeGB NETPLAY"
    private const val PROTOCOL_VERSION: Byte = 0x06
    private const val REJECTION_MARKER = 0xff
    private const val MAX_COMPRESSED_PAYLOAD = 64 * 1024 * 1024
    private const val ROM: Byte = 0x01
    private const val INPUT: Byte = 0x03
    private const val RESET: Byte = 0x06
    private const val STOP: Byte = 0x07
    private const val START: Byte = 0x08
    private const val SYNCHRONIZE: Byte = 0x09
    private const val ROM_HEADER_SIZE = 45

    internal fun reject(outputStream: OutputStream, reason: RejectionReason) {
      val output = DataOutputStream(BufferedOutputStream(outputStream))
      val buf = ByteArray(PROTOCOL_NAME.length + 3)
      PROTOCOL_NAME.toByteArray().copyInto(buf)
      buf[PROTOCOL_NAME.length] = PROTOCOL_VERSION
      buf[PROTOCOL_NAME.length + 1] = REJECTION_MARKER.toByte()
      buf[PROTOCOL_NAME.length + 2] = reason.wireCode.toByte()
      output.write(buf)
      output.flush()
    }

    fun deflate(data: ByteArray): ByteArray {
      val deflater = Deflater(Deflater.BEST_SPEED)
      deflater.setInput(data)
      deflater.finish()
      val out = ByteArrayOutputStream(data.size / 2 + 64)
      val buffer = ByteArray(64 * 1024)
      while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        out.write(buffer, 0, count)
      }
      deflater.end()
      return out.toByteArray()
    }

    fun inflate(data: ByteArray?, originalSize: Int): ByteArray? {
      if (data == null) return null
      if (originalSize < 0 || originalSize > MAX_COMPRESSED_PAYLOAD * 4) {
        throw IOException("Invalid uncompressed payload size: $originalSize")
      }
      val inflater = Inflater()
      inflater.setInput(data)
      val result = ByteArray(originalSize)
      var offset = 0
      while (offset < originalSize && !inflater.finished()) {
        val count = inflater.inflate(result, offset, originalSize - offset)
        if (count == 0 && inflater.needsInput()) throw IOException("Truncated compressed payload")
        offset += count
      }
      inflater.end()
      if (offset != originalSize) {
        throw IOException("Corrupted compressed payload: expected $originalSize, got $offset")
      }
      return result
    }
  }
}
