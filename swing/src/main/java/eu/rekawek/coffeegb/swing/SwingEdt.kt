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
 * Admits a ROM lifecycle callback when it is received, then revalidates it on the EDT. The first
 * check permanently rejects an old session's uncorrelated Stop while a managed replacement is in
 * progress; the second rejects an otherwise-valid callback if a newer request becomes visible
 * while its runnable is queued.
 */
internal fun dispatchAcceptedRomLifecycle(
    openRequestId: Long?,
    accept: (Long?) -> Boolean,
    action: () -> Unit,
) {
  if (!accept(openRequestId)) return
  dispatchSwingMutation {
    if (accept(openRequestId)) {
      action()
    }
  }
}
