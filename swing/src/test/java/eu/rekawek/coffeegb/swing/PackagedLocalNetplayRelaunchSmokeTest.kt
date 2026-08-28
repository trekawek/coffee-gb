package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PackagedLocalNetplayRelaunchSmokeTest {

  @Test
  fun `failed PID publication cleanup forcibly terminates and preserves interruption`() {
    val process = FakeProcess()
    Thread.currentThread().interrupt()
    try {
      assertTrue(terminateStartedChild(process, timeoutMillis = 100))
      assertFalse(process.isAlive)
      assertEquals(1, process.forcedDestroyCount)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `parent uses the production local client request`() {
    val directory = Files.createTempDirectory("local-netplay-relaunch-parent")
    val marker = directory.resolve("child.marker")
    val launches = mutableListOf<List<String>>()

    launchPackagedLocalNetplayRelaunchChildIfRequested(
        environment = mapOf(LOCAL_NETPLAY_RELAUNCH_MARKER_ENV to marker.toString()),
        launcher = LocalNetplayInstanceLauncher { rom, profile, endpoint, count ->
          launches += listOf(rom.toString(), profile.id(), endpoint.startClientValue, "$count")
          LocalNetplayInstanceLaunchResult(1, 1, launcherAvailable = true)
        },
    )

    assertEquals(
        listOf(
            listOf(
                directory.resolve("local-netplay-relaunch-smoke.gb").toString(),
                HardwareProfileRegistry.DMG.id(),
                "localhost",
                "1",
            )),
        launches,
    )
    assertEquals(0x8000, Files.size(directory.resolve("local-netplay-relaunch-smoke.gb")))
  }

  @Test
  fun `child validates parsed request and writes PID evidence`() {
    val directory = Files.createTempDirectory("local-netplay-relaunch-child")
    val marker = directory.resolve("child.marker")
    val rom = Files.createFile(directory.resolve("local-netplay-relaunch-smoke.gb"))
    val packagedLauncher = Files.createFile(directory.resolve("Coffee GB.exe"))
    val environment =
        mapOf(
            LOCAL_NETPLAY_RELAUNCH_MARKER_ENV to marker.toString(),
            LOCAL_NETPLAY_RELAUNCH_EXPECTED_LAUNCHER_ENV to packagedLauncher.toString(),
            LOCAL_NETPLAY_RELAUNCH_ENDPOINT_ENV to "127.0.0.1:4567",
        )

    assertTrue(
        validatePackagedLocalNetplayRelaunchChildIfRequested(
            arrayOf(
                "--profile=dmg",
                "--disable-battery-saves",
                "--start-muted",
                "--join-netplay",
                "127.0.0.1:4567",
                rom.toString(),
            ),
            environment,
            packagedLauncher.toString(),
            processId = 1234,
        ))
    val evidence = Files.readString(marker)
    assertTrue(evidence.startsWith("Coffee GB local netplay relaunch OK:"))
    assertTrue(evidence.contains("pid=1234"))
    assertTrue(evidence.contains("profile=dmg"))
    assertTrue(evidence.contains("audio=muted"))
    assertTrue(evidence.contains("join=127.0.0.1:4567"))

    assertFalse(
        validatePackagedLocalNetplayRelaunchChildIfRequested(
            arrayOf("--package-smoke"),
            environment,
            packagedLauncher.toString(),
        ))
  }

  private class FakeProcess : Process() {
    private var alive = true
    var forcedDestroyCount = 0
      private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
      alive = false
      return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

    override fun exitValue(): Int =
        if (alive) throw IllegalThreadStateException("still running") else 0

    override fun destroy() {
      alive = false
    }

    override fun destroyForcibly(): Process {
      forcedDestroyCount++
      alive = false
      return this
    }

    override fun isAlive(): Boolean = alive
  }
}
