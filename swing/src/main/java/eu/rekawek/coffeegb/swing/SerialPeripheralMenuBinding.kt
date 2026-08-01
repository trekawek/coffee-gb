package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralError
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatus
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import java.util.Collections
import java.util.EnumMap
import javax.swing.ButtonGroup
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingUtilities

/** Immutable, presentation-safe view of link-port ownership and attachment. */
internal data class SerialPeripheralUiSnapshot(
    val selection: SerialPeripheralSelection,
    val statusSelection: SerialPeripheralSelection,
    val status: SerialPeripheralStatus,
    val error: SerialPeripheralError? = null,
) {
  init {
    require((status == SerialPeripheralStatus.UNAVAILABLE) == (error != null)) {
      "Unavailable serial UI status and typed error must be present together"
    }
  }
}

/** Latest generation-correlated, presentation-safe Mobile Adapter backend status. */
internal data class MobileAdapterNetworkUiSnapshot(
    val attachmentId: Long,
    val policyRevision: Long,
    val phase: Controller.MobileAdapterNetworkPhase,
    val slot: Int?,
    val activeConnections: Int,
    val error: Controller.MobileAdapterNetworkError?,
    val disconnectReason: Controller.MobileAdapterDisconnectReason?,
) {
  companion object {
    fun from(event: Controller.MobileAdapterNetworkStatusEvent): MobileAdapterNetworkUiSnapshot =
        MobileAdapterNetworkUiSnapshot(
            event.attachmentId,
            event.policyRevision,
            event.phase,
            event.slot,
            event.activeConnections,
            event.error,
            event.disconnectReason,
        )
  }
}

/** Closed set of lifecycle-only prerequisites allowed before a standalone port selection. */
internal enum class SerialPeripheralTransitionPrerequisite {
  STOP_SERVER,
  STOP_CLIENT,
  ENSURE_STANDALONE_CONTROLLER;

  fun event(): Event =
      when (this) {
        STOP_SERVER -> StopServerEvent()
        STOP_CLIENT -> StopClientEvent()
        ENSURE_STANDALONE_CONTROLLER -> EnsureStandaloneControllerEvent()
      }
}

/** Pure production policy; in particular it cannot create a network-start event. */
internal fun serialPeripheralTransitionPrerequisites(
    selection: SerialPeripheralSelection,
    serverSelected: Boolean,
    clientSelected: Boolean,
    linkedControllerActive: Boolean,
): List<SerialPeripheralTransitionPrerequisite> {
  if (selection == SerialPeripheralSelection.PEER_TO_PEER) return emptyList()
  return buildList {
    if (serverSelected) add(SerialPeripheralTransitionPrerequisite.STOP_SERVER)
    if (clientSelected) add(SerialPeripheralTransitionPrerequisite.STOP_CLIENT)
    if (linkedControllerActive) {
      add(SerialPeripheralTransitionPrerequisite.ENSURE_STANDALONE_CONTROLLER)
    }
  }
}

/**
 * Owns the exclusive Swing radio group for the standalone serial port.
 *
 * This adapter can post only the closed stop/ownership prerequisites above followed by
 * [Controller.SetSerialPeripheralEvent]. Selecting the offline Mobile Adapter cannot start DNS,
 * TCP, UDP, a listener, or a netplay action. Controller callbacks are always copied into an
 * immutable snapshot and applied on the EDT.
 */
internal class SerialPeripheralMenuBinding(
    private val eventBus: EventBus,
    initialSelection: SerialPeripheralSelection = SerialPeripheralSelection.PEER_TO_PEER,
    private val transitionPrerequisites:
        (SerialPeripheralSelection) -> List<SerialPeripheralTransitionPrerequisite> = {
          emptyList()
        },
    private val onRendered: (SerialPeripheralUiSnapshot) -> Unit = {},
) {
  val menu = JMenu("Link-port device")
  val statusItem = JMenuItem()
  val items: Map<SerialPeripheralSelection, JRadioButtonMenuItem>

  @Volatile
  private var current =
      SerialPeripheralUiSnapshot(
          selection = initialSelection,
          statusSelection = initialSelection,
          status = SerialPeripheralStatus.DETACHED,
      )

  @Volatile private var mobileNetwork: MobileAdapterNetworkUiSnapshot? = null

  init {
    val group = ButtonGroup()
    val mutableItems = EnumMap<SerialPeripheralSelection, JRadioButtonMenuItem>(SerialPeripheralSelection::class.java)
    DISPLAY_ORDER.forEach { selection ->
      val item = JRadioButtonMenuItem(label(selection), selection == initialSelection)
      item.accessibleContext.accessibleDescription = description(selection)
      item.addActionListener {
        check(SwingUtilities.isEventDispatchThread()) {
          "Serial peripheral choices must be made on the Event Dispatch Thread"
        }
        val prerequisites = transitionPrerequisites(selection).toList()
        if (prerequisites.isEmpty()) {
          postSelectionWithoutOwnershipRollback(selection)
        } else {
          // A netplay stop can flush/persist a linked controller. Preserve event ordering while
          // keeping that bounded lifecycle wait off Swing's event-dispatch thread.
          menu.isEnabled = false
          Thread(
                  {
                    try {
                      var prerequisitesCompleted = true
                      try {
                        prerequisites.map { it.event() }.forEach(eventBus::post)
                      } catch (_: RuntimeException) {
                        // A linked-controller stop may fail at its persistence barrier. Its own UI
                        // reports that failure; restore this radio group with a bounded typed
                        // ownership result and never expose the exception text.
                        postPrerequisiteFailure(selection)
                        prerequisitesCompleted = false
                      }
                      // A Set subscriber may commit controller ownership before a later subscriber
                      // throws. Never reinterpret that ambiguous notification failure as a failed
                      // prerequisite or roll the already-committed radio state back.
                      if (prerequisitesCompleted) {
                        postSelectionWithoutOwnershipRollback(selection)
                      }
                    } finally {
                      SwingUtilities.invokeLater { menu.isEnabled = true }
                    }
                  },
                  "coffee-gb-serial-peripheral-transition",
              )
              .apply {
                isDaemon = true
                start()
              }
        }
      }
      group.add(item)
      menu.add(item)
      mutableItems[selection] = item
    }
    items = Collections.unmodifiableMap(mutableItems)
    menu.addSeparator()
    statusItem.isEnabled = false
    menu.add(statusItem)
    render(current)

    eventBus.register<Controller.SerialPeripheralSelectionChangedEvent> { event ->
      dispatchSwingMutation {
        val previous = current
        if (event.selection != SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
          mobileNetwork = null
        }
        current =
            SerialPeripheralUiSnapshot(
                selection = event.selection,
                statusSelection = event.selection,
                status = SerialPeripheralStatus.DETACHED,
            )
        items.getValue(event.selection).isSelected = true
        if (previous != current) render(current)
      }
    }
    eventBus.register<Controller.SerialPeripheralStatusEvent> { event ->
      dispatchSwingMutation {
        if (event.status == SerialPeripheralStatus.UNAVAILABLE) {
          // A failed preparation/handoff leaves the controller's prior owner committed. Swing's
          // radio model selects on click, so explicitly roll that optimistic visual state back.
          items.getValue(current.selection).isSelected = true
        }
        current =
            SerialPeripheralUiSnapshot(
                selection = current.selection,
                statusSelection = event.selection,
                status = event.status,
                error = event.error,
            )
        render(current)
      }
    }
    eventBus.register<Controller.MobileAdapterNetworkStatusEvent> { event ->
      val detached = MobileAdapterNetworkUiSnapshot.from(event)
      dispatchSwingMutation {
        val previous = mobileNetwork
        // Attachment IDs are process-monotonic. A worker from a detached endpoint may finish
        // after Swing has already queued the new endpoint's READY status; never let that stale
        // status replace the new owner.
        if (previous == null || detached.attachmentId >= previous.attachmentId) {
          mobileNetwork = detached
          if (current.statusSelection == SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
            render(current)
          }
        }
      }
    }
    eventBus.register<ControllerOwnershipCommittedEvent> {
      dispatchSwingMutation {
        // A replacement controller starts with the deterministic standalone peer owner. With no
        // ROM there is no session activation event to reassert that default, so clear stale local
        // peripheral UI state only after the replacement ownership transaction commits. A failed
        // close deliberately retains the prior controller and its serial owner.
        current =
            SerialPeripheralUiSnapshot(
                selection = SerialPeripheralSelection.PEER_TO_PEER,
                statusSelection = SerialPeripheralSelection.PEER_TO_PEER,
              status = SerialPeripheralStatus.DETACHED,
            )
        mobileNetwork = null
        items.getValue(SerialPeripheralSelection.PEER_TO_PEER).isSelected = true
        render(current)
      }
    }
  }

  fun snapshot(): SerialPeripheralUiSnapshot = current

  fun isSelected(selection: SerialPeripheralSelection): Boolean = items[selection]?.isSelected == true

  private fun postSelectionWithoutOwnershipRollback(selection: SerialPeripheralSelection) {
    try {
      eventBus.post(Controller.SetSerialPeripheralEvent(selection))
    } catch (_: RuntimeException) {
      // EventBus dispatch is synchronous. The controller or an earlier subscriber may already
      // have committed the selection and published its typed status, so no compensating status is
      // safe here. Re-select whatever authoritative snapshot exists when this EDT action runs: a
      // committed SelectionChanged callback is queued first, while a pre-controller failure leaves
      // the old selection authoritative.
      dispatchSwingMutation { items.getValue(current.selection).isSelected = true }
    }
  }

  private fun postPrerequisiteFailure(selection: SerialPeripheralSelection) {
    try {
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              selection,
              SerialPeripheralStatus.UNAVAILABLE,
              SerialPeripheralError.PORT_OWNED_BY_LINK,
          ))
    } catch (_: RuntimeException) {
      // The application event tree may itself be closing; no UI remains to fix.
    }
  }

  private fun render(snapshot: SerialPeripheralUiSnapshot) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Serial peripheral menu rendering must run on the Event Dispatch Thread"
    }
    statusItem.text = statusText(snapshot, mobileNetwork)
    statusItem.toolTipText =
        if (snapshot.statusSelection == SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
          mobileNetworkTooltip(mobileNetwork)
        } else {
          null
        }
    onRendered(snapshot)
  }

  companion object {
    private val DISPLAY_ORDER =
        listOf(
            SerialPeripheralSelection.PEER_TO_PEER,
            SerialPeripheralSelection.NONE,
            SerialPeripheralSelection.PRINTER,
            SerialPeripheralSelection.BARCODE_BOY,
            SerialPeripheralSelection.GPS_RECEIVER,
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
        )

    internal fun label(selection: SerialPeripheralSelection): String =
        when (selection) {
          SerialPeripheralSelection.PEER_TO_PEER -> "Link cable (default)"
          SerialPeripheralSelection.NONE -> "No link-port peripheral"
          SerialPeripheralSelection.PRINTER -> "Game Boy Printer"
          SerialPeripheralSelection.BARCODE_BOY -> "Barcode Boy"
          SerialPeripheralSelection.GPS_RECEIVER -> "GPS Receiver (GPS Boy)"
          SerialPeripheralSelection.MOBILE_ADAPTER_GB -> "Mobile Adapter GB"
        }

    private fun description(selection: SerialPeripheralSelection): String =
        if (selection == SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
          "Attach the deterministic Mobile Adapter protocol engine; custom-server networking remains separately consent-gated"
        } else {
          "Make ${label(selection)} the exclusive owner of the Game Boy serial port"
        }

    internal fun statusText(snapshot: SerialPeripheralUiSnapshot): String =
        statusText(snapshot, null)

    internal fun statusText(
        snapshot: SerialPeripheralUiSnapshot,
        network: MobileAdapterNetworkUiSnapshot?,
    ): String {
      val owner = label(snapshot.statusSelection)
      return when (snapshot.status) {
        SerialPeripheralStatus.DETACHED -> "Status: $owner — detached"
        SerialPeripheralStatus.ATTACHED ->
            if (snapshot.statusSelection == SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
              "Status: $owner — attached; ${mobileNetworkStatusText(network)}"
            } else {
              "Status: $owner — attached"
            }
        SerialPeripheralStatus.UNAVAILABLE -> {
          val error = checkNotNull(snapshot.error)
          "Status: $owner — ${error.code}: ${error.userMessage}"
        }
      }
    }

    private fun mobileNetworkStatusText(network: MobileAdapterNetworkUiSnapshot?): String =
        when (network?.phase) {
          null, Controller.MobileAdapterNetworkPhase.OFFLINE -> "network offline"
          Controller.MobileAdapterNetworkPhase.READY -> "custom network ready"
          Controller.MobileAdapterNetworkPhase.RESOLVING -> "custom DNS resolving"
          Controller.MobileAdapterNetworkPhase.CONNECTING -> "custom server connecting"
          Controller.MobileAdapterNetworkPhase.CONNECTED ->
              network.slot?.let { "custom server connected (slot $it)" }
                  ?: "custom server connected (${network.activeConnections}/2)"
          Controller.MobileAdapterNetworkPhase.TRANSFERRING ->
              network.slot?.let { "custom transfer active (slot $it)" }
                  ?: "custom transfer active"
          Controller.MobileAdapterNetworkPhase.CANCELLING -> "custom network cancelling"
          Controller.MobileAdapterNetworkPhase.DISCONNECTED ->
              "custom network disconnected (${checkNotNull(network.disconnectReason).name})"
          Controller.MobileAdapterNetworkPhase.FAILED -> {
            val error = checkNotNull(network.error)
            if (error == Controller.MobileAdapterNetworkError.REMOTE_CLOSED &&
                network.slot != null) {
              "slot ${network.slot} closed; ${network.activeConnections}/2 connections remain — " +
                  "${error.code}: ${error.userMessage}"
            } else {
              "${error.code}: ${error.userMessage}"
            }
          }
        }

    private fun mobileNetworkTooltip(network: MobileAdapterNetworkUiSnapshot?): String =
        when (network?.phase) {
          null, Controller.MobileAdapterNetworkPhase.OFFLINE ->
              "Custom-server networking is disabled until explicit session consent is granted."
          Controller.MobileAdapterNetworkPhase.FAILED ->
              checkNotNull(network.error).userMessage
          else ->
              "Only the explicitly configured custom-server policy is available; Nintendo production services are never contacted."
        }
  }
}
