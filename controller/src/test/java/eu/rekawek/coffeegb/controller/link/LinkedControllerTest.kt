package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.EventQueue
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
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerCheckpointEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerStateEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerStopEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerProtocolErrorEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.ApplyStage
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.LinkedPlayerState
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.LinkedTopologyState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SerialPeripheralState
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateField
import eu.rekawek.coffeegb.controller.state.StateValue
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointKind
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointMetadata
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointRequest
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint
import eu.rekawek.coffeegb.controller.network.v9.V9ValidatedCheckpoint
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkedControllerTest {

  @Test
  fun localRomEventIncludesAdjacentBatteryOnlyWhenBatterySavesAreEnabled() {
    val directory = Files.createTempDirectory("coffee-gb-linked-battery")
    val rom = directory.resolve("linked-battery.gb")
    val battery = directory.resolve("linked-battery.sav")
    val batteryBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
    Files.copy(ROM.toPath(), rom)
    Files.write(battery, batteryBytes)

    fun load(enabled: Boolean): LinkedController.LocalRomLoadedEvent {
      val eventBus = EventBusImpl()
      val properties =
          EmulatorProperties(
              settingsPath = directory.resolve("settings-$enabled.properties"),
              overrides = ApplicationSettingsOverrides(batterySavesEnabled = enabled),
          )
      val controller = LinkedController(eventBus, properties, null).also {
        it.timingTicker.disabled = true
      }
      val received = AtomicReference<LinkedController.LocalRomLoadedEvent?>()
      eventBus.register<LinkedController.LocalRomLoadedEvent>(received::set)
      try {
        eventBus.post(LoadRomEvent(rom.toFile()))
        controller.runFrame()
        return assertNotNull(received.get())
      } finally {
        controller.close()
        properties.close()
        eventBus.close()
      }
    }

    try {
      assertNull(load(enabled = false).batteryFile)
      assertContentEquals(batteryBytes, assertNotNull(load(enabled = true).batteryFile))
    } finally {
      Files.deleteIfExists(directory.resolve("settings-false.properties"))
      Files.deleteIfExists(directory.resolve("settings-true.properties"))
      Files.deleteIfExists(battery)
      Files.deleteIfExists(rom)
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun sameRomReloadSendsRamAndRtcGenerationFlushedAtFrameSafePoint() {
    val directory = Files.createTempDirectory("coffee-gb-linked-battery-refresh")
    val rom = directory.resolve("linked-battery.gb")
    val battery = directory.resolve("linked-battery.sav")
    val romBytes = ROM.readBytes()
    romBytes[0x147] = 0x10 // MBC3 + timer + RAM + battery
    romBytes[0x149] = 0x03 // 32 KiB RAM
    Files.write(rom, romBytes)
    val eventBus = EventBusImpl()
    val properties =
        EmulatorProperties(
            settingsPath = directory.resolve("settings.properties"),
            overrides = ApplicationSettingsOverrides(batterySavesEnabled = true),
        )
    val controller =
        LinkedController(eventBus, properties, null).also { it.timingTicker.disabled = true }
    val loaded = LinkedBlockingQueue<LinkedController.LocalRomLoadedEvent>()
    eventBus.register<LinkedController.LocalRomLoadedEvent> { loaded.add(it) }

    try {
      eventBus.post(LoadRomEvent(rom.toFile()))
      controller.runFrame()
      assertNotNull(loaded.poll(1, TimeUnit.SECONDS))

      val oldSession = assertNotNull(privateList(controller, "sessions")[0] as Session?)
      oldSession.gameboy.addressSpace.setByte(0x0000, 0x0a)
      oldSession.gameboy.addressSpace.setByte(0x4000, 0x00)
      oldSession.gameboy.addressSpace.setByte(0xa000, 0x5a)
      oldSession.gameboy.addressSpace.setByte(0x4000, 0x08)
      oldSession.gameboy.addressSpace.setByte(0xa000, 37)
      oldSession.gameboy.addressSpace.setByte(0x4000, 0x0c)
      oldSession.gameboy.addressSpace.setByte(0xa000, 0x40) // halt for a stable RTC generation

      eventBus.post(LoadRomEvent(rom.toFile()))
      controller.runFrame()

      val replacement = assertNotNull(loaded.poll(1, TimeUnit.SECONDS))
      val transmitted = assertNotNull(replacement.batteryFile)
      assertEquals(0x8000 + 11 * Int.SIZE_BYTES, transmitted.size)
      assertEquals(0x5a, transmitted[0].toInt() and 0xff)
      val rtc =
          ByteBuffer.wrap(transmitted, 0x8000, 11 * Int.SIZE_BYTES)
              .slice()
              .order(ByteOrder.LITTLE_ENDIAN)
      assertEquals(37, rtc.getInt(0))
      assertEquals(0x40, rtc.getInt(4 * Int.SIZE_BYTES))
      assertContentEquals(Files.readAllBytes(battery), transmitted)
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      Files.deleteIfExists(directory.resolve("settings.properties"))
      Files.deleteIfExists(battery)
      Files.deleteIfExists(rom)
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun successfulLocalReplacementPublishesLoadingStoppedThenStartedToRootSubscribers() {
    val eventBus = EventBusImpl()
    val console = TrackingConsole()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), console).also {
          it.timingTicker.disabled = true
        }
    val lifecycle = mutableListOf<String>()
    eventBus.register<Controller.RomLoadingEvent> { lifecycle += "loading" }
    eventBus.register<Controller.EmulationStoppedEvent> { lifecycle += "stopped" }
    eventBus.register<Controller.EmulationStartedEvent> { lifecycle += "started" }

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertEquals(listOf("loading", "started"), lifecycle)
      val oldGameboy = assertNotNull(console.attachedGameboy)
      lifecycle.clear()

      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()

      assertEquals(
          listOf("loading", "stopped", "started"),
          lifecycle,
          "the old linked owner must stop only after replacement commit",
      )
      assertNotNull(console.attachedGameboy)
      assertTrue(
          console.attachedGameboy !== oldGameboy,
          "old-session cleanup must not detach the committed staged candidate",
      )
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun explicitLocalStopPublishesStoppedBeforeTheSessionIsReleased() {
    val eventBus = EventBusImpl()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), null).also {
          it.timingTicker.disabled = true
        }
    val stopped = LinkedBlockingQueue<Controller.EmulationStoppedEvent>()
    eventBus.register<Controller.EmulationStoppedEvent> { stopped.add(it) }

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()

      eventBus.post(Controller.StopEmulationEvent())
      controller.runFrame()

      assertNotNull(stopped.poll(1, TimeUnit.SECONDS))
      assertNull(privateList(controller, "sessions")[0])
      eventBus.post(Controller.StopEmulationEvent())
      controller.runFrame()
      assertNull(stopped.poll(100, TimeUnit.MILLISECONDS), "an empty slot must not stop twice")
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun throwingStoppedSubscriberCannotInterruptCommittedReplacementOrLaterLoads() {
    val eventBus = EventBusImpl()
    val console = TrackingConsole()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), console).also {
          it.timingTicker.disabled = true
        }
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    val thrownStops = AtomicInteger()
    eventBus.register<Controller.EmulationStartedEvent> { started.add(it) }

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      val originalSession = assertNotNull(privateList(controller, "sessions")[0] as Session?)

      eventBus.register<Controller.EmulationStoppedEvent> {
        thrownStops.incrementAndGet()
        throw IllegalStateException("injected stopped subscriber failure")
      }

      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()

      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      val firstReplacement = assertNotNull(privateList(controller, "sessions")[0] as Session?)
      assertTrue(firstReplacement !== originalSession)
      assertNotNull(console.attachedGameboy)

      // The committed candidate and controller thread remain usable after the subscriber failure.
      controller.runFrame()
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()

      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      assertTrue(privateList(controller, "sessions")[0] !== firstReplacement)
      assertEquals(2, thrownStops.get())
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun throwingStoppedSubscriberCannotInterruptExplicitStopOrLaterLoad() {
    val eventBus = EventBusImpl()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), null).also {
          it.timingTicker.disabled = true
        }
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    val thrownStops = AtomicInteger()
    eventBus.register<Controller.EmulationStartedEvent> { started.add(it) }

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      eventBus.register<Controller.EmulationStoppedEvent> {
        thrownStops.incrementAndGet()
        throw IllegalStateException("injected stopped subscriber failure")
      }

      eventBus.post(Controller.StopEmulationEvent())
      controller.runFrame()

      assertEquals(1, thrownStops.get())
      assertNull(privateList(controller, "sessions")[0])
      controller.runFrame()

      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      assertNotNull(privateList(controller, "sessions")[0])
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun failedCandidateStateRestoreDoesNotRewriteAdjacentBatterySidecar() {
    val directory = Files.createTempDirectory("coffee-gb-linked-candidate-discard")
    val rom = directory.resolve("candidate.gb")
    val battery = directory.resolve("candidate.sav")
    val romBytes = ROM.readBytes()
    romBytes[0x147] = 0x10 // MBC3 + timer + RAM + battery
    romBytes[0x149] = 0x03 // 32 KiB RAM
    Files.write(rom, romBytes)
    val originalBattery = byteArrayOf(0x12, 0x34, 0x56, 0x78)
    Files.write(battery, originalBattery)

    val seedBus = EventBusImpl()
    val seedConfig =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setHardwareProfile(HardwareProfileRegistry.SGB)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
    val seedSession = Session(seedConfig, seedBus, null)
    val incompatibleState = DetachedStateAdapter.capture(seedSession.gameboy)
    seedSession.close()
    seedBus.close()

    val eventBus = EventBusImpl()
    val properties =
        EmulatorProperties(
            settingsPath = directory.resolve("settings.properties"),
            overrides = ApplicationSettingsOverrides(batterySavesEnabled = true),
        )
    val controller =
        LinkedController(eventBus, properties, null).also { it.timingTicker.disabled = true }
    val failures = LinkedBlockingQueue<Controller.LoadRomFailedEvent>()
    val stopped = LinkedBlockingQueue<Controller.EmulationStoppedEvent>()
    eventBus.register<Controller.LoadRomFailedEvent> { failures.add(it) }
    eventBus.register<Controller.EmulationStoppedEvent> { stopped.add(it) }

    try {
      eventBus.post(LoadRomEvent(rom.toFile(), incompatibleState))
      controller.runFrame()

      assertNotNull(failures.poll(1, TimeUnit.SECONDS))
      assertNull(stopped.poll(100, TimeUnit.MILLISECONDS))
      assertEquals(0, controller.activeSessionCount())
      assertContentEquals(originalBattery, Files.readAllBytes(battery))
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      Files.deleteIfExists(directory.resolve("settings.properties"))
      Files.deleteIfExists(battery)
      Files.deleteIfExists(rom)
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun synchronousRomPreparationFailureReportsLoadFailureAndKeepsOldSessionRunning() {
    val invalidRom = Files.createTempFile("coffee-gb-linked-invalid", ".gb")
    Files.write(invalidRom, byteArrayOf(0))
    val eventBus = EventBusImpl()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), null).also {
          it.timingTicker.disabled = true
        }
    val loading = LinkedBlockingQueue<Controller.RomLoadingEvent>()
    val failures = LinkedBlockingQueue<Controller.LoadRomFailedEvent>()
    val stopped = LinkedBlockingQueue<Controller.EmulationStoppedEvent>()
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    eventBus.register<Controller.RomLoadingEvent> { loading.add(it) }
    eventBus.register<Controller.LoadRomFailedEvent> { failures.add(it) }
    eventBus.register<Controller.EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<Controller.EmulationStartedEvent> { started.add(it) }

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      loading.clear()
      val oldSession = assertNotNull(privateList(controller, "sessions")[0] as Session?)

      eventBus.post(LoadRomEvent(invalidRom.toFile()))

      assertEquals(invalidRom.toFile(), assertNotNull(loading.poll(1, TimeUnit.SECONDS)).rom)
      assertEquals(invalidRom.toFile(), assertNotNull(failures.poll(1, TimeUnit.SECONDS)).rom)
      assertNull(stopped.poll(100, TimeUnit.MILLISECONDS))
      assertTrue(oldSession === privateList(controller, "sessions")[0])

      // A synchronous parse/configuration error must not poison the frame loop or next load.
      controller.runFrame()
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      assertTrue(oldSession !== privateList(controller, "sessions")[0])
    } finally {
      controller.close()
      eventBus.close()
      Files.deleteIfExists(invalidRom)
    }
  }

  @Test
  fun failedSafePointBatteryPublishRetainsOldLinkedOwnershipAndLifecycle() {
    val eventBus = EventBusImpl()
    val controller =
        LinkedController(eventBus, EmulatorProperties(), null).also {
          it.timingTicker.disabled = true
        }
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<Controller.EmulationStoppedEvent>()
    val loading = LinkedBlockingQueue<Controller.RomLoadingEvent>()
    val loaded = LinkedBlockingQueue<LinkedController.LocalRomLoadedEvent>()
    val loadFailures = LinkedBlockingQueue<Controller.LoadRomFailedEvent>()
    val batteryFailures = LinkedBlockingQueue<BatteryPersistenceFailedEvent>()
    eventBus.register<Controller.EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<Controller.RomLoadingEvent> { loading.add(it) }
    eventBus.register<LinkedController.LocalRomLoadedEvent> { loaded.add(it) }
    eventBus.register<Controller.LoadRomFailedEvent> { loadFailures.add(it) }
    eventBus.register<BatteryPersistenceFailedEvent> { batteryFailures.add(it) }

    try {
      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(loading.poll(1, TimeUnit.SECONDS))
      controller.runFrame()
      assertNotNull(started.poll(1, TimeUnit.SECONDS))
      assertNotNull(loaded.poll(1, TimeUnit.SECONDS))
      val oldSession = assertNotNull(privateList(controller, "sessions")[0] as Session?)
      val oldConfig = assertNotNull(privateList(controller, "configs")[0])
      stopped.clear()
      controller.persistLocalBatteryCapture = {
        BatteryPersistenceResult.Failure(
            BatteryPersistenceResult.FailureKind.WRITE_FAILED,
            "cpu_instrs.sav",
            "injected safe-point write failure",
            IOException("disk full"),
        )
      }

      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(loading.poll(1, TimeUnit.SECONDS))
      controller.runFrame()

      assertNotNull(loadFailures.poll(1, TimeUnit.SECONDS))
      assertEquals(
          BatteryPersistenceFailedEvent.Operation.SAVE,
          assertNotNull(batteryFailures.poll(1, TimeUnit.SECONDS)).operation,
      )
      assertNull(started.poll(100, TimeUnit.MILLISECONDS))
      assertNull(stopped.poll(100, TimeUnit.MILLISECONDS))
      assertNull(loaded.poll(100, TimeUnit.MILLISECONDS))
      assertTrue(oldSession === privateList(controller, "sessions")[0])
      assertTrue(oldConfig === privateList(controller, "configs")[0])
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun oversizedAdjacentBatteryIsRejectedBeforeLinkedPayloadRetention() {
    val directory = Files.createTempDirectory("coffee-gb-linked-battery-limit")
    val rom = directory.resolve("linked-battery.gb")
    val battery = directory.resolve("linked-battery.sav")
    Files.copy(ROM.toPath(), rom)
    Files.newByteChannel(
            battery,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.CREATE,
        )
        .use {
          it.position(StateLimits.BATTERY.decodedBytes.toLong())
          it.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }
    val eventBus = EventBusImpl()
    val properties =
        EmulatorProperties(
            settingsPath = directory.resolve("settings.properties"),
            overrides = ApplicationSettingsOverrides(batterySavesEnabled = true),
        )
    val controller =
        LinkedController(eventBus, properties, null).also { it.timingTicker.disabled = true }
    val loaded = AtomicReference<LinkedController.LocalRomLoadedEvent?>()
    val failure = AtomicReference<BatteryPersistenceFailedEvent?>()
    eventBus.register<LinkedController.LocalRomLoadedEvent>(loaded::set)
    eventBus.register<BatteryPersistenceFailedEvent>(failure::set)

    try {
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      val oldState = controller.encodedSessionStates()
      loaded.set(null)

      eventBus.post(LoadRomEvent(rom.toFile()))
      controller.runFrame()

      assertNull(loaded.get())
      assertEncodedStatesEqual(oldState, controller.encodedSessionStates())
      assertEquals(
          BatteryPersistenceFailedEvent.Operation.LOAD,
          assertNotNull(failure.get()).operation,
      )
      assertTrue(assertNotNull(failure.get()).message.contains("2097152-byte safety limit"))
    } finally {
      controller.close()
      properties.close()
      eventBus.close()
      Files.deleteIfExists(directory.resolve("settings.properties"))
      Files.deleteIfExists(battery)
      Files.deleteIfExists(rom)
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun v9RollbackDiagnosticsObserveSafePointReplayWithoutChangingState() {
    val (_, controller) = configuredController(LinkMode.NORMAL, 2)
    val target = controller.createV9Target()
    try {
      repeat(6) { controller.runFrame() }
      val accepted = AtomicReference<V9ErrorCode?>()
      val lateFrame = controller.currentFrame() - 3
      target.input(
          eu.rekawek.coffeegb.controller.network.v9.V9InputState(lateFrame, 1, 0x10, 1),
      ) { accepted.set(it) }
      dispatchOnly(controller)
      assertNull(accepted.get())
      controller.runFrame()
      val metrics = controller.rollbackMetricsSource().snapshot()
      assertEquals(1, metrics.rollbackCount)
      assertTrue(metrics.lastFramesRewound >= 2)
      assertTrue(metrics.totalFramesResimulated >= 1)
      assertEquals(eu.rekawek.coffeegb.controller.network.NetplayRollbackReason.REMOTE_INPUT,
          metrics.lastReason)
      assertTrue(metrics.historyEntries in 1..metrics.historyCapacity)
      val stateAfterReplay = controller.encodedSessionStates()
      repeat(100) { controller.rollbackMetricsSource().snapshot() }
      assertEncodedStatesEqual(stateAfterReplay, controller.encodedSessionStates())

      val rejected = AtomicReference<V9ErrorCode?>()
      target.input(
          eu.rekawek.coffeegb.controller.network.v9.V9InputState(-1, 1, 0, 2),
      ) { rejected.set(it) }
      dispatchOnly(controller)
      assertEquals(V9ErrorCode.SEQUENCE_ERROR, rejected.get())
      assertEquals(1, controller.rollbackMetricsSource().snapshot().tooOldInputs)
    } finally {
      target.close()
      controller.closeWithState()
    }
  }

  @Test
  fun v9ProviderCapturesAtFrameSafePointAndCancellationReleasesWaiter() {
    val (eventBus, controller) = configuredController(LinkMode.NORMAL, 2)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val target = controller.createV9Target()
      val frame = controller.currentFrame()
      val machine =
          executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint> {
            target.capture(V9CheckpointRequest(V9CheckpointKind.MACHINE, 0x02, 1, frame))
          }
      awaitPendingCapture(target)
      assertFalse(machine.isDone)
      dispatchOnly(controller)
      val machineCapture = machine.get(5, TimeUnit.SECONDS)
      val machineBytes = machineCapture.takeStateFile()
      assertEquals(frame, machineCapture.frame)
      assertEquals(frame, controller.currentFrame())
      assertTrue(StateCodec.decode(machineBytes).root is MachineStateRoot)
      machineBytes.fill(0)

      val session =
          executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint> {
            target.capture(V9CheckpointRequest(V9CheckpointKind.SESSION, 0x02, 1, frame))
          }
      awaitPendingCapture(target)
      assertFalse(session.isDone)
      dispatchOnly(controller)
      val sessionCapture = session.get(5, TimeUnit.SECONDS)
      val sessionBytes = sessionCapture.takeStateFile()
      assertTrue(StateCodec.decode(sessionBytes).root is SessionStateRoot)
      sessionBytes.fill(0)

      val cancelledTarget = controller.createV9Target()
      val cancelled =
          executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint> {
            cancelledTarget.capture(
                V9CheckpointRequest(V9CheckpointKind.MACHINE, 0x02, 1, frame))
          }
      awaitPendingCapture(cancelledTarget)
      assertFalse(cancelled.isDone)
      cancelledTarget.close()
      val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
        cancelled.get(5, TimeUnit.SECONDS)
      }
      assertEquals(
          V9ErrorCode.CANCELLED,
          (failure.cause as eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException).reason,
      )

      val cancelledGenerationTarget = controller.createV9Target()
      val cancelledGeneration =
          executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9TargetGeneration> {
            cancelledGenerationTarget.captureGeneration()
          }
      awaitPendingCapture(cancelledGenerationTarget)
      assertFalse(cancelledGeneration.isDone)
      cancelledGenerationTarget.close()
      val generationFailure = assertFailsWith<java.util.concurrent.ExecutionException> {
        cancelledGeneration.get(5, TimeUnit.SECONDS)
      }
      assertEquals(
          V9ErrorCode.CANCELLED,
          (generationFailure.cause as
              eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException).reason,
      )
      assertEquals(0, cancelledGenerationTarget.pendingCaptureCount())
    } finally {
      executor.shutdownNow()
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v9LinkedProviderPreservesEmptySlotsAcrossStopResetAndRejectsPreparedTargetChange() {
    val (eventBus, controller) = configuredController(LinkMode.FOUR_PLAYER_ADAPTER, 2)
    val executor = Executors.newSingleThreadExecutor()
    val target = controller.createV9Target()
    try {
      fun capture(): eu.rekawek.coffeegb.controller.state.StateFile {
        val future =
            executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint> {
              target.capture(
                  V9CheckpointRequest(
                      V9CheckpointKind.LINKED_SESSION,
                      0x0f,
                      0,
                      controller.currentFrame(),
                  ),
              )
            }
        awaitPendingCapture(target)
        dispatchOnly(controller)
        val bytes = future.get(5, TimeUnit.SECONDS).takeStateFile()
        return StateCodec.decode(bytes).also { bytes.fill(0) }
      }

      val initial = capture()
      assertEquals(listOf(false, false, true, true), initial.identities.map { it.identity == null })
      assertEquals(
          listOf(false, false, true, true),
          (initial.root as eu.rekawek.coffeegb.controller.state.LinkedSessionStateRoot)
              .linked.players.map { it.session == null },
      )

      val preparedRef = AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
      val generation = captureGeneration(target, controller)
      target.prepare(
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.LINKED_SESSION,
                  0x0f,
                  0,
                  controller.currentFrame(),
                  41,
              ),
              initial,
              MessageDigest.getInstance("SHA-256").digest(StateCodec.encode(initial)),
          ),
          generation,
      ) { prepared, failure ->
        assertNull(failure)
        preparedRef.set(prepared)
      }
      dispatchOnly(controller)

      val stop = AtomicReference<V9ErrorCode?>()
      target.control(
          eu.rekawek.coffeegb.controller.network.v9.V9RuntimeControl(
              eu.rekawek.coffeegb.controller.network.v9.V9RuntimeMessageKind.STOP,
              controller.currentFrame(),
              1,
          ),
          stop::set,
      )
      dispatchOnly(controller)
      assertNull(stop.get())
      val stopped = controller.captureDetachedState()
      assertNull(stopped.players[1].session)

      val commitFailure = AtomicReference<V9ErrorCode?>()
      assertNotNull(preparedRef.get()).commit(commitFailure::set)
      dispatchOnly(controller)
      assertEquals(V9ErrorCode.TOPOLOGY_MISMATCH, commitFailure.get())
      assertEquals(stopped, controller.captureDetachedState())

      val reset = AtomicReference<V9ErrorCode?>()
      target.control(
          eu.rekawek.coffeegb.controller.network.v9.V9RuntimeControl(
              eu.rekawek.coffeegb.controller.network.v9.V9RuntimeMessageKind.RESET,
              controller.currentFrame(),
              1,
          ),
          reset::set,
      )
      dispatchOnly(controller)
      assertNull(reset.get())
      assertNotNull(controller.captureDetachedState().players[1].session)
      assertEquals(listOf(false, false, true, true), capture().identities.map { it.identity == null })
    } finally {
      target.disconnected(1)
      executor.shutdownNow()
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v9TargetUsesPostTransferRomAndProfileIdentityForCheckpointCapture() {
    val (eventBus, controller) = configuredController(LinkMode.NORMAL, 1)
    val target = controller.createV9Target()
    val executor = Executors.newSingleThreadExecutor()
    try {
      assertNull(captureGeneration(target, controller).identities[1].identity)
      val transferredRom = mbc2Rom()
      eventBus.post(
          PeerLoadedGameEvent(
              transferredRom,
              null,
              null,
              GameboyType.DMG,
              Gameboy.BootstrapMode.SKIP,
              controller.currentFrame(),
              player = 1,
          ),
      )
      controller.runFrame()
      val postTransferIdentity =
          assertNotNull(captureGeneration(target, controller).identities[1].identity)
      val future =
          executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint> {
            target.capture(
                V9CheckpointRequest(
                    V9CheckpointKind.MACHINE,
                    0x02,
                    1,
                    controller.currentFrame(),
                ),
            )
          }
      awaitPendingCapture(target)
      dispatchOnly(controller)
      val bytes = future.get(5, TimeUnit.SECONDS).takeStateFile()
      val file = StateCodec.decode(bytes)
      bytes.fill(0)
      val capturedIdentity = assertNotNull(file.identities.single().identity)
      assertEquals(postTransferIdentity.primaryRom, capturedIdentity.primaryRom)
      assertEquals(postTransferIdentity.slotRom, capturedIdentity.slotRom)
      assertEquals(postTransferIdentity.profile.hardware, capturedIdentity.profile.hardware)
      assertEquals(
          postTransferIdentity.profile.canonicalProfileId,
          capturedIdentity.profile.canonicalProfileId,
      )
      assertEquals(HardwareProfileRegistry.DMG.id(), postTransferIdentity.profile.canonicalProfileId)
    } finally {
      target.disconnected(1)
      executor.shutdownNow()
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v9MachineCheckpointAppliesOnlyAtFrameSafePointAndMismatchIsAtomic() {
    val (eventBus, controller) = configuredController(LinkMode.NORMAL, 2)
    val sourceConfig = Controller.createGameboyConfig(EmulatorProperties(), Rom(ROM))
        .setSupportBatterySave(false)
    val source = Session(sourceConfig, EventBusImpl(), null)
    try {
      repeat(2_048) { source.gameboy.tick() }
      val file = StateCodec.captureVersion2(sourceConfig, source.gameboy)
      val encoded = StateCodec.encode(file)
      val checkpoint =
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.MACHINE,
                  0x02,
                  1,
                  controller.currentFrame(),
                  41,
              ),
              file,
              MessageDigest.getInstance("SHA-256").digest(encoded),
          )
      val target = controller.createV9Target()
      val targetGeneration = captureGeneration(target, controller)
      val prepared = AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
      val preparationFailure = AtomicReference<V9ErrorCode?>()
      val preparedLatch = CountDownLatch(1)
      val before = controller.captureDetachedState()
      target.prepare(checkpoint, targetGeneration) { transaction, failure ->
        prepared.set(transaction)
        preparationFailure.set(failure)
        preparedLatch.countDown()
      }
      assertEquals(before, controller.captureDetachedState())
      assertEquals(1, preparedLatch.count)

      dispatchOnly(controller)
      assertTrue(preparedLatch.await(5, TimeUnit.SECONDS))
      assertNull(preparationFailure.get())
      assertEquals(before, controller.captureDetachedState())
      val completion = AtomicReference<V9ErrorCode?>()
      val completed = CountDownLatch(1)
      assertNotNull(prepared.get()).commit {
        completion.set(it)
        completed.countDown()
      }
      assertEquals(before, controller.captureDetachedState())
      dispatchOnly(controller)
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      assertNull(completion.get())
      assertEquals(
          DetachedStateAdapter.capture(source.gameboy),
          assertNotNull(controller.captureDetachedState().players[1].session).machine,
      )
      val restoredSession = assertNotNull(privateList(controller, "sessions")[1] as Session?)
      repeat(4_096) {
        source.gameboy.tick()
        restoredSession.gameboy.tick()
      }
      assertEquals(
          DetachedStateAdapter.capture(source.gameboy),
          DetachedStateAdapter.capture(restoredSession.gameboy),
          "portable MACHINE commit must continue deterministically",
      )
      assertEquals(
          "909608afe8ea1510ea187b080ce48818b5b1f62379a0e23dde31cbb7ad61bd99",
          sha256Hex(
              StateCodec.encode(
                  StateCodec.captureVersion2(restoredSession.config, restoredSession.gameboy))),
          "normal v9 continuation hash",
      )

      val sessionFile = StateCodec.captureVersion2(restoredSession)
      val expectedSession = restoredSession.captureDetachedState()
      repeat(1_024) { restoredSession.gameboy.tick() }
      val resyncPrepared =
          AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
      val resyncPreparedLatch = CountDownLatch(1)
      target.prepare(
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.SESSION,
                  0x02,
                  1,
                  controller.currentFrame(),
                  41,
              ),
              sessionFile,
              MessageDigest.getInstance("SHA-256").digest(StateCodec.encode(sessionFile)),
          ),
          targetGeneration,
      ) { transaction, failure ->
        assertNull(failure)
        resyncPrepared.set(transaction)
        resyncPreparedLatch.countDown()
      }
      dispatchOnly(controller)
      assertTrue(resyncPreparedLatch.await(5, TimeUnit.SECONDS))
      val resyncCommitted = CountDownLatch(1)
      assertNotNull(resyncPrepared.get()).commit { failure ->
        assertNull(failure)
        resyncCommitted.countDown()
      }
      dispatchOnly(controller)
      assertTrue(resyncCommitted.await(5, TimeUnit.SECONDS))
      assertEquals(expectedSession, restoredSession.captureDetachedState())

      val stable = controller.captureDetachedState()
      val stableHistory = controller.stateHistory.captureSnapshot()
      val stableConfigs = privateList(controller, "configs")
      val stableRoms = privateByteArrays(controller, "romBuffers")
      val stableSlots = privateByteArrays(controller, "slotRomBuffers")
      val stableBatteries = privateByteArrays(controller, "batteryBuffers")
      val rollbackPrepared =
          AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
      target.prepare(
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.SESSION,
                  0x02,
                  1,
                  controller.currentFrame(),
                  41,
              ),
              sessionFile,
              MessageDigest.getInstance("SHA-256").digest(StateCodec.encode(sessionFile)),
          ),
          targetGeneration,
      ) { transaction, failure ->
        assertNull(failure)
        rollbackPrepared.set(transaction)
      }
      dispatchOnly(controller)
      controller.v9ApplyProbe = { player, stage ->
        if (player == 1 && stage == ApplyStage.AFTER_MACHINE_MUTATION) {
          throw IllegalStateException("injected v9 commit failure")
        }
      }
      val rollbackFailure = AtomicReference<V9ErrorCode?>()
      assertNotNull(rollbackPrepared.get()).commit(rollbackFailure::set)
      dispatchOnly(controller)
      controller.v9ApplyProbe = null
      assertEquals(V9ErrorCode.TOPOLOGY_MISMATCH, rollbackFailure.get())
      assertEquals(stable, controller.captureDetachedState())
      assertEquals(stableHistory, controller.stateHistory.captureSnapshot())
      stableConfigs.zip(privateList(controller, "configs")).forEach { (expected, actual) ->
        assertTrue(expected === actual)
      }
      assertByteArraysEqual(stableRoms, privateByteArrays(controller, "romBuffers"))
      assertByteArraysEqual(stableSlots, privateByteArrays(controller, "slotRomBuffers"))
      assertByteArraysEqual(stableBatteries, privateByteArrays(controller, "batteryBuffers"))

      val wrongConfig =
          StateCodecTestSupport.configuration(ROM_BYTES, GameboyType.SGB)
      val wrongSession = StateCodecTestSupport.session(wrongConfig)
      try {
        val wrongFile = StateCodec.captureVersion2(wrongConfig, wrongSession.gameboy)
        val failure = AtomicReference<V9ErrorCode?>()
        val failed = CountDownLatch(1)
        val badPrepared = AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
        target.prepare(
            V9ValidatedCheckpoint(
                checkpoint.metadata,
                wrongFile,
                MessageDigest.getInstance("SHA-256").digest(StateCodec.encode(wrongFile)),
            ),
            targetGeneration,
        ) { transaction, error ->
          badPrepared.set(transaction)
          failure.set(error)
          failed.countDown()
        }
        dispatchOnly(controller)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(V9ErrorCode.TOPOLOGY_MISMATCH, failure.get())
        assertNull(badPrepared.get())
        assertEquals(stable, controller.captureDetachedState())
      } finally {
        wrongSession.close()
      }
      encoded.fill(0)
    } finally {
      source.close()
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v9CancellationLinearizesQueuedPrepareAndCommitBeforeSafePointMutation() {
    val (eventBus, controller) = configuredController(LinkMode.NORMAL, 2)
    val sourceConfig = Controller.createGameboyConfig(EmulatorProperties(), Rom(ROM))
        .setSupportBatterySave(false)
    val source = Session(sourceConfig, EventBusImpl(), null)
    val workers = Executors.newFixedThreadPool(2)
    try {
      repeat(4_096) { source.gameboy.tick() }
      val file = StateCodec.captureVersion2(sourceConfig, source.gameboy)
      val checkpoint =
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.MACHINE,
                  0x02,
                  1,
                  controller.currentFrame(),
                  41,
              ),
              file,
              MessageDigest.getInstance("SHA-256").digest(StateCodec.encode(file)),
          )
      val stableState = controller.captureDetachedState()
      val stableHistory = controller.stateHistory.captureSnapshot()
      val stableConfigs = privateList(controller, "configs")
      val stableRoms = privateByteArrays(controller, "romBuffers")
      val stableSlots = privateByteArrays(controller, "slotRomBuffers")
      val stableBatteries = privateByteArrays(controller, "batteryBuffers")
      val stableHeld = controller.heldButtonStates()

      fun assertStable() {
        assertEquals(stableState, controller.captureDetachedState())
        assertEquals(stableHistory, controller.stateHistory.captureSnapshot())
        stableConfigs.zip(privateList(controller, "configs")).forEach { (expected, actual) ->
          assertTrue(expected === actual)
        }
        assertByteArraysEqual(stableRoms, privateByteArrays(controller, "romBuffers"))
        assertByteArraysEqual(stableSlots, privateByteArrays(controller, "slotRomBuffers"))
        assertByteArraysEqual(stableBatteries, privateByteArrays(controller, "batteryBuffers"))
        assertEquals(stableHeld, controller.heldButtonStates())
      }

      val prepareTarget = controller.createV9Target()
      val prepareGeneration = captureGeneration(prepareTarget, controller)
      val prepareFailure = AtomicReference<V9ErrorCode?>()
      val prepareDone = CountDownLatch(1)
      prepareTarget.prepare(checkpoint, prepareGeneration) { prepared, failure ->
        prepared?.close()
        prepareFailure.set(failure)
        prepareDone.countDown()
      }
      assertEquals(1, prepareTarget.pendingCaptureCount())
      prepareTarget.close()
      assertTrue(prepareDone.await(5, TimeUnit.SECONDS))
      assertEquals(V9ErrorCode.CANCELLED, prepareFailure.get())
      dispatchOnly(controller)
      assertEquals(0, prepareTarget.pendingCaptureCount())
      assertEquals(0, prepareTarget.preparedTransactionCount())
      assertStable()

      val queuedTarget = controller.createV9Target()
      val queuedGeneration = captureGeneration(queuedTarget, controller)
      val queuedPrepared = AtomicReference<V9PreparedCheckpoint?>()
      queuedTarget.prepare(checkpoint, queuedGeneration) { prepared, failure ->
        assertNull(failure)
        queuedPrepared.set(prepared)
      }
      dispatchOnly(controller)
      val queuedFailure = AtomicReference<V9ErrorCode?>()
      val queuedDone = CountDownLatch(1)
      assertNotNull(queuedPrepared.get()).commit { failure ->
        queuedFailure.set(failure)
        queuedDone.countDown()
      }
      queuedTarget.close()
      assertTrue(queuedDone.await(5, TimeUnit.SECONDS))
      assertEquals(V9ErrorCode.CANCELLED, queuedFailure.get())
      dispatchOnly(controller)
      assertEquals(0, queuedTarget.pendingCaptureCount())
      assertEquals(0, queuedTarget.preparedTransactionCount())
      assertStable()

      val winningTarget = controller.createV9Target()
      val winningGeneration = captureGeneration(winningTarget, controller)
      val winningPrepared = AtomicReference<V9PreparedCheckpoint?>()
      winningTarget.prepare(checkpoint, winningGeneration) { prepared, failure ->
        assertNull(failure)
        winningPrepared.set(prepared)
      }
      dispatchOnly(controller)
      val enteredApply = CountDownLatch(1)
      val releaseApply = CountDownLatch(1)
      val completions = AtomicInteger()
      val winningFailure = AtomicReference<V9ErrorCode?>()
      controller.v9ApplyProbe = { player, stage ->
        if (player == 1 && stage == ApplyStage.AFTER_MACHINE_MUTATION) {
          enteredApply.countDown()
          releaseApply.await()
        }
      }
      assertNotNull(winningPrepared.get()).commit { failure ->
        winningFailure.set(failure)
        completions.incrementAndGet()
      }
      val dispatch = workers.submit {
        while (enteredApply.count != 0L && completions.get() == 0) {
          dispatchOnly(controller)
        }
      }
      assertTrue(
          enteredApply.await(5, TimeUnit.SECONDS),
          "apply did not start: completions=${completions.get()} " +
              "failure=${winningFailure.get()} prepared=${winningTarget.preparedTransactionCount()} " +
              "dispatchDone=${dispatch.isDone}",
      )
      val closeStarted = CountDownLatch(1)
      val close = workers.submit {
        closeStarted.countDown()
        winningTarget.close()
      }
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
      assertFalse(close.isDone, "target close must wait for the selected atomic safe-point outcome")
      releaseApply.countDown()
      dispatch.get(5, TimeUnit.SECONDS)
      close.get(5, TimeUnit.SECONDS)
      controller.v9ApplyProbe = null
      assertEquals(1, completions.get())
      assertNull(winningFailure.get())
      assertEquals(
          DetachedStateAdapter.capture(source.gameboy),
          assertNotNull(controller.captureDetachedState().players[1].session).machine,
      )
      assertEquals(0, winningTarget.pendingCaptureCount())
      assertEquals(0, winningTarget.preparedTransactionCount())
    } finally {
      controller.v9ApplyProbe = null
      workers.shutdownNow()
      source.close()
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v9FourPlayerLinkedCheckpointCommitsWholeRosterAtOneSafePoint() {
    val (sourceBus, source) = configuredController(LinkMode.FOUR_PLAYER_ADAPTER, 4)
    val (targetBus, targetController) = configuredController(LinkMode.FOUR_PLAYER_ADAPTER, 4)
    try {
      repeat(2) { source.runFrame() }
      val file = StateCodec.captureVersion2(source)
      val encoded = StateCodec.encode(file)
      val checkpoint =
          V9ValidatedCheckpoint(
              V9CheckpointMetadata(
                  V9CheckpointKind.LINKED_SESSION,
                  0x0f,
                  0,
                  source.currentFrame(),
                  41,
              ),
              file,
              MessageDigest.getInstance("SHA-256").digest(encoded),
          )
      val before = targetController.captureDetachedState()
      val prepared = AtomicReference<eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint?>()
      val preparedLatch = CountDownLatch(1)
      val v9Target = targetController.createV9Target()
      val targetGeneration = captureGeneration(v9Target, targetController)
      v9Target.prepare(checkpoint, targetGeneration) { transaction, failure ->
        assertNull(failure)
        prepared.set(transaction)
        preparedLatch.countDown()
      }
      assertEquals(before, targetController.captureDetachedState())
      dispatchOnly(targetController)
      assertTrue(preparedLatch.await(5, TimeUnit.SECONDS))
      assertEquals(before, targetController.captureDetachedState())

      val committed = CountDownLatch(1)
      val commitFailure = AtomicReference<V9ErrorCode?>()
      assertNotNull(prepared.get()).commit { failure ->
        commitFailure.set(failure)
        committed.countDown()
      }
      assertEquals(before, targetController.captureDetachedState())
      dispatchOnly(targetController)
      assertTrue(committed.await(5, TimeUnit.SECONDS))
      assertNull(commitFailure.get())
      val sourceState = source.captureDetachedState()
      assertEquals(
          LinkedSessionState(
              sourceState.frame,
              0,
              sourceState.topology,
              sourceState.players,
          ),
          targetController.captureDetachedState(),
      )
      repeat(2) {
        source.runFrame()
        targetController.runFrame()
      }
      assertEquals(source.captureDetachedState(), targetController.captureDetachedState())
      assertEquals(
          "f19740d73c745a76b8bb7a2898ff308bec7d8e855d191efb8f248108a33149ae",
          sha256Hex(StateCodec.encode(StateCodec.captureVersion2(targetController))),
          "four-player v9 continuation hash",
      )
      encoded.fill(0)
    } finally {
      source.close()
      targetController.close()
      sourceBus.close()
      targetBus.close()
    }
  }

  @Test
  fun v9InputIsFrameSafeBoundedAndQueuedInputIsCancelledWithItsConnection() {
    val (eventBus, controller) = configuredController(LinkMode.NORMAL, 2)
    try {
      val cancelledTarget = controller.createV9Target()
      val cancelled = AtomicReference<V9ErrorCode?>()
      val cancelledLatch = CountDownLatch(1)
      cancelledTarget.input(
          eu.rekawek.coffeegb.controller.network.v9.V9InputState(
              controller.currentFrame(), 1, 0x10, 1),
      ) {
        cancelled.set(it)
        cancelledLatch.countDown()
      }
      cancelledTarget.disconnected(1)
      assertEquals(1, cancelledLatch.count)
      dispatchOnly(controller)
      assertTrue(cancelledLatch.await(5, TimeUnit.SECONDS))
      assertEquals(V9ErrorCode.CANCELLED, cancelled.get())
      assertTrue((privateList(controller, "sessions")[1] as Session).heldButtons.isEmpty())

      val target = controller.createV9Target()
      val accepted = AtomicReference<V9ErrorCode?>()
      val acceptedLatch = CountDownLatch(1)
      target.input(
          eu.rekawek.coffeegb.controller.network.v9.V9InputState(
              controller.currentFrame(), 1, 0x10, 1),
      ) {
        accepted.set(it)
        acceptedLatch.countDown()
      }
      assertEquals(1, acceptedLatch.count)
      controller.runFrame()
      assertTrue(acceptedLatch.await(5, TimeUnit.SECONDS))
      assertNull(accepted.get())
      assertTrue(Button.A in (privateList(controller, "sessions")[1] as Session).heldButtons)

      val rejected = AtomicReference<V9ErrorCode?>()
      val rejectedLatch = CountDownLatch(1)
      target.input(
          eu.rekawek.coffeegb.controller.network.v9.V9InputState(
              StateLimits.NETPLAY_MAX_FRAME, 1, 0x20, 2),
      ) {
        rejected.set(it)
        rejectedLatch.countDown()
      }
      dispatchOnly(controller)
      assertTrue(rejectedLatch.await(5, TimeUnit.SECONDS))
      assertEquals(V9ErrorCode.SEQUENCE_ERROR, rejected.get())
      assertTrue(Button.A in (privateList(controller, "sessions")[1] as Session).heldButtons)
      assertFalse(Button.B in (privateList(controller, "sessions")[1] as Session).heldButtons)
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun v2OnlyProfileLocalLoadRejectsBeforeLinkedSessionConstructionAndRetainsBasicState() {
    for (profile in
        listOf(
            HardwareProfileRegistry.SGB,
            HardwareProfileRegistry.SGB2,
            HardwareProfileRegistry.MGB,
        )) {
      val seedBus = EventBusImpl()
      val seedConfig =
          Gameboy.GameboyConfiguration(Rom(ROM))
              .setHardwareProfile(profile)
              .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
              .setSupportBatterySave(false)
      val seedSession = Session(seedConfig, seedBus, null)
      val seedState = DetachedStateAdapter.capture(seedSession.gameboy)
      seedSession.close()
      seedBus.close()

      val eventBus = EventBusImpl()
      val errors = mutableListOf<ServerProtocolErrorEvent>()
      var stopRequests = 0
      eventBus.register<ServerProtocolErrorEvent> { errors += it }
      eventBus.register<StopServerEvent> { stopRequests++ }
      val sut =
          LinkedController(
              eventBus,
              EmulatorProperties(profile),
              null,
              LinkMode.NORMAL,
              localPlayer = 0,
          )
      sut.timingTicker.disabled = true
      val before = sut.captureDetachedState()

      eventBus.post(LoadRomEvent(ROM, seedState))
      eventBus.drainAsyncEvents()

      assertEquals(0, sut.activeSessionCount())
      assertEquals(before, sut.captureDetachedState())
      assertEquals(1, errors.size)
      assertEquals(0, errors.single().player)
      assertTrue(errors.single().message.contains("protocol v8"))
      assertTrue(errors.single().message.contains("StateFile v1"))
      assertEquals(1, stopRequests)

      val returned = assertNotNull(sut.closeWithState())
      assertEquals(seedState, returned.state)
      assertContentEquals(ROM.readBytes(), returned.rom.file.readBytes())
    }
  }

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
    val detached = sut.captureDetachedState()
    assertEquals(LinkedTopologyState.FOUR_PLAYER_ADAPTER, detached.topology)
    assertEquals(4, detached.players.size)
    assertNotNull(detached.players[0].session)
    assertTrue(detached.players.drop(1).all { it.session == null })
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
    assertTrue(
        checkpoint.states.all {
          it.portableState?.copyOfRange(0, 4)?.contentEquals("CGBS".toByteArray()) == true
        })

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
    val clientFailures = mutableListOf<ProtocolErrorReason>()
    val serverSource = PeerEventSource(0) { reason, _ -> clientFailures += reason }

    fun deliverCheckpoint(value: LinkedController.SessionStateReadyEvent) {
      clientBus.post(checkpointEvent(value, serverSource))
    }

    deliverCheckpoint(checkpoint)
    client.runFrame()

    assertEquals(2, client.activeSessionCount())
    assertEquals(host.currentFrame(), client.currentFrame())
    assertEquals(host.stateHistory.getHead().frame, client.stateHistory.getHead().frame)

    // RESET is a generation boundary. A valid input at the same frame must apply to the fresh
    // machine instead of letting history merge restore the pre-reset session over it.
    val resetFrame = host.currentFrame()
    hostBus.post(ReceivedRemoteResetEvent(resetFrame, player = 1))
    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            resetFrame,
            Input(listOf(Button.SELECT), emptyList()),
            player = 1,
        ))
    host.runFrame()
    val resetCheckpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    deliverCheckpoint(resetCheckpoint)
    client.runFrame()
    assertCompleteStateEquals(host, client)

    // Another physical port can hot-plug while the original client is already running.
    val joinBoundaryPatchFrame = host.currentFrame() - 1
    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            joinBoundaryPatchFrame,
            Input(listOf(Button.A), emptyList()),
            player = 1,
        ))
    hostBus.post(ReceivedRemoteResetEvent(host.currentFrame(), player = 1))
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
    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            disconnectBoundaryPatchFrame,
            Input(listOf(Button.B), emptyList()),
            player = 1,
        ))
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
    assertTrue(clientFailures.isEmpty(), "healthy host checkpoints exhausted the client budget")

    clientBus.close()
    hostBus.close()
  }

  @Test
  fun fourPlayerFormationResetAndReplacementFitPersistentCheckpointBudget() {
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

    val clientBus = EventBusImpl()
    val clientProperties = EmulatorProperties()
    val client =
        LinkedController(
            clientBus,
            clientProperties,
            null,
            LinkMode.FOUR_PLAYER_ADAPTER,
            localPlayer = 1,
        )
    client.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val serverSource = PeerEventSource(0) { reason, _ -> failures += reason }

    fun deliverCheckpoint(
        expectedPlayers: List<Int>,
    ): LinkedController.SessionStateReadyEvent {
      val checkpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
      assertEquals(expectedPlayers, checkpoint.states.map { it.player })
      clientBus.post(checkpointEvent(checkpoint, serverSource))
      client.runFrame()
      assertTrue(failures.isEmpty(), "normal topology formation exhausted the checkpoint budget")
      assertCompleteStateEquals(host, client)
      return checkpoint
    }

    hostBus.post(LoadRomEvent(ROM))
    hostBus.post(peerState(0, PeerEventSource(1) { _, _ -> }))
    clientBus.post(LoadRomEvent(ROM))
    client.runFrame()
    host.runFrame()
    deliverCheckpoint(listOf(0, 1))
    assertEquals(listOf(true, true, null, null), client.releasedInputSourceAssignments())

    // A host RESET is relayed before the same-frame authoritative checkpoint. It consumes one
    // replay/state-change token, while the checkpoint uses the transition credit instead of being
    // charged a second time.
    val resetFrame = host.currentFrame()
    hostBus.post(Controller.ResetEmulationEvent())
    host.runFrame()
    clientBus.post(ReceivedRemoteResetEvent(resetFrame, player = 0, source = serverSource))
    deliverCheckpoint(listOf(0, 1))

    hostBus.post(peerState(0, PeerEventSource(2) { _, _ -> }).copy(player = 2))
    host.runFrame()
    deliverCheckpoint(listOf(0, 1, 2))

    hostBus.post(peerState(0, PeerEventSource(3) { _, _ -> }).copy(player = 3))
    host.runFrame()
    deliverCheckpoint(listOf(0, 1, 2, 3))
    assertEquals(listOf(true, true, true, true), client.releasedInputSourceAssignments())

    // The reset origin does not receive its own relayed RESET. Its trusted local transition grants
    // the equivalent one-use credit, so the checkpoint is not charged twice during the formation
    // burst.
    val originResetFrame = host.currentFrame()
    clientBus.post(Controller.ResetEmulationEvent())
    hostBus.post(
        ReceivedRemoteResetEvent(
            originResetFrame,
            player = 1,
            source = PeerEventSource(1) { _, _ -> },
        ))
    host.runFrame()
    deliverCheckpoint(listOf(0, 1, 2, 3))

    // Pending input makes history reconciliation publish the RESET checkpoint one frame later.
    // The source-scoped credit follows the logical transition rather than requiring F == F + 1.
    val pendingResetFrame = host.currentFrame()
    val resetOrigin = PeerEventSource(2) { _, _ -> }
    val pendingInput = Input(listOf(Button.B), emptyList())
    hostBus.post(
        LinkedController.RemoteButtonStateEvent(
            pendingResetFrame,
            pendingInput,
            player = 2,
            source = resetOrigin,
        ))
    hostBus.post(
        ReceivedRemoteResetEvent(pendingResetFrame, player = 2, source = resetOrigin))
    host.runFrame()
    clientBus.post(
        LinkedController.RemoteButtonStateEvent(
            pendingResetFrame,
            pendingInput,
            player = 2,
            source = serverSource,
        ))
    clientBus.post(
        ReceivedRemoteResetEvent(pendingResetFrame, player = 2, source = serverSource))
    val advancedResetCheckpoint = deliverCheckpoint(listOf(0, 1, 2, 3))
    assertEquals(pendingResetFrame + 1, advancedResetCheckpoint.frame)

    // Normal churn immediately after formation remains admissible on the same server source.
    hostBus.post(ServerPlayerDisconnectedEvent(3))
    host.runFrame()
    deliverCheckpoint(listOf(0, 1, 2))

    hostBus.post(peerState(0, PeerEventSource(3) { _, _ -> }).copy(player = 3))
    host.runFrame()
    deliverCheckpoint(listOf(0, 1, 2, 3))

    clientProperties.playerInputSource.openSource(0).update(setOf(Button.A))
    clientProperties.playerInputSource.openSource(2).update(setOf(Button.START))
    client.runFrame()
    assertTrue(
        client.mainEffectivePressedButtons().isEmpty(),
        "a checkpoint replacement must keep the local linked machine detached from the hub",
    )
    assertTrue(client.releasedInputSourceAssignments().filterNotNull().all { it })

    assertTrue(failures.isEmpty())
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
  fun queueAdmissionAcceptsEveryLegalStateBoundaryAndRejectsAggregatePlusOne() {
    val eventBus = EventBusImpl()
    LinkedController(eventBus, EmulatorProperties(), null)
    val maxRom = ByteArray(StateLimits.ROM.decodedBytes)

    fun state(
        rom: ByteArray,
        battery: ByteArray? = null,
        source: PeerEventSource,
    ) =
        PeerLoadedGameEvent(
            rom,
            battery,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
            source = source,
        )

    val romSource = PeerEventSource(1) { _, _ -> }
    eventBus.post(state(maxRom, source = romSource))
    eventBus.post(PeerEventSourceDisconnectedEvent(romSource))

    val boundarySource = PeerEventSource(1) { _, _ -> }
    eventBus.post(
        SessionCheckpointEvent(
            0,
            listOf(
                state(maxRom, source = boundarySource),
                state(maxRom, source = boundarySource),
            ),
            boundarySource,
        ))
    eventBus.post(PeerEventSourceDisconnectedEvent(boundarySource))

    val oversizedSource = PeerEventSource(1) { _, _ -> }
    assertFailsWith<EventQueue.EventQueueFullException> {
      eventBus.post(
          SessionCheckpointEvent(
              0,
              listOf(
                  state(maxRom, source = oversizedSource),
                  state(maxRom, byteArrayOf(0), oversizedSource),
              ),
              oversizedSource,
          ))
    }
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
    eventBus.post(
        PeerLoadedGameEvent(
            ROM_BYTES,
            null,
            null,
            GAMEBOY_TYPE,
            BOOTSTRAP_MODE,
            0,
        ))
    sut.runFrame()

    repeat(3) {
      eventBus.post(
          ReceivedRemoteResetEvent(sut.currentFrame(), player = 1, source = offender))
      eventBus.post(peerState(sut.currentFrame(), offender))
    }
    sut.runFrame()

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertEquals(StateLimits.NETPLAY_REPLAY_WORK_FRAMES, sut.lastDispatchReplayFrames)
    assertEquals(2, sut.activeSessionCount())

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
  fun streamingCurrentFrameStopAndReloadChangesAreBoundedAcrossDispatches() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val offender = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    sut.runFrame()

    eventBus.register<ValidatedPeerStateEvent> { validated ->
      if (validated.event.source === offender) {
        eventBus.post(
            ReceivedRemoteStopEvent(sut.currentFrame(), player = 1, source = offender))
      }
    }
    eventBus.register<ValidatedPeerStopEvent> { validated ->
      if (validated.event.source === offender) {
        eventBus.post(peerState(sut.currentFrame(), offender))
      }
    }
    eventBus.post(peerState(sut.currentFrame(), offender))
    repeat(12) {
      if (failures.isEmpty()) sut.runFrame()
    }

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertTrue(sut.currentFrame() > 1, "bounded dispatches must continue advancing frames")

    val healthyFailures = mutableListOf<ProtocolErrorReason>()
    val healthy = PeerEventSource(1) { reason, _ -> healthyFailures += reason }
    eventBus.post(peerState(sut.currentFrame(), healthy))
    sut.runFrame()
    assertTrue(healthyFailures.isEmpty(), "only the streaming source should be rejected")
    assertEquals(2, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun repeatedStopOfAnEmptyPortIsACheapNoOp() {
    val eventBus = EventBusImpl()
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val failures = mutableListOf<ProtocolErrorReason>()
    val source = PeerEventSource(1) { reason, _ -> failures += reason }
    eventBus.post(LoadRomEvent(ROM))
    sut.runFrame()

    repeat(100) {
      eventBus.post(ReceivedRemoteStopEvent(sut.currentFrame(), player = 1, source = source))
    }
    sut.runFrame()

    assertTrue(failures.isEmpty())
    assertEquals(0, sut.lastDispatchReplayFrames)
    assertEquals(1, sut.activeSessionCount())
    eventBus.close()
  }

  @Test
  fun streamingLeapingCheckpointsCannotRefillTheirOwnWorkBudget() {
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
    val offender = PeerEventSource(0) { reason, _ -> failures += reason }
    var acceptedCheckpoints = 0
    eventBus.register<ValidatedPeerCheckpointEvent> { validated ->
      if (validated.event.source === offender) {
        acceptedCheckpoints++
        eventBus.post(
            SessionCheckpointEvent(
                validated.event.frame + StateLimits.NETPLAY_STATE_CHANGE_FIXED_WORK,
                emptyList(),
                offender,
            ))
      }
    }
    eventBus.post(SessionCheckpointEvent(0, emptyList(), offender))
    repeat(20) {
      if (failures.isEmpty()) sut.runFrame()
    }

    assertEquals(listOf(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK), failures)
    assertEquals(9, acceptedCheckpoints)
    assertEquals(
        acceptedCheckpoints.toLong() + 1,
        sut.meteredWorkFrames(),
        "bounded dispatches must continue running emulator frames",
    )

    val healthyFailures = mutableListOf<ProtocolErrorReason>()
    val healthy = PeerEventSource(0) { reason, _ -> healthyFailures += reason }
    eventBus.post(SessionCheckpointEvent(sut.currentFrame(), emptyList(), healthy))
    sut.runFrame()
    assertTrue(healthyFailures.isEmpty(), "only the streaming source should be rejected")
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
    val properties = EmulatorProperties()
    val sut =
        LinkedController(
            eventBus,
            properties,
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
    assertEquals(listOf(true, true, true, true), sut.releasedInputSourceAssignments())
    eventBus.drainAsyncEvents()
    localInputs.clear()

    val physicalP1 = properties.playerInputSource.openSource(0)
    val physicalP3 = properties.playerInputSource.openSource(2)
    physicalP1.update(setOf(Button.A))
    physicalP3.update(setOf(Button.START))
    sut.runFrame()
    eventBus.drainAsyncEvents()
    assertTrue(
        sut.mainEffectivePressedButtons().isEmpty(),
        "a linked machine must never sample the asynchronously updated desktop hub",
    )
    assertTrue(localInputs.isEmpty(), "direct hub mutation must not create frame-owned input")

    eventBus.post(LogicalPlayerButtonPressEvent(2, Button.A))
    sut.runFrame()
    eventBus.drainAsyncEvents()
    assertTrue(localInputs.isEmpty(), "local SGB P3 must not become linked emulator player input")
    assertTrue(sut.mainEffectivePressedButtons().isEmpty())

    eventBus.post(LogicalPlayerButtonPressEvent(0, Button.B))
    assertTrue(
        sut.mainEffectivePressedButtons().isEmpty(),
        "logical desktop P1 must wait for the linked frame event queue",
    )
    sut.runFrame()
    eventBus.drainAsyncEvents()
    val local = localInputs.poll(5, TimeUnit.SECONDS)
    assertEquals(0, local.player)
    assertEquals(listOf(Button.B), local.input.pressedButtons)
    assertEquals(setOf(Button.B), sut.mainEffectivePressedButtons())
    assertTrue(localInputs.isEmpty(), "logical P1 must be applied exactly once")

    // Historical/agent callers still use the direct legacy event API. Its linked behavior is
    // unchanged and remains frame-owned for ordinary DMG/CGB as well as SGB sessions.
    eventBus.post(ButtonPressEvent(Button.SELECT))
    sut.runFrame()
    eventBus.drainAsyncEvents()
    val legacy = localInputs.poll(5, TimeUnit.SECONDS)
    assertEquals(listOf(Button.SELECT), legacy.input.pressedButtons)
    assertEquals(setOf(Button.B, Button.SELECT), sut.mainEffectivePressedButtons())

    eventBus.post(
        LinkedController.RemoteButtonStateEvent(0, Input(listOf(Button.A), emptyList()), 1))
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(0, Input(listOf(Button.START), emptyList()), 3))
    sut.runFrame()

    assertTrue(replayed.any { it.gameboy == 1 && it.button == Button.A })
    assertTrue(replayed.any { it.gameboy == 3 && it.button == Button.START })
    assertEquals(listOf(true, true, true, true), sut.releasedInputSourceAssignments())
    assertEquals(
        setOf(Button.B, Button.SELECT),
        sut.mainEffectivePressedButtons(),
        "rollback replay must retain frame-owned P1 without consulting the physical hub",
    )

    val previousFrame = sut.stateHistory.getHead().frame
    eventBus.post(ReceivedRemoteStopEvent(previousFrame, player = 3))
    sut.runFrame()
    assertEquals(3, sut.activeSessionCount())
    assertTrue(sut.releasedInputSourceAssignments().filterNotNull().all { it })
    assertTrue(sut.stateHistory.getHead().frame > previousFrame)
    eventBus.close()
  }

  @Test
  fun ordinaryDmgLinkedInputRemainsFrameOwnedForLogicalAndLegacyP1() {
    val romBytes = ByteArray(0x8000)
    val romFile = Files.createTempFile("coffee-gb-linked-dmg", ".gb").toFile()
    romFile.writeBytes(romBytes)
    val eventBus = EventBusImpl()
    try {
      val properties = EmulatorProperties()
      properties.properties.setProperty(
          EmulatorProperties.Key.DmgGamesType.propertyName,
          GameboyType.DMG.name,
      )
      val sut = LinkedController(eventBus, properties, null)
      sut.timingTicker.disabled = true
      val localInputs = LinkedBlockingQueue<LinkedController.LocalButtonStateEvent>()
      eventBus.register<LinkedController.LocalButtonStateEvent> { localInputs.add(it) }

      eventBus.post(LoadRomEvent(romFile))
      eventBus.post(
          PeerLoadedGameEvent(
              romBytes,
              null,
              null,
              GameboyType.DMG,
              Gameboy.BootstrapMode.SKIP,
              0,
          ))
      sut.runFrame()
      eventBus.drainAsyncEvents()
      localInputs.clear()
      assertEquals(listOf(true, true), sut.releasedInputSourceAssignments())

      properties.playerInputSource.openSource(0).update(setOf(Button.A))
      sut.runFrame()
      assertTrue(sut.mainEffectivePressedButtons().isEmpty())

      eventBus.post(LogicalPlayerButtonPressEvent(0, Button.B))
      assertTrue(sut.mainEffectivePressedButtons().isEmpty())
      sut.runFrame()
      eventBus.drainAsyncEvents()
      assertEquals(setOf(Button.B), sut.mainEffectivePressedButtons())
      assertEquals(listOf(Button.B), localInputs.poll(5, TimeUnit.SECONDS).input.pressedButtons)

      eventBus.post(ButtonPressEvent(Button.START))
      sut.runFrame()
      assertEquals(setOf(Button.B, Button.START), sut.mainEffectivePressedButtons())
    } finally {
      eventBus.close()
      romFile.delete()
    }
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

  @Test
  fun normalLinkedStateRestoresHeldInputAndContinuesDeterministically() {
    val (eventBus, sut) = configuredController(LinkMode.NORMAL, 2)
    eventBus.post(ButtonPressEvent(Button.A))
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            sut.currentFrame(),
            Input(listOf(Button.B), emptyList()),
            player = 1,
        ))
    sut.runFrame()
    val captured = sut.captureDetachedState()
    assertEquals(4, captured.players.size)
    assertTrue(captured.players.drop(2).all { it.session == null })
    assertEquals(setOf(Button.A), sut.heldButtonStates()[0])
    assertEquals(setOf(Button.B), sut.heldButtonStates()[1])

    sut.runFrame()
    val expected = sut.captureDetachedState()
    eventBus.post(ButtonReleaseEvent(Button.A))
    sut.runFrame()

    sut.restoreDetachedState(captured)
    sut.runFrame()
    assertEquals(expected, sut.captureDetachedState())
    assertTrue(sut.releasedInputSourceAssignments().filterNotNull().all { it })
    eventBus.close()
  }

  @Test
  fun fourPlayerLinkedStateRestoresAtomicallyAndRejectsIncoherentAdapterCopies() {
    val (eventBus, sut) = configuredController(LinkMode.FOUR_PLAYER_ADAPTER, 3)
    eventBus.post(ButtonPressEvent(Button.SELECT))
    eventBus.post(
        LinkedController.RemoteButtonStateEvent(
            sut.currentFrame(),
            Input(listOf(Button.START), emptyList()),
            player = 2,
        ))
    sut.runFrame()
    val target = sut.captureDetachedState()
    assertEquals(setOf(Button.SELECT), sut.heldButtonStates()[0])
    assertEquals(setOf(Button.START), sut.heldButtonStates()[2])
    val activeStates = target.players.mapNotNull { it.session }
    assertEquals(1, activeStates.map { it.serialState }.distinct().size)

    sut.runFrame()
    val expectedContinuation = sut.captureDetachedState()
    sut.runFrame()
    sut.restoreDetachedState(target)
    sut.runFrame()
    assertEquals(expectedContinuation, sut.captureDetachedState())
    assertTrue(sut.releasedInputSourceAssignments().filterNotNull().all { it })

    val coherent = sut.captureDetachedState()
    val playerOne = assertNotNull(coherent.players[1].session)
    val adapter = playerOne.serialState as RecordState
    val inconsistentAdapter =
        RecordState(
            adapter.typeId,
            adapter.fields.map { field ->
              if (field.name == "packetByte") {
                StateField(field.name, Int32State((field.value as Int32State).value + 1))
              } else {
                field
              }
            },
        )
    val inconsistentSession = copySession(playerOne, serialState = inconsistentAdapter)
    val inconsistent =
        linkedCopy(
            coherent,
            players =
                coherent.players.map {
                  if (it.player == 1) LinkedPlayerState(1, inconsistentSession) else it
                },
        )
    assertFailsWith<StateApplyException> { sut.restoreDetachedState(inconsistent) }
    assertEquals(coherent, sut.captureDetachedState())

    sut.runFrame()
    val beforeInjectedFailure = sut.captureDetachedState()
    assertFailsWith<StateApplyException> {
      sut.restoreDetachedState(coherent) { player, stage ->
        if (player == 1 && stage == ApplyStage.AFTER_MACHINE_MUTATION) {
          throw IllegalStateException("injected linked restore failure")
        }
      }
    }
    assertEquals(beforeInjectedFailure, sut.captureDetachedState())
    eventBus.close()
  }

  @Test
  fun malformedPeerCheckpointPreparationLeavesControllerGroupAndHistoryUnchanged() {
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
    eventBus.post(LoadRomEvent(ROM))
    sut.runFrame()
    for (player in listOf(0, 2)) {
      eventBus.post(
          PeerLoadedGameEvent(
              ROM_BYTES,
              null,
              null,
              GAMEBOY_TYPE,
              BOOTSTRAP_MODE,
              sut.currentFrame(),
              player = player,
          ))
      sut.runFrame()
    }
    assertEquals(3, sut.activeSessionCount())

    val failures = mutableListOf<ProtocolErrorReason>()
    val source = PeerEventSource(0) { reason, _ -> failures += reason }
    val states =
        sut.encodedSessionStates().mapIndexedNotNull { player, bytes ->
          bytes?.let {
            PeerLoadedGameEvent(
                rom = if (player == 2) byteArrayOf(1) else ROM_BYTES,
                battery = null,
                portableState = StateCodec.decode(it),
                gameboyType = GAMEBOY_TYPE,
                bootstrapMode = BOOTSTRAP_MODE,
                frame = sut.currentFrame(),
                player = player,
                heldButtons = sut.heldButtonStates()[player] ?: emptySet(),
                source = source,
            )
          }
        }
    val beforeState = sut.captureDetachedState()
    val beforeEncoded = sut.encodedSessionStates()
    val beforeHistory = sut.stateHistory.captureSnapshot()
    val beforeConfigs = privateList(sut, "configs")
    val beforeRoms = privateByteArrays(sut, "romBuffers")
    val beforeSlots = privateByteArrays(sut, "slotRomBuffers")
    val beforeBatteries = privateByteArrays(sut, "batteryBuffers")
    val beforeSources = sut.releasedInputSourceAssignments()

    eventBus.post(SessionCheckpointEvent(sut.currentFrame(), states, source))
    dispatchOnly(sut)

    assertEquals(listOf(ProtocolErrorReason.INVALID_PORTABLE_STATE), failures)
    assertEquals(beforeState, sut.captureDetachedState())
    assertEncodedStatesEqual(beforeEncoded, sut.encodedSessionStates())
    assertEquals(beforeHistory, sut.stateHistory.captureSnapshot())
    beforeConfigs.zip(privateList(sut, "configs")).forEach { (expected, actual) ->
      assertTrue(expected === actual)
    }
    assertByteArraysEqual(beforeRoms, privateByteArrays(sut, "romBuffers"))
    assertByteArraysEqual(beforeSlots, privateByteArrays(sut, "slotRomBuffers"))
    assertByteArraysEqual(beforeBatteries, privateByteArrays(sut, "batteryBuffers"))
    assertEquals(beforeSources, sut.releasedInputSourceAssignments())
    assertTrue(sut.releasedInputSourceAssignments().filterNotNull().all { it })
    eventBus.close()
  }

  @Test
  fun linkedStateRejectsMalformedTopologyBeforeMutation() {
    val (eventBus, sut) = configuredController(LinkMode.FOUR_PLAYER_ADAPTER, 2)
    val valid = sut.captureDetachedState()
    val session = assertNotNull(valid.players[0].session)
    val invalidStates =
        listOf(
            linkedCopy(valid, frame = -1),
            linkedCopy(valid, localPlayer = 1),
            linkedCopy(valid, topology = LinkedTopologyState.NORMAL),
            linkedCopy(valid, players = valid.players.dropLast(1)),
            linkedCopy(
                valid,
                players =
                    valid.players.map {
                      if (it.player == 1) LinkedPlayerState(0, it.session) else it
                    },
            ),
            linkedCopy(
                valid,
                players =
                    valid.players.map {
                      if (it.player == 1) LinkedPlayerState(1, null) else it
                    },
            ),
            linkedCopy(
                valid,
                players =
                    valid.players.map {
                      if (it.player == 0) {
                        LinkedPlayerState(
                            0,
                            copySession(
                                session,
                                serialPeripheral = SerialPeripheralState.PEER_TO_PEER,
                            ),
                        )
                      } else {
                        it
                      }
                    },
            ),
        )
    invalidStates.forEach { invalid ->
      val before = sut.captureDetachedState()
      assertFailsWith<StateApplyException> { sut.restoreDetachedState(invalid) }
      assertEquals(before, sut.captureDetachedState())
    }
    eventBus.close()
  }

  private class TrackingConsole : Console() {
    @Volatile var attachedGameboy: Gameboy? = null

    override fun setGameboy(gameboy: Gameboy?) {
      attachedGameboy = gameboy
    }
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

    fun dispatchOnly(controller: LinkedController) {
      LinkedController::class.java.getDeclaredField("eventQueue").also { field ->
        field.isAccessible = true
        (field.get(controller) as EventQueue).dispatch()
      }
    }

    fun awaitPendingCapture(target: V9LinkedControllerTarget) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (target.pendingCaptureCount() == 0) {
        if (System.nanoTime() >= deadline) throw AssertionError("capture was not enqueued")
        Thread.yield()
      }
    }

    fun captureGeneration(
        target: V9LinkedControllerTarget,
        controller: LinkedController,
    ): eu.rekawek.coffeegb.controller.network.v9.V9TargetGeneration {
      val executor = Executors.newSingleThreadExecutor()
      return try {
        val future = executor.submit<eu.rekawek.coffeegb.controller.network.v9.V9TargetGeneration> {
          target.captureGeneration()
        }
        awaitPendingCapture(target)
        dispatchOnly(controller)
        future.get(5, TimeUnit.SECONDS)
      } finally {
        executor.shutdownNow()
      }
    }

    @Suppress("UNCHECKED_CAST")
    fun privateList(controller: LinkedController, name: String): List<Any?> =
        LinkedController::class.java.getDeclaredField(name).let { field ->
          field.isAccessible = true
          (field.get(controller) as List<Any?>).toList()
        }

    fun privateByteArrays(controller: LinkedController, name: String): List<ByteArray?> =
        privateList(controller, name).map { (it as ByteArray?)?.clone() }

    fun assertByteArraysEqual(expected: List<ByteArray?>, actual: List<ByteArray?>) {
      expected.indices.forEach { index ->
        val expectedBytes = expected[index]
        val actualBytes = actual[index]
        if (expectedBytes == null || actualBytes == null) {
          assertEquals(expectedBytes, actualBytes)
        } else {
          assertContentEquals(expectedBytes, actualBytes)
        }
      }
    }

    fun assertEncodedStatesEqual(expected: List<ByteArray?>, actual: List<ByteArray?>) =
        assertByteArraysEqual(expected, actual)

    fun configuredController(
        mode: LinkMode,
        activePlayers: Int,
    ): Pair<EventBusImpl, LinkedController> {
      val eventBus = EventBusImpl()
      val controller =
          LinkedController(eventBus, EmulatorProperties(), null, mode, localPlayer = 0).also {
            it.timingTicker.disabled = true
          }
      eventBus.post(LoadRomEvent(ROM))
      controller.runFrame()
      for (player in 1 until activePlayers) {
        eventBus.post(
            PeerLoadedGameEvent(
                ROM_BYTES,
                null,
                null,
                GAMEBOY_TYPE,
                BOOTSTRAP_MODE,
                controller.currentFrame(),
                player = player,
            ))
        controller.runFrame()
      }
      assertEquals(activePlayers, controller.activeSessionCount())
      return eventBus to controller
    }

    fun copySession(
        state: SessionState,
        serialPeripheral: SerialPeripheralState = state.serialPeripheral,
        serialState: StateValue = state.serialState,
    ) =
        SessionState(
            state.machine,
            serialPeripheral,
            serialState,
            state.serialRuntime,
            state.heldButtons,
        )

    fun linkedCopy(
        state: LinkedSessionState,
        frame: Long = state.frame,
        localPlayer: Int = state.localPlayer,
        topology: LinkedTopologyState = state.topology,
        players: Collection<LinkedPlayerState> = state.players,
    ) = LinkedSessionState(frame, localPlayer, topology, players)

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

    fun checkpointEvent(
        value: LinkedController.SessionStateReadyEvent,
        source: PeerEventSource,
    ) =
        SessionCheckpointEvent(
            value.frame,
            value.states.map { state ->
              PeerLoadedGameEvent(
                  rom = state.romFile,
                  battery = state.batteryFile,
                  portableState = state.portableState?.let(StateCodec::decode),
                  gameboyType = state.gameboyType,
                  bootstrapMode = state.bootstrapMode,
                  frame = state.frame,
                  cgb0Revision = state.cgb0Revision,
                  player = state.player,
                  heldButtons = state.heldButtons,
                  slotRom = state.slotRomFile,
                  mealybugDmgBlob = state.mealybugDmgBlob,
                  codeBreakerRumble = state.codeBreakerRumble,
                  displaySgbBorder = state.displaySgbBorder,
                  source = source,
              )
            },
            source,
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

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
          "%02x".format(it.toInt() and 0xff)
        }
  }
}
