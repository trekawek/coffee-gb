package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class RomChangeConfirmationTest {

  @Test
  fun `policy gates both accepted and rejected confirmation decisions`() {
    RomChangeConfirmationPolicy.entries.forEach { policy ->
      listOf(false, true).forEach { running ->
        var prompts = 0
        val shouldPrompt = policy.shouldConfirm(running)

        val rejected =
            proceedWithRomChange(policy, running) {
              prompts++
              false
            }
        assertEquals(if (shouldPrompt) 1 else 0, prompts)
        assertEquals(!shouldPrompt, rejected)

        prompts = 0
        val accepted =
            proceedWithRomChange(policy, running) {
              prompts++
              true
            }
        assertTrue(accepted)
        assertEquals(if (shouldPrompt) 1 else 0, prompts)
      }
    }
  }

  @Test
  fun `session state publishes controller lifecycle transitions to UI readers`() {
    val state = RomSessionState()

    assertEquals(false, state.isRunning())
    state.markStarted()
    assertEquals(true, state.isRunning())
    state.markStopped()
    assertEquals(false, state.isRunning())
  }
}
