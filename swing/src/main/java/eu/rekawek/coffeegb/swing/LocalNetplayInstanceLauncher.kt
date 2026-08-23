package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import java.nio.file.Path

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
 * a platform-specific installation path. Child clients deliberately disable battery saves so they
 * cannot race the host process over the same sidecar files.
 */
internal class CurrentProcessLocalNetplayInstanceLauncher(
    private val currentCommand: List<String> = currentProcessCommand(),
    private val startProcess: (List<String>) -> Unit = ::startProcessWithInheritedError,
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
    val command =
        launcher +
            listOf(
                "--profile=${profile.id()}",
                "--disable-battery-saves",
                "--join-netplay",
                endpoint.startClientValue,
                rom.toAbsolutePath().normalize().toString(),
            )
    var started = 0
    repeat(count) {
      try {
        startProcess(command)
        started++
      } catch (_: Exception) {
        return LocalNetplayInstanceLaunchResult(started, count, launcherAvailable = true)
      }
    }
    return LocalNetplayInstanceLaunchResult(started, count, launcherAvailable = true)
  }
}

/**
 * A child desktop process has no terminal of its own when launched from the netplay window.
 * Keep its diagnostic stream attached to the host process so a rejected checkpoint can be
 * diagnosed without a separate client console.
 */
private fun startProcessWithInheritedError(command: List<String>) {
  localNetplayProcessBuilder(command).start()
}

internal fun localNetplayProcessBuilder(command: List<String>): ProcessBuilder =
    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)

/** Builds a fresh Coffee GB command without replaying this process's app arguments. */
internal fun localNetplayLauncherPrefix(command: List<String>): List<String>? {
  if (command.isEmpty()) return null
  val jar = command.indexOf("-jar")
  if (jar >= 0 && jar + 1 < command.size) return command.take(jar + 2)

  val module = command.indexOfFirst { it == "-m" || it == "--module" }
  if (module >= 0 && module + 1 < command.size) return command.take(module + 2)

  val mainClass = command.indexOf("eu.rekawek.coffeegb.swing.MainKt")
  if (mainClass >= 0) return command.take(mainClass + 1)

  val executable = command.first()
  val executableName = executable.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
  if (executableName.equals("java", ignoreCase = true)) {
    return null
  }
  return listOf(executable)
}

private fun currentProcessCommand(): List<String> {
  val info = ProcessHandle.current().info()
  val executable = info.command().orElse(null) ?: return emptyList()
  return listOf(executable) + info.arguments().orElse(emptyArray()).toList()
}
