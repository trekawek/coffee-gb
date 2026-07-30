package eu.rekawek.coffeegb.cli

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CliParserTest {
  @Test
  fun parsesBoundedRunWithHardwareInspectionAndArtifactOptions() {
    val command =
        command(
            "run",
            "--rom",
            "game.zip",
            "--rom-entry",
            "game.gb",
            "--ticks",
            "1200",
            "--profile",
            "cgb0",
            "--bootstrap",
            "fast-forward",
            "--sgb-border",
            "off",
            "--slot-rom",
            "slot.gb",
            "--slot-rom-entry",
            "slot-entry.gb",
            "--rtc-epoch-millis",
            "0",
            "--input-script",
            "input.cgbi",
            "--break",
            "pc:0x0150",
            "--memory",
            "rom:0x0100:32",
            "--memory",
            "work-ram:0xC000:64",
            "--expect-full-hash",
            "ab".repeat(32),
            "--screenshot",
            "frame.png",
            "--wav",
            "audio.wav",
            "--json-out",
            "report.json",
            "--bundle",
            "diagnostics.zip",
            "--bundle-include-memory",
            "--bundle-include-media",
            "--confirm-sensitive-bundle",
        )
    val run = assertIs<CliCommandSpec.Run>(command)
    assertEquals(Path.of("game.zip"), run.rom)
    assertEquals("game.gb", run.romEntry)
    assertEquals(ExecutionBound.Ticks(1200), run.bound)
    assertEquals(HardwareProfileSelection.CGB0, run.profile)
    assertEquals(BootstrapSelection.FAST_FORWARD, run.bootstrap)
    assertEquals(SgbBorderSelection.OFF, run.sgbBorder)
    assertEquals(CliBreakpoint.ProgramCounter(0x150), run.breakpoint)
    assertEquals(96, run.memory.sumOf { it.length })
    assertEquals("ab".repeat(32), run.expectedFullHash)
    assertTrue(run.outputs.includeMemoryInBundle)
    assertTrue(run.outputs.includeMediaInBundle)
    assertTrue(run.outputs.sensitiveBundleConfirmed)
  }

  @Test
  fun replayUsesRecordedIdentityByDefaultAndRequiresMaxTicks() {
    val replay =
        assertIs<CliCommandSpec.Replay>(
            command(
                "replay",
                "--rom",
                "game.gb",
                "--replay",
                "run.cgbr",
                "--max-ticks",
                "5000",
                "--bundle",
                "bundle.zip",
                "--bundle-include-replay",
                "--confirm-sensitive-bundle",
            ))
    assertEquals(HardwareProfileSelection.REPLAY, replay.profile)
    assertEquals(BootstrapSelection.REPLAY, replay.bootstrap)
    assertEquals(SgbBorderSelection.REPLAY, replay.sgbBorder)
    assertEquals(5000, replay.maxTicks)
    assertTrue(replay.outputs.includeReplayInBundle)
  }

  @Test
  fun rejectsMissingConflictingRepeatedUnknownAndUnboundedArguments() {
    fails("missing-command")
    fails("execution-bound", "run", "--rom", "game.gb")
    fails(
        "execution-bound",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "1",
        "--frames",
        "1",
    )
    fails(
        "repeated-option",
        "run",
        "--rom",
        "one.gb",
        "--rom",
        "two.gb",
        "--ticks",
        "1",
    )
    fails("unknown-option", "run", "--rom", "game.gb", "--ticks", "1", "--wat")
    fails(
        "bounded-number",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        (CliParser.MAX_EXECUTION_TICKS + 1).toString(),
    )
    fails(
        "bounded-number",
        "run",
        "--rom",
        "game.gb",
        "--frames",
        (CliParser.MAX_EXECUTION_FRAMES + 1).toString(),
    )
    fails(
        "missing-option",
        "replay",
        "--rom",
        "game.gb",
        "--replay",
        "run.cgbr",
    )
  }

  @Test
  fun sensitiveBundleContentUsesTwoIndependentGates() {
    fails(
        "bundle-gate",
        "replay",
        "--rom",
        "game.gb",
        "--replay",
        "run.cgbr",
        "--max-ticks",
        "1",
        "--bundle-include-replay",
        "--confirm-sensitive-bundle",
    )
    fails(
        "bundle-confirmation",
        "replay",
        "--rom",
        "game.gb",
        "--replay",
        "run.cgbr",
        "--max-ticks",
        "1",
        "--bundle",
        "bundle.zip",
        "--bundle-include-replay",
    )
    fails(
        "bundle-confirmation",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "1",
        "--bundle",
        "bundle.zip",
        "--confirm-sensitive-bundle",
    )
    fails(
        "bundle-memory",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "1",
        "--bundle",
        "bundle.zip",
        "--bundle-include-memory",
        "--confirm-sensitive-bundle",
    )
  }

  @Test
  fun validatesBreakMemoryIdentityAndPathConflicts() {
    fails("break-condition", "run", "--rom", "game.gb", "--ticks", "10", "--break", "pc:150")
    fails("break-condition", "run", "--rom", "game.gb", "--ticks", "10", "--break", "tick:0")
    fails("break-condition", "run", "--rom", "game.gb", "--frames", "10", "--break", "frame:0")
    fails(
        "break-bound",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "10",
        "--break",
        "tick:11",
    )
    fails(
        "memory-request",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "10",
        "--memory",
        "rom:0xFFF0:32",
    )
    fails(
        "hardware-profile",
        "replay",
        "--rom",
        "game.gb",
        "--replay",
        "run.cgbr",
        "--max-ticks",
        "1",
        "--profile",
        "auto",
    )
    fails(
        "input-output-conflict",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "1",
        "--json-out",
        "./game.gb",
    )
    fails(
        "output-path-conflict",
        "run",
        "--rom",
        "game.gb",
        "--ticks",
        "1",
        "--screenshot",
        "evidence",
        "--json-out",
        "evidence/report.json",
    )
  }

  @Test
  fun helpAndVersionAreExactGlobalInvocations() {
    assertIs<CliParseResult.Help>(CliParser.parse(arrayOf("--help")))
    assertIs<CliParseResult.Help>(CliParser.parse(arrayOf("run", "--help")))
    assertIs<CliParseResult.Version>(CliParser.parse(arrayOf("--version")))
    fails("unknown-command", "--version", "extra")
    assertNotNull(CliParser.helpText.lines().find { "--max-ticks" in it })
  }

  private fun command(vararg args: String): CliCommandSpec =
      assertIs<CliParseResult.Command>(CliParser.parse(args.toList())).command

  private fun fails(code: String, vararg args: String) {
    val failure =
        kotlin.test.assertFailsWith<CliUsageException> { CliParser.parse(args.toList()) }
    assertEquals(code, failure.diagnosticCode)
    assertTrue(failure.message.orEmpty().none { it == '\r' || it == '\n' })
  }
}
