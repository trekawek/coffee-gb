package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.StateHistory.GameboyJoypadPressEvent
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.network.Connection.PeerEventSource
import eu.rekawek.coffeegb.controller.network.Connection.PeerEventSourceDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.Connection.ProtocolErrorReason
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteStopEvent
import eu.rekawek.coffeegb.controller.network.Connection.SessionCheckpointEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerButtonStateEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerStateEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerDisconnectedEvent
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import org.junit.Test
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkedControllerTest {

  @Test
  fun fourPlayerHostRunsImmediatelyWithEmptyAdapterPorts() {
    val eventBus = EventBusImpl()
    val sut =
        LinkedController(
            eventBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 0,
        )
    sut.timingTicker.disabled = true

    eventBus.post(LoadRomEvent(ROM))
    repeat(3) { sut.runFrame() }

    assertEquals(1, sut.activeSessionCount())
    assertEquals(3, sut.currentFrame())
    assertEquals(2, sut.stateHistory.getHead().frame)
    eventBus.close()
  }

  @Test
  fun lateFourPlayerClientRestoresCoherentHostCheckpoint() {
    val hostBus = EventBusImpl()
    val host =
        LinkedController(
            hostBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 0,
        )
    host.timingTicker.disabled = true
    val checkpoints = LinkedBlockingQueue<LinkedController.SessionStateReadyEvent>()
    hostBus.register<LinkedController.SessionStateReadyEvent> { checkpoints.add(it) }

    hostBus.post(LoadRomEvent(ROM))
    host.runFrame()
    setControllerFrame(host, StateLimits.NETPLAY_FUTURE_FRAMES + 5)
    checkpoints.clear()

    // Player 2 joins after the host has already advanced. Its existing Game Boy is hot-plugged at
    // the current adapter phase; the host then publishes both complete Session mementos.
    hostBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            player = 1,
        ))
    host.runFrame()
    val checkpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    assertTrue(checkpoint.frame > StateLimits.NETPLAY_FUTURE_FRAMES)
    assertEquals(listOf(0, 1), checkpoint.states.map { it.player })
    assertTrue(checkpoint.states.all { it.sessionSnapshot != null })

    val clientBus = EventBusImpl()
    val client =
        LinkedController(
            clientBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 1,
        )
    client.timingTicker.disabled = true
    clientBus.post(LoadRomEvent(ROM))
    client.runFrame()
    assertEquals(0, client.currentFrame(), "client must wait for the host checkpoint")

    fun deliverCheckpoint(value: LinkedController.SessionStateReadyEvent) {
      clientBus.post(
          SessionCheckpointEvent(
              value.frame,
              value.states.map { state ->
                PeerLoadedGameEvent(
                    rom = state.romFile,
                    battery = state.batteryFile,
                    snapshot = state.snapshot,
                    gameboyType = state.gameboyType,
                    bootstrapMode = state.bootstrapMode,
                    frame = state.frame,
                    cgb0Revision = state.cgb0Revision,
                    player = state.player,
                    sessionSnapshot = state.sessionSnapshot,
                    heldButtons = state.heldButtons,
                )
              },
          ))
    }

    deliverCheckpoint(checkpoint)
    client.runFrame()

    assertEquals(2, client.activeSessionCount())
    assertEquals(host.currentFrame(), client.currentFrame())
    assertEquals(host.stateHistory.getHead().frame, client.stateHistory.getHead().frame)

    // Another physical port can hot-plug while the original client is already running.
    val joinBoundaryPatchFrame = host.currentFrame() - 1
    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            joinBoundaryPatchFrame,
            Input(listOf(Button.A), emptyList()),
            player = 1,
        ))
    hostBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            player = 2,
        ))
    host.runFrame()
    val expandedCheckpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    assertEquals(listOf(0, 1, 2), expandedCheckpoint.states.map { it.player })
    deliverCheckpoint(expandedCheckpoint)
    client.runFrame()
    assertEquals(3, host.activeSessionCount())
    assertEquals(3, client.activeSessionCount())
    assertEquals(host.currentFrame(), client.currentFrame())

    // Old-generation traffic received after the checkpoint must not change either side.
    hostBus.post(ReceivedRemoteResetEvent(expandedCheckpoint.frame - 1, player = 1))
    clientBus.post(ReceivedRemoteResetEvent(expandedCheckpoint.frame - 1, player = 1))
    repeat(3) {
      host.runFrame()
      client.runFrame()
    }
    assertCompleteStateEquals(host, client)

    // Removing Player 3 leaves the other consoles and adapter running from an authoritative
    // checkpoint, and frees that physical slot for a replacement.
    val disconnectBoundaryPatchFrame = host.currentFrame() - 1
    hostBus.post(ReceivedRemoteResetEvent(disconnectBoundaryPatchFrame, player = 1))
    hostBus.post(ServerPlayerDisconnectedEvent(2))
    host.runFrame()
    val disconnectCheckpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    assertEquals(listOf(0, 1), disconnectCheckpoint.states.map { it.player })
    deliverCheckpoint(disconnectCheckpoint)
    client.runFrame()
    assertEquals(2, host.activeSessionCount())
    assertEquals(2, client.activeSessionCount())
    assertEquals(host.currentFrame(), client.currentFrame())
    assertTrue(host.currentFrame() > StateLimits.NETPLAY_FUTURE_FRAMES)

    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            disconnectCheckpoint.frame - 1,
            Input(listOf(Button.START), emptyList()),
            player = 1,
        ))
    clientBus.post(
        LinkedController.RemoteButtonStateEvent(
            disconnectCheckpoint.frame - 1,
            Input(listOf(Button.START), emptyList()),
            player = 1,
        ))
    repeat(3) {
      host.runFrame()
      client.runFrame()
    }
    assertCompleteStateEquals(host, client)

    clientBus.close()
    hostBus.close()
  }

  @Test
  fun checkpointFloorDropsDelayedRuntimeAndEmptyCheckpointCanRestartLater() {
    val eventBus = EventBusImpl()
    val sut =
        LinkedController(
            eventBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 1,
        )
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val source = PeerEventSource(0) { reason, _ -> failures += reason }

    eventBus.post(SessionCheckpointEvent(100, emptyList(), source))
    sut.runFrame()
    assertEquals(0, sut.activeSessionCount())
    assertEquals(101, sut.currentFrame())

    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            99,
            Input(listOf(Button.A), emptyList()),
            player = 0,
            source = source,
        ))
    eventBus.post(ReceivedRemoteResetEvent(99, player = 0, source = source))
    eventBus.post(ReceivedRemoteStopEvent(99, player = 0, source = source))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            99,
            player = 0,
            source = source,
        ))
    sut.runFrame()
    assertTrue(failures.isEmpty(), "old-generation runtime should be discarded, not fatal")
    assertEquals(0, sut.activeSessionCount())
    assertEquals(102, sut.currentFrame())

    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            sut.currentFrame(),
            player = 0,
            source = source,
        ))
    sut.runFrame()
    assertEquals(1, sut.activeSessionCount())
    assertTrue(failures.isEmpty())
    eventBus.close()
  }

  @Test
  fun peerWithEmptyMbc2SaveStartsSession() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true

    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(
            mbc2Rom(),
            ByteArray(0),
            null,
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            0,
        ))

    sut.runFrame()
    assertEquals(2, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun controllerClockRejectsExtremeStateAndInputWithoutStoppingLiveSession() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<Pair<ProtocolErrorReason, IOException>>()
    val source = PeerEventSource(1) { reason, cause -> failures += reason to cause }
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            source = source,
        ))
    sut.runFrame()
    assertEquals(2, sut.activeSessionCount())

    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            Int.MAX_VALUE.toLong(),
            source = source,
        ))
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            Int.MAX_VALUE.toLong(),
            Input(listOf(Button.A), emptyList()),
            source = source,
        ))
    sut.runFrame()

    assertEquals(2, sut.activeSessionCount())
    assertEquals(1, failures.size)
    assertTrue(failures.all { it.first == ProtocolErrorReason.INVALID_FRAME })
    sut.runFrame()
    assertTrue(sut.currentFrame() >= 3, "controller stopped after hostile frames")
    eventBus.close()
  }

  @Test
  fun idleHeartbeatIsBoundedByControllerRatherThanPeerHighWaterMark() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val accepted = mutableListOf<ValidatedPeerButtonStateEvent>()
    eventBus.register<ValidatedPeerButtonStateEvent> { accepted += it }
    val source = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            source = source,
        ))
    sut.runFrame()
    setControllerFrame(sut, 301)

    val idleHeartbeatFrame = sut.currentFrame()
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            idleHeartbeatFrame,
            Input(emptyList(), emptyList()),
            source = source,
        ))
    sut.runFrame()
    assertTrue(failures.isEmpty(), "a healthy post-idle heartbeat was rejected")
    assertEquals(listOf(idleHeartbeatFrame), accepted.map { it.event.frame })

    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            sut.currentFrame() + StateLimits.NETPLAY_FUTURE_FRAMES + 1,
            Input(emptyList(), emptyList()),
            source = source,
        ))
    sut.runFrame()

    assertEquals(listOf(ProtocolErrorReason.INVALID_FRAME), failures)
    assertEquals(listOf(idleHeartbeatFrame), accepted.map { it.event.frame })
    assertEquals(2, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun cumulativeRollbackWorkRejectsOnlyOffendingConnection() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val offender = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    sut.runFrame()
    setControllerFrame(sut, StateLimits.NETPLAY_ROLLBACK_FRAMES)

    eventBus.post(ReceivedRemoteResetEvent(0, player = 1, source = offender))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            player = 1,
            source = offender,
        ))
    eventBus.post(ReceivedRemoteResetEvent(0, player = 1, source = offender))
    sut.runFrame()

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertEquals(StateLimits.NETPLAY_REPLAY_WORK_FRAMES, sut.lastDispatchReplayFrames)
    assertEquals(1, sut.activeSessionCount())

    val replacementFailures = mutableListOf<ProtocolErrorReason>()
    val replacement = PeerEventSource(1) { reason, _ -> replacementFailures += reason }
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            sut.currentFrame(),
            player = 1,
            source = replacement,
        ))
    sut.runFrame()
    assertEquals(2, sut.activeSessionCount())
    assertTrue(replacementFailures.isEmpty())
    eventBus.close()
  }

  @Test
  fun streamingCurrentFrameResetsAreBoundedAcrossDispatches() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val offender = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            source = offender,
        ))
    sut.runFrame()

    eventBus.register<ValidatedPeerResetEvent> {
      eventBus.post(
          ReceivedRemoteResetEvent(sut.currentFrame(), player = 1, source = offender))
    }
    eventBus.post(ReceivedRemoteResetEvent(sut.currentFrame(), player = 1, source = offender))
    repeat(12) {
      if (failures.isEmpty()) sut.runFrame()
    }

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertTrue(sut.currentFrame() >= 5, "bounded dispatches must continue advancing frames")

    val healthyFailures = mutableListOf<ProtocolErrorReason>()
    val healthy = PeerEventSource(1) { reason, _ -> healthyFailures += reason }
    eventBus.post(ReceivedRemoteResetEvent(sut.currentFrame(), player = 1, source = healthy))
    sut.runFrame()
    assertTrue(healthyFailures.isEmpty(), "only the streaming source should be rejected")
    assertEquals(2, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun streamingCurrentFrameRomChangesAreBoundedAcrossDispatches() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val offender = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    sut.runFrame()

    eventBus.register<ValidatedPeerStateEvent> { validated ->
      if (validated.event.source === offender) {
        eventBus.post(peerState(sut.currentFrame(), offender))
      }
    }
    eventBus.post(peerState(sut.currentFrame(), offender))
    repeat(12) {
      if (failures.isEmpty()) sut.runFrame()
    }

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertTrue(sut.currentFrame() >= 5, "bounded dispatches must continue advancing frames")

    val healthyFailures = mutableListOf<ProtocolErrorReason>()
    val healthy = PeerEventSource(1) { reason, _ -> healthyFailures += reason }
    eventBus.post(peerState(sut.currentFrame(), healthy))
    sut.runFrame()
    assertTrue(healthyFailures.isEmpty(), "only the streaming source should be rejected")
    assertEquals(2, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun disconnectDuringStateDispatchDoesNotRaceReplayAccounting() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val source = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(peerState(0, source))
    sut.runFrame()

    val inDispatch = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    eventBus.register<ValidatedPeerStateEvent> { validated ->
      if (validated.event.source === source) {
        inDispatch.countDown()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "disconnect did not overlap dispatch")
      }
    }
    eventBus.post(peerState(sut.currentFrame(), source))
    val dispatch = Thread(sut::runFrame, "state-dispatch-test")
    val disconnect =
        Thread(
            {
              assertTrue(inDispatch.await(5, TimeUnit.SECONDS), "state dispatch did not begin")
              eventBus.post(PeerEventSourceDisconnectedEvent(source))
              disconnected.countDown()
            },
            "disconnect-test",
        )
    dispatch.start()
    disconnect.start()
    dispatch.join(5_000)
    disconnect.join(5_000)

    assertFalse(dispatch.isAlive, "state dispatch deadlocked with disconnect cleanup")
    assertFalse(disconnect.isAlive, "disconnect cleanup deadlocked with state dispatch")
    sut.runFrame()
    assertTrue(failures.isEmpty())
    eventBus.close()
  }

  @Test
  fun fourPlayerControllerRunsAllConsolesAndLabelsLocalAndRemoteInput() {
    val eventBus = EventBusImpl()
    val sut =
        LinkedController(
            eventBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 0,
        )
    sut.timingTicker.disabled = true
    val replayed = mutableListOf<GameboyJoypadPressEvent>()
    sut.stateHistory.debugEventBus =
        EventBusImpl().also { debug -> debug.register<GameboyJoypadPressEvent> { replayed += it } }
    val localInputs = LinkedBlockingQueue<LinkedController.LocalButtonStateEvent>()
    eventBus.register<LinkedController.LocalButtonStateEvent> { localInputs.add(it) }

    eventBus.post(LoadRomEvent(ROM))
    for (player in listOf(1, 2, 3)) {
      eventBus.post(
          PeerLoadedGameEvent(
              ROM_BYTES,
              null,
              null,
              GAMEBOY_TYPE,
              BOOTSTRAP_MODE,
              0,
              player = player,
          ))
    }
    sut.runFrame()
    assertEquals(4, sut.activeSessionCount())
    eventBus.drainAsyncEvents()
    localInputs.clear()

    eventBus.post(ButtonPressEvent(Button.B))
    sut.runFrame()
    eventBus.drainAsyncEvents()
    val local = localInputs.poll(5, TimeUnit.SECONDS)
    assertEquals(0, local.player)
    assertEquals(listOf(Button.B), local.input.pressedButtons)

    eventBus.post(
        LinkedController.RemoteButtonStateEvent(0, Input(listOf(Button.A), emptyList()), 1))
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(0, Input(listOf(Button.START), emptyList()), 3))
    sut.runFrame()

    assertTrue(replayed.any { it.gameboy == 1 && it.button == Button.A })
    assertTrue(replayed.any { it.gameboy == 3 && it.button == Button.START })

    val previousFrame = sut.stateHistory.getHead().frame
    eventBus.post(ReceivedRemoteStopEvent(previousFrame, player = 3))
    sut.runFrame()
    assertEquals(3, sut.activeSessionCount())
    assertTrue(sut.stateHistory.getHead().frame > previousFrame)
    eventBus.close()
  }

  @Test
  fun existingFourPlayerPeerReloadUsesCurrentFrameWhileNewSlotUsesZeroFrame() {
    val eventBus = EventBusImpl()
    val sut =
        LinkedController(
            eventBus,
            EmulatorProperties(),
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 0,
        )
    sut.timingTicker.disabled = true
    val failures = mutableListOf<Pair<Int, ProtocolErrorReason>>()
    val existing = PeerEventSource(1) { reason, _ -> failures += 1 to reason }
    val joining = PeerEventSource(2) { reason, _ -> failures += 2 to reason }
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            player = 1,
            source = existing,
        ))
    sut.runFrame()
    setControllerFrame(sut, StateLimits.NETPLAY_FUTURE_FRAMES + 10)

    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            sut.currentFrame(),
            player = 1,
            source = existing,
        ))
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            player = 2,
            source = joining,
        ))
    sut.runFrame()

    assertTrue(failures.isEmpty())
    assertEquals(3, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun localChangesAreReplayedOnRewind() {
    val eventBus = EventBusImpl()
    val buttons = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus.register<Joypad.JoypadPressEvent> { buttons += it }
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val randomJoypad = RandomJoypad(eventBus)
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0)
    )
    repeat(100) {
      sut.runFrame()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    sut.runFrame()

    val expectedButtons = buttons.toList()
    buttons.clear()

    sut.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 0) {
              buttons += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus.post(LinkedController.RemoteButtonStateEvent(1, Input(listOf(Button.UP), emptyList())))
    repeat(5) {
      eventBus.drainAsyncEvents()
      sut.runFrame()
    }
    eventBus.close()

    val actualButtons = buttons.toList()

    assertJoypadEventsEqual(expectedButtons, actualButtons)
  }

  @Test
  fun heldButtonSurvivesRebasePastThePress() {
    // Issue #79: a button held from before a rebase's base frame must not be dropped. The
    // joypad keeps its held-button set out of the memento (so single-player rewind preserves
    // physical input), and the recorded per-frame Input only carries changes - so unless the
    // rebase restores the held-button set explicitly, a held button vanishes for the whole
    // re-simulation, desyncing the two linked machines.
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0))

    // establish the sessions, then press and HOLD A (never released)
    repeat(5) { sut.runFrame() }
    eventBus.post(ButtonPressEvent(Button.A))
    repeat(11) { sut.runFrame() } // frame ~5 processes the press; A is held through frame ~15
    assertTrue(sut.mainHeldButtons().contains(Button.A), "A should be held before the rebase")

    // a remote patch for a frame well past the press forces a rebase whose base frame is after
    // the press was recorded
    eventBus.post(LinkedController.RemoteButtonStateEvent(10, Input(emptyList(), emptyList())))
    sut.runFrame()

    assertTrue(
        sut.mainHeldButtons().contains(Button.A), "held button A was lost across the rebase")
  }

  @Test
  fun remoteChangesAreSentCorrectly() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    sut1.timingTicker.disabled = true
    val randomJoypad = RandomJoypad(eventBus1)
    eventBus1.post(LoadRomEvent(ROM))
    eventBus1.post(
        PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0)
    )

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    sut2.timingTicker.disabled = true
    eventBus2.post(LoadRomEvent(ROM))
    eventBus2.post(
        PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0)
    )
    sut2.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LinkedController.LocalButtonStateEvent> {
      eventBus2.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }

    repeat(100) {
      sut1.runFrame()
      sut2.runFrame()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    repeat(5) {
      eventBus1.drainAsyncEvents()
      eventBus2.drainAsyncEvents()
      sut1.runFrame()
      sut2.runFrame()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  @Test
  fun twoWayCommunicationProducesSameResults() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    sut1.timingTicker.disabled = true
    val randomJoypad1 = RandomJoypad(eventBus1)
    eventBus1.post(LoadRomEvent(ROM))
    eventBus1.post(
        PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0)
    )

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    sut2.timingTicker.disabled = true
    val randomJoypad2 = RandomJoypad(eventBus2)
    eventBus2.post(LoadRomEvent(ROM))
    eventBus2.post(
        PeerLoadedGameEvent(ROM_BYTES, null, null, GAMEBOY_TYPE, BOOTSTRAP_MODE, 0)
    )
    sut2.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LinkedController.LocalButtonStateEvent> {
      eventBus2.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }
    eventBus2.register<LinkedController.LocalButtonStateEvent> {
      eventBus1.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }

    repeat(100) {
      sut1.runFrame()
      sut2.runFrame()
      randomJoypad1.tick()
      randomJoypad2.tick()
    }
    // input patches travel between the controllers through the async event threads;
    // make sure every in-flight patch is delivered before the flushing frames run, so
    // the final inputs are rebased (and their joypad events observed) on both sides
    repeat(5) {
      eventBus1.drainAsyncEvents()
      eventBus2.drainAsyncEvents()
      sut1.runFrame()
      sut2.runFrame()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val ROM_BYTES = ROM.readBytes()

    // the peer session must be built exactly like the remote main session; the
    // production flow forwards the main config's type and bootstrap mode in
    // LocalRomLoadedEvent (the previous hardcoded DMG+SKIP diverged from the CGB
    // main session built for this universal rom)
    val MAIN_CONFIG =
        Controller.createGameboyConfig(EmulatorProperties(), eu.rekawek.coffeegb.core.memory.cart.Rom(ROM))

    val GAMEBOY_TYPE: GameboyType = MAIN_CONFIG.getGameboyType()

    val BOOTSTRAP_MODE: Gameboy.BootstrapMode = MAIN_CONFIG.getBootstrapMode()

    fun mbc2Rom() =
        ByteArray(0x8000).also {
          it[0x0147] = 0x06
          it[0x0148] = 0x00
        }

    fun assertJoypadEventsEqual(
        expectedButtons: List<Joypad.JoypadPressEvent>,
        actualButtons: List<Joypad.JoypadPressEvent>,
    ) {

      val ticks =
          (expectedButtons.map { it.tick }.toSet() + actualButtons.map { it.tick() }.toSet())
              .toList()
              .sorted()
      for (t in ticks) {
        val exp = expectedButtons.filter { it.tick == t }.map { it.button }.sorted()
        val act = actualButtons.filter { it.tick == t }.map { it.button }.sorted()
        assertEquals(exp, act, "At tick $t, frame ${t/Gameboy.TICKS_PER_FRAME}")
      }
    }

    fun setControllerFrame(controller: LinkedController, frame: Long) {
      LinkedController::class.java.getDeclaredField("frame").also { field ->
        field.isAccessible = true
        field.setLong(controller, frame)
      }
    }

    fun peerState(frame: Long, source: PeerEventSource) =
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            frame,
            source = source,
        )

    fun assertCompleteStateEquals(expected: LinkedController, actual: LinkedController) {
      val expectedStates = expected.encodedSessionStates()
      val actualStates = actual.encodedSessionStates()
      assertEquals(expectedStates.size, actualStates.size)
      expectedStates.indices.forEach { player ->
        val expectedState = expectedStates[player]
        val actualState = actualStates[player]
        if (expectedState == null || actualState == null) {
          assertEquals(expectedState, actualState, "active state differs for player $player")
        } else {
          assertContentEquals(
              expectedState,
              actualState,
              "game and adapter state differ for player $player",
          )
        }
      }
      assertEquals(expected.heldButtonStates(), actual.heldButtonStates())
    }
  }
}
