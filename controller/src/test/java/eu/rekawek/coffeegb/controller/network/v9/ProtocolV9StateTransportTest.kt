package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.state.RomIdentity
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.StateHistory
import eu.rekawek.coffeegb.controller.state.LinkedPlayerState
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.LinkedSessionStateRoot
import eu.rekawek.coffeegb.controller.state.LinkedTopologyState
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9StateTransportTest {

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
  fun realSocketAuthManifestConsentCheckpointStartReadyAndActiveInputAreProductionIntegrated() {
    val fixture = stateFixture()
    val pair = manifestPair(fixture.identity)
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val clientApplied = AtomicReference<V9ValidatedCheckpoint?>()
    val serverInputs = LinkedBlockingQueue<V9InputState>()
    val clientInputs = LinkedBlockingQueue<V9InputState>()
    val serverTarget = target(fixture.identities, AtomicReference(), serverInputs)
    val clientTarget = target(fixture.identities, clientApplied, clientInputs)
    val serverPlay =
        V9GuestPlayPlan(
            serverTarget,
            serverTarget,
            V9CheckpointProvider { request ->
              if (request.kind == V9CheckpointKind.MACHINE) fixture.v2.copyOf()
              else fixture.v2Session.copyOf()
            },
            V9CheckpointKind.MACHINE,
            0,
            9,
            V9SessionIdSource { 0x1020304050607080L },
        )
    val clientPlay =
        V9GuestPlayPlan(
            clientTarget,
            clientTarget,
            initialKind = V9CheckpointKind.MACHINE,
            initialOwnerPlayer = 0,
            initialFrame = 9,
        )
    val server =
        V9FoundationServer(
            mode = V9LinkMode.NORMAL,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to pair.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to part3Guest())),
            playPlan = V9PlayPlan.server(V9LinkMode.NORMAL, mapOf(1 to serverPlay)),
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
    } finally {
      client?.close()
      server.close()
      host.close()
      fixture.close()
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
    val coordinator = V9FourPlayerCoordinator()
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
  fun realSocketFourPlayerBarrierRequiresOneCoherentLinkedCheckpointBeforeAllGuestsBecomeActive() {
    val fixture = linkedStateFixture()
    val coordinator = V9FourPlayerCoordinator()
    val host = V9InvitationHost(V9LinkMode.FOUR_PLAYER)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val pairs = (1..3).associateWith { fourManifestPair(it, fixture.identities) }
    val serverTargets = (1..3).associateWith {
      target(fixture.identities, AtomicReference(), LinkedBlockingQueue())
    }
    val serverPlans =
        (1..3).associateWith { guest ->
          V9GuestPlayPlan(
              serverTargets.getValue(guest),
              serverTargets.getValue(guest),
              V9CheckpointProvider { fixture.bytes.copyOf() },
              V9CheckpointKind.LINKED_SESSION,
              0,
              11,
              V9SessionIdSource { 0x1122334455667788L },
              coordinator,
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
        val clientTarget = target(fixture.identities, AtomicReference(), LinkedBlockingQueue())
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
                            initialFrame = 11,
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
            assertNotNull(serverConnections.getValue(guest).awaitActiveBoundary(10, TimeUnit.SECONDS))
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

  private fun target(
      identities: List<StateIdentityEntry>,
      checkpoint: AtomicReference<V9ValidatedCheckpoint?>,
      inputs: LinkedBlockingQueue<V9InputState>,
  ) = object : V9CheckpointTarget, V9GameplayTarget {
    override fun expectedIdentities(): List<StateIdentityEntry> = identities
    override fun prepare(value: V9ValidatedCheckpoint, completion: V9CheckpointPrepareCompletion) {
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
    }
    override fun input(value: V9InputState, completion: V9GameplayCompletion) {
      inputs.put(value)
      completion.complete(null)
    }
    override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) {
      completion.complete(null)
    }
  }

  private fun part3Guest() =
      V9GuestPart3Plan(emptyMap(), V9BulkCandidateSink { it.close() })

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

  private class MutableClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }
}
