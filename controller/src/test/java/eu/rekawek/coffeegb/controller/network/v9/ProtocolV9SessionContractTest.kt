package eu.rekawek.coffeegb.controller.network.v9

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Executable Phase #346 session contract. This is deliberately test-only: Phase #347 consumes
 * these registries and models when production protocol v9 is implemented.
 */
class ProtocolV9SessionContractTest {

  @Test
  fun headerFailuresFollowTheExactPreallocationPrecedence() {
    val specs = messageSpecs()
    rows("/netplay-v9/header-precedence-vectors.tsv").forEach { row ->
      val bytes = headerFrom(row)
      val preflight = HeaderPreflight(
          specs,
          expectedSequence = 1,
          currentFrames = row.long("current_frames"),
          currentWireBytes = row.long("current_wire"),
          currentDecodedBytes = row.long("current_decoded"),
      )
      var result: HeaderPreflight.Result? = null
      bytes.forEach { byte ->
        if (result == null) result = preflight.feed(byte)
      }
      val final = result ?: preflight.finish()
      assertEquals(row.getValue("expected"), final.outcome, row.getValue("id"))
      assertEquals(row.int("decisive_read"), final.consumed, row.getValue("id"))
      assertEquals(row.int("payload_reservations"), final.payloadReservations, row.getValue("id"))
      assertEquals(0, final.payloadBytesRead, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-header-precedence", row.getValue("provenance"))
    }
  }

  @Test
  fun requestResponseLedgerFreezesRelationshipsAndUnsignedSequenceExhaustion() {
    rows("/netplay-v9/response-vectors.tsv").forEach { row ->
      val outstandingSequence = row.getValue("outstanding_sequence").takeUnless { it == "-" }
          ?.toLong()
      val expectedSequence = row.long("expected_sequence")
      val ledger = ResponseLedger(expectedSequence.takeUnless { it == U32_MAX })
      if (outstandingSequence != null) {
        ledger.recordPeerRequest(
            outstandingSequence,
            row.getValue("outstanding_message"),
            row.getValue("already_responded").toBoolean(),
        )
      }
      val result = ledger.accept(
          row.long("incoming_sequence"),
          row.getValue("message"),
          row.hexInt("flags"),
          row.long("correlation"),
      )
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
      assertEquals(
          row.getValue("next_sequence"),
          ledger.nextSequence?.toString() ?: "EXHAUSTED",
          row.getValue("id"),
      )
      assertEquals("coffee-gb-synthetic-response", row.getValue("provenance"))
    }

    val duplicate = ResponseLedger(1)
    duplicate.recordPeerRequest(7, "AUTH", false)
    assertEquals("SUCCESS", duplicate.accept(1, "AUTH_RESULT", RESPONSE, 7))
    duplicate.forceNextSequence(2)
    assertEquals("CORRELATION_ERROR", duplicate.accept(2, "AUTH_RESULT", RESPONSE, 7))
  }

  @Test
  fun negotiatedCapabilitiesGateModesClassesCompressionAndLiveness() {
    rows("/netplay-v9/capability-gate-vectors.tsv").forEach { row ->
      val capabilities = row.getValue("negotiated").split(',').map(String::toInt).toSet()
      val result = CapabilityGate.validate(
          capabilities,
          row.getValue("mode"),
          row.getValue("message"),
          row.hexInt("flags"),
      )
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-capability-gate", row.getValue("provenance"))
    }
  }

  @Test
  fun everyMessagePayloadHasStructuralAndStatefulValidation() {
    val context = PayloadContext(mode = "normal", authenticatedPlayer = 1, rosterMask = 0x03)

    val hello = helloPayload((1..7).toSet())
    assertEquals(null, PayloadSchemas.validate("HELLO", 0, 0, hello, context))
    assertEquals("CAPABILITY_MISMATCH",
        PayloadSchemas.validate("HELLO", 0, 0, hello.copyOf().also { it[3] = 1 }, context))

    val auth = ByteBuffer.allocate(36).put(1).put(ByteArray(3)).put(ByteArray(32) { 1 }).array()
    assertEquals(null, PayloadSchemas.validate("AUTH", 0, 0, auth, context))
    assertEquals("AUTH_FAILED",
        PayloadSchemas.validate("AUTH", 0, 0, auth.copyOf().also { it[0] = 0 }, context))
    assertEquals("AUTH_FAILED",
        PayloadSchemas.validate("AUTH", 0, 0, auth.copyOf().also { it[1] = 1 }, context))

    val authAccepted = ByteBuffer.allocate(4).putShort(0).putShort(0).array()
    val authRejected = ByteBuffer.allocate(4).putShort(1).putShort(0).array()
    assertEquals(null,
        PayloadSchemas.validate("AUTH_RESULT", RESPONSE, 0, authAccepted, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("AUTH_RESULT", RESPONSE or TERMINAL, 0, authAccepted, context))
    assertEquals(null,
        PayloadSchemas.validate("AUTH_RESULT", RESPONSE or TERMINAL, 0, authRejected, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("AUTH_RESULT", RESPONSE, 0, authRejected, context))

    val manifest = validNormalManifest().encode()
    assertEquals(null, PayloadSchemas.validate("MANIFEST", 0, 0, manifest, context))
    assertEquals("MANIFEST_MISMATCH",
        PayloadSchemas.validate(
            "MANIFEST", 0, 0, manifest.copyOf().also { it[20] = (it[20] + 1).toByte() },
            context))
    val invalidTitle = validNormalManifest(
        entries = normalEntries().mapIndexed { index, entry ->
          if (index == 1) entry.copy(title = "BAD\nTITLE") else entry
        }).encode()
    assertEquals("MANIFEST_MISMATCH",
        PayloadSchemas.validate("MANIFEST", 0, 0, invalidTitle, context))
    val proposedManifest = validNormalManifest(proposals = listOf(romProposal())).encode()
    val firstDiff = 52 + normalEntries().sumOf { it.encode().size }
    assertEquals("MANIFEST_MISMATCH", PayloadSchemas.validate(
        "MANIFEST", 0, 0, proposedManifest.copyOf().also {
          it[firstDiff + 2] = INFORMATIONAL.toByte()
        }, context))
    assertEquals("MANIFEST_MISMATCH", PayloadSchemas.validate(
        "MANIFEST", 0, 0, proposedManifest.copyOf().also {
          it[firstDiff + 7] = 42
        }, context))

    val serverManifest = sha256(manifest)
    val clientManifest = sha256(validNormalManifest(sender = 1).encode())
    val proposal = romProposal()
    val consent = consentPayload(proposal, actor = 0, decision = APPROVE,
        serverManifest = serverManifest, clientManifest = clientManifest)
    assertEquals(null, PayloadSchemas.validate("CONSENT", 0, 0, consent, context))
    assertEquals("CONSENT_REJECTED",
        PayloadSchemas.validate("CONSENT", 0, 0,
            consent.copyOf().also { it[11] = 1 }, context))

    val start = ByteBuffer.allocate(16).putLong(77).putLong(9).array()
    assertEquals(null, PayloadSchemas.validate("START", 0, 0, start, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("START", 0, 0, start.copyOf().also {
          for (i in 0..7) it[i] = 0
        }, context))
    val readyContext = context.copy(sessionId = 77)
    assertEquals(null, PayloadSchemas.validate(
        "READY", RESPONSE, 0, ByteBuffer.allocate(8).putLong(77).array(), readyContext))
    assertEquals("CORRELATION_ERROR", PayloadSchemas.validate(
        "READY", RESPONSE, 0, ByteBuffer.allocate(8).putLong(78).array(), readyContext))

    val input = ByteBuffer.allocate(16).putLong(12).put(1).put(0x11).putShort(0)
        .putInt(0).array()
    assertEquals(null, PayloadSchemas.validate("INPUT", 0, 2, input, context))
    assertEquals("TOPOLOGY_MISMATCH",
        PayloadSchemas.validate("INPUT", 0, 1, input, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("INPUT", 0, 2, input.copyOf().also { it[15] = 1 }, context))

    val reset = ByteBuffer.allocate(16).putLong(12).put(1).put(ByteArray(7)).array()
    assertEquals(null, PayloadSchemas.validate("RESET", 0, 2, reset, context))
    assertEquals(null, PayloadSchemas.validate("STOP", 0, 2, reset, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("STOP", 0, 2, reset.copyOf().also { it[9] = 1 }, context))

    val ping = ByteBuffer.allocate(16).putLong(0x0102030405060708L).putLong(9).array()
    assertEquals(null, PayloadSchemas.validate("PING", 0, 0, ping, context))
    assertEquals(null, PayloadSchemas.validate(
        "PONG", RESPONSE, 0, ping, context.copy(outstandingPingNonce = 0x0102030405060708L)))
    assertEquals("CORRELATION_ERROR", PayloadSchemas.validate(
        "PONG", RESPONSE, 0, ping, context.copy(outstandingPingNonce = 3)))

    val cancel = terminalPayload(1, "cancel")
    assertEquals(null, PayloadSchemas.validate("CANCEL", TERMINAL, 0, cancel, context))
    assertEquals(null, PayloadSchemas.validate("GOODBYE", TERMINAL, 0,
        terminalPayload(0, ""), context))
    assertEquals("STRICT_UTF8", PayloadSchemas.validate("CANCEL", TERMINAL, 0,
        terminalPayload(1, "bad\ntext"), context))

    val error = errorPayload(0x10, 2, 7, "full")
    assertEquals(null, PayloadSchemas.validate("ERROR", TERMINAL, 0, error, context))
    assertEquals("MALFORMED_HEADER", PayloadSchemas.validate("ERROR", TERMINAL, 0,
        error.copyOf().also { it[10] = 1 }, context))
  }

  @Test
  fun bulkTransactionsAreDirectionalConsentBoundAndAtomic() {
    val proposal = romProposal()
    val serverManifest = ByteArray(32) { 1 }
    val clientManifest = ByteArray(32) { 2 }
    val ledger = ConsentLedger(proposal, serverManifest, clientManifest)
    val tracker = BulkTracker(ledger)
    val declaration = BulkDeclaration(
        transactionId = 9,
        proposalId = proposal.id,
        source = proposal.source,
        target = proposal.target,
        owner = proposal.owner,
        assetKind = proposal.assetKind,
        total = proposal.size,
        digest = proposal.digest,
        chunkSize = 65_536,
    )
    assertEquals("CONSENT_REJECTED", tracker.begin("ROM", 2, declaration))
    assertEquals(0, tracker.retainedBytes)
    assertEquals("CONSENT_REQUIRED", ledger.decide(consentFor(
        proposal, actor = 0, serverManifest, clientManifest)))
    assertEquals("SUCCESS", ledger.decide(consentFor(
        proposal, actor = 1, serverManifest, clientManifest)))
    assertEquals("SUCCESS", tracker.begin("ROM", 2, declaration))
    assertEquals("CONSENT_REJECTED", tracker.begin("ROM", 2, declaration))

    val first = ByteArray(16_384) { (it and 0xff).toByte() }
    val second = ByteArray(proposal.size.toInt() - first.size) {
      ((it + first.size) and 0xff).toByte()
    }
    val actualDigest = sha256(first + second)
    val digestDeclaration = declaration.copy(digest = actualDigest)
    val exactLedger = ConsentLedger(proposal.copy(digest = actualDigest), serverManifest, clientManifest)
    assertEquals("CONSENT_REQUIRED", exactLedger.decide(consentFor(
        proposal.copy(digest = actualDigest), 0, serverManifest, clientManifest)))
    assertEquals("SUCCESS", exactLedger.decide(consentFor(
        proposal.copy(digest = actualDigest), 1, serverManifest, clientManifest)))
    val exact = BulkTracker(exactLedger)
    assertEquals("SUCCESS", exact.begin("ROM", 2, digestDeclaration))
    assertEquals("SUCCESS", exact.chunk(9, 0, first))
    assertEquals("TRANSACTION_MISMATCH", exact.chunk(9, 1, second))
    assertEquals(first.size, exact.retainedBytes)
    assertEquals("SUCCESS", exact.chunk(9, first.size.toLong(), second))
    assertEquals("SUCCESS", exact.end(9, actualDigest))
    assertTrue(exact.committed)

    val battery = declaration.copy(assetKind = BATTERY_ASSET)
    assertEquals("CONSENT_REJECTED", BulkTracker(ledger).begin("BATTERY", 2, battery))

    val checkpointProposal = Proposal(
        id = 43, action = OFFER, assetClass = CHECKPOINT_CLASS,
        assetKind = CHECKPOINT_ASSET, owner = GROUP_PLAYER, source = 0, target = 1,
        size = 0, digest = ByteArray(32))
    val checkpointLedger = ConsentLedger(checkpointProposal, serverManifest, clientManifest)
    val checkpointDeclaration = BulkDeclaration(
        10, 43, 0, 1, GROUP_PLAYER, CHECKPOINT_ASSET, 0, ByteArray(32), 0)
    assertFalse(checkpointLedger.claim(checkpointDeclaration))
    assertEquals("CONSENT_REQUIRED", checkpointLedger.decide(
        consentFor(checkpointProposal, 0, serverManifest, clientManifest)))
    assertFalse(checkpointLedger.claim(checkpointDeclaration))
    assertEquals("SUCCESS", checkpointLedger.decide(
        consentFor(checkpointProposal, 1, serverManifest, clientManifest)))
    assertTrue(checkpointLedger.claim(checkpointDeclaration))
    assertFalse(checkpointLedger.claim(checkpointDeclaration))
  }

  @Test
  fun hostCoordinatorOwnsSlotsChannelsRosterAndOneAtomicFourPlayerCheckpoint() {
    rows("/netplay-v9/topology-vectors.tsv").forEach { row ->
      val coordinator = TopologyCoordinator(row.getValue("mode"), row.hexInt("occupied_mask"))
      val result = coordinator.prepareCandidate(
          authenticatedPlayer = row.int("authenticated_player"),
          candidatePlayer = row.int("candidate_player"),
          rosterMask = row.hexInt("roster_mask"),
          checkpointKind = row.getValue("checkpoint_kind"),
          checkpointMask = row.hexInt("checkpoint_mask"),
      )
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
      assertEquals(row.hexInt("committed_mask"), coordinator.liveMask, row.getValue("id"))
      assertEquals(row.getValue("candidate_mutated").toBoolean(),
          coordinator.candidateCommitted, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-topology", row.getValue("provenance"))
    }

    assertEquals(1, playerChannel(0))
    assertEquals(2, playerChannel(1))
    assertEquals(3, playerChannel(2))
    assertEquals(4, playerChannel(3))

    val roster = validFourRoster()
    val commitment = roster.rosterDigest()
    val checkpoint = ByteArray(128) { it.toByte() }
    val checkpointDigest = sha256(checkpoint)
    val perGuest = (1..3).map { guest ->
      FourGuestSession(guest, roster.rosterMask, commitment, checkpointDigest)
    }
    assertTrue(perGuest.all { it.manifestCoversHostAndGuest() })
    assertTrue(perGuest.all { it.checkpointDigest.contentEquals(checkpointDigest) })
    assertFalse(perGuest.any { it.canStart })
    perGuest.forEach { it.approveCheckpointFromHost() }
    assertTrue(perGuest.all { it.canStart })
  }

  @Test
  fun manifestDiffAndItemConsentVectorsExecuteNoImplicitTransferPolicy() {
    rows("/netplay-v9/manifest-consent-vectors.tsv").forEach { row ->
      val result = ManifestConsentScenarios.execute(row.getValue("scenario"))
      assertEquals(row.getValue("expected"), result.outcome, row.getValue("id"))
      assertEquals(row.getValue("severity"), result.severity, row.getValue("id"))
      assertEquals(row.getValue("transfer_allowed").toBoolean(),
          result.transferAllowed, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-manifest-consent", row.getValue("provenance"))
    }
  }

  @Test
  fun queuedWireBudgetCountsEveryHeaderAndEncodedPayloadByte() {
    rows("/netplay-v9/aggregate-vectors.tsv").forEach { row ->
      val result = WireBudget.reserve(
          row.long("current_frames"),
          row.long("current_wire"),
          row.long("current_decoded"),
          row.long("add_frames"),
          row.long("add_wire"),
          row.long("add_decoded"),
      )
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
    }
    assertEquals(33_554_516L, Math.addExact(64L, 33_554_452L))
    assertEquals(262_656L, Math.multiplyExact(4L, Math.addExact(64L, 65_600L)))
    assertEquals(33_817_172L, Math.addExact(33_554_516L, 262_656L))
  }

  private data class MessageSpec(
      val name: String,
      val minDecoded: Long,
      val maxDecoded: Long,
      val maxEncoded: Long,
      val allowedFlags: Int,
      val requiredFlags: Int,
      val channels: String,
      val compression: String,
  )

  private class HeaderPreflight(
      private val specs: Map<Int, MessageSpec>,
      private val expectedSequence: Long,
      private val currentFrames: Long,
      private val currentWireBytes: Long,
      private val currentDecodedBytes: Long,
  ) {
    data class Result(
        val outcome: String,
        val consumed: Int,
        val payloadReservations: Int,
        val payloadBytesRead: Int,
    )

    private val bytes = ByteArray(64)
    private var count = 0
    private var result: Result? = null

    fun feed(value: Byte): Result? {
      if (result != null) return result
      bytes[count++] = value
      val error = validateAvailable()
      if (error != null) {
        result = Result(error, count, 0, 0)
      } else if (count == 64) {
        result = Result("ACCEPT", 64, 1, 0)
      }
      return result
    }

    fun finish() = result ?: Result("NEED_MORE", count, 0, 0)

    private fun validateAvailable(): String? {
      if (count >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) != "CGB9") {
        return "UNSUPPORTED_PROTOCOL"
      }
      if (count >= 6 && ((bytes[4].toInt() and 0xff) != 9 ||
              (bytes[5].toInt() and 0xff) != 0)) {
        return "UNSUPPORTED_PROTOCOL"
      }
      if (count >= 8 && u16(bytes, 6) != 64) return "MALFORMED_HEADER"
      if (count < 12) return null
      val type = u16(bytes, 8)
      val flags = u16(bytes, 10)
      val spec = specs[type]
      if (spec == null && flags != OPTIONAL) return "UNKNOWN_REQUIRED_TYPE"
      if (spec != null && (flags and KNOWN_FLAGS.inv() != 0 ||
              flags and spec.allowedFlags.inv() != 0 ||
              flags and spec.requiredFlags != spec.requiredFlags ||
              flags and DEFLATE != 0 && spec.compression != "raw-deflate")) {
        return "UNKNOWN_REQUIRED_FLAG"
      }
      if (count < 32) return null
      val sequence = u32(bytes, 12)
      val correlation = u32(bytes, 16)
      val encoded = u32(bytes, 20)
      val decoded = u32(bytes, 24)
      val channel = u32(bytes, 28)
      if (spec == null) {
        if (encoded != decoded || encoded > 4_096 || channel != 0L || correlation != 0L) {
          return "UNKNOWN_REQUIRED_TYPE"
        }
      } else if (!validChannel(spec.channels, channel)) {
        return "MALFORMED_HEADER"
      }
      if (expectedSequence == U32_MAX || sequence != expectedSequence) return "SEQUENCE_ERROR"
      if (flags and RESPONSE != 0 && correlation == 0L ||
          flags and RESPONSE == 0 && correlation != 0L) return "CORRELATION_ERROR"
      if (flags and DEFLATE == 0 && encoded != decoded) return "MALFORMED_HEADER"
      if (spec != null &&
          (encoded > spec.maxEncoded || decoded > spec.maxDecoded || decoded < spec.minDecoded)) {
        return "LIMIT_EXCEEDED"
      }
      val wire = try {
        Math.addExact(64L, encoded)
      } catch (_: ArithmeticException) {
        return "LIMIT_EXCEEDED"
      }
      return WireBudget.reserve(
          currentFrames, currentWireBytes, currentDecodedBytes, 1, wire, decoded)
          .takeUnless { it == "SUCCESS" }
    }
  }

  private class ResponseLedger(var nextSequence: Long?) {
    private data class Outstanding(val request: String, var responded: Boolean)
    private val outstanding = mutableMapOf<Long, Outstanding>()

    fun recordPeerRequest(sequence: Long, message: String, alreadyResponded: Boolean) {
      outstanding[sequence] = Outstanding(message, alreadyResponded)
    }

    fun forceNextSequence(sequence: Long) {
      nextSequence = sequence
    }

    fun accept(sequence: Long, message: String, flags: Int, correlation: Long): String {
      val expected = nextSequence ?: return "SEQUENCE_ERROR"
      if (expected == U32_MAX || sequence != expected || sequence !in 0..U32_LAST_USABLE) {
        return "SEQUENCE_ERROR"
      }
      val response = flags and RESPONSE != 0
      if (response) {
        if (correlation == 0L) return "CORRELATION_ERROR"
        val request = outstanding[correlation] ?: return "CORRELATION_ERROR"
        if (request.responded) return "CORRELATION_ERROR"
        val expectedResponse = when (request.request) {
          "AUTH" -> "AUTH_RESULT"
          "START" -> "READY"
          "PING" -> "PONG"
          else -> null
        }
        if (message != "ERROR" && message != expectedResponse) return "CORRELATION_ERROR"
        request.responded = true
      } else if (correlation != 0L) {
        return "CORRELATION_ERROR"
      }
      nextSequence = if (sequence == U32_LAST_USABLE) null else sequence + 1
      return "SUCCESS"
    }
  }

  private object CapabilityGate {
    fun validate(capabilities: Set<Int>, mode: String, message: String, flags: Int): String {
      if (!(1..7).all(capabilities::contains)) return "CAPABILITY_MISMATCH"
      if (mode == "four" && 11 !in capabilities) return "CAPABILITY_MISMATCH"
      if (message.startsWith("ROM_") && 8 !in capabilities) return "CAPABILITY_MISMATCH"
      if (message.startsWith("BATTERY_") && 9 !in capabilities) return "CAPABILITY_MISMATCH"
      if (flags and DEFLATE != 0 && 10 !in capabilities) return "CAPABILITY_MISMATCH"
      if (message in setOf("PING", "PONG") && 12 !in capabilities) {
        return "CAPABILITY_MISMATCH"
      }
      return "SUCCESS"
    }
  }

  private data class PayloadContext(
      val mode: String,
      val authenticatedPlayer: Int,
      val rosterMask: Int,
      val sessionId: Long? = null,
      val outstandingPingNonce: Long? = null,
  )

  private object PayloadSchemas {
    fun validate(
        message: String,
        flags: Int,
        channel: Long,
        payload: ByteArray,
        context: PayloadContext,
    ): String? = when (message) {
      "HELLO" -> validateHello(payload)
      "AUTH" -> validateAuth(payload, context)
      "AUTH_RESULT" -> validateAuthResult(payload, flags)
      "MANIFEST" -> Manifest.decode(payload, context)?.let { "MANIFEST_MISMATCH" }
      "CONSENT" -> Consent.decode(payload, context)?.let { "CONSENT_REJECTED" }
      "START" -> if (payload.size != 16 || u64(payload, 0) == 0L) "MALFORMED_HEADER" else null
      "READY" -> if (payload.size != 8 || context.sessionId == null ||
          u64(payload, 0) != context.sessionId) "CORRELATION_ERROR" else null
      "INPUT" -> validatePlayerFrame(payload, channel, 16, context, input = true)
      "RESET", "STOP" -> validatePlayerFrame(payload, channel, 16, context, input = false)
      "PING" -> if (payload.size != 16) "MALFORMED_HEADER" else null
      "PONG" -> if (payload.size != 16 || context.outstandingPingNonce == null ||
          u64(payload, 0) != context.outstandingPingNonce) "CORRELATION_ERROR" else null
      "CANCEL", "GOODBYE" -> validateTerminal(payload)
      "ERROR" -> validateError(payload)
      else -> null
    }

    private fun validateHello(payload: ByteArray): String? {
      if (payload.size < 38 || payload[0].toInt() and 0xff !in 1..2 ||
          payload[1].toInt() and 0xff != 9 || payload[2].toInt() and 0xff != 9 ||
          payload[3].toInt() != 0) return "CAPABILITY_MISMATCH"
      val count = u16(payload, 36)
      if (count > 32 || payload.size != 38 + count * 8) return "CAPABILITY_MISMATCH"
      var previous = 0
      val required = mutableSetOf<Int>()
      repeat(count) { index ->
        val offset = 38 + index * 8
        val id = u16(payload, offset)
        val version = u16(payload, offset + 2)
        val entryFlags = u32(payload, offset + 4)
        if (id <= previous || entryFlags !in 0..1 || id in 1..12 && version != 1) {
          return "CAPABILITY_MISMATCH"
        }
        if (id in 1..7 && entryFlags != 1L) return "CAPABILITY_MISMATCH"
        if (id > 12 && entryFlags == 1L) return "UNKNOWN_REQUIRED_CAPABILITY"
        if (id in 1..7) required += id
        previous = id
      }
      return if (required == (1..7).toSet()) null else "CAPABILITY_MISMATCH"
    }

    private fun validateAuth(payload: ByteArray, context: PayloadContext): String? {
      if (payload.size != 36 || payload.sliceArray(1..3).any { it.toInt() != 0 }) {
        return "AUTH_FAILED"
      }
      val player = payload[0].toInt() and 0xff
      val legal = if (context.mode == "normal") player == 1 else player in 1..3
      return if (!legal || player != context.authenticatedPlayer) "AUTH_FAILED" else null
    }

    private fun validateAuthResult(payload: ByteArray, flags: Int): String? {
      if (payload.size != 4 || u16(payload, 2) != 0) return "MALFORMED_HEADER"
      return when (u16(payload, 0)) {
        0 -> if (flags == RESPONSE) null else "MALFORMED_HEADER"
        1 -> if (flags == RESPONSE or TERMINAL) null else "MALFORMED_HEADER"
        else -> "MALFORMED_HEADER"
      }
    }

    private fun validatePlayerFrame(
        payload: ByteArray,
        channel: Long,
        size: Int,
        context: PayloadContext,
        input: Boolean,
    ): String? {
      if (payload.size != size) return "MALFORMED_HEADER"
      val player = payload[8].toInt() and 0xff
      if (player !in 0..3 || channel != playerChannel(player).toLong() ||
          context.rosterMask and (1 shl player) == 0) return "TOPOLOGY_MISMATCH"
      val reservedAt = if (input) 12 else 9
      if (payload.copyOfRange(reservedAt, payload.size).any { it.toInt() != 0 }) {
        return "MALFORMED_HEADER"
      }
      return null
    }

    private fun validateTerminal(payload: ByteArray): String? {
      if (payload.size < 4) return "MALFORMED_HEADER"
      val reason = u16(payload, 0)
      val length = u16(payload, 2)
      if (reason !in 0..0x1f || length > 256 || payload.size != 4 + length) {
        return "MALFORMED_HEADER"
      }
      return if (strictText(payload.copyOfRange(4, payload.size))) null else "STRICT_UTF8"
    }

    private fun validateError(payload: ByteArray): String? {
      if (payload.size < 12) return "MALFORMED_HEADER"
      val code = u16(payload, 0)
      val length = u16(payload, 8)
      if (code !in 1..0x1f || u16(payload, 10) != 0 ||
          length > 512 || payload.size != 12 + length) return "MALFORMED_HEADER"
      return if (strictText(payload.copyOfRange(12, payload.size))) null else "STRICT_UTF8"
    }
  }

  private data class ManifestEntry(
      val player: Int,
      val primaryPresent: Boolean,
      val slotPresent: Boolean,
      val batteryPresent: Boolean,
      val bootstrap: Int,
      val accessoryFlags: Int,
      val profile: String,
      val title: String,
      val cartridgeType: Int,
      val mapperFamily: Int,
      val primaryLength: Long,
      val slotLength: Long,
      val primaryDigest: ByteArray,
      val slotDigest: ByteArray,
      val bootDigest: ByteArray,
      val patchDigest: ByteArray,
  ) {
    fun encode(): ByteArray {
      val profileBytes = profile.toByteArray(StandardCharsets.US_ASCII)
      val titleBytes = title.toByteArray(StandardCharsets.US_ASCII)
      val flags = (if (primaryPresent) 1 else 0) or
          (if (slotPresent) 2 else 0) or
          (if (batteryPresent) 4 else 0)
      return ByteBuffer.allocate(144 + profileBytes.size + titleBytes.size)
          .put(player.toByte()).put(flags.toByte()).put(bootstrap.toByte())
          .put(accessoryFlags.toByte()).put(profileBytes.size.toByte())
          .put(titleBytes.size.toByte()).put(cartridgeType.toByte())
          .put(mapperFamily.toByte()).putInt(primaryLength.toInt()).putInt(slotLength.toInt())
          .put(primaryDigest).put(slotDigest).put(bootDigest).put(patchDigest)
          .put(profileBytes).put(titleBytes).array()
    }
  }

  private data class ManifestDiff(
      val code: Int,
      val severity: Int,
      val player: Int,
      val proposalId: Long = 0,
  ) {
    fun encode(): ByteArray = ByteBuffer.allocate(12).putShort(code.toShort())
        .put(severity.toByte()).put(player.toByte()).putInt(proposalId.toInt())
        .putInt(0).array()
  }

  private data class Proposal(
      val id: Long,
      val action: Int,
      val assetClass: Int,
      val assetKind: Int,
      val owner: Int,
      val source: Int,
      val target: Int,
      val size: Long,
      val digest: ByteArray,
  ) {
    fun encode(): ByteArray = ByteBuffer.allocate(48).putInt(id.toInt())
        .put(action.toByte()).put(assetClass.toByte()).put(assetKind.toByte())
        .put(owner.toByte()).put(source.toByte()).put(target.toByte())
        .put(WARNING.toByte()).put(0).putInt(size.toInt()).put(digest).array()
  }

  private data class Manifest(
      val mode: Int,
      val sender: Int,
      val rosterMask: Int,
      val rosterGeneration: Long,
      val entries: List<ManifestEntry>,
      val diffs: List<ManifestDiff>,
      val proposals: List<Proposal>,
  ) {
    fun rosterDigest(): ByteArray {
      val output = ByteArrayOutputStream()
      output.write("CoffeeGB-v9-roster-v1".toByteArray(StandardCharsets.US_ASCII))
      output.write(mode)
      output.write(rosterMask)
      entries.forEach { output.write(it.encode()) }
      return sha256(output.toByteArray())
    }

    fun encode(): ByteArray {
      val entryBytes = entries.map(ManifestEntry::encode)
      val output = ByteArrayOutputStream()
      output.write(ByteBuffer.allocate(52)
          .putShort(1).put(mode.toByte()).put(sender.toByte())
          .put(entries.size.toByte()).put(proposals.size.toByte()).put(diffs.size.toByte())
          .put(0.toByte()).put(9.toByte()).put(2.toByte()).putShort(1).putShort(1)
          .put(rosterMask.toByte()).put(0.toByte()).putInt(rosterGeneration.toInt())
          .put(rosterDigest()).array())
      entryBytes.forEach(output::write)
      diffs.forEach { output.write(it.encode()) }
      proposals.forEach { output.write(it.encode()) }
      return output.toByteArray()
    }

    companion object {
      fun decode(payload: ByteArray, context: PayloadContext): String? {
        if (payload.size !in MANIFEST_MIN..MANIFEST_MAX || u16(payload, 0) != 1) {
          return "shape"
        }
        val mode = payload[2].toInt() and 0xff
        val sender = payload[3].toInt() and 0xff
        val entryCount = payload[4].toInt() and 0xff
        val proposalCount = payload[5].toInt() and 0xff
        val diffCount = payload[6].toInt() and 0xff
        val rosterMask = payload[14].toInt() and 0xff
        if (mode !in 1..2 || sender !in 0..3 || entryCount !in 2..4 ||
            proposalCount > 8 || diffCount > 16 || payload[7].toInt() != 0 ||
            payload[8].toInt() and 0xff != 9 || payload[9].toInt() and 0xff != 2 ||
            u16(payload, 10) != 1 || u16(payload, 12) != 1 ||
            payload[15].toInt() != 0 || u32(payload, 16) == 0L ||
            rosterMask and 1 == 0 || Integer.bitCount(rosterMask) != entryCount ||
            rosterMask and (1 shl sender) == 0) return "header"
        if (context.mode == "normal" && (mode != 1 || rosterMask != 0x03) ||
            context.mode == "four" && mode != 2 ||
            sender != 0 && sender != context.authenticatedPlayer) return "mode"

        var offset = 52
        val entryBytes = mutableListOf<ByteArray>()
        val players = mutableListOf<Int>()
        repeat(entryCount) {
          if (payload.size - offset < 144) return "entry-truncated"
          val profileLength = payload[offset + 4].toInt() and 0xff
          val titleLength = payload[offset + 5].toInt() and 0xff
          val length = try {
            Math.addExact(144, Math.addExact(profileLength, titleLength))
          } catch (_: ArithmeticException) {
            return "entry-overflow"
          }
          if (length > payload.size - offset) return "entry-truncated"
          val entry = payload.copyOfRange(offset, offset + length)
          if (validateEntry(entry) != null) return "entry"
          val player = entry[0].toInt() and 0xff
          if (players.isNotEmpty() && player <= players.last() ||
              rosterMask and (1 shl player) == 0) return "entry-order"
          players += player
          entryBytes += entry
          offset += length
        }
        if (players.toSet() != (0..3).filter { rosterMask and (1 shl it) != 0 }.toSet()) {
          return "roster"
        }
        val expectedRoster = ByteArrayOutputStream().apply {
          write("CoffeeGB-v9-roster-v1".toByteArray(StandardCharsets.US_ASCII))
          write(mode)
          write(rosterMask)
          entryBytes.forEach(::write)
        }.toByteArray()
        if (!MessageDigest.isEqual(payload.copyOfRange(20, 52), sha256(expectedRoster))) {
          return "roster-digest"
        }

        val warningProposalIds = mutableSetOf<Long>()
        repeat(diffCount) {
          if (payload.size - offset < 12) return "diff-truncated"
          val code = u16(payload, offset)
          val severity = payload[offset + 2].toInt() and 0xff
          val player = payload[offset + 3].toInt() and 0xff
          val proposalId = u32(payload, offset + 4)
          val expectedSeverity = when (code) {
            in 1..4 -> FATAL
            in 5..8 -> WARNING
            in 9..10 -> INFORMATIONAL
            else -> -1
          }
          if (severity != expectedSeverity || player !in 0..3 ||
              u32(payload, offset + 8) != 0L ||
              severity == WARNING && proposalId == 0L ||
              severity != WARNING && proposalId != 0L) return "diff"
          if (severity == WARNING && !warningProposalIds.add(proposalId)) {
            return "diff-proposal"
          }
          offset += 12
        }
        val proposalIds = mutableSetOf<Long>()
        repeat(proposalCount) {
          if (payload.size - offset < 48) return "proposal-truncated"
          val id = u32(payload, offset)
          val action = payload[offset + 4].toInt() and 0xff
          val assetClass = payload[offset + 5].toInt() and 0xff
          val assetKind = payload[offset + 6].toInt() and 0xff
          val owner = payload[offset + 7].toInt() and 0xff
          val source = payload[offset + 8].toInt() and 0xff
          val target = payload[offset + 9].toInt() and 0xff
          val disposition = payload[offset + 10].toInt() and 0xff
          val size = u32(payload, offset + 12)
          val digest = payload.copyOfRange(offset + 16, offset + 48)
          if (id == 0L || !proposalIds.add(id) || action !in 1..2 ||
              assetClass !in 1..3 || assetKind !in 1..4 ||
              owner !in 0..3 && owner != GROUP_PLAYER ||
              source !in 0..3 || target !in 0..3 || source == target ||
              rosterMask and (1 shl source) == 0 || rosterMask and (1 shl target) == 0 ||
              disposition != WARNING || payload[offset + 11].toInt() != 0 ||
              action == OFFER && sender != source || action == REQUEST && sender != target ||
              !classMatchesAsset(assetClass, assetKind) ||
              assetKind == CHECKPOINT_ASSET && (owner != GROUP_PLAYER ||
                  size != 0L || digest.any { byte -> byte.toInt() != 0 }) ||
              assetKind != CHECKPOINT_ASSET && (owner !in 0..3 ||
                  size == 0L || digest.all { byte -> byte.toInt() == 0 })) return "proposal"
          offset += 48
        }
        if (warningProposalIds != proposalIds) return "diff-proposal"
        return if (offset == payload.size) null else "trailing"
      }

      private fun validateEntry(entry: ByteArray): String? {
        val player = entry[0].toInt() and 0xff
        val flags = entry[1].toInt() and 0xff
        val bootstrap = entry[2].toInt() and 0xff
        val accessories = entry[3].toInt() and 0xff
        val profileLength = entry[4].toInt() and 0xff
        val titleLength = entry[5].toInt() and 0xff
        val cartridgeType = entry[6].toInt() and 0xff
        val mapperFamily = entry[7].toInt() and 0xff
        val primaryLength = u32(entry, 8)
        val slotLength = u32(entry, 12)
        val primaryDigest = entry.copyOfRange(16, 48)
        val slotDigest = entry.copyOfRange(48, 80)
        val bootDigest = entry.copyOfRange(80, 112)
        val profile = String(entry, 144, profileLength, StandardCharsets.US_ASCII)
        val title = entry.copyOfRange(144 + profileLength, entry.size)
        if (player !in 0..3 || flags and 0xf8 != 0 || bootstrap !in 1..3 ||
            accessories and 0xf8 != 0 || profileLength !in 1..32 ||
            titleLength !in 0..16 || cartridgeType !in 0..0xff ||
            mapperFamily !in 1..16 || profile !in CANONICAL_PROFILES ||
            !PROFILE_PATTERN.matches(profile) || !strictTitle(title)) return "identity"
        val primaryPresent = flags and 1 != 0
        val slotPresent = flags and 2 != 0
        if (primaryPresent != (primaryLength in 1..ROM_LIMIT) ||
            primaryPresent != primaryDigest.any { it.toInt() != 0 } ||
            slotPresent != (slotLength in 1..ROM_LIMIT) ||
            slotPresent != slotDigest.any { it.toInt() != 0 }) return "content-presence"
        if (bootstrap == 3 && bootDigest.any { it.toInt() != 0 } ||
            bootstrap != 3 && bootDigest.all { it.toInt() == 0 }) return "boot"
        return null
      }
    }
  }

  private data class Consent(
      val decisionId: Long,
      val actor: Int,
      val decision: Int,
      val assetClass: Int,
      val assetKind: Int,
      val source: Int,
      val target: Int,
      val owner: Int,
      val proposalId: Long,
      val size: Long,
      val digest: ByteArray,
      val serverManifest: ByteArray,
      val clientManifest: ByteArray,
  ) {
    fun encode(): ByteArray = ByteBuffer.allocate(116).putInt(decisionId.toInt())
        .put(actor.toByte()).put(decision.toByte()).put(assetClass.toByte())
        .put(assetKind.toByte()).put(source.toByte()).put(target.toByte())
        .put(owner.toByte()).put(0.toByte()).putInt(proposalId.toInt()).putInt(size.toInt())
        .put(digest).put(serverManifest).put(clientManifest).array()

    companion object {
      fun decode(payload: ByteArray, context: PayloadContext): String? {
        if (payload.size != 116 || u32(payload, 0) == 0L ||
            payload[11].toInt() != 0 || u32(payload, 12) == 0L) return "shape"
        val actor = payload[4].toInt() and 0xff
        val decision = payload[5].toInt() and 0xff
        val assetClass = payload[6].toInt() and 0xff
        val assetKind = payload[7].toInt() and 0xff
        val source = payload[8].toInt() and 0xff
        val target = payload[9].toInt() and 0xff
        val owner = payload[10].toInt() and 0xff
        val size = u32(payload, 16)
        val digest = payload.copyOfRange(20, 52)
        if (actor !in setOf(0, context.authenticatedPlayer) || decision !in 1..2 ||
            assetClass !in 1..3 || assetKind !in 1..4 ||
            source !in 0..3 || target !in 0..3 || source == target ||
            actor !in setOf(source, target) || !classMatchesAsset(assetClass, assetKind) ||
            owner !in 0..3 && owner != GROUP_PLAYER ||
            assetKind == CHECKPOINT_ASSET && (owner != GROUP_PLAYER ||
                size != 0L || digest.any { it.toInt() != 0 }) ||
            assetKind != CHECKPOINT_ASSET && (owner !in 0..3 ||
                size == 0L || digest.all { it.toInt() == 0 }) ||
            payload.copyOfRange(52, 84).all { it.toInt() == 0 } ||
            payload.copyOfRange(84, 116).all { it.toInt() == 0 }) return "identity"
        return null
      }
    }
  }

  private class ConsentLedger(
      private val proposal: Proposal,
      private val serverManifest: ByteArray,
      private val clientManifest: ByteArray,
  ) {
    private val approvals = mutableSetOf<Int>()
    private var rejected = false
    private var transactionClaimed = false

    fun decide(consent: Consent): String {
      if (rejected || !matches(consent) || consent.actor !in setOf(proposal.source, proposal.target) ||
          !approvals.add(consent.actor)) return "CONSENT_REJECTED"
      if (consent.decision == REJECT) {
        rejected = true
        return "CONSENT_REJECTED"
      }
      return if (approvals == setOf(proposal.source, proposal.target)) "SUCCESS"
      else "CONSENT_REQUIRED"
    }

    fun claim(declaration: BulkDeclaration): Boolean {
      if (transactionClaimed || rejected ||
          approvals != setOf(proposal.source, proposal.target) ||
          declaration.proposalId != proposal.id ||
          declaration.source != proposal.source || declaration.target != proposal.target ||
          declaration.owner != proposal.owner || declaration.assetKind != proposal.assetKind ||
          declaration.total != proposal.size ||
          !MessageDigest.isEqual(declaration.digest, proposal.digest)) return false
      transactionClaimed = true
      return true
    }

    private fun matches(consent: Consent): Boolean =
        consent.decisionId == DECISION_ID && consent.proposalId == proposal.id &&
            consent.assetClass == proposal.assetClass && consent.assetKind == proposal.assetKind &&
            consent.source == proposal.source && consent.target == proposal.target &&
            consent.owner == proposal.owner && consent.size == proposal.size &&
            MessageDigest.isEqual(consent.digest, proposal.digest) &&
            MessageDigest.isEqual(consent.serverManifest, serverManifest) &&
            MessageDigest.isEqual(consent.clientManifest, clientManifest)
  }

  private data class BulkDeclaration(
      val transactionId: Long,
      val proposalId: Long,
      val source: Int,
      val target: Int,
      val owner: Int,
      val assetKind: Int,
      val total: Long,
      val digest: ByteArray,
      val chunkSize: Long,
  )

  private class BulkTracker(private val consent: ConsentLedger) {
    private var declaration: BulkDeclaration? = null
    private val bytes = ByteArrayOutputStream()
    var committed = false
      private set
    val retainedBytes: Int get() = bytes.size()

    fun begin(assetClass: String, channel: Int, value: BulkDeclaration): String {
      if (declaration != null || value.transactionId == 0L || value.proposalId == 0L ||
          value.source !in 0..3 || value.target !in 0..3 || value.source == value.target ||
          value.owner !in 0..3 || channel != playerChannel(value.owner) ||
          value.total !in 1..when (assetClass) {
            "ROM" -> ROM_LIMIT
            "BATTERY" -> BATTERY_LIMIT
            else -> 0
          } || value.chunkSize !in 1..65_536 ||
          assetClass == "ROM" && value.assetKind !in setOf(PRIMARY_ROM_ASSET, SLOT_ROM_ASSET) ||
          assetClass == "BATTERY" && value.assetKind != BATTERY_ASSET ||
          !consent.claim(value)) return "CONSENT_REJECTED"
      declaration = value
      return "SUCCESS"
    }

    fun chunk(transactionId: Long, offset: Long, data: ByteArray): String {
      val current = declaration ?: return "TRANSACTION_MISMATCH"
      if (transactionId != current.transactionId || offset != bytes.size().toLong() ||
          data.isEmpty() || data.size > current.chunkSize ||
          Math.addExact(bytes.size().toLong(), data.size.toLong()) > current.total) {
        return "TRANSACTION_MISMATCH"
      }
      bytes.write(data)
      return "SUCCESS"
    }

    fun end(transactionId: Long, digest: ByteArray): String {
      val current = declaration ?: return "TRANSACTION_MISMATCH"
      if (transactionId != current.transactionId || bytes.size().toLong() != current.total ||
          !MessageDigest.isEqual(digest, current.digest) ||
          !MessageDigest.isEqual(sha256(bytes.toByteArray()), current.digest)) {
        return "TRANSACTION_MISMATCH"
      }
      committed = true
      declaration = null
      return "SUCCESS"
    }
  }

  private class TopologyCoordinator(
      private val mode: String,
      occupiedMask: Int,
  ) {
    var liveMask = occupiedMask
      private set
    var candidateCommitted = false
      private set

    fun prepareCandidate(
        authenticatedPlayer: Int,
        candidatePlayer: Int,
        rosterMask: Int,
        checkpointKind: String,
        checkpointMask: Int,
    ): String {
      val legalPlayer = if (mode == "normal") authenticatedPlayer == 1 else authenticatedPlayer in 1..3
      if (!legalPlayer || authenticatedPlayer != candidatePlayer) return "AUTH_FAILED"
      if (liveMask and (1 shl candidatePlayer) != 0) return "SERVER_FULL"
      if (rosterMask and 1 == 0 || rosterMask and (1 shl candidatePlayer) == 0 ||
          rosterMask != (liveMask or (1 shl candidatePlayer))) return "TOPOLOGY_MISMATCH"
      if (mode == "normal") {
        if (rosterMask != 0x03 || checkpointKind != "SESSION" ||
            checkpointMask != 1 shl candidatePlayer) return "ROOT_KIND_MISMATCH"
      } else if (checkpointKind != "LINKED_SESSION") {
        return "ROOT_KIND_MISMATCH"
      } else if (checkpointMask != rosterMask) {
        return "TOPOLOGY_MISMATCH"
      }
      liveMask = rosterMask
      candidateCommitted = true
      return "SUCCESS"
    }
  }

  private data class FourGuestSession(
      val player: Int,
      val rosterMask: Int,
      val rosterCommitment: ByteArray,
      val checkpointDigest: ByteArray,
      var canStart: Boolean = false,
  ) {
    fun manifestCoversHostAndGuest() =
        player in 1..3 && rosterMask and 1 != 0 && rosterMask and (1 shl player) != 0 &&
            rosterCommitment.size == 32

    fun approveCheckpointFromHost() {
      check(manifestCoversHostAndGuest() && checkpointDigest.size == 32)
      canStart = true
    }
  }

  private data class ScenarioResult(
      val outcome: String,
      val severity: String,
      val transferAllowed: Boolean,
  )

  private object ManifestConsentScenarios {
    fun execute(scenario: String): ScenarioResult {
      val severity = if (scenario in setOf("own-rom-match")) "INFORMATIONAL"
      else if (scenario in setOf("protocol-context-mismatch", "profile-mismatch")) "FATAL"
      else "WARNING_REQUIRES_APPROVAL"
      if (scenario == "own-rom-match") return ScenarioResult("SUCCESS", severity, false)
      if (scenario == "protocol-context-mismatch") {
        return ScenarioResult("CAPABILITY_MISMATCH", severity, false)
      }
      if (scenario == "profile-mismatch") {
        return ScenarioResult("MANIFEST_MISMATCH", severity, false)
      }
      if (scenario == "missing-rom-no-proposal") {
        return ScenarioResult("CONSENT_REJECTED", severity, false)
      }

      val proposal = romProposal()
      val server = ByteArray(32) { 1 }
      val client = ByteArray(32) { 2 }
      val ledger = ConsentLedger(proposal, server, client)
      if (scenario == "different-rom-offer-pending") {
        return ScenarioResult("CONSENT_REQUIRED", severity, false)
      }
      if (scenario == "warning-rejected") {
        val rejected = consentFor(proposal, 1, server, client).copy(decision = REJECT)
        return ScenarioResult(ledger.decide(rejected), severity, false)
      }
      if (scenario == "one-side-approved") {
        return ScenarioResult(
            ledger.decide(consentFor(proposal, 0, server, client)), severity, false)
      }
      val serverConsent = consentFor(proposal, 0, server, client)
      val clientConsent = consentFor(proposal, 1, server, client)
      when (scenario) {
        "wrong-direction" -> return ScenarioResult(
            ledger.decide(clientConsent.copy(source = 1, target = 0)), severity, false)
        "wrong-player" -> return ScenarioResult(
            ledger.decide(clientConsent.copy(owner = 2)), severity, false)
        "wrong-asset" -> return ScenarioResult(
            ledger.decide(clientConsent.copy(assetKind = SLOT_ROM_ASSET)), severity, false)
        "manifest-changed" -> return ScenarioResult(
            ledger.decide(clientConsent.copy(clientManifest = ByteArray(32) { 3 })),
            severity, false)
        "begin-before-approval" -> {
          val allowed = ledger.claim(declaration(proposal))
          return ScenarioResult(if (allowed) "SUCCESS" else "CONSENT_REJECTED",
              severity, allowed)
        }
      }
      ledger.decide(serverConsent)
      if (scenario == "approval-replay") {
        return ScenarioResult(ledger.decide(serverConsent), severity, false)
      }
      val accepted = ledger.decide(clientConsent)
      if (scenario == "two-sided-approved") {
        val allowed = ledger.claim(declaration(proposal))
        return ScenarioResult(if (allowed) accepted else "CONSENT_REJECTED", severity, allowed)
      }
      if (scenario == "extra-transaction") {
        check(accepted == "SUCCESS")
        check(ledger.claim(declaration(proposal)))
        val extra = ledger.claim(declaration(proposal).copy(transactionId = 10))
        return ScenarioResult(if (extra) "SUCCESS" else "CONSENT_REJECTED", severity, false)
      }
      if (scenario == "battery-with-rom-consent") {
        check(accepted == "SUCCESS")
        val allowed = ledger.claim(declaration(proposal).copy(assetKind = BATTERY_ASSET))
        return ScenarioResult(if (allowed) "SUCCESS" else "CONSENT_REJECTED", severity, allowed)
      }
      if (scenario == "checkpoint-with-rom-consent") {
        check(accepted == "SUCCESS")
        val allowed = ledger.claim(declaration(proposal).copy(
            assetKind = CHECKPOINT_ASSET, owner = GROUP_PLAYER, total = 0,
            digest = ByteArray(32)))
        return ScenarioResult(if (allowed) "SUCCESS" else "CONSENT_REJECTED", severity, allowed)
      }
      error("Unknown manifest/consent scenario $scenario")
    }

    private fun declaration(proposal: Proposal) = BulkDeclaration(
        9, proposal.id, proposal.source, proposal.target, proposal.owner, proposal.assetKind,
        proposal.size, proposal.digest, 65_536)
  }

  private object WireBudget {
    fun reserve(
        currentFrames: Long,
        currentWire: Long,
        currentDecoded: Long,
        addFrames: Long,
        addWire: Long,
        addDecoded: Long,
    ): String {
      if (listOf(currentFrames, currentWire, currentDecoded, addFrames, addWire, addDecoded)
          .any { it < 0 }) return "MALFORMED_HEADER"
      val frames: Long
      val wire: Long
      val decoded: Long
      try {
        frames = Math.addExact(currentFrames, addFrames)
        wire = Math.addExact(currentWire, addWire)
        decoded = Math.addExact(currentDecoded, addDecoded)
      } catch (_: ArithmeticException) {
        return "LIMIT_EXCEEDED"
      }
      if (frames > 256 || wire > QUEUED_WIRE_LIMIT) return "QUEUE_OVERFLOW"
      if (decoded > DECODED_AGGREGATE_LIMIT) return "LIMIT_EXCEEDED"
      return "SUCCESS"
    }
  }

  private fun headerFrom(row: Map<String, String>): ByteArray =
      ByteBuffer.allocate(64)
          .put(row.getValue("magic").toByteArray(StandardCharsets.US_ASCII))
          .put(row.int("major").toByte()).put(row.int("minor").toByte())
          .putShort(row.int("header_length").toShort())
          .putShort(row.hexInt("type").toShort()).putShort(row.hexInt("flags").toShort())
          .putInt(row.long("sequence").toInt()).putInt(row.long("correlation").toInt())
          .putInt(row.long("encoded").toInt()).putInt(row.long("decoded").toInt())
          .putInt(row.long("channel").toInt()).put(ByteArray(32)).array()

  private fun messageSpecs(): Map<Int, MessageSpec> =
      rows("/netplay-v9/messages.tsv").associate { row ->
        row.hexInt("id") to MessageSpec(
            row.getValue("name"), row.long("min_decoded"), row.long("max_decoded"),
            row.long("max_encoded"), row.hexInt("allowed_flags"),
            row.hexInt("required_flags"), row.getValue("channels"),
            row.getValue("compression"))
      }

  private fun helloPayload(capabilities: Set<Int>): ByteArray {
    val ordered = capabilities.sorted()
    val buffer = ByteBuffer.allocate(38 + ordered.size * 8)
        .put(1).put(9).put(9).put(0).put(ByteArray(32))
        .putShort(ordered.size.toShort())
    ordered.forEach { id ->
      buffer.putShort(id.toShort()).putShort(1).putInt(if (id <= 7) 1 else 0)
    }
    return buffer.array()
  }

  private fun normalEntries(): List<ManifestEntry> = listOf(
      manifestEntry(0, "HOST", 1),
      manifestEntry(1, "GUEST", 2),
  )

  private fun validNormalManifest(
      entries: List<ManifestEntry> = normalEntries(),
      sender: Int = 0,
      proposals: List<Proposal> = emptyList(),
  ): Manifest = Manifest(
      mode = 1,
      sender = sender,
      rosterMask = 0x03,
      rosterGeneration = 1,
      entries = entries,
      diffs = if (proposals.isEmpty()) {
        listOf(ManifestDiff(10, INFORMATIONAL, 0))
      } else {
        proposals.map { ManifestDiff(6, WARNING, it.owner, it.id) }
      },
      proposals = proposals,
  )

  private fun validFourRoster(): Manifest = Manifest(
      mode = 2,
      sender = 0,
      rosterMask = 0x0f,
      rosterGeneration = 7,
      entries = (0..3).map { manifestEntry(it, "PLAYER$it", it + 1) },
      diffs = (0..3).map { ManifestDiff(10, INFORMATIONAL, it) },
      proposals = emptyList(),
  )

  private fun manifestEntry(player: Int, title: String, digestByte: Int) = ManifestEntry(
      player = player,
      primaryPresent = true,
      slotPresent = false,
      batteryPresent = false,
      bootstrap = 3,
      accessoryFlags = 0,
      profile = "dmg",
      title = title,
      cartridgeType = 0,
      mapperFamily = 1,
      primaryLength = 32_768,
      slotLength = 0,
      primaryDigest = ByteArray(32) { digestByte.toByte() },
      slotDigest = ByteArray(32),
      bootDigest = ByteArray(32),
      patchDigest = ByteArray(32),
  )

  private fun consentPayload(
      proposal: Proposal,
      actor: Int,
      decision: Int,
      serverManifest: ByteArray,
      clientManifest: ByteArray,
  ) = consentFor(proposal, actor, serverManifest, clientManifest, decision).encode()

  private fun terminalPayload(reason: Int, value: String): ByteArray {
    val text = value.toByteArray(StandardCharsets.UTF_8)
    return ByteBuffer.allocate(4 + text.size).putShort(reason.toShort())
        .putShort(text.size.toShort()).put(text).array()
  }

  private fun errorPayload(code: Int, type: Int, sequence: Int, value: String): ByteArray {
    val text = value.toByteArray(StandardCharsets.UTF_8)
    return ByteBuffer.allocate(12 + text.size).putShort(code.toShort()).putShort(type.toShort())
        .putInt(sequence).putShort(text.size.toShort()).putShort(0).put(text).array()
  }

  private fun rows(resource: String): List<Map<String, String>> {
    val stream = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing $resource" }
    val lines = stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
      reader.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
    }
    val header = lines.first().split('\t')
    return lines.drop(1).mapIndexed { index, line ->
      val values = line.split('\t')
      assertEquals(header.size, values.size, "$resource:${index + 2}")
      header.zip(values).toMap()
    }
  }

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

  private fun Map<String, String>.int(name: String) = getValue(name).toInt()
  private fun Map<String, String>.long(name: String) = getValue(name).toLong()
  private fun Map<String, String>.hexInt(name: String) =
      getValue(name).removePrefix("0x").toInt(16)

  private companion object {
    const val OPTIONAL = 0x0001
    const val DEFLATE = 0x0002
    const val RESPONSE = 0x0004
    const val TERMINAL = 0x0008
    const val KNOWN_FLAGS = 0x000f
    const val OFFER = 1
    const val REQUEST = 2
    const val APPROVE = 1
    const val REJECT = 2
    const val FATAL = 1
    const val WARNING = 2
    const val INFORMATIONAL = 3
    const val ROM_CLASS = 1
    const val BATTERY_CLASS = 2
    const val CHECKPOINT_CLASS = 3
    const val PRIMARY_ROM_ASSET = 1
    const val SLOT_ROM_ASSET = 2
    const val BATTERY_ASSET = 3
    const val CHECKPOINT_ASSET = 4
    const val GROUP_PLAYER = 0xff
    const val DECISION_ID = 17L
    const val MANIFEST_MIN = 342
    const val MANIFEST_MAX = 1_396
    const val ROM_LIMIT = 67_108_864L
    const val BATTERY_LIMIT = 2_097_152L
    const val QUEUED_WIRE_LIMIT = 33_817_172L
    const val DECODED_AGGREGATE_LIMIT = 134_217_728L
    const val U32_MAX = 0xffff_ffffL
    const val U32_LAST_USABLE = 0xffff_fffeL
    val CANONICAL_PROFILES = setOf("dmg", "cgb", "cgb0", "sgb", "sgb2", "mgb")
    val PROFILE_PATTERN = Regex("[a-z][a-z0-9-]{0,31}")

    fun playerChannel(player: Int): Int {
      require(player in 0..3)
      return player + 1
    }

    fun classMatchesAsset(assetClass: Int, assetKind: Int) = when (assetClass) {
      ROM_CLASS -> assetKind in setOf(PRIMARY_ROM_ASSET, SLOT_ROM_ASSET)
      BATTERY_CLASS -> assetKind == BATTERY_ASSET
      CHECKPOINT_CLASS -> assetKind == CHECKPOINT_ASSET
      else -> false
    }

    fun romProposal() = Proposal(
        id = 41,
        action = OFFER,
        assetClass = ROM_CLASS,
        assetKind = PRIMARY_ROM_ASSET,
        owner = 1,
        source = 0,
        target = 1,
        size = 32_768,
        digest = ByteArray(32) { 7 },
    )

    fun consentFor(
        proposal: Proposal,
        actor: Int,
        serverManifest: ByteArray,
        clientManifest: ByteArray,
        decision: Int = APPROVE,
    ) = Consent(
        decisionId = DECISION_ID,
        actor = actor,
        decision = decision,
        assetClass = proposal.assetClass,
        assetKind = proposal.assetKind,
        source = proposal.source,
        target = proposal.target,
        owner = proposal.owner,
        proposalId = proposal.id,
        size = proposal.size,
        digest = proposal.digest,
        serverManifest = serverManifest,
        clientManifest = clientManifest,
    )

    fun strictText(bytes: ByteArray): Boolean {
      val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
      } catch (_: Exception) {
        return false
      }
      return text.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }
    }

    fun strictTitle(bytes: ByteArray) =
        bytes.all { value -> value.toInt() and 0xff in 0x20..0x7e }

    fun validChannel(kind: String, channel: Long) = when (kind) {
      "control" -> channel == 0L
      "player" -> channel in 1L..4L
      "group-or-player" -> channel == U32_MAX || channel in 1L..4L
      else -> false
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun u16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    fun u64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).long
  }
}
