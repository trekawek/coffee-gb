package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
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
    val roster = validNormalManifest()
    val context = PayloadContext(
        mode = "normal",
        authenticatedPlayer = 1,
        rosterMask = 0x03,
        rosterGeneration = roster.rosterGeneration,
        rosterCommitment = roster.rosterDigest(),
    )
    assertEquals(
        rows("/netplay-v9/messages.tsv").map { it.getValue("name") }.toSet(),
        PayloadSchemas.supportedMessages)
    assertEquals(
        rows("/netplay-v9/messages.tsv").map { it.getValue("payload_schema") }.toSet(),
        PayloadSchemas.supportedSchemas)

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

    val manifest = roster.encode()
    assertEquals(null, PayloadSchemas.validate("MANIFEST", 0, 0, manifest, context))
    val clientManifestPayload = validNormalManifest(sender = 1).encode()
    assertEquals(null, PayloadSchemas.validate(
        "MANIFEST", 0, 0, clientManifestPayload,
        context.copy(wireSource = 1, wireTarget = 0)))
    val senderSpoof = validNormalManifest(sender = 0, proposals = listOf(romProposal())).encode()
    assertEquals("MANIFEST_MISMATCH", PayloadSchemas.validate(
        "MANIFEST", 0, 0, senderSpoof,
        context.copy(wireSource = 1, wireTarget = 0)))
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
    val maskedDifference = validNormalManifest(proposals = listOf(romProposal())).copy(
        diffs = listOf(
            ManifestDiff(MATCH_DIFF, INFORMATIONAL, 1),
            ManifestDiff(PRIMARY_ROM_DIFFERENT_DIFF, WARNING, 1, romProposal().id),
        )).encode()
    assertEquals("MANIFEST_MISMATCH",
        PayloadSchemas.validate("MANIFEST", 0, 0, maskedDifference, context))
    val partialFour = Manifest(
        mode = 2,
        sender = 1,
        rosterMask = 0x07,
        rosterGeneration = 7,
        entries = (0..2).map { manifestEntry(it, "PLAYER$it", it + 1) },
        diffs = (0..2).map { ManifestDiff(MATCH_DIFF, INFORMATIONAL, it) },
        proposals = emptyList(),
    )
    assertEquals("MANIFEST_MISMATCH", PayloadSchemas.validate(
        "MANIFEST", 0, 0, partialFour.encode(),
        PayloadContext(
            mode = "four",
            authenticatedPlayer = 1,
            rosterMask = 0x07,
            rosterGeneration = 7,
            rosterCommitment = partialFour.rosterDigest(),
            wireSource = 1,
            wireTarget = 0,
        )))

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
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("RESET", 0, 2, reset.copyOf(15), context))
    assertEquals(null, PayloadSchemas.validate("STOP", 0, 2, reset, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("STOP", 0, 2, reset.copyOf().also { it[9] = 1 }, context))

    val ping = ByteBuffer.allocate(16).putLong(0x0102030405060708L).putLong(9).array()
    assertEquals(null, PayloadSchemas.validate("PING", 0, 0, ping, context))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("PING", 0, 0, ping.copyOf(15), context))
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

    val stateBytes = checkNotNull(
        javaClass.getResourceAsStream("/state-file-v2/sgb2-session-deflate.cgbstate"))
        .use { it.readBytes() }
    val checkpointProposal = Proposal(
        77, OFFER, CHECKPOINT_CLASS, CHECKPOINT_ASSET, GROUP_PLAYER, 0, 1, 0,
        ByteArray(32))
    fun approvedCheckpointGrant(
        mask: Int = 0x03,
        generation: Long = roster.rosterGeneration,
        commitment: ByteArray = roster.rosterDigest(),
        maximumTransactions: Int = 32,
    ): CheckpointGrant {
      val ledger = ConsentLedger(checkpointProposal, serverManifest, clientManifest)
      ledger.decide(consentFor(checkpointProposal, 0, serverManifest, clientManifest))
      ledger.decide(consentFor(checkpointProposal, 1, serverManifest, clientManifest))
      return CheckpointGrant(ledger, mask, generation, commitment, maximumTransactions)
    }
    val checkpointGrant = approvedCheckpointGrant()
    val checkpointContext = context.copy(
        serverManifestHash = serverManifest,
        clientManifestHash = clientManifest,
        expectedProfiles = mapOf(0 to "sgb2"),
        admissions = StatefulAdmissions(null, checkpointGrant))
    val checkpoint = checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, stateBytes)
    val malformedState = ByteArray(68) { 0x55 }
    assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, malformedState),
        context.copy(expectedProfiles = mapOf(0 to "sgb2"))))
    val deniedLedger = ConsentLedger(checkpointProposal, serverManifest, clientManifest)
    val deniedGrant = CheckpointGrant(
        deniedLedger, 0x03, roster.rosterGeneration, roster.rosterDigest())
    assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, malformedState),
        context.copy(
            serverManifestHash = serverManifest,
            clientManifestHash = clientManifest,
            admissions = StatefulAdmissions(null, deniedGrant))))
    listOf(
        checkpointContext.copy(wireSource = 1, wireTarget = 0),
        checkpointContext.copy(serverManifestHash = ByteArray(32) { 9 }),
        checkpointContext.copy(
            admissions = StatefulAdmissions(null, approvedCheckpointGrant(mask = 0x0f))),
        checkpointContext.copy(
            admissions = StatefulAdmissions(null, approvedCheckpointGrant(generation = 2))),
        checkpointContext.copy(
            admissions = StatefulAdmissions(
                null, approvedCheckpointGrant(maximumTransactions = 0))),
    ).forEachIndexed { index, rejectedContext ->
      assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
          "CHECKPOINT", 0, playerChannel(0).toLong(),
          checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, malformedState),
          rejectedContext), "checkpoint preflight $index")
    }
    assertEquals("STATEFILE_VERSION", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, malformedState),
        checkpointContext))
    assertEquals(0, checkpointGrant.used)
    assertEquals("PROFILE_MISMATCH", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpoint,
        checkpointContext.copy(expectedProfiles = mapOf(0 to "dmg"))))
    assertEquals(0, checkpointGrant.used)
    assertEquals(null, PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(), checkpoint, checkpointContext))
    assertEquals(1, checkpointGrant.used)
    assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(), checkpoint, checkpointContext))
    assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(1, 0x01, 0, 9, checkpointProposal.id, malformedState),
        checkpointContext))
    assertEquals(1, checkpointGrant.used)
    assertEquals("MALFORMED_HEADER", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpoint.copyOf().also { for (i in 16..19) it[i] = 0 }, checkpointContext))
    assertEquals("TOPOLOGY_MISMATCH", PayloadSchemas.validate(
        "CHECKPOINT", 0, GROUP_CHANNEL,
        checkpointPayload(2, 0x03, 0, 10, checkpointProposal.id, stateBytes),
        checkpointContext))

    val linkedStateBytes = linkedCheckpointState()
    val fourRoster = validFourRoster()
    val fourGrant = approvedCheckpointGrant(
        0x0f, fourRoster.rosterGeneration, fourRoster.rosterDigest())
    val fourContext = PayloadContext(
        mode = "four",
        authenticatedPlayer = 1,
        rosterMask = 0x0f,
        rosterGeneration = fourRoster.rosterGeneration,
        rosterCommitment = fourRoster.rosterDigest(),
        serverManifestHash = serverManifest,
        clientManifestHash = clientManifest,
        expectedProfiles = (0..3).associateWith { "dmg" },
        admissions = StatefulAdmissions(null, fourGrant),
    )
    assertEquals("TOPOLOGY_MISMATCH", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(1, 0x01, 0, 10, checkpointProposal.id, stateBytes), fourContext))
    assertEquals("TOPOLOGY_MISMATCH", PayloadSchemas.validate(
        "CHECKPOINT", 0, GROUP_CHANNEL,
        checkpointPayload(2, 0x07, 0, 10, checkpointProposal.id, linkedStateBytes), fourContext))
    assertEquals("TOPOLOGY_MISMATCH", PayloadSchemas.validate(
        "CHECKPOINT", 0, playerChannel(0).toLong(),
        checkpointPayload(2, 0x0f, 0, 10, checkpointProposal.id, linkedStateBytes), fourContext))
    assertEquals(0, fourGrant.used)
    assertEquals(null, PayloadSchemas.validate(
        "CHECKPOINT", 0, GROUP_CHANNEL,
        checkpointPayload(2, 0x0f, 0, 10, checkpointProposal.id, linkedStateBytes), fourContext))
    assertEquals(1, fourGrant.used)

    val bulkBytes = ByteArray(32) { it.toByte() }
    val bulkProposal = romProposal().copy(
        size = bulkBytes.size.toLong(), digest = sha256(bulkBytes))
    fun approvedBulkContext(): PayloadContext {
      val ledger = ConsentLedger(bulkProposal, serverManifest, clientManifest)
      ledger.decide(consentFor(bulkProposal, 0, serverManifest, clientManifest))
      ledger.decide(consentFor(bulkProposal, 1, serverManifest, clientManifest))
      return context.copy(admissions = StatefulAdmissions(BulkTracker(ledger), null))
    }
    val rawBulkContext = approvedBulkContext()
    val begin = bulkBeginPayload(91, bulkProposal, chunkSize = 32)
    assertEquals(null, PayloadSchemas.validate(
        "ROM_BEGIN", 0, playerChannel(1).toLong(), begin, rawBulkContext))
    assertEquals("MALFORMED_HEADER", PayloadSchemas.validate(
        "ROM_BEGIN", 0, playerChannel(1).toLong(), begin.copyOf(51), approvedBulkContext()))
    val chunk = bulkChunkPayload(91, 0, bulkBytes)
    assertEquals(null, PayloadSchemas.validate("ROM_CHUNK", 0, 2, chunk, rawBulkContext))
    val end = bulkEndPayload(91, sha256(bulkBytes))
    assertEquals(null, PayloadSchemas.validate("ROM_END", 0, 2, end, rawBulkContext))
    assertEquals("MALFORMED_HEADER",
        PayloadSchemas.validate("ROM_END", 0, 2, end.copyOf(35), approvedBulkContext()))
    assertEquals("TRANSACTION_MISMATCH", PayloadSchemas.validate(
        "ROM_CHUNK", 0, 2, bulkChunkPayload(92, 0, byteArrayOf(1)), approvedBulkContext()))

    val batteryProposal = bulkProposal.copy(
        id = 92, assetClass = BATTERY_CLASS, assetKind = BATTERY_ASSET)
    val batteryLedger = ConsentLedger(batteryProposal, serverManifest, clientManifest)
    batteryLedger.decide(consentFor(batteryProposal, 0, serverManifest, clientManifest))
    batteryLedger.decide(consentFor(batteryProposal, 1, serverManifest, clientManifest))
    val batteryContext = context.copy(
        admissions = StatefulAdmissions(BulkTracker(batteryLedger), null))
    val batteryBegin = bulkBeginPayload(93, batteryProposal, 32)
    assertEquals(null, PayloadSchemas.validate(
        "BATTERY_BEGIN", 0, 2, batteryBegin, batteryContext))
    assertEquals("CONSENT_REJECTED", PayloadSchemas.validate(
        "BATTERY_BEGIN", 0, 2,
        batteryBegin.copyOf().also { it[11] = PRIMARY_ROM_ASSET.toByte() },
        context.copy(admissions = StatefulAdmissions(BulkTracker(batteryLedger), null))))
    assertEquals(null, PayloadSchemas.validate(
        "BATTERY_CHUNK", 0, 2, bulkChunkPayload(93, 0, bulkBytes), batteryContext))
    assertEquals("TRANSACTION_MISMATCH", PayloadSchemas.validate(
        "BATTERY_CHUNK", 0, 2, bulkChunkPayload(94, 0, byteArrayOf(1)),
        approvedBulkContext()))
    assertEquals(null, PayloadSchemas.validate(
        "BATTERY_END", 0, 2, bulkEndPayload(93, sha256(bulkBytes)), batteryContext))
    assertEquals("MALFORMED_HEADER", PayloadSchemas.validate(
        "BATTERY_END", 0, 2, ByteArray(35), batteryContext))

    var unknownFailed = false
    try {
      PayloadSchemas.validate("NOT_REGISTERED", 0, 0, ByteArray(0), context)
    } catch (_: IllegalArgumentException) {
      unknownFailed = true
    }
    assertTrue(unknownFailed)
  }

  @Test
  fun bulkTransactionsAreDirectionalConsentBoundAndAtomic() {
    assertEquals(32, rows("/netplay-v9/limits.tsv")
        .single { it.getValue("name") == "CHECKPOINTS_PER_DIRECTIONAL_GRANT" }.int("value"))
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
    val rosterCommitment = ByteArray(32) { 3 }
    val checkpointGrant = CheckpointGrant(
        checkpointLedger, 0x03, 7, rosterCommitment, maximumTransactions = 3)
    val checkpointContext = PayloadContext(
        "normal", 1, 0x03, 7, rosterCommitment,
        serverManifestHash = serverManifest,
        clientManifestHash = clientManifest)
    val checkpointDeclaration = CheckpointDeclaration(
        43, 1, 0x01, 0, 10, 1024, ByteArray(32) { 4 })
    assertEquals("CONSENT_REJECTED",
        checkpointGrant.admit(checkpointDeclaration, checkpointContext))
    assertEquals("CONSENT_REQUIRED", checkpointLedger.decide(
        consentFor(checkpointProposal, 0, serverManifest, clientManifest)))
    assertEquals("CONSENT_REJECTED",
        checkpointGrant.admit(checkpointDeclaration, checkpointContext))
    assertEquals("SUCCESS", checkpointLedger.decide(
        consentFor(checkpointProposal, 1, serverManifest, clientManifest)))
    assertEquals("CONSENT_REJECTED", checkpointGrant.admit(
        checkpointDeclaration,
        checkpointContext.copy(wireSource = 1, wireTarget = 0)))
    assertEquals("SUCCESS", checkpointGrant.admit(checkpointDeclaration, checkpointContext))
    assertEquals("SUCCESS", checkpointGrant.admit(
        checkpointDeclaration.copy(frame = 11, stateDigest = ByteArray(32) { 5 }),
        checkpointContext))
    assertEquals("SUCCESS", checkpointGrant.admit(
        checkpointDeclaration.copy(frame = 12, stateDigest = ByteArray(32) { 6 }),
        checkpointContext))
    assertEquals("CONSENT_REJECTED", checkpointGrant.admit(
        checkpointDeclaration.copy(frame = 12, stateDigest = ByteArray(32) { 6 }),
        checkpointContext))
    assertEquals("CONSENT_REJECTED", checkpointGrant.admit(
        checkpointDeclaration.copy(frame = 13, stateDigest = ByteArray(32) { 7 }),
        checkpointContext))
    assertEquals(3, checkpointGrant.used)
    assertEquals("CONSENT_REJECTED", checkpointGrant.admit(
        checkpointDeclaration.copy(frame = 14),
        checkpointContext.copy(rosterGeneration = 8)))

    val unsignedLedger = ConsentLedger(
        checkpointProposal, serverManifest, clientManifest).also {
      assertEquals("CONSENT_REQUIRED", it.decide(
          consentFor(checkpointProposal, 0, serverManifest, clientManifest)))
      assertEquals("SUCCESS", it.decide(
          consentFor(checkpointProposal, 1, serverManifest, clientManifest)))
    }
    val unsignedGrant = CheckpointGrant(
        unsignedLedger, 0x03, 7, rosterCommitment, maximumTransactions = 3)
    assertEquals("SUCCESS", unsignedGrant.admit(
        checkpointDeclaration.copy(frame = Long.MAX_VALUE), checkpointContext))
    assertEquals("SUCCESS", unsignedGrant.admit(
        checkpointDeclaration.copy(frame = Long.MIN_VALUE), checkpointContext))
    assertEquals("CONSENT_REJECTED", unsignedGrant.preflight(
        checkpointDeclaration.copy(frame = Long.MAX_VALUE), checkpointContext))
    assertEquals("SUCCESS", unsignedGrant.admit(
        checkpointDeclaration.copy(frame = -1L), checkpointContext))
    assertEquals("CONSENT_REJECTED", unsignedGrant.admit(
        checkpointDeclaration.copy(frame = -1L), checkpointContext))
    assertEquals(3, unsignedGrant.used)
  }

  @Test
  fun repeatedConsentBarrierRequiresEveryActorProposalAndPreparation() {
    val rom = romProposal()
    val battery = Proposal(
        id = 42, action = OFFER, assetClass = BATTERY_CLASS, assetKind = BATTERY_ASSET,
        owner = 1, source = 0, target = 1, size = 8_192,
        digest = ByteArray(32) { 9 })
    val serverManifest = ByteArray(32) { 1 }
    val clientManifest = ByteArray(32) { 2 }
    val empty = ConsentBook(emptyList(), serverManifest, clientManifest, 1)
    assertEquals("ALL_ITEMS_APPROVED", empty.status)
    assertTrue(empty.canStart(candidatesPrepared = true))

    val book = ConsentBook(listOf(rom, battery), serverManifest, clientManifest, 1)
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("CONSENT_REQUIRED", book.decide(
        consentFor(rom, 0, serverManifest, clientManifest)))
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("ITEM_APPROVED", book.decide(
        consentFor(rom, 1, serverManifest, clientManifest)))
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("CONSENT_REQUIRED", book.decide(
        consentFor(battery, 1, serverManifest, clientManifest)))
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("ALL_ITEMS_APPROVED", book.decide(
        consentFor(battery, 0, serverManifest, clientManifest)))
    assertFalse(book.canStart(candidatesPrepared = false))
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("SUCCESS", book.markPrepared(rom.id))
    assertFalse(book.canStart(candidatesPrepared = true))
    assertEquals("SUCCESS", book.markPrepared(battery.id))
    assertTrue(book.canStart(candidatesPrepared = true))

    val crossGuest = rom.copy(id = 99, source = 0, target = 2, owner = 2)
    assertEquals("CONSENT_REJECTED",
        ConsentBook(listOf(crossGuest), serverManifest, clientManifest, 1).status)
    val rejected = ConsentBook(listOf(rom), serverManifest, clientManifest, 1)
    assertEquals("CONSENT_REJECTED", rejected.decide(
        consentFor(rom, 1, serverManifest, clientManifest, REJECT)))
    assertFalse(rejected.canStart(candidatesPrepared = true))

    val maximum = (0 until 8).map { index -> rom.copy(id = 100L + index) }
    val maximumBook = ConsentBook(maximum, serverManifest, clientManifest, 1)
    maximum.forEachIndexed { index, item ->
      val firstActor = if (index % 2 == 0) 0 else 1
      val secondActor = 1 - firstActor
      maximumBook.decide(consentFor(
          item, firstActor, serverManifest, clientManifest))
      maximumBook.decide(consentFor(
          item, secondActor, serverManifest, clientManifest))
    }
    assertEquals("ALL_ITEMS_APPROVED", maximumBook.status)
    assertFalse(maximumBook.canStart(candidatesPrepared = true))
    maximum.reversed().forEach { item ->
      assertEquals("SUCCESS", maximumBook.markPrepared(item.id))
    }
    assertEquals(8, maximumBook.preparedCount)
    assertTrue(maximumBook.canStart(candidatesPrepared = true))
    assertEquals("CONSENT_REJECTED",
        ConsentBook(maximum + rom.copy(id = 108), serverManifest, clientManifest, 1).status)

    val duplicateDecision = ConsentBook(listOf(rom), serverManifest, clientManifest, 1)
    assertEquals("CONSENT_REQUIRED", duplicateDecision.decide(
        consentFor(rom, 0, serverManifest, clientManifest)))
    assertEquals("CONSENT_REJECTED", duplicateDecision.decide(
        consentFor(rom, 0, serverManifest, clientManifest)))
    assertEquals(0, duplicateDecision.preparedCount)
    assertFalse(duplicateDecision.canStart(candidatesPrepared = true))
  }

  @Test
  fun manifestSenderIsBoundToTheActualWireDirection() {
    rows("/netplay-v9/manifest-direction-vectors.tsv").forEach { row ->
      val proposal = romProposal().copy(
          action = row.int("action"),
          source = row.int("proposal_source"),
          target = row.int("proposal_target"),
      )
      val manifest = validNormalManifest(
          sender = row.int("sender"),
          proposals = listOf(proposal),
      )
      val context = PayloadContext(
          mode = row.getValue("mode"),
          authenticatedPlayer = row.int("guest"),
          rosterMask = 0x03,
          rosterGeneration = manifest.rosterGeneration,
          rosterCommitment = manifest.rosterDigest(),
          wireSource = row.int("wire_source"),
          wireTarget = row.int("wire_target"),
      )
      val outcome = if (Manifest.decode(manifest.encode(), context) == null) {
        "SUCCESS"
      } else {
        "MANIFEST_MISMATCH"
      }
      assertEquals(row.getValue("expected"), outcome, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-manifest-direction", row.getValue("provenance"))
    }
  }

  @Test
  fun manifestProposalClassCeilingsAndContentBindingsAreSemantic() {
    val entries = listOf(
        manifestEntry(0, "HOST", 1).copy(
            slotPresent = true,
            slotLength = 16_384,
            slotDigest = ByteArray(32) { 11 }),
        manifestEntry(1, "GUEST", 2).copy(
            slotPresent = true,
            slotLength = 16_384,
            slotDigest = ByteArray(32) { 12 }),
    )
    val context = PayloadContext(
        mode = "normal",
        authenticatedPlayer = 1,
        rosterMask = 0x03,
        rosterGeneration = 1,
        wireSource = 0,
        wireTarget = 1,
    )
    val primaryHost = Proposal(
        51, OFFER, ROM_CLASS, PRIMARY_ROM_ASSET, 0, 0, 1, 32_768,
        entries[0].primaryDigest)
    val slotHost = Proposal(
        52, OFFER, ROM_CLASS, SLOT_ROM_ASSET, 0, 0, 1, 16_384,
        entries[0].slotDigest)
    val primaryGuest = Proposal(
        53, OFFER, ROM_CLASS, PRIMARY_ROM_ASSET, 1, 0, 1, 32_768,
        entries[1].primaryDigest)
    val threeRom = Manifest(
        1, 0, 0x03, 1, entries,
        listOf(
            ManifestDiff(PRIMARY_ROM_DIFFERENT_DIFF, WARNING, 0, primaryHost.id),
            ManifestDiff(SLOT_ROM_DIFFERENT_DIFF, WARNING, 0, slotHost.id),
            ManifestDiff(PRIMARY_ROM_DIFFERENT_DIFF, WARNING, 1, primaryGuest.id),
        ),
        listOf(primaryHost, slotHost, primaryGuest),
    )
    assertEquals("proposal-class-limit", Manifest.decode(threeRom.encode(), context))

    val batteryHost = Proposal(
        61, OFFER, BATTERY_CLASS, BATTERY_ASSET, 0, 0, 1, 8_192,
        ByteArray(32) { 21 })
    val batteryGuest = Proposal(
        62, OFFER, BATTERY_CLASS, BATTERY_ASSET, 1, 0, 1, 8_192,
        ByteArray(32) { 22 })
    val twoBattery = Manifest(
        1, 0, 0x03, 1, entries,
        listOf(
            ManifestDiff(BATTERY_TRANSFER_DIFF, WARNING, 0, batteryHost.id),
            ManifestDiff(BATTERY_TRANSFER_DIFF, WARNING, 1, batteryGuest.id),
        ),
        listOf(batteryHost, batteryGuest),
    )
    assertEquals("proposal-class-limit", Manifest.decode(twoBattery.encode(), context))

    val checkpointOne = Proposal(
        71, OFFER, CHECKPOINT_CLASS, CHECKPOINT_ASSET, GROUP_PLAYER, 0, 1, 0,
        ByteArray(32))
    val checkpointTwo = checkpointOne.copy(id = 72)
    val twoCheckpoint = Manifest(
        1, 0, 0x03, 1, entries,
        listOf(ManifestDiff(CHECKPOINT_SYNC_DIFF, WARNING, 1, checkpointOne.id)),
        listOf(checkpointOne, checkpointTwo),
    )
    assertEquals("proposal-class-limit", Manifest.decode(twoCheckpoint.encode(), context))

    val wrongBinding = Manifest(
        1, 0, 0x03, 1, entries,
        listOf(ManifestDiff(PRIMARY_ROM_DIFFERENT_DIFF, WARNING, 0, primaryHost.id)),
        listOf(primaryHost.copy(size = primaryHost.size - 1)),
    )
    assertEquals("proposal-content", Manifest.decode(wrongBinding.encode(), context))
  }

  @Test
  fun hostCoordinatorOwnsSlotsChannelsRosterAndOneAtomicFourPlayerCheckpoint() {
    rows("/netplay-v9/topology-vectors.tsv").forEach { row ->
      val commitment = ByteArray(32) { row.int("commitment_byte").toByte() }
      val checkpointDigest = ByteArray(32) { row.int("checkpoint_digest_byte").toByte() }
      val coordinator = TopologyCoordinator(
          row.getValue("mode"),
          row.hexInt("live_mask"),
          row.hexInt("target_mask"),
          row.long("target_generation"),
          commitment,
          checkpointDigest,
      )
      val result = coordinator.prepareCandidate(
          authenticatedPlayer = row.int("authenticated_player"),
          candidatePlayer = row.int("candidate_player"),
          rosterMask = row.hexInt("roster_mask"),
          rosterGeneration = row.long("roster_generation"),
          rosterCommitment = ByteArray(32) { row.int("candidate_commitment_byte").toByte() },
          checkpointKind = row.getValue("checkpoint_kind"),
          checkpointMask = row.hexInt("checkpoint_mask"),
          checkpointDigest = ByteArray(32) {
            row.int("candidate_checkpoint_digest_byte").toByte()
          },
      )
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
      assertEquals(row.hexInt("committed_mask"), coordinator.liveMask, row.getValue("id"))
      assertEquals(row.hexInt("candidate_mask"), coordinator.candidateMask, row.getValue("id"))
      assertEquals(row.getValue("barrier_open").toBoolean(),
          coordinator.barrierOpen, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-topology", row.getValue("provenance"))
    }

    assertEquals(1, playerChannel(0))
    assertEquals(2, playerChannel(1))
    assertEquals(3, playerChannel(2))
    assertEquals(4, playerChannel(3))

    val normalCommitment = ByteArray(32) { 7 }
    val normalCheckpoint = ByteArray(32) { 8 }
    val normal = TopologyCoordinator(
        "normal", 0x01, 0x03, 1, normalCommitment, normalCheckpoint)
    assertEquals("SUCCESS", normal.prepareCandidate(
        1, 1, 0x03, 1, normalCommitment, "SESSION", 0x02, normalCheckpoint))
    assertTrue(normal.barrierOpen)
    assertEquals(0x01, normal.liveMask)
    assertEquals("SUCCESS", normal.commitBarrier())
    assertEquals(0x03, normal.liveMask)

    val roster = validFourRoster()
    val commitment = roster.rosterDigest()
    val checkpointDigest = sha256(ByteArray(128) { it.toByte() })
    val coordinator = TopologyCoordinator(
        "four", 0x01, 0x0f, roster.rosterGeneration, commitment, checkpointDigest)
    assertEquals("SUCCESS", coordinator.prepareCandidate(
        1, 1, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    assertEquals(0x01, coordinator.liveMask)
    assertEquals(0x02, coordinator.candidateMask)
    assertFalse(coordinator.barrierOpen)
    assertEquals("SUCCESS", coordinator.prepareCandidate(
        2, 2, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    assertEquals(0x01, coordinator.liveMask)
    assertEquals(0x06, coordinator.candidateMask)
    assertFalse(coordinator.barrierOpen)
    assertEquals("TOPOLOGY_MISMATCH", coordinator.prepareCandidate(
        3, 3, 0x0f, roster.rosterGeneration, commitment.copyOf().also {
          it[0] = (it[0].toInt() xor 1).toByte()
        }, "LINKED_SESSION", 0x0f, checkpointDigest))
    assertEquals(0x06, coordinator.candidateMask)
    assertEquals(0x01, coordinator.liveMask)
    assertEquals("SUCCESS", coordinator.prepareCandidate(
        3, 3, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    assertTrue(coordinator.barrierOpen)
    assertEquals(0x01, coordinator.liveMask)
    assertEquals("SUCCESS", coordinator.commitBarrier())
    assertEquals(0x0f, coordinator.liveMask)
    assertEquals("SERVER_FULL", coordinator.prepareCandidate(
        1, 1, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    assertEquals("SUCCESS", coordinator.prepareReplacement(
        1, roster.rosterGeneration, commitment, checkpointDigest))
    assertEquals("TOPOLOGY_MISMATCH", coordinator.prepareReplacement(
        1, roster.rosterGeneration + 1, commitment, checkpointDigest))
    assertEquals(0x0f, coordinator.liveMask)

    val isolated = TopologyCoordinator(
        "four", 0x01, 0x0f, roster.rosterGeneration, commitment, checkpointDigest)
    assertEquals("SUCCESS", isolated.prepareCandidate(
        1, 1, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    assertEquals("SUCCESS", isolated.prepareCandidate(
        2, 2, 0x0f, roster.rosterGeneration, commitment, "LINKED_SESSION", 0x0f,
        checkpointDigest))
    isolated.failCandidate(2)
    assertEquals(0x02, isolated.candidateMask)
    assertEquals(0x01, isolated.liveMask)
    assertFalse(isolated.barrierOpen)
  }

  @Test
  fun manifestDiffAndItemConsentVectorsExecuteNoImplicitTransferPolicy() {
    rows("/netplay-v9/manifest-consent-vectors.tsv").forEach { row ->
      val result = ManifestConsentScenarios().execute(row)
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
      val rosterGeneration: Long = 1,
      val rosterCommitment: ByteArray? = null,
      val serverManifestHash: ByteArray? = null,
      val clientManifestHash: ByteArray? = null,
      val expectedProfiles: Map<Int, String> = emptyMap(),
      val sessionId: Long? = null,
      val outstandingPingNonce: Long? = null,
      val admissions: StatefulAdmissions? = null,
      val wireSource: Int = 0,
      val wireTarget: Int = 1,
  )

  private object PayloadSchemas {
    val supportedMessages = setOf(
        "HELLO", "AUTH", "AUTH_RESULT", "MANIFEST", "CONSENT", "START", "READY", "INPUT",
        "CHECKPOINT", "ROM_BEGIN", "ROM_CHUNK", "ROM_END", "BATTERY_BEGIN",
        "BATTERY_CHUNK", "BATTERY_END", "RESET", "STOP", "PING", "PONG", "CANCEL",
        "GOODBYE", "ERROR")
    val supportedSchemas = setOf(
        "hello-v1", "auth-v1", "auth-result-v1", "manifest-v1", "consent-v1",
        "start-v1", "ready-v1", "input-v1", "statefile-v2-group-v1", "bulk-begin-v1",
        "bulk-chunk-v1", "bulk-end-v1", "reset-v1", "stop-v1", "ping-v1", "pong-v1",
        "terminal-text-v1", "error-v1")

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
      "CHECKPOINT" -> validateCheckpoint(payload, channel, context)
      "ROM_BEGIN" -> validateBulkBegin("ROM", payload, channel, context)
      "ROM_CHUNK" -> validateBulkChunk("ROM", payload, context)
      "ROM_END" -> validateBulkEnd("ROM", payload, context)
      "BATTERY_BEGIN" -> validateBulkBegin("BATTERY", payload, channel, context)
      "BATTERY_CHUNK" -> validateBulkChunk("BATTERY", payload, context)
      "BATTERY_END" -> validateBulkEnd("BATTERY", payload, context)
      "RESET", "STOP" -> validatePlayerFrame(payload, channel, 16, context, input = false)
      "PING" -> if (payload.size != 16) "MALFORMED_HEADER" else null
      "PONG" -> if (payload.size != 16 || context.outstandingPingNonce == null ||
          u64(payload, 0) != context.outstandingPingNonce) "CORRELATION_ERROR" else null
      "CANCEL", "GOODBYE" -> validateTerminal(payload)
      "ERROR" -> validateError(payload)
      else -> throw IllegalArgumentException("No frozen payload validator for $message")
    }

    private fun validateCheckpoint(
        payload: ByteArray,
        channel: Long,
        context: PayloadContext,
    ): String? {
      if (payload.size < 88) return "STATEFILE_MALFORMED"
      val kind = payload[0].toInt() and 0xff
      val mask = payload[1].toInt() and 0xff
      val owner = payload[2].toInt() and 0xff
      if (kind !in 0..2 || payload[3].toInt() != 0 || mask !in 1..15 ||
          owner !in 0..3 || mask and (1 shl owner) == 0 ||
          context.mode == "normal" && (context.rosterMask != 0x03 || kind !in 0..1 ||
              Integer.bitCount(mask) != 1 || context.rosterMask and mask != mask ||
              channel != playerChannel(owner).toLong()) ||
          context.mode == "four" && (context.rosterMask != 0x0f || kind != 2 ||
              mask != 0x0f || channel != GROUP_CHANNEL) ||
          context.mode !in setOf("normal", "four")) {
        return "TOPOLOGY_MISMATCH"
      }
      val frame = u64(payload, 4)
      val stateLength = u32(payload, 12)
      val proposalId = u32(payload, 16)
      if (proposalId == 0L || stateLength != payload.size.toLong() - 20L) {
        return "MALFORMED_HEADER"
      }
      val declaration = CheckpointDeclaration(
          proposalId, kind, mask, owner, frame, stateLength,
          sha256(payload, 20, payload.size - 20))
      val grant = context.admissions?.checkpoint ?: return "CONSENT_REJECTED"
      val preflight = grant.preflight(declaration, context)
      if (preflight != "SUCCESS") return preflight

      val state = payload.copyOfRange(20, payload.size)
      if (!state.copyOfRange(0, minOf(4, state.size))
              .contentEquals("CGBS".toByteArray(StandardCharsets.US_ASCII)) ||
          state.size < 68 || u16(state, 4) != 2) return "STATEFILE_VERSION"
      val inspection = try {
        eu.rekawek.coffeegb.controller.state.StateCodec.inspect(state)
      } catch (e: eu.rekawek.coffeegb.controller.state.StateDecodeException) {
        return when (e.reason) {
          eu.rekawek.coffeegb.controller.state.StateDecodeReason.UNSUPPORTED_FORMAT_VERSION ->
            "STATEFILE_VERSION"
          eu.rekawek.coffeegb.controller.state.StateDecodeReason.CORRUPT_CHECKSUM ->
            "CHECKSUM_MISMATCH"
          eu.rekawek.coffeegb.controller.state.StateDecodeReason.LIMIT_EXCEEDED ->
            "LIMIT_EXCEEDED"
          else -> "STATEFILE_MALFORMED"
        }
      }
      val expectedRoot = when (kind) {
        0 -> eu.rekawek.coffeegb.controller.state.StateRootKind.MACHINE
        1 -> eu.rekawek.coffeegb.controller.state.StateRootKind.SESSION
        else -> eu.rekawek.coffeegb.controller.state.StateRootKind.LINKED_SESSION
      }
      if (inspection.rootKind != expectedRoot) return "ROOT_KIND_MISMATCH"
      val identityMask = inspection.identities.filter { it.identity != null }
          .fold(0) { current, identity -> current or (1 shl identity.player) }
      if (identityMask != mask) return "TOPOLOGY_MISMATCH"
      if (context.expectedProfiles.isNotEmpty() && inspection.identities
          .filter { it.identity != null }
          .any { inspected ->
            context.expectedProfiles[inspected.player] !=
                inspected.identity!!.profile.canonicalProfileId
          }) return "PROFILE_MISMATCH"
      return grant.commit(declaration, context).takeUnless { it == "SUCCESS" }
    }

    private fun validateBulkBegin(
        assetClass: String,
        payload: ByteArray,
        channel: Long,
        context: PayloadContext,
    ): String? {
      if (payload.size != 52) return "MALFORMED_HEADER"
      val declaration = BulkDeclaration(
          u32(payload, 0), u32(payload, 4),
          payload[8].toInt() and 0xff, payload[9].toInt() and 0xff,
          payload[10].toInt() and 0xff, payload[11].toInt() and 0xff,
          u32(payload, 12), payload.copyOfRange(16, 48), u32(payload, 48))
      return (context.admissions?.bulk?.begin(assetClass, channel.toInt(), declaration)
          ?: "CONSENT_REJECTED").takeUnless { it == "SUCCESS" }
    }

    private fun validateBulkChunk(
        assetClass: String,
        payload: ByteArray,
        context: PayloadContext,
    ): String? {
      if (payload.size !in 9..65_544) return "MALFORMED_HEADER"
      return (context.admissions?.bulk?.chunk(
          assetClass, u32(payload, 0), u32(payload, 4), payload.copyOfRange(8, payload.size))
          ?: "TRANSACTION_MISMATCH").takeUnless { it == "SUCCESS" }
    }

    private fun validateBulkEnd(
        assetClass: String,
        payload: ByteArray,
        context: PayloadContext,
    ): String? {
      if (payload.size != 36) return "MALFORMED_HEADER"
      return (context.admissions?.bulk?.end(
          assetClass, u32(payload, 0), payload.copyOfRange(4, 36))
          ?: "TRANSACTION_MISMATCH").takeUnless { it == "SUCCESS" }
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

  private data class EntryBinding(
      val player: Int,
      val primaryPresent: Boolean,
      val slotPresent: Boolean,
      val batteryPresent: Boolean,
      val primaryLength: Long,
      val slotLength: Long,
      val primaryDigest: ByteArray,
      val slotDigest: ByteArray,
  )

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
      output.write("CoffeeGB-v9-roster-v2".toByteArray(StandardCharsets.US_ASCII))
      output.write(mode)
      output.write(rosterMask)
      output.write(ByteBuffer.allocate(4).putInt(rosterGeneration.toInt()).array())
      entries.forEach { output.write(it.player) }
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
            context.mode == "four" && (mode != 2 || rosterMask != 0x0f) ||
            sender != context.wireSource ||
            rosterMask != context.rosterMask ||
            u32(payload, 16) != context.rosterGeneration ||
            context.rosterCommitment?.let {
              !MessageDigest.isEqual(payload.copyOfRange(20, 52), it)
            } == true) return "mode"

        var offset = 52
        val entryBytes = mutableListOf<ByteArray>()
        val entryBindings = mutableMapOf<Int, EntryBinding>()
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
          val entryFlags = entry[1].toInt() and 0xff
          entryBindings[player] = EntryBinding(
              player,
              entryFlags and 1 != 0,
              entryFlags and 2 != 0,
              entryFlags and 4 != 0,
              u32(entry, 8),
              u32(entry, 12),
              entry.copyOfRange(16, 48),
              entry.copyOfRange(48, 80),
          )
          offset += length
        }
        if (players.toSet() != (0..3).filter { rosterMask and (1 shl it) != 0 }.toSet()) {
          return "roster"
        }
        val expectedRoster = ByteArrayOutputStream().apply {
          write("CoffeeGB-v9-roster-v2".toByteArray(StandardCharsets.US_ASCII))
          write(mode)
          write(rosterMask)
          write(ByteBuffer.allocate(4).putInt(u32(payload, 16).toInt()).array())
          players.forEach(::write)
        }.toByteArray()
        if (!MessageDigest.isEqual(payload.copyOfRange(20, 52), sha256(expectedRoster))) {
          return "roster-digest"
        }

        val decodedDiffs = mutableListOf<ManifestDiff>()
        val diffKeys = mutableSetOf<Pair<Int, Int>>()
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
            in 11..12 -> WARNING
            else -> -1
          }
          if (severity != expectedSeverity || player !in 0..3 ||
              u32(payload, offset + 8) != 0L ||
              severity == WARNING && proposalId == 0L ||
              severity != WARNING && proposalId != 0L) return "diff"
          if (!diffKeys.add(code to player)) return "diff-duplicate"
          if (severity == WARNING && !warningProposalIds.add(proposalId)) {
            return "diff-proposal"
          }
          decodedDiffs += ManifestDiff(code, severity, player, proposalId)
          offset += 12
        }
        val proposalIds = mutableSetOf<Long>()
        val decodedProposals = mutableMapOf<Long, Proposal>()
        val proposalKeys = mutableSetOf<Triple<Int, Int, Int>>()
        val classCounts = mutableMapOf<Int, Int>()
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
              setOf(source, target) != setOf(0, context.authenticatedPlayer) ||
              disposition != WARNING || payload[offset + 11].toInt() != 0 ||
              action == OFFER && sender != source || action == REQUEST && sender != target ||
              !classMatchesAsset(assetClass, assetKind) ||
              assetKind == CHECKPOINT_ASSET && (owner != GROUP_PLAYER ||
                  size != 0L || digest.any { byte -> byte.toInt() != 0 }) ||
              assetKind != CHECKPOINT_ASSET && (owner !in 0..3 ||
                  size == 0L || digest.all { byte -> byte.toInt() == 0 })) return "proposal"
          val proposal = Proposal(
              id, action, assetClass, assetKind, owner, source, target, size, digest)
          val classCount = Math.addExact(classCounts.getOrDefault(assetClass, 0), 1)
          classCounts[assetClass] = classCount
          if (assetClass == ROM_CLASS && classCount > 2 ||
              assetClass in setOf(BATTERY_CLASS, CHECKPOINT_CLASS) && classCount > 1) {
            return "proposal-class-limit"
          }
          if (!proposalKeys.add(Triple(assetClass, assetKind, owner))) return "proposal-duplicate"
          if (assetKind in setOf(PRIMARY_ROM_ASSET, SLOT_ROM_ASSET)) {
            val entry = entryBindings[owner] ?: return "proposal-owner"
            val present = if (assetKind == PRIMARY_ROM_ASSET) entry.primaryPresent else entry.slotPresent
            val expectedSize = if (assetKind == PRIMARY_ROM_ASSET) {
              entry.primaryLength
            } else {
              entry.slotLength
            }
            val expectedDigest = if (assetKind == PRIMARY_ROM_ASSET) {
              entry.primaryDigest
            } else {
              entry.slotDigest
            }
            if (!present || size != expectedSize ||
                !MessageDigest.isEqual(digest, expectedDigest)) return "proposal-content"
          }
          decodedProposals[id] = proposal
          offset += 48
        }
        if (warningProposalIds != proposalIds) return "diff-proposal"
        if (decodedDiffs.groupBy { it.player }.any { (_, playerDiffs) ->
              playerDiffs.any { it.code == MATCH_DIFF } && playerDiffs.size != 1
            }) return "match-masks-difference"
        decodedDiffs.filter { it.severity == WARNING }.forEach { diff ->
          val proposal = decodedProposals[diff.proposalId] ?: return "diff-proposal"
          val expectedAsset = when (diff.code) {
            PRIMARY_ROM_MISSING_DIFF, PRIMARY_ROM_DIFFERENT_DIFF -> PRIMARY_ROM_ASSET
            SLOT_ROM_MISSING_DIFF, SLOT_ROM_DIFFERENT_DIFF -> SLOT_ROM_ASSET
            BATTERY_TRANSFER_DIFF -> BATTERY_ASSET
            CHECKPOINT_SYNC_DIFF -> CHECKPOINT_ASSET
            else -> return "diff-code"
          }
          if (proposal.assetKind != expectedAsset ||
              expectedAsset != CHECKPOINT_ASSET && proposal.owner != diff.player ||
              expectedAsset == CHECKPOINT_ASSET && (proposal.owner != GROUP_PLAYER ||
                  diff.player != context.authenticatedPlayer)) return "diff-proposal"
        }
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
            setOf(source, target) != setOf(0, context.authenticatedPlayer) ||
            context.rosterMask and (1 shl source) == 0 ||
            context.rosterMask and (1 shl target) == 0 ||
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
      val proposal: Proposal,
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
      if (proposal.assetClass == CHECKPOINT_CLASS || transactionClaimed || rejected ||
          approvals != setOf(proposal.source, proposal.target) ||
          declaration.proposalId != proposal.id ||
          declaration.source != proposal.source || declaration.target != proposal.target ||
          declaration.owner != proposal.owner || declaration.assetKind != proposal.assetKind ||
          declaration.total != proposal.size ||
          !MessageDigest.isEqual(declaration.digest, proposal.digest)) return false
      transactionClaimed = true
      return true
    }

    fun approved(): Boolean =
        !rejected && approvals == setOf(proposal.source, proposal.target)

    fun matchesManifestPair(server: ByteArray?, client: ByteArray?): Boolean =
        server != null && client != null &&
            MessageDigest.isEqual(server, serverManifest) &&
            MessageDigest.isEqual(client, clientManifest)

    private fun matches(consent: Consent): Boolean =
        consent.decisionId == proposal.id && consent.proposalId == proposal.id &&
            consent.assetClass == proposal.assetClass && consent.assetKind == proposal.assetKind &&
            consent.source == proposal.source && consent.target == proposal.target &&
            consent.owner == proposal.owner && consent.size == proposal.size &&
            MessageDigest.isEqual(consent.digest, proposal.digest) &&
            MessageDigest.isEqual(consent.serverManifest, serverManifest) &&
            MessageDigest.isEqual(consent.clientManifest, clientManifest)
  }

  private class ConsentBook(
      proposals: List<Proposal>,
      serverManifest: ByteArray,
      clientManifest: ByteArray,
      authenticatedGuest: Int,
  ) {
    private val ledgers = proposals.associate { proposal ->
      proposal.id to ConsentLedger(proposal, serverManifest, clientManifest)
    }
    private val prepared = mutableSetOf<Long>()
    var status = when {
      proposals.isEmpty() -> "ALL_ITEMS_APPROVED"
      proposals.all { setOf(it.source, it.target) == setOf(0, authenticatedGuest) } ->
        "CONSENT_REQUIRED"
      else -> "CONSENT_REJECTED"
    }
      private set

    init {
      if (proposals.map { it.id }.toSet().size != proposals.size ||
          proposals.size > 8) status = "CONSENT_REJECTED"
    }

    fun decide(consent: Consent): String {
      if (status == "CONSENT_REJECTED") return status
      val ledger = ledgers[consent.proposalId] ?: return "CONSENT_REJECTED"
      val item = ledger.decide(consent)
      if (item == "CONSENT_REJECTED") {
        status = item
        return item
      }
      status = if (ledgers.values.all(ConsentLedger::approved)) {
        "ALL_ITEMS_APPROVED"
      } else if (ledger.approved()) {
        "ITEM_APPROVED"
      } else {
        "CONSENT_REQUIRED"
      }
      return status
    }

    fun markPrepared(proposalId: Long): String {
      val ledger = ledgers[proposalId] ?: return "CONSENT_REJECTED"
      if (!ledger.approved() || !prepared.add(proposalId)) return "CONSENT_REJECTED"
      return "SUCCESS"
    }

    fun canStart(candidatesPrepared: Boolean): Boolean =
        status != "CONSENT_REJECTED" && candidatesPrepared &&
            ledgers.values.all(ConsentLedger::approved) &&
            prepared == ledgers.keys

    val preparedCount: Int get() = prepared.size
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
    private var openClass: String? = null
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
      openClass = assetClass
      return "SUCCESS"
    }

    fun chunk(assetClass: String, transactionId: Long, offset: Long, data: ByteArray): String {
      if (assetClass != openClass) return "TRANSACTION_MISMATCH"
      return chunk(transactionId, offset, data)
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

    fun end(assetClass: String, transactionId: Long, digest: ByteArray): String {
      if (assetClass != openClass) return "TRANSACTION_MISMATCH"
      return end(transactionId, digest)
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
      openClass = null
      return "SUCCESS"
    }
  }

  private data class StatefulAdmissions(
      val bulk: BulkTracker?,
      val checkpoint: CheckpointGrant?,
  )

  private data class CheckpointDeclaration(
      val proposalId: Long,
      val kind: Int,
      val rosterMask: Int,
      val owner: Int,
      val frame: Long,
      val stateLength: Long,
      val stateDigest: ByteArray,
  )

  /**
   * CHECKPOINT consent is the sole non-one-use grant. It is directional, bound to one manifest
   * pair/roster generation, admits at most 32 checkpoints, and requires strictly increasing frames.
   * ROM and battery continue to consume their ItemConsent exactly once.
   */
  private class CheckpointGrant(
      private val consent: ConsentLedger,
      private val rosterMask: Int,
      private val rosterGeneration: Long,
      private val rosterCommitment: ByteArray,
      private val maximumTransactions: Int = 32,
  ) {
    private var admitted = 0
    private var lastFrame: Long? = null

    val used: Int get() = admitted

    fun preflight(value: CheckpointDeclaration, context: PayloadContext): String {
      val proposal = consent.proposal
      if (!consent.approved() ||
          !consent.matchesManifestPair(
              context.serverManifestHash, context.clientManifestHash) ||
          proposal.assetClass != CHECKPOINT_CLASS ||
          proposal.assetKind != CHECKPOINT_ASSET ||
          proposal.owner != GROUP_PLAYER || proposal.size != 0L ||
          proposal.digest.any { it.toInt() != 0 } ||
          context.wireSource != proposal.source || context.wireTarget != proposal.target ||
          setOf(proposal.source, proposal.target) != setOf(0, context.authenticatedPlayer) ||
          value.proposalId != proposal.id || context.rosterMask != rosterMask ||
          context.rosterGeneration != rosterGeneration ||
          context.rosterCommitment == null ||
          !MessageDigest.isEqual(context.rosterCommitment, rosterCommitment) ||
          context.mode == "normal" && (rosterMask != 0x03 || value.kind !in 0..1 ||
              Integer.bitCount(value.rosterMask) != 1 ||
              value.rosterMask and rosterMask != value.rosterMask) ||
          context.mode == "four" && (rosterMask != 0x0f || value.kind != 2 ||
              value.rosterMask != 0x0f) ||
          context.mode !in setOf("normal", "four") ||
          value.owner !in 0..3 || value.rosterMask and (1 shl value.owner) == 0 ||
          value.stateLength <= 0 || value.stateDigest.size != 32 ||
          value.stateDigest.all { it.toInt() == 0 } ||
          admitted >= maximumTransactions ||
          lastFrame?.let { java.lang.Long.compareUnsigned(value.frame, it) <= 0 } == true) {
        return "CONSENT_REJECTED"
      }
      return "SUCCESS"
    }

    fun commit(value: CheckpointDeclaration, context: PayloadContext): String {
      val result = preflight(value, context)
      if (result != "SUCCESS") return result
      admitted++
      lastFrame = value.frame
      return "SUCCESS"
    }

    fun admit(value: CheckpointDeclaration, context: PayloadContext): String =
        commit(value, context)
  }

  private class TopologyCoordinator(
      private val mode: String,
      private val initialLiveMask: Int,
      private val targetMask: Int,
      private val targetGeneration: Long,
      private val targetCommitment: ByteArray,
      private val targetCheckpointDigest: ByteArray,
  ) {
    var liveMask = initialLiveMask
      private set
    var candidateMask = 0
      private set
    val barrierOpen: Boolean
      get() {
        if (targetMask != exactRosterMask(mode)) return false
        val requiredCandidates = targetMask and initialLiveMask.inv() and 0x0f
        return liveMask == targetMask || candidateMask == requiredCandidates
      }

    fun prepareCandidate(
        authenticatedPlayer: Int,
        candidatePlayer: Int,
        rosterMask: Int,
        rosterGeneration: Long,
        rosterCommitment: ByteArray,
        checkpointKind: String,
        checkpointMask: Int,
        checkpointDigest: ByteArray,
    ): String {
      val legalPlayer = if (mode == "normal") authenticatedPlayer == 1 else authenticatedPlayer in 1..3
      if (!legalPlayer || authenticatedPlayer != candidatePlayer) return "AUTH_FAILED"
      val bit = 1 shl candidatePlayer
      if (liveMask and bit != 0 || candidateMask and bit != 0) return "SERVER_FULL"
      if (targetMask != exactRosterMask(mode) || rosterMask != targetMask ||
          rosterGeneration != targetGeneration ||
          rosterMask and 1 == 0 || rosterMask and bit == 0 ||
          !MessageDigest.isEqual(rosterCommitment, targetCommitment) ||
          !MessageDigest.isEqual(checkpointDigest, targetCheckpointDigest)) {
        return "TOPOLOGY_MISMATCH"
      }
      if (mode == "normal") {
        if (targetMask != 0x03 || checkpointKind != "SESSION") return "ROOT_KIND_MISMATCH"
        if (checkpointMask != bit) return "TOPOLOGY_MISMATCH"
      } else if (checkpointKind != "LINKED_SESSION") {
        return "ROOT_KIND_MISMATCH"
      } else if (checkpointMask != targetMask) {
        return "TOPOLOGY_MISMATCH"
      }
      candidateMask = candidateMask or bit
      return "SUCCESS"
    }

    fun commitBarrier(): String {
      if (!barrierOpen) return "TOPOLOGY_MISMATCH"
      liveMask = targetMask
      candidateMask = 0
      return "SUCCESS"
    }

    fun failCandidate(player: Int) {
      require(player in 1..3)
      candidateMask = candidateMask and (1 shl player).inv()
    }

    fun prepareReplacement(
        player: Int,
        generation: Long,
        commitment: ByteArray,
        checkpointDigest: ByteArray,
    ): String {
      if (liveMask != targetMask || player !in 1..3 ||
          liveMask and (1 shl player) == 0 ||
          generation != targetGeneration ||
          !MessageDigest.isEqual(commitment, targetCommitment) ||
          !MessageDigest.isEqual(checkpointDigest, targetCheckpointDigest)) {
        return "TOPOLOGY_MISMATCH"
      }
      return "SUCCESS"
    }

    private fun exactRosterMask(value: String): Int = when (value) {
      "normal" -> 0x03
      "four" -> 0x0f
      else -> -1
    }
  }

  private data class ScenarioResult(
      val outcome: String,
      val severity: String,
      val transferAllowed: Boolean,
  )

  private inner class ManifestConsentScenarios {
    fun execute(row: Map<String, String>): ScenarioResult {
      val guest = row.int("guest")
      val serverMask = row.hexInt("server_roster")
      val clientMask = row.hexInt("client_roster")
      val serverGeneration = row.long("server_generation")
      val clientGeneration = row.long("client_generation")
      val clientPresent = row.getValue("client_primary_present").toBoolean()
      val clientSize = row.long("client_primary_size")
      val clientDigest = ByteArray(32) { row.int("client_primary_digest").toByte() }
      val serverEntries = normalEntries()
      val clientEntries = listOf(
          serverEntries[0],
          serverEntries[1].copy(
              primaryPresent = clientPresent,
              primaryLength = clientSize,
              primaryDigest = if (clientPresent) clientDigest else ByteArray(32),
              profile = row.getValue("client_profile"),
          ),
      )
      val proposal = row.optionalLong("proposal_id")?.let { id ->
        Proposal(
            id = id,
            action = OFFER,
            assetClass = ROM_CLASS,
            assetKind = row.int("proposal_asset"),
            owner = row.int("proposal_owner"),
            source = row.int("proposal_source"),
            target = row.int("proposal_target"),
            size = row.long("proposal_size"),
            digest = ByteArray(32) { row.int("proposal_digest").toByte() },
        )
      }
      val diff = row.optionalInt("diff_code")?.let { code ->
        ManifestDiff(
            code,
            row.int("diff_severity"),
            row.int("diff_player"),
            row.optionalLong("diff_proposal") ?: 0,
        )
      }
      val serverManifest = Manifest(
          1, 0, serverMask, serverGeneration, serverEntries,
          listOfNotNull(diff), listOfNotNull(proposal))
      val clientManifest = Manifest(
          1, guest, clientMask, clientGeneration, clientEntries, emptyList(), emptyList())
      val serverBytes = serverManifest.encode()
      val clientBytes = clientManifest.encode().also {
        if (row.getValue("client_commitment") == "corrupt") it[20] =
            (it[20].toInt() xor 1).toByte()
      }
      val context = PayloadContext(
          "normal", guest, serverMask, serverGeneration, serverManifest.rosterDigest())
      if (!row.getValue("protocol_compatible").toBoolean()) {
        return ScenarioResult("CAPABILITY_MISMATCH", "FATAL", false)
      }
      if (Manifest.decode(
              serverBytes, context.copy(wireSource = 0, wireTarget = guest)) != null ||
          Manifest.decode(
              clientBytes, context.copy(wireSource = guest, wireTarget = 0)) != null) {
        return ScenarioResult("MANIFEST_MISMATCH", row.getValue("severity"), false)
      }

      val serverGuest = serverEntries.single { it.player == guest }
      val clientGuest = clientEntries.single { it.player == guest }
      val expected = expectedDifference(serverGuest, clientGuest)
      if (diff == null || diff.code != expected.first || diff.severity != expected.second ||
          diff.player != guest) {
        return ScenarioResult("MANIFEST_MISMATCH", row.getValue("severity"), false)
      }
      if (expected.second == FATAL) {
        return ScenarioResult("MANIFEST_MISMATCH", "FATAL", false)
      }
      if (expected.second == INFORMATIONAL) {
        return ScenarioResult("SUCCESS", "INFORMATIONAL", false)
      }
      if (proposal == null) {
        return ScenarioResult("MANIFEST_MISMATCH", "WARNING_REQUIRES_APPROVAL", false)
      }

      val serverHash = sha256(serverBytes)
      val clientHash = sha256(clientBytes)
      val ledger = ConsentLedger(proposal, serverHash, clientHash)
      val serverDecision = row.getValue("server_decision")
      val clientDecision = row.getValue("client_decision")
      if (serverDecision == "none" && clientDecision == "none") {
        return ScenarioResult("CONSENT_REQUIRED", "WARNING_REQUIRES_APPROVAL", false)
      }
      if (serverDecision != "none") {
        val result = ledger.decide(consentFor(
            proposal, 0, serverHash, clientHash,
            if (serverDecision == "approve") APPROVE else REJECT))
        if (result == "CONSENT_REJECTED") {
          return ScenarioResult(result, "WARNING_REQUIRES_APPROVAL", false)
        }
      }
      if (clientDecision == "none") {
        return ScenarioResult("CONSENT_REQUIRED", "WARNING_REQUIRES_APPROVAL", false)
      }
      val clientConsent = consentFor(
          proposal, guest, serverHash, clientHash,
          if (clientDecision == "approve") APPROVE else REJECT)
      val consentResult = ledger.decide(
          if (row.getValue("consent_manifest") == "changed") {
            clientConsent.copy(clientManifest = ByteArray(32) { 3 })
          } else {
            clientConsent
          })
      if (consentResult != "SUCCESS") {
        return ScenarioResult(consentResult, "WARNING_REQUIRES_APPROVAL", false)
      }
      val declaration = BulkDeclaration(
          9, proposal.id, proposal.source, proposal.target, proposal.owner, proposal.assetKind,
          proposal.size, proposal.digest, 65_536)
      val allowed = ledger.claim(declaration)
      val replayAllowed = if (row.getValue("replay") == "true") {
        ledger.claim(declaration.copy(transactionId = 10))
      } else {
        false
      }
      if (row.getValue("replay") == "true") {
        return ScenarioResult(
            if (replayAllowed) "SUCCESS" else "CONSENT_REJECTED",
            "WARNING_REQUIRES_APPROVAL",
            false,
        )
      }
      return ScenarioResult(
          if (allowed && !replayAllowed) "SUCCESS" else "CONSENT_REJECTED",
          "WARNING_REQUIRES_APPROVAL",
          allowed && !replayAllowed,
      )
    }

    private fun expectedDifference(
        server: ManifestEntry,
        client: ManifestEntry,
    ): Pair<Int, Int> = when {
      server.profile != client.profile -> 3 to FATAL
      !client.primaryPresent -> PRIMARY_ROM_MISSING_DIFF to WARNING
      server.primaryLength != client.primaryLength ||
          !MessageDigest.isEqual(server.primaryDigest, client.primaryDigest) ->
        PRIMARY_ROM_DIFFERENT_DIFF to WARNING
      else -> MATCH_DIFF to INFORMATIONAL
    }
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

  private fun checkpointPayload(
      kind: Int,
      rosterMask: Int,
      owner: Int,
      frame: Long,
      proposalId: Long,
      state: ByteArray,
  ): ByteArray = ByteBuffer.allocate(20 + state.size)
      .put(kind.toByte()).put(rosterMask.toByte()).put(owner.toByte()).put(0)
      .putLong(frame).putInt(state.size).putInt(proposalId.toInt()).put(state).array()

  private fun linkedCheckpointState(): ByteArray {
    val romBytes = StateCodecTestSupport.rom()
    val file = Files.createTempFile("coffee-gb-v9-linked-contract-", ".gb").toFile()
    file.writeBytes(romBytes)
    val properties = EmulatorProperties(HardwareProfileRegistry.DMG)
    val configuration = Controller.createGameboyConfig(properties, Rom(romBytes))
    val bus = EventBusImpl()
    val controller = LinkedController(
        bus, properties, null, LinkMode.FOUR_PLAYER_ADAPTER, localPlayer = 0).also {
      it.timingTicker.disabled = true
    }
    try {
      bus.post(LoadRomEvent(file))
      controller.runFrame()
      for (player in 1..3) {
        bus.post(PeerLoadedGameEvent(
            romBytes,
            null,
            null,
            configuration.gameboyType,
            configuration.bootstrapMode,
            controller.currentFrame(),
            player = player,
        ))
        controller.runFrame()
      }
      assertEquals(4, controller.activeSessionCount())
      val captured = StateCodec.capture(controller)
      return StateCodec.encode(
          StateFile(
              captured.identities,
              captured.root,
              captured.diagnostics,
              formatVersion = 2,
          ),
          StateCompression.DEFLATE,
      )
    } finally {
      controller.close()
      bus.close()
      file.delete()
    }
  }

  private fun bulkBeginPayload(
      transactionId: Long,
      proposal: Proposal,
      chunkSize: Long,
  ): ByteArray = ByteBuffer.allocate(52)
      .putInt(transactionId.toInt()).putInt(proposal.id.toInt())
      .put(proposal.source.toByte()).put(proposal.target.toByte()).put(proposal.owner.toByte())
      .put(proposal.assetKind.toByte()).putInt(proposal.size.toInt()).put(proposal.digest)
      .putInt(chunkSize.toInt()).array()

  private fun bulkChunkPayload(
      transactionId: Long,
      offset: Long,
      data: ByteArray,
  ): ByteArray = ByteBuffer.allocate(8 + data.size)
      .putInt(transactionId.toInt()).putInt(offset.toInt()).put(data).array()

  private fun bulkEndPayload(
      transactionId: Long,
      digest: ByteArray,
  ): ByteArray = ByteBuffer.allocate(36).putInt(transactionId.toInt()).put(digest).array()

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
  private fun Map<String, String>.optionalLong(name: String) =
      getValue(name).takeUnless { it == "-" }?.toLong()
  private fun Map<String, String>.optionalInt(name: String) =
      getValue(name).takeUnless { it == "-" }?.toInt()
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
    const val GROUP_CHANNEL = 0xffff_ffffL
    const val PRIMARY_ROM_MISSING_DIFF = 5
    const val PRIMARY_ROM_DIFFERENT_DIFF = 6
    const val SLOT_ROM_MISSING_DIFF = 7
    const val SLOT_ROM_DIFFERENT_DIFF = 8
    const val MATCH_DIFF = 10
    const val BATTERY_TRANSFER_DIFF = 11
    const val CHECKPOINT_SYNC_DIFF = 12
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
        digest = ByteArray(32) { 2 },
    )

    fun consentFor(
        proposal: Proposal,
        actor: Int,
        serverManifest: ByteArray,
        clientManifest: ByteArray,
        decision: Int = APPROVE,
    ) = Consent(
        decisionId = proposal.id,
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

    fun sha256(bytes: ByteArray, offset: Int, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").apply {
          update(bytes, offset, length)
        }.digest()

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
