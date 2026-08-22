package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientConnectionRejectedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientHandshakeCompletedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerCountEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartFailedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStoppedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.awt.Component
import java.awt.Container
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class NetplayWindowTest {

  @Test
  fun `v8 address validation matches the current hostname IPv4 and optional port forms`() {
    val hostname = assertIs<NetplayAddressValidation.Valid>(validateNetplayV8Address("play.local"))
    assertEquals("play.local", hostname.endpoint.host)
    assertEquals(6688, hostname.endpoint.port)
    assertEquals("play.local", hostname.endpoint.startClientValue)

    val ipv4 =
        assertIs<NetplayAddressValidation.Valid>(validateNetplayV8Address(" 192.0.2.20:7000 "))
    assertEquals("192.0.2.20", ipv4.endpoint.host)
    assertEquals(7000, ipv4.endpoint.port)
    assertEquals("192.0.2.20:7000", ipv4.endpoint.startClientValue)
    assertFalse(ipv4.endpoint.toString().contains("192.0.2.20"))

    listOf(
            "",
            "play local",
            ":6688",
            "play.local:",
            "play.local:http",
            "play.local:0",
            "play.local:65536",
            "2001:db8::1",
            "[2001:db8::1]:6688",
        )
        .forEach { address ->
          assertIs<NetplayAddressValidation.Invalid>(
              validateNetplayV8Address(address),
              "expected '$address' to be rejected",
          )
        }
  }

  @Test
  fun `immutable presentation exposes only truthful actions for each controller phase`() {
    val availability =
        NetplayAvailability.Available("Tetris", "dmg", "Game Boy (DMG)")
    val disconnected = NetplayUiState(availability = availability)
    assertTrue(presentNetplay(disconnected).canStart)
    assertEquals(NetplaySessionAction.NONE, presentNetplay(disconnected).sessionAction)

    val connecting =
        disconnected.copy(
            phase = NetplayPhase.CONNECTING,
            role = NetplayRole.CLIENT,
            endpoint = validEndpoint("play.local"),
        )
    assertEquals(NetplaySessionAction.CANCEL, presentNetplay(connecting).sessionAction)

    val negotiating = connecting.copy(phase = NetplayPhase.NEGOTIATING, localPlayer = 1)
    assertEquals(NetplaySessionAction.CANCEL, presentNetplay(negotiating).sessionAction)

    val activeClient = negotiating.copy(phase = NetplayPhase.ACTIVE, mode = LinkMode.NORMAL)
    assertEquals(NetplaySessionAction.DISCONNECT, presentNetplay(activeClient).sessionAction)

    val waitingHost =
        disconnected.copy(
            phase = NetplayPhase.WAITING_FOR_PEERS,
            role = NetplayRole.HOST,
            mode = LinkMode.FOUR_PLAYER_ADAPTER,
            requiredPeers = 3,
            localPlayer = 0,
        )
    assertEquals(NetplaySessionAction.STOP_HOSTING, presentNetplay(waitingHost).sessionAction)

    val failed =
        connecting.copy(
            phase = NetplayPhase.FAILED,
            failure = NetplayFailure(NetplayFailureKind.REJECTED, "Session rejected."),
        )
    assertEquals(NetplaySessionAction.TRY_AGAIN, presentNetplay(failed).sessionAction)
    assertEquals("Session rejected.", presentNetplay(failed).status)
  }

  @Test
  fun `host lifecycle is retained and uses fixed-port v8 commands with explicit stop`() {
    val bus = EventBusImpl()
    val serverStarts = mutableListOf<StartServerEvent>()
    val serverStops = mutableListOf<StopServerEvent>()
    val peripheralSelections = mutableListOf<Controller.SerialPeripheralSelection>()
    bus.register<StartServerEvent>(serverStarts::add)
    bus.register<StopServerEvent>(serverStops::add)
    bus.register<Controller.SetSerialPeripheralEvent> { peripheralSelections += it.selection }
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      flushEdt()
      assertTrue(onEdt { fixture.host.currentPresentation().canStart })

      onEdt {
        fixture.host.show()
        fixture.host.show()
      }
      assertEquals(1, fixture.factoryCalls)
      assertEquals(2, fixture.view.showCalls)

      onEdt { fixture.view.actions.startHosting(LinkMode.FOUR_PLAYER_ADAPTER, 0) }
      assertEquals(listOf(LinkMode.FOUR_PLAYER_ADAPTER), serverStarts.map { it.mode })
      val attemptId = serverStarts.single().attemptId
      assertTrue(attemptId > 0)
      assertEquals(
          listOf(Controller.SerialPeripheralSelection.PEER_TO_PEER),
          peripheralSelections,
      )
      assertEquals(NetplayPhase.STARTING_HOST, onEdt { fixture.host.currentPresentation().state.phase })
      assertEquals(
          NetplaySessionAction.CANCEL,
          onEdt { fixture.host.currentPresentation().sessionAction },
      )

      bus.post(ServerStartedEvent(LinkMode.FOUR_PLAYER_ADAPTER, attemptId))
      bus.post(ServerPlayerCountEvent(2, 3, LinkMode.FOUR_PLAYER_ADAPTER, attemptId))
      flushEdt()
      val waiting = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.WAITING_FOR_PEERS, waiting.state.phase)
      assertEquals(2, waiting.state.connectedPeers)
      assertEquals(NetplaySessionAction.STOP_HOSTING, waiting.sessionAction)
      assertTrue(waiting.status.contains("all local interfaces"))

      bus.post(ServerGotConnectionEvent("localhost", LinkMode.FOUR_PLAYER_ADAPTER, 0, attemptId))
      flushEdt()
      assertEquals(NetplayPhase.ACTIVE, onEdt { fixture.host.currentPresentation().state.phase })
      assertEquals(1, fixture.view.hideCalls)

      onEdt { fixture.view.actions.stopSession() }
      assertEquals(listOf(attemptId), serverStops.map { it.attemptId })
      assertEquals(NetplayPhase.STOPPING, onEdt { fixture.host.currentPresentation().state.phase })
      bus.post(ServerStoppedEvent(attemptId))
      flushEdt()
      val stopped = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.DISCONNECTED, stopped.state.phase)
      assertEquals(NetplaySetupView.HOST, stopped.state.setupView)
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `client lifecycle validates inline retains literal failure and disconnects only when active`() {
    val bus = EventBusImpl()
    val clientStarts = mutableListOf<StartClientEvent>()
    val clientStops = mutableListOf<StopClientEvent>()
    bus.register<StartClientEvent>(clientStarts::add)
    bus.register<StopClientEvent>(clientStops::add)
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      flushEdt()
      onEdt {
        fixture.host.show()
        fixture.view.actions.selectSetup(NetplaySetupView.JOIN)
        fixture.view.actions.join("invalid address")
      }
      assertTrue(clientStarts.isEmpty())
      assertEquals(NetplayPhase.DISCONNECTED, onEdt { fixture.host.currentPresentation().state.phase })

      onEdt { fixture.view.actions.join("gameserver.local:7000") }
      assertEquals(listOf("gameserver.local:7000"), clientStarts.map { it.host })
      val firstAttemptId = clientStarts.single().attemptId
      assertTrue(firstAttemptId > 0)
      var presentation = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.CONNECTING, presentation.state.phase)
      assertEquals(NetplaySessionAction.CANCEL, presentation.sessionAction)

      bus.post(ClientHandshakeCompletedEvent(LinkMode.NORMAL, 1, firstAttemptId))
      flushEdt()
      presentation = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.NEGOTIATING, presentation.state.phase)
      assertEquals(NetplaySessionAction.CANCEL, presentation.sessionAction)

      bus.post(ClientConnectedToServerEvent(LinkMode.NORMAL, 1, firstAttemptId))
      flushEdt()
      presentation = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.ACTIVE, presentation.state.phase)
      assertEquals(NetplaySessionAction.DISCONNECT, presentation.sessionAction)
      assertEquals(1, fixture.view.hideCalls)

      onEdt { fixture.host.show() }
      assertEquals(1, fixture.view.hideCalls, "reopening an active session keeps the window open")

      onEdt { fixture.view.actions.stopSession() }
      assertEquals(listOf(firstAttemptId), clientStops.map { it.attemptId })
      assertEquals(
          NetplayPhase.DISCONNECTED,
          onEdt { fixture.host.currentPresentation().state.phase },
          "disconnecting a client whose transport already stopped must not leave the UI stopping",
      )
      bus.post(ClientDisconnectedFromServerEvent(firstAttemptId))
      flushEdt()
      assertEquals(NetplayPhase.DISCONNECTED, onEdt { fixture.host.currentPresentation().state.phase })

      onEdt { fixture.view.actions.join("gameserver.local") }
      val secondAttemptId = clientStarts.last().attemptId
      bus.post(
          ClientConnectionRejectedEvent(
              "<html><b>Peer rejected this session</b></html>",
              secondAttemptId,
          ))
      bus.post(ClientDisconnectedFromServerEvent(secondAttemptId))
      flushEdt()
      presentation = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.FAILED, presentation.state.phase)
      assertEquals(NetplayFailureKind.REJECTED, presentation.state.failure?.kind)
      assertEquals(
          "<html><b>Peer rejected this session</b></html>",
          presentation.status,
          "remote text remains literal rather than becoming Swing label markup",
      )

      onEdt { fixture.view.actions.editConnection() }
      assertEquals(NetplayPhase.DISCONNECTED, onEdt { fixture.host.currentPresentation().state.phase })
      assertEquals(NetplaySetupView.JOIN, onEdt { fixture.host.currentPresentation().state.setupView })
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `initial CLI join waits for the loaded ROM and starts the client once`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartClientEvent>()
    bus.register<StartClientEvent>(starts::add)
    val fixture = onEdt { HostFixture(bus, initialJoinEndpoint = validEndpoint("localhost")) }

    try {
      publishAvailableGame(bus)
      flushEdt()

      assertEquals(listOf("localhost"), starts.map { it.host })
      assertEquals(NetplayPhase.CONNECTING, onEdt { fixture.host.currentPresentation().state.phase })

      bus.post(Controller.EmulationStartedEvent("Tetris"))
      flushEdt()
      assertEquals(1, starts.size)
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `host launches requested local clients after the server is listening`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartServerEvent>()
    val launches = mutableListOf<LocalLaunch>()
    bus.register<StartServerEvent>(starts::add)
    val fixture =
        onEdt {
          HostFixture(
              bus,
              localRomPath = { Path.of("Tetris.gb") },
              localInstanceLauncher =
                  LocalNetplayInstanceLauncher { rom, profile, endpoint, count ->
                    launches += LocalLaunch(rom, profile.id(), endpoint.startClientValue, count)
                    LocalNetplayInstanceLaunchResult(count, count, launcherAvailable = true)
                  },
          )
        }

    try {
      publishAvailableGame(bus)
      flushEdt()
      onEdt { fixture.host.show() }
      onEdt { fixture.view.actions.startHosting(LinkMode.FOUR_PLAYER_ADAPTER, 3) }
      val attemptId = starts.single().attemptId

      assertTrue(launches.isEmpty())
      bus.post(ServerStartedEvent(LinkMode.FOUR_PLAYER_ADAPTER, attemptId))
      flushEdt()

      assertEquals(
          listOf(LocalLaunch(Path.of("Tetris.gb"), "dmg", "localhost", 3)),
          launches,
      )
      assertTrue(onEdt { fixture.host.currentPresentation().status.contains("Started 3 local") })
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `profile preflight rejects MGB and closing the retained host never disconnects`() {
    val bus = EventBusImpl()
    val stopRequests = AtomicInteger()
    bus.register<StopServerEvent> { stopRequests.incrementAndGet() }
    bus.register<StopClientEvent> { stopRequests.incrementAndGet() }
    val fixture = onEdt { HostFixture(bus) }

    try {
      bus.post(Controller.HardwareProfileEvent(HardwareProfileRegistry.MGB))
      bus.post(Controller.EmulationStartedEvent("Kirby's Dream Land"))
      flushEdt()
      val presentation = onEdt { fixture.host.currentPresentation() }
      assertFalse(presentation.canStart)
      assertIs<NetplayAvailability.IncompatibleProfile>(presentation.state.availability)
      assertTrue(presentation.availabilityText.contains("StateFile v2 identity"))

      onEdt {
        fixture.host.show()
        fixture.host.close()
        fixture.host.close()
      }
      assertEquals(0, stopRequests.get())
      assertEquals(1, fixture.view.closeCalls)
    } finally {
      bus.close()
    }
  }

  @Test
  fun `host start failure becomes retryable and restores the previous serial peripheral`() {
    val bus = EventBusImpl()
    val selections = mutableListOf<Controller.SerialPeripheralSelection>()
    val starts = mutableListOf<StartServerEvent>()
    bus.register<Controller.SetSerialPeripheralEvent> { selections += it.selection }
    bus.register<StartServerEvent>(starts::add)
    val confirmed = mutableListOf<Controller.SerialPeripheralSelection>()
    val fixture =
        onEdt {
          HostFixture(bus, confirmPeripheralHandoff = { selection -> confirmed += selection; true })
        }

    try {
      publishAvailableGame(bus)
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PRINTER))
      flushEdt()

      onEdt { fixture.host.show() }
      onEdt { fixture.view.actions.startHosting(LinkMode.NORMAL, 0) }
      assertEquals(listOf(Controller.SerialPeripheralSelection.PRINTER), confirmed)
      assertEquals(Controller.SerialPeripheralSelection.PEER_TO_PEER, selections.last())
      assertTrue(starts.isEmpty(), "network startup waits for the serial ownership commit")

      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PEER_TO_PEER))
      flushEdt()
      val attemptId = starts.single().attemptId

      bus.post(
          ServerStartFailedEvent(
              eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartFailure
                  .PORT_UNAVAILABLE,
              6688,
              attemptId,
          ))
      flushEdt()

      val failed = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.FAILED, failed.state.phase)
      assertEquals(NetplaySessionAction.TRY_AGAIN, failed.sessionAction)
      assertTrue(failed.status.contains("TCP port 6688"))
      assertEquals(Controller.SerialPeripheralSelection.PRINTER, selections.last())

      bus.post(
          Controller.SerialPeripheralStatusEvent(
              Controller.SerialPeripheralSelection.PRINTER,
              Controller.SerialPeripheralStatus.UNAVAILABLE,
              Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE,
          ))
      flushEdt()
      val restoreFailed = onEdt { fixture.host.currentPresentation() }
      assertTrue(restoreFailed.status.contains("TCP port 6688"))
      assertTrue(restoreFailed.status.contains("could not restore"))
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `join waits for peer to peer ownership and reports an attach failure without connecting`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartClientEvent>()
    val selections = mutableListOf<Controller.SerialPeripheralSelection>()
    bus.register<StartClientEvent>(starts::add)
    bus.register<Controller.SetSerialPeripheralEvent> { selections += it.selection }
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.BARCODE_BOY))
      flushEdt()

      onEdt { fixture.host.show() }
      onEdt { fixture.view.actions.join("gameserver.local") }
      assertTrue(starts.isEmpty())
      assertEquals(
          listOf(Controller.SerialPeripheralSelection.PEER_TO_PEER),
          selections,
      )

      bus.post(
          Controller.SerialPeripheralStatusEvent(
              Controller.SerialPeripheralSelection.PEER_TO_PEER,
              Controller.SerialPeripheralStatus.UNAVAILABLE,
              Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE,
          ))
      flushEdt()

      val failed = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.FAILED, failed.state.phase)
      assertEquals(NetplayFailureKind.LINK_PORT, failed.state.failure?.kind)
      assertTrue(failed.status.contains("could not reserve the link port"))
      assertTrue(starts.isEmpty())
      assertEquals(
          listOf(Controller.SerialPeripheralSelection.PEER_TO_PEER),
          selections,
          "a failed prepare leaves the old owner intact, so no restore command is needed",
      )
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `a stale terminal event cannot overwrite a newer host retry`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartServerEvent>()
    bus.register<StartServerEvent>(starts::add)
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      flushEdt()

      onEdt { fixture.host.show() }
      onEdt { fixture.view.actions.startHosting(LinkMode.NORMAL, 0) }
      val firstAttemptId = starts.single().attemptId
      bus.post(
          ServerStartFailedEvent(
              eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartFailure
                  .PORT_UNAVAILABLE,
              6688,
              firstAttemptId,
          ))
      flushEdt()
      assertEquals(NetplayPhase.FAILED, onEdt { fixture.host.currentPresentation().state.phase })

      onEdt { fixture.view.actions.retry() }
      val secondAttemptId = starts.last().attemptId
      assertTrue(secondAttemptId > firstAttemptId)
      assertEquals(NetplayPhase.STARTING_HOST, onEdt { fixture.host.currentPresentation().state.phase })

      bus.post(ServerStoppedEvent(firstAttemptId))
      flushEdt()
      assertEquals(
          NetplayPhase.STARTING_HOST,
          onEdt { fixture.host.currentPresentation().state.phase },
      )

      bus.post(ServerStartedEvent(LinkMode.NORMAL, secondAttemptId))
      flushEdt()
      assertEquals(
          NetplayPhase.WAITING_FOR_PEERS,
          onEdt { fixture.host.currentPresentation().state.phase },
      )
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `cancel during serial handoff stops the attempt and queues the displaced peripheral restore`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartServerEvent>()
    val stops = mutableListOf<StopServerEvent>()
    val selections = mutableListOf<Controller.SerialPeripheralSelection>()
    bus.register<StartServerEvent>(starts::add)
    bus.register<StopServerEvent>(stops::add)
    bus.register<Controller.SetSerialPeripheralEvent> { selections += it.selection }
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PRINTER))
      flushEdt()
      onEdt { fixture.host.show() }

      onEdt { fixture.view.actions.startHosting(LinkMode.NORMAL, 0) }
      assertTrue(starts.isEmpty())
      assertEquals(NetplaySessionAction.CANCEL, onEdt { fixture.host.currentPresentation().sessionAction })

      onEdt { fixture.view.actions.stopSession() }
      val canceledAttemptId = stops.single().attemptId
      assertTrue(canceledAttemptId > 0)
      assertEquals(
          listOf(
              Controller.SerialPeripheralSelection.PEER_TO_PEER,
              Controller.SerialPeripheralSelection.PRINTER,
          ),
          selections,
          "the restore must follow the already queued peer-to-peer request",
      )
      val canceled = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.DISCONNECTED, canceled.state.phase)
      assertEquals(NetplaySetupView.HOST, canceled.state.setupView)
      assertEquals("Hosting was canceled before the session started.", canceled.status)

      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PEER_TO_PEER))
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PRINTER))
      bus.post(ServerStoppedEvent(canceledAttemptId))
      flushEdt()
      assertEquals(NetplayPhase.DISCONNECTED, onEdt { fixture.host.currentPresentation().state.phase })
      assertTrue(starts.isEmpty())
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `cancel keeps a launched attempt stopping until its correlated terminal event`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartServerEvent>()
    val stops = mutableListOf<StopServerEvent>()
    val selections = mutableListOf<Controller.SerialPeripheralSelection>()
    bus.register<StartServerEvent>(starts::add)
    bus.register<StopServerEvent>(stops::add)
    bus.register<Controller.SetSerialPeripheralEvent> { selections += it.selection }
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PRINTER))
      flushEdt()
      onEdt { fixture.host.show() }

      onEdt { fixture.view.actions.startHosting(LinkMode.NORMAL, 0) }
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.PEER_TO_PEER))
      flushEdt()
      val attemptId = starts.single().attemptId
      onEdt { fixture.view.actions.stopSession() }
      assertEquals(listOf(attemptId), stops.map { it.attemptId })
      assertEquals(NetplayPhase.STOPPING, onEdt { fixture.host.currentPresentation().state.phase })
      assertEquals(
          listOf(Controller.SerialPeripheralSelection.PEER_TO_PEER),
          selections,
          "the displaced device must not be restored before the worker terminates",
      )

      bus.post(ServerStartedEvent(LinkMode.NORMAL, attemptId))
      bus.post(ServerStoppedEvent(attemptId + 100))
      flushEdt()
      assertEquals(NetplayPhase.STOPPING, onEdt { fixture.host.currentPresentation().state.phase })

      bus.post(ServerStoppedEvent(attemptId))
      flushEdt()
      val canceled = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.DISCONNECTED, canceled.state.phase)
      assertEquals("Hosting was canceled before the session started.", canceled.status)
      assertEquals(Controller.SerialPeripheralSelection.PRINTER, selections.last())
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `cancel while negotiating ignores late readiness and returns to Join setup`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartClientEvent>()
    val stops = mutableListOf<StopClientEvent>()
    bus.register<StartClientEvent>(starts::add)
    bus.register<StopClientEvent>(stops::add)
    val fixture = onEdt { HostFixture(bus) }

    try {
      publishAvailableGame(bus)
      flushEdt()
      onEdt { fixture.host.show() }

      onEdt { fixture.view.actions.join("gameserver.local") }
      val attemptId = starts.single().attemptId
      bus.post(ClientHandshakeCompletedEvent(LinkMode.NORMAL, 1, attemptId))
      flushEdt()
      assertEquals(NetplaySessionAction.CANCEL, onEdt { fixture.host.currentPresentation().sessionAction })

      onEdt { fixture.view.actions.stopSession() }
      assertEquals(listOf(attemptId), stops.map { it.attemptId })
      bus.post(ClientConnectedToServerEvent(LinkMode.NORMAL, 1, attemptId))
      flushEdt()
      assertEquals(NetplayPhase.STOPPING, onEdt { fixture.host.currentPresentation().state.phase })

      bus.post(ClientDisconnectedFromServerEvent(attemptId))
      flushEdt()
      val canceled = onEdt { fixture.host.currentPresentation() }
      assertEquals(NetplayPhase.DISCONNECTED, canceled.state.phase)
      assertEquals(NetplaySetupView.JOIN, canceled.state.setupView)
      assertEquals("The connection attempt was canceled.", canceled.status)
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `Cancel with the live controller returns to Join before its action returns`() {
    val listener = ServerSocket(0)
    val accepted = CompletableFuture<Socket>()
    Thread(
            { accepted.complete(listener.accept()) },
            "netplay-window-cancel-peer",
        )
        .apply {
          isDaemon = true
          start()
        }
    val bus = EventBusImpl()
    ConnectionController(bus)
    val fixture = onEdt { HostFixture(bus) }
    var peer: Socket? = null
    try {
      publishAvailableGame(bus)
      flushEdt()
      onEdt {
        fixture.host.show()
        fixture.view.actions.join("127.0.0.1:${listener.localPort}")
      }
      peer = accepted.get(1, TimeUnit.SECONDS)

      val canceled =
          onEdt {
            assertEquals(
                NetplaySessionAction.CANCEL,
                fixture.host.currentPresentation().sessionAction,
            )
            fixture.view.actions.stopSession()
            fixture.host.currentPresentation()
          }

      assertEquals(NetplayPhase.DISCONNECTED, canceled.state.phase)
      assertEquals(NetplaySetupView.JOIN, canceled.state.setupView)
      assertEquals("The connection attempt was canceled.", canceled.status)
    } finally {
      peer?.close()
      listener.close()
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `cancelled serial peripheral handoff leaves setup disconnected`() {
    val bus = EventBusImpl()
    val starts = mutableListOf<StartServerEvent>()
    bus.register<StartServerEvent>(starts::add)
    val fixture = onEdt { HostFixture(bus, confirmPeripheralHandoff = { false }) }
    try {
      publishAvailableGame(bus)
      bus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              Controller.SerialPeripheralSelection.BARCODE_BOY))
      flushEdt()

      onEdt { fixture.host.show() }
      onEdt { fixture.view.actions.startHosting(LinkMode.NORMAL, 0) }

      assertTrue(starts.isEmpty())
      assertEquals(NetplayPhase.DISCONNECTED, onEdt { fixture.host.currentPresentation().state.phase })
    } finally {
      onEdt { fixture.host.close() }
      bus.close()
    }
  }

  @Test
  fun `Host and Join cards expose fixed port privacy text and accessible literal controls`() {
    onEdt {
        val panel = NetplayPanel(noOpActions())
        panel.render(
            presentNetplay(
                NetplayUiState(
                    availability =
                        NetplayAvailability.Available(
                            "Pokemon Crystal",
                            "cgb",
                            "Game Boy Color (CGB)",
                        ))))
        val components = descendants(panel)
        val texts =
            components.mapNotNull { component ->
              when (component) {
                is JLabel -> component.text
                is AbstractButton -> component.text
                is JTextArea -> component.text
                else -> null
              }
            }

        assertTrue(texts.any { it.contains("6688 (fixed by protocol v8)") })
        assertTrue(texts.any { it.contains("primary ROM") && it.contains("battery data") })
        assertTrue(texts.any { it.contains("not encrypted or peer-authenticated") })
        assertTrue(texts.any { it.contains("trusted LAN") && it.contains("secured tunnel") })
        assertTrue(texts.none { it.startsWith("<html>", ignoreCase = true) })

        val address =
            components.filterIsInstance<JTextField>().single {
              it.accessibleContext.accessibleName == "Netplay server address"
            }
        assertNotNull(address.accessibleContext.accessibleDescription)
        assertTrue(
            components.filterIsInstance<JTextArea>().count {
              it.accessibleContext.accessibleName == "Netplay data and privacy notice"
            } >= 2)
        assertNotNull(
            components.filterIsInstance<AbstractButton>().single { it.text == "Start hosting" }
                .accessibleContext.accessibleDescription)
        assertNotNull(
            components.filterIsInstance<AbstractButton>().single { it.text == "Join game" }
                .accessibleContext.accessibleDescription)

        panel.render(
            presentNetplay(
                NetplayUiState(
                    availability =
                        NetplayAvailability.Available("Pokemon Crystal", "cgb", "Game Boy Color"),
                    setupView = NetplaySetupView.JOIN,
                    phase = NetplayPhase.CONNECTING,
                    role = NetplayRole.CLIENT,
                    endpoint = validEndpoint("gameserver.local"),
                )))
        val cancel = components.filterIsInstance<AbstractButton>().single { it.text == "Cancel" }
        assertTrue(cancel.isVisible)
        assertTrue(cancel.isEnabled)
        assertTrue(cancel.accessibleContext.accessibleDescription.contains("Cancel"))
      }
  }

  @Test
  fun `host card limits local clients for normal link and exposes one through three for adapter`() {
    onEdt {
      val starts = mutableListOf<Pair<LinkMode, Int>>()
      val panel =
          NetplayPanel(
              NetplayWindowActions(
                  { _ -> },
                  { mode, count -> starts += mode to count },
                  { _ -> },
                  {},
                  {},
                  {},
              ))
      panel.render(
          presentNetplay(
              NetplayUiState(
                  availability = NetplayAvailability.Available("Tetris", "dmg", "Game Boy"))))
      val components = descendants(panel)
      val checkbox =
          components.filterIsInstance<JCheckBox>().single {
            it.accessibleContext.accessibleName == "Start local Coffee GB instances"
          }
      val count =
          components.filterIsInstance<JComboBox<*>>().single {
            it.accessibleContext.accessibleName == "Number of local Coffee GB instances"
          }
      val normal = components.filterIsInstance<JRadioButton>().single { it.text == "2-player link" }
      val adapter = components.filterIsInstance<JRadioButton>().single { it.text == "4-player adapter" }
      val start = components.filterIsInstance<AbstractButton>().single { it.text == "Start hosting" }

      assertEquals(listOf(1), (0 until count.itemCount).map(count::getItemAt))
      assertFalse(count.isEnabled)
      checkbox.doClick()
      assertTrue(count.isEnabled)
      adapter.doClick()
      assertEquals(listOf(1, 2, 3), (0 until count.itemCount).map(count::getItemAt))
      count.selectedItem = 3
      start.doClick()
      assertEquals(listOf(LinkMode.FOUR_PLAYER_ADAPTER to 3), starts)

      normal.doClick()
      assertEquals(listOf(1), (0 until count.itemCount).map(count::getItemAt))
    }
  }

  private class HostFixture(
      bus: EventBusImpl,
      confirmPeripheralHandoff: (Controller.SerialPeripheralSelection) -> Boolean = { true },
      initialJoinEndpoint: NetplayV8Endpoint? = null,
      localRomPath: () -> Path? = { null },
      localInstanceLauncher: LocalNetplayInstanceLauncher =
          LocalNetplayInstanceLauncher { _, _, _, count ->
            LocalNetplayInstanceLaunchResult(count, count, launcherAvailable = true)
          },
  ) {
    lateinit var view: RecordingNetplayView
    var factoryCalls = 0
    val host =
        NetplayWindowHost(
            bus,
            NetplayWindowViewFactory { actions ->
              factoryCalls++
              RecordingNetplayView(actions).also { view = it }
            },
            initialJoinEndpoint = initialJoinEndpoint,
            localRomPath = localRomPath,
            localInstanceLauncher = localInstanceLauncher,
            confirmPeripheralHandoff = confirmPeripheralHandoff,
        )
  }

  private data class LocalLaunch(
      val rom: Path,
      val profile: String,
      val endpoint: String,
      val count: Int,
  )

  private class RecordingNetplayView(val actions: NetplayWindowActions) : NetplayWindowView {
    val presentations = mutableListOf<NetplayUiPresentation>()
    var showCalls = 0
    var hideCalls = 0
    var closeCalls = 0

    override fun render(presentation: NetplayUiPresentation) {
      assertTrue(SwingUtilities.isEventDispatchThread())
      presentations += presentation
    }

    override fun showOrRaise() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      showCalls++
    }

    override fun hide() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      hideCalls++
    }

    override fun close() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      closeCalls++
    }
  }

  private fun publishAvailableGame(bus: EventBusImpl) {
    bus.post(Controller.HardwareProfileEvent(HardwareProfileRegistry.DMG))
    bus.post(Controller.EmulationStartedEvent("Tetris"))
  }

  private fun validEndpoint(value: String): NetplayV8Endpoint =
      assertIs<NetplayAddressValidation.Valid>(validateNetplayV8Address(value)).endpoint

  private fun noOpActions(): NetplayWindowActions =
      NetplayWindowActions({ _ -> }, { _, _ -> }, { _ -> }, {}, {}, {})

  private fun descendants(component: Component): List<Component> =
      buildList {
        add(component)
        if (component is Container) {
          component.components.forEach { addAll(descendants(it)) }
        }
      }

  private fun flushEdt() {
    onEdt { Unit }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
