package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationLoadResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSource
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
    assertTrue(text.contains("[256 bytes hidden]"))
    assertTrue(text.contains("read-only"))
    assertTrue(text.contains("network service fields are deferred"))
    assertFalse(text.contains("5a5a"))
    assertFalse(text.contains('/'))
    assertFalse(text.contains('\\'))
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

    assertTrue(text.contains("recovered private backup"))
    assertTrue(text.contains("a complete backup was restored"))
    assertTrue(text.contains("Diagnostic: none"))
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

    assertTrue(text.contains("stale transaction artifacts were cleaned"))
    assertFalse(text.contains("backup was restored"))
  }
}
