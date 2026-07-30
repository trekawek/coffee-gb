package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.state.Int32MapEntry
import eu.rekawek.coffeegb.controller.state.Int32MapState
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.ListState
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.ObjectArrayState
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateField
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateValue
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRecorderPlayerTest {

  @Test
  fun recordsBothInputPhasesAndReplaysToTheFinalHash() {
    val rig = rig()
    rig.session.use { session ->
      ReplayRecorder.start(
              session,
              ReplayRecordingOptions(checkpointIntervalFrames = 1),
          )
          .use { recorder ->
            session.eventBus.post(ButtonPressEvent(Button.A))
            rig.p1.update(setOf(Button.LEFT))
            recorder.tick()

            session.eventBus.post(ButtonReleaseEvent(Button.A))
            rig.p1.update(setOf(Button.LEFT, Button.START))
            rig.p2.update(setOf(Button.B))
            recorder.tick()

            rig.p1.update(emptySet())
            rig.p2.update(emptySet())
            repeat(10) { recorder.tick() }

            val replay = recorder.finish()
            assertEquals(
                listOf(
                    ReplayInputPhase.LEGACY_P1_BEFORE_TICK,
                    ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE,
                    ReplayInputPhase.LEGACY_P1_BEFORE_TICK,
                    ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE,
                    ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE,
                    ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE,
                    ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE,
                ),
                replay.inputs.map { it.phase },
            )
            assertEquals(listOf(0L, 0L, 1L, 1L, 1L, 2L, 2L), replay.inputs.map { it.tick })

            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            ReplayPlayer.open(decoded, rig.configuration).use { player ->
              val result = player.playToEnd(replayDuration(decoded))
              assertTrue(result is ReplayPlaybackStatus.Completed)
              assertEquals(decoded.checkpoints.last().tick, result.position.tick)
            }
          }
    }
  }

  @Test
  fun stopsAtFirstCheckpointWhoseInputStateDiverges() {
    val rig = rig()
    val replay =
        rig.session.use { session ->
          ReplayRecorder.start(session).use { recorder ->
            session.eventBus.post(ButtonPressEvent(Button.A))
            repeat(8) { recorder.tick() }
            recorder.finish()
          }
        }
    val changedInputs =
        replay.inputs.map { record ->
          if (record.phase != ReplayInputPhase.LEGACY_P1_BEFORE_TICK) {
            record
          } else {
            record.copy(
                absoluteMask = JoypadButtonMask.B,
                changedMask = JoypadButtonMask.B,
            )
          }
        }
    val divergent =
        ReplayFile(
            replay.identity,
            replay.initialConditions,
            changedInputs,
            replay.checkpoints,
            replay.metadata,
            replay.embeddedState,
        )

    ReplayPlayer.open(divergent, rig.configuration).use { player ->
      val result = player.playToEnd(replayDuration(divergent))
      assertTrue(result is ReplayPlaybackStatus.Diverged)
      val divergence = (result as ReplayPlaybackStatus.Diverged).divergence
      assertEquals(divergent.checkpoints.first().tick, divergence.tick)
      assertTrue(ReplaySubsystem.FULL in divergence.mismatchedSubsystems)
      assertTrue(ReplaySubsystem.INPUT in divergence.mismatchedSubsystems)
    }
  }

  @Test
  fun embeddingStateRequiresAnExplicitSensitiveStateOptIn() {
    rig().session.use { session ->
      val failure =
          org.junit.Assert.assertThrows(ReplayRecordingException::class.java) {
            ReplayRecorder.start(
                session,
                ReplayRecordingOptions(initialMode = ReplayInitialMode.EMBEDDED_SESSION_STATE),
            )
          }
      assertEquals(
          ReplayRecordingReason.SENSITIVE_STATE_CONSENT_REQUIRED,
          failure.reason,
      )
    }
  }

  @Test
  fun explicitlyEmbeddedInProgressStateReplaysInAnIsolatedSession() {
    val rig = rig()
    val replay =
        rig.session.use { session ->
          repeat(17) { session.gameboy.tick() }
          ReplayRecorder.start(
                  session,
                  ReplayRecordingOptions(
                      initialMode = ReplayInitialMode.EMBEDDED_SESSION_STATE,
                      includeSensitiveInitialState = true,
                  ),
              )
              .use { recorder ->
                session.eventBus.post(ButtonPressEvent(Button.START))
                repeat(9) { recorder.tick() }
                recorder.finish()
              }
        }
    assertTrue(replay.embeddedState != null)
    ReplayPlayer.open(ReplayCodec.decode(ReplayCodec.encode(replay)), rig.configuration).use {
        player ->
      assertTrue(player.playToEnd(replayDuration(replay)) is ReplayPlaybackStatus.Completed)
    }
  }

  @Test
  fun invalidEmbeddedTargetSemanticsUseStableReplayCompatibilityFailure() {
    val rig = rig()
    val replay =
        rig.session.use { session ->
          ReplayRecorder.start(
                  session,
                  ReplayRecordingOptions(
                      initialMode = ReplayInitialMode.EMBEDDED_SESSION_STATE,
                      includeSensitiveInitialState = true,
                  ),
              )
              .use { recorder ->
                recorder.tick()
                recorder.finish()
              }
        }
    val embedded = StateCodec.decode(requireNotNull(replay.embeddedState))
    val root = (embedded.root as SessionStateRoot).session
    val invalidRoot =
        root.machine.root.replaceRecordField(
            "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState",
            "i",
            Int32State(-1),
        )
    val invalidSession =
        SessionState(
            MachineState(
                invalidRoot,
                root.machine.rtcRuntime,
                root.machine.hardware,
                root.machine.dmgFifoRuntime,
            ),
            root.serialPeripheral,
            root.serialState,
            root.serialRuntime,
            root.heldButtons,
        )
    val invalidEmbedded =
        StateCodec.encode(
            StateFile(
                embedded.identities,
                SessionStateRoot(invalidSession),
                embedded.diagnostics,
                embedded.formatVersion,
            ),
        )
    val invalidReplay =
        ReplayFile(
            replay.identity,
            replay.initialConditions,
            replay.inputs,
            replay.checkpoints,
            replay.metadata,
            invalidEmbedded,
        )

    val failure =
        org.junit.Assert.assertThrows(ReplayCompatibilityException::class.java) {
          ReplayPlayer.open(invalidReplay, rig.configuration)
        }

    assertEquals(ReplayCompatibilityReason.INVALID_EMBEDDED_STATE, failure.reason)
    assertTrue(failure.cause is StateDecodeException)
  }

  private fun RecordState.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): RecordState {
    fun replace(value: StateValue): StateValue =
        when (value) {
          is RecordState ->
              RecordState(
                  value.typeId,
                  value.fields.map { field ->
                    val owner = StateTypeRegistry.recordClassNames[value.typeId - 1] == ownerClass
                    StateField(
                        field.name,
                        if (owner && field.name == fieldName) replacement
                        else replace(field.value),
                    )
                  },
              )
          is ObjectArrayState -> ObjectArrayState(value.values.map(::replace))
          is ListState -> ListState(value.values.map(::replace))
          is Int32MapState ->
              Int32MapState(value.entries.map { Int32MapEntry(it.key, replace(it.value)) })
          else -> value
        }
    return replace(this) as RecordState
  }

  @Test
  fun bootReferenceRejectsAnAlreadyAdvancedSession() {
    rig().session.use { session ->
      session.gameboy.tick()
      val failure =
          org.junit.Assert.assertThrows(ReplayRecordingException::class.java) {
            ReplayRecorder.start(session)
          }
      assertEquals(
          ReplayRecordingReason.BOOT_REFERENCE_REQUIRES_FRESH_SESSION,
          failure.reason,
      )
    }
  }

  @Test
  fun recorderOwnershipIsExclusiveAndReleasedOnClose() {
    rig().session.use { session ->
      val first = ReplayRecorder.start(session)
      val failure =
          org.junit.Assert.assertThrows(ReplayRecordingException::class.java) {
            ReplayRecorder.start(session)
          }
      assertEquals(ReplayRecordingReason.CAPTURE_ALREADY_ACTIVE, failure.reason)

      first.close()
      ReplayRecorder.start(session).use { second ->
        second.tick()
        second.finish()
      }
    }
  }

  @Test
  fun recorderRejectsLegacyInputPostedFromInsideATick() {
    val input = CallbackInputSource()
    val configuration = configuration(input)
    Session(configuration, EventBusImpl(null, null, false), null).use { session ->
      ReplayRecorder.start(session).use { recorder ->
        input.onSample = { session.eventBus.post(ButtonPressEvent(Button.A)) }

        val failure =
            org.junit.Assert.assertThrows(ReplayRecordingException::class.java) {
              recorder.tick()
            }

        assertEquals(ReplayRecordingReason.LEGACY_INPUT_DURING_TICK, failure.reason)
      }
    }
  }

  @Test
  fun recorderReservesMutableHostServices() {
    rig().session.use { session ->
      ReplayRecorder.start(session).use { recorder ->
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
          session.setSerialEndpoint(ByteReceivingSerialEndpoint {})
        }
        recorder.tick()
        recorder.finish()
      }
    }
  }

  @Test
  fun boundedPlayerRejectsExhaustionAndNonFrameCheckpointCadence() {
    val rig = rig()
    val replay =
        rig.session.use { session ->
          ReplayRecorder.start(session).use { recorder ->
            repeat(12) { recorder.tick() }
            recorder.finish()
          }
        }

    ReplayPlayer.open(replay, rig.configuration).use { player ->
      val exhausted =
          org.junit.Assert.assertThrows(ReplayPlaybackException::class.java) {
            player.playToEnd(1)
          }
      assertEquals(ReplayPlaybackReason.EXECUTION_BUDGET_EXCEEDED, exhausted.reason)
      assertTrue(
          player.playToEnd(replayDuration(replay) - 1L) is ReplayPlaybackStatus.Completed,
      )
    }

    val invalidCadence =
        ReplayFile(
            replay.identity,
            replay.initialConditions,
            replay.inputs,
            listOf(
                ReplayCheckpoint(0, 0, replay.checkpoints.last().hashes),
                replay.checkpoints.last(),
            ),
            replay.metadata,
            replay.embeddedState,
        )
    val cadenceFailure =
        org.junit.Assert.assertThrows(ReplayPlaybackException::class.java) {
          ReplayPlayer.open(invalidCadence, rig.configuration)
        }
    assertEquals(ReplayPlaybackReason.INVALID_INPUT_TIMELINE, cadenceFailure.reason)
  }

  @Test
  fun playerCapsSameTickWorkAndDoesNotConsumeARejectedInput() {
    val rig = rig()
    val alternating =
        List(ReplayLimits.MAX_INPUT_RECORDS_PER_TICK + 1) { index ->
          ReplayInputRecord(
              0,
              ReplayInputPhase.LEGACY_P1_BEFORE_TICK,
              0,
              if (index % 2 == 0) JoypadButtonMask.A else 0,
              JoypadButtonMask.A,
          )
        }
    val excessive =
        ReplayFile(
            ReplayCompatibility.identity(rig.configuration),
            ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 0),
            alternating,
            listOf(ReplayCheckpoint(0, 0, ReplayTestFixture.hashes())),
        )
    val rateFailure =
        org.junit.Assert.assertThrows(ReplayPlaybackException::class.java) {
          ReplayPlayer.open(excessive, rig.configuration)
        }
    assertEquals(ReplayPlaybackReason.INVALID_INPUT_TIMELINE, rateFailure.reason)

    val malformedTransition =
        ReplayFile(
            excessive.identity,
            excessive.initialConditions,
            listOf(
                ReplayInputRecord(
                    0,
                    ReplayInputPhase.LEGACY_P1_BEFORE_TICK,
                    0,
                    JoypadButtonMask.A,
                    JoypadButtonMask.START,
                ),
            ),
            excessive.checkpoints,
        )
    ReplayPlayer.open(malformedTransition, rig.configuration).use { player ->
      repeat(2) {
        val mismatch =
            org.junit.Assert.assertThrows(ReplayPlaybackException::class.java) {
              player.step()
            }
        assertEquals(ReplayPlaybackReason.INPUT_MASK_MISMATCH, mismatch.reason)
        assertEquals(null, player.position.tick)
      }
    }
    rig.session.close()
  }

  private fun rig(): Rig {
    val input = PlayerInputHub()
    val configuration = configuration(input)
    val session = Session(configuration, EventBusImpl(null, null, false), null)
    return Rig(
        configuration,
        session,
        input.openSource(0),
        input.openSource(1),
    )
  }

  private data class Rig(
      val configuration: Gameboy.GameboyConfiguration,
      val session: Session,
      val p1: PlayerInputHub.SourceHandle,
      val p2: PlayerInputHub.SourceHandle,
  )

  private class CallbackInputSource : PlayerInputSource {
    var onSample: () -> Unit = {}

    override fun sample(): PlayerInputSnapshot {
      onSample()
      return PlayerInputSnapshot.released()
    }
  }

  private fun configuration(input: PlayerInputSource): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(syntheticRom()))
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          .setSupportBatterySave(false)
          .setBatteryStorage(null, null)
          .setPlayerInputSource(input)

  companion object {
    fun replayDuration(replay: ReplayFile): Long =
        replay.checkpoints.last().tick - replay.initialConditions.initialTick + 1L

    fun syntheticRom(): ByteArray =
        ByteArray(0x8000).also { bytes ->
          "CGBR-TEST".forEachIndexed { index, character ->
            bytes[0x134 + index] = character.code.toByte()
          }
          bytes[0x100] = 0x00
          bytes[0x101] = 0x18
          bytes[0x102] = 0xfd.toByte()
          bytes[0x143] = 0
          bytes[0x146] = 0
          bytes[0x147] = 0
          bytes[0x148] = 0
          bytes[0x149] = 0
        }
  }
}
