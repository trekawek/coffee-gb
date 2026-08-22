package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientConnectionRejectedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientHandshakeCompletedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientProtocolErrorEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerLostConnectionEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerCountEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerProtocolErrorEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartFailedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStoppedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.controller.network.TcpServer
import eu.rekawek.coffeegb.controller.state.StateProfilePolicy
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal const val NETPLAY_V8_PORT: Int = 6688

internal enum class NetplaySetupView {
  HOST,
  JOIN,
}

internal enum class NetplayRole {
  HOST,
  CLIENT,
}

internal enum class NetplayPhase(val title: String) {
  DISCONNECTED("Set up netplay"),
  STARTING_HOST("Starting host"),
  WAITING_FOR_PEERS("Waiting for players"),
  CONNECTING("Connecting"),
  NEGOTIATING("Negotiating and synchronizing"),
  ACTIVE("Active"),
  STOPPING("Stopping"),
  FAILED("Connection failed"),
}

internal sealed interface NetplayAvailability {
  val gameName: String?
  val available: Boolean
  val message: String

  data object NoGame : NetplayAvailability {
    override val gameName: String? = null
    override val available: Boolean = false
    override val message: String = "Load a game to use netplay."
  }

  data class CheckingProfile(override val gameName: String) : NetplayAvailability {
    override val available: Boolean = false
    override val message: String = "Checking this game's hardware profile."
  }

  data class Available(
      override val gameName: String,
      val profileId: String,
      val profileName: String,
  ) : NetplayAvailability {
    override val available: Boolean = true
    override val message: String = "$profileName is supported by protocol v8."
  }

  data class IncompatibleProfile(
      override val gameName: String,
      val profileId: String,
      val profileName: String,
  ) : NetplayAvailability {
    override val available: Boolean = false
    override val message: String =
        "Profile $profileId cannot use netplay: protocol v8 uses StateFile v1, while this " +
            "profile requires explicit StateFile v2 identity. Reopen the game with a compatible " +
            "DMG, CGB, or CGB0 profile."
  }
}

internal enum class NetplayFailureKind {
  REJECTED,
  PROTOCOL,
  DISCONNECTED,
  LINK_PORT,
}

/** A bounded literal message. It is never interpreted as Swing HTML or copied automatically. */
internal data class NetplayFailure(
    val kind: NetplayFailureKind,
    val message: String,
)

/**
 * A validated value for the exact parser currently consumed by [StartClientEvent]. [toString]
 * deliberately omits the destination so snapshots are safe to include in ordinary diagnostics.
 */
internal class NetplayV8Endpoint private constructor(
    val host: String,
    val port: Int,
    val startClientValue: String,
) {
  override fun equals(other: Any?): Boolean =
      other is NetplayV8Endpoint &&
          host == other.host &&
          port == other.port &&
          startClientValue == other.startClientValue

  override fun hashCode(): Int = 31 * (31 * host.hashCode() + port) + startClientValue.hashCode()

  override fun toString(): String = "NetplayV8Endpoint(<redacted>, port=$port)"

  companion object {
    fun create(host: String, port: Int, startClientValue: String): NetplayV8Endpoint =
        NetplayV8Endpoint(host, port, startClientValue)
  }
}

internal sealed interface NetplayAddressValidation {
  data class Valid(val endpoint: NetplayV8Endpoint) : NetplayAddressValidation

  data class Invalid(val message: String) : NetplayAddressValidation
}

/**
 * Validates only forms the current v8 client can parse: hostname/IPv4, optionally followed by one
 * decimal port. Bracketed and unbracketed IPv6 are not advertised because TcpClient splits at the
 * first colon. Resolution remains the network controller's responsibility.
 */
internal fun validateNetplayV8Address(input: String): NetplayAddressValidation {
  val value = input.trim()
  if (value.isEmpty()) {
    return NetplayAddressValidation.Invalid("Enter a hostname or IPv4 address.")
  }
  if (value.length > MAX_NETPLAY_ADDRESS_CHARS) {
    return NetplayAddressValidation.Invalid("The address is too long.")
  }
  if (value.any { it.isWhitespace() || it.isISOControl() }) {
    return NetplayAddressValidation.Invalid("The address cannot contain spaces or control characters.")
  }
  if ('[' in value || ']' in value || value.count { it == ':' } > 1) {
    return NetplayAddressValidation.Invalid(
        "IPv6 addresses are not supported by the current protocol-v8 client.")
  }

  val separator = value.indexOf(':')
  val host = if (separator < 0) value else value.substring(0, separator)
  if (host.isEmpty()) {
    return NetplayAddressValidation.Invalid("Enter a hostname or IPv4 address before the port.")
  }
  if (host.length > MAX_NETPLAY_HOST_CHARS) {
    return NetplayAddressValidation.Invalid("The hostname or IPv4 address is too long.")
  }

  if (separator < 0) {
    return NetplayAddressValidation.Valid(
        NetplayV8Endpoint.create(host, NETPLAY_V8_PORT, host))
  }

  val portText = value.substring(separator + 1)
  if (portText.isEmpty()) {
    return NetplayAddressValidation.Invalid("Enter a port after the colon.")
  }
  if (!portText.all(Char::isDigit)) {
    return NetplayAddressValidation.Invalid("The port must contain decimal digits only.")
  }
  val port = portText.toIntOrNull()
  if (port == null || port !in 1..65535) {
    return NetplayAddressValidation.Invalid("The port must be between 1 and 65535.")
  }
  return NetplayAddressValidation.Valid(NetplayV8Endpoint.create(host, port, "$host:$port"))
}

internal data class NetplayUiState(
    val availability: NetplayAvailability = NetplayAvailability.NoGame,
    val setupView: NetplaySetupView = NetplaySetupView.HOST,
    val phase: NetplayPhase = NetplayPhase.DISCONNECTED,
    val role: NetplayRole? = null,
    val mode: LinkMode? = null,
    val endpoint: NetplayV8Endpoint? = null,
    val connectedPeers: Int = 0,
    val requiredPeers: Int = 0,
    val localPlayer: Int? = null,
    val localInstanceCount: Int = 0,
    val failure: NetplayFailure? = null,
    val notice: String? = null,
) {
  init {
    require(connectedPeers >= 0) { "Connected peer count cannot be negative" }
    require(requiredPeers >= 0) { "Required peer count cannot be negative" }
    require(connectedPeers <= requiredPeers || requiredPeers == 0) {
      "Connected peer count cannot exceed the required count"
    }
    require(localPlayer == null || localPlayer in 0..3) { "Netplay player must be in 0..3" }
    require(localInstanceCount in 0..3) { "Local instance count must be in 0..3" }
  }
}

internal enum class NetplaySessionAction {
  NONE,
  CANCEL,
  STOP_HOSTING,
  DISCONNECT,
  TRY_AGAIN,
}

internal data class NetplayUiPresentation(
    val state: NetplayUiState,
    val heading: String,
    val status: String,
    val gameText: String,
    val availabilityText: String,
    val sessionVisible: Boolean,
    val sessionDetails: String,
    val sessionAction: NetplaySessionAction,
    val canStart: Boolean,
)

internal fun presentNetplay(state: NetplayUiState): NetplayUiPresentation {
  val gameText = state.availability.gameName ?: "No game loaded"
  val sessionVisible = state.phase != NetplayPhase.DISCONNECTED
  val status =
      state.failure?.message
          ?: state.notice
          ?: when (state.phase) {
            NetplayPhase.DISCONNECTED -> "Choose Host or Join to begin."
            NetplayPhase.STARTING_HOST -> "Starting a protocol-v8 server on TCP port 6688."
            NetplayPhase.WAITING_FOR_PEERS ->
                "The server is listening on all local interfaces, subject to your firewall."
            NetplayPhase.CONNECTING -> "Resolving and connecting to the selected server."
            NetplayPhase.NEGOTIATING ->
                "Negotiating protocol v8 and synchronizing the linked game."
            NetplayPhase.ACTIVE -> "The linked session is active."
            NetplayPhase.STOPPING -> "Stopping the current netplay session."
            NetplayPhase.FAILED -> "The netplay connection failed."
          }
  val sessionDetails =
      when (state.role) {
        NetplayRole.HOST -> {
          val mode = state.mode?.displayName() ?: "Link"
          "$mode host, Player 1. ${state.connectedPeers} of ${state.requiredPeers} peer connections. TCP port 6688."
        }
        NetplayRole.CLIENT -> {
          val player = state.localPlayer?.plus(1)?.toString() ?: "not assigned yet"
          val mode = state.mode?.displayName() ?: "Mode not negotiated yet"
          "$mode client. Local player: $player."
        }
        null -> "No netplay session is active."
      }
  val action =
      when {
        state.phase == NetplayPhase.FAILED -> NetplaySessionAction.TRY_AGAIN
        state.phase in
            setOf(
                NetplayPhase.STARTING_HOST,
                NetplayPhase.CONNECTING,
                NetplayPhase.NEGOTIATING,
            ) -> NetplaySessionAction.CANCEL
        state.role == NetplayRole.HOST &&
            state.phase in setOf(NetplayPhase.WAITING_FOR_PEERS, NetplayPhase.ACTIVE) ->
            NetplaySessionAction.STOP_HOSTING
        state.role == NetplayRole.CLIENT && state.phase == NetplayPhase.ACTIVE ->
            NetplaySessionAction.DISCONNECT
        else -> NetplaySessionAction.NONE
      }
  return NetplayUiPresentation(
      state = state,
      heading = state.phase.title,
      status = status,
      gameText = gameText,
      availabilityText = state.availability.message,
      sessionVisible = sessionVisible,
      sessionDetails = sessionDetails,
      sessionAction = action,
      canStart = state.phase == NetplayPhase.DISCONNECTED && state.availability.available,
  )
}

internal data class NetplayWindowActions(
    val selectSetup: (NetplaySetupView) -> Unit,
    val startHosting: (LinkMode, Int) -> Unit,
    val join: (String) -> Unit,
    val stopSession: () -> Unit,
    val retry: () -> Unit,
    val editConnection: () -> Unit,
)

internal interface NetplayWindowView : AutoCloseable {
  fun render(presentation: NetplayUiPresentation)

  fun showOrRaise()

  /** Hides the retained modeless window without changing the active netplay session. */
  fun hide()
}

internal fun interface NetplayWindowViewFactory {
  fun create(actions: NetplayWindowActions): NetplayWindowView
}

/**
 * Retains one modeless v8 setup/session window and translates correlated connection callbacks into
 * an immutable EDT-owned presentation. Network work starts only after the controller confirms that
 * the peer-to-peer serial endpoint owns the link port.
 */
internal class NetplayWindowHost(
    rootEventBus: EventBus,
    private val viewFactory: NetplayWindowViewFactory,
    private val initialJoinEndpoint: NetplayV8Endpoint? = null,
    private val localRomPath: () -> Path? = { null },
    private val localInstanceLauncher: LocalNetplayInstanceLauncher =
        CurrentProcessLocalNetplayInstanceLauncher(),
    private val onPresentation: (NetplayUiPresentation) -> Unit = {},
    private val confirmPeripheralHandoff:
        (Controller.SerialPeripheralSelection) -> Boolean = { true },
) : AutoCloseable {
  private val eventBus: EventBus
  private var state = NetplayUiState()
  private var view: NetplayWindowView? = null
  private var latestProfile: HardwareProfile? = null
  private var idleSerialSelection = Controller.SerialPeripheralSelection.PEER_TO_PEER
  private var serialSelectionBeforeNetplay: Controller.SerialPeripheralSelection? = null
  private var restoringSerialSelection: Controller.SerialPeripheralSelection? = null
  private var pendingNetworkStart: PendingNetworkStart? = null
  private var activeAttemptId: Long? = null
  private var cancellationAttemptId: Long? = null
  private var nextAttemptId = 1L
  private var automaticJoinEndpoint = initialJoinEndpoint
  private var pendingLocalInstanceLaunch: PendingLocalInstanceLaunch? = null
  private var closed = false

  private sealed interface PendingNetworkStart {
    val attemptId: Long

    data class Host(
        override val attemptId: Long,
        val mode: LinkMode,
        val localInstanceCount: Int,
    ) : PendingNetworkStart

    data class Client(
        override val attemptId: Long,
        val endpoint: NetplayV8Endpoint,
    ) : PendingNetworkStart
  }

  private data class PendingLocalInstanceLaunch(
      val attemptId: Long,
      val count: Int,
  )

  constructor(
      owner: JFrame,
      rootEventBus: EventBus,
      initialJoinEndpoint: NetplayV8Endpoint? = null,
      localRomPath: () -> Path? = { null },
      localInstanceLauncher: LocalNetplayInstanceLauncher =
          CurrentProcessLocalNetplayInstanceLauncher(),
      onPresentation: (NetplayUiPresentation) -> Unit = {},
      confirmPeripheralHandoff: (Controller.SerialPeripheralSelection) -> Boolean = { true },
      initialBounds: Rectangle? = null,
      onBoundsChanged: (Rectangle) -> Unit = {},
  ) : this(
      rootEventBus,
      NetplayWindowViewFactory { actions ->
        SwingNetplayWindow(owner, actions, initialBounds, onBoundsChanged)
      },
      initialJoinEndpoint,
      localRomPath,
      localInstanceLauncher,
      onPresentation,
      confirmPeripheralHandoff,
  )

  init {
    requireNetplayEdt("Netplay host construction")
    check(TcpServer.PORT == NETPLAY_V8_PORT) { "Netplay UI port must match the v8 controller" }
    eventBus = rootEventBus.fork("desktop-netplay")
    registerLifecycle()
    onPresentation(presentNetplay(state))
  }

  fun show() {
    requireNetplayEdt("Netplay window opening")
    if (closed) return
    retainedView().showOrRaise()
  }

  internal fun currentPresentation(): NetplayUiPresentation {
    requireNetplayEdt("Netplay presentation access")
    return presentNetplay(state)
  }

  private fun retainedView(): NetplayWindowView =
      view
          ?: viewFactory.create(actions()).also { created ->
            created.render(presentNetplay(state))
            view = created
          }

  private fun actions(): NetplayWindowActions =
      NetplayWindowActions(
          selectSetup = ::selectSetup,
          startHosting = ::startHosting,
          join = ::join,
          stopSession = ::stopSession,
          retry = ::retry,
          editConnection = ::editConnection,
      )

  private fun selectSetup(setup: NetplaySetupView) {
    requireNetplayEdt("Netplay setup selection")
    if (closed || state.phase != NetplayPhase.DISCONNECTED) return
    update(state.copy(setupView = setup, failure = null, notice = null))
  }

  private fun startHosting(mode: LinkMode, localInstanceCount: Int = 0) {
    requireNetplayEdt("Netplay host start")
    if (closed || state.phase != NetplayPhase.DISCONNECTED || !state.availability.available) return
    if (localInstanceCount !in 0..mode.playerCount - 1) return
    val previous = serialSelectionForRestore()
    if (!mayTakePeerToPeerPort(previous)) return
    val pending = PendingNetworkStart.Host(allocateAttemptId(), mode, localInstanceCount)
    cancellationAttemptId = null
    update(
        state.copy(
            setupView = NetplaySetupView.HOST,
            phase = NetplayPhase.STARTING_HOST,
            role = NetplayRole.HOST,
            mode = mode,
            endpoint = null,
            connectedPeers = 0,
            requiredPeers = mode.playerCount - 1,
            localPlayer = 0,
            localInstanceCount = localInstanceCount,
            failure = null,
            notice = null,
        ))
    preparePeerToPeerPort(pending, previous)
  }

  private fun join(address: String) {
    requireNetplayEdt("Netplay client start")
    if (closed || state.phase != NetplayPhase.DISCONNECTED || !state.availability.available) return
    val endpoint = (validateNetplayV8Address(address) as? NetplayAddressValidation.Valid)?.endpoint
        ?: return
    val previous = serialSelectionForRestore()
    if (!mayTakePeerToPeerPort(previous)) return
    val pending = PendingNetworkStart.Client(allocateAttemptId(), endpoint)
    cancellationAttemptId = null
    update(
        state.copy(
            setupView = NetplaySetupView.JOIN,
            phase = NetplayPhase.CONNECTING,
            role = NetplayRole.CLIENT,
            mode = null,
            endpoint = endpoint,
            connectedPeers = 0,
            requiredPeers = 0,
            localPlayer = null,
            failure = null,
            notice = null,
        ))
    preparePeerToPeerPort(pending, previous)
  }

  private fun startAutomaticJoinIfReady() {
    val endpoint = automaticJoinEndpoint ?: return
    if (state.phase != NetplayPhase.DISCONNECTED || !state.availability.available) return
    automaticJoinEndpoint = null
    join(endpoint.startClientValue)
  }

  private fun launchPendingLocalInstances(attemptId: Long) {
    dispatchSwingMutation {
      if (closed || state.role != NetplayRole.HOST || !matchesAttempt(attemptId)) {
        return@dispatchSwingMutation
      }
      val pending = pendingLocalInstanceLaunch?.takeIf { it.attemptId == attemptId }
          ?: return@dispatchSwingMutation
      pendingLocalInstanceLaunch = null
      val rom = localRomPath()
      val profile = latestProfile
      if (rom == null || profile == null) {
        update(
            state.copy(
                notice =
                    "Hosting is ready, but local client instances require a game opened directly from a file.",
            ))
        return@dispatchSwingMutation
      }
      val localhost =
          (validateNetplayV8Address("localhost") as NetplayAddressValidation.Valid).endpoint
      update(
          state.copy(
              notice = localInstanceLauncher.launch(rom, profile, localhost, pending.count).userMessage(),
          ))
    }
  }

  private fun serialSelectionForRestore(): Controller.SerialPeripheralSelection =
      restoringSerialSelection ?: idleSerialSelection

  private fun mayTakePeerToPeerPort(previous: Controller.SerialPeripheralSelection): Boolean =
      previous in
          setOf(
              Controller.SerialPeripheralSelection.PEER_TO_PEER,
              Controller.SerialPeripheralSelection.NONE,
          ) || confirmPeripheralHandoff(previous)

  private fun preparePeerToPeerPort(
      pending: PendingNetworkStart,
      previous: Controller.SerialPeripheralSelection,
  ) {
    val awaitingOlderRestore = restoringSerialSelection != null
    serialSelectionBeforeNetplay = previous
    restoringSerialSelection = null
    pendingNetworkStart = pending
    activeAttemptId = pending.attemptId
    val alreadyPeerToPeer =
        !awaitingOlderRestore &&
            idleSerialSelection == Controller.SerialPeripheralSelection.PEER_TO_PEER
    eventBus.post(
        Controller.SetSerialPeripheralEvent(Controller.SerialPeripheralSelection.PEER_TO_PEER))
    if (alreadyPeerToPeer) launchPendingNetworkStart(pending)
  }

  private fun launchPendingNetworkStart(pending: PendingNetworkStart) {
    if (closed || activeAttemptId != pending.attemptId || pendingNetworkStart != pending) return
    pendingNetworkStart = null
    when (pending) {
      is PendingNetworkStart.Host -> {
        if (pending.localInstanceCount > 0) {
          pendingLocalInstanceLaunch =
              PendingLocalInstanceLaunch(pending.attemptId, pending.localInstanceCount)
        }
        eventBus.post(StartServerEvent(pending.mode, pending.attemptId))
      }
      is PendingNetworkStart.Client ->
          eventBus.post(StartClientEvent(pending.endpoint.startClientValue, pending.attemptId))
    }
  }

  private fun restoreSerialPeripheralAfterNetplay(forceRequest: Boolean = false) {
    val previous = serialSelectionBeforeNetplay ?: return
    serialSelectionBeforeNetplay = null
    pendingNetworkStart = null
    if (previous == Controller.SerialPeripheralSelection.PEER_TO_PEER ||
        (!forceRequest && idleSerialSelection == previous)) {
      idleSerialSelection = previous
      restoringSerialSelection = null
      return
    }
    restoringSerialSelection = previous
    eventBus.post(Controller.SetSerialPeripheralEvent(previous))
  }

  private fun stopSession() {
    requireNetplayEdt("Netplay session stop")
    if (closed) return
    when {
      state.role == NetplayRole.HOST && state.phase == NetplayPhase.STARTING_HOST ->
          cancelAttempt(
              NetplayRole.HOST,
              NetplaySetupView.HOST,
              "Hosting was canceled before the session started.",
          )
      state.role == NetplayRole.CLIENT &&
          state.phase in setOf(NetplayPhase.CONNECTING, NetplayPhase.NEGOTIATING) ->
          cancelAttempt(
              NetplayRole.CLIENT,
              NetplaySetupView.JOIN,
              "The connection attempt was canceled.",
          )
      state.role == NetplayRole.HOST &&
          state.phase in setOf(NetplayPhase.WAITING_FOR_PEERS, NetplayPhase.ACTIVE) -> {
        update(state.copy(phase = NetplayPhase.STOPPING, failure = null))
        eventBus.post(StopServerEvent(activeAttemptId ?: ConnectionController.LEGACY_ATTEMPT))
      }
      state.role == NetplayRole.CLIENT && state.phase == NetplayPhase.ACTIVE -> {
        val attemptId = activeAttemptId
        update(state.copy(phase = NetplayPhase.STOPPING, failure = null))
        eventBus.post(StopClientEvent(attemptId ?: ConnectionController.LEGACY_ATTEMPT))

        // The connection may already have ended remotely. In that case the controller has
        // cleared its client before this event is handled and cannot publish a terminal event
        // in response to StopClientEvent. Do not leave the window permanently in STOPPING while
        // waiting for an event that will never arrive.
        if (
            attemptId != null &&
                activeAttemptId == attemptId &&
                state.phase == NetplayPhase.STOPPING
        ) {
          activeAttemptId = null
          restoreSerialPeripheralAfterNetplay()
          update(disconnectedState(NetplaySetupView.JOIN))
        }
      }
    }
  }

  private fun cancelAttempt(
      role: NetplayRole,
      setup: NetplaySetupView,
      notice: String,
  ) {
    val attemptId = activeAttemptId ?: return
    val waitingForSerialCommit = pendingNetworkStart?.attemptId == attemptId
    if (waitingForSerialCommit) {
      pendingNetworkStart = null
      postAttemptStop(role, attemptId)
      activeAttemptId = null
      restoreSerialPeripheralAfterNetplay(forceRequest = true)
      update(disconnectedState(setup, notice))
      return
    }

    cancellationAttemptId = attemptId
    update(state.copy(phase = NetplayPhase.STOPPING, failure = null, notice = null))
    postAttemptStop(role, attemptId)
  }

  private fun postAttemptStop(role: NetplayRole, attemptId: Long) {
    when (role) {
      NetplayRole.HOST -> eventBus.post(StopServerEvent(attemptId))
      NetplayRole.CLIENT -> eventBus.post(StopClientEvent(attemptId))
    }
  }

  private fun retry() {
    requireNetplayEdt("Netplay retry")
    if (closed || state.phase != NetplayPhase.FAILED) return
    val old = state
    update(disconnectedState(old.setupView))
    when (old.role) {
      NetplayRole.HOST -> old.mode?.let { startHosting(it, old.localInstanceCount) }
      NetplayRole.CLIENT -> old.endpoint?.startClientValue?.let(::join)
      null -> Unit
    }
  }

  private fun editConnection() {
    requireNetplayEdt("Netplay connection editing")
    if (closed || state.phase != NetplayPhase.FAILED) return
    pendingNetworkStart = null
    activeAttemptId = null
    cancellationAttemptId = null
    update(disconnectedState(state.setupView))
  }

  private fun allocateAttemptId(): Long {
    val allocated = nextAttemptId
    nextAttemptId = if (allocated == Long.MAX_VALUE) 1L else allocated + 1L
    return allocated
  }

  private fun disconnectedState(
      setup: NetplaySetupView = state.setupView,
      notice: String? = null,
  ): NetplayUiState =
      NetplayUiState(
          availability = state.availability,
          setupView = setup,
          mode = state.mode,
          endpoint = state.endpoint,
          localInstanceCount = state.localInstanceCount,
          notice = notice,
      )

  private fun failPendingSerialAttachment(event: Controller.SerialPeripheralStatusEvent) {
    val previous = serialSelectionBeforeNetplay ?: idleSerialSelection
    pendingNetworkStart = null
    activeAttemptId = null
    cancellationAttemptId = null
    serialSelectionBeforeNetplay = null
    restoringSerialSelection = null
    // UNAVAILABLE is a failed prepare/commit: the controller guarantees the previous owner stayed.
    idleSerialSelection = previous
    update(
        state.copy(
            phase = NetplayPhase.FAILED,
            failure =
                NetplayFailure(
                    NetplayFailureKind.LINK_PORT,
                    "Coffee GB could not reserve the link port for netplay. " +
                        serialPeripheralFailureDetail(event),
                ),
        ))
  }

  private fun failSerialRestore(event: Controller.SerialPeripheralStatusEvent) {
    restoringSerialSelection = null
    // A failed restoration leaves the netplay peer-to-peer endpoint committed.
    idleSerialSelection = Controller.SerialPeripheralSelection.PEER_TO_PEER
    val message =
        "Coffee GB could not restore the previous link-port device. " +
            serialPeripheralFailureDetail(event)
    val previousFailure = state.failure
    val failure =
        if (previousFailure == null) {
          NetplayFailure(NetplayFailureKind.LINK_PORT, message)
        } else {
          previousFailure.copy(message = "${previousFailure.message} $message")
        }
    update(state.copy(failure = failure))
  }

  private fun serialPeripheralFailureDetail(event: Controller.SerialPeripheralStatusEvent): String =
      event.error?.userMessage
          ?: "The controller did not attach the requested serial peripheral."

  private fun registerLifecycle() {
    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent> { event ->
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        idleSerialSelection = event.selection
        val pending = pendingNetworkStart
        if (pending != null &&
            event.selection == Controller.SerialPeripheralSelection.PEER_TO_PEER) {
          restoringSerialSelection = null
          launchPendingNetworkStart(pending)
          return@dispatchSwingMutation
        }
        val restoring = restoringSerialSelection
        if (event.selection == restoring) {
          restoringSerialSelection = null
        } else if (serialSelectionBeforeNetplay != null &&
            event.selection != Controller.SerialPeripheralSelection.PEER_TO_PEER) {
          // Respect an explicit peripheral choice made while a stop/retry transition is pending.
          serialSelectionBeforeNetplay = event.selection
        }
      }
    }
    eventBus.register<Controller.SerialPeripheralStatusEvent> { event ->
      dispatchSwingMutation {
        if (closed || event.status != Controller.SerialPeripheralStatus.UNAVAILABLE) {
          return@dispatchSwingMutation
        }
        when {
          pendingNetworkStart != null &&
              event.selection == Controller.SerialPeripheralSelection.PEER_TO_PEER ->
              failPendingSerialAttachment(event)
          event.selection == restoringSerialSelection -> failSerialRestore(event)
        }
      }
    }
    eventBus.register<Controller.HardwareProfileEvent> { event ->
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        latestProfile = event.profile
        val game = state.availability.gameName
        if (game != null) {
          update(state.copy(availability = availability(game, event.profile)))
          startAutomaticJoinIfReady()
        }
      }
    }
    eventBus.register<Controller.EmulationStartedEvent> { event ->
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        val profile = latestProfile
        val availability =
            if (profile == null) NetplayAvailability.CheckingProfile(event.romName)
            else availability(event.romName, profile)
        update(state.copy(availability = availability))
        startAutomaticJoinIfReady()
      }
    }
    eventBus.register<Controller.EmulationStoppedEvent> {
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        latestProfile = null
        update(state.copy(availability = NetplayAvailability.NoGame))
      }
    }
    eventBus.register<StartServerEvent> { event ->
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        if (state.role == NetplayRole.HOST && activeAttemptId == event.attemptId) {
          return@dispatchSwingMutation
        }
        pendingNetworkStart = null
        activeAttemptId = event.attemptId
        cancellationAttemptId = null
        update(
            state.copy(
                phase = NetplayPhase.STARTING_HOST,
                role = NetplayRole.HOST,
                mode = event.mode,
                endpoint = null,
                connectedPeers = 0,
                requiredPeers = event.mode.playerCount - 1,
                localPlayer = 0,
                localInstanceCount = 0,
                failure = null,
                notice = null,
            ))
      }
    }
    eventBus.register<StartClientEvent> { event ->
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        if (state.role == NetplayRole.CLIENT && activeAttemptId == event.attemptId) {
          return@dispatchSwingMutation
        }
        pendingNetworkStart = null
        activeAttemptId = event.attemptId
        cancellationAttemptId = null
        val endpoint =
            (validateNetplayV8Address(event.host) as? NetplayAddressValidation.Valid)?.endpoint
        update(
            state.copy(
                setupView = NetplaySetupView.JOIN,
                phase = NetplayPhase.CONNECTING,
                role = NetplayRole.CLIENT,
                mode = null,
                endpoint = endpoint,
                connectedPeers = 0,
                requiredPeers = 0,
                localPlayer = null,
                failure = null,
                notice = null,
            ))
      }
    }
    eventBus.register<ServerStartedEvent> { event ->
      mutateHost(event.attemptId) {
        it.copy(
            phase = NetplayPhase.WAITING_FOR_PEERS,
            mode = event.mode,
            connectedPeers = 0,
            requiredPeers = event.mode.playerCount - 1,
            localPlayer = 0,
            failure = null,
        )
      }
      launchPendingLocalInstances(event.attemptId)
    }
    eventBus.register<ServerStartFailedEvent> { event ->
      mutateHost(event.attemptId, terminal = true) {
        clearPendingLocalInstanceLaunch(event.attemptId)
        val canceled = consumeCancellation(event.attemptId)
        restoreSerialPeripheralAfterNetplay()
        if (canceled) {
          disconnectedState(
              NetplaySetupView.HOST,
              "Hosting was canceled before the session started.",
          )
        } else {
          it.copy(
              phase = NetplayPhase.FAILED,
              failure =
                  NetplayFailure(
                      NetplayFailureKind.DISCONNECTED,
                      "Coffee GB could not listen on TCP port ${event.port}. " +
                          "Another application may already be using it.",
                  ),
              notice = null,
          )
        }
      }
    }
    eventBus.register<ServerPlayerCountEvent> { event ->
      mutateHost(event.attemptId) {
        val active = it.phase == NetplayPhase.ACTIVE
        it.copy(
            phase = if (active) NetplayPhase.ACTIVE else NetplayPhase.WAITING_FOR_PEERS,
            mode = event.mode,
            connectedPeers = event.connected.coerceIn(0, event.required),
            requiredPeers = event.required,
        )
      }
    }
    eventBus.register<ServerGotConnectionEvent> { event ->
      mutateHost(event.attemptId) {
        it.copy(
            phase = NetplayPhase.ACTIVE,
            mode = event.mode,
            localPlayer = 0,
            connectedPeers =
                if (event.mode == LinkMode.NORMAL) event.mode.playerCount - 1
                else it.connectedPeers,
            requiredPeers = event.mode.playerCount - 1,
            failure = null,
        )
      }
    }
    eventBus.register<ServerPlayerDisconnectedEvent> { event ->
      mutateHost(event.attemptId) {
        it.copy(connectedPeers = (it.connectedPeers - 1).coerceAtLeast(0))
      }
    }
    eventBus.register<ServerLostConnectionEvent> { event ->
      mutateHost(event.attemptId) {
        it.copy(
            phase = NetplayPhase.WAITING_FOR_PEERS,
            connectedPeers = 0,
            failure =
                it.failure
                    ?: NetplayFailure(
                        NetplayFailureKind.DISCONNECTED,
                        "The peer disconnected. The server is still waiting for a new peer.",
                    ),
        )
      }
    }
    eventBus.register<ServerProtocolErrorEvent> { event ->
      mutateHost(event.attemptId) {
        it.copy(
            failure =
                NetplayFailure(
                    NetplayFailureKind.PROTOCOL,
                    "Player ${event.player + 1}: ${literalNetworkMessage(event.message)}",
                ))
      }
    }
    eventBus.register<StopServerEvent> { event ->
      mutateHost(event.attemptId) { current ->
        if (current.phase in setOf(NetplayPhase.STARTING_HOST, NetplayPhase.STOPPING)) current
        else current.copy(phase = NetplayPhase.STOPPING, failure = null)
      }
    }
    eventBus.register<ServerStoppedEvent> { event ->
      mutateHost(event.attemptId, terminal = true) {
        clearPendingLocalInstanceLaunch(event.attemptId)
        val canceled = consumeCancellation(event.attemptId)
        restoreSerialPeripheralAfterNetplay()
        disconnectedState(
            NetplaySetupView.HOST,
            if (canceled) "Hosting was canceled before the session started." else null,
        )
      }
    }
    eventBus.register<ClientHandshakeCompletedEvent> { event ->
      mutateClient(event.attemptId) {
        it.copy(
            phase = NetplayPhase.NEGOTIATING,
            mode = event.mode,
            localPlayer = event.player,
            failure = null,
        )
      }
    }
    eventBus.register<ClientConnectedToServerEvent> { event ->
      mutateClient(event.attemptId) {
        it.copy(
            phase = NetplayPhase.ACTIVE,
            mode = event.mode,
            localPlayer = event.player,
            failure = null,
        )
      }
    }
    eventBus.register<ClientConnectionRejectedEvent> { event ->
      failClient(NetplayFailureKind.REJECTED, event.message, event.attemptId)
    }
    eventBus.register<ClientProtocolErrorEvent> { event ->
      failClient(NetplayFailureKind.PROTOCOL, event.message, event.attemptId)
    }
    eventBus.register<StopClientEvent> { event ->
      mutateClient(event.attemptId) { current ->
        if (current.phase == NetplayPhase.ACTIVE) {
          current.copy(phase = NetplayPhase.STOPPING, failure = null)
        } else {
          current
        }
      }
    }
    eventBus.register<ClientDisconnectedFromServerEvent> { event ->
      mutateClient(event.attemptId, terminal = true) { current ->
        val canceled = consumeCancellation(event.attemptId)
        val next = when (current.phase) {
          NetplayPhase.STOPPING ->
              disconnectedState(
                  NetplaySetupView.JOIN,
                  if (canceled) "The connection attempt was canceled." else null,
              )
          NetplayPhase.FAILED -> current
          else ->
              current.copy(
                  phase = NetplayPhase.FAILED,
                  failure =
                      NetplayFailure(
                          NetplayFailureKind.DISCONNECTED,
                          "The connection ended before the session was stopped locally.",
                      ),
              )
        }
        if (next.phase == NetplayPhase.DISCONNECTED || next.phase == NetplayPhase.FAILED) {
          restoreSerialPeripheralAfterNetplay()
        }
        next
      }
    }
  }

  private fun failClient(kind: NetplayFailureKind, message: String, attemptId: Long) {
    mutateClient(attemptId) {
      restoreSerialPeripheralAfterNetplay()
      it.copy(
          phase = NetplayPhase.FAILED,
          failure = NetplayFailure(kind, literalNetworkMessage(message)),
      )
    }
  }

  private fun clearPendingLocalInstanceLaunch(attemptId: Long) {
    if (pendingLocalInstanceLaunch?.attemptId == attemptId) {
      pendingLocalInstanceLaunch = null
    }
  }

  private fun mutateHost(
      attemptId: Long,
      terminal: Boolean = false,
      transform: (NetplayUiState) -> NetplayUiState,
  ) {
    dispatchSwingMutation {
      if (closed || state.role != NetplayRole.HOST || !matchesAttempt(attemptId)) {
        return@dispatchSwingMutation
      }
      if (!terminal && state.phase == NetplayPhase.STOPPING) return@dispatchSwingMutation
      if (terminal) activeAttemptId = null
      update(transform(state))
    }
  }

  private fun mutateClient(
      attemptId: Long,
      terminal: Boolean = false,
      transform: (NetplayUiState) -> NetplayUiState,
  ) {
    dispatchSwingMutation {
      if (closed || state.role != NetplayRole.CLIENT || !matchesAttempt(attemptId)) {
        return@dispatchSwingMutation
      }
      if (!terminal && state.phase == NetplayPhase.STOPPING) return@dispatchSwingMutation
      if (terminal) activeAttemptId = null
      update(transform(state))
    }
  }

  private fun matchesAttempt(attemptId: Long): Boolean =
      attemptId == ConnectionController.LEGACY_ATTEMPT || activeAttemptId == attemptId

  private fun consumeCancellation(attemptId: Long): Boolean {
    val canceledAttempt = cancellationAttemptId ?: return false
    if (attemptId != ConnectionController.LEGACY_ATTEMPT && attemptId != canceledAttempt) {
      return false
    }
    cancellationAttemptId = null
    return true
  }

  private fun update(next: NetplayUiState) {
    requireNetplayEdt("Netplay presentation update")
    val becameActive = state.phase != NetplayPhase.ACTIVE && next.phase == NetplayPhase.ACTIVE
    state = next
    val presentation = presentNetplay(next)
    view?.render(presentation)
    if (becameActive) view?.hide()
    onPresentation(presentation)
  }

  override fun close() {
    requireNetplayEdt("Netplay host disposal")
    if (closed) return
    closed = true
    var failure: Exception? = null
    try {
      eventBus.close()
    } catch (problem: Exception) {
      failure = problem
    }
    try {
      view?.close()
    } catch (problem: Exception) {
      failure?.addSuppressed(problem) ?: run { failure = problem }
    } finally {
      view = null
    }
    failure?.let { throw it }
  }

  private fun availability(gameName: String, profile: HardwareProfile): NetplayAvailability =
      if (StateProfilePolicy.protocolV8Representable(profile)) {
        NetplayAvailability.Available(gameName, profile.id(), profile.displayName())
      } else {
        NetplayAvailability.IncompatibleProfile(gameName, profile.id(), profile.displayName())
      }
}

private class SwingNetplayWindow(
    owner: JFrame,
    private val actions: NetplayWindowActions,
    initialBounds: Rectangle?,
    private val onBoundsChanged: (Rectangle) -> Unit,
) : NetplayWindowView {
  private val dialog = JDialog(owner, "Netplay — Coffee GB", Dialog.ModalityType.MODELESS)
  private val panel = NetplayPanel(actions)
  private var positioned = initialBounds != null

  init {
    requireNetplayEdt("Netplay Swing view construction")
    dialog.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
    dialog.minimumSize = Dimension(620, 520)
    dialog.preferredSize = Dimension(690, 580)
    dialog.contentPane = panel
    dialog.accessibleContext.accessibleName = "Netplay"
    dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide-netplay")
    dialog.rootPane.actionMap.put(
        "hide-netplay",
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) {
            publishBounds()
            dialog.isVisible = false
          }
        })
    dialog.pack()
    initialBounds?.let { dialog.bounds = Rectangle(it) }
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentMoved(event: ComponentEvent) = publishBounds()

          override fun componentResized(event: ComponentEvent) = publishBounds()
        })
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = publishBounds()
        })
  }

  override fun render(presentation: NetplayUiPresentation) {
    requireNetplayEdt("Netplay Swing view rendering")
    panel.render(presentation)
  }

  override fun showOrRaise() {
    requireNetplayEdt("Netplay Swing view opening")
    if (!positioned) {
      dialog.setLocationRelativeTo(dialog.owner)
      positioned = true
    }
    dialog.isVisible = true
    dialog.toFront()
    dialog.requestFocus()
  }

  override fun hide() {
    requireNetplayEdt("Netplay Swing view hiding")
    if (!dialog.isVisible) return
    publishBounds()
    dialog.isVisible = false
  }

  override fun close() {
    requireNetplayEdt("Netplay Swing view disposal")
    publishBounds()
    dialog.dispose()
  }

  private fun publishBounds() {
    if (dialog.width > 0 && dialog.height > 0) {
      onBoundsChanged(Rectangle(dialog.bounds))
    }
  }
}

/** The headless-testable content of the retained window. */
internal class NetplayPanel(private val actions: NetplayWindowActions) :
    JPanel(BorderLayout(12, 12)), DesktopThemeRefreshHook {
  private val heading = JLabel("Set up netplay")
  private val status = literalTextArea("Choose Host or Join to begin.", 2)
  private val hostTab = JToggleButton("Host")
  private val joinTab = JToggleButton("Join")
  private val contentLayout = CardLayout()
  private val content = JPanel(contentLayout)
  private val setupLayout = CardLayout()
  private val setupCards = JPanel(setupLayout)
  private val gameValues = mutableListOf<JLabel>()
  private val availabilityMessages = mutableListOf<JTextArea>()
  private val normalMode = JRadioButton("2-player link", true)
  private val fourPlayerMode = JRadioButton("4-player adapter")
  private val startLocalInstances = JCheckBox("Start")
  private val localInstanceCount = JComboBox(DefaultComboBoxModel(arrayOf(1)))
  private val localInstanceSuffix = JLabel("local Coffee GB instance and connect it to this host")
  private val startHosting = JButton("Start hosting")
  private val address = JTextField("127.0.0.1", 28)
  private val addressValidation = literalTextArea(" ", 2)
  private val joinGame = JButton("Join game")
  private val sessionDetails = literalTextArea("No netplay session is active.", 2)
  private val sessionStatus = literalTextArea(" ", 3)
  private val sessionAction = JButton()
  private val editConnection = JButton("Edit connection")
  private val privacyNotices = mutableListOf<JPanel>()
  private val privacyTitles = mutableListOf<JLabel>()
  private var themeTokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
  private var presentation = presentNetplay(NetplayUiState())
  private var rendering = false

  init {
    requireNetplayEdt("Netplay panel construction")
    border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
    getAccessibleContext().accessibleName = "Netplay setup and session"

    heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 4f)
    heading.accessibleContext.accessibleName = "Netplay status heading"
    status.accessibleContext.accessibleName = "Netplay status"
    val header = JPanel(BorderLayout(0, 5))
    header.add(heading, BorderLayout.NORTH)
    header.add(status, BorderLayout.CENTER)
    add(header, BorderLayout.NORTH)

    val setup = JPanel(BorderLayout(0, 12))
    val tabs = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    ButtonGroup().apply {
      add(hostTab)
      add(joinTab)
    }
    hostTab.accessibleContext.accessibleName = "Host netplay"
    joinTab.accessibleContext.accessibleName = "Join netplay"
    hostTab.addActionListener {
      if (!rendering) actions.selectSetup(NetplaySetupView.HOST)
    }
    joinTab.addActionListener {
      if (!rendering) actions.selectSetup(NetplaySetupView.JOIN)
    }
    tabs.add(hostTab)
    tabs.add(joinTab)
    setup.add(tabs, BorderLayout.NORTH)

    setupCards.add(createHostCard(), NetplaySetupView.HOST.name)
    setupCards.add(createJoinCard(), NetplaySetupView.JOIN.name)
    setup.add(setupCards, BorderLayout.CENTER)
    content.add(setup, SETUP_CARD)
    content.add(createSessionCard(), SESSION_CARD)
    add(content, BorderLayout.CENTER)

    render(presentation)
  }

  override fun getMinimumSize(): Dimension = Dimension(560, 440)

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    themeTokens = tokens
    val labelFont = UIManager.getFont("Label.font") ?: heading.font
    heading.font = labelFont.deriveFont(Font.BOLD, labelFont.size2D + 4f)
    privacyTitles.forEach { title -> title.font = labelFont.deriveFont(Font.BOLD) }
    privacyNotices.forEach { panel ->
      panel.border =
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(tokens.border),
              BorderFactory.createEmptyBorder(10, 10, 10, 10),
          )
    }
    refreshLiteralTextStyles(this, labelFont, tokens.primaryText)
    updateAddressValidation()
    revalidate()
    repaint()
  }

  internal fun render(next: NetplayUiPresentation) {
    requireNetplayEdt("Netplay panel rendering")
    rendering = true
    try {
      presentation = next
      heading.text = next.heading
      status.text = next.status
      gameValues.forEach { it.text = next.gameText }
      availabilityMessages.forEach { it.text = next.availabilityText }
      hostTab.isSelected = next.state.setupView == NetplaySetupView.HOST
      joinTab.isSelected = next.state.setupView == NetplaySetupView.JOIN
      setupLayout.show(setupCards, next.state.setupView.name)
      normalMode.isSelected = next.state.mode != LinkMode.FOUR_PLAYER_ADAPTER
      fourPlayerMode.isSelected = next.state.mode == LinkMode.FOUR_PLAYER_ADAPTER
      updateLocalInstanceControls()
      if (next.state.setupView == NetplaySetupView.JOIN &&
          next.state.phase == NetplayPhase.DISCONNECTED &&
          next.state.endpoint != null &&
          address.text != next.state.endpoint.startClientValue) {
        address.text = next.state.endpoint.startClientValue
      }
      startHosting.isEnabled = next.canStart
      sessionDetails.text = next.sessionDetails
      sessionStatus.text = next.status
      configureSessionAction(next.sessionAction)
      editConnection.isVisible = next.state.phase == NetplayPhase.FAILED
      contentLayout.show(content, if (next.sessionVisible) SESSION_CARD else SETUP_CARD)
      updateAddressValidation()
    } finally {
      rendering = false
    }
  }

  private fun createHostCard(): JPanel {
    val card = JPanel(BorderLayout(0, 12))
    card.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    val form = JPanel(GridBagLayout())
    val game = JLabel()
    gameValues += game
    addFormRow(form, 0, "Game", game)
    val modes = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    ButtonGroup().apply {
      add(normalMode)
      add(fourPlayerMode)
    }
    normalMode.accessibleContext.accessibleDescription = "Host one other player over a normal link cable."
    fourPlayerMode.accessibleContext.accessibleDescription =
        "Host up to three peers through the four-player adapter."
    modes.add(normalMode)
    modes.add(fourPlayerMode)
    addFormRow(form, 1, "Mode", modes)
    normalMode.addActionListener {
      if (!rendering) updateLocalInstanceControls()
    }
    fourPlayerMode.addActionListener {
      if (!rendering) updateLocalInstanceControls()
    }

    startLocalInstances.accessibleContext.accessibleName = "Start local Coffee GB instances"
    startLocalInstances.accessibleContext.accessibleDescription =
        "Launch local Coffee GB instances with this game and connect them to this host."
    startLocalInstances.addActionListener { updateLocalInstanceControls() }
    localInstanceCount.accessibleContext.accessibleName = "Number of local Coffee GB instances"
    localInstanceCount.accessibleContext.accessibleDescription =
        "Two-player link can launch one local client; the four-player adapter can launch one to three."
    localInstanceCount.addActionListener { updateLocalInstanceSuffix() }
    val localInstances = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0))
    localInstances.add(startLocalInstances)
    localInstances.add(localInstanceCount)
    localInstances.add(localInstanceSuffix)
    addFormRow(form, 2, "Local clients", localInstances)

    addFormRow(form, 3, "Port", JLabel("6688 (fixed by protocol v8)"))
    val availability = literalTextArea(" ", 3)
    availability.accessibleContext.accessibleName = "Hosting availability"
    availabilityMessages += availability
    val availabilityConstraints = formValueConstraints(4)
    availabilityConstraints.gridwidth = 2
    availabilityConstraints.gridx = 0
    form.add(availability, availabilityConstraints)
    card.add(form, BorderLayout.NORTH)

    val center = JPanel(BorderLayout(0, 10))
    center.add(
        literalTextArea(
            "Four-player hosting accepts up to three peers. The server assigns player slots. " +
                "Coffee GB does not discover a public address, configure a firewall or router, " +
                "or provide matchmaking, relay, or NAT traversal.",
            4,
        ),
        BorderLayout.NORTH,
    )
    center.add(createPrivacyNotice(hosting = true), BorderLayout.CENTER)
    card.add(center, BorderLayout.CENTER)

    startHosting.accessibleContext.accessibleDescription =
        "Start a direct TCP protocol-v8 server on port 6688."
    startHosting.addActionListener {
      actions.startHosting(
          if (fourPlayerMode.isSelected) LinkMode.FOUR_PLAYER_ADAPTER else LinkMode.NORMAL,
          if (startLocalInstances.isSelected) localInstanceCount.selectedItem as Int else 0,
      )
    }
    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0))
    buttons.add(startHosting)
    card.add(buttons, BorderLayout.SOUTH)
    return card
  }

  private fun updateLocalInstanceControls() {
    val options = if (fourPlayerMode.isSelected) arrayOf(1, 2, 3) else arrayOf(1)
    val selected = localInstanceCount.selectedItem as? Int ?: 1
    if ((0 until localInstanceCount.itemCount).map(localInstanceCount::getItemAt) != options.toList()) {
      localInstanceCount.model = DefaultComboBoxModel(options)
    }
    localInstanceCount.selectedItem = selected.takeIf { it in options } ?: 1
    localInstanceCount.isEnabled = startLocalInstances.isSelected
    updateLocalInstanceSuffix()
  }

  private fun updateLocalInstanceSuffix() {
    localInstanceSuffix.text =
        if (localInstanceCount.selectedItem == 1) {
          "local Coffee GB instance and connect it to this host"
        } else {
          "local Coffee GB instances and connect them to this host"
        }
  }

  private fun createJoinCard(): JPanel {
    val card = JPanel(BorderLayout(0, 12))
    card.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    val form = JPanel(GridBagLayout())
    val game = JLabel()
    gameValues += game
    addFormRow(form, 0, "Game", game)
    val addressLabel = JLabel("Address")
    addressLabel.labelFor = address
    address.accessibleContext.accessibleName = "Netplay server address"
    address.accessibleContext.accessibleDescription =
        "Hostname or IPv4 address, with an optional port. The default port is 6688."
    val labelConstraints = formLabelConstraints(1)
    form.add(addressLabel, labelConstraints)
    form.add(address, formValueConstraints(1))
    val example = literalTextArea("Example: 192.168.1.20 or gameserver.local:6688", 1)
    example.accessibleContext.accessibleName = "Netplay address example"
    val exampleConstraints = formValueConstraints(2)
    form.add(example, exampleConstraints)
    addressValidation.accessibleContext.accessibleName = "Netplay address validation"
    val validationConstraints = formValueConstraints(3)
    form.add(addressValidation, validationConstraints)
    val availability = literalTextArea(" ", 3)
    availability.accessibleContext.accessibleName = "Joining availability"
    availabilityMessages += availability
    val availabilityConstraints = formValueConstraints(4)
    availabilityConstraints.gridwidth = 2
    availabilityConstraints.gridx = 0
    form.add(availability, availabilityConstraints)
    card.add(form, BorderLayout.NORTH)
    card.add(createPrivacyNotice(hosting = false), BorderLayout.CENTER)

    address.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = updateAddressValidation()

          override fun removeUpdate(event: DocumentEvent) = updateAddressValidation()

          override fun changedUpdate(event: DocumentEvent) = updateAddressValidation()
        })
    address.addActionListener {
      if (joinGame.isEnabled) actions.join(address.text)
    }
    joinGame.accessibleContext.accessibleDescription =
        "Connect directly to a protocol-v8 server."
    joinGame.addActionListener { actions.join(address.text) }
    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0))
    buttons.add(joinGame)
    card.add(buttons, BorderLayout.SOUTH)
    return card
  }

  private fun createPrivacyNotice(hosting: Boolean): JPanel {
    val panel = JPanel(BorderLayout(0, 6))
    panel.border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor") ?: Color.GRAY),
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
        )
    val title = JLabel("Data sent and received")
    title.font = title.font.deriveFont(Font.BOLD)
    privacyNotices += panel
    privacyTitles += title
    val hostText =
        if (hosting) " Hosting listens on all local interfaces, subject to the firewall." else ""
    val notice =
        literalTextArea(
            "Each peer may send and receive its primary ROM, attached slot ROM, battery data, " +
                "and a state checkpoint for its linked player. Traffic is direct TCP: it is not " +
                "encrypted or peer-authenticated.$hostText Use a trusted LAN or a separately " +
                "secured tunnel.",
            6,
        )
    notice.accessibleContext.accessibleName = "Netplay data and privacy notice"
    panel.add(title, BorderLayout.NORTH)
    panel.add(notice, BorderLayout.CENTER)
    return panel
  }

  private fun createSessionCard(): JPanel {
    val card = JPanel(BorderLayout(0, 12))
    sessionDetails.accessibleContext.accessibleName = "Netplay session details"
    sessionStatus.accessibleContext.accessibleName = "Netplay session status"
    val center = JPanel(BorderLayout(0, 10))
    center.add(sessionDetails, BorderLayout.NORTH)
    center.add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
    val lower = JPanel(BorderLayout(0, 10))
    lower.add(sessionStatus, BorderLayout.NORTH)
    lower.add(createPrivacyNotice(hosting = false), BorderLayout.CENTER)
    center.add(lower, BorderLayout.SOUTH)
    card.add(center, BorderLayout.CENTER)

    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0))
    editConnection.accessibleContext.accessibleDescription =
        "Return to the Host or Join setup without starting a connection."
    editConnection.addActionListener { actions.editConnection() }
    sessionAction.addActionListener {
      when (presentation.sessionAction) {
        NetplaySessionAction.CANCEL,
        NetplaySessionAction.STOP_HOSTING,
        NetplaySessionAction.DISCONNECT -> actions.stopSession()
        NetplaySessionAction.TRY_AGAIN -> actions.retry()
        NetplaySessionAction.NONE -> Unit
      }
    }
    buttons.add(editConnection)
    buttons.add(sessionAction)
    card.add(buttons, BorderLayout.SOUTH)
    return card
  }

  private fun configureSessionAction(action: NetplaySessionAction) {
    sessionAction.text =
        when (action) {
          NetplaySessionAction.NONE -> ""
          NetplaySessionAction.CANCEL -> "Cancel"
          NetplaySessionAction.STOP_HOSTING -> "Stop hosting"
          NetplaySessionAction.DISCONNECT -> "Disconnect"
          NetplaySessionAction.TRY_AGAIN -> "Try again"
        }
    sessionAction.isVisible = action != NetplaySessionAction.NONE
    sessionAction.isEnabled = action != NetplaySessionAction.NONE
    sessionAction.accessibleContext.accessibleName = sessionAction.text.ifEmpty { "No session action" }
    sessionAction.accessibleContext.accessibleDescription =
        when (action) {
          NetplaySessionAction.CANCEL -> "Cancel this protocol-v8 netplay attempt."
          NetplaySessionAction.STOP_HOSTING -> "Stop the current protocol-v8 server."
          NetplaySessionAction.DISCONNECT -> "Disconnect from the current protocol-v8 server."
          NetplaySessionAction.TRY_AGAIN -> "Retry the failed protocol-v8 netplay attempt."
          NetplaySessionAction.NONE -> "No session action is currently available."
        }
  }

  private fun updateAddressValidation() {
    val validation = validateNetplayV8Address(address.text)
    addressValidation.text =
        when (validation) {
          is NetplayAddressValidation.Valid ->
              if (validation.endpoint.port == NETPLAY_V8_PORT) "Ready to use TCP port 6688."
              else "Ready to use TCP port ${validation.endpoint.port}."
          is NetplayAddressValidation.Invalid -> validation.message
        }
    addressValidation.foreground =
        if (validation is NetplayAddressValidation.Valid) {
          themeTokens.success
        } else {
          themeTokens.danger
        }
    joinGame.isEnabled = presentation.canStart && validation is NetplayAddressValidation.Valid
  }

  private fun addFormRow(panel: JPanel, row: Int, labelText: String, value: Component) {
    val label = JLabel(labelText)
    label.labelFor = value
    panel.add(label, formLabelConstraints(row))
    panel.add(value, formValueConstraints(row))
  }

  private fun formLabelConstraints(row: Int): GridBagConstraints =
      GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = Insets(5, 0, 5, 12)
      }

  private fun formValueConstraints(row: Int): GridBagConstraints =
      GridBagConstraints().apply {
        gridx = 1
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = Insets(5, 0, 5, 0)
      }

  private companion object {
    const val SETUP_CARD = "setup"
    const val SESSION_CARD = "session"
  }
}

private fun refreshLiteralTextStyles(root: Component, font: Font, foreground: Color) {
  if (root is JTextArea) {
    root.font = font
    root.foreground = foreground
  }
  if (root is Container) {
    root.components.forEach { refreshLiteralTextStyles(it, font, foreground) }
  }
}

private fun literalTextArea(text: String, rows: Int): JTextArea =
    JTextArea(text, rows, 1).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      border = null
      isFocusable = false
      font = UIManager.getFont("Label.font") ?: font
      foreground = UIManager.getColor("Label.foreground") ?: foreground
    }

private fun LinkMode.displayName(): String =
    when (this) {
      LinkMode.NORMAL -> "2-player link"
      LinkMode.FOUR_PLAYER_ADAPTER -> "4-player adapter"
    }

private fun literalNetworkMessage(message: String): String {
  val literal =
      buildString(message.length.coerceAtMost(MAX_NETWORK_MESSAGE_CHARS)) {
        message.forEach { character ->
          if (length >= MAX_NETWORK_MESSAGE_CHARS) return@forEach
          append(if (character.isISOControl()) ' ' else character)
        }
      }
          .trim()
          .replace(Regex("\\s+"), " ")
  return literal.ifEmpty { "The peer reported a netplay error." }
}

private fun requireNetplayEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the Event Dispatch Thread" }
}

private const val MAX_NETPLAY_ADDRESS_CHARS = 260
private const val MAX_NETPLAY_HOST_CHARS = 253
private const val MAX_NETWORK_MESSAGE_CHARS = 320
