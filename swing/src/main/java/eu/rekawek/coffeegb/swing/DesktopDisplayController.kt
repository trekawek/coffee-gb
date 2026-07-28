package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetBlendingEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetColorCorrectionEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetGrayscaleEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetLetterboxColorEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetRotationEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetScaleModeEvent
import java.awt.Color
import javax.swing.SwingUtilities

/**
 * Single EDT owner for persisted display choices and their live Swing/fullscreen application.
 *
 * Menu actions and Preferences both enter through this class. [DisplaySettingsChangedEvent] then
 * updates every passive view without action-listener feedback loops.
 */
internal class DesktopDisplayController(
    private val settings: DisplaySettingsAccess,
    private val eventBus: EventBus,
    private val fullscreenRuntime: FullscreenRuntime,
    private val windowSizingRuntime: DisplayWindowSizingRuntime = DisplayWindowSizingRuntime.NOOP,
) {
  constructor(
      properties: EmulatorProperties,
      eventBus: EventBus,
      fullscreenRuntime: FullscreenRuntime,
      windowSizingRuntime: DisplayWindowSizingRuntime = DisplayWindowSizingRuntime.NOOP,
  ) : this(
      EmulatorDisplaySettingsAccess(properties),
      eventBus,
      fullscreenRuntime,
      windowSizingRuntime,
  )

  fun current(): ApplicationSettings.Display = settings.current()

  fun applyCurrent() = apply(current(), persist = false)

  fun update(transform: (ApplicationSettings.Display) -> ApplicationSettings.Display) {
    requireEdt()
    apply(transform(current()), persist = true)
  }

  fun apply(
      display: ApplicationSettings.Display,
      persist: Boolean,
  ) {
    requireEdt()
    if (persist) {
      settings.replace(display)
    }

    val enteringFullscreen = display.fullscreen && !fullscreenRuntime.isFullscreen()
    val exitingFullscreen = !display.fullscreen && fullscreenRuntime.isFullscreen()

    // A large windowed explicit/SGB minimum must not constrain borderless fullscreen bounds.
    // Conversely, restore the current dynamic minimum after exit even when scale/rotation did not
    // change and SwingDisplay therefore correctly suppressed its ordinary size event.
    if (enteringFullscreen) {
      windowSizingRuntime.refresh(windowed = false)
    }
    if (exitingFullscreen) {
      fullscreenRuntime.setFullscreen(false)
    }
    eventBus.post(SetScaleModeEvent(display.toRuntimeScaleMode()))
    eventBus.post(SetRotationEvent(display.rotation.degrees))
    eventBus.post(SetGrayscaleEvent(display.grayscale))
    eventBus.post(SetBlendingEvent(display.blending))
    eventBus.post(SetColorCorrectionEvent(display.colorCorrection))
    eventBus.post(SetLetterboxColorEvent(Color(display.letterboxColor)))
    eventBus.post(SgbDisplay.SetSgbBorder(display.showSgbBorder))
    if (enteringFullscreen) {
      fullscreenRuntime.setFullscreen(true)
    }
    if (exitingFullscreen) {
      windowSizingRuntime.refresh(windowed = true)
    }
    eventBus.post(DisplaySettingsChangedEvent(display))
  }

  fun setFullscreen(fullscreen: Boolean) {
    requireEdt()
    if (fullscreen == current().fullscreen && fullscreen == fullscreenRuntime.isFullscreen()) {
      return
    }
    update { it.copy(fullscreen = fullscreen) }
  }

  fun isFullscreen(): Boolean = fullscreenRuntime.isFullscreen()

  fun close() {
    requireEdt()
    fullscreenRuntime.close()
  }

  private fun requireEdt() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Display settings must be applied on the Event Dispatch Thread"
    }
  }
}

internal interface DisplaySettingsAccess {
  fun current(): ApplicationSettings.Display

  fun replace(display: ApplicationSettings.Display)
}

internal fun interface DisplayWindowSizingRuntime {
  fun refresh(windowed: Boolean)

  companion object {
    val NOOP = DisplayWindowSizingRuntime {}
  }
}

private class EmulatorDisplaySettingsAccess(
    private val properties: EmulatorProperties,
) : DisplaySettingsAccess {
  override fun current(): ApplicationSettings.Display = properties.applicationSettings.display

  override fun replace(display: ApplicationSettings.Display) {
    properties.updateApplicationSettings { settings -> settings.copy(display = display) }
  }
}

internal data class DisplaySettingsChangedEvent(
    val display: ApplicationSettings.Display,
) : Event

internal fun ApplicationSettings.Display.toRuntimeScaleMode(): DisplayScaleMode =
    when (scalingMode) {
      ApplicationSettings.DisplayScalingMode.INTEGER_FIT -> DisplayScaleMode.INTEGER_FIT
      ApplicationSettings.DisplayScalingMode.ASPECT_FIT -> DisplayScaleMode.ASPECT_FIT
      ApplicationSettings.DisplayScalingMode.EXPLICIT ->
          DisplayScaleMode.explicit(explicitScale)
    }
