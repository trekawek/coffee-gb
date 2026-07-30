package eu.rekawek.coffeegb.cli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** One absolute controller-mask transition applied immediately before zero-based [tick]. */
data class CgbiInputRecord(val tick: Long, val playerNumber: Int, val buttons: Int)

/** Immutable, canonical CGBI input timeline. */
class CgbiInputScript(records: List<CgbiInputRecord>) {
  val records: List<CgbiInputRecord> = records.toList()

  init {
    require(this.records.size <= CgbiInputScriptCodec.MAX_INPUT_RECORDS) {
      "Input script has too many records"
    }
    var previousTick = -1L
    var previousPlayer = 0
    val masks = IntArray(CgbiInputScriptCodec.MAX_PLAYERS)
    for (record in this.records) {
      require(record.tick in 0..CgbiInputScriptCodec.MAX_TIMELINE_TICK) {
        "Input script tick is out of range"
      }
      require(record.playerNumber in 1..CgbiInputScriptCodec.MAX_PLAYERS) {
        "Input script player number is out of range"
      }
      require(record.buttons in 0..0xff) { "Input script button mask is out of range" }
      require(record.tick >= previousTick) { "Input script records are not ordered" }
      if (record.tick == previousTick) {
        require(record.playerNumber > previousPlayer) {
          "Input script players at one tick are not strictly ordered"
        }
      } else {
        previousPlayer = 0
      }
      val playerIndex = record.playerNumber - 1
      require(masks[playerIndex] != record.buttons) { "Input script contains a no-op transition" }
      masks[playerIndex] = record.buttons
      previousTick = record.tick
      previousPlayer = record.playerNumber
    }
  }

  override fun equals(other: Any?): Boolean =
      other is CgbiInputScript && records == other.records

  override fun hashCode(): Int = records.hashCode()

  override fun toString(): String = "CgbiInputScript(records=${records.size})"
}

enum class CgbiInputError {
  IO,
  TOO_LARGE,
  INVALID_UTF8,
  INVALID_HEADER,
  INVALID_LINE_ENDING,
  INVALID_RECORD,
  TOO_MANY_RECORDS,
  NON_CANONICAL_TIMELINE,
}

/** Decode failure whose message never includes script contents or a filesystem path. */
class CgbiInputException(
    val error: CgbiInputError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Strict codec for the small, reviewable Coffee GB Input (`CGBI`) v1 text format.
 *
 * The decoder has independent byte, line, and record limits and never silently ignores malformed
 * rows. Records contain absolute button masks so applying a script is independent of key-repeat or
 * host input state.
 */
object CgbiInputScriptCodec {
  const val MAX_FILE_BYTES = 16 * 1024 * 1024
  const val MAX_INPUT_RECORDS = 1_000_000
  const val MAX_PLAYERS = 4
  const val MAX_TIMELINE_TICK = Long.MAX_VALUE - 1L
  const val MAX_LINE_CHARS = 96

  private const val HEADER = "CGBI\t1\t0"
  private val DECIMAL = Regex("0|[1-9][0-9]*")
  private val MASK = Regex("0x[0-9a-fA-F]{2}")

  @Throws(CgbiInputException::class)
  fun read(path: Path): CgbiInputScript {
    val declaredSize =
        try {
          Files.size(path)
        } catch (failure: IOException) {
          throw CgbiInputException(CgbiInputError.IO, "Input script could not be read", failure)
        } catch (failure: SecurityException) {
          throw CgbiInputException(CgbiInputError.IO, "Input script could not be read", failure)
        }
    if (declaredSize > MAX_FILE_BYTES) {
      throw CgbiInputException(CgbiInputError.TOO_LARGE, "Input script exceeds the byte limit")
    }

    val bytes =
        try {
          Files.newInputStream(path).use { input ->
            val output = ByteArrayOutputStream(minOf(declaredSize.toInt(), 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
              val read = input.read(buffer)
              if (read == -1) break
              total += read
              if (total > MAX_FILE_BYTES) {
                throw CgbiInputException(
                    CgbiInputError.TOO_LARGE,
                    "Input script exceeds the byte limit",
                )
              }
              output.write(buffer, 0, read)
            }
            output.toByteArray()
          }
        } catch (failure: CgbiInputException) {
          throw failure
        } catch (failure: IOException) {
          throw CgbiInputException(CgbiInputError.IO, "Input script could not be read", failure)
        } catch (failure: SecurityException) {
          throw CgbiInputException(CgbiInputError.IO, "Input script could not be read", failure)
        }
    return decode(bytes)
  }

  @Throws(CgbiInputException::class)
  fun decode(bytes: ByteArray): CgbiInputScript {
    if (bytes.size > MAX_FILE_BYTES) {
      throw CgbiInputException(CgbiInputError.TOO_LARGE, "Input script exceeds the byte limit")
    }
    val text =
        try {
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString()
        } catch (failure: Exception) {
          throw CgbiInputException(
              CgbiInputError.INVALID_UTF8,
              "Input script is not strict UTF-8",
              failure,
          )
        }
    if (text.isEmpty() || text[0] == '\uFEFF') {
      throw CgbiInputException(CgbiInputError.INVALID_HEADER, "Input script header is invalid")
    }
    for (index in text.indices) {
      if (text[index] == '\r' && (index + 1 >= text.length || text[index + 1] != '\n')) {
        throw CgbiInputException(
            CgbiInputError.INVALID_LINE_ENDING,
            "Input script contains a bare carriage return",
        )
      }
    }

    val records = ArrayList<CgbiInputRecord>()
    val masks = IntArray(MAX_PLAYERS)
    var previousTick = -1L
    var previousPlayer = 0
    var lineNumber = 0
    var start = 0
    while (start < text.length) {
      val newline = text.indexOf('\n', start)
      val physicalEnd = if (newline == -1) text.length else newline
      val end = if (physicalEnd > start && text[physicalEnd - 1] == '\r') physicalEnd - 1 else physicalEnd
      val line = text.substring(start, end)
      lineNumber++
      if (line.length > MAX_LINE_CHARS) {
        throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script line is too long")
      }
      if (lineNumber == 1) {
        if (line != HEADER) {
          throw CgbiInputException(CgbiInputError.INVALID_HEADER, "Input script header is invalid")
        }
      } else {
        if (line.isEmpty()) {
          throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script has a blank row")
        }
        if (records.size >= MAX_INPUT_RECORDS) {
          throw CgbiInputException(
              CgbiInputError.TOO_MANY_RECORDS,
              "Input script exceeds the record limit",
          )
        }
        val fields = line.split('\t')
        if (fields.size != 3) {
          throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script row is invalid")
        }
        val tick = parseDecimal(fields[0], MAX_TIMELINE_TICK)
        val player = fields[1].takeIf { it.length == 1 }?.toIntOrNull()
        val buttons =
            fields[2].takeIf(MASK::matches)?.substring(2)?.toIntOrNull(16)
        val validTick = tick
            ?: throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script row is invalid")
        val validPlayer = player
            ?: throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script row is invalid")
        val validButtons = buttons
            ?: throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script row is invalid")
        if (validPlayer !in 1..MAX_PLAYERS) {
          throw CgbiInputException(CgbiInputError.INVALID_RECORD, "Input script row is invalid")
        }
        if (validTick < previousTick ||
            (validTick == previousTick && validPlayer <= previousPlayer)) {
          throw CgbiInputException(
              CgbiInputError.NON_CANONICAL_TIMELINE,
              "Input script timeline is not canonical",
          )
        }
        val playerIndex = validPlayer - 1
        if (masks[playerIndex] == validButtons) {
          throw CgbiInputException(
              CgbiInputError.NON_CANONICAL_TIMELINE,
              "Input script contains a no-op transition",
          )
        }
        masks[playerIndex] = validButtons
        records += CgbiInputRecord(validTick, validPlayer, validButtons)
        previousTick = validTick
        previousPlayer = validPlayer
      }
      if (newline == -1) break
      start = newline + 1
    }
    return CgbiInputScript(records)
  }

  @Throws(CgbiInputException::class)
  fun encode(script: CgbiInputScript): ByteArray {
    val text = StringBuilder(HEADER.length + 1 + script.records.size * 16)
    text.append(HEADER).append('\n')
    for (record in script.records) {
      val mask = record.buttons.toString(16).uppercase().padStart(2, '0')
      val line = "${record.tick}\t${record.playerNumber}\t0x$mask\n"
      if (text.length + line.length > MAX_FILE_BYTES) {
        throw CgbiInputException(CgbiInputError.TOO_LARGE, "Input script exceeds the byte limit")
      }
      text.append(line)
    }
    return text.toString().toByteArray(StandardCharsets.UTF_8)
  }

  private fun parseDecimal(value: String, maximum: Long): Long? {
    if (!DECIMAL.matches(value)) return null
    val parsed = value.toLongOrNull() ?: return null
    return parsed.takeIf { it <= maximum }
  }
}
