package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateDecodeReason
import eu.rekawek.coffeegb.controller.state.StateRootKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9ContractTest {

  @Test
  fun numericRegistriesAreCompleteStableAndMirroredByTheNormativeSpecification() {
    val messages = rows("/netplay-v9/messages.tsv")
    val fields = rows("/netplay-v9/fields.tsv")
    val capabilities = rows("/netplay-v9/capabilities.tsv")
    val limits = rows("/netplay-v9/limits.tsv")
    val timeouts = rows("/netplay-v9/timeouts.tsv")
    val errors = rows("/netplay-v9/errors.tsv")
    val transitions = rows("/netplay-v9/transitions.tsv")
    val spec = repositoryRoot().resolve("docs/netplay-protocol-v9.md").readUtf8()

    assertEquals((1..0x16).toList(), messages.map { it.hexInt("id") })
    assertEquals(messages.size, messages.map { it.getValue("name") }.toSet().size)
    assertEquals((1..12).toList(), capabilities.map { it.hexInt("id") })
    assertEquals(7, capabilities.count { it.getValue("required") == "true" })
    assertEquals((1..0x1f).toList(), errors.map { it.hexInt("code") })
    assertTrue(limits.size >= 20)
    assertEquals(22, timeouts.size)
    assertTrue(transitions.any { it.getValue("state") == "ACTIVE" })

    val timeoutStates = timeouts.map { it.getValue("state") }.toSet()
    val concreteNonterminalStates = transitions.flatMap {
      listOf(it.getValue("state"), it.getValue("next_state"))
    }.filter { it !in setOf("ANY_NONTERMINAL", "CLOSED") }.toSet()
    val directlyTimedStates = concreteNonterminalStates - "ACTIVE"
    assertTrue(timeoutStates.containsAll(directlyTimedStates))
    assertEquals(
        setOf("ACTIVE_IDLE", "BULK_PROGRESS", "TERMINAL_CLEANUP"),
        timeoutStates - directlyTimedStates,
    )

    val payloadSchemas = messages.map { it.getValue("payload_schema") }.toSet()
    val fieldSchemas = fields.map { it.getValue("schema") }.toSet()
    assertTrue(fieldSchemas.contains("frame-v1"))
    assertTrue(fieldSchemas.containsAll(payloadSchemas))
    assertEquals(fields.size, fields.map { it.getValue("schema") to it.getValue("field") }.toSet().size)
    fields.forEach {
      assertFalse(it.getValue("offset").isBlank())
      assertFalse(it.getValue("width").isBlank())
      assertFalse(it.getValue("rule").isBlank())
    }
    val frameFields = fields.filter { it.getValue("schema") == "frame-v1" }
    assertEquals(64, frameFields.sumOf { it.int("width") })

    messages.forEach { row ->
      assertTrue(row.int("min_decoded") <= row.int("max_decoded"))
      assertTrue(row.int("max_decoded") <= 33_554_452)
      assertTrue(row.int("max_encoded") <= 33_554_452)
      assertTrue(spec.contains("`${row.getValue("id").removePrefix("0x")}`") ||
          spec.contains(row.getValue("name")), "Spec lost ${row.getValue("name")}")
    }
    capabilities.forEach { assertTrue(spec.contains(it.getValue("name"))) }
    errors.forEach { assertTrue(spec.contains(it.getValue("name"))) }
    assertFalse(Regex("\\b(?:TBD|implementation-defined)\\b", RegexOption.IGNORE_CASE).containsMatchIn(spec))
    assertTrue(spec.contains("StateFile v2"))
    assertTrue(spec.contains("no downgrade", ignoreCase = true))
  }

  @Test
  fun transitionRegistryExecutesTheCompleteConsentBeforeSynchronizationHandshake() {
    val transitions = rows("/netplay-v9/transitions.tsv")
    assertEquals(transitions.size, transitions.map {
      listOf(it.getValue("role"), it.getValue("state"), it.getValue("direction"),
          it.getValue("message"), it.getValue("condition"))
    }.toSet().size)
    val messageNames = rows("/netplay-v9/messages.tsv").map { it.getValue("name") }.toSet()
    assertTrue(transitions.all { it.getValue("message") in messageNames })

    val server = ContractStateMachine("server", transitions, "SEND_SERVER_HELLO")
    listOf(
        Step("out", "HELLO", "always"),
        Step("in", "HELLO", "valid-required-capabilities"),
        Step("in", "AUTH", "complete-proof"),
        Step("out", "AUTH_RESULT", "accepted"),
        Step("out", "MANIFEST", "authenticated"),
        Step("in", "MANIFEST", "compatible"),
        Step("out", "CONSENT", "local-decision"),
        Step("in", "CONSENT", "matching-decision-and-required-classes"),
        Step("out", "START", "all-consented-transfers-complete-and-candidates-prepared"),
        Step("in", "READY", "matching-session-id-and-correlation"),
    ).forEach(server::advance)
    assertEquals("ACTIVE", server.state)

    val client = ContractStateMachine("client", transitions, "WAIT_SERVER_HELLO")
    listOf(
        Step("in", "HELLO", "valid-required-capabilities"),
        Step("out", "HELLO", "always"),
        Step("out", "AUTH", "complete-proof"),
        Step("in", "AUTH_RESULT", "accepted"),
        Step("in", "MANIFEST", "authenticated"),
        Step("out", "MANIFEST", "locally-compatible"),
        Step("in", "CONSENT", "local-decision-available"),
        Step("out", "CONSENT", "matching-decision"),
        Step("in", "START", "all-consented-transfers-complete-and-candidates-prepared"),
        Step("out", "READY", "prepared-session"),
    ).forEach(client::advance)
    assertEquals("ACTIVE", client.state)

    val privateMessages = setOf(
        "CHECKPOINT", "ROM_BEGIN", "ROM_CHUNK", "ROM_END", "BATTERY_BEGIN",
        "BATTERY_CHUNK", "BATTERY_END")
    transitions.filter { it.getValue("message") in privateMessages }.forEach { transition ->
      assertTrue(transition.getValue("state") in setOf("SYNCHRONIZING", "ACTIVE"))
      if (transition.getValue("message").startsWith("ROM_") ||
          transition.getValue("message").startsWith("BATTERY_")) {
        assertEquals("SYNCHRONIZING", transition.getValue("state"))
      }
    }
    val beforeConsent = ContractStateMachine("server", transitions, "WAIT_AUTH")
    assertFalse(beforeConsent.tryAdvance(Step("in", "CHECKPOINT", "consented-StateFile-v2-atomic")))
    assertEquals("WAIT_AUTH", beforeConsent.state)

    val busyServer = ContractStateMachine("server", transitions, "SEND_SERVER_HELLO")
    busyServer.advance(Step("out", "ERROR", "SERVER_BUSY-or-all-slots-full-terminal"))
    assertEquals("CLOSED", busyServer.state)
    val busyClient = ContractStateMachine("client", transitions, "WAIT_SERVER_HELLO")
    busyClient.advance(Step("in", "ERROR", "SERVER_BUSY-or-all-slots-full-terminal"))
    assertEquals("CLOSED", busyClient.state)
  }

  @Test
  fun committedWireCorpusExecutesWholeFragmentedAndCoalescedInputs() {
    val specs = messageSpecs()
    val vectors = rows("/netplay-v9/wire-vectors.tsv")
    assertTrue(vectors.size >= 35)
    vectors.forEach { row ->
      val bytes = hex(row.getValue("input_hex"))
      assertEquals(row.getValue("sha256"), sha256(bytes), row.getValue("id"))
      assertEquals("coffee-gb-synthetic-v9", row.getValue("provenance"))
      val feeds = listOf(listOf(bytes), bytes.map { byteArrayOf(it) })
      feeds.forEachIndexed { feedIndex, chunks ->
        val result = ReferenceDecoder(specs).decode(
            chunks,
            row.getValue("role"),
            row.getValue("initial_state"),
            row.int("next_sequence"),
            eof = true,
        )
        val context = "${row.getValue("id")}/${if (feedIndex == 0) "whole" else "byte"}"
        assertEquals(row.getValue("expected"), result.outcome, context)
        assertEquals(row.getValue("expected_state"), result.state, context)
        assertTrue(result.consumed <= row.int("decisive_read"), context)
        assertEquals(row.int("payload_allocations"), result.payloadAllocations, context)
        assertEquals("false", row.getValue("mutation_allowed"))
        assertFalse(result.mutated, "Contract parser must never mutate an emulator: $context")
      }
      assertEquals("ProtocolV9ContractTest.frame-v1", row.getValue("generator"))
    }

    val coalesced = vectors.single { it.getValue("id") == "coalesced_input_ping" }
    val coalescedResult = ReferenceDecoder(specs).decode(
        listOf(hex(coalesced.getValue("input_hex"))), "both", "ACTIVE", 1, true)
    assertEquals(2, coalescedResult.frames)
  }

  @Test
  fun fourBytePrefaceRejectsV8OrEarlierWithoutParsingItAsV9() {
    rows("/netplay-v9/preface-vectors.tsv").forEach { row ->
      val result = detectPreface(hex(row.getValue("input_hex")), row.getValue("eof").toBoolean())
      assertEquals(row.getValue("expected"), result.first, row.getValue("id"))
      assertEquals(row.getValue("diagnostic"), result.second, row.getValue("id"))
      assertTrue(hex(row.getValue("input_hex")).size <= row.int("decisive_read"))
      assertEquals("coffee-gb-synthetic-preface", row.getValue("provenance"))
    }
  }

  @Test
  fun resourceManifestHashesEveryCommittedV9ContractArtifact() {
    val root = repositoryRoot().resolve("controller/src/test/resources/netplay-v9")
    val manifest = rows("/netplay-v9/manifest.tsv").associateBy { it.getValue("path") }
    val actual = Files.list(root).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest.tsv" }
          .map { it.fileName.toString() }.toList().toSet()
    }
    assertEquals(actual, manifest.keys)
    manifest.forEach { (name, row) ->
      assertEquals(sha256(Files.readAllBytes(root.resolve(name))), row.getValue("sha256"), name)
      assertTrue(row.getValue("kind") in setOf("normative-registry", "conformance-vector", "documentation"))
      assertEquals("coffee-gb-phase-346", row.getValue("provenance"))
      assertFalse(row.getValue("generator").isBlank())
    }
  }

  @Test
  fun everyFixedHeaderBoundaryTruncatesWithoutPayloadAllocationOrStateTransition() {
    val row = rows("/netplay-v9/header-truncations.tsv").single()
    val base = rows("/netplay-v9/wire-vectors.tsv")
        .single { it.getValue("id") == row.getValue("base_fixture") }
    val bytes = hex(base.getValue("input_hex"))
    row.getValue("cut_offsets").split(',').map(String::toInt).forEach { cut ->
      val result = ReferenceDecoder(messageSpecs()).decode(
          listOf(bytes.copyOf(cut)), "both", "ACTIVE", 2, true)
      assertEquals("TRUNCATED", result.outcome, "cut=$cut")
      assertEquals(0, result.payloadAllocations, "cut=$cut")
      assertEquals(0, result.frames, "cut=$cut")
      assertFalse(result.mutated)
    }
  }

  @Test
  fun exactAndBoundaryPlusOneDeclarationsAreDecidedBeforePayloadRead() {
    val vectors = rows("/netplay-v9/wire-vectors.tsv").associateBy { it.getValue("id") }
    val decoder = ReferenceDecoder(messageSpecs())

    val exact = decoder.inspectHeader(hex(vectors.getValue("checkpoint_exact_declaration").getValue("input_hex")))
    assertEquals(HeaderDecision.ACCEPT_DECLARATION, exact)
    val over = decoder.inspectHeader(hex(vectors.getValue("checkpoint_plus_one_declaration").getValue("input_hex")))
    assertEquals(HeaderDecision.LIMIT_EXCEEDED, over)
    val unsigned = decoder.inspectHeader(hex(vectors.getValue("unsigned_length_overflow").getValue("input_hex")))
    assertEquals(HeaderDecision.LIMIT_EXCEEDED, unsigned)

    val smallExact = decoder.decode(
        listOf(hex(vectors.getValue("cancel_boundary").getValue("input_hex"))),
        "both", "ACTIVE", 3, true)
    assertEquals("SUCCESS", smallExact.outcome)
    val smallOver = decoder.decode(
        listOf(hex(vectors.getValue("cancel_boundary_plus_one").getValue("input_hex"))),
        "both", "ACTIVE", 3, true)
    assertEquals("LIMIT_EXCEEDED", smallOver.outcome)
    assertEquals(0, smallOver.payloadAllocations)

    fun hello(capabilityCount: Int): DecodeResult {
      val payload = ByteBuffer.allocate(38 + capabilityCount * 8)
          .put(1.toByte()).put(9.toByte()).put(9.toByte()).put(0.toByte())
          .put(ByteArray(32)).putShort(capabilityCount.toShort())
      repeat(capabilityCount) { index ->
        val id = index + 1
        payload.putShort(id.toShort()).putShort(1.toShort())
            .putInt(if (id <= 7) 1 else 0)
      }
      val bytes = payload.array()
      return decoder.decode(
          listOf(frame(0x0001, 0, 0, 0, 0, bytes, bytes.size, sha256Bytes(bytes))),
          "client", "WAIT_SERVER_HELLO", 0, true)
    }
    assertEquals("SUCCESS", hello(32).outcome)
    val capabilityPlusOne = hello(33)
    assertEquals("LIMIT_EXCEEDED", capabilityPlusOne.outcome)
    assertEquals(0, capabilityPlusOne.payloadAllocations)

    fun declaration(type: Int, flags: Int, size: Int): HeaderDecision {
      val payload = ByteArray(size)
      return decoder.inspectHeader(frame(
          type, flags, 1, 0, 0, payload, payload.size, sha256Bytes(payload)))
    }
    assertEquals(HeaderDecision.ACCEPT_DECLARATION, declaration(0x0004, 0, 712))
    assertEquals(HeaderDecision.LIMIT_EXCEEDED, declaration(0x0004, 0, 713))
    assertEquals(HeaderDecision.ACCEPT_DECLARATION, declaration(0x7777, OPTIONAL, 4_096))
    assertEquals(HeaderDecision.LIMIT_EXCEEDED, declaration(0x7777, OPTIONAL, 4_097))
  }

  @Test
  fun aggregateAndBulkDeclarationsUseCheckedExactBoundariesBeforeLargeRetention() {
    rows("/netplay-v9/aggregate-vectors.tsv").forEach { row ->
      val result = ReferenceBudget.reserve(
          row.long("current_frames"), row.long("current_encoded"), row.long("current_decoded"),
          row.long("add_frames"), row.long("add_encoded"), row.long("add_decoded"))
      assertEquals(row.getValue("expected"), result, row.getValue("id"))
      assertEquals("coffee-gb-synthetic-budget", row.getValue("provenance"))
    }

    val decoder = ReferenceDecoder(messageSpecs())
    fun begin(type: Int, total: Long, chunk: Long): DecodeResult {
      val payload = ByteBuffer.allocate(48).putInt(1).put(0.toByte()).put(ByteArray(3))
          .putInt(total.toInt()).put(ByteArray(32)).putInt(chunk.toInt()).array()
      return decoder.decode(
          listOf(frame(type, 0, 1, 0, 1, payload, payload.size, sha256Bytes(payload))),
          "both", "SYNCHRONIZING", 1, true)
    }
    assertEquals("SUCCESS", begin(0x000a, 67_108_864, 65_536).outcome)
    assertEquals("LIMIT_EXCEEDED", begin(0x000a, 67_108_865, 65_536).outcome)
    assertEquals("SUCCESS", begin(0x000d, 2_097_152, 65_536).outcome)
    assertEquals("LIMIT_EXCEEDED", begin(0x000d, 2_097_153, 65_536).outcome)
    assertEquals("LIMIT_EXCEEDED", begin(0x000a, 1, 0).outcome)

    fun chunk(size: Int): DecodeResult {
      val payload = ByteArray(8 + size)
      return decoder.decode(
          listOf(frame(0x000b, 0, 1, 0, 1, payload, payload.size, sha256Bytes(payload))),
          "both", "SYNCHRONIZING", 1, true)
    }
    assertEquals("SUCCESS", chunk(65_536).outcome)
    val oversizedChunk = chunk(65_537)
    assertEquals("LIMIT_EXCEEDED", oversizedChunk.outcome)
    assertEquals(0, oversizedChunk.payloadAllocations)
  }

  @Test
  fun checkpointCarriesOneDirectReleasedStateFileV2WithoutAnOuterCodec() {
    val stateBytes = assertNotNull(
        javaClass.getResourceAsStream("/state-file-v2/sgb2-session-deflate.cgbstate"))
        .use { it.readBytes() }
    assertEquals(
        "2d2178e6eba26a8debdacf84be144cccd1b42e50bf0dbce5c41612bcb16aa226",
        sha256(stateBytes))
    assertContentEquals("CGBS".toByteArray(StandardCharsets.US_ASCII), stateBytes.copyOfRange(0, 4))
    val inspection = StateCodec.inspect(stateBytes)
    assertEquals(2, inspection.formatVersion)
    assertEquals(StateRootKind.SESSION, inspection.rootKind)
    assertEquals("sgb2", inspection.identities.single().identity!!.profile.canonicalProfileId)
    val decoded = StateCodec.decode(stateBytes)
    assertContentEquals(stateBytes, StateCodec.encode(decoded, StateCompression.DEFLATE))

    fun checkpointFrame(state: ByteArray, kind: Int = 1, mask: Int = 1): ByteArray {
      val payload = ByteBuffer.allocate(20 + state.size)
          .put(kind.toByte()).put(mask.toByte()).put(0.toByte()).put(0.toByte())
          .putLong(123).putInt(state.size).putInt(0).put(state).array()
      return frame(0x0009, 0, 1, 0, -1, payload, payload.size, sha256Bytes(payload))
    }
    val decoder = ReferenceDecoder(messageSpecs())
    val result = decoder.decode(
        listOf(checkpointFrame(stateBytes)), "both", "SYNCHRONIZING", 1, true)
    assertEquals("SUCCESS", result.outcome)
    assertEquals(1, result.payloadAllocations)
    assertFalse(result.mutated)

    val wrongRoot = decoder.decode(
        listOf(checkpointFrame(stateBytes, kind = 0)), "both", "SYNCHRONIZING", 1, true)
    assertEquals("ROOT_KIND_MISMATCH", wrongRoot.outcome)
    assertFalse(wrongRoot.mutated)

    val corruptState = stateBytes.copyOf().also {
      it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    }
    val corrupt = decoder.decode(
        listOf(checkpointFrame(corruptState)), "both", "SYNCHRONIZING", 1, true)
    assertEquals("CHECKSUM_MISMATCH", corrupt.outcome)
    assertFalse(corrupt.mutated)
  }

  @Test
  fun rawDeflateRejectsBombDictionaryCorruptionTruncationAndTrailingBytes() {
    val specs = messageSpecs()
    val decoder = ReferenceDecoder(specs)
    val vectors = rows("/netplay-v9/wire-vectors.tsv").associateBy { it.getValue("id") }
    listOf("corrupt_deflate", "deflate_trailing").forEach { id ->
      val result = decoder.decode(
          listOf(hex(vectors.getValue(id).getValue("input_hex"))), "both", "ACTIVE", 1, true)
      assertEquals("DECOMPRESSION_FAILED", result.outcome, id)
    }

    val valid = decoder.decode(
        listOf(hex(vectors.getValue("valid_deflate_chunk").getValue("input_hex"))),
        "both", "SYNCHRONIZING", 1, true)
    assertEquals("SUCCESS", valid.outcome)

    val bombDecoded = ByteArray(65_544)
    val bombEncoded = deflateRaw(bombDecoded)
    val bomb = frame(0x000b, 0x0002, 1, 0, 1, bombEncoded, 9, sha256Bytes(ByteArray(9)))
    assertEquals("DECOMPRESSION_FAILED",
        decoder.decode(listOf(bomb), "both", "SYNCHRONIZING", 1, true).outcome)

    val dictionary = "fixture-dictionary".toByteArray(StandardCharsets.US_ASCII)
    val dictionaryPayload = ByteArray(4_096) { dictionary[it % dictionary.size] }
    val withDictionary = deflateRaw(dictionaryPayload, dictionary)
    val dictionaryFrame = frame(
        0x000b, 0x0002, 1, 0, 1, withDictionary, dictionaryPayload.size,
        sha256Bytes(dictionaryPayload))
    assertEquals("DECOMPRESSION_FAILED",
        decoder.decode(listOf(dictionaryFrame), "both", "SYNCHRONIZING", 1, true).outcome)

    val validBytes = hex(vectors.getValue("valid_deflate_chunk").getValue("input_hex"))
    assertEquals("TRUNCATED",
        decoder.decode(
            listOf(validBytes.copyOf(validBytes.size - 1)), "both", "SYNCHRONIZING", 1, true).outcome)
  }

  @Test
  fun illegalTransitionTimeoutCancellationAndCleanupAreSessionLocal() {
    rows("/netplay-v9/timeouts.tsv").forEach { row ->
      val deadline = row.long("milliseconds")
      val stage = ReferenceSession(
          row.getValue("state"), deadlineMillis = deadline,
          expiryOutcome = row.getValue("on_expiry"))
      stage.advanceTo(deadline - 1)
      assertTrue(stage.socketOpen, row.getValue("state"))
      stage.advanceTo(deadline)
      assertEquals("CLOSED", stage.state, row.getValue("state"))
      assertEquals(row.getValue("on_expiry"), stage.lastOutcome, row.getValue("state"))
    }

    val input = rows("/netplay-v9/wire-vectors.tsv")
        .single { it.getValue("id") == "valid_input" }
    val illegal = ReferenceDecoder(messageSpecs()).decode(
        listOf(hex(input.getValue("input_hex"))), "client", "WAIT_SERVER_HELLO", 1, true)
    assertEquals("UNEXPECTED_MESSAGE", illegal.outcome)
    assertFalse(illegal.mutated)

    val timed = ReferenceSession("WAIT_AUTH", deadlineMillis = 5_000)
    timed.advanceTo(4_999)
    assertEquals("WAIT_AUTH", timed.state)
    assertTrue(timed.socketOpen)
    timed.advanceTo(5_000)
    assertEquals("CLOSED", timed.state)
    assertEquals("TIMEOUT", timed.lastOutcome)
    assertFalse(timed.socketOpen)
    assertFalse(timed.taskOpen)
    assertEquals(0, timed.queuedFrames)

    val cancelled = ReferenceSession("ACTIVE", deadlineMillis = 30_000, queuedFrames = 4)
    val healthy = ReferenceSession("ACTIVE", deadlineMillis = 30_000, queuedFrames = 2)
    cancelled.cancel()
    assertEquals("CLOSED", cancelled.state)
    assertEquals("ACTIVE", healthy.state)
    assertEquals(2, healthy.queuedFrames)
    healthy.advanceTo(1)
    assertEquals("ACTIVE", healthy.state)

    val terminal = ReferenceSession("ACTIVE", deadlineMillis = 30_000, queuedFrames = 3)
    terminal.beginTerminal(10)
    assertEquals("TERMINAL_CLEANUP", terminal.state)
    assertFalse(terminal.outputOpen)
    assertTrue(terminal.socketOpen)
    assertEquals(0, terminal.queuedFrames)
    terminal.advanceTo(2_009)
    assertTrue(terminal.socketOpen)
    terminal.advanceTo(2_010)
    assertFalse(terminal.socketOpen)

    val cleanEof = ReferenceSession("ACTIVE", deadlineMillis = 30_000)
    cleanEof.eof(partialBytes = 0)
    assertEquals("UNEXPECTED_EOF", cleanEof.lastOutcome)
    val partialEof = ReferenceSession("ACTIVE", deadlineMillis = 30_000)
    partialEof.eof(partialBytes = 1)
    assertEquals("TRUNCATED", partialEof.lastOutcome)
  }

  @Test
  fun invitationVectorsFreezeCanonicalGrammarAndSyntheticTokenRules() {
    val vectors = rows("/netplay-v9/invitation-vectors.tsv")
    assertTrue(vectors.size >= 35)
    vectors.forEach { row ->
      val raw = when (row.getValue("input")) {
        "<512-ascii-bytes>" -> "a".repeat(512)
        "<513-ascii-bytes>" -> "a".repeat(513)
        else -> row.getValue("input").replace("\\n", "\n")
      }
      val result = InvitationReference.parse(raw)
      assertEquals(row.getValue("expected"), result.outcome, row.getValue("id"))
      assertEquals(row.getValue("error"), result.error ?: "-", row.getValue("id"))
      assertEquals(row.getValue("canonical"), result.canonical ?: "-", row.getValue("id"))
      assertEquals("coffee-gb-synthetic-invitation", row.getValue("provenance"))
    }

    val randomToken = InvitationReference.newToken(SecureRandom())
    assertEquals(22, randomToken.length)
    assertFalse(randomToken.contains('='))
    assertEquals(16, Base64.getUrlDecoder().decode(randomToken).size)
    val expected = ByteArray(16) { it.toByte() }
    assertTrue(InvitationReference.constantTimeEquals(expected, expected.copyOf()))
    val wrong = expected.copyOf().also { it[15] = 16 }
    assertFalse(InvitationReference.constantTimeEquals(expected, wrong))
  }

  @Test
  fun invitationProofExpiryOneUseSlotBindingAndRateLimitAreExactAndGeneric() {
    val row = rows("/netplay-v9/auth-vectors.tsv").single()
    val token = hex(row.getValue("token_hex"))
    val serverNonce = hex(row.getValue("server_nonce_hex"))
    val clientNonce = hex(row.getValue("client_nonce_hex"))
    val proof = authProof(token, serverNonce, clientNonce, row.int("slot"))
    assertContentEquals(hex(row.getValue("proof_hex")), proof)
    assertEquals("SUCCESS", row.getValue("expected"))
    assertEquals("coffee-gb-synthetic-auth", row.getValue("provenance"))

    val slotBound = InvitationLedger(token, 2, expiresAtMillis = 300_000)
    assertEquals("AUTH_FAILED", slotBound.authenticate(proof, 1, 1, serverNonce, clientNonce))
    assertEquals("SUCCESS", slotBound.authenticate(proof, 2, 1, serverNonce, clientNonce))
    assertEquals("AUTH_FAILED", slotBound.authenticate(proof, 2, 2, serverNonce, clientNonce))

    val expired = InvitationLedger(token, 2, expiresAtMillis = 300_000)
    assertEquals("SUCCESS", expired.authenticate(proof, 2, 299_999, serverNonce, clientNonce))
    val exactBoundary = InvitationLedger(token, 2, expiresAtMillis = 300_000)
    assertEquals(
        "AUTH_FAILED", exactBoundary.authenticate(proof, 2, 300_000, serverNonce, clientNonce))

    val limited = InvitationLedger(token, 2, expiresAtMillis = 300_000)
    val wrong = proof.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    repeat(8) {
      assertEquals("AUTH_FAILED", limited.authenticate(wrong, 2, 0, serverNonce, clientNonce))
    }
    assertEquals("AUTH_FAILED", limited.authenticate(proof, 2, 59_999, serverNonce, clientNonce))
    assertEquals("SUCCESS", limited.authenticate(proof, 2, 60_000, serverNonce, clientNonce))

    val stopped = InvitationLedger(token, 2, expiresAtMillis = 300_000)
    stopped.stop()
    assertEquals("AUTH_FAILED", stopped.authenticate(proof, 2, 1, serverNonce, clientNonce))
  }

  @Test
  fun invitationLifecycleVectorsFreezeLifetimeUseSlotStopAndRateLimitBoundaries() {
    val token = ByteArray(16) { it.toByte() }
    val serverNonce = ByteArray(32) { it.toByte() }
    val clientNonce = ByteArray(32) { (it + 32).toByte() }
    rows("/netplay-v9/invitation-lifecycle-vectors.tsv").forEach { row ->
      val ttl = row.long("ttl_seconds")
      if (ttl !in 60L..600L) {
        assertEquals("INV_EXPIRY", row.getValue("expected"), row.getValue("id"))
        return@forEach
      }
      val boundSlot = row.int("bound_slot")
      val candidateSlot = row.int("candidate_slot")
      val ledger = InvitationLedger(
          token, boundSlot, expiresAtMillis = Math.multiplyExact(ttl, 1_000L))
      val correct = authProof(token, serverNonce, clientNonce, candidateSlot)
      when (row.getValue("precondition")) {
        "fresh" -> Unit
        "used" -> assertEquals(
            "SUCCESS",
            ledger.authenticate(
                authProof(token, serverNonce, clientNonce, boundSlot), boundSlot, 0,
                serverNonce, clientNonce))
        "stopped" -> ledger.stop()
        "eight-failures" -> {
          val wrong = authProof(token, serverNonce, clientNonce, boundSlot).also {
            it[0] = (it[0].toInt() xor 1).toByte()
          }
          repeat(8) {
            assertEquals(
                "AUTH_FAILED",
                ledger.authenticate(wrong, boundSlot, 0, serverNonce, clientNonce))
          }
        }
        else -> throw AssertionError("Unknown lifecycle precondition ${row.getValue("precondition")}")
      }
      assertEquals(
          row.getValue("expected"),
          ledger.authenticate(
              correct, candidateSlot, row.long("now_millis"), serverNonce, clientNonce),
          row.getValue("id"))
      assertEquals("coffee-gb-synthetic-invitation-lifecycle", row.getValue("provenance"))
    }
  }

  @Test
  fun productionRemainsV8OnlyAndTheContractCannotReachLegacyOrNativeState() {
    val root = repositoryRoot()
    val productionRoots = listOf(root.resolve("core/src/main"), root.resolve("controller/src/main"), root.resolve("swing/src/main"))
    val production = productionRoots.flatMap { sourceRoot ->
      Files.walk(sourceRoot).use { paths ->
        paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java")) }
            .toList()
      }
    }.associateWith { it.readUtf8() }
    assertFalse(production.values.any { "CGB9" in it || "PROTOCOL_VERSION: Byte = 0x09" in it })

    val v8 = root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/network/Connection.kt").readUtf8()
    listOf(
        "private const val PROTOCOL_NAME = \"CoffeeGB NETPLAY\"",
        "internal const val PROTOCOL_VERSION: Byte = 0x08",
        "internal const val STATE_CAPABILITY_BYTES = 4",
        "private const val ROM: Byte = 0x01",
        "private const val PROTOCOL_ERROR: Byte = 0x0a",
        "internal const val ROM_HEADER_SIZE = 44",
    ).forEach { assertTrue(v8.contains(it), "v8 production contract drift: $it") }

    val contractRoot = root.resolve("controller/src/test/resources/netplay-v9")
    val contractResources = Files.list(contractRoot).use { paths ->
      paths.filter { Files.isRegularFile(it) }.sorted()
          .map { it.readUtf8() }.toList().joinToString("\n")
    }
    listOf("ObjectInputStream", "ObjectOutputStream", "LegacySnapshotImporter", "CGBN", "Memento<")
        .forEach { assertFalse(contractResources.contains(it)) }
    assertFalse(contractResources.contains("gameboy.datacenter.ne.jp"))
    assertFalse(contractResources.contains("java.util." + "Hex" + "Format"))
    assertFalse(Regex("(?:Enum\\.)?ordinal(?:\\(\\))?|\\.ordinal").containsMatchIn(contractResources))
  }

  private data class DecodeResult(
      val outcome: String,
      val state: String,
      val consumed: Int,
      val frames: Int,
      val payloadAllocations: Int,
      val mutated: Boolean = false,
  )

  private enum class HeaderDecision { ACCEPT_DECLARATION, LIMIT_EXCEEDED, MALFORMED }

  private data class MessageSpec(
      val id: Int,
      val name: String,
      val minDecoded: Long,
      val maxDecoded: Long,
      val maxEncoded: Long,
      val allowedFlags: Int,
      val requiredFlags: Int,
      val channels: String,
      val compression: String,
  )

  private data class Header(
      val type: Int,
      val flags: Int,
      val sequence: Long,
      val correlation: Long,
      val encoded: Long,
      val decoded: Long,
      val channel: Long,
      val digest: ByteArray,
  )

  private inner class ReferenceDecoder(private val specs: Map<Int, MessageSpec>) {
    fun inspectHeader(bytes: ByteArray): HeaderDecision {
      if (bytes.size < 64) return HeaderDecision.MALFORMED
      val header = header(bytes, 0) ?: return HeaderDecision.MALFORMED
      val spec = specs[header.type]
      if (spec == null) {
        return if (header.flags == OPTIONAL && header.encoded <= 4096 && header.encoded == header.decoded)
          HeaderDecision.ACCEPT_DECLARATION else HeaderDecision.LIMIT_EXCEEDED
      }
      if (header.flags and DEFLATE == 0 && header.encoded != header.decoded) return HeaderDecision.MALFORMED
      if (!validChannel(spec, header.channel)) return HeaderDecision.MALFORMED
      if (header.encoded > spec.maxEncoded || header.decoded > spec.maxDecoded ||
          header.decoded < spec.minDecoded) return HeaderDecision.LIMIT_EXCEEDED
      return HeaderDecision.ACCEPT_DECLARATION
    }

    fun decode(chunks: List<ByteArray>, role: String, initialState: String, initialSequence: Int, eof: Boolean): DecodeResult {
      val input = chunks.fold(ByteArrayOutputStream()) { output, chunk -> output.apply { write(chunk) } }.toByteArray()
      var offset = 0
      var state = initialState
      var expectedSequence = initialSequence.toLong()
      var frames = 0
      var allocations = 0
      while (offset < input.size || (input.isEmpty() && offset == 0)) {
        if (input.size - offset < 64) {
          return DecodeResult(if (eof) "TRUNCATED" else "NEED_MORE", "CLOSED", input.size, frames, allocations)
        }
        val h = header(input, offset)
            ?: return DecodeResult(headerError(input, offset), "CLOSED", minOf(input.size, offset + 64), frames, allocations)
        if (h.sequence != expectedSequence) return DecodeResult("SEQUENCE_ERROR", "CLOSED", offset + 64, frames, allocations)
        if (h.flags and RESPONSE != 0 && h.correlation == 0L) {
          return DecodeResult("CORRELATION_ERROR", "CLOSED", offset + 64, frames, allocations)
        }
        if (h.flags and RESPONSE == 0 && h.correlation != 0L) {
          return DecodeResult("CORRELATION_ERROR", "CLOSED", offset + 64, frames, allocations)
        }
        val spec = specs[h.type]
        if (spec == null) {
          if (h.flags != OPTIONAL || h.encoded != h.decoded || h.encoded > 4096 || h.channel != 0L || h.correlation != 0L) {
            return DecodeResult("UNKNOWN_REQUIRED_TYPE", "CLOSED", offset + 64, frames, allocations)
          }
        } else {
          if (h.flags and KNOWN_FLAGS.inv() != 0 || h.flags and spec.allowedFlags.inv() != 0 ||
              h.flags and spec.requiredFlags != spec.requiredFlags) {
            return DecodeResult("UNKNOWN_REQUIRED_FLAG", "CLOSED", offset + 64, frames, allocations)
          }
          if (!validChannel(spec, h.channel)) {
            return DecodeResult("MALFORMED_HEADER", "CLOSED", offset + 64, frames, allocations)
          }
          if (h.flags and DEFLATE == 0 && h.encoded != h.decoded) {
            return DecodeResult("MALFORMED_HEADER", "CLOSED", offset + 64, frames, allocations)
          }
          if (h.encoded > spec.maxEncoded || h.decoded > spec.maxDecoded || h.decoded < spec.minDecoded) {
            return DecodeResult("LIMIT_EXCEEDED", "CLOSED", offset + 64, frames, allocations)
          }
          if (h.flags and DEFLATE != 0 && spec.compression != "raw-deflate") {
            return DecodeResult("UNKNOWN_REQUIRED_FLAG", "CLOSED", offset + 64, frames, allocations)
          }
        }
        if (h.encoded > Int.MAX_VALUE || h.encoded > input.size.toLong() - offset - 64) {
          return DecodeResult(if (eof) "TRUNCATED" else "NEED_MORE", "CLOSED", input.size, frames, allocations)
        }
        val payload = input.copyOfRange(offset + 64, offset + 64 + h.encoded.toInt())
        allocations++
        val decoded = if (h.flags and DEFLATE != 0) {
          inflateExact(payload, h.decoded.toInt())
              ?: return DecodeResult("DECOMPRESSION_FAILED", "CLOSED", offset + 64 + payload.size, frames, allocations)
        } else payload
        if (!MessageDigest.isEqual(h.digest, sha256Bytes(decoded))) {
          return DecodeResult("CHECKSUM_MISMATCH", "CLOSED", offset + 64 + payload.size, frames, allocations)
        }
        val semantic = validatePayload(h.type, decoded)
        if (semantic != null) return DecodeResult(semantic, "CLOSED", offset + 64 + payload.size, frames, allocations)
        val next = transition(role, state, spec?.name, h.flags)
            ?: return DecodeResult("UNEXPECTED_MESSAGE", "CLOSED", offset + 64 + payload.size, frames, allocations)
        state = next
        offset += 64 + payload.size
        expectedSequence++
        if (spec != null) frames++
        if (h.flags and TERMINAL != 0 && offset != input.size) {
          return DecodeResult("TRAILING_DATA", "CLOSED", offset + 1, frames, allocations)
        }
        if (input.isEmpty()) break
      }
      return DecodeResult(if (frames == 0) "SKIPPED_OPTIONAL" else "SUCCESS", state, offset, frames, allocations)
    }

    private fun validatePayload(type: Int, payload: ByteArray): String? {
      if (type == 0x0001) {
        if (payload.size < 38 || payload[1].toInt() != 9 || payload[2].toInt() != 9 || payload[3].toInt() != 0) return "CAPABILITY_MISMATCH"
        val count = u16(payload, 36)
        if (count > 32 || payload.size != 38 + count * 8) return "CAPABILITY_MISMATCH"
        var prior = 0
        val required = mutableSetOf<Int>()
        repeat(count) { index ->
          val at = 38 + index * 8
          val id = u16(payload, at)
          val version = u16(payload, at + 2)
          val flags = u32(payload, at + 4)
          if (id <= prior || flags !in 0L..1L) return "CAPABILITY_MISMATCH"
          prior = id
          if (id in 1..12 && version != 1) return "CAPABILITY_MISMATCH"
          if (id in 1..7 && flags != 1L) return "CAPABILITY_MISMATCH"
          if (id > 12 && flags == 1L) return "UNKNOWN_REQUIRED_CAPABILITY"
          if (id in 1..7) required += id
        }
        if (required != (1..7).toSet()) return "CAPABILITY_MISMATCH"
      }
      if (type == 0x0009) {
        if (payload.size < 88) return "LIMIT_EXCEEDED"
        val kind = payload[0].toInt() and 0xff
        val mask = payload[1].toInt() and 0xff
        val owner = payload[2].toInt() and 0xff
        if (kind !in 0..2 || mask !in 1..15 || owner !in 0..3 || mask and (1 shl owner) == 0 ||
            payload[3].toInt() != 0 || u32(payload, 16) != 0L) return "STATEFILE_MALFORMED"
        if (kind in 0..1 && Integer.bitCount(mask) != 1 ||
            kind == 2 && Integer.bitCount(mask) !in 2..4) return "TOPOLOGY_MISMATCH"
        val declared = u32(payload, 12)
        if (declared != payload.size.toLong() - 20L) return "MALFORMED_HEADER"
        val state = payload.copyOfRange(20, payload.size)
        if (state.size < 68 || !state.copyOfRange(0, 4).contentEquals("CGBS".toByteArray(StandardCharsets.US_ASCII)) ||
            u16(state, 4) != 2) return "STATEFILE_VERSION"
        val inspection = try {
          StateCodec.inspect(state)
        } catch (e: StateDecodeException) {
          return when (e.reason) {
            StateDecodeReason.UNSUPPORTED_FORMAT_VERSION -> "STATEFILE_VERSION"
            StateDecodeReason.CORRUPT_CHECKSUM -> "CHECKSUM_MISMATCH"
            StateDecodeReason.LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
            StateDecodeReason.HARDWARE_PROFILE_MISMATCH -> "PROFILE_MISMATCH"
            else -> "STATEFILE_MALFORMED"
          }
        }
        val expectedRoot = when (kind) {
          0 -> StateRootKind.MACHINE
          1 -> StateRootKind.SESSION
          else -> StateRootKind.LINKED_SESSION
        }
        if (inspection.rootKind != expectedRoot) return "ROOT_KIND_MISMATCH"
        val identityMask = inspection.identities.filter { it.identity != null }
            .fold(0) { current, identity -> current or (1 shl identity.player) }
        if (identityMask != mask) return "TOPOLOGY_MISMATCH"
      }
      if (type == 0x000a || type == 0x000d) {
        val total = u32(payload, 8)
        val max = if (type == 0x000a) 67_108_864L else 2_097_152L
        val chunk = u32(payload, 44)
        if (total == 0L || total > max || chunk !in 1..65_536) return "LIMIT_EXCEEDED"
      }
      if (type == 0x0014 || type == 0x0015) {
        val reason = u16(payload, 0)
        val length = u16(payload, 2)
        if (reason !in 0..0x1f || type == 0x0014 && reason == 0 ||
            payload.size != 4 + length) return "MALFORMED_HEADER"
        if (!strictDiagnostic(payload.copyOfRange(4, payload.size), 256)) return "STRICT_UTF8"
      }
      if (type == 0x0016) {
        val code = u16(payload, 0)
        val length = u16(payload, 8)
        if (code !in 1..0x1f || u16(payload, 10) != 0 || payload.size != 12 + length) {
          return "MALFORMED_HEADER"
        }
        if (!strictDiagnostic(payload.copyOfRange(12, payload.size), 512)) return "STRICT_UTF8"
      }
      return null
    }

    private fun strictDiagnostic(bytes: ByteArray, maximum: Int): Boolean {
      if (bytes.size > maximum) return false
      val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
      } catch (_: CharacterCodingException) {
        return false
      }
      return text.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }
    }

    private fun transition(role: String, state: String, name: String?, flags: Int): String? {
      if (name == null) return if (state in setOf("SYNCHRONIZING", "ACTIVE")) state else null
      if (flags and TERMINAL != 0) return if (name in setOf("AUTH_RESULT", "CANCEL", "GOODBYE", "ERROR")) "CLOSED" else null
      if (state == "ACTIVE" && name in ACTIVE_MESSAGES) return "ACTIVE"
      if (state == "SYNCHRONIZING" && name in SYNC_MESSAGES) return "SYNCHRONIZING"
      return when (Triple(role, state, name)) {
        Triple("client", "WAIT_SERVER_HELLO", "HELLO") -> "SEND_CLIENT_HELLO"
        Triple("server", "SEND_SERVER_HELLO", "HELLO") -> "WAIT_CLIENT_HELLO"
        else -> null
      }
    }

    private fun validChannel(spec: MessageSpec, channel: Long): Boolean = when (spec.channels) {
      "control" -> channel == 0L
      "player" -> channel in 1L..4L
      "group-or-player" -> channel == 0xffff_ffffL || channel in 1L..4L
      else -> false
    }

    private fun header(bytes: ByteArray, offset: Int): Header? {
      if (bytes.size - offset < 64) return null
      if (!bytes.copyOfRange(offset, offset + 4).contentEquals(MAGIC)) return null
      if ((bytes[offset + 4].toInt() and 0xff) != 9 || (bytes[offset + 5].toInt() and 0xff) != 0) return null
      if (u16(bytes, offset + 6) != 64) return null
      return Header(
          u16(bytes, offset + 8),
          u16(bytes, offset + 10),
          u32(bytes, offset + 12),
          u32(bytes, offset + 16),
          u32(bytes, offset + 20),
          u32(bytes, offset + 24),
          u32(bytes, offset + 28),
          bytes.copyOfRange(offset + 32, offset + 64),
      )
    }

    private fun headerError(bytes: ByteArray, offset: Int): String {
      if (!bytes.copyOfRange(offset, offset + 4).contentEquals(MAGIC) ||
          (bytes[offset + 4].toInt() and 0xff) != 9 || (bytes[offset + 5].toInt() and 0xff) != 0) return "UNSUPPORTED_PROTOCOL"
      return "MALFORMED_HEADER"
    }

  }

  private data class ReferenceSession(
      var state: String,
      val deadlineMillis: Long,
      var queuedFrames: Int = 0,
      var socketOpen: Boolean = true,
      var taskOpen: Boolean = true,
      var outputOpen: Boolean = true,
      var lastOutcome: String? = null,
      var terminalDeadlineMillis: Long? = null,
      val expiryOutcome: String = "TIMEOUT",
  ) {
    fun advanceTo(nowMillis: Long) {
      val deadline = terminalDeadlineMillis ?: deadlineMillis
      if (state != "CLOSED" && nowMillis >= deadline) {
        lastOutcome = if (terminalDeadlineMillis == null) expiryOutcome else "CLOSED"
        close()
      }
    }
    fun cancel() { lastOutcome = "CANCELLED"; close() }
    fun beginTerminal(nowMillis: Long) {
      state = "TERMINAL_CLEANUP"
      queuedFrames = 0
      outputOpen = false
      taskOpen = false
      terminalDeadlineMillis = Math.addExact(nowMillis, 2_000)
    }
    fun eof(partialBytes: Int) {
      lastOutcome = if (partialBytes == 0) "UNEXPECTED_EOF" else "TRUNCATED"
      close()
    }
    private fun close() {
      state = "CLOSED"
      queuedFrames = 0
      socketOpen = false
      outputOpen = false
      taskOpen = false
    }
  }

  private data class Step(val direction: String, val message: String, val condition: String)

  private class ContractStateMachine(
      private val role: String,
      private val transitions: List<Map<String, String>>,
      var state: String,
  ) {
    fun advance(step: Step) {
      check(tryAdvance(step)) { "Illegal $role transition from $state using $step" }
    }

    fun tryAdvance(step: Step): Boolean {
      val matches = transitions.filter { row ->
        (row.getValue("role") == role || row.getValue("role") == "both") &&
            (row.getValue("state") == state || row.getValue("state") == "ANY_NONTERMINAL") &&
            (row.getValue("direction") == step.direction || row.getValue("direction") == "in-out") &&
            row.getValue("message") == step.message && row.getValue("condition") == step.condition
      }
      if (matches.size != 1) return false
      state = matches.single().getValue("next_state")
      return true
    }
  }

  private object ReferenceBudget {
    fun reserve(
        currentFrames: Long,
        currentEncoded: Long,
        currentDecoded: Long,
        addFrames: Long,
        addEncoded: Long,
        addDecoded: Long,
    ): String {
      if (listOf(currentFrames, currentEncoded, currentDecoded, addFrames, addEncoded, addDecoded)
          .any { it < 0 }) return "MALFORMED_HEADER"
      val totals = try {
        listOf(
            Math.addExact(currentFrames, addFrames),
            Math.addExact(currentEncoded, addEncoded),
            Math.addExact(currentDecoded, addDecoded))
      } catch (_: ArithmeticException) {
        return "LIMIT_EXCEEDED"
      }
      if (totals[0] > 256 || totals[1] > 33_816_576) return "QUEUE_OVERFLOW"
      if (totals[2] > 134_217_728) return "LIMIT_EXCEEDED"
      return "SUCCESS"
    }
  }

  private class InvitationLedger(
      token: ByteArray,
      private val slot: Int,
      private val expiresAtMillis: Long,
  ) {
    private val token = token.copyOf()
    private var used = false
    private var stopped = false
    private var failureWindowStart = 0L
    private var failures = 0

    fun authenticate(
        proof: ByteArray,
        candidateSlot: Int,
        nowMillis: Long,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
    ): String {
      if (nowMillis - failureWindowStart >= 60_000) {
        failureWindowStart = nowMillis
        failures = 0
      }
      val expected = authProof(token, serverNonce, clientNonce, candidateSlot)
      val proofMatches = MessageDigest.isEqual(expected, proof)
      val accepted = !stopped && !used && nowMillis < expiresAtMillis && candidateSlot == slot &&
          failures < 8 && proofMatches
      if (accepted) {
        used = true
        return "SUCCESS"
      }
      if (failures < 8) failures++
      return "AUTH_FAILED"
    }

    fun stop() {
      stopped = true
      token.fill(0)
    }
  }

  private object InvitationReference {
    private val tokenPattern = Regex("[A-Za-z0-9_-]{22}")
    private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val decimal = Regex("0|[1-9][0-9]*")

    data class Result(val outcome: String, val error: String? = null, val canonical: String? = null)

    fun parse(value: String): Result {
      if (value.toByteArray(StandardCharsets.UTF_8).size > 512) return fail("INV_TOO_LONG")
      if (value.any { it.code < 0x20 || it.code == 0x7f }) return fail("INV_CONTROL")
      if ('%' in value) return fail("INV_ENCODING")
      if ('#' in value) return fail("INV_FRAGMENT")
      if (!value.startsWith("coffeegb://")) return fail("INV_SCHEME")
      val rest = value.removePrefix("coffeegb://")
      val pathAt = rest.indexOf("/join?")
      if (pathAt < 0) return fail(if ('/' in rest) "INV_PATH" else "INV_AUTHORITY")
      val authority = rest.substring(0, pathAt)
      val query = rest.substring(pathAt + 6)
      if ('@' in authority) return fail("INV_AUTHORITY")
      val (host, portText) = parseAuthority(authority) ?: return fail(
          if (!authority.contains(':') || authority.substringAfterLast(':').isEmpty()) "INV_PORT" else "INV_AUTHORITY")
      if (!validHost(host)) return fail("INV_HOST")
      if (!decimal.matches(portText) || portText.startsWith('0') || portText.toLongOrNull() !in 1L..65_535L) return fail("INV_PORT")

      val pieces = query.split('&')
      val pairs = pieces.map { it.substringBefore('=') to it.substringAfter('=', "") }
      val expected = listOf("v", "mode", "slot", "exp", "token")
      if (pairs.map { it.first }.groupingBy { it }.eachCount().values.any { it > 1 }) return fail("INV_DUPLICATE")
      if (pairs.any { it.first !in expected }) return fail("INV_UNKNOWN")
      if (!pairs.map { it.first }.containsAll(expected)) return fail("INV_MISSING")
      if (pairs.map { it.first } != expected) return fail("INV_QUERY_ORDER")
      val values = pairs.toMap()
      if (values.getValue("v") != "9") return fail("INV_VERSION")
      val mode = values.getValue("mode")
      if (mode !in setOf("normal", "four")) return fail("INV_MODE")
      val slotText = values.getValue("slot")
      if (!decimal.matches(slotText) || slotText.startsWith('0') && slotText != "0") return fail("INV_SLOT")
      val slot = slotText.toIntOrNull() ?: return fail("INV_SLOT")
      if ((mode == "normal" && slot != 0) || (mode == "four" && slot !in 0..3)) return fail("INV_SLOT")
      val expiry = values.getValue("exp")
      if (!decimal.matches(expiry) || expiry.startsWith('0') || expiry.toLongOrNull() !in 1L..253_402_300_799L) return fail("INV_EXPIRY")
      val token = values.getValue("token")
      if (!tokenPattern.matches(token)) return fail("INV_TOKEN")
      val decoded = try { Base64.getUrlDecoder().decode(token) } catch (_: IllegalArgumentException) { return fail("INV_TOKEN") }
      if (decoded.size != 16 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != token) return fail("INV_TOKEN")
      return Result("SUCCESS", canonical = value)
    }

    fun newToken(random: SecureRandom): String = ByteArray(16).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

    private fun parseAuthority(authority: String): Pair<String, String>? {
      if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 0 || end + 1 >= authority.length || authority[end + 1] != ':') return null
        return authority.substring(1, end) to authority.substring(end + 2)
      }
      if (authority.count { it == ':' } != 1) return null
      return authority.substringBefore(':') to authority.substringAfter(':')
    }

    private fun validHost(host: String): Boolean {
      if (host.isEmpty() || host.length > 253 || host.any { it.code > 0x7f }) return false
      if (':' in host) return canonicalIpv6(host) == host
      val parts = host.split('.')
      if (parts.size == 4 && parts.all { it.all(Char::isDigit) }) {
        return parts.all { decimal.matches(it) && !(it.startsWith('0') && it != "0") && it.toIntOrNull() in 0..255 }
      }
      return !host.endsWith('.') && parts.all { it.length in 1..63 && dnsLabel.matches(it) }
    }

    private fun canonicalIpv6(input: String): String? {
      if (input.any { it in 'A'..'F' } || '%' in input || input.count { it == ':' } < 2) return null
      if (input.indexOf("::") != input.lastIndexOf("::")) return null
      val halves = input.split("::", limit = 2)
      fun groups(value: String): List<Int>? {
        if (value.isEmpty()) return emptyList()
        return value.split(':').map { part ->
          if (part.isEmpty() || part.length > 4 || !part.all { it.isDigit() || it in 'a'..'f' }) return null
          part.toInt(16)
        }
      }
      val leftGroups = groups(halves[0]) ?: return null
      val rightGroups = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
      val missing = 8 - leftGroups.size - rightGroups.size
      if ((halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) return null
      val values = leftGroups + List(if (halves.size == 2) missing else 0) { 0 } + rightGroups
      if (values.size != 8) return null
      var bestStart = -1
      var bestLength = 0
      var index = 0
      while (index < values.size) {
        if (values[index] != 0) { index++; continue }
        var end = index
        while (end < values.size && values[end] == 0) end++
        if (end - index >= 2 && end - index > bestLength) { bestStart = index; bestLength = end - index }
        index = end
      }
      if (bestStart < 0) return values.joinToString(":") { it.toString(16) }
      val leftText = values.take(bestStart).joinToString(":") { it.toString(16) }
      val rightText = values.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
      return when {
        leftText.isEmpty() && rightText.isEmpty() -> "::"
        leftText.isEmpty() -> "::$rightText"
        rightText.isEmpty() -> "$leftText::"
        else -> "$leftText::$rightText"
      }
    }

    private fun fail(error: String) = Result("FAIL", error)
  }

  private fun messageSpecs(): Map<Int, MessageSpec> = rows("/netplay-v9/messages.tsv").associate { row ->
    row.hexInt("id") to MessageSpec(
        row.hexInt("id"), row.getValue("name"), row.long("min_decoded"), row.long("max_decoded"),
        row.long("max_encoded"), row.hexInt("allowed_flags"), row.hexInt("required_flags"),
        row.getValue("channels"), row.getValue("compression"))
  }

  private fun detectPreface(bytes: ByteArray, eof: Boolean): Pair<String, String> {
    if (bytes.size < 4) {
      return if (eof) "TRUNCATED" to "truncated-v9-preface-expected-4-bytes"
      else "NEED_MORE" to "waiting-for-4-byte-v9-preface"
    }
    val prefix = bytes.copyOf(4)
    if (prefix.contentEquals(MAGIC)) return "V9" to "detected-v9-supported-v9"
    if (prefix.contentEquals("Coff".toByteArray(StandardCharsets.US_ASCII))) {
      return "UNSUPPORTED_PROTOCOL" to "detected-legacy-CoffeeGB-v8-or-earlier-expected-v9"
    }
    val detected = if (prefix.contentEquals("CGB8".toByteArray(StandardCharsets.US_ASCII))) {
      "CGB8"
    } else {
      "unknown-" + prefix.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    return "UNSUPPORTED_PROTOCOL" to "detected-$detected-expected-CGB9-v9"
  }

  private fun frame(type: Int, flags: Int, sequence: Int, correlation: Int, channel: Int,
                    encoded: ByteArray, decodedLength: Int, digest: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(64)
        .put("CGB9".toByteArray(StandardCharsets.US_ASCII)).put(9.toByte()).put(0.toByte()).putShort(64.toShort())
        .putShort(type.toShort()).putShort(flags.toShort()).putInt(sequence).putInt(correlation)
        .putInt(encoded.size).putInt(decodedLength).putInt(channel).put(digest).array()
    return header + encoded
  }

  private fun deflateRaw(bytes: ByteArray, dictionary: ByteArray? = null): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    if (dictionary != null) deflater.setDictionary(dictionary)
    deflater.setInput(bytes)
    deflater.finish()
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(256)
    while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk))
    deflater.end()
    return output.toByteArray()
  }

  private fun inflateExact(encoded: ByteArray, decodedLength: Int): ByteArray? {
    if (decodedLength < 0 || decodedLength > 65_544) return null
    val inflater = Inflater(true)
    return try {
      inflater.setInput(encoded)
      val output = ByteArray(decodedLength)
      var offset = 0
      while (!inflater.finished() && offset < output.size) {
        val count = inflater.inflate(output, offset, output.size - offset)
        if (count == 0) {
          if (inflater.needsDictionary() || inflater.needsInput()) break
          return null
        }
        offset += count
      }
      if (!inflater.finished() || offset != decodedLength || inflater.remaining != 0 || inflater.needsDictionary()) null else output
    } catch (_: DataFormatException) { null } finally { inflater.end() }
  }

  private fun rows(resource: String): List<Map<String, String>> {
    val stream = assertNotNull(javaClass.getResourceAsStream(resource), "Missing $resource")
    val lines = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
        .filter { it.isNotBlank() && !it.startsWith('#') }
    val header = lines.first().split('\t')
    return lines.drop(1).mapIndexed { index, line ->
      val values = line.split('\t')
      assertEquals(header.size, values.size, "$resource:${index + 2}")
      header.zip(values).toMap()
    }
  }

  private fun Map<String, String>.int(name: String) = getValue(name).toInt()
  private fun Map<String, String>.long(name: String) = getValue(name).toLong()
  private fun Map<String, String>.hexInt(name: String) = getValue(name).removePrefix("0x").toInt(16)

  private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
      val high = Character.digit(value[index * 2], 16)
      val low = Character.digit(value[index * 2 + 1], 16)
      require(high >= 0 && low >= 0)
      ((high shl 4) or low).toByte()
    }
  }

  private fun sha256(bytes: ByteArray) = sha256Bytes(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  private fun sha256Bytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

  private fun u16(bytes: ByteArray, offset: Int) =
      ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

  private fun u32(bytes: ByteArray, offset: Int): Long =
      ((bytes[offset].toLong() and 0xff) shl 24) or
          ((bytes[offset + 1].toLong() and 0xff) shl 16) or
          ((bytes[offset + 2].toLong() and 0xff) shl 8) or
          (bytes[offset + 3].toLong() and 0xff)

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

  private fun Path.readUtf8() = Files.readString(this, StandardCharsets.UTF_8)

  private companion object {
    fun authProof(
        token: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
        slot: Int,
    ): ByteArray {
      require(token.size == 16 && serverNonce.size == 32 && clientNonce.size == 32 && slot in 0..3)
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(token, "HmacSHA256"))
      mac.update("CoffeeGB-v9".toByteArray(StandardCharsets.US_ASCII))
      mac.update(serverNonce)
      mac.update(clientNonce)
      mac.update(slot.toByte())
      return mac.doFinal()
    }

    val MAGIC = "CGB9".toByteArray(StandardCharsets.US_ASCII)
    val ACTIVE_MESSAGES = setOf(
        "INPUT", "CHECKPOINT", "RESET", "STOP", "PING", "PONG", "CANCEL", "GOODBYE",
        "ERROR")
    val SYNC_MESSAGES = setOf(
        "CHECKPOINT", "ROM_BEGIN", "ROM_CHUNK", "ROM_END", "BATTERY_BEGIN",
        "BATTERY_CHUNK", "BATTERY_END", "CANCEL", "GOODBYE", "ERROR")
    const val OPTIONAL = 0x0001
    const val DEFLATE = 0x0002
    const val RESPONSE = 0x0004
    const val TERMINAL = 0x0008
    const val KNOWN_FLAGS = 0x000f
  }
}
