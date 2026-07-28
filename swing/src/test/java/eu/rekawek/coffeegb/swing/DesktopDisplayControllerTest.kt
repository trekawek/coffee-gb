package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DesktopDisplayControllerTest {
  @Test
  fun `one coordinator persists and publishes the complete live display state`() {
    val initial = ApplicationSettings.Display()
    val settings = FakeDisplaySettings(initial)
    val fullscreen = FakeFullscreenRuntime()
    val eventBus = EventBusImpl()
    var scaleMode: DisplayScaleMode? = null
    var rotation: Int? = null
    var grayscale: Boolean? = null
    var blending: Boolean? = null
    var colorCorrection: Boolean? = null
    var letterbox: Color? = null
    var sgbBorder: Boolean? = null
    var synchronized: ApplicationSettings.Display? = null
    eventBus.register<SwingDisplay.SetScaleModeEvent> { scaleMode = it.mode() }
    eventBus.register<SwingDisplay.SetRotationEvent> { rotation = it.rotation() }
    eventBus.register<SwingDisplay.SetGrayscaleEvent> { grayscale = it.grayscale() }
    eventBus.register<SwingDisplay.SetBlendingEvent> { blending = it.blending() }
    eventBus.register<SwingDisplay.SetColorCorrectionEvent> {
      colorCorrection = it.colorCorrection()
    }
    eventBus.register<SwingDisplay.SetLetterboxColorEvent> { letterbox = it.color() }
    eventBus.register<SgbDisplay.SetSgbBorder> { sgbBorder = it.borderEnabled() }
    eventBus.register<DisplaySettingsChangedEvent> { synchronized = it.display }

    val updated =
        initial.copy(
            scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
            explicitScale = 3,
            letterboxColor = 0x123456,
            fullscreen = true,
            rotation = ApplicationSettings.Rotation.DEG_270,
            grayscale = true,
            blending = false,
            colorCorrection = false,
            showSgbBorder = true,
        )
    lateinit var controller: DesktopDisplayController
    SwingUtilities.invokeAndWait {
      controller = DesktopDisplayController(settings, eventBus, fullscreen)
      controller.apply(updated, persist = true)
    }

    assertEquals(updated, settings.display)
    assertEquals(1, settings.replacements)
    assertEquals(DisplayScaleMode.EXPLICIT_3X, scaleMode)
    assertEquals(270, rotation)
    assertEquals(true, grayscale)
    assertEquals(false, blending)
    assertEquals(false, colorCorrection)
    assertEquals(Color(0x123456), letterbox)
    assertEquals(true, sgbBorder)
    assertEquals(updated, synchronized)
    assertTrue(fullscreen.fullscreenState)

    SwingUtilities.invokeAndWait {
      controller.update {
        it.copy(
            scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
            fullscreen = false,
        )
      }
      controller.close()
    }
    assertEquals(DisplayScaleMode.INTEGER_FIT, scaleMode)
    assertFalse(fullscreen.fullscreenState)
    assertTrue(fullscreen.closed)
    eventBus.close()
  }

  @Test
  fun `applying an already persisted preference draft does not write it twice`() {
    val display =
        ApplicationSettings.Display(
            scalingMode = ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
            letterboxColor = 0x010203,
        )
    val settings = FakeDisplaySettings(display)
    val fullscreen = FakeFullscreenRuntime()
    val eventBus = EventBusImpl()
    var published: ApplicationSettings.Display? = null
    eventBus.register<DisplaySettingsChangedEvent> { published = it.display }

    SwingUtilities.invokeAndWait {
      DesktopDisplayController(settings, eventBus, fullscreen)
          .apply(display, persist = false)
    }

    assertEquals(0, settings.replacements)
    assertEquals(display, published)
    eventBus.close()
  }

  @Test
  fun `fullscreen boundaries refresh sizing around transitions even when geometry events repeat`() {
    val initial = ApplicationSettings.Display(fullscreen = true)
    val settings = FakeDisplaySettings(initial)
    val operations = mutableListOf<String>()
    val fullscreen =
        FakeFullscreenRuntime(fullscreenState = true) { enabled ->
          operations += "fullscreen:$enabled"
        }
    val eventBus = EventBusImpl()
    eventBus.register<SwingDisplay.SetScaleModeEvent> { operations += "scale" }
    val controller =
        onEdt {
          DesktopDisplayController(
              settings,
              eventBus,
              fullscreen,
              DisplayWindowSizingRuntime { windowed -> operations += "windowed:$windowed" },
          )
        }

    onEdt { controller.apply(initial.copy(fullscreen = false), persist = true) }
    assertEquals(listOf("fullscreen:false", "scale", "windowed:true"), operations)

    operations.clear()
    onEdt { controller.apply(initial.copy(fullscreen = true), persist = true) }
    assertEquals(listOf("windowed:false", "scale", "fullscreen:true"), operations)

    // A source/explicit-size change while fullscreen uses the base fullscreen minimum. Exiting
    // must still force a windowed sizing refresh even if the subsequent scale event is unchanged.
    operations.clear()
    val larger = initial.copy(explicitScale = 4, fullscreen = true)
    onEdt { controller.apply(larger, persist = true) }
    assertEquals(listOf("scale"), operations)

    operations.clear()
    onEdt { controller.apply(larger.copy(fullscreen = false), persist = true) }
    assertEquals(listOf("fullscreen:false", "scale", "windowed:true"), operations)
    eventBus.close()
  }

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }

  private class FakeDisplaySettings(
      var display: ApplicationSettings.Display,
  ) : DisplaySettingsAccess {
    var replacements = 0

    override fun current(): ApplicationSettings.Display = display

    override fun replace(display: ApplicationSettings.Display) {
      this.display = display
      replacements++
    }
  }

  private class FakeFullscreenRuntime(
      var fullscreenState: Boolean = false,
      private val onSet: (Boolean) -> Unit = {},
  ) : FullscreenRuntime {
    var closed = false

    override fun isFullscreen(): Boolean = fullscreenState

    override fun setFullscreen(fullscreen: Boolean) {
      this.fullscreenState = fullscreen
      onSet(fullscreen)
    }

    override fun refreshScreens() = Unit

    override fun close() {
      closed = true
    }
  }
}
