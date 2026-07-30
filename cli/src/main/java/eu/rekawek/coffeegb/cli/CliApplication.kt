package eu.rekawek.coffeegb.cli

import java.io.PrintStream

/** Owns process-stream and exit-code behavior independently of the emulator implementation. */
class CliApplication(private val engine: CliExecutionEngine) {

  fun run(
      args: Array<String>,
      stdout: PrintStream = System.out,
      stderr: PrintStream = System.err,
      version: String = "development",
  ): Int {
    val parsed =
        try {
          CliParser.parse(args)
        } catch (failure: CliUsageException) {
          return publishLocalFailure(
              CliExitCode.INVALID_ARGUMENTS,
              failure.diagnosticCode,
              failure.message ?: "Invalid arguments",
              stdout,
              stderr,
          )
        }

    when (parsed) {
      CliParseResult.Help -> {
        stdout.println(CliParser.helpText)
        return CliExitCode.SUCCESS.processCode
      }
      CliParseResult.Version -> {
        stdout.println("coffee-gb-cli $version")
        return CliExitCode.SUCCESS.processCode
      }
      is CliParseResult.Command -> return execute(parsed.command, stdout, stderr)
    }
  }

  private fun execute(
      command: CliCommandSpec,
      stdout: PrintStream,
      stderr: PrintStream,
  ): Int {
    val inputScript =
        if (command is CliCommandSpec.Run && command.inputScript != null) {
          try {
            CgbiInputScriptCodec.read(command.inputScript)
          } catch (_: CgbiInputException) {
            return publishLocalFailure(
                CliExitCode.INVALID_ARGUMENTS,
                "input-script-invalid",
                "Input script is missing, unreadable, or invalid",
                stdout,
                stderr,
            )
          }
        } else {
          null
        }

    val outcome =
        try {
          engine.execute(CliExecutionRequest(command, inputScript))
        } catch (failure: Throwable) {
          if (failure is InterruptedException) Thread.currentThread().interrupt()
          return publishLocalFailure(
              CliExitCode.EMULATION_FAILURE,
              "emulation-failure",
              "Headless emulation failed",
              stdout,
              stderr,
          )
        }
    val report = normalizeJsonObjectLine(outcome.stdoutJson)
    if (report == null) {
      return publishLocalFailure(
          CliExitCode.EMULATION_FAILURE,
          "invalid-runtime-report",
          "Headless emulation produced an invalid report",
          stdout,
          stderr,
      )
    }
    stdout.println(report)
    outcome.stderrDiagnostic?.let { diagnostic(stderr, it.code, it.message) }
    return outcome.exitCode.processCode
  }

  private fun publishLocalFailure(
      exitCode: CliExitCode,
      code: String,
      message: String,
      stdout: PrintStream,
      stderr: PrintStream,
  ): Int {
    stdout.println(errorJson(exitCode, code))
    diagnostic(stderr, code, message)
    return exitCode.processCode
  }

  private fun diagnostic(stream: PrintStream, code: String, message: String) {
    stream.println("error[$code]: $message")
  }

  private fun errorJson(exitCode: CliExitCode, code: String): String =
      "{\"schema\":\"coffee-gb/headless-report\",\"version\":1," +
          "\"status\":\"error\",\"exitCode\":${exitCode.processCode}," +
          "\"error\":{\"code\":\"$code\"}}"

  private fun normalizeJsonObjectLine(value: String): String? {
    val line = if (value.endsWith('\n')) value.dropLast(1) else value
    return line.takeIf {
      it.length in 2..MAX_REPORT_CHARS &&
          it.first() == '{' &&
          it.last() == '}' &&
          it.none { character ->
            character == '\r' || character == '\n' || character.code < 0x20
          }
    }
  }

  private companion object {
    const val MAX_REPORT_CHARS = 4 * 1024 * 1024
  }
}
