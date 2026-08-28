package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.Bios
import eu.rekawek.coffeegb.swing.SwingGui.Companion.run
import eu.rekawek.coffeegb.swing.packaging.NativeRuntimeBootstrap
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val SUCCESS = 0
private const val USAGE_ERROR = 2
private const val DEVELOPMENT_VERSION = "development"

/** Everything the CLI contributes to one desktop launch. CLI values remain process-local. */
internal data class CliLaunchRequest(
    val debug: Boolean,
    val initialRom: File?,
    /** Validated direct-host endpoint text to join after [initialRom] has opened. */
    val joinNetplayHost: String?,
    /** A netplay child receives its authoritative state from the host, never from a local resume. */
    val suppressInitialAutosaveResume: Boolean,
    val settingsOverrides: ApplicationSettingsOverrides,
)

private sealed class CliCommand {
  data class Launch(val request: CliLaunchRequest) : CliCommand()

  object Help : CliCommand()

  object Version : CliCommand()

  object PackageSmoke : CliCommand()
}

fun main(args: Array<String>) {
  validatePackagedLocalNetplayRelaunchChildIfRequested(args)
  val exitCode =
      runCli(
          args,
          System.out,
          System.err,
          applicationVersion(),
          packageSmoke = {
            launchPackagedLocalNetplayRelaunchChildIfRequested()
            // Exercise the package-native handoff before constructing the synthetic archive and
            // machine. This in-process leg is headless and never reads an external/user ROM.
            val selection = NativeRuntimeBootstrap.bootstrapFromSystem()
            val nativeTarget =
                NativeRuntimeBootstrap.requirePackageSmokeSelection(
                    selection,
                    System.getProperty(NativeRuntimeBootstrap.TARGET_PROPERTY),
                )
            PackageRuntimeSmoke.run(nativeTarget)
          },
      ) { request ->
        // SwingGui installs the macOS open-file handler before resolving package natives so an
        // early Finder event cannot be lost during bootstrap.
        run(
            request.debug,
            request.initialRom,
            request.settingsOverrides,
            request.joinNetplayHost,
            request.suppressInitialAutosaveResume,
        )
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
    packageSmoke: () -> PackageRuntimeSmoke.Result = { PackageRuntimeSmoke.run() },
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
    CliCommand.PackageSmoke -> {
      val result = packageSmoke()
      stdout.println(
          "Coffee GB package smoke OK: " +
              "ticks=${result.ticks}, video=${result.videoFrames}, " +
              "audio=${result.audioBuffers}, state=${result.stateBytes}, " +
              "native-target=${result.nativeTarget}",
      )
      SUCCESS
    }
  }
}

private fun parseCli(args: Array<String>): CliCommand {
  var parseOptions = true
  var help = false
  var version = false
  var packageSmoke = false
  var debug = false
  var forceDmg = false
  var forceCgb = false
  var useBootstrap = false
  var disableBatterySaves = false
  var profileId: String? = null
  var profileOccurrences = 0
  var joinNetplayEndpoint: NetplayV8Endpoint? = null
  val positional = mutableListOf<String>()

  var index = 0
  while (index < args.size) {
    val argument = args[index]
    if (!parseOptions) {
      positional += argument
      index++
      continue
    }
    if (argument == "--") {
      parseOptions = false
      index++
      continue
    }
    if (argument == "-" || !argument.startsWith("-")) {
      positional += argument
      index++
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
      index++
      continue
    }
    if (argument.startsWith("--join-netplay=")) {
      if (joinNetplayEndpoint != null) repeatedOption("--join-netplay")
      joinNetplayEndpoint = parseJoinNetplayEndpoint(argument.substringAfter("--join-netplay="))
      index++
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
      "--package-smoke" -> {
        if (packageSmoke) repeatedOption("--package-smoke")
        packageSmoke = true
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
      "--join-netplay" -> {
        if (joinNetplayEndpoint != null) repeatedOption("--join-netplay")
        val value = args.getOrNull(++index)
            ?: cliError("--join-netplay requires a hostname or IPv4 address")
        if (value.startsWith("-")) {
          cliError("--join-netplay requires a hostname or IPv4 address")
        }
        joinNetplayEndpoint = parseJoinNetplayEndpoint(value)
      }
      else -> cliError("Unknown option '$argument'")
    }
    index++
  }

  if (positional.size > 1) {
    cliError("Expected at most one ROM file, received ${positional.size}")
  }
  if (listOf(help, version, packageSmoke).count { it } > 1) {
    cliError("--help, --version, and --package-smoke cannot be used together")
  }
  if (forceDmg && forceCgb) {
    cliError("--force-dmg and --force-cgb cannot be used together")
  }
  if (profileId != null && (forceDmg || forceCgb)) {
    cliError("--profile conflicts with --force-dmg/--force-cgb")
  }
  if (joinNetplayEndpoint != null && positional.isEmpty()) {
    cliError("--join-netplay requires a ROM file")
  }
  if (packageSmoke &&
      (debug ||
          forceDmg ||
          forceCgb ||
          useBootstrap ||
          disableBatterySaves ||
          profileId != null ||
          joinNetplayEndpoint != null ||
          positional.isNotEmpty())) {
    cliError("--package-smoke cannot be combined with launch options or a ROM file")
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
  if (packageSmoke) {
    return CliCommand.PackageSmoke
  }

  return CliCommand.Launch(
      CliLaunchRequest(
          debug = debug,
          initialRom = positional.singleOrNull()?.let(::File),
          joinNetplayHost = joinNetplayEndpoint?.startClientValue,
          suppressInitialAutosaveResume = joinNetplayEndpoint != null,
          settingsOverrides =
              ApplicationSettingsOverrides(
                  hardwareProfile = profileOverride,
                  bootstrapMode = if (useBootstrap) BootstrapMode.NORMAL else null,
                  batterySavesEnabled = if (disableBatterySaves) false else null,
                  forceInMemoryBattery = joinNetplayEndpoint != null,
                  suppressCloseAutosave = joinNetplayEndpoint != null,
              ),
      ))
}

private fun parseJoinNetplayEndpoint(value: String): NetplayV8Endpoint =
    when (val validation = validateNetplayV8Address(value)) {
      is NetplayAddressValidation.Valid -> validation.endpoint
      is NetplayAddressValidation.Invalid -> cliError("--join-netplay ${validation.message}")
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
  stream.println("      --join-netplay HOST        Join a netplay host after opening ROM_FILE")
  stream.println("      --debug                    Enable debug console")
  stream.println("  -h  --help                     Display this help and exit")
  stream.println("      --version                  Display version and exit")
  stream.println("      --package-smoke            Run the self-contained headless package self-test")
  stream.println("      --                         Treat the remaining argument as the ROM file")
  stream.println()
  stream.println(
      "ROM_FILE must be a local .gb, .gbc, or .rom file, or a bounded .zip/.7z archive."
  )
}

private object VersionMarker

private fun applicationVersion(): String =
    VersionMarker::class.java.`package`?.implementationVersion ?: DEVELOPMENT_VERSION
