package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9Diagnostic
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9Lifecycle
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState
import eu.rekawek.coffeegb.controller.network.v9.V9Role
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class V9SwingLifecycleAdapterTest {

  @Test
  fun everyLifecycleCallbackIsMarshalledToTheEdtAndCloseStopsDelivery() {
    val lifecycle = V9Lifecycle(V9Role.CLIENT)
    val callbackCount = AtomicInteger()
    val allOnEdt = AtomicBoolean(true)
    val delivered = CountDownLatch(2)
    val adapter =
        V9SwingLifecycleAdapter(lifecycle) { snapshot ->
          allOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
          callbackCount.incrementAndGet()
          if (snapshot.state in
              setOf(V9LifecycleState.WAIT_SERVER_HELLO, V9LifecycleState.CLOSED)) {
            delivered.countDown()
          }
        }
    lifecycle.fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
    assertTrue(delivered.await(3, TimeUnit.SECONDS))
    assertTrue(allOnEdt.get())
    assertEquals(2, callbackCount.get())

    adapter.close()
    lifecycle.closeNormally()
    SwingUtilities.invokeAndWait {}
    assertEquals(2, callbackCount.get())
  }

  @Test
  fun controllerV9SourcesHaveNoSwingOrAwtDependency() {
    val root = repositoryRoot()
    val sourceRoot =
        root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/network/v9")
    val sources =
        Files.walk(sourceRoot).use { paths ->
          paths.filter { Files.isRegularFile(it) }
              .map { Files.readString(it, StandardCharsets.UTF_8) }
              .toList()
              .joinToString("\n")
        }
    assertFalse(sources.contains("java.awt"))
    assertFalse(sources.contains("javax.swing"))
  }

  private fun repositoryRoot(): Path {
    var current = Path.of("").toAbsolutePath()
    while (!Files.exists(current.resolve("pom.xml")) ||
        !Files.exists(current.resolve("controller"))) {
      current = current.parent ?: error("repository root not found")
    }
    return current
  }
}
