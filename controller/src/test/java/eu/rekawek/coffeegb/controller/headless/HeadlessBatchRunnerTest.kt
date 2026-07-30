package eu.rekawek.coffeegb.controller.headless

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.replay.ReplayFile
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackStatus
import eu.rekawek.coffeegb.controller.replay.ReplayPlayer
import eu.rekawek.coffeegb.controller.replay.ReplayRecorder
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingOptions
import eu.rekawek.coffeegb.controller.replay.ReplayStateHashes
import eu.rekawek.coffeegb.controller.replay.ReplaySubsystem
import eu.rekawek.coffeegb.controller.replay.ReplayRuntime
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.Test

class HeadlessBatchRunnerTest {

  @Test
  fun exactTickAndFrameLimitsReturnOneCoherentTerminalInspection() {
    val inspection =
        DebugInspectionRequest(
            emptyList(),
            listOf(DebugMemoryRequest(DebugAddressSpace.ROM, 0x100, 3)),
            setOf(DebugInspectionSection.AUDIO),
        )
    val partial =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Ticks(17), inspection = inspection),
        )

    assertEquals(HeadlessTerminationReason.TICK_LIMIT, partial.reason)
    assertEquals(HeadlessPosition(17, 0, 17), partial.position)
    assertEquals(partial.position.completedTicks, partial.inspection.snapshot().masterTick())
    assertEquals(partial.position.frame, partial.inspection.snapshot().frame())
    assertEquals(partial.position.ticksIntoFrame, partial.inspection.snapshot().framePosition())
    assertTrue(partial.inspection.snapshot().execution().retiredInstructions() > 0)
    assertContentEquals(byteArrayOf(0x00, 0x18, 0xfd.toByte()), partial.inspection.memoryBlocks()[0].let {
      ByteArray(it.length()) { index -> it.byteAt(index) }
    })
    assertTrue(partial.inspection.audio().isPresent)

    val frame =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Frames(1)),
        )
    assertEquals(HeadlessTerminationReason.FRAME_LIMIT, frame.reason)
    assertEquals(configuration().clockSpec.controllerTicksPerFrame().toLong(), frame.position.completedTicks)
    assertEquals(1, frame.position.frame)
    assertEquals(0, frame.position.ticksIntoFrame)
  }

  @Test
  fun strictAbsoluteInputsAreCopiedAndAffectCanonicalInputHash() {
    val mutable =
        mutableListOf(
            HeadlessInputTransition(0, 0, JoypadButtonMask.A),
            HeadlessInputTransition(9, 0, 0),
        )
    val request = HeadlessRunRequest(HeadlessExecutionLimit.Ticks(12), mutable)
    mutable.clear()

    val scripted = HeadlessBatchRunner.run(configuration(), request)
    val released =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Ticks(12)),
        )
    assertEquals(2, request.inputs.size)
    assertFalse(scripted.hashes.input.contentEquals(released.hashes.input))

    assertFailsWith<IllegalArgumentException> {
      HeadlessBatchRunner.run(
          configuration(),
          HeadlessRunRequest(
              HeadlessExecutionLimit.Ticks(2),
              listOf(HeadlessInputTransition(0, 0, 0)),
          ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      HeadlessBatchRunner.run(
          configuration(),
          HeadlessRunRequest(
              HeadlessExecutionLimit.Ticks(2),
              listOf(
                  HeadlessInputTransition(1, 0, JoypadButtonMask.A),
                  HeadlessInputTransition(0, 1, JoypadButtonMask.B),
              ),
          ),
      )
    }
  }

  @Test
  fun masterTickBreakpointStopsBeforeTheRequestedLimit() {
    val breakpoint =
        DebugBreakpoint(
            DebugBreakpointId(41),
            true,
            DebugCounterCondition.atMasterTick(7),
        )
    val result =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(
                HeadlessExecutionLimit.Ticks(100),
                breakpoint = breakpoint,
            ),
        )

    assertEquals(HeadlessTerminationReason.BREAKPOINT, result.reason)
    assertEquals(7, result.position.completedTicks)
    val hit = assertNotNull(result.breakpointHit)
    assertEquals(DebugBreakpointId(41), hit.breakpointId())
    assertEquals(7, hit.matchMasterTick())
    assertEquals(result.inspection.snapshot(), hit.snapshot())
  }

  @Test
  fun replaySuccessAndDivergenceMatchReplayPlayerSemantics() {
    val configuration = configuration()
    val replay = recordReplay(configuration, 128)
    val success =
        HeadlessBatchRunner.replay(
            replay,
            configuration,
            HeadlessReplayRequest(maximumTicks = 128),
        )
    assertEquals(HeadlessTerminationReason.REPLAY_COMPLETED, success.reason)
    assertIs<ReplayPlaybackStatus.Completed>(success.replayStatus)
    assertEquals(replay.checkpoints.last().hashes, success.hashes)

    val expected = replay.checkpoints.last().hashes
    val corruptFull = expected.full.also { it[0] = (it[0].toInt() xor 1).toByte() }
    val corruptHashes =
        ReplayStateHashes(
            corruptFull,
            expected.cpu,
            expected.memory,
            expected.ppu,
            expected.apu,
            expected.mapper,
            expected.serial,
            expected.input,
        )
    val divergent =
        ReplayFile(
            replay.identity,
            replay.initialConditions,
            replay.inputs,
            replay.checkpoints.dropLast(1) + replay.checkpoints.last().copy(hashes = corruptHashes),
            replay.metadata,
            replay.embeddedState,
        )
    val direct =
        ReplayPlayer.open(divergent, configuration).use { player ->
          assertIs<ReplayPlaybackStatus.Diverged>(player.playToEnd(128))
        }
    val sameTickBreakpoint =
        DebugBreakpoint(
            DebugBreakpointId(9),
            true,
            DebugCounterCondition.atMasterTick(128),
        )
    val batch =
        HeadlessBatchRunner.replay(
            divergent,
            configuration,
            HeadlessReplayRequest(128, breakpoint = sameTickBreakpoint),
        )
    val batchStatus = assertIs<ReplayPlaybackStatus.Diverged>(batch.replayStatus)
    assertEquals(HeadlessTerminationReason.REPLAY_DIVERGED, batch.reason)
    assertEquals(direct.divergence, batchStatus.divergence)
    assertEquals(batchStatus.divergence.actual, batch.hashes)
    assertTrue(ReplaySubsystem.FULL in batchStatus.divergence.mismatchedSubsystems)
    assertEquals(null, batch.breakpointHit)
  }

  @Test
  fun exactPartialTickPcmIsBoundedAndByteStable() {
    val capture = HeadlessCaptureOptions(pcm16 = true, maximumPcmBytes = 4)
    val beforeFirstSample =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(
                HeadlessExecutionLimit.Ticks(95),
                capture = HeadlessCaptureOptions(pcm16 = true, maximumPcmBytes = 0),
            ),
        )
    assertEquals(0, assertNotNull(beforeFirstSample.pcm).sampleFrames)

    val first =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Ticks(96), capture = capture),
        )
    val second =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Ticks(96), capture = capture),
        )
    val firstPcm = assertNotNull(first.pcm)
    val secondPcm = assertNotNull(second.pcm)
    assertEquals(1, firstPcm.sampleFrames)
    assertEquals(96, firstPcm.completedTicks)
    assertContentEquals(firstPcm.bytes, secondPcm.bytes)

    assertFailsWith<IllegalArgumentException> {
      HeadlessBatchRunner.run(
          configuration(),
          HeadlessRunRequest(
              HeadlessExecutionLimit.Ticks(96),
              capture = HeadlessCaptureOptions(pcm16 = true, maximumPcmBytes = 3),
          ),
      )
    }
  }

  @Test
  fun replayPcmPreflightUsesTheRecordingEndRatherThanTheLargerCallerBudget() {
    val configuration = configuration()
    val replay = recordReplay(configuration, 128)
    val result =
        HeadlessBatchRunner.replay(
            replay,
            configuration,
            HeadlessReplayRequest(
                maximumTicks = HeadlessBatchRunner.MAXIMUM_TICKS,
                capture = HeadlessCaptureOptions(pcm16 = true, maximumPcmBytes = 8),
            ),
        )

    assertEquals(HeadlessTerminationReason.REPLAY_COMPLETED, result.reason)
    assertEquals(1, assertNotNull(result.pcm).sampleFrames)
  }

  @Test
  fun latestDmgCgbAndSgbFramesAreDetachedAndDeterministic() {
    val profiles =
        listOf(
            HardwareProfileRegistry.DMG to (160 to 144),
            HardwareProfileRegistry.CGB to (160 to 144),
            HardwareProfileRegistry.SGB to (256 to 224),
        )
    profiles.forEach { (profile, dimensions) ->
      val request =
          HeadlessRunRequest(
              HeadlessExecutionLimit.Frames(1),
              capture = HeadlessCaptureOptions(latestFrame = true),
          )
      val first = HeadlessBatchRunner.run(configuration(profile), request)
      val second = HeadlessBatchRunner.run(configuration(profile), request)
      val firstFrame = assertNotNull(first.latestFrame, profile.id())
      val secondFrame = assertNotNull(second.latestFrame, profile.id())
      assertEquals(dimensions.first, firstFrame.image.width)
      assertEquals(dimensions.second, firstFrame.image.height)
      assertTrue(firstFrame.completedTicks in 1..first.position.completedTicks)
      assertEquals(firstFrame, secondFrame)
    }
  }

  @Test
  fun rtcEpochIsExplicitAndEachEpochReplaysDeterministically() {
    val epochA = 946_684_800_000L
    val epochB = epochA + 123_456L
    fun execute(epoch: Long) =
        HeadlessBatchRunner.run(
            configuration(rtc = true),
            HeadlessRunRequest(
                HeadlessExecutionLimit.Ticks(257),
                rtcEpochMillis = epoch,
            ),
        )

    val firstA = execute(epochA)
    val secondA = execute(epochA)
    val firstB = execute(epochB)
    val secondB = execute(epochB)
    assertEquals(firstA.hashes, secondA.hashes)
    assertEquals(firstB.hashes, secondB.hashes)
    assertEquals(epochA, firstA.rtcEpochMillis)
    assertEquals(epochB, firstB.rtcEpochMillis)
  }

  @Test
  fun ownerBoundaryAndMachineRejectCrossThreadAccessAndAlwaysDetachMedia() {
    val caller = Thread.currentThread()
    val owner = HeadlessExecutionOwner.execute { Thread.currentThread() }
    assertNotSame(caller, owner)
    assertTrue(owner.name.startsWith("coffee-gb-headless-"))

    val isolated =
        ReplayRuntime.configuration(
            configuration(),
            VirtualTimeSource(),
            PlayerInputSource.RELEASED,
        )
    val machine = HeadlessMachineSession(ReplayRuntime.session(isolated, false))
    machine.enableMedia(
        HeadlessCaptureOptions(pcm16 = true, maximumPcmBytes = 4),
        maximumTicks = 96,
    )
    val crossThreadFailure = AtomicReference<Throwable>()
    Thread {
          try {
            machine.tick()
          } catch (failure: Throwable) {
            crossThreadFailure.set(failure)
          }
        }
        .apply { start() }
        .join()
    assertIs<IllegalStateException>(crossThreadFailure.get())

    val sound = machine.session.gameboy.sound
    machine.close()
    val replacement = eu.rekawek.coffeegb.core.sound.SoundOutputObserver { _, _ -> }
    assertTrue(sound.attachOutputObserver(replacement))
    assertTrue(sound.detachOutputObserver(replacement))
  }

  @Test
  fun resultRomIdentityDoesNotExposeMutableDigestStorage() {
    val result =
        HeadlessBatchRunner.run(
            configuration(),
            HeadlessRunRequest(HeadlessExecutionLimit.Ticks(0)),
        )
    assertEquals("HEADLESS-TEST", result.rom.title)
    assertEquals(0x8000, result.rom.sizeBytes)
    assertEquals("dmg", result.rom.profileId)
    val digest = result.rom.sha256
    digest[0] = (digest[0].toInt() xor 1).toByte()
    assertFalse(digest.contentEquals(result.rom.sha256))
  }

  private fun recordReplay(
      configuration: Gameboy.GameboyConfiguration,
      ticks: Int,
  ): ReplayFile =
      Session(configuration, EventBusImpl(null, null, false), null).use { session ->
        ReplayRecorder.start(
                session,
                ReplayRecordingOptions(
                    rtcEpochMillis = ReplayRecordingOptions.DEFAULT_RTC_EPOCH_MILLIS,
                ),
            )
            .use { recorder ->
              repeat(ticks) { recorder.tick() }
              recorder.finish()
            }
      }

  private fun configuration(
      profile: HardwareProfile = HardwareProfileRegistry.DMG,
      rtc: Boolean = false,
  ): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(syntheticRom(rtc)))
          .setHardwareProfile(profile)
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          .setSupportBatterySave(false)
          .setBatteryStorage(null, null)
          .setRtcTimeSource(VirtualTimeSource(ReplayRecordingOptions.DEFAULT_RTC_EPOCH_MILLIS))

  private fun syntheticRom(rtc: Boolean): ByteArray =
      ByteArray(0x8000).also { bytes ->
        "HEADLESS-TEST".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x100] = 0x00
        bytes[0x101] = 0x18
        bytes[0x102] = 0xfd.toByte()
        bytes[0x143] = 0
        bytes[0x146] = 0
        bytes[0x147] = if (rtc) 0x10 else 0
        bytes[0x148] = 0
        bytes[0x149] = if (rtc) 0x03 else 0
        var checksum = 0
        for (address in 0x134..0x14c) {
          checksum = (checksum - (bytes[address].toInt() and 0xff) - 1) and 0xff
        }
        bytes[0x14d] = checksum.toByte()
      }
}
