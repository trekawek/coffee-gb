package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.StateHistory
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateRootKind
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
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
    private val cancelTransport: (() -> Unit)? = null,
) : Runnable, AutoCloseable {

  private val input = DataInputStream(BufferedInputStream(inputStream))

  private val output = DataOutputStream(BufferedOutputStream(outputStream))

  private val outputLock = Object()

  private val outboundMessages = ArrayDeque<ByteArray>()

  private var outboundMessageCount = 0

  private var outboundBytes = 0L

  private var writerClosing = false

  private var stopInputWhenDrained = false

  @Volatile private var writerFailure: IOException? = null

  private val writerThread: Thread

  private val eventBus: EventBus = mainEventBus.fork("connection-$assignedPlayer")

  val mode: LinkMode

  /** The player at the remote end when hosting, or this application's player when connected. */
  val player: Int

  @Volatile private var doStop = false

  @Volatile private var controllerFailure: ProtocolException? = null

  private var outboundPhase = if (server) OutboundPhase.HANDSHAKE else OutboundPhase.ACTIVE

  private var startRequested = false

  private val pendingBootstrapMessages = ArrayDeque<ByteArray>()

  private val pendingRuntimeMessages = ArrayDeque<ByteArray>()

  private var pendingOutboundBytes = 0L

  private var sessionActive = server

  private val pendingEvents = mutableListOf<Event>()

  private var pendingEventBytes = 0L

  private val pendingCheckpointStates = mutableListOf<PeerLoadedGameEvent>()

  private var pendingCheckpointBytes = 0L

  private val peerSource: PeerEventSource

  @Volatile private var peerSourceDisconnected = false

  init {
    val handshake =
        try {
          handshake(requestedMode, assignedPlayer)
        } catch (e: Exception) {
          try {
            eventBus.close()
          } catch (closeFailure: Exception) {
            e.addSuppressed(closeFailure)
          }
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
          throw e
        }
    mode = handshake.mode
    player = handshake.player
    peerSource = PeerEventSource(player, ::rejectFromController)

    eventBus.register<LinkedController.LocalRomLoadedEvent> {
      // Four-player host state is sent as an atomic SessionStateReadyEvent checkpoint. Sending the
      // ordinary host ROM event as well would let clients run before the adapter state arrives.
      if (shouldSendLocal(it.player) && (!server || mode != LinkMode.FOUR_PLAYER_ADAPTER)) {
        sendSafely { sendRom(it, StateRootKind.MACHINE) }
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

    // Relay only after LinkedController has checked the frame against its authoritative clock.
    // The originating connection is skipped; every other client receives the same player label.
    eventBus.register<ValidatedPeerButtonStateEvent> {
      if (server && it.event.source !== peerSource) {
        sendSafely {
          sendButtons(
              LinkedController.LocalButtonStateEvent(
                  it.event.frame,
                  it.event.input,
                  it.event.player,
              ))
        }
      }
    }
    eventBus.register<ValidatedPeerResetEvent> {
      if (server && it.event.source !== peerSource) {
        sendSafely { sendFrameCommand(RESET, it.event.frame, it.event.player) }
      }
    }
    eventBus.register<LinkedController.SessionStateReadyEvent> {
      if (server && mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        sendSafely {
          // A checkpoint is one output transaction. Runtime traffic cannot split its state
          // records from the synchronization marker that commits them.
          synchronized(outputLock) {
            it.states.forEach { state -> sendRom(state, StateRootKind.SESSION) }
            sendSynchronization(it.frame)
          }
        }
      }
    }
    writerThread =
        Thread(::writeOutbound, "netplay-writer-$assignedPlayer").also {
          it.isDaemon = true
          it.start()
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
      abortWriter(e)
    }
  }

  private fun sendRom(
      event: LinkedController.LocalRomLoadedEvent,
      expectedRoot: StateRootKind,
  ) {
    if (event.hardwareProfileId == HardwareProfileRegistry.SGB.id() ||
        event.hardwareProfileId == HardwareProfileRegistry.SGB2.id()) {
      throw IOException(
          "SGB-family netplay is unavailable: protocol v8 negotiates StateFile v1 with " +
              "the historical 4,194,304-unit SGB RTC phase; exact-clock sgb/sgb2 state requires v2")
    }
    if (expectedRoot == StateRootKind.SESSION && event.portableState == null) {
      throw IOException("A running-session checkpoint requires a SESSION StateFile")
    }
    val stateDeclaration =
        event.portableState?.let { validateOutgoingState(it, expectedRoot) }
    checkedDecodedMessageSize(
        event.romFile.size,
        event.slotRomFile?.size ?: 0,
        event.batteryFile?.size ?: 0,
        stateDeclaration?.decodedBytes ?: 0,
    )
    val rom = deflate(event.romFile, StateLimits.ROM)
    val slotRom = event.slotRomFile?.let { deflate(it, StateLimits.ROM) }
    val battery = event.batteryFile?.let { deflate(it, StateLimits.BATTERY) }
    val heldButtons = event.heldButtons.sorted()
    val messageSize =
        checkedMessageSize(
            1L + ROM_HEADER_SIZE + heldButtons.size,
            rom,
            slotRom,
            battery,
            event.portableState,
        )
    val buf = ByteBuffer.allocate(messageSize)
    buf.put(ROM)
    buf.put(event.player.toByte())
    buf.putLong(event.frame)
    buf.put(event.gameboyType.ordinal.toByte())
    buf.put(event.bootstrapMode.ordinal.toByte())
    buf.putInt(profileFlags(event))
    buf.put(heldButtons.size.toByte())
    buf.putInt(event.romFile.size)
    buf.putInt(rom.size)
    buf.putInt(event.slotRomFile?.size ?: 0)
    buf.putInt(slotRom?.size ?: 0)
    buf.putInt(event.batteryFile?.size ?: 0)
    buf.putInt(battery?.size ?: 0)
    buf.putInt(event.portableState?.size ?: 0)
    heldButtons.forEach { buf.put(it.ordinal.toByte()) }
    event.portableState?.let(buf::put)
    buf.put(rom)
    slotRom?.let(buf::put)
    battery?.let(buf::put)
    sendMessage(buf, OutboundMessage.BOOTSTRAP)
    LOG.atInfo().log(
        "Sent player {} ROM ({} -> {} bytes compressed, state root {})",
        event.player + 1,
        event.romFile.size,
        rom.size,
        expectedRoot,
    )
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
    sendMessage(buf, OutboundMessage.RUNTIME)
    LOG.atDebug().log("Sent {}", event)
  }

  private fun sendFrameCommand(command: Byte, frame: Long, eventPlayer: Int) {
    val buf = ByteBuffer.allocate(10)
    buf.put(command)
    buf.put(eventPlayer.toByte())
    buf.putLong(frame)
    sendMessage(buf, OutboundMessage.RUNTIME)
  }

  private fun sendSynchronization(frame: Long) {
    val buf = ByteBuffer.allocate(9)
    buf.put(SYNCHRONIZE)
    buf.putLong(frame)
    sendMessage(buf, OutboundMessage.BOOTSTRAP)
  }

  private fun sendMessage(buf: ByteBuffer, kind: OutboundMessage = OutboundMessage.RUNTIME) {
    val message = buf.array().copyOf(buf.position())
    synchronized(outputLock) {
      if (doStop) return
      if (server &&
          (outboundPhase == OutboundPhase.HANDSHAKE ||
              (outboundPhase == OutboundPhase.BOOTSTRAP && kind == OutboundMessage.RUNTIME))) {
        val pendingCount = pendingBootstrapMessages.size + pendingRuntimeMessages.size
        if (pendingCount >= StateLimits.NETPLAY_HANDSHAKE_PENDING_MESSAGES ||
            message.size > StateLimits.NETPLAY_HANDSHAKE_PENDING_BYTES - pendingOutboundBytes) {
          throw IOException("Netplay handshake outbound queue limit exceeded")
        }
        if (kind == OutboundMessage.BOOTSTRAP) {
          pendingBootstrapMessages += message
        } else {
          pendingRuntimeMessages += message
        }
        pendingOutboundBytes += message.size
        return
      }
      enqueueOutboundLocked(message)
    }
  }

  /** The controller only enqueues; this one bounded writer owns every blocking socket write. */
  private fun writeOutbound() {
    while (true) {
      val message =
          synchronized(outputLock) {
            while (outboundMessages.isEmpty() && !writerClosing) {
              try {
                outputLock.wait()
              } catch (_: InterruptedException) {
                writerClosing = true
              }
            }
            if (outboundMessages.isEmpty()) {
              if (stopInputWhenDrained) {
                try {
                  input.close()
                } catch (_: IOException) {
                  // The peer may already have closed.
                }
              }
              return
            }
            outboundMessages.removeFirst()
          }
      try {
        output.write(message)
        output.flush()
        synchronized(outputLock) {
          outboundMessageCount--
          outboundBytes -= message.size
        }
      } catch (e: IOException) {
        writerFailure = e
        doStop = true
        synchronized(outputLock) {
          outboundMessages.clear()
          outboundMessageCount = 0
          outboundBytes = 0
          writerClosing = true
          outputLock.notifyAll()
        }
        try {
          input.close()
        } catch (closeFailure: IOException) {
          e.addSuppressed(closeFailure)
        }
        return
      }
    }
  }

  private fun enqueueOutboundLocked(message: ByteArray) {
    if (writerClosing) throw IOException("Netplay writer is closing")
    if (outboundMessageCount >= StateLimits.NETPLAY_OUTBOUND_MESSAGES ||
        message.size > StateLimits.NETPLAY_OUTBOUND_BYTES - outboundBytes) {
      throw IOException("Netplay outbound queue limit exceeded")
    }
    outboundMessages += message
    outboundMessageCount++
    outboundBytes += message.size
    outputLock.notifyAll()
  }

  private fun abortWriter(cause: IOException) {
    synchronized(outputLock) {
      outboundMessages.clear()
      // An in-flight record remains included until the writer observes the closed stream.
      writerClosing = true
      outputLock.notifyAll()
    }
    try {
      cancelTransport?.invoke()
    } catch (closeFailure: Exception) {
      cause.addSuppressed(closeFailure)
    }
    try {
      input.close()
    } catch (closeFailure: IOException) {
      cause.addSuppressed(closeFailure)
    }
  }

  /** Releases a client after the host has accepted its physical link port. */
  fun startSession() {
    check(server)
    synchronized(outputLock) {
      check(outboundPhase != OutboundPhase.ACTIVE) { "Netplay session already started" }
      if (outboundPhase == OutboundPhase.HANDSHAKE) {
        startRequested = true
      } else {
        finishStartLocked()
      }
    }
  }

  override fun run() {
    if (server) completeServerHandshake()
    while (!doStop) {
      val command =
          try {
            input.read()
          } catch (e: IOException) {
            controllerFailure?.let { throw it }
            writerFailure?.let { throw it }
            if (doStop) -1 else throw e
          }
      controllerFailure?.let { throw it }
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
                  PeerFrameWindow.validateCheckpoint(
                      input.readLong(),
                      pendingCheckpointStates.map(PeerLoadedGameEvent::frame),
                  )
              val states = pendingCheckpointStates.toList()
              pendingCheckpointStates.clear()
              pendingCheckpointBytes = 0
              validateCheckpointStates(states)
              deliver(SessionCheckpointEvent(frame, states, peerSource))
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
    val frame = PeerFrameWindow.validateAbsolute(buf.getLong())
    val gameboyType = enumValue<GameboyType>(buf.get(), "Game Boy type")
    val bootstrapMode = enumValue<BootstrapMode>(buf.get(), "bootstrap mode")
    val profileFlags = validateProfileFlags(buf.getInt(), gameboyType)
    if (gameboyType == GameboyType.SGB) {
      failProtocol(
          ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT,
          IOException(
              "Protocol v8 StateFile v1 cannot carry exact-clock SGB-family RTC phase state; " +
                  "a versioned protocol with StateFile v2 is required"),
      )
    }
    val heldCount = buf.get().toInt() and 0xff
    if (heldCount > Button.entries.size) throw IOException("Invalid held button count $heldCount")
    val romSize = buf.getInt()
    val romCompressed = buf.getInt()
    val slotRomSize = buf.getInt()
    val slotRomCompressed = buf.getInt()
    val batterySize = buf.getInt()
    val batteryCompressed = buf.getInt()
    val stateSize = buf.getInt()
    val romDeclaration =
        validateDeclaration(romSize, romCompressed, StateLimits.ROM, required = true)
    val slotRomDeclaration =
        validateDeclaration(slotRomSize, slotRomCompressed, StateLimits.ROM)
    val batteryDeclaration =
        validateDeclaration(batterySize, batteryCompressed, StateLimits.BATTERY)
    val stateDeclaration = validateStateFileDeclaration(stateSize)
    val checkpointRecord = !server && mode == LinkMode.FOUR_PLAYER_ADAPTER
    val expectedStateRoot =
        if (checkpointRecord) StateRootKind.SESSION else StateRootKind.MACHINE
    if (checkpointRecord && stateDeclaration == null) {
      throw IOException("Four-player checkpoint record is missing its SESSION StateFile")
    }
    checkedMessageSize(
        1L + ROM_HEADER_SIZE + heldCount,
        romDeclaration.encodedBytes,
        slotRomDeclaration.encodedBytes,
        batteryDeclaration.encodedBytes,
        stateDeclaration?.wireBytes ?: 0,
    )
    checkedDecodedMessageSize(
        romDeclaration.decodedBytes,
        slotRomDeclaration.decodedBytes,
        batteryDeclaration.decodedBytes,
        stateDeclaration?.wireBytes ?: 0,
    )
    val preStateRetained =
        checkedDecodedMessageTotal(
            romDeclaration.decodedBytes,
            slotRomDeclaration.decodedBytes,
            batteryDeclaration.decodedBytes,
            stateDeclaration?.wireBytes ?: 0,
        )
    preflightPendingRetention(preStateRetained, checkpointRecord)
    val heldButtons = ByteArray(heldCount).also(input::readFully).map {
      val ordinal = it.toInt() and 0xff
      if (ordinal !in Button.entries.indices) throw IOException("Invalid held button $ordinal")
      Button.entries[ordinal]
    }.toSet()
    val decodedState =
        stateDeclaration?.let {
          readNetworkState(
              it,
              expectedStateRoot,
              checkedDecodedMessageTotal(
                  romDeclaration.decodedBytes,
                  slotRomDeclaration.decodedBytes,
                  batteryDeclaration.decodedBytes,
              ),
          )
        }
    val rom = inflate(readPayload(romDeclaration), romDeclaration, StateLimits.ROM)!!
    val slotRom =
        inflate(readPayload(slotRomDeclaration), slotRomDeclaration, StateLimits.ROM)
    val battery =
        inflate(readPayload(batteryDeclaration), batteryDeclaration, StateLimits.BATTERY)
    val configuration =
        validatePeerConfiguration(
            rom,
            slotRom,
            battery,
            gameboyType,
            bootstrapMode,
            profileFlags,
        )
    decodedState?.let {
      validatePeerState(configuration, it.file, expectedStateRoot, eventPlayer)
      val session = it.file.root as? SessionStateRoot
      if (session != null) {
        val stateHeld = session.session.heldButtons.map { button -> Button.valueOf(button.name) }.toSet()
        if (stateHeld != heldButtons) {
          failProtocol(
              ProtocolErrorReason.INVALID_PORTABLE_STATE,
              IOException("Checkpoint held-button header disagrees with its SESSION StateFile"),
          )
        }
      }
    }
    val event =
        PeerLoadedGameEvent(
            rom = rom,
            slotRom = slotRom,
            battery = battery,
            gameboyType = gameboyType,
            bootstrapMode = bootstrapMode,
            frame = frame,
            cgb0Revision = profileFlags and PROFILE_CGB0 != 0,
            mealybugDmgBlob = profileFlags and PROFILE_MEALYBUG_DMG_BLOB != 0,
            codeBreakerRumble = profileFlags and PROFILE_CODEBREAKER_RUMBLE != 0,
            displaySgbBorder = profileFlags and PROFILE_SGB_BORDER != 0,
            player = eventPlayer,
            heldButtons = heldButtons,
            portableState = decodedState?.file,
            stateWireBytes = decodedState?.wireBytes ?: 0,
            stateDecodedBytes = decodedState?.decodedBytes ?: 0,
            source = peerSource,
        )
    if (checkpointRecord) {
      if (pendingCheckpointStates.size >= mode.playerCount ||
          pendingCheckpointStates.any { it.player == event.player }) {
        throw IOException("Duplicate or excessive four-player checkpoint state")
      }
      val eventBytes = peerStateBytes(event)
      checkedPendingStateBytes(pendingCheckpointBytes, eventBytes)
      pendingCheckpointStates += event
      pendingCheckpointBytes += eventBytes
    } else {
      deliver(event)
    }
    LOG.atInfo().log("Received player {} ROM", eventPlayer + 1)
  }

  private fun receiveButtons() {
    val header = ByteArray(11)
    input.readFully(header)
    val buf = ByteBuffer.wrap(header)
    val eventPlayer = receivedPlayer(buf.get().toInt())
    val frame = PeerFrameWindow.validateAbsolute(buf.getLong())
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
    val event =
        LinkedController.RemoteButtonStateEvent(
            frame,
            Input(pressed, released),
            eventPlayer,
            peerSource,
        )
    deliver(event)
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
    if (command == RESET) {
      val event = ReceivedRemoteResetEvent(frame, eventPlayer, peerSource)
      deliver(event)
    } else {
      val event = ReceivedRemoteStopEvent(frame, eventPlayer, peerSource)
      deliver(event)
    }
  }

  private fun readPlayerAndFrame(): Pair<Int, Long> {
    val payload = ByteArray(9)
    input.readFully(payload)
    val buf = ByteBuffer.wrap(payload)
    return receivedPlayer(buf.get().toInt()) to PeerFrameWindow.validateAbsolute(buf.getLong())
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
      checkedPendingStateBytes(pendingEventBytes, eventBytes)
      pendingEvents += event
      pendingEventBytes += eventBytes
    }
  }

  private fun peerStateBytes(event: PeerLoadedGameEvent): Long =
      listOf(event.rom, event.slotRom, event.battery)
          .filterNotNull()
          .fold(event.stateDecodedBytes.toLong()) { total, bytes ->
            Math.addExact(total, bytes.size.toLong())
          }

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

  private fun validatePeerConfiguration(
      rom: ByteArray,
      slotRom: ByteArray?,
      battery: ByteArray?,
      gameboyType: GameboyType,
      bootstrapMode: BootstrapMode,
      profileFlags: Int,
  ): Gameboy.GameboyConfiguration =
      try {
        peerConfiguration(
            rom,
            slotRom,
            battery,
            gameboyType,
            bootstrapMode,
            profileFlags and PROFILE_CGB0 != 0,
            profileFlags and PROFILE_MEALYBUG_DMG_BLOB != 0,
            profileFlags and PROFILE_CODEBREAKER_RUMBLE != 0,
            profileFlags and PROFILE_SGB_BORDER != 0,
        )
      } catch (e: Exception) {
        failProtocol(
            ProtocolErrorReason.MALFORMED_MESSAGE,
            IOException("Peer ROM configuration is invalid", e),
        )
      }

  /**
   * Checks identity and target-dependent structure against an isolated probe. Candidate records
   * remain detached; complete reconstruction/application occurs only in LinkedController.
   */
  private fun validatePeerState(
      configuration: Gameboy.GameboyConfiguration,
      file: StateFile,
      expectedRoot: StateRootKind,
      eventPlayer: Int,
  ) {
    try {
      StateCodec.validateForTarget(
          file,
          expectedRoot,
          listOf(StateIdentityEntry(0, StateIdentity.from(configuration))),
      )
      when (val root = file.root) {
        is MachineStateRoot -> {
          val probeBus = EventBusImpl(null, null, false)
          val probe = configuration.forRestore().build()
          try {
            probe.init(
                probeBus,
                SerialEndpoint.NULL_ENDPOINT,
                InfraredEndpoint.NULL_ENDPOINT,
                null,
            )
            DetachedStateAdapter.validateTarget(probe, root.machine)
          } finally {
            probe.stop()
            probe.close()
            probeBus.close()
          }
        }
        is SessionStateRoot -> {
          val links = StateHistory.createLinks(mode, configuration.clockSpec)
          val probeBus = EventBusImpl(null, null, false)
          val probe =
              Session(
                  configuration.forRestore(),
                  probeBus,
                  null,
                  links.serial[eventPlayer],
                  links.infrared[eventPlayer],
              )
          try {
            DetachedStateAdapter.validateTarget(probe, root.session)
          } finally {
            probe.close()
          }
        }
        else -> throw IOException("Unexpected network StateFile root ${file.root.kind}")
      }
    } catch (e: Exception) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException(
              "Peer ${expectedRoot.name} StateFile does not match its ROM/profile or endpoint",
              e,
          ),
      )
    }
  }

  private fun readNetworkState(
      declaration: StateFileDeclaration,
      expectedRoot: StateRootKind,
      otherDecodedBytes: Long,
  ): DecodedNetworkState {
    val prefixSize = minOf(STATE_MAGIC.size, declaration.wireBytes)
    val prefix = ByteArray(prefixSize).also(input::readFully)
    if (prefixSize < STATE_MAGIC.size) {
      val cgbPrefix = prefix.indices.all { prefix[it] == STATE_MAGIC[it] }
      if (cgbPrefix) {
        return failProtocol(
            ProtocolErrorReason.INVALID_PORTABLE_STATE,
            IOException("Truncated CGBS StateFile prefix"),
        )
      }
      return failProtocol(
          ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT,
          IOException("Network state does not begin with CGBS"),
      )
    }
    if (!prefix.contentEquals(STATE_MAGIC)) {
      return failProtocol(
          ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT,
          IOException("Network state does not begin with CGBS"),
      )
    }

    val bytes: ByteArray
    val decodedBytes: Int
    if (declaration.wireBytes < StateCodec.HEADER_SIZE) {
      bytes = ByteArray(declaration.wireBytes)
      prefix.copyInto(bytes)
      input.readFully(bytes, prefix.size, bytes.size - prefix.size)
      decodedBytes = 0
    } else {
      val header = ByteArray(StateCodec.HEADER_SIZE)
      prefix.copyInto(header)
      input.readFully(header, prefix.size, header.size - prefix.size)
      decodedBytes = validateNetworkStateEnvelope(header, declaration)
      val retained = Math.addExact(otherDecodedBytes, decodedBytes.toLong())
      checkedDecodedMessageSizeLong(retained)
      preflightPendingRetention(
          retained,
          !server && mode == LinkMode.FOUR_PLAYER_ADAPTER,
      )
      bytes = ByteArray(declaration.wireBytes)
      header.copyInto(bytes)
      input.readFully(bytes, header.size, bytes.size - header.size)
    }

    val file =
        try {
          StateCodec.decode(bytes)
        } catch (failure: StateDecodeException) {
          failProtocol(
              ProtocolErrorReason.INVALID_PORTABLE_STATE,
              IOException(
                  "Invalid CGBS StateFile (${failure.reason}): ${failure.message}",
                  failure,
              ),
          )
        }
    if (file.root.kind != expectedRoot) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException(
              "Network StateFile root ${file.root.kind} does not match expected $expectedRoot"),
      )
    }
    return DecodedNetworkState(file, declaration.wireBytes, decodedBytes)
  }

  private fun validateCheckpointStates(states: List<PeerLoadedGameEvent>) {
    try {
      val sessions =
          states.map { state ->
            (state.portableState?.root as? SessionStateRoot)?.session
                ?: throw IOException("Four-player checkpoint contains a non-SESSION state")
          }
      if (sessions.any {
            it.serialPeripheral !=
                eu.rekawek.coffeegb.controller.state.SerialPeripheralState.FOUR_PLAYER_ADAPTER
          }) {
        throw IOException("Four-player checkpoint contains the wrong serial endpoint identity")
      }
      if (sessions.map { it.serialState }.distinct().size > 1) {
        throw IOException("Four-player checkpoint disagrees on shared adapter state")
      }
    } catch (e: Exception) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException("Four-player checkpoint is not a coherent atomic group", e),
      )
    }
  }

  private fun preflightPendingRetention(
      declaredBytes: Long,
      checkpointRecord: Boolean,
  ) {
    val retained =
        when {
          checkpointRecord -> pendingCheckpointBytes
          !sessionActive -> pendingEventBytes
          else -> 0L
        }
    checkedPendingStateBytes(retained, declaredBytes)
  }

  private fun validateOutgoingState(
      bytes: ByteArray,
      expectedRoot: StateRootKind,
  ): StateFileDeclaration {
    val declaration =
        validateStateFileDeclaration(bytes.size)
            ?: throw IOException("A non-null StateFile cannot have zero bytes")
    val file =
        try {
          StateCodec.decode(bytes)
        } catch (failure: StateDecodeException) {
          throw IOException(
              "Local $expectedRoot StateFile is invalid (${failure.reason}): ${failure.message}",
              failure,
          )
        }
    if (file.root.kind != expectedRoot) {
      throw IOException(
          "Local StateFile root ${file.root.kind} does not match expected $expectedRoot")
    }
    val inspection = StateCodec.inspect(bytes)
    if (inspection.formatVersion != StateCodec.PROTOCOL_V8_FORMAT_VERSION) {
      throw IOException(
          "Protocol v8 supports StateFile v${StateCodec.PROTOCOL_V8_FORMAT_VERSION}, not v${inspection.formatVersion}")
    }
    if (inspection.decodedPayloadLength > StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES) {
      throw IOException(
          "Local StateFile decoded payload ${inspection.decodedPayloadLength} exceeds " +
              "${StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES} network bytes")
    }
    return StateFileDeclaration(bytes.size, inspection.decodedPayloadLength.toInt())
  }

  private fun validateNetworkStateEnvelope(
      header: ByteArray,
      declaration: StateFileDeclaration,
  ): Int {
    val buffer = ByteBuffer.wrap(header)
    val magic = ByteArray(STATE_MAGIC.size).also(buffer::get)
    check(magic.contentEquals(STATE_MAGIC))
    val version = buffer.short.toInt() and 0xffff
    if (version != StateCodec.PROTOCOL_V8_FORMAT_VERSION) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException(
              "Unsupported network StateFile version $version; supported " +
                  "${StateCodec.PROTOCOL_V8_FORMAT_VERSION}",
              StateDecodeException(
                  eu.rekawek.coffeegb.controller.state.StateDecodeReason
                      .UNSUPPORTED_FORMAT_VERSION,
                  "Unsupported StateFile version $version",
              ),
          ),
      )
    }
    val headerSize = buffer.short.toInt() and 0xffff
    if (headerSize != StateCodec.HEADER_SIZE) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          IOException(
              "Unsupported network StateFile header size $headerSize; expected " +
                  "${StateCodec.HEADER_SIZE}"),
      )
    }
    buffer.position(20)
    val encoded = buffer.long
    val decoded = buffer.long
    return try {
      validateNetworkStateLengths(encoded, decoded, declaration.wireBytes)
    } catch (failure: IOException) {
      failProtocol(
          ProtocolErrorReason.INVALID_PORTABLE_STATE,
          failure,
      )
    }
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

  private fun rejectFromController(reason: ProtocolErrorReason, cause: IOException) {
    val failure = ProtocolException(reason, cause)
    synchronized(outputLock) {
      if (controllerFailure != null || doStop) return
      try {
        enqueueOutboundLocked(byteArrayOf(PROTOCOL_ERROR, reason.wireCode.toByte()))
      } catch (sendFailure: IOException) {
        failure.addSuppressed(sendFailure)
      }
      controllerFailure = failure
      doStop = true
      stopInputWhenDrained = true
      writerClosing = true
      outputLock.notifyAll()
    }
    // Give an idle writer one short chance to send the diagnostic. Never wait for a peer to drain
    // earlier state: independently cancel the raw transport at the deadline so the reader exits
    // and TcpServer can release this player's slot.
    Thread(
            {
              try {
                writerThread.join(StateLimits.NETPLAY_PROTOCOL_ERROR_GRACE_MILLIS)
              } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
              }
              if (writerThread.isAlive) abortWriter(failure)
            },
            "netplay-rejection-$player",
        )
        .also {
          it.isDaemon = true
          it.start()
        }
  }

  fun stop() {
    doStop = true
    synchronized(outputLock) {
      writerClosing = true
      outputLock.notifyAll()
    }
  }

  override fun close() {
    doStop = true
    if (!peerSourceDisconnected) {
      synchronized(outputLock) {
        if (!peerSourceDisconnected) {
          peerSourceDisconnected = true
          eventBus.post(PeerEventSourceDisconnectedEvent(peerSource))
        }
      }
    }
    eventBus.close()
    var failure: IOException? = null
    synchronized(outputLock) {
      writerClosing = true
      outputLock.notifyAll()
    }
    try {
      writerThread.join(StateLimits.NETPLAY_WRITER_CLOSE_MILLIS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    try {
      input.close()
    } catch (e: IOException) {
      failure = e
    }
    try {
      output.close()
    } catch (e: IOException) {
      failure?.addSuppressed(e) ?: run { failure = e }
    }
    if (writerThread !== Thread.currentThread()) {
      try {
        writerThread.join(StateLimits.NETPLAY_WRITER_CLOSE_MILLIS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    failure?.let { throw it }
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
      writeCapabilities()
      output.flush()
      return Handshake(requestedMode, assignedPlayer)
    }

    input.readFully(buf)
    val receivedProtocolName = String(buf, 0, PROTOCOL_NAME.length)
    if (receivedProtocolName != PROTOCOL_NAME) {
      throw IOException("Protocol mismatch: expected $PROTOCOL_NAME, received $receivedProtocolName")
    }
    val receivedVersion = buf[PROTOCOL_NAME.length].toInt() and 0xff
    if (receivedVersion != PROTOCOL_VERSION.toInt()) {
      throw CompatibilityException(
          "Incompatible netplay protocol: expected version " +
              "${PROTOCOL_VERSION.toInt()}, received $receivedVersion.")
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
    readCapabilities("server")
    writeCapabilities()
    output.flush()
    LOG.atInfo().log("Connected as player {} in {} mode", receivedPlayer + 1, receivedMode)
    return Handshake(receivedMode, receivedPlayer)
  }

  internal fun completeServerHandshake() {
    synchronized(outputLock) {
      if (outboundPhase != OutboundPhase.HANDSHAKE) return
    }
    readCapabilities("client")
    synchronized(outputLock) {
      if (outboundPhase != OutboundPhase.HANDSHAKE) return
      outboundPhase = OutboundPhase.BOOTSTRAP
      pendingBootstrapMessages.forEach { message ->
        enqueueOutboundLocked(message)
        pendingOutboundBytes -= message.size
      }
      pendingBootstrapMessages.clear()
      if (startRequested) {
        finishStartLocked()
      }
    }
  }

  private fun writeCapabilities() {
    output.write(
        byteArrayOf(
            PROTOCOL_VERSION,
            STATE_NEGOTIATION_VERSION,
            StateCodec.PROTOCOL_V8_FORMAT_VERSION.toByte(),
            STATE_ROOT_CAPABILITIES,
        ))
  }

  /**
   * Reads the peer's version byte first. A protocol-v7 client sends its old one-byte capability
   * marker here, so it is rejected without consuming the following v7 command as v8 negotiation.
   */
  private fun readCapabilities(peer: String) {
    val peerProtocol = input.read()
    if (peerProtocol == -1) {
      throw CompatibilityException(
          "Truncated $peer netplay negotiation: expected protocol v${PROTOCOL_VERSION.toInt()} " +
              "and StateFile v${StateCodec.PROTOCOL_V8_FORMAT_VERSION}.")
    }
    if (peerProtocol != PROTOCOL_VERSION.toInt()) {
      val detected =
          if (peerProtocol == V7_STATE_CAPABILITY) {
            "legacy protocol-v7 capability marker"
          } else {
            "protocol version $peerProtocol"
          }
      throw CompatibilityException(
          "Incompatible $peer netplay negotiation: detected $detected; expected protocol " +
              "v${PROTOCOL_VERSION.toInt()} with StateFile v${StateCodec.PROTOCOL_V8_FORMAT_VERSION}.")
    }
    val rest = ByteArray(STATE_CAPABILITY_BYTES - 1)
    try {
      input.readFully(rest)
    } catch (failure: IOException) {
      throw CompatibilityException(
          "Truncated $peer StateFile negotiation: expected negotiation v" +
              "$STATE_NEGOTIATION_VERSION, StateFile v${StateCodec.PROTOCOL_V8_FORMAT_VERSION}, " +
              "and root capabilities 0x${STATE_ROOT_CAPABILITIES.toString(16)}.",
      )
    }
    val negotiationVersion = rest[0].toInt() and 0xff
    if (negotiationVersion != STATE_NEGOTIATION_VERSION.toInt()) {
      throw CompatibilityException(
          "Unsupported $peer StateFile negotiation version $negotiationVersion; supported " +
              "${STATE_NEGOTIATION_VERSION.toInt()}.")
    }
    val stateVersion = rest[1].toInt() and 0xff
    if (stateVersion != StateCodec.PROTOCOL_V8_FORMAT_VERSION) {
      throw CompatibilityException(
          "Unsupported $peer StateFile format version $stateVersion; supported " +
              "${StateCodec.PROTOCOL_V8_FORMAT_VERSION}.")
    }
    val roots = rest[2].toInt() and 0xff
    if (roots != STATE_ROOT_CAPABILITIES.toInt()) {
      throw CompatibilityException(
          "Unsupported $peer StateFile root capabilities 0x${roots.toString(16)}; expected " +
              "0x${STATE_ROOT_CAPABILITIES.toString(16)} (MACHINE, SESSION, LINKED_SESSION).")
    }
  }

  /** Must be called with [outputLock] held. START and queued runtime traffic are one commit. */
  private fun finishStartLocked() {
    enqueueOutboundLocked(byteArrayOf(START))
    outboundPhase = OutboundPhase.ACTIVE
    startRequested = false
    pendingRuntimeMessages.forEach { message ->
      enqueueOutboundLocked(message)
      pendingOutboundBytes -= message.size
    }
    pendingRuntimeMessages.clear()
  }

  private enum class OutboundPhase {
    HANDSHAKE,
    BOOTSTRAP,
    ACTIVE,
  }

  private enum class OutboundMessage {
    BOOTSTRAP,
    RUNTIME,
  }

  private data class Handshake(val mode: LinkMode, val player: Int)

  internal enum class RejectionReason(
      val wireCode: Int,
      val userMessage: String,
  ) {
    SERVER_FULL(1, "The netplay server is already full."),
    SERVER_BUSY(2, "The netplay server has too many pending connections."),
  }

  internal class ConnectionRejectedException(val reason: RejectionReason) :
      IOException(reason.userMessage)

  internal class CompatibilityException(message: String) : IOException(message)

  internal enum class ProtocolErrorReason(
      val wireCode: Int,
      val userMessage: String,
  ) {
    MALFORMED_MESSAGE(
        1,
        "The peer sent malformed or oversized netplay data. The connection was closed safely.",
    ),
    UNSUPPORTED_STATE_FORMAT(
        2,
        "The peer sent an unsupported network state format; protocol v8 requires CGBS " +
            "StateFile v1. The connection was closed safely.",
    ),
    INVALID_PORTABLE_STATE(
        3,
        "The peer sent an invalid CGBS StateFile v1. The connection was closed safely.",
    ),
    INVALID_FRAME(
        4,
        "The peer sent a frame outside the safe rollback window. " +
            "The connection was closed safely.",
    ),
    EXCESSIVE_REPLAY_WORK(
        5,
        "The peer requested excessive rollback replay work. The connection was closed safely.",
    ),
  }

  internal class ProtocolException(
      val reason: ProtocolErrorReason,
      cause: Throwable? = null,
  ) :
      IOException(
          if (cause?.message.isNullOrBlank()) reason.userMessage
          else "${reason.userMessage} ${cause.message}",
          cause,
      )

  class PeerEventSource internal constructor(
      val player: Int,
      private val reject: (ProtocolErrorReason, IOException) -> Unit,
  ) {
    internal fun reject(reason: ProtocolErrorReason, cause: IOException) =
        reject.invoke(reason, cause)
  }

  data class PeerLoadedGameEvent(
      val rom: ByteArray,
      val battery: ByteArray?,
      val portableState: StateFile?,
      val gameboyType: GameboyType,
      val bootstrapMode: BootstrapMode,
      val frame: Long,
      val cgb0Revision: Boolean = false,
      val player: Int = 1,
      val heldButtons: Set<Button> = emptySet(),
      val slotRom: ByteArray? = null,
      val mealybugDmgBlob: Boolean = false,
      val codeBreakerRumble: Boolean = false,
      val displaySgbBorder: Boolean = false,
      internal val stateWireBytes: Int = 0,
      internal val stateDecodedBytes: Int = 0,
      internal val source: PeerEventSource? = null,
  ) : Event

  data class SessionCheckpointEvent(
      val frame: Long,
      val states: List<PeerLoadedGameEvent>,
      internal val source: PeerEventSource? = null,
  ) : Event

  internal data class PeerEventSourceDisconnectedEvent(val source: PeerEventSource) : Event

  data class RequestResetEvent(val frame: Long, val player: Int = 0) : Event

  data class RequestStopEvent(val frame: Long, val player: Int = 0) : Event

  data class ReceivedRemoteResetEvent(
      val frame: Long,
      val player: Int = 1,
      internal val source: PeerEventSource? = null,
  ) : Event

  data class ReceivedRemoteStopEvent(
      val frame: Long,
      val player: Int = 1,
      internal val source: PeerEventSource? = null,
  ) : Event

  internal data class ValidatedPeerButtonStateEvent(
      val event: LinkedController.RemoteButtonStateEvent,
  ) : Event

  internal data class ValidatedPeerResetEvent(val event: ReceivedRemoteResetEvent) : Event

  internal data class ValidatedPeerStopEvent(val event: ReceivedRemoteStopEvent) : Event

  internal data class ValidatedPeerCheckpointEvent(val event: SessionCheckpointEvent) : Event

  internal data class ValidatedPeerStateEvent(val event: PeerLoadedGameEvent) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
    private const val PROTOCOL_NAME = "CoffeeGB NETPLAY"
    internal const val PROTOCOL_VERSION: Byte = 0x08
    internal const val STATE_NEGOTIATION_VERSION: Byte = 0x01
    internal const val STATE_CAPABILITY_BYTES = 4
    internal const val STATE_ROOT_CAPABILITIES: Byte = 0x07
    private const val V7_STATE_CAPABILITY = 0x01
    private const val REJECTION_MARKER = 0xff
    private const val ROM: Byte = 0x01
    private const val INPUT: Byte = 0x03
    private const val RESET: Byte = 0x06
    private const val STOP: Byte = 0x07
    private const val START: Byte = 0x08
    private const val SYNCHRONIZE: Byte = 0x09
    private const val PROTOCOL_ERROR: Byte = 0x0a
    internal const val ROM_HEADER_SIZE = 44
    private val STATE_MAGIC =
        byteArrayOf(
            'C'.code.toByte(),
            'G'.code.toByte(),
            'B'.code.toByte(),
            'S'.code.toByte(),
        )
    private const val PROFILE_CGB0 = 1
    private const val PROFILE_MEALYBUG_DMG_BLOB = 1 shl 1
    private const val PROFILE_CODEBREAKER_RUMBLE = 1 shl 2
    private const val PROFILE_SGB_BORDER = 1 shl 3
    private const val KNOWN_PROFILE_FLAGS =
        PROFILE_CGB0 or
            PROFILE_MEALYBUG_DMG_BLOB or
            PROFILE_CODEBREAKER_RUMBLE or
            PROFILE_SGB_BORDER

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

    private fun profileFlags(event: LinkedController.LocalRomLoadedEvent): Int {
      var flags = 0
      if (event.cgb0Revision) flags = flags or PROFILE_CGB0
      if (event.mealybugDmgBlob) flags = flags or PROFILE_MEALYBUG_DMG_BLOB
      if (event.codeBreakerRumble) flags = flags or PROFILE_CODEBREAKER_RUMBLE
      if (event.displaySgbBorder) flags = flags or PROFILE_SGB_BORDER
      return validateProfileFlags(flags, event.gameboyType)
    }

    internal fun validateProfileFlags(flags: Int, hardware: GameboyType): Int {
      if (flags and KNOWN_PROFILE_FLAGS.inv() != 0) {
        throw IOException("Undefined netplay hardware profile flags 0x${flags.toString(16)}")
      }
      if (flags and PROFILE_CGB0 != 0 && hardware != GameboyType.CGB) {
        throw IOException("CGB0 profile flag is invalid for $hardware")
      }
      if (flags and PROFILE_MEALYBUG_DMG_BLOB != 0 && hardware == GameboyType.CGB) {
        throw IOException("Mealybug DMG-blob profile flag is invalid for native CGB")
      }
      if (flags and PROFILE_SGB_BORDER != 0 && hardware != GameboyType.SGB) {
        throw IOException("SGB-border profile flag is invalid for $hardware")
      }
      return flags
    }

    internal fun peerConfiguration(
        rom: ByteArray,
        slotRom: ByteArray?,
        battery: ByteArray?,
        gameboyType: GameboyType,
        bootstrapMode: BootstrapMode,
        cgb0Revision: Boolean,
        mealybugDmgBlob: Boolean,
        codeBreakerRumble: Boolean,
        displaySgbBorder: Boolean,
    ): Gameboy.GameboyConfiguration {
      val primary = Rom(rom)
      if (slotRom != null &&
          primary.cartridgeProperties.mapper != CartridgeProperties.Mapper.DATEL) {
        throw IOException("A slot ROM is valid only for a Datel pass-through cartridge")
      }
      return Gameboy.GameboyConfiguration(primary)
          .setGameboyType(gameboyType)
          .setBootstrapMode(bootstrapMode)
          .setCgb0Revision(cgb0Revision)
          .setMealybugDmgBlob(mealybugDmgBlob)
          .setCodeBreakerRumble(codeBreakerRumble)
          .setDisplaySgbBorder(displaySgbBorder)
          .setSlotRom(slotRom?.let(::Rom))
          .setBatteryData(battery?.clone())
          .setSupportBatterySave(false)
    }

    internal fun validateStateFileDeclaration(size: Int): StateFileDeclaration? {
      if (size == 0) return null
      if (size < 0 || size > StateLimits.NETPLAY_STATE_FILE_BYTES) {
        throw IOException(
            "Direct network StateFile exceeds ${StateLimits.NETPLAY_STATE_FILE_BYTES} bytes: $size")
      }
      return StateFileDeclaration(size, 0)
    }

    internal fun validateNetworkStateLengths(
        encoded: Long,
        decoded: Long,
        wireBytes: Int,
    ): Int {
      if (encoded < 0 ||
          encoded > StateLimits.NETPLAY_STATE_FILE_BYTES - StateCodec.HEADER_SIZE ||
          decoded < 0 ||
          decoded > StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES) {
        throw IOException(
            "Network StateFile declares $encoded encoded/$decoded decoded bytes; limits are " +
                "${StateLimits.NETPLAY_STATE_FILE_BYTES - StateCodec.HEADER_SIZE}/" +
                "${StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES}")
      }
      val expectedWire =
          try {
            Math.addExact(StateCodec.HEADER_SIZE.toLong(), encoded)
          } catch (failure: ArithmeticException) {
            throw IOException("Network StateFile encoded length overflows", failure)
          }
      if (expectedWire != wireBytes.toLong()) {
        throw IOException(
            "Network StateFile wire length $wireBytes disagrees with envelope $expectedWire")
      }
      return decoded.toInt()
    }

    internal fun checkedPendingStateBytes(retained: Long, incoming: Long): Long {
      if (retained < 0 ||
          incoming < 0 ||
          retained > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES ||
          incoming > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES - retained) {
        throw IOException("Pending netplay state exceeds the cumulative limit")
      }
      return retained + incoming
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
      checkedDecodedMessageSizeLong(checkedDecodedMessageTotal(*decodedSizes))
    }

    internal fun checkedDecodedMessageTotal(vararg decodedSizes: Int): Long =
        try {
          decodedSizes.fold(0L) { sum, size ->
            if (size < 0) throw IOException("Decoded netplay size is negative: $size")
            Math.addExact(sum, size.toLong())
          }
        } catch (e: ArithmeticException) {
          throw IOException("Decoded netplay ROM message size overflow", e)
        }

    internal fun checkedDecodedMessageSizeLong(total: Long) {
      if (total < 0) throw IOException("Decoded netplay ROM message size is negative")
      if (total > StateLimits.NETPLAY_DECODED_MESSAGE_BYTES) {
        throw IOException(
            "Decoded netplay ROM message exceeds " +
                "${StateLimits.NETPLAY_DECODED_MESSAGE_BYTES} bytes: $total")
      }
    }
  }

  internal data class PayloadDeclaration(val decodedBytes: Int, val encodedBytes: Int)
  internal data class StateFileDeclaration(val wireBytes: Int, val decodedBytes: Int)
  private data class DecodedNetworkState(
      val file: StateFile,
      val wireBytes: Int,
      val decodedBytes: Int,
  )
}
