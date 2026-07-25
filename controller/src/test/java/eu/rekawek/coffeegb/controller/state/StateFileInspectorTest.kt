package eu.rekawek.coffeegb.controller.state

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StateFileInspectorTest {
  @Test
  fun headlessCommandPrintsBoundedMetadataWithoutDecodingTheStateRoot() {
    val fixture =
        checkNotNull(javaClass.getResourceAsStream("/state-file-v1/session-barcode-deflate.cgbstate"))
            .use { it.readBytes() }
    val path = Files.createTempFile("state-file-inspector-", ".cgbstate")
    Files.write(path, fixture)
    val previous = System.out
    val captured = ByteArrayOutputStream()
    try {
      System.setOut(PrintStream(captured, true, Charsets.UTF_8))
      StateFileInspector.main(arrayOf(path.toString()))
    } finally {
      System.setOut(previous)
      Files.deleteIfExists(path)
    }
    val output = captured.toString(Charsets.UTF_8)
    assertTrue(output.contains("magic=CGBS format=1 root=SESSION"))
    assertTrue(output.contains("compression=DEFLATE"))
    assertTrue(output.contains("checksum=true"))
    assertTrue(output.contains("section=2 version=1 required=true"))
    assertFalse(output.contains("GameboyMemento"))
  }
}
