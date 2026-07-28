package eu.rekawek.coffeegb.controller.state

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

/** Bounded deterministic PNG encoding shared by thumbnails and screenshots. */
object StatePngCodec {
  const val MAX_PNG_BYTES = 2 * 1024 * 1024
  const val MAX_METADATA_ENTRIES = 8
  const val MAX_METADATA_UTF8_BYTES = 1024

  private val KEY = Regex("[A-Za-z][A-Za-z0-9 ]{0,31}")
  private val VALUE = Regex("[\\x20-\\x7e]{1,128}")

  @JvmOverloads
  fun encode(
      image: StateImage,
      metadata: Map<String, String> = emptyMap(),
  ): ByteArray {
    validateMetadata(metadata)
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    val target = (buffered.raster.dataBuffer as DataBufferInt).data
    image.copyRgb().copyInto(target)

    val writer =
        ImageIO.getImageWritersByFormatName("png").asSequence().firstOrNull()
            ?: throw IOException("The Java runtime has no PNG writer")
    try {
      val output = ByteArrayOutputStream()
      ImageIO.createImageOutputStream(output).use { imageOutput ->
        writer.output = imageOutput
        val parameters = writer.defaultWriteParam
        if (parameters.canWriteCompressed()) {
          parameters.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
          parameters.compressionQuality = 0.9f
        }
        val pngMetadata = writer.getDefaultImageMetadata(
            javax.imageio.ImageTypeSpecifier.createFromRenderedImage(buffered),
            parameters,
        )
        if (metadata.isNotEmpty()) {
          val root = IIOMetadataNode(PNG_METADATA_FORMAT)
          val text = IIOMetadataNode("tEXt")
          metadata.toSortedMap().forEach { (key, value) ->
            val entry = IIOMetadataNode("tEXtEntry")
            entry.setAttribute("keyword", key)
            entry.setAttribute("value", value)
            text.appendChild(entry)
          }
          root.appendChild(text)
          pngMetadata.mergeTree(PNG_METADATA_FORMAT, root)
        }
        writer.write(null, IIOImage(buffered, null, pngMetadata), parameters)
        imageOutput.flush()
      }
      return output.toByteArray().also {
        if (it.size > MAX_PNG_BYTES) {
          throw IOException("PNG exceeds the $MAX_PNG_BYTES-byte limit")
        }
      }
    } finally {
      writer.dispose()
    }
  }

  /**
   * Decodes only a bounded image after checking dimensions through ImageIO's header reader. This
   * prevents a small hostile PNG from allocating an arbitrary raster.
   */
  fun decode(bytes: ByteArray): StateImage {
    if (bytes.isEmpty() || bytes.size > MAX_PNG_BYTES) {
      throw IOException("PNG byte count must be between 1 and $MAX_PNG_BYTES")
    }
    val reader =
        ImageIO.getImageReadersByFormatName("png").asSequence().firstOrNull()
            ?: throw IOException("The Java runtime has no PNG reader")
    try {
      MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        reader.input = input
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width !in 1..StateImage.MAX_WIDTH || height !in 1..StateImage.MAX_HEIGHT) {
          throw IOException(
              "PNG dimensions $width x $height exceed " +
                  "${StateImage.MAX_WIDTH} x ${StateImage.MAX_HEIGHT}")
        }
        val buffered = reader.read(0)
            ?: throw IOException("PNG has no readable first image")
        val rgb = IntArray(Math.multiplyExact(width, height))
        buffered.getRGB(0, 0, width, height, rgb, 0, width)
        return StateImage(width, height, rgb)
      }
    } catch (failure: IOException) {
      throw failure
    } catch (failure: RuntimeException) {
      throw IOException("PNG is malformed", failure)
    } finally {
      reader.dispose()
    }
  }

  private fun validateMetadata(metadata: Map<String, String>) {
    require(metadata.size <= MAX_METADATA_ENTRIES) {
      "PNG metadata contains more than $MAX_METADATA_ENTRIES entries"
    }
    var bytes = 0
    metadata.forEach { (key, value) ->
      require(KEY.matches(key)) { "Invalid PNG metadata key" }
      require(VALUE.matches(value)) { "Invalid PNG metadata value for $key" }
      bytes = Math.addExact(
          bytes,
          key.toByteArray(StandardCharsets.UTF_8).size +
              value.toByteArray(StandardCharsets.UTF_8).size,
      )
    }
    require(bytes <= MAX_METADATA_UTF8_BYTES) {
      "PNG metadata exceeds $MAX_METADATA_UTF8_BYTES UTF-8 bytes"
    }
  }

  private const val PNG_METADATA_FORMAT = "javax_imageio_png_1.0"
}
