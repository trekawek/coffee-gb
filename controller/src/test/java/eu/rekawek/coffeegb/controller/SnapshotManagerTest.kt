package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateDecodeReason
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateRootKind
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.Ram
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InvalidObjectException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
        assertFalse(LegacyMementoCodec.hasJavaSerializationHeader(bytes))
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
      assertTrue(LegacyMementoCodec.hasJavaSerializationHeader(file.readBytes()))

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
  fun failedLegacyReadAndApplyNeverMutateOrRewriteOriginalFile() {
    withDefaultMachine { rom, configuration, gameboy ->
      val manager =
          SnapshotManager(
              configuration,
              LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS,
          )
      val truncated = LEGACY_HEADER.clone()
      assertRejectedLegacyUnchanged(manager, rom, configuration, gameboy, truncated)

      repeat(100) { gameboy.tick() }
      val beforeBytes = StateCodec.encode(StateCodec.capture(configuration, gameboy))
      repeat(2_000) { gameboy.tick() }
      val later = gameboy.saveToMemento()
      val mmu = recordComponent(later, "mmuMemento")!!
      val invalidMmu = replaceRecordComponent(mmu, "ramC000Memento", Ram.RamMemento(IntArray(0)))
      @Suppress("UNCHECKED_CAST")
      val invalid = replaceRecordComponent(later, "mmuMemento", invalidMmu) as Memento<Gameboy>
      val invalidBytes = LegacyMementoCodec.serializeGameboy(invalid)
      StateCodec.decodeAndApply(beforeBytes, configuration, gameboy)

      val file = snapshotFile(rom, 0)
      file.writeBytes(invalidBytes)
      val failure = assertFailsWith<SnapshotLoadException> { manager.loadSnapshot(0, gameboy) }
      assertEquals(SnapshotFileFormat.LEGACY_JAVA, failure.format)
      assertNull(failure.stateDecodeReason)
      assertContentEquals(invalidBytes, file.readBytes())
      assertContentEquals(beforeBytes, StateCodec.encode(StateCodec.capture(configuration, gameboy)))
    }
  }

  @Test
  fun unexpectedLegacyLiveApplyFailureRollsBackAndDoesNotMigrate() {
    withDefaultMachine { rom, configuration, gameboy ->
      repeat(127) { gameboy.tick() }
      val before = StateCodec.encode(StateCodec.capture(configuration, gameboy))
      repeat(1_000) { gameboy.tick() }
      val legacy = LegacyMementoCodec.serializeGameboy(gameboy.saveToMemento())
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

  private companion object {
    val CGBS =
        byteArrayOf(
            'C'.code.toByte(),
            'G'.code.toByte(),
            'B'.code.toByte(),
            'S'.code.toByte(),
        )
    val LEGACY_HEADER = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
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
}
