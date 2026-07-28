package eu.rekawek.coffeegb.swing

import javax.swing.SwingUtilities

/** Preserves callback order while making Swing ownership explicit at one boundary. */
internal fun dispatchSwingMutation(action: () -> Unit) {
  if (SwingUtilities.isEventDispatchThread()) {
    action()
  } else {
    SwingUtilities.invokeLater(action)
  }
}

/**
 * Correlates a ROM lifecycle callback only after it reaches the EDT. A newer request may become
 * visible while this runnable is queued, so checking on the controller thread is not sufficient.
 */
internal fun dispatchAcceptedRomLifecycle(
    openRequestId: Long?,
    accept: (Long?) -> Boolean,
    action: () -> Unit,
) {
  dispatchSwingMutation {
    if (accept(openRequestId)) {
      action()
    }
  }
}
