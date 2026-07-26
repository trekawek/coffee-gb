package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9InvitationAuthTest {

  @Test
  fun everyFrozenInvitationVectorIsExecutedByThePureProductionParser() {
    rows("/netplay-v9/invitation-vectors.tsv").forEach { row ->
      val input =
          when (row.getValue("input")) {
            "<512-ascii-bytes>" -> "a".repeat(512)
            "<513-ascii-bytes>" -> "a".repeat(513)
            else -> row.getValue("input").replace("\\n", "\n")
          }
      if (row.getValue("expected") == "SUCCESS") {
        V9Invitation.parse(input).use { invitation ->
          assertEquals(row.getValue("canonical"), invitation.render(), row.getValue("id"))
          assertEquals("V9Invitation([redacted])", invitation.toString())
        }
      } else {
        val error = assertFailsWith<V9InvitationParseException>(row.getValue("id")) {
          V9Invitation.parse(input)
        }
        assertEquals(row.getValue("error"), error.reason.name, row.getValue("id"))
        assertFalse(error.message.orEmpty().contains(input))
      }
    }
  }

  @Test
  fun generatedInvitationsAreExactRandomBoundedCanonicalAndRedacted() {
    val clock = FakeClock(10_000)
    val token = ByteArray(16) { (0xa0 + it).toByte() }
    val host =
        V9InvitationHost(
            V9LinkMode.FOUR_PLAYER,
            clock,
            V9UtcSeconds { 2_000_000_000L },
            fixedRandom(token),
        )
    val value = host.createInvitation("2001:db8::1", 65_535, 3)
    assertEquals(
        "coffeegb://[2001:db8::1]:65535/join?v=9&mode=four&slot=3" +
            "&exp=2000000300&token=oKGio6SlpqeoqaqrrK2urw",
        value.render(),
    )
    val rendered = value.render()
    V9Invitation.parse(rendered).use { parsed ->
      assertEquals(rendered, parsed.render())
    }
    assertFalse(value.toString().contains("oKGi"))
    assertFailsWith<V9InvitationParseException> {
      host.createInvitation("play.example", 6688, 1, 59)
    }
    assertFailsWith<V9InvitationParseException> {
      host.createInvitation("play.example", 6688, 1, 601)
    }
    host.close()
    assertEquals(0, host.outstandingInvitations())
    assertFalse(value.isSecretAvailable())
    assertFailsWith<IllegalStateException> { value.render() }
    assertFailsWith<IllegalStateException> {
      host.createInvitation("play.example", 6688, 1)
    }
  }

  @Test
  fun hostInvalidationDestroysEverySharedApplicationView() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val token = ByteArray(16) { it.toByte() }
    val serverNonce = ByteArray(32) { 1 }
    val clientNonce = ByteArray(32) { 2 }
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            clock,
            V9UtcSeconds { 1 },
            fixedRandom(token),
            scheduler,
        )

    val replaced = host.createInvitation("play.example", 6688, 1, 60)
    val replacement = host.createInvitation("play.example", 6688, 1, 60)
    assertFalse(replaced.isSecretAvailable())
    assertFailsWith<IllegalStateException> { replaced.render() }
    replaced.close()

    val client = replacement.forClientAuthentication()
    assertFalse(replacement.isSecretAvailable())
    assertTrue(client.isSecretAvailable())
    val auth = client.createAuth(serverNonce, clientNonce)
    val accepted =
        assertIs<V9Authentication.Accepted>(
            host.authenticate(auth, serverNonce, clientNonce),
        )
    assertFalse(client.isSecretAvailable())
    assertFailsWith<IllegalStateException> {
      client.createAuth(serverNonce, clientNonce)
    }
    client.close()
    client.close()
    replacement.close()
    replacement.close()
    accepted.reservation.close()
    host.close()
    assertEquals(0, host.outstandingInvitations())
    assertFalse(scheduler.closed.get())
  }

  @Test
  fun expiryStopAndExplicitCloseInvalidateRenderingWithoutChangingDisclosedStrings() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            clock,
            V9UtcSeconds { 1 },
            fixedRandom(ByteArray(16) { 7 }),
            scheduler,
        )

    val expired = host.createInvitation("play.example", 6688, 1, 60)
    val alreadyDisclosed = expired.render()
    clock.now = 60_000
    scheduler.runDue()
    assertFalse(expired.isSecretAvailable())
    assertFailsWith<IllegalStateException> { expired.render() }
    // The immutable caller-owned String is outside the wipeable secret boundary.
    assertTrue(alreadyDisclosed.endsWith("token=BwcHBwcHBwcHBwcHBwcHBw"))
    expired.close()

    val explicitlyClosed = host.createInvitation("play.example", 6688, 1, 60)
    explicitlyClosed.close()
    explicitlyClosed.close()
    assertFalse(explicitlyClosed.isSecretAvailable())
    assertFailsWith<IllegalStateException> { explicitlyClosed.render() }
    assertEquals(0, host.outstandingInvitations())

    val stopped = host.createInvitation("play.example", 6688, 1, 60)
    host.close()
    host.close()
    assertFalse(stopped.isSecretAvailable())
    assertFailsWith<IllegalStateException> { stopped.render() }
    stopped.close()
  }

  @Test
  fun parserTransfersOneSecretLeaseAndParseFailuresRemainRedacted() {
    val canonical =
        "coffeegb://play.example:6688/join?v=9&mode=normal&slot=1" +
            "&exp=2000000000&token=AAECAwQFBgcICQoLDA0ODw"
    val parsed = V9Invitation.parse(canonical)
    val client = parsed.forClientAuthentication()
    assertFalse(parsed.isSecretAvailable())
    assertFailsWith<IllegalStateException> { parsed.render() }
    assertTrue(client.isSecretAvailable())
    client.createAuth(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    client.close()
    assertFalse(client.isSecretAvailable())
    assertFailsWith<IllegalStateException> {
      client.createAuth(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    }
    parsed.close()

    val noncanonicalToken = canonical.dropLast(1) + "x"
    val failure = assertFailsWith<V9InvitationParseException> {
      V9Invitation.parse(noncanonicalToken)
    }
    assertEquals(V9InvitationError.INV_TOKEN, failure.reason)
    assertEquals("INV_TOKEN", failure.message)
    assertTrue(noncanonicalToken.endsWith("token=AAECAwQFBgcICQoLDA0ODx"))

    var failedGenerationBuffer: ByteArray? = null
    val failedHost =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            random =
                V9SecureRandom { target ->
                  failedGenerationBuffer = target
                  target.fill(0x5a.toByte())
                  throw IllegalStateException("injected random failure")
                },
        )
    assertFailsWith<IllegalStateException> {
      failedHost.createInvitation("play.example", 6688, 1)
    }
    assertTrue(requireNotNull(failedGenerationBuffer).all { it == 0.toByte() })
    failedHost.close()
  }

  @Test
  fun everyAuthVectorMatchesTheExactProductionHmacAndPayloadCodecs() {
    rows("/netplay-v9/auth-vectors.tsv").forEach { row ->
      val mode =
          if (row.getValue("mode") == "normal") V9LinkMode.NORMAL
          else V9LinkMode.FOUR_PLAYER
      val proof =
          V9AuthCodec.proof(
              hex(row.getValue("token_hex")),
              hex(row.getValue("server_nonce_hex")),
              hex(row.getValue("client_nonce_hex")),
              row.int("slot"),
          )
      assertContentEquals(hex(row.getValue("proof_hex")), proof, row.getValue("id"))
      val encoded = V9AuthCodec.encode(V9Auth(row.int("slot"), proof))
      assertEquals(36, encoded.size)
      assertEquals(0, encoded[1].toInt())
      assertContentEquals(
          proof,
          V9AuthCodec.decode(encoded, mode).proof(),
          row.getValue("id"),
      )
    }

    val accepted = V9AuthCodec.encode(V9AuthResult(V9AuthStatus.ACCEPTED))
    assertContentEquals(byteArrayOf(0, 0, 0, 0), accepted)
    assertEquals(
        V9AuthStatus.ACCEPTED,
        V9AuthCodec.decodeResult(accepted, V9Flag.RESPONSE.wireMask).status,
    )
    val rejected = V9AuthCodec.encode(V9AuthResult(V9AuthStatus.REJECTED))
    assertContentEquals(byteArrayOf(0, 1, 0, 0), rejected)
    assertEquals(
        V9AuthStatus.REJECTED,
        V9AuthCodec.decodeResult(
                rejected,
                V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask,
            )
            .status,
    )
    assertFailsWith<V9ProtocolException> {
      V9AuthCodec.decodeResult(rejected, V9Flag.RESPONSE.wireMask)
    }
  }

  @Test
  fun everyInvitationLifecycleVectorRunsAgainstTheProductionLedger() {
    val token = ByteArray(16) { (0xa0 + it).toByte() }
    val serverNonce = ByteArray(32) { it.toByte() }
    val clientNonce = ByteArray(32) { (it + 32).toByte() }
    rows("/netplay-v9/invitation-lifecycle-vectors.tsv").forEach { row ->
      val clock = FakeClock(0)
      val ttl = row.long("ttl_seconds")
      val host =
          V9InvitationHost(
              V9LinkMode.FOUR_PLAYER,
              clock,
              V9UtcSeconds { 1 },
              fixedRandom(token),
          )
      if (ttl !in 60..600) {
        val error = assertFailsWith<V9InvitationParseException>(row.getValue("id")) {
          host.createInvitation("play.example", 6688, row.int("bound_slot"), ttl)
        }
        assertEquals(V9InvitationError.INV_EXPIRY, error.reason)
        assertEquals("INV_EXPIRY", row.getValue("expected"))
        host.close()
        return@forEach
      }
      val invitation =
          host.createInvitation("play.example", 6688, row.int("bound_slot"), ttl)
      val boundAuth =
          V9Auth(
              row.int("bound_slot"),
              V9AuthCodec.proof(
                  token,
                  serverNonce,
                  clientNonce,
                  row.int("bound_slot"),
              ),
          )
      when (row.getValue("precondition")) {
        "fresh" -> Unit
        "used" -> assertIs<V9Authentication.Accepted>(
            host.authenticate(boundAuth, serverNonce, clientNonce),
        )
        "stopped" -> host.close()
        "eight-failures" -> {
          val wrong = boundAuth.proof().also { it[0] = (it[0].toInt() xor 1).toByte() }
          repeat(8) {
            assertEquals(
                V9Authentication.Failed,
                host.authenticate(
                    V9Auth(row.int("bound_slot"), wrong),
                    serverNonce,
                    clientNonce,
                ),
            )
          }
        }
        else -> error("unknown precondition")
      }
      clock.now = row.long("now_millis")
      val candidateSlot = row.int("candidate_slot")
      val candidate =
          V9Auth(
              candidateSlot,
              V9AuthCodec.proof(token, serverNonce, clientNonce, candidateSlot),
          )
      val outcome = host.authenticate(candidate, serverNonce, clientNonce)
      val actual = if (outcome is V9Authentication.Accepted) "SUCCESS" else "AUTH_FAILED"
      assertEquals(row.getValue("expected"), actual, row.getValue("id"))
      if (outcome is V9Authentication.Accepted) outcome.reservation.close()
      invitation.close()
      host.close()
    }
  }

  @Test
  fun invitationConsumptionAndSlotReservationAreOneAtomicDecision() {
    val clock = FakeClock()
    val token = ByteArray(16) { it.toByte() }
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            clock,
            V9UtcSeconds { 1 },
            fixedRandom(token),
        )
    host.createInvitation("play.example", 6688, 1)
    val auth =
        V9Auth(
            1,
            V9AuthCodec.proof(token, ByteArray(32) { 1 }, ByteArray(32) { 2 }, 1),
        )
    val start = CountDownLatch(1)
    val results = mutableListOf<V9Authentication>()
    val lock = Any()
    val threads =
        List(2) {
          thread {
            start.await()
            val result = host.authenticate(auth, ByteArray(32) { 1 }, ByteArray(32) { 2 })
            synchronized(lock) { results += result }
          }
        }
    start.countDown()
    threads.forEach(Thread::join)
    assertEquals(1, results.count { it is V9Authentication.Accepted })
    assertEquals(1, results.count { it === V9Authentication.Failed })
    assertEquals(setOf(1), host.occupiedSlots())
    assertEquals(0, host.outstandingInvitations())

    val first = results.filterIsInstance<V9Authentication.Accepted>().single().reservation
    first.close()
    assertTrue(host.occupiedSlots().isEmpty())
    host.close()
  }

  @Test
  fun monotonicExpiryRemovesTokenExactlyAtDeadlineAndInjectedSchedulerIsCallerOwned() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            clock,
            V9UtcSeconds { 1 },
            fixedRandom(ByteArray(16) { 7 }),
            scheduler,
        )
    host.createInvitation("play.example", 6688, 1, 60)
    clock.now = 59_999
    scheduler.runDue()
    assertEquals(1, host.outstandingInvitations())
    clock.now = 60_000
    scheduler.runDue()
    assertEquals(0, host.outstandingInvitations())
    host.close()
    assertFalse(scheduler.closed.get())
  }

  @Test
  fun collisionDoesNotConsumeReplacementInvitationAndOtherFourPlayerSlotsStayIndependent() {
    val clock = FakeClock()
    val random = SequenceRandom(
        listOf(
            ByteArray(16) { 1 },
            ByteArray(16) { 2 },
            ByteArray(16) { 3 },
            ByteArray(16) { 4 },
        ),
    )
    val host =
        V9InvitationHost(
            V9LinkMode.FOUR_PLAYER,
            clock,
            V9UtcSeconds { 1 },
            random,
        )
    val serverNonce = ByteArray(32) { 9 }
    val clientNonce = ByteArray(32) { 8 }
    val firstInvitation = host.createInvitation("play.example", 6688, 1)
    val first =
        assertIs<V9Authentication.Accepted>(
            host.authenticate(
                auth(firstInvitation, serverNonce, clientNonce),
                serverNonce,
                clientNonce,
            ),
        )
    val replacement = host.createInvitation("play.example", 6688, 1)
    val replacementUri = replacement.render()
    assertEquals(
        V9Authentication.SlotFull,
        host.authenticate(auth(replacement, serverNonce, clientNonce), serverNonce, clientNonce),
    )
    val slot2 = host.createInvitation("play.example", 6688, 2)
    val second =
        assertIs<V9Authentication.Accepted>(
            host.authenticate(auth(slot2, serverNonce, clientNonce), serverNonce, clientNonce),
        )
    assertEquals(setOf(1, 2), host.occupiedSlots())
    first.reservation.close()
    assertIs<V9Authentication.Accepted>(
        host.authenticate(
            auth(V9Invitation.parse(replacementUri), serverNonce, clientNonce),
            serverNonce,
            clientNonce,
        ),
    )
    second.reservation.close()
    host.close()
  }

  @Test
  fun fragmentedTransportAuthenticatesThenStopsBeforeManifestOrPrivateTraffic() {
    val token = ByteArray(16) { (0xa0 + it).toByte() }
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            FakeClock(),
            V9UtcSeconds { 2_000_000_000L },
            fixedRandom(token),
        )
    val invitation = host.createInvitation("play.example", 6688, 1)
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(1)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            nonce = ByteArray(32) { it.toByte() },
            invitationHost = host,
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            nonce = ByteArray(32) { (it + 32).toByte() },
            clientInvitation = invitation.forClientAuthentication(),
        )
    try {
      server.start()
      client.start()
      assertEquals(
          V9LifecycleState.SEND_SERVER_MANIFEST,
          server.awaitPostAuthBoundary(5, TimeUnit.SECONDS).state,
      )
      assertEquals(
          V9LifecycleState.WAIT_SERVER_MANIFEST,
          client.awaitPostAuthBoundary(5, TimeUnit.SECONDS).state,
      )
      assertEquals(1, server.authenticatedSlot())
      assertEquals(1, client.authenticatedSlot())
      assertEquals(
          V9PostAuthBoundary(
              V9Role.SERVER,
              V9LinkMode.NORMAL,
              1,
              V9LifecycleState.SEND_SERVER_MANIFEST,
          ),
          server.postAuthBoundary(),
      )
      assertFailsWith<V9ProtocolException> { server.sendUnavailable(V9MessageType.MANIFEST) }
      assertFailsWith<V9ProtocolException> { client.sendUnavailable(V9MessageType.ROM_BEGIN) }
      assertEquals(setOf(1), host.occupiedSlots())

      val allWire = serverChannel.recordedBytes() + clientChannel.recordedBytes()
      assertFalse(allWire.containsSubsequence(token), "raw invitation token appeared on the wire")
      assertFalse(allWire.toString(StandardCharsets.ISO_8859_1).contains("coffeegb://"))
      assertEquals(
          setOf(V9MessageType.HELLO, V9MessageType.AUTH_RESULT),
          decodeRecorded(serverChannel.recordedBytes()).map { it.header.type }.toSet(),
      )
      assertEquals(
          setOf(V9MessageType.HELLO, V9MessageType.AUTH),
          decodeRecorded(clientChannel.recordedBytes()).map { it.header.type }.toSet(),
      )
    } finally {
      client.close()
      server.close()
      assertTrue(host.occupiedSlots().isEmpty())
      host.close()
    }
    assertTrue(serverChannel.closed.get())
    assertTrue(clientChannel.closed.get())
  }

  @Test
  fun wrongProofIsOneGenericTerminalAuthResultAndNeverReservesTheSlot() {
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            FakeClock(),
            V9UtcSeconds { 2_000_000_000L },
            fixedRandom(ByteArray(16) { (0xa0 + it).toByte() }),
        )
    host.createInvitation("play.example", 6688, 1)
    val wrong =
        V9Invitation.parse(
                "coffeegb://play.example:6688/join?v=9&mode=normal&slot=1" +
                    "&exp=2000000300&token=AAECAwQFBgcICQoLDA0ODw",
            )
            .forClientAuthentication()
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(7)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            nonce = ByteArray(32) { 1 },
            invitationHost = host,
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            nonce = ByteArray(32) { 2 },
            clientInvitation = wrong,
        )
    try {
      server.start()
      client.start()
      assertEquals(
          V9ErrorCode.AUTH_FAILED,
          client.awaitPostAuthBoundary(5, TimeUnit.SECONDS).failure?.reason,
      )
      awaitClosed(server)
      assertEquals(V9ErrorCode.AUTH_FAILED, server.snapshot().failure?.reason)
      assertTrue(host.occupiedSlots().isEmpty())
      assertEquals(1, host.outstandingInvitations())

      val resultFrames = decodeRecorded(serverChannel.recordedBytes())
      val result = resultFrames.single { it.header.type == V9MessageType.AUTH_RESULT }
      assertEquals(
          V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask,
          result.header.flags,
      )
      assertEquals(1, result.header.correlation)
      assertEquals(
          V9AuthStatus.REJECTED,
          V9AuthCodec.decodeResult(result.payload(), result.header.flags).status,
      )
      resultFrames.forEach(V9Frame::close)
    } finally {
      client.close()
      server.close()
      host.close()
    }
  }

  @Test
  fun authFramesAreIncrementalCoalescedAndLaterPrivateDeclarationsFailBeforeAllocation() {
    val proof = ByteArray(32) { it.toByte() }
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.AUTH),
            negotiatedCapabilities = V9Capability.entries.toSet(),
        )
    fun frame(sequence: Long): ByteArray =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.AUTH,
                0,
                sequence,
                0,
                ProtocolV9.CONTROL_CHANNEL,
                V9AuthCodec.encode(V9Auth(1, proof)),
            ),
            policy,
        )
    val first = frame(0)
    val second = frame(1)
    val fragmented = V9IncrementalDecoder(policy = policy)
    first.forEachIndexed { index, byte ->
      val batch = fragmented.feed(byteArrayOf(byte))
      assertNull(batch.failure)
      if (index < first.lastIndex) {
        assertTrue(batch.needsMore)
        assertTrue(batch.frames.isEmpty())
      } else {
        assertEquals(V9MessageType.AUTH, batch.frames.single().header.type)
        batch.frames.single().close()
      }
    }
    val coalesced = V9IncrementalDecoder(policy = policy).feed(first + second)
    assertNull(coalesced.failure)
    assertEquals(listOf(0L, 1L), coalesced.frames.map { it.header.sequence })
    coalesced.frames.forEach(V9Frame::close)

    val privateFrame =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.ROM_BEGIN,
                0,
                0,
                0,
                1,
                ByteArray(52),
            ),
            V9DecoderPolicy(
                allowedMessages = setOf(V9MessageType.ROM_BEGIN),
                negotiatedCapabilities = V9Capability.entries.toSet(),
            ),
        )
    val rejected =
        V9IncrementalDecoder(
                policy =
                    V9DecoderPolicy(
                        allowedMessages =
                            setOf(
                                V9MessageType.HELLO,
                                V9MessageType.AUTH,
                                V9MessageType.AUTH_RESULT,
                                V9MessageType.ERROR,
                            ),
                    ),
            )
            .feed(privateFrame.copyOf(ProtocolV9.HEADER_BYTES))
    assertEquals(V9ErrorCode.UNEXPECTED_MESSAGE, rejected.failure?.reason)
    assertEquals(0, rejected.payloadAllocations)
    assertEquals(32, rejected.failure?.decisiveBytes)
  }

  @Test
  fun rejectedSocketCandidateDoesNotPoisonListenerAndLaterValidCandidateOwnsThenReleasesSlot() {
    repeat(32) { round ->
      rejectedThenValidSocketCandidate(round)
    }
  }

  private fun rejectedThenValidSocketCandidate(round: Int) {
    val token = ByteArray(16) { (0xa0 + it).toByte() }
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            random = fixedRandom(token),
        )
    val invitation = host.createInvitation("127.0.0.1", 1, 1)
    val accepted = mutableListOf<V9FoundationConnection>()
    val callbackCount = CountDownLatch(2)
    V9FoundationServer(
            invitationHost = host,
            onAwaitingPairing = {
              synchronized(accepted) { accepted += it }
              callbackCount.countDown()
            },
        )
        .use { server ->
          server.start()
          val address = InetSocketAddress("127.0.0.1", server.localPort)
          val wrong =
              V9Invitation.parse(
                      "coffeegb://127.0.0.1:${server.localPort}/join?v=9&mode=normal&slot=1" +
                          "&exp=2000000300&token=AAECAwQFBgcICQoLDA0ODw",
                  )
                  .forClientAuthentication()
          val rejected = V9FoundationClient.connect(address, invitation = wrong)
          try {
            val boundary = rejected.awaitPostAuthBoundary(5, TimeUnit.SECONDS)
            assertEquals(
                V9ErrorCode.AUTH_FAILED,
                boundary.failure?.reason,
                "round=$round client=$boundary servers=${serverSnapshots(accepted)}",
            )
          } finally {
            rejected.close()
          }

          val valid = V9FoundationClient.connect(
              address,
              invitation = invitation.forClientAuthentication(),
          )
          try {
            val boundary = valid.awaitPostAuthBoundary(5, TimeUnit.SECONDS)
            assertEquals(
                V9LifecycleState.WAIT_SERVER_MANIFEST,
                boundary.state,
                "round=$round client=$boundary servers=${serverSnapshots(accepted)}",
            )
            assertTrue(callbackCount.await(5, TimeUnit.SECONDS))
            assertEquals(setOf(1), host.occupiedSlots())
          } finally {
            valid.close()
          }
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
          while (host.occupiedSlots().isNotEmpty() && System.nanoTime() < deadline) {
            Thread.yield()
          }
          assertTrue(host.occupiedSlots().isEmpty())
          synchronized(accepted) { accepted.forEach(V9FoundationConnection::close) }
        }
    assertEquals(0, host.outstandingInvitations())
  }

  private fun serverSnapshots(
      accepted: MutableList<V9FoundationConnection>,
  ): List<V9LifecycleSnapshot> =
      synchronized(accepted) { accepted.map(V9FoundationConnection::snapshot) }

  @Test(timeout = 25_000)
  fun realSocketUsesTenSecondPreManifestDeadlineInsteadOfFiveSecondReadTimeout() {
    val token = ByteArray(16) { (0xa0 + it).toByte() }
    val host =
        V9InvitationHost(
            V9LinkMode.NORMAL,
            random = fixedRandom(token),
        )
    val invitation = host.createInvitation("127.0.0.1", 1, 1)
    val accepted = LinkedBlockingQueue<V9FoundationConnection>()
    var client: V9FoundationConnection? = null
    var serverConnection: V9FoundationConnection? = null
    try {
      V9FoundationServer(
              invitationHost = host,
              onAwaitingPairing = accepted::offer,
          )
          .use { server ->
          server.start()
          client =
              V9FoundationClient.connect(
                  InetSocketAddress("127.0.0.1", server.localPort),
                  invitation = invitation.forClientAuthentication(),
              )
          serverConnection = accepted.poll(5, TimeUnit.SECONDS)
          assertNotNull(serverConnection)
          assertEquals(
              V9LifecycleState.WAIT_SERVER_MANIFEST,
              client!!.awaitPostAuthBoundary(5, TimeUnit.SECONDS).state,
          )
          awaitState(serverConnection!!, V9LifecycleState.SEND_SERVER_MANIFEST, 5_000)
          val authenticatedAt = System.nanoTime()

          Thread.sleep(6_000)
          assertFalse(client!!.isClosed(), client!!.snapshot().toString())
          assertFalse(serverConnection!!.isClosed(), serverConnection!!.snapshot().toString())
          assertEquals(setOf(1), host.occupiedSlots())

          val closeDeadline = authenticatedAt + TimeUnit.SECONDS.toNanos(14)
          while ((!client!!.isClosed() ||
                  !serverConnection!!.isClosed() ||
                  host.occupiedSlots().isNotEmpty()) &&
              System.nanoTime() < closeDeadline) {
            Thread.sleep(10)
          }
          val elapsedMillis =
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - authenticatedAt)
          assertTrue(elapsedMillis >= 9_000, "closed before frozen deadline: ${elapsedMillis}ms")
          assertTrue(elapsedMillis < 14_000, "did not close near frozen deadline: ${elapsedMillis}ms")
          assertTrue(client!!.isClosed())
          assertTrue(serverConnection!!.isClosed())
          assertTrue(host.occupiedSlots().isEmpty())
          assertTrue(
              listOf(client!!.snapshot(), serverConnection!!.snapshot())
                  .any { it.failure?.reason == V9ErrorCode.TIMEOUT },
          )
          client!!.close()
          serverConnection!!.close()
          assertTrue(host.occupiedSlots().isEmpty())
          }
    } finally {
      client?.close()
      serverConnection?.close()
      host.close()
    }
  }

  @Test
  fun authStageDeadlinesExpireAtExactlyFiveSeconds() {
    val cases =
        listOf(
            Triple(V9Role.CLIENT, V9LifecycleState.SEND_AUTH) { value: V9Lifecycle ->
              value.serverHelloReceived()
              value.clientHelloSent(requiredCapabilities())
            },
            Triple(V9Role.SERVER, V9LifecycleState.WAIT_AUTH) { value: V9Lifecycle ->
              value.serverHelloSent()
              value.clientHelloReceived(requiredCapabilities())
            },
            Triple(V9Role.CLIENT, V9LifecycleState.WAIT_AUTH_RESULT) { value: V9Lifecycle ->
              value.serverHelloReceived()
              value.clientHelloSent(requiredCapabilities())
              value.clientAuthSent()
            },
            Triple(V9Role.SERVER, V9LifecycleState.SEND_AUTH_RESULT) { value: V9Lifecycle ->
              value.serverHelloSent()
              value.clientHelloReceived(requiredCapabilities())
              value.clientAuthReceived()
            },
        )
    cases.forEach { (role, expectedState, prepare) ->
      val clock = FakeClock()
      val lifecycle = V9Lifecycle(role, clock)
      prepare(lifecycle)
      assertEquals(expectedState, lifecycle.snapshot().state)
      clock.now = 4_999
      assertNull(lifecycle.checkDeadline())
      assertEquals(expectedState, lifecycle.snapshot().state)
      clock.now = 5_000
      assertEquals(V9ErrorCode.TIMEOUT, lifecycle.checkDeadline()?.reason)
      assertEquals(V9LifecycleState.CLOSED, lifecycle.snapshot().state)
    }
  }

  @Test
  fun preManifestDeadlinesRemainOpenAtNineThousandNineHundredNinetyNine() {
    val cases =
        listOf(
            Triple(V9Role.SERVER, V9LifecycleState.SEND_SERVER_MANIFEST) {
                value: V9Lifecycle ->
              value.serverHelloSent()
              value.clientHelloReceived(requiredCapabilities())
              value.clientAuthReceived()
              value.serverAuthResultSent()
            },
            Triple(V9Role.CLIENT, V9LifecycleState.WAIT_SERVER_MANIFEST) {
                value: V9Lifecycle ->
              value.serverHelloReceived()
              value.clientHelloSent(requiredCapabilities())
              value.clientAuthSent()
              value.serverAuthResultReceived()
            },
        )
    cases.forEach { (role, expectedState, prepare) ->
      val clock = FakeClock()
      val lifecycle = V9Lifecycle(role, clock)
      prepare(lifecycle)
      assertEquals(expectedState, lifecycle.snapshot().state)
      clock.now = 9_999
      assertNull(lifecycle.checkDeadline())
      assertEquals(expectedState, lifecycle.snapshot().state)
      clock.now = 10_000
      assertEquals(V9ErrorCode.TIMEOUT, lifecycle.checkDeadline()?.reason)
      assertEquals(V9LifecycleState.CLOSED, lifecycle.snapshot().state)
    }
  }

  private fun requiredCapabilities() =
      V9NegotiatedCapabilities(V9Capability.requiredCapabilities)

  private fun awaitClosed(connection: V9FoundationConnection) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (connection.snapshot().state != V9LifecycleState.CLOSED &&
        System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertEquals(V9LifecycleState.CLOSED, connection.snapshot().state)
  }

  private fun awaitState(
      connection: V9FoundationConnection,
      state: V9LifecycleState,
      timeoutMillis: Long,
  ) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (connection.snapshot().state != state && System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertEquals(state, connection.snapshot().state)
  }

  private fun auth(
      invitation: V9Invitation,
      serverNonce: ByteArray,
      clientNonce: ByteArray,
  ): V9Auth {
    val client = invitation.forClientAuthentication()
    return try {
      client.createAuth(serverNonce, clientNonce)
    } finally {
      client.close()
    }
  }

  private fun fixedRandom(bytes: ByteArray) = V9SecureRandom { target ->
    require(target.size == bytes.size)
    System.arraycopy(bytes, 0, target, 0, target.size)
  }

  private fun decodeRecorded(bytes: ByteArray): List<V9Frame> {
    val decoder =
        V9IncrementalDecoder(
            policy =
                V9DecoderPolicy(
                    allowedMessages =
                        setOf(
                            V9MessageType.HELLO,
                            V9MessageType.AUTH,
                            V9MessageType.AUTH_RESULT,
                        ),
                ),
        )
    val result = decoder.feed(bytes)
    assertNull(result.failure)
    return result.frames
  }

  private class FakeClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class ManualScheduler(private val clock: FakeClock) :
      V9DeadlineScheduler, AutoCloseable {
    private val tasks = mutableListOf<Task>()
    val closed = AtomicBoolean(false)

    @Synchronized
    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
      val task = Task(deadlineMillis, action)
      tasks += task
      return Closeable { task.active.set(false) }
    }

    @Synchronized
    fun runDue() {
      tasks.filter { it.active.get() && it.deadline <= clock.now }.forEach {
        if (it.active.compareAndSet(true, false)) it.action.run()
      }
    }

    override fun close() {
      closed.set(true)
    }

    private data class Task(
        val deadline: Long,
        val action: Runnable,
        val active: AtomicBoolean = AtomicBoolean(true),
    )
  }

  private class SequenceRandom(values: List<ByteArray>) : V9SecureRandom {
    private val values = ArrayDeque(values.map(ByteArray::copyOf))

    override fun nextBytes(target: ByteArray) {
      val value = values.removeFirst()
      require(value.size == target.size)
      System.arraycopy(value, 0, target, 0, target.size)
    }
  }

  private class RecordingMemoryChannel(private val maximumWrite: Int) : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    private val recorded = mutableListOf<Byte>()
    val closed = AtomicBoolean(false)
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

    companion object {
      fun pair(maximumWrite: Int): Pair<RecordingMemoryChannel, RecordingMemoryChannel> {
        val first = RecordingMemoryChannel(maximumWrite)
        val second = RecordingMemoryChannel(maximumWrite)
        first.peer = second
        second.peer = first
        return first to second
      }
    }
  }

  private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return indices.any { start ->
      start <= size - needle.size &&
          needle.indices.all { this[start + it] == needle[it] }
    }
  }

  private fun rows(resource: String): List<Map<String, String>> {
    val text =
        requireNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
    val lines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
    val header = lines.first().split('\t')
    return lines.drop(1).map { line ->
      val values = line.split('\t')
      require(values.size == header.size) { "Malformed $resource row: $line" }
      header.zip(values).toMap()
    }
  }

  private fun Map<String, String>.int(name: String): Int = getValue(name).toInt()

  private fun Map<String, String>.long(name: String): Long = getValue(name).toLong()

  private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
      value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
  }
}
