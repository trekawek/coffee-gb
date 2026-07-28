package eu.rekawek.coffeegb.controller.state

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StateImageAndScreenshotTest {

  @Test
  fun `image owns pixels and thumbnail is deterministic nearest-neighbour letterbox`() {
    val source = intArrayOf(0x11_22_33, 0xaa_bb_cc)
    val image = StateImage(2, 1, source)
    source.fill(0)

    val first = image.thumbnail(4, 4)
    val second = image.thumbnail(4, 4)
    assertEquals(first, second)
    assertContentEquals(
        intArrayOf(
            0, 0, 0, 0,
            0x11_22_33, 0x11_22_33, 0xaa_bb_cc, 0xaa_bb_cc,
            0x11_22_33, 0x11_22_33, 0xaa_bb_cc, 0xaa_bb_cc,
            0, 0, 0, 0,
        ),
        first.copyRgb(),
    )
    val returned = first.copyRgb()
    returned.fill(0xffffff)
    assertFalse(first.copyRgb().contentEquals(returned))
  }

  @Test
  fun `png encoding is stable bounded and rejects dimensions before decoding a raster`() {
    val image =
        StateImage(
            3,
            2,
            intArrayOf(0, 0x123456, 0xffffff, 0x010203, 0xabcdef, 0x222222),
        )
    val metadata = mapOf("Software" to "Coffee GB", "Hardware Profile" to "cgb0")
    val first = StatePngCodec.encode(image, metadata)
    val second = StatePngCodec.encode(image, metadata.toList().reversed().toMap())

    assertContentEquals(first, second)
    assertEquals(image, StatePngCodec.decode(first))
    assertTrue(first.size <= StatePngCodec.MAX_PNG_BYTES)
    assertFailsWith<IllegalArgumentException> {
      StatePngCodec.encode(image, mapOf("Private/Path" to "/tmp/game.gb"))
    }

    val oversized = BufferedImage(StateImage.MAX_WIDTH + 1, 1, BufferedImage.TYPE_INT_RGB)
    val encodedOversized =
        ByteArrayOutputStream().also { ImageIO.write(oversized, "png", it) }.toByteArray()
    assertFailsWith<IOException> { StatePngCodec.decode(encodedOversized) }
  }

  @Test
  fun `screenshots have collision-safe deterministic names and nonsensitive metadata`() {
    val directory = Files.createTempDirectory("state-screenshot")
    val instant = Instant.parse("2026-07-28T03:04:05.006Z")
    val store = StateScreenshotStore(directory, Clock.fixed(instant, ZoneOffset.UTC))
    val image = StateImage(2, 2, intArrayOf(0, 1, 2, 3))

    val first = store.save(image, "cgb0")
    val second = store.save(image, "cgb0")

    assertEquals("coffee-gb-20260728-030405-006.png", first.path.fileName.toString())
    assertEquals("coffee-gb-20260728-030405-006-1.png", second.path.fileName.toString())
    assertContentEquals(Files.readAllBytes(first.path), Files.readAllBytes(second.path))
    assertEquals(image, StatePngCodec.decode(Files.readAllBytes(first.path)))
    val rawText = String(Files.readAllBytes(first.path), StandardCharsets.ISO_8859_1)
    assertTrue(rawText.contains("Coffee GB"))
    assertTrue(rawText.contains("Hardware Profile"))
    assertFalse(rawText.contains("secret-game.gb"))
    assertFalse(rawText.contains("/private/roms"))
  }
}
