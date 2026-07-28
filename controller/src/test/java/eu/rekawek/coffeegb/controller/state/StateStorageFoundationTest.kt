package eu.rekawek.coffeegb.controller.state

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateStorageFoundationTest {

  @Test
  fun `references and layout use only fixed bounded collision-safe components`() {
    val root = Files.createTempDirectory("coffee-gb-状態").resolve(".game .. folder")
    val layout = StateStorageLayout(root)
    val slot = StateRef.Slot(0)
    val named = StateRef.Named(UUID.fromString("12345678-1234-5678-9abc-def012345678"))
    val autosave = StateRef.Autosave

    assertEquals(slot, StateRef.parseStorageKey("slot:0"))
    assertEquals(named, StateRef.parseStorageKey(named.storageKey()))
    assertEquals(autosave, StateRef.parseStorageKey("autosave"))
    assertEquals("state.cgbstate", layout.stateFile(slot).fileName.toString())
    assertEquals("state.cgbstate", layout.stateFile(named).fileName.toString())
    assertEquals("state.cgbstate", layout.stateFile(autosave).fileName.toString())
    assertEquals("metadata.properties", layout.metadataFile(named).fileName.toString())
    assertTrue(layout.stateFile(slot).startsWith(layout.gameDirectory))
    assertTrue(layout.stateFile(named).startsWith(layout.gameDirectory))
    assertTrue(layout.stateFile(autosave).startsWith(layout.gameDirectory))
    assertEquals(3, setOf(layout.stateFile(slot), layout.stateFile(named), layout.stateFile(autosave)).size)
    assertEquals(named, layout.parseNamedDirectoryName(named.id.toString()))
    assertNull(layout.parseNamedDirectoryName("../../escape"))
    assertNull(layout.parseNamedDirectoryName(named.id.toString().uppercase()))
    assertEquals(
        "thumbnail-${"a".repeat(64)}.png",
        layout.thumbnailFile(named, "a".repeat(64)).fileName.toString(),
    )
  }

  @Test
  fun `references reject traversal aliases noncanonical values and out of range slots`() {
    listOf(-1, 10).forEach { index ->
      assertFailsWith<IllegalArgumentException> { StateRef.Slot(index) }
    }
    listOf(
            "slot:00",
            "slot:+1",
            "slot:../../1",
            "named:../../escape",
            "named:12345678-1234-5678-9ABC-DEF012345678",
            "named:not-a-uuid",
            "autosave/other",
        )
        .forEach { value ->
          assertFailsWith<IllegalArgumentException>("Expected rejection for $value") {
            StateRef.parseStorageKey(value)
          }
        }
    assertFailsWith<IllegalArgumentException> {
      StateStorageLayout(Path.of("/"))
    }
    assertFailsWith<IllegalArgumentException> {
      StateStorageLayout(Files.createTempDirectory("state-layout"))
          .thumbnailFile(StateRef.Autosave, "A".repeat(64))
    }
  }

  @Test
  fun `metadata round trips canonical unicode values and binds state bytes`() {
    val metadata =
        StateMetadata(
            ref =
                StateRef.Named(
                    UUID.fromString("12345678-1234-5678-9abc-def012345678")),
            label = "Pokémon 一 / .. remains a label",
            savedAt = Instant.parse("2026-07-28T01:02:03.456Z"),
            playDurationNanos = 123_456_789L,
            stateBytes = 4_096,
            stateSha256 = "a".repeat(64),
            thumbnailSha256 = "b".repeat(64),
        )

    val first = StateMetadataCodec.encode(metadata)
    val second = StateMetadataCodec.encode(metadata)

    assertContentEquals(first, second)
    assertEquals(metadata, StateMetadataCodec.decode(first))
    val text = first.toString(StandardCharsets.US_ASCII)
    assertFalse(text.contains('#'))
    assertEquals(
        text.lines().filter(String::isNotEmpty).sorted(),
        text.lines().filter(String::isNotEmpty),
    )
  }

  @Test
  fun `metadata rejects unbounded malformed noncanonical and unknown input`() {
    assertFailsWith<IllegalArgumentException> {
      StateSaveMetadata("x".repeat(121), Instant.EPOCH)
    }
    assertFailsWith<IllegalArgumentException> {
      StateSaveMetadata("line\nbreak", Instant.EPOCH)
    }
    assertFailsWith<IllegalArgumentException> {
      StateSaveMetadata("\uD800", Instant.EPOCH)
    }
    assertFailsWith<IllegalArgumentException> {
      StateSaveMetadata(savedAt = Instant.EPOCH, playDurationNanos = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      StateSaveMetadata(
          savedAt = Instant.EPOCH,
          thumbnailSha256 = "A".repeat(64),
      )
    }
    assertFailsWith<StateMetadataException> {
      StateMetadataCodec.decode(ByteArray(StateMetadataCodec.MAX_METADATA_BYTES + 1))
    }

    val valid =
        StateMetadataCodec.encode(
            StateMetadata(
                StateRef.Slot(1),
                "valid",
                Instant.EPOCH,
                null,
                100,
                "c".repeat(64),
                null,
            ))
    listOf(
            valid + "plugin.unknown=value\n".toByteArray(),
            "state.sha256=${"c".repeat(64)}\n".toByteArray() + valid,
            valid.toString(StandardCharsets.US_ASCII)
                .replace("1970-01-01T00\\:00\\:00Z", "1970-01-01T00\\:00\\:00.000Z")
                .toByteArray(StandardCharsets.US_ASCII),
        )
        .forEach { malformed ->
          assertFailsWith<StateMetadataException> {
            StateMetadataCodec.decode(malformed)
          }
        }
  }

  @Test
  fun `sha256 helper is deterministic lowercase and content-sensitive`() {
    val first = StateMetadataCodec.sha256("first".toByteArray())
    val again = StateMetadataCodec.sha256("first".toByteArray())
    val second = StateMetadataCodec.sha256("second".toByteArray())

    assertEquals(first, again)
    assertEquals(64, first.length)
    assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    assertTrue(first != second)
  }
}
