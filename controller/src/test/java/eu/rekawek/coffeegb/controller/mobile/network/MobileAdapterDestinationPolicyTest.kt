package eu.rekawek.coffeegb.controller.mobile.network

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterDestinationPolicyTest {

  @Test
  fun `strict IPv4 parser rejects legacy and ambiguous forms`() {
    val accepted = MobileAdapterIpv4Address.parse("203.0.114.9")
    assertEquals(MobileAdapterAddressClass.PUBLIC, classifyMobileAdapterAddress(accepted))

    listOf(
            "",
            "127.1",
            "127.0.0",
            "127.0.0.1.2",
            "0177.0.0.1",
            "0x7f.0.0.1",
            "+127.0.0.1",
            "127.0.0.-1",
            "127.0.0.256",
            "127.0. 0.1",
            "127.0.0.\u0661",
        )
        .forEach { assertFailsWith<IllegalArgumentException>(it) { MobileAdapterIpv4Address.parse(it) } }
  }

  @Test
  fun `classifier gates only loopback and RFC1918 and hard denies special ranges`() {
    listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1")
        .forEach {
          assertEquals(
              MobileAdapterAddressClass.PRIVATE_LOCAL,
              classifyMobileAdapterAddress(MobileAdapterIpv4Address.parse(it)),
              it,
          )
        }
    listOf(
            "0.0.0.0",
            "100.64.0.1",
            "169.254.169.254",
            "192.0.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
        )
        .forEach {
          assertEquals(
              MobileAdapterAddressClass.HARD_DENY,
              classifyMobileAdapterAddress(MobileAdapterIpv4Address.parse(it)),
              it,
          )
        }
    assertEquals(
        MobileAdapterAddressClass.PUBLIC,
        classifyMobileAdapterAddress(MobileAdapterIpv4Address.parse("203.0.114.1")),
    )
  }

  @Test
  fun `network and private development gates are independent and default deny`() {
    val policy = MobileAdapterDestinationPolicy.offline()
    val publicAddress = MobileAdapterIpv4Address.parse("203.0.114.1")
    val privateAddress = MobileAdapterIpv4Address.parse("127.0.0.1")
    val metadata = MobileAdapterIpv4Address.parse("169.254.169.254")

    assertEquals(
        MobileAdapterDestinationDecision.NETWORK_CONSENT_REQUIRED,
        policy.decide(publicAddress, MobileAdapterRuntimeAuthorization.DISABLED),
    )
    assertEquals(
        MobileAdapterDestinationDecision.PRIVATE_LOCAL_CONSENT_REQUIRED,
        policy.decide(privateAddress, MobileAdapterRuntimeAuthorization(true, false)),
    )
    assertEquals(
        MobileAdapterDestinationDecision.ALLOWED,
        policy.decide(privateAddress, MobileAdapterRuntimeAuthorization(true, true)),
    )
    assertEquals(
        MobileAdapterDestinationDecision.HARD_DENIED,
        policy.decide(metadata, MobileAdapterRuntimeAuthorization(true, true)),
    )
    assertEquals(
        MobileAdapterDestinationDecision.HARD_DENIED,
        policy.decide(metadata, MobileAdapterRuntimeAuthorization.DISABLED),
    )
  }

  @Test
  fun `policy freezes exact rules rejects ambiguity and redacts every endpoint`() {
    val target = MobileAdapterTransportTarget.parse("Custom.Example")
    val rules =
        mutableListOf(
            MobileAdapterDestinationRule(
                "Game.Service",
                target,
                MobileAdapterTransportProtocol.TCP,
                80,
                8080,
            ),
            MobileAdapterDestinationRule(
                "game.service",
                target,
                MobileAdapterTransportProtocol.UDP,
                53,
                5353,
            ),
        )
    val policy =
        MobileAdapterDestinationPolicy(
            7,
            MobileAdapterDnsResolver(MobileAdapterIpv4Address.parse("203.0.114.53")),
            rules,
        )
    rules.clear()

    assertEquals(2, policy.rules().size)
    val rendered = policy.toString() + policy.rules().joinToString() + target
    assertFalse(rendered.contains("game.service", ignoreCase = true))
    assertFalse(rendered.contains("custom.example", ignoreCase = true))
    assertFalse(rendered.contains("8080"))
    assertTrue(rendered.contains("[redacted]"))

    assertFailsWith<IllegalArgumentException> {
      MobileAdapterDestinationPolicy(
          8,
          policy.resolver,
          policy.rules() +
              MobileAdapterDestinationRule(
                  "game.service",
                  MobileAdapterTransportTarget.parse("different.example"),
                  MobileAdapterTransportProtocol.TCP,
                  443,
              ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterDestinationPolicy(
          8,
          policy.resolver,
          listOf(
              MobileAdapterDestinationRule(
                  "game.service",
                  target,
                  MobileAdapterTransportProtocol.TCP,
                  80,
                  8080,
              ),
              MobileAdapterDestinationRule(
                  "game.service",
                  target,
                  MobileAdapterTransportProtocol.TCP,
                  80,
                  8081,
              ),
          ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterDestinationPolicy(
          8,
          policy.resolver,
          (0..MobileAdapterDestinationPolicy.MAX_RULES).map {
            MobileAdapterDestinationRule(
                "host$it.example",
                target,
                MobileAdapterTransportProtocol.TCP,
                it + 1,
            )
          },
      )
    }
  }

  @Test
  fun `host and port boundaries are checked before policy publication`() {
    val maxName =
        listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61))
            .joinToString(".")
    assertEquals(253, maxName.length)
    MobileAdapterDestinationRule(
        maxName,
        MobileAdapterTransportTarget.parse("203.0.114.1"),
        MobileAdapterTransportProtocol.TCP,
        1,
        65535,
    )
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterDestinationRule(
          "a".repeat(64) + ".example",
          MobileAdapterTransportTarget.parse("203.0.114.1"),
          MobileAdapterTransportProtocol.TCP,
          1,
      )
    }
    listOf(0, 65536).forEach { port ->
      assertFailsWith<IllegalArgumentException> {
        MobileAdapterDestinationRule(
            "host.example",
            MobileAdapterTransportTarget.parse("203.0.114.1"),
            MobileAdapterTransportProtocol.TCP,
            port,
        )
      }
    }
  }
}
