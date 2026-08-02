package eu.rekawek.coffeegb.controller.mobile.config

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MobileAdapterConfigurationStoreTest {

  @Test
  fun `configuration owns bytes validates bounds and redacts diagnostics`() {
    val input = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { it.toByte() }
    val configuration = MobileAdapterConfiguration(127, input)
    input[0] = 0x7f
    val detached = configuration.configurationBytes()
    detached[1] = 0

    assertEquals(0, configuration.configurationBytes()[0].toInt() and 0xff)
    assertEquals(1, configuration.configurationBytes()[1].toInt() and 0xff)
    assertEquals(configuration, MobileAdapterConfiguration(127, ByteArray(256) { it.toByte() }))
    assertEquals(configuration.hashCode(), MobileAdapterConfiguration(127, ByteArray(256) { it.toByte() }).hashCode())
    assertEquals(
        "MobileAdapterConfiguration(deviceId=127, configuration=[redacted], networkPolicy=OFFLINE)",
        configuration.toString(),
    )
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(-1, ByteArray(256)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(128, ByteArray(256)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(8, ByteArray(255)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(8, ByteArray(257)) }
  }

  @Test
  fun `custom server policy canonicalizes bounded inputs and redacts diagnostics`() {
    val suppliedMappings =
        mutableListOf(
            MobileAdapterPortMapping(MobileAdapterTransport.UDP, 443, 8443),
            MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8080),
        )
    val suppliedAliases =
        mutableListOf(
            "Zeta.Example-Test.Org",
            "Alt.Example-Test.Org",
        )
    val policy =
        MobileAdapterNetworkPolicy.CustomServer(
            dnsQueryName = "Api.Example-Test.Org",
            resolverIpv4Address = "192.0.2.53",
            resolverPort = 53,
            portMappings = suppliedMappings,
            additionalDnsQueryNames = suppliedAliases,
        )
    val configuration = MobileAdapterConfiguration(8, ByteArray(256), policy)
    suppliedMappings.clear()
    suppliedAliases.clear()

    assertEquals(MobileAdapterNetworkMode.CUSTOM_SERVER, policy.mode)
    assertEquals("api.example-test.org", policy.dnsQueryName)
    assertEquals(
        listOf("alt.example-test.org", "zeta.example-test.org"),
        policy.additionalDnsQueryNames,
    )
    assertEquals("192.0.2.53", policy.resolverIpv4Address)
    assertEquals(MobileAdapterTransport.TCP, policy.portMappings[0].transport)
    assertEquals(MobileAdapterTransport.UDP, policy.portMappings[1].transport)
    assertEquals(2, policy.portMappings.size)
    assertEquals(
        policy,
        MobileAdapterNetworkPolicy.CustomServer(
            "api.example-test.org",
            "192.0.2.53",
            53,
            policy.portMappings.reversed(),
            policy.additionalDnsQueryNames.reversed(),
        ),
    )
    assertEquals(policy.hashCode(), configuration.networkPolicy.hashCode())
    assertFalse(policy.toString().contains("api.example-test.org"))
    assertFalse(policy.toString().contains("alt.example-test.org"))
    assertTrue(policy.toString().contains("additionalDnsQueryNames=2"))
    assertFalse(policy.toString().contains("192.0.2.53"))
    assertFalse(policy.toString().contains("8080"))
    assertFalse(configuration.toString().contains("example-test"))
    assertFailsWith<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (policy.additionalDnsQueryNames as MutableList<String>).add("mutated.example-test.org")
    }
    assertEquals("MobileAdapterPortMapping([redacted])", policy.portMappings.single { it.guestPort == 80 }.toString())
  }

  @Test
  fun `custom server policy rejects invalid DNS IPv4 ports and mapping bounds`() {
    val invalidDnsNames =
        listOf(
            "",
            "a".repeat(64) + ".test",
            listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)).joinToString("."),
            "-host.test",
            "host-.test",
            "host..test",
            "host.test.",
            "host_name.test",
            "host name.test",
            "host\ntest",
            "bücher.test",
            "127.0.0.1",
            "999.999.999.999",
        )
    invalidDnsNames.forEach { dnsName ->
      assertFailsWith<IllegalArgumentException>(dnsName) {
        customPolicy(dnsQueryName = dnsName)
      }
    }

    listOf("", "1.2.3", "1.2.3.4.5", "01.2.3.4", "256.2.3.4", "1.2.3.-1", "localhost")
        .forEach { address ->
          assertFailsWith<IllegalArgumentException>(address) {
            customPolicy(resolverIpv4Address = address)
          }
        }
    listOf(0, 65_536).forEach { port ->
      assertFailsWith<IllegalArgumentException> { customPolicy(resolverPort = port) }
      assertFailsWith<IllegalArgumentException> {
        MobileAdapterPortMapping(MobileAdapterTransport.TCP, port, 1)
      }
      assertFailsWith<IllegalArgumentException> {
        MobileAdapterPortMapping(MobileAdapterTransport.TCP, 1, port)
      }
    }
    assertFailsWith<IllegalArgumentException> {
      customPolicy(
          portMappings =
              (1..17).map {
                MobileAdapterPortMapping(MobileAdapterTransport.TCP, it, it)
              })
    }
    assertFailsWith<IllegalArgumentException> {
      customPolicy(
          portMappings =
              listOf(
                  MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8080),
                  MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8081),
              ))
    }
    assertEquals(
        2,
        customPolicy(
                portMappings =
                    listOf(
                        MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8080),
                        MobileAdapterPortMapping(MobileAdapterTransport.UDP, 80, 8080),
                    ))
            .portMappings
            .size,
    )
    assertEquals(
        MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES,
        customPolicy(
                additionalDnsQueryNames =
                    (1..MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES)
                        .map { "alias$it.example.test" })
            .additionalDnsQueryNames
            .size,
    )
    assertFailsWith<IllegalArgumentException> {
      customPolicy(
          additionalDnsQueryNames =
              (0..MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES)
                  .map { "alias$it.example.test" })
    }
    assertFailsWith<IllegalArgumentException> {
      customPolicy(additionalDnsQueryNames = listOf("ALIAS.example.test", "alias.example.test"))
    }
    assertFailsWith<IllegalArgumentException> {
      customPolicy(additionalDnsQueryNames = listOf("RESOLVER.EXAMPLE.TEST"))
    }
    invalidDnsNames.forEach { dnsName ->
      assertFailsWith<IllegalArgumentException>(dnsName) {
        customPolicy(additionalDnsQueryNames = listOf(dnsName))
      }
    }
  }

  @Test
  fun `codec deterministically writes bounded version three records`() {
    val fallback = MobileAdapterConfiguration.syntheticFallback()
    val first = MobileAdapterConfigurationCodec.encode(fallback)
    val second = MobileAdapterConfigurationCodec.encode(fallback)

    assertEquals(308, first.size)
    assertContentEquals(first, second)
    assertContentEquals("CGBMACFG".toByteArray(StandardCharsets.US_ASCII), first.copyOfRange(0, 8))
    assertEquals(MobileAdapterConfigurationCodec.FORMAT_VERSION, first[8].toInt() and 0xff)
    assertEquals(0x08, first[9].toInt() and 0xff)
    assertEquals(0x4d, first[10].toInt() and 0xff)
    assertEquals(0, first[266].toInt() and 0xff)
    assertEquals(fallback, MobileAdapterConfigurationCodec.decode(first))
    assertEquals(0x4d, fallback.configurationBytes()[0].toInt() and 0xff)
    assertEquals(0x81, fallback.configurationBytes()[2].toInt() and 0xff)
    assertEquals(127, fallback.configurationBytes()[255].toInt() and 0xff)
  }

  @Test
  fun `codec round trips sorted exact aliases and rejects version two alias loss`() {
    val expected =
        configuration(
            8,
            0x35,
            customPolicy(
                portMappings =
                    listOf(MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080)),
                additionalDnsQueryNames =
                    listOf("Zeta.Example.Test", "Alternate.Example.Test"),
            ),
        )

    val encoded = MobileAdapterConfigurationCodec.encode(expected)
    val decoded = MobileAdapterConfigurationCodec.decode(encoded)
    val policy = decoded.networkPolicy as MobileAdapterNetworkPolicy.CustomServer

    assertEquals(MobileAdapterConfigurationCodec.FORMAT_VERSION, encoded[8].toInt() and 0xff)
    assertEquals(expected, decoded)
    assertEquals(
        listOf("alternate.example.test", "zeta.example.test"),
        policy.additionalDnsQueryNames,
    )
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterConfigurationCodec.encodeVersion2(expected)
    }
  }

  @Test
  fun `codec decodes exact version one records as offline and migrates on save`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-v1")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(31, 0x42)
    val legacy = MobileAdapterConfigurationCodec.encodeVersion1(expected)

    assertEquals(MobileAdapterConfigurationCodec.LEGACY_ENCODED_SIZE, legacy.size)
    assertEquals(MobileAdapterConfigurationCodec.LEGACY_FORMAT_VERSION, legacy[8].toInt() and 0xff)
    assertEquals(1, legacy[10].toInt() and 0xff)
    assertEquals(0, legacy[11].toInt() and 0xff)
    assertEquals(expected, MobileAdapterConfigurationCodec.decode(legacy))
    Files.write(path, legacy)

    val store = MobileAdapterConfigurationStore(path)
    val loaded = store.load()
    assertEquals(MobileAdapterConfigurationSource.PERSISTED, loaded.source)
    assertEquals(MobileAdapterNetworkPolicy.Offline, loaded.configuration.networkPolicy)
    assertTrue(store.save(loaded.configuration).saved)
    assertEquals(MobileAdapterConfigurationCodec.FORMAT_VERSION, Files.readAllBytes(path)[8].toInt() and 0xff)
    assertEquals(loaded.configuration, MobileAdapterConfigurationCodec.decode(Files.readAllBytes(path)))
  }

  @Test
  fun `codec decodes exact version two custom records and migrates them without aliases`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-v2")
    val path = directory.resolve("adapter.bin")
    val expected =
        configuration(
            17,
            0x29,
            customPolicy(
                portMappings =
                    listOf(MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080))),
        )
    val version2 = MobileAdapterConfigurationCodec.encodeVersion2(expected)

    assertEquals(
        MobileAdapterConfigurationCodec.VERSION_2_FORMAT_VERSION,
        version2[8].toInt() and 0xff,
    )
    val decoded = MobileAdapterConfigurationCodec.decode(version2)
    assertEquals(expected, decoded)
    assertTrue(
        (decoded.networkPolicy as MobileAdapterNetworkPolicy.CustomServer)
            .additionalDnsQueryNames
            .isEmpty())
    Files.write(path, version2)

    val store = MobileAdapterConfigurationStore(path)
    val loaded = store.load()
    assertEquals(MobileAdapterConfigurationSource.PERSISTED, loaded.source)
    assertTrue(store.save(loaded.configuration).saved)
    val migrated = Files.readAllBytes(path)
    assertEquals(MobileAdapterConfigurationCodec.FORMAT_VERSION, migrated[8].toInt() and 0xff)
    assertEquals(expected, MobileAdapterConfigurationCodec.decode(migrated))
  }

  @Test
  fun `codec accepts maximum DNS and mapping boundaries`() {
    val maximumDnsName =
        listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61))
            .joinToString(".")
    val maximumAliases =
        ('e'..'k').map { character ->
          listOf(
                  character.toString().repeat(63),
                  character.toString().repeat(63),
                  character.toString().repeat(63),
                  character.toString().repeat(61),
              )
              .joinToString(".")
        }
    val mappings =
        (0 until MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS).map { index ->
          MobileAdapterPortMapping(
              MobileAdapterTransport.TCP,
              if (index == 15) 65_535 else index + 1,
              if (index == 0) 65_535 else index,
          )
        }
    val expected =
        configuration(
            127,
            0x7f,
            customPolicy(
                dnsQueryName = maximumDnsName,
                resolverIpv4Address = "255.255.255.255",
                resolverPort = 65_535,
                portMappings = mappings,
                additionalDnsQueryNames = maximumAliases,
            ),
        )

    val encoded = MobileAdapterConfigurationCodec.encode(expected)

    assertEquals(253, maximumDnsName.length)
    assertEquals(
        MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES,
        maximumAliases.size,
    )
    assertTrue(maximumAliases.all { it.length == 253 })
    assertEquals(MobileAdapterConfigurationCodec.MAX_ENCODED_SIZE, encoded.size)
    assertEquals(expected, MobileAdapterConfigurationCodec.decode(encoded))

    val version2Maximum =
        MobileAdapterConfigurationCodec.encodeVersion2(
            configuration(
                127,
                0x43,
                MobileAdapterNetworkPolicy.CustomServer(
                    maximumDnsName,
                    "255.255.255.255",
                    65_535,
                    mappings,
                ),
            ))
    assertEquals(MobileAdapterConfigurationCodec.VERSION_2_MAX_ENCODED_SIZE, version2Maximum.size)
    assertEquals(
        MobileAdapterConfigurationError.MALFORMED_FILE,
        assertFailsWith<MobileAdapterConfigurationFormatException> {
              MobileAdapterConfigurationCodec.decode(version2Maximum.copyOf(version2Maximum.size + 1))
            }
            .error,
    )
  }

  @Test
  fun `codec rejects malformed bounded records and integrity violations`() {
    val offline = MobileAdapterConfigurationCodec.encode(MobileAdapterConfiguration.syntheticFallback())
    val custom =
        MobileAdapterConfigurationCodec.encode(
            configuration(
                8,
                0x22,
                customPolicy(
                    portMappings =
                        listOf(
                            MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8080),
                            MobileAdapterPortMapping(MobileAdapterTransport.UDP, 53, 5353),
                        ),
                    additionalDnsQueryNames = listOf("alternate.example.test"),
                ),
            ))
    val queryNameLength = custom[273].toInt() and 0xff
    val aliasCountOffset = 274 + queryNameLength
    val firstAliasLengthOffset = aliasCountOffset + 1
    val firstAliasOffset = firstAliasLengthOffset + 1
    val firstAliasLength = custom[firstAliasLengthOffset].toInt() and 0xff
    val mappingCountOffset = firstAliasOffset + firstAliasLength
    val mappingsOffset = mappingCountOffset + 1

    assertDecodeError(
        ByteArray(MobileAdapterConfigurationCodec.MIN_ENCODED_SIZE - 1),
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        ByteArray(MobileAdapterConfigurationCodec.MAX_ENCODED_SIZE + 1),
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        offline.clone().also { it[0] = 0 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        offline.clone().also {
          it[8] = (MobileAdapterConfigurationCodec.FORMAT_VERSION + 1).toByte()
        },
        MobileAdapterConfigurationError.UNSUPPORTED_VERSION,
    )
    assertDecodeError(
        mutateAndResign(offline) { it[9] = 0x80.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(MobileAdapterConfigurationCodec.encodeVersion1(configuration(8, 1))) {
          it[10] = 0
          it[11] = 0
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        offline.clone().also { it[10] = (it[10].toInt() xor 1).toByte() },
        MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED,
    )
    assertDecodeError(
        mutateAndResign(offline) { it[266] = 0x02 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(offline) { it[267] = 1 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[274] = 0x80.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[274] = '_'.code.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) {
          it[271] = 0
          it[272] = 0
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) {
          it[aliasCountOffset] =
              (MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES + 1)
                  .toByte()
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[firstAliasLengthOffset] = 0 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[firstAliasOffset] = 0x80.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[firstAliasOffset] = '_'.code.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[mappingCountOffset] = 17 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) { it[mappingsOffset] = 3 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) {
          it[mappingsOffset + 1] = 0
          it[mappingsOffset + 2] = 0
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom) {
          val second = mappingsOffset + 5
          it[second] = it[mappingsOffset]
          it[second + 1] = it[mappingsOffset + 1]
          it[second + 2] = it[mappingsOffset + 2]
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        mutateAndResign(custom.copyOf(custom.size + 1)) {},
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )

    val duplicatePrimary =
        MobileAdapterConfigurationCodec.encode(
            configuration(
                8,
                0x24,
                customPolicy(
                    dnsQueryName = "primary.example.test",
                    additionalDnsQueryNames = listOf("another.example.test"),
                ),
            ))
    val duplicateQueryLength = duplicatePrimary[273].toInt() and 0xff
    val duplicateAliasCountOffset = 274 + duplicateQueryLength
    val duplicateAliasLengthOffset = duplicateAliasCountOffset + 1
    val duplicateAliasOffset = duplicateAliasLengthOffset + 1
    assertEquals(duplicateQueryLength, duplicatePrimary[duplicateAliasLengthOffset].toInt() and 0xff)
    assertDecodeError(
        mutateAndResign(duplicatePrimary) {
          it.copyInto(
              destination = it,
              destinationOffset = duplicateAliasOffset,
              startIndex = 274,
              endIndex = 274 + duplicateQueryLength,
          )
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
  }

  @Test
  fun `missing file uses deterministic synthetic fallback without warning`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-missing")
    val store = MobileAdapterConfigurationStore(directory.resolve("adapter.bin"))

    val first = store.load()
    val second = MobileAdapterConfigurationStore(directory.resolve("other.bin")).load()

    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, first.source)
    assertEquals(null, first.error)
    assertFalse(first.recoveryPerformed)
    assertEquals(MobileAdapterConfiguration.syntheticFallback(), first.configuration)
    assertEquals(first.configuration, second.configuration)
  }

  @Test
  fun `save and load round trip atomically with restrictive final permissions`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-roundtrip")
    val path = directory.resolve("adapter.bin")
    val expected =
        configuration(
            42,
            0x5a,
            customPolicy(
                portMappings =
                    listOf(
                        MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 8080),
                    )),
        )
    val store = MobileAdapterConfigurationStore(path)

    assertEquals(MobileAdapterConfigurationSaveResult(saved = true), store.save(expected))
    assertEquals(MobileAdapterConfigurationCodec.encode(expected).size.toLong(), Files.size(path))
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      assertEquals(
          setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(path),
      )
    }

    val loaded = MobileAdapterConfigurationStore(path).load()
    assertEquals(MobileAdapterConfigurationSource.PERSISTED, loaded.source)
    assertEquals(null, loaded.error)
    assertEquals(expected, loaded.configuration)
  }

  @Test
  fun `corrupt startup data falls back with typed redacted error`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-corrupt")
    val path = directory.resolve("private-account-adapter.bin")
    val hostile = ByteArray(MobileAdapterConfigurationCodec.LEGACY_ENCODED_SIZE)
    val secret = "phone=5551234 token=private /sensitive/account"
    secret.toByteArray(StandardCharsets.UTF_8).copyInto(hostile)
    Files.write(path, hostile)

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, result.source)
    assertEquals(MobileAdapterConfigurationError.MALFORMED_FILE, result.error)
    assertEquals(MobileAdapterConfiguration.syntheticFallback(), result.configuration)
    assertFalse(result.toString().contains("5551234"))
    assertFalse(result.toString().contains("private-account"))
    assertFalse(result.toString().contains(directory.toString()))
    assertFalse(result.error!!.userMessage.contains(path.toString()))
  }

  @Test
  fun `malformed existing record is permission hardened before decode fails`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-private-corrupt")
    val path = directory.resolve("adapter.bin")
    Files.write(path, ByteArray(MobileAdapterConfigurationCodec.LEGACY_ENCODED_SIZE))
    if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
    Files.setPosixFilePermissions(
        path,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        ),
    )

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationError.MALFORMED_FILE, result.error)
    assertEquals(
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(path),
    )
  }

  @Test
  fun `store rejects records outside allocation bounds`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-size-bounds")
    val tooSmall = directory.resolve("small.bin")
    val tooLarge = directory.resolve("large.bin")
    Files.write(tooSmall, ByteArray(MobileAdapterConfigurationCodec.MIN_ENCODED_SIZE - 1))
    Files.write(tooLarge, ByteArray(MobileAdapterConfigurationCodec.MAX_ENCODED_SIZE + 1))

    assertEquals(
        MobileAdapterConfigurationError.MALFORMED_FILE,
        MobileAdapterConfigurationStore(tooSmall).load().error,
    )
    assertEquals(
        MobileAdapterConfigurationError.MALFORMED_FILE,
        MobileAdapterConfigurationStore(tooLarge).load().error,
    )
  }

  @Test
  fun `corrupt reload retains the last good immutable configuration`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-last-good")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(9, 0x33)
    val store = MobileAdapterConfigurationStore(path)
    assertTrue(store.save(expected).saved)
    val corrupt = Files.readAllBytes(path)
    corrupt[12] = (corrupt[12].toInt() xor 1).toByte()
    Files.write(path, corrupt)

    val result = store.load()

    assertEquals(MobileAdapterConfigurationSource.LAST_GOOD, result.source)
    assertEquals(MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED, result.error)
    assertEquals(expected, result.configuration)
    assertEquals(expected, store.current())
  }

  @Test
  fun `failed pre commit replacement preserves persisted and in memory last good values`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-interrupted")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val originalBytes = Files.readAllBytes(path)
    val store = MobileAdapterConfigurationStore(path, FailBeforeWriteWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.STORAGE_WRITE_FAILED, failure.error)
    assertContentEquals(originalBytes, Files.readAllBytes(path))
    assertEquals(original, store.current())
  }

  @Test
  fun `reported post commit failure stays failed and retains last good until retry`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-post-commit")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    val updated = configuration(10, 0x7f)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val store = MobileAdapterConfigurationStore(path, CommitThenFailOnceWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(updated)

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.STORAGE_WRITE_FAILED, failure.error)
    assertContentEquals(MobileAdapterConfigurationCodec.encode(updated), Files.readAllBytes(path))
    assertEquals(original, store.current())

    assertTrue(store.save(updated).saved)
    assertEquals(updated, store.current())
    assertEquals(updated, MobileAdapterConfigurationStore(path).load().configuration)
  }

  @Test
  fun `permission preparation failure cannot commit private replacement`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-permission-failure")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val originalBytes = Files.readAllBytes(path)
    val store = MobileAdapterConfigurationStore(path, PermissionFailingWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED, failure.error)
    assertContentEquals(originalBytes, Files.readAllBytes(path))
    assertEquals(original, store.current())
  }

  @Test
  fun `group writable parent is rejected before a private record is created`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-untrusted-parent")
    assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
    Files.setPosixFilePermissions(
        directory,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
        ),
    )
    val path = directory.resolve("adapter.bin")

    val failure = MobileAdapterConfigurationStore(path).save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED, failure.error)
    assertFalse(Files.exists(path))
  }

  @Test
  fun `atomic writer restores a complete last good backup before decoding`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-backup")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(17, 0x66)
    Files.write(backupPath(path), MobileAdapterConfigurationCodec.encode(expected))

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationSource.RECOVERED_BACKUP, result.source)
    assertTrue(result.recoveryPerformed)
    assertEquals(null, result.error)
    assertEquals(expected, result.configuration)
    assertTrue(Files.isRegularFile(path))
    assertFalse(Files.exists(backupPath(path)))
  }

  @Test
  fun `import rejects private target and transaction artifacts without changing sources`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-import-conflict")
    val target = directory.resolve("adapter.bin")
    val backup = backupPath(target)
    val temp = temporaryTransactionPath(target)
    val sources = listOf(target, backup, temp)
    val originalBytes =
        sources.associateWith { source ->
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { index ->
                (index * 17 + source.fileName.toString().length).toByte()
              }
              .also { Files.write(source, it) }
        }
    val store = MobileAdapterConfigurationStore(target)
    val replacement = configuration(24, 0x62)

    sources.forEach { source ->
      assertEquals(
          MobileAdapterConfigurationError.IMPORT_SOURCE_CONFLICT,
          store.validateImportSource(source),
      )
      val save = store.saveImported(source, replacement)
      assertFalse(save.saved)
      assertEquals(MobileAdapterConfigurationError.IMPORT_SOURCE_CONFLICT, save.error)
      originalBytes.forEach { (path, bytes) ->
        assertTrue(Files.exists(path))
        assertContentEquals(bytes, Files.readAllBytes(path))
      }
    }
  }

  @Test
  fun `import save rechecks a source that becomes a target hard-link alias`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-import-hard-link")
    val target = directory.resolve("adapter.bin")
    val source = directory.resolve("selected.bin")
    val targetBytes = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { it.toByte() }
    Files.write(target, targetBytes)
    Files.write(
        source,
        ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { 0x55.toByte() },
    )
    val store = MobileAdapterConfigurationStore(target)
    assertEquals(null, store.validateImportSource(source))
    Files.delete(source)
    try {
      Files.createLink(source, target)
    } catch (unsupported: UnsupportedOperationException) {
      return
    } catch (denied: IOException) {
      return
    } catch (denied: SecurityException) {
      return
    }

    val save = store.saveImported(source, configuration(25, 0x63))

    assertFalse(save.saved)
    assertEquals(MobileAdapterConfigurationError.IMPORT_SOURCE_CONFLICT, save.error)
    assertContentEquals(targetBytes, Files.readAllBytes(target))
    assertContentEquals(targetBytes, Files.readAllBytes(source))
  }

  @Test
  fun `non regular target is rejected without following a symbolic link`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-symlink")
    val external = directory.resolve("external.bin")
    val externalBytes = MobileAdapterConfigurationCodec.encode(configuration(2, 0x44))
    Files.write(external, externalBytes)
    val path = directory.resolve("adapter.bin")
    try {
      Files.createSymbolicLink(path, external.fileName)
    } catch (unsupported: UnsupportedOperationException) {
      return
    } catch (denied: SecurityException) {
      return
    } catch (denied: IOException) {
      return
    }
    val store = MobileAdapterConfigurationStore(path)

    val load = store.load()
    val save = store.save(configuration(3, 0x55))

    assertEquals(MobileAdapterConfigurationError.NON_REGULAR_FILE, load.error)
    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, load.source)
    assertEquals(MobileAdapterConfigurationError.NON_REGULAR_FILE, save.error)
    assertTrue(Files.isSymbolicLink(path))
    assertContentEquals(externalBytes, Files.readAllBytes(external))
  }

  private fun assertDecodeError(
      encoded: ByteArray,
      expected: MobileAdapterConfigurationError,
  ) {
    val failure =
        assertFailsWith<MobileAdapterConfigurationFormatException> {
          MobileAdapterConfigurationCodec.decode(encoded)
        }
    assertEquals(expected, failure.error)
  }

  private fun configuration(deviceId: Int, seed: Int): MobileAdapterConfiguration =
      configuration(deviceId, seed, MobileAdapterNetworkPolicy.Offline)

  private fun configuration(
      deviceId: Int,
      seed: Int,
      networkPolicy: MobileAdapterNetworkPolicy,
  ): MobileAdapterConfiguration =
      MobileAdapterConfiguration(
          deviceId,
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { index ->
            (seed + index * 31).toByte()
          },
          networkPolicy,
      )

  private fun customPolicy(
      dnsQueryName: String = "resolver.example.test",
      resolverIpv4Address: String = "192.0.2.53",
      resolverPort: Int = 53,
      portMappings: Collection<MobileAdapterPortMapping> = emptyList(),
      additionalDnsQueryNames: Collection<String> = emptyList(),
  ): MobileAdapterNetworkPolicy.CustomServer =
      MobileAdapterNetworkPolicy.CustomServer(
          dnsQueryName,
          resolverIpv4Address,
          resolverPort,
          portMappings,
          additionalDnsQueryNames,
      )

  private fun mutateAndResign(
      original: ByteArray,
      mutation: (ByteArray) -> Unit,
  ): ByteArray {
    val mutated = original.clone()
    mutation(mutated)
    val bodySize = mutated.size - SHA_256_BYTES
    val digest = MessageDigest.getInstance("SHA-256").digest(mutated.copyOfRange(0, bodySize))
    digest.copyInto(mutated, bodySize)
    return mutated
  }

  private fun backupPath(target: Path): Path {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(target.fileName.toString().toByteArray(StandardCharsets.UTF_8))
    val id = (0 until 16).joinToString("") { "%02x".format(digest[it].toInt() and 0xff) }
    return target.parent.resolve(".coffeegb-$id.backup")
  }

  private fun temporaryTransactionPath(target: Path): Path {
    val backupName = backupPath(target).fileName.toString()
    val id = backupName.removePrefix(".coffeegb-").removeSuffix(".backup")
    return target.parent.resolve(".coffeegb-$id.tmp-test.part")
  }

  private class FailBeforeWriteWriter : AtomicFileWriter() {
    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      throw IOException("injected pre-commit replacement failure")
    }
  }

  private class CommitThenFailOnceWriter : AtomicFileWriter() {
    private var fail = true

    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      AtomicFileWriter.system().writeOwnerOnly(target, intendedBytes)
      if (fail) {
        fail = false
        throw IOException("injected post-commit replacement failure")
      }
    }
  }

  private class PermissionFailingWriter : AtomicFileWriter() {
    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      throw AtomicFileWriter.OwnerOnlyPermissionsException(
          "injected private permission preparation failure")
    }
  }

  companion object {
    private const val SHA_256_BYTES = 32
  }
}
