package eu.rekawek.coffeegb.controller.state

import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StateDiagnosticRedactorTest {
  @Test
  fun `redacts explicit unix windows and home paths before bounding text`() {
    val root = Paths.get("/private/users/alice/save-root")
    val input =
        "failed at $root/games/hash/state.cgbstate, " +
            "C:\\Users\\Alice\\Desktop\\export.cgbstate; /var/tmp/secret.part\u0000\n" +
            "x".repeat(2_000)

    val redacted = StateDiagnosticRedactor.redact(input, listOf(root), 240)

    assertFalse(redacted.contains(root.toString()))
    assertFalse(redacted.contains("C:\\Users\\Alice"))
    assertFalse(redacted.contains("/var/tmp"))
    assertFalse(redacted.contains('\u0000'))
    assertTrue(redacted.contains("<path>"))
    assertTrue(redacted.length <= 240)
  }

  @Test
  fun `redacts complete inside and outside unix paths containing spaces`() {
    val root = Paths.get("/private/User Data/Coffee GB Saves")
    val input =
        "inside $root/Account One/state.cgbstate; " +
            "outside /var/private/Other Account/secret state.cgbstate, retry"

    val redacted = StateDiagnosticRedactor.redact(input, listOf(root))

    assertFalse(redacted.contains(root.toString()))
    assertFalse(redacted.contains("Account One"))
    assertFalse(redacted.contains("Other Account"))
    assertFalse(redacted.contains("secret state.cgbstate"))
    assertTrue(redacted.contains("<path>"))
    assertTrue(redacted.endsWith("retry"))
  }
}
