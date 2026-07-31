package eu.rekawek.coffeegb.swing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopUiStateStoreTest {
  @Test
  fun `round trip writes only the complete harmless allow list`() {
    val node = MapDesktopUiStateNode()
    val store = DesktopUiStateStore(node)
    val expected =
        DesktopUiState(
            mainWindow =
                DesktopMainWindowState(
                    normalBounds = DesktopBounds(-1710, 45, 960, 720),
                    maximized = true,
                ),
            utilityBounds =
                mapOf(
                    DesktopUtilityWindow.PREFERENCES to DesktopBounds(40, 60, 760, 620),
                    DesktopUtilityWindow.NETPLAY to DesktopBounds(80, 100, 720, 540),
                    DesktopUtilityWindow.STATES to DesktopBounds(120, 140, 900, 640),
                    DesktopUtilityWindow.MOBILE_ADAPTER to DesktopBounds(160, 180, 680, 560),
                    DesktopUtilityWindow.PRINTER to DesktopBounds(200, 220, 520, 700),
                ),
            lastPreferencesCategory = DesktopPreferencesCategory.SAVES_AND_REWIND,
        )

    assertTrue(store.save(expected))

    assertEquals(DesktopUiStateStore.SAFE_KEYS, node.values.keys)
    assertEquals(
        DesktopUiStateStore.CURRENT_SCHEMA_VERSION.toString(),
        node.values[DesktopUiStateStore.KEY_SCHEMA_VERSION],
    )
    assertFalse(
        node.values.keys.any { key ->
          listOf(
                  "visible",
                  "token",
                  "clipboard",
                  "path",
                  "content",
                  "draft",
                  "notification",
                  "session",
              )
              .any(key::contains)
        })
    assertEquals(expected, DesktopUiStateStore(node).load())
  }

  @Test
  fun `unknown schema version ignores otherwise valid values`() {
    val node =
        MapDesktopUiStateNode(
            mutableMapOf(
                DesktopUiStateStore.KEY_SCHEMA_VERSION to "99",
                "main-x" to "20",
                "main-y" to "30",
                "main-width" to "800",
                "main-height" to "600",
                DesktopUiStateStore.KEY_MAIN_MAXIMIZED to "true",
                DesktopUiStateStore.KEY_PREFERENCES_CATEGORY to "DISPLAY",
            ))

    assertEquals(DesktopUiState(), DesktopUiStateStore(node).load())
    assertEquals(
        setOf(DesktopUiStateStore.KEY_SCHEMA_VERSION),
        node.readKeys,
        "a foreign schema is not partially interpreted",
    )
  }

  @Test
  fun `malformed and out of range fields fail closed independently`() {
    val node =
        MapDesktopUiStateNode(
            mutableMapOf(
                DesktopUiStateStore.KEY_SCHEMA_VERSION to "1",
                "main-x" to "100001",
                "main-y" to "0",
                "main-width" to "800",
                "main-height" to "600",
                DesktopUiStateStore.KEY_MAIN_MAXIMIZED to "TRUE",
                "preferences-x" to "30",
                "preferences-y" to "40",
                "preferences-width" to "319",
                "preferences-height" to "600",
                "netplay-x" to "50",
                "netplay-y" to "60",
                "netplay-width" to "not-a-number",
                "netplay-height" to "500",
                "states-x" to "-100000",
                "states-y" to "100000",
                "states-width" to "320",
                "states-height" to "240",
                DesktopUiStateStore.KEY_PREFERENCES_CATEGORY to "NETWORK_AND_SECRETS",
            ))

    val state = DesktopUiStateStore(node).load()

    assertNull(state.mainWindow.normalBounds)
    assertFalse(state.mainWindow.maximized)
    assertNull(state.bounds(DesktopUtilityWindow.PREFERENCES))
    assertNull(state.bounds(DesktopUtilityWindow.NETPLAY))
    assertEquals(
        DesktopBounds(-100_000, 100_000, 320, 240),
        state.bounds(DesktopUtilityWindow.STATES),
    )
    assertEquals(DesktopPreferencesCategory.GENERAL, state.lastPreferencesCategory)
  }

  @Test
  fun `saving absent or implausible bounds removes stale geometry`() {
    val node = MapDesktopUiStateNode()
    val store = DesktopUiStateStore(node)
    assertTrue(
        store.save(
            DesktopUiState(
                mainWindow = DesktopMainWindowState(DesktopBounds(10, 20, 800, 600), true),
                utilityBounds =
                    mapOf(
                        DesktopUtilityWindow.NETPLAY to DesktopBounds(30, 40, 700, 500),
                    ),
            )))

    assertTrue(
        store.save(
            DesktopUiState(
                mainWindow =
                    DesktopMainWindowState(
                        normalBounds = DesktopBounds(100_001, 20, 800, 600),
                    ),
                utilityBounds =
                    mapOf(
                        DesktopUtilityWindow.NETPLAY to DesktopBounds(30, 40, 10_001, 500),
                    ),
                lastPreferencesCategory = DesktopPreferencesCategory.AUDIO,
            )))

    assertEquals(
        setOf(
            DesktopUiStateStore.KEY_SCHEMA_VERSION,
            DesktopUiStateStore.KEY_MAIN_MAXIMIZED,
            DesktopUiStateStore.KEY_PREFERENCES_CATEGORY,
        ),
        node.values.keys,
    )
    assertEquals(
        DesktopUiState(lastPreferencesCategory = DesktopPreferencesCategory.AUDIO),
        store.load(),
    )
  }

  @Test
  fun `backend failure is contained and leaves no committed schema marker`() {
    val node = MapDesktopUiStateNode(failOnPut = DesktopUiStateStore.KEY_MAIN_MAXIMIZED)

    assertFalse(DesktopUiStateStore(node).save(DesktopUiState()))
    assertNull(node.values[DesktopUiStateStore.KEY_SCHEMA_VERSION])
    assertEquals(DesktopUiState(), DesktopUiStateStore(node).load())
  }

  private class MapDesktopUiStateNode(
      val values: MutableMap<String, String> = mutableMapOf(),
      private val failOnPut: String? = null,
  ) : DesktopUiStateNode {
    val readKeys = mutableSetOf<String>()

    override fun get(key: String): String? {
      readKeys += key
      return values[key]
    }

    override fun put(key: String, value: String) {
      if (key == failOnPut) error("simulated preferences failure")
      values[key] = value
    }

    override fun remove(key: String) {
      values.remove(key)
    }
  }
}
