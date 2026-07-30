package eu.rekawek.coffeegb.cli

import eu.rekawek.coffeegb.cli.codec.CanonicalJson
import eu.rekawek.coffeegb.cli.codec.CanonicalJsonWriter
import eu.rekawek.coffeegb.controller.headless.HeadlessBatchResult
import eu.rekawek.coffeegb.controller.headless.HeadlessTerminationReason
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackStatus
import eu.rekawek.coffeegb.controller.replay.ReplayStateHashes
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import java.security.MessageDigest

/** Path-free values needed to encode one stable execution report. */
internal data class HeadlessReportContext(
    val command: String,
    val configuration: Gameboy.GameboyConfiguration,
    val requestedUnit: String,
    val requestedCount: Long,
    val inputRecords: Int,
    val expectedFullHash: String? = null,
)

internal data class HeadlessEncodedMedia(
    val png: ByteArray? = null,
    val wav: ByteArray? = null,
)

/** Canonical JSON boundary shared by stdout, standalone JSON, and diagnostic bundles. */
internal object HeadlessReportEncoder {

  fun encode(
      result: HeadlessBatchResult,
      context: HeadlessReportContext,
      exitCode: CliExitCode,
      media: HeadlessEncodedMedia,
      includeMemory: Boolean,
      redactRomTitle: Boolean = false,
  ): ByteArray {
    val snapshot = result.inspection.snapshot()
    val registers = snapshot.registers()
    val interrupts = snapshot.interrupts()
    val timer = snapshot.timer()
    val ppu = snapshot.ppu()
    val apu = snapshot.apu()
    val mapper = snapshot.mapper()
    val execution = snapshot.execution()
    val divergence = divergence(result, context)
    val title = if (redactRomTitle) REDACTED else result.rom.title

    return CanonicalJsonWriter.encode(
        CanonicalJson.obj(
            "schema" to CanonicalJson.string(REPORT_SCHEMA),
            "version" to CanonicalJson.number(REPORT_VERSION),
            "status" to CanonicalJson.string(status(result.reason, exitCode)),
            "exitCode" to CanonicalJson.number(exitCode.processCode),
            "command" to CanonicalJson.string(context.command),
            "termination" to CanonicalJson.string(enumName(result.reason)),
            "configuration" to
                CanonicalJson.obj(
                    "profile" to
                        CanonicalJson.string(context.configuration.hardwareProfile.id()),
                    "bootstrap" to
                        CanonicalJson.string(enumName(context.configuration.bootstrapMode)),
                    "sgbBorder" to
                        CanonicalJson.bool(context.configuration.isDisplaySgbBorder),
                    "batteryPersistence" to CanonicalJson.bool(false),
                    "serial" to CanonicalJson.string("none"),
                    "infrared" to CanonicalJson.string("none"),
                    "rtcEpochMillis" to CanonicalJson.number(result.rtcEpochMillis),
                    "requestedUnit" to CanonicalJson.string(context.requestedUnit),
                    "requestedCount" to CanonicalJson.number(context.requestedCount),
                    "inputRecords" to CanonicalJson.number(context.inputRecords),
                ),
            "position" to
                CanonicalJson.obj(
                    "completedTicks" to CanonicalJson.number(result.position.completedTicks),
                    "frame" to CanonicalJson.number(result.position.frame),
                    "ticksIntoFrame" to CanonicalJson.number(result.position.ticksIntoFrame),
                ),
            "rom" to
                CanonicalJson.obj(
                    "sha256" to CanonicalJson.string(hex(result.rom.sha256)),
                    "bytes" to CanonicalJson.number(result.rom.sizeBytes),
                    "title" to CanonicalJson.string(title),
                    "cgbFlag" to CanonicalJson.number(result.rom.cgbFlag),
                    "sgbFlag" to CanonicalJson.number(result.rom.sgbFlag),
                    "cartridgeType" to CanonicalJson.number(result.rom.cartridgeType),
                    "romSizeCode" to CanonicalJson.number(result.rom.romSizeCode),
                    "ramSizeCode" to CanonicalJson.number(result.rom.ramSizeCode),
                    "nintendoLogoValid" to CanonicalJson.bool(result.rom.nintendoLogoValid),
                    "headerChecksumValid" to CanonicalJson.bool(result.rom.headerChecksumValid),
                ),
            "snapshot" to
                CanonicalJson.obj(
                    "masterTick" to CanonicalJson.number(snapshot.masterTick()),
                    "frame" to CanonicalJson.number(snapshot.frame()),
                    "framePosition" to CanonicalJson.number(snapshot.framePosition()),
                    "paused" to CanonicalJson.bool(snapshot.paused()),
                    "registers" to
                        CanonicalJson.obj(
                            "a" to CanonicalJson.number(registers.a()),
                            "f" to CanonicalJson.number(registers.f()),
                            "b" to CanonicalJson.number(registers.b()),
                            "c" to CanonicalJson.number(registers.c()),
                            "d" to CanonicalJson.number(registers.d()),
                            "e" to CanonicalJson.number(registers.e()),
                            "h" to CanonicalJson.number(registers.h()),
                            "l" to CanonicalJson.number(registers.l()),
                            "sp" to CanonicalJson.number(registers.sp()),
                            "pc" to CanonicalJson.number(registers.pc()),
                        ),
                    "interrupts" to
                        CanonicalJson.obj(
                            "ime" to CanonicalJson.bool(interrupts.ime()),
                            "imeEnablePending" to
                                CanonicalJson.bool(interrupts.imeEnablePending()),
                            "requestFlags" to CanonicalJson.number(interrupts.requestFlags()),
                            "enableFlags" to CanonicalJson.number(interrupts.enableFlags()),
                            "pendingFlags" to CanonicalJson.number(interrupts.pendingFlags()),
                        ),
                    "timer" to
                        CanonicalJson.obj(
                            "dividerCounter" to CanonicalJson.number(timer.dividerCounter()),
                            "tima" to CanonicalJson.number(timer.tima()),
                            "tma" to CanonicalJson.number(timer.tma()),
                            "tac" to CanonicalJson.number(timer.tac()),
                            "overflowPending" to CanonicalJson.bool(timer.overflowPending()),
                            "overflowDelayTicks" to
                                CanonicalJson.number(timer.overflowDelayTicks()),
                        ),
                    "ppu" to
                        CanonicalJson.obj(
                            "lcdEnabled" to CanonicalJson.bool(ppu.lcdEnabled()),
                            "mode" to CanonicalJson.string(enumName(ppu.mode())),
                            "line" to CanonicalJson.number(ppu.line()),
                            "dot" to CanonicalJson.number(ppu.dot()),
                            "lcdc" to CanonicalJson.number(ppu.lcdc()),
                            "stat" to CanonicalJson.number(ppu.stat()),
                            "scy" to CanonicalJson.number(ppu.scy()),
                            "scx" to CanonicalJson.number(ppu.scx()),
                            "lyc" to CanonicalJson.number(ppu.lyc()),
                            "wy" to CanonicalJson.number(ppu.wy()),
                            "wx" to CanonicalJson.number(ppu.wx()),
                        ),
                    "apu" to
                        CanonicalJson.obj(
                            "enabled" to CanonicalJson.bool(apu.enabled()),
                            "frameSequencerStep" to
                                CanonicalJson.number(apu.frameSequencerStep()),
                            "channel1Enabled" to CanonicalJson.bool(apu.channel1Enabled()),
                            "channel2Enabled" to CanonicalJson.bool(apu.channel2Enabled()),
                            "channel3Enabled" to CanonicalJson.bool(apu.channel3Enabled()),
                            "channel4Enabled" to CanonicalJson.bool(apu.channel4Enabled()),
                            "nr50" to CanonicalJson.number(apu.nr50()),
                            "nr51" to CanonicalJson.number(apu.nr51()),
                            "nr52" to CanonicalJson.number(apu.nr52()),
                        ),
                    "mapper" to
                        CanonicalJson.obj(
                            "id" to CanonicalJson.string(mapper.mapperId()),
                            "romBank" to CanonicalJson.number(mapper.romBank()),
                            "ramBank" to CanonicalJson.number(mapper.ramBank()),
                            "ramEnabled" to CanonicalJson.string(enumName(mapper.ramEnabled())),
                            "rtcSelected" to CanonicalJson.string(enumName(mapper.rtcSelected())),
                            "rumbleEnabled" to
                                CanonicalJson.string(enumName(mapper.rumbleEnabled())),
                        ),
                    "execution" to
                        CanonicalJson.obj(
                            "cpuState" to CanonicalJson.string(enumName(execution.cpuState())),
                            "opcode" to CanonicalJson.number(execution.opcode()),
                            "extendedOpcode" to
                                CanonicalJson.number(execution.extendedOpcode()),
                            "machineCycle" to CanonicalJson.number(execution.machineCycle()),
                            "doubleSpeed" to CanonicalJson.bool(execution.doubleSpeed()),
                            "haltBug" to CanonicalJson.bool(execution.haltBug()),
                            "retiredInstructions" to
                                CanonicalJson.number(execution.retiredInstructions()),
                        ),
                ),
            "memory" to
                if (includeMemory) memoryBlocks(result.inspection.memoryBlocks())
                else CanonicalJson.array(),
            "hashes" to hashes(result.hashes),
            "breakpoint" to breakpoint(result),
            "divergence" to divergence,
            "media" to media(result, media),
        ))
  }

  fun encodeMemory(result: HeadlessBatchResult): ByteArray =
      CanonicalJsonWriter.encode(
          CanonicalJson.obj(
              "schema" to CanonicalJson.string(MEMORY_SCHEMA),
              "version" to CanonicalJson.number(1),
              "masterTick" to CanonicalJson.number(result.inspection.snapshot().masterTick()),
              "blocks" to memoryBlocks(result.inspection.memoryBlocks()),
          ))

  private fun memoryBlocks(blocks: List<DebugMemoryBlock>): CanonicalJson.Value =
      CanonicalJson.array(
          blocks.map { block ->
            CanonicalJson.obj(
                "space" to CanonicalJson.string(enumName(block.addressSpace())),
                "start" to CanonicalJson.number(block.startAddress()),
                "length" to CanonicalJson.number(block.length()),
                "bytes" to CanonicalJson.string(blockHex(block)),
            )
          })

  private fun hashes(value: ReplayStateHashes): CanonicalJson.Value =
      CanonicalJson.obj(
          "full" to CanonicalJson.string(hex(value.full)),
          "cpu" to CanonicalJson.string(hex(value.cpu)),
          "memory" to CanonicalJson.string(hex(value.memory)),
          "ppu" to CanonicalJson.string(hex(value.ppu)),
          "apu" to CanonicalJson.string(hex(value.apu)),
          "mapper" to CanonicalJson.string(hex(value.mapper)),
          "serial" to CanonicalJson.string(hex(value.serial)),
          "input" to CanonicalJson.string(hex(value.input)),
      )

  private fun breakpoint(result: HeadlessBatchResult): CanonicalJson.Value {
    val hit = result.breakpointHit ?: return CanonicalJson.nil()
    return CanonicalJson.obj(
        "id" to CanonicalJson.number(hit.breakpointId().value()),
        "masterTick" to CanonicalJson.number(hit.matchMasterTick()),
    )
  }

  private fun divergence(
      result: HeadlessBatchResult,
      context: HeadlessReportContext,
  ): CanonicalJson.Value {
    val replay = result.replayStatus
    if (replay is ReplayPlaybackStatus.Diverged) {
      return CanonicalJson.obj(
          "source" to CanonicalJson.string("replay-checkpoint"),
          "tick" to CanonicalJson.number(replay.divergence.tick),
          "frame" to CanonicalJson.number(replay.divergence.frame),
          "expected" to hashes(replay.divergence.expected),
          "actual" to hashes(replay.divergence.actual),
          "mismatchedSubsystems" to
              CanonicalJson.array(
                  replay.divergence.mismatchedSubsystems
                      .map { enumName(it) }
                      .sorted()
                      .map { CanonicalJson.string(it) }),
      )
    }
    val expected = context.expectedFullHash
    val actual = hex(result.hashes.full)
    if (expected != null &&
        result.reason in
            setOf(HeadlessTerminationReason.TICK_LIMIT, HeadlessTerminationReason.FRAME_LIMIT) &&
        expected != actual) {
      return CanonicalJson.obj(
          "source" to CanonicalJson.string("expected-full-hash"),
          "expectedFull" to CanonicalJson.string(expected),
          "actualFull" to CanonicalJson.string(actual),
          "mismatchedSubsystems" to
              CanonicalJson.array(CanonicalJson.string("full")),
      )
    }
    return CanonicalJson.nil()
  }

  private fun media(
      result: HeadlessBatchResult,
      encoded: HeadlessEncodedMedia,
  ): CanonicalJson.Value {
    val frame = result.latestFrame
    val pcm = result.pcm
    return CanonicalJson.obj(
        "screenshot" to
            if (frame != null && encoded.png != null) {
              CanonicalJson.obj(
                  "width" to CanonicalJson.number(frame.image.width),
                  "height" to CanonicalJson.number(frame.image.height),
                  "completedTicks" to CanonicalJson.number(frame.completedTicks),
                  "sha256" to CanonicalJson.string(sha256(encoded.png)),
              )
            } else {
              CanonicalJson.nil()
            },
        "audio" to
            if (pcm != null && encoded.wav != null) {
              CanonicalJson.obj(
                  "sampleRate" to CanonicalJson.number(pcm.sampleRate),
                  "channels" to CanonicalJson.number(pcm.channels),
                  "sampleFrames" to CanonicalJson.number(pcm.sampleFrames),
                  "completedTicks" to CanonicalJson.number(pcm.completedTicks),
                  "sha256" to CanonicalJson.string(sha256(encoded.wav)),
              )
            } else {
              CanonicalJson.nil()
            },
    )
  }

  private fun status(reason: HeadlessTerminationReason, exitCode: CliExitCode): String =
      when (exitCode) {
        CliExitCode.SUCCESS -> "completed"
        CliExitCode.BREAKPOINT_REACHED -> "breakpoint"
        CliExitCode.DETERMINISTIC_DIVERGENCE -> "diverged"
        else -> if (reason == HeadlessTerminationReason.REPLAY_BUDGET_EXHAUSTED) "error" else "failed"
      }

  private fun blockHex(block: DebugMemoryBlock): String =
      buildString(block.length() * 2) {
        repeat(block.length()) { index ->
          append(HEX[block.unsignedByteAt(index) ushr 4])
          append(HEX[block.unsignedByteAt(index) and 0x0f])
        }
      }

  private fun sha256(bytes: ByteArray): String =
      hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  internal fun hex(bytes: ByteArray): String =
      buildString(bytes.size * 2) {
        bytes.forEach { byte ->
          val value = byte.toInt() and 0xff
          append(HEX[value ushr 4])
          append(HEX[value and 0x0f])
        }
      }

  private fun enumName(value: Enum<*>): String =
      value.name.lowercase().replace('_', '-')

  private const val REPORT_SCHEMA = "coffee-gb/headless-report"
  private const val REPORT_VERSION = 1
  private const val MEMORY_SCHEMA = "coffee-gb/headless-memory"
  private const val REDACTED = "<redacted>"
  private const val HEX = "0123456789abcdef"
}
