package eu.rekawek.coffeegb.cli.bundle

import eu.rekawek.coffeegb.cli.codec.CanonicalJson
import eu.rekawek.coffeegb.cli.codec.CanonicalJsonWriter
import eu.rekawek.coffeegb.cli.codec.ExclusiveArtifactWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a byte-for-byte reproducible, allowlisted diagnostic archive. */
object DiagnosticBundleWriter {
  const val MANIFEST_PATH = "manifest.json"
  const val MANIFEST_SCHEMA = "coffee-gb/diagnostic-bundle-manifest"
  const val MANIFEST_VERSION = 1
  const val MAX_BUNDLE_BYTES = 128 * 1024 * 1024

  fun encode(
      metadata: DiagnosticBundleMetadata,
      entries: Collection<DiagnosticBundleEntry>,
      consent: DiagnosticBundleConsent = DiagnosticBundleConsent.NONE,
  ): ByteArray {
    require(entries.size <= DiagnosticBundleEntryKind.entries.size) { "Too many bundle entries" }
    val byKind = entries.associateBy { it.kind }
    require(byKind.size == entries.size) { "A bundle entry kind may occur only once" }
    require(DiagnosticBundleEntryKind.REPORT_JSON in byKind &&
        DiagnosticBundleEntryKind.LOGS_NDJSON in byKind) {
      "A diagnostic bundle requires report.json and logs.ndjson"
    }

    val actualSensitive = byKind.keys.mapNotNull { it.sensitiveCategory }.toSet()
    require(actualSensitive == consent.requestedSensitive) {
      "Sensitive bundle entries must exactly match the explicitly requested categories"
    }
    require(actualSensitive == consent.confirmedSensitive) {
      "Sensitive bundle entries require a separate matching confirmation"
    }

    val payloads = byKind.values
        .map { validatedPayload(it) }
        .sortedBy { it.kind.path }
    val totalPayloadBytes = payloads.sumOf { it.bytes.size.toLong() }
    require(totalPayloadBytes <= MAX_BUNDLE_BYTES.toLong()) { "Bundle payloads are too large" }

    val manifest = manifest(metadata, payloads, actualSensitive)
    val archiveEntries = ArrayList<ArchiveEntry>(payloads.size + 1)
    archiveEntries += ArchiveEntry(MANIFEST_PATH, manifest)
    payloads.forEach { archiveEntries += ArchiveEntry(it.kind.path, it.bytes) }
    archiveEntries.sortBy { it.path }

    val output = ByteArrayOutputStream()
    ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
      archiveEntries.forEach { archiveEntry -> writeStoredEntry(zip, archiveEntry) }
    }
    val encoded = output.toByteArray()
    require(encoded.size <= MAX_BUNDLE_BYTES) { "Encoded diagnostic bundle is too large" }
    return encoded
  }

  fun write(
      target: Path,
      metadata: DiagnosticBundleMetadata,
      entries: Collection<DiagnosticBundleEntry>,
      consent: DiagnosticBundleConsent = DiagnosticBundleConsent.NONE,
  ): Path = ExclusiveArtifactWriter.write(target, encode(metadata, entries, consent))

  private fun validatedPayload(entry: DiagnosticBundleEntry): BundlePayload {
    val bytes = entry.payload()
    require(bytes.size <= entry.kind.maximumBytes) {
      "${entry.kind.path} exceeds its fixed size limit"
    }
    if (entry.kind != DiagnosticBundleEntryKind.LOGS_NDJSON) {
      require(bytes.isNotEmpty()) { "${entry.kind.path} must not be empty" }
    }
    when (entry.kind) {
      DiagnosticBundleEntryKind.REPORT_JSON,
      DiagnosticBundleEntryKind.LOGS_NDJSON,
      DiagnosticBundleEntryKind.MEMORY_JSON -> validatePrivacySafeText(bytes, entry.kind.path)
      DiagnosticBundleEntryKind.SCREENSHOT_PNG -> requirePngEnvelope(bytes)
      DiagnosticBundleEntryKind.AUDIO_WAV -> requireWavEnvelope(bytes)
      DiagnosticBundleEntryKind.SAFE_REPLAY,
      DiagnosticBundleEntryKind.RAW_REPLAY -> Unit
    }
    return BundlePayload(entry.kind, bytes)
  }

  private fun manifest(
      metadata: DiagnosticBundleMetadata,
      payloads: List<BundlePayload>,
      actualSensitive: Set<DiagnosticSensitiveCategory>,
  ): ByteArray {
    val included = actualSensitive.map { it.manifestName }.sorted()
    val absentSensitive = DiagnosticSensitiveCategory.entries
        .filterNot { it in actualSensitive }
        .map { it.manifestName }
    val excluded = (ALWAYS_EXCLUDED + absentSensitive).sorted()

    val root = CanonicalJson.obj(
        "schema" to CanonicalJson.string(MANIFEST_SCHEMA),
        "version" to CanonicalJson.number(MANIFEST_VERSION),
        "application" to CanonicalJson.obj(
            "version" to CanonicalJson.string(sanitize(metadata.applicationVersion))),
        "runtime" to CanonicalJson.obj(
            "javaVersion" to CanonicalJson.string(sanitize(metadata.javaVersion)),
            "javaVendor" to CanonicalJson.string(sanitize(metadata.javaVendor)),
            "javaVmName" to CanonicalJson.string(sanitize(metadata.javaVmName)),
            "osName" to CanonicalJson.string(sanitize(metadata.osName)),
            "osVersion" to CanonicalJson.string(sanitize(metadata.osVersion)),
            "osArchitecture" to CanonicalJson.string(sanitize(metadata.osArchitecture)),
        ),
        "configuration" to stringMap(metadata.configuration),
        "rom" to romMetadata(metadata.rom),
        "execution" to stringMap(metadata.execution),
        "privacy" to CanonicalJson.obj(
            "includedSensitive" to CanonicalJson.array(
                included.map { CanonicalJson.string(it) }),
            "excluded" to CanonicalJson.array(
                excluded.map { CanonicalJson.string(it) }),
        ),
        "entries" to CanonicalJson.array(
            payloads.map { payload ->
              CanonicalJson.obj(
                  "name" to CanonicalJson.string(payload.kind.path),
                  "mediaType" to CanonicalJson.string(payload.kind.mediaType),
                  "bytes" to CanonicalJson.number(payload.bytes.size),
                  "sha256" to CanonicalJson.string(sha256(payload.bytes)),
                  "sensitive" to CanonicalJson.bool(payload.kind.sensitiveCategory != null),
              )
            }),
    )
    return CanonicalJsonWriter.encode(root)
  }

  private fun stringMap(source: Map<String, String>): CanonicalJson.Value =
      CanonicalJson.obj(source.entries.map { (key, value) ->
        key to CanonicalJson.string(sanitize(value))
      })

  private fun romMetadata(rom: DiagnosticRomMetadata?): CanonicalJson.Value {
    if (rom == null) return CanonicalJson.nil()
    return CanonicalJson.obj(
        "sha256" to CanonicalJson.string(rom.sha256),
        "byteLength" to CanonicalJson.number(rom.byteLength),
        "header" to stringMap(rom.header),
    )
  }

  private fun sanitize(value: String): String {
    val oneLine = value
        .map { character -> if (character.code < 0x20 || character.code == 0x7f) ' ' else character }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()
    if (oneLine.isEmpty()) return REDACTED
    require(oneLine.length <= MAX_METADATA_CHARS) { "Diagnostic metadata value is too long" }
    return if (containsPrivateMaterial(oneLine)) REDACTED else oneLine
  }

  private fun validatePrivacySafeText(bytes: ByteArray, name: String) {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
      throw IllegalArgumentException("$name is not valid UTF-8")
    }
    require(text.none { it == '\u0000' || (it.code < 0x20 && it !in "\r\n\t") }) {
      "$name contains forbidden control characters"
    }
    require(!containsPrivateMaterial(text)) {
      "$name contains a filesystem path or credential-like assignment"
    }
  }

  private fun containsPrivateMaterial(value: String): Boolean =
      CREDENTIAL_ASSIGNMENT.containsMatchIn(value) ||
          UNIX_ABSOLUTE_PATH.containsMatchIn(value) ||
          WINDOWS_ABSOLUTE_PATH.containsMatchIn(value) ||
          FILE_URI.containsMatchIn(value) ||
          HOME_PATH.containsMatchIn(value)

  private fun requirePngEnvelope(bytes: ByteArray) {
    require(bytes.size >= PNG_SIGNATURE.size + 12) { "screenshot.png is not a PNG" }
    require(bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
      "screenshot.png is not a PNG"
    }
  }

  private fun requireWavEnvelope(bytes: ByteArray) {
    require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
      "audio.wav is not a PCM WAVE file"
    }
    require(ascii(bytes, 12, 4) == "fmt " && ascii(bytes, 36, 4) == "data") {
      "audio.wav contains an unsupported chunk layout"
    }
    require(unsignedShortLittleEndian(bytes, 20) == 1 &&
        unsignedShortLittleEndian(bytes, 22) == 2 &&
        unsignedShortLittleEndian(bytes, 34) == 16) {
      "audio.wav must be PCM16 stereo"
    }
    require(intLittleEndian(bytes, 40) == bytes.size - 44) { "audio.wav has an invalid data size" }
  }

  private fun writeStoredEntry(zip: ZipOutputStream, archiveEntry: ArchiveEntry) {
    val crc = CRC32().apply { update(archiveEntry.bytes) }.value
    val entry = ZipEntry(archiveEntry.path)
    entry.method = ZipEntry.STORED
    entry.size = archiveEntry.bytes.size.toLong()
    entry.compressedSize = archiveEntry.bytes.size.toLong()
    entry.crc = crc
    entry.setTimeLocal(FIXED_ZIP_TIME)
    zip.putNextEntry(entry)
    zip.write(archiveEntry.bytes)
    zip.closeEntry()
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
      String(bytes, offset, length, StandardCharsets.US_ASCII)

  private fun unsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int =
      (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

  private fun intLittleEndian(bytes: ByteArray, offset: Int): Int =
      (bytes[offset].toInt() and 0xff) or
          ((bytes[offset + 1].toInt() and 0xff) shl 8) or
          ((bytes[offset + 2].toInt() and 0xff) shl 16) or
          ((bytes[offset + 3].toInt() and 0xff) shl 24)

  private data class BundlePayload(
      val kind: DiagnosticBundleEntryKind,
      val bytes: ByteArray,
  )

  private data class ArchiveEntry(
      val path: String,
      val bytes: ByteArray,
  )

  private const val MAX_METADATA_CHARS = 256
  private const val REDACTED = "<redacted>"
  private val FIXED_ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)
  private val ALWAYS_EXCLUDED = setOf("boot-rom", "paths", "rom", "saves", "tokens")
  private val WHITESPACE = Regex("\\s+")
  private val CREDENTIAL_ASSIGNMENT = Regex(
      "(?i)(?:token|password|secret|authorization|api[_-]?key)\\s*[\"']?\\s*[:=]")
  private val UNIX_ABSOLUTE_PATH = Regex("(^|[\\s\"'])/(?!/)[^\\s\"']+")
  private val WINDOWS_ABSOLUTE_PATH = Regex("(?i)(^|[\\s\"'])[a-z]:[\\\\/]")
  private val FILE_URI = Regex("(?i)file:(?:/{1,3}|\\\\)")
  private val HOME_PATH = Regex("(^|[\\s\"'])~[\\\\/]")
  private val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
