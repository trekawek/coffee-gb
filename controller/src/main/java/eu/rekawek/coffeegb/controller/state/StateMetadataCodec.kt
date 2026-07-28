package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Optional UI metadata. Compatibility always comes from the associated decoded [StateFile]. */
data class StateMetadata(
    val ref: StateRef,
    val label: String?,
    val savedAt: Instant,
    val playDurationNanos: Long?,
    val stateBytes: Int,
    val stateSha256: String,
    val thumbnailSha256: String?,
) {
  init {
    validateStateLabel(label)
    require(playDurationNanos == null || playDurationNanos >= 0) {
      "State play duration must not be negative"
    }
    require(stateBytes in 1..StateLimits.PORTABLE_MAX_FILE_BYTES) {
      "State byte count must be between 1 and ${StateLimits.PORTABLE_MAX_FILE_BYTES}"
    }
    require(StateStorageLayout.isSha256(stateSha256)) {
      "State hash must be 64 lowercase hex digits"
    }
    require(thumbnailSha256 == null || StateStorageLayout.isSha256(thumbnailSha256)) {
      "Thumbnail hash must be 64 lowercase hex digits"
    }
  }
}

/** Caller-supplied fields for a repository save; state size and hash are computed by the store. */
data class StateSaveMetadata(
    val label: String? = null,
    val savedAt: Instant,
    val playDurationNanos: Long? = null,
    val thumbnailSha256: String? = null,
) {
  init {
    validateStateLabel(label)
    require(playDurationNanos == null || playDurationNanos >= 0) {
      "State play duration must not be negative"
    }
    require(thumbnailSha256 == null || StateStorageLayout.isSha256(thumbnailSha256)) {
      "Thumbnail hash must be 64 lowercase hex digits"
    }
  }
}

class StateMetadataException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Strict canonical bounded properties codec for [StateMetadata]. */
object StateMetadataCodec {
  const val SCHEMA_VERSION = 1
  const val MAX_METADATA_BYTES = 16 * 1024

  private const val SCHEMA_KEY = "metadata.schemaVersion"
  private const val REFERENCE_KEY = "state.reference"
  private const val LABEL_KEY = "state.label"
  private const val SAVED_AT_KEY = "state.savedAt"
  private const val PLAY_DURATION_KEY = "state.playDurationNanos"
  private const val STATE_BYTES_KEY = "state.bytes"
  private const val STATE_SHA256_KEY = "state.sha256"
  private const val THUMBNAIL_SHA256_KEY = "thumbnail.sha256"

  private val KNOWN_KEYS =
      setOf(
          SCHEMA_KEY,
          REFERENCE_KEY,
          LABEL_KEY,
          SAVED_AT_KEY,
          PLAY_DURATION_KEY,
          STATE_BYTES_KEY,
          STATE_SHA256_KEY,
          THUMBNAIL_SHA256_KEY,
      )

  fun encode(metadata: StateMetadata): ByteArray {
    val values =
        linkedMapOf(
            SCHEMA_KEY to SCHEMA_VERSION.toString(),
            REFERENCE_KEY to metadata.ref.storageKey(),
            SAVED_AT_KEY to metadata.savedAt.toString(),
            STATE_BYTES_KEY to metadata.stateBytes.toString(),
            STATE_SHA256_KEY to metadata.stateSha256,
        )
    metadata.label?.let { values[LABEL_KEY] = it }
    metadata.playDurationNanos?.let { values[PLAY_DURATION_KEY] = it.toString() }
    metadata.thumbnailSha256?.let { values[THUMBNAIL_SHA256_KEY] = it }
    return ApplicationSettingsStore.encodeProperties(values).also {
      require(it.size <= MAX_METADATA_BYTES) {
        "Encoded state metadata exceeds the $MAX_METADATA_BYTES-byte limit"
      }
    }
  }

  fun decode(bytes: ByteArray): StateMetadata {
    if (bytes.size > MAX_METADATA_BYTES) {
      throw StateMetadataException(
          "State metadata exceeds the $MAX_METADATA_BYTES-byte limit")
    }
    try {
      val values = ApplicationSettingsStore.decodeProperties(bytes, StandardCharsets.UTF_8)
      require(values.keys == values.keys.intersect(KNOWN_KEYS)) {
        "State metadata contains unknown properties"
      }
      require(values[SCHEMA_KEY] == SCHEMA_VERSION.toString()) {
        "Unsupported state metadata schema ${values[SCHEMA_KEY] ?: "absent"}"
      }
      val savedAtText = required(values, SAVED_AT_KEY)
      val savedAt = Instant.parse(savedAtText)
      require(savedAt.toString() == savedAtText) { "State save time is not canonical UTC text" }
      val metadata =
          StateMetadata(
              ref = StateRef.parseStorageKey(required(values, REFERENCE_KEY)),
              label = values[LABEL_KEY],
              savedAt = savedAt,
              playDurationNanos = values[PLAY_DURATION_KEY]?.let(::parseNonNegativeLong),
              stateBytes = parsePositiveInt(required(values, STATE_BYTES_KEY)),
              stateSha256 = required(values, STATE_SHA256_KEY),
              thumbnailSha256 = values[THUMBNAIL_SHA256_KEY],
          )
      require(bytes.contentEquals(encode(metadata))) {
        "State metadata is not in canonical form"
      }
      return metadata
    } catch (failure: StateMetadataException) {
      throw failure
    } catch (failure: RuntimeException) {
      throw StateMetadataException(
          failure.message ?: "State metadata is malformed",
          failure,
      )
    }
  }

  fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun required(values: Map<String, String>, key: String): String =
      requireNotNull(values[key]) { "State metadata has no $key" }

  private fun parseNonNegativeLong(value: String): Long {
    require(value == "0" || (value.isNotEmpty() && value[0] in '1'..'9' &&
        value.all { it in '0'..'9' })) {
      "Invalid state play duration: $value"
    }
    return value.toLongOrNull()
        ?: throw IllegalArgumentException("State play duration is too large")
  }

  private fun parsePositiveInt(value: String): Int {
    require(value.isNotEmpty() && value[0] in '1'..'9' && value.all { it in '0'..'9' }) {
      "Invalid state byte count: $value"
    }
    return value.toIntOrNull() ?: throw IllegalArgumentException("State byte count is too large")
  }
}

private fun validateStateLabel(label: String?) {
  if (label == null) return
  require(label.isNotEmpty()) { "State label must not be empty" }
  require(label.codePointCount(0, label.length) <= MAX_STATE_LABEL_CODE_POINTS) {
    "State label exceeds $MAX_STATE_LABEL_CODE_POINTS Unicode characters"
  }
  require(StandardCharsets.UTF_8.newEncoder().canEncode(label)) {
    "State label contains malformed Unicode"
  }
  require(label.toByteArray(StandardCharsets.UTF_8).size <= MAX_STATE_LABEL_UTF8_BYTES) {
    "State label exceeds $MAX_STATE_LABEL_UTF8_BYTES UTF-8 bytes"
  }
  require(label.none { it == '\u0000' || it == '\n' || it == '\r' || Character.isISOControl(it) }) {
    "State label contains control characters"
  }
}

private const val MAX_STATE_LABEL_CODE_POINTS = 120
private const val MAX_STATE_LABEL_UTF8_BYTES = 512
