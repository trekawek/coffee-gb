package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkMode
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterPortMapping
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.JTextComponent

internal fun showMobileAdapterConfigurationDialog(
    owner: Component,
    coordinator: MobileAdapterConfigurationCoordinator,
    eventBus: EventBus,
    launcherState: MobileAdapterConfigurationUiState,
) {
  check(SwingUtilities.isEventDispatchThread()) {
    "Mobile Adapter configuration must open on the Event Dispatch Thread"
  }
  val snapshot = coordinator.snapshot()
  val custom = snapshot.configuration.networkPolicy as? MobileAdapterNetworkPolicy.CustomServer

  val mode = JComboBox(MobileAdapterNetworkMode.entries.toTypedArray())
  mode.selectedItem = custom?.mode ?: MobileAdapterNetworkMode.OFFLINE
  val queryName = JTextField(custom?.dnsQueryName.orEmpty(), 28)
  val resolverAddress = JTextField(custom?.resolverIpv4Address.orEmpty(), 16)
  val resolverPort = JTextField(custom?.resolverPort?.toString() ?: "53", 6)
  val mappings =
      JTextArea(
          custom
              ?.portMappings
              ?.joinToString("\n") { "${it.transport.name} ${it.guestPort} ${it.targetPort}" }
              .orEmpty(),
          6,
          28,
      )
  installMobileAdapterDocumentLimit(queryName, MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES)
  installMobileAdapterDocumentLimit(resolverAddress, MAX_MOBILE_ADAPTER_IPV4_TEXT_CHARS)
  installMobileAdapterDocumentLimit(resolverPort, MAX_MOBILE_ADAPTER_PORT_TEXT_CHARS)
  installMobileAdapterDocumentLimit(mappings, MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS)
  val networkConsent = JCheckBox("Allow outbound custom-server networking for this session")
  networkConsent.isSelected = snapshot.networkConsent
  val privateLocal = JCheckBox("Development only: allow loopback and private/LAN destinations")
  privateLocal.isSelected = snapshot.privateLocalDevelopment
  val cancelNetwork = JButton("Cancel active network work")
  cancelNetwork.addActionListener {
    eventBus.post(Controller.CancelMobileAdapterNetworkEvent)
  }

  val customFields =
      JPanel(GridLayout(0, 2, 8, 5)).apply {
        border = BorderFactory.createTitledBorder("Custom service policy (saved owner-only)")
        add(JLabel("Mode"))
        add(mode)
        add(JLabel("Allowed DNS name / service"))
        add(queryName)
        add(JLabel("Literal IPv4 DNS resolver"))
        add(resolverAddress)
        add(JLabel("Resolver port"))
        add(resolverPort)
        add(JLabel("Port mappings"))
        add(JScrollPane(mappings))
      }
  val authorization =
      JPanel(GridLayout(0, 1, 0, 5)).apply {
        border = BorderFactory.createTitledBorder("Runtime authorization (never saved)")
        add(networkConsent)
        add(privateLocal)
        add(cancelNetwork)
      }
  val startupSummary =
      JTextArea(launcherState.startupSummaryText(), 3, 44).apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
      }
  val policyNotice =
      JLabel(
          "<html>Only the exact custom service and mapped ports are eligible. " +
              "Nintendo production services, dial-up and listener mode are unsupported.<br>" +
              "Policy changes and every application start reset both runtime permissions. " +
              "Mappings use: <code>TCP|UDP guest-port target-port</code>, one per line.</html>")
  val notice =
      JPanel(BorderLayout(0, 4)).apply {
        add(startupSummary, BorderLayout.NORTH)
        add(policyNotice, BorderLayout.CENTER)
      }
  val panel =
      JPanel(BorderLayout(8, 8)).apply {
        add(notice, BorderLayout.NORTH)
        add(customFields, BorderLayout.CENTER)
        add(authorization, BorderLayout.SOUTH)
      }

  fun updateEnabledState() {
    val customSelected = mode.selectedItem == MobileAdapterNetworkMode.CUSTOM_SERVER
    queryName.isEnabled = customSelected
    resolverAddress.isEnabled = customSelected
    resolverPort.isEnabled = customSelected
    mappings.isEnabled = customSelected
    networkConsent.isEnabled = customSelected
    privateLocal.isEnabled = customSelected && networkConsent.isSelected
    if (!customSelected) {
      networkConsent.isSelected = false
      privateLocal.isSelected = false
    }
    if (!networkConsent.isSelected) privateLocal.isSelected = false
  }
  mode.addActionListener { updateEnabledState() }
  networkConsent.addActionListener { updateEnabledState() }
  updateEnabledState()

  while (true) {
    val choice =
        JOptionPane.showConfirmDialog(
            owner,
            panel,
            "Mobile Adapter GB custom-server configuration",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
    if (choice != JOptionPane.OK_OPTION) return

    val policy =
        try {
          when (mode.selectedItem as MobileAdapterNetworkMode) {
            MobileAdapterNetworkMode.OFFLINE -> MobileAdapterNetworkPolicy.Offline
            MobileAdapterNetworkMode.CUSTOM_SERVER ->
                MobileAdapterNetworkPolicy.CustomServer(
                    queryName.text,
                    resolverAddress.text,
                    parsePort(resolverPort.text, "Resolver port"),
                    parseMobileAdapterMappings(mappings.text),
                )
          }
        } catch (failure: IllegalArgumentException) {
          JOptionPane.showMessageDialog(
              owner,
              failure.message ?: "The custom-server policy is invalid.",
              "Invalid Mobile Adapter policy",
              JOptionPane.ERROR_MESSAGE,
          )
          continue
        }

    if (policy != snapshot.configuration.networkPolicy) {
      coordinator.savePolicy(snapshot.revision, policy, eventBus) { result ->
        SwingUtilities.invokeLater {
          if (!owner.isDisplayable) return@invokeLater
          if (result.saved) {
            JOptionPane.showMessageDialog(
                owner,
                "The owner-only policy was saved. Runtime network permissions were reset; " +
                    "reopen this dialog to grant consent for the current session.",
                "Mobile Adapter policy saved",
                JOptionPane.INFORMATION_MESSAGE,
            )
          } else {
            val error = MobileAdapterConfigurationCoordinator.stableSaveError(result)
            val message =
                if (error == MobileAdapterConfigurationError.CONFIGURATION_STALE) {
                  "${error.code}: ${error.userMessage} No change was saved or applied. Reopen " +
                      "this dialog to review the current policy."
                } else {
                  "${error.code}: ${error.userMessage} The previous policy remains active with " +
                      "runtime network permission revoked."
                }
            JOptionPane.showMessageDialog(
                owner,
                message,
                "Mobile Adapter policy not saved",
                JOptionPane.ERROR_MESSAGE,
            )
          }
        }
      }
      return
    }

    if (networkConsent.isSelected && !snapshot.networkConsent) {
      val consent =
          JOptionPane.showConfirmDialog(
              owner,
              "Allow outbound DNS/TCP/UDP for this session only? Only the saved custom-service " +
                  "policy is eligible; Nintendo services remain unsupported.",
              "Allow Mobile Adapter networking",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE,
          )
      if (consent != JOptionPane.YES_OPTION) continue
    }
    if (privateLocal.isSelected && !snapshot.privateLocalDevelopment) {
      val consent =
          JOptionPane.showConfirmDialog(
              owner,
              "Enable the separate development-only gate for loopback and private/LAN " +
                  "destinations? This can reach services on your computer or local network.",
              "Allow private/LAN development destinations",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE,
          )
      if (consent != JOptionPane.YES_OPTION) continue
    }
    if (!coordinator.applyRuntimeAuthorization(
            snapshot.revision,
            networkConsent.isSelected,
            privateLocal.isSelected,
            eventBus,
        )) {
      JOptionPane.showMessageDialog(
          owner,
          "The custom-server policy changed while this dialog was open. Reopen it to review " +
              "the current policy before granting session permission.",
          "Mobile Adapter policy changed",
          JOptionPane.WARNING_MESSAGE,
      )
    }
    return
  }
}

internal fun parseMobileAdapterMappings(value: String): List<MobileAdapterPortMapping> {
  require(value.length <= MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS) {
    "Port mappings must contain at most $MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS characters."
  }
  val mappings = ArrayList<MobileAdapterPortMapping>(MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS)
  value.lineSequence().forEach { rawLine ->
    require(rawLine.length <= MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS) {
      "Each port mapping must contain at most $MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS characters."
    }
    val line = rawLine.trim()
    if (line.isEmpty()) return@forEach
    require(mappings.size < MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS) {
      "At most ${MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS} port mappings are allowed."
    }
    val index = mappings.size
    val fields = line.split(MOBILE_ADAPTER_MAPPING_WHITESPACE)
    require(fields.size == 3) {
      "Mapping ${index + 1} must use: TCP|UDP guest-port target-port."
    }
    val transport =
        MobileAdapterTransport.entries.singleOrNull { it.name.equals(fields[0], ignoreCase = true) }
            ?: throw IllegalArgumentException("Mapping ${index + 1} must start with TCP or UDP.")
    mappings.add(
        MobileAdapterPortMapping(
            transport,
            parsePort(fields[1], "Mapping ${index + 1} guest port"),
            parsePort(fields[2], "Mapping ${index + 1} target port"),
        ))
  }
  return mappings
}

private fun parsePort(value: String, label: String): Int =
    value
        .takeIf { it.length in 1..5 && it.all { character -> character in '0'..'9' } }
        ?.toIntOrNull()
        ?.takeIf { it in 1..65_535 }
        ?: throw IllegalArgumentException("$label must be a decimal number in 1..65535.")

internal const val MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS = 1_024
internal const val MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS = 64
internal const val MAX_MOBILE_ADAPTER_IPV4_TEXT_CHARS = 15
internal const val MAX_MOBILE_ADAPTER_PORT_TEXT_CHARS = 5
private val MOBILE_ADAPTER_MAPPING_WHITESPACE = Regex("\\s+")

internal fun installMobileAdapterDocumentLimit(component: JTextComponent, maxChars: Int) {
  require(maxChars >= 0) { "Document limit must not be negative" }
  (component.document as AbstractDocument).documentFilter =
      MobileAdapterBoundedDocumentFilter(maxChars)
}

internal class MobileAdapterBoundedDocumentFilter(
    private val maxChars: Int,
) : DocumentFilter() {
  init {
    require(maxChars >= 0) { "Document limit must not be negative" }
  }

  override fun insertString(
      filterBypass: FilterBypass,
      offset: Int,
      string: String?,
      attributeSet: AttributeSet?,
  ) {
    if (retainedLength(filterBypass, 0, string) <= maxChars) {
      super.insertString(filterBypass, offset, string, attributeSet)
    }
  }

  override fun replace(
      filterBypass: FilterBypass,
      offset: Int,
      length: Int,
      text: String?,
      attributes: AttributeSet?,
  ) {
    if (retainedLength(filterBypass, length, text) <= maxChars) {
      super.replace(filterBypass, offset, length, text, attributes)
    }
  }

  private fun retainedLength(
      filterBypass: FilterBypass,
      replacedLength: Int,
      replacement: String?,
  ): Long =
      filterBypass.document.length.toLong() - replacedLength.toLong() +
          (replacement?.length?.toLong() ?: 0L)
}
