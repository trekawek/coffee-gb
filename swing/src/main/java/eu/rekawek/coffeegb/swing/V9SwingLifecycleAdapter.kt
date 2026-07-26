package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleListener
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleSnapshot
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * EDT boundary for the opt-in v9 lifecycle.
 *
 * The controller publishes platform-neutral immutable values. This adapter is deliberately not
 * wired into the user-visible netplay flow until the remaining manifest/consent work lands.
 */
class V9SwingLifecycleAdapter(
    source: V9LifecycleSource,
    private val consumer: (V9LifecycleSnapshot) -> Unit,
) : Closeable {
  private val closed = AtomicBoolean(false)
  private val subscription =
      source.addListener(
          V9LifecycleListener { value ->
            SwingUtilities.invokeLater {
              if (!closed.get()) consumer(value)
            }
          },
      )

  override fun close() {
    if (closed.compareAndSet(false, true)) subscription.close()
  }
}
