package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.Int32ArrayState
import eu.rekawek.coffeegb.controller.state.Int32MapState
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.Int64ArrayState
import eu.rekawek.coffeegb.controller.state.Int64State
import eu.rekawek.coffeegb.controller.state.ListState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.ObjectArrayState
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateDecodeReason
import eu.rekawek.coffeegb.controller.state.StateGraph
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateRootKind
import eu.rekawek.coffeegb.controller.state.StateValue
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InvalidObjectException
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SnapshotManagerTest {

  @Test
  fun memoryOriginReportsNoSnapshotInsteadOfInventingFilesystemPersistence() {
    val image = RomImage.memory(ROM.readBytes(), "memory-fixture.gb")
    val configuration =
        Gameboy.GameboyConfiguration(Rom(image)).setBootstrapMode(BootstrapMode.SKIP)

    assertFalse(SnapshotManager(configuration).snapshotAvailable(0))
  }

  @Test
  fun archiveEntriesUseDistinctSnapshotAnchors() {
    withDirectory { directory ->
      val container = directory.resolve("collection.zip")
      val firstOrigin = RomOrigin.archiveEntry(container, "red/game.gb", false)
      val secondOrigin = RomOrigin.archiveEntry(container, "blue/game.gb", false)
      val firstConfig =
          Gameboy.GameboyConfiguration(Rom(RomImage(firstOrigin, ROM.readBytes())))
              .setBootstrapMode(BootstrapMode.SKIP)
      val secondConfig =
          Gameboy.GameboyConfiguration(Rom(RomImage(secondOrigin, ROM.readBytes())))
              .setBootstrapMode(BootstrapMode.SKIP)
      val first = firstConfig.build()
      val second = secondConfig.build()
      try {
        SnapshotManager(firstConfig).saveSnapshot(0, first)
        SnapshotManager(secondConfig).saveSnapshot(0, second)

        val firstFile = firstOrigin.persistencePath(".sn0").orElseThrow()
        val secondFile = secondOrigin.persistencePath(".sn0").orElseThrow()
        assertTrue(Files.isRegularFile(firstFile))
        assertTrue(Files.isRegularFile(secondFile))
        assertTrue(firstFile != secondFile)
        assertFalse(Files.exists(directory.resolve("collection.sn0")))
      } finally {
        first.discardUnstarted()
        second.discardUnstarted()
      }
    }
  }

  @Test
  fun singleEntryArchiveCanLoadLegacyContainerSnapshot() {
    withDirectory { directory ->
      val container = directory.resolve("collection.zip")
      val origin = RomOrigin.archiveEntry(container, "game.gb", true)
      val config =
          Gameboy.GameboyConfiguration(Rom(RomImage(origin, ROM.readBytes())))
              .setBootstrapMode(BootstrapMode.SKIP)
      val gameboy = config.build()
      try {
        val manager = SnapshotManager(config)
        manager.saveSnapshot(0, gameboy)
        val primary = origin.persistencePath(".sn0").orElseThrow()
        val legacy = origin.legacyArchivePersistencePath(".sn0").orElseThrow()
        Files.move(primary, legacy)

        assertTrue(manager.snapshotAvailable(0))
        assertTrue(manager.loadSnapshot(0, gameboy))
        assertFalse(Files.exists(primary))
      } finally {
        gameboy.discardUnstarted()
      }
    }
  }

  @Test
  fun failedReplacementRetainsReadablePriorSnapshotAndLaterRetrySucceeds() {
    withDefaultMachine { rom, configuration, gameboy ->
      val persistence = ToggleFailWriter()
      val manager =
          SnapshotManager.testing(
              configuration,
              LegacySnapshotMigrationPolicy.PRESERVE,
              persistence = persistence,
          )
      repeat(100) { gameboy.tick() }
      manager.saveSnapshot(0, gameboy)
      val oldBytes = snapshotFile(rom, 0).readBytes()
      val oldState = StateCodec.capture(configuration, gameboy).root

      repeat(500) { gameboy.tick() }
      persistence.fail = true
      assertFailsWith<IOException> { manager.saveSnapshot(0, gameboy) }
      assertContentEquals(oldBytes, snapshotFile(rom, 0).readBytes())
      assertTrue(manager.snapshotAvailable(0))
      assertTrue(manager.loadSnapshot(0, gameboy))
      assertEquals(oldState, StateCodec.capture(configuration, gameboy).root)

      repeat(250) { gameboy.tick() }
      persistence.fail = false
      manager.saveSnapshot(0, gameboy)
      assertFalse(oldBytes.contentEquals(snapshotFile(rom, 0).readBytes()))
    }
  }

  @Test
  fun availabilityAndLoadRecoverAnInterruptedFallbackBackupBeforeReading() {
    withDefaultMachine { rom, configuration, gameboy ->
      val manager = SnapshotManager(configuration)
      repeat(333) { gameboy.tick() }
      manager.saveSnapshot(0, gameboy)
      val expected = StateCodec.capture(configuration, gameboy).root
      val target = snapshotFile(rom, 0).toPath().toAbsolutePath().normalize()
      val backup = recoveryBackup(target)
      Files.move(target, backup)
      assertFalse(Files.exists(target))

      assertTrue(manager.snapshotAvailable(0))
      assertTrue(Files.exists(target))
      assertFalse(Files.exists(backup))

      repeat(999) { gameboy.tick() }
      assertTrue(manager.loadSnapshot(0, gameboy))
      assertEquals(expected, StateCodec.capture(configuration, gameboy).root)
    }
  }

  @Test
  fun newSaveIsDeflatedMachineStateWithExactPrimarySlotAndProfileIdentity() {
    withDirectory { directory ->
      val primary =
          directory.resolve("datel.gbc").toFile().also {
            it.writeBytes(StateCodecTestSupport.datelRom())
          }
      val slot =
          directory.resolve("slot.gbc").toFile().also {
            it.writeBytes(StateCodecTestSupport.rom(seed = 37, cgb = true))
          }
      withMachine(
          primary,
          configure = {
            it.setGameboyType(GameboyType.CGB)
                .setCgb0Revision(true)
                .setCodeBreakerRumble(true)
                .setSlotRom(Rom(slot))
          },
      ) { configuration, gameboy ->
        repeat(317) { gameboy.tick() }
        SnapshotManager(configuration).saveSnapshot(2, gameboy)

        val bytes = snapshotFile(primary, 2).readBytes()
        assertContentEquals(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte()), bytes.copyOf(4))
        assertFalse(LegacySnapshotImporter.hasJavaSerializationHeader(bytes))
        val inspection = StateCodec.inspect(bytes)
        assertEquals(StateRootKind.MACHINE, inspection.rootKind)
        assertEquals(StateCompression.DEFLATE, inspection.compression)
        assertEquals(StateIdentity.from(configuration), inspection.identities.single().identity)
        assertNotNull(inspection.identities.single().identity?.slotRom)
      }
    }
  }

  @Test
  fun portableSaveLoadResumesDeterministically() {
    withDefaultMachine { rom, configuration, gameboy ->
      val manager = SnapshotManager(configuration)
      repeat(503) { gameboy.tick() }
      manager.saveSnapshot(0, gameboy)
      val saved =
          (StateCodec.decode(snapshotFile(rom, 0).readBytes()).root as MachineStateRoot).machine

      repeat(777) { gameboy.tick() }
      val continuation = StateCodec.capture(configuration, gameboy).root

      assertTrue(manager.loadSnapshot(0, gameboy))
      assertEquals(saved, (StateCodec.capture(configuration, gameboy).root as MachineStateRoot).machine)
      repeat(777) { gameboy.tick() }
      assertEquals(continuation, StateCodec.capture(configuration, gameboy).root)
    }
  }

  @Test
  fun portableCorruptionTruncationUnknownPrefixesAndStreamingOverflowAreAtomic() {
    withDefaultMachine { rom, configuration, gameboy ->
      val ordinary = SnapshotManager(configuration)
      repeat(211) { gameboy.tick() }
      ordinary.saveSnapshot(0, gameboy)
      val valid = snapshotFile(rom, 0).readBytes()

      val corrupt = valid.clone().also { it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte() }
      assertRejectedUnchanged(
          ordinary,
          rom,
          configuration,
          gameboy,
          corrupt,
          SnapshotFileFormat.PORTABLE,
          StateDecodeReason.CORRUPT_CHECKSUM,
      )
      assertRejectedUnchanged(
          ordinary,
          rom,
          configuration,
          gameboy,
          valid.copyOf(24),
          SnapshotFileFormat.PORTABLE,
          StateDecodeReason.TRUNCATED,
      )
      assertRejectedUnchanged(
          ordinary,
          rom,
          configuration,
          gameboy,
          byteArrayOf(1, 2, 3, 4),
          null,
          null,
      )
      assertRejectedUnchanged(
          ordinary,
          rom,
          configuration,
          gameboy,
          byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x04),
          null,
          null,
      )

      val tinyLimits = SnapshotReadLimits(portableBytes = 8, legacyBytes = 8)
      val bounded =
          SnapshotManager.testing(
              configuration,
              LegacySnapshotMigrationPolicy.PRESERVE,
              tinyLimits,
          )
      assertRejectedUnchanged(
          bounded,
          rom,
          configuration,
          gameboy,
          CGBS + ByteArray(5),
          SnapshotFileFormat.PORTABLE,
          null,
      )
    }
  }

  @Test
  fun romAndHardwareMismatchErrorsCarryTypedReasonAndSafeIdentityDiagnostics() {
    withDirectory { directory ->
      val sourceRom =
          directory.resolve("source.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
      val targetBytes = ROM.readBytes().also { it[0x134] = (it[0x134] + 1).toByte() }
      val targetRom =
          directory.resolve("target.gb").toFile().also { it.writeBytes(targetBytes) }

      lateinit var portable: ByteArray
      withMachine(sourceRom) { sourceConfiguration, sourceGameboy ->
        SnapshotManager(sourceConfiguration).saveSnapshot(0, sourceGameboy)
        portable = snapshotFile(sourceRom, 0).readBytes()
      }
      withMachine(targetRom) { targetConfiguration, targetGameboy ->
        snapshotFile(targetRom, 0).writeBytes(portable)
        val before = StateCodec.capture(targetConfiguration, targetGameboy).root
        val failure =
            assertFailsWith<SnapshotLoadException> {
              SnapshotManager(targetConfiguration).loadSnapshot(0, targetGameboy)
            }
        assertEquals(StateDecodeReason.ROM_MISMATCH, failure.stateDecodeReason)
        val sourceIdentity = assertNotNull(failure.sourceIdentity)
        assertTrue(failure.message.orEmpty().contains(sourceIdentity.primaryRom.hex()))
        assertTrue(failure.message.orEmpty().contains(failure.targetIdentity.primaryRom.hex()))
        assertTrue(failure.message.orEmpty().contains("profile="))
        assertEquals(before, StateCodec.capture(targetConfiguration, targetGameboy).root)
      }

      val sharedRom =
          directory.resolve("hardware.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
      withMachine(sharedRom, configure = { it.setGameboyType(GameboyType.DMG) }) {
          sourceConfiguration,
          sourceGameboy ->
        SnapshotManager(sourceConfiguration).saveSnapshot(1, sourceGameboy)
      }
      withMachine(sharedRom, configure = { it.setGameboyType(GameboyType.SGB) }) {
          targetConfiguration,
          targetGameboy ->
        val before = StateCodec.capture(targetConfiguration, targetGameboy).root
        val failure =
            assertFailsWith<SnapshotLoadException> {
              SnapshotManager(targetConfiguration).loadSnapshot(1, targetGameboy)
            }
        assertEquals(StateDecodeReason.HARDWARE_PROFILE_MISMATCH, failure.stateDecodeReason)
        assertIs<StateDecodeException>(failure.cause)
        assertTrue(failure.message.orEmpty().contains("hardware=DMG"))
        assertTrue(failure.message.orEmpty().contains("hardware=SGB"))
        assertEquals(before, StateCodec.capture(targetConfiguration, targetGameboy).root)
      }
    }
  }

  @Test
  fun supportedPriorReleaseFixturesLoadOnlyThroughStrictLocalReaderAndRemainUnchangedByDefault() {
    withDefaultMachine { rom, configuration, gameboy ->
      LEGACY_FIXTURES.forEachIndexed { slot, fixture ->
        val original = fixture.readBytes()
        snapshotFile(rom, slot).writeBytes(original)
        assertTrue(SnapshotManager(configuration).loadSnapshot(slot, gameboy), fixture.name)
        assertContentEquals(original, snapshotFile(rom, slot).readBytes(), fixture.name)
      }
    }
  }

  @Test
  fun optInLegacyMigrationRewritesOnlyAfterSuccessfulRestoreAndThenLoadsPortably() {
    withDefaultMachine { rom, configuration, gameboy ->
      val file = snapshotFile(rom, 0)
      file.writeBytes(LEGACY_FIXTURES.last().readBytes())
      assertTrue(LegacySnapshotImporter.hasJavaSerializationHeader(file.readBytes()))

      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )
      assertTrue(manager.loadSnapshot(0, gameboy))
      val migrated = file.readBytes()
      assertContentEquals(CGBS, migrated.copyOf(4))
      assertEquals(StateIdentity.from(configuration), StateCodec.inspect(migrated).identities.single().identity)
      val restored = StateCodec.capture(configuration, gameboy).root

      repeat(1_000) { gameboy.tick() }
      assertTrue(manager.loadSnapshot(0, gameboy))
      assertEquals(restored, StateCodec.capture(configuration, gameboy).root)
    }
  }

  @Test
  fun readOnlyCompatibilityLoadNeverRewritesLegacySidecarEvenWhenMigrationIsEnabled() {
    withDefaultMachine { rom, configuration, gameboy ->
      val file = snapshotFile(rom, 0)
      val original = LEGACY_FIXTURES.last().readBytes()
      file.writeBytes(original)
      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )

      val snapshot = assertNotNull(manager.readSnapshotReadOnly(0))
      manager.applySnapshotReadOnly(snapshot, gameboy)

      assertContentEquals(original, file.readBytes())
      assertTrue(LegacySnapshotImporter.hasJavaSerializationHeader(file.readBytes()))
    }
  }

  @Test
  fun compatibilityPreflightDoesNotAdvancePausedMbc3Rtc() {
    val time = VirtualTimeSource(120_000)
    val rom =
        StateCodecTestSupport.rom(seed = 4).also {
          it[0x147] = 0x10
          it[0x149] = 0x03
        }
    val configuration = StateCodecTestSupport.configuration(rom).setRtcTimeSource(time)
    StateCodecTestSupport.session(configuration).use { session ->
      val manager = SnapshotManager(configuration)
      val portable =
          CompatibilitySnapshot.Portable(
              StateCodec.capture(session),
              StateIdentity.from(configuration),
          )
      val legacy =
          CompatibilitySnapshot.Legacy(
              LegacySnapshotImporter.importGameboyState(LEGACY_FIXTURES.last().readBytes()))
      session.gameboy.setCartridgeClockPaused(true)

      val beforePortableState =
          StateGraph.capture(session.gameboy.captureStateWithoutTimeSource())
      val beforePortableRuntime = session.gameboy.captureRtcRuntimeStateWithoutTimeSource()
      time.forward(7, TimeUnit.SECONDS)

      manager.validateSnapshotReadOnly(portable, session)

      assertEquals(
          beforePortableState,
          StateGraph.capture(session.gameboy.captureStateWithoutTimeSource()),
      )
      assertEquals(
          beforePortableRuntime,
          session.gameboy.captureRtcRuntimeStateWithoutTimeSource(),
      )

      val beforeLegacyState =
          StateGraph.capture(session.gameboy.captureStateWithoutTimeSource())
      val beforeLegacyRuntime = session.gameboy.captureRtcRuntimeStateWithoutTimeSource()
      time.forward(7, TimeUnit.SECONDS)

      assertFailsWith<StateApplyException> {
        manager.validateSnapshotReadOnly(legacy, session)
      }

      assertEquals(
          beforeLegacyState,
          StateGraph.capture(session.gameboy.captureStateWithoutTimeSource()),
      )
      assertEquals(
          beforeLegacyRuntime,
          session.gameboy.captureRtcRuntimeStateWithoutTimeSource(),
      )
    }
  }

  @Test
  fun readOnlyCompatibilityReadNeverRecoversOrCleansInterruptedTransactionArtifacts() {
    withDefaultMachine { rom, configuration, _ ->
      val target = snapshotFile(rom, 0).toPath().toAbsolutePath().normalize()
      val original = LEGACY_FIXTURES.last().readBytes()
      val backup = recoveryBackup(target)
      val temporary = recoveryTemporary(target)
      Files.write(target, original)
      Files.write(backup, original)
      Files.write(temporary, byteArrayOf(7, 8, 9))
      val manager = SnapshotManager(configuration)

      assertNotNull(manager.readSnapshotReadOnly(0))

      assertContentEquals(original, Files.readAllBytes(target))
      assertContentEquals(original, Files.readAllBytes(backup))
      assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(temporary))

      Files.delete(target)
      assertNull(manager.readSnapshotReadOnly(0))
      assertFalse(Files.exists(target))
      assertContentEquals(original, Files.readAllBytes(backup))
      assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(temporary))
    }
  }

  @Test
  fun failedBestEffortLegacyRewriteLeavesACompleteLegacyOrPortableFile() {
    withDefaultMachine { rom, configuration, gameboy ->
      val file = snapshotFile(rom, 0)
      val legacy = LEGACY_FIXTURES.last().readBytes()

      file.writeBytes(legacy)
      val failBeforeWrite = ToggleFailWriter().also { it.fail = true }
      val preservingManager =
          SnapshotManager.testing(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
              persistence = failBeforeWrite,
          )
      assertTrue(preservingManager.loadSnapshot(0, gameboy))
      assertContentEquals(legacy, file.readBytes())

      file.writeBytes(legacy)
      val commitThenFail = CommitThenFailOnceWriter()
      val committingManager =
          SnapshotManager.testing(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
              persistence = commitThenFail,
          )
      assertTrue(committingManager.loadSnapshot(0, gameboy))
      val committed = file.readBytes()
      assertContentEquals(CGBS, committed.copyOf(4))
      assertEquals(StateRootKind.MACHINE, StateCodec.inspect(committed).rootKind)
    }
  }

  @Test
  fun legacyMigrationPreservesTheRetainedOverfullFifoRingOrder() {
    val fixtureBytes = LEGACY_FIXTURES.last().readBytes()
    val fixture = LegacySnapshotImporter.importGameboyState(fixtureBytes)
    val legacyRoot = StateGraph.captureLegacyRoot(fixture, LEGACY_GAMEBOY_MEMENTO)
    val original =
        legacyRoot.records(COLOR_FIFO_TYPE_ID).first {
          it.int("delaySize") == 321 &&
              it.int("delayHead") == 0 &&
              it.intArray("delayEntry").size == 8 &&
              it.longArray("delayStamp").size == 8
        }
    assertEquals(321, original.int("delaySize"))
    assertEquals(0, original.int("delayHead"))
    assertEquals(8, original.intArray("delayEntry").size)
    @Suppress("UNCHECKED_CAST")
    val orderedFixture =
        replaceFirstRecord(
            fixture,
            LEGACY_COLOR_FIFO_MEMENTO,
            predicate = {
              recordComponent(it, "delaySize") as Int == 321 &&
                  recordComponent(it, "delayHead") as Int == 0
            },
        ) {
          replaceRecordComponent(
              replaceRecordComponent(it, "delayEntry", IntArray(8) { index -> 10 + index }),
              "delayStamp",
              LongArray(8) { index -> 100L + index },
          )
        } as Memento<Gameboy>
    val orderedRoot = StateGraph.captureLegacyRoot(orderedFixture, LEGACY_GAMEBOY_MEMENTO)
    val ordered =
        orderedRoot.records(COLOR_FIFO_TYPE_ID).first {
          it.int("delaySize") == 321 &&
              it.int("delayHead") == 0 &&
              it.intArray("delayEntry").contentEquals(IntArray(8) { index -> 10 + index })
        }
    val expectedHead = 1
    val expectedEntries = ordered.orderedIntRing(expectedHead, 8)
    val expectedStamps = ordered.orderedLongRing(expectedHead, 8)
    val staleEntries = ordered.orderedIntRing(0, 8)
    val staleStamps = ordered.orderedLongRing(0, 8)
    assertFalse(
        expectedEntries.contentEquals(staleEntries) &&
            expectedStamps.contentEquals(staleStamps),
        "The seeded retained entries must distinguish the rebased logical ring order",
    )

    withDefaultMachine { rom, configuration, gameboy ->
      val file = snapshotFile(rom, 0)
      file.writeBytes(serializeLegacyForTest(orderedFixture))
      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )

      assertTrue(manager.loadSnapshot(0, gameboy))

      val migrated =
          ((StateCodec.decode(file.readBytes()).root as MachineStateRoot).machine.root)
              .records(COLOR_FIFO_TYPE_ID)
              .first {
                it.intArray("delayEntry").contentEquals(ordered.intArray("delayEntry")) &&
                    it.longArray("delayStamp").contentEquals(ordered.longArray("delayStamp"))
              }
      assertEquals(8, migrated.int("delaySize"))
      assertEquals(expectedHead, migrated.int("delayHead"))
      assertEquals(107L, migrated.long("outputTicks"))
      assertContentEquals(expectedEntries, migrated.orderedIntRing(expectedHead, 8))
      assertContentEquals(expectedStamps, migrated.orderedLongRing(expectedHead, 8))
    }
  }

  @Test
  fun malformedLegacyOverfullFifoShapeIsRejectedAtomicallyWithoutMigration() {
    withDefaultMachine { rom, configuration, gameboy ->
      val fixtureBytes = LEGACY_FIXTURES.last().readBytes()
      val fixture = LegacySnapshotImporter.importGameboyState(fixtureBytes)
      @Suppress("UNCHECKED_CAST")
      val malformed =
          replaceFirstRecord(
              fixture,
              LEGACY_COLOR_FIFO_MEMENTO,
              predicate = {
                val entries = recordComponent(it, "delayEntry") as IntArray
                recordComponent(it, "delaySize") as Int > entries.size
              },
          ) {
            val stamps = recordComponent(it, "delayStamp") as LongArray
            replaceRecordComponent(it, "delayStamp", stamps.copyOf(stamps.size - 1))
          } as Memento<Gameboy>
      val malformedBytes = serializeLegacyForTest(malformed)
      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )

      assertRejectedLegacyUnchanged(
          manager,
          rom,
          configuration,
          gameboy,
          malformedBytes,
      )
    }
  }

  @Test
  fun failedLegacyReadNeverMutatesOrRewritesOriginalFile() {
    withDefaultMachine { rom, configuration, gameboy ->
      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )
      val truncated = LEGACY_HEADER.clone()
      assertRejectedLegacyUnchanged(manager, rom, configuration, gameboy, truncated)
    }
  }

  @Test
  fun unexpectedLegacyLiveApplyFailureRollsBackAndDoesNotMigrate() {
    withDefaultMachine { rom, configuration, gameboy ->
      repeat(127) { gameboy.tick() }
      val before = StateCodec.encode(StateCodec.capture(configuration, gameboy))
      val legacy = LEGACY_FIXTURES.last().readBytes()
      StateCodec.decodeAndApply(before, configuration, gameboy)

      val file = snapshotFile(rom, 0)
      file.writeBytes(legacy)
      val manager =
          SnapshotManager.testing(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
              SnapshotReadLimits.DEFAULT,
          ) {
            throw IllegalStateException("injected after legacy memento mutation")
          }
      val failure = assertFailsWith<SnapshotLoadException> { manager.loadSnapshot(0, gameboy) }
      assertEquals(SnapshotFileFormat.LEGACY_JAVA, failure.format)
      assertTrue(failure.message.orEmpty().contains("atomically"))
      assertContentEquals(legacy, file.readBytes())
      assertContentEquals(before, StateCodec.encode(StateCodec.capture(configuration, gameboy)))
    }
  }

  @Test
  fun streamingReaderAcceptsExactLimitRejectsBoundaryPlusOneAndDispatchesOnlyExactMagic() {
    val limits = SnapshotReadLimits(portableBytes = 8, legacyBytes = 8)
    assertEquals(StateLimits.PORTABLE_MAX_FILE_BYTES, SnapshotReadLimits.DEFAULT.portableBytes)
    assertEquals(StateLimits.GAME_SNAPSHOT.decodedBytes, SnapshotReadLimits.DEFAULT.legacyBytes)

    listOf(
            SnapshotFileFormat.PORTABLE to CGBS,
            SnapshotFileFormat.LEGACY_JAVA to LEGACY_HEADER,
        )
        .forEach { (format, prefix) ->
          val exact = prefix + ByteArray(4)
          val decoded = SnapshotFileReader.read(ByteArrayInputStream(exact), limits)
          assertEquals(format, decoded.format)
          assertContentEquals(exact, decoded.bytes)

          val failure =
              assertFailsWith<SnapshotReadException> {
                SnapshotFileReader.read(ByteArrayInputStream(prefix + ByteArray(5)), limits)
              }
          assertEquals(format, failure.format)
        }

    listOf(
            byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte()),
            byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x04),
            byteArrayOf(0, 0, 0, 0),
        )
        .forEach {
          val failure =
              assertFailsWith<SnapshotReadException> {
                SnapshotFileReader.read(ByteArrayInputStream(it), limits)
              }
          assertNull(failure.format)
        }
  }

  private fun assertRejectedUnchanged(
      manager: SnapshotManager,
      rom: File,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      bytes: ByteArray,
      format: SnapshotFileFormat?,
      reason: StateDecodeReason?,
  ) {
    val before = StateCodec.encode(StateCodec.capture(configuration, gameboy))
    snapshotFile(rom, 0).writeBytes(bytes)
    val failure = assertFailsWith<SnapshotLoadException> { manager.loadSnapshot(0, gameboy) }
    assertEquals(format, failure.format)
    assertEquals(reason, failure.stateDecodeReason)
    if (format == SnapshotFileFormat.PORTABLE && reason != null) {
      assertIs<StateDecodeException>(failure.cause)
      assertFalse(failure.cause is InvalidObjectException)
    }
    assertContentEquals(before, StateCodec.encode(StateCodec.capture(configuration, gameboy)))
  }

  private fun assertRejectedLegacyUnchanged(
      manager: SnapshotManager,
      rom: File,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      bytes: ByteArray,
  ) {
    val before = StateCodec.encode(StateCodec.capture(configuration, gameboy))
    val file = snapshotFile(rom, 0)
    file.writeBytes(bytes)
    val failure = assertFailsWith<SnapshotLoadException> { manager.loadSnapshot(0, gameboy) }
    assertEquals(SnapshotFileFormat.LEGACY_JAVA, failure.format)
    assertContentEquals(bytes, file.readBytes())
    assertContentEquals(before, StateCodec.encode(StateCodec.capture(configuration, gameboy)))
  }

  private fun withDefaultMachine(
      block: (File, Gameboy.GameboyConfiguration, Gameboy) -> Unit,
  ) {
    withDirectory { directory ->
      val rom = directory.resolve("fixture.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
      withMachine(rom) { configuration, gameboy -> block(rom, configuration, gameboy) }
    }
  }

  private fun withMachine(
      rom: File,
      configure: (Gameboy.GameboyConfiguration) -> Gameboy.GameboyConfiguration = { it },
      block: (Gameboy.GameboyConfiguration, Gameboy) -> Unit,
  ) {
    val configuration =
        configure(
            Gameboy.GameboyConfiguration(Rom(rom))
                .setBootstrapMode(BootstrapMode.SKIP))
    val eventBus = EventBusImpl()
    val gameboy = configuration.build()
    gameboy.init(
        eventBus,
        SerialEndpoint.NULL_ENDPOINT,
        InfraredEndpoint.NULL_ENDPOINT,
        null,
    )
    try {
      block(configuration, gameboy)
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
    }
  }

  private fun withDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("coffee-gb-snapshot-test")
    try {
      block(directory)
    } finally {
      Files.list(directory).use { files -> files.forEach { it.deleteIfExists() } }
      directory.deleteIfExists()
    }
  }

  private fun snapshotFile(rom: File, slot: Int) =
      rom.parentFile.resolve("${rom.nameWithoutExtension}.sn$slot")

  private fun recoveryBackup(target: Path): Path {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(target.fileName.toString().toByteArray(Charsets.UTF_8))
    val id = digest.copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return target.parent.resolve(".coffeegb-$id.backup")
  }

  private fun recoveryTemporary(target: Path): Path {
    val backupName = recoveryBackup(target).fileName.toString()
    val id = backupName.removePrefix(".coffeegb-").removeSuffix(".backup")
    return target.parent.resolve(".coffeegb-$id.tmp-interrupted.part")
  }

  private fun recordComponent(record: Any, name: String): Any? =
      record.javaClass.recordComponents.single { it.name == name }.accessor.let { accessor ->
        accessor.isAccessible = true
        accessor.invoke(record)
      }

  private fun replaceRecordComponent(record: Any, name: String, replacement: Any?): Any {
    val components = record.javaClass.recordComponents
    val constructor =
        record.javaClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray()).also {
          it.isAccessible = true
        }
    val arguments =
        components.map { component ->
          if (component.name == name) {
            replacement
          } else {
            component.accessor.let { accessor ->
              accessor.isAccessible = true
              accessor.invoke(record)
            }
          }
        }.toTypedArray()
    return constructor.newInstance(*arguments)
  }

  private fun replaceFirstRecord(
      root: Any,
      className: String,
      predicate: (Any) -> Boolean,
      replacement: (Any) -> Any,
  ): Any {
    var replaced = false

    fun visit(value: Any?): Any? {
      if (value == null || replaced) return value
      if (value.javaClass.name == className && predicate(value)) {
        replaced = true
        return replacement(value)
      }
      if (!value.javaClass.isRecord) return value

      val components = value.javaClass.recordComponents
      val original =
          components.map { component ->
            component.accessor.let { accessor ->
              accessor.isAccessible = true
              accessor.invoke(value)
            }
          }
      val updated = original.map(::visit)
      if (original.indices.all { original[it] === updated[it] }) return value
      val constructor =
          value.javaClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray()).also {
            it.isAccessible = true
          }
      return constructor.newInstance(*updated.toTypedArray())
    }

    return checkNotNull(visit(root)).also {
      check(replaced) { "No matching $className record found" }
    }
  }

  private fun StateValue.records(typeId: Int): List<RecordState> {
    val matches = mutableListOf<RecordState>()
    fun visit(value: StateValue) {
      when (value) {
        is RecordState -> {
          if (value.typeId == typeId) matches += value
          value.fields.forEach { visit(it.value) }
        }
        is ObjectArrayState -> value.values.forEach(::visit)
        is ListState -> value.values.forEach(::visit)
        is Int32MapState -> value.entries.forEach { visit(it.value) }
        else -> Unit
      }
    }
    visit(this)
    return matches
  }

  private fun RecordState.int(name: String): Int =
      (fields.single { it.name == name }.value as Int32State).value

  private fun RecordState.long(name: String): Long =
      (fields.single { it.name == name }.value as Int64State).value

  private fun RecordState.intArray(name: String): IntArray =
      (fields.single { it.name == name }.value as Int32ArrayState).copyValue()

  private fun RecordState.longArray(name: String): LongArray =
      (fields.single { it.name == name }.value as Int64ArrayState).copyValue()

  private fun RecordState.orderedIntRing(head: Int, size: Int): IntArray {
    val values = intArray("delayEntry")
    return IntArray(size) { offset -> values[(head + offset) % values.size] }
  }

  private fun RecordState.orderedLongRing(head: Int, size: Int): LongArray {
    val values = longArray("delayStamp")
    return LongArray(size) { offset -> values[(head + offset) % values.size] }
  }

  private companion object {
    val CGBS =
        byteArrayOf(
            'C'.code.toByte(),
            'G'.code.toByte(),
            'B'.code.toByte(),
            'S'.code.toByte(),
        )
    val LEGACY_HEADER = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
    const val LEGACY_GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"
    const val LEGACY_COLOR_FIFO_MEMENTO =
        "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoMemento"
    const val PORTABLE_COLOR_FIFO_STATE =
        "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState"
    val COLOR_FIFO_TYPE_ID =
        StateTypeRegistry.recordClassNames.indexOf(PORTABLE_COLOR_FIFO_STATE).plus(1).also {
          check(it > 0)
        }
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    val LEGACY_FIXTURES =
        listOf("1.7.13", "1.7.14").map { version ->
          Paths.get(
                  "src/test/resources/legacy",
                  "coffee-gb-$version-cpu-instrs.sn",
              )
              .toFile()
        }
  }

  /** Test-only producer for hostile/modified historical graphs; production has no legacy writer. */
  private fun serializeLegacyForTest(value: Any): ByteArray =
      ByteArrayOutputStream().use { output ->
        ObjectOutputStream(output).use { it.writeObject(value) }
        output.toByteArray()
      }

  private class ToggleFailWriter : AtomicFileWriter() {
    var fail = false

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (fail) {
        throw IOException("injected snapshot replacement failure")
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class CommitThenFailOnceWriter : AtomicFileWriter() {
    private var fail = true

    override fun write(target: Path, intendedBytes: ByteArray) {
      AtomicFileWriter.system().write(target, intendedBytes)
      if (fail) {
        fail = false
        throw IOException("injected after snapshot replacement")
      }
    }
  }
}
