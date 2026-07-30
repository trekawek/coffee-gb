package eu.rekawek.coffeegb.cli

import java.nio.file.Path

/** Stable process exit codes for the headless command line. */
enum class CliExitCode(val processCode: Int) {
  SUCCESS(0),
  INVALID_ARGUMENTS(2),
  INCOMPATIBLE_REPLAY_STATE(3),
  BREAKPOINT_REACHED(4),
  DETERMINISTIC_DIVERGENCE(5),
  EMULATION_FAILURE(6),
}

enum class HardwareProfileSelection(val cliName: String) {
  AUTO("auto"),
  REPLAY("replay"),
  DMG("dmg"),
  CGB("cgb"),
  CGB0("cgb0"),
  SGB("sgb"),
  SGB2("sgb2"),
  MGB("mgb"),
}

enum class BootstrapSelection(val cliName: String) {
  REPLAY("replay"),
  SKIP("skip"),
  NORMAL("normal"),
  FAST_FORWARD("fast-forward"),
}

enum class SgbBorderSelection(val cliName: String) {
  REPLAY("replay"),
  ON("on"),
  OFF("off"),
}

sealed interface ExecutionBound {
  data class Ticks(val count: Long) : ExecutionBound

  data class Frames(val count: Long) : ExecutionBound
}

sealed interface CliBreakpoint {
  data class ProgramCounter(val address: Int) : CliBreakpoint

  data class Opcode(val opcode: Int) : CliBreakpoint

  data class CbOpcode(val opcode: Int) : CliBreakpoint

  data class Tick(val tick: Long) : CliBreakpoint

  data class Frame(val frame: Long) : CliBreakpoint
}

enum class CliMemorySpace(val cliName: String) {
  SYSTEM_BUS("system-bus"),
  ROM("rom"),
  CARTRIDGE_RAM("cartridge-ram"),
  VIDEO_RAM("video-ram"),
  WORK_RAM("work-ram"),
  OAM("oam"),
  IO_REGISTERS("io-registers"),
  HIGH_RAM("high-ram"),
}

data class CliMemoryRequest(val space: CliMemorySpace, val start: Int, val length: Int)

data class CliArtifactOutputs(
    val screenshot: Path? = null,
    val wav: Path? = null,
    val json: Path? = null,
    val bundle: Path? = null,
    val includeReplayInBundle: Boolean = false,
    val includeMemoryInBundle: Boolean = false,
    val includeMediaInBundle: Boolean = false,
    val sensitiveBundleConfirmed: Boolean = false,
)

/** Parsed command contract. Implementations must treat all referenced input files as untrusted. */
sealed interface CliCommandSpec {
  val rom: Path
  val romEntry: String?
  val slotRom: Path?
  val slotRomEntry: String?
  val profile: HardwareProfileSelection
  val bootstrap: BootstrapSelection
  val sgbBorder: SgbBorderSelection
  val breakpoint: CliBreakpoint?
  val memory: List<CliMemoryRequest>
  val outputs: CliArtifactOutputs

  data class Run(
      override val rom: Path,
      override val romEntry: String?,
      override val slotRom: Path?,
      override val slotRomEntry: String?,
      override val profile: HardwareProfileSelection,
      override val bootstrap: BootstrapSelection,
      override val sgbBorder: SgbBorderSelection,
      val bound: ExecutionBound,
      val rtcEpochMillis: Long?,
      val inputScript: Path?,
      val expectedFullHash: String?,
      override val breakpoint: CliBreakpoint?,
      override val memory: List<CliMemoryRequest>,
      override val outputs: CliArtifactOutputs,
  ) : CliCommandSpec

  data class Replay(
      override val rom: Path,
      override val romEntry: String?,
      override val slotRom: Path?,
      override val slotRomEntry: String?,
      override val profile: HardwareProfileSelection,
      override val bootstrap: BootstrapSelection,
      override val sgbBorder: SgbBorderSelection,
      val replay: Path,
      val maxTicks: Long,
      override val breakpoint: CliBreakpoint?,
      override val memory: List<CliMemoryRequest>,
      override val outputs: CliArtifactOutputs,
  ) : CliCommandSpec
}

/** A parsed command plus any small, strictly decoded side input. ROMs and replays remain paths. */
data class CliExecutionRequest(
    val command: CliCommandSpec,
    val inputScript: CgbiInputScript? = null,
)

fun interface CliExecutionEngine {
  fun execute(request: CliExecutionRequest): CliExecutionOutcome
}

/** Sanitized, single-line diagnostic intended for stderr. It must never contain user paths. */
data class CliDiagnostic(val code: String, val message: String) {
  init {
    require(SAFE_CODE.matches(code)) { "Diagnostic code must be stable lowercase ASCII" }
    require(message.isNotBlank() && message.length <= MAX_MESSAGE_CHARS) {
      "Diagnostic message must be short and non-empty"
    }
    require(message.none { it == '\r' || it == '\n' || it.code < 0x20 }) {
      "Diagnostic message must be one printable line"
    }
  }

  private companion object {
    val SAFE_CODE = Regex("[a-z][a-z0-9-]{0,63}")
    const val MAX_MESSAGE_CHARS = 240
  }
}

/**
 * Runtime result returned through the narrow CLI seam.
 *
 * [stdoutJson] is an already encoded JSON object, with either no terminator or one trailing LF.
 * [CliApplication] verifies and publishes exactly one line, so arbitrary runtime exceptions can
 * never leak onto the process streams.
 */
data class CliExecutionOutcome(
    val exitCode: CliExitCode,
    val stdoutJson: String,
    val stderrDiagnostic: CliDiagnostic? = null,
)
