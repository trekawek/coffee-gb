package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.hardware.HardwareProfile as CoreHardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.charset.StandardCharsets

internal object StateIdentitySectionCodec {
  const val ID = 1
  const val VERSION_1 = 1
  const val VERSION_2 = 2
  const val PROFILE_CGB0 = 1
  const val PROFILE_MEALYBUG_DMG_BLOB = 1 shl 1
  const val PROFILE_CODEBREAKER_RUMBLE = 1 shl 2
  const val PROFILE_SGB_BORDER = 1 shl 3
  const val PROFILE_FLAGS =
      PROFILE_CGB0 or
          PROFILE_MEALYBUG_DMG_BLOB or
          PROFILE_CODEBREAKER_RUMBLE or
          PROFILE_SGB_BORDER

  fun encode(entries: List<StateIdentityEntry>, version: Int): ByteArray {
    try {
      if (version != VERSION_1 && version != VERSION_2) {
        throw StateEncodeException("Unsupported identity section version $version")
      }
      val writer = PortableWriter(StateLimits.PORTABLE_MAX_SECTION_BYTES)
      requireEntries(entries)
      writer.writeU32(entries.size.toLong())
      entries.forEach { entry ->
        writer.writeU32(entry.player.toLong())
        writer.writeBoolean(entry.identity != null)
        entry.identity?.let { identity ->
          validateProfile(identity.profile)
          writer.writeBytes(identity.primaryRom.copyBytes())
          writer.writeBoolean(identity.slotRom != null)
          identity.slotRom?.let { writer.writeBytes(it.copyBytes()) }
          writer.writeU16(identity.profile.version)
          writer.writeByte(hardwareId(identity.profile.hardware))
          writer.writeByte(identity.profile.bootstrapMode.id)
          var flags = 0
          if (identity.profile.cgb0Revision) flags = flags or PROFILE_CGB0
          if (identity.profile.mealybugDmgBlob) flags = flags or PROFILE_MEALYBUG_DMG_BLOB
          if (identity.profile.codeBreakerRumble) flags = flags or PROFILE_CODEBREAKER_RUMBLE
          if (identity.profile.displaySgbBorder) flags = flags or PROFILE_SGB_BORDER
          writer.writeU32(flags.toLong())
          if (version == VERSION_1) {
            if (identity.profile.explicitProfileId != null) {
              throw StateEncodeException(
                  "StateFile v1 cannot encode explicit profile " +
                      identity.profile.canonicalProfileId)
            }
          } else {
            val profileId = identity.profile.canonicalProfileId
            val encodedId = profileId.toByteArray(StandardCharsets.US_ASCII)
            if (encodedId.size > StateLimits.PORTABLE_MAX_PROFILE_ID_BYTES ||
                encodedId.any { (it.toInt() and 0xff) !in 0x21..0x7e }) {
              throw StateEncodeException("Portable profile ID '$profileId' is not bounded ASCII")
            }
            writer.writeU16(encodedId.size)
            writer.writeBytes(encodedId)
          }
        }
      }
      return writer.toByteArray()
    } catch (failure: StateDecodeException) {
      throw StateEncodeException(failure.message ?: "Portable identity is invalid", failure)
    }
  }

  fun decode(reader: PortableReader, version: Int): List<StateIdentityEntry> {
    if (version != VERSION_1 && version != VERSION_2) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_SECTION_VERSION,
          "Unsupported identity section version $version",
      )
    }
    val count =
        PortableBounds.requireCount(
            reader.readU32(),
            StateLimits.PORTABLE_MAX_LINKED_PLAYERS.toLong(),
            "State identity count",
        )
    if (count != 1 && count != StateLimits.PORTABLE_MAX_LINKED_PLAYERS) {
      malformed("State identity count must be one or four")
    }
    val entries =
        ArrayList<StateIdentityEntry>(count).also { result ->
          repeat(count) {
            val player =
                PortableBounds.requireCount(
                    reader.readU32(),
                    (StateLimits.PORTABLE_MAX_LINKED_PLAYERS - 1).toLong(),
                    "State identity player",
                )
            if (player != result.size) malformed("State identity players are not canonical")
            val identity =
                if (!reader.readBoolean()) {
                  null
                } else {
                  val primary = RomIdentity(reader.readBytes(RomIdentity.SHA256_BYTES, RomIdentity.SHA256_BYTES))
                  val slot =
                      if (reader.readBoolean()) {
                        RomIdentity(
                            reader.readBytes(
                                RomIdentity.SHA256_BYTES,
                                RomIdentity.SHA256_BYTES,
                            ))
                      } else {
                        null
                      }
                  val profileVersion = reader.readU16()
                  if (profileVersion != HardwareProfile.VERSION) {
                    throw StateDecodeException(
                        StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
                        "Unsupported hardware profile version $profileVersion",
                    )
                  }
                  val hardware = hardware(reader.readByte())
                  val bootstrap = StateBootstrapMode.fromId(reader.readByte())
                  val flags = reader.readU32()
                  if (flags and PROFILE_FLAGS.inv().toLong() != 0L) {
                    throw StateDecodeException(
                        StateDecodeReason.UNSUPPORTED_FLAGS,
                        "Hardware profile has undefined flags 0x${flags.toString(16)}",
                    )
                  }
                  val profile =
                      HardwareProfile(
                          profileVersion,
                          hardware,
                          bootstrap,
                          flags and PROFILE_CGB0.toLong() != 0L,
                          flags and PROFILE_MEALYBUG_DMG_BLOB.toLong() != 0L,
                          flags and PROFILE_CODEBREAKER_RUMBLE.toLong() != 0L,
                          flags and PROFILE_SGB_BORDER.toLong() != 0L,
                          if (version == VERSION_2) readProfileId(reader) else null,
                      )
                  validateProfile(profile)
                  MachineIdentity(
                      primary,
                      slot,
                      profile,
                  )
                }
            result += StateIdentityEntry(player, identity)
          }
        }
    reader.requireExhausted()
    return entries
  }

  private fun requireEntries(entries: List<StateIdentityEntry>) {
    if (entries.size != 1 && entries.size != StateLimits.PORTABLE_MAX_LINKED_PLAYERS) {
      throw StateEncodeException("State identity count must be one or four")
    }
    if (entries.indices.any { entries[it].player != it }) {
      throw StateEncodeException("State identity players are not canonical")
    }
  }

  private fun validateProfile(profile: HardwareProfile) {
    if (profile.cgb0Revision && profile.hardware != MachineHardwareState.CGB) {
      malformed("CGB0 profile flag is set on ${profile.hardware}")
    }
    if (profile.mealybugDmgBlob && profile.hardware == MachineHardwareState.CGB) {
      malformed("Mealybug DMG-blob profile flag is set on native CGB")
    }
    if (profile.displaySgbBorder && profile.hardware != MachineHardwareState.SGB) {
      malformed("SGB-border profile flag is set on ${profile.hardware}")
    }
    val registered =
        try {
          HardwareProfileRegistry.resolve(profile.canonicalProfileId)
        } catch (failure: IllegalArgumentException) {
          throw StateDecodeException(
              StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
              "Unknown portable hardware profile '${profile.canonicalProfileId}'",
              failure,
          )
        }
    val expectedHardware =
        when (registered.family()) {
          CoreHardwareProfile.Family.DMG -> MachineHardwareState.DMG
          CoreHardwareProfile.Family.CGB -> MachineHardwareState.CGB
          CoreHardwareProfile.Family.SGB -> MachineHardwareState.SGB
        }
    if (expectedHardware != profile.hardware ||
        (registered == HardwareProfileRegistry.CGB0) != profile.cgb0Revision) {
      throw StateDecodeException(
          StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
          "Portable profile ${profile.canonicalProfileId} conflicts with ${profile.hardware}",
      )
    }
  }

  private fun readProfileId(reader: PortableReader): String {
    val length =
        PortableBounds.requireCount(
            reader.readU16().toLong(),
            StateLimits.PORTABLE_MAX_PROFILE_ID_BYTES.toLong(),
            "Portable profile ID bytes",
        )
    if (length == 0) malformed("Portable profile ID is empty")
    val bytes = reader.readBytes(length, StateLimits.PORTABLE_MAX_PROFILE_ID_BYTES)
    if (bytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) {
      malformed("Portable profile ID is not canonical ASCII")
    }
    val id = String(bytes, StandardCharsets.US_ASCII)
    if (!id.matches(Regex("[a-z][a-z0-9-]*"))) {
      malformed("Portable profile ID '$id' is not canonical lowercase ASCII")
    }
    return id
  }

  internal fun hardwareId(hardware: MachineHardwareState): Int =
      when (hardware) {
        MachineHardwareState.DMG -> 1
        MachineHardwareState.CGB -> 2
        MachineHardwareState.SGB -> 3
      }

  internal fun hardware(id: Int): MachineHardwareState =
      when (id) {
        1 -> MachineHardwareState.DMG
        2 -> MachineHardwareState.CGB
        3 -> MachineHardwareState.SGB
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_ENUM,
                "Unknown hardware type $id",
            )
      }

  private fun malformed(message: String): Nothing = PortableBounds.malformed(message)
}

internal object StatePayloadSectionCodec {
  const val ID = 2
  const val VERSION = 1
  private const val RTC_NONE = 0
  private const val RTC_MBC3 = 1
  private const val SERIAL_RUNTIME_NONE = 0
  private const val SERIAL_RUNTIME_BARCODE = 1
  private const val GAMEBOY_ROOT =
      "eu.rekawek.coffeegb.core.Gameboy\$GameboyState"

  fun encode(root: StateFileRoot): ByteArray {
    try {
      val writer = PortableWriter(StateLimits.PORTABLE_MAX_SECTION_BYTES)
      val values = StateValueCodec.Encoder(writer)
      when (root) {
        is MachineStateRoot -> writeMachine(writer, values, root.machine)
        is SessionStateRoot -> writeSession(writer, values, root.session)
        is LinkedSessionStateRoot -> writeLinked(writer, values, root.linked)
      }
      return writer.toByteArray()
    } catch (failure: StateDecodeException) {
      throw StateEncodeException(failure.message ?: "Portable state payload is invalid", failure)
    }
  }

  fun decode(kind: StateRootKind, reader: PortableReader): StateFileRoot {
    val values = StateValueCodec.Decoder(reader)
    val root =
        when (kind) {
          StateRootKind.MACHINE -> MachineStateRoot(readMachine(reader, values))
          StateRootKind.SESSION -> SessionStateRoot(readSession(reader, values))
          StateRootKind.LINKED_SESSION -> LinkedSessionStateRoot(readLinked(reader, values))
        }
    reader.requireExhausted()
    return root
  }

  private fun writeMachine(
      writer: PortableWriter,
      values: StateValueCodec.Encoder,
      machine: MachineState,
  ) {
    requireGameboyRoot(machine.root)
    if ((machine.hardware == MachineHardwareState.CGB) != (machine.dmgFifoRuntime == null)) {
      malformed("DMG FIFO runtime presence does not match machine hardware")
    }
    writer.writeByte(StateIdentitySectionCodec.hardwareId(machine.hardware))
    writeRtc(writer, machine.rtcRuntime.primary)
    writeRtc(writer, machine.rtcRuntime.slot)
    writer.writeBoolean(machine.dmgFifoRuntime != null)
    machine.dmgFifoRuntime?.let {
      writeDmgRuntime(writer, it.timing)
      writeDmgRuntime(writer, it.output)
    }
    values.write(machine.root)
  }

  private fun readMachine(
      reader: PortableReader,
      values: StateValueCodec.Decoder,
  ): MachineState {
    val hardware = StateIdentitySectionCodec.hardware(reader.readByte())
    val rtc = CartridgeRtcRuntimeState(readRtc(reader), readRtc(reader))
    val dmg =
        if (reader.readBoolean()) {
          DmgFifoRuntimeState(readDmgRuntime(reader), readDmgRuntime(reader))
        } else {
          null
        }
    if ((hardware == MachineHardwareState.CGB) != (dmg == null)) {
      malformed("DMG FIFO runtime presence does not match machine hardware")
    }
    val root = values.read()
    if (root !is RecordState) malformed("Machine state root is not a record")
    requireGameboyRoot(root)
    return MachineState(root, rtc, hardware, dmg)
  }

  private fun writeSession(
      writer: PortableWriter,
      values: StateValueCodec.Encoder,
      session: SessionState,
  ) {
    writeMachine(writer, values, session.machine)
    if ((session.serialPeripheral == SerialPeripheralState.NONE) !=
        (session.serialState === NullState)) {
      malformed("Serial state presence does not match peripheral identity")
    }
    validateSerialRoot(session.serialPeripheral, session.serialState)
    if ((session.serialPeripheral == SerialPeripheralState.BARCODE_BOY) !=
        (session.serialRuntime is BarcodeBoyRuntimeState)) {
      malformed("Serial runtime does not match peripheral identity")
    }
    writer.writeByte(serialPeripheralId(session.serialPeripheral))
    values.write(session.serialState)
    when (val runtime = session.serialRuntime) {
      NoSerialRuntimeState -> writer.writeByte(SERIAL_RUNTIME_NONE)
      is BarcodeBoyRuntimeState -> {
        writer.writeByte(SERIAL_RUNTIME_BARCODE)
        writer.writeBoolean(runtime.transferArmed)
        val pendingSize = runtime.pendingSize
        writer.writeBoolean(pendingSize != null)
        pendingSize?.let { size ->
          if (size != 30) malformed("Barcode runtime frame must contain 30 bytes")
          PortableBounds.arrayBytes(size.toLong(), Int.SIZE_BYTES.toLong())
          val pending = checkNotNull(runtime.copyPending())
          writer.writeU32(pending.size.toLong())
          pending.forEach {
            value -> requireByteValue(value)
            writer.writeInt(value)
          }
        }
      }
    }
    if (session.heldButtons.size > HeldButtonState.entries.size) {
      throw StateEncodeException("Too many held buttons")
    }
    writer.writeU32(session.heldButtons.size.toLong())
    var previous = -1
    session.heldButtons.forEach {
      val id = heldButtonId(it)
      if (id <= previous) throw StateEncodeException("Held buttons are not canonical")
      previous = id
      writer.writeByte(id)
    }
  }

  private fun readSession(
      reader: PortableReader,
      values: StateValueCodec.Decoder,
  ): SessionState {
    val machine = readMachine(reader, values)
    val peripheral = serialPeripheral(reader.readByte())
    val serialState = values.read()
    if ((peripheral == SerialPeripheralState.NONE) != (serialState === NullState)) {
      malformed("Serial state presence does not match peripheral identity")
    }
    validateSerialRoot(peripheral, serialState)
    val runtime =
        when (val tag = reader.readByte()) {
          SERIAL_RUNTIME_NONE -> NoSerialRuntimeState
          SERIAL_RUNTIME_BARCODE -> {
            val armed = reader.readBoolean()
            val pending =
                if (reader.readBoolean()) {
                  val count =
                      PortableBounds.requireCount(
                          reader.readU32(),
                          StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS.toLong(),
                          "Barcode runtime length",
                      )
                  PortableBounds.arrayBytes(count.toLong(), Int.SIZE_BYTES.toLong())
                  if (count != 30) malformed("Barcode runtime frame must contain 30 bytes")
                  IntArray(count) { reader.readInt().also(::requireByteValue) }
                } else {
                  null
                }
            BarcodeBoyRuntimeState(armed, pending)
          }
          else ->
              throw StateDecodeException(
                  StateDecodeReason.MALFORMED_TAG,
                  "Unknown serial runtime tag $tag",
              )
        }
    if ((peripheral == SerialPeripheralState.BARCODE_BOY) !=
        (runtime is BarcodeBoyRuntimeState)) {
      malformed("Serial runtime does not match peripheral identity")
    }
    val heldCount =
        PortableBounds.requireCount(
            reader.readU32(),
            HeldButtonState.entries.size.toLong(),
            "Held-button count",
        )
    var previous = 0
    val held =
        ArrayList<HeldButtonState>(heldCount).also { result ->
          repeat(heldCount) {
            val id = reader.readByte()
            if (id <= previous) malformed("Held buttons are not strictly ordered")
            previous = id
            result += heldButton(id)
          }
        }
    return SessionState(machine, peripheral, serialState, runtime, held)
  }

  private fun writeLinked(
      writer: PortableWriter,
      values: StateValueCodec.Encoder,
      linked: LinkedSessionState,
  ) {
    if (linked.players.size != StateLimits.PORTABLE_MAX_LINKED_PLAYERS ||
        linked.players.indices.any { linked.players[it].player != it }) {
      throw StateEncodeException("Linked players are not canonical")
    }
    if (linked.frame !in 0..Int.MAX_VALUE.toLong()) malformed("Linked frame is outside v1 range")
    val activeSlots = if (linked.topology == LinkedTopologyState.NORMAL) 2 else 4
    if (linked.localPlayer !in 0 until activeSlots) malformed("Linked local player is outside topology")
    if (linked.players.drop(activeSlots).any { it.session != null }) {
      malformed("Linked state populates slots outside its topology")
    }
    val active = linked.players.mapNotNull(LinkedPlayerState::session)
    val peripheral =
        if (linked.topology == LinkedTopologyState.NORMAL) SerialPeripheralState.PEER_TO_PEER
        else SerialPeripheralState.FOUR_PLAYER_ADAPTER
    if (active.any { it.serialPeripheral != peripheral }) {
      malformed("Linked serial peripheral does not match topology")
    }
    if (linked.topology == LinkedTopologyState.FOUR_PLAYER_ADAPTER &&
        active.map(SessionState::serialState).distinct().size > 1) {
      malformed("Linked four-player adapter state is incoherent")
    }
    writer.writeLong(linked.frame)
    writer.writeByte(linked.localPlayer)
    writer.writeByte(topologyId(linked.topology))
    writer.writeU32(linked.players.size.toLong())
    linked.players.forEach { player ->
      writer.writeByte(player.player)
      writer.writeBoolean(player.session != null)
      player.session?.let { writeSession(writer, values, it) }
    }
  }

  private fun readLinked(
      reader: PortableReader,
      values: StateValueCodec.Decoder,
  ): LinkedSessionState {
    val frame = reader.readLong()
    if (frame !in 0..Int.MAX_VALUE.toLong()) malformed("Linked frame is outside v1 range")
    val localPlayer = reader.readByte()
    val topology = topology(reader.readByte())
    val playerCount =
        PortableBounds.requireCount(
            reader.readU32(),
            StateLimits.PORTABLE_MAX_LINKED_PLAYERS.toLong(),
            "Linked player count",
        )
    if (playerCount != StateLimits.PORTABLE_MAX_LINKED_PLAYERS) {
      malformed("Linked state must contain four canonical slots")
    }
    val players =
        ArrayList<LinkedPlayerState>(playerCount).also { result ->
          repeat(playerCount) { expected ->
            val player = reader.readByte()
            if (player != expected) malformed("Linked player indices are not canonical")
            result +=
                LinkedPlayerState(
                    player,
                    if (reader.readBoolean()) readSession(reader, values) else null,
                )
          }
        }
    val activeSlots = if (topology == LinkedTopologyState.NORMAL) 2 else 4
    if (localPlayer !in 0 until activeSlots) malformed("Linked local player is outside topology")
    if (players.drop(activeSlots).any { it.session != null }) {
      malformed("Linked state populates slots outside its topology")
    }
    val active = players.mapNotNull(LinkedPlayerState::session)
    val peripheral =
        if (topology == LinkedTopologyState.NORMAL) SerialPeripheralState.PEER_TO_PEER
        else SerialPeripheralState.FOUR_PLAYER_ADAPTER
    if (active.any { it.serialPeripheral != peripheral }) {
      malformed("Linked serial peripheral does not match topology")
    }
    if (topology == LinkedTopologyState.FOUR_PLAYER_ADAPTER &&
        active.map(SessionState::serialState).distinct().size > 1) {
      malformed("Linked four-player adapter state is incoherent")
    }
    return LinkedSessionState(frame, localPlayer, topology, players)
  }

  private fun writeRtc(writer: PortableWriter, rtc: Mbc3RtcRuntimeState?) {
    if (rtc == null) {
      writer.writeByte(RTC_NONE)
    } else {
      writer.writeByte(RTC_MBC3)
      writer.writeBoolean(rtc.emulationPaused)
      writer.writeLong(rtc.pauseStartedMillis)
    }
  }

  private fun readRtc(reader: PortableReader): Mbc3RtcRuntimeState? =
      when (val tag = reader.readByte()) {
        RTC_NONE -> null
        RTC_MBC3 -> Mbc3RtcRuntimeState(reader.readBoolean(), reader.readLong())
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_TAG,
                "Unknown RTC runtime tag $tag",
            )
      }

  private fun writeDmgRuntime(writer: PortableWriter, runtime: DmgPixelFifoRuntimeState) {
    validateDmgRuntime(runtime)
    writer.writeInt(runtime.linePixels)
    writer.writeInt(runtime.outCount)
    writer.writeInt(runtime.firstEntry)
    writer.writeInt(runtime.firstBgp)
    writer.writeInt(runtime.firstObp0)
    writer.writeInt(runtime.firstObp1)
  }

  private fun readDmgRuntime(reader: PortableReader): DmgPixelFifoRuntimeState =
      DmgPixelFifoRuntimeState(
              reader.readInt(),
              reader.readInt(),
              reader.readInt(),
              reader.readInt(),
              reader.readInt(),
              reader.readInt(),
          )
          .also(::validateDmgRuntime)

  private fun validateDmgRuntime(runtime: DmgPixelFifoRuntimeState) {
    if (runtime.linePixels !in 0..160) malformed("DMG FIFO line position is invalid")
    if (runtime.outCount < 0) malformed("DMG FIFO output count is negative")
    if (runtime.firstEntry !in -1..0x3f) malformed("DMG FIFO first entry is invalid")
    if (runtime.firstEntry >= 0 && runtime.outCount != 1) {
      malformed("DMG FIFO pending first entry requires output count one")
    }
    requireByteValue(runtime.firstBgp)
    requireByteValue(runtime.firstObp0)
    requireByteValue(runtime.firstObp1)
  }

  private fun requireGameboyRoot(root: RecordState) {
    val expected = StateTypeRegistry.recordClassNames.indexOf(GAMEBOY_ROOT) + 1
    if (root.typeId != expected) {
      malformed("Machine root type ${root.typeId} is not the Gameboy root $expected")
    }
  }

  private fun validateSerialRoot(
      peripheral: SerialPeripheralState,
      state: StateValue,
  ) {
    if (peripheral == SerialPeripheralState.NONE) return
    val record = state as? RecordState ?: malformed("Serial state root is not a record")
    val expectedNames =
        when (peripheral) {
          SerialPeripheralState.NONE -> return
          SerialPeripheralState.BYTE_RECEIVER ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint\$ByteReceivingSerialEndpointState")
          SerialPeripheralState.PEER_TO_PEER ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint\$Peer2PeerSerialEndpointState")
          SerialPeripheralState.PRINTER ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint\$PrinterState")
          SerialPeripheralState.GPS_RECEIVER ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint\$GpsReceiverState")
          SerialPeripheralState.BARCODE_BOY ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyState")
          SerialPeripheralState.FOUR_PLAYER_ADAPTER ->
              listOf("eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$AdapterState")
          SerialPeripheralState.MOBILE_ADAPTER_GB ->
              listOf(
                  "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointState",
                  "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointNetworkState",
              )
        }
    val expected =
        expectedNames.map { StateTypeRegistry.recordClassNames.indexOf(it) + 1 }.filter { it > 0 }
    if (record.typeId !in expected) {
      malformed(
          "Serial state type ${record.typeId} does not match $peripheral (${expected.joinToString()})")
    }
  }

  private fun requireByteValue(value: Int) {
    if (value !in 0..0xff) malformed("Portable byte-valued integer $value is invalid")
  }

  internal fun serialPeripheralId(value: SerialPeripheralState): Int =
      when (value) {
        SerialPeripheralState.NONE -> 1
        SerialPeripheralState.BYTE_RECEIVER -> 2
        SerialPeripheralState.PEER_TO_PEER -> 3
        SerialPeripheralState.PRINTER -> 4
        SerialPeripheralState.GPS_RECEIVER -> 5
        SerialPeripheralState.BARCODE_BOY -> 6
        SerialPeripheralState.FOUR_PLAYER_ADAPTER -> 7
        SerialPeripheralState.MOBILE_ADAPTER_GB -> 8
      }

  internal fun serialPeripheral(id: Int): SerialPeripheralState =
      when (id) {
        1 -> SerialPeripheralState.NONE
        2 -> SerialPeripheralState.BYTE_RECEIVER
        3 -> SerialPeripheralState.PEER_TO_PEER
        4 -> SerialPeripheralState.PRINTER
        5 -> SerialPeripheralState.GPS_RECEIVER
        6 -> SerialPeripheralState.BARCODE_BOY
        7 -> SerialPeripheralState.FOUR_PLAYER_ADAPTER
        8 -> SerialPeripheralState.MOBILE_ADAPTER_GB
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_ENUM,
                "Unknown serial peripheral $id",
            )
      }

  private fun heldButtonId(value: HeldButtonState): Int =
      when (value) {
        HeldButtonState.RIGHT -> 1
        HeldButtonState.LEFT -> 2
        HeldButtonState.UP -> 3
        HeldButtonState.DOWN -> 4
        HeldButtonState.A -> 5
        HeldButtonState.B -> 6
        HeldButtonState.SELECT -> 7
        HeldButtonState.START -> 8
      }

  private fun heldButton(id: Int): HeldButtonState =
      when (id) {
        1 -> HeldButtonState.RIGHT
        2 -> HeldButtonState.LEFT
        3 -> HeldButtonState.UP
        4 -> HeldButtonState.DOWN
        5 -> HeldButtonState.A
        6 -> HeldButtonState.B
        7 -> HeldButtonState.SELECT
        8 -> HeldButtonState.START
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_ENUM,
                "Unknown held button $id",
            )
      }

  private fun topologyId(value: LinkedTopologyState): Int =
      when (value) {
        LinkedTopologyState.NORMAL -> 1
        LinkedTopologyState.FOUR_PLAYER_ADAPTER -> 2
      }

  private fun topology(id: Int): LinkedTopologyState =
      when (id) {
        1 -> LinkedTopologyState.NORMAL
        2 -> LinkedTopologyState.FOUR_PLAYER_ADAPTER
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_ENUM,
                "Unknown linked topology $id",
            )
      }

  private fun malformed(message: String): Nothing = PortableBounds.malformed(message)
}

internal object StateDiagnosticSectionCodec {
  const val ID = 3
  const val VERSION = 1

  fun encode(metadata: StateDiagnosticMetadata): ByteArray {
    val writer = PortableWriter(StateLimits.PORTABLE_MAX_SECTION_BYTES)
    writer.writeString(metadata.coreVersion)
    writer.writeString(metadata.buildId)
    return writer.toByteArray()
  }

  fun decode(reader: PortableReader): StateDiagnosticMetadata =
      StateDiagnosticMetadata(reader.readString(), reader.readString()).also {
        reader.requireExhausted()
      }
}
