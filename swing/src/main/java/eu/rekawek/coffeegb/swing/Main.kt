package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.Bios
import eu.rekawek.coffeegb.swing.SwingGui.Companion.run
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val SUCCESS = 0
private const val USAGE_ERROR = 2
private const val DEVELOPMENT_VERSION = "development"

/** Everything the CLI contributes to one desktop launch. CLI values remain process-local. */
data class CliLaunchRequest(
    val debug: Boolean,
    val initialRom: File?,
    val settingsOverrides: ApplicationSettingsOverrides,
)

private sealed class CliCommand {
  data class Launch(val request: CliLaunchRequest) : CliCommand()

  object Help : CliCommand()

  object Version : CliCommand()
}

fun main(args: Array<String>) {
  val exitCode =
      runCli(args, System.out, System.err, applicationVersion()) { request ->
        // Help/version never enter the desktop launch boundary, so they perform no package I/O.
        run(request.debug, request.initialRom, request.settingsOverrides)
      }
  if (exitCode != SUCCESS) {
    exitProcess(exitCode)
  }
}

/**
 * Runs only the deterministic command-line boundary. The launcher callback keeps help, version,
 * and error tests independent from Swing and the EDT.
 */
internal fun runCli(
    args: Array<String>,
    stdout: PrintStream,
    stderr: PrintStream,
    version: String,
    launcher: (CliLaunchRequest) -> Unit,
): Int {
  val command =
      try {
        parseCli(args)
      } catch (failure: IllegalArgumentException) {
        stderr.println("coffee-gb: ${failure.message}")
        stderr.println("Try 'java -jar coffee-gb.jar --help' for more information.")
        return USAGE_ERROR
      }

  return when (command) {
    is CliCommand.Launch -> {
      launcher(command.request)
      SUCCESS
    }
    CliCommand.Help -> {
      printUsage(stdout)
      SUCCESS
    }
    CliCommand.Version -> {
      stdout.println("Coffee GB $version")
      SUCCESS
    }
  }
}

private fun parseCli(args: Array<String>): CliCommand {
  var parseOptions = true
  var help = false
  var version = false
  var debug = false
  var forceDmg = false
  var forceCgb = false
  var useBootstrap = false
  var disableBatterySaves = false
  var profileId: String? = null
  var profileOccurrences = 0
  val positional = mutableListOf<String>()

  for (argument in args) {
    if (!parseOptions) {
      positional += argument
      continue
    }
    if (argument == "--") {
      parseOptions = false
      continue
    }
    if (argument == "-" || !argument.startsWith("-")) {
      positional += argument
      continue
    }
    if (argument.startsWith("--profile=")) {
      profileOccurrences++
      if (profileOccurrences > 1) {
        repeatedOption("--profile")
      }
      val value = argument.substringAfter("--profile=")
      if (value.isBlank() || value.contains('=')) {
        cliError("--profile requires one non-empty stable ID")
      }
      profileId = value
      continue
    }

    when (argument) {
      "-h", "--help" -> {
        if (help) repeatedOption("--help")
        help = true
      }
      "--version" -> {
        if (version) repeatedOption("--version")
        version = true
      }
      "--debug" -> {
        if (debug) repeatedOption("--debug")
        debug = true
      }
      "-d", "--force-dmg" -> {
        if (forceDmg) repeatedOption("--force-dmg")
        forceDmg = true
      }
      "-c", "--force-cgb" -> {
        if (forceCgb) repeatedOption("--force-cgb")
        forceCgb = true
      }
      "-b", "--use-bootstrap" -> {
        if (useBootstrap) repeatedOption("--use-bootstrap")
        useBootstrap = true
      }
      "-db", "--disable-battery-saves" -> {
        if (disableBatterySaves) repeatedOption("--disable-battery-saves")
        disableBatterySaves = true
      }
      "--profile" -> cliError("--profile requires =<id>")
      else -> cliError("Unknown option '$argument'")
    }
  }

  if (positional.size > 1) {
    cliError("Expected at most one ROM file, received ${positional.size}")
  }
  if (help && version) {
    cliError("--help and --version cannot be used together")
  }
  if (forceDmg && forceCgb) {
    cliError("--force-dmg and --force-cgb cannot be used together")
  }
  if (profileId != null && (forceDmg || forceCgb)) {
    cliError("--profile conflicts with --force-dmg/--force-cgb")
  }

  val profileOverride =
      when {
        profileId != null -> HardwareProfileRegistry.resolve(profileId)
        forceDmg -> HardwareProfileRegistry.DMG
        forceCgb -> HardwareProfileRegistry.CGB
        else -> null
      }
  validateBootstrapProfile(useBootstrap, profileOverride)

  if (help) {
    return CliCommand.Help
  }
  if (version) {
    return CliCommand.Version
  }

  return CliCommand.Launch(
      CliLaunchRequest(
          debug = debug,
          initialRom = positional.singleOrNull()?.let(::File),
          settingsOverrides =
              ApplicationSettingsOverrides(
                  hardwareProfile = profileOverride,
                  bootstrapMode = if (useBootstrap) BootstrapMode.NORMAL else null,
                  batterySavesEnabled = if (disableBatterySaves) false else null,
              ),
      ))
}

private fun validateBootstrapProfile(useBootstrap: Boolean, profile: HardwareProfile?) {
  if (useBootstrap && profile != null && !Bios.hasBundledBootRom(profile)) {
    cliError(
        "--use-bootstrap cannot be used with profile '${profile.id()}': " +
            "Coffee GB does not bundle its boot ROM")
  }
}

private fun cliError(message: String): Nothing = throw IllegalArgumentException(message)

private fun repeatedOption(option: String): Nothing =
    cliError("Option '$option' may be specified only once")

internal fun printUsage(stream: PrintStream) {
  stream.println("Usage:")
  stream.println("java -jar coffee-gb.jar [OPTIONS] [ROM_FILE]")
  stream.println()
  stream.println("Available options:")
  stream.println("  -d  --force-dmg                Select the DMG profile for all ROMs")
  stream.println("  -c  --force-cgb                Select the CGB profile for all ROMs")
  stream.println(
      "      --profile=<id>             Select profile: " +
          HardwareProfileRegistry.supportedIds().joinToString(", "))
  stream.println("  -b  --use-bootstrap            Run the bundled boot ROM normally")
  stream.println("  -db --disable-battery-saves    Disable battery save reads and writes")
  stream.println("      --debug                    Enable debug console")
  stream.println("  -h  --help                     Display this help and exit")
  stream.println("      --version                  Display version and exit")
  stream.println("      --                         Treat the remaining argument as the ROM file")
  stream.println()
  stream.println("ROM_FILE must be a local .gb, .gbc, .rom, or bounded .zip file.")
}

private object VersionMarker

private fun applicationVersion(): String =
    VersionMarker::class.java.`package`?.implementationVersion ?: DEVELOPMENT_VERSION
