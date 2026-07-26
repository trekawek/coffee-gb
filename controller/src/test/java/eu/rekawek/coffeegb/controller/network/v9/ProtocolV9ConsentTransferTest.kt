package eu.rekawek.coffeegb.controller.network.v9

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9ConsentTransferTest {

  @Test
  fun immediateConsentWaitsForTheFinalManifestWriteTransition() {
    val bytes = privateBytes(2_113)
    val manifestGate = FrameWriteGate(V9MessageType.MANIFEST)
    val fixture =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = Int.MAX_VALUE,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
            clientWriteGate = manifestGate,
        )
    try {
      fixture.start()
      assertTrue(manifestGate.entered.await(5, TimeUnit.SECONDS))
      assertNull(fixture.client.manifestBoundary())
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))

      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil { fixture.clientChannel.hasReadType(V9MessageType.CONSENT) }
      assertFalse(fixture.client.isClosed(), fixture.client.snapshot().toString())
      assertNull(fixture.client.part3Progress(), "CONSENT must wait behind MANIFEST completion")

      manifestGate.release.countDown()
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      waitUntil {
        fixture.client.part3Progress()?.items?.single()?.state ==
            V9ConsentItemState.WAITING_FOR_LOCAL_DECISION
      }
      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertNotNull(fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
    } finally {
      manifestGate.release.countDown()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun immediateBeginWaitsForTheFinalConsentWriteTransition() {
    val bytes = privateBytes(2_117)
    val consentGate = FrameWriteGate(V9MessageType.CONSENT)
    val fixture =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = Int.MAX_VALUE,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
            clientWriteGate = consentGate,
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil {
        fixture.client.part3Progress()?.items?.single()?.state ==
            V9ConsentItemState.WAITING_FOR_LOCAL_DECISION
      }

      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertTrue(consentGate.entered.await(5, TimeUnit.SECONDS))
      waitUntil { fixture.clientChannel.hasReadType(V9MessageType.ROM_BEGIN) }
      assertFalse(fixture.client.isClosed(), fixture.client.snapshot().toString())
      assertEquals(V9LifecycleState.EXCHANGE_CONSENT, fixture.client.snapshot().state)
      assertNull(fixture.client.preparationBoundary())

      consentGate.release.countDown()
      assertNotNull(fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
    } finally {
      consentGate.release.countDown()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun preSessionProgressHandleRemainsAuthoritativeAfterPromotion() {
    val bytes = privateBytes(2_123)
    val fixture =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = 17,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    val delivered = AtomicInteger()
    val observerFailures = AtomicInteger()
    val handle =
        fixture.client.addPart3ProgressListener {
          delivered.incrementAndGet()
        }
    val faulty =
        fixture.client.addPart3ProgressListener {
          observerFailures.incrementAndGet()
          throw IllegalStateException("synthetic observer failure")
        }
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      waitUntil { delivered.get() > 0 && observerFailures.get() > 0 }
      handle.close()
      val afterUnsubscribe = delivered.get()

      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertNotNull(fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertEquals(afterUnsubscribe, delivered.get())
      assertTrue(observerFailures.get() > 1)
      assertFalse(fixture.client.isClosed(), fixture.client.snapshot().toString())
    } finally {
      handle.close()
      faulty.close()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun closeRacingListenerPromotionInstallsNoPostCloseSubscription() {
    val bytes = privateBytes(2_129)
    val fixture =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = 19,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val callbacks = AtomicInteger()
    val handle =
        fixture.client.addPart3ProgressListener {
          callbacks.incrementAndGet()
          entered.countDown()
          var waiting = true
          while (waiting) {
            try {
              release.await()
              waiting = false
            } catch (_: InterruptedException) {
              // Keep the promotion suspended until the test releases the exact boundary.
            }
          }
        }
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      fixture.client.close()
      assertTrue(fixture.client.isClosed())
      release.countDown()
      waitUntil { fixture.client.activeTaskCount() == 0 }
      handle.close()
      assertEquals(1, callbacks.get())
    } finally {
      release.countDown()
      handle.close()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun consentCodecIsExactDetachedAndRejectsEveryTruncationAndFieldMismatch() {
    val data = privateBytes(257)
    val pair = proposalPair(data)
    val proposal = requireNotNull(pair.proposal)
    val serverPayload = V9ManifestCodec.encode(pair.server, context(pair.server, 0))
    val clientPayload = V9ManifestCodec.encode(pair.client, context(pair.client, 1))
    val serverHash = V9ManifestDigest.sha256(serverPayload)
    val clientHash = V9ManifestDigest.sha256(clientPayload)
    val context =
        V9ConsentValidationContext(1, 0, listOf(proposal), serverHash, clientHash)
    val vote =
        V9ConsentCodec.forProposal(
            proposal,
            0,
            V9ConsentDecision.APPROVE,
            serverHash,
            clientHash,
        )
    val encoded = V9ConsentCodec.encode(vote, context)
    assertEquals(116, encoded.size)
    val decoded = V9ConsentCodec.decode(encoded, context)
    assertEquals(41, decoded.decisionId)
    assertEquals(V9ConsentDecision.APPROVE, decoded.decision)
    assertEquals(V9TransferClass.ROM, decoded.transferClass)
    assertEquals(V9TransferAsset.PRIMARY_ROM, decoded.asset)
    assertFalse(decoded.toString().contains(hex(data)))
    val mutableProgress =
        mutableListOf(
            V9ConsentItemProgress(
                V9ConsentItem.from(proposal),
                V9ConsentItemState.WAITING_FOR_LOCAL_DECISION,
                0,
            ),
        )
    val detachedProgress =
        V9Part3Progress(
            V9LifecycleState.EXCHANGE_CONSENT,
            mutableProgress,
            false,
            null,
        )
    mutableProgress.clear()
    assertEquals(1, detachedProgress.items.size)
    assertFailsWith<UnsupportedOperationException> {
      (detachedProgress.items as MutableList).clear()
    }

    encoded.fill(0)
    assertFalse(decoded.expectedSha256().isZero())
    val valid = V9ConsentCodec.encode(vote, context)
    for (length in 0 until V9ConsentCodec.PAYLOAD_BYTES) {
      assertConsentRejected(valid.copyOf(length), context)
    }
    assertConsentRejected(valid.copyOf(V9ConsentCodec.PAYLOAD_BYTES + 1), context)

    listOf(0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 20, 52, 84).forEach { offset ->
      val malformed = valid.copyOf()
      malformed[offset] = (malformed[offset].toInt() xor 0x7f).toByte()
      assertConsentRejected(malformed, context)
    }
    val wrongActor =
        V9ConsentValidationContext(1, 1, listOf(proposal), serverHash, clientHash)
    assertConsentRejected(valid, wrongActor)
    data.fill(0)
  }

  @Test
  fun bulkCodecsEnforceExactBoundariesAndDetachedData() {
    val digest = digest(privateBytes(1))
    val begin = V9BulkBegin(1, 2, 0, 1, 1, V9TransferAsset.PRIMARY_ROM, 1, digest, 65_536)
    assertEquals(begin, V9BulkCodec.decodeBegin(V9BulkCodec.encodeBegin(begin)))
    listOf(
        V9BulkBegin(
            1,
            2,
            0,
            1,
            1,
            V9TransferAsset.PRIMARY_ROM,
            V9Limit.ROM_BYTES.value,
            digest,
            65_536,
        ),
        V9BulkBegin(
            1,
            2,
            0,
            1,
            1,
            V9TransferAsset.BATTERY,
            V9Limit.BATTERY_BYTES.value,
            digest,
            65_536,
        ),
    ).forEach { assertEquals(it, V9BulkCodec.decodeBegin(V9BulkCodec.encodeBegin(it))) }
    listOf(
        begin.copy(totalDecodedLength = V9Limit.ROM_BYTES.value + 1),
        begin.copy(
            asset = V9TransferAsset.BATTERY,
            totalDecodedLength = V9Limit.BATTERY_BYTES.value + 1,
        ),
        begin.copy(chunkSize = V9Limit.BULK_CHUNK_DECODED_BYTES.value + 1),
    ).forEach {
      assertEquals(
          V9ErrorCode.MALFORMED_HEADER,
          assertFailsWith<V9ProtocolException> { V9BulkCodec.encodeBegin(it) }.reason,
      )
    }
    assertBulkMalformed(ByteArray(V9BulkCodec.BEGIN_BYTES - 1), V9BulkCodec::decodeBegin)
    assertBulkMalformed(ByteArray(V9BulkCodec.BEGIN_BYTES + 1), V9BulkCodec::decodeBegin)

    listOf(1, 65_536).forEach { size ->
      val source = ByteArray(size) { (it and 0xff).toByte() }
      val chunk = V9BulkChunk(1, 0, source)
      source.fill(0)
      val encoded = V9BulkCodec.encodeChunk(chunk)
      val decoded = V9BulkCodec.decodeChunk(encoded)
      assertEquals(size, decoded.data().size)
      encoded.fill(0)
      assertEquals((0 until size).map { (it and 0xff).toByte() }, decoded.data().toList())
      decoded.close()
      assertFailsWith<IllegalStateException> { decoded.data() }
    }
    assertBulkMalformed(ByteArray(8), V9BulkCodec::decodeChunk)
    assertBulkMalformed(ByteArray(65_545), V9BulkCodec::decodeChunk)
    val end = V9BulkEnd(7, digest)
    assertEquals(7, V9BulkCodec.decodeEnd(V9BulkCodec.encodeEnd(end)).transactionId)
    assertBulkMalformed(ByteArray(35), V9BulkCodec::decodeEnd)
    assertBulkMalformed(ByteArray(37), V9BulkCodec::decodeEnd)
  }

  @Test
  fun exactTwoSidedApprovalLazilyTransfersAndStopsAtPreStartBoundary() {
    val bytes = privateBytes(140_321)
    val pair = proposalPair(bytes)
    val opened = AtomicInteger()
    val received = LinkedBlockingQueue<V9CompletedBulkCandidate>()
    val sourcePlan =
        V9GuestPart3Plan(
            mapOf(
                41L to
                    V9BulkSourceProvider {
                      opened.incrementAndGet()
                      ByteArrayBulkSource(bytes)
                    },
            ),
            V9BulkCandidateSink { it.close() },
            compressChunks = true,
            transactionIds = V9TransactionIdSource { 77 },
        )
    val targetPlan =
        V9GuestPart3Plan(
            emptyMap(),
            V9BulkCandidateSink { received.put(it) },
        )
    val fixture =
        connections(
            pair,
            sourcePlan,
            targetPlan,
            maximumWrite = 3,
            capabilities =
                setOf(V9Capability.ROM_TRANSFER_V1, V9Capability.RAW_DEFLATE_V1),
        )
    try {
      fixture.start()
      assertEquals(
          V9LifecycleState.EXCHANGE_CONSENT,
          assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS)).state,
      )
      assertEquals(
          V9LifecycleState.EXCHANGE_CONSENT,
          assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS)).state,
      )
      assertEquals(0, opened.get())
      assertNull(fixture.server.awaitPreparationBoundary(20, TimeUnit.MILLISECONDS))
      assertEquals(
          V9LifecycleState.EXCHANGE_CONSENT,
          fixture.server.part3Progress()?.lifecycle,
          fixture.server.snapshot().toString(),
      )
      assertNull(
          fixture.server.part3HeaderAdmissionForTest(
              V9MessageType.CONSENT,
              0,
              0,
              116,
              116,
          ),
      )
      assertTrue(V9MessageType.CONSENT in fixture.server.configuredDecoderMessagesForTest())

      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil {
        fixture.client.part3Progress()?.items?.single()?.state ==
            V9ConsentItemState.WAITING_FOR_PEER_DECISION
      }
      assertEquals(0, opened.get(), "one vote must not open private content")
      assertTrue(privateWireTypes(fixture.serverChannel.recordedBytes()).isEmpty())
      assertTrue(privateWireTypes(fixture.clientChannel.recordedBytes()).isEmpty())
      assertFalse(fixture.server.isClosed(), fixture.server.snapshot().toString())
      assertEquals(
          V9LifecycleState.EXCHANGE_CONSENT,
          fixture.server.snapshot().state,
          fixture.server.snapshot().toString(),
      )

      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      val serverPrepared = fixture.server.awaitPreparationBoundary(10, TimeUnit.SECONDS)
      val clientPrepared = fixture.client.awaitPreparationBoundary(10, TimeUnit.SECONDS)
      assertNotNull(serverPrepared, fixture.server.snapshot().toString())
      assertNotNull(clientPrepared, fixture.client.snapshot().toString())
      assertEquals(V9LifecycleState.SYNCHRONIZING, serverPrepared.state)
      assertEquals(V9LifecycleState.SYNCHRONIZING, clientPrepared.state)
      assertEquals(1, opened.get())
      val candidate = assertNotNull(received.poll(2, TimeUnit.SECONDS))
      assertContentEquals(bytes, candidate.bytes())
      candidate.close()
      assertFailsWith<IllegalStateException> { candidate.bytes() }
      assertEquals(
          setOf(
              V9MessageType.ROM_BEGIN,
              V9MessageType.ROM_CHUNK,
              V9MessageType.ROM_END,
          ),
          privateWireTypes(fixture.serverChannel.recordedBytes()),
      )
      assertTrue(privateWireTypes(fixture.clientChannel.recordedBytes()).isEmpty())
      assertEquals(V9LifecycleState.SYNCHRONIZING, fixture.server.snapshot().state)
      assertEquals(V9LifecycleState.SYNCHRONIZING, fixture.client.snapshot().state)
      laterUnavailable().forEach { type ->
        assertEquals(
            V9ErrorCode.UNEXPECTED_MESSAGE,
            assertFailsWith<V9ProtocolException> {
              fixture.server.sendUnavailable(type)
            }.reason,
        )
      }
    } finally {
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun realSocketNormalAndFourPlayerSessionsReachOnlyTheVerifiedPreStartBoundary() {
    listOf(V9LinkMode.NORMAL to 1, V9LinkMode.FOUR_PLAYER to 2).forEach { (mode, guest) ->
      val bytes = privateBytes(67_003)
      val pair = proposalPair(mode, guest, bytes)
      val received = LinkedBlockingQueue<V9CompletedBulkCandidate>()
      val accepted = LinkedBlockingQueue<V9FoundationConnection>()
      val host = V9InvitationHost(mode)
      val optional =
          buildSet {
            add(V9Capability.ROM_TRANSFER_V1)
            add(V9Capability.RAW_DEFLATE_V1)
            if (mode == V9LinkMode.FOUR_PLAYER) add(V9Capability.FOUR_PLAYER_V1)
          }
      val server =
          V9FoundationServer(
              mode = mode,
              optionalCapabilities = optional,
              invitationHost = host,
              manifestPlan = V9ManifestPlan.server(mode, mapOf(guest to pair.server)),
              part3Plan =
                  V9Part3Plan.server(
                      mode,
                      mapOf(guest to sourcePlan(bytes, compress = true)),
                  ),
          ) {
            accepted.put(it)
          }
      var client: V9FoundationConnection? = null
      try {
        server.start()
        val invitation =
            host.createInvitation("127.0.0.1", server.localPort, guest)
                .forClientAuthentication()
        client =
            V9FoundationClient.connect(
                InetSocketAddress("127.0.0.1", server.localPort),
                mode = mode,
                optionalCapabilities = optional,
                invitation = invitation,
                manifestPlan = V9ManifestPlan.client(mode, guest, pair.client),
                part3Plan =
                    V9Part3Plan.client(
                        mode,
                        guest,
                        V9GuestPart3Plan(
                            emptyMap(),
                            V9BulkCandidateSink { received.put(it) },
                        ),
                    ),
            )
        val serverConnection = assertNotNull(accepted.poll(5, TimeUnit.SECONDS))
        assertNotNull(serverConnection.awaitManifestBoundary(5, TimeUnit.SECONDS))
        assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
        client.submitConsent(41, V9ConsentDecision.APPROVE)
        serverConnection.submitConsent(41, V9ConsentDecision.APPROVE)
        assertNotNull(serverConnection.awaitPreparationBoundary(10, TimeUnit.SECONDS))
        assertNotNull(client.awaitPreparationBoundary(10, TimeUnit.SECONDS))
        val candidate = assertNotNull(received.poll(2, TimeUnit.SECONDS))
        assertContentEquals(bytes, candidate.bytes())
        candidate.close()
        assertEquals(V9LifecycleState.SYNCHRONIZING, client.snapshot().state)
        assertEquals(V9LifecycleState.SYNCHRONIZING, serverConnection.snapshot().state)
      } finally {
        client?.close()
        server.close()
        host.close()
        bytes.fill(0)
      }
      waitUntil { server.pendingCandidateCount() == 0 }
      waitUntil { server.activeConnectionCount() == 0 }
    }
  }

  @Test
  fun fourPlayerGuestFailureDoesNotCloseOrCorruptAnotherGuestTransaction() {
    val firstBytes = privateBytes(3_001)
    val secondBytes = privateBytes(5_003)
    val firstPair = proposalPair(V9LinkMode.FOUR_PLAYER, 1, firstBytes)
    val secondPair = proposalPair(V9LinkMode.FOUR_PLAYER, 2, secondBytes)
    val host = V9InvitationHost(V9LinkMode.FOUR_PLAYER)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    val received = LinkedBlockingQueue<V9CompletedBulkCandidate>()
    val optional = setOf(V9Capability.FOUR_PLAYER_V1, V9Capability.ROM_TRANSFER_V1)
    val server =
        V9FoundationServer(
            mode = V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = optional,
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.FOUR_PLAYER,
                    mapOf(1 to firstPair.server, 2 to secondPair.server),
                ),
            part3Plan =
                V9Part3Plan.server(
                    V9LinkMode.FOUR_PLAYER,
                    mapOf(1 to sourcePlan(firstBytes), 2 to sourcePlan(secondBytes)),
                ),
        ) {
          accepted.put(it)
        }
    var firstClient: V9FoundationConnection? = null
    var secondClient: V9FoundationConnection? = null
    try {
      server.start()
      fun connect(guest: Int, pair: ManifestPair): V9FoundationConnection {
        val invitation =
            host.createInvitation("127.0.0.1", server.localPort, guest)
                .forClientAuthentication()
        return V9FoundationClient.connect(
            InetSocketAddress("127.0.0.1", server.localPort),
            mode = V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = optional,
            invitation = invitation,
            manifestPlan =
                V9ManifestPlan.client(V9LinkMode.FOUR_PLAYER, guest, pair.client),
            part3Plan =
                V9Part3Plan.client(
                    V9LinkMode.FOUR_PLAYER,
                    guest,
                    V9GuestPart3Plan(
                        emptyMap(),
                        V9BulkCandidateSink { received.put(it) },
                    ),
                ),
        )
      }
      firstClient = connect(1, firstPair)
      secondClient = connect(2, secondPair)
      val acceptedConnections =
          listOf(
              assertNotNull(accepted.poll(5, TimeUnit.SECONDS)),
              assertNotNull(accepted.poll(5, TimeUnit.SECONDS)),
          )
      acceptedConnections.forEach {
        assertNotNull(it.awaitManifestBoundary(5, TimeUnit.SECONDS))
      }
      val serverConnections =
          acceptedConnections.associateBy { assertNotNull(it.authenticatedSlot()) }
      val firstServer = assertNotNull(serverConnections[1])
      val secondServer = assertNotNull(serverConnections[2])
      listOf(firstClient, secondClient).forEach {
        assertNotNull(it.awaitManifestBoundary(5, TimeUnit.SECONDS))
      }

      firstClient.submitConsent(41, V9ConsentDecision.REJECT)
      waitUntil { firstClient.snapshot().state == V9LifecycleState.CLOSED }
      assertEquals(V9ErrorCode.CONSENT_REJECTED, firstClient.snapshot().failure?.reason)
      assertFalse(secondClient.isClosed())
      assertTrue(server.acceptThreadAlive())

      secondClient.submitConsent(41, V9ConsentDecision.APPROVE)
      secondServer.submitConsent(41, V9ConsentDecision.APPROVE)
      assertNotNull(secondServer.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertNotNull(secondClient.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      val candidate = assertNotNull(received.poll(2, TimeUnit.SECONDS))
      assertContentEquals(secondBytes, candidate.bytes())
      candidate.close()
      assertEquals(V9LifecycleState.SYNCHRONIZING, secondClient.snapshot().state)
      assertNull(firstServer.preparationBoundary())
    } finally {
      firstClient?.close()
      secondClient?.close()
      server.close()
      host.close()
      firstBytes.fill(0)
      secondBytes.fill(0)
    }
    waitUntil { server.activeConnectionCount() == 0 }
    waitUntil { server.pendingCandidateCount() == 0 }
  }

  @Test
  fun sourceAndTargetVotesMayArriveInEitherOrderButDuplicatesAndRejectsClose() {
    val bytes = privateBytes(2_048)
    listOf(true, false).forEach { serverFirst ->
      val pair = proposalPair(bytes)
      val fixture =
          connections(
              pair,
              sourcePlan(bytes),
              targetPlan(),
              maximumWrite = 9,
              capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
          )
      try {
        fixture.start()
        assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
        assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
        if (serverFirst) {
          fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
          assertEquals(
              V9ErrorCode.CONSENT_REJECTED,
              assertFailsWith<V9ProtocolException> {
                fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
              }.reason,
          )
          fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
        } else {
          fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
          assertEquals(
              V9ErrorCode.CONSENT_REJECTED,
              assertFailsWith<V9ProtocolException> {
                fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
              }.reason,
          )
          fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
        }
        assertNotNull(fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS))
        assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      } finally {
        fixture.close()
      }
    }

    val rejected =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = 17,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      rejected.start()
      assertNotNull(rejected.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(rejected.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      rejected.client.submitConsent(41, V9ConsentDecision.REJECT)
      waitUntil { rejected.server.snapshot().state == V9LifecycleState.CLOSED }
      assertEquals(V9ErrorCode.CONSENT_REJECTED, rejected.server.snapshot().failure?.reason)
      assertNull(rejected.server.preparationBoundary())
    } finally {
      rejected.close()
      bytes.fill(0)
    }
  }

  @Test
  fun multipleRomAndBatteryItemsRequireEveryActorBeforeEitherProviderOpens() {
    val rom = privateBytes(70_000)
    val battery = privateBytes(4_097)
    val romDigest = digest(rom)
    val batteryDigest = digest(battery)
    val romProposal =
        proposal(41, V9TransferAsset.PRIMARY_ROM, 1, 0, 1, rom, romDigest)
    val batteryProposal =
        proposal(42, V9TransferAsset.BATTERY, 1, 1, 0, battery, batteryDigest)
    val hostEntry = entry(0, digest(privateBytes(11)))
    val serverGuest = entry(1, romDigest, rom.size)
    val clientGuest = entry(1, digest(privateBytes(13)), 13, battery = true)
    val server =
        manifest(
            0,
            listOf(
                V9ManifestDifference(
                    V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                    1,
                    41,
                ),
            ),
            listOf(romProposal),
            listOf(hostEntry, serverGuest),
        )
    val client =
        manifest(
            1,
            listOf(
                V9ManifestDifference(
                    V9ManifestDifferenceCode.BATTERY_TRANSFER,
                    1,
                    42,
                ),
            ),
            listOf(batteryProposal),
            listOf(hostEntry, clientGuest),
        )
    val serverOpens = AtomicInteger()
    val clientOpens = AtomicInteger()
    val receivedRom = LinkedBlockingQueue<V9CompletedBulkCandidate>()
    val receivedBattery = LinkedBlockingQueue<V9CompletedBulkCandidate>()
    assertEquals(server.mode, client.mode)
    assertEquals(server.rosterMask, client.rosterMask)
    assertEquals(server.rosterGeneration, client.rosterGeneration)
    assertContentEquals(server.rosterCommitment().bytes(), client.rosterCommitment().bytes())
    assertEquals(server.entries.map { it.player }, client.entries.map { it.player })
    assertTrue(
        V9ManifestCompatibility.compare(server, client, 1) is
            V9ManifestComparisonResult.Compatible,
        V9ManifestCompatibility.compare(server, client, 1).toString(),
    )
    val fixture =
        connections(
            ManifestPair(server, client, romProposal),
            V9GuestPart3Plan(
                mapOf(
                    41L to V9BulkSourceProvider {
                      serverOpens.incrementAndGet()
                      ByteArrayBulkSource(rom)
                    },
                ),
                V9BulkCandidateSink { receivedBattery.put(it) },
                transactionIds = V9TransactionIdSource { 101 },
            ),
            V9GuestPart3Plan(
                mapOf(
                    42L to V9BulkSourceProvider {
                      clientOpens.incrementAndGet()
                      ByteArrayBulkSource(battery)
                    },
                ),
                V9BulkCandidateSink { receivedRom.put(it) },
                transactionIds = V9TransactionIdSource { 102 },
            ),
            maximumWrite = 11,
            capabilities =
                setOf(
                    V9Capability.ROM_TRANSFER_V1,
                    V9Capability.BATTERY_TRANSFER_V1,
                ),
        )
    try {
      fixture.start()
      assertNotNull(
          fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS),
          fixture.server.snapshot().toString(),
      )
      assertNotNull(
          fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS),
          fixture.client.snapshot().toString(),
      )
      fixture.client.submitConsent(42, V9ConsentDecision.APPROVE)
      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil {
        fixture.server.part3Progress()?.items?.count {
          it.state == V9ConsentItemState.APPROVED ||
              it.state == V9ConsentItemState.WAITING_FOR_LOCAL_DECISION
        } == 2
      }
      assertEquals(0, serverOpens.get())
      assertEquals(0, clientOpens.get())
      fixture.server.submitConsent(42, V9ConsentDecision.APPROVE)

      assertNotNull(fixture.server.awaitPreparationBoundary(10, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitPreparationBoundary(10, TimeUnit.SECONDS))
      assertEquals(1, serverOpens.get())
      assertEquals(1, clientOpens.get())
      assertContentEquals(rom, assertNotNull(receivedRom.poll(2, TimeUnit.SECONDS)).useBytes())
      assertContentEquals(
          battery,
          assertNotNull(receivedBattery.poll(2, TimeUnit.SECONDS)).useBytes(),
      )
    } finally {
      fixture.close()
      rom.fill(0)
      battery.fill(0)
    }
  }

  @Test
  fun laterPrivateHeadersAreRejectedBeforeAllocationUntilBothVotes() {
    val bytes = privateBytes(32)
    val pair = proposalPair(bytes)
    val fixture =
        connections(
            pair,
            sourcePlan(bytes),
            targetPlan(),
            maximumWrite = 64,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      val before = fixture.client.part3Progress()
      assertEquals(V9ConsentItemState.WAITING_FOR_LOCAL_DECISION, before?.items?.single()?.state)
      val admission =
          V9HeaderAdmission { type, flags, channel, encoded, decoded ->
            fixture.client.part3HeaderAdmissionForTest(type, flags, channel, encoded, decoded)
          }
      val decoder =
          V9IncrementalDecoder(
              policy =
                  V9DecoderPolicy(
                      allowedMessages = setOf(V9MessageType.ROM_CHUNK),
                      negotiatedCapabilities =
                          V9Capability.requiredCapabilities + V9Capability.ROM_TRANSFER_V1,
                      headerAdmission = admission,
                  ),
          )
      val header = header(V9MessageType.ROM_CHUNK, 0, 1, 9)
      val result = decoder.feed(header, 0, 32)
      assertEquals(V9ErrorCode.CONSENT_REJECTED, result.failure?.reason)
      assertEquals(0, result.payloadAllocations)
      assertEquals(0, result.payloadReservations)
    } finally {
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun classAndCompressionCapabilitiesRejectBeforeReservationOrPayloadAllocation() {
    fun reject(
        type: V9MessageType,
        flags: Int,
        capabilities: Set<V9Capability>,
        expected: V9ErrorCode,
    ) {
      val decoder =
          V9IncrementalDecoder(
              policy =
                  V9DecoderPolicy(
                      allowedMessages = setOf(type),
                      negotiatedCapabilities =
                          V9Capability.requiredCapabilities + capabilities,
                  ),
          )
      val decoded =
          if (type == V9MessageType.ROM_BEGIN || type == V9MessageType.BATTERY_BEGIN) {
            V9BulkCodec.BEGIN_BYTES
          }
          else V9BulkCodec.CHUNK_HEADER_BYTES + 1
      val encoded = decoded
      val result = decoder.feed(header(type, flags, 1, encoded, decoded), 0, 64)
      assertEquals(expected, result.failure?.reason)
      assertEquals(0, result.payloadAllocations)
      assertEquals(0, result.payloadReservations)
    }
    reject(
        V9MessageType.ROM_BEGIN,
        0,
        emptySet(),
        V9ErrorCode.CAPABILITY_MISMATCH,
    )
    reject(
        V9MessageType.BATTERY_BEGIN,
        0,
        setOf(V9Capability.ROM_TRANSFER_V1),
        V9ErrorCode.CAPABILITY_MISMATCH,
    )
    reject(
        V9MessageType.ROM_CHUNK,
        V9Flag.DEFLATE.wireMask,
        setOf(V9Capability.ROM_TRANSFER_V1),
        V9ErrorCode.CAPABILITY_MISMATCH,
    )
  }

  @Test
  fun sourceAndSinkFailuresRemainAtomicAndNeverPublishReceiverPreparation() {
    val bytes = privateBytes(4_096)
    val sourceFailure =
        connections(
            proposalPair(bytes),
            V9GuestPart3Plan(
                mapOf(41L to V9BulkSourceProvider {
                  ByteArrayBulkSource(bytes.copyOf(bytes.size - 1))
                }),
                V9BulkCandidateSink { it.close() },
            ),
            targetPlan(),
            maximumWrite = 19,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      sourceFailure.start()
      assertNotNull(sourceFailure.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(sourceFailure.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      sourceFailure.server.submitConsent(41, V9ConsentDecision.APPROVE)
      sourceFailure.client.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil { sourceFailure.client.snapshot().state == V9LifecycleState.CLOSED }
      assertNull(sourceFailure.client.preparationBoundary())
      assertEquals(
          V9ErrorCode.CHECKSUM_MISMATCH,
          sourceFailure.server.snapshot().failure?.reason,
      )
    } finally {
      sourceFailure.close()
    }

    var rejectedCandidate: V9CompletedBulkCandidate? = null
    val sinkFailure =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            V9GuestPart3Plan(
                emptyMap(),
                V9BulkCandidateSink {
                  rejectedCandidate = it
                  throw IllegalStateException("private sink detail")
                },
            ),
            maximumWrite = 23,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      sinkFailure.start()
      assertNotNull(sinkFailure.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(sinkFailure.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      sinkFailure.server.submitConsent(41, V9ConsentDecision.APPROVE)
      sinkFailure.client.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil { sinkFailure.client.snapshot().state == V9LifecycleState.CLOSED }
      assertNull(sinkFailure.client.preparationBoundary())
      assertFailsWith<IllegalStateException> { assertNotNull(rejectedCandidate).bytes() }
      assertEquals(V9Diagnostic.TRANSFER_REJECTED, sinkFailure.client.snapshot().failure?.diagnostic)
    } finally {
      sinkFailure.close()
    }

    var interruptedCandidate: V9CompletedBulkCandidate? = null
    val checkedFailure =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            V9GuestPart3Plan(
                emptyMap(),
                V9BulkCandidateSink {
                  interruptedCandidate = it
                  throw InterruptedException("synthetic checked sink failure")
                },
            ),
            maximumWrite = 29,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      checkedFailure.start()
      assertNotNull(checkedFailure.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(checkedFailure.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      checkedFailure.server.submitConsent(41, V9ConsentDecision.APPROVE)
      checkedFailure.client.submitConsent(41, V9ConsentDecision.APPROVE)
      waitUntil { checkedFailure.client.snapshot().state == V9LifecycleState.CLOSED }
      assertEquals(V9ErrorCode.CANCELLED, checkedFailure.client.snapshot().failure?.reason)
      assertNull(checkedFailure.client.preparationBoundary())
      assertFailsWith<IllegalStateException> { assertNotNull(interruptedCandidate).bytes() }
    } finally {
      checkedFailure.close()
      bytes.fill(0)
    }
  }

  @Test
  fun cancellingWhileCandidateSinkIsBlockedRevokesFoundationOwnership() {
    val bytes = privateBytes(4_111)
    val sinkEntered = CountDownLatch(1)
    val sinkRelease = CountDownLatch(1)
    val candidate = AtomicReference<V9CompletedBulkCandidate?>()
    val fixture =
        connections(
            proposalPair(bytes),
            sourcePlan(bytes),
            V9GuestPart3Plan(
                emptyMap(),
                V9BulkCandidateSink {
                  candidate.set(it)
                  sinkEntered.countDown()
                  sinkRelease.await()
                },
            ),
            maximumWrite = 31,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertTrue(sinkEntered.await(5, TimeUnit.SECONDS))
      fixture.client.cancel()
      waitUntil { fixture.client.activeTaskCount() == 0 }
      assertNull(fixture.client.preparationBoundary())
      assertEquals(V9ErrorCode.CANCELLED, fixture.client.snapshot().failure?.reason)
      assertFailsWith<IllegalStateException> { assertNotNull(candidate.get()).bytes() }
    } finally {
      sinkRelease.countDown()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun aBlockedCandidateDeliveryBackpressuresTheNextApprovedProposal() {
    val primary = privateBytes(3_071)
    val slot = privateBytes(3_073)
    val pair = twoRomProposalPair(primary, slot)
    val sinkCalls = AtomicInteger()
    val firstEntered = CountDownLatch(1)
    val firstRelease = CountDownLatch(1)
    val candidates = LinkedBlockingQueue<V9CompletedBulkCandidate>()
    val transaction = AtomicInteger(70)
    val fixture =
        connections(
            pair,
            V9GuestPart3Plan(
                mapOf(
                    41L to V9BulkSourceProvider { ByteArrayBulkSource(primary) },
                    42L to V9BulkSourceProvider { ByteArrayBulkSource(slot) },
                ),
                V9BulkCandidateSink { it.close() },
                transactionIds = V9TransactionIdSource { transaction.incrementAndGet().toLong() },
            ),
            V9GuestPart3Plan(
                emptyMap(),
                V9BulkCandidateSink {
                  if (sinkCalls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    firstRelease.await()
                  }
                  candidates.put(it)
                },
            ),
            maximumWrite = 37,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      listOf(41L, 42L).forEach { proposalId ->
        fixture.server.submitConsent(proposalId, V9ConsentDecision.APPROVE)
        fixture.client.submitConsent(proposalId, V9ConsentDecision.APPROVE)
      }
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
      assertNotNull(
          fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS),
          "server=${fixture.server.snapshot()} progress=${fixture.server.part3Progress()} " +
              "wire=${wireTypes(fixture.serverChannel.recordedBytes())}",
      )
      assertEquals(1, sinkCalls.get(), "a second candidate must not be retained concurrently")
      assertNull(fixture.client.preparationBoundary())

      firstRelease.countDown()
      assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertEquals(2, sinkCalls.get())
      val delivered = listOf(
          assertNotNull(candidates.poll(2, TimeUnit.SECONDS)),
          assertNotNull(candidates.poll(2, TimeUnit.SECONDS)),
      )
      assertContentEquals(primary, delivered[0].bytes())
      assertContentEquals(slot, delivered[1].bytes())
      delivered.forEach(V9CompletedBulkCandidate::close)
    } finally {
      firstRelease.countDown()
      fixture.close()
      primary.fill(0)
      slot.fill(0)
    }
  }

  @Test
  fun cancellationClosesAnActiveLazySourceExactlyOnceAndPublishesNoCandidate() {
    val bytes = privateBytes(2_048)
    val source = BlockingBulkSource(bytes)
    val delivered = AtomicInteger()
    val fixture =
        connections(
            proposalPair(bytes),
            V9GuestPart3Plan(
                mapOf(41L to V9BulkSourceProvider { source }),
                V9BulkCandidateSink { it.close() },
            ),
            V9GuestPart3Plan(
                emptyMap(),
                V9BulkCandidateSink {
                  delivered.incrementAndGet()
                  it.close()
                },
            ),
            maximumWrite = 64,
            capabilities = setOf(V9Capability.ROM_TRANSFER_V1),
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertNotNull(fixture.client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      fixture.server.submitConsent(41, V9ConsentDecision.APPROVE)
      fixture.client.submitConsent(41, V9ConsentDecision.APPROVE)
      assertTrue(source.readEntered.await(2, TimeUnit.SECONDS))
      fixture.server.cancel()
      waitUntil { fixture.server.activeTaskCount() == 0 }
      assertEquals(1, source.closeCount.get())
      assertEquals(0, delivered.get())
      assertNull(fixture.client.preparationBoundary())
    } finally {
      source.release.countDown()
      fixture.close()
      bytes.fill(0)
    }
  }

  @Test
  fun zeroProposalPartThreePreservesDirectSynchronizingCompletion() {
    val manifests =
        ManifestPair(
            manifest(0, emptyList(), emptyList(), matchingEntries()),
            manifest(1, emptyList(), emptyList(), matchingEntries()),
            null,
        )
    val fixture =
        connections(
            manifests,
            V9GuestPart3Plan(emptyMap(), V9BulkCandidateSink { it.close() }),
            V9GuestPart3Plan(emptyMap(), V9BulkCandidateSink { it.close() }),
            maximumWrite = 5,
            capabilities = emptySet(),
        )
    try {
      fixture.start()
      assertNotNull(fixture.server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      val server = assertNotNull(fixture.server.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      val client = assertNotNull(fixture.client.awaitPreparationBoundary(5, TimeUnit.SECONDS))
      assertTrue(server.preparedItems.isEmpty())
      assertTrue(client.preparedItems.isEmpty())
      assertEquals(V9LifecycleState.SYNCHRONIZING, fixture.server.snapshot().state)
      assertTrue(
          fixture.serverChannel.recordedBytes().let(::wireTypes)
              .none { it == V9MessageType.CONSENT },
      )
    } finally {
      fixture.close()
    }
  }

  @Test
  fun consentDeadlineIsExactAndDoesNotResetForPartialVotes() {
    val clock = FakeClock()
    val lifecycle = V9Lifecycle(V9Role.CLIENT, clock)
    lifecycle.serverHelloReceived()
    lifecycle.clientHelloSent(V9NegotiatedCapabilities(V9Capability.requiredCapabilities))
    lifecycle.clientAuthSent()
    lifecycle.serverAuthResultReceived()
    lifecycle.serverManifestReceived()
    lifecycle.clientManifestSent(true)
    assertEquals(V9LifecycleState.EXCHANGE_CONSENT, lifecycle.snapshot().state)
    clock.now = 119_999
    assertNull(lifecycle.checkDeadline())
    clock.now = 120_000
    assertEquals(V9ErrorCode.CONSENT_REJECTED, lifecycle.checkDeadline()?.reason)
  }

  @Test
  fun bulkProgressDeadlineClosesExactlyAtFifteenSecondsAfterValidBegin() {
    val bytes = privateBytes(32)
    val pair = proposalPair(bytes)
    val proposal = assertNotNull(pair.proposal)
    val serverPayload = V9ManifestCodec.encode(pair.server, context(pair.server, 0))
    val clientPayload = V9ManifestCodec.encode(pair.client, context(pair.client, 1))
    val boundary =
        V9ManifestPairingBoundary(
            V9Role.CLIENT,
            V9LinkMode.NORMAL,
            1,
            V9LifecycleState.EXCHANGE_CONSENT,
            pair.server,
            pair.client,
            V9ManifestDigest.sha256(serverPayload),
            V9ManifestDigest.sha256(clientPayload),
            pair.server.differences,
            listOf(proposal),
        )
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val failure = AtomicReference<V9ErrorCode?>()
    val session =
        V9Part3Session(
            V9Role.CLIENT,
            V9LinkMode.NORMAL,
            1,
            targetPlan(),
            clock,
            scheduler::schedule,
            { name, block ->
              Thread(block, name).also { it.start() }
            },
            { _, _, _, _, written ->
              written()
              true
            },
            {},
            { reason, _ -> failure.compareAndSet(null, reason) },
            {},
        )
    try {
      session.start(boundary)
      session.submitConsent(41, V9ConsentDecision.APPROVE)
      val peerVote =
          V9ConsentCodec.forProposal(
              proposal,
              0,
              V9ConsentDecision.APPROVE,
              boundary.serverPayloadSha256(),
              boundary.clientPayloadSha256(),
          )
      session.handleConsent(
          V9ConsentCodec.encode(
              peerVote,
              V9ConsentValidationContext(
                  1,
                  0,
                  listOf(proposal),
                  boundary.serverPayloadSha256(),
                  boundary.clientPayloadSha256(),
              ),
          ),
      )
      session.handleBulk(
          V9MessageType.ROM_BEGIN,
          0,
          2,
          V9BulkCodec.encodeBegin(
              V9BulkBegin(
                  9,
                  41,
                  0,
                  1,
                  1,
                  V9TransferAsset.PRIMARY_ROM,
                  bytes.size.toLong(),
                  proposal.expectedSha256(),
                  32,
              ),
          ),
      )
      clock.now = 14_999
      scheduler.runDue()
      assertNull(failure.get())
      clock.now = 15_000
      scheduler.runDue()
      assertEquals(V9ErrorCode.TIMEOUT, failure.get())
    } finally {
      session.close()
      serverPayload.fill(0)
      clientPayload.fill(0)
      bytes.fill(0)
    }
  }

  private fun connections(
      pair: ManifestPair,
      serverPart3: V9GuestPart3Plan,
      clientPart3: V9GuestPart3Plan,
      maximumWrite: Int,
      capabilities: Set<V9Capability>,
      serverWriteGate: FrameWriteGate? = null,
      clientWriteGate: FrameWriteGate? = null,
  ): ConnectionFixture {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val invitation = host.createInvitation("example.com", 6688, 1)
    val clientInvitation = invitation.forClientAuthentication()
    val (serverChannel, clientChannel) =
        RecordingMemoryChannel.pair(maximumWrite, serverWriteGate, clientWriteGate)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            optionalCapabilities = capabilities,
            invitationHost = host,
            manifestPlan = V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to pair.server)),
            part3Plan = V9Part3Plan.server(V9LinkMode.NORMAL, mapOf(1 to serverPart3)),
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            optionalCapabilities = capabilities,
            clientInvitation = clientInvitation,
            manifestPlan = V9ManifestPlan.client(V9LinkMode.NORMAL, 1, pair.client),
            part3Plan = V9Part3Plan.client(V9LinkMode.NORMAL, 1, clientPart3),
        )
    return ConnectionFixture(host, server, client, serverChannel, clientChannel)
  }

  private fun sourcePlan(
      bytes: ByteArray,
      compress: Boolean = false,
  ): V9GuestPart3Plan =
      V9GuestPart3Plan(
          mapOf(41L to V9BulkSourceProvider { ByteArrayBulkSource(bytes) }),
          V9BulkCandidateSink { it.close() },
          compressChunks = compress,
          transactionIds = V9TransactionIdSource { 1 },
      )

  private fun targetPlan(): V9GuestPart3Plan =
      V9GuestPart3Plan(emptyMap(), V9BulkCandidateSink { it.close() })

  private fun proposalPair(bytes: ByteArray): ManifestPair =
      proposalPair(V9LinkMode.NORMAL, 1, bytes)

  private fun twoRomProposalPair(primary: ByteArray, slot: ByteArray): ManifestPair {
    val primaryProposal = proposal(41, V9TransferAsset.PRIMARY_ROM, 1, 0, 1, primary)
    val slotProposal = proposal(42, V9TransferAsset.SLOT_ROM, 1, 0, 1, slot)
    val host = entry(0, digest(privateBytes(9)))
    val serverGuest =
        V9ManifestEntry(
            1,
            true,
            true,
            false,
            V9ManifestBootstrap.SKIP,
            0,
            "dmg",
            "PLAYER1",
            0,
            V9MapperFamily.ROM_ONLY,
            primary.size.toLong(),
            slot.size.toLong(),
            digest(primary),
            digest(slot),
            V9ManifestDigest.zero(),
            V9ManifestDigest.zero(),
        )
    val clientGuest =
        V9ManifestEntry(
            1,
            true,
            true,
            false,
            V9ManifestBootstrap.SKIP,
            0,
            "dmg",
            "PLAYER1",
            0,
            V9MapperFamily.ROM_ONLY,
            primary.size.toLong(),
            slot.size.toLong(),
            digest(privateBytes(17)),
            digest(privateBytes(19)),
            V9ManifestDigest.zero(),
            V9ManifestDigest.zero(),
        )
    return ManifestPair(
        manifest(
            0,
            listOf(
                V9ManifestDifference(V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT, 1, 41),
                V9ManifestDifference(V9ManifestDifferenceCode.SLOT_ROM_DIFFERENT, 1, 42),
            ),
            listOf(primaryProposal, slotProposal),
            listOf(host, serverGuest),
        ),
        manifest(1, emptyList(), emptyList(), listOf(host, clientGuest)),
        primaryProposal,
    )
  }

  private fun proposalPair(
      mode: V9LinkMode,
      guest: Int,
      bytes: ByteArray,
  ): ManifestPair {
    val contentDigest = digest(bytes)
    val proposal =
        V9TransferProposal(
            41,
            V9TransferAction.OFFER_BY_SOURCE,
            V9TransferClass.ROM,
            V9TransferAsset.PRIMARY_ROM,
            guest,
            0,
            guest,
            bytes.size.toLong(),
            contentDigest,
        )
    val entryCount = if (mode == V9LinkMode.NORMAL) 2 else 4
    val serverEntries =
        (0 until entryCount).map { player ->
          if (player == guest) {
            entry(player, contentDigest, bytes.size)
          } else {
            entry(player, digest(privateBytes(player + 5)))
          }
        }
    val clientEntries =
        serverEntries.map { value ->
          if (value.player == guest) {
            entry(guest, digest(privateBytes(guest + 17)), guest + 17)
          } else {
            value
          }
        }
    val manifestMode =
        if (mode == V9LinkMode.NORMAL) V9ManifestMode.NORMAL
        else V9ManifestMode.FOUR_PLAYER
    val generation = if (mode == V9LinkMode.NORMAL) 1L else 9L
    fun value(
        sender: Int,
        differences: List<V9ManifestDifference>,
        proposals: List<V9TransferProposal>,
        entries: List<V9ManifestEntry>,
    ) =
        V9Manifest(
            manifestMode,
            sender,
            generation,
            V9ManifestCodec.rosterCommitment(mode, generation),
            entries,
            differences,
            proposals,
        )
    val server =
        value(
            0,
            listOf(
                V9ManifestDifference(
                    V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                    guest,
                    41,
                ),
            ),
            listOf(proposal),
            serverEntries,
        )
    val client = value(guest, emptyList(), emptyList(), clientEntries)
    return ManifestPair(server, client, proposal)
  }

  private fun proposal(
      id: Long,
      asset: V9TransferAsset,
      owner: Int,
      source: Int,
      target: Int,
      bytes: ByteArray,
      contentDigest: V9ManifestDigest = digest(bytes),
  ): V9TransferProposal =
      V9TransferProposal(
          id,
          V9TransferAction.OFFER_BY_SOURCE,
          asset.transferClass,
          asset,
          owner,
          source,
          target,
          bytes.size.toLong(),
          contentDigest,
      )

  private fun manifest(
      sender: Int,
      differences: List<V9ManifestDifference>,
      proposals: List<V9TransferProposal>,
      entries: List<V9ManifestEntry>,
  ): V9Manifest =
      V9Manifest(
          V9ManifestMode.NORMAL,
          sender,
          1,
          V9ManifestCodec.rosterCommitment(V9LinkMode.NORMAL, 1),
          entries,
          differences,
          proposals,
      )

  private fun matchingEntries(): List<V9ManifestEntry> =
      listOf(entry(0, digest(privateBytes(5))), entry(1, digest(privateBytes(7))))

  private fun entry(
      player: Int,
      primary: V9ManifestDigest,
      length: Int = 32_768,
      battery: Boolean = false,
  ): V9ManifestEntry =
      V9ManifestEntry(
          player,
          true,
          false,
          battery,
          V9ManifestBootstrap.SKIP,
          0,
          "dmg",
          "PLAYER$player",
          0,
          V9MapperFamily.ROM_ONLY,
          length.toLong(),
          0,
          primary,
          V9ManifestDigest.zero(),
          V9ManifestDigest.zero(),
          V9ManifestDigest.zero(),
      )

  private fun context(manifest: V9Manifest, source: Int): V9ManifestValidationContext =
      V9ManifestValidationContext(
          V9LinkMode.NORMAL,
          1,
          source,
          if (source == 0) 1 else 0,
          manifest.rosterGeneration,
          manifest.rosterCommitment(),
          V9Capability.requiredCapabilities +
              setOf(V9Capability.ROM_TRANSFER_V1, V9Capability.RAW_DEFLATE_V1),
      )

  private fun privateBytes(size: Int): ByteArray =
      ByteArray(size) { ((it * 31 + 17) and 0xff).toByte() }

  private fun digest(bytes: ByteArray): V9ManifestDigest =
      V9ManifestDigest(MessageDigest.getInstance("SHA-256").digest(bytes))

  private fun hex(bytes: ByteArray): String =
      bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun assertConsentRejected(
      payload: ByteArray,
      context: V9ConsentValidationContext,
  ) {
    assertEquals(
        V9ErrorCode.CONSENT_REJECTED,
        assertFailsWith<V9ProtocolException> {
          V9ConsentCodec.decode(payload, context)
        }.reason,
    )
  }

  private fun <T> assertBulkMalformed(payload: ByteArray, decode: (ByteArray) -> T) {
    assertEquals(
        V9ErrorCode.MALFORMED_HEADER,
        assertFailsWith<V9ProtocolException> { decode(payload) }.reason,
    )
  }

  private fun header(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      length: Int,
  ): ByteArray = header(type, flags, channel, length, length)

  private fun header(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      encodedLength: Int,
      decodedLength: Int,
  ): ByteArray =
      ByteBuffer.allocate(ProtocolV9.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
          .put(ProtocolV9.MAGIC)
          .put(ProtocolV9.MAJOR.toByte())
          .put(ProtocolV9.MINOR.toByte())
          .putShort(ProtocolV9.HEADER_BYTES.toShort())
          .putShort(type.wireId.toShort())
          .putShort(flags.toShort())
          .putInt(0)
          .putInt(0)
          .putInt(encodedLength)
          .putInt(decodedLength)
          .putInt(channel.toInt())
          .put(ByteArray(32))
          .array()

  private fun privateWireTypes(bytes: ByteArray): Set<V9MessageType> =
      wireTypes(bytes).filterTo(linkedSetOf()) {
        it in
            setOf(
                V9MessageType.ROM_BEGIN,
                V9MessageType.ROM_CHUNK,
                V9MessageType.ROM_END,
                V9MessageType.BATTERY_BEGIN,
                V9MessageType.BATTERY_CHUNK,
                V9MessageType.BATTERY_END,
            )
      }

  private fun wireTypes(bytes: ByteArray): List<V9MessageType> {
    val result = mutableListOf<V9MessageType>()
    var offset = 0
    while (offset < bytes.size) {
      val header =
          ByteBuffer.wrap(bytes, offset, ProtocolV9.HEADER_BYTES)
              .slice()
              .order(ByteOrder.BIG_ENDIAN)
      header.position(8)
      result += requireNotNull(V9MessageType.fromWireId(header.short.toInt() and 0xffff))
      header.position(20)
      val length = header.int
      offset = Math.addExact(offset, Math.addExact(ProtocolV9.HEADER_BYTES, length))
    }
    assertEquals(bytes.size, offset)
    return result
  }

  private fun laterUnavailable(): Set<V9MessageType> =
      setOf(
          V9MessageType.CHECKPOINT,
          V9MessageType.START,
          V9MessageType.READY,
          V9MessageType.INPUT,
          V9MessageType.RESET,
          V9MessageType.STOP,
          V9MessageType.PING,
          V9MessageType.PONG,
      )

  private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition()) {
      if (System.nanoTime() >= deadline) throw AssertionError("condition did not become true")
      Thread.yield()
    }
  }

  private fun V9CompletedBulkCandidate.useBytes(): ByteArray =
      try {
        bytes()
      } finally {
        close()
      }

  private data class ManifestPair(
      val server: V9Manifest,
      val client: V9Manifest,
      val proposal: V9TransferProposal?,
  )

  private class ByteArrayBulkSource(bytes: ByteArray) : V9BulkSource {
    private val owned = bytes.copyOf()
    private var offset = 0
    private var closed = false

    override val length: Long get() = owned.size.toLong()

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      check(!closed)
      if (this.offset == owned.size) return -1
      val count = minOf(length, owned.size - this.offset)
      System.arraycopy(owned, this.offset, bytes, offset, count)
      this.offset += count
      return count
    }

    override fun close() {
      if (!closed) {
        closed = true
        owned.fill(0)
      }
    }
  }

  private class BlockingBulkSource(bytes: ByteArray) : V9BulkSource {
    private val owned = bytes.copyOf()
    val readEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closeCount = AtomicInteger()
    private val closed = AtomicBoolean()

    override val length: Long get() = owned.size.toLong()

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      readEntered.countDown()
      release.await()
      if (closed.get()) return -1
      val count = minOf(length, owned.size)
      System.arraycopy(owned, 0, bytes, offset, count)
      return count
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        closeCount.incrementAndGet()
        owned.fill(0)
        release.countDown()
      }
    }
  }

  private data class ConnectionFixture(
      val host: V9InvitationHost,
      val server: V9FoundationConnection,
      val client: V9FoundationConnection,
      val serverChannel: RecordingMemoryChannel,
      val clientChannel: RecordingMemoryChannel,
  ) : AutoCloseable {
    fun start() {
      server.start()
      client.start()
    }

    override fun close() {
      client.close()
      server.close()
      host.close()
    }
  }

  private class FrameWriteGate(private val target: V9MessageType) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val fired = AtomicBoolean()

    fun afterPeerReceived(bytes: ByteArray, offset: Int, count: Int) {
      if (offset != 0 || count != bytes.size || bytes.size < ProtocolV9.HEADER_BYTES) return
      val typeId = ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
      if (V9MessageType.fromWireId(typeId) != target || !fired.compareAndSet(false, true)) return
      entered.countDown()
      release.await()
    }
  }

  private class RecordingMemoryChannel(
      private val maximumWrite: Int,
      private val writeGate: FrameWriteGate? = null,
  ) : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    private val recorded = mutableListOf<Byte>()
    private val observed = mutableListOf<Byte>()
    private val observedTypes = mutableSetOf<V9MessageType>()
    private val closed = AtomicBoolean(false)
    private lateinit var peer: RecordingMemoryChannel

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
      observeRead(bytes, offset, count)
      return count
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      if (closed.get()) throw IOException("closed")
      val count = minOf(length, maximumWrite)
      synchronized(recorded) {
        repeat(count) {
          recorded += bytes[offset + it]
          peer.incoming.put(bytes[offset + it].toInt() and 0xff)
        }
      }
      writeGate?.afterPeerReceived(bytes, offset, count)
      return count
    }

    override fun shutdownOutput() {
      peer.incoming.offer(-1)
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        incoming.offer(-1)
        if (::peer.isInitialized) peer.incoming.offer(-1)
      }
    }

    fun recordedBytes(): ByteArray =
        synchronized(recorded) { recorded.toByteArray() }

    fun hasReadType(type: V9MessageType): Boolean =
        synchronized(observed) { type in observedTypes }

    private fun observeRead(bytes: ByteArray, offset: Int, count: Int) {
      synchronized(observed) {
        repeat(count) { observed += bytes[offset + it] }
        while (observed.size >= 32) {
          val decisive = observed.take(32).toByteArray()
          val typeId = ByteBuffer.wrap(decisive).order(ByteOrder.BIG_ENDIAN)
              .getShort(8).toInt() and 0xffff
          V9MessageType.fromWireId(typeId)?.let(observedTypes::add)
          if (observed.size < ProtocolV9.HEADER_BYTES) return
          val header = observed.take(ProtocolV9.HEADER_BYTES).toByteArray()
          val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
          val payloadLength = buffer.getInt(20)
          val total = Math.addExact(ProtocolV9.HEADER_BYTES, payloadLength)
          if (payloadLength < 0 || observed.size < total) return
          observed.subList(0, total).clear()
        }
      }
    }

    companion object {
      fun pair(
          maximumWrite: Int,
          firstWriteGate: FrameWriteGate? = null,
          secondWriteGate: FrameWriteGate? = null,
      ): Pair<RecordingMemoryChannel, RecordingMemoryChannel> {
        val first = RecordingMemoryChannel(maximumWrite, firstWriteGate)
        val second = RecordingMemoryChannel(maximumWrite, secondWriteGate)
        first.peer = second
        second.peer = first
        return first to second
      }
    }
  }

  private class FakeClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class ManualScheduler(private val clock: FakeClock) {
    private data class Task(
        val deadline: Long,
        val action: Runnable,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val tasks = mutableListOf<Task>()

    fun schedule(deadline: Long, action: Runnable): java.io.Closeable {
      val task = Task(deadline, action)
      tasks += task
      return java.io.Closeable { task.cancelled.set(true) }
    }

    fun runDue() {
      tasks.filter { !it.cancelled.get() && clock.now >= it.deadline }
          .toList()
          .forEach {
            it.cancelled.set(true)
            it.action.run()
          }
    }
  }
}
