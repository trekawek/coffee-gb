package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9ProductionTest {

  @Test
  fun productionRegistriesExactlyMirrorEveryFrozenRow() {
    val messages = rows("/netplay-v9/messages.tsv")
    assertEquals(messages.size, V9MessageType.entries.size)
    messages.forEach { row ->
      val type = assertNotNull(V9MessageType.fromWireId(row.hexInt("id")))
      val spec = type.spec
      assertEquals(row.getValue("name"), spec.wireName)
      assertEquals(row.long("min_decoded"), spec.minimumDecodedBytes)
      assertEquals(row.long("max_decoded"), spec.maximumDecodedBytes)
      assertEquals(row.long("max_encoded"), spec.maximumEncodedBytes)
      assertEquals(row.hexInt("allowed_flags"), spec.allowedFlags)
      assertEquals(row.hexInt("required_flags"), spec.requiredFlags)
      assertEquals(
          when (row.getValue("channels")) {
            "control" -> V9ChannelKind.CONTROL
            "player" -> V9ChannelKind.PLAYER
            "group-or-player" -> V9ChannelKind.GROUP_OR_PLAYER
            else -> error("unknown channel registry value")
          },
          spec.channelKind,
      )
      assertEquals(
          if (row.getValue("compression") == "raw-deflate") V9Compression.RAW_DEFLATE
          else V9Compression.NONE,
          spec.compression,
      )
      assertEquals(row.getValue("payload_schema"), spec.payloadSchema)
    }

    val capabilities = rows("/netplay-v9/capabilities.tsv")
    assertEquals(capabilities.size, V9Capability.entries.size)
    capabilities.zip(V9Capability.entries).forEach { (row, capability) ->
      assertEquals(row.hexInt("id"), capability.wireId)
      assertEquals(row.getValue("name"), capability.wireName)
      assertEquals(row.int("version"), capability.schemaVersion)
      assertEquals(row.boolean("required"), capability.required)
      assertEquals(row.int("phase_owner"), capability.phaseOwner)
      assertEquals(row.getValue("meaning"), capability.meaning)
    }

    val errors = rows("/netplay-v9/errors.tsv")
    assertEquals(errors.size, V9ErrorCode.entries.size)
    errors.zip(V9ErrorCode.entries).forEach { (row, error) ->
      assertEquals(row.hexInt("code"), error.wireId)
      assertEquals(row.getValue("name"), error.wireName)
      assertEquals(row.boolean("peer_visible"), error.peerVisible)
      assertEquals(row.boolean("retry_same_connection"), error.retrySameConnection)
      assertEquals(row.boolean("mutation_allowed"), error.mutationAllowed)
    }

    val limits = rows("/netplay-v9/limits.tsv")
    assertEquals(limits.size, V9Limit.entries.size)
    limits.zip(V9Limit.entries).forEach { (row, limit) ->
      assertEquals(row.getValue("name"), limit.name)
      assertEquals(row.long("value"), limit.value)
      assertEquals(row.getValue("unit"), limit.unit)
      assertEquals(row.getValue("validation_boundary"), limit.validationBoundary)
      assertEquals(row.getValue("rationale"), limit.rationale)
    }

    val timeouts = rows("/netplay-v9/timeouts.tsv")
    assertEquals(timeouts.size, V9Timeout.entries.size)
    timeouts.zip(V9Timeout.entries).forEach { (row, timeout) ->
      assertEquals(row.getValue("state"), timeout.name)
      assertEquals(row.long("milliseconds"), timeout.milliseconds)
      assertEquals("monotonic", row.getValue("clock"))
      val expected = row.getValue("on_expiry")
      if (expected == "CLOSED") assertNull(timeout.expiryError)
      else assertEquals(expected, timeout.expiryError?.wireName)
    }
    val transitionStates =
        rows("/netplay-v9/transitions.tsv")
            .flatMap { listOf(it.getValue("state"), it.getValue("next_state")) }
            .filter { it !in setOf("ANY_NONTERMINAL") }
            .toSet()
    val concreteStates =
        transitionStates +
            rows("/netplay-v9/timeouts.tsv").map {
              it.getValue("state").let { state ->
                if (state == "ACTIVE_IDLE") "ACTIVE" else state
              }
            } +
            "CLOSED"
    assertEquals(
        concreteStates,
        V9LifecycleState.entries.map { it.name }.toSet(),
    )
  }

  @Test
  fun productionHeaderPreflightMatchesFrozenPrecedenceAndAllocationBoundary() {
    rows("/netplay-v9/header-precedence-vectors.tsv").forEach { row ->
      val budget =
          V9QueueBudget(
              initialFrames = row.long("current_frames"),
              initialWireBytes = row.long("current_wire"),
              initialDecodedBytes = row.long("current_decoded"),
          )
      val decoder =
          V9IncrementalDecoder(
              initialSequence = 1,
              budget = budget,
              policy =
                  V9DecoderPolicy(
                      allowedMessages = V9MessageType.entries.toSet(),
                      negotiatedCapabilities = V9Capability.entries.toSet(),
                  ),
          )
      val header = headerFrom(row)
      val decisive = row.int("decisive_read")
      val result = decoder.feed(header.copyOf(decisive))
      if (row.getValue("expected") == "ACCEPT") {
        assertNull(result.failure, row.getValue("id"))
        assertEquals(1, result.payloadReservations, row.getValue("id"))
        assertEquals(1, result.payloadAllocations, row.getValue("id"))
      } else {
        assertEquals(row.getValue("expected"), result.failure?.reason?.wireName, row.getValue("id"))
        assertEquals(decisive, result.failure?.decisiveBytes, row.getValue("id"))
        assertEquals(0, result.payloadReservations, row.getValue("id"))
        assertEquals(0, result.payloadAllocations, row.getValue("id"))
      }
      result.frames.forEach(V9Frame::close)
    }
  }

  @Test
  fun productionDecoderHandlesBytewiseIrregularCoalescedAndExactEofBoundaries() {
    val vectors = rows("/netplay-v9/wire-vectors.tsv").associateBy { it.getValue("id") }
    val input = hex(vectors.getValue("valid_input").getValue("input_hex"))
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.INPUT, V9MessageType.PING),
            negotiatedCapabilities = V9Capability.entries.toSet(),
        )
    val bytewise = V9IncrementalDecoder(1, policy = policy)
    input.forEachIndexed { index, byte ->
      val result = bytewise.feed(byteArrayOf(byte))
      if (index < input.lastIndex) {
        assertTrue(result.needsMore, "index=$index")
        assertEquals(0, result.frames.size, "index=$index")
        assertTrue(result.retainedBytes in 1..79, "index=$index")
      } else {
        assertEquals(1, result.frames.size)
        assertEquals(V9MessageType.INPUT, result.frames.single().header.type)
        result.frames.single().close()
      }
    }
    assertEquals(V9QueueSnapshot(0, 0, 0), bytewise.queueSnapshot())

    val irregular = V9IncrementalDecoder(1, policy = policy)
    var offset = 0
    listOf(3, 1, 17, 2, 31, 5, 7, 14).forEach { size ->
      val result = irregular.feed(input, offset, size)
      offset += size
      result.frames.forEach(V9Frame::close)
    }
    assertEquals(input.size, offset)
    assertNull(irregular.snapshot().failure)
    assertEquals(0, irregular.snapshot().retainedBytes)

    val coalesced = hex(vectors.getValue("coalesced_input_ping").getValue("input_hex"))
    val together = V9IncrementalDecoder(1, policy = policy).feed(coalesced)
    assertEquals(listOf(V9MessageType.INPUT, V9MessageType.PING), together.frames.map { it.header.type })
    together.frames.forEach(V9Frame::close)

    val ping = hex(vectors.getValue("valid_ping").getValue("input_hex"))
    rows("/netplay-v9/header-truncations.tsv").single()
        .getValue("cut_offsets").split(',').map(String::toInt).forEach { cut ->
          val decoder = V9IncrementalDecoder(2, policy = policy)
          decoder.feed(ping.copyOf(cut))
          val eof = decoder.finish()
          assertEquals(V9ErrorCode.TRUNCATED, eof.failure?.reason, "cut=$cut")
          assertEquals(0, eof.frames.size, "cut=$cut")
          assertEquals(0, eof.payloadAllocations, "cut=$cut")
      }

    val complete = V9IncrementalDecoder(2, policy = policy)
    val completeFrame = complete.feed(ping)
    completeFrame.frames.single().close()
    assertEquals(V9ErrorCode.UNEXPECTED_EOF, complete.finish().failure?.reason)
  }

  @Test
  fun compressedFramesAreExactBoundedAndCannotCarryBombsOrTrailingStreams() {
    val capabilities = V9Capability.entries.toSet()
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.ROM_CHUNK),
            negotiatedCapabilities = capabilities,
        )
    val payload = ByteBuffer.allocate(9).putInt(1).putInt(0).put(0x5a).array()
    val compressed =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.ROM_CHUNK,
                V9Flag.DEFLATE.wireMask,
                0,
                0,
                1,
                payload,
            ),
            policy,
        )
    val decoded = V9IncrementalDecoder(policy = policy).feed(compressed)
    assertContentEquals(payload, decoded.frames.single().payload())
    decoded.frames.single().close()

    val corrupt =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "corrupt_deflate" }
                .getValue("input_hex"),
        )
    assertEquals(
        V9ErrorCode.DECOMPRESSION_FAILED,
        V9IncrementalDecoder(1, policy = policy).feed(corrupt).failure?.reason,
    )

    val trailing = compressed.copyOf(compressed.size + 1).also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(20, compressed.size - 64 + 1)
      it[it.lastIndex] = 0
    }
    assertEquals(
        V9ErrorCode.DECOMPRESSION_FAILED,
        V9IncrementalDecoder(policy = policy).feed(trailing).failure?.reason,
    )

    val maximum = ByteArray(V9MessageType.ROM_CHUNK.spec.maximumDecodedBytes.toInt()).also {
      ByteBuffer.wrap(it).putInt(1).putInt(0)
    }
    val maximumCompressed =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.ROM_CHUNK,
                V9Flag.DEFLATE.wireMask,
                0,
                0,
                1,
                maximum,
            ),
            policy,
        )
    val bomb = maximumCompressed.copyOf().also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(24, 9)
    }
    assertEquals(
        V9ErrorCode.DECOMPRESSION_FAILED,
        V9IncrementalDecoder(policy = policy).feed(bomb).failure?.reason,
    )
    val over = ByteArray(maximum.size + 1)
    val exception =
        assertFailsWith<V9ProtocolException> {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.ROM_CHUNK,
                  V9Flag.DEFLATE.wireMask,
                  0,
                  0,
                  1,
                  over,
              ),
              policy,
          )
        }
    assertEquals(V9ErrorCode.LIMIT_EXCEEDED, exception.reason)

    val missingCodec =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.ROM_CHUNK),
            negotiatedCapabilities =
                capabilities - V9Capability.RAW_DEFLATE_V1,
        )
    val gate = V9IncrementalDecoder(policy = missingCodec).feed(compressed.copyOf(32))
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, gate.failure?.reason)
    assertEquals(0, gate.payloadAllocations)
  }

  @Test
  fun aggregateQueueArithmeticMatchesEveryFrozenBoundary() {
    rows("/netplay-v9/aggregate-vectors.tsv").forEach { row ->
      val result =
          V9QueueBudget.evaluateAdmission(
              V9QueueSnapshot(
                  row.long("current_frames"),
                  row.long("current_wire"),
                  row.long("current_decoded"),
              ),
              V9QueueSnapshot(
                  row.long("add_frames"),
                  row.long("add_wire"),
                  row.long("add_decoded"),
              ),
          )
      val actual = result?.wireName ?: "SUCCESS"
      assertEquals(row.getValue("expected"), actual, row.getValue("id"))
    }
  }

  @Test
  fun capabilityExecutionGatesExactlyMirrorEveryFrozenVector() {
    rows("/netplay-v9/capability-gate-vectors.tsv").forEach { row ->
      val capabilities =
          row.getValue("negotiated").split(',').map {
            assertNotNull(V9Capability.fromWireId(it.toInt()), row.getValue("id"))
          }.toSet()
      val policy =
          V9DecoderPolicy(
              allowedMessages = V9MessageType.entries.toSet(),
              negotiatedCapabilities = capabilities,
              linkMode =
                  when (row.getValue("mode")) {
                    "normal" -> V9LinkMode.NORMAL
                    "four" -> V9LinkMode.FOUR_PLAYER
                    else -> error("unknown mode")
                  },
          )
      val message = assertNotNull(V9MessageType.fromWireName(row.getValue("message")))
      val actual = capabilityFailure(message, row.hexInt("flags"), policy)?.wireName ?: "SUCCESS"
      assertEquals(row.getValue("expected"), actual, row.getValue("id"))
    }
  }

  @Test
  fun hostileFrozenHeadersAndPrefacesFailWithTheProductionTypedReason() {
    rows("/netplay-v9/preface-vectors.tsv").forEach { row ->
      val actual =
          V9Preface.detect(
              hex(row.getValue("input_hex")),
              row.boolean("eof"),
          )
      val expected = when (row.getValue("expected")) {
        "V9" -> V9PrefaceKind.V9
        "NEED_MORE" -> V9PrefaceKind.NEED_MORE
        "TRUNCATED" -> V9PrefaceKind.TRUNCATED
        else -> if (row.getValue("id") == "v8_or_earlier") {
          V9PrefaceKind.LEGACY_V8_OR_EARLIER
        } else {
          V9PrefaceKind.UNSUPPORTED
        }
      }
      assertEquals(expected, actual, row.getValue("id"))
    }

    val selected =
        setOf(
            "bad_magic",
            "bad_major",
            "bad_minor",
            "bad_header_length",
            "unknown_required",
            "reserved_flag",
            "input_zero",
            "input_plus_one",
            "raw_length_mismatch",
            "sequence_gap",
            "invalid_input_channel",
            "checksum_mismatch",
            "terminal_trailing",
        )
    rows("/netplay-v9/wire-vectors.tsv").filter { it.getValue("id") in selected }.forEach { row ->
      val policy =
          V9DecoderPolicy(
              allowedMessages = V9MessageType.entries.toSet(),
              negotiatedCapabilities = V9Capability.entries.toSet(),
          )
      val result =
          V9IncrementalDecoder(row.long("next_sequence"), policy = policy)
              .feed(hex(row.getValue("input_hex")))
      assertEquals(row.getValue("expected"), result.failure?.reason?.wireName, row.getValue("id"))
      assertEquals(row.int("decisive_read"), result.failure?.decisiveBytes, row.getValue("id"))
      result.frames.forEach(V9Frame::close)
    }

    val checkpointHeader =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "checkpoint_exact_declaration" }
                .getValue("input_hex"),
        )
    val unavailable = V9IncrementalDecoder(1).feed(checkpointHeader)
    assertEquals(V9ErrorCode.UNEXPECTED_MESSAGE, unavailable.failure?.reason)
    assertEquals(0, unavailable.payloadAllocations)
    assertEquals(0, unavailable.payloadReservations)

    val unknownOptionalBytes =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "unknown_optional" }
                .getValue("input_hex"),
        )
    val skipped =
        V9IncrementalDecoder(
                3,
                policy =
                    V9DecoderPolicy(
                        allowedMessages = V9MessageType.entries.toSet(),
                        allowUnknownOptional = true,
                    ),
            )
            .feed(unknownOptionalBytes)
    assertNull(skipped.failure)
    assertEquals(0, skipped.frames.size)
    assertEquals(1, skipped.payloadAllocations)
    assertEquals(0, skipped.retainedBytes)

    val declaredTruncated =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "declared_actual_truncated" }
                .getValue("input_hex"),
        )
    val payloadDecoder =
        V9IncrementalDecoder(
            policy =
                V9DecoderPolicy(
                    allowedMessages = setOf(V9MessageType.PING),
                    negotiatedCapabilities = V9Capability.entries.toSet(),
                ),
        )
    val partial = payloadDecoder.feed(declaredTruncated)
    assertTrue(partial.needsMore)
    assertEquals(1, partial.payloadAllocations)
    assertEquals(V9ErrorCode.TRUNCATED, payloadDecoder.finish().failure?.reason)
    assertEquals(V9QueueSnapshot(0, 0, 0), payloadDecoder.queueSnapshot())

    val refused = V9IncrementalDecoder(3).feed(unknownOptionalBytes.copyOf(32))
    assertEquals(V9ErrorCode.UNEXPECTED_MESSAGE, refused.failure?.reason)
    assertEquals(0, refused.payloadAllocations)
  }

  @Test
  fun smallTypedErrorDiscardsRemoteTextAndWriterQueueIsBounded() {
    val payload =
        V9ErrorPayloadCodec.encode(
            V9ErrorCode.SERVER_BUSY,
            diagnostic = "candidate unavailable",
        )
    val frame =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.ERROR,
                V9Flag.TERMINAL.wireMask,
                0,
                0,
                0,
                payload,
            ),
        )
    val decoded = V9IncrementalDecoder().feed(frame)
    val error = V9ErrorPayloadCodec.decode(decoded.frames.single().payload())
    assertEquals(V9ErrorCode.SERVER_BUSY, error.error)
    assertFalse(error.toString().contains("candidate unavailable"))
    decoded.frames.single().close()

    val queue = V9WriterQueue()
    repeat(V9Limit.QUEUED_FRAMES.value.toInt()) {
      assertTrue(queue.offer(byteArrayOf(it.toByte())) {})
    }
    assertFalse(queue.offer(byteArrayOf(0)) {})
    assertEquals(V9Limit.QUEUED_FRAMES.value, queue.snapshot().frames)
    queue.close()
    assertEquals(V9QueueSnapshot(0, 0, 0), queue.snapshot())
  }

  @Test
  fun helloNegotiationRejectsDowngradeMissingDuplicateAndUnknownRequiredCapabilities() {
    val nonce = ByteArray(32) { it.toByte() }
    val server = V9HelloCodec.create(V9Role.SERVER, nonce)
    val client = V9HelloCodec.create(V9Role.CLIENT, nonce.reversedArray())
    val serverBytes = V9HelloCodec.encode(server)
    assertEquals(94, serverBytes.size)
    assertEquals(V9Role.SERVER, V9HelloCodec.decode(serverBytes).role)
    assertEquals(
        V9Capability.requiredCapabilities,
        V9HelloCodec.negotiate(client, server, V9Role.SERVER, V9LinkMode.NORMAL).capabilities,
    )

    val missing = serverBytes.copyOf(serverBytes.size - 8).also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(36, 6)
    }
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        assertFailsWith<V9ProtocolException> { V9HelloCodec.decode(missing) }.reason,
    )
    val duplicate = serverBytes.copyOf().also {
      System.arraycopy(it, 38 + 5 * 8, it, 38 + 6 * 8, 8)
    }
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        assertFailsWith<V9ProtocolException> { V9HelloCodec.decode(duplicate) }.reason,
    )
    val wrongVersion = serverBytes.copyOf().also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(40, 2)
    }
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        assertFailsWith<V9ProtocolException> { V9HelloCodec.decode(wrongVersion) }.reason,
    )
    val downgrade = serverBytes.copyOf().also { it[1] = 8 }
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        assertFailsWith<V9ProtocolException> { V9HelloCodec.decode(downgrade) }.reason,
    )

    val unknownRequired = helloWithUnknown(serverBytes, required = true)
    assertEquals(
        V9ErrorCode.UNKNOWN_REQUIRED_CAPABILITY,
        assertFailsWith<V9ProtocolException> { V9HelloCodec.decode(unknownRequired) }.reason,
    )
    val unknownOptional = V9HelloCodec.decode(helloWithUnknown(serverBytes, required = false))
    assertEquals(8, unknownOptional.capabilities.size)
    assertNull(unknownOptional.capabilities.last().capability)

    val fourFailure =
        assertFailsWith<V9ProtocolException> {
          V9HelloCodec.negotiate(client, server, V9Role.SERVER, V9LinkMode.FOUR_PLAYER)
        }
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, fourFailure.reason)
    val serverFour =
        V9HelloCodec.create(
            V9Role.SERVER,
            nonce,
            setOf(V9Capability.FOUR_PLAYER_V1),
        )
    val clientFour =
        V9HelloCodec.create(
            V9Role.CLIENT,
            nonce.reversedArray(),
            setOf(V9Capability.FOUR_PLAYER_V1),
        )
    assertTrue(
        V9Capability.FOUR_PLAYER_V1 in
            V9HelloCodec.negotiate(
                clientFour,
                serverFour,
                V9Role.SERVER,
                V9LinkMode.FOUR_PLAYER,
            ).capabilities,
    )
  }

  @Test
  fun serverWritesHelloBeforeReadingAndReturnsOnlyASmallTypedCapabilityRejection() {
    val (serverChannel, peerChannel) = MemoryChannel.pair(maximumWrite = 3)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            nonce = ByteArray(32) { 3 },
        )
    server.start()

    val peerDecoder =
        V9IncrementalDecoder(
            policy =
                V9DecoderPolicy(
                    allowedMessages = setOf(V9MessageType.HELLO, V9MessageType.ERROR),
                    negotiatedCapabilities = V9Capability.entries.toSet(),
                ),
        )
    val hello = peerDecoder.feed(readFrame(peerChannel))
    assertEquals(V9MessageType.HELLO, hello.frames.single().header.type)
    hello.frames.single().close()

    val malformed =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "hello_missing_required_cap" }
                .getValue("input_hex"),
        )
    writeAll(peerChannel, malformed)
    val rejection = peerDecoder.feed(readFrame(peerChannel))
    assertEquals(V9MessageType.ERROR, rejection.frames.single().header.type)
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        V9ErrorPayloadCodec.decode(rejection.frames.single().payload()).error,
    )
    assertTrue(rejection.frames.single().payload().size <= V9MessageType.ERROR.spec.maximumDecodedBytes)
    rejection.frames.single().close()

    val terminal = server.awaitPairingBoundary(3, TimeUnit.SECONDS)
    assertEquals(V9LifecycleState.CLOSED, terminal.state)
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, terminal.failure?.reason)
    server.close()
  }

  @Test
  fun sequenceAndResponseLedgerNeverUsesOrdinalsOrWraps() {
    rows("/netplay-v9/response-vectors.tsv").forEach { row ->
      val exhausted = row.getValue("expected_sequence") == "4294967295"
      val expected =
          if (exhausted) ProtocolV9.EXHAUSTED_SEQUENCE
          else row.long("expected_sequence")
      val alreadyResponded = row.boolean("already_responded")
      val ledger =
          V9ResponseLedger(
              if (alreadyResponded) Math.subtractExact(expected, 1) else expected,
          )
      if (row.getValue("outstanding_sequence") != "-") {
        val requestSequence = row.long("outstanding_sequence")
        val requestType =
            assertNotNull(V9MessageType.fromWireName(row.getValue("outstanding_message")))
        ledger.recordPeerRequest(requestSequence, requestType)
        if (alreadyResponded) {
          assertNull(
              ledger.accept(
                  Math.subtractExact(expected, 1),
                  V9MessageType.AUTH_RESULT,
                  V9Flag.RESPONSE.wireMask,
                  requestSequence,
              ),
          )
          assertEquals(0, ledger.outstandingRequests)
        }
      }
      val result =
          ledger.accept(
              row.long("incoming_sequence"),
              assertNotNull(V9MessageType.fromWireName(row.getValue("message"))),
              row.hexInt("flags"),
              row.long("correlation"),
          )
      assertEquals(
          row.getValue("expected"),
          result?.wireName ?: "SUCCESS",
          row.getValue("id"),
      )
      val next = row.getValue("next_sequence")
      if (next == "EXHAUSTED") assertNull(ledger.nextIncomingSequence, row.getValue("id"))
      else assertEquals(next.toLong(), ledger.nextIncomingSequence, row.getValue("id"))
    }
    val bounded = V9ResponseLedger(1)
    bounded.recordPeerRequest(7, V9MessageType.AUTH)
    assertNull(
        bounded.accept(
            1,
            V9MessageType.AUTH_RESULT,
            V9Flag.RESPONSE.wireMask,
            7,
        ),
    )
    assertEquals(0, bounded.outstandingRequests)
    assertFailsWith<IllegalArgumentException> {
      bounded.recordPeerRequest(7, V9MessageType.AUTH)
    }

    val exhausted = V9ResponseLedger(ProtocolV9.LAST_SEQUENCE)
    assertNull(exhausted.accept(ProtocolV9.LAST_SEQUENCE, V9MessageType.INPUT, 0, 0))
    assertNull(exhausted.nextIncomingSequence)
    assertEquals(
        V9ErrorCode.SEQUENCE_ERROR,
        exhausted.accept(0, V9MessageType.INPUT, 0, 0),
    )

    val input =
        hex(
            rows("/netplay-v9/wire-vectors.tsv")
                .single { it.getValue("id") == "valid_input" }
                .getValue("input_hex"),
        )
    ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN).putInt(12, ProtocolV9.LAST_SEQUENCE.toInt())
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.INPUT),
            negotiatedCapabilities = V9Capability.entries.toSet(),
        )
    val decoder = V9IncrementalDecoder(ProtocolV9.LAST_SEQUENCE, policy = policy)
    val accepted = decoder.feed(input)
    assertTrue(accepted.directionExhausted)
    assertEquals(V9MessageType.INPUT, accepted.frames.single().header.type)
    accepted.frames.single().close()
    val wrapped = input.copyOf().also {
      ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(12, 0)
    }
    val rejected = decoder.feed(wrapped.copyOf(32))
    assertEquals(V9ErrorCode.SEQUENCE_ERROR, rejected.failure?.reason)
    assertTrue(rejected.directionExhausted)
    assertEquals(1, rejected.payloadAllocations)
    assertEquals(V9QueueSnapshot(0, 0, 0), decoder.queueSnapshot())
  }

  @Test
  fun lifecycleUsesInjectedMonotonicDeadlineAndPublishesImmutableSanitizedState() {
    val clock = FakeClock()
    val lifecycle = V9Lifecycle(V9Role.CLIENT, clock)
    val events = mutableListOf<V9LifecycleSnapshot>()
    lifecycle.addListener { events += it }
    assertEquals(5_000, lifecycle.snapshot().deadlineMillis)
    clock.now = 4_999
    assertNull(lifecycle.checkDeadline())
    clock.now = 5_000
    val failure = assertNotNull(lifecycle.checkDeadline())
    assertEquals(V9ErrorCode.TIMEOUT, failure.reason)
    assertEquals(V9Diagnostic.TIMEOUT, failure.diagnostic)
    assertEquals(V9LifecycleState.CLOSED, lifecycle.snapshot().state)
    assertTrue(events.all { it.failure?.toString()?.contains("token", ignoreCase = true) != true })

    val mutable =
        lifecycle.snapshot().negotiatedCapabilities as java.util.Set<V9Capability>
    assertFailsWith<UnsupportedOperationException> {
      mutable.add(V9Capability.PING_V1)
    }

    val terminalClock = FakeClock()
    val terminal = V9Lifecycle(V9Role.CLIENT, terminalClock)
    val original =
        assertNotNull(
            terminal.beginTerminalCleanup(
                V9ErrorCode.CAPABILITY_MISMATCH,
                V9Diagnostic.CAPABILITY_MISMATCH,
            ),
        )
    assertEquals(V9LifecycleState.WAIT_SERVER_HELLO, original.stage)
    assertEquals(2_000, terminal.snapshot().deadlineMillis)
    terminalClock.now = 1_999
    assertEquals(original, terminal.checkDeadline())
    assertEquals(V9LifecycleState.TERMINAL_CLEANUP, terminal.snapshot().state)
    terminalClock.now = 2_000
    assertEquals(original, terminal.checkDeadline())
    assertEquals(V9LifecycleState.CLOSED, terminal.snapshot().state)
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, terminal.snapshot().failure?.reason)
    assertEquals(original, terminal.cancel())

    val normal = V9Lifecycle(V9Role.CLIENT)
    normal.closeNormally()
    assertNull(normal.cancel())
    assertEquals(V9LifecycleState.CLOSED, normal.snapshot().state)
  }

  @Test
  fun partialWriteFoundationStopsExactlyAtAwaitingPairingAndRejectsLaterTraffic() {
    val (serverChannel, clientChannel) = MemoryChannel.pair(maximumWrite = 3)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            nonce = ByteArray(32) { 1 },
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            nonce = ByteArray(32) { 2 },
        )
    server.start()
    client.start()
    val serverState = server.awaitPairingBoundary(3, TimeUnit.SECONDS)
    val clientState = client.awaitPairingBoundary(3, TimeUnit.SECONDS)
    assertEquals(V9LifecycleState.WAIT_AUTH, serverState.state)
    assertEquals(V9LifecycleState.SEND_AUTH, clientState.state)
    assertEquals(V9LifecyclePhase.AWAITING_PAIRING, serverState.phase)
    assertEquals(V9Capability.requiredCapabilities, server.negotiatedCapabilities())
    assertEquals(V9Capability.requiredCapabilities, client.negotiatedCapabilities())
    assertFailsWith<V9ProtocolException> { client.sendUnavailable(V9MessageType.AUTH) }
    client.cancel()
    server.close()
    client.close()
    assertTrue(clientChannel.closed.get())
    assertTrue(serverChannel.closed.get())
  }

  @Test
  fun timeoutAndCancellationCloseBlockedReadAndWriteAndAreIdempotent() {
    val readClock = FakeClock()
    val readScheduler = ManualScheduler(readClock)
    val readChannel = BlockingChannel(blockWrites = false)
    val waiting =
        V9FoundationConnection(
            readChannel,
            V9Role.CLIENT,
            clock = readClock,
            scheduler = readScheduler,
        )
    waiting.start()
    readClock.now = 4_999
    readScheduler.runDue()
    assertEquals(V9LifecycleState.WAIT_SERVER_HELLO, waiting.snapshot().state)
    readClock.now = 5_000
    readScheduler.runDue()
    assertEquals(V9ErrorCode.TIMEOUT, waiting.snapshot().failure?.reason)
    assertEquals(1, readChannel.closeCount.get())
    waiting.close()
    assertEquals(1, readChannel.closeCount.get())

    val writeChannel = BlockingChannel(blockWrites = true)
    val writing = V9FoundationConnection(writeChannel, V9Role.SERVER)
    writing.start()
    assertTrue(writeChannel.writeEntered.await(2, TimeUnit.SECONDS))
    writing.cancel()
    assertEquals(V9ErrorCode.CANCELLED, writing.snapshot().failure?.reason)
    assertEquals(1, writeChannel.closeCount.get())
    assertEquals(V9QueueSnapshot(0, 0, 0), writing.writerQueueSnapshot())

    val connectable = BlockingConnectableChannel()
    val attempt = V9FoundationConnectAttempt { connectable }
    val result = CountDownLatch(1)
    var connectError: V9ErrorCode? = null
    attempt.start(InetSocketAddress("127.0.0.1", 1)) { _, error ->
      connectError = error
      result.countDown()
    }
    assertTrue(connectable.connectEntered.await(2, TimeUnit.SECONDS))
    attempt.cancel()
    assertTrue(result.await(2, TimeUnit.SECONDS))
    assertEquals(V9ErrorCode.CANCELLED, connectError)
    assertEquals(1, connectable.closeCount.get())
  }

  @Test
  fun optInSocketLoopbackSurvivesMalformedCandidateAndStopsBeforePairing() {
    val serverBoundary = CountDownLatch(1)
    var accepted: V9FoundationConnection? = null
    V9FoundationServer(onAwaitingPairing = {
      accepted = it
      serverBoundary.countDown()
    }).use { server ->
      server.start()
      Socket("127.0.0.1", server.localPort).use { bad ->
        bad.getOutputStream().write(byteArrayOf(0x42, 0x41, 0x44, 0x39))
        bad.getOutputStream().flush()
      }
      Socket("127.0.0.1", server.localPort).use { rejected ->
        rejected.soTimeout = 3_000
        readExactly(rejected, 64 + 94) // Server HELLO is complete before peer reads begin.
        val malformedHello =
            hex(
                rows("/netplay-v9/wire-vectors.tsv")
                    .single { it.getValue("id") == "hello_missing_required_cap" }
                    .getValue("input_hex"),
            )
        rejected.getOutputStream().write(malformedHello)
        rejected.getOutputStream().flush()
        val errorBytes = readExactly(rejected, 64 + 12)
        val errorBatch =
            V9IncrementalDecoder(
                    1,
                    policy = V9DecoderPolicy(allowedMessages = setOf(V9MessageType.ERROR)),
                )
                .feed(errorBytes)
        assertEquals(V9MessageType.ERROR, errorBatch.frames.single().header.type)
        assertEquals(
            V9ErrorCode.CAPABILITY_MISMATCH,
            V9ErrorPayloadCodec.decode(errorBatch.frames.single().payload()).error,
        )
        errorBatch.frames.single().close()
      }
      Socket("127.0.0.1", server.localPort).use { stalled ->
        val client =
            V9FoundationClient.connect(InetSocketAddress("127.0.0.1", server.localPort))
        try {
          assertTrue(serverBoundary.await(5, TimeUnit.SECONDS))
          assertEquals(
              V9LifecyclePhase.AWAITING_PAIRING,
              client.awaitPairingBoundary(5, TimeUnit.SECONDS).phase,
          )
          assertEquals(V9LifecycleState.WAIT_AUTH, assertNotNull(accepted).snapshot().state)
          assertEquals(V9LifecycleState.SEND_AUTH, client.snapshot().state)
        } finally {
          client.close()
          accepted?.close()
        }
      }
    }
  }

  private fun readExactly(socket: Socket, size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < result.size) {
      val count = socket.getInputStream().read(result, offset, result.size - offset)
      if (count < 0) throw IOException("unexpected test EOF")
      offset += count
    }
    return result
  }

  private fun headerFrom(row: Map<String, String>): ByteArray {
    val magic = row.getValue("magic").toByteArray(StandardCharsets.US_ASCII)
    return ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        .put(magic)
        .put(row.int("major").toByte())
        .put(row.int("minor").toByte())
        .putShort(row.int("header_length").toShort())
        .putShort(row.hexInt("type").toShort())
        .putShort(row.hexInt("flags").toShort())
        .putInt(row.long("sequence").toInt())
        .putInt(row.long("correlation").toInt())
        .putInt(row.long("encoded").toInt())
        .putInt(row.long("decoded").toInt())
        .putInt(row.long("channel").toInt())
        .put(ByteArray(32))
        .array()
  }

  private fun helloWithUnknown(base: ByteArray, required: Boolean): ByteArray {
    val result = base.copyOf(base.size + 8)
    ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        .putShort(36, 8)
        .position(base.size)
    ByteBuffer.wrap(result, base.size, 8).order(ByteOrder.BIG_ENDIAN)
        .putShort(0x1000.toShort())
        .putShort(1)
        .putInt(if (required) 1 else 0)
    return result
  }

  private fun readFrame(channel: V9TransportChannel): ByteArray {
    val header = ByteArray(ProtocolV9.HEADER_BYTES)
    readFully(channel, header)
    val length = u32(header, 20).toInt()
    val frame = header.copyOf(header.size + length)
    if (length > 0) {
      val payload = ByteArray(length)
      readFully(channel, payload)
      System.arraycopy(payload, 0, frame, header.size, length)
    }
    return frame
  }

  private fun readFully(channel: V9TransportChannel, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
      val count = channel.read(bytes, offset, bytes.size - offset)
      check(count > 0) { "unexpected EOF" }
      offset += count
    }
  }

  private fun writeAll(channel: V9TransportChannel, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
      val count = channel.write(bytes, offset, bytes.size - offset)
      check(count > 0) { "writer made no progress" }
      offset += count
    }
  }

  private class FakeClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class ManualScheduler(private val clock: FakeClock) : V9DeadlineScheduler {
    private val tasks = mutableListOf<Task>()

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

    private data class Task(
        val deadline: Long,
        val action: Runnable,
        val active: AtomicBoolean = AtomicBoolean(true),
    )
  }

  private class BlockingChannel(private val blockWrites: Boolean) : V9TransportChannel {
    val closeCount = AtomicInteger()
    val writeEntered = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val wake = CountDownLatch(1)

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      wake.await()
      return -1
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      writeEntered.countDown()
      if (blockWrites) wake.await()
      if (closed.get()) throw IOException("closed")
      return length
    }

    override fun shutdownOutput() = Unit

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        closeCount.incrementAndGet()
        wake.countDown()
      }
    }
  }

  private class BlockingConnectableChannel : V9ConnectableChannel {
    val connectEntered = CountDownLatch(1)
    val closeCount = AtomicInteger()
    private val wake = CountDownLatch(1)
    private val closed = AtomicBoolean(false)

    override fun connect(address: InetSocketAddress, timeoutMillis: Int) {
      connectEntered.countDown()
      wake.await()
      throw IOException("closed")
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = -1

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int = length

    override fun shutdownOutput() = Unit

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        closeCount.incrementAndGet()
        wake.countDown()
      }
    }
  }

  private class MemoryChannel(private val maximumWrite: Int) : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    val closed = AtomicBoolean(false)
    private lateinit var peer: MemoryChannel

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
      repeat(count) { peer.incoming.put(bytes[offset + it].toInt() and 0xff) }
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

    companion object {
      fun pair(maximumWrite: Int): Pair<MemoryChannel, MemoryChannel> {
        val first = MemoryChannel(maximumWrite)
        val second = MemoryChannel(maximumWrite)
        first.peer = second
        second.peer = first
        return first to second
      }
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

  private fun Map<String, String>.hexInt(name: String): Int =
      getValue(name).removePrefix("0x").toInt(16)

  private fun Map<String, String>.boolean(name: String): Boolean =
      getValue(name).toBooleanStrict()

  private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
      value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
  }
}
