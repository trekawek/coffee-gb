package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.network.v9.V9DiagnosticEndpoint
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState
import eu.rekawek.coffeegb.controller.network.v9.V9LinkMode
import eu.rekawek.coffeegb.controller.network.v9.V9Role
import eu.rekawek.coffeegb.controller.network.v9.V9TransportMetricsSnapshot
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class NetplayDiagnosticsTest {

  @Test
  fun longRollbackSequenceHasExactBoundedAggregatesAndSaturates() {
    val metrics = NetplayRollbackMetrics(300)
    var expectedTotal = 0L
    var expectedRollbacks = 0L
    val lastWindow = ArrayDeque<Long>()
    repeat(10_000) { index ->
      val rewound = (index % 47).toLong()
      val resimulated = rewound + 1
      expectedTotal += resimulated
      metrics.recordRollback(rewound, resimulated)
      if (rewound > 0) {
        expectedRollbacks++
        lastWindow += rewound
        if (lastWindow.size > NetplayRollbackMetrics.ROLLING_SAMPLES) lastWindow.removeFirst()
      }
      metrics.updateHistory((index % 301))
    }
    repeat(17) { metrics.recordHistoryExhausted() }
    repeat(9) { metrics.recordCheckpoint(NetplayRollbackReason.RESYNCHRONIZATION) }
    val snapshot = metrics.snapshot()
    assertEquals(expectedRollbacks, snapshot.rollbackCount)
    assertEquals(46, snapshot.maximumFramesRewound)
    assertEquals(expectedTotal, snapshot.totalFramesResimulated)
    assertEquals(lastWindow.sum() * 1_000 / lastWindow.size, snapshot.rollingAverageFramesRewoundMilli)
    assertEquals(17, snapshot.tooOldInputs)
    assertEquals(9, snapshot.checkpointResynchronizations)
    assertEquals(NetplayRollbackReason.RESYNCHRONIZATION, snapshot.lastReason)
    assertEquals(NetplayRollbackMetrics.ROLLING_SAMPLES, metrics.retainedSampleCount())

    metrics.seedCountersForTest(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
    metrics.recordRollback(1, 1)
    metrics.recordHistoryExhausted()
    metrics.recordCheckpoint()
    val saturated = metrics.snapshot()
    assertEquals(Long.MAX_VALUE, saturated.rollbackCount)
    assertEquals(Long.MAX_VALUE, saturated.totalFramesResimulated)
    assertEquals(Long.MAX_VALUE, saturated.tooOldInputs)
    assertEquals(Long.MAX_VALUE, saturated.checkpointResynchronizations)
    metrics.close()
  }

  @Test
  fun exportIsWhitelistedBoundedAndAddressOptInIsExplicit() {
    val transport = transport()
    val rollback =
        NetplayRollbackMetricsSnapshot(3, 2, 4, 1_500, 8, 42, 300, 1, 2,
            NetplayRollbackReason.REMOTE_INPUT)
    val safe = NetplayDiagnosticsExporter.export(transport, rollback)
    assertTrue(safe.contains("remote-address=redacted"))
    assertFalse(safe.contains("192.0.2.44"))
    assertFalse(safe.contains("token", ignoreCase = true))
    assertTrue(safe.length <= NetplayDiagnosticsExporter.MAX_EXPORT_CHARS)

    val explicit = NetplayDiagnosticsExporter.export(transport, rollback, includeAddress = true)
    assertTrue(explicit.contains("remote-address=192.0.2.44:8765"))
    assertFalse(explicit.contains("coffeegb://"))
    val scoped = V9DiagnosticEndpoint("fe80::1%en0", 8765)
    assertFalse(scoped.toString().contains("fe80"))
    val ipv6 =
        NetplayDiagnosticsExporter.export(
            transport().copy(remoteEndpoint = scoped),
            rollback,
            includeAddress = true,
        )
    assertTrue(ipv6.contains("remote-address=[fe80::1%en0]:8765"))
    assertFailsWith<IllegalArgumentException> { V9DiagnosticEndpoint("dead.beef", 8765) }
    assertFailsWith<IllegalArgumentException> { V9DiagnosticEndpoint("192.168.001.1", 8765) }
    assertFailsWith<IllegalArgumentException> { V9DiagnosticEndpoint("2001:::1", 8765) }
  }

  @Test
  fun adversarialTextRedactionCoversSecretsPathsAddressesControlsAndBounds() {
    val hostile =
        "coffeegb://example.test:8765/join?v=9&token=AAAAAAAAAAAAAAAAAAAAAA " +
            "https://alice:password@example.test/x C:\\Users\\alice\\save.sav " +
            "/home/alice/rom.gb 192.0.2.42 [2001:db8::42] [fe80::1%en0] " +
            "peer.example.test\u0000" +
            "x".repeat(20_000)
    val value = NetplayDiagnosticSanitizer.redact(hostile)
    assertTrue(value.contains("[redacted-invitation]"))
    assertTrue(value.contains("[redacted-credentials]"))
    assertTrue(value.contains("[redacted-path]"))
    assertTrue(value.contains("[redacted-address]"))
    assertTrue(value.contains("[redacted-host]"))
    assertFalse(value.contains("AAAAAAAAAAAAAAAAAAAAAA"))
    assertFalse(value.contains("alice"))
    assertTrue(value.length <= NetplayDiagnosticSanitizer.MAX_OUTPUT_CHARS)
    assertFalse(value.any { it.code < 0x20 })
  }

  @Test
  fun runtimeInputAndPeerFailuresHaveNoInfoLogPath() {
    val root = repositoryRoot()
    val linked = Files.readString(
        root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/link/LinkedController.kt"),
    )
    val v9 =
        Files.walk(
            root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/network/v9"),
        ).use { paths ->
          paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
              .sorted()
              .map(Files::readString)
              .toList()
              .joinToString("\n")
        }
    assertFalse("LOG.info(" in linked || "LOG.atInfo(" in linked)
    assertFalse("LOG.info(" in v9 || "LOG.atInfo(" in v9)
  }

  @Test
  fun finalBoundedSnapshotIsDeliveredWithoutBlockingItsProducer() {
    val publisher = BoundedSnapshotPublisher(0, "netplay-diagnostics-close-test")
    val delivered = CountDownLatch(1)
    publisher.addListener { if (it == 2) delivered.countDown() }
    publisher.update(1)
    publisher.update(2)
    publisher.close()
    assertTrue(delivered.await(1, TimeUnit.SECONDS))
    assertTrue(publisher.awaitTermination(1, TimeUnit.SECONDS))
    assertEquals(0, publisher.activeWorkerCount())
    assertEquals(0, publisher.activeListenerCount())
  }

  @Test
  fun snapshotListenerBoundaryAndBoundaryPlusOneAreEnforced() {
    val publisher = BoundedSnapshotPublisher(0, "netplay-diagnostics-listener-bound")
    val subscriptions =
        List(BoundedSnapshotPublisher.MAX_LISTENERS) { index ->
          publisher.addListener { _ -> check(index >= 0) }
        }
    assertEquals(BoundedSnapshotPublisher.MAX_LISTENERS, publisher.activeListenerCount())
    assertFailsWith<IllegalStateException> { publisher.addListener { _ -> } }
    subscriptions.forEach(Closeable::close)
    publisher.close()
    assertTrue(publisher.awaitTermination(1, TimeUnit.SECONDS))
    assertEquals(0, publisher.activeListenerCount())
  }

  private fun transport() =
      V9TransportMetricsSnapshot(
          1_000,
          1_100,
          900,
          1_300,
          50,
          1,
          0,
          123,
          456,
          7_000,
          80,
          79,
          4,
          V9LifecycleState.ACTIVE,
          V9LinkMode.NORMAL,
          V9Role.CLIENT,
          1,
          V9DiagnosticEndpoint("192.0.2.44", 8765),
      )

  private fun repositoryRoot(): Path {
    System.getProperty("maven.multiModuleProjectDirectory")?.let { candidate ->
      val root = Path.of(candidate).toAbsolutePath().normalize()
      if (Files.isRegularFile(root.resolve("controller/pom.xml"))) return root
    }
    var candidate = Path.of("").toAbsolutePath().normalize()
    while (candidate.parent != null) {
      if (Files.isRegularFile(candidate.resolve("controller/pom.xml"))) return candidate
      candidate = candidate.parent
    }
    throw AssertionError("Cannot locate repository root")
  }
}
