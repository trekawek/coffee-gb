package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationLoadResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSource
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterPortMapping
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterConfigurationUiStateTest {

  @Test
  fun `presentation includes source device and safe diagnostic but no private bytes`() {
    val secretBytes = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { 0x5a }
    val state =
        MobileAdapterConfigurationUiState.from(
            MobileAdapterConfigurationLoadResult(
                configuration = MobileAdapterConfiguration(0x2a, secretBytes),
                source = MobileAdapterConfigurationSource.LAST_GOOD,
                error = MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED,
            ))

    val text = state.detailsText()
    val startupSummary = state.startupSummaryText()
    val differentPrivateBytes =
        MobileAdapterConfigurationUiState.from(
            MobileAdapterConfigurationLoadResult(
                configuration =
                    MobileAdapterConfiguration(
                        0x2a,
                        ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { 0xa5.toByte() },
                    ),
                source = MobileAdapterConfigurationSource.LAST_GOOD,
                error = MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED,
            ))

    assertTrue(state == differentPrivateBytes)
    assertTrue(text == differentPrivateBytes.detailsText())
    assertTrue(text.contains("Device ID: 0x2a"))
    assertTrue(text.contains("last validated in-memory record"))
    assertTrue(text.contains("INTEGRITY_CHECK_FAILED"))
    assertTrue(text.contains(MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED.userMessage))
    assertTrue(startupSummary.contains("last validated in-memory record"))
    assertTrue(startupSummary.contains("INTEGRITY_CHECK_FAILED"))
    assertTrue(
        startupSummary.contains(
            MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED.userMessage))
    assertTrue(text.contains("[256 bytes hidden]"))
    assertTrue(text.contains("explicit session consent"))
    assertTrue(text.contains("Nintendo production services remain unsupported"))
    assertFalse(text.contains("5a5a"))
    assertFalse(text.contains('/'))
    assertFalse(text.contains('\\'))
    assertFalse(startupSummary.contains("5a5a"))
    assertFalse(startupSummary.contains('/'))
    assertFalse(startupSummary.contains('\\'))
  }

  @Test
  fun `custom policy presentation reveals only mode and mapping count`() {
    val configuration =
        MobileAdapterConfiguration(
            0x08,
            ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE),
            MobileAdapterNetworkPolicy.CustomServer(
                "private-service.example",
                "192.168.10.20",
                5353,
                listOf(MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080)),
            ),
        )
    val text =
        MobileAdapterConfigurationUiState.from(
                MobileAdapterConfigurationLoadResult(
                    configuration,
                    MobileAdapterConfigurationSource.PERSISTED,
                ))
            .detailsText()

    assertTrue(text.contains("custom server"))
    assertTrue(text.contains("Custom-server mappings: 1"))
    assertFalse(text.contains("private-service"))
    assertFalse(text.contains("192.168"))
    assertFalse(text.contains("18080"))
  }

  @Test
  fun `recovered source reports successful recovery without an error`() {
    val state =
        MobileAdapterConfigurationUiState.from(
            MobileAdapterConfigurationLoadResult(
                configuration = MobileAdapterConfiguration.syntheticFallback(),
                source = MobileAdapterConfigurationSource.RECOVERED_BACKUP,
                recoveryPerformed = true,
            ))

    val text = state.detailsText()
    val startupSummary = state.startupSummaryText()

    assertTrue(text.contains("recovered private backup"))
    assertTrue(text.contains("a complete backup was restored"))
    assertTrue(text.contains("Diagnostic: none"))
    assertTrue(startupSummary.contains("a complete backup was restored"))
    assertTrue(startupSummary.contains("Startup diagnostic: none"))
  }

  @Test
  fun `persisted source distinguishes cleanup from a restored backup`() {
    val state =
        MobileAdapterConfigurationUiState.from(
            MobileAdapterConfigurationLoadResult(
                configuration = MobileAdapterConfiguration.syntheticFallback(),
                source = MobileAdapterConfigurationSource.PERSISTED,
                recoveryPerformed = true,
            ))

    val text = state.detailsText()
    val startupSummary = state.startupSummaryText()

    assertTrue(text.contains("stale transaction artifacts were cleaned"))
    assertFalse(text.contains("backup was restored"))
    assertTrue(startupSummary.contains("stale transaction artifacts were cleaned"))
    assertFalse(startupSummary.contains("backup was restored"))
  }
}
