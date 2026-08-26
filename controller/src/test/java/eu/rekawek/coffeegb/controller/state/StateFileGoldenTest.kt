package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateFileGoldenTest {

  @Test
  fun committedV1FixtureDecodesMeaningfulStateAndReencodesExactly() {
    val bytes =
        checkNotNull(javaClass.getResourceAsStream("/state-file-v1/session-barcode-deflate.cgbstate"))
            .use { it.readBytes() }
    assertEquals(EXPECTED_SHA256, sha256(bytes))

    val decoded = StateCodec.decode(bytes)
    assertEquals(StateRootKind.SESSION, decoded.root.kind)
    assertEquals("golden-v1", decoded.diagnostics?.buildId)
    val identity = assertNotNull(decoded.identities.single().identity)
    assertEquals(MachineHardwareState.DMG, identity.profile.hardware)
    assertEquals("dmg", identity.profile.canonicalProfileId)
    val session = assertIs<SessionStateRoot>(decoded.root).session
    assertNull(session.machine.bootstrapOutcome)
    assertEquals(
        listOf(HeldButtonState.RIGHT, HeldButtonState.A, HeldButtonState.START),
        session.heldButtons,
    )
    assertEquals(SerialPeripheralState.BARCODE_BOY, session.serialPeripheral)
    val runtime = assertIs<BarcodeBoyRuntimeState>(session.serialRuntime)
    assertEquals(30, runtime.pendingSize)
    assertTrue(session.machine.dmgFifoRuntime != null)

    assertContentEquals(bytes, StateCodec.encode(decoded, StateCompression.DEFLATE))
    val inspection = StateFileInspector.inspect(bytes)
    assertEquals(listOf(1, 2, 3), inspection.sections.map { it.id })
    assertTrue(inspection.checksumValid)
    assertTrue(inspection.render().contains("profile=dmg"))
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private companion object {
    const val EXPECTED_SHA256 = "e5ae258c3f1a9405ca87518dbb13526def9fd3e44a4486d7a495c111958cf091"
  }
}

internal object StateFileGoldenFixture {
  fun create(): ByteArray {
    val endpoint = BarcodeBoySerialEndpoint()
    return StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      endpoint.scan("4901234567894")
      session.heldButtons = setOf(Button.RIGHT, Button.A, Button.START)
      repeat(4_321) { session.gameboy.tick() }
      StateCodec.encode(
          StateCodec.capture(
              session,
              StateDiagnosticMetadata("coffee-gb-test", "golden-v1"),
          ),
          StateCompression.DEFLATE,
      )
    }
  }
}
