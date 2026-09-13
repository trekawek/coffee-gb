package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.core.joypad.Button
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetplayInputLogTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `log preserves pre-roll all players late arrival order and rollback without private state`() {
    val statuses = mutableListOf<NetplayRecordingStatusEvent>()
    var now = 100L
    val log = NetplayInputLog(LinkMode.FOUR_PLAYER_ADAPTER, 2, statuses::add, nanoTime = { now++ })
    val path = temporary.root.toPath().resolve("capture.jsonl")
    log.input(4, 4, 2, Input(listOf(Button.A), emptyList()), remote = false)
    log.start(NetplayRecordingStartEvent(log.sessionId, path), 10)
    log.input(5, 10, 0, Input(listOf(Button.LEFT), emptyList()), remote = true)
    log.input(8, 10, 3, Input(emptyList(), listOf(Button.START)), remote = true)
    log.event("rollback", 10, "framesRewound" to 5, "framesResimulated" to 5)
    log.stop(11)
    log.finish(12, deadline())

    val lines = Files.readAllLines(path)
    assertTrue(lines.first().contains("\"localPlayer\":2"))
    assertTrue(lines.first().contains("\"initialStateIncluded\":false"))
    assertTrue(lines.first().contains("\"droppedEvents\":0"))
    assertTrue(lines[1].contains("\"inputFrame\":4,\"player\":2,\"source\":\"local\""))
    assertTrue(lines[3].contains("\"frame\":10,\"inputFrame\":5,\"player\":0"))
    assertTrue(lines[4].contains("\"released\":[\"START\"]"))
    assertTrue(lines[5].contains("\"type\":\"rollback\""))
    assertTrue(lines.last().contains("\"reason\":\"user\""))
    assertFalse(lines.joinToString().contains(temporary.root.toString()))
    assertEquals(path.toAbsolutePath(), statuses.single { it.savedPath != null }.savedPath)
    assertFalse(statuses.last().available)
  }

  @Test
  fun `bounded history reports truncation and active limit saves instead of losing later input silently`() {
    val log = NetplayInputLog(LinkMode.NORMAL, 0, {}, historyLimit = 2, recordingLimit = 5)
    val path = temporary.root.toPath().resolve("bounded.jsonl")
    repeat(6) { log.event("progress", it.toLong()) }
    log.start(NetplayRecordingStartEvent(log.sessionId, path), 6)
    log.event("progress", 7)
    log.event("progress", 8)
    assertEquals(ReplayRecordingPhase.SAVING, log.phase)
    log.finish(9, deadline())
    val lines = Files.readAllLines(path)
    assertEquals(7, lines.size)
    assertTrue(lines.first().contains("\"droppedEvents\":4"))
    assertTrue(lines.last().contains("\"reason\":\"record_limit\""))
  }

  @Test
  fun `failed save retains exact log for retry and never overwrites an existing file`() {
    val original = temporary.newFile("existing.jsonl").toPath()
    Files.writeString(original, "keep this")
    val log = NetplayInputLog(LinkMode.NORMAL, 0, {})
    log.start(NetplayRecordingStartEvent(log.sessionId, original), 0)
    log.input(1, 1, 0, Input(listOf(Button.B), emptyList()), remote = false)
    assertFailsWith<IOException> { log.finish(2, deadline()) }
    assertEquals(ReplayRecordingPhase.UNSAVED, log.phase)
    assertEquals("keep this", Files.readString(original))
    val retry = temporary.root.toPath().resolve("retry.jsonl")
    log.retry(NetplayRecordingRetryEvent(log.sessionId, retry))
    log.finish(20, deadline())
    assertTrue(Files.readString(retry).contains("\"pressed\":[\"B\"]"))
    assertTrue(Files.readAllLines(retry).last().contains("\"frame\":2"))
  }

  @Test
  fun `slow save does not block input owner and a close timeout reuses the writer`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var writes = 0
    val log = NetplayInputLog(LinkMode.NORMAL, 0, {}, write = { path, _ ->
      writes++
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      path
    })
    try {
      log.start(NetplayRecordingStartEvent(log.sessionId, Path.of("test.jsonl")), 0)
      log.stop(1)
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      log.poll()
      log.event("progress", 2)
      assertFailsWith<java.util.concurrent.TimeoutException> { log.finish(3, System.nanoTime()) }
      assertEquals(ReplayRecordingPhase.SAVING, log.phase)
    } finally {
      release.countDown()
      log.finish(4, deadline())
    }
    assertEquals(1, writes)
  }

  @Test
  fun `stale start and retry requests cannot affect another session`() {
    val log = NetplayInputLog(LinkMode.NORMAL, 0, {})
    log.start(NetplayRecordingStartEvent(log.sessionId + 1, Path.of("stale.jsonl")), 0)
    assertEquals(ReplayRecordingPhase.IDLE, log.phase)
    log.retry(NetplayRecordingRetryEvent(log.sessionId + 1, Path.of("stale.jsonl")))
    log.finish(0, deadline())
  }

  private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
}
