package eu.rekawek.coffeegb.swing

import javax.swing.SwingUtilities
import kotlin.math.min

/** Positive logical size in the coordinate space used by a host window. */
internal data class DesktopSize(val width: Int, val height: Int) {
  init {
    require(width > 0 && height > 0) { "Desktop size must be positive" }
  }
}

/**
 * Immutable half-open desktop rectangle. Negative coordinates are valid for monitors positioned
 * left of or above the primary display.
 */
internal data class DesktopBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
  init {
    require(width > 0 && height > 0) { "Desktop bounds must be positive" }
    require(rightExclusive <= Int.MAX_VALUE.toLong() + 1L) {
      "Desktop bounds extend beyond the integer coordinate space"
    }
    require(bottomExclusive <= Int.MAX_VALUE.toLong() + 1L) {
      "Desktop bounds extend beyond the integer coordinate space"
    }
  }

  val rightExclusive: Long
    get() = x.toLong() + width

  val bottomExclusive: Long
    get() = y.toLong() + height

  fun contains(other: DesktopBounds): Boolean =
      other.x >= x &&
          other.y >= y &&
          other.rightExclusive <= rightExclusive &&
          other.bottomExclusive <= bottomExclusive

  fun intersectionArea(other: DesktopBounds): Long {
    val left = maxOf(x.toLong(), other.x.toLong())
    val top = maxOf(y.toLong(), other.y.toLong())
    val right = minOf(rightExclusive, other.rightExclusive)
    val bottom = minOf(bottomExclusive, other.bottomExclusive)
    if (right <= left || bottom <= top) return 0
    return Math.multiplyExact(right - left, bottom - top)
  }

  /** Squared gap between two rectangles; zero means they overlap or touch. */
  fun distanceSquared(other: DesktopBounds): Double {
    val horizontal =
        when {
          rightExclusive < other.x.toLong() -> other.x.toDouble() - rightExclusive
          other.rightExclusive < x.toLong() -> x.toDouble() - other.rightExclusive
          else -> 0.0
        }
    val vertical =
        when {
          bottomExclusive < other.y.toLong() -> other.y.toDouble() - bottomExclusive
          other.bottomExclusive < y.toLong() -> y.toDouble() - other.bottomExclusive
          else -> 0.0
        }
    return horizontal * horizontal + vertical * vertical
  }
}

/**
 * One immutable GraphicsConfiguration-like snapshot supplied by a production adapter.
 *
 * [screenId] must be the stable GraphicsDevice identity, not an array index. Fullscreen uses
 * [fullBounds] exactly; restored window placement is constrained to [usableBounds]. Scale values
 * convert remembered device-pixel placement through the latest per-monitor transform.
 */
internal data class ScreenSnapshot(
    val screenId: String,
    val fullBounds: DesktopBounds,
    val usableBounds: DesktopBounds,
    val scaleX: Double,
    val scaleY: Double,
) {
  init {
    require(screenId.isNotBlank()) { "Screen ID must not be blank" }
    require(fullBounds.contains(usableBounds)) {
      "Usable screen bounds must be contained by full screen bounds"
    }
    require(scaleX.isFinite() && scaleX > 0.0) { "Screen X scale must be finite and positive" }
    require(scaleY.isFinite() && scaleY > 0.0) { "Screen Y scale must be finite and positive" }
  }
}

/**
 * Defensive, point-in-time monitor layout. A stale/missing primary ID is tolerated and resolved
 * deterministically by geometry and stable screen ID.
 */
internal class ScreenLayout(
    screens: Collection<ScreenSnapshot>,
    val primaryScreenId: String? = null,
) {
  val screens: List<ScreenSnapshot> = screens.toList()

  init {
    require(this.screens.isNotEmpty()) { "At least one screen is required" }
    require(this.screens.map(ScreenSnapshot::screenId).toSet().size == this.screens.size) {
      "Screen IDs must be unique"
    }
  }

  fun screen(screenId: String?): ScreenSnapshot? =
      screenId?.let { expected -> screens.firstOrNull { it.screenId == expected } }

  fun primary(): ScreenSnapshot =
      screen(primaryScreenId)
          ?: screens.minWith(screenTieBreaker())

  /**
   * Uses an exact ID first, then largest overlap, then nearest geometry. Ties prefer the designated
   * primary and finally stable coordinate/ID order, so identical monitor geometries remain
   * deterministic.
   */
  fun resolve(screenId: String?, reference: DesktopBounds): ScreenSnapshot {
    screen(screenId)?.let {
      return it
    }
    val maximumIntersection = screens.maxOf { it.fullBounds.intersectionArea(reference) }
    if (maximumIntersection > 0) {
      return screens
          .filter { it.fullBounds.intersectionArea(reference) == maximumIntersection }
          .minWith(screenTieBreaker())
    }
    return nearest(reference)
  }

  fun nearest(reference: DesktopBounds): ScreenSnapshot =
      screens.minWith(
          compareBy<ScreenSnapshot>(
              { it.fullBounds.distanceSquared(reference) },
              { if (it.screenId == primaryScreenId) 0 else 1 },
              { it.fullBounds.x },
              { it.fullBounds.y },
              { it.screenId },
          ))

  private fun screenTieBreaker(): Comparator<ScreenSnapshot> =
      compareBy(
          { if (it.screenId == primaryScreenId) 0 else 1 },
          { it.fullBounds.x },
          { it.fullBounds.y },
          { it.screenId },
      )
}

internal data class FullscreenWindowSnapshot(
    val bounds: DesktopBounds,
    val screenId: String?,
    val undecorated: Boolean,
)

/** Headless seam for the small set of JFrame operations whose ordering is significant. */
internal interface FullscreenWindow {
  fun snapshot(): FullscreenWindowSnapshot

  /** Retains visible owned tools across native-peer recreation. */
  fun beginPeerTransition() = Unit

  fun dispose()

  fun setUndecorated(undecorated: Boolean)

  fun setBounds(bounds: DesktopBounds)

  fun showWindow()

  /** Restores the retained owned-tool visibility after the owner peer is live again. */
  fun endPeerTransition() = Unit
}

internal fun interface ScreenLayoutProvider {
  fun snapshot(): ScreenLayout
}

internal fun interface EdtOwnership {
  fun isEventDispatchThread(): Boolean

  companion object {
    val SWING = EdtOwnership { SwingUtilities.isEventDispatchThread() }
  }
}

/**
 * EDT-owned, borderless-fullscreen state machine independent from AWT monitor enumeration.
 *
 * The remembered window placement is stored relative to its stable screen ID in device pixels.
 * Restoring through the newest screen snapshot therefore survives a DPI transform change without
 * reusing stale logical coordinates. Missing screens use nearest geometry with a primary/ID
 * tie-break and every restored rectangle is clamped to current usable bounds.
 */
internal class FullscreenController(
    private val window: FullscreenWindow,
    private val screenProvider: ScreenLayoutProvider,
    private val minimumWindowSize: DesktopSize,
    private val edtOwnership: EdtOwnership = EdtOwnership.SWING,
) {
  private var fullscreen = false
  private var rememberedPlacement: RememberedPlacement? = null
  private var activeFullscreenScreenId: String? = null
  private var activeFullscreenBounds: DesktopBounds? = null

  fun isFullscreen(): Boolean = fullscreen

  fun activeScreenId(): String? = activeFullscreenScreenId

  fun enterFullscreen() {
    requireEdt()
    if (fullscreen) return

    val layout = screenProvider.snapshot()
    val before = window.snapshot()
    val target = layout.resolve(before.screenId, before.bounds)
    val remembered = remember(before, target)

    recreatePeer(undecorated = true, bounds = target.fullBounds)

    rememberedPlacement = remembered
    activeFullscreenScreenId = target.screenId
    activeFullscreenBounds = target.fullBounds
    fullscreen = true
  }

  fun exitFullscreen() {
    requireEdt()
    if (!fullscreen) return

    val layout = screenProvider.snapshot()
    val remembered = checkNotNull(rememberedPlacement)
    val target =
        layout.screen(remembered.screenId)
            ?: layout.nearest(remembered.sourceFullBounds)
    val restored = restoreAndClamp(remembered, target)

    recreatePeer(undecorated = remembered.undecorated, bounds = restored)

    fullscreen = false
    rememberedPlacement = null
    activeFullscreenScreenId = null
    activeFullscreenBounds = null
  }

  fun toggleFullscreen() {
    requireEdt()
    if (fullscreen) exitFullscreen() else enterFullscreen()
  }

  /**
   * Applies a current GraphicsConfiguration snapshot while fullscreen. This covers monitor removal,
   * work-area changes, and per-monitor DPI transitions without repeating dispose/decorate calls.
   */
  fun refreshScreens() {
    requireEdt()
    if (!fullscreen) return

    val layout = screenProvider.snapshot()
    val reference =
        activeFullscreenBounds
            ?: checkNotNull(rememberedPlacement).sourceFullBounds
    val target =
        layout.screen(activeFullscreenScreenId)
            ?: layout.nearest(reference)
    if (target.fullBounds != activeFullscreenBounds) {
      window.setBounds(target.fullBounds)
    }
    activeFullscreenScreenId = target.screenId
    activeFullscreenBounds = target.fullBounds
  }

  private fun recreatePeer(undecorated: Boolean, bounds: DesktopBounds) {
    window.beginPeerTransition()
    try {
      window.dispose()
      window.setUndecorated(undecorated)
      window.setBounds(bounds)
      window.showWindow()
    } finally {
      window.endPeerTransition()
    }
  }

  private fun remember(
      snapshot: FullscreenWindowSnapshot,
      screen: ScreenSnapshot,
  ): RememberedPlacement {
    val relativeX = snapshot.bounds.x.toLong() - screen.usableBounds.x
    val relativeY = snapshot.bounds.y.toLong() - screen.usableBounds.y
    return RememberedPlacement(
        screenId = screen.screenId,
        sourceFullBounds = screen.fullBounds,
        undecorated = snapshot.undecorated,
        deviceX = relativeX * screen.scaleX,
        deviceY = relativeY * screen.scaleY,
        deviceWidth = snapshot.bounds.width * screen.scaleX,
        deviceHeight = snapshot.bounds.height * screen.scaleY,
    )
  }

  private fun restoreAndClamp(
      placement: RememberedPlacement,
      target: ScreenSnapshot,
  ): DesktopBounds {
    val rawX = target.usableBounds.x + placement.deviceX / target.scaleX
    val rawY = target.usableBounds.y + placement.deviceY / target.scaleY
    val rawWidth = placement.deviceWidth / target.scaleX
    val rawHeight = placement.deviceHeight / target.scaleY
    return clampToUsable(rawX, rawY, rawWidth, rawHeight, target.usableBounds)
  }

  private fun clampToUsable(
      rawX: Double,
      rawY: Double,
      rawWidth: Double,
      rawHeight: Double,
      usable: DesktopBounds,
  ): DesktopBounds {
    val minimumWidth = min(minimumWindowSize.width, usable.width)
    val minimumHeight = min(minimumWindowSize.height, usable.height)
    val width = safeRound(rawWidth).coerceIn(minimumWidth, usable.width)
    val height = safeRound(rawHeight).coerceIn(minimumHeight, usable.height)

    val maximumX = usable.rightExclusive - width
    val maximumY = usable.bottomExclusive - height
    val x =
        safeRoundToLong(rawX)
            .coerceIn(usable.x.toLong(), maximumX)
            .toInt()
    val y =
        safeRoundToLong(rawY)
            .coerceIn(usable.y.toLong(), maximumY)
            .toInt()
    return DesktopBounds(x, y, width, height)
  }

  private fun safeRound(value: Double): Int =
      safeRoundToLong(value).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

  private fun safeRoundToLong(value: Double): Long =
      when {
        value.isNaN() -> 0L
        value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> Math.round(value)
      }

  private fun requireEdt() {
    check(edtOwnership.isEventDispatchThread()) {
      "Fullscreen transitions must run on the Event Dispatch Thread"
    }
  }

  private data class RememberedPlacement(
      val screenId: String,
      val sourceFullBounds: DesktopBounds,
      val undecorated: Boolean,
      val deviceX: Double,
      val deviceY: Double,
      val deviceWidth: Double,
      val deviceHeight: Double,
  )
}
