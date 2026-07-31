package eu.rekawek.coffeegb.swing

import java.util.prefs.Preferences

/** Retained desktop windows whose harmless normal bounds may survive a restart. */
internal enum class DesktopUtilityWindow(val storagePrefix: String) {
  PREFERENCES("preferences"),
  NETPLAY("netplay"),
  STATES("states"),
  MOBILE_ADAPTER("mobile-adapter"),
  PRINTER("printer"),
}

/** Stable identifiers for the first Preferences category navigation model. */
internal enum class DesktopPreferencesCategory {
  GENERAL,
  DISPLAY,
  AUDIO,
  CONTROLS,
  SAVES_AND_REWIND,
  SYSTEM,
  PERIPHERALS,
}

/**
 * Harmless main-window presentation state.
 *
 * [normalBounds] always describes the decorated outer frame while it is neither maximized nor
 * fullscreen. Transient maximized/fullscreen geometry must never replace it.
 */
internal data class DesktopMainWindowState(
    val normalBounds: DesktopBounds? = null,
    val maximized: Boolean = false,
)

/**
 * The complete deliberately small desktop UI-state document.
 *
 * It has no place for visibility, paths, drafts, clipboard/network data, notifications, or
 * window content. Debugger state remains in its existing feature-owned stores.
 */
internal data class DesktopUiState(
    val mainWindow: DesktopMainWindowState = DesktopMainWindowState(),
    val utilityBounds: Map<DesktopUtilityWindow, DesktopBounds> = emptyMap(),
    val lastPreferencesCategory: DesktopPreferencesCategory = DesktopPreferencesCategory.GENERAL,
) {
  fun bounds(window: DesktopUtilityWindow): DesktopBounds? = utilityBounds[window]
}

/** Minimal backend keeps every persisted key independently testable. */
internal interface DesktopUiStateNode {
  fun get(key: String): String?

  fun put(key: String, value: String)

  fun remove(key: String)
}

private class JavaDesktopUiStateNode(
    private val preferences: Preferences =
        Preferences.userNodeForPackage(DesktopUiStateStore::class.java).node("desktop-ui-state"),
) : DesktopUiStateNode {
  override fun get(key: String): String? = preferences.get(key, null)

  override fun put(key: String, value: String) {
    preferences.put(key, value)
  }

  override fun remove(key: String) {
    preferences.remove(key)
  }
}

/**
 * Versioned persistence for harmless desktop presentation state only.
 *
 * Unknown schema versions and malformed values fail closed to defaults. Reads, writes, and
 * removals are restricted to [SAFE_KEYS]; all geometry is independently range-checked before it
 * enters or leaves the store.
 */
internal class DesktopUiStateStore(
    private val node: DesktopUiStateNode = JavaDesktopUiStateNode(),
) {
  fun load(): DesktopUiState =
      runCatching {
            if (integer(KEY_SCHEMA_VERSION) != CURRENT_SCHEMA_VERSION) {
              return@runCatching DesktopUiState()
            }
            DesktopUiState(
                mainWindow =
                    DesktopMainWindowState(
                        normalBounds = readBounds(MAIN_PREFIX, MAIN_MINIMUM_SIZE),
                        maximized = strictBoolean(KEY_MAIN_MAXIMIZED) ?: false,
                    ),
                utilityBounds =
                    DesktopUtilityWindow.entries
                        .mapNotNull { window ->
                          readBounds(window.storagePrefix, UTILITY_MINIMUM_SIZE)?.let { bounds ->
                            window to bounds
                          }
                        }
                        .toMap(),
                lastPreferencesCategory =
                    value(KEY_PREFERENCES_CATEGORY)?.let { encoded ->
                      DesktopPreferencesCategory.entries.firstOrNull { it.name == encoded }
                    } ?: DesktopPreferencesCategory.GENERAL,
            )
          }
          .getOrDefault(DesktopUiState())

  /** Returns false when the platform preferences backend rejects the update. */
  fun save(state: DesktopUiState): Boolean =
      runCatching {
            // Treat the schema key as a commit marker so an interrupted first write is ignored.
            remove(KEY_SCHEMA_VERSION)
            writeBounds(MAIN_PREFIX, state.mainWindow.normalBounds, MAIN_MINIMUM_SIZE)
            put(KEY_MAIN_MAXIMIZED, state.mainWindow.maximized.toString())
            DesktopUtilityWindow.entries.forEach { window ->
              writeBounds(
                  window.storagePrefix,
                  state.utilityBounds[window],
                  UTILITY_MINIMUM_SIZE,
              )
            }
            put(KEY_PREFERENCES_CATEGORY, state.lastPreferencesCategory.name)
            put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION.toString())
          }
          .isSuccess

  private fun readBounds(prefix: String, minimumSize: DesktopSize): DesktopBounds? {
    val x = integer(boundsKey(prefix, X_SUFFIX)) ?: return null
    val y = integer(boundsKey(prefix, Y_SUFFIX)) ?: return null
    val width = integer(boundsKey(prefix, WIDTH_SUFFIX)) ?: return null
    val height = integer(boundsKey(prefix, HEIGHT_SUFFIX)) ?: return null
    val bounds = runCatching { DesktopBounds(x, y, width, height) }.getOrNull() ?: return null
    return bounds.takeIf { DesktopWindowPlacement.isPlausible(it, minimumSize) }
  }

  private fun writeBounds(
      prefix: String,
      bounds: DesktopBounds?,
      minimumSize: DesktopSize,
  ) {
    val keys = BOUNDS_SUFFIXES.map { suffix -> boundsKey(prefix, suffix) }
    val safeBounds = bounds?.takeIf { DesktopWindowPlacement.isPlausible(it, minimumSize) }
    if (safeBounds == null) {
      keys.forEach(::remove)
      return
    }
    put(keys[0], safeBounds.x.toString())
    put(keys[1], safeBounds.y.toString())
    put(keys[2], safeBounds.width.toString())
    put(keys[3], safeBounds.height.toString())
  }

  private fun integer(key: String): Int? = value(key)?.toIntOrNull()

  private fun strictBoolean(key: String): Boolean? =
      when (value(key)) {
        "true" -> true
        "false" -> false
        else -> null
      }

  private fun value(key: String): String? {
    requireSafeKey(key)
    return node.get(key)
  }

  private fun put(key: String, value: String) {
    requireSafeKey(key)
    node.put(key, value)
  }

  private fun remove(key: String) {
    requireSafeKey(key)
    node.remove(key)
  }

  private fun requireSafeKey(key: String) {
    check(key in SAFE_KEYS) { "Desktop UI state key is not allow-listed: $key" }
  }

  companion object {
    internal const val CURRENT_SCHEMA_VERSION = 1

    internal val MAIN_MINIMUM_SIZE = DesktopSize(320, 288)
    internal val UTILITY_MINIMUM_SIZE = DesktopSize(320, 240)

    internal const val KEY_SCHEMA_VERSION = "schema-version"
    internal const val KEY_MAIN_MAXIMIZED = "main-maximized"
    internal const val KEY_PREFERENCES_CATEGORY = "preferences-category"

    private const val MAIN_PREFIX = "main"
    private const val X_SUFFIX = "x"
    private const val Y_SUFFIX = "y"
    private const val WIDTH_SUFFIX = "width"
    private const val HEIGHT_SUFFIX = "height"
    private val BOUNDS_SUFFIXES = listOf(X_SUFFIX, Y_SUFFIX, WIDTH_SUFFIX, HEIGHT_SUFFIX)

    internal fun boundsKey(prefix: String, suffix: String): String = "$prefix-$suffix"

    internal val SAFE_KEYS: Set<String> =
        buildSet {
          add(KEY_SCHEMA_VERSION)
          add(KEY_MAIN_MAXIMIZED)
          add(KEY_PREFERENCES_CATEGORY)
          BOUNDS_SUFFIXES.forEach { suffix -> add(boundsKey(MAIN_PREFIX, suffix)) }
          DesktopUtilityWindow.entries.forEach { window ->
            BOUNDS_SUFFIXES.forEach { suffix -> add(boundsKey(window.storagePrefix, suffix)) }
          }
        }
  }
}
