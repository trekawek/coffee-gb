package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.network.v9.V9TransportMetricsSnapshot
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState

/** Whitelist-only bounded diagnostics export. It never receives peer text or payload objects. */
object NetplayDiagnosticsExporter {
  const val MAX_EXPORT_CHARS = 8_192

  fun export(
      transport: V9TransportMetricsSnapshot,
      rollback: NetplayRollbackMetricsSnapshot?,
      includeAddress: Boolean = false,
  ): String {
    val lines = mutableListOf<String>()
    lines += "Coffee GB netplay diagnostics"
    lines += "protocol=v9"
    lines += "role=${if (transport.role.wireId == 1) "server" else "client"}"
    lines += "mode=${if (transport.mode == eu.rekawek.coffeegb.controller.network.v9.V9LinkMode.NORMAL) "normal" else "four-player"}"
    lines += "state=${stableLifecycleId(transport.lifecycle)}"
    lines += "slot=${transport.authenticatedSlot?.toString() ?: "unassigned"}"
    lines += "rtt-current-us=${number(transport.currentRttMicros)}"
    lines += "rtt-ewma-us=${number(transport.ewmaRttMicros)}"
    lines += "rtt-recent-min-us=${number(transport.recentMinimumRttMicros)}"
    lines += "rtt-recent-max-us=${number(transport.recentMaximumRttMicros)}"
    lines += "jitter-us=${number(transport.jitterMicros)}"
    lines += "unanswered-pings=${transport.unansweredPings}"
    lines += "timed-out-pings=${transport.timedOutPings}"
    lines += "bytes-sent=${transport.bytesSent}"
    lines += "bytes-received=${transport.bytesReceived}"
    lines += "duration-ms=${transport.connectionDurationMillis}"
    lines += "local-frame=${number(transport.localFrame)}"
    lines += "remote-frame=${number(transport.remoteFrame)}"
    lines += "newest-remote-input-age-ms=${number(transport.newestRemoteInputAgeMillis)}"
    if (includeAddress) {
      val endpoint = transport.remoteEndpoint
      lines +=
          if (endpoint == null) "remote-address=unavailable"
          else "remote-address=${formatEndpoint(endpoint.numericAddress, endpoint.port)}"
    } else {
      lines += "remote-address=redacted"
    }
    if (rollback != null) {
      lines += "rollback-count=${rollback.rollbackCount}"
      lines += "rollback-last-frames=${rollback.lastFramesRewound}"
      lines += "rollback-max-frames=${rollback.maximumFramesRewound}"
      lines += "rollback-average-milliframes=${rollback.rollingAverageFramesRewoundMilli}"
      lines += "resimulated-frames=${rollback.totalFramesResimulated}"
      lines += "history-entries=${rollback.historyEntries}"
      lines += "history-capacity=${rollback.historyCapacity}"
      lines += "too-old-inputs=${rollback.tooOldInputs}"
      lines += "checkpoint-resyncs=${rollback.checkpointResynchronizations}"
      lines += "rollback-last-reason=${rollback.lastReason.stableId}"
    }
    return lines.joinToString("\n").take(MAX_EXPORT_CHARS)
  }

  private fun number(value: Long?): String = value?.toString() ?: "unavailable"

  private fun formatEndpoint(address: String, port: Int): String =
      if (':' in address) "[$address]:$port" else "$address:$port"

  private fun stableLifecycleId(value: V9LifecycleState): String = when (value) {
    V9LifecycleState.SEND_SERVER_HELLO -> "send-server-hello"
    V9LifecycleState.WAIT_SERVER_HELLO -> "wait-server-hello"
    V9LifecycleState.SEND_CLIENT_HELLO -> "send-client-hello"
    V9LifecycleState.WAIT_CLIENT_HELLO -> "wait-client-hello"
    V9LifecycleState.SEND_AUTH -> "send-auth"
    V9LifecycleState.WAIT_AUTH -> "wait-auth"
    V9LifecycleState.SEND_AUTH_RESULT -> "send-auth-result"
    V9LifecycleState.WAIT_AUTH_RESULT -> "wait-auth-result"
    V9LifecycleState.SEND_SERVER_MANIFEST -> "send-server-manifest"
    V9LifecycleState.WAIT_SERVER_MANIFEST -> "wait-server-manifest"
    V9LifecycleState.SEND_CLIENT_MANIFEST -> "send-client-manifest"
    V9LifecycleState.WAIT_CLIENT_MANIFEST -> "wait-client-manifest"
    V9LifecycleState.EXCHANGE_CONSENT -> "exchange-consent"
    V9LifecycleState.SYNCHRONIZING -> "synchronizing"
    V9LifecycleState.SEND_READY -> "send-ready"
    V9LifecycleState.WAIT_READY -> "wait-ready"
    V9LifecycleState.ACTIVE -> "active"
    V9LifecycleState.BULK_PROGRESS -> "bulk-progress"
    V9LifecycleState.TERMINAL_CLEANUP -> "terminal-cleanup"
    V9LifecycleState.CLOSED -> "closed"
  }
}

/** Defense-in-depth for bounded UI status text; exports above never consume untrusted text. */
object NetplayDiagnosticSanitizer {
  const val MAX_INPUT_CHARS = 4_096
  const val MAX_OUTPUT_CHARS = 1_024

  private val invitation = Regex("(?i)coffeegb://[^\\s]+")
  private val credential = Regex("(?i)[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@")
  private val windowsPath = Regex("(?i)[a-z]:\\\\[^\\s]+")
  private val posixPath = Regex("(?<![a-zA-Z0-9])/(?:[^\\s/]+/)*[^\\s/]+")
  private val ipv4 = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
  private val bracketedIpv6 = Regex("\\[(?=[0-9a-zA-Z:._%-]*:)[0-9a-zA-Z:._%-]+]")
  private val dns = Regex("(?i)(?<![a-z0-9-])(?:[a-z0-9-]+\\.)+[a-z]{2,63}(?![a-z0-9-])")

  fun redact(value: String): String {
    var bounded = value.take(MAX_INPUT_CHARS).map { if (it.code in 0x20..0x7e) it else '?' }
        .joinToString("")
    bounded = invitation.replace(bounded, "[redacted-invitation]")
    bounded = credential.replace(bounded, "[redacted-credentials]")
    bounded = windowsPath.replace(bounded, "[redacted-path]")
    bounded = posixPath.replace(bounded, "[redacted-path]")
    bounded = bracketedIpv6.replace(bounded, "[redacted-address]")
    bounded = ipv4.replace(bounded, "[redacted-address]")
    bounded = dns.replace(bounded, "[redacted-host]")
    return bounded.take(MAX_OUTPUT_CHARS)
  }
}
