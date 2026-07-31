package eu.rekawek.coffeegb.swing

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Color
import java.awt.Component
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.UnsupportedLookAndFeelException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.Test

class DesktopThemeManagerTest {

  @Test
  fun `light dark and system install through FlatLaf and expose semantic tokens`() {
    val previousLookAndFeel = UIManager.getLookAndFeel()?.javaClass?.name
    val refreshed = mutableListOf<DesktopThemeTokens>()
    try {
      onEdt {
        val manager =
            DesktopThemeManager(
                refreshRuntime = DesktopThemeRefreshRuntime(refreshed::add),
            )

        val light = manager.apply(DesktopAppearance.LIGHT)
        assertTrue(UIManager.getLookAndFeel() is FlatLightLaf)
        assertSemanticContrast(light.tokens)

        val dark = manager.apply(DesktopAppearance.DARK)
        assertTrue(UIManager.getLookAndFeel() is FlatDarkLaf)
        assertSemanticContrast(dark.tokens)

        val system = manager.apply(DesktopAppearance.SYSTEM)
        assertEquals(
            UIManager.getSystemLookAndFeelClassName(),
            UIManager.getLookAndFeel().javaClass.name,
        )
        assertEquals(DesktopAppearance.SYSTEM, system.effectiveAppearance)
        assertNull(system.fallback)
        assertSame(system, manager.current)

        assertEquals(listOf(light.tokens, dark.tokens, system.tokens), refreshed)
      }
    } finally {
      if (previousLookAndFeel != null) {
        onEdt { UIManager.setLookAndFeel(previousLookAndFeel) }
      }
    }
  }

  @Test
  fun `failed FlatLaf selection falls back to system and all work stays on EDT`() {
    val installations = mutableListOf<DesktopAppearance>()
    val tokens = testTokens()
    var tokenCaptureOnEdt = false
    var refreshOnEdt = false
    val manager =
        DesktopThemeManager(
            lookAndFeelInstaller =
                DesktopLookAndFeelInstaller { appearance ->
                  assertTrue(SwingUtilities.isEventDispatchThread())
                  installations += appearance
                  if (appearance == DesktopAppearance.LIGHT) {
                    throw UnsupportedLookAndFeelException("environment-specific detail")
                  }
                },
            tokenCapture = { effective ->
              tokenCaptureOnEdt = SwingUtilities.isEventDispatchThread()
              assertEquals(DesktopAppearance.SYSTEM, effective)
              tokens
            },
            refreshRuntime =
                DesktopThemeRefreshRuntime { refreshedTokens ->
                  refreshOnEdt = SwingUtilities.isEventDispatchThread()
                  assertSame(tokens, refreshedTokens)
                },
        )

    assertFalse(SwingUtilities.isEventDispatchThread())
    val application = manager.apply(DesktopAppearance.LIGHT)

    assertEquals(listOf(DesktopAppearance.LIGHT, DesktopAppearance.SYSTEM), installations)
    assertEquals(DesktopAppearance.LIGHT, application.requestedAppearance)
    assertEquals(DesktopAppearance.SYSTEM, application.effectiveAppearance)
    assertEquals(DesktopAppearance.LIGHT, assertNotNull(application.fallback).unavailableAppearance)
    assertEquals("UnsupportedLookAndFeelException", application.fallback?.failureType)
    assertTrue(tokenCaptureOnEdt)
    assertTrue(refreshOnEdt)
    assertSame(application, manager.current)
  }

  @Test
  fun `system installation failure is explicit and does not refresh windows`() {
    var refreshed = false
    val manager =
        DesktopThemeManager(
            lookAndFeelInstaller =
                DesktopLookAndFeelInstaller { throw IllegalStateException("unavailable") },
            tokenCapture = { testTokens() },
            refreshRuntime = DesktopThemeRefreshRuntime { refreshed = true },
        )

    val failure =
        assertFailsWith<DesktopThemeInstallationException> {
          manager.apply(DesktopAppearance.SYSTEM)
        }

    assertEquals(DesktopAppearance.SYSTEM, failure.requestedAppearance)
    assertFalse(refreshed)
    assertNull(manager.current)
  }

  @Test
  fun `hidden component trees update before cached UIManager hooks`() {
    val tokens = testTokens()
    var treeUpdated = false
    var hookCalls = 0
    val cachedComponent =
        object : JPanel(), DesktopThemeRefreshHook {
          override fun desktopThemeChanged(tokensFromManager: DesktopThemeTokens) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertTrue(treeUpdated)
            assertSame(tokens, tokensFromManager)
            hookCalls++
          }
        }
    val hiddenRoot = JPanel().apply {
      isVisible = false
      add(cachedComponent)
    }
    val updatedRoots = mutableListOf<Component>()
    val runtime =
        SwingDesktopThemeRefreshRuntime(
            rootProvider = { listOf(hiddenRoot, hiddenRoot) },
            updateComponentTree = { root ->
              assertTrue(SwingUtilities.isEventDispatchThread())
              updatedRoots += root
              treeUpdated = true
            },
        )

    onEdt { runtime.refresh(tokens) }

    assertFalse(hiddenRoot.isVisible)
    assertEquals(listOf<Component>(hiddenRoot), updatedRoots)
    assertEquals(1, hookCalls)
  }

  @Test
  fun `scaled components reset before tree update and recapture afterward`() {
    val events = mutableListOf<String>()
    val component =
        object : JPanel(), DesktopThemePrepareHook, DesktopThemeRefreshHook {
          override fun desktopThemeWillChange() {
            events += "prepare"
          }

          override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
            events += "refresh"
          }
        }
    val root = JPanel().apply { add(component) }
    val runtime =
        SwingDesktopThemeRefreshRuntime(
            rootProvider = { listOf(root) },
            updateComponentTree = { events += "update" },
        )

    onEdt { runtime.refresh(testTokens()) }

    assertEquals(listOf("prepare", "update", "refresh"), events)
  }

  private fun assertSemanticContrast(tokens: DesktopThemeTokens) {
    assertTrue(tokens.surface.rgb != tokens.elevatedSurface.rgb)
    listOf(
            tokens.primaryText,
            tokens.secondaryText,
            tokens.accent,
            tokens.success,
            tokens.warning,
            tokens.danger,
        )
        .forEach { color ->
          assertTrue(
              desktopContrastRatio(color, tokens.surface) >=
                  DesktopThemeTokens.MINIMUM_TEXT_CONTRAST,
              "Expected $color to contrast with ${tokens.surface}",
          )
        }
    listOf(tokens.border, tokens.focus).forEach { color ->
      assertTrue(
          desktopContrastRatio(color, tokens.surface) >=
              DesktopThemeTokens.MINIMUM_NON_TEXT_CONTRAST,
          "Expected $color to contrast with ${tokens.surface}",
      )
    }
    assertTrue(
        desktopContrastRatio(tokens.onAccent, tokens.accent) >=
            DesktopThemeTokens.MINIMUM_TEXT_CONTRAST)
  }

  private fun testTokens(): DesktopThemeTokens =
      DesktopThemeTokens(
          surface = Color.WHITE,
          elevatedSurface = Color(0xF0F0F0),
          primaryText = Color.BLACK,
          secondaryText = Color.DARK_GRAY,
          border = Color.GRAY,
          focus = Color.BLUE,
          accent = Color(0xA6422B),
          onAccent = Color.WHITE,
          success = Color(0x256F3A),
          warning = Color(0x805500),
          danger = Color(0xB3261E),
      )

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
