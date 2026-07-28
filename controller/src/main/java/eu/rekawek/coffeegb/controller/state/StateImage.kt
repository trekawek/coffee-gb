package eu.rekawek.coffeegb.controller.state

import java.util.Arrays

/**
 * Immutable, toolkit-neutral RGB image used at the controller/desktop boundary.
 *
 * The dimensions are deliberately limited to Coffee GB's largest SGB presentation surface.
 * Callers never expose a live display raster: construction and [copyRgb] both copy.
 */
class StateImage(
    val width: Int,
    val height: Int,
    rgb: IntArray,
) {
  private val pixels: IntArray

  init {
    require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT) {
      "State image dimensions must be between 1x1 and ${MAX_WIDTH}x$MAX_HEIGHT"
    }
    require(rgb.size == Math.multiplyExact(width, height)) {
      "State image pixel count must equal width times height"
    }
    pixels = IntArray(rgb.size) { index -> rgb[index] and 0x00ffffff }
  }

  fun copyRgb(): IntArray = pixels.clone()

  /**
   * Produces a deterministic nearest-neighbour thumbnail with a black letterbox. Integer mapping
   * keeps the result byte-for-byte stable and avoids platform rendering differences.
   */
  fun thumbnail(
      targetWidth: Int = THUMBNAIL_WIDTH,
      targetHeight: Int = THUMBNAIL_HEIGHT,
  ): StateImage {
    require(targetWidth in 1..MAX_WIDTH && targetHeight in 1..MAX_HEIGHT)
    val scaleNumerator =
        minOf(
            targetWidth.toLong() * SCALE_DENOMINATOR / width,
            targetHeight.toLong() * SCALE_DENOMINATOR / height,
        )
    val scaledWidth =
        maxOf(1, (width.toLong() * scaleNumerator / SCALE_DENOMINATOR).toInt())
            .coerceAtMost(targetWidth)
    val scaledHeight =
        maxOf(1, (height.toLong() * scaleNumerator / SCALE_DENOMINATOR).toInt())
            .coerceAtMost(targetHeight)
    val xOffset = (targetWidth - scaledWidth) / 2
    val yOffset = (targetHeight - scaledHeight) / 2
    val output = IntArray(Math.multiplyExact(targetWidth, targetHeight))
    repeat(scaledHeight) { destinationY ->
      val sourceY = destinationY * height / scaledHeight
      repeat(scaledWidth) { destinationX ->
        val sourceX = destinationX * width / scaledWidth
        output[(destinationY + yOffset) * targetWidth + destinationX + xOffset] =
            pixels[sourceY * width + sourceX]
      }
    }
    return StateImage(targetWidth, targetHeight, output)
  }

  override fun equals(other: Any?): Boolean =
      other is StateImage &&
          width == other.width &&
          height == other.height &&
          pixels.contentEquals(other.pixels)

  override fun hashCode(): Int =
      31 * (31 * width + height) + Arrays.hashCode(pixels)

  companion object {
    const val MAX_WIDTH = 256
    const val MAX_HEIGHT = 224
    const val THUMBNAIL_WIDTH = 160
    const val THUMBNAIL_HEIGHT = 144
    private const val SCALE_DENOMINATOR = 1_000_000L
  }
}
