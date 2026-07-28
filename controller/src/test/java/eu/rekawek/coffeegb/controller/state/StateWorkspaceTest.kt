package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateWorkspaceTest {

  @Test
  fun `stable slots prefer active source while fallback delete and export retain identity`() {
    val fixture = fixture()
    val active = StateStorageLayout(Files.createTempDirectory("workspace-active"))
    val fallback = StateStorageLayout(Files.createTempDirectory("workspace-fallback"))
    val fallbackRepository = StateRepository(fallback)
    fallbackRepository.save(
        StateRef.Slot(3),
        fixture.bytes,
        StateSaveMetadata("old slot", SAVE_TIME),
    )
    val workspace =
        StateWorkspace(
            StateStoragePaths(active, active.screenshotsDirectory, listOf(fallback)))

    val initial = workspace.catalog(fixture.identity)
    assertEquals(StateRef.SLOT_COUNT, initial.entries.take(StateRef.SLOT_COUNT).size)
    val fallbackRow = initial.entries.single { it.ref == StateRef.Slot(3) }
    assertEquals(1, fallbackRow.key.sourceIndex)
    assertTrue(fallbackRow.canLoad)
    assertEquals(fixture.file, workspace.read(fallbackRow.key).state)
    val fallbackRead = workspace.readFirst(StateRef.Slot(3))!!
    assertEquals(1, fallbackRead.first.sourceIndex)
    assertEquals(fixture.file, fallbackRead.second.state)

    workspace.save(
        StateRef.Slot(3),
        fixture.bytes,
        StateSaveMetadata("active slot", SAVE_TIME.plusSeconds(1)),
        null,
    )
    val activeRow =
        workspace.catalog(fixture.identity).entries.single { it.ref == StateRef.Slot(3) }
    assertEquals(0, activeRow.key.sourceIndex)
    assertEquals("active slot", activeRow.catalogEntry?.metadata?.label)
    assertEquals(0, workspace.readFirst(StateRef.Slot(3))!!.first.sourceIndex)

    Files.write(active.stateFile(StateRef.Slot(3)), byteArrayOf(1, 2, 3, 4))
    assertFailsWith<StateDecodeException> {
      workspace.readFirst(StateRef.Slot(3))
    }

    val export = Files.createTempDirectory("workspace-export").resolve("old.cgbstate")
    workspace.export(fallbackRow.key, export)
    assertContentEquals(fixture.bytes, Files.readAllBytes(export))
    workspace.delete(fallbackRow.key)
    assertFalse(Files.exists(fallback.stateFile(StateRef.Slot(3))))
    assertTrue(Files.exists(active.stateFile(StateRef.Slot(3))))
  }

  @Test
  fun `thumbnail is hash-bound and a later thumbnail-free save removes the old asset`() {
    val fixture = fixture()
    val layout = StateStorageLayout(Files.createTempDirectory("workspace-thumbnail"))
    val repository = StateRepository(layout)
    val ref = StateRef.Slot(0)
    val thumbnail =
        StatePngCodec.encode(
            StateImage(2, 1, intArrayOf(0x112233, 0xaabbcc)).thumbnail())

    val saved =
        repository.saveWithThumbnail(
            ref,
            fixture.bytes,
            StateSaveMetadata("with preview", SAVE_TIME),
            thumbnail,
        )
    assertTrue(saved.thumbnailCommitted)
    val metadata = repository.read(ref).metadata!!
    val thumbnailPath = layout.thumbnailFile(ref, metadata.stateSha256)
    assertTrue(Files.isRegularFile(thumbnailPath))
    assertContentEquals(
        thumbnail,
        repository
            .readThumbnail(ref, metadata.stateSha256, metadata.thumbnailSha256!!)
            .copyBytes(),
    )

    repository.saveWithThumbnail(
        ref,
        fixture.bytes,
        StateSaveMetadata("without preview", SAVE_TIME.plusSeconds(1)),
        null,
    )
    assertNull(repository.read(ref).metadata?.thumbnailSha256)
    assertFalse(Files.exists(thumbnailPath))
  }

  private fun fixture(): Fixture {
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = 7))
    val gameboy = configuration.build()
    try {
      gameboy.init(EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null)
      repeat(128) { gameboy.tick() }
      val file =
          StateCodec.capture(
              configuration,
              gameboy,
              StateDiagnosticMetadata("test-core", "workspace-test"),
          )
      return Fixture(
          file,
          StateCodec.encode(file, StateCompression.DEFLATE),
          StateIdentity.from(configuration),
      )
    } finally {
      gameboy.stop()
      gameboy.close()
    }
  }

  private data class Fixture(
      val file: StateFile,
      val bytes: ByteArray,
      val identity: MachineIdentity,
  )

  private companion object {
    val SAVE_TIME: Instant = Instant.parse("2026-07-28T03:00:00Z")
  }
}
