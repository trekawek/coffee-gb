package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationLoadResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSource

/** Immutable, redacted launcher-to-EDT view of the offline Mobile Adapter configuration. */
internal data class MobileAdapterConfigurationUiState(
    val source: MobileAdapterConfigurationSource,
    val deviceId: Int,
    val error: MobileAdapterConfigurationError?,
    val recoveryPerformed: Boolean,
) {
  init {
    require(deviceId in 0..0x7f) { "Mobile Adapter UI device ID must fit in seven bits" }
  }

  fun detailsText(): String =
      buildString {
        appendLine("Mode: deterministic offline (read-only)")
        appendLine("Device ID: 0x${deviceId.toString(16).padStart(2, '0')}")
        appendLine("Source: ${sourceLabel(source)}")
        appendLine("Private configuration: [256 bytes hidden]")
        appendLine(
            when {
              source == MobileAdapterConfigurationSource.RECOVERED_BACKUP ->
                  "Recovery: a complete backup was restored"
              recoveryPerformed -> "Recovery: stale transaction artifacts were cleaned"
              else -> "Recovery: not needed"
            })
        if (error == null) {
          appendLine("Diagnostic: none")
        } else {
          appendLine("Diagnostic: ${error.code} — ${error.userMessage}")
        }
        appendLine("Supported commands: begin, end, reset, and configuration read.")
        appendLine("DNS, TCP, UDP, dialling, and Nintendo services are disabled.")
        append("Editing and network service fields are deferred to the online phase.")
      }

  companion object {
    fun from(result: MobileAdapterConfigurationLoadResult): MobileAdapterConfigurationUiState =
        MobileAdapterConfigurationUiState(
            source = result.source,
            deviceId = result.configuration.deviceId,
            error = result.error,
            recoveryPerformed = result.recoveryPerformed,
        )

    private fun sourceLabel(source: MobileAdapterConfigurationSource): String =
        when (source) {
          MobileAdapterConfigurationSource.PERSISTED -> "validated private record"
          MobileAdapterConfigurationSource.RECOVERED_BACKUP -> "recovered private backup"
          MobileAdapterConfigurationSource.LAST_GOOD -> "last validated in-memory record"
          MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK -> "synthetic offline fallback"
        }
  }
}
