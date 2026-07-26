package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StateFileV2GoldenTest {

  @Test
  fun committedSgb2V2FixtureDecodesAndReencodesExactly() {
    if (java.lang.Boolean.getBoolean("updateStateFileV2Golden")) {
      val bytes = createFixture()
      val path = Path.of("src/test/resources/state-file-v2/sgb2-session-deflate.cgbstate")
      Files.createDirectories(path.parent)
      Files.write(path, bytes)
      throw AssertionError("Updated $path sha256=${sha256(bytes)}; review and pin the hash")
    }

    val bytes =
        checkNotNull(javaClass.getResourceAsStream("/state-file-v2/sgb2-session-deflate.cgbstate"))
            .use { it.readBytes() }
    assertEquals(EXPECTED_SHA256, sha256(bytes))
    val decoded = StateCodec.decode(bytes)
    assertEquals(2, decoded.formatVersion)
    assertEquals(StateRootKind.SESSION, decoded.root.kind)
    assertEquals("sgb2", decoded.identities.single().identity!!.profile.canonicalProfileId)
    assertEquals("golden-v2-sgb2", decoded.diagnostics?.buildId)
    assertTrue(StateCodec.inspect(bytes).render().contains("format=2"))
    assertContentEquals(bytes, StateCodec.encode(decoded, StateCompression.DEFLATE))
  }

  private fun createFixture(): ByteArray {
    val configuration =
        StateCodecTestSupport.configuration(
                StateCodecTestSupport.rom(seed = 2, sgb = true),
                GameboyType.SGB,
            )
            .setHardwareProfile(HardwareProfileRegistry.SGB2)
    return StateCodecTestSupport.session(configuration).use { session ->
      repeat(4_321) { session.gameboy.tick() }
      StateCodec.encode(
          StateCodec.capture(
              session,
              StateDiagnosticMetadata("coffee-gb-test", "golden-v2-sgb2"),
          ),
          StateCompression.DEFLATE,
      )
    }
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes)
          .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private companion object {
    const val EXPECTED_SHA256 = "2d2178e6eba26a8debdacf84be144cccd1b42e50bf0dbce5c41612bcb16aa226"
  }
}
