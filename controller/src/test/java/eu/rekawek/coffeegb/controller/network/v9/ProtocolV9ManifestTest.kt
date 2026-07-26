package eu.rekawek.coffeegb.controller.network.v9

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9ManifestTest {

  @Test
  fun manifestRegistriesExactlyMatchTheFrozenRows() {
    val mappers = rows("/netplay-v9/mapper-families.tsv")
    assertEquals(V9MapperFamily.entries.size, mappers.size)
    mappers.forEach { row ->
      val value = requireNotNull(V9MapperFamily.fromWireId(row.hexInt("id")))
      assertEquals(row.getValue("name"), value.wireName)
    }

    val differences = rows("/netplay-v9/manifest-diffs.tsv")
    assertEquals(V9ManifestDifferenceCode.entries.size, differences.size)
    differences.forEach { row ->
      val value = requireNotNull(V9ManifestDifferenceCode.fromWireId(row.hexInt("id")))
      assertEquals(row.getValue("name"), value.wireName)
      assertEquals(row.getValue("severity"), value.severity.name)
      assertEquals(row.getValue("approval_allowed").toBoolean(), value.approvalAllowed)
    }
  }

  @Test
  fun normalAndFourPlayerManifestsRoundTripWithDetachedOwnership() {
    val normal = normalManifest(sender = 0, differences = emptyList())
    val normalContext = context(normal, guest = 1, wireSource = 0)
    val encoded = V9ManifestCodec.encode(normal, normalContext)
    assertEquals(360, encoded.size)
    val decoded = V9ManifestCodec.decode(encoded, normalContext)
    assertEquals(listOf(0, 1), decoded.entries.map { it.player })
    assertEquals("dmg", decoded.entries[1].profileId)

    val digest = decoded.entries[1].primaryRomSha256().bytes()
    digest.fill(0)
    assertFalse(decoded.entries[1].primaryRomSha256().isZero())
    encoded.fill(0)
    assertFalse(decoded.entries[1].primaryRomSha256().isZero())
    assertFalse(decoded.toString().contains(decoded.entries[1].internalTitle))
    assertFalse(decoded.toString().contains("010101"))

    val four = fourManifest(sender = 2)
    val fourContext =
        context(
            four,
            guest = 2,
            wireSource = 2,
            capabilities = requiredCapabilities() + V9Capability.FOUR_PLAYER_V1,
        )
    val fourBytes = V9ManifestCodec.encode(four, fourContext)
    val fourDecoded = V9ManifestCodec.decode(fourBytes, fourContext)
    assertEquals(listOf(0, 1, 2, 3), fourDecoded.entries.map { it.player })
    assertEquals(V9ManifestMode.FOUR_PLAYER, fourDecoded.mode)
  }

  @Test
  fun outerManifestLengthBoundsAreExactWhileSemanticClassLimitsRemainStrict() {
    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.MANIFEST),
            negotiatedCapabilities = V9Capability.entries.toSet(),
        )
    listOf(342, 1_396).forEach { size ->
      val frame =
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.MANIFEST,
                  0,
                  0,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  ByteArray(size),
              ),
              policy,
          )
      assertEquals(ProtocolV9.HEADER_BYTES + size, frame.size)
    }
    val tooLarge =
        assertFailsWith<V9ProtocolException> {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.MANIFEST,
                  0,
                  0,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  ByteArray(1_397),
              ),
              policy,
          )
        }
    assertEquals(V9ErrorCode.LIMIT_EXCEEDED, tooLarge.reason)

    val manifest = normalManifest(sender = 0)
    val bytes = V9ManifestCodec.encode(manifest, context(manifest, 1, 0))
    assertManifestMismatch(bytes.copyOf(bytes.size - 1), context(manifest, 1, 0))
    assertManifestMismatch(ByteArray(1_397), context(manifest, 1, 0))
  }

  @Test
  fun everyTruncatedManifestBoundaryFailsAndFramingIsIncrementalAndCoalesced() {
    val manifest = normalManifest(sender = 0)
    val context = context(manifest, 1, 0)
    val payload = V9ManifestCodec.encode(manifest, context)
    for (length in 0 until payload.size) {
      assertManifestMismatch(payload.copyOf(length), context, "truncation $length")
    }

    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.MANIFEST),
            negotiatedCapabilities = requiredCapabilities(),
        )
    fun encoded(sequence: Long): ByteArray =
        V9FrameEncoder.encode(
            V9OutboundFrame(
                V9MessageType.MANIFEST,
                0,
                sequence,
                0,
                ProtocolV9.CONTROL_CHANNEL,
                payload,
            ),
            policy,
        )

    val bytewise = V9IncrementalDecoder(policy = policy)
    val firstFrame = encoded(0)
    firstFrame.indices.forEach { index ->
      val result = bytewise.feed(firstFrame, index, 1)
      if (index < firstFrame.lastIndex) {
        assertTrue(result.frames.isEmpty(), "frame completed at byte $index")
        assertTrue(result.needsMore)
      } else {
        val frame = result.frames.single()
        frame.use {
          V9ManifestCodec.decode(it.payload(), context)
        }
      }
    }

    val coalesced = encoded(0) + encoded(1)
    val irregular = V9IncrementalDecoder(policy = policy)
    val decoded = mutableListOf<V9Manifest>()
    val decodedSequences = mutableListOf<Long>()
    var offset = 0
    val chunks = intArrayOf(3, 61, 5, 127, 11, 257)
    var chunkIndex = 0
    while (offset < coalesced.size) {
      val length = minOf(chunks[chunkIndex++ % chunks.size], coalesced.size - offset)
      val result = irregular.feed(coalesced, offset, length)
      result.frames.forEach { frame ->
        frame.use {
          decodedSequences += it.header.sequence
          decoded += V9ManifestCodec.decode(it.payload(), context)
        }
      }
      offset += length
    }
    assertEquals(2, decoded.size)
    assertEquals(listOf(0L, 1L), decodedSequences)
    assertEquals(0, irregular.queueSnapshot().frames)
  }

  @Test
  fun headerEntryDifferenceAndProposalMutationsFailClosed() {
    val proposal = primaryProposal(owner = 1, source = 0, target = 1, digestByte = 2)
    val server =
        normalManifest(
            sender = 0,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        proposal.proposalId,
                    ),
                ),
            proposals = listOf(proposal),
        )
    val capabilities = requiredCapabilities() + V9Capability.ROM_TRANSFER_V1
    val context = context(server, 1, 0, capabilities)
    val valid = V9ManifestCodec.encode(server, context)

    val mutations =
        listOf<(ByteArray) -> Unit>(
            { it[0] = 1 },
            { it[2] = 2 },
            { it[3] = 1 },
            { it[4] = 3 },
            { it[5] = 9 },
            { it[6] = 17 },
            { it[7] = 1 },
            { it[8] = 8 },
            { it[9] = 1 },
            { it[10] = 1 },
            { it[12] = 1 },
            { it[14] = 0x0f },
            { it[15] = 1 },
            { it[16] = 0; it[17] = 0; it[18] = 0; it[19] = 0 },
            { it[20] = (it[20].toInt() xor 1).toByte() },
            { it[52] = 1 },
            { it[53] = 0x08 },
            { it[54] = 0 },
            { it[55] = 0x08 },
            { it[56] = 0 },
            { it[57] = 17 },
            { it[59] = 0 },
            { it[60] = 0; it[61] = 0; it[62] = 0; it[63] = 0 },
        )
    mutations.forEachIndexed { index, mutation ->
      val candidate = valid.copyOf()
      mutation(candidate)
      assertManifestMismatch(candidate, context, "mutation $index")
    }

    val firstEntryLength = 144 + server.entries[0].profileId.length +
        server.entries[0].internalTitle.length
    val secondEntryLength = 144 + server.entries[1].profileId.length +
        server.entries[1].internalTitle.length
    val diffOffset = 52 + firstEntryLength + secondEntryLength
    val proposalOffset = diffOffset + 12
    listOf(
        diffOffset,
        diffOffset + 2,
        diffOffset + 3,
        diffOffset + 4,
        diffOffset + 8,
        proposalOffset,
        proposalOffset + 4,
        proposalOffset + 5,
        proposalOffset + 6,
        proposalOffset + 7,
        proposalOffset + 8,
        proposalOffset + 9,
        proposalOffset + 10,
        proposalOffset + 11,
        proposalOffset + 12,
        proposalOffset + 16,
    ).forEach { offset ->
      val candidate = valid.copyOf()
      candidate[offset] = (candidate[offset].toInt() xor 0x40).toByte()
      assertManifestMismatch(candidate, context, "offset $offset")
    }
  }

  @Test
  fun entryOrderingPresenceTextAndRosterRelationsAreValidatedBeforeUse() {
    val manifest = normalManifest(sender = 0)
    val context = context(manifest, 1, 0)
    val bytes = V9ManifestCodec.encode(manifest, context)
    val secondOffset =
        52 + 144 + manifest.entries[0].profileId.length + manifest.entries[0].internalTitle.length

    val duplicatePlayer = bytes.copyOf().also { it[secondOffset] = 0 }
    assertManifestMismatch(duplicatePlayer, context)
    val absentWithSize = bytes.copyOf().also {
      it[secondOffset + 1] = (it[secondOffset + 1].toInt() and 0xfe).toByte()
    }
    assertManifestMismatch(absentWithSize, context)
    val zeroDigestPresent = bytes.copyOf().also {
      it.fill(0, secondOffset + 16, secondOffset + 48)
    }
    assertManifestMismatch(zeroDigestPresent, context)
    val nonCanonicalProfile = bytes.copyOf().also {
      it[secondOffset + 144] = 'D'.code.toByte()
    }
    assertManifestMismatch(nonCanonicalProfile, context)
    val controlTitle = bytes.copyOf().also {
      val title = secondOffset + 144 + manifest.entries[1].profileId.length
      it[title] = 0x1f
    }
    assertManifestMismatch(controlTitle, context)

    val swapped = normalManifest(sender = 0, entries = manifest.entries.reversed())
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(swapped, context)
    }
  }

  @Test
  fun everyFrozenDirectionVectorUsesTheActualWireSender() {
    val capabilities = requiredCapabilities() + V9Capability.ROM_TRANSFER_V1
    rows("/netplay-v9/manifest-direction-vectors.tsv").forEach { row ->
      val guest = row.int("guest")
      val source = row.int("proposal_source")
      val target = row.int("proposal_target")
      val sender = row.int("sender")
      val proposal =
          primaryProposal(
              owner = 1,
              source = source,
              target = target,
              digestByte = 2,
              action =
                  if (row.int("action") == 1) {
                    V9TransferAction.OFFER_BY_SOURCE
                  } else {
                    V9TransferAction.REQUEST_BY_TARGET
                  },
          )
      val manifest =
          normalManifest(
              sender = sender,
              differences =
                  listOf(
                      V9ManifestDifference(
                          V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                          1,
                          proposal.proposalId,
                      ),
                  ),
              proposals = listOf(proposal),
          )
      val context =
          context(
              manifest,
              guest,
              row.int("wire_source"),
              capabilities,
              wireTarget = row.int("wire_target"),
          )
      val outcome =
          try {
            val bytes = encodeWithoutDirectionCheck(manifest, capabilities)
            V9ManifestCodec.decode(bytes, context)
            "SUCCESS"
          } catch (_: V9ProtocolException) {
            "MANIFEST_MISMATCH"
          }
      assertEquals(row.getValue("expected"), outcome, row.getValue("id"))
    }
  }

  @Test
  fun frozenManifestClassificationVectorsReachOnlyThePreConsentResult() {
    rows("/netplay-v9/manifest-consent-vectors.tsv").forEach { row ->
      val outcome = executeClassificationVector(row)
      val expected =
          when (row.getValue("id")) {
            "compatible_own_rom" -> "SUCCESS"
            "fatal_protocol" -> "CAPABILITY_MISMATCH"
            "fatal_profile",
            "missing_rom_no_proposal",
            "wrong_proposal_size",
            "wrong_proposal_digest",
            "wrong_diff_pairing",
            "absent_proposal_owner",
            "cross_guest_endpoint",
            "wrong_offer_direction",
            "wrong_asset_pairing",
            "different_roster",
            "different_generation",
            "different_commitment" -> "MANIFEST_MISMATCH"
            else -> "CONSENT_REQUIRED"
          }
      assertEquals(expected, outcome, row.getValue("id"))
    }
  }

  @Test
  fun proposalClassCapabilityAndContentBindingsAreStrict() {
    val base = normalManifest(sender = 0)
    val rom = primaryProposal(1, 0, 1, 2)
    val slot =
        proposal(
            42,
            V9TransferAsset.SLOT_ROM,
            owner = 1,
            source = 0,
            target = 1,
            size = 16_384,
            digestByte = 8,
        )
    val battery =
        proposal(
            43,
            V9TransferAsset.BATTERY,
            owner = 1,
            source = 0,
            target = 1,
            size = 128,
            digestByte = 7,
        )
    val checkpoint =
        proposal(
            44,
            V9TransferAsset.CHECKPOINT,
            owner = 0xff,
            source = 0,
            target = 1,
            size = 0,
            digestByte = 0,
        )
    val entries =
        base.entries.map {
          if (it.player == 1) {
            entry(1, 2, slotDigestByte = 8, slotLength = 16_384, battery = true)
          } else {
            it
          }
        }
    val manifest =
        normalManifest(
            sender = 0,
            entries = entries,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        41,
                    ),
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.SLOT_ROM_DIFFERENT,
                        1,
                        42,
                    ),
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.BATTERY_TRANSFER,
                        1,
                        43,
                    ),
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.CHECKPOINT_SYNC,
                        1,
                        44,
                    ),
                ),
            proposals = listOf(rom, slot, battery, checkpoint),
        )
    val allCapabilities =
        requiredCapabilities() +
            setOf(V9Capability.ROM_TRANSFER_V1, V9Capability.BATTERY_TRANSFER_V1)
    V9ManifestCodec.encode(manifest, context(manifest, 1, 0, allCapabilities))

    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(
          manifest,
          context(manifest, 1, 0, requiredCapabilities()),
      )
    }
    val wrongRom =
        normalManifest(
            sender = 0,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        41,
                    ),
                ),
            proposals = listOf(primaryProposal(1, 0, 1, 9)),
        )
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(wrongRom, context(wrongRom, 1, 0, allCapabilities))
    }
  }

  @Test
  fun semanticProposalClassCeilingsRejectThirdRomSecondBatteryAndSecondCheckpoint() {
    val entries =
        listOf(
            entry(0, 1, slotDigestByte = 5, slotLength = 8_192, battery = true),
            entry(1, 2, battery = true),
        )
    val romProposals =
        listOf(
            proposal(41, V9TransferAsset.PRIMARY_ROM, 0, 0, 1, 32_768, 1),
            proposal(42, V9TransferAsset.SLOT_ROM, 0, 0, 1, 8_192, 5),
            proposal(43, V9TransferAsset.PRIMARY_ROM, 1, 0, 1, 32_768, 2),
        )
    val threeRom =
        normalManifest(
            0,
            entries = entries,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        0,
                        41,
                    ),
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.SLOT_ROM_DIFFERENT,
                        0,
                        42,
                    ),
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        43,
                    ),
                ),
            proposals = romProposals,
        )
    val capabilities =
        requiredCapabilities() +
            setOf(V9Capability.ROM_TRANSFER_V1, V9Capability.BATTERY_TRANSFER_V1)
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(threeRom, context(threeRom, 1, 0, capabilities))
    }

    val batteries =
        listOf(
            proposal(51, V9TransferAsset.BATTERY, 0, 0, 1, 128, 6),
            proposal(52, V9TransferAsset.BATTERY, 1, 0, 1, 128, 7),
        )
    val twoBattery =
        normalManifest(
            0,
            entries = entries,
            differences =
                listOf(
                    V9ManifestDifference(V9ManifestDifferenceCode.BATTERY_TRANSFER, 0, 51),
                    V9ManifestDifference(V9ManifestDifferenceCode.BATTERY_TRANSFER, 1, 52),
                ),
            proposals = batteries,
        )
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(twoBattery, context(twoBattery, 1, 0, capabilities))
    }

    val checkpoints =
        listOf(
            proposal(61, V9TransferAsset.CHECKPOINT, 0xff, 0, 1, 0, 0),
            proposal(62, V9TransferAsset.CHECKPOINT, 0xff, 0, 1, 0, 0),
        )
    val twoCheckpoint =
        normalManifest(
            0,
            entries = entries,
            differences =
                listOf(
                    V9ManifestDifference(V9ManifestDifferenceCode.CHECKPOINT_SYNC, 1, 61),
                    V9ManifestDifference(V9ManifestDifferenceCode.CHECKPOINT_SYNC, 1, 62),
                ),
            proposals = checkpoints,
        )
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(twoCheckpoint, context(twoCheckpoint, 1, 0, capabilities))
    }
  }

  @Test
  fun exactOwnRomAndWarningPairComparisonsAreDeterministic() {
    val server = normalManifest(sender = 0)
    val client = normalManifest(sender = 1, differences = emptyList())
    val exact = V9ManifestCompatibility.compare(server, client, 1)
    assertIs<V9ManifestComparisonResult.Compatible>(exact)
    assertTrue(exact.proposals.isEmpty())

    val proposal = primaryProposal(1, 0, 1, 2)
    val changedServer =
        normalManifest(
            sender = 0,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        proposal.proposalId,
                    ),
                ),
            proposals = listOf(proposal),
        )
    val changedEntries = client.entries.map {
      if (it.player == 1) entry(1, 3) else it
    }
    val changedClient =
        normalManifest(sender = 1, entries = changedEntries, differences = emptyList())
    val warning = V9ManifestCompatibility.compare(changedServer, changedClient, 1)
    assertIs<V9ManifestComparisonResult.Compatible>(warning)
    assertEquals(listOf(41L), warning.proposals.map { it.proposalId })

    val noProposal =
        normalManifest(
            sender = 0,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        41,
                    ),
                ),
            proposals = emptyList(),
        )
    assertFailsWith<V9ProtocolException> {
      V9ManifestCodec.encode(
          noProposal,
          context(noProposal, 1, 0, requiredCapabilities() + V9Capability.ROM_TRANSFER_V1),
      )
    }
    val capability =
        V9ManifestCompatibility.compare(
            server,
            client,
            1,
            protocolCapabilityCompatible = false,
        )
    assertEquals(
        V9ErrorCode.CAPABILITY_MISMATCH,
        assertIs<V9ManifestComparisonResult.Rejected>(capability).reason,
    )
  }

  @Test
  fun productionConnectionsExchangeFragmentedNormalManifestsAndStopBeforeConsent() {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val invitation = host.createInvitation("example.com", 6688, 1)
    val clientInvitation = invitation.forClientAuthentication()
    val serverManifest = normalManifest(sender = 0)
    val clientManifest = normalManifest(sender = 1, differences = emptyList())
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(maximumWrite = 1)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.NORMAL,
                    mapOf(1 to serverManifest),
                ),
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            clientInvitation = clientInvitation,
            manifestPlan =
                V9ManifestPlan.client(V9LinkMode.NORMAL, 1, clientManifest),
        )
    try {
      server.start()
      client.start()
      val serverBoundary = assertNotNull(server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      val clientBoundary = assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertEquals(V9LifecycleState.SYNCHRONIZING, serverBoundary.state)
      assertEquals(V9LifecycleState.SYNCHRONIZING, clientBoundary.state)
      assertEquals(V9LifecycleState.SYNCHRONIZING, server.snapshot().state)
      assertEquals(V9LifecycleState.SYNCHRONIZING, client.snapshot().state)
      assertTrue(serverBoundary.proposals.isEmpty())
      assertEquals(serverBoundary.serverPayloadSha256(), clientBoundary.serverPayloadSha256())
      assertEquals(serverBoundary.clientPayloadSha256(), clientBoundary.clientPayloadSha256())
      assertEquals(
          setOf(V9MessageType.HELLO, V9MessageType.AUTH_RESULT, V9MessageType.MANIFEST),
          wireTypes(serverChannel.recordedBytes()),
      )
      assertEquals(
          setOf(V9MessageType.HELLO, V9MessageType.AUTH, V9MessageType.MANIFEST),
          wireTypes(clientChannel.recordedBytes()),
      )
      laterTypes().forEach { type ->
        assertEquals(
            V9ErrorCode.UNEXPECTED_MESSAGE,
            assertFailsWith<V9ProtocolException> { server.sendUnavailable(type) }.reason,
        )
      }
    } finally {
      client.close()
      server.close()
      host.close()
    }
  }

  @Test
  fun validProposalStopsBothPeersAtImmutableExchangeConsentBoundary() {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val invitation = host.createInvitation("example.com", 6688, 1)
    val clientInvitation = invitation.forClientAuthentication()
    val proposal = primaryProposal(1, 0, 1, 2)
    val serverManifest =
        normalManifest(
            sender = 0,
            differences =
                listOf(
                    V9ManifestDifference(
                        V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT,
                        1,
                        proposal.proposalId,
                    ),
                ),
            proposals = listOf(proposal),
        )
    val clientEntries =
        normalEntries().map { if (it.player == 1) entry(1, 3) else it }
    val clientManifest =
        normalManifest(sender = 1, entries = clientEntries, differences = emptyList())
    val optional = setOf(V9Capability.ROM_TRANSFER_V1)
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(maximumWrite = 7)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            optionalCapabilities = optional,
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(V9LinkMode.NORMAL, mapOf(1 to serverManifest)),
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            optionalCapabilities = optional,
            clientInvitation = clientInvitation,
            manifestPlan =
                V9ManifestPlan.client(V9LinkMode.NORMAL, 1, clientManifest),
        )
    try {
      server.start()
      client.start()
      val serverBoundary = assertNotNull(server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      val clientBoundary = assertNotNull(client.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertEquals(V9LifecycleState.EXCHANGE_CONSENT, serverBoundary.state)
      assertEquals(V9LifecycleState.EXCHANGE_CONSENT, clientBoundary.state)
      assertEquals(listOf(41L), serverBoundary.proposals.map { it.proposalId })
      assertFalse(serverBoundary.toString().contains("PLAYER"))
      assertFalse(serverBoundary.toString().contains("020202"))
    } finally {
      client.close()
      server.close()
      host.close()
    }
  }

  @Test
  fun fourPlayerManifestExchangeUsesTheFullRosterOnTheAuthenticatedGuestSocket() {
    val host = V9InvitationHost(V9LinkMode.FOUR_PLAYER)
    val invitation = host.createInvitation("example.com", 6688, 2)
    val clientInvitation = invitation.forClientAuthentication()
    val serverManifest = fourManifest(sender = 0)
    val clientManifest = fourManifest(sender = 2, differences = emptyList())
    val optional = setOf(V9Capability.FOUR_PLAYER_V1)
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(maximumWrite = 13)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = optional,
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.FOUR_PLAYER,
                    mapOf(2 to serverManifest),
                ),
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            V9LinkMode.FOUR_PLAYER,
            optionalCapabilities = optional,
            clientInvitation = clientInvitation,
            manifestPlan =
                V9ManifestPlan.client(V9LinkMode.FOUR_PLAYER, 2, clientManifest),
        )
    try {
      server.start()
      client.start()
      val boundary = assertNotNull(server.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertEquals(listOf(0, 1, 2, 3), boundary.clientManifest.entries.map { it.player })
      assertEquals(2, boundary.authenticatedGuest)
      assertEquals(V9LifecycleState.SYNCHRONIZING, boundary.state)
    } finally {
      client.close()
      server.close()
      host.close()
    }
  }

  @Test
  fun onePreparedFourPlayerPlanCannotMixRosterGenerationsOrCommitments() {
    val first = fourManifest(sender = 0, generation = 9)
    val differentGeneration = fourManifest(sender = 0, generation = 10)
    assertFailsWith<IllegalArgumentException> {
      V9ManifestPlan.server(
          V9LinkMode.FOUR_PLAYER,
          mapOf(1 to first, 2 to differentGeneration),
      )
    }
    val corruptedCommitment =
        V9Manifest(
            first.mode,
            first.senderPlayer,
            first.rosterGeneration,
            V9ManifestDigest(
                first.rosterCommitment().bytes().also {
                  it[0] = (it[0].toInt() xor 1).toByte()
                },
            ),
            first.entries,
            first.differences,
            first.proposals,
        )
    assertFailsWith<IllegalArgumentException> {
      V9ManifestPlan.server(
          V9LinkMode.FOUR_PLAYER,
          mapOf(1 to first, 2 to corruptedCommitment),
      )
    }
  }

  @Test
  fun manifestStageDeadlinesExpireExactlyWithoutTrickleExtension() {
    listOf(
        V9Role.SERVER to V9LifecycleState.SEND_SERVER_MANIFEST,
        V9Role.SERVER to V9LifecycleState.WAIT_CLIENT_MANIFEST,
        V9Role.CLIENT to V9LifecycleState.WAIT_SERVER_MANIFEST,
        V9Role.CLIENT to V9LifecycleState.SEND_CLIENT_MANIFEST,
    ).forEach { (role, expected) ->
      val clock = FakeClock()
      val lifecycle = V9Lifecycle(role, clock)
      if (role == V9Role.SERVER) {
        lifecycle.serverHelloSent()
        lifecycle.clientHelloReceived(negotiated())
        lifecycle.clientAuthReceived()
        lifecycle.serverAuthResultSent()
        if (expected == V9LifecycleState.WAIT_CLIENT_MANIFEST) {
          lifecycle.serverManifestSent()
        }
      } else {
        lifecycle.serverHelloReceived()
        lifecycle.clientHelloSent(negotiated())
        lifecycle.clientAuthSent()
        lifecycle.serverAuthResultReceived()
        if (expected == V9LifecycleState.SEND_CLIENT_MANIFEST) {
          lifecycle.serverManifestReceived()
        }
      }
      assertEquals(expected, lifecycle.snapshot().state)
      clock.now = 9_999
      assertNull(lifecycle.checkDeadline())
      assertEquals(expected, lifecycle.snapshot().state)
      clock.now = 10_000
      assertEquals(V9ErrorCode.TIMEOUT, lifecycle.checkDeadline()?.reason)
      assertEquals(V9LifecycleState.CLOSED, lifecycle.snapshot().state)
    }
  }

  @Test
  fun manifestCancellationIsIdempotentInEverySendAndWaitState() {
    listOf(
        V9Role.SERVER to V9LifecycleState.SEND_SERVER_MANIFEST,
        V9Role.SERVER to V9LifecycleState.WAIT_CLIENT_MANIFEST,
        V9Role.CLIENT to V9LifecycleState.WAIT_SERVER_MANIFEST,
        V9Role.CLIENT to V9LifecycleState.SEND_CLIENT_MANIFEST,
    ).forEach { (role, expected) ->
      val lifecycle = manifestLifecycle(role, expected, FakeClock())
      val first = lifecycle.cancel()
      val second = lifecycle.cancel()
      lifecycle.closeNormally()
      assertEquals(V9ErrorCode.CANCELLED, first?.reason, expected.name)
      assertEquals(first, second, expected.name)
      assertEquals(V9LifecycleState.CLOSED, lifecycle.snapshot().state)
    }
  }

  @Test
  fun malformedManifestCandidateReleasesItsSlotAndDoesNotKillTheListener() {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val serverManifest = normalManifest(sender = 0, differences = emptyList())
    val accepted = mutableListOf<V9FoundationConnection>()
    val server =
        V9FoundationServer(
            mode = V9LinkMode.NORMAL,
            invitationHost = host,
            manifestPlan =
                V9ManifestPlan.server(
                    V9LinkMode.NORMAL,
                    mapOf(1 to serverManifest),
                ),
        ) { connection ->
          synchronized(accepted) { accepted += connection }
        }
    server.start()
    var rejected: V9FoundationConnection? = null
    var valid: V9FoundationConnection? = null
    try {
      val badInvitation =
          host.createInvitation("127.0.0.1", server.localPort, 1)
              .forClientAuthentication()
      val badEntries =
          normalEntries().map { if (it.player == 1) entry(1, 2, profile = "cgb") else it }
      val badManifest =
          normalManifest(
              sender = 1,
              entries = badEntries,
              differences =
                  listOf(
                      V9ManifestDifference(
                          V9ManifestDifferenceCode.PROFILE_IDENTITY,
                          1,
                      ),
                  ),
          )
      rejected =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              invitation = badInvitation,
              manifestPlan =
                  V9ManifestPlan.client(V9LinkMode.NORMAL, 1, badManifest),
          )
      assertNull(rejected.awaitManifestBoundary(5, TimeUnit.SECONDS))
      waitUntil { rejected.snapshot().state == V9LifecycleState.CLOSED }
      assertEquals(V9ErrorCode.MANIFEST_MISMATCH, rejected.snapshot().failure?.reason)
      waitUntil { host.occupiedSlots().isEmpty() }

      val goodInvitation =
          host.createInvitation("127.0.0.1", server.localPort, 1)
              .forClientAuthentication()
      val clientManifest = normalManifest(sender = 1, differences = emptyList())
      valid =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              invitation = goodInvitation,
              manifestPlan =
                  V9ManifestPlan.client(V9LinkMode.NORMAL, 1, clientManifest),
          )
      val boundary = assertNotNull(valid.awaitManifestBoundary(5, TimeUnit.SECONDS))
      assertEquals(V9LifecycleState.SYNCHRONIZING, boundary.state)
      waitUntil { synchronized(accepted) { accepted.size >= 2 } }
      assertTrue(server.acceptThreadAlive())
    } finally {
      rejected?.close()
      valid?.close()
      server.close()
      host.close()
      waitUntil { server.pendingCandidateCount() == 0 }
      waitUntil { server.activeConnectionCount() == 0 }
    }
  }

  @Test
  fun everyLaterPrivateOrGameplayHeaderRejectsBeforePayloadAllocation() {
    val unavailable =
        V9MessageType.entries.toSet() -
            setOf(
                V9MessageType.HELLO,
                V9MessageType.AUTH,
                V9MessageType.AUTH_RESULT,
                V9MessageType.MANIFEST,
                V9MessageType.CANCEL,
                V9MessageType.GOODBYE,
                V9MessageType.ERROR,
            )
    unavailable.forEach { type ->
      val decoder =
          V9IncrementalDecoder(
              policy =
                  V9DecoderPolicy(
                      allowedMessages =
                          setOf(
                              V9MessageType.HELLO,
                              V9MessageType.AUTH,
                              V9MessageType.AUTH_RESULT,
                              V9MessageType.MANIFEST,
                              V9MessageType.CANCEL,
                              V9MessageType.GOODBYE,
                              V9MessageType.ERROR,
                          ),
                      negotiatedCapabilities = V9Capability.entries.toSet(),
                      linkMode = V9LinkMode.NORMAL,
                  ),
          )
      val header = validHeader(type)
      val result = decoder.feed(header, 0, 32)
      assertEquals(V9ErrorCode.UNEXPECTED_MESSAGE, result.failure?.reason, type.wireName)
      assertEquals(0, result.payloadAllocations, type.wireName)
      assertEquals(0, result.payloadReservations, type.wireName)
      assertEquals(32, result.consumedBytes, type.wireName)
    }
  }

  @Test
  fun partOneCallersRemainAtTheUnchangedPostAuthBoundary() {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val invitation = host.createInvitation("example.com", 6688, 1)
    val clientInvitation = invitation.forClientAuthentication()
    val (serverChannel, clientChannel) = RecordingMemoryChannel.pair(maximumWrite = 17)
    val server =
        V9FoundationConnection(
            serverChannel,
            V9Role.SERVER,
            invitationHost = host,
        )
    val client =
        V9FoundationConnection(
            clientChannel,
            V9Role.CLIENT,
            clientInvitation = clientInvitation,
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
      assertNull(server.awaitManifestBoundary(20, TimeUnit.MILLISECONDS))
      assertNull(client.awaitManifestBoundary(20, TimeUnit.MILLISECONDS))
      assertFalse(wireTypes(serverChannel.recordedBytes()).contains(V9MessageType.MANIFEST))
    } finally {
      client.close()
      server.close()
      host.close()
    }
  }

  private fun executeClassificationVector(row: Map<String, String>): String {
    val guest = row.int("guest")
    val serverGeneration = row.long("server_generation")
    val clientGeneration = row.long("client_generation")
    val serverEntries = normalEntries()
    val clientPresent = row.getValue("client_primary_present").toBoolean()
    val clientDigest = row.int("client_primary_digest")
    val clientEntries =
        listOf(
            serverEntries[0],
            entry(
                1,
                if (clientPresent) clientDigest else 0,
                primaryPresent = clientPresent,
                profile = row.getValue("client_profile"),
            ),
        )
    val proposal =
        row.optionalLong("proposal_id")?.let {
          proposal(
              it,
              when (row.int("proposal_asset")) {
                1 -> V9TransferAsset.PRIMARY_ROM
                2 -> V9TransferAsset.SLOT_ROM
                3 -> V9TransferAsset.BATTERY
                else -> V9TransferAsset.CHECKPOINT
              },
              owner = row.int("proposal_owner"),
              source = row.int("proposal_source"),
              target = row.int("proposal_target"),
              size = row.long("proposal_size"),
              digestByte = row.int("proposal_digest"),
          )
        }
    val difference =
        row.optionalInt("diff_code")?.let {
          V9ManifestDifference(
              requireNotNull(V9ManifestDifferenceCode.fromWireId(it)),
              row.int("diff_player"),
              row.optionalLong("diff_proposal") ?: 0,
          )
        }
    val server =
        normalManifest(
            sender = 0,
            generation = serverGeneration,
            entries = serverEntries,
            differences = listOfNotNull(difference),
            proposals = listOfNotNull(proposal),
            rosterMaskOverride = row.hexInt("server_roster"),
        )
    val client =
        normalManifest(
            sender = guest,
            generation = clientGeneration,
            entries = clientEntries,
            differences = emptyList(),
            rosterMaskOverride = row.hexInt("client_roster"),
            corruptCommitment = row.getValue("client_commitment") == "corrupt",
        )
    if (!row.getValue("protocol_compatible").toBoolean()) {
      val result =
          V9ManifestCompatibility.compare(server, client, guest, false)
      return assertIs<V9ManifestComparisonResult.Rejected>(result).reason.wireName
    }
    val capabilities =
        requiredCapabilities() +
            setOf(V9Capability.ROM_TRANSFER_V1, V9Capability.BATTERY_TRANSFER_V1)
    return try {
      val serverBytes =
          encodeWithoutDirectionCheck(server, capabilities)
      val clientBytes =
          encodeWithoutDirectionCheck(client, capabilities)
      val reference =
          normalManifest(sender = 0, generation = serverGeneration)
      val rosterDigest = reference.rosterCommitment()
      val serverContext =
          V9ManifestValidationContext(
              V9LinkMode.NORMAL,
              guest,
              0,
              guest,
              serverGeneration,
              rosterDigest,
              capabilities,
          )
      val clientContext =
          V9ManifestValidationContext(
              V9LinkMode.NORMAL,
              guest,
              guest,
              0,
              serverGeneration,
              rosterDigest,
              capabilities,
          )
      val decodedServer = V9ManifestCodec.decode(serverBytes, serverContext)
      val decodedClient = V9ManifestCodec.decode(clientBytes, clientContext)
      when (val result = V9ManifestCompatibility.compare(decodedServer, decodedClient, guest)) {
        is V9ManifestComparisonResult.Rejected -> result.reason.wireName
        is V9ManifestComparisonResult.Compatible ->
          if (result.proposals.isEmpty()) "SUCCESS" else "CONSENT_REQUIRED"
      }
    } catch (_: V9ProtocolException) {
      "MANIFEST_MISMATCH"
    }
  }

  /**
   * Produces bytes for hostile direction/tuple tests without weakening production validation.
   * A valid same-sender context is used only for encoding; production decode receives the actual
   * wire context and must reject the spoofed or mismatched value.
   */
  private fun encodeWithoutDirectionCheck(
      manifest: V9Manifest,
      capabilities: Set<V9Capability>,
  ): ByteArray {
    val guest =
        if (manifest.senderPlayer == 0) {
          if (manifest.mode == V9ManifestMode.NORMAL) 1 else 2
        } else {
          manifest.senderPlayer
        }
    val target = if (manifest.senderPlayer == 0) guest else 0
    return V9ManifestCodec.encode(
        manifest,
        V9ManifestValidationContext(
            manifest.mode.linkMode(),
            guest,
            manifest.senderPlayer,
            target,
            manifest.rosterGeneration,
            manifest.rosterCommitment(),
            capabilities,
        ),
    )
  }

  private fun normalManifest(
      sender: Int,
      generation: Long = 1,
      entries: List<V9ManifestEntry> = normalEntries(),
      differences: List<V9ManifestDifference> =
          listOf(V9ManifestDifference(V9ManifestDifferenceCode.MATCH, 1)),
      proposals: List<V9TransferProposal> = emptyList(),
      rosterMaskOverride: Int = 0x03,
      corruptCommitment: Boolean = false,
  ): V9Manifest {
    val mode = V9ManifestMode.NORMAL
    val commitment =
        if (rosterMaskOverride == mode.rosterMask) {
          V9ManifestCodec.rosterCommitment(V9LinkMode.NORMAL, generation)
        } else {
          rosterDigest(mode, rosterMaskOverride, generation)
        }
    val value =
        if (corruptCommitment) {
          V9ManifestDigest(commitment.bytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
        } else {
          commitment
        }
    return V9Manifest(mode, sender, generation, value, entries, differences, proposals)
  }

  private fun fourManifest(
      sender: Int,
      generation: Long = 9L,
      differences: List<V9ManifestDifference> =
          listOf(V9ManifestDifference(V9ManifestDifferenceCode.MATCH, 2)),
  ): V9Manifest {
    return V9Manifest(
        V9ManifestMode.FOUR_PLAYER,
        sender,
        generation,
        V9ManifestCodec.rosterCommitment(V9LinkMode.FOUR_PLAYER, generation),
        (0..3).map { entry(it, it + 1) },
        differences,
        emptyList(),
    )
  }

  private fun normalEntries(): List<V9ManifestEntry> =
      listOf(entry(0, 1), entry(1, 2))

  private fun entry(
      player: Int,
      digestByte: Int,
      primaryPresent: Boolean = true,
      slotDigestByte: Int = 0,
      slotLength: Long = 0,
      battery: Boolean = false,
      profile: String = "dmg",
  ): V9ManifestEntry =
      V9ManifestEntry(
          player,
          primaryPresent,
          slotLength != 0L,
          battery,
          V9ManifestBootstrap.SKIP,
          0,
          profile,
          "PLAYER$player",
          0,
          V9MapperFamily.ROM_ONLY,
          if (primaryPresent) 32_768 else 0,
          slotLength,
          digest(if (primaryPresent) digestByte else 0),
          digest(slotDigestByte),
          digest(0),
          digest(0),
      )

  private fun primaryProposal(
      owner: Int,
      source: Int,
      target: Int,
      digestByte: Int,
      action: V9TransferAction = V9TransferAction.OFFER_BY_SOURCE,
  ): V9TransferProposal =
      proposal(
          41,
          V9TransferAsset.PRIMARY_ROM,
          owner,
          source,
          target,
          32_768,
          digestByte,
          action,
      )

  private fun proposal(
      id: Long,
      asset: V9TransferAsset,
      owner: Int,
      source: Int,
      target: Int,
      size: Long,
      digestByte: Int,
      action: V9TransferAction = V9TransferAction.OFFER_BY_SOURCE,
  ): V9TransferProposal =
      V9TransferProposal(
          id,
          action,
          asset.transferClass,
          asset,
          owner,
          source,
          target,
          size,
          digest(digestByte),
      )

  private fun context(
      manifest: V9Manifest,
      guest: Int,
      wireSource: Int,
      capabilities: Set<V9Capability> = requiredCapabilities(),
      wireTarget: Int = if (wireSource == 0) guest else 0,
  ): V9ManifestValidationContext =
      V9ManifestValidationContext(
          manifest.mode.linkMode(),
          guest,
          wireSource,
          wireTarget,
          manifest.rosterGeneration,
          manifest.rosterCommitment(),
          capabilities,
      )

  private fun requiredCapabilities(): Set<V9Capability> =
      V9Capability.requiredCapabilities

  private fun negotiated(): V9NegotiatedCapabilities =
      V9NegotiatedCapabilities(requiredCapabilities())

  private fun manifestLifecycle(
      role: V9Role,
      target: V9LifecycleState,
      clock: FakeClock,
  ): V9Lifecycle {
    val lifecycle = V9Lifecycle(role, clock)
    if (role == V9Role.SERVER) {
      lifecycle.serverHelloSent()
      lifecycle.clientHelloReceived(negotiated())
      lifecycle.clientAuthReceived()
      lifecycle.serverAuthResultSent()
      if (target == V9LifecycleState.WAIT_CLIENT_MANIFEST) {
        lifecycle.serverManifestSent()
      }
    } else {
      lifecycle.serverHelloReceived()
      lifecycle.clientHelloSent(negotiated())
      lifecycle.clientAuthSent()
      lifecycle.serverAuthResultReceived()
      if (target == V9LifecycleState.SEND_CLIENT_MANIFEST) {
        lifecycle.serverManifestReceived()
      }
    }
    assertEquals(target, lifecycle.snapshot().state)
    return lifecycle
  }

  private fun digest(value: Int): V9ManifestDigest =
      V9ManifestDigest(ByteArray(32) { value.toByte() })

  private fun rosterDigest(
      mode: V9ManifestMode,
      mask: Int,
      generation: Long,
  ): V9ManifestDigest {
    val players = (0..3).filter { mask and (1 shl it) != 0 }
    val label = "CoffeeGB-v9-roster-v2".toByteArray(StandardCharsets.US_ASCII)
    val bytes =
        ByteBuffer.allocate(label.size + 2 + 4 + players.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(label)
            .put(mode.wireId.toByte())
            .put(mask.toByte())
            .putInt(generation.toInt())
            .apply { players.forEach { put(it.toByte()) } }
            .array()
    return V9ManifestDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
  }

  private fun assertManifestMismatch(
      payload: ByteArray,
      context: V9ManifestValidationContext,
      message: String? = null,
  ) {
    val failure =
        assertFailsWith<V9ProtocolException>(message) {
          V9ManifestCodec.decode(payload, context)
        }
    assertEquals(V9ErrorCode.MANIFEST_MISMATCH, failure.reason, message)
  }

  private fun laterTypes(): Set<V9MessageType> =
      V9MessageType.entries.toSet() -
          setOf(
              V9MessageType.HELLO,
              V9MessageType.AUTH,
              V9MessageType.AUTH_RESULT,
              V9MessageType.MANIFEST,
              V9MessageType.ERROR,
          )

  private fun wireTypes(bytes: ByteArray): Set<V9MessageType> {
    val result = linkedSetOf<V9MessageType>()
    var offset = 0
    while (offset < bytes.size) {
      assertTrue(bytes.size - offset >= ProtocolV9.HEADER_BYTES)
      val header =
          ByteBuffer.wrap(bytes, offset, ProtocolV9.HEADER_BYTES)
              .slice()
              .order(ByteOrder.BIG_ENDIAN)
      header.position(8)
      result += requireNotNull(V9MessageType.fromWireId(header.short.toInt() and 0xffff))
      header.position(20)
      val payload = header.int
      offset = Math.addExact(offset, Math.addExact(ProtocolV9.HEADER_BYTES, payload))
    }
    assertEquals(bytes.size, offset)
    return result
  }

  private fun validHeader(type: V9MessageType): ByteArray {
    val flags = type.spec.requiredFlags
    val length = type.spec.minimumDecodedBytes.toInt()
    val channel =
        when (type.spec.channelKind) {
          V9ChannelKind.CONTROL -> ProtocolV9.CONTROL_CHANNEL
          V9ChannelKind.PLAYER -> 1
          V9ChannelKind.GROUP_OR_PLAYER -> ProtocolV9.GROUP_CHANNEL
        }
    return ByteBuffer.allocate(ProtocolV9.HEADER_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(ProtocolV9.MAGIC)
        .put(ProtocolV9.MAJOR.toByte())
        .put(ProtocolV9.MINOR.toByte())
        .putShort(ProtocolV9.HEADER_BYTES.toShort())
        .putShort(type.wireId.toShort())
        .putShort(flags.toShort())
        .putInt(0)
        .putInt(if (flags and V9Flag.RESPONSE.wireMask != 0) 1 else 0)
        .putInt(length)
        .putInt(length)
        .putInt(channel.toInt())
        .put(ByteArray(32))
        .array()
  }

  private fun rows(resource: String): List<Map<String, String>> {
    val lines =
        requireNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { reader ->
              reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            }
    val header = lines.first().split('\t')
    return lines.drop(1).map { line ->
      val values = line.split('\t')
      assertEquals(header.size, values.size, "$resource: $line")
      header.zip(values).toMap()
    }
  }

  private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition()) {
      if (System.nanoTime() >= deadline) throw AssertionError("condition did not become true")
      Thread.yield()
    }
  }

  private fun Map<String, String>.int(name: String): Int = getValue(name).toInt()

  private fun Map<String, String>.long(name: String): Long = getValue(name).toLong()

  private fun Map<String, String>.hexInt(name: String): Int =
      getValue(name).removePrefix("0x").toInt(16)

  private fun Map<String, String>.optionalInt(name: String): Int? =
      getValue(name).takeUnless { it == "-" }?.toInt()

  private fun Map<String, String>.optionalLong(name: String): Long? =
      getValue(name).takeUnless { it == "-" }?.toLong()

  private class FakeClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class RecordingMemoryChannel(private val maximumWrite: Int) : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    private val recorded = mutableListOf<Byte>()
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
}
