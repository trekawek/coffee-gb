package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure policy boundary shared by ROM replacement and application close.
 *
 * [confirm] is deliberately lazy so `NEVER` and idle `WHEN_RUNNING` actions do not construct or
 * show Swing dialogs.
 */
internal fun proceedWithRomChange(
    policy: RomChangeConfirmationPolicy,
    isRomRunning: Boolean,
    confirm: () -> Boolean,
): Boolean = !policy.shouldConfirm(isRomRunning) || confirm()

/** Cross-thread session state used only for prompt policy decisions. */
internal class RomSessionState {
  private val running = AtomicBoolean()

  fun markStarted() = running.set(true)

  fun markStopped() = running.set(false)

  fun isRunning(): Boolean = running.get()
}
