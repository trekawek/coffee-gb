package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkMode
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterPortMapping
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.IdentityHashMap
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.JTextComponent

/**
 * Compatibility entry point retained for existing menu integrations.
 *
 * The registry is scoped to the owning window, so repeated invocations raise one retained modeless
 * window. New integrations should own a [MobileAdapterConfigurationWindowHost] directly and close
 * it with the rest of the desktop lifecycle.
 */
internal fun showMobileAdapterConfigurationDialog(
    owner: Component,
    coordinator: MobileAdapterConfigurationCoordinator,
    eventBus: EventBus,
    launcherState: MobileAdapterConfigurationUiState,
) {
  requireMobileAdapterEdt("Mobile Adapter configuration opening")
  val window = owner as? Window ?: SwingUtilities.getWindowAncestor(owner)
  checkNotNull(window) { "Mobile Adapter configuration needs an owning window" }
  LegacyMobileAdapterWindowRegistry.show(window, coordinator, eventBus, launcherState)
}

internal data class MobileAdapterMappingDraft(
    val transport: MobileAdapterTransport = MobileAdapterTransport.TCP,
    val guestPort: String = "",
    val targetPort: String = "",
)

internal data class MobileAdapterPolicyDraft(
    val mode: MobileAdapterNetworkMode,
    val dnsQueryName: String,
    val resolverIpv4Address: String,
    val resolverPort: String,
    val portMappings: List<MobileAdapterMappingDraft>,
    val additionalDnsQueryNamesText: String = "",
) {
  companion object {
    fun from(policy: MobileAdapterNetworkPolicy): MobileAdapterPolicyDraft =
        when (policy) {
          MobileAdapterNetworkPolicy.Offline ->
              MobileAdapterPolicyDraft(
                  mode = MobileAdapterNetworkMode.OFFLINE,
                  dnsQueryName = "",
                  resolverIpv4Address = "",
                  resolverPort = "53",
                  portMappings = emptyList(),
              )
          is MobileAdapterNetworkPolicy.CustomServer ->
              MobileAdapterPolicyDraft(
                  mode = MobileAdapterNetworkMode.CUSTOM_SERVER,
                  dnsQueryName = policy.dnsQueryName,
                  resolverIpv4Address = policy.resolverIpv4Address,
                  resolverPort = policy.resolverPort.toString(),
                  portMappings =
                      policy.portMappings.map {
                        MobileAdapterMappingDraft(
                            transport = it.transport,
                            guestPort = it.guestPort.toString(),
                            targetPort = it.targetPort.toString(),
                        )
                      },
                  additionalDnsQueryNamesText = policy.additionalDnsQueryNames.joinToString("\n"),
              )
        }
  }
}

internal enum class MobileAdapterPolicyField {
  DNS_QUERY_NAME,
  ADDITIONAL_DNS_QUERY_NAMES,
  RESOLVER_ADDRESS,
  RESOLVER_PORT,
  PORT_MAPPINGS,
}

internal sealed interface MobileAdapterPolicyValidation {
  data class Valid(val policy: MobileAdapterNetworkPolicy) : MobileAdapterPolicyValidation

  data class Invalid(val message: String, val field: MobileAdapterPolicyField) :
      MobileAdapterPolicyValidation
}

/** Validates the bounded structured draft through the controller's authoritative value types. */
internal fun validateMobileAdapterPolicyDraft(
    draft: MobileAdapterPolicyDraft
): MobileAdapterPolicyValidation {
  if (draft.mode == MobileAdapterNetworkMode.OFFLINE) {
    return MobileAdapterPolicyValidation.Valid(MobileAdapterNetworkPolicy.Offline)
  }
  if (draft.portMappings.size > MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS) {
    return MobileAdapterPolicyValidation.Invalid(
        "At most ${MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS} port mappings are allowed.",
        MobileAdapterPolicyField.PORT_MAPPINGS,
    )
  }
  val basePolicy =
      try {
        val mappings =
            draft.portMappings.mapIndexed { index, mapping ->
              MobileAdapterPortMapping(
                  mapping.transport,
                  parsePort(mapping.guestPort, "Mapping ${index + 1} guest port"),
                  parsePort(mapping.targetPort, "Mapping ${index + 1} target port"),
              )
            }
        MobileAdapterNetworkPolicy.CustomServer(
            draft.dnsQueryName,
            draft.resolverIpv4Address,
            parsePort(draft.resolverPort, "Resolver port"),
            mappings,
        )
      } catch (failure: IllegalArgumentException) {
        val message = failure.message ?: "The custom-server policy is invalid."
        val field =
            when {
              message.startsWith("DNS query name") -> MobileAdapterPolicyField.DNS_QUERY_NAME
              message.startsWith("Resolver address") || message.startsWith("Resolver IPv4") ->
                  MobileAdapterPolicyField.RESOLVER_ADDRESS
              message.startsWith("Resolver port") -> MobileAdapterPolicyField.RESOLVER_PORT
              else -> MobileAdapterPolicyField.PORT_MAPPINGS
            }
        return MobileAdapterPolicyValidation.Invalid(message, field)
      }
  return try {
    val additionalNames =
        parseMobileAdapterAdditionalDnsQueryNames(draft.additionalDnsQueryNamesText)
    MobileAdapterPolicyValidation.Valid(
        MobileAdapterNetworkPolicy.CustomServer(
            basePolicy.dnsQueryName,
            basePolicy.resolverIpv4Address,
            basePolicy.resolverPort,
            basePolicy.portMappings,
            additionalNames,
        ))
  } catch (failure: IllegalArgumentException) {
    MobileAdapterPolicyValidation.Invalid(
        failure.message ?: "The additional DNS query names are invalid.",
        MobileAdapterPolicyField.ADDITIONAL_DNS_QUERY_NAMES,
    )
  }
}

internal enum class MobileAdapterSavePhase {
  IDLE,
  SAVING,
  IMPORTING,
}

internal enum class MobileAdapterStatusTone {
  NEUTRAL,
  SUCCESS,
  WARNING,
  ERROR,
}

internal data class MobileAdapterGuestImagePersistencePresentation(
    val status: String,
    val tone: MobileAdapterStatusTone,
)

internal data class MobileAdapterGuestImagePersistenceCursor(
    val sequence: Long,
    val phase: Controller.MobileAdapterConfigurationPersistencePhase,
)

internal fun presentMobileAdapterGuestImagePersistence(
    phase: Controller.MobileAdapterConfigurationPersistencePhase,
    error: MobileAdapterConfigurationError?,
): MobileAdapterGuestImagePersistencePresentation =
    when (phase) {
      Controller.MobileAdapterConfigurationPersistencePhase.PENDING ->
          MobileAdapterGuestImagePersistencePresentation(
              "Changes received from the emulated Mobile Adapter are active and being saved. Keep Coffee GB open until saving finishes.",
              MobileAdapterStatusTone.WARNING,
          )
      Controller.MobileAdapterConfigurationPersistencePhase.SAVED ->
          MobileAdapterGuestImagePersistencePresentation(
              "Changes received from the emulated Mobile Adapter are active and saved. No action is needed.",
              MobileAdapterStatusTone.SUCCESS,
          )
      Controller.MobileAdapterConfigurationPersistencePhase.SUPERSEDED ->
          MobileAdapterGuestImagePersistencePresentation(
              "Pending changes received from the emulated Mobile Adapter were replaced by the owner-selected adapter image. Review the imported image before continuing.",
              MobileAdapterStatusTone.NEUTRAL,
          )
      Controller.MobileAdapterConfigurationPersistencePhase.FAILED -> {
        val stableError = checkNotNull(error)
        MobileAdapterGuestImagePersistencePresentation(
            "${stableError.code}: ${stableError.userMessage} Changes received from the emulated Mobile Adapter remain active for this session but could not be saved. Check private configuration storage, then retry by closing Coffee GB again.",
            MobileAdapterStatusTone.ERROR,
        )
      }
    }

/**
 * Accepts the globally sequenced guest-image durability stream without carrying its correlation
 * metadata into presentation. A failed write remains retryable, so a later saved/superseded phase
 * for that same sequence must be allowed to replace the warning.
 */
internal fun acceptsMobileAdapterGuestImagePersistence(
    current: MobileAdapterGuestImagePersistenceCursor?,
    sequence: Long,
    phase: Controller.MobileAdapterConfigurationPersistencePhase,
): Boolean {
  if (current == null || sequence > current.sequence) return true
  if (sequence < current.sequence) return false
  return mobileAdapterGuestImagePersistencePhaseOrder(phase) >
      mobileAdapterGuestImagePersistencePhaseOrder(current.phase)
}

private fun mobileAdapterGuestImagePersistencePhaseOrder(
    phase: Controller.MobileAdapterConfigurationPersistencePhase
): Int =
    when (phase) {
      Controller.MobileAdapterConfigurationPersistencePhase.PENDING -> 0
      Controller.MobileAdapterConfigurationPersistencePhase.FAILED -> 1
      Controller.MobileAdapterConfigurationPersistencePhase.SAVED,
      Controller.MobileAdapterConfigurationPersistencePhase.SUPERSEDED -> 2
    }

internal data class MobileAdapterSessionPresentation(
    val summary: String,
    val cancelEnabled: Boolean,
    val tone: MobileAdapterStatusTone = MobileAdapterStatusTone.NEUTRAL,
)

internal fun presentMobileAdapterSession(
    network: MobileAdapterNetworkUiSnapshot?,
    policy: MobileAdapterNetworkPolicy,
    networkConsent: Boolean,
): MobileAdapterSessionPresentation {
  if (network == null) {
    return when {
      policy == MobileAdapterNetworkPolicy.Offline ->
          MobileAdapterSessionPresentation(
              "Offline. The saved policy does not allow custom-server networking.", false)
      !networkConsent ->
          MobileAdapterSessionPresentation(
              "Ready, but custom-server networking is blocked until you allow it for this session.",
              false,
          )
      else -> MobileAdapterSessionPresentation("Ready. No custom-server work is active.", false)
    }
  }

  val connections =
      if (network.activeConnections == 1) "1 active connection"
      else "${network.activeConnections} active connections"
  val slot = network.slot?.let { " Connection slot ${it + 1}." }.orEmpty()
  return when (network.phase) {
    Controller.MobileAdapterNetworkPhase.OFFLINE ->
        MobileAdapterSessionPresentation("Offline. No custom-server work is active.", false)
    Controller.MobileAdapterNetworkPhase.READY ->
        MobileAdapterSessionPresentation("Ready. No custom-server work is active.", false)
    Controller.MobileAdapterNetworkPhase.RESOLVING ->
        MobileAdapterSessionPresentation("Resolving the allowed custom service.$slot", true)
    Controller.MobileAdapterNetworkPhase.CONNECTING ->
        MobileAdapterSessionPresentation("Connecting to the allowed custom service.$slot", true)
    Controller.MobileAdapterNetworkPhase.CONNECTED ->
        MobileAdapterSessionPresentation("Connected. $connections.$slot", true)
    Controller.MobileAdapterNetworkPhase.TRANSFERRING ->
        MobileAdapterSessionPresentation("Transferring custom-service data. $connections.$slot", true)
    Controller.MobileAdapterNetworkPhase.CANCELLING ->
        MobileAdapterSessionPresentation("Cancelling active custom-server work…", false)
    Controller.MobileAdapterNetworkPhase.DISCONNECTED ->
        MobileAdapterSessionPresentation(
            mobileAdapterDisconnectMessage(checkNotNull(network.disconnectReason)),
            false,
            MobileAdapterStatusTone.WARNING,
        )
    Controller.MobileAdapterNetworkPhase.FAILED -> {
      val error = checkNotNull(network.error)
      MobileAdapterSessionPresentation(
          "${error.code}: ${error.userMessage}$slot $connections remain.",
          network.activeConnections > 0,
          MobileAdapterStatusTone.ERROR,
      )
    }
  }
}

private fun mobileAdapterDisconnectMessage(reason: Controller.MobileAdapterDisconnectReason): String =
    when (reason) {
      Controller.MobileAdapterDisconnectReason.USER_CANCELLED ->
          "Custom-server work was cancelled by you."
      Controller.MobileAdapterDisconnectReason.POLICY_CHANGED ->
          "Custom-server work was disconnected because the saved policy changed."
      Controller.MobileAdapterDisconnectReason.PROTOCOL_RESET ->
          "Custom-server work was disconnected by a Mobile Adapter protocol reset."
      Controller.MobileAdapterDisconnectReason.STATE_LOAD ->
          "Custom-server work was disconnected while loading state."
      Controller.MobileAdapterDisconnectReason.REWIND ->
          "Custom-server work was disconnected while rewinding."
      Controller.MobileAdapterDisconnectReason.DETACHED ->
          "Custom-server work was disconnected because the Mobile Adapter was detached."
      Controller.MobileAdapterDisconnectReason.SESSION_STOPPED ->
          "Custom-server work was disconnected because emulation stopped."
      Controller.MobileAdapterDisconnectReason.SHUTDOWN ->
          "Custom-server work was disconnected during shutdown."
    }

internal data class MobileAdapterConfigurationPresentation(
    val revision: Long,
    val launcherSummary: String,
    val baselinePolicy: MobileAdapterNetworkPolicy,
    val draft: MobileAdapterPolicyDraft,
    val validation: MobileAdapterPolicyValidation,
    val policyDirty: Boolean,
    val stale: Boolean,
    val savePhase: MobileAdapterSavePhase,
    val networkConsent: Boolean,
    val privateLocalDevelopment: Boolean,
    val guestImageStatus: String,
    val guestImageStatusTone: MobileAdapterStatusTone,
    val policyStatus: String,
    val policyStatusTone: MobileAdapterStatusTone,
    val session: MobileAdapterSessionPresentation,
) {
  val canEditPolicy: Boolean
    get() = savePhase == MobileAdapterSavePhase.IDLE

  val canSave: Boolean
    get() =
        canEditPolicy && policyDirty && !stale && validation is MobileAdapterPolicyValidation.Valid

  val canImportConfigurationImage: Boolean
    get() = canEditPolicy && !policyDirty && !stale

  val savedCustomPolicy: Boolean
    get() = baselinePolicy is MobileAdapterNetworkPolicy.CustomServer

  val canGrantNetworkConsent: Boolean
    get() = canEditPolicy && !policyDirty && !stale && savedCustomPolicy
}

/** Concise policy/session metadata safe to pass into Preferences or other shell presentation. */
internal data class MobileAdapterConfigurationSummary(
    val mode: MobileAdapterNetworkMode,
    val portMappingCount: Int,
    val networkConsent: Boolean,
    val privateLocalDevelopment: Boolean,
) {
  init {
    require(portMappingCount in 0..MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS) {
      "Mobile Adapter summary mapping count is outside the supported bound"
    }
    require(!privateLocalDevelopment || networkConsent) {
      "Private/LAN permission requires session networking permission"
    }
    require(mode == MobileAdapterNetworkMode.CUSTOM_SERVER || portMappingCount == 0) {
      "An offline Mobile Adapter summary cannot contain custom mappings"
    }
    require(mode == MobileAdapterNetworkMode.CUSTOM_SERVER || !networkConsent) {
      "An offline Mobile Adapter summary cannot authorize custom networking"
    }
  }

  fun preferencesText(): String {
    val policy =
        when (mode) {
          MobileAdapterNetworkMode.OFFLINE -> "Offline"
          MobileAdapterNetworkMode.CUSTOM_SERVER ->
              "Custom Server · $portMappingCount ${if (portMappingCount == 1) "mapping" else "mappings"}"
        }
    val authorization =
        when {
          privateLocalDevelopment -> "networking and private/LAN development allowed for this session"
          networkConsent -> "networking allowed for this session; private/LAN blocked"
          else -> "networking blocked for this session"
        }
    return "$policy · $authorization"
  }
}

internal fun MobileAdapterConfigurationPresentation.redactedSummary():
    MobileAdapterConfigurationSummary {
  val custom = baselinePolicy as? MobileAdapterNetworkPolicy.CustomServer
  return MobileAdapterConfigurationSummary(
      mode = custom?.mode ?: MobileAdapterNetworkMode.OFFLINE,
      portMappingCount = custom?.portMappings?.size ?: 0,
      networkConsent = networkConsent,
      privateLocalDevelopment = privateLocalDevelopment,
  )
}

internal data class MobileAdapterConfigurationWindowActions(
    val draftChanged: (MobileAdapterPolicyDraft) -> Unit,
    val savePolicy: () -> Unit,
    val importConfigurationImage: () -> Unit,
    val reloadPolicy: () -> Unit,
    val setNetworkConsent: (Boolean) -> Unit,
    val setPrivateLocalDevelopment: (Boolean) -> Unit,
    val cancelNetwork: () -> Unit,
    val hide: () -> Unit,
)

internal interface MobileAdapterConfigurationWindowView : AutoCloseable {
  fun render(presentation: MobileAdapterConfigurationPresentation)

  fun showOrRaise()

  fun hide()
}

internal fun interface MobileAdapterConfigurationWindowViewFactory {
  fun create(actions: MobileAdapterConfigurationWindowActions):
      MobileAdapterConfigurationWindowView
}

internal interface MobileAdapterConfigurationDecisionPrompter {
  fun allowSessionNetworking(): Boolean

  fun allowPrivateLocalDevelopment(): Boolean

  fun discardPolicyChanges(): Boolean
}

internal fun interface MobileAdapterConfigurationImageSelector {
  fun select(): Path?
}

/**
 * Owns one retained Mobile Adapter configuration/session window and its revision-aware draft.
 * Controller events are reduced to immutable, privacy-safe presentation values on the EDT.
 */
internal class MobileAdapterConfigurationWindowHost(
    private val coordinator: MobileAdapterConfigurationCoordinator,
    rootEventBus: EventBus,
    private val launcherState: MobileAdapterConfigurationUiState,
    private val viewFactory: MobileAdapterConfigurationWindowViewFactory,
    private val decisions: MobileAdapterConfigurationDecisionPrompter,
    private val onSummary: (MobileAdapterConfigurationSummary) -> Unit = {},
    private val onGuestImagePersistenceFailure: (String) -> Unit = {},
    private val imageSelector: MobileAdapterConfigurationImageSelector =
        MobileAdapterConfigurationImageSelector { null },
) : AutoCloseable {
  private val eventBus = rootEventBus.fork("desktop-mobile-adapter-configuration")
  private var runtime = coordinator.snapshot()
  private var draft = MobileAdapterPolicyDraft.from(runtime.configuration.networkPolicy)
  private var validation = validateMobileAdapterPolicyDraft(draft)
  private var network: MobileAdapterNetworkUiSnapshot? = null
  private var view: MobileAdapterConfigurationWindowView? = null
  private var stale = false
  private var savePhase = MobileAdapterSavePhase.IDLE
  private var policyStatus = "Review saved policy and session-only permissions separately."
  private var policyStatusTone = MobileAdapterStatusTone.NEUTRAL
  private var guestImagePersistence =
      MobileAdapterGuestImagePersistencePresentation(
          "No guest-authored adapter image changes have been received this session.",
          MobileAdapterStatusTone.NEUTRAL,
      )
  private var guestImagePersistenceCursor: MobileAdapterGuestImagePersistenceCursor? = null
  private var closed = false
  private var saveGeneration = 0L

  constructor(
      owner: Window,
      coordinator: MobileAdapterConfigurationCoordinator,
      rootEventBus: EventBus,
      launcherState: MobileAdapterConfigurationUiState,
      initialBounds: Rectangle? = null,
      onBoundsChanged: (Rectangle) -> Unit = {},
      dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
      tokenProvider: () -> DesktopThemeTokens = {
        DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
      },
      onSummary: (MobileAdapterConfigurationSummary) -> Unit = {},
      onGuestImagePersistenceFailure: (String) -> Unit = {},
  ) : this(
      coordinator = coordinator,
      rootEventBus = rootEventBus,
      launcherState = launcherState,
      viewFactory =
          MobileAdapterConfigurationWindowViewFactory { actions ->
            SwingMobileAdapterConfigurationWindow(
                owner,
                actions,
                initialBounds,
                onBoundsChanged,
                tokenProvider(),
            )
          },
      decisions = DesktopMobileAdapterConfigurationDecisionPrompter(owner, dialogFactory),
      onSummary = onSummary,
      onGuestImagePersistenceFailure = onGuestImagePersistenceFailure,
      imageSelector = DesktopMobileAdapterConfigurationImageSelector(owner),
  )

  init {
    requireMobileAdapterEdt("Mobile Adapter window host construction")
    registerNetworkStatus()
    registerGuestImagePersistenceStatus()
    publish()
  }

  fun show() = showOrRaise()

  fun showOrRaise() {
    requireMobileAdapterEdt("Mobile Adapter window opening")
    if (closed) return
    refreshFromCoordinatorIfClean()
    retainedView().showOrRaise()
  }

  internal fun currentPresentation(): MobileAdapterConfigurationPresentation {
    requireMobileAdapterEdt("Mobile Adapter presentation access")
    return presentation()
  }

  /** Does not expose DNS names, resolver addresses, ports, mappings, or private device bytes. */
  internal fun currentSummary(): MobileAdapterConfigurationSummary {
    requireMobileAdapterEdt("Mobile Adapter summary access")
    return presentation().redactedSummary()
  }

  private fun retainedView(): MobileAdapterConfigurationWindowView =
      view
          ?: viewFactory.create(actions()).also { created ->
            created.render(presentation())
            view = created
          }

  private fun actions(): MobileAdapterConfigurationWindowActions =
      MobileAdapterConfigurationWindowActions(
          draftChanged = ::draftChanged,
          savePolicy = ::savePolicy,
          importConfigurationImage = ::importConfigurationImage,
          reloadPolicy = ::reloadPolicy,
          setNetworkConsent = ::setNetworkConsent,
          setPrivateLocalDevelopment = ::setPrivateLocalDevelopment,
          cancelNetwork = ::cancelNetwork,
          hide = ::requestHide,
      )

  private fun draftChanged(next: MobileAdapterPolicyDraft) {
    requireMobileAdapterEdt("Mobile Adapter draft editing")
    if (closed || savePhase != MobileAdapterSavePhase.IDLE) return
    draft = next.detached()
    validation = validateMobileAdapterPolicyDraft(draft)
    if (!stale) {
      policyStatus =
          if (policyDirty()) {
            "Unsaved policy changes. Saving revokes both session permissions and cancels active network work."
          } else {
            "The saved owner-only policy is unchanged."
          }
      policyStatusTone =
          if (policyDirty()) MobileAdapterStatusTone.WARNING else MobileAdapterStatusTone.NEUTRAL
    }
    publish()
  }

  private fun savePolicy() {
    requireMobileAdapterEdt("Mobile Adapter policy saving")
    if (closed || stale || savePhase != MobileAdapterSavePhase.IDLE || !policyDirty()) return
    val policy = (validation as? MobileAdapterPolicyValidation.Valid)?.policy ?: return
    savePhase = MobileAdapterSavePhase.SAVING
    runtime = runtime.copy(networkConsent = false, privateLocalDevelopment = false)
    policyStatus = "Saving the owner-only policy… Session permissions have been revoked."
    policyStatusTone = MobileAdapterStatusTone.NEUTRAL
    val generation = ++saveGeneration
    publish()
    coordinator.savePolicy(runtime.revision, policy, eventBus) { result ->
      dispatchSwingMutation {
        if (closed || generation != saveGeneration) return@dispatchSwingMutation
        savePhase = MobileAdapterSavePhase.IDLE
        runtime = coordinator.snapshot()
        if (result.saved) {
          draft = MobileAdapterPolicyDraft.from(runtime.configuration.networkPolicy)
          validation = validateMobileAdapterPolicyDraft(draft)
          stale = false
          policyStatus =
              "Policy saved. Runtime networking remains blocked until you grant permission for this session."
          policyStatusTone = MobileAdapterStatusTone.SUCCESS
        } else {
          val error = MobileAdapterConfigurationCoordinator.stableSaveError(result)
          stale = error == MobileAdapterConfigurationError.CONFIGURATION_STALE
          policyStatus =
              if (stale) {
                "${error.code}: ${error.userMessage} Your draft was kept. Reload the current policy before saving again."
              } else {
                "${error.code}: ${error.userMessage} Your draft was kept; the previous policy remains active and session permissions are revoked."
              }
          policyStatusTone = MobileAdapterStatusTone.ERROR
        }
        publish()
      }
    }
  }

  private fun importConfigurationImage() {
    requireMobileAdapterEdt("Mobile Adapter image import")
    if (closed || !presentation().canImportConfigurationImage) return
    val source = imageSelector.select() ?: return
    // A native/modal chooser runs a nested EDT. Application shutdown or another presentation
    // transition can complete while it is open, so revalidate before mutating state or calling
    // the coordinator selected before that nested loop.
    if (closed || !presentation().canImportConfigurationImage) return
    savePhase = MobileAdapterSavePhase.IMPORTING
    runtime = runtime.copy(networkConsent = false, privateLocalDevelopment = false)
    policyStatus =
        "Importing the owner-selected adapter image… Session permissions have been revoked."
    policyStatusTone = MobileAdapterStatusTone.NEUTRAL
    val generation = ++saveGeneration
    publish()
    coordinator.importConfigurationImage(runtime.revision, source, eventBus) { result ->
      dispatchSwingMutation {
        if (closed || generation != saveGeneration) return@dispatchSwingMutation
        savePhase = MobileAdapterSavePhase.IDLE
        runtime = coordinator.snapshot()
        draft = MobileAdapterPolicyDraft.from(runtime.configuration.networkPolicy)
        validation = validateMobileAdapterPolicyDraft(draft)
        if (result.saved) {
          stale = false
          policyStatus =
              "Adapter image imported. Library host metadata was ignored, the saved policy was preserved, and runtime networking remains blocked."
          policyStatusTone = MobileAdapterStatusTone.SUCCESS
        } else {
          val error = MobileAdapterConfigurationCoordinator.stableSaveError(result)
          stale = error == MobileAdapterConfigurationError.CONFIGURATION_STALE
          policyStatus =
              if (stale) {
                "${error.code}: ${error.userMessage} Reload the current configuration before importing again."
              } else {
                "${error.code}: ${error.userMessage} The previous adapter image and saved policy remain active; session permissions are revoked."
              }
          policyStatusTone = MobileAdapterStatusTone.ERROR
        }
        publish()
      }
    }
  }

  private fun reloadPolicy() {
    requireMobileAdapterEdt("Mobile Adapter policy reload")
    if (closed || savePhase != MobileAdapterSavePhase.IDLE) return
    loadCurrentPolicy("Current policy reloaded. Unsaved changes were discarded.")
  }

  private fun setNetworkConsent(enabled: Boolean) {
    requireMobileAdapterEdt("Mobile Adapter session authorization")
    if (closed || enabled == runtime.networkConsent) return
    if (enabled && !canGrantAuthorization()) {
      policyStatus =
          "Save or reload the current Custom Server policy before granting session permission."
      policyStatusTone = MobileAdapterStatusTone.WARNING
      publish()
      return
    }
    if (enabled && !decisions.allowSessionNetworking()) {
      policyStatus = "Custom-server networking remains blocked for this session."
      policyStatusTone = MobileAdapterStatusTone.NEUTRAL
      publish()
      return
    }
    applyAuthorization(enabled, if (enabled) runtime.privateLocalDevelopment else false)
  }

  private fun setPrivateLocalDevelopment(enabled: Boolean) {
    requireMobileAdapterEdt("Mobile Adapter private-network authorization")
    if (closed || enabled == runtime.privateLocalDevelopment) return
    if (enabled && (!runtime.networkConsent || !canGrantAuthorization())) {
      policyStatus =
          "Allow custom-server networking on the saved policy before enabling private/LAN destinations."
      policyStatusTone = MobileAdapterStatusTone.WARNING
      publish()
      return
    }
    if (enabled && !decisions.allowPrivateLocalDevelopment()) {
      policyStatus = "Private and LAN destinations remain blocked for this session."
      policyStatusTone = MobileAdapterStatusTone.NEUTRAL
      publish()
      return
    }
    applyAuthorization(runtime.networkConsent, enabled)
  }

  private fun applyAuthorization(networkConsent: Boolean, privateLocalDevelopment: Boolean) {
    val previousPolicy = runtime.configuration.networkPolicy
    val applied =
        try {
          coordinator.applyRuntimeAuthorization(
              runtime.revision,
              networkConsent,
              privateLocalDevelopment,
              eventBus,
          )
        } catch (_: IllegalStateException) {
          false
        }
    val current = coordinator.snapshot()
    if (!applied) {
      runtime = current
      stale = current.configuration.networkPolicy != previousPolicy || policyDirty()
      policyStatus =
          if (stale) {
            "The saved policy changed before session permission could be updated. Reload it before continuing."
          } else {
            "Session permissions changed before this request completed. Review the current values and try again."
          }
      policyStatusTone = MobileAdapterStatusTone.ERROR
    } else {
      runtime = current
      policyStatus = authorizationStatus(current)
      policyStatusTone =
          if (current.networkConsent) MobileAdapterStatusTone.SUCCESS
          else MobileAdapterStatusTone.NEUTRAL
    }
    publish()
  }

  private fun cancelNetwork() {
    requireMobileAdapterEdt("Mobile Adapter network cancellation")
    if (closed || !presentation().session.cancelEnabled) return
    try {
      eventBus.post(Controller.CancelMobileAdapterNetworkEvent)
      policyStatus = "Cancellation requested. The current session status will update when it stops."
      policyStatusTone = MobileAdapterStatusTone.NEUTRAL
    } catch (_: RuntimeException) {
      policyStatus = "Unable to request cancellation because the emulator session is closing."
      policyStatusTone = MobileAdapterStatusTone.ERROR
    }
    publish()
  }

  private fun requestHide() {
    requireMobileAdapterEdt("Mobile Adapter window hiding")
    if (closed) return
    if (savePhase != MobileAdapterSavePhase.IDLE) {
      view?.hide()
      return
    }
    if (policyDirty() && !decisions.discardPolicyChanges()) return
    if (policyDirty()) {
      loadCurrentPolicy("Unsaved policy changes were discarded.")
    }
    view?.hide()
  }

  private fun refreshFromCoordinatorIfClean() {
    if (savePhase != MobileAdapterSavePhase.IDLE || policyDirty()) return
    val current = coordinator.snapshot()
    if (current.revision != runtime.revision) {
      runtime = current
      draft = MobileAdapterPolicyDraft.from(current.configuration.networkPolicy)
      validation = validateMobileAdapterPolicyDraft(draft)
      stale = false
      policyStatus = authorizationStatus(current)
      policyStatusTone = MobileAdapterStatusTone.NEUTRAL
      publish()
    }
  }

  private fun loadCurrentPolicy(message: String) {
    runtime = coordinator.snapshot()
    draft = MobileAdapterPolicyDraft.from(runtime.configuration.networkPolicy)
    validation = validateMobileAdapterPolicyDraft(draft)
    stale = false
    policyStatus = message
    policyStatusTone = MobileAdapterStatusTone.NEUTRAL
    publish()
  }

  private fun canGrantAuthorization(): Boolean =
      savePhase == MobileAdapterSavePhase.IDLE &&
          !policyDirty() &&
          !stale &&
          runtime.configuration.networkPolicy is MobileAdapterNetworkPolicy.CustomServer

  private fun policyDirty(): Boolean =
      (validation as? MobileAdapterPolicyValidation.Valid)?.policy !=
          runtime.configuration.networkPolicy ||
          validation is MobileAdapterPolicyValidation.Invalid

  private fun presentation(): MobileAdapterConfigurationPresentation =
      MobileAdapterConfigurationPresentation(
          revision = runtime.revision,
          launcherSummary = launcherState.startupSummaryText(),
          baselinePolicy = runtime.configuration.networkPolicy,
          draft = draft.detached(),
          validation = validation,
          policyDirty = policyDirty(),
          stale = stale,
          savePhase = savePhase,
          networkConsent = runtime.networkConsent,
          privateLocalDevelopment = runtime.privateLocalDevelopment,
          guestImageStatus = guestImagePersistence.status,
          guestImageStatusTone = guestImagePersistence.tone,
          policyStatus = policyStatus,
          policyStatusTone = policyStatusTone,
          session =
              presentMobileAdapterSession(
                  network?.takeIf { it.policyRevision == runtime.revision },
                  runtime.configuration.networkPolicy,
                  runtime.networkConsent,
              ),
      )

  private fun publish() {
    requireMobileAdapterEdt("Mobile Adapter presentation update")
    val next = presentation()
    view?.render(next)
    onSummary(next.redactedSummary())
  }

  private fun registerNetworkStatus() {
    eventBus.register<Controller.MobileAdapterNetworkStatusEvent> { event ->
      val next = MobileAdapterNetworkUiSnapshot.from(event)
      dispatchSwingMutation {
        if (closed) return@dispatchSwingMutation
        val previous = network
        if (previous == null || next.attachmentId >= previous.attachmentId) {
          network = next
          publish()
        }
      }
    }
  }

  private fun registerGuestImagePersistenceStatus() {
    eventBus.register<Controller.MobileAdapterConfigurationPersistenceStatusEvent> { event ->
      dispatchSwingMutation {
        if (closed ||
            !acceptsMobileAdapterGuestImagePersistence(
                guestImagePersistenceCursor,
                event.sequence,
                event.phase,
            )) {
          return@dispatchSwingMutation
        }
        guestImagePersistenceCursor =
            MobileAdapterGuestImagePersistenceCursor(event.sequence, event.phase)
        guestImagePersistence = presentMobileAdapterGuestImagePersistence(event.phase, event.error)
        publish()
        if (event.phase == Controller.MobileAdapterConfigurationPersistencePhase.FAILED) {
          onGuestImagePersistenceFailure(guestImagePersistence.status)
        }
      }
    }
  }

  override fun close() {
    requireMobileAdapterEdt("Mobile Adapter window disposal")
    if (closed) return
    closed = true
    saveGeneration++
    var failure: Exception? = null
    try {
      eventBus.close()
    } catch (problem: Exception) {
      failure = problem
    }
    try {
      view?.close()
    } catch (problem: Exception) {
      failure?.addSuppressed(problem) ?: run { failure = problem }
    } finally {
      view = null
    }
    failure?.let { throw it }
  }

  private fun authorizationStatus(state: MobileAdapterRuntimeUiState): String =
      when {
        state.privateLocalDevelopment ->
            "Custom-server networking and the separate private/LAN development permission are allowed for this session."
        state.networkConsent ->
            "Custom-server networking is allowed for this session; private and LAN destinations remain blocked."
        else -> "Custom-server networking is blocked for this session."
      }
}

private fun MobileAdapterPolicyDraft.detached(): MobileAdapterPolicyDraft =
    copy(portMappings = portMappings.map(MobileAdapterMappingDraft::copy))

private class DesktopMobileAdapterConfigurationImageSelector(
    private val owner: Window,
) : MobileAdapterConfigurationImageSelector {
  override fun select(): Path? {
    requireMobileAdapterEdt("Mobile Adapter image selection")
    val chooser =
        JFileChooser().apply {
          dialogTitle = "Import Mobile Adapter image"
          approveButtonText = "Import"
          fileSelectionMode = JFileChooser.FILES_ONLY
          isMultiSelectionEnabled = false
        }
    return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
      chooser.selectedFile?.toPath()
    } else {
      null
    }
  }
}

private class DesktopMobileAdapterConfigurationDecisionPrompter(
    private val owner: Window,
    private val dialogs: DesktopDialogFactory,
) : MobileAdapterConfigurationDecisionPrompter {
  override fun allowSessionNetworking(): Boolean =
      dialogs.showDecision(owner, networkConsentSpec()) == MobileAdapterDecision.ALLOW

  override fun allowPrivateLocalDevelopment(): Boolean =
      dialogs.showDecision(owner, privateLocalConsentSpec()) == MobileAdapterDecision.ALLOW

  override fun discardPolicyChanges(): Boolean =
      dialogs.showDecision(owner, discardChangesSpec()) == MobileAdapterDecision.DISCARD

  private fun networkConsentSpec() =
      DesktopDecisionSpec(
          title = "Allow Mobile Adapter networking",
          heading = "Allow custom-server networking for this session?",
          message =
              "Coffee GB may use outbound DNS, TCP, and UDP only through the exact saved custom-service policy. " +
                  "Nintendo production services remain unsupported. This permission is kept in memory and resets when the policy changes or Coffee GB restarts.",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Allow networking",
                          MobileAdapterDecision.ALLOW,
                          mnemonic = KeyEvent.VK_A,
                      ),
                  cancel =
                      DesktopDialogAction(
                          "Keep blocked",
                          MobileAdapterDecision.KEEP_BLOCKED,
                          mnemonic = KeyEvent.VK_K,
                      ),
                  defaultButton = DesktopDialogDefaultButton.CANCEL,
              ),
      )

  private fun privateLocalConsentSpec() =
      DesktopDecisionSpec(
          title = "Allow private and LAN destinations",
          heading = "Enable the development-only private-network permission?",
          message =
              "This separate permission can reach services on your computer or local network through the saved custom-service policy. It is kept in memory and resets when the policy changes or Coffee GB restarts.",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Allow private and LAN",
                          MobileAdapterDecision.ALLOW,
                          mnemonic = KeyEvent.VK_A,
                      ),
                  cancel =
                      DesktopDialogAction(
                          "Keep blocked",
                          MobileAdapterDecision.KEEP_BLOCKED,
                          mnemonic = KeyEvent.VK_K,
                      ),
                  defaultButton = DesktopDialogDefaultButton.CANCEL,
              ),
      )

  private fun discardChangesSpec() =
      DesktopDecisionSpec(
          title = "Discard Mobile Adapter policy changes",
          heading = "Discard unsaved policy changes?",
          message =
              "The saved owner-only policy and current session permissions will remain unchanged.",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Discard changes",
                          MobileAdapterDecision.DISCARD,
                          mnemonic = KeyEvent.VK_D,
                          destructive = true,
                      ),
                  cancel =
                      DesktopDialogAction(
                          "Keep editing",
                          MobileAdapterDecision.KEEP_EDITING,
                          mnemonic = KeyEvent.VK_K,
                      ),
                  defaultButton = DesktopDialogDefaultButton.CANCEL,
              ),
      )

  private enum class MobileAdapterDecision {
    ALLOW,
    KEEP_BLOCKED,
    DISCARD,
    KEEP_EDITING,
  }
}

private class SwingMobileAdapterConfigurationWindow(
    owner: Window,
    actions: MobileAdapterConfigurationWindowActions,
    initialBounds: Rectangle?,
    private val onBoundsChanged: (Rectangle) -> Unit,
    initialTokens: DesktopThemeTokens,
) : MobileAdapterConfigurationWindowView {
  private val dialog =
      JDialog(owner, "Mobile Adapter Configuration — Coffee GB", Dialog.ModalityType.MODELESS)
  private val panel = MobileAdapterConfigurationPanel(actions, initialTokens)
  private var positioned = initialBounds != null

  init {
    requireMobileAdapterEdt("Mobile Adapter Swing window construction")
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.minimumSize = Dimension(680, 590)
    dialog.preferredSize = Dimension(760, 700)
    dialog.contentPane = panel
    dialog.accessibleContext.accessibleName = "Mobile Adapter Configuration"
    dialog.accessibleContext.accessibleDescription =
        "Private Mobile Adapter image import, saved custom-service policy, current session permissions, and network status"
    dialog.rootPane
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), HIDE_ACTION)
    dialog.rootPane.actionMap.put(
        HIDE_ACTION,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) = actions.hide()
        },
    )
    dialog.pack()
    initialBounds?.let { dialog.bounds = Rectangle(it) }
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentMoved(event: ComponentEvent) = publishBounds()

          override fun componentResized(event: ComponentEvent) = publishBounds()

          override fun componentHidden(event: ComponentEvent) = publishBounds()
        })
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = actions.hide()
        })
  }

  override fun render(presentation: MobileAdapterConfigurationPresentation) {
    requireMobileAdapterEdt("Mobile Adapter Swing window rendering")
    panel.render(presentation)
  }

  override fun showOrRaise() {
    requireMobileAdapterEdt("Mobile Adapter Swing window opening")
    if (!positioned) {
      dialog.setLocationRelativeTo(dialog.owner)
      positioned = true
    }
    dialog.isVisible = true
    dialog.toFront()
    dialog.requestFocus()
  }

  override fun hide() {
    requireMobileAdapterEdt("Mobile Adapter Swing window hiding")
    publishBounds()
    dialog.isVisible = false
  }

  override fun close() {
    requireMobileAdapterEdt("Mobile Adapter Swing window disposal")
    publishBounds()
    dialog.dispose()
  }

  private fun publishBounds() {
    if (dialog.width > 0 && dialog.height > 0) onBoundsChanged(Rectangle(dialog.bounds))
  }

  private companion object {
    const val HIDE_ACTION = "coffee-gb.mobile-adapter.hide"
  }
}

/** Headless-testable content of the retained Mobile Adapter window. */
internal class MobileAdapterConfigurationPanel(
    private val actions: MobileAdapterConfigurationWindowActions,
    initialTokens: DesktopThemeTokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM),
) : JPanel(BorderLayout(0, initialTokens.spacing.section)), DesktopThemeRefreshHook {
  internal val startupSummary = mobileAdapterLiteralText("", 3, "Mobile Adapter startup summary")
  internal val offlineMode = JRadioButton("Offline")
  internal val customServerMode = JRadioButton("Custom Server")
  internal val queryName = JTextField(28)
  internal val additionalDnsQueryNames =
      JTextArea(3, 28).apply {
        lineWrap = false
        wrapStyleWord = false
      }
  internal val resolverAddress = JTextField(16)
  internal val resolverPort = JTextField(6)
  internal val mappingModel = MobileAdapterMappingTableModel()
  internal val mappingsTable = JTable(mappingModel)
  internal val addMappingButton = JButton("Add mapping")
  internal val removeMappingButton = JButton("Remove")
  internal val mappingCount = JLabel()
  internal val validationStatus = mobileAdapterLiteralText("", 2, "Policy validation")
  internal val networkConsent = JCheckBox("Allow custom-server networking for this session")
  internal val privateLocal =
      JCheckBox("Development only: allow loopback and private/LAN destinations")
  internal val sessionStatus = mobileAdapterLiteralText("", 3, "Current session status")
  internal val cancelNetwork = JButton("Cancel active network work")
  internal val guestImageStatus =
      mobileAdapterLiteralText("", 3, "Guest-authored adapter image persistence status")
  internal val policyStatus = mobileAdapterLiteralText("", 3, "Mobile Adapter policy status")
  internal val importImageButton = JButton("Import adapter image…")
  internal val reloadButton = JButton("Reload policy")
  internal val closeButton = JButton("Close")
  internal val saveButton = JButton("Save changes")

  private val customFields = JPanel(GridBagLayout())
  private val customCardLayout = CardLayout()
  private val customCard = JPanel(customCardLayout)
  private val imageSection = JPanel(BorderLayout(0, initialTokens.spacing.related))
  private val policySection = JPanel(BorderLayout(0, initialTokens.spacing.related))
  private val sessionSection = JPanel(BorderLayout(0, initialTokens.spacing.related))
  private val footer = JPanel(BorderLayout(initialTokens.spacing.related, 0))
  private val buttonBar = JPanel(FlowLayout(FlowLayout.TRAILING, initialTokens.spacing.related, 0))
  private var rendering = false
  private var current = initialPresentation()
  private var tokens = initialTokens

  init {
    requireMobileAdapterEdt("Mobile Adapter panel construction")
    getAccessibleContext().accessibleName = "Mobile Adapter configuration and session"
    installLimits()
    configureAccessibility()
    configureMappingTable()
    configureListeners()

    val body = JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
    body.add(createHeader())
    body.add(javax.swing.Box.createVerticalStrut(initialTokens.spacing.section))
    body.add(createAdapterImageSection())
    body.add(javax.swing.Box.createVerticalStrut(initialTokens.spacing.section))
    body.add(createPolicySection())
    body.add(javax.swing.Box.createVerticalStrut(initialTokens.spacing.section))
    body.add(createSessionSection())
    val scroll = JScrollPane(body).apply { border = null }
    add(scroll, BorderLayout.CENTER)

    reloadButton.addActionListener { actions.reloadPolicy() }
    closeButton.addActionListener { actions.hide() }
    saveButton.addActionListener { actions.savePolicy() }
    buttonBar.add(reloadButton)
    buttonBar.add(closeButton)
    buttonBar.add(saveButton)
    footer.add(policyStatus, BorderLayout.CENTER)
    footer.add(buttonBar, BorderLayout.SOUTH)
    add(footer, BorderLayout.SOUTH)

    desktopThemeChanged(initialTokens)
    render(current)
  }

  internal fun render(next: MobileAdapterConfigurationPresentation) {
    requireMobileAdapterEdt("Mobile Adapter panel rendering")
    rendering = true
    try {
      if (collectDraft() != next.draft) applyDraft(next.draft)
      current = next
      startupSummary.text = next.launcherSummary
      val custom = next.draft.mode == MobileAdapterNetworkMode.CUSTOM_SERVER
      offlineMode.isSelected = !custom
      customServerMode.isSelected = custom
      customCardLayout.show(customCard, if (custom) CUSTOM_CARD else OFFLINE_CARD)
      setPolicyFieldsEnabled(next.canEditPolicy && custom)
      val validationMessage = (next.validation as? MobileAdapterPolicyValidation.Invalid)?.message
      validationStatus.text = validationMessage.orEmpty()
      validationStatus.isVisible = !validationMessage.isNullOrBlank()
      validationStatus.accessibleContext.accessibleDescription = validationMessage.orEmpty()
      mappingCount.text =
          "${next.draft.portMappings.size} of ${MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS} mappings"
      addMappingButton.isEnabled =
          next.canEditPolicy &&
              custom &&
              next.draft.portMappings.size < MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS
      removeMappingButton.isEnabled =
          next.canEditPolicy && custom && mappingsTable.selectedRow >= 0

      networkConsent.isSelected = next.networkConsent
      privateLocal.isSelected = next.privateLocalDevelopment
      networkConsent.isEnabled =
          next.savedCustomPolicy &&
              next.savePhase == MobileAdapterSavePhase.IDLE &&
              (next.canGrantNetworkConsent || next.networkConsent)
      privateLocal.isEnabled =
          next.networkConsent &&
              next.savePhase == MobileAdapterSavePhase.IDLE &&
              (next.canGrantNetworkConsent || next.privateLocalDevelopment)
      sessionStatus.text = next.session.summary
      sessionStatus.accessibleContext.accessibleDescription = next.session.summary
      cancelNetwork.isEnabled = next.session.cancelEnabled

      guestImageStatus.text = next.guestImageStatus
      guestImageStatus.accessibleContext.accessibleDescription = next.guestImageStatus
      policyStatus.text = next.policyStatus
      policyStatus.accessibleContext.accessibleDescription = next.policyStatus
      importImageButton.text =
          if (next.savePhase == MobileAdapterSavePhase.IMPORTING) {
            "Importing…"
          } else {
            "Import adapter image…"
          }
      importImageButton.isEnabled = next.canImportConfigurationImage
      reloadButton.isVisible = next.stale
      reloadButton.isEnabled = next.savePhase == MobileAdapterSavePhase.IDLE
      saveButton.text =
          if (next.savePhase == MobileAdapterSavePhase.SAVING) "Saving…" else "Save changes"
      saveButton.isEnabled = next.canSave
      applyStatusColors()
    } finally {
      rendering = false
    }
    revalidate()
    repaint()
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    requireMobileAdapterEdt("Mobile Adapter theme refresh")
    this.tokens = tokens
    background = tokens.surface
    border =
        BorderFactory.createEmptyBorder(
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
        )
    listOf(imageSection, policySection, sessionSection).forEach { section ->
      section.background = tokens.surface
      section.border =
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(tokens.border),
              BorderFactory.createEmptyBorder(
                  tokens.spacing.related,
                  tokens.spacing.related,
                  tokens.spacing.related,
                  tokens.spacing.related,
              ),
          )
    }
    customFields.background = tokens.surface
    customCard.background = tokens.surface
    footer.background = tokens.surface
    buttonBar.background = tokens.surface
    mappingsTable.gridColor = tokens.border
    mappingsTable.selectionBackground = tokens.focus
    mappingsTable.selectionForeground = contrastingMobileAdapterText(tokens.focus)
    policyStatus.background = tokens.surface
    guestImageStatus.background = tokens.surface
    sessionStatus.background = tokens.surface
    startupSummary.background = tokens.surface
    validationStatus.background = tokens.surface
    startupSummary.foreground = tokens.secondaryText
    saveButton.background = tokens.accent
    saveButton.foreground = tokens.onAccent
    saveButton.isOpaque = true
    listOf(importImageButton, closeButton, reloadButton).forEach { button ->
      button.background = tokens.elevatedSurface
      button.foreground = tokens.primaryText
      button.isOpaque = true
    }
    applyStatusColors()
  }

  private fun createHeader(): JPanel =
      JPanel(BorderLayout(0, tokens.spacing.related)).apply {
        val heading = JLabel("Mobile Adapter Configuration")
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 4f)
        heading.accessibleContext.accessibleName = "Mobile Adapter Configuration"
        val notice =
            mobileAdapterLiteralText(
                "The saved owner-only policy limits eligible custom services. Nintendo production services, dial-up, and listener mode are unsupported. Saving policy never grants network permission.",
                3,
                "Mobile Adapter policy notice",
            )
        add(heading, BorderLayout.NORTH)
        val prose = JPanel(BorderLayout(0, tokens.spacing.compact))
        prose.add(notice, BorderLayout.NORTH)
        prose.add(startupSummary, BorderLayout.CENTER)
        add(prose, BorderLayout.CENTER)
      }

  private fun createAdapterImageSection(): JPanel {
    val heading = JLabel("Adapter image")
    heading.font = heading.font.deriveFont(Font.BOLD)
    val explanation =
        mobileAdapterLiteralText(
            "Import an exact 256-byte adapter image or a validated 512-byte REON/libmobile file. The file may contain dial-up or account data. Coffee GB stores only the 256-byte game-visible image; library DNS, relay, token, and other host metadata is ignored. Importing preserves the saved policy, revokes both session permissions, and never grants networking.",
            4,
            "Mobile Adapter image import explanation",
        )
    val buttonRow = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    buttonRow.add(importImageButton)
    val actionsAndStatus = JPanel(BorderLayout(0, tokens.spacing.related))
    actionsAndStatus.add(buttonRow, BorderLayout.NORTH)
    actionsAndStatus.add(guestImageStatus, BorderLayout.CENTER)
    val content = JPanel(BorderLayout(0, tokens.spacing.related))
    content.add(explanation, BorderLayout.CENTER)
    content.add(actionsAndStatus, BorderLayout.SOUTH)
    imageSection.add(heading, BorderLayout.NORTH)
    imageSection.add(content, BorderLayout.CENTER)
    return imageSection
  }

  private fun createPolicySection(): JPanel {
    val heading = JLabel("Saved policy")
    heading.font = heading.font.deriveFont(Font.BOLD)
    val modePanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    ButtonGroup().apply {
      add(offlineMode)
      add(customServerMode)
    }
    modePanel.add(offlineMode)
    modePanel.add(customServerMode)
    val modeRow = JPanel(BorderLayout(tokens.spacing.controlGap, 0))
    val modeLabel = JLabel("Mode").apply { labelFor = offlineMode }
    modeRow.add(modeLabel, BorderLayout.WEST)
    modeRow.add(modePanel, BorderLayout.CENTER)

    addFormRow(customFields, 0, "Primary DNS name / service", queryName)
    val aliases =
        JPanel(BorderLayout(0, tokens.spacing.compact)).apply {
          add(JScrollPane(additionalDnsQueryNames), BorderLayout.CENTER)
          add(
              mobileAdapterLiteralText(
                  "Optional: one exact DNS name per line, up to " +
                      "${MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES}. " +
                      "Empty lines are ignored. All names share the resolver and port mappings below.",
                  2,
                  "Additional DNS names help",
              ),
              BorderLayout.SOUTH,
          )
        }
    addFormRow(
        customFields,
        1,
        "Additional exact DNS names",
        aliases,
        additionalDnsQueryNames,
    )
    addFormRow(customFields, 2, "Literal IPv4 DNS resolver", resolverAddress)
    addFormRow(customFields, 3, "Resolver port", resolverPort)
    val mappings = createMappingsPanel()
    val customContent = JPanel(BorderLayout(0, tokens.spacing.related))
    customContent.add(customFields, BorderLayout.NORTH)
    customContent.add(mappings, BorderLayout.CENTER)
    customCard.add(
        mobileAdapterLiteralText(
            "Offline keeps the deterministic Mobile Adapter protocol available without outbound custom-server networking.",
            2,
            "Offline mode explanation",
        ),
        OFFLINE_CARD,
    )
    customCard.add(customContent, CUSTOM_CARD)

    val center = JPanel(BorderLayout(0, tokens.spacing.related))
    center.add(modeRow, BorderLayout.NORTH)
    center.add(customCard, BorderLayout.CENTER)
    policySection.add(heading, BorderLayout.NORTH)
    policySection.add(center, BorderLayout.CENTER)
    policySection.add(validationStatus, BorderLayout.SOUTH)
    return policySection
  }

  private fun createMappingsPanel(): JPanel {
    val header = JPanel(BorderLayout())
    val label = JLabel("Port mappings").apply { labelFor = mappingsTable }
    header.add(label, BorderLayout.WEST)
    header.add(mappingCount, BorderLayout.EAST)
    val buttons = JPanel(FlowLayout(FlowLayout.LEADING, tokens.spacing.related, 0))
    buttons.add(addMappingButton)
    buttons.add(removeMappingButton)
    return JPanel(BorderLayout(0, tokens.spacing.compact)).apply {
      add(header, BorderLayout.NORTH)
      add(JScrollPane(mappingsTable), BorderLayout.CENTER)
      add(buttons, BorderLayout.SOUTH)
    }
  }

  private fun createSessionSection(): JPanel {
    val heading = JLabel("Current session")
    heading.font = heading.font.deriveFont(Font.BOLD)
    val explanation =
        mobileAdapterLiteralText(
            "Session permissions are kept in memory, apply only to the exact saved policy, and reset after a policy change or application restart.",
            3,
            "Session permission explanation",
        )
    val authorization = JPanel(GridBagLayout())
    val full = GridBagConstraints().apply {
      gridx = 0
      weightx = 1.0
      fill = GridBagConstraints.HORIZONTAL
      anchor = GridBagConstraints.LINE_START
    }
    authorization.add(networkConsent, full.copyAt(0))
    authorization.add(privateLocal, full.copyAt(1))
    authorization.add(JSeparator(), full.copyAt(2).apply { insets = Insets(6, 0, 6, 0) })
    authorization.add(sessionStatus, full.copyAt(3))
    val cancelRow = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    cancelRow.add(cancelNetwork)
    authorization.add(cancelRow, full.copyAt(4))
    val content = JPanel(BorderLayout(0, tokens.spacing.related))
    content.add(explanation, BorderLayout.NORTH)
    content.add(authorization, BorderLayout.CENTER)
    sessionSection.add(heading, BorderLayout.NORTH)
    sessionSection.add(content, BorderLayout.CENTER)
    return sessionSection
  }

  private fun configureListeners() {
    offlineMode.addActionListener { if (!rendering) publishDraft() }
    customServerMode.addActionListener { if (!rendering) publishDraft() }
    listOf(queryName, additionalDnsQueryNames, resolverAddress, resolverPort).forEach { field ->
      field.document.addDocumentListener(
          object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = publishDraft()

            override fun removeUpdate(event: DocumentEvent) = publishDraft()

            override fun changedUpdate(event: DocumentEvent) = publishDraft()
          })
    }
    mappingModel.onChanged = ::publishDraft
    mappingsTable.selectionModel.addListSelectionListener {
      if (!it.valueIsAdjusting) {
        removeMappingButton.isEnabled =
            current.canEditPolicy &&
                current.draft.mode == MobileAdapterNetworkMode.CUSTOM_SERVER &&
                mappingsTable.selectedRow >= 0
      }
    }
    addMappingButton.addActionListener {
      if (mappingModel.rowCount >= MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS) {
        return@addActionListener
      }
      val nextPort = mappingModel.nextAvailableGuestPort()
      mappingModel.add(MobileAdapterMappingDraft(guestPort = nextPort, targetPort = nextPort))
      val row = mappingModel.rowCount - 1
      mappingsTable.setRowSelectionInterval(row, row)
      mappingsTable.editCellAt(row, 1)
      mappingsTable.editorComponent?.requestFocusInWindow()
    }
    removeMappingButton.addActionListener {
      val row = mappingsTable.selectedRow
      if (row >= 0) mappingModel.remove(row)
    }
    networkConsent.addActionListener {
      if (!rendering) actions.setNetworkConsent(networkConsent.isSelected)
    }
    privateLocal.addActionListener {
      if (!rendering) actions.setPrivateLocalDevelopment(privateLocal.isSelected)
    }
    cancelNetwork.addActionListener { actions.cancelNetwork() }
    importImageButton.addActionListener { actions.importConfigurationImage() }
  }

  private fun configureMappingTable() {
    mappingsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    mappingsTable.fillsViewportHeight = true
    mappingsTable.putClientProperty("terminateEditOnFocusLost", true)
    mappingsTable.accessibleContext.accessibleName = "Mobile Adapter port mappings"
    mappingsTable.accessibleContext.accessibleDescription =
        "Transport, guest port, and target port for each allowed mapping"
    mappingsTable.columnModel.getColumn(0).cellEditor =
        javax.swing.DefaultCellEditor(JComboBox(MobileAdapterTransport.entries.toTypedArray()))
    val portEditor = JTextField()
    installMobileAdapterDocumentLimit(portEditor, MAX_MOBILE_ADAPTER_PORT_TEXT_CHARS)
    mappingsTable.columnModel.getColumn(1).cellEditor = javax.swing.DefaultCellEditor(portEditor)
    val targetEditor = JTextField()
    installMobileAdapterDocumentLimit(targetEditor, MAX_MOBILE_ADAPTER_PORT_TEXT_CHARS)
    mappingsTable.columnModel.getColumn(2).cellEditor = javax.swing.DefaultCellEditor(targetEditor)
    val literalRenderer =
        object : DefaultTableCellRenderer() {
          override fun getTableCellRendererComponent(
              table: JTable,
              value: Any?,
              isSelected: Boolean,
              hasFocus: Boolean,
              row: Int,
              column: Int,
          ): Component =
              (super.getTableCellRendererComponent(
                      table, value, isSelected, hasFocus, row, column)
                  as JLabel)
                  .apply { putClientProperty("html.disable", true) }
        }
    (0 until mappingsTable.columnCount).forEach { column ->
      mappingsTable.columnModel.getColumn(column).cellRenderer = literalRenderer
    }
  }

  private fun configureAccessibility() {
    queryName.accessibleContext.accessibleName = "Primary DNS name or service"
    queryName.accessibleContext.accessibleDescription =
        "The primary exact saved custom-service DNS name eligible for Mobile Adapter requests"
    additionalDnsQueryNames.accessibleContext.accessibleName = "Additional exact DNS names"
    additionalDnsQueryNames.accessibleContext.accessibleDescription =
        "Optional exact saved DNS names, one per line, sharing the same resolver and port mappings"
    resolverAddress.accessibleContext.accessibleName = "Literal IPv4 DNS resolver"
    resolverAddress.accessibleContext.accessibleDescription =
        "Canonical dotted-decimal IPv4 address for the saved custom DNS resolver"
    resolverPort.accessibleContext.accessibleName = "DNS resolver port"
    resolverPort.accessibleContext.accessibleDescription = "Decimal port from 1 through 65535"
    offlineMode.accessibleContext.accessibleDescription =
        "Disable outbound Mobile Adapter custom-server networking in the saved policy"
    customServerMode.accessibleContext.accessibleDescription =
        "Configure a primary exact custom service, optional exact aliases, and bounded port mappings"
    networkConsent.accessibleContext.accessibleDescription =
        "Grant or revoke the memory-only policy-wide custom-server permission"
    privateLocal.accessibleContext.accessibleDescription =
        "Grant or revoke the separate development-only private and LAN permission"
    addMappingButton.accessibleContext.accessibleDescription =
        "Add one bounded TCP or UDP port mapping"
    removeMappingButton.accessibleContext.accessibleDescription =
        "Remove the selected port mapping"
    cancelNetwork.accessibleContext.accessibleDescription =
        "Cancel current Mobile Adapter host work without detaching the serial endpoint"
    importImageButton.accessibleContext.accessibleDescription =
        "Select and privately import a 256-byte adapter image or validated 512-byte REON/libmobile file"
    saveButton.accessibleContext.accessibleDescription =
        "Save the owner-only policy and revoke runtime network permissions"
  }

  private fun installLimits() {
    installMobileAdapterDocumentLimit(
        queryName, MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES)
    installMobileAdapterDocumentLimit(
        additionalDnsQueryNames, MAX_MOBILE_ADAPTER_ADDITIONAL_DNS_QUERY_NAMES_TEXT_CHARS)
    installMobileAdapterDocumentLimit(resolverAddress, MAX_MOBILE_ADAPTER_IPV4_TEXT_CHARS)
    installMobileAdapterDocumentLimit(resolverPort, MAX_MOBILE_ADAPTER_PORT_TEXT_CHARS)
  }

  private fun publishDraft() {
    if (!rendering) actions.draftChanged(collectDraft())
  }

  private fun collectDraft(): MobileAdapterPolicyDraft =
      MobileAdapterPolicyDraft(
          mode =
              if (customServerMode.isSelected) MobileAdapterNetworkMode.CUSTOM_SERVER
              else MobileAdapterNetworkMode.OFFLINE,
          dnsQueryName = queryName.text,
          resolverIpv4Address = resolverAddress.text,
          resolverPort = resolverPort.text,
          portMappings = mappingModel.snapshot(),
          additionalDnsQueryNamesText = additionalDnsQueryNames.text,
      )

  private fun applyDraft(draft: MobileAdapterPolicyDraft) {
    offlineMode.isSelected = draft.mode == MobileAdapterNetworkMode.OFFLINE
    customServerMode.isSelected = draft.mode == MobileAdapterNetworkMode.CUSTOM_SERVER
    queryName.text = draft.dnsQueryName
    additionalDnsQueryNames.text = draft.additionalDnsQueryNamesText
    resolverAddress.text = draft.resolverIpv4Address
    resolverPort.text = draft.resolverPort
    mappingModel.replace(draft.portMappings)
  }

  private fun setPolicyFieldsEnabled(enabled: Boolean) {
    listOf(queryName, additionalDnsQueryNames, resolverAddress, resolverPort, mappingsTable)
        .forEach { it.isEnabled = enabled }
    offlineMode.isEnabled = current.savePhase == MobileAdapterSavePhase.IDLE
    customServerMode.isEnabled = current.savePhase == MobileAdapterSavePhase.IDLE
  }

  private fun addFormRow(
      panel: JPanel,
      row: Int,
      text: String,
      field: JComponent,
      labelTarget: JComponent = field,
  ) {
    val label = JLabel(text)
    label.labelFor = labelTarget
    panel.add(
        label,
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          anchor = GridBagConstraints.LINE_START
          insets = Insets(4, 0, 4, tokens.spacing.controlGap)
        },
    )
    panel.add(
        field,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(4, 0, 4, 0)
        },
    )
  }

  private fun applyStatusColors() {
    validationStatus.foreground = tokens.danger
    guestImageStatus.foreground = colorFor(current.guestImageStatusTone)
    policyStatus.foreground = colorFor(current.policyStatusTone)
    sessionStatus.foreground = colorFor(current.session.tone)
  }

  private fun colorFor(tone: MobileAdapterStatusTone): Color =
      when (tone) {
        MobileAdapterStatusTone.NEUTRAL -> tokens.secondaryText
        MobileAdapterStatusTone.SUCCESS -> tokens.success
        MobileAdapterStatusTone.WARNING -> tokens.warning
        MobileAdapterStatusTone.ERROR -> tokens.danger
      }

  private companion object {
    const val OFFLINE_CARD = "offline"
    const val CUSTOM_CARD = "custom"

    fun initialPresentation(): MobileAdapterConfigurationPresentation {
      val policy = MobileAdapterNetworkPolicy.Offline
      val draft = MobileAdapterPolicyDraft.from(policy)
      return MobileAdapterConfigurationPresentation(
          revision = 0,
          launcherSummary = "",
          baselinePolicy = policy,
          draft = draft,
          validation = MobileAdapterPolicyValidation.Valid(policy),
          policyDirty = false,
          stale = false,
          savePhase = MobileAdapterSavePhase.IDLE,
          networkConsent = false,
          privateLocalDevelopment = false,
          guestImageStatus =
              "No guest-authored adapter image changes have been received this session.",
          guestImageStatusTone = MobileAdapterStatusTone.NEUTRAL,
          policyStatus = "",
          policyStatusTone = MobileAdapterStatusTone.NEUTRAL,
          session = MobileAdapterSessionPresentation("", false),
      )
    }
  }
}

internal class MobileAdapterMappingTableModel : AbstractTableModel() {
  private val rows = mutableListOf<MobileAdapterMappingDraft>()
  var onChanged: () -> Unit = {}

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = 3

  override fun getColumnName(column: Int): String =
      when (column) {
        0 -> "Transport"
        1 -> "Guest port"
        2 -> "Target port"
        else -> error("Unknown Mobile Adapter mapping column $column")
      }

  override fun getColumnClass(columnIndex: Int): Class<*> =
      if (columnIndex == 0) MobileAdapterTransport::class.java else String::class.java

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
      when (columnIndex) {
        0 -> rows[rowIndex].transport
        1 -> rows[rowIndex].guestPort
        2 -> rows[rowIndex].targetPort
        else -> error("Unknown Mobile Adapter mapping column $columnIndex")
      }

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    val before = rows[rowIndex]
    val after =
        when (columnIndex) {
          0 -> before.copy(transport = value as MobileAdapterTransport)
          1 -> before.copy(guestPort = value?.toString().orEmpty())
          2 -> before.copy(targetPort = value?.toString().orEmpty())
          else -> error("Unknown Mobile Adapter mapping column $columnIndex")
        }
    if (before == after) return
    rows[rowIndex] = after
    fireTableCellUpdated(rowIndex, columnIndex)
    onChanged()
  }

  fun snapshot(): List<MobileAdapterMappingDraft> = rows.map(MobileAdapterMappingDraft::copy)

  fun replace(next: List<MobileAdapterMappingDraft>) {
    rows.clear()
    rows.addAll(next.map(MobileAdapterMappingDraft::copy))
    fireTableDataChanged()
  }

  fun add(mapping: MobileAdapterMappingDraft) {
    require(rows.size < MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS) {
      "The Mobile Adapter mapping table is full"
    }
    rows.add(mapping.copy())
    val index = rows.lastIndex
    fireTableRowsInserted(index, index)
    onChanged()
  }

  fun remove(index: Int) {
    rows.removeAt(index)
    fireTableRowsDeleted(index, index)
    onChanged()
  }

  fun nextAvailableGuestPort(): String {
    val used = rows.mapNotNull { it.guestPort.toIntOrNull() }.toSet()
    return (1..65_535).first { it !in used }.toString()
  }
}

private object LegacyMobileAdapterWindowRegistry {
  private val entries = IdentityHashMap<Window, MobileAdapterConfigurationWindowHost>()

  fun show(
      owner: Window,
      coordinator: MobileAdapterConfigurationCoordinator,
      eventBus: EventBus,
      launcherState: MobileAdapterConfigurationUiState,
  ) {
    val existing = entries[owner]
    if (existing != null) {
      existing.showOrRaise()
      return
    }
    lateinit var host: MobileAdapterConfigurationWindowHost
    val listener =
        object : WindowAdapter() {
          override fun windowClosed(event: WindowEvent) {
            entries.remove(owner)?.close()
            owner.removeWindowListener(this)
          }
        }
    host =
        MobileAdapterConfigurationWindowHost(
            owner,
            coordinator,
            eventBus,
            launcherState,
        )
    entries[owner] = host
    owner.addWindowListener(listener)
    host.showOrRaise()
  }
}

private fun mobileAdapterLiteralText(text: String, rows: Int, accessibleName: String): JTextArea =
    JTextArea(text, rows, 54).apply {
      isEditable = false
      isFocusable = false
      lineWrap = true
      wrapStyleWord = true
      isOpaque = false
      border = null
      putClientProperty("html.disable", true)
      getAccessibleContext().accessibleName = accessibleName
      getAccessibleContext().accessibleDescription = text
    }

private fun GridBagConstraints.copyAt(row: Int): GridBagConstraints =
    clone().let { it as GridBagConstraints }.apply { gridy = row }

private fun contrastingMobileAdapterText(background: Color): Color =
    if (desktopContrastRatio(Color.BLACK, background) >=
        desktopContrastRatio(Color.WHITE, background)) {
      Color.BLACK
    } else {
      Color.WHITE
    }

private fun requireMobileAdapterEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) {
    "$operation must run on the Event Dispatch Thread"
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

internal fun parseMobileAdapterAdditionalDnsQueryNames(value: String): List<String> {
  require(value.length <= MAX_MOBILE_ADAPTER_ADDITIONAL_DNS_QUERY_NAMES_TEXT_CHARS) {
    "Additional DNS query names must contain at most " +
        "$MAX_MOBILE_ADAPTER_ADDITIONAL_DNS_QUERY_NAMES_TEXT_CHARS characters."
  }
  val names =
      ArrayList<String>(MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES)
  value.lineSequence().forEach { name ->
    if (name.isEmpty()) return@forEach
    require(name.length <= MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES) {
      "Each additional DNS query name must contain at most " +
          "${MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES} characters."
    }
    require(
        names.size < MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES) {
          "At most ${MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES} " +
              "additional DNS query names are allowed."
        }
    names.add(name)
  }
  return names
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
internal const val MAX_MOBILE_ADAPTER_ADDITIONAL_DNS_QUERY_NAMES_TEXT_CHARS =
    MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES *
        (MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES + 1)
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
