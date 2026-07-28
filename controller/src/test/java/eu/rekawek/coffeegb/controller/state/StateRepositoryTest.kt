package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateRepositoryTest {

  @Test
  fun `stable named and autosave namespaces round trip and catalog deterministically`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-basic"))
    val repository = StateRepository(layout)
    val slot = StateRef.Slot(2)
    val named = StateRef.Named(UUID.fromString("12345678-1234-5678-9abc-def012345678"))
    val autosave = StateRef.Autosave

    repository.save(
        slot,
        fixture.plain,
        StateSaveMetadata("Slot ../../ label", SAVE_TIME, 5_000),
    )
    repository.save(
        named,
        fixture.deflated,
        StateSaveMetadata("Named 一", SAVE_TIME.plusSeconds(1), 6_000),
    )
    repository.save(
        autosave,
        fixture.plain,
        StateSaveMetadata(savedAt = SAVE_TIME.plusSeconds(2)),
    )

    val read = repository.read(named)
    assertEquals(named, read.ref)
    assertEquals("Named 一", read.metadata?.label)
    assertEquals(fixture.file, read.state)
    assertEquals(fixture.file.diagnostics, read.inspection.diagnostics)
    assertEquals(StateMetadataCodec.sha256(fixture.deflated), read.stateSha256)
    assertNull(read.metadataWarning)
    assertFalse(read.recovery.recoveredAnything)

    val paths = listOf(slot, named, autosave).map(layout::stateFile)
    assertEquals(3, paths.toSet().size)
    assertTrue(paths.all(Files::isRegularFile))
    assertFalse(Files.exists(layout.gameDirectory.resolve("Slot ../../ label")))

    val catalog = repository.catalog(StateIdentity.from(fixture.configuration))
    assertEquals(listOf(slot, named, autosave), catalog.entries.map { it.ref })
    assertTrue(catalog.entries.all { it.status == StateCatalogStatus.AVAILABLE })
    assertTrue(catalog.entries.all { it.compatibility?.isCompatible == true })
    assertFalse(catalog.namedStatesTruncated)
    assertNull(catalog.namedStatesError)
    assertFailsWith<UnsupportedOperationException> {
      (catalog.entries as MutableList<StateCatalogEntry>).clear()
    }
  }

  @Test
  fun `catalog fully decodes corrupt states and classifies wrong rom before mutation`() {
    val fixture = machineFixture(seed = 1)
    val other = machineFixture(seed = 2)
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-catalog"))
    val repository = StateRepository(layout)
    val valid = StateRef.Slot(0)
    val corrupt =
        StateRef.Named(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    repository.save(valid, fixture.plain, StateSaveMetadata(savedAt = SAVE_TIME))
    val corruptBytes =
        fixture.plain.clone().also { it[36] = (it[36].toInt() xor 1).toByte() }
    AtomicFileWriter.system().write(layout.stateFile(corrupt), corruptBytes)
    AtomicFileWriter.system()
        .write(
            layout.metadataFile(corrupt),
            StateMetadataCodec.encode(
                StateMetadata(
                    corrupt,
                    "Damaged but identifiable",
                    SAVE_TIME,
                    null,
                    corruptBytes.size,
                    StateMetadataCodec.sha256(corruptBytes),
                    null,
                )),
        )

    val catalog = repository.catalog(StateIdentity.from(other.configuration))
    val validEntry = catalog.entries.single { it.ref == valid }
    val corruptEntry = catalog.entries.single { it.ref == corrupt }

    assertEquals(StateCatalogStatus.INCOMPATIBLE, validEntry.status)
    assertEquals(StateCompatibilityStatus.ROM_MISMATCH, validEntry.compatibility?.status)
    assertEquals(StateDecodeReason.ROM_MISMATCH, validEntry.compatibility?.reason)
    assertEquals(StateCatalogStatus.CORRUPT, corruptEntry.status)
    assertTrue(corruptEntry.detail!!.contains(StateDecodeReason.CORRUPT_CHECKSUM.name))
    assertNotNull(corruptEntry.stateSha256)
    assertEquals("Damaged but identifiable", corruptEntry.metadata?.label)
    assertNull(corruptEntry.metadataWarning)
  }

  @Test
  fun `future formats and required extensions stay incompatible with matching metadata`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-future"))
    val sectionOffsets = sectionOffsets(fixture.plain)
    val variants =
        listOf(
            StateDecodeReason.UNSUPPORTED_FORMAT_VERSION to
                fixture.plain.clone().also {
                  writeU16(it, 4, StateCodec.LATEST_FORMAT_VERSION + 1)
                },
            StateDecodeReason.UNSUPPORTED_SECTION_VERSION to
                fixture.plain.clone().also {
                  writeU16(it, sectionOffsets.first() + 2, 99)
                  refreshEnvelopeChecksum(it)
                },
            StateDecodeReason.UNSUPPORTED_FLAGS to
                fixture.plain.clone().also {
                  it[11] = 2
                },
            StateDecodeReason.UNKNOWN_REQUIRED_SECTION to
                fixture.plain.clone().also {
                  val diagnostic = sectionOffsets.last()
                  writeU16(it, diagnostic, 4)
                  writeU16(it, diagnostic + 4, 1)
                  refreshEnvelopeChecksum(it)
                },
        )

    variants.forEachIndexed { index, (reason, bytes) ->
      val ref = StateRef.Slot(index)
      val hash = StateMetadataCodec.sha256(bytes)
      AtomicFileWriter.system().write(layout.stateFile(ref), bytes)
      AtomicFileWriter.system()
          .write(
              layout.metadataFile(ref),
              StateMetadataCodec.encode(
                  StateMetadata(
                      ref,
                      "Unsupported $reason",
                      SAVE_TIME.plusSeconds(index.toLong()),
                      null,
                      bytes.size,
                      hash,
                      null,
                  )),
          )
      if (index == 0) {
        val metadataPath = layout.metadataFile(ref)
        Files.move(
            metadataPath,
            metadataPath.parent.resolve(".coffeegb-${artifactId(metadataPath)}.backup"),
            StandardCopyOption.REPLACE_EXISTING,
        )
      }
    }

    val entries = StateRepository(layout).catalog().entries.associateBy { it.ref }
    variants.forEachIndexed { index, (reason, bytes) ->
      val entry = assertNotNull(entries[StateRef.Slot(index)])
      assertEquals(StateCatalogStatus.INCOMPATIBLE, entry.status)
      assertEquals(StateCompatibilityStatus.INCOMPATIBLE, entry.compatibility?.status)
      assertEquals(reason, entry.compatibility?.reason)
      assertTrue(entry.detail!!.contains(reason.name))
      assertEquals("Unsupported $reason", entry.metadata?.label)
      assertNull(entry.metadataWarning)
      assertEquals(StateMetadataCodec.sha256(bytes), entry.stateSha256)
      if (index == 0) {
        assertTrue(assertNotNull(entry.recovery).metadata.backupRestored())
      }
    }
  }

  @Test
  fun `metadata failure preserves the new state and stale previous metadata is ignored`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-sidecar"))
    val ref = StateRef.Slot(4)
    StateRepository(layout)
        .save(ref, fixture.plain, StateSaveMetadata("old", SAVE_TIME))

    val result =
        StateRepository(layout, SelectiveFailureWriter(failMetadata = true))
            .save(
                ref,
                fixture.deflated,
                StateSaveMetadata("new", SAVE_TIME.plusSeconds(1)),
            )

    assertFalse(result.metadataCommitted)
    assertNotNull(result.metadataFailure)
    val read = StateRepository(layout).read(ref)
    assertEquals(StateMetadataCodec.sha256(fixture.deflated), read.stateSha256)
    assertNull(read.metadata)
    assertEquals(
        StateMetadataWarningReason.STATE_HASH_MISMATCH,
        read.metadataWarning?.reason,
    )

    AtomicFileWriter.system()
        .write(layout.metadataFile(ref), "not=canonical\n".toByteArray())
    val corruptMetadata = StateRepository(layout).read(ref)
    assertNull(corruptMetadata.metadata)
    assertEquals(StateMetadataWarningReason.CORRUPT, corruptMetadata.metadataWarning?.reason)
  }

  @Test
  fun `injected state write failure keeps the last valid state and metadata`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-failure"))
    val ref = StateRef.Autosave
    val repository = StateRepository(layout)
    repository.save(ref, fixture.plain, StateSaveMetadata("old", SAVE_TIME))

    assertFailsWith<IOException> {
      StateRepository(layout, SelectiveFailureWriter(failState = true))
          .save(
              ref,
              fixture.deflated,
              StateSaveMetadata("new", SAVE_TIME.plusSeconds(1)),
          )
    }

    val read = repository.read(ref)
    assertContentEquals(fixture.plain, Files.readAllBytes(layout.stateFile(ref)))
    assertEquals(StateMetadataCodec.sha256(fixture.plain), read.stateSha256)
    assertEquals("old", read.metadata?.label)
    assertNull(read.metadataWarning)
  }

  @Test
  fun `repository exposes restored backup and cleaned temporary artifact reports`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-recovery"))
    val ref = StateRef.Slot(1)
    val repository = StateRepository(layout)
    repository.save(ref, fixture.plain, StateSaveMetadata(savedAt = SAVE_TIME))
    val statePath = layout.stateFile(ref)
    val artifactId = artifactId(statePath)
    val backup = statePath.parent.resolve(".coffeegb-$artifactId.backup")
    val stale = statePath.parent.resolve(".coffeegb-$artifactId.tmp-stale.part")
    Files.move(statePath, backup, StandardCopyOption.REPLACE_EXISTING)
    Files.write(stale, byteArrayOf(1, 2, 3))

    val read = repository.read(ref)

    assertTrue(read.recovery.state.backupRestored())
    assertEquals(1, read.recovery.state.staleTemporaryFilesRemoved())
    assertTrue(read.recovery.recoveredAnything)
    assertContentEquals(fixture.plain, Files.readAllBytes(statePath))
    assertFalse(Files.exists(backup))
    assertFalse(Files.exists(stale))
  }

  @Test
  fun `rapid repeated saves remain state metadata consistent and artifact free`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-rapid"))
    val firstRepository = StateRepository(layout)
    val secondRepository = StateRepository(layout)
    val ref =
        StateRef.Named(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
    val start = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val threads =
        listOf(
            thread(start = true) {
              start.await()
              repeat(12) {
                try {
                  firstRepository.save(
                      ref,
                      fixture.plain,
                      StateSaveMetadata("plain", SAVE_TIME),
                  )
                } catch (thrown: Throwable) {
                  failure.compareAndSet(null, thrown)
                }
              }
            },
            thread(start = true) {
              start.await()
              repeat(12) {
                try {
                  secondRepository.save(
                      ref,
                      fixture.deflated,
                      StateSaveMetadata("deflated", SAVE_TIME),
                  )
                } catch (thrown: Throwable) {
                  failure.compareAndSet(null, thrown)
                }
              }
            },
        )
    start.countDown()
    threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
    assertTrue(threads.none(Thread::isAlive))
    failure.get()?.let { throw AssertionError(it) }

    val read = StateRepository(layout).read(ref)
    assertNull(read.metadataWarning)
    assertEquals(read.stateSha256, read.metadata?.stateSha256)
    val expectedLabel =
        if (read.stateSha256 == StateMetadataCodec.sha256(fixture.plain)) {
          "plain"
        } else {
          "deflated"
        }
    assertEquals(expectedLabel, read.metadata?.label)
    Files.list(layout.directory(ref)).use { files ->
      assertFalse(
          files.anyMatch {
            it.fileName.toString().startsWith(".coffeegb-")
          })
    }
  }

  @Test
  fun `named capacity is shared across repositories while overwrite remains allowed`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-capacity"))
    val firstRepository = StateRepository(layout)
    val secondRepository = StateRepository(layout)
    val existing =
        (1 until StateRepository.MAX_NAMED_STATES).map { index ->
          StateRef.Named(UUID(0, index.toLong()))
        }
    existing.forEach { ref ->
      firstRepository.save(
          ref,
          fixture.deflated,
          StateSaveMetadata(savedAt = SAVE_TIME),
      )
    }

    val candidates =
        listOf(
            StateRef.Named(UUID(1, 1)),
            StateRef.Named(UUID(1, 2)),
        )
    val start = CountDownLatch(1)
    val outcomes = ConcurrentLinkedQueue<SaveOutcome>()
    val threads =
        listOf(
            thread(start = true) {
              start.await()
              outcomes += saveOutcome(firstRepository, candidates[0], fixture.deflated)
            },
            thread(start = true) {
              start.await()
              outcomes += saveOutcome(secondRepository, candidates[1], fixture.deflated)
            },
        )
    start.countDown()
    threads.forEach { it.join(TimeUnit.SECONDS.toMillis(20)) }
    assertTrue(threads.none(Thread::isAlive))

    val successes = outcomes.filter { it.failure == null }
    val failures = outcomes.filter { it.failure != null }
    assertEquals(1, successes.size)
    assertEquals(1, failures.size)
    assertTrue(failures.single().failure is StateRepositoryCapacityException)
    assertFalse(Files.exists(layout.directory(failures.single().ref)))

    val atCapacity = StateRepository(layout).catalog()
    assertEquals(StateRepository.MAX_NAMED_STATES, atCapacity.entries.size)
    assertFalse(atCapacity.namedStatesTruncated)
    assertNull(atCapacity.namedStatesError)

    val overwrite = existing.first()
    secondRepository.save(
        overwrite,
        fixture.deflated,
        StateSaveMetadata("overwritten", SAVE_TIME.plusSeconds(1)),
    )
    assertEquals("overwritten", firstRepository.read(overwrite).metadata?.label)

    val rejected = StateRef.Named(UUID(2, 1))
    assertFailsWith<StateRepositoryCapacityException> {
      firstRepository.save(
          rejected,
          fixture.deflated,
          StateSaveMetadata(savedAt = SAVE_TIME),
      )
    }
    assertFalse(Files.exists(layout.directory(rejected)))
  }

  @Test
  fun `named-state discovery is bounded deterministic and ignores symlink directories`() {
    val fixture = machineFixture()
    val root = Files.createTempDirectory("state-repository-discovery")
    val layout = StateStorageLayout(root)
    Files.createDirectories(layout.namedDirectory)
    val emptyRefs =
        (0 until StateRepository.MAX_NAMED_STATES).map { index ->
          StateRef.Named(UUID.nameUUIDFromBytes("state-$index".toByteArray()))
        }
    emptyRefs.forEach { Files.createDirectories(layout.directory(it)) }
    val repository = StateRepository(layout)
    val valid =
        StateRef.Named(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))
    repository.save(
        valid,
        fixture.deflated,
        StateSaveMetadata("not crowded out", SAVE_TIME),
    )
    val bounded = repository.catalog()
    assertFalse(bounded.namedStatesTruncated)
    assertNull(bounded.namedStatesError)
    assertEquals(listOf(valid), bounded.entries.map { it.ref })
    assertEquals("not crowded out", bounded.entries.single().metadata?.label)

    emptyRefs.forEach { AtomicFileWriter.system().write(layout.stateFile(it), fixture.deflated) }
    val actuallyTruncated = repository.catalog()
    val expected =
        (emptyRefs + valid).sortedBy { it.id.toString() }.take(StateRepository.MAX_NAMED_STATES)
    assertTrue(actuallyTruncated.namedStatesTruncated)
    assertNull(actuallyTruncated.namedStatesError)
    assertEquals(expected, actuallyTruncated.entries.map { it.ref })

    val outside = Files.createTempDirectory("state-repository-outside")
    val symlinkRef =
        StateRef.Named(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
    val symlink = layout.directory(symlinkRef)
    try {
      Files.createSymbolicLink(symlink, outside)
    } catch (_: UnsupportedOperationException) {
      return
    } catch (_: IOException) {
      return
    }
    assertTrue(repository.catalog().entries.none { it.ref == symlinkRef })
    assertFailsWith<IOException> {
      repository.save(symlinkRef, fixture.plain, StateSaveMetadata(savedAt = SAVE_TIME))
    }
  }

  @Test
  fun `named fanout is isolated from slots and repository rejects non-machine roots`() {
    val fixture = machineFixture()
    val layout = StateStorageLayout(Files.createTempDirectory("state-repository-fanout"))
    val repository = StateRepository(layout)
    val slot = StateRef.Slot(7)
    repository.save(slot, fixture.deflated, StateSaveMetadata(savedAt = SAVE_TIME))
    repository.save(
        StateRef.Autosave,
        fixture.deflated,
        StateSaveMetadata(savedAt = SAVE_TIME),
    )
    Files.createDirectories(layout.namedDirectory)
    repeat(StateRepository.MAX_CATALOG_DIRECTORY_ENTRIES) { index ->
      Files.write(layout.namedDirectory.resolve("untrusted-$index"), byteArrayOf())
    }
    val rejected =
        StateRef.Named(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
    assertFailsWith<StateRepositoryCapacityException> {
      repository.save(
          rejected,
          fixture.deflated,
          StateSaveMetadata(savedAt = SAVE_TIME),
      )
    }
    assertFalse(Files.exists(layout.directory(rejected)))

    Files.write(
        layout.namedDirectory.resolve(
            "untrusted-${StateRepository.MAX_CATALOG_DIRECTORY_ENTRIES}"),
        byteArrayOf(),
    )
    val isolated = repository.catalog()
    assertEquals(listOf(slot, StateRef.Autosave), isolated.entries.map { it.ref })
    assertTrue(isolated.namedStatesTruncated)
    assertTrue(
        assertNotNull(isolated.namedStatesError)
            .contains(StateRepository.MAX_CATALOG_DIRECTORY_ENTRIES.toString()))

    StateCodecTestSupport.session().use { session ->
      val sessionBytes = StateCodec.encode(StateCodec.capture(session))
      val sessionLayout =
          StateStorageLayout(Files.createTempDirectory("state-session-root"))
      val sessionRef = StateRef.Slot(0)
      val failure =
          assertFailsWith<StateDecodeException> {
            StateRepository(sessionLayout)
                .save(
                    sessionRef,
                    sessionBytes,
                    StateSaveMetadata(savedAt = SAVE_TIME),
                )
          }
      assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason)
      AtomicFileWriter.system().write(sessionLayout.stateFile(sessionRef), sessionBytes)
      val entry = StateRepository(sessionLayout).catalog().entries.single()
      assertEquals(StateCatalogStatus.INCOMPATIBLE, entry.status)
      assertEquals(StateCompatibilityStatus.ROOT_MISMATCH, entry.compatibility?.status)
    }
  }

  private fun machineFixture(seed: Int = 0): MachineFixture {
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = seed))
    val gameboy = configuration.build()
    try {
      gameboy.init(EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null)
      repeat(512) { gameboy.tick() }
      val file =
          StateCodec.capture(
              configuration,
              gameboy,
              StateDiagnosticMetadata("test-core", "repository-build"),
          )
      return MachineFixture(
          configuration,
          file,
          StateCodec.encode(file, StateCompression.NONE),
          StateCodec.encode(file, StateCompression.DEFLATE),
      )
    } finally {
      gameboy.stop()
      gameboy.close()
    }
  }

  private fun saveOutcome(
      repository: StateRepository,
      ref: StateRef.Named,
      bytes: ByteArray,
  ): SaveOutcome =
      try {
        repository.save(ref, bytes, StateSaveMetadata(savedAt = SAVE_TIME))
        SaveOutcome(ref, null)
      } catch (failure: Throwable) {
        SaveOutcome(ref, failure)
      }

  private fun sectionOffsets(bytes: ByteArray): List<Int> {
    val sectionCount = ByteBuffer.wrap(bytes, 16, Int.SIZE_BYTES).int
    var offset = StateCodec.HEADER_SIZE
    return List(sectionCount) {
      val current = offset
      val length = ByteBuffer.wrap(bytes, current + 8, Long.SIZE_BYTES).long
      require(length in 0..Int.MAX_VALUE.toLong())
      offset = Math.addExact(offset, Math.addExact(StateCodec.SECTION_HEADER_SIZE, length.toInt()))
      current
    }.also { require(offset == bytes.size) }
  }

  private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    require(value in 0..0xffff)
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
  }

  private fun refreshEnvelopeChecksum(bytes: ByteArray) {
    val checksum =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(StateCodec.HEADER_SIZE, bytes.size))
    checksum.copyInto(bytes, STATE_CHECKSUM_OFFSET)
  }

  private fun artifactId(target: Path): String {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(target.fileName.toString().toByteArray(StandardCharsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private data class MachineFixture(
      val configuration: Gameboy.GameboyConfiguration,
      val file: StateFile,
      val plain: ByteArray,
      val deflated: ByteArray,
  )

  private data class SaveOutcome(
      val ref: StateRef.Named,
      val failure: Throwable?,
  )

  private class SelectiveFailureWriter(
      private val failState: Boolean = false,
      private val failMetadata: Boolean = false,
  ) : AtomicFileWriter() {
    override fun write(target: Path, intendedBytes: ByteArray) {
      if (failState && target.fileName.toString() == StateStorageLayout.STATE_FILE) {
        throw IOException("injected state disk-full failure")
      }
      if (failMetadata && target.fileName.toString() == StateStorageLayout.METADATA_FILE) {
        throw IOException("injected metadata read-only failure")
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  companion object {
    private val SAVE_TIME = Instant.parse("2026-07-28T02:03:04Z")
    private const val STATE_CHECKSUM_OFFSET = 36
  }
}
