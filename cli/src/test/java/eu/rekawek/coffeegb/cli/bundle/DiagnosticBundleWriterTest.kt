package eu.rekawek.coffeegb.cli.bundle

import eu.rekawek.coffeegb.cli.codec.DeterministicPngEncoder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticBundleWriterTest {
  @get:Rule
  val temporary = TemporaryFolder()

  @Test
  fun defaultBundleHasDeterministicStoredEntriesAndGoldenManifest() {
    val first = DiagnosticBundleWriter.encode(metadata(), defaultEntries())
    val second = DiagnosticBundleWriter.encode(metadata(), defaultEntries().reversed())
    assertContentEquals(first, second)

    val archive = unzip(first)
    assertEquals(listOf("logs.ndjson", "manifest.json", "report.json"), archive.map { it.entry.name })
    archive.forEach { payload ->
      assertEquals(ZipEntry.STORED, payload.entry.method)
      assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), payload.entry.timeLocal)
      assertEquals(payload.bytes.size.toLong(), payload.entry.size)
      assertEquals(payload.bytes.size.toLong(), payload.entry.compressedSize)
      assertEquals(null, payload.entry.comment)
    }

    val manifest = archive.single { it.entry.name == "manifest.json" }
        .bytes.toString(StandardCharsets.UTF_8)
    assertEquals(EXPECTED_DEFAULT_MANIFEST, manifest)
  }

  @Test
  fun allOptionalContentRequiresMatchingRequestAndConfirmationGates() {
    val screenshot = DiagnosticBundleEntry(
        DiagnosticBundleEntryKind.SCREENSHOT_PNG,
        DeterministicPngEncoder.encodeRgb8(1, 1, intArrayOf(0x123456)),
    )
    val withMedia = defaultEntries() + screenshot

    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(metadata(), withMedia)
    }
    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(
          metadata(),
          withMedia,
          DiagnosticBundleConsent(requestedSensitive = setOf(DiagnosticSensitiveCategory.MEDIA)),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(
          metadata(),
          withMedia,
          DiagnosticBundleConsent(confirmedSensitive = setOf(DiagnosticSensitiveCategory.MEDIA)),
      )
    }

    val encoded = DiagnosticBundleWriter.encode(
        metadata(),
        withMedia,
        confirmed(DiagnosticSensitiveCategory.MEDIA),
    )
    assertTrue(unzip(encoded).any { it.entry.name == "screenshot.png" })
  }

  @Test
  fun memoryAndBothReplayFormsUseDistinctSensitiveCategories() {
    val optionalEntries = listOf(
        DiagnosticBundleEntry(
            DiagnosticBundleEntryKind.MEMORY_JSON,
            "{\"address\":0,\"bytes\":\"00\"}\n".toByteArray(),
        ),
        DiagnosticBundleEntry(DiagnosticBundleEntryKind.SAFE_REPLAY, byteArrayOf(1, 2)),
        DiagnosticBundleEntry(DiagnosticBundleEntryKind.RAW_REPLAY, byteArrayOf(3, 4)),
    )
    val categories = setOf(
        DiagnosticSensitiveCategory.MEMORY,
        DiagnosticSensitiveCategory.REPLAY,
        DiagnosticSensitiveCategory.RAW_REPLAY,
    )

    val encoded = DiagnosticBundleWriter.encode(
        metadata(),
        defaultEntries() + optionalEntries,
        DiagnosticBundleConsent(categories, categories),
    )
    assertEquals(
        listOf(
            "logs.ndjson",
            "manifest.json",
            "replay.cgbreplay",
            "report.json",
            "sensitive/memory.json",
            "sensitive/replay.cgbreplay",
        ),
        unzip(encoded).map { it.entry.name },
    )
  }

  @Test
  fun entryAllowlistCannotRepresentRomSaveBootPathOrTokenPayloads() {
    assertEquals(
        setOf(
            "REPORT_JSON",
            "LOGS_NDJSON",
            "SAFE_REPLAY",
            "SCREENSHOT_PNG",
            "AUDIO_WAV",
            "MEMORY_JSON",
            "RAW_REPLAY",
        ),
        DiagnosticBundleEntryKind.entries.map { it.name }.toSet(),
    )
    val forbidden = Regex("rom|save|boot|path|token", RegexOption.IGNORE_CASE)
    DiagnosticBundleEntryKind.entries.forEach { kind ->
      assertFalse(forbidden.containsMatchIn(kind.name))
      assertFalse(forbidden.containsMatchIn(kind.path))
    }
  }

  @Test
  fun rejectsQuotedCredentialKeysAndCommonPathFormsInTextPayloads() {
    val canaries = listOf(
        "{\"token\":\"SECRET\"}\n",
        "{\"password\":\"SECRET\"}\n",
        "{\"apiKey\":\"SECRET\"}\n",
        "{\"note\":\"/home/alice/private\"}\n",
        "{\"note\":\"C:\\\\Users\\\\alice\\\\private\"}\n",
        "{\"note\":\"~/private\"}\n",
    )
    canaries.forEach { canary ->
      assertFailsWith<IllegalArgumentException>(canary) {
        DiagnosticBundleWriter.encode(
            metadata(),
            defaultEntries(report = canary.toByteArray(StandardCharsets.UTF_8)),
        )
      }
    }
  }

  @Test
  fun redactsPrivateMetadataBeforeItCanReachTheManifest() {
    val privatePath = "/home/alice/private"
    val encoded = DiagnosticBundleWriter.encode(
        metadata(execution = mapOf("status" to privatePath)),
        defaultEntries(),
    )
    val manifest = unzip(encoded).single { it.entry.name == "manifest.json" }
        .bytes.toString(StandardCharsets.UTF_8)

    assertFalse(manifest.contains(privatePath))
    assertTrue(manifest.contains("<redacted>"))
  }

  @Test
  fun rejectsUnknownMetadataDuplicateKindsAndOversizedEntries() {
    assertFailsWith<IllegalArgumentException> {
      metadata(configuration = mapOf("userHome" to "/private"))
    }
    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(metadata(), defaultEntries() + defaultEntries().first())
    }
    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(
          metadata(),
          defaultEntries(report = ByteArray(DiagnosticBundleEntryKind.REPORT_JSON.maximumBytes + 1)),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DiagnosticBundleWriter.encode(
          metadata(),
          listOf(defaultEntries().first()),
      )
    }
  }

  @Test
  fun bundleWriteNeverOverwritesAnExistingArtifact() {
    val target = temporary.root.toPath().resolve("diagnostic.zip")
    val expected = DiagnosticBundleWriter.encode(metadata(), defaultEntries())

    DiagnosticBundleWriter.write(target, metadata(), defaultEntries())
    assertFailsWith<FileAlreadyExistsException> {
      DiagnosticBundleWriter.write(target, metadata(), defaultEntries())
    }
    assertContentEquals(expected, Files.readAllBytes(target))
  }

  private fun metadata(
      configuration: Map<String, String> = mapOf("profile" to "dmg", "bootstrap" to "skip"),
      execution: Map<String, String> = mapOf("status" to "completed", "executedTicks" to "42"),
  ) = DiagnosticBundleMetadata(
      applicationVersion = "1.7.17-test",
      javaVersion = "21.0.7",
      javaVendor = "Test Vendor",
      javaVmName = "Test VM",
      osName = "Test OS",
      osVersion = "1",
      osArchitecture = "x86_64",
      configuration = configuration,
      execution = execution,
  )

  private fun defaultEntries(
      report: ByteArray = REPORT,
      logs: ByteArray = LOGS,
  ): List<DiagnosticBundleEntry> = listOf(
      DiagnosticBundleEntry(DiagnosticBundleEntryKind.REPORT_JSON, report),
      DiagnosticBundleEntry(DiagnosticBundleEntryKind.LOGS_NDJSON, logs),
  )

  private fun confirmed(category: DiagnosticSensitiveCategory): DiagnosticBundleConsent =
      DiagnosticBundleConsent(setOf(category), setOf(category))

  private fun unzip(encoded: ByteArray): List<ZipPayload> {
    val result = ArrayList<ZipPayload>()
    ZipInputStream(ByteArrayInputStream(encoded), StandardCharsets.UTF_8).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        result += ZipPayload(entry, zip.readBytes())
        zip.closeEntry()
      }
    }
    return result
  }

  private data class ZipPayload(val entry: ZipEntry, val bytes: ByteArray)

  companion object {
    private val REPORT =
        "{\"schema\":\"coffee-gb/headless-report\",\"version\":1}\n".toByteArray()
    private val LOGS = "{\"level\":\"info\",\"message\":\"ok\"}\n".toByteArray()
    private const val EXPECTED_DEFAULT_MANIFEST =
        "{\"schema\":\"coffee-gb/diagnostic-bundle-manifest\",\"version\":1," +
            "\"application\":{\"version\":\"1.7.17-test\"}," +
            "\"runtime\":{\"javaVersion\":\"21.0.7\",\"javaVendor\":\"Test Vendor\"," +
            "\"javaVmName\":\"Test VM\",\"osName\":\"Test OS\",\"osVersion\":\"1\"," +
            "\"osArchitecture\":\"x86_64\"}," +
            "\"configuration\":{\"bootstrap\":\"skip\",\"profile\":\"dmg\"}," +
            "\"rom\":null,\"execution\":{\"executedTicks\":\"42\",\"status\":\"completed\"}," +
            "\"privacy\":{\"includedSensitive\":[],\"excluded\":[\"boot-rom\",\"media\"," +
            "\"memory\",\"paths\",\"raw-replay\",\"replay\",\"rom\",\"saves\",\"tokens\"]}," +
            "\"entries\":[{\"name\":\"logs.ndjson\",\"mediaType\":\"application/x-ndjson\"," +
            "\"bytes\":32,\"sha256\":\"ce8193f87c232b570ee651648d84eed2f30e82bc10825f5b728dd9865d845c3e\"," +
            "\"sensitive\":false},{\"name\":\"report.json\",\"mediaType\":\"application/json\"," +
            "\"bytes\":51,\"sha256\":\"b923d6ad57e740d00ba95d3d450eb2040a42700ef34bed30f364aae8cdf54bf0\"," +
            "\"sensitive\":false}]}\n"
  }
}
