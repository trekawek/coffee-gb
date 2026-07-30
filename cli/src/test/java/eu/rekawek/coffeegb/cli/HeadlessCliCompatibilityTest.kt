package eu.rekawek.coffeegb.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** End-to-end compatibility coverage for the public CLI and its real headless engine. */
class HeadlessCliCompatibilityTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun boundedRunIsDisplayIndependentAndPublishesByteStableEvidence() {
    withHeadlessJvm {
      val rom = writeSyntheticRom(temporary.root.toPath().resolve("private-synthetic.gb"))
      val first = artifactTargets(temporary.newFolder("first").toPath())
      val second = artifactTargets(temporary.newFolder("second").toPath())

      val firstInvocation = invoke(*runWithEvidenceArgs(rom, first))
      val secondInvocation = invoke(*runWithEvidenceArgs(rom, second))

      assertEquals(0, firstInvocation.exitCode)
      assertEquals("", firstInvocation.stderr)
      assertEquals(0, secondInvocation.exitCode)
      assertEquals("", secondInvocation.stderr)
      assertEquals(firstInvocation.stdout, secondInvocation.stdout)
      assertEquals(Files.readString(first.report), firstInvocation.stdout)
      assertEquals(Files.readString(second.report), secondInvocation.stdout)
      assertTrue(firstInvocation.stdout.contains("\"status\":\"completed\""))
      assertTrue(firstInvocation.stdout.contains("\"termination\":\"frame-limit\""))
      assertTrue(firstInvocation.stdout.contains("\"requestedCount\":1"))
      assertTrue(firstInvocation.stdout.contains("\"space\":\"work-ram\""))

      assertArtifactsEqual(first, second)
      assertPng(Files.readAllBytes(first.screenshot))
      assertWav(Files.readAllBytes(first.wav))

      val archive = unzip(Files.readAllBytes(first.bundle))
      assertEquals(listOf("logs.ndjson", "manifest.json", "report.json"), archive.keys.toList())
      val manifest = archive.getValue("manifest.json").toString(StandardCharsets.UTF_8)
      val bundledReport = archive.getValue("report.json").toString(StandardCharsets.UTF_8)
      assertTrue(manifest.contains("\"includedSensitive\":[]"))
      assertTrue(
          manifest.contains(
              "\"excluded\":[\"boot-rom\",\"media\",\"memory\",\"paths\"," +
                  "\"raw-replay\",\"replay\",\"rom\",\"saves\",\"tokens\"]"))
      assertTrue(bundledReport.contains("\"title\":\"<redacted>\""))
      assertTrue(bundledReport.contains("\"memory\":[]"))
      assertFalse(archive.containsKey("screenshot.png"))
      assertFalse(archive.containsKey("audio.wav"))
      assertFalse(archive.containsKey("sensitive/memory.json"))

      val bundleBytes = Files.readAllBytes(first.bundle)
      assertFalse(bundleBytes.contains(temporary.root.absolutePath.toByteArray(StandardCharsets.UTF_8)))
      assertFalse(bundleBytes.contains(Files.readAllBytes(rom)))
      assertEquals("true", System.getProperty("java.awt.headless"))
    }
  }

  @Test
  fun breakpointAndExpectedHashUseTheStableTerminalExitCodes() {
    val rom = writeSyntheticRom(temporary.root.toPath().resolve("synthetic.gb"))

    val breakpoint =
        invoke(
            "run",
            "--rom",
            rom.toString(),
            "--profile",
            "dmg",
            "--bootstrap",
            "skip",
            "--sgb-border",
            "off",
            "--ticks",
            "100",
            "--break",
            "tick:7",
            "--expect-full-hash",
            "0".repeat(64),
        )
    assertEquals(4, breakpoint.exitCode)
    assertTrue(breakpoint.stdout.contains("\"exitCode\":4"))
    assertTrue(breakpoint.stdout.contains("\"termination\":\"breakpoint\""))
    assertTrue(breakpoint.stdout.contains("\"completedTicks\":7"))
    assertTrue(breakpoint.stdout.contains("\"divergence\":null"))
    assertTrue(breakpoint.stderr.startsWith("error[breakpoint-reached]:"))

    val divergence =
        invoke(
            "run",
            "--rom",
            rom.toString(),
            "--profile",
            "dmg",
            "--bootstrap",
            "skip",
            "--sgb-border",
            "off",
            "--ticks",
            "17",
            "--expect-full-hash",
            "0".repeat(64),
        )
    assertEquals(5, divergence.exitCode)
    assertTrue(divergence.stdout.contains("\"exitCode\":5"))
    assertTrue(divergence.stdout.contains("\"source\":\"expected-full-hash\""))
    assertTrue(divergence.stderr.startsWith("error[deterministic-divergence]:"))
  }

  @Test
  fun goldenReplayCompletesWhileMismatchBudgetAndCollisionFailPrecisely() {
    val fixture =
        repositoryRoot().resolve("controller/src/test/resources/replay-v1/synthetic-input.cgbreplay")
    assertTrue(Files.isRegularFile(fixture), "Missing repository-owned replay fixture")
    val rom = writeSyntheticRom(temporary.root.toPath().resolve("replay-rom.gb"))

    val completed =
        invoke(
            "replay",
            "--rom",
            rom.toString(),
            "--replay",
            fixture.toString(),
            "--max-ticks",
            GOLDEN_REPLAY_TICKS.toString(),
        )
    assertEquals(0, completed.exitCode)
    assertEquals("", completed.stderr)
    assertTrue(completed.stdout.contains("\"termination\":\"replay-completed\""))
    assertTrue(completed.stdout.contains("\"completedTicks\":$GOLDEN_REPLAY_TICKS"))

    val budget =
        invoke(
            "replay",
            "--rom",
            rom.toString(),
            "--replay",
            fixture.toString(),
            "--max-ticks",
            "1",
        )
    assertEquals(6, budget.exitCode)
    assertTrue(budget.stdout.contains("\"exitCode\":6"))
    assertTrue(budget.stdout.contains("\"termination\":\"replay-budget-exhausted\""))
    assertTrue(budget.stderr.startsWith("error[replay-budget-exhausted]:"))

    val incompatibleBytes = syntheticRom().also { it[0x200] = 1 }
    val incompatibleRom = temporary.root.toPath().resolve("different-rom.gb")
    Files.write(incompatibleRom, incompatibleBytes)
    val incompatible =
        invoke(
            "replay",
            "--rom",
            incompatibleRom.toString(),
            "--replay",
            fixture.toString(),
            "--max-ticks",
            GOLDEN_REPLAY_TICKS.toString(),
        )
    assertEquals(3, incompatible.exitCode)
    assertTrue(incompatible.stdout.contains("\"exitCode\":3"))
    assertTrue(incompatible.stdout.contains("\"code\":\"replay-incompatible\""))
    assertTrue(incompatible.stderr.startsWith("error[replay-incompatible]:"))
    assertFalse(incompatible.stdout.contains(incompatibleRom.toString()))
    assertFalse(incompatible.stderr.contains(incompatibleRom.toString()))

    val occupied = temporary.root.toPath().resolve("occupied-report.json")
    val sentinel = "do-not-replace".toByteArray(StandardCharsets.US_ASCII)
    Files.write(occupied, sentinel)
    val collision =
        invoke(
            "run",
            "--rom",
            rom.toString(),
            "--ticks",
            "1",
            "--json-out",
            occupied.toString(),
        )
    assertEquals(6, collision.exitCode)
    assertTrue(collision.stdout.contains("\"code\":\"output-exists\""))
    assertTrue(collision.stderr.startsWith("error[output-exists]:"))
    assertContentEquals(sentinel, Files.readAllBytes(occupied))
  }

  @Test
  fun invalidRomAndControlOnlyHeaderMetadataRemainSafeAndTyped() {
    val missing = temporary.root.toPath().resolve("private-missing.gb")
    val invalid = invoke("run", "--rom", missing.toString(), "--ticks", "1")
    assertEquals(2, invalid.exitCode)
    assertTrue(invalid.stdout.contains("\"code\":\"rom-invalid\""))
    assertFalse(invalid.stdout.contains(missing.toString()))
    assertFalse(invalid.stderr.contains(missing.toString()))

    val hostileBytes =
        syntheticRom().also { bytes ->
          for (address in 0x134..0x142) bytes[address] = 0x7f
          updateHeaderChecksum(bytes)
        }
    val hostileRom = temporary.root.toPath().resolve("control-title.gb")
    val bundle = temporary.root.toPath().resolve("control-title.zip")
    Files.write(hostileRom, hostileBytes)
    val bundled =
        invoke(
            "run",
            "--rom",
            hostileRom.toString(),
            "--ticks",
            "1",
            "--bundle",
            bundle.toString(),
        )
    assertEquals(0, bundled.exitCode)
    assertEquals("", bundled.stderr)
    val manifest =
        unzip(Files.readAllBytes(bundle))
            .getValue("manifest.json")
            .toString(StandardCharsets.UTF_8)
    assertTrue(manifest.contains("\"title\":\"<redacted>\""))
    assertFalse(manifest.contains("\\u007f"))
  }

  private fun runWithEvidenceArgs(rom: Path, outputs: ArtifactTargets): Array<String> =
      arrayOf(
          "run",
          "--rom",
          rom.toString(),
          "--profile",
          "dmg",
          "--bootstrap",
          "skip",
          "--sgb-border",
          "off",
          "--frames",
          "1",
          "--rtc-epoch-millis",
          "946684800000",
          "--memory",
          "work-ram:0xc000:16",
          "--screenshot",
          outputs.screenshot.toString(),
          "--wav",
          outputs.wav.toString(),
          "--json-out",
          outputs.report.toString(),
          "--bundle",
          outputs.bundle.toString(),
      )

  private fun artifactTargets(directory: Path): ArtifactTargets =
      ArtifactTargets(
          directory.resolve("screenshot.png"),
          directory.resolve("audio.wav"),
          directory.resolve("report.json"),
          directory.resolve("diagnostic.zip"),
      )

  private fun assertArtifactsEqual(first: ArtifactTargets, second: ArtifactTargets) {
    assertContentEquals(Files.readAllBytes(first.screenshot), Files.readAllBytes(second.screenshot))
    assertContentEquals(Files.readAllBytes(first.wav), Files.readAllBytes(second.wav))
    assertContentEquals(Files.readAllBytes(first.report), Files.readAllBytes(second.report))
    assertContentEquals(Files.readAllBytes(first.bundle), Files.readAllBytes(second.bundle))
  }

  private fun assertPng(bytes: ByteArray) {
    assertTrue(bytes.size > 33)
    assertContentEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
    assertEquals(160, bigEndianInt(bytes, 16))
    assertEquals(144, bigEndianInt(bytes, 20))
  }

  private fun assertWav(bytes: ByteArray) {
    assertTrue(bytes.size > 44)
    assertEquals("RIFF", String(bytes, 0, 4, StandardCharsets.US_ASCII))
    assertEquals("WAVE", String(bytes, 8, 4, StandardCharsets.US_ASCII))
    assertEquals("fmt ", String(bytes, 12, 4, StandardCharsets.US_ASCII))
    assertEquals("data", String(bytes, 36, 4, StandardCharsets.US_ASCII))
  }

  private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes()
        zip.closeEntry()
      }
    }
    return entries
  }

  private fun invoke(vararg args: String): Invocation {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val exitCode =
        CliApplication(HeadlessCliEngineFactory.create())
            .run(
                arrayOf(*args),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
                "test-version",
            )
    return Invocation(
        exitCode,
        stdout.toString(StandardCharsets.UTF_8),
        stderr.toString(StandardCharsets.UTF_8),
    )
  }

  private fun writeSyntheticRom(path: Path): Path {
    Files.write(path, syntheticRom())
    return path
  }

  private fun syntheticRom(): ByteArray =
      ByteArray(0x8000).also { bytes ->
        "CGBR-TEST".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x100] = 0x00
        bytes[0x101] = 0x18
        bytes[0x102] = 0xfd.toByte()
        bytes[0x143] = 0
        bytes[0x146] = 0
        bytes[0x147] = 0
        bytes[0x148] = 0
        bytes[0x149] = 0
        updateHeaderChecksum(bytes)
      }

  private fun updateHeaderChecksum(bytes: ByteArray) {
    var headerChecksum = 0
    for (address in 0x134..0x14c) {
      headerChecksum = (headerChecksum - (bytes[address].toInt() and 0xff) - 1) and 0xff
    }
    bytes[0x14d] = headerChecksum.toByte()
  }

  private fun repositoryRoot(): Path {
    System.getProperty("coffee-gb.repo-root")?.let { return Path.of(it) }
    val current = Path.of("").toAbsolutePath().normalize()
    return if (Files.isDirectory(current.resolve("controller"))) current else current.parent
  }

  private fun <T> withHeadlessJvm(action: () -> T): T {
    val previous = System.getProperty("java.awt.headless")
    System.setProperty("java.awt.headless", "true")
    return try {
      action()
    } finally {
      if (previous == null) System.clearProperty("java.awt.headless")
      else System.setProperty("java.awt.headless", previous)
    }
  }

  private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    outer@ for (offset in 0..size - needle.size) {
      for (index in needle.indices) {
        if (this[offset + index] != needle[index]) continue@outer
      }
      return true
    }
    return false
  }

  private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)

  private data class ArtifactTargets(
      val screenshot: Path,
      val wav: Path,
      val report: Path,
      val bundle: Path,
  )

  private data class Invocation(val exitCode: Int, val stdout: String, val stderr: String)

  private companion object {
    const val GOLDEN_REPLAY_TICKS = 69_912L
    val PNG_SIGNATURE =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
  }
}
