package eu.rekawek.coffeegb.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path

sealed interface CliParseResult {
  data class Command(val command: CliCommandSpec) : CliParseResult

  data object Help : CliParseResult

  data object Version : CliParseResult
}

class CliUsageException(val diagnosticCode: String, message: String) : Exception(message)

/** Strict, side-effect-free parser for the public headless command contract. */
object CliParser {
  const val MAX_EXECUTION_TICKS = 1_000_000_000L
  const val MAX_EXECUTION_FRAMES = 14_000L
  const val MAX_MEMORY_REQUESTS = 16
  const val MAX_MEMORY_BYTES = 4_096

  val helpText: String =
      """
      Coffee GB headless CLI

      Usage:
        coffee-gb-cli --help
        coffee-gb-cli --version
        coffee-gb-cli run --rom FILE (--ticks N | --frames N) [OPTIONS]
        coffee-gb-cli replay --rom FILE --replay FILE --max-ticks N [OPTIONS]

      Hardware and ROM options:
        --profile auto|dmg|cgb|cgb0|sgb|sgb2|mgb
        --bootstrap skip|normal|fast-forward
        --sgb-border on|off
        --rom-entry ENTRY
        --slot-rom FILE [--slot-rom-entry ENTRY]
        --rtc-epoch-millis N                 run only

      Bounded execution and evidence:
        --input-script FILE                  strict CGBI v1, run only
        --break pc:0xNNNN|opcode:0xNN|cb:0xNN|tick:N|frame:N
        --memory SPACE:0xSTART:LENGTH         repeatable, 16 blocks / 4096 bytes
        --expect-full-hash SHA256             run only
        --screenshot FILE                     PNG
        --wav FILE
        --json-out FILE
        --bundle FILE

      Sensitive diagnostic-bundle additions require both an include flag and confirmation:
        --bundle-include-replay
        --bundle-include-memory
        --bundle-include-media
        --confirm-sensitive-bundle

      Replay identity defaults to --profile replay --bootstrap replay --sgb-border replay.
      Canonical memory spaces: system-bus, rom, cartridge-ram, video-ram, work-ram,
      oam, io-registers, high-ram.
      """
          .trimIndent()

  private val RUN_VALUE_OPTIONS =
      setOf(
          "rom",
          "ticks",
          "frames",
          "profile",
          "bootstrap",
          "sgb-border",
          "rom-entry",
          "slot-rom",
          "slot-rom-entry",
          "rtc-epoch-millis",
          "input-script",
          "break",
          "memory",
          "expect-full-hash",
          "screenshot",
          "wav",
          "json-out",
          "bundle",
      )
  private val REPLAY_VALUE_OPTIONS =
      setOf(
          "rom",
          "replay",
          "max-ticks",
          "profile",
          "bootstrap",
          "sgb-border",
          "rom-entry",
          "slot-rom",
          "slot-rom-entry",
          "break",
          "memory",
          "screenshot",
          "wav",
          "json-out",
          "bundle",
      )
  private val FLAG_OPTIONS =
      setOf(
          "bundle-include-replay",
          "bundle-include-memory",
          "bundle-include-media",
          "confirm-sensitive-bundle",
      )
  private val REPEATABLE_OPTIONS = setOf("memory")
  private val DECIMAL = Regex("0|[1-9][0-9]*")
  private val FULL_HASH = Regex("[0-9a-fA-F]{64}")
  private val ENTRY = Regex("[^\\p{Cntrl}]{1,1024}")

  @Throws(CliUsageException::class)
  fun parse(args: Array<String>): CliParseResult = parse(args.toList())

  @Throws(CliUsageException::class)
  fun parse(args: List<String>): CliParseResult {
    if (args.isEmpty()) fail("missing-command", "A command is required; use --help")
    if (args == listOf("--help") || args == listOf("-h")) return CliParseResult.Help
    if (args == listOf("--version")) return CliParseResult.Version
    if (args.size == 2 && args[1] in setOf("--help", "-h") && args[0] in setOf("run", "replay")) {
      return CliParseResult.Help
    }

    val command = args[0]
    val raw =
        when (command) {
          "run" -> scan(args.drop(1), RUN_VALUE_OPTIONS)
          "replay" -> scan(args.drop(1), REPLAY_VALUE_OPTIONS)
          else -> fail("unknown-command", "Unknown command; use --help")
        }
    return CliParseResult.Command(
        when (command) {
          "run" -> parseRun(raw)
          else -> parseReplay(raw)
        })
  }

  private fun parseRun(raw: RawOptions): CliCommandSpec.Run {
    val rom = path(raw.required("rom"), "--rom")
    val ticks = raw.single("ticks")
    val frames = raw.single("frames")
    if ((ticks == null) == (frames == null)) {
      fail("execution-bound", "Run requires exactly one of --ticks or --frames")
    }
    val bound =
        if (ticks != null) {
          ExecutionBound.Ticks(positiveDecimal(ticks, MAX_EXECUTION_TICKS, "--ticks"))
        } else {
          ExecutionBound.Frames(
              positiveDecimal(frames!!, MAX_EXECUTION_FRAMES, "--frames"))
        }
    val profile = profile(raw.single("profile") ?: "auto", allowReplay = false, allowAuto = true)
    val bootstrap = bootstrap(raw.single("bootstrap") ?: "skip", allowReplay = false)
    val sgbBorder = sgbBorder(raw.single("sgb-border") ?: "off", allowReplay = false)
    val slotRom = raw.single("slot-rom")?.let { path(it, "--slot-rom") }
    val slotRomEntry = raw.single("slot-rom-entry")?.let(::entry)
    if (slotRomEntry != null && slotRom == null) {
      fail("slot-rom-entry", "--slot-rom-entry requires --slot-rom")
    }
    val rtcEpoch =
        raw.single("rtc-epoch-millis")?.let {
          nonNegativeDecimal(it, Long.MAX_VALUE, "--rtc-epoch-millis")
        }
    val inputScript = raw.single("input-script")?.let { path(it, "--input-script") }
    val breakpoint = raw.single("break")?.let(::breakpoint)
    validateBreakpointBound(breakpoint, bound)
    val memory = memory(raw.all("memory"))
    val expectedHash =
        raw.single("expect-full-hash")?.let {
          if (!FULL_HASH.matches(it)) {
            fail("full-hash", "--expect-full-hash requires exactly 64 hexadecimal digits")
          }
          it.lowercase()
        }
    val outputs = outputs(raw, memory, replayCommand = false)
    validatePaths(
        inputs = listOfNotNull(rom, slotRom, inputScript),
        outputs = listOfNotNull(outputs.screenshot, outputs.wav, outputs.json, outputs.bundle),
    )
    return CliCommandSpec.Run(
        rom = rom,
        romEntry = raw.single("rom-entry")?.let(::entry),
        slotRom = slotRom,
        slotRomEntry = slotRomEntry,
        profile = profile,
        bootstrap = bootstrap,
        sgbBorder = sgbBorder,
        bound = bound,
        rtcEpochMillis = rtcEpoch,
        inputScript = inputScript,
        expectedFullHash = expectedHash,
        breakpoint = breakpoint,
        memory = memory,
        outputs = outputs,
    )
  }

  private fun parseReplay(raw: RawOptions): CliCommandSpec.Replay {
    val rom = path(raw.required("rom"), "--rom")
    val replay = path(raw.required("replay"), "--replay")
    val maxTicks =
        positiveDecimal(raw.required("max-ticks"), MAX_EXECUTION_TICKS, "--max-ticks")
    val profile = profile(raw.single("profile") ?: "replay", allowReplay = true, allowAuto = false)
    val bootstrap = bootstrap(raw.single("bootstrap") ?: "replay", allowReplay = true)
    val sgbBorder = sgbBorder(raw.single("sgb-border") ?: "replay", allowReplay = true)
    val slotRom = raw.single("slot-rom")?.let { path(it, "--slot-rom") }
    val slotRomEntry = raw.single("slot-rom-entry")?.let(::entry)
    if (slotRomEntry != null && slotRom == null) {
      fail("slot-rom-entry", "--slot-rom-entry requires --slot-rom")
    }
    val breakpoint = raw.single("break")?.let(::breakpoint)
    if (breakpoint is CliBreakpoint.Tick && breakpoint.tick > maxTicks) {
      fail("break-bound", "Tick break condition exceeds --max-ticks")
    }
    val memory = memory(raw.all("memory"))
    val outputs = outputs(raw, memory, replayCommand = true)
    validatePaths(
        inputs = listOfNotNull(rom, slotRom, replay),
        outputs = listOfNotNull(outputs.screenshot, outputs.wav, outputs.json, outputs.bundle),
    )
    return CliCommandSpec.Replay(
        rom = rom,
        romEntry = raw.single("rom-entry")?.let(::entry),
        slotRom = slotRom,
        slotRomEntry = slotRomEntry,
        profile = profile,
        bootstrap = bootstrap,
        sgbBorder = sgbBorder,
        replay = replay,
        maxTicks = maxTicks,
        breakpoint = breakpoint,
        memory = memory,
        outputs = outputs,
    )
  }

  private fun scan(args: List<String>, valueOptions: Set<String>): RawOptions {
    val values = linkedMapOf<String, MutableList<String>>()
    val flags = linkedSetOf<String>()
    var index = 0
    while (index < args.size) {
      val token = args[index]
      if (!token.startsWith("--") || token.length == 2 || '=' in token) {
        fail("unexpected-argument", "Unexpected argument; use --help")
      }
      val name = token.substring(2)
      when {
        name in FLAG_OPTIONS -> {
          if (!flags.add(name)) fail("repeated-option", "Option --$name may be specified once")
          index++
        }
        name in valueOptions -> {
          if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
            fail("missing-option-value", "Option --$name requires a value")
          }
          val existing = values.getOrPut(name) { mutableListOf() }
          if (existing.isNotEmpty() && name !in REPEATABLE_OPTIONS) {
            fail("repeated-option", "Option --$name may be specified once")
          }
          existing += args[index + 1]
          index += 2
        }
        else -> fail("unknown-option", "Unknown option; use --help")
      }
    }
    return RawOptions(values.mapValues { it.value.toList() }, flags.toSet())
  }

  private fun profile(
      value: String,
      allowReplay: Boolean,
      allowAuto: Boolean,
  ): HardwareProfileSelection {
    val selection = HardwareProfileSelection.entries.find { it.cliName == value }
        ?: fail("hardware-profile", "Hardware profile is not recognized")
    if (selection == HardwareProfileSelection.REPLAY && !allowReplay) {
      fail("hardware-profile", "The replay hardware profile is only valid for replay")
    }
    if (selection == HardwareProfileSelection.AUTO && !allowAuto) {
      fail("hardware-profile", "The auto hardware profile is only valid for run")
    }
    return selection
  }

  private fun bootstrap(value: String, allowReplay: Boolean): BootstrapSelection {
    val selection = BootstrapSelection.entries.find { it.cliName == value }
        ?: fail("bootstrap-mode", "Bootstrap mode is not recognized")
    if (selection == BootstrapSelection.REPLAY && !allowReplay) {
      fail("bootstrap-mode", "Replay bootstrap mode is only valid for replay")
    }
    return selection
  }

  private fun sgbBorder(value: String, allowReplay: Boolean): SgbBorderSelection {
    val selection = SgbBorderSelection.entries.find { it.cliName == value }
        ?: fail("sgb-border", "SGB border mode is not recognized")
    if (selection == SgbBorderSelection.REPLAY && !allowReplay) {
      fail("sgb-border", "Replay SGB border mode is only valid for replay")
    }
    return selection
  }

  private fun breakpoint(value: String): CliBreakpoint {
    Regex("pc:0x([0-9a-fA-F]{4})").matchEntire(value)?.let {
      return CliBreakpoint.ProgramCounter(it.groupValues[1].toInt(16))
    }
    Regex("opcode:0x([0-9a-fA-F]{2})").matchEntire(value)?.let {
      return CliBreakpoint.Opcode(it.groupValues[1].toInt(16))
    }
    Regex("cb:0x([0-9a-fA-F]{2})").matchEntire(value)?.let {
      return CliBreakpoint.CbOpcode(it.groupValues[1].toInt(16))
    }
    Regex("tick:([1-9][0-9]*)").matchEntire(value)?.let {
      val tick = it.groupValues[1].toLongOrNull()
      if (tick != null && tick <= CgbiInputScriptCodec.MAX_TIMELINE_TICK) {
        return CliBreakpoint.Tick(tick)
      }
    }
    Regex("frame:([1-9][0-9]*)").matchEntire(value)?.let {
      val frame = it.groupValues[1].toLongOrNull()
      if (frame != null && frame <= CgbiInputScriptCodec.MAX_TIMELINE_TICK) {
        return CliBreakpoint.Frame(frame)
      }
    }
    fail("break-condition", "Break condition is invalid")
  }

  private fun validateBreakpointBound(breakpoint: CliBreakpoint?, bound: ExecutionBound) {
    if (breakpoint is CliBreakpoint.Tick && bound is ExecutionBound.Ticks &&
        breakpoint.tick > bound.count) {
      fail("break-bound", "Tick break condition exceeds --ticks")
    }
    if (breakpoint is CliBreakpoint.Frame && bound is ExecutionBound.Frames &&
        breakpoint.frame > bound.count) {
      fail("break-bound", "Frame break condition exceeds --frames")
    }
  }

  private fun memory(values: List<String>): List<CliMemoryRequest> {
    if (values.size > MAX_MEMORY_REQUESTS) {
      fail("memory-count", "At most $MAX_MEMORY_REQUESTS memory blocks may be requested")
    }
    var total = 0
    return values.map { value ->
      val fields = value.split(':')
      if (fields.size != 3) fail("memory-request", "Memory request is invalid")
      val space = CliMemorySpace.entries.find { it.cliName == fields[0] }
          ?: fail("memory-request", "Memory address space is not recognized")
      val start =
          Regex("0x([0-9a-fA-F]{1,4})")
              .matchEntire(fields[1])
              ?.groupValues
              ?.get(1)
              ?.toIntOrNull(16)
          ?: fail("memory-request", "Memory start address is invalid")
      val length =
          fields[2].takeIf(DECIMAL::matches)?.toIntOrNull()
              ?: fail("memory-request", "Memory length is invalid")
      if (length !in 1..MAX_MEMORY_BYTES || start.toLong() + length > 0x1_0000L) {
        fail("memory-request", "Memory range is outside the bounded 16-bit view")
      }
      total += length
      if (total > MAX_MEMORY_BYTES) {
        fail("memory-bytes", "Memory requests exceed the $MAX_MEMORY_BYTES-byte aggregate limit")
      }
      CliMemoryRequest(space, start, length)
    }
  }

  private fun outputs(
      raw: RawOptions,
      memory: List<CliMemoryRequest>,
      replayCommand: Boolean,
  ): CliArtifactOutputs {
    val bundle = raw.single("bundle")?.let { path(it, "--bundle") }
    val includeReplay = raw.flag("bundle-include-replay")
    val includeMemory = raw.flag("bundle-include-memory")
    val includeMedia = raw.flag("bundle-include-media")
    val confirmed = raw.flag("confirm-sensitive-bundle")
    val includesSensitive = includeReplay || includeMemory || includeMedia
    if (includesSensitive && bundle == null) {
      fail("bundle-gate", "Sensitive bundle include flags require --bundle")
    }
    if (includesSensitive && !confirmed) {
      fail("bundle-confirmation", "Sensitive bundle additions require explicit confirmation")
    }
    if (confirmed && !includesSensitive) {
      fail("bundle-confirmation", "Bundle confirmation requires a sensitive include flag")
    }
    if (includeReplay && !replayCommand) {
      fail("bundle-replay", "Replay inclusion is only valid for the replay command")
    }
    if (includeMemory && memory.isEmpty()) {
      fail("bundle-memory", "Memory inclusion requires at least one --memory request")
    }
    return CliArtifactOutputs(
        screenshot = raw.single("screenshot")?.let { path(it, "--screenshot") },
        wav = raw.single("wav")?.let { path(it, "--wav") },
        json = raw.single("json-out")?.let { path(it, "--json-out") },
        bundle = bundle,
        includeReplayInBundle = includeReplay,
        includeMemoryInBundle = includeMemory,
        includeMediaInBundle = includeMedia,
        sensitiveBundleConfirmed = confirmed,
    )
  }

  private fun validatePaths(inputs: List<Path>, outputs: List<Path>) {
    val normalizedOutputs = outputs.map { it.toAbsolutePath().normalize() }
    if (normalizedOutputs.toSet().size != normalizedOutputs.size) {
      fail("output-path-conflict", "Output paths must be distinct")
    }
    normalizedOutputs.forEachIndexed { index, output ->
      normalizedOutputs.drop(index + 1).forEach { other ->
        if (output.startsWith(other) || other.startsWith(output)) {
          fail("output-path-conflict", "Output paths must not contain one another")
        }
      }
    }
    val normalizedInputs = inputs.map { it.toAbsolutePath().normalize() }.toSet()
    if (normalizedOutputs.any { it in normalizedInputs }) {
      fail("input-output-conflict", "An output path must not overwrite an input")
    }
  }

  private fun path(value: String, option: String): Path =
      try {
        if (value.isBlank()) fail("invalid-path", "$option requires a valid platform path")
        Path.of(value)
      } catch (_: InvalidPathException) {
        fail("invalid-path", "$option requires a valid platform path")
      }

  private fun entry(value: String): String {
    if (!ENTRY.matches(value) || value.isBlank()) {
      fail("archive-entry", "Archive entry name is invalid")
    }
    return value
  }

  private fun positiveDecimal(value: String, maximum: Long, option: String): Long {
    val parsed = value.takeIf(DECIMAL::matches)?.toLongOrNull()
    if (parsed == null || parsed !in 1..maximum) {
      fail("bounded-number", "$option requires a positive bounded decimal value")
    }
    return parsed
  }

  private fun nonNegativeDecimal(value: String, maximum: Long, option: String): Long {
    val parsed = value.takeIf(DECIMAL::matches)?.toLongOrNull()
    if (parsed == null || parsed !in 0..maximum) {
      fail("bounded-number", "$option requires a non-negative bounded decimal value")
    }
    return parsed
  }

  private fun fail(code: String, message: String): Nothing = throw CliUsageException(code, message)

  private data class RawOptions(
      private val values: Map<String, List<String>>,
      private val flags: Set<String>,
  ) {
    fun single(name: String): String? = values[name]?.singleOrNull()

    fun required(name: String): String =
        single(name) ?: fail("missing-option", "Required option --$name is missing")

    fun all(name: String): List<String> = values[name].orEmpty()

    fun flag(name: String): Boolean = name in flags
  }
}
