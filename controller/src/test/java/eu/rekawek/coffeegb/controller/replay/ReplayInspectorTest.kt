package eu.rekawek.coffeegb.controller.replay

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ReplayInspectorTest {
  @Test
  fun inspectionReportsBoundedMetadataAndAuthoritativeTerminalCheckpoint() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())

    val inspection = ReplayCodec.inspect(encoded)
    val rendered = inspection.render()

    assertEquals(ReplayCodec.FORMAT_VERSION, inspection.formatVersion)
    assertTrue(inspection.checksumValid)
    assertEquals(3, inspection.inputCount)
    assertEquals(2, inspection.checkpointCount)
    assertEquals(5, inspection.finalTick)
    assertEquals(1, inspection.finalFrame)
    assertEquals("dmg", inspection.identity.canonicalProfileId)
    assertEquals("coffee-gb-test", inspection.metadata?.producerVersion)
    assertFalse(inspection.hasEmbeddedState)
    assertTrue(rendered.contains("magic=CGBR format=1 checksum=true"))
    assertTrue(rendered.contains("inputs=3 checkpoints=2 final-tick=5 final-frame=1"))
    assertTrue(rendered.contains("section=3 version=1 required=true"))
    assertFalse(rendered.contains("GameboyState"))
  }

  @Test
  fun inspectionReturnsOwnedIdentityDigestsAndImmutableSectionView() {
    val inspection = ReplayCodec.inspect(ReplayCodec.encode(ReplayTestFixture.file()))
    val original = inspection.identity.primaryRomSha256

    inspection.identity.primaryRomSha256.fill(0)

    assertTrue(original.contentEquals(inspection.identity.primaryRomSha256))
    @Suppress("UNCHECKED_CAST")
    val mutable = inspection.sections as MutableList<ReplaySectionInspection>
    assertFailsWith<UnsupportedOperationException> { mutable.clear() }
  }

  @Test
  fun unknownOptionalFeatureBitsRemainInspectableAndDoNotBlockDecode() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())
    ReplayTestFixture.writeLong(encoded, 16, 0x4000)

    val inspection = ReplayCodec.inspect(encoded)

    assertEquals(0L, inspection.requiredFeatureFlags)
    assertEquals(0x4000L, inspection.optionalFeatureFlags)
    assertTrue(inspection.render().contains("optional-features=0x4000"))
  }

  @Test
  fun renderedUntrustedTextCannotForgeLinesOrTerminalEscapes() {
    val base = ReplayTestFixture.file()
    val identity =
        ReplayIdentity(
            base.identity.primaryRomSha256,
            base.identity.slotRomSha256,
            "dmg\nforged\u001b",
            base.identity.clocks,
            base.identity.bootstrapFlags,
            base.identity.behaviorFlags,
        )
    val file =
        ReplayFile(
            identity,
            base.initialConditions,
            base.inputs,
            base.checkpoints,
            ReplayMetadata(
                "producer\rname\u2066",
                note = "note\n\u001b[31m\u2028split\u202espoof",
            ),
        )

    val rendered = ReplayCodec.inspect(ReplayCodec.encode(file)).render()

    assertFalse(rendered.contains("dmg\nforged"))
    assertFalse(rendered.contains('\u001b'))
    assertFalse(rendered.contains('\u2028'))
    assertFalse(rendered.contains('\u202e'))
    assertTrue(rendered.contains("profile=\"dmg\\nforged\\u001b\""))
    assertTrue(rendered.contains("producer=\"producer\\rname\\u2066\""))
    assertTrue(rendered.contains("note=\"note\\n\\u001b[31m\\u2028split\\u202espoof\""))
  }
}
