package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.link.LinkMode
import kotlin.test.assertEquals
import org.junit.Test

class NetplayFooterSummaryTest {

  @Test
  fun `footer identifies the host and each client while retaining the connection phase`() {
    assertEquals("Netplay: Off", netplaySummary(presentNetplay(NetplayUiState())))

    assertEquals(
        "Netplay: Host — Hosting",
        netplaySummary(
            presentNetplay(
                NetplayUiState(
                    phase = NetplayPhase.WAITING_FOR_PEERS,
                    role = NetplayRole.HOST,
                    mode = LinkMode.FOUR_PLAYER_ADAPTER,
                    localPlayer = 0,
                ))),
    )
    assertEquals(
        "Netplay: Client — Connecting",
        netplaySummary(
            presentNetplay(
                NetplayUiState(
                    phase = NetplayPhase.CONNECTING,
                    role = NetplayRole.CLIENT,
                    endpoint = NetplayV8Endpoint.create("localhost", NETPLAY_V8_PORT, "localhost"),
                ))),
    )
    assertEquals(
        "Netplay: Client 4 — Active",
        netplaySummary(
            presentNetplay(
                NetplayUiState(
                    phase = NetplayPhase.ACTIVE,
                    role = NetplayRole.CLIENT,
                    mode = LinkMode.FOUR_PLAYER_ADAPTER,
                    localPlayer = 3,
                ))),
    )
  }
}
