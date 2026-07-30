package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import java.util.EnumSet
import java.util.prefs.Preferences

/** Only harmless presentation choices are eligible for desktop debugger persistence. */
internal data class DebuggerUiPreferences(
    val bounds: DebuggerWindowBounds? = null,
    val cpuScalarDivider: Int = -1,
    val cpuCodeDivider: Int = -1,
    val cpuVerticalDivider: Int = -1,
    val selectedPane: Int = 0,
    val fontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val timelineCategories: Set<TraceCategory> = DEFAULT_TIMELINE_CATEGORIES,
    val timelineCapacity: Int = DEFAULT_TIMELINE_CAPACITY,
) {
  fun sanitized(): DebuggerUiPreferences =
      copy(
          bounds = bounds?.takeIf(DebuggerWindowBounds::isValid),
          cpuScalarDivider = sanitizeDivider(cpuScalarDivider),
          cpuCodeDivider = sanitizeDivider(cpuCodeDivider),
          cpuVerticalDivider = sanitizeDivider(cpuVerticalDivider),
          selectedPane = selectedPane.coerceIn(0, MAX_SELECTED_PANE),
          fontScalePercent =
              fontScalePercent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT),
          timelineCategories = immutableCategories(timelineCategories),
          timelineCapacity =
              timelineCapacity.coerceIn(MIN_TIMELINE_CAPACITY, MAX_TIMELINE_CAPACITY),
      )

  companion object {
    const val DEFAULT_FONT_SCALE_PERCENT = 100
    const val MIN_FONT_SCALE_PERCENT = 70
    const val MAX_FONT_SCALE_PERCENT = 200
    const val FONT_SCALE_STEP = 10
    const val DEFAULT_TIMELINE_CAPACITY = 2_000
    const val MIN_TIMELINE_CAPACITY = 64
    const val MAX_TIMELINE_CAPACITY = DebuggerTimelineTableModel.MAX_RETAINED_ROWS
    const val MAX_SELECTED_PANE = 5
    val DEFAULT_TIMELINE_CATEGORIES: Set<TraceCategory> =
        immutableCategories(EnumSet.of(TraceCategory.INTERRUPT, TraceCategory.PPU, TraceCategory.INPUT))

    private fun sanitizeDivider(value: Int): Int = value.takeIf { it in 0..10_000 } ?: -1

    private fun immutableCategories(values: Set<TraceCategory>): Set<TraceCategory> =
        if (values.isEmpty()) emptySet() else EnumSet.copyOf(values).toSet()
  }
}

internal data class DebuggerWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
  fun isValid(): Boolean =
      x in -100_000..100_000 &&
          y in -100_000..100_000 &&
          width in 900..10_000 &&
          height in 620..10_000
}

/** Minimal backend keeps the persisted key allow-list visible and independently testable. */
internal interface DebuggerPreferenceNode {
  fun get(key: String): String?

  fun put(key: String, value: String)
}

private class JavaDebuggerPreferenceNode(
    private val preferences: Preferences =
        Preferences.userNodeForPackage(DebuggerWindow::class.java).node("debugger"),
) : DebuggerPreferenceNode {
  override fun get(key: String): String? = preferences.get(key, null)

  override fun put(key: String, value: String) {
    preferences.put(key, value)
  }
}

internal class DebuggerPreferencesStore(
    private val node: DebuggerPreferenceNode = JavaDebuggerPreferenceNode(),
) {
  fun load(): DebuggerUiPreferences =
      runCatching {
            val hasBounds = node.get(KEY_WIDTH) != null && node.get(KEY_HEIGHT) != null
            DebuggerUiPreferences(
                    bounds =
                        if (hasBounds) {
                          DebuggerWindowBounds(
                              integer(KEY_X, 0),
                              integer(KEY_Y, 0),
                              integer(KEY_WIDTH, -1),
                              integer(KEY_HEIGHT, -1),
                          )
                        } else {
                          null
                        },
                    cpuScalarDivider = integer(KEY_CPU_SCALAR_DIVIDER, -1),
                    cpuCodeDivider = integer(KEY_CPU_CODE_DIVIDER, -1),
                    cpuVerticalDivider = integer(KEY_CPU_VERTICAL_DIVIDER, -1),
                    selectedPane = integer(KEY_SELECTED_PANE, 0),
                    fontScalePercent =
                        integer(KEY_FONT_SCALE, DebuggerUiPreferences.DEFAULT_FONT_SCALE_PERCENT),
                    timelineCategories = categories(),
                    timelineCapacity =
                        integer(
                            KEY_TIMELINE_CAPACITY,
                            DebuggerUiPreferences.DEFAULT_TIMELINE_CAPACITY,
                        ),
                )
                .sanitized()
          }
          .getOrDefault(DebuggerUiPreferences())

  fun save(value: DebuggerUiPreferences) {
    val safe = value.sanitized()
    runCatching {
      safe.bounds?.let { bounds ->
        node.put(KEY_X, bounds.x.toString())
        node.put(KEY_Y, bounds.y.toString())
        node.put(KEY_WIDTH, bounds.width.toString())
        node.put(KEY_HEIGHT, bounds.height.toString())
      }
      node.put(KEY_CPU_SCALAR_DIVIDER, safe.cpuScalarDivider.toString())
      node.put(KEY_CPU_CODE_DIVIDER, safe.cpuCodeDivider.toString())
      node.put(KEY_CPU_VERTICAL_DIVIDER, safe.cpuVerticalDivider.toString())
      node.put(KEY_SELECTED_PANE, safe.selectedPane.toString())
      node.put(KEY_FONT_SCALE, safe.fontScalePercent.toString())
      node.put(
          KEY_TIMELINE_CATEGORIES,
          safe.timelineCategories.map(TraceCategory::name).sorted().joinToString(","),
      )
      node.put(KEY_TIMELINE_CAPACITY, safe.timelineCapacity.toString())
    }
  }

  private fun integer(key: String, fallback: Int): Int = node.get(key)?.toIntOrNull() ?: fallback

  private fun categories(): Set<TraceCategory> {
    val encoded =
        node.get(KEY_TIMELINE_CATEGORIES)
            ?: return DebuggerUiPreferences.DEFAULT_TIMELINE_CATEGORIES
    return encoded
        .split(',')
        .mapNotNull { name -> TraceCategory.entries.firstOrNull { it.name == name } }
        .toSet()
  }

  companion object {
    internal const val KEY_X = "window-x"
    internal const val KEY_Y = "window-y"
    internal const val KEY_WIDTH = "window-width"
    internal const val KEY_HEIGHT = "window-height"
    internal const val KEY_CPU_SCALAR_DIVIDER = "cpu-scalar-divider"
    internal const val KEY_CPU_CODE_DIVIDER = "cpu-code-divider"
    internal const val KEY_CPU_VERTICAL_DIVIDER = "cpu-vertical-divider"
    internal const val KEY_SELECTED_PANE = "selected-pane"
    internal const val KEY_FONT_SCALE = "font-scale-percent"
    internal const val KEY_TIMELINE_CATEGORIES = "timeline-categories"
    internal const val KEY_TIMELINE_CAPACITY = "timeline-capacity"

    internal val SAFE_KEYS =
        setOf(
            KEY_X,
            KEY_Y,
            KEY_WIDTH,
            KEY_HEIGHT,
            KEY_CPU_SCALAR_DIVIDER,
            KEY_CPU_CODE_DIVIDER,
            KEY_CPU_VERTICAL_DIVIDER,
            KEY_SELECTED_PANE,
            KEY_FONT_SCALE,
            KEY_TIMELINE_CATEGORIES,
            KEY_TIMELINE_CAPACITY,
        )
  }
}
