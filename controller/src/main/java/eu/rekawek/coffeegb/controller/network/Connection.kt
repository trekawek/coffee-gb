package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.LegacyMementoCodec
import eu.rekawek.coffeegb.controller.PortableMementoCodec
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.StateHistory
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
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

  @Volatile private var serverHandshakeComplete = !server

  private var sessionActive = server

  private val pendingEvents = mutableListOf<Event>()

  private var pendingEventBytes = 0L

  private val pendingCheckpointStates = mutableListOf<PeerLoadedGameEvent>()

  private var pendingCheckpointBytes = 0L

  private val peerFrames = PeerFrameWindow()

  init {
    val handshake = handshake(requestedMode, assignedPlayer)
    mode = handshake.mode
    player = handshake.player

    eventBus.register<LinkedController.LocalRomLoadedEvent> {
      // Four-player host state is sent as an atomic SessionStateReadyEvent checkpoint. Sending the
      // ordinary host ROM event as well would let clients run before the adapter memento arrives.
      if (shouldSendLocal(it.player) && (!server || mode != LinkMode.FOUR_PLAYER_ADAPTER)) {
        sendSafely { sendRom(it) }
      }
    }
    eventBus.register<LinkedController.LocalButtonStateEvent> {
      if (shouldSendLocal(it.player)) sendSafely { sendButtons(it) }
    }
    eventBus.register<RequestResetEvent> {
      if (shouldSendLocal(it.player)) {
        sendSafely { sendFrameCommand(RESET, it.frame, it.player) }
      }
    }
    eventBus.register<RequestStopEvent> {
      if (shouldSendLocal(it.player)) {
        sendSafely { sendFrameCommand(STOP, it.frame, it.player) }
      }
    }

    // Only host-side connections see relay events. The originating player's connection is
    // skipped; every other client receives the exact same player-labelled message.
    eventBus.register<RelayRomEvent> {
      if (server && player != it.sourcePlayer) sendSafely { sendRom(it.event) }
    }
    eventBus.register<RelayButtonsEvent> {
      if (server && player != it.sourcePlayer) sendSafely { sendButtons(it.event) }
    }
    eventBus.register<RelayResetEvent> {
      if (server && player != it.sourcePlayer) {
        sendSafely { sendFrameCommand(RESET, it.event.frame, it.event.player) }
      }
    }
    eventBus.register<RelayStopEvent> {
      if (server && player != it.sourcePlayer) {
        sendSafely { sendFrameCommand(STOP, it.event.frame, it.event.player) }
      }
    }
    eventBus.register<LinkedController.SessionStateReadyEvent> {
      if (server && mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        sendSafely {
          it.states.forEach(::sendRom)
          sendSynchronization(it.frame)
        }
      }
    }
  }

  private fun shouldSendLocal(eventPlayer: Int): Boolean =
      if (server) eventPlayer == 0 else eventPlayer == player

  private inline fun sendSafely(block: () -> Unit) {
    try {
      block()
    } catch (e: IOException) {
      LOG.info("Closing player {} connection after destination write failure: {}", player + 1,
          e.message)
      doStop = true
      try {
        input.close()
      } catch (closeFailure: IOException) {
        e.addSuppressed(closeFailure)
      }
      try {
        output.close()
      } catch (closeFailure: IOException) {
        e.addSuppressed(closeFailure)
      }
    }
  }

  private fun sendRom(event: LinkedController.LocalRomLoadedEvent) {
    if (server && !serverHandshakeComplete) return
    checkedDecodedMessageSize(
        event.romFile.size,
        event.batteryFile?.size ?: 0,
        event.snapshot?.size ?: 0,
        event.sessionSnapshot?.size ?: 0,
    )
    val rom = deflate(event.romFile, StateLimits.ROM)
    val battery = event.batteryFile?.let { deflate(it, StateLimits.BATTERY) }
    val snapshot = event.snapshot?.let { deflate(it, StateLimits.GAME_SNAPSHOT) }
    val sessionSnapshot =
        event.sessionSnapshot?.let { deflate(it, StateLimits.SESSION_SNAPSHOT) }
    val heldButtons = event.heldButtons.sorted()
    val messageSize =
        checkedMessageSize(
            1L + ROM_HEADER_SIZE + heldButtons.size,
            rom,
            battery,
            snapshot,
            sessionSnapshot,
        )
    val buf =
        ByteBuffer.allocate(messageSize)
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
    peerFrames.establishSharedFrame(event.frame)
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
    if (doStop || (server && !serverHandshakeComplete)) return
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
    if (server) completeServerHandshake()
    while (!doStop) {
      val command =
          try {
            input.read()
          } catch (e: IOException) {
            if (doStop) -1 else throw e
          }
      if (command == -1) return

      try {
        when (command.toByte()) {
          ROM -> receiveRom()
          INPUT -> receiveButtons()
          RESET -> receiveReset()
          STOP -> receiveStop()
          PROTOCOL_ERROR -> receiveProtocolError()
          SYNCHRONIZE -> {
            if (!server && mode == LinkMode.FOUR_PLAYER_ADAPTER) {
              val frame =
                  peerFrames.validateCheckpoint(
                      input.readLong(),
                      pendingCheckpointStates.map(PeerLoadedGameEvent::frame),
                  )
              val states = pendingCheckpointStates.toList()
              peerFrames.accept(frame)
              pendingCheckpointStates.clear()
              pendingCheckpointBytes = 0
              deliver(SessionCheckpointEvent(frame, states))
            } else {
              throw IOException("Client sent a server-only synchronization command")
            }
          }
          START -> {
            if (!server && !sessionActive) {
              sessionActive = true
              eventBus.post(ConnectionController.ClientConnectedToServerEvent(mode, player))
              // The host may have sent its ROM before START. The connection is already listening at
              // that point, but the LinkedController is created by the event above; deliver cached
              // state only after that synchronous transition has completed.
              pendingEvents.forEach(eventBus::post)
              pendingEvents.clear()
              pendingEventBytes = 0
            } else {
              throw IOException("Unexpected netplay start command")
            }
          }
          else -> throw IOException("Unknown netplay command $command")
        }
      } catch (e: ProtocolException) {
        throw e
      } catch (e: EventQueue.EventQueueFullException) {
        failProtocol(
            ProtocolErrorReason.MALFORMED_MESSAGE,
            IOException("Netplay event queue limit exceeded", e),
        )
      } catch (e: IOException) {
        failProtocol(ProtocolErrorReason.MALFORMED_MESSAGE, e)
      }
    }
  }

  private fun receiveRom() {
    val header = ByteArray(ROM_HEADER_SIZE)
    input.readFully(header)
    val buf = ByteBuffer.wrap(header)
    val wirePlayer = buf.get().toInt()
    val eventPlayer = receivedPlayer(wirePlayer)
    val frame = peerFrames.validateStateFrame(buf.getLong())
    val gameboyType = enumValue<GameboyType>(buf.get(), "Game Boy type")
    val bootstrapMode = enumValue<BootstrapMode>(buf.get(), "bootstrap mode")
    val cgb0RevisionValue = buf.get().toInt() and 0xff
    if (cgb0RevisionValue !in 0..1) {
      throw IOException("Invalid CGB0 revision flag $cgb0RevisionValue")
    }
    val cgb0Revision = cgb0RevisionValue == 1
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
    val romDeclaration =
        validateDeclaration(romSize, romCompressed, StateLimits.ROM, required = true)
    val batteryDeclaration =
        validateDeclaration(batterySize, batteryCompressed, StateLimits.BATTERY)
    val snapshotDeclaration =
        validateDeclaration(snapshotSize, snapshotCompressed, StateLimits.GAME_SNAPSHOT)
    val sessionSnapshotDeclaration =
        validateDeclaration(
            sessionSnapshotSize,
            sessionSnapshotCompressed,
            StateLimits.SESSION_SNAPSHOT,
        )
    checkedMessageSize(
        1L + ROM_HEADER_SIZE + heldCount,
        romDeclaration,
        batteryDeclaration,
        snapshotDeclaration,
        sessionSnapshotDeclaration,
    )
    checkedDecodedMessageSize(
        romDeclaration.decodedBytes,
        batteryDeclaration.decodedBytes,
        snapshotDeclaration.decodedBytes,
        sessionSnapshotDeclaration.decodedBytes,
    )
    val heldButtons = ByteArray(heldCount).also(input::readFully).map {
      val ordinal = it.toInt() and 0xff
      if (ordinal !in Button.entries.indices) throw IOException("Invalid held button $ordinal")
      Button.entries[ordinal]
    }.toSet()
    val rom = inflate(readPayload(romDeclaration), romDeclaration, StateLimits.ROM)!!
    val battery =
        inflate(readPayload(batteryDeclaration), batteryDeclaration, StateLimits.BATTERY)
    val snapshotBytes =
        inflate(
            readPayload(snapshotDeclaration),
            snapshotDeclaration,
            StateLimits.GAME_SNAPSHOT,
        )
    val sessionSnapshotBytes =
        inflate(
            readPayload(sessionSnapshotDeclaration),
            sessionSnapshotDeclaration,
            StateLimits.SESSION_SNAPSHOT,
        )
    val snapshot = snapshotBytes?.let(::decodePeerGameState)
    val sessionSnapshot = sessionSnapshotBytes?.let(::decodePeerSessionState)
    val configuration =
        validatePeerConfiguration(
            rom,
            battery,
            gameboyType,
            bootstrapMode,
            cgb0Revision,
        )
    if (sessionSnapshot != null) {
      validatePeerSessionState(configuration, sessionSnapshot, eventPlayer)
    } else {
      validatePeerGameState(configuration, snapshot)
    }
    // A message carrying both forms is unusual but must validate both detached roots.
    if (sessionSnapshot != null && snapshot != null) {
      validatePeerGameState(configuration, snapshot)
    }
    val event =
        PeerLoadedGameEvent(
            rom = rom,
            battery = battery,
            snapshot = snapshotBytes,
            gameboyType = gameboyType,
            bootstrapMode = bootstrapMode,
            frame = frame,
            cgb0Revision = cgb0Revision,
            player = eventPlayer,
            sessionSnapshot = sessionSnapshotBytes,
            heldButtons = heldButtons,
            decodedSnapshot = snapshot,
            decodedSessionSnapshot = sessionSnapshot,
        )
    peerFrames.accept(frame)
    if (!server && mode == LinkMode.FOUR_PLAYER_ADAPTER && event.sessionSnapshot != null) {
      if (pendingCheckpointStates.size >= mode.playerCount ||
          pendingCheckpointStates.any { it.player == event.player }) {
        throw IOException("Duplicate or excessive four-player checkpoint state")
      }
      val eventBytes = peerStateBytes(event)
      if (eventBytes > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES - pendingCheckpointBytes) {
        throw IOException("Four-player checkpoint exceeds the cumulative state limit")
      }
      pendingCheckpointStates += event
      pendingCheckpointBytes += eventBytes
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
                  snapshotBytes,
                  event.gameboyType,
                  event.bootstrapMode,
                  event.frame,
                  event.cgb0Revision,
                  event.player,
                  sessionSnapshotBytes,
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
    val frame = peerFrames.validateRuntimeFrame(buf.getLong())
    val pressedCount = buf.get().toInt() and 0xff
    val releasedCount = buf.get().toInt() and 0xff
    if (pressedCount > Button.entries.size || releasedCount > Button.entries.size) {
      throw IOException(
          "Invalid button counts: $pressedCount pressed, $releasedCount released")
    }
    val buttons = ByteArray(pressedCount + releasedCount)
    input.readFully(buttons)
    val pressed = (0 until pressedCount).map { buttonValue(buttons[it]) }
    val released =
        (0 until releasedCount).map { buttonValue(buttons[pressedCount + it]) }
    peerFrames.accept(frame)
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

  private fun receiveProtocolError(): Nothing {
    val wireCode = input.read()
    if (wireCode == -1) throw IOException("Truncated netplay protocol error")
    val reason =
        ProtocolErrorReason.entries.firstOrNull { it.wireCode == wireCode }
            ?: throw IOException("Peer reported unknown protocol error $wireCode")
    throw ProtocolException(reason)
  }

  private fun receiveFrameEvent(command: Byte) {
    val (eventPlayer, frame) = readPlayerAndFrame()
    peerFrames.accept(frame)
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
    return receivedPlayer(buf.get().toInt()) to peerFrames.validateRuntimeFrame(buf.getLong())
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
      if (event !is PeerLoadedGameEvent && event !is SessionCheckpointEvent) {
        throw IOException("Received ${event.javaClass.simpleName} before session start")
      }
      if (pendingEvents.size >= StateLimits.NETPLAY_PENDING_EVENTS ||
          (event is PeerLoadedGameEvent &&
              pendingEvents.filterIsInstance<PeerLoadedGameEvent>().any {
                it.player == event.player
              })) {
        throw IOException("Too many pending netplay events before session start")
      }
      val eventBytes =
          when (event) {
            is PeerLoadedGameEvent -> peerStateBytes(event)
            is SessionCheckpointEvent ->
                event.states.fold(0L) { total, state ->
                  Math.addExact(total, peerStateBytes(state))
                }
            else -> 0L
          }
      if (eventBytes > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES - pendingEventBytes) {
        throw IOException("Pending netplay state exceeds the cumulative limit")
      }
      pendingEvents += event
      pendingEventBytes += eventBytes
    }
  }

  private fun peerStateBytes(event: PeerLoadedGameEvent): Long =
      listOf(event.rom, event.battery, event.snapshot, event.sessionSnapshot)
          .filterNotNull()
          .fold(0L) { total, bytes -> Math.addExact(total, bytes.size.toLong()) }

  private fun readPayload(declaration: PayloadDeclaration): ByteArray? {
    if (declaration.encodedBytes == 0) return null
    return ByteArray(declaration.encodedBytes).also(input::readFully)
  }

  private fun buttonValue(value: Byte): Button {
    val ordinal = value.toInt() and 0xff
    if (ordinal !in Button.entries.indices) throw IOException("Invalid button $ordinal")
    return Button.entries[ordinal]
  }

  private inline fun <reified T : Enum<T>> enumValue(value: Byte, description: String): T {
    val ordinal = value.toInt() and 0xff
    val values = enumValues<T>()
    if (ordinal !in values.indices) throw IOException("Invalid $description $ordinal")
    return values[ordinal]
  }

  private fun decodePeerGameState(bytes: ByteArray): Memento<eu.rekawek.coffeegb.core.Gameboy> {
    validatePeerStateHeader(bytes)
    return try {
      PortableMementoCodec.decodeGameboy(bytes)
    } catch (e: PortableMementoCodec.DecodeException) {
      failProtocol(ProtocolErrorReason.INVALID_PORTABLE_STATE, e)
    }
  }

  private fun decodePeerSessionState(bytes: ByteArray): Memento<Session> {
    validatePeerStateHeader(bytes)
    return try {
      PortableMementoCodec.decodeSession(bytes)
    } catch (e: PortableMementoCodec.DecodeException) {
      failProtocol(ProtocolErrorReason.INVALID_PORTABLE_STATE, e)
    }
  }

  private fun validatePeerConfiguration(
      rom: ByteArray,
      battery: ByteArray?,
      gameboyType: GameboyType,
      bootstrapMode: BootstrapMode,
      cgb0Revision: Boolean,
  ): Gameboy.GameboyConfiguration =
      try {
        Gameboy.GameboyConfiguration(Rom(rom))
            .setGameboyType(gameboyType)
            .setBootstrapMode(bootstrapMode)
            .setCgb0Revision(cgb0Revision)
            .setBatteryData(battery?.clone())
            .setSupportBatterySave(false)
      } catch (e: Exception) {
        failProtocol(
            ProtocolErrorReason.MALFORMED_MESSAGE,
            IOException("Peer ROM configuration is invalid", e),
        )
      }

  /** Restores only into a detached probe so invalid dimensions cannot reach the live session. */
  private fun validatePeerGameState(
      configuration: Gameboy.GameboyConfiguration,
      snapshot: Memento<Gameboy>?,
  ) {
    val probeBus = EventBusImpl(null, null, false)
    var probe: Gameboy? = null
    try {
      probe = configuration.forRestore().build()
      probe.init(
          probeBus,
          SerialEndpoint.NULL_ENDPOINT,
          InfraredEndpoint.NULL_ENDPOINT,
          null,
      )
      snapshot?.let(probe::restoreFromMemento)
    } catch (e: Exception) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException("Peer game state cannot be restored safely", e),
      )
    } finally {
      probe?.stop()
      probe?.close()
      probeBus.close()
    }
  }

  private fun validatePeerSessionState(
      configuration: Gameboy.GameboyConfiguration,
      snapshot: Memento<Session>,
      eventPlayer: Int,
  ) {
    val links = StateHistory.createLinks(mode)
    val probeBus = EventBusImpl(null, null, false)
    var probe: Session? = null
    try {
      probe =
          Session(
              configuration.forRestore(),
              probeBus,
              null,
              links.serial[eventPlayer],
              links.infrared[eventPlayer],
          )
      probe.restoreFromMemento(snapshot)
    } catch (e: Exception) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException("Peer session state cannot be restored safely", e),
      )
    } finally {
      probe?.close() ?: probeBus.close()
    }
  }

  private fun validatePeerStateHeader(bytes: ByteArray) {
    val reason =
        when {
          LegacyMementoCodec.hasJavaSerializationHeader(bytes) ->
              ProtocolErrorReason.LEGACY_JAVA_STATE
          !PortableMementoCodec.hasHeader(bytes) -> ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT
          else -> return
        }
    sendProtocolError(reason)
    throw ProtocolException(reason)
  }

  private fun sendProtocolError(reason: ProtocolErrorReason) {
    val buf = ByteBuffer.allocate(2)
    buf.put(PROTOCOL_ERROR)
    buf.put(reason.wireCode.toByte())
    sendMessage(buf)
  }

  private fun failProtocol(reason: ProtocolErrorReason, cause: IOException): Nothing {
    try {
      sendProtocolError(reason)
    } catch (sendFailure: IOException) {
      cause.addSuppressed(sendFailure)
    }
    throw ProtocolException(reason, cause)
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
      output.writeByte(STATE_FORMAT_CAPABILITIES)
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
      throw CompatibilityException(
          "Incompatible netplay protocol: expected version " +
              "${PROTOCOL_VERSION.toInt()}, received ${receivedVersion.toInt()}.")
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
    val capabilities = input.read()
    if (capabilities == -1) throw IOException("Truncated netplay capability handshake")
    if (capabilities and STATE_FORMAT_CAPABILITIES == 0) {
      throw CompatibilityException("The server does not support portable state format 1.")
    }
    output.writeByte(STATE_FORMAT_CAPABILITIES)
    output.flush()
    LOG.atInfo().log("Connected as player {} in {} mode", receivedPlayer + 1, receivedMode)
    return Handshake(receivedMode, receivedPlayer)
  }

  @Synchronized
  internal fun completeServerHandshake() {
    if (serverHandshakeComplete) return
    val capabilities = input.read()
    if (capabilities == -1) throw CompatibilityException("Truncated netplay capability handshake")
    if (capabilities and STATE_FORMAT_CAPABILITIES == 0) {
      throw CompatibilityException("The client does not support portable state format 1.")
    }
    serverHandshakeComplete = true
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

  internal class CompatibilityException(message: String) : IOException(message)

  internal enum class ProtocolErrorReason(
      val wireCode: Int,
      val userMessage: String,
  ) {
    LEGACY_JAVA_STATE(
        1,
        "The peer sent an unsafe legacy Java save state. " +
            "Network state transfer requires the portable state format.",
    ),
    MALFORMED_MESSAGE(
        2,
        "The peer sent malformed or oversized netplay data. The connection was closed safely.",
    ),
    UNSUPPORTED_STATE_FORMAT(
        3,
        "The peer sent an unsupported state format. The connection was closed safely.",
    ),
    INVALID_PORTABLE_STATE(
        4,
        "The peer sent an invalid portable state. The connection was closed safely.",
    ),
  }

  internal class ProtocolException(
      val reason: ProtocolErrorReason,
      cause: Throwable? = null,
  ) : IOException(reason.userMessage, cause)

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
      internal val decodedSnapshot: Memento<eu.rekawek.coffeegb.core.Gameboy>? = null,
      internal val decodedSessionSnapshot: Memento<Session>? = null,
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
    private const val PROTOCOL_VERSION: Byte = 0x07
    private const val STATE_FORMAT_CAPABILITIES = 0x01
    private const val REJECTION_MARKER = 0xff
    private const val ROM: Byte = 0x01
    private const val INPUT: Byte = 0x03
    private const val RESET: Byte = 0x06
    private const val STOP: Byte = 0x07
    private const val START: Byte = 0x08
    private const val SYNCHRONIZE: Byte = 0x09
    private const val PROTOCOL_ERROR: Byte = 0x0a
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

    fun deflate(data: ByteArray): ByteArray = deflate(data, StateLimits.GAME_SNAPSHOT)

    internal fun deflate(data: ByteArray, limit: StateLimits.Payload): ByteArray {
      validateDecodedSize(data.size, limit)
      val deflater = Deflater(Deflater.BEST_SPEED)
      try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
          val count = deflater.deflate(buffer)
          out.write(buffer, 0, count)
          if (out.size() > limit.encodedBytes) {
            throw IOException(
                "Compressed ${limit.description} exceeds ${limit.encodedBytes} bytes")
          }
        }
        return out.toByteArray()
      } finally {
        deflater.end()
      }
    }

    fun inflate(data: ByteArray?, originalSize: Int): ByteArray? {
      val declaration =
          validateDeclaration(
              originalSize,
              data?.size ?: 0,
              StateLimits.GAME_SNAPSHOT,
              required = data != null,
          )
      return inflate(data, declaration, StateLimits.GAME_SNAPSHOT)
    }

    internal fun inflate(
        data: ByteArray?,
        declaration: PayloadDeclaration,
        limit: StateLimits.Payload,
    ): ByteArray? {
      if (data == null) {
        if (declaration.decodedBytes != 0 || declaration.encodedBytes != 0) {
          throw IOException("Missing compressed ${limit.description}")
        }
        return null
      }
      if (data.size != declaration.encodedBytes) {
        throw IOException(
            "Compressed ${limit.description} length changed while it was being read")
      }
      val inflater = Inflater()
      try {
        inflater.setInput(data)
        val result = ByteArray(declaration.decodedBytes)
        var offset = 0
        val overflowProbe = ByteArray(1)
        while (!inflater.finished()) {
          val count =
              if (offset < result.size) {
                inflater.inflate(result, offset, result.size - offset)
              } else {
                inflater.inflate(overflowProbe)
              }
          if (count > 0) {
            if (offset == result.size) {
              throw IOException("${limit.description} expands beyond its declared size")
            }
            offset += count
            continue
          }
          if (inflater.needsDictionary()) {
            throw IOException("Compressed ${limit.description} requires a dictionary")
          }
          if (inflater.needsInput()) {
            throw IOException("Truncated compressed ${limit.description}")
          }
          if (!inflater.finished()) {
            throw IOException("Compressed ${limit.description} made no progress")
          }
        }
        if (offset != declaration.decodedBytes) {
          throw IOException(
              "Corrupted compressed ${limit.description}: expected " +
                  "${declaration.decodedBytes}, got $offset")
        }
        if (inflater.remaining != 0) {
          throw IOException("Trailing data after compressed ${limit.description}")
        }
        return result
      } catch (e: DataFormatException) {
        throw IOException("Corrupted compressed ${limit.description}", e)
      } finally {
        inflater.end()
      }
    }

    internal fun validateDeclaration(
        decodedBytes: Int,
        encodedBytes: Int,
        limit: StateLimits.Payload,
        required: Boolean = false,
    ): PayloadDeclaration {
      if (decodedBytes == 0 && encodedBytes == 0 && !required) {
        return PayloadDeclaration(0, 0)
      }
      if (decodedBytes <= 0 || encodedBytes <= 0) {
        throw IOException(
            "Inconsistent ${limit.description} lengths: $decodedBytes decoded, " +
                "$encodedBytes encoded")
      }
      validateDecodedSize(decodedBytes, limit)
      if (encodedBytes > limit.encodedBytes) {
        throw IOException(
            "Compressed ${limit.description} exceeds ${limit.encodedBytes} bytes: $encodedBytes")
      }
      return PayloadDeclaration(decodedBytes, encodedBytes)
    }

    private fun validateDecodedSize(size: Int, limit: StateLimits.Payload) {
      if (size < 0 || size > limit.decodedBytes) {
        throw IOException(
            "${limit.description} exceeds ${limit.decodedBytes} decoded bytes: $size")
      }
    }

    private fun checkedMessageSize(baseBytes: Long, vararg payloads: ByteArray?): Int =
        checkedMessageSize(baseBytes, *payloads.map { it?.size ?: 0 }.toIntArray())

    private fun checkedMessageSize(
        baseBytes: Long,
        vararg declarations: PayloadDeclaration,
    ): Int =
        checkedMessageSize(
            baseBytes,
            *declarations.map(PayloadDeclaration::encodedBytes).toIntArray(),
        )

    internal fun checkedMessageSize(baseBytes: Long, vararg encodedSizes: Int): Int {
      val total =
          try {
            encodedSizes.fold(baseBytes) { sum, size -> Math.addExact(sum, size.toLong()) }
          } catch (e: ArithmeticException) {
            throw IOException("Encoded netplay ROM message size overflow", e)
          }
      if (total > StateLimits.NETPLAY_ENCODED_MESSAGE_BYTES) {
        throw IOException(
            "Encoded netplay ROM message exceeds " +
                "${StateLimits.NETPLAY_ENCODED_MESSAGE_BYTES} bytes: $total")
      }
      return total.toInt()
    }

    internal fun checkedDecodedMessageSize(vararg decodedSizes: Int) {
      val total =
          try {
            decodedSizes.fold(0L) { sum, size -> Math.addExact(sum, size.toLong()) }
          } catch (e: ArithmeticException) {
            throw IOException("Decoded netplay ROM message size overflow", e)
          }
      if (total > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES) {
        throw IOException(
            "Decoded netplay ROM message exceeds " +
                "${StateLimits.NETPLAY_DECODED_MESSAGE_BYTES} bytes: $total")
      }
    }
  }

  internal data class PayloadDeclaration(val decodedBytes: Int, val encodedBytes: Int)
}
