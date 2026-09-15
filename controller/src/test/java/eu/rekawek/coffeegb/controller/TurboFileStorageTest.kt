package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.serial.TurboFileSerialEndpoint
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class TurboFileStorageTest {
  @Test fun deviceFlashSurvivesReattachmentAndRejectsTruncatedImages() {
    val directory = Files.createTempDirectory("turbo-file")
    val path = directory.resolve("device.bin")
    try {
      val store = TurboFileStorage(path)
      store.open(false) { fail("Persistence failed") }.also {
        it.importImage(ByteArray(TurboFileSerialEndpoint.IMAGE_BYTES) { 42 }, false)
        it.disconnect()
      }
      val reopened = store.open(false) { fail("Persistence failed") }
      assertEquals(42, reopened.exportImage(false)[1234].toInt())
      assertEquals(255, reopened.exportImage(true)[1234].toInt() and 255)
      Files.write(path, byteArrayOf(1))
      assertFailsWith<IllegalArgumentException> { store.open(false) {} }
    } finally {
      Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
  }
}
