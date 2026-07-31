package eu.rekawek.coffeegb.swing

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Implement on a durable owning component when it caches colors, fonts, row heights, borders, or
 * other values derived from [UIManager]. The callback runs on the EDT after the component tree has
 * adopted the new look and feel. Renderers that are not permanent tree children should be
 * refreshed by their owning component's implementation.
 */
internal fun interface DesktopThemeRefreshHook {
  fun desktopThemeChanged(tokens: DesktopThemeTokens)
}

/** Lets components remove application scaling before Swing installs a new LAF baseline. */
internal fun interface DesktopThemePrepareHook {
  fun desktopThemeWillChange()
}

internal data class DesktopThemeFallback(
    val unavailableAppearance: DesktopAppearance,
    /** Exception type only; messages may contain environment-specific details. */
    val failureType: String,
)

internal data class DesktopThemeApplication(
    val requestedAppearance: DesktopAppearance,
    val effectiveAppearance: DesktopAppearance,
    val tokens: DesktopThemeTokens,
    val fallback: DesktopThemeFallback? = null,
)

internal fun interface DesktopLookAndFeelInstaller {
  fun install(appearance: DesktopAppearance)
}

internal fun interface DesktopThemeRefreshRuntime {
  fun refresh(tokens: DesktopThemeTokens)
}

/** Updates every created Window, including hidden retained dialogs, then invokes cache hooks. */
internal class SwingDesktopThemeRefreshRuntime(
    private val rootProvider: () -> Iterable<Component> = {
      Window.getWindows().asIterable()
    },
    private val updateComponentTree: (Component) -> Unit = { component ->
      SwingUtilities.updateComponentTreeUI(component)
    },
) : DesktopThemeRefreshRuntime {
  override fun refresh(tokens: DesktopThemeTokens) {
    check(SwingUtilities.isEventDispatchThread()) { "Desktop themes must refresh on the EDT" }

    val roots = identityDistinct(rootProvider())
    val preparedHooks = Collections.newSetFromMap(IdentityHashMap<DesktopThemePrepareHook, Boolean>())
    roots.forEach { root -> invokePrepareHooks(root, preparedHooks) }
    roots.forEach(updateComponentTree)

    val invokedHooks = Collections.newSetFromMap(IdentityHashMap<DesktopThemeRefreshHook, Boolean>())
    roots.forEach { root -> invokeHooks(root, tokens, invokedHooks) }
  }

  private fun invokePrepareHooks(
      root: Component,
      invokedHooks: MutableSet<DesktopThemePrepareHook>,
  ) {
    val remaining = ArrayDeque<Component>()
    remaining.add(root)
    while (remaining.isNotEmpty()) {
      val component = remaining.removeFirst()
      if (component is DesktopThemePrepareHook && invokedHooks.add(component)) {
        component.desktopThemeWillChange()
      }
      if (component is Container) {
        component.components.forEach(remaining::addLast)
      }
    }
  }

  private fun invokeHooks(
      root: Component,
      tokens: DesktopThemeTokens,
      invokedHooks: MutableSet<DesktopThemeRefreshHook>,
  ) {
    val remaining = ArrayDeque<Component>()
    remaining.add(root)
    while (remaining.isNotEmpty()) {
      val component = remaining.removeFirst()
      if (component is DesktopThemeRefreshHook && invokedHooks.add(component)) {
        component.desktopThemeChanged(tokens)
      }
      if (component is Container) {
        component.components.forEach(remaining::addLast)
      }
    }
  }

  private fun identityDistinct(components: Iterable<Component>): List<Component> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())
    return components.filter(seen::add)
  }
}

/** Installs one global desktop look and feel and publishes its semantic token snapshot. */
internal class DesktopThemeManager(
    private val lookAndFeelInstaller: DesktopLookAndFeelInstaller = SwingLookAndFeelInstaller,
    private val tokenCapture: (DesktopAppearance) -> DesktopThemeTokens =
        DesktopThemeTokens::capture,
    private val refreshRuntime: DesktopThemeRefreshRuntime = SwingDesktopThemeRefreshRuntime(),
) {
  @Volatile private var latestApplication: DesktopThemeApplication? = null

  val current: DesktopThemeApplication?
    get() = latestApplication

  /** Synchronously transfers installation and every live-window mutation to the EDT. */
  fun apply(appearance: DesktopAppearance): DesktopThemeApplication =
      invokeOnEdt { applyOnEdt(appearance) }

  private fun applyOnEdt(appearance: DesktopAppearance): DesktopThemeApplication {
    check(SwingUtilities.isEventDispatchThread()) { "Desktop themes must be applied on the EDT" }
    val installation = installWithSystemFallback(appearance)
    val tokens = tokenCapture(installation.effectiveAppearance)
    val application =
        DesktopThemeApplication(
            requestedAppearance = appearance,
            effectiveAppearance = installation.effectiveAppearance,
            tokens = tokens,
            fallback = installation.fallback,
        )
    latestApplication = application
    refreshRuntime.refresh(tokens)
    return application
  }

  private fun installWithSystemFallback(appearance: DesktopAppearance): Installation {
    return try {
      lookAndFeelInstaller.install(appearance)
      Installation(appearance)
    } catch (failure: Exception) {
      if (appearance == DesktopAppearance.SYSTEM) {
        throw DesktopThemeInstallationException(appearance, failure)
      }
      try {
        lookAndFeelInstaller.install(DesktopAppearance.SYSTEM)
      } catch (systemFailure: Exception) {
        systemFailure.addSuppressed(failure)
        throw DesktopThemeInstallationException(appearance, systemFailure)
      }
      Installation(
          effectiveAppearance = DesktopAppearance.SYSTEM,
          fallback =
              DesktopThemeFallback(
                  unavailableAppearance = appearance,
                  failureType = sanitizedFailureType(failure),
              ),
      )
    }
  }

  private fun sanitizedFailureType(failure: Exception): String =
      failure.javaClass.simpleName
          .takeIf { it.matches(SAFE_FAILURE_TYPE) }
          ?: "LookAndFeelException"

  private data class Installation(
      val effectiveAppearance: DesktopAppearance,
      val fallback: DesktopThemeFallback? = null,
  )

  companion object {
    private val SAFE_FAILURE_TYPE = Regex("[A-Za-z0-9_$]{1,128}")
  }
}

internal class DesktopThemeInstallationException(
    val requestedAppearance: DesktopAppearance,
    cause: Exception,
) : IllegalStateException("No supported look and feel could be installed", cause)

private object SwingLookAndFeelInstaller : DesktopLookAndFeelInstaller {
  override fun install(appearance: DesktopAppearance) {
    check(SwingUtilities.isEventDispatchThread()) { "Look and feel installation must run on EDT" }
    when (appearance) {
      DesktopAppearance.LIGHT -> UIManager.setLookAndFeel(FlatLightLaf())
      DesktopAppearance.DARK -> UIManager.setLookAndFeel(FlatDarkLaf())
      DesktopAppearance.SYSTEM ->
          UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }
  }
}

private fun <T> invokeOnEdt(action: () -> T): T {
  if (SwingUtilities.isEventDispatchThread()) {
    return action()
  }
  var outcome: Result<T>? = null
  try {
    SwingUtilities.invokeAndWait { outcome = runCatching(action) }
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IllegalStateException("Interrupted while applying desktop appearance", interrupted)
  }
  return checkNotNull(outcome).getOrThrow()
}
