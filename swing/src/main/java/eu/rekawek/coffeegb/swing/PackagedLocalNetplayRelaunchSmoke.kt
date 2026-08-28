package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

internal const val LOCAL_NETPLAY_RELAUNCH_MARKER_ENV =
    "COFFEE_GB_LOCAL_NETPLAY_RELAUNCH_SMOKE_MARKER"
internal const val LOCAL_NETPLAY_RELAUNCH_EXPECTED_LAUNCHER_ENV =
    "COFFEE_GB_LOCAL_NETPLAY_RELAUNCH_EXPECTED_LAUNCHER"
internal const val LOCAL_NETPLAY_RELAUNCH_PID_MARKER_ENV =
    "COFFEE_GB_LOCAL_NETPLAY_RELAUNCH_SMOKE_PID_MARKER"
internal const val LOCAL_NETPLAY_RELAUNCH_ENDPOINT_ENV =
    "COFFEE_GB_LOCAL_NETPLAY_RELAUNCH_SMOKE_ENDPOINT"
private const val RELAUNCH_ROM_NAME = "local-netplay-relaunch-smoke.gb"
private const val RELAUNCH_EVIDENCE_PREFIX = "Coffee GB local netplay relaunch OK:"

/**
 * The native-package verifier sets the marker only for a primary-launcher `--package-smoke` run.
 * The parent uses the production netplay launcher. Its child validates the exact typed request,
 * then continues through normal Swing, ROM, and automatic-connect startup.
 */
internal fun launchPackagedLocalNetplayRelaunchChildIfRequested(
    environment: Map<String, String> = System.getenv(),
    launcher: LocalNetplayInstanceLauncher? = null,
) {
  val marker = relaunchMarker(environment) ?: return
  val parent = requireNotNull(marker.parent) { "Local netplay relaunch marker must have a parent" }
  check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
    "Local netplay relaunch marker parent is not a directory"
  }
  check(!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
    "Local netplay relaunch marker already exists"
  }
  val rom = parent.resolve(RELAUNCH_ROM_NAME)
  Files.newByteChannel(
          rom,
          setOf(
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS,
          ),
      )
      .use { channel ->
        val generated = ByteBuffer.wrap(syntheticPackageRom())
        while (generated.hasRemaining()) channel.write(generated)
      }
  val endpointText =
      environment[LOCAL_NETPLAY_RELAUNCH_ENDPOINT_ENV]?.takeIf(String::isNotBlank) ?: "localhost"
  val endpoint =
      (validateNetplayV8Address(endpointText) as? NetplayAddressValidation.Valid)?.endpoint
          ?: error("Packaged local netplay relaunch endpoint is invalid")
  val activeLauncher = launcher ?: observedPackagedLocalNetplayLauncher(environment)
  val result = activeLauncher.launch(rom, HardwareProfileRegistry.DMG, endpoint, 1)
  check(result.launcherAvailable && result.started == 1) {
    "Packaged local netplay relaunch did not start its child"
  }
}

/**
 * Validates the packaged child before normal CLI/Swing startup and writes exclusive request
 * evidence. Returning true identifies the child but deliberately does not intercept it: package
 * verification continues through ROM activation and the automatic netplay connection.
 */
internal fun validatePackagedLocalNetplayRelaunchChildIfRequested(
    args: Array<String>,
    environment: Map<String, String> = System.getenv(),
    packagedLauncher: String? = System.getProperty("jpackage.app-path"),
    processId: Long = ProcessHandle.current().pid(),
): Boolean {
  val marker = relaunchMarker(environment) ?: return false
  if (args.contentEquals(arrayOf("--package-smoke"))) return false

  val expectedLauncher =
      environment[LOCAL_NETPLAY_RELAUNCH_EXPECTED_LAUNCHER_ENV]
          ?.takeIf(String::isNotBlank)
          ?.let(Path::of)
          ?.toAbsolutePath()
          ?.normalize()
          ?: error("Packaged local netplay relaunch expected launcher is missing")
  val actualLauncher =
      packagedLauncher
          ?.takeIf(String::isNotBlank)
          ?.let(Path::of)
          ?.toAbsolutePath()
          ?.normalize()
          ?: error("Packaged local netplay child did not report jpackage.app-path")
  check(Files.isSameFile(expectedLauncher, actualLauncher)) {
    "Packaged local netplay child used an unexpected launcher"
  }

  var launchRequest: CliLaunchRequest? = null
  val nullStream = PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
  nullStream.use {
    val exitCode =
        runCli(
            args,
            it,
            it,
            "package-relaunch-smoke",
            packageSmoke = { error("Relaunch child unexpectedly selected package smoke") },
        ) { request -> launchRequest = request }
    check(exitCode == 0) { "Packaged local netplay child request did not parse" }
  }
  val request = checkNotNull(launchRequest) { "Packaged local netplay child did not request launch" }
  val expectedRom = marker.resolveSibling(RELAUNCH_ROM_NAME).toAbsolutePath().normalize()
  check(request.initialRom?.toPath()?.toAbsolutePath()?.normalize() == expectedRom) {
    "Packaged local netplay child received an unexpected ROM"
  }
  check(request.settingsOverrides.hardwareProfile == HardwareProfileRegistry.DMG) {
    "Packaged local netplay child did not retain the DMG profile"
  }
  check(request.settingsOverrides.batterySavesEnabled == false) {
    "Packaged local netplay child did not disable battery saves"
  }
  check(request.settingsOverrides.forceInMemoryBattery) {
    "Packaged local netplay child did not select an in-memory battery"
  }
  check(request.settingsOverrides.suppressCloseAutosave) {
    "Packaged local netplay child did not suppress close autosave"
  }
  val expectedEndpoint =
      environment[LOCAL_NETPLAY_RELAUNCH_ENDPOINT_ENV]?.takeIf(String::isNotBlank) ?: "localhost"
  check(request.joinNetplayHost == expectedEndpoint && request.suppressInitialAutosaveResume) {
    "Packaged local netplay child did not retain its localhost join request"
  }

  val evidence =
      "$RELAUNCH_EVIDENCE_PREFIX pid=$processId, launcher=$actualLauncher, " +
          "profile=dmg, battery-saves=false, join=$expectedEndpoint\n"
  writeExclusiveText(marker, evidence)
  return true
}

private fun observedPackagedLocalNetplayLauncher(
    environment: Map<String, String>
): LocalNetplayInstanceLauncher {
  val pidMarker =
      environment[LOCAL_NETPLAY_RELAUNCH_PID_MARKER_ENV]
          ?.takeIf(String::isNotBlank)
          ?.let(Path::of)
          ?.toAbsolutePath()
          ?.normalize()
          ?: error("Packaged local netplay relaunch PID marker is missing")
  val parent = requireNotNull(pidMarker.parent) { "Local netplay PID marker must have a parent" }
  check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
    "Local netplay PID marker parent is not a directory"
  }
  check(!Files.exists(pidMarker, LinkOption.NOFOLLOW_LINKS)) {
    "Local netplay PID marker already exists"
  }
  return CurrentProcessLocalNetplayInstanceLauncher(
      currentProcessCommand(),
  ) { command ->
    val child = localNetplayProcessBuilder(command).start()
    try {
      writeExclusivePidMarker(pidMarker, child.pid())
    } catch (failure: Exception) {
      val terminated = terminateStartedChild(child, timeoutMillis = 5_000)
      if (!terminated) {
        throw IllegalStateException(
            "Packaged local netplay child survived failed PID publication",
            failure,
        )
      }
      throw failure
    }
  }
}

/** Publishes a complete PID or no marker at all, so verifier cleanup never parses partial data. */
private fun writeExclusivePidMarker(marker: Path, processId: Long) {
  require(processId > 0) { "Packaged local netplay child PID must be positive" }
  val parent = requireNotNull(marker.parent) { "Local netplay PID marker must have a parent" }
  val temporary = Files.createTempFile(parent, ".local-netplay-pid-", ".tmp")
  try {
    Files.newByteChannel(
            temporary,
            setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
        .use { channel ->
          val bytes = StandardCharsets.UTF_8.encode("$processId\n")
          while (bytes.hasRemaining()) channel.write(bytes)
        }
    try {
      Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temporary, marker)
    }
  } finally {
    Files.deleteIfExists(temporary)
  }
}

/** Bounded, interruption-preserving cleanup used when PID publication itself fails. */
internal fun terminateStartedChild(child: Process, timeoutMillis: Long): Boolean {
  require(timeoutMillis > 0) { "Child cleanup timeout must be positive" }
  var interrupted = Thread.interrupted()
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  try {
    child.destroyForcibly()
    while (child.isAlive && System.nanoTime() < deadline) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) break
      try {
        child.waitFor(
            TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 100),
            TimeUnit.MILLISECONDS,
        )
      } catch (_: InterruptedException) {
        interrupted = true
      }
      if (child.isAlive) child.destroyForcibly()
    }
    return !child.isAlive
  } finally {
    if (interrupted) Thread.currentThread().interrupt()
  }
}

private fun writeExclusiveText(path: Path, text: String) {
  Files.newByteChannel(
          path,
          setOf(
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS,
          ),
      )
      .use { channel ->
        val bytes = StandardCharsets.UTF_8.encode(text)
        while (bytes.hasRemaining()) channel.write(bytes)
      }
}

private fun relaunchMarker(environment: Map<String, String>): Path? =
    environment[LOCAL_NETPLAY_RELAUNCH_MARKER_ENV]
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
