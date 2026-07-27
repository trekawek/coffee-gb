package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.state.RomIdentity
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.V9LinkedControllerTarget
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.StateHistory
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.state.LinkedPlayerState
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.LinkedSessionStateRoot
import eu.rekawek.coffeegb.controller.state.LinkedTopologyState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9StateTransportTest {

  @Test
  fun startAdmissionPublishesCorrelationAndWaitReadyBeforeImmediatePeerReady() {
    val fixture = stateFixture()
    val lifecycle = serverLifecycleAtSynchronizing()
    val active = AtomicReference<V9ActiveBoundary?>()
    val target = target(fixture.identities, AtomicReference(), LinkedBlockingQueue())
    val generation = target.captureGeneration()
    val plan =
        V9GuestPlayPlan(
            target,
            target,
            provider(generation) { fixture.v2.copyOf() },
            V9CheckpointKind.MACHINE,
            0,
            V9SessionIdSource { 0x1020304050607080L },
        )
    lateinit var session: V9PlaySession
    var nextSequence = 1L
    val sender =
        V9PlaySend { type, flags, correlation, channel, payload, onAdmitted, onWritten ->
          val sequence = nextSequence++
          val rollback = onAdmitted(sequence)
          if (type == V9MessageType.START) {
            assertEquals(V9LifecycleState.WAIT_READY, lifecycle.snapshot().state)
            val sessionId = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
            V9Frame(
                    V9FrameHeader(
                        V9MessageType.READY.wireId,
                        V9MessageType.READY,
                        V9Flag.RESPONSE.wireMask,
                        0,
                        sequence,
                        8,
                        8,
                        ProtocolV9.CONTROL_CHANNEL,
                        ByteArray(32),
                    ),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(sessionId).array(),
                )
                .use(session::handle)
          }
          onWritten()
          // Successful publication owns the admission; rollback is intentionally unused.
          assertNotNull(rollback)
          sequence
        }
    session =
        V9PlaySession(
            V9Role.SERVER,
            V9LinkMode.NORMAL,
            1,
            authorization(fixture.identity),
            plan,
            generation,
            { _, task -> Thread().also { task() } },
            sender,
            lifecycle,
            { reason, _ -> throw AssertionError("unexpected failure $reason") },
            active::set,
        )
    try {
      session.start()
      assertEquals(V9LifecycleState.ACTIVE, lifecycle.snapshot().state)
      assertEquals(0x1020304050607080L, assertNotNull(active.get()).sessionId)
    } finally {
      session.close()
      fixture.close()
    }
  }

  @Test
  fun directCheckpointRequiresV2AndDoesNotConsumeGrantUntilValidationAndApplyCommit() {
    val fixture = stateFixture()
    try {
      val authorization = authorization(fixture.identity)
      val grant = V9CheckpointGrant(authorization)
      val metadata = V9CheckpointMetadata(V9CheckpointKind.MACHINE, 0x01, 0, 7, 41)

      fun declaration(bytes: ByteArray) =
          V9CheckpointDeclaration(
              metadata,
              bytes.size,
              MessageDigest.getInstance("SHA-256").digest(bytes),
          )

      val legacy = fixture.v1
      grant.preflight(declaration(legacy), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.STATEFILE_VERSION,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  legacy,
                  declaration(legacy),
                  fixture.identities,
              )
            }.reason,
        )
      }
      assertEquals(0, grant.used())

      val corrupt = fixture.v2.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
      grant.preflight(declaration(corrupt), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.CHECKSUM_MISMATCH,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  corrupt,
                  declaration(corrupt),
                  fixture.identities,
              )
            }.reason,
        )
      }
      assertEquals(0, grant.used())

      val oversizedDecoded = fixture.v2.copyOf()
      ByteBuffer.wrap(oversizedDecoded, 28, 8).order(ByteOrder.BIG_ENDIAN)
          .putLong(V9Limit.STATEFILE_DECODED_BYTES.value + 1)
      grant.preflight(declaration(oversizedDecoded), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.LIMIT_EXCEEDED,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  oversizedDecoded,
                  declaration(oversizedDecoded),
                  fixture.identities,
              )
            }.reason,
        )
      }
      assertEquals(0, grant.used())

      val validDeclaration = declaration(fixture.v2)
      val ticket = grant.preflight(validDeclaration, 0, 1, 1)
      val file =
          V9CheckpointStateValidation.decodeAndValidate(
              fixture.v2,
              validDeclaration,
              fixture.identities,
          )
      assertEquals(2, file.formatVersion)
      assertEquals(0, grant.used())
      ticket.commit()
      assertEquals(1, grant.used())
      grant.close()
      corrupt.fill(0)
      oversizedDecoded.fill(0)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun closingPlayableSessionCancelsQueuedCommitAndLeavesGrantUnconsumed() {
    val fixture = stateFixture()
    val generation = targetGeneration(fixture.identities, frame = 9)
    val queuedCompletion = AtomicReference<V9CheckpointCommitCompletion?>()
    val prepared =
        object : V9PreparedCheckpoint {
          private val cancelled = AtomicBoolean()

          override fun commit(completion: V9CheckpointCommitCompletion) {
            assertTrue(queuedCompletion.compareAndSet(null, completion))
          }

          override fun cancelBeforeCommit(): Boolean {
            if (cancelled.compareAndSet(false, true)) {
              queuedCompletion.getAndSet(null)?.complete(V9ErrorCode.CANCELLED)
            }
            return true
          }

          override fun close() {
            cancelBeforeCommit()
          }
        }
    val target =
        object : V9CheckpointTarget, V9GameplayTarget {
          override fun captureGeneration(): V9TargetGeneration = generation

          override fun prepare(
              checkpoint: V9ValidatedCheckpoint,
              expected: V9TargetGeneration,
              completion: V9CheckpointPrepareCompletion,
          ): java.io.Closeable {
            assertTrue(generation.sameIdentityGeneration(expected))
            completion.complete(prepared, null)
            return java.io.Closeable {}
          }

          override fun input(value: V9InputState, completion: V9GameplayCompletion) =
              completion.complete(null)

          override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) =
              completion.complete(null)
        }
    val lifecycle = clientLifecycleAtActive()
    val plan =
        V9GuestPlayPlan(
            target,
            target,
            initialKind = V9CheckpointKind.MACHINE,
            initialOwnerPlayer = 0,
        )
    val session =
        V9PlaySession(
            V9Role.CLIENT,
            V9LinkMode.NORMAL,
            1,
            authorization(fixture.identity),
            plan,
            generation,
            { _, task -> Thread().also { task() } },
            V9PlaySend { _, _, _, _, _, _, _ -> 1 },
            lifecycle,
            { reason, _ -> throw AssertionError("unexpected failure $reason") },
            {},
        )
    try {
      val metadata = V9CheckpointMetadata(V9CheckpointKind.SESSION, 0x01, 0, 10, 41)
      val payload = V9CheckpointCodec.encode(metadata, fixture.v2Session)
      V9Frame(
              V9FrameHeader(
                  V9MessageType.CHECKPOINT.wireId,
                  V9MessageType.CHECKPOINT,
                  0,
                  0,
                  1,
                  payload.size.toLong(),
                  payload.size.toLong(),
                  1,
                  ByteArray(32),
              ),
              payload,
          )
          .use(session::handle)
      assertNotNull(queuedCompletion.get())
      session.close()
      assertEquals(V9ErrorCode.CANCELLED, session.checkpointOutcome())
      assertEquals(1, session.checkpointCompletions())
      assertEquals(0, session.grantUses())
      assertNull(queuedCompletion.get())
    } finally {
      session.close()
      fixture.close()
    }
  }

  @Test
  fun safePointCommitWinnerCompletesOnceAndConsumesGrantWhenCloseRacesAfterSelection() {
    val fixture = stateFixture()
    val generation = targetGeneration(fixture.identities, frame = 9)
    val selected = AtomicBoolean()
    val commitCompletion = AtomicReference<V9CheckpointCommitCompletion?>()
    val prepared =
        object : V9PreparedCheckpoint {
          override fun commit(completion: V9CheckpointCommitCompletion) {
            assertTrue(commitCompletion.compareAndSet(null, completion))
          }

          override fun cancelBeforeCommit(): Boolean = !selected.get()

          override fun close() {}
        }
    val target =
        object : V9CheckpointTarget, V9GameplayTarget {
          override fun captureGeneration(): V9TargetGeneration = generation

          override fun prepare(
              checkpoint: V9ValidatedCheckpoint,
              generation: V9TargetGeneration,
              completion: V9CheckpointPrepareCompletion,
          ): java.io.Closeable {
            completion.complete(prepared, null)
            return java.io.Closeable {}
          }

          override fun input(value: V9InputState, completion: V9GameplayCompletion) =
              completion.complete(null)

          override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) =
              completion.complete(null)
        }
    val session =
        V9PlaySession(
            V9Role.CLIENT,
            V9LinkMode.NORMAL,
            1,
            authorization(fixture.identity),
            V9GuestPlayPlan(target, target, initialKind = V9CheckpointKind.MACHINE,
                initialOwnerPlayer = 0),
            generation,
            { _, task -> Thread().also { task() } },
            V9PlaySend { _, _, _, _, _, _, _ -> 1 },
            clientLifecycleAtActive(),
            { reason, _ -> throw AssertionError("unexpected failure $reason") },
            {},
        )
    try {
      val metadata = V9CheckpointMetadata(V9CheckpointKind.SESSION, 0x01, 0, 10, 41)
      val payload = V9CheckpointCodec.encode(metadata, fixture.v2Session)
      V9Frame(
              V9FrameHeader(
                  V9MessageType.CHECKPOINT.wireId,
                  V9MessageType.CHECKPOINT,
                  0,
                  0,
                  1,
                  payload.size.toLong(),
                  payload.size.toLong(),
                  1,
                  ByteArray(32),
              ),
              payload,
          )
          .use(session::handle)
      assertNotNull(commitCompletion.get())
      selected.set(true)
      session.close()
      assertEquals(0, session.checkpointCompletions())
      commitCompletion.getAndSet(null)?.complete(null)
      assertEquals(1, session.checkpointCompletions())
      assertNull(session.checkpointOutcome())
      assertEquals(1, session.grantUses())
      prepared.close()
      assertEquals(1, session.checkpointCompletions())
    } finally {
      session.close()
      fixture.close()
    }
  }

  @Test
  fun checkpointPrefixTruncationReservedFieldsAndHeaderBoundaryPlusOneRejectBeforePayloadAllocation() {
    val metadata = V9CheckpointMetadata(V9CheckpointKind.MACHINE, 0x01, 0, 1, 41)
    val minimum = ByteArray(V9CheckpointCodec.MINIMUM_STATEFILE_BYTES)
    val payload = V9CheckpointCodec.encode(metadata, minimum)
    for (length in 0 until V9CheckpointCodec.PREFIX_BYTES) {
      assertEquals(
          V9ErrorCode.STATEFILE_MALFORMED,
          assertFailsWith<V9ProtocolException> {
            V9CheckpointCodec.decodeDeclaration(payload.copyOf(length))
          }.reason,
      )
    }
    assertEquals(V9CheckpointCodec.MINIMUM_STATEFILE_BYTES,
        V9CheckpointCodec.decodeDeclaration(payload).stateLength)
    payload[3] = 1
    assertEquals(
        V9ErrorCode.STATEFILE_MALFORMED,
        assertFailsWith<V9ProtocolException> {
          V9CheckpointCodec.decodeDeclaration(payload)
        }.reason,
    )

    val maximum = V9MessageType.CHECKPOINT.spec.maximumEncodedBytes
    val decoder =
        V9IncrementalDecoder(
            policy = V9DecoderPolicy(
                allowedMessages = setOf(V9MessageType.CHECKPOINT),
                negotiatedCapabilities = V9Capability.requiredCapabilities,
                linkMode = V9LinkMode.NORMAL,
            ),
        )
    val exact = checkpointHeader(maximum)
    val exactBatch = decoder.feed(exact, 0, 32)
    assertTrue(exactBatch.needsMore)
    assertEquals(32, exactBatch.retainedBytes)
    assertEquals(0, exactBatch.payloadAllocations)
    decoder.finish()

    val oversized = V9IncrementalDecoder(
        policy = V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.CHECKPOINT),
            negotiatedCapabilities = V9Capability.requiredCapabilities,
            linkMode = V9LinkMode.NORMAL,
        ),
    )
    val failure = oversized.feed(checkpointHeader(maximum + 1), 0, 32)
    assertEquals(V9ErrorCode.LIMIT_EXCEEDED, failure.failure?.reason)
    assertEquals(0, failure.payloadAllocations)
    assertEquals(0, failure.payloadReservations)
    oversized.finish()
    payload.fill(0)
    minimum.fill(0)
  }

  @Test
  fun productionDecoderRetainsOneBoundedCheckpointAcrossBytewiseIrregularAndCoalescedInput() {
    val fixture = stateFixture()
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.CHECKPOINT, V9MessageType.INPUT),
            negotiatedCapabilities = V9Capability.requiredCapabilities,
            linkMode = V9LinkMode.NORMAL,
        )
    val checkpoint =
        V9CheckpointCodec.encode(
            V9CheckpointMetadata(V9CheckpointKind.MACHINE, 0x01, 0, 7, 41),
            fixture.v2,
        )
    val first =
        V9FrameEncoder.encode(
            V9OutboundFrame(V9MessageType.CHECKPOINT, 0, 0, 0, 1, checkpoint),
            policy,
        )
    val input = V9GameplayCodec.encodeInput(V9InputState(8, 0, 0x31, 1))
    val second =
        V9FrameEncoder.encode(
            V9OutboundFrame(V9MessageType.INPUT, 0, 1, 0, 1, input),
            policy,
        )
    checkpoint.fill(0)
    input.fill(0)
    try {
      val bytewise = V9IncrementalDecoder(policy = policy)
      first.indices.forEach { index ->
        val batch = bytewise.feedOne(first, index, 1)
        if (index < first.lastIndex) {
          assertTrue(batch.needsMore)
          assertTrue(batch.frames.isEmpty())
          assertTrue(batch.retainedBytes <= first.size)
          assertTrue(batch.payloadAllocations <= 1)
        } else {
          assertEquals(1, batch.frames.size)
          batch.frames.single().use { frame ->
            assertEquals(V9MessageType.CHECKPOINT, frame.header.type)
            assertEquals(fixture.v2.size, V9CheckpointCodec.decodeDeclaration(frame.payloadView()).stateLength)
          }
        }
      }

      val coalesced = first + second
      val decoder = V9IncrementalDecoder(policy = policy)
      val firstBatch = decoder.feedOne(coalesced, 0, coalesced.size)
      assertEquals(first.size.toLong(), firstBatch.consumedBytes)
      firstBatch.frames.single().close()
      val secondBatch = decoder.feedOne(coalesced, first.size, second.size)
      assertEquals(coalesced.size.toLong(), secondBatch.consumedBytes)
      secondBatch.frames.single().use { frame ->
        assertEquals(0x31, V9GameplayCodec.decodeInput(frame.payloadView(), 1).buttonMask)
      }
      assertEquals(0, decoder.queueSnapshot().frames)
      coalesced.fill(0)

      val irregular = V9IncrementalDecoder(policy = policy)
      var offset = 0
      val chunks = intArrayOf(3, 29, 1, 47, 5, 257)
      var chunk = 0
      while (offset < first.size) {
        val length = minOf(chunks[chunk++ % chunks.size], first.size - offset)
        val before = irregular.snapshot().consumedBytes
        val batch = irregular.feedOne(first, offset, length)
        val consumed = (batch.consumedBytes - before).toInt()
        assertTrue(consumed in 1..length)
        offset += consumed
        batch.frames.forEach(V9Frame::close)
      }
      assertEquals(first.size.toLong(), irregular.snapshot().consumedBytes)
    } finally {
      first.fill(0)
      second.fill(0)
      fixture.close()
    }
  }

  @Test
  fun checkpointRootRomProfileDigestTopologyAndUnsignedGrantRulesFailClosed() {
    val fixture = stateFixture()
    try {
      val authorization = authorization(fixture.identity)
      val grant = V9CheckpointGrant(authorization)
      fun declaration(metadata: V9CheckpointMetadata, bytes: ByteArray) =
          V9CheckpointDeclaration(
              metadata,
              bytes.size,
              MessageDigest.getInstance("SHA-256").digest(bytes),
          )

      val wrongRoot = V9CheckpointMetadata(V9CheckpointKind.SESSION, 0x01, 0, 1, 41)
      grant.preflight(declaration(wrongRoot, fixture.v2), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.ROOT_KIND_MISMATCH,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  fixture.v2,
                  declaration(wrongRoot, fixture.v2),
                  fixture.identities,
              )
            }.reason,
        )
      }

      val wrongRom =
          fixture.identities.map { entry ->
            if (entry.player != 0) entry
            else StateIdentityEntry(
                0,
                checkNotNull(entry.identity).copy(
                    primaryRom = RomIdentity(ByteArray(32) { 0x55 }),
                ),
            )
          }
      val machine = V9CheckpointMetadata(V9CheckpointKind.MACHINE, 0x01, 0, 2, 41)
      grant.preflight(declaration(machine, fixture.v2), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.ROM_MISMATCH,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  fixture.v2,
                  declaration(machine, fixture.v2),
                  wrongRom,
              )
            }.reason,
        )
      }

      val cgbIdentity =
          StateIdentity.from(
              StateCodecTestSupport.configuration(
                  StateCodecTestSupport.rom(),
                  eu.rekawek.coffeegb.core.GameboyType.CGB,
              ),
          )
      val wrongProfile =
          listOf(StateIdentityEntry(0, cgbIdentity), StateIdentityEntry(1, cgbIdentity))
      grant.preflight(declaration(machine.copy(frame = 3), fixture.v2), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.PROFILE_MISMATCH,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  fixture.v2,
                  declaration(machine.copy(frame = 3), fixture.v2),
                  wrongProfile,
              )
            }.reason,
        )
      }

      val trailing = fixture.v2 + byteArrayOf(0)
      grant.preflight(declaration(machine.copy(frame = 4), trailing), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.TRAILING_DATA,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  trailing,
                  declaration(machine.copy(frame = 4), trailing),
                  fixture.identities,
              )
            }.reason,
        )
      }
      val future = fixture.v2.copyOf().also { it[4] = 0; it[5] = 3 }
      grant.preflight(declaration(machine.copy(frame = 5), future), 0, 1, 1).use {
        assertEquals(
            V9ErrorCode.STATEFILE_VERSION,
            assertFailsWith<V9ProtocolException> {
              V9CheckpointStateValidation.decodeAndValidate(
                  future,
                  declaration(machine.copy(frame = 5), future),
                  fixture.identities,
              )
            }.reason,
        )
      }

      val wrongDigest = declaration(machine.copy(frame = 3), fixture.v2)
      val payload = V9CheckpointCodec.encode(machine.copy(frame = 3), fixture.v2)
      payload[payload.lastIndex] = (payload.last() + 1).toByte()
      val decoded = V9CheckpointCodec.decodeDeclaration(payload)
      assertTrue(!MessageDigest.isEqual(wrongDigest.digestView(), decoded.digestView()))
      payload.fill(0)

      val unsignedFrames = listOf(Long.MAX_VALUE, Long.MIN_VALUE, -1L)
      unsignedFrames.forEach { frame ->
        val metadata = machine.copy(frame = frame)
        grant.preflight(declaration(metadata, fixture.v2), 0, 1, 1).commit()
      }
      assertEquals(3, grant.used())
      assertEquals(
          V9ErrorCode.CONSENT_REJECTED,
          assertFailsWith<V9ProtocolException> {
            val wrapped = machine.copy(frame = 0)
            grant.preflight(declaration(wrapped, fixture.v2), 0, 1, 1)
          }.reason,
      )
      assertEquals(3, grant.used())
      grant.close()
      trailing.fill(0)
      future.fill(0)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun directionalCheckpointGrantAcceptsExactlyThirtyTwoCommittedUses() {
    val fixture = stateFixture()
    val grant = V9CheckpointGrant(authorization(fixture.identity))
    try {
      for (frame in 1L..V9Limit.CHECKPOINTS_PER_DIRECTIONAL_GRANT.value) {
        val metadata = V9CheckpointMetadata(V9CheckpointKind.MACHINE, 0x01, 0, frame, 41)
        grant.preflight(checkpointDeclaration(metadata, fixture.v2), 0, 1, 1).commit()
      }
      assertEquals(V9Limit.CHECKPOINTS_PER_DIRECTIONAL_GRANT.value.toInt(), grant.used())
      val overflow =
          V9CheckpointMetadata(
              V9CheckpointKind.MACHINE,
              0x01,
              0,
              V9Limit.CHECKPOINTS_PER_DIRECTIONAL_GRANT.value + 1,
              41,
          )
      assertEquals(
          V9ErrorCode.CONSENT_REJECTED,
          assertFailsWith<V9ProtocolException> {
            grant.preflight(checkpointDeclaration(overflow, fixture.v2), 0, 1, 1)
          }.reason,
      )
      assertEquals(V9Limit.CHECKPOINTS_PER_DIRECTIONAL_GRANT.value.toInt(), grant.used())
    } finally {
      grant.close()
      fixture.close()
    }
  }

  @Test
  fun foundationPingSchedulerNegotiationCadencePendingBoundAndLivenessAreExact() {
    diagnosticPair(serverDiagnostics = true, clientDiagnostics = false).use { disabled ->
      assertFalse(V9Capability.PING_V1 in disabled.server.negotiatedCapabilities())
      assertFalse(V9Capability.PING_V1 in disabled.client.negotiatedCapabilities())
      assertNotNull(disabled.server.transportMetricsSource())
      assertNull(disabled.client.transportMetricsSource())
      assertEquals(listOf(30_000L), disabled.serverScheduler.activeDeadlines())
      assertEquals(listOf(30_000L), disabled.clientScheduler.activeDeadlines())
      disabled.serverScheduler.runAt(1_000)
      assertEquals(0, disabled.serverChannel.messageCount(V9MessageType.PING))
      assertEquals(0, disabled.clientChannel.messageCount(V9MessageType.PING))
      assertEquals(V9LifecycleState.ACTIVE, disabled.server.snapshot().state)
    }

    val timed = diagnosticPair(serverDiagnostics = true, clientDiagnostics = true)
    val metrics = assertNotNull(timed.server.transportMetricsSource()) as V9TransportMetrics
    val callbacks = AtomicInteger()
    val subscription = metrics.addListener { callbacks.incrementAndGet() }
    try {
      assertTrue(V9Capability.PING_V1 in timed.server.negotiatedCapabilities())
      assertTrue(V9Capability.PING_V1 in timed.client.negotiatedCapabilities())
      assertTrue(timed.serverScheduler.activeDeadlines().contains(1_000L))
      timed.clientChannel.blockWrites()
      timed.serverScheduler.runAt(1_000)
      assertTrue(timed.clientChannel.awaitBlockedWrite(1, TimeUnit.SECONDS))
      waitUntil { timed.serverChannel.messageCount(V9MessageType.PING) == 1 }
      val firstPing = timed.serverChannel.lastMessage(V9MessageType.PING)
      assertEquals(ProtocolV9.HEADER_BYTES + V9PingCodec.PAYLOAD_BYTES, firstPing.size)
      val firstHeader = ByteBuffer.wrap(firstPing).order(ByteOrder.BIG_ENDIAN)
      assertEquals(0, firstHeader.getShort(10).toInt())
      assertEquals(V9PingCodec.PAYLOAD_BYTES, firstHeader.getInt(20))
      assertEquals(V9PingCodec.PAYLOAD_BYTES, firstHeader.getInt(24))
      assertEquals(ProtocolV9.CONTROL_CHANNEL.toInt(), firstHeader.getInt(28))
      firstPing.fill(0)
      assertEquals(1, metrics.pendingCount())
      assertEquals(1, metrics.snapshot().unansweredPings)

      timed.serverScheduler.runAt(2_000)
      timed.serverScheduler.runAt(3_000)
      timed.serverScheduler.runAt(3_999)
      assertEquals(1, timed.serverChannel.messageCount(V9MessageType.PING))
      assertEquals(0, metrics.snapshot().timedOutPings)
      assertEquals(V9LifecycleState.ACTIVE, timed.server.snapshot().state)

      timed.serverScheduler.runAt(4_000)
      waitUntil { timed.serverChannel.messageCount(V9MessageType.PING) == 2 }
      assertEquals(1, metrics.snapshot().timedOutPings)
      assertEquals(1, metrics.pendingCount())
      timed.serverScheduler.runAt(5_000)
      timed.serverScheduler.runAt(6_000)
      timed.serverScheduler.runAt(6_999)
      assertEquals(V9LifecycleState.ACTIVE, timed.server.snapshot().state)
      timed.serverScheduler.runAt(7_000)
      assertEquals(V9LifecycleState.CLOSED, timed.server.snapshot().state)
      assertEquals(V9ErrorCode.TIMEOUT, timed.server.snapshot().failure?.reason)
      assertEquals(2, metrics.snapshot().timedOutPings)
      assertEquals(0, metrics.pendingCount())
    } finally {
      timed.close()
    }
    assertEquals(0, timed.serverScheduler.activeTaskCount())
    assertEquals(0, timed.clientScheduler.activeTaskCount())
    assertEquals(0, metrics.pendingCount())
    assertEquals(0, metrics.listenerCountForTest())
    subscription.close()
  }

  @Test
  fun validPongResetsFoundationTimeoutProgressionAndCloseCancelsPingOwnership() {
    val pair = diagnosticPair(serverDiagnostics = true, clientDiagnostics = true)
    val metrics = assertNotNull(pair.server.transportMetricsSource()) as V9TransportMetrics
    try {
      pair.server.seedConsecutivePingTimeoutsForTest(1)
      pair.serverScheduler.runAt(1_000)
      waitUntil { metrics.pendingCount() == 0 && metrics.snapshot().currentRttMicros != null }
      assertEquals(V9LifecycleState.ACTIVE, pair.server.snapshot().state)

      pair.clientChannel.blockWrites()
      pair.serverScheduler.runAt(2_000)
      assertTrue(pair.clientChannel.awaitBlockedWrite(1, TimeUnit.SECONDS))
      pair.serverScheduler.runAt(3_000)
      pair.serverScheduler.runAt(4_000)
      pair.serverScheduler.runAt(4_999)
      assertEquals(V9LifecycleState.ACTIVE, pair.server.snapshot().state)
      pair.serverScheduler.runAt(5_000)
      // The valid PONG reset the consecutive counter: this is the first new missed probe.
      assertEquals(V9LifecycleState.ACTIVE, pair.server.snapshot().state)
      assertEquals(1, metrics.snapshot().timedOutPings)
      assertEquals(1, metrics.pendingCount())
    } finally {
      pair.close()
    }
    assertEquals(0, pair.serverScheduler.activeTaskCount())
    assertEquals(0, pair.clientScheduler.activeTaskCount())
    assertEquals(0, metrics.pendingCount())
  }

  @Test
  fun liveFoundationRejectsLateDuplicateWrongCorrelationAndWrongNoncePongs() {
    fun rejected(
        expected: V9ErrorCode,
        prepare: (DiagnosticConnectionPair) -> ByteArray,
    ) {
      diagnosticPair(serverDiagnostics = true, clientDiagnostics = true).use { pair ->
        val hostile = prepare(pair)
        pair.clientChannel.injectToPeer(hostile)
        hostile.fill(0)
        waitUntil { pair.server.snapshot().failure != null }
        assertEquals(expected, pair.server.snapshot().failure?.reason)
      }
    }

    rejected(V9ErrorCode.CORRELATION_ERROR) { pair ->
      pair.clientChannel.blockWrites()
      pair.serverScheduler.runAt(1_000)
      assertTrue(pair.clientChannel.awaitBlockedWrite(1, TimeUnit.SECONDS))
      val pong = pair.clientChannel.lastMessage(V9MessageType.PONG)
      pair.clientChannel.discardBlockedWrite(keepBlocking = false)
      ByteBuffer.wrap(pong).order(ByteOrder.BIG_ENDIAN).putInt(16, 0x7fffffff)
      pong
    }

    rejected(V9ErrorCode.CORRELATION_ERROR) { pair ->
      pair.clientChannel.blockWrites()
      pair.serverScheduler.runAt(1_000)
      assertTrue(pair.clientChannel.awaitBlockedWrite(1, TimeUnit.SECONDS))
      val pong = pair.clientChannel.lastMessage(V9MessageType.PONG)
      pair.clientChannel.discardBlockedWrite(keepBlocking = false)
      pong[ProtocolV9.HEADER_BYTES] = (pong[ProtocolV9.HEADER_BYTES].toInt() xor 1).toByte()
      val digest = MessageDigest.getInstance("SHA-256")
          .digest(pong.copyOfRange(ProtocolV9.HEADER_BYTES, pong.size))
      System.arraycopy(digest, 0, pong, 32, digest.size)
      digest.fill(0)
      pong
    }

    rejected(V9ErrorCode.CORRELATION_ERROR) { pair ->
      pair.clientChannel.blockWrites()
      pair.serverScheduler.runAt(1_000)
      assertTrue(pair.clientChannel.awaitBlockedWrite(1, TimeUnit.SECONDS))
      val late = pair.clientChannel.lastMessage(V9MessageType.PONG)
      pair.serverScheduler.runAt(2_000)
      pair.serverScheduler.runAt(3_000)
      pair.serverScheduler.runAt(4_000)
      late
    }

    rejected(V9ErrorCode.SEQUENCE_ERROR) { pair ->
      pair.serverScheduler.runAt(1_000)
      val metrics = assertNotNull(pair.server.transportMetricsSource())
      waitUntil { metrics.snapshot().currentRttMicros != null }
      pair.clientChannel.lastMessage(V9MessageType.PONG)
    }
  }

  @Test
  fun realSocketProductionFlowAndConcurrentCheckpointRuntimeCloseDoNotDeadlock() {
    val fixture = stateFixture()
    val pair = manifestPair(fixture.identity)
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val clientApplied = AtomicReference<V9ValidatedCheckpoint?>()
    val serverInputs = LinkedBlockingQueue<V9InputState>()
    val clientInputs = LinkedBlockingQueue<V9InputState>()
    val serverTarget = target(fixture.identities, AtomicReference(), serverInputs)
    val clientTarget = target(fixture.identities, clientApplied, clientInputs)
    val serverGeneration = serverTarget.captureGeneration()
    val serverPlay =
        V9GuestPlayPlan(
            serverTarget,
            serverTarget,
            provider(serverGeneration) { request ->
              if (request.kind == V9CheckpointKind.MACHINE) fixture.v2.copyOf()
              else fixture.v2Session.copyOf()
            },
            V9CheckpointKind.MACHINE,
            0,
            V9SessionIdSource { 0x1020304050607080L },
        )
    val clientPlay =
        V9GuestPlayPlan(
            clientTarget,
            clientTarget,
            initialKind = V9CheckpointKind.MACHINE,
            initialOwnerPlayer = 0,
        )
    val server =
        V9FoundationServer(
            mode = V9LinkMode.NORMAL,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to pair.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to part3Guest())),
            playPlan = V9PlayPlan.server(V9LinkMode.NORMAL, mapOf(1 to serverPlay)),
            diagnosticsOptions =
                V9DiagnosticsOptions(enabled = true, pingCadenceMillis = 1_000,
                    pingTimeoutMillis = 2_000),
        ) { accepted.put(it) }
    var client: V9FoundationConnection? = null
    try {
      server.start()
      val invitation =
          host.createInvitation("127.0.0.1", server.localPort, 1).forClientAuthentication()
      client =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              mode = V9LinkMode.NORMAL,
              invitation = invitation,
              manifestPlan = V9ManifestPlan.client(V9LinkMode.NORMAL, 1, pair.client),
              part3Plan = V9Part3Plan.client(V9LinkMode.NORMAL, 1, part3Guest()),
              playPlan = V9PlayPlan.client(V9LinkMode.NORMAL, 1, clientPlay),
              diagnosticsOptions =
                  V9DiagnosticsOptions(enabled = true, pingCadenceMillis = 1_000,
                      pingTimeoutMillis = 2_000),
          )
      val serverConnection = assertNotNull(accepted.poll(5, TimeUnit.SECONDS))
      assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      serverConnection.submitConsent(41, V9ConsentDecision.APPROVE)
      client.submitConsent(41, V9ConsentDecision.APPROVE)

      val serverActive = assertNotNull(serverConnection.awaitActiveBoundary(10, TimeUnit.SECONDS))
      val clientActive = assertNotNull(client.awaitActiveBoundary(10, TimeUnit.SECONDS))
      assertEquals(9, serverActive.initialFrame)
      assertEquals(serverActive.sessionId, clientActive.sessionId)
      assertEquals(V9LifecycleState.ACTIVE, serverConnection.snapshot().state)
      assertEquals(V9LifecycleState.ACTIVE, client.snapshot().state)
      assertTrue(V9Capability.PING_V1 in serverConnection.negotiatedCapabilities())
      assertTrue(V9Capability.PING_V1 in client.negotiatedCapabilities())
      val serverPingObserved = CountDownLatch(1)
      val clientPingObserved = CountDownLatch(1)
      val serverMetrics = assertNotNull(serverConnection.transportMetricsSource())
      val clientMetrics = assertNotNull(client.transportMetricsSource())
      val serverSubscription = serverMetrics.addListener {
        if (it.currentRttMicros != null) serverPingObserved.countDown()
      }
      val clientSubscription = clientMetrics.addListener {
        if (it.currentRttMicros != null) clientPingObserved.countDown()
      }
      assertTrue(serverPingObserved.await(5, TimeUnit.SECONDS))
      assertTrue(clientPingObserved.await(5, TimeUnit.SECONDS))
      serverSubscription.close()
      clientSubscription.close()
      assertEquals(0, serverMetrics.snapshot().unansweredPings)
      assertEquals(0, clientMetrics.snapshot().unansweredPings)
      assertEquals(9, serverMetrics.snapshot().localFrame)
      assertEquals(9, serverMetrics.snapshot().remoteFrame)
      assertEquals(9, clientMetrics.snapshot().localFrame)
      assertEquals(9, clientMetrics.snapshot().remoteFrame)
      assertTrue(requireNotNull(serverMetrics.snapshot().currentRttMicros) >= 0)
      assertTrue(requireNotNull(clientMetrics.snapshot().currentRttMicros) >= 0)
      val applied = assertNotNull(clientApplied.get())
      assertContentEquals(
          MessageDigest.getInstance("SHA-256").digest(fixture.v2),
          applied.stateDigest(),
      )

      assertFailsWith<IllegalArgumentException> {
        serverConnection.sendCheckpoint(V9CheckpointKind.MACHINE, 0, 10)
      }
      serverConnection.sendCheckpoint(V9CheckpointKind.SESSION, 0, 10)
      waitUntil { clientApplied.get()?.metadata?.frame == 10L }
      assertEquals(V9CheckpointKind.SESSION, assertNotNull(clientApplied.get()).metadata.kind)
      serverConnection.sendCheckpoint(V9CheckpointKind.SESSION, 0, 11)
      waitUntil { clientApplied.get()?.metadata?.frame == 11L }

      serverConnection.sendInput(V9InputState(12, 0, 0x10, 1))
      client.sendInput(V9InputState(12, 1, 0x20, 1))
      assertEquals(0x10, assertNotNull(clientInputs.poll(5, TimeUnit.SECONDS)).buttonMask)
      assertEquals(0x20, assertNotNull(serverInputs.poll(5, TimeUnit.SECONDS)).buttonMask)
      assertNull(serverConnection.snapshot().failure)
      assertNull(client.snapshot().failure)

      val concurrent = Executors.newFixedThreadPool(3)
      val ready = java.util.concurrent.CountDownLatch(3)
      val start = java.util.concurrent.CountDownLatch(1)
      val finished = AtomicInteger()
      fun submit(action: () -> Unit) = concurrent.submit {
        ready.countDown()
        start.await()
        try {
          action()
        } catch (_: IllegalStateException) {
          // Close is allowed to win admission; termination, not a specific winner, is invariant.
        } finally {
          finished.incrementAndGet()
        }
      }
      val calls =
          listOf(
              submit { serverConnection.sendCheckpoint(V9CheckpointKind.SESSION, 0, 12) },
              submit {
                serverConnection.sendControl(
                    V9RuntimeControl(V9RuntimeMessageKind.RESET, 12, 0),
                )
              },
              submit { serverConnection.close() },
          )
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      calls.forEach { it.get(5, TimeUnit.SECONDS) }
      concurrent.shutdownNow()
      assertEquals(3, finished.get())
    } finally {
      client?.close()
      server.close()
      host.close()
      fixture.close()
    }
    waitUntil { server.pendingCandidateCount() == 0 && server.activeConnectionCount() == 0 }
  }

  @Test
  fun realSocketNormalUsesLinkedControllerSafePointForInitialAndActiveResync() {
    val source = linkedControllerFixture(LinkMode.NORMAL, 0, 2)
    val recipient = linkedControllerFixture(LinkMode.NORMAL, 1, 2)
    val sourceTarget = source.controller.createV9Target()
    val recipientTarget = recipient.controller.createV9Target()
    val sourceGeneration = captureGeneration(sourceTarget, source.controller)
    val recipientGeneration = captureGeneration(recipientTarget, recipient.controller)
    val identity = assertNotNull(sourceGeneration.identities[0].identity)
    assertEquals(sourceGeneration.identities, recipientGeneration.identities)
    val pair = manifestPair(identity)
    val frame = source.controller.currentFrame()
    assertEquals(frame, recipient.controller.currentFrame())
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val transport = FragmentingSocketChannel()
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val server =
        V9FoundationServer(
            mode = V9LinkMode.NORMAL,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to pair.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to part3Guest())),
            playPlan =
                V9PlayPlan.server(
                    V9LinkMode.NORMAL,
                    mapOf(
                        1 to
                            V9GuestPlayPlan(
                                sourceTarget,
                                sourceTarget,
                                sourceTarget,
                                V9CheckpointKind.MACHINE,
                                0,
                                V9SessionIdSource { 0x0102030405060708L },
                            ),
                    ),
                ),
        ) { accepted.put(it) }
    var client: V9FoundationConnection? = null
    try {
      server.start()
      val invitation =
          host.createInvitation("127.0.0.1", server.localPort, 1).forClientAuthentication()
      client =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              V9LinkMode.NORMAL,
              emptySet(),
              V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
              invitation,
              V9ManifestPlan.client(V9LinkMode.NORMAL, 1, pair.client),
              V9Part3Plan.client(V9LinkMode.NORMAL, 1, part3Guest()),
              V9PlayPlan.client(
                  V9LinkMode.NORMAL,
                  1,
                  V9GuestPlayPlan(
                      recipientTarget,
                      recipientTarget,
                      initialKind = V9CheckpointKind.MACHINE,
                      initialOwnerPlayer = 0,
                  ),
              ),
          ) { transport }
      val serverConnection = assertNotNull(accepted.poll(5, TimeUnit.SECONDS))
      assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      repeat(3) {
        source.controller.runFrame()
        recipient.controller.runFrame()
      }
      val captureFrame = source.controller.currentFrame()
      val expectedMachineHash = machineHash(source.controller, 0)
      serverConnection.submitConsent(41, V9ConsentDecision.APPROVE)
      client.submitConsent(41, V9ConsentDecision.APPROVE)

      pumpSafePoints(listOf(source.controller, recipient.controller)) {
        serverConnection.activeBoundary() != null && client.activeBoundary() != null
      }
      assertEquals(captureFrame, assertNotNull(serverConnection.activeBoundary()).initialFrame)
      assertContentEquals(expectedMachineHash, machineHash(recipient.controller, 0))
      assertEquals(V9LifecycleState.ACTIVE, serverConnection.snapshot().state)
      assertEquals(V9LifecycleState.ACTIVE, client.snapshot().state)

      val replayEvents = AtomicInteger()
      val replayBus = EventBusImpl().also { bus ->
        bus.register<StateHistory.GameboyJoypadPressEvent> { event ->
          if (event.gameboy == 1 && event.button == Button.A) replayEvents.incrementAndGet()
        }
      }
      source.controller.stateHistory.debugEventBus = replayBus
      val delayedFrame = recipient.controller.currentFrame()
      recipient.eventBus.post(ButtonPressEvent(Button.A))
      recipient.controller.runFrame()
      transport.scheduleWrites()
      client.sendInput(V9InputState(delayedFrame, 1, 0x10, 1))
      waitUntil { transport.blockedWriteCount() >= 1 }
      transport.allowWriteFragments(1)
      waitUntil { transport.blockedWriteCount() >= 2 }
      source.controller.runFrame()
      transport.allowWriteFragments(2)
      waitUntil { transport.blockedWriteCount() >= 4 }
      source.controller.runFrame()
      recipient.controller.runFrame()
      transport.releaseScheduledWrites()
      waitUntil {
        dispatchOnly(source.controller)
        source.controller.stateHistory.captureSnapshot().patches.isNotEmpty()
      }
      source.controller.runFrame()
      while (recipient.controller.currentFrame() < source.controller.currentFrame()) {
        recipient.controller.runFrame()
      }
      assertTrue(replayEvents.get() > 0, "late v9 input must drive controller rollback replay")
      val sourceContinuation = source.controller.captureDetachedState()
      val recipientContinuation = recipient.controller.captureDetachedState()
      assertEquals(sourceContinuation.frame, recipientContinuation.frame)
      sourceContinuation.players.indices.forEach { player ->
        assertEquals(
            sourceContinuation.players[player].session,
            recipientContinuation.players[player].session,
            "late-input continuation differs for player $player",
        )
      }
      assertContentEquals(linkedHash(source.controller), linkedHash(recipient.controller))
      source.controller.stateHistory.debugEventBus = null
      replayBus.close()

      source.controller.runFrame()
      val resyncFrame = source.controller.currentFrame()
      val expectedSessionHash = sessionHash(source.controller, 0)
      serverConnection.sendCheckpoint(V9CheckpointKind.SESSION, 0, resyncFrame)
      pumpSafePoints(listOf(source.controller, recipient.controller)) {
        sessionHash(recipient.controller, 0).contentEquals(expectedSessionHash)
      }
      repeat(StateLimits.NETPLAY_ROLLBACK_FRAMES.toInt() + 2) {
        source.controller.runFrame()
      }
      val staleFrame = assertNotNull(source.controller.stateHistory.oldestFrame()) - 1
      val beforeRejectedInput = source.controller.captureDetachedState()
      client.sendInput(V9InputState(staleFrame, 1, 0, 2))
      pumpSafePoints(listOf(source.controller)) {
        serverConnection.snapshot().failure?.reason == V9ErrorCode.SEQUENCE_ERROR
      }
      assertEquals(beforeRejectedInput, source.controller.captureDetachedState())
      assertEquals(0, sourceTarget.pendingCaptureCount())
      assertEquals(0, recipientTarget.pendingCaptureCount())
    } finally {
      client?.close()
      server.close()
      sourceTarget.disconnected(1)
      recipientTarget.disconnected(0)
      host.close()
      source.close()
      recipient.close()
    }
    waitUntil { server.pendingCandidateCount() == 0 && server.activeConnectionCount() == 0 }
  }

  @Test
  fun cancellingConnectionOwnsPendingTargetGenerationTaskAndLeavesControllerUnchanged() {
    val source = linkedControllerFixture(LinkMode.NORMAL, 0, 2)
    val delegate = source.controller.createV9Target()
    val sourceGeneration = captureGeneration(delegate, source.controller)
    val identity = assertNotNull(sourceGeneration.identities[0].identity)
    val pair = manifestPair(identity)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val targetClosed = AtomicBoolean()
    val target =
        object : V9CheckpointTarget, V9CheckpointProvider, V9GameplayTarget {
          override fun captureGeneration(): V9TargetGeneration {
            entered.countDown()
            try {
              release.await()
            } catch (interrupted: InterruptedException) {
              Thread.currentThread().interrupt()
              throw V9ProtocolException(V9ErrorCode.CANCELLED, 0)
            }
            if (targetClosed.get()) throw V9ProtocolException(V9ErrorCode.CANCELLED, 0)
            return delegate.captureGeneration()
          }

          override fun capture(request: V9CheckpointRequest): V9CapturedCheckpoint =
              delegate.capture(request)

          override fun prepare(
              checkpoint: V9ValidatedCheckpoint,
              generation: V9TargetGeneration,
              completion: V9CheckpointPrepareCompletion,
          ): java.io.Closeable = delegate.prepare(checkpoint, generation, completion)

          override fun input(value: V9InputState, completion: V9GameplayCompletion) =
              delegate.input(value, completion)

          override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) =
              delegate.control(value, completion)

          override fun close() {
            if (targetClosed.compareAndSet(false, true)) {
              delegate.close()
              release.countDown()
            }
          }
        }
    val before = source.controller.captureDetachedState()
    val beforeHistory = source.controller.stateHistory.captureSnapshot()
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val server =
        V9FoundationServer(
            mode = V9LinkMode.NORMAL,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to pair.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to part3Guest())),
            playPlan =
                V9PlayPlan.server(
                    V9LinkMode.NORMAL,
                    mapOf(
                        1 to V9GuestPlayPlan(
                            target,
                            target,
                            target,
                            V9CheckpointKind.MACHINE,
                            0,
                        ),
                    ),
                ),
        ) { accepted.put(it) }
    var client: V9FoundationConnection? = null
    try {
      server.start()
      val invitation =
          host.createInvitation("127.0.0.1", server.localPort, 1).forClientAuthentication()
      client =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              V9LinkMode.NORMAL,
              emptySet(),
              V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
              invitation,
              V9ManifestPlan.client(V9LinkMode.NORMAL, 1, pair.client),
              V9Part3Plan.client(V9LinkMode.NORMAL, 1, part3Guest()),
              V9PlayPlan.client(
                  V9LinkMode.NORMAL,
                  1,
                  target(
                      sourceGeneration.identities,
                      AtomicReference(),
                      LinkedBlockingQueue(),
                  ).let { clientTarget ->
                    V9GuestPlayPlan(
                        clientTarget,
                        clientTarget,
                        initialKind = V9CheckpointKind.MACHINE,
                        initialOwnerPlayer = 0,
                    )
                  },
              ),
          )
      val serverConnection = assertNotNull(accepted.poll(5, TimeUnit.SECONDS))
      assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      waitUntil {
        try {
          serverConnection.submitConsent(41, V9ConsentDecision.APPROVE)
          true
        } catch (_: IllegalStateException) {
          false
        }
      }
      waitUntil {
        try {
          client.submitConsent(41, V9ConsentDecision.APPROVE)
          true
        } catch (_: IllegalStateException) {
          false
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertTrue(serverConnection.activeTaskCount() > 0)
      serverConnection.cancel()
      waitUntil {
        serverConnection.activeTaskCount() == 0 && delegate.pendingCaptureCount() == 0
      }
      assertTrue(targetClosed.get())
      assertEquals(V9ErrorCode.CANCELLED, serverConnection.snapshot().failure?.reason)
      assertNull(serverConnection.activeBoundary())
      assertEquals(before, source.controller.captureDetachedState())
      assertEquals(beforeHistory, source.controller.stateHistory.captureSnapshot())
    } finally {
      release.countDown()
      client?.close()
      server.close()
      target.close()
      host.close()
      source.close()
    }
    waitUntil { server.pendingCandidateCount() == 0 && server.activeConnectionCount() == 0 }
  }

  @Test
  fun readyAndActiveDeadlinesAreExactAndOnlyValidatedProgressReanchorsActive() {
    val clock = MutableClock()
    fun clientAtSynchronizing(): V9Lifecycle {
      val value = V9Lifecycle(V9Role.CLIENT, clock)
      value.serverHelloReceived()
      value.clientHelloSent(V9NegotiatedCapabilities(V9Capability.requiredCapabilities))
      value.clientAuthSent()
      value.serverAuthResultReceived()
      value.serverManifestReceived()
      value.clientManifestSent(true)
      value.consentComplete()
      return value
    }

    val ready = clientAtSynchronizing()
    ready.clientStartReceived()
    clock.now = 14_999
    assertNull(ready.checkDeadline())
    clock.now = 15_000
    assertEquals(V9ErrorCode.TIMEOUT, ready.checkDeadline()?.reason)

    clock.now = 0
    val active = clientAtSynchronizing()
    active.clientStartReceived()
    active.clientReadySent()
    clock.now = 29_999
    assertNull(active.checkDeadline())
    active.activeProgress()
    clock.now = 59_998
    assertNull(active.checkDeadline())
    clock.now = 59_999
    assertEquals(V9ErrorCode.TIMEOUT, active.checkDeadline()?.reason)
  }

  @Test
  fun fourPlayerCoordinatorKeepsFailedCandidatesOutsideTheAtomicStartAndReadyBarriers() {
    val generation = targetGeneration(emptyList(), V9LinkMode.FOUR_PLAYER, frame = 9)
    val coordinator = V9FourPlayerCoordinator(provider(generation) { ByteArray(1) })
    val digest = ByteArray(32) { 0x31 }
    val wrong = ByteArray(32) { 0x32 }
    var starts = 0
    val first = coordinator.prepared(1, 9, digest) { starts++ }
    val second = coordinator.prepared(2, 9, digest) { starts++ }
    assertEquals(2, coordinator.candidateCount())
    assertEquals(0, starts)
    assertEquals(
        V9ErrorCode.TOPOLOGY_MISMATCH,
        assertFailsWith<V9ProtocolException> {
          coordinator.prepared(3, 9, wrong) { starts++ }
        }.reason,
    )
    assertEquals(2, coordinator.candidateCount())
    assertEquals(0, starts)

    val third = coordinator.prepared(3, 9, digest) { starts++ }
    assertEquals(3, starts)
    var active = 0
    coordinator.ready(1) { active++ }
    coordinator.ready(2) { active++ }
    assertEquals(0, active)
    coordinator.ready(3) { active++ }
    assertEquals(3, active)
    third.close()
    assertEquals(2, coordinator.candidateCount())
    val replacement = coordinator.prepared(3, 9, digest) { starts++ }
    assertEquals(4, starts)
    coordinator.ready(3) { active++ }
    assertEquals(4, active)
    first.close()
    second.close()
    replacement.close()
    assertEquals(0, coordinator.candidateCount())
    coordinator.close()
  }

  @Test
  fun fourPlayerCoordinatorFreezesOneAdvancingCaptureAndLetsFailedGuestBeReplaced() {
    val request =
        V9CheckpointRequest(V9CheckpointKind.LINKED_SESSION, 0x0f, 0, 11)
    var captures = 0
    val generation = targetGeneration(emptyList(), V9LinkMode.FOUR_PLAYER, frame = 11)
    val provider =
        provider(generation) {
          captures++
          ByteArray(96) { index -> (captures * 31 + index).toByte() }
        }
    val coordinator = V9FourPlayerCoordinator(provider)
    val firstCapture = coordinator.captureInitial(1, request)
    val secondCapture = coordinator.captureInitial(2, request)
    val failedCapture = coordinator.captureInitial(3, request)
    val firstBytes = firstCapture.copyStateFile()
    val secondBytes = secondCapture.copyStateFile()
    val failedBytes = failedCapture.copyStateFile()
    assertEquals(1, captures)
    assertEquals(1, coordinator.captureCount())
    assertContentEquals(firstBytes, secondBytes)
    assertContentEquals(firstBytes, failedBytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(firstBytes)
    val wrongDigest = digest.copyOf().also { it[0] = (it[0] + 1).toByte() }
    val first = coordinator.prepared(1, 11, digest) {}
    val second = coordinator.prepared(2, 11, digest) {}
    assertEquals(
        V9ErrorCode.TOPOLOGY_MISMATCH,
        assertFailsWith<V9ProtocolException> {
          coordinator.prepared(3, 11, wrongDigest) {}
        }.reason,
    )
    coordinator.abandon(3)
    val replacementCapture = coordinator.captureInitial(3, request)
    val replacementBytes = replacementCapture.copyStateFile()
    assertContentEquals(firstBytes, replacementBytes)
    assertEquals(1, captures)
    val replacement = coordinator.prepared(3, 11, digest) {}
    assertEquals(3, coordinator.candidateCount())
    replacement.close()
    first.close()
    second.close()
    firstCapture.close()
    secondCapture.close()
    failedCapture.close()
    replacementCapture.close()
    firstBytes.fill(0)
    secondBytes.fill(0)
    failedBytes.fill(0)
    replacementBytes.fill(0)
    digest.fill(0)
    wrongDigest.fill(0)
    assertEquals(0, coordinator.candidateCount())
    coordinator.close()
  }

  @Test
  fun fourPlayerCoordinatorRejectsStaleReplacementAndWipesItsOwnedGeneration() {
    val initialGeneration = targetGeneration(emptyList(), V9LinkMode.FOUR_PLAYER, frame = 11)
    val currentGeneration = AtomicReference(initialGeneration)
    val ownedState = ByteArray(96) { index -> (index * 7 + 3).toByte() }
    val provider =
        object : V9CheckpointProvider {
          override fun captureGeneration(): V9TargetGeneration = currentGeneration.get()
          override fun capture(request: V9CheckpointRequest) =
              V9CapturedCheckpoint(initialGeneration, 11, ownedState)
        }
    val coordinator = V9FourPlayerCoordinator(provider)
    val request = V9CheckpointRequest(V9CheckpointKind.LINKED_SESSION, 0x0f, 0, null)
    val captures = (1..3).associateWith { coordinator.captureInitial(it, request) }
    val bytes = captures.getValue(1).copyStateFile()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val registrations =
        (1..3).associateWith { guest -> coordinator.prepared(guest, 11, digest) {} }
    registrations.getValue(3).close()
    currentGeneration.set(
        V9TargetGeneration(
            initialGeneration.id,
            V9LinkMode.FOUR_PLAYER,
            12,
            initialGeneration.identities,
        ),
    )
    assertEquals(
        V9ErrorCode.TOPOLOGY_MISMATCH,
        assertFailsWith<V9ProtocolException> {
          coordinator.captureInitial(3, request)
        }.reason,
    )
    assertEquals(2, coordinator.candidateCount())
    assertEquals(1, coordinator.captureCount())
    captures.values.forEach(V9CapturedCheckpoint::close)
    registrations.getValue(1).close()
    registrations.getValue(2).close()
    bytes.fill(0)
    digest.fill(0)
    coordinator.close()
    assertTrue(ownedState.all { it == 0.toByte() })
  }

  @Test
  fun closingSharedFourPlayerCaptureCancelsProviderWorkerAndEveryWaiter() {
    val source = linkedControllerFixture(LinkMode.FOUR_PLAYER_ADAPTER, 0, 4)
    val target = source.controller.createV9Target()
    val coordinator = V9FourPlayerCoordinator(target)
    val waiter = Executors.newSingleThreadExecutor()
    try {
      val capture = waiter.submit<V9CapturedCheckpoint> {
        coordinator.captureInitial(
            1,
            V9CheckpointRequest(V9CheckpointKind.LINKED_SESSION, 0x0f, 0, null),
        )
      }
      awaitCapture(target)
      assertEquals(1, coordinator.pendingCaptureCount())
      coordinator.close()
      val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
        capture.get(5, TimeUnit.SECONDS)
      }
      assertEquals(
          V9ErrorCode.CANCELLED,
          (failure.cause as V9ProtocolException).reason,
      )
      waitUntil {
        target.pendingCaptureCount() == 0 && coordinator.workerTerminated()
      }
      assertEquals(0, coordinator.pendingCaptureCount())
    } finally {
      coordinator.close()
      waiter.shutdownNow()
      source.close()
    }
  }

  @Test
  fun cancellingOneSharedCaptureWaiterDoesNotCloseHealthyFourPlayerGuests() {
    val generation = targetGeneration(emptyList(), V9LinkMode.FOUR_PLAYER, frame = 13)
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val providerClosed = AtomicBoolean()
    val ownedState = ByteArray(96) { index -> (index * 11 + 5).toByte() }
    val provider =
        object : V9CheckpointProvider {
          override fun captureGeneration(): V9TargetGeneration = generation

          override fun capture(request: V9CheckpointRequest): V9CapturedCheckpoint {
            entered.countDown()
            try {
              release.await()
            } catch (interrupted: InterruptedException) {
              Thread.currentThread().interrupt()
              throw V9ProtocolException(V9ErrorCode.CANCELLED, 0)
            }
            return V9CapturedCheckpoint(generation, generation.observedFrame, ownedState)
          }

          override fun close() {
            providerClosed.set(true)
            release.countDown()
          }
        }
    val coordinator = V9FourPlayerCoordinator(provider)
    val waiters = Executors.newFixedThreadPool(2)
    val request = V9CheckpointRequest(V9CheckpointKind.LINKED_SESSION, 0x0f, 0, null)
    try {
      val cancelled = waiters.submit<V9CapturedCheckpoint> {
        coordinator.captureInitial(1, request)
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val healthy = waiters.submit<V9CapturedCheckpoint> {
        coordinator.captureInitial(2, request)
      }
      waitUntil { coordinator.captureClaimantCount() == 2 }
      assertTrue(cancelled.cancel(true))
      waitUntil { coordinator.captureClaimantCount() == 1 }
      assertFalse(providerClosed.get())
      release.countDown()
      val capture = healthy.get(5, TimeUnit.SECONDS)
      assertContentEquals(ownedState, capture.copyStateFile())
      capture.close()
      assertEquals(1, coordinator.captureClaimantCount())
    } finally {
      coordinator.close()
      waiters.shutdownNow()
    }
    assertTrue(providerClosed.get())
    assertTrue(ownedState.all { it == 0.toByte() })
  }

  @Test
  fun activeInputAndControlUseStableMasksChannelsAndRejectMalformedOrWrongOwners() {
    val input = V9InputState(123, 2, 0xa5, 0xffff)
    val encodedInput = V9GameplayCodec.encodeInput(input)
    assertEquals(input, V9GameplayCodec.decodeInput(encodedInput, 3))
    assertEquals(
        V9ErrorCode.TOPOLOGY_MISMATCH,
        assertFailsWith<V9ProtocolException> {
          V9GameplayCodec.decodeInput(encodedInput, 2)
        }.reason,
    )
    encodedInput[15] = 1
    assertEquals(
        V9ErrorCode.MALFORMED_HEADER,
        assertFailsWith<V9ProtocolException> {
          V9GameplayCodec.decodeInput(encodedInput, 3)
        }.reason,
    )

    val reset = V9RuntimeControl(V9RuntimeMessageKind.RESET, 124, 2)
    val encodedReset = V9GameplayCodec.encodeControl(reset)
    assertEquals(reset, V9GameplayCodec.decodeControl(V9MessageType.RESET, encodedReset, 3))
    assertEquals(
        V9ErrorCode.MALFORMED_HEADER,
        assertFailsWith<V9ProtocolException> {
          V9GameplayCodec.decodeControl(V9MessageType.INPUT, encodedReset, 3)
        }.reason,
    )
    encodedInput.fill(0)
    encodedReset.fill(0)
  }

  @Test
  fun fourPlayerServerRejectsGuestSpoofBeforeTargetAndAcceptsAuthenticatedPlayer() {
    val fixture = linkedStateFixture()
    val pair = fourManifestPair(1, fixture.identities)
    val authorization = fourAuthorization(pair, 1)
    val inputs = LinkedBlockingQueue<V9InputState>()
    val target =
        target(
            fixture.identities,
            AtomicReference(),
            inputs,
            V9LinkMode.FOUR_PLAYER,
            frame = 11,
        )
    val coordinator = V9FourPlayerCoordinator(provider(target.captureGeneration()) { fixture.bytes })
    val failure = AtomicReference<V9ErrorCode?>()
    val session =
        V9PlaySession(
            V9Role.SERVER,
            V9LinkMode.FOUR_PLAYER,
            1,
            authorization,
            V9GuestPlayPlan(
                target,
                target,
                initialKind = V9CheckpointKind.LINKED_SESSION,
                initialOwnerPlayer = 0,
                fourPlayerCoordinator = coordinator,
            ),
            target.captureGeneration(),
            { name, task ->
              Thread(task, name).also {
                it.isDaemon = true
                it.start()
              }
            },
            V9PlaySend { _, _, _, _, _, _, _ -> 1 },
            serverLifecycleAtActive(),
            { reason, _ -> failure.compareAndSet(null, reason) },
            {},
        )
    try {
      runtimeFrame(V9InputState(11, 2, 0x10, 1)).use(session::handle)
      assertEquals(V9ErrorCode.TOPOLOGY_MISMATCH, failure.get())
      assertNull(inputs.poll())

      failure.set(null)
      runtimeFrame(V9InputState(11, 1, 0x10, 1)).use(session::handle)
      assertEquals(V9InputState(11, 1, 0x10, 1), inputs.poll(5, TimeUnit.SECONDS))
      assertNull(failure.get())
    } finally {
      session.close()
      coordinator.close()
      fixture.close()
    }
  }

  @Test
  fun fourPlayerRuntimeRelayHandoffIsBoundedAndOverflowFailsOnlyItsDestination() {
    val fixture = linkedStateFixture()
    val pair = fourManifestPair(1, fixture.identities)
    val target =
        target(
            fixture.identities,
            AtomicReference(),
            LinkedBlockingQueue(),
            V9LinkMode.FOUR_PLAYER,
            frame = 11,
        )
    val coordinator = V9FourPlayerCoordinator(provider(target.captureGeneration()) { fixture.bytes })
    val enteredSend = CountDownLatch(1)
    val releaseSend = CountDownLatch(1)
    val tasks = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    val failure = AtomicReference<V9ErrorCode?>()
    val session =
        V9PlaySession(
            V9Role.SERVER,
            V9LinkMode.FOUR_PLAYER,
            1,
            fourAuthorization(pair, 1),
            V9GuestPlayPlan(
                target,
                target,
                initialKind = V9CheckpointKind.LINKED_SESSION,
                initialOwnerPlayer = 0,
                fourPlayerCoordinator = coordinator,
            ),
            target.captureGeneration(),
            { name, task ->
              Thread(task, name).also {
                it.isDaemon = true
                tasks += it
                it.start()
              }
            },
            V9PlaySend { _, _, _, _, _, onAdmitted, onWritten ->
              onAdmitted(1)
              enteredSend.countDown()
              releaseSend.await()
              onWritten()
              1
            },
            serverLifecycleAtActive(),
            { reason, _ -> failure.compareAndSet(null, reason) },
            {},
        )
    try {
      session.offerRelayedInputForTest(V9InputState(11, 0, 0x10, 1))
      assertTrue(enteredSend.await(5, TimeUnit.SECONDS))
      repeat(V9Limit.QUEUED_FRAMES.value.toInt()) { order ->
        session.offerRelayedInputForTest(V9InputState(11, 0, 0x10, order + 2))
      }
      assertEquals(V9Limit.QUEUED_FRAMES.value.toInt(), session.runtimeRelayQueueSize())
      assertEquals(
          V9ErrorCode.QUEUE_OVERFLOW,
          assertFailsWith<V9ProtocolException> {
            session.offerRelayedInputForTest(V9InputState(11, 0, 0x10, 0xffff))
          }.reason,
      )
      waitUntil { failure.get() == V9ErrorCode.QUEUE_OVERFLOW }
    } finally {
      session.close()
      releaseSend.countDown()
      tasks.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
      coordinator.close()
      fixture.close()
    }
    assertEquals(0, session.runtimeRelayQueueSize())
    assertTrue(tasks.none(Thread::isAlive))
  }

  @Test
  fun realSocketFourPlayerBarrierRequiresOneCoherentLinkedCheckpointBeforeAllGuestsBecomeActive() {
    val fixture = linkedStateFixture()
    val sharedGeneration =
        targetGeneration(fixture.identities, V9LinkMode.FOUR_PLAYER, frame = 11)
    val sharedProvider = provider(sharedGeneration) { fixture.bytes.copyOf() }
    val coordinator = V9FourPlayerCoordinator(sharedProvider)
    val host = V9InvitationHost(V9LinkMode.FOUR_PLAYER)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val pairs = (1..3).associateWith { fourManifestPair(it, fixture.identities) }
    val serverTargets = (1..3).associateWith {
      target(
          fixture.identities,
          AtomicReference(),
          LinkedBlockingQueue(),
          V9LinkMode.FOUR_PLAYER,
          11,
      )
    }
    val serverPlans =
        (1..3).associateWith { guest ->
          V9GuestPlayPlan(
              checkpointTarget = serverTargets.getValue(guest),
              gameplayTarget = serverTargets.getValue(guest),
              initialKind = V9CheckpointKind.LINKED_SESSION,
              initialOwnerPlayer = 0,
              sessionIds = V9SessionIdSource { 0x1122334455667788L },
              fourPlayerCoordinator = coordinator,
          )
        }
    val server =
        V9FoundationServer(
            mode = V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = setOf(V9Capability.FOUR_PLAYER_V1),
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.FOUR_PLAYER,
                    pairs.mapValues { it.value.server },
                ),
            part3Plan =
                V9Part3Plan.server(
                    V9LinkMode.FOUR_PLAYER,
                    (1..3).associateWith { part3Guest() },
                ),
            playPlan = V9PlayPlan.server(V9LinkMode.FOUR_PLAYER, serverPlans),
        ) { accepted.put(it) }
    val clients = mutableListOf<V9FoundationConnection>()
    try {
      server.start()
      (1..3).forEach { guest ->
        val clientTarget =
            target(
                fixture.identities,
                AtomicReference(),
                LinkedBlockingQueue(),
                V9LinkMode.FOUR_PLAYER,
                11,
            )
        val invitation =
            host.createInvitation("127.0.0.1", server.localPort, guest)
                .forClientAuthentication()
        clients +=
            V9FoundationClient.connect(
                InetSocketAddress("127.0.0.1", server.localPort),
                mode = V9LinkMode.FOUR_PLAYER,
                optionalCapabilities = setOf(V9Capability.FOUR_PLAYER_V1),
                invitation = invitation,
                manifestPlan =
                    V9ManifestPlan.client(
                        V9LinkMode.FOUR_PLAYER,
                        guest,
                        pairs.getValue(guest).client,
                    ),
                part3Plan =
                    V9Part3Plan.client(V9LinkMode.FOUR_PLAYER, guest, part3Guest()),
                playPlan =
                    V9PlayPlan.client(
                        V9LinkMode.FOUR_PLAYER,
                        guest,
                        V9GuestPlayPlan(
                            clientTarget,
                            clientTarget,
                            initialKind = V9CheckpointKind.LINKED_SESSION,
                            initialOwnerPlayer = 0,
                        ),
                    ),
            )
      }
      val acceptedConnections =
          List(3) { assertNotNull(accepted.poll(5, TimeUnit.SECONDS)) }
      waitUntil { acceptedConnections.all { it.authenticatedSlot() != null } }
      val serverConnections =
          acceptedConnections.associateBy { assertNotNull(it.authenticatedSlot()) }
      (1..3).forEach { guest ->
        val serverConnection = assertNotNull(serverConnections[guest])
        val client = clients[guest - 1]
        assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
        assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
        serverConnection.submitConsent(40L + guest, V9ConsentDecision.APPROVE)
        client.submitConsent(40L + guest, V9ConsentDecision.APPROVE)
        if (guest < 3) {
          assertNull(serverConnection.awaitActiveBoundary(200, TimeUnit.MILLISECONDS))
          assertTrue(coordinator.candidateCount() < 3)
        }
      }
      val serverActive =
          (1..3).map { guest ->
            assertNotNull(
                serverConnections.getValue(guest).awaitActiveBoundary(10, TimeUnit.SECONDS),
                "four-player server did not activate: " +
                    (serverConnections.values + clients).map { it.snapshot() },
            )
          }
      val clientActive =
          clients.map { assertNotNull(it.awaitActiveBoundary(10, TimeUnit.SECONDS)) }
      assertEquals(setOf(11L), (serverActive + clientActive).map { it.initialFrame }.toSet())
      assertEquals(setOf(0x1122334455667788L), serverActive.map { it.sessionId }.toSet())
      assertTrue((serverConnections.values + clients).all {
        it.snapshot().state == V9LifecycleState.ACTIVE && it.snapshot().failure == null
      })
    } finally {
      clients.forEach { it.close() }
      server.close()
      host.close()
      fixture.close()
    }
    waitUntil { server.pendingCandidateCount() == 0 && server.activeConnectionCount() == 0 }
  }

  @Test
  fun realSocketFourPlayerUsesOneLinkedControllerGenerationAcrossAllGuests() {
    val source = linkedControllerFixture(LinkMode.FOUR_PLAYER_ADAPTER, 0, 4)
    val recipients =
        (1..3).map { guest ->
          linkedControllerFixture(LinkMode.FOUR_PLAYER_ADAPTER, guest, 4)
        }
    val captureTarget = source.controller.createV9Target()
    val serverTargets = (1..3).associateWith { source.controller.createV9Target() }
    val clientTargets = recipients.map { it.controller.createV9Target() }
    val guestOneIngress = CountDownLatch(1)
    val guestOneRelayToTwo = CountDownLatch(1)
    val guestOneRelayToThree = CountDownLatch(1)
    val hostReleaseRelays = CountDownLatch(3)
    val serverGameplayTargets =
        serverTargets.mapValues { (guest, target) ->
          object : V9GameplayTarget {
            override fun input(value: V9InputState, completion: V9GameplayCompletion) {
              target.input(value, completion)
              if (guest == 1 && value.player == 1) guestOneIngress.countDown()
            }

            override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) {
              target.control(value, completion)
            }

            override fun disconnected(player: Int) = target.disconnected(player)
          }
        }
    val clientGameplayTargets =
        clientTargets.mapIndexed { index, target ->
          object : V9GameplayTarget {
            override fun input(value: V9InputState, completion: V9GameplayCompletion) {
              target.input(value, completion)
              if (index == 1 && value.player == 1) guestOneRelayToTwo.countDown()
              if (index == 2 && value.player == 1) guestOneRelayToThree.countDown()
              if (value.player == 0 && value.buttonMask == 0) hostReleaseRelays.countDown()
            }

            override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) {
              target.control(value, completion)
            }

            override fun disconnected(player: Int) = target.disconnected(player)
          }
        }
    val identities = captureGeneration(captureTarget, source.controller).identities
    assertTrue(identities.all { it.identity != null })
    assertTrue(identities.all { it.identity?.profile?.canonicalProfileId != "sgb" })
    assertTrue(clientTargets.zip(recipients).all { (target, recipient) ->
      captureGeneration(target, recipient.controller).identities == identities
    })
    val pairs = (1..3).associateWith { fourManifestPair(it, identities) }
    val expectedHash = linkedHash(source.controller)
    val coordinator = V9FourPlayerCoordinator(captureTarget)
    val host = V9InvitationHost(V9LinkMode.FOUR_PLAYER)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val transports = (1..3).associateWith { FragmentingSocketChannel() }
    val server =
        V9FoundationServer(
            mode = V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = setOf(V9Capability.FOUR_PLAYER_V1),
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.FOUR_PLAYER,
                    pairs.mapValues { it.value.server },
                ),
            part3Plan =
                V9Part3Plan.server(
                    V9LinkMode.FOUR_PLAYER,
                    (1..3).associateWith { part3Guest() },
                ),
            playPlan =
                V9PlayPlan.server(
                    V9LinkMode.FOUR_PLAYER,
                    (1..3).associateWith { guest ->
                      V9GuestPlayPlan(
                          checkpointTarget = serverTargets.getValue(guest),
                          gameplayTarget = serverGameplayTargets.getValue(guest),
                          initialKind = V9CheckpointKind.LINKED_SESSION,
                          initialOwnerPlayer = 0,
                          sessionIds = V9SessionIdSource { 0x1112131415161718L },
                          fourPlayerCoordinator = coordinator,
                      )
                    },
                ),
        ) { accepted.put(it) }
    val serverWriteGates = ConcurrentHashMap<V9FoundationConnection, BlockingWriteChannel>()
    val defaultConnectionFactory = server.connectionFactory
    server.connectionFactory = { channel, role, linkMode, capabilities ->
      val controlled = BlockingWriteChannel(channel)
      defaultConnectionFactory(controlled, role, linkMode, capabilities).also {
        serverWriteGates[it] = controlled
      }
    }
    val clients = mutableListOf<V9FoundationConnection>()
    try {
      server.start()
      val beforeBadCandidate = source.controller.captureDetachedState()
      val beforeBadRecipient = recipients[2].controller.captureDetachedState()
      val badPair = pairs.getValue(3)
      val badInvitation =
          host.createInvitation("127.0.0.1", server.localPort, 3)
              .forClientAuthentication()
      val badClient =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              V9LinkMode.FOUR_PLAYER,
              setOf(V9Capability.FOUR_PLAYER_V1),
              V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
              badInvitation,
              V9ManifestPlan.client(
                  V9LinkMode.FOUR_PLAYER,
                  3,
                  manifestWithProfile(badPair.client, 3, "sgb"),
              ),
              V9Part3Plan.client(V9LinkMode.FOUR_PLAYER, 3, part3Guest()),
              V9PlayPlan.client(
                  V9LinkMode.FOUR_PLAYER,
                  3,
                  V9GuestPlayPlan(
                      clientTargets[2],
                      clientGameplayTargets[2],
                      initialKind = V9CheckpointKind.LINKED_SESSION,
                      initialOwnerPlayer = 0,
                  ),
              ),
          ) { FragmentingSocketChannel() }
      val badServer = assertNotNull(accepted.poll(5, TimeUnit.SECONDS))
      waitUntil {
        badClient.snapshot().failure != null || badServer.snapshot().failure != null ||
            badClient.manifestBoundary() != null || badServer.manifestBoundary() != null
      }
      assertTrue(
          badClient.snapshot().failure?.reason == V9ErrorCode.MANIFEST_MISMATCH ||
              badServer.snapshot().failure?.reason == V9ErrorCode.MANIFEST_MISMATCH,
          "bad candidate was not rejected: client=${badClient.snapshot()} " +
              "server=${badServer.snapshot()}",
      )
      assertEquals(beforeBadCandidate, source.controller.captureDetachedState())
      assertEquals(beforeBadRecipient, recipients[2].controller.captureDetachedState())
      assertEquals(0, coordinator.candidateCount())
      assertEquals(0, coordinator.captureCount())
      badClient.close()
      badServer.close()
      waitUntil { server.activeConnectionCount() == 0 }

      (1..3).forEach { guest ->
        val invitation =
            host.createInvitation("127.0.0.1", server.localPort, guest)
                .forClientAuthentication()
        clients +=
            V9FoundationClient.connect(
                InetSocketAddress("127.0.0.1", server.localPort),
                V9LinkMode.FOUR_PLAYER,
                setOf(V9Capability.FOUR_PLAYER_V1),
                V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
                invitation,
                V9ManifestPlan.client(
                    V9LinkMode.FOUR_PLAYER,
                    guest,
                    pairs.getValue(guest).client,
                ),
                V9Part3Plan.client(V9LinkMode.FOUR_PLAYER, guest, part3Guest()),
                V9PlayPlan.client(
                    V9LinkMode.FOUR_PLAYER,
                    guest,
                    V9GuestPlayPlan(
                        clientTargets[guest - 1],
                        clientGameplayTargets[guest - 1],
                        initialKind = V9CheckpointKind.LINKED_SESSION,
                        initialOwnerPlayer = 0,
                    ),
                ),
            ) { transports.getValue(guest) }
      }
      val acceptedConnections =
          List(3) { assertNotNull(accepted.poll(5, TimeUnit.SECONDS)) }
      waitUntil { acceptedConnections.all { it.authenticatedSlot() != null } }
      val serverConnections =
          acceptedConnections.associateBy { assertNotNull(it.authenticatedSlot()) }
      (1..3).forEach { guest ->
        val serverConnection = serverConnections.getValue(guest)
        val client = clients[guest - 1]
        assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
        assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
        serverConnection.submitConsent(40L + guest, V9ConsentDecision.APPROVE)
        client.submitConsent(40L + guest, V9ConsentDecision.APPROVE)
      }
      pumpSafePoints(
          listOf(source.controller) + recipients.map { it.controller },
          failureDetail = {
            " snapshots=" + (serverConnections.values + clients).map { it.snapshot() } +
                " capture=${coordinator.pendingCaptureCount()}/${coordinator.captureCount()}" +
                " targets=" + listOf(captureTarget.pendingCaptureCount()) +
                serverTargets.values.map { it.pendingCaptureCount() } +
                clientTargets.map { it.pendingCaptureCount() }
          },
      ) {
        serverConnections.values.all { it.activeBoundary() != null } &&
            clients.all { it.activeBoundary() != null }
      }
      assertEquals(1, coordinator.captureCount())
      recipients.forEach { assertContentEquals(expectedHash, linkedHash(it.controller)) }
      assertTrue((serverConnections.values + clients).all {
        it.snapshot().state == V9LifecycleState.ACTIVE && it.snapshot().failure == null
      })
      assertEquals(3, coordinator.activeRuntimeCount())

      // A host-player-0 frame occupies guest 3's deliberately blocked server writer. Guest 1's
      // later input is independently fragmented while the host and other replicas advance. The
      // host safe-point callback must never wait for guest 3's channel-write lock: it applies and
      // returns, guest 2 receives through the bounded relay handoff, and guest 3 catches up only
      // after its gate is released.
      val replayControllers =
          listOf(source.controller, recipients[1].controller, recipients[2].controller)
      val replayBuses = replayControllers.map { controller ->
        val replayed = AtomicInteger()
        val bus = EventBusImpl().also { debug ->
          debug.register<StateHistory.GameboyJoypadPressEvent> { event ->
            if (event.gameboy == 1 && event.button == Button.A) replayed.incrementAndGet()
          }
        }
        controller.stateHistory.debugEventBus = bus
        replayed to bus
      }
      val blockedServer = serverConnections.getValue(3)
      val blockedWrite = serverWriteGates.getValue(blockedServer)
      val hostInputFrame = source.controller.currentFrame()
      source.eventBus.post(ButtonPressEvent(Button.B))
      source.controller.runFrame()
      blockedWrite.scheduleWrites()
      serverConnections.getValue(1)
          .sendInput(V9InputState(hostInputFrame, 0, 0x20, 1))
      assertTrue(blockedWrite.awaitBlockedWrite(5, TimeUnit.SECONDS))

      val delayedFrame = recipients[0].controller.currentFrame()
      recipients[0].eventBus.post(ButtonPressEvent(Button.A))
      recipients[0].controller.runFrame()
      transports.getValue(1).scheduleWrites()
      clients[0].sendInput(V9InputState(delayedFrame, 1, 0x10, 1))
      waitUntil { transports.getValue(1).blockedWriteCount() >= 1 }
      transports.getValue(1).allowWriteFragments(1)
      waitUntil { transports.getValue(1).blockedWriteCount() >= 2 }
      replayControllers.forEach(LinkedController::runFrame)
      transports.getValue(1).allowWriteFragments(2)
      waitUntil { transports.getValue(1).blockedWriteCount() >= 4 }
      replayControllers.forEach(LinkedController::runFrame)
      transports.getValue(1).releaseScheduledWrites()
      assertTrue(guestOneIngress.await(5, TimeUnit.SECONDS))

      val hostSafePointReturned = CountDownLatch(1)
      val hostSafePointFailure = AtomicReference<Throwable?>()
      val hostSafePoint =
          Thread(
              {
                try {
                  source.controller.runFrame()
                } catch (problem: Throwable) {
                  hostSafePointFailure.set(problem)
                } finally {
                  hostSafePointReturned.countDown()
                }
              },
              "v9-four-player-host-safe-point",
          ).also { it.isDaemon = true }
      hostSafePoint.start()
      try {
        assertTrue(
            hostSafePointReturned.await(1, TimeUnit.SECONDS),
            "host safe point waited for the blocked guest-3 socket write",
        )
        assertNull(hostSafePointFailure.get())
        assertTrue(blockedWrite.blockedWriteCount() >= 1)
        assertTrue(guestOneRelayToTwo.await(5, TimeUnit.SECONDS))
        recipients[1].controller.runFrame()
        assertTrue(replayBuses[1].first.get() > 0)
        assertEquals(V9LifecycleState.ACTIVE, serverConnections.getValue(2).snapshot().state)
        assertNull(serverConnections.getValue(2).snapshot().failure)
      } finally {
        blockedWrite.releaseScheduledWrites()
        hostSafePoint.join(TimeUnit.SECONDS.toMillis(5))
      }
      assertTrue(guestOneRelayToThree.await(5, TimeUnit.SECONDS))
      recipients[2].controller.runFrame()
      assertTrue(
          replayBuses.all { it.first.get() > 0 },
          "late guest input must replay on host and every non-origin replica",
      )
      val allControllers = listOf(source.controller) + recipients.map { it.controller }
      val continuationFrame = allControllers.maxOf { it.currentFrame() }
      allControllers.forEach { controller ->
        while (controller.currentFrame() < continuationFrame) controller.runFrame()
      }
      assertEquals(1, allControllers.map { it.currentFrame() }.toSet().size)
      val continuationHash = linkedHash(source.controller)
      recipients.forEach { assertContentEquals(continuationHash, linkedHash(it.controller)) }
      waitUntil {
        blockedServer.runtimeRelayQueueSizeForTest() == 0 &&
            blockedServer.writerQueueSnapshot() == V9QueueSnapshot(0, 0, 0)
      }
      replayControllers.forEach { it.stateHistory.debugEventBus = null }
      replayBuses.forEach { it.second.close() }

      assertFailsWith<IllegalArgumentException> {
        clients[0].sendInput(V9InputState(continuationFrame, 2, 0x10, 2))
      }

      // Host-player-0 control uses the same production fan-out. Every active guest observes the
      // logical 0x0f topology with a null physical port, then the reset hot-plugs the session and
      // restores complete linked-state convergence.
      val hostReleaseFrame = source.controller.currentFrame()
      source.eventBus.post(ButtonReleaseEvent(Button.B))
      source.controller.runFrame()
      serverConnections.getValue(1)
          .sendInput(V9InputState(hostReleaseFrame, 0, 0, 1))
      assertTrue(hostReleaseRelays.await(5, TimeUnit.SECONDS))
      recipients.forEach { it.controller.runFrame() }
      val releaseContinuation = allControllers.maxOf { it.currentFrame() }
      allControllers.forEach { controller ->
        while (controller.currentFrame() < releaseContinuation) controller.runFrame()
      }
      val releaseHash = linkedHash(source.controller)
      recipients.forEach { assertContentEquals(releaseHash, linkedHash(it.controller)) }

      val hostStopFrame = source.controller.currentFrame()
      source.eventBus.post(Controller.StopEmulationEvent())
      dispatchOnly(source.controller)
      serverConnections.getValue(1)
          .sendControl(V9RuntimeControl(V9RuntimeMessageKind.STOP, hostStopFrame, 0))
      pumpSafePoints(recipients.map { it.controller }) {
        recipients.all { it.controller.activeSessionCount() == 3 }
      }
      allControllers.forEach { controller ->
        assertNull(controller.captureDetachedState().players[0].session)
        assertEquals(
            LinkedTopologyState.FOUR_PLAYER_ADAPTER,
            controller.captureDetachedState().topology,
        )
      }
      val hostResetFrame = source.controller.currentFrame()
      source.eventBus.post(Controller.ResetEmulationEvent())
      dispatchOnly(source.controller)
      serverConnections.getValue(1)
          .sendControl(V9RuntimeControl(V9RuntimeMessageKind.RESET, hostResetFrame, 0))
      pumpSafePoints(recipients.map { it.controller }) {
        recipients.all { it.controller.activeSessionCount() == 4 }
      }
      val hostResetContinuation = allControllers.maxOf { it.currentFrame() }
      allControllers.forEach { controller ->
        while (controller.currentFrame() < hostResetContinuation) controller.runFrame()
        assertNotNull(controller.captureDetachedState().players[0].session)
      }
      val hostResetHash = linkedHash(source.controller)
      recipients.forEach { assertContentEquals(hostResetHash, linkedHash(it.controller)) }

      // A guest-originated STOP/RESET is performed locally first, then accepted by the host and
      // relayed to the remaining guests. The logical 0x0f topology survives the null physical
      // slot and every replica converges after the hot-plug reset.
      val hotPlugOrigin = recipients[0]
      val stopFrame = hotPlugOrigin.controller.currentFrame()
      hotPlugOrigin.eventBus.post(Controller.StopEmulationEvent())
      hotPlugOrigin.controller.runFrame()
      clients[0].sendControl(V9RuntimeControl(V9RuntimeMessageKind.STOP, stopFrame, 1))
      pumpSafePoints(replayControllers) {
        replayControllers.all { it.activeSessionCount() == 3 }
      }
      allControllers.forEach { controller ->
        assertNull(controller.captureDetachedState().players[1].session)
        assertEquals(
            LinkedTopologyState.FOUR_PLAYER_ADAPTER,
            controller.captureDetachedState().topology,
        )
        assertEquals(4, controller.captureDetachedState().players.size)
      }
      val stoppedFrame = allControllers.maxOf { it.currentFrame() }
      allControllers.forEach { controller ->
        while (controller.currentFrame() < stoppedFrame) controller.runFrame()
      }
      val resetFrame = hotPlugOrigin.controller.currentFrame()
      hotPlugOrigin.eventBus.post(Controller.ResetEmulationEvent())
      hotPlugOrigin.controller.runFrame()
      clients[0].sendControl(V9RuntimeControl(V9RuntimeMessageKind.RESET, resetFrame, 1))
      pumpSafePoints(replayControllers) {
        replayControllers.all { it.activeSessionCount() == 4 }
      }
      val resetContinuation = allControllers.maxOf { it.currentFrame() }
      allControllers.forEach { controller ->
        while (controller.currentFrame() < resetContinuation) controller.runFrame()
        assertNotNull(controller.captureDetachedState().players[1].session)
      }
      val resetHash = linkedHash(source.controller)
      recipients.forEach { assertContentEquals(resetHash, linkedHash(it.controller)) }

      // Closing guest 3 removes only that relay destination. Guest 1 can still reach the host and
      // guest 2; no coordinator lock is held across the downstream send.
      clients[2].close()
      waitUntil {
        coordinator.activeRuntimeCount() == 2 &&
            serverConnections.getValue(3).isClosed() &&
            serverConnections.getValue(3).activeTaskCount() == 0
      }
      assertEquals(0, serverConnections.getValue(3).runtimeRelayQueueSizeForTest())
      assertEquals(V9QueueSnapshot(0, 0, 0), serverConnections.getValue(3).writerQueueSnapshot())
      val isolatedFrame = hotPlugOrigin.controller.currentFrame()
      hotPlugOrigin.eventBus.post(ButtonPressEvent(Button.B))
      hotPlugOrigin.controller.runFrame()
      clients[0].sendInput(V9InputState(isolatedFrame, 1, 0x20, 2))
      pumpSafePoints(listOf(source.controller, recipients[1].controller)) {
        source.controller.stateHistory.captureSnapshot().patches.isNotEmpty() &&
            recipients[1].controller.stateHistory.captureSnapshot().patches.isNotEmpty()
      }
      source.controller.runFrame()
      recipients[1].controller.runFrame()
      assertEquals(V9LifecycleState.ACTIVE, serverConnections.getValue(1).snapshot().state)
      assertEquals(V9LifecycleState.ACTIVE, serverConnections.getValue(2).snapshot().state)
      assertNull(serverConnections.getValue(1).snapshot().failure)
      assertNull(serverConnections.getValue(2).snapshot().failure)
      assertEquals(0, captureTarget.pendingCaptureCount())
      clientTargets.forEach { assertEquals(0, it.pendingCaptureCount()) }
    } finally {
      clients.forEach(V9FoundationConnection::close)
      server.close()
      captureTarget.disconnected(0)
      serverTargets.forEach { (guest, target) -> target.disconnected(guest) }
      clientTargets.forEachIndexed { index, target -> target.disconnected(0) }
      host.close()
      source.close()
      recipients.forEach(ControllerFixture::close)
    }
    waitUntil { server.pendingCandidateCount() == 0 && server.activeConnectionCount() == 0 }
  }

  private fun target(
      identities: List<StateIdentityEntry>,
      checkpoint: AtomicReference<V9ValidatedCheckpoint?>,
      inputs: LinkedBlockingQueue<V9InputState>,
      mode: V9LinkMode = V9LinkMode.NORMAL,
      frame: Long = 9,
  ) = object : V9CheckpointTarget, V9GameplayTarget {
    private val generation = targetGeneration(identities, mode, frame)
    override fun captureGeneration(): V9TargetGeneration = generation
    override fun prepare(
        value: V9ValidatedCheckpoint,
        expectedGeneration: V9TargetGeneration,
        completion: V9CheckpointPrepareCompletion,
    ): java.io.Closeable {
      if (!generation.sameIdentityGeneration(expectedGeneration)) {
        completion.complete(null, V9ErrorCode.TOPOLOGY_MISMATCH)
        return java.io.Closeable {}
      }
      completion.complete(
          object : V9PreparedCheckpoint {
            private var closed = false
            override fun commit(done: V9CheckpointCommitCompletion) {
              if (closed) done.complete(V9ErrorCode.CANCELLED)
              else {
                checkpoint.set(value)
                closed = true
                done.complete(null)
              }
            }
            override fun close() {
              closed = true
            }
          },
          null,
      )
      return java.io.Closeable {}
    }
    override fun input(value: V9InputState, completion: V9GameplayCompletion) {
      inputs.put(value)
      completion.complete(null)
    }
    override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) {
      completion.complete(null)
    }
  }

  private fun targetGeneration(
      identities: List<StateIdentityEntry>,
      mode: V9LinkMode = V9LinkMode.NORMAL,
      frame: Long = 9,
      id: Long = 1,
  ) = V9TargetGeneration(id, mode, frame, identities)

  private fun provider(
      generation: V9TargetGeneration,
      capture: (V9CheckpointRequest) -> ByteArray,
  ): V9CheckpointProvider = object : V9CheckpointProvider {
    override fun captureGeneration(): V9TargetGeneration = generation
    override fun capture(request: V9CheckpointRequest): V9CapturedCheckpoint {
      val frame = request.frame ?: generation.observedFrame
      return V9CapturedCheckpoint(
          V9TargetGeneration(generation.id, generation.mode, frame, generation.identities),
          frame,
          capture(request),
      )
    }
  }

  private fun part3Guest() =
      V9GuestPart3Plan(emptyMap(), V9BulkCandidateSink { it.close() })

  private fun serverLifecycleAtSynchronizing(): V9Lifecycle =
      V9Lifecycle(V9Role.SERVER).also {
        it.serverHelloSent()
        it.clientHelloReceived(V9NegotiatedCapabilities(V9Capability.requiredCapabilities))
        it.clientAuthReceived()
        it.serverAuthResultSent()
        it.serverManifestSent()
        it.clientManifestReceived(true)
        it.consentComplete()
      }

  private fun clientLifecycleAtActive(): V9Lifecycle =
      V9Lifecycle(V9Role.CLIENT).also {
        it.serverHelloReceived()
        it.clientHelloSent(V9NegotiatedCapabilities(V9Capability.requiredCapabilities))
        it.clientAuthSent()
        it.serverAuthResultReceived()
        it.serverManifestReceived()
        it.clientManifestSent(true)
        it.consentComplete()
        it.clientStartReceived()
        it.clientReadySent()
      }

  private fun serverLifecycleAtActive(): V9Lifecycle =
      serverLifecycleAtSynchronizing().also {
        it.serverStartSent()
        it.serverReadyReceived()
      }

  private fun runtimeFrame(value: V9InputState): V9Frame {
    val payload = V9GameplayCodec.encodeInput(value)
    return V9Frame(
        V9FrameHeader(
            V9MessageType.INPUT.wireId,
            V9MessageType.INPUT,
            0,
            0,
            1,
            payload.size.toLong(),
            payload.size.toLong(),
            value.player.toLong() + 1,
            ByteArray(32),
        ),
        payload,
    )
  }

  private fun checkpointDeclaration(
      metadata: V9CheckpointMetadata,
      bytes: ByteArray,
  ) = V9CheckpointDeclaration(
      metadata,
      bytes.size,
      MessageDigest.getInstance("SHA-256").digest(bytes),
  )

  private fun stateFixture(): StateFixture {
    val configuration = StateCodecTestSupport.configuration()
    val session = StateCodecTestSupport.session(configuration)
    repeat(2_048) { session.gameboy.tick() }
    val identity = StateIdentity.from(configuration)
    val identities = listOf(StateIdentityEntry(0, identity), StateIdentityEntry(1, identity))
    val v1 = StateCodec.encode(StateCodec.capture(configuration, session.gameboy), StateCompression.DEFLATE)
    val v2 = StateCodec.encode(StateCodec.captureVersion2(configuration, session.gameboy), StateCompression.DEFLATE)
    val v2Session = StateCodec.encode(StateCodec.captureVersion2(session), StateCompression.DEFLATE)
    return StateFixture(session, identity, identities, v1, v2, v2Session)
  }

  private fun authorization(identity: eu.rekawek.coffeegb.controller.state.MachineIdentity):
      V9CheckpointAuthorization {
    val pair = manifestPair(identity)
    val serverPayload = V9ManifestCodec.encode(pair.server, context(pair.server, 0))
    val clientPayload = V9ManifestCodec.encode(pair.client, context(pair.client, 1))
    return V9CheckpointAuthorization(
        V9ManifestPairingBoundary(
            V9Role.SERVER,
            V9LinkMode.NORMAL,
            1,
            V9LifecycleState.EXCHANGE_CONSENT,
            pair.server,
            pair.client,
            V9ManifestDigest.sha256(serverPayload),
            V9ManifestDigest.sha256(clientPayload),
            pair.server.differences,
            pair.server.proposals,
        ),
        pair.proposal,
    )
  }

  private fun fourAuthorization(pair: ManifestPair, guest: Int): V9CheckpointAuthorization {
    fun fourContext(manifest: V9Manifest, source: Int) =
        V9ManifestValidationContext(
            V9LinkMode.FOUR_PLAYER,
            guest,
            source,
            if (source == 0) guest else 0,
            9,
            manifest.rosterCommitment(),
            V9Capability.requiredCapabilities + V9Capability.FOUR_PLAYER_V1,
        )
    val serverPayload = V9ManifestCodec.encode(pair.server, fourContext(pair.server, 0))
    val clientPayload = V9ManifestCodec.encode(pair.client, fourContext(pair.client, guest))
    return V9CheckpointAuthorization(
        V9ManifestPairingBoundary(
            V9Role.SERVER,
            V9LinkMode.FOUR_PLAYER,
            guest,
            V9LifecycleState.EXCHANGE_CONSENT,
            pair.server,
            pair.client,
            V9ManifestDigest.sha256(serverPayload),
            V9ManifestDigest.sha256(clientPayload),
            pair.server.differences,
            pair.server.proposals,
        ),
        pair.proposal,
    )
  }

  private fun manifestPair(
      identity: eu.rekawek.coffeegb.controller.state.MachineIdentity,
  ): ManifestPair {
    val entries = (0..1).map { manifestEntry(it, identity) }
    val proposal =
        V9TransferProposal(
            41,
            V9TransferAction.OFFER_BY_SOURCE,
            V9TransferClass.CHECKPOINT,
            V9TransferAsset.CHECKPOINT,
            0xff,
            0,
            1,
            0,
            V9ManifestDigest.zero(),
        )
    fun manifest(sender: Int, includeProposal: Boolean) =
        V9Manifest(
            V9ManifestMode.NORMAL,
            sender,
            1,
            V9ManifestCodec.rosterCommitment(V9LinkMode.NORMAL, 1),
            entries,
            if (includeProposal) listOf(
                V9ManifestDifference(V9ManifestDifferenceCode.CHECKPOINT_SYNC, 1, 41),
            ) else emptyList(),
            if (includeProposal) listOf(proposal) else emptyList(),
        )
    return ManifestPair(manifest(0, true), manifest(1, false), proposal)
  }

  private fun fourManifestPair(
      guest: Int,
      identities: List<StateIdentityEntry>,
  ): ManifestPair {
    val entries = identities.map { manifestEntry(it.player, checkNotNull(it.identity)) }
    val proposal =
        V9TransferProposal(
            40L + guest,
            V9TransferAction.OFFER_BY_SOURCE,
            V9TransferClass.CHECKPOINT,
            V9TransferAsset.CHECKPOINT,
            0xff,
            0,
            guest,
            0,
            V9ManifestDigest.zero(),
        )
    fun manifest(sender: Int, includeProposal: Boolean) =
        V9Manifest(
            V9ManifestMode.FOUR_PLAYER,
            sender,
            9,
            V9ManifestCodec.rosterCommitment(V9LinkMode.FOUR_PLAYER, 9),
            entries,
            if (includeProposal) listOf(
                V9ManifestDifference(
                    V9ManifestDifferenceCode.CHECKPOINT_SYNC,
                    guest,
                    proposal.proposalId,
                ),
            ) else emptyList(),
            if (includeProposal) listOf(proposal) else emptyList(),
        )
    return ManifestPair(manifest(0, true), manifest(guest, false), proposal)
  }

  private fun manifestWithProfile(
      manifest: V9Manifest,
      player: Int,
      profileId: String,
  ): V9Manifest =
      V9Manifest(
          manifest.mode,
          manifest.senderPlayer,
          manifest.rosterGeneration,
          manifest.rosterCommitment(),
          manifest.entries.map { entry ->
            if (entry.player != player) entry
            else V9ManifestEntry(
                entry.player,
                entry.primaryRomPresent,
                entry.slotRomPresent,
                entry.batteryPresent,
                entry.bootstrap,
                entry.accessoryFlags,
                profileId,
                entry.internalTitle,
                entry.cartridgeType,
                entry.mapperFamily,
                entry.primaryRomLength,
                entry.slotRomLength,
                entry.primaryRomSha256(),
                entry.slotRomSha256(),
                entry.bootRomSha256(),
                entry.patchSetSha256(),
            )
          },
          manifest.differences,
          manifest.proposals,
      )

  private fun manifestEntry(
      player: Int,
      identity: eu.rekawek.coffeegb.controller.state.MachineIdentity,
  ): V9ManifestEntry {
    val profile = identity.profile
    val flags =
        (if (profile.mealybugDmgBlob) 1 else 0) or
            (if (profile.codeBreakerRumble) 2 else 0) or
            (if (profile.displaySgbBorder) 4 else 0)
    return V9ManifestEntry(
        player,
        true,
        identity.slotRom != null,
        false,
        V9ManifestBootstrap.SKIP,
        flags,
        profile.canonicalProfileId,
        "SYNTHETIC",
        0,
        V9MapperFamily.ROM_ONLY,
        0x8000,
        if (identity.slotRom == null) 0 else 0x8000,
        V9ManifestDigest(identity.primaryRom.copyBytes()),
        identity.slotRom?.let { V9ManifestDigest(it.copyBytes()) } ?: V9ManifestDigest.zero(),
        V9ManifestDigest.zero(),
        V9ManifestDigest.zero(),
    )
  }

  private fun context(manifest: V9Manifest, source: Int) =
      V9ManifestValidationContext(
          V9LinkMode.NORMAL,
          1,
          source,
          if (source == 0) 1 else 0,
          1,
          manifest.rosterCommitment(),
          V9Capability.requiredCapabilities,
      )

  private fun linkedStateFixture(): LinkedFixture {
    val links = StateHistory.createLinks(LinkMode.FOUR_PLAYER_ADAPTER)
    val buses = (0..3).map { EventBusImpl() }
    val sessions =
        (0..3).map { player ->
          Session(
              StateCodecTestSupport.configuration(),
              buses[player],
              null,
              links.serial[player],
              links.infrared[player],
          )
        }
    repeat(2_048) { sessions.forEach { it.gameboy.tick() } }
    val identities =
        sessions.mapIndexed { player, session ->
          StateIdentityEntry(player, StateIdentity.from(session.config))
        }
    val linked =
        LinkedSessionState(
            11,
            0,
            LinkedTopologyState.FOUR_PLAYER_ADAPTER,
            sessions.mapIndexed { player, session ->
              LinkedPlayerState(player, session.captureDetachedState())
            },
        )
    val bytes =
        StateCodec.encode(
            StateFile(identities, LinkedSessionStateRoot(linked), formatVersion = 2),
            StateCompression.DEFLATE,
        )
    return LinkedFixture(sessions, buses, identities, bytes)
  }

  private fun linkedControllerFixture(
      mode: LinkMode,
      localPlayer: Int,
      activePlayers: Int,
  ): ControllerFixture {
    val bytes = Files.readAllBytes(Path.of("src/test/resources/roms/cpu_instrs.gb"))
    val path = Files.createTempFile("coffee-gb-v9-linked-", ".gb")
    Files.write(path, bytes)
    val eventBus = EventBusImpl()
    val properties = EmulatorProperties()
    val reference = Controller.createGameboyConfig(properties, Rom(bytes.copyOf()))
    val controller =
        LinkedController(eventBus, properties, null, mode, localPlayer).also {
          it.timingTicker.disabled = true
        }
    eventBus.post(LoadRomEvent(path.toFile()))
    controller.runFrame()
    // The local ROM event follows the controller's ownership slot. Populate whichever canonical
    // physical slots remain empty so host and guest fixtures have the same complete shape.
    (0 until activePlayers).filter {
      controller.capturePortableIdentities()[it].identity == null
    }.forEach { player ->
      eventBus.post(
          PeerLoadedGameEvent(
              bytes.copyOf(),
              null,
              null,
              reference.gameboyType,
              reference.bootstrapMode,
              controller.currentFrame(),
              player = player,
          ),
      )
      controller.runFrame()
    }
    assertEquals(activePlayers, controller.activeSessionCount())
    return ControllerFixture(eventBus, controller, path)
  }

  private fun machineHash(controller: LinkedController, player: Int): ByteArray {
    val linked = controller.captureDetachedState()
    val machine = assertNotNull(linked.players[player].session).machine
    val identity = assertNotNull(controller.capturePortableIdentities()[player].identity)
    val bytes =
        StateCodec.encode(
            StateFile(
                listOf(StateIdentityEntry(0, identity)),
                MachineStateRoot(machine),
                formatVersion = 2,
            ),
            StateCompression.DEFLATE,
        )
    return MessageDigest.getInstance("SHA-256").digest(bytes).also { bytes.fill(0) }
  }

  private fun sessionHash(controller: LinkedController, player: Int): ByteArray {
    val linked = controller.captureDetachedState()
    val state = assertNotNull(linked.players[player].session)
    val identity = assertNotNull(controller.capturePortableIdentities()[player].identity)
    val bytes =
        StateCodec.encode(
            StateFile(
                listOf(StateIdentityEntry(0, identity)),
                SessionStateRoot(state),
                formatVersion = 2,
            ),
            StateCompression.DEFLATE,
        )
    return MessageDigest.getInstance("SHA-256").digest(bytes).also { bytes.fill(0) }
  }

  private fun linkedHash(controller: LinkedController): ByteArray {
    val linked = controller.captureDetachedState()
    val normalized =
        LinkedSessionState(linked.frame, 0, linked.topology, linked.players)
    val bytes =
        StateCodec.encode(
            StateFile(
                controller.capturePortableIdentities().take(4),
                LinkedSessionStateRoot(normalized),
                formatVersion = 2,
            ),
            StateCompression.DEFLATE,
        )
    return MessageDigest.getInstance("SHA-256").digest(bytes).also { bytes.fill(0) }
  }

  private fun dispatchOnly(controller: LinkedController) {
    LinkedController::class.java.getDeclaredField("eventQueue").also { field ->
      field.isAccessible = true
      (field.get(controller) as EventQueue).dispatch()
    }
  }

  private fun awaitCapture(target: V9LinkedControllerTarget) {
    waitUntil { target.pendingCaptureCount() == 1 }
  }

  private fun captureGeneration(
      target: V9LinkedControllerTarget,
      controller: LinkedController,
  ): V9TargetGeneration {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      val future = executor.submit<V9TargetGeneration> { target.captureGeneration() }
      awaitCapture(target)
      dispatchOnly(controller)
      future.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  private fun pumpSafePoints(
      controllers: List<LinkedController>,
      timeoutMillis: Long = 10_000,
      failureDetail: () -> String = { "" },
      condition: () -> Boolean,
  ) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition()) {
      controllers.forEach(::dispatchOnly)
      if (System.nanoTime() >= deadline) {
        throw AssertionError("safe-point operation did not finish" + failureDetail())
      }
      Thread.yield()
    }
  }

  private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition()) {
      if (System.nanoTime() >= deadline) throw AssertionError("condition did not become true")
      Thread.yield()
    }
  }

  private fun checkpointHeader(length: Long): ByteArray =
      ByteBuffer.allocate(ProtocolV9.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
          .put(ProtocolV9.MAGIC)
          .put(ProtocolV9.MAJOR.toByte())
          .put(ProtocolV9.MINOR.toByte())
          .putShort(ProtocolV9.HEADER_BYTES.toShort())
          .putShort(V9MessageType.CHECKPOINT.wireId.toShort())
          .putShort(0)
          .putInt(0)
          .putInt(0)
          .putInt(length.toInt())
          .putInt(length.toInt())
          .putInt(1)
          .put(ByteArray(32))
          .array()

  private data class ManifestPair(
      val server: V9Manifest,
      val client: V9Manifest,
      val proposal: V9TransferProposal,
  )

  private data class StateFixture(
      val session: eu.rekawek.coffeegb.controller.Session,
      val identity: eu.rekawek.coffeegb.controller.state.MachineIdentity,
      val identities: List<StateIdentityEntry>,
      val v1: ByteArray,
      val v2: ByteArray,
      val v2Session: ByteArray,
  ) : AutoCloseable {
    override fun close() {
      session.close()
      v1.fill(0)
      v2.fill(0)
      v2Session.fill(0)
    }
  }

  private data class LinkedFixture(
      val sessions: List<Session>,
      val buses: List<EventBusImpl>,
      val identities: List<StateIdentityEntry>,
      val bytes: ByteArray,
  ) : AutoCloseable {
    override fun close() {
      sessions.forEach { it.close() }
      buses.forEach { it.close() }
      bytes.fill(0)
    }
  }

  private data class ControllerFixture(
      val eventBus: EventBusImpl,
      val controller: LinkedController,
      val romPath: Path,
  ) : AutoCloseable {
    override fun close() {
      controller.closeWithState()
      Files.deleteIfExists(romPath)
    }
  }

  private fun diagnosticPair(
      serverDiagnostics: Boolean,
      clientDiagnostics: Boolean,
  ): DiagnosticConnectionPair {
    val fixture = stateFixture()
    val manifests = manifestPair(fixture.identity)
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val channels = DiagnosticMemoryChannel.pair()
    val clock = MutableClock()
    val serverScheduler = DiagnosticManualScheduler(clock)
    val clientScheduler = DiagnosticManualScheduler(clock)
    val serverTarget = target(fixture.identities, AtomicReference(), LinkedBlockingQueue())
    val clientTarget = target(fixture.identities, AtomicReference(), LinkedBlockingQueue())
    val serverGeneration = serverTarget.captureGeneration()
    val serverPlan =
        V9GuestPlayPlan(
            serverTarget,
            serverTarget,
            provider(serverGeneration) { fixture.v2.copyOf() },
            V9CheckpointKind.MACHINE,
            0,
            V9SessionIdSource { 0x0102030405060708L },
        )
    val clientPlan =
        V9GuestPlayPlan(
            clientTarget,
            clientTarget,
            initialKind = V9CheckpointKind.MACHINE,
            initialOwnerPlayer = 0,
        )
    val invitation =
        host.createInvitation("127.0.0.1", 8765, 1).forClientAuthentication()
    val options = { enabled: Boolean ->
      V9DiagnosticsOptions(enabled = enabled, pingCadenceMillis = 1_000,
          pingTimeoutMillis = 3_000)
    }
    val server =
        V9FoundationConnection(
            channels.first,
            V9Role.SERVER,
            V9LinkMode.NORMAL,
            clock = clock,
            scheduler = serverScheduler,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to manifests.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to part3Guest())),
            playPlan = V9PlayPlan.server(V9LinkMode.NORMAL, mapOf(1 to serverPlan)),
            diagnosticsOptions = options(serverDiagnostics),
        )
    val client =
        V9FoundationConnection(
            channels.second,
            V9Role.CLIENT,
            V9LinkMode.NORMAL,
            clock = clock,
            scheduler = clientScheduler,
            clientInvitation = invitation,
            manifestPlan = V9ManifestPlan.client(V9LinkMode.NORMAL, 1, manifests.client),
            part3Plan = V9Part3Plan.client(V9LinkMode.NORMAL, 1, part3Guest()),
            playPlan = V9PlayPlan.client(V9LinkMode.NORMAL, 1, clientPlan),
            diagnosticsOptions = options(clientDiagnostics),
        )
    val result =
        DiagnosticConnectionPair(
            fixture,
            host,
            channels.first,
            channels.second,
            clock,
            serverScheduler,
            clientScheduler,
            server,
            client,
        )
    try {
      server.start()
      client.start()
      assertNotNull(server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      server.submitConsent(41, V9ConsentDecision.APPROVE)
      client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertNotNull(server.awaitActiveBoundary(5, TimeUnit.SECONDS))
      assertNotNull(client.awaitActiveBoundary(5, TimeUnit.SECONDS))
      assertEquals(V9LifecycleState.ACTIVE, server.snapshot().state)
      assertEquals(V9LifecycleState.ACTIVE, client.snapshot().state)
      return result
    } catch (failure: Throwable) {
      result.close()
      throw failure
    }
  }

  private data class DiagnosticConnectionPair(
      val fixture: StateFixture,
      val host: V9InvitationHost,
      val serverChannel: DiagnosticMemoryChannel,
      val clientChannel: DiagnosticMemoryChannel,
      val clock: MutableClock,
      val serverScheduler: DiagnosticManualScheduler,
      val clientScheduler: DiagnosticManualScheduler,
      val server: V9FoundationConnection,
      val client: V9FoundationConnection,
  ) : Closeable {
    override fun close() {
      server.close()
      client.close()
      host.close()
      fixture.close()
    }
  }

  private class DiagnosticManualScheduler(private val clock: MutableClock) :
      V9DeadlineScheduler {
    private val tasks = mutableListOf<Task>()

    @Synchronized
    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
      val task = Task(deadlineMillis, action)
      tasks += task
      return Closeable { task.active.set(false) }
    }

    fun runAt(now: Long) {
      require(now >= clock.now)
      clock.now = now
      while (true) {
        val task = synchronized(this) {
          tasks.firstOrNull { it.active.get() && it.deadline <= clock.now }
        } ?: return
        if (task.active.compareAndSet(true, false)) task.action.run()
      }
    }

    @Synchronized
    fun activeDeadlines(): List<Long> =
        tasks.filter { it.active.get() }.map(Task::deadline).sorted()

    @Synchronized
    fun activeTaskCount(): Int = tasks.count { it.active.get() }

    private data class Task(
        val deadline: Long,
        val action: Runnable,
        val active: AtomicBoolean = AtomicBoolean(true),
    )
  }

  private class DiagnosticMemoryChannel : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    private val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
    private val gateDecisions = LinkedBlockingQueue<Boolean>()
    private val closed = AtomicBoolean(false)
    private val blockedWrites = AtomicInteger()
    private val completedBlocked = AtomicInteger()
    @Volatile private var blocking = false
    @Volatile private var blocked = CountDownLatch(0)
    private lateinit var peer: DiagnosticMemoryChannel

    fun blockWrites() {
      blocked = CountDownLatch(1)
      blocking = true
    }

    fun awaitBlockedWrite(timeout: Long, unit: TimeUnit): Boolean = blocked.await(timeout, unit)

    fun blockedWriteCount(): Int = blockedWrites.get()

    fun completedBlockedWrites(): Int = completedBlocked.get()

    fun discardBlockedWrite(keepBlocking: Boolean) {
      blocking = keepBlocking
      gateDecisions.offer(false)
    }

    fun injectToPeer(bytes: ByteArray) {
      bytes.forEach { peer.incoming.put(it.toInt() and 0xff) }
    }

    fun messageCount(type: V9MessageType): Int = synchronized(writes) {
      writes.count { messageType(it) == type }
    }

    fun lastMessage(type: V9MessageType): ByteArray = synchronized(writes) {
      requireNotNull(writes.lastOrNull { messageType(it) == type }).copyOf()
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      val first = incoming.take()
      if (first < 0) return -1
      bytes[offset] = first.toByte()
      var count = 1
      while (count < length) {
        val next = incoming.poll() ?: break
        if (next < 0) {
          incoming.offer(next)
          break
        }
        bytes[offset + count++] = next.toByte()
      }
      return count
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      if (closed.get()) throw IOException("closed")
      val owned = bytes.copyOfRange(offset, offset + length)
      writes += owned
      if (blocking) {
        blockedWrites.incrementAndGet()
        blocked.countDown()
        val deliver = try {
          gateDecisions.take()
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("diagnostic write gate was cancelled", interrupted)
        } finally {
          completedBlocked.incrementAndGet()
        }
        if (!deliver) return length
      }
      injectToPeer(owned)
      return length
    }

    override fun shutdownOutput() {
      peer.incoming.offer(-1)
    }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      blocking = false
      repeat(4) { gateDecisions.offer(false) }
      incoming.offer(-1)
      if (::peer.isInitialized) peer.incoming.offer(-1)
      synchronized(writes) {
        writes.forEach { it.fill(0) }
        writes.clear()
      }
    }

    private fun messageType(bytes: ByteArray): V9MessageType? {
      if (bytes.size < ProtocolV9.HEADER_BYTES ||
          !bytes.copyOfRange(0, 4).contentEquals(ProtocolV9.MAGIC)) return null
      val id = ((bytes[8].toInt() and 0xff) shl 8) or (bytes[9].toInt() and 0xff)
      return V9MessageType.fromWireId(id)
    }

    companion object {
      fun pair(): Pair<DiagnosticMemoryChannel, DiagnosticMemoryChannel> {
        val first = DiagnosticMemoryChannel()
        val second = DiagnosticMemoryChannel()
        first.peer = second
        second.peer = first
        return first to second
      }
    }
  }

  /** Deterministic transport scheduling: fragments may be explicitly released without sleeping. */
  private class FragmentingSocketChannel : V9ConnectableChannel {
    private val delegate = V9SocketChannel(Socket())
    private val fragments = intArrayOf(1, 3, 17, 5, 64, 2, 31)
    private val scheduledWrites = AtomicBoolean()
    private val writePermits = Semaphore(0)
    private val blockedWrites = AtomicInteger()
    private var readIndex = 0
    private var writeIndex = 0

    fun scheduleWrites() {
      check(scheduledWrites.compareAndSet(false, true))
      blockedWrites.set(0)
      writePermits.drainPermits()
    }

    fun blockedWriteCount(): Int = blockedWrites.get()

    fun allowWriteFragments(count: Int) {
      require(count > 0)
      writePermits.release(count)
    }

    fun releaseScheduledWrites() {
      scheduledWrites.set(false)
      writePermits.release(1_024)
    }

    override fun connect(address: InetSocketAddress, timeoutMillis: Int) =
        delegate.connect(address, timeoutMillis)

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      val result = delegate.read(bytes, offset, minOf(length, fragments[readIndex++ % fragments.size]))
      Thread.yield()
      return result
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      if (scheduledWrites.get()) {
        blockedWrites.incrementAndGet()
        try {
          writePermits.acquire()
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("scheduled write was cancelled", interrupted)
        }
      }
      val result = delegate.write(bytes, offset, minOf(length, fragments[writeIndex++ % fragments.size]))
      Thread.yield()
      return result
    }

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun close() {
      releaseScheduledWrites()
      delegate.close()
    }
  }

  /** Server-side write gate used to hold a real TCP destination without blocking its reader. */
  private class BlockingWriteChannel(
      private val delegate: V9TransportChannel,
  ) : V9TransportChannel {
    private val scheduledWrites = AtomicBoolean()
    private val writePermits = Semaphore(0)
    private val blockedWrites = AtomicInteger()
    private val firstBlockedWrite = CountDownLatch(1)

    fun scheduleWrites() {
      check(scheduledWrites.compareAndSet(false, true))
      blockedWrites.set(0)
      writePermits.drainPermits()
    }

    fun awaitBlockedWrite(timeout: Long, unit: TimeUnit): Boolean =
        firstBlockedWrite.await(timeout, unit)

    fun blockedWriteCount(): Int = blockedWrites.get()

    fun releaseScheduledWrites() {
      scheduledWrites.set(false)
      writePermits.release(1_024)
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        delegate.read(bytes, offset, length)

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      if (scheduledWrites.get()) {
        blockedWrites.incrementAndGet()
        firstBlockedWrite.countDown()
        try {
          writePermits.acquire()
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("scheduled server write was cancelled", interrupted)
        }
      }
      return delegate.write(bytes, offset, length)
    }

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun close() {
      releaseScheduledWrites()
      delegate.close()
    }
  }

  private class MutableClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }
}
