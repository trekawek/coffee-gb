package eu.rekawek.coffeegb.swing

import java.awt.Color
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Theme-independent roles for application chrome, controls, and status presentation.
 *
 * These colors must not be used to tint the emulated raster, printer pixels, hardware palettes,
 * tile/object data, Wave RAM plots, or any other value whose color carries emulated-data meaning.
 */
internal data class DesktopThemeTokens(
    val surface: Color,
    val elevatedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val border: Color,
    val focus: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val spacing: DesktopSpacingTokens = DesktopSpacingTokens(),
) {
  companion object {
    /** Captures one immutable semantic snapshot after the requested look and feel is installed. */
    fun capture(appearance: DesktopAppearance): DesktopThemeTokens {
      val surfaceFallback =
          if (appearance == DesktopAppearance.DARK) DARK_SURFACE else LIGHT_SURFACE
      val surface = uiColor("Panel.background", "control") ?: surfaceFallback
      val dark = desktopRelativeLuminance(surface) < DARK_SURFACE_THRESHOLD
      val elevatedCandidate =
          uiColor("TextField.background", "Table.background", "List.background") ?: surface
      val elevatedSurface = distinctElevatedSurface(elevatedCandidate, surface, dark)

      val primaryFallback = if (dark) Color.WHITE else Color.BLACK
      val primaryText =
          ensureContrast(
              uiColor("Label.foreground", "textText") ?: primaryFallback,
              surface,
              MINIMUM_TEXT_CONTRAST,
          )
      val secondaryText =
          ensureContrast(
              uiColor("Label.disabledForeground", "textInactiveText")
                  ?: if (dark) DARK_SECONDARY_TEXT else LIGHT_SECONDARY_TEXT,
              surface,
              MINIMUM_TEXT_CONTRAST,
          )
      val border =
          ensureContrast(
              uiColor("Component.borderColor", "Separator.foreground", "controlShadow")
                  ?: if (dark) DARK_BORDER else LIGHT_BORDER,
              surface,
              MINIMUM_NON_TEXT_CONTRAST,
          )
      val focus =
          ensureContrast(
              uiColor("Component.focusColor", "Focus.color", "Table.selectionBackground")
                  ?: if (dark) DARK_ACCENT else LIGHT_ACCENT,
              surface,
              MINIMUM_NON_TEXT_CONTRAST,
          )
      val accent =
          ensureContrast(
              if (dark) DARK_ACCENT else LIGHT_ACCENT,
              surface,
              MINIMUM_TEXT_CONTRAST,
          )

      return DesktopThemeTokens(
          surface = surface,
          elevatedSurface = elevatedSurface,
          primaryText = primaryText,
          secondaryText = secondaryText,
          border = border,
          focus = focus,
          accent = accent,
          onAccent = highestContrast(Color.BLACK, Color.WHITE, accent),
          success =
              ensureContrast(
                  if (dark) DARK_SUCCESS else LIGHT_SUCCESS,
                  surface,
                  MINIMUM_TEXT_CONTRAST,
              ),
          warning =
              ensureContrast(
                  if (dark) DARK_WARNING else LIGHT_WARNING,
                  surface,
                  MINIMUM_TEXT_CONTRAST,
              ),
          danger =
              ensureContrast(
                  if (dark) DARK_DANGER else LIGHT_DANGER,
                  surface,
                  MINIMUM_TEXT_CONTRAST,
              ),
      )
    }

    private val LIGHT_SURFACE = Color(0xF7F4F1)
    private val DARK_SURFACE = Color(0x2C2928)
    private val LIGHT_SECONDARY_TEXT = Color(0x5C4E49)
    private val DARK_SECONDARY_TEXT = Color(0xC8C2BF)
    private val LIGHT_BORDER = Color(0x746B67)
    private val DARK_BORDER = Color(0x9D9490)
    private val LIGHT_ACCENT = Color(0xA6422B)
    private val DARK_ACCENT = Color(0xED9277)
    private val LIGHT_SUCCESS = Color(0x256F3A)
    private val DARK_SUCCESS = Color(0x78D69A)
    private val LIGHT_WARNING = Color(0x805500)
    private val DARK_WARNING = Color(0xF2C14E)
    private val LIGHT_DANGER = Color(0xB3261E)
    private val DARK_DANGER = Color(0xFFB4AB)
    private const val DARK_SURFACE_THRESHOLD = 0.30
    internal const val MINIMUM_TEXT_CONTRAST = 4.5
    internal const val MINIMUM_NON_TEXT_CONTRAST = 3.0

    private fun uiColor(vararg keys: String): Color? =
        keys.firstNotNullOfOrNull { key -> UIManager.getColor(key)?.opaqueCopy() }

    private fun distinctElevatedSurface(
        candidate: Color,
        surface: Color,
        dark: Boolean,
    ): Color {
      val opaqueCandidate = candidate.opaqueCopy()
      if (opaqueCandidate.rgb != surface.rgb) {
        return opaqueCandidate
      }
      return blend(surface, if (dark) Color.WHITE else Color.BLACK, ELEVATION_BLEND)
    }

    private fun ensureContrast(
        candidate: Color,
        background: Color,
        minimum: Double,
    ): Color {
      val opaqueCandidate = candidate.opaqueCopy()
      if (desktopContrastRatio(opaqueCandidate, background) >= minimum) {
        return opaqueCandidate
      }
      val target = highestContrast(Color.BLACK, Color.WHITE, background)
      for (step in 1..CONTRAST_ADJUSTMENT_STEPS) {
        val adjusted = blend(opaqueCandidate, target, step.toDouble() / CONTRAST_ADJUSTMENT_STEPS)
        if (desktopContrastRatio(adjusted, background) >= minimum) {
          return adjusted
        }
      }
      return target
    }

    private fun highestContrast(first: Color, second: Color, background: Color): Color =
        if (desktopContrastRatio(first, background) >= desktopContrastRatio(second, background)) {
          first
        } else {
          second
        }

    private fun blend(first: Color, second: Color, amount: Double): Color {
      val bounded = amount.coerceIn(0.0, 1.0)
      fun channel(a: Int, b: Int): Int = (a + (b - a) * bounded).roundToInt().coerceIn(0, 255)
      return Color(
          channel(first.red, second.red),
          channel(first.green, second.green),
          channel(first.blue, second.blue),
      )
    }

    private fun Color.opaqueCopy(): Color = Color(red, green, blue)

    private const val ELEVATION_BLEND = 0.06
    private const val CONTRAST_ADJUSTMENT_STEPS = 100
  }
}

/** Small logical-pixel scale; Java/FlatLaf applies platform DPI scaling to these values. */
internal data class DesktopSpacingTokens(
    val compact: Int = 4,
    val related: Int = 8,
    val controlGap: Int = 12,
    val section: Int = 16,
    val dialogEdge: Int = 24,
) {
  init {
    require(listOf(compact, related, controlGap, section, dialogEdge).zipWithNext().all {
      (first, second) -> first > 0 && first < second
    })
  }
}

internal fun desktopContrastRatio(foreground: Color, background: Color): Double {
  val foregroundLuminance = desktopRelativeLuminance(foreground)
  val backgroundLuminance = desktopRelativeLuminance(background)
  return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
      (min(foregroundLuminance, backgroundLuminance) + 0.05)
}

/** Initial semantic error color for panels created after the current LAF was installed. */
internal fun desktopValidationErrorColor(): Color =
    DesktopThemeTokens.capture(DesktopAppearance.SYSTEM).danger

private fun desktopRelativeLuminance(color: Color): Double {
  fun linear(channel: Int): Double {
    val component = channel / 255.0
    return if (component <= 0.04045) component / 12.92
    else ((component + 0.055) / 1.055).pow(2.4)
  }
  return 0.2126 * linear(color.red) +
      0.7152 * linear(color.green) +
      0.0722 * linear(color.blue)
}
