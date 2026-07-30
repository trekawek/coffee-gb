package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationLoadResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSource
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkMode

/** Immutable, redacted launcher-to-EDT view of the Mobile Adapter configuration. */
internal data class MobileAdapterConfigurationUiState(
    val source: MobileAdapterConfigurationSource,
    val deviceId: Int,
    val error: MobileAdapterConfigurationError?,
    val recoveryPerformed: Boolean,
    val networkMode: MobileAdapterNetworkMode = MobileAdapterNetworkMode.OFFLINE,
    val portMappingCount: Int = 0,
) {
  init {
    require(deviceId in 0..0x7f) { "Mobile Adapter UI device ID must fit in seven bits" }
  }

  /** Compact startup-only storage summary safe to render beside the editable private policy. */
  fun startupSummaryText(): String =
      buildString {
        append("Startup source: ${sourceLabel(source)}. ")
        append(recoveryText())
        append(". ")
        if (error == null) {
          append("Startup diagnostic: none.")
        } else {
          append("Startup diagnostic: ${error.code} — ${error.userMessage}")
        }
      }

  fun detailsText(): String =
      buildString {
        appendLine("Mode: ${networkMode.name.lowercase().replace('_', ' ')}")
        appendLine("Device ID: 0x${deviceId.toString(16).padStart(2, '0')}")
        appendLine("Source: ${sourceLabel(source)}")
        appendLine("Private configuration: [256 bytes hidden]")
        appendLine("Custom-server mappings: $portMappingCount (targets hidden)")
        appendLine(recoveryText())
        if (error == null) {
          appendLine("Diagnostic: none")
        } else {
          appendLine("Diagnostic: ${error.code} — ${error.userMessage}")
        }
        appendLine("Supported engine commands include configuration, DNS, TCP, and UDP channels.")
        appendLine("Outbound work requires explicit session consent and an exact saved policy.")
        append("Dial-up and Nintendo production services remain unsupported.")
      }

  private fun recoveryText(): String =
      when {
        source == MobileAdapterConfigurationSource.RECOVERED_BACKUP ->
            "Recovery: a complete backup was restored"
        recoveryPerformed -> "Recovery: stale transaction artifacts were cleaned"
        else -> "Recovery: not needed"
      }

  companion object {
    fun from(result: MobileAdapterConfigurationLoadResult): MobileAdapterConfigurationUiState =
        MobileAdapterConfigurationUiState(
            source = result.source,
            deviceId = result.configuration.deviceId,
            error = result.error,
            recoveryPerformed = result.recoveryPerformed,
            networkMode = result.configuration.networkPolicy.mode,
            portMappingCount =
                (result.configuration.networkPolicy as?
                        eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
                            .CustomServer)
                    ?.portMappings
                    ?.size
                    ?: 0,
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
