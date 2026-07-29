package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopWindowSizeControllerTest {

  @Test
  fun `no saved size retains the packed baseline and a later resize persists exactly`() {
    val settings = FakeSettings()
    val host = FakeHost(size = DesktopSize(480, 360))
    val scheduler = FakeScheduler()
    val controller = controller(settings, host, scheduler)

    controller.restore()
    controller.install()

    assertTrue(host.resizedTo.isEmpty())
    assertTrue(settings.replacements.isEmpty())
    host.fireResize()
    assertTrue(settings.replacements.isEmpty(), "the initial component event is not a user resize")

    host.size = DesktopSize(733, 519)
    host.fireResize()
    assertTrue(settings.replacements.isEmpty(), "live resize is coalesced off the event callback")
    scheduler.fire()
    assertEquals(ApplicationSettings.WindowSize(733, 519), settings.current())
    assertEquals(listOf(ApplicationSettings.WindowSize(733, 519)), settings.replacements)
  }

  @Test
  fun `restoration clamps dimensions independently to the current usable range`() {
    val settings = FakeSettings(ApplicationSettings.WindowSize(100, Int.MAX_VALUE))
    val host =
        FakeHost(
            size = DesktopSize(480, 360),
            minimum = DesktopSize(300, 200),
            maximum = DesktopSize(800, 600),
        )
    val controller = controller(settings, host)

    controller.restore()

    assertEquals(listOf(DesktopSize(300, 600)), host.resizedTo)
    assertEquals(ApplicationSettings.WindowSize(300, 600), settings.current())
    assertEquals(listOf(ApplicationSettings.WindowSize(300, 600)), settings.replacements)

    assertEquals(
        DesktopSize(200, 150),
        clampRestoredWindowSize(
            ApplicationSettings.WindowSize(Int.MAX_VALUE, Int.MAX_VALUE),
            minimum = DesktopSize(300, 200),
            maximum = DesktopSize(200, 150),
        ),
        "a tiny work area wins over the ordinary frame minimum",
    )
  }

  @Test
  fun `restoration persists the size actually accepted by the host`() {
    val saved = ApplicationSettings.WindowSize(700, 500)
    val settings = FakeSettings(saved)
    val host =
        FakeHost(
            size = DesktopSize(480, 360),
            resizeAdjustment = { DesktopSize(it.width + 16, it.height + 9) },
        )

    controller(settings, host).restore()

    assertEquals(listOf(DesktopSize(700, 500)), host.resizedTo)
    assertEquals(ApplicationSettings.WindowSize(716, 509), settings.current())
    assertEquals(listOf(ApplicationSettings.WindowSize(716, 509)), settings.replacements)
  }

  @Test
  fun `fullscreen maximized and iconified resize events never replace the normal size`() {
    val saved = ApplicationSettings.WindowSize(640, 480)
    val settings = FakeSettings(saved)
    val host = FakeHost(size = DesktopSize(400, 300))
    val controller = controller(settings, host)
    controller.restore()
    controller.install()

    host.normalWindow = false
    host.size = DesktopSize(1_920, 1_080)
    host.fireResize()
    controller.scheduler.fire()
    assertEquals(saved, settings.current())
    assertTrue(settings.replacements.isEmpty())

    host.normalWindow = true
    host.size = DesktopSize(640, 480)
    host.fireWindowStateChange()
    controller.scheduler.fire()
    assertTrue(settings.replacements.isEmpty(), "restoring the old normal bounds is not a change")

    host.size = DesktopSize(701, 503)
    host.fireResize()
    controller.scheduler.fire()
    assertEquals(ApplicationSettings.WindowSize(701, 503), settings.current())
  }

  @Test
  fun `resize and state events in either order retain only confirmed normal geometry`() {
    val saved = ApplicationSettings.WindowSize(640, 480)
    val settings = FakeSettings(saved)
    val host = FakeHost(size = DesktopSize(400, 300))
    val controller = controller(settings, host)
    controller.restore()
    controller.install()

    // X11 may deliver maximized bounds before the separate window-state transition.
    host.size = DesktopSize(1_920, 1_040)
    host.fireResize()
    host.normalWindow = false
    host.fireWindowStateChange()
    controller.scheduler.fire()
    assertTrue(settings.replacements.isEmpty())

    // The inverse ordering is also valid while restoring a normal window.
    host.size = DesktopSize(684, 517)
    host.fireResize()
    host.normalWindow = true
    host.fireWindowStateChange()
    controller.scheduler.fire()
    assertEquals(
        listOf(ApplicationSettings.WindowSize(684, 517)),
        settings.replacements,
    )
  }

  @Test
  fun `close captures an undelivered final resize and removes the listener before disposal`() {
    val settings = FakeSettings()
    val host = FakeHost(size = DesktopSize(480, 360))
    val controller = controller(settings, host)
    controller.install()

    host.size = DesktopSize(777, 555)
    controller.close()

    assertEquals(ApplicationSettings.WindowSize(777, 555), settings.current())
    assertEquals(1, host.removedListeners)
    host.size = DesktopSize(999, 888)
    host.fireResize()
    assertEquals(ApplicationSettings.WindowSize(777, 555), settings.current())
    assertEquals(1, settings.replacements.size)

    controller.close()
    assertEquals(1, host.removedListeners, "close is idempotent")
  }

  @Test
  fun `suspend flushes and resume tracks geometry after a recoverable close failure`() {
    val settings = FakeSettings()
    val host = FakeHost(size = DesktopSize(480, 360))
    val controller = controller(settings, host)
    controller.install()

    host.size = DesktopSize(700, 500)
    host.fireResize()
    controller.suspend()
    assertEquals(ApplicationSettings.WindowSize(700, 500), settings.current())
    assertEquals(1, host.removedListeners)

    controller.resume()
    host.size = DesktopSize(720, 540)
    host.fireResize()
    controller.scheduler.fire()
    assertEquals(ApplicationSettings.WindowSize(720, 540), settings.current())
    assertEquals(2, settings.replacements.size)
  }

  @Test
  fun `close before installation cannot overwrite a saved size with partial startup geometry`() {
    val saved = ApplicationSettings.WindowSize(640, 480)
    val settings = FakeSettings(saved)
    val host = FakeHost(size = DesktopSize(1, 1))
    val controller = controller(settings, host)

    controller.close()

    assertEquals(saved, settings.current())
    assertTrue(settings.replacements.isEmpty())
    assertEquals(0, host.removedListeners)
  }

  @Test
  fun `read only settings ignore restoration resize and close mutations`() {
    val saved = ApplicationSettings.WindowSize(100, 900)
    val settings = FakeSettings(saved, writable = false)
    val host =
        FakeHost(
            size = DesktopSize(480, 360),
            minimum = DesktopSize(300, 200),
            maximum = DesktopSize(800, 600),
        )
    val controller = controller(settings, host)

    controller.restore()
    controller.install()
    host.size = DesktopSize(711, 522)
    host.fireResize()
    controller.scheduler.fire()
    controller.close()

    assertEquals(saved, settings.current())
    assertTrue(settings.replacements.isEmpty())
  }

  @Test
  fun `resize bursts commit only their latest normal size`() {
    val settings = FakeSettings()
    val host = FakeHost()
    val controller = controller(settings, host)
    controller.install()

    repeat(25) { index ->
      host.size = DesktopSize(600 + index, 400 + index)
      host.fireResize()
    }

    assertTrue(settings.replacements.isEmpty())
    assertEquals(25, controller.scheduler.restartCount)
    controller.scheduler.fire()
    assertEquals(
        listOf(ApplicationSettings.WindowSize(624, 424)),
        settings.replacements,
    )
  }

  @Test
  fun `controller rejects every restore listen and close mutation away from the EDT`() {
    val settings = FakeSettings(ApplicationSettings.WindowSize(640, 480))
    val host = FakeHost()
    val controller =
        DesktopWindowSizeController(
            settings,
            host,
            edtOwnership = EdtOwnership { false },
        )

    assertFailsWith<IllegalStateException> { controller.restore() }
    assertFailsWith<IllegalStateException> { controller.install() }
    assertFailsWith<IllegalStateException> { controller.close() }
    assertNull(host.listener)
    assertTrue(settings.replacements.isEmpty())
  }

  private fun controller(
      settings: FakeSettings,
      host: FakeHost,
      scheduler: FakeScheduler = FakeScheduler(),
  ): TestController {
    val controller =
        DesktopWindowSizeController(
            settings,
            host,
            commitScheduler = scheduler,
            edtOwnership = EdtOwnership { true },
        )
    return TestController(controller, scheduler)
  }

  private data class TestController(
      val delegate: DesktopWindowSizeController,
      val scheduler: FakeScheduler,
  ) {
    fun restore() = delegate.restore()

    fun install() = delegate.install()

    fun suspend() = delegate.suspend()

    fun resume() = delegate.resume()

    fun close() = delegate.close()
  }

  private class FakeSettings(
      private var size: ApplicationSettings.WindowSize? = null,
      private val writable: Boolean = true,
  ) : DesktopWindowSizeSettings {
    val replacements = mutableListOf<ApplicationSettings.WindowSize>()

    override fun current(): ApplicationSettings.WindowSize? = size

    override fun canPersist(): Boolean = writable

    override fun replace(size: ApplicationSettings.WindowSize) {
      this.size = size
      replacements += size
    }
  }

  private class FakeHost(
      var size: DesktopSize = DesktopSize(480, 360),
      var minimum: DesktopSize = DesktopSize(172, 181),
      var maximum: DesktopSize = DesktopSize(1_920, 1_040),
      var normalWindow: Boolean = true,
      var resizeAdjustment: (DesktopSize) -> DesktopSize = { it },
  ) : DesktopWindowSizeHost {
    val resizedTo = mutableListOf<DesktopSize>()
    var listener: (() -> Unit)? = null
    var removedListeners = 0

    override fun currentSize(): DesktopSize = size

    override fun minimumSize(): DesktopSize = minimum

    override fun maximumSize(): DesktopSize = maximum

    override fun resize(size: DesktopSize) {
      this.size = resizeAdjustment(size)
      resizedTo += size
    }

    override fun isNormalWindow(): Boolean = normalWindow

    override fun listen(listener: () -> Unit): DesktopWindowResizeSubscription {
      check(this.listener == null)
      this.listener = listener
      return DesktopWindowResizeSubscription {
        if (this.listener != null) {
          this.listener = null
          removedListeners++
        }
      }
    }

    fun fireResize() = listener?.invoke()

    fun fireWindowStateChange() = listener?.invoke()
  }

  private class FakeScheduler : DesktopWindowSizeCommitScheduler {
    private var action: (() -> Unit)? = null
    var restartCount = 0
      private set

    override fun restart(action: () -> Unit) {
      this.action = action
      restartCount++
    }

    override fun cancel() {
      action = null
    }

    fun fire() {
      val pending = action
      action = null
      pending?.invoke()
    }
  }
}
