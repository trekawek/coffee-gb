package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val DESKTOP_MAIN_CLASS = "eu.rekawek.coffeegb.swing.MainKt"
private const val JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path"

/** Launches sibling desktop processes that use the current executable or Java launcher. */
internal fun interface LocalNetplayInstanceLauncher {
  fun launch(
      rom: Path,
      profile: HardwareProfile,
      endpoint: NetplayV8Endpoint,
      count: Int,
  ): LocalNetplayInstanceLaunchResult
}

internal data class LocalNetplayInstanceLaunchResult(
    val started: Int,
    val requested: Int,
    val launcherAvailable: Boolean,
) {
  init {
    require(requested in 1..3) { "Local netplay client count must be in 1..3" }
    require(started in 0..requested) { "Started client count must not exceed requested count" }
  }

  fun userMessage(): String =
      when {
        !launcherAvailable ->
            "Hosting is ready, but Coffee GB could not determine how to relaunch this installation."
        started == requested ->
            "Started $started local Coffee GB ${instanceWord(started)}; ${pluralPronoun(started)} will join localhost."
        started == 0 -> "Hosting is ready, but Coffee GB could not start the local client instances."
        else ->
            "Hosting is ready, but Coffee GB started only $started of $requested local client instances."
      }

  private fun instanceWord(count: Int): String = if (count == 1) "instance" else "instances"

  private fun pluralPronoun(count: Int): String = if (count == 1) "it" else "them"
}

/**
 * Reuses the current package launcher (or `java -jar`/module invocation) rather than depending on
 * a platform-specific installation path. Every child receives an isolated persistent battery so
 * it cannot race the host process over the original sidecar.
 */
internal class CurrentProcessLocalNetplayInstanceLauncher(
    private val currentCommand: List<String> = currentProcessCommand(),
    private val startProcess: (List<String>) -> Unit = ::startLocalNetplayProcess,
) : LocalNetplayInstanceLauncher {

  override fun launch(
      rom: Path,
      profile: HardwareProfile,
      endpoint: NetplayV8Endpoint,
      count: Int,
  ): LocalNetplayInstanceLaunchResult {
    require(count in 1..3) { "Local netplay client count must be in 1..3" }
    val launcher = localNetplayLauncherPrefix(currentCommand)
        ?: return LocalNetplayInstanceLaunchResult(0, count, launcherAvailable = false)
    val normalizedRom = rom.toAbsolutePath().normalize()
    val hostBattery =
        RomOrigin.directFile(normalizedRom).persistencePath(".sav").orElseThrow()
    var started = 0
    repeat(count) { index ->
      try {
        val clientBattery = localNetplayClientBatteryPath(hostBattery, index + 1)
        copyHostBatteryIfClientIsNew(hostBattery, clientBattery)
        val command =
            launcher +
                listOf(
                    "--profile=${profile.id()}",
                    "--battery-save",
                    clientBattery.toString(),
                    "--start-muted",
                    "--join-netplay",
                    endpoint.startClientValue,
                    normalizedRom.toString(),
                )
        startProcess(command)
        started++
      } catch (_: Exception) {
        return LocalNetplayInstanceLaunchResult(started, count, launcherAvailable = true)
      }
    }
    return LocalNetplayInstanceLaunchResult(started, count, launcherAvailable = true)
  }
}

private fun localNetplayClientBatteryPath(hostBattery: Path, clientNumber: Int): Path {
  require(clientNumber in 1..3) { "Local netplay client number must be in 1..3" }
  val hostName = hostBattery.fileName.toString()
  val stem = hostName.removeSuffix(".sav")
  return hostBattery.resolveSibling("$stem-client$clientNumber.sav")
}

private fun copyHostBatteryIfClientIsNew(hostBattery: Path, clientBattery: Path) {
  if (Files.exists(clientBattery, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isRegularFile(hostBattery, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.copy(hostBattery, clientBattery)
}

/**
 * A packaged GUI process may have no valid console handles, especially when started from the
 * Windows Start menu. Route child output to the platform null device so relaunch never depends on
 * inheritable standard streams and an unobserved pipe cannot fill up and stall emulation.
 */
private fun startLocalNetplayProcess(command: List<String>) {
  localNetplayProcessBuilder(command).start()
}

internal fun localNetplayProcessBuilder(command: List<String>): ProcessBuilder =
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)

/** Builds a fresh Coffee GB command without replaying this process's app arguments. */
internal fun localNetplayLauncherPrefix(command: List<String>): List<String>? {
  if (command.isEmpty()) return null
  val jar = command.indexOf("-jar")
  if (jar >= 0 && jar + 1 < command.size) return command.take(jar + 2)

  val module = command.indexOfFirst { it == "-m" || it == "--module" }
  if (module >= 0 && module + 1 < command.size) return command.take(module + 2)

  val mainClass = command.indexOf(DESKTOP_MAIN_CLASS)
  if (mainClass >= 0) return command.take(mainClass + 1)

  val executable = command.first()
  if (isJavaLauncher(executable)) {
    return null
  }
  return listOf(executable)
}

internal fun currentProcessCommand(): List<String> {
  val info = ProcessHandle.current().info()
  return currentProcessCommand(
      packagedLauncher = System.getProperty(JPACKAGE_APP_PATH_PROPERTY),
      executable = info.command().orElse(null),
      arguments = info.arguments().orElse(emptyArray()).toList(),
      classPath = System.getProperty("java.class.path"),
      desktopMainOnSystemClassPath = desktopMainOnSystemClassPath(),
  )
}

/**
 * Reconstructs a fresh desktop invocation without relying on application arguments being exposed
 * by [ProcessHandle]. Windows JVMs may report `java.exe`/`javaw.exe` while omitting every argument,
 * whereas jpackage publishes the exact native launcher through `jpackage.app-path`.
 */
internal fun currentProcessCommand(
    packagedLauncher: String?,
    executable: String?,
    arguments: List<String>,
    classPath: String?,
    desktopMainOnSystemClassPath: Boolean = true,
): List<String> {
  packagedLauncher?.takeIf(String::isNotBlank)?.let { return listOf(it) }
  val currentExecutable = executable?.takeIf(String::isNotBlank) ?: return emptyList()
  if (!isJavaLauncher(currentExecutable)) return listOf(currentExecutable)

  if (!desktopMainOnSystemClassPath) return emptyList()
  localNetplayLauncherPrefix(listOf(currentExecutable) + arguments)?.let { return it }
  val currentClassPath = classPath?.takeIf(String::isNotBlank) ?: return emptyList()
  return listOf(currentExecutable, "-cp", currentClassPath, DESKTOP_MAIN_CLASS)
}

/**
 * A custom Java host (for example Maven's plugin realm) can load Coffee GB without putting it on
 * the system class path inherited by a fresh JVM. Only reconstruct a class-path command when the
 * same system loader that a child JVM will use can resolve the desktop entry point.
 */
private fun desktopMainOnSystemClassPath(): Boolean =
    runCatching {
          Class.forName(
              DESKTOP_MAIN_CLASS,
              false,
              ClassLoader.getSystemClassLoader(),
          )
        }
        .isSuccess

private fun isJavaLauncher(executable: String): Boolean {
  val name =
      executable.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
  return name.equals("java", ignoreCase = true) || name.equals("javaw", ignoreCase = true)
}
