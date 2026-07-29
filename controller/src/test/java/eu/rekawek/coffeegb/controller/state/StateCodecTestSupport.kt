package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.security.MessageDigest

internal object StateCodecTestSupport {
  fun rom(seed: Int = 0, cgb: Boolean = false, sgb: Boolean = false): ByteArray =
      ByteArray(0x8000).also { bytes ->
        "CGBS-TEST".forEachIndexed { index, character ->
          bytes[0x134 + index] = (character.code + seed).toByte()
        }
        bytes[0x100] = 0x18
        bytes[0x101] = 0xfe.toByte()
        bytes[0x143] = if (cgb) 0x80.toByte() else 0
        bytes[0x146] = if (sgb) 0x03 else 0
        bytes[0x147] = 0
        bytes[0x148] = 0
        bytes[0x149] = 0
      }

  fun datelRom(): ByteArray =
      ByteArray(0x20000).also { bytes ->
        bytes[0x100] = 0
        bytes[0x101] = 0xc3.toByte()
        bytes[0x102] = 0x50
        bytes[0x103] = 0x01
        bytes[0x104] = 0x44
        "Action Replay V4".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x147] = 0
        bytes[0x148] = 0x02
        bytes[0x150] = 0x18
        bytes[0x151] = 0xfe.toByte()
      }

  fun configuration(
      bytes: ByteArray = rom(),
      hardware: GameboyType = GameboyType.DMG,
  ): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(bytes))
          .setGameboyType(hardware)
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          .setSupportBatterySave(false)

  fun session(
      configuration: Gameboy.GameboyConfiguration = configuration(),
      endpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
  ): Session = Session(configuration, EventBusImpl(), null, endpoint)

  fun rawFile(
      kind: StateRootKind,
      sections: List<RawSection>,
      envelopeFlags: Int = 0,
      decodedOverride: Long? = null,
      encodedOverride: Long? = null,
      encodedPayloadOverride: ByteArray? = null,
      formatVersion: Int = StateCodec.V1_FORMAT_VERSION,
  ): ByteArray {
    val payloadWriter = PortableWriter(8 * 1024 * 1024)
    sections.forEach { section ->
      payloadWriter.writeU16(section.id)
      payloadWriter.writeU16(section.version)
      payloadWriter.writeU16(section.flags)
      payloadWriter.writeU16(section.reserved)
      payloadWriter.writeLong(section.declaredLength ?: section.body.size.toLong())
      payloadWriter.writeBytes(section.body)
    }
    val decoded = payloadWriter.toByteArray()
    val encoded = encodedPayloadOverride ?: decoded
    val writer = PortableWriter(8 * 1024 * 1024)
    writer.writeBytes(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte()))
    writer.writeU16(formatVersion)
    writer.writeU16(StateCodec.HEADER_SIZE)
    writer.writeU32(envelopeFlags.toLong())
    writer.writeByte(kind.id)
    writer.writeByte(1)
    writer.writeU16(0)
    writer.writeU32(sections.size.toLong())
    writer.writeLong(encodedOverride ?: encoded.size.toLong())
    writer.writeLong(decodedOverride ?: decoded.size.toLong())
    writer.writeBytes(MessageDigest.getInstance("SHA-256").digest(encoded))
    writer.writeBytes(encoded)
    return writer.toByteArray()
  }

  fun sections(bytes: ByteArray): List<RawSection> {
    require(bytes[8].toInt() == 0 && bytes[9].toInt() == 0 && bytes[10].toInt() == 0 && bytes[11].toInt() == 0)
    val count = readInt(bytes, 16)
    var offset = StateCodec.HEADER_SIZE
    return List(count) {
      val id = readU16(bytes, offset)
      val version = readU16(bytes, offset + 2)
      val flags = readU16(bytes, offset + 4)
      val reserved = readU16(bytes, offset + 6)
      val length = readLong(bytes, offset + 8)
      require(length in 0..Int.MAX_VALUE.toLong())
      val body = bytes.copyOfRange(offset + StateCodec.SECTION_HEADER_SIZE, offset + StateCodec.SECTION_HEADER_SIZE + length.toInt())
      offset += StateCodec.SECTION_HEADER_SIZE + length.toInt()
      RawSection(id, version, flags, reserved, body)
    }
  }

  fun withChecksum(bytes: ByteArray): ByteArray =
      bytes.clone().also { result ->
        val checksum =
            MessageDigest.getInstance("SHA-256")
                .digest(result.copyOfRange(StateCodec.HEADER_SIZE, result.size))
        checksum.copyInto(result, 36)
      }

  fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
  }

  fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
  }

  fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0..7) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
  }

  fun readU16(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

  fun readInt(bytes: ByteArray, offset: Int): Int =
      (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 0xff) }

  fun readLong(bytes: ByteArray, offset: Int): Long =
      (0..7).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

  data class RawSection(
      val id: Int,
      val version: Int,
      val flags: Int,
      val reserved: Int,
      val body: ByteArray,
      val declaredLength: Long? = null,
  )
}
