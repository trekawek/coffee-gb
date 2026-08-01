package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSource
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationStore
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkMode
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterPortMapping
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.JTextArea
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterConfigurationWindowTest {
  @Test
  fun `structured policy validation preserves controller limits ports and uniqueness`() {
    val valid =
        customDraft(
            listOf(
                MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "80", "18080"),
                MobileAdapterMappingDraft(MobileAdapterTransport.UDP, "53", "15353"),
            ))
    val policy = assertIs<MobileAdapterPolicyValidation.Valid>(validateMobileAdapterPolicyDraft(valid))
    assertEquals(
        listOf(
            MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080),
            MobileAdapterPortMapping(MobileAdapterTransport.UDP, 53, 15353),
        ).toSet(),
        assertIs<MobileAdapterNetworkPolicy.CustomServer>(policy.policy).portMappings.toSet(),
    )

    val duplicate =
        validateMobileAdapterPolicyDraft(
            customDraft(
                listOf(
                    MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "80", "18080"),
                    MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "80", "28080"),
                )))
    assertTrue(assertIs<MobileAdapterPolicyValidation.Invalid>(duplicate).message.contains("only one"))

    val excessive =
        validateMobileAdapterPolicyDraft(
            customDraft(
                (1..17).map {
                  MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "$it", "$it")
                }))
    assertEquals(
        MobileAdapterPolicyField.PORT_MAPPINGS,
        assertIs<MobileAdapterPolicyValidation.Invalid>(excessive).field,
    )

    val unicodePort =
        validateMobileAdapterPolicyDraft(
            customDraft(listOf(MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "１２", "80"))))
    assertTrue(assertIs<MobileAdapterPolicyValidation.Invalid>(unicodePort).message.contains("decimal"))
  }

  @Test
  fun `panel uses a structured mapping table live inline validation literal text and semantic theme`() =
      onEdt {
        val edited = mutableListOf<MobileAdapterPolicyDraft>()
        val imports = AtomicInteger()
        val actions =
            noOpActions().copy(
                draftChanged = edited::add,
                importConfigurationImage = { imports.incrementAndGet() },
            )
        val initialTokens = tokens()
        val panel = MobileAdapterConfigurationPanel(actions, initialTokens)
        val policy = customPolicy()
        val draft = MobileAdapterPolicyDraft.from(policy)
        panel.render(
            presentation(
                policy = policy,
                draft = draft,
                policyStatus = "<html><b>literal policy status</b></html>",
                policyTone = MobileAdapterStatusTone.ERROR,
                session =
                    MobileAdapterSessionPresentation(
                        "<html><b>literal session status</b></html>",
                        cancelEnabled = true,
                        tone = MobileAdapterStatusTone.WARNING,
                    ),
            ))

        assertEquals(2, panel.mappingModel.rowCount)
        assertEquals(
            listOf("Transport", "Guest port", "Target port"),
            (0 until panel.mappingModel.columnCount).map(panel.mappingModel::getColumnName),
        )
        assertEquals(true, panel.policyStatus.getClientProperty("html.disable"))
        assertEquals("<html><b>literal policy status</b></html>", panel.policyStatus.text)
        assertEquals("<html><b>literal session status</b></html>", panel.sessionStatus.text)
        assertTrue(
            descendants(panel)
                .filterIsInstance<JLabel>()
                .none { it.text.startsWith("<html>", ignoreCase = true) })
        val importExplanation =
            descendants(panel)
                .filterIsInstance<JTextArea>()
                .single { it.accessibleContext.accessibleName == "Mobile Adapter image import explanation" }
                .text
        assertTrue(importExplanation.contains("account data"))
        assertTrue(importExplanation.contains("host metadata is ignored"))
        panel.importImageButton.doClick()
        assertEquals(1, imports.get())

        panel.mappingModel.setValueAt("0", 0, 1)
        assertEquals("0", edited.last().portMappings.first().guestPort)
        val invalid = validateMobileAdapterPolicyDraft(edited.last())
        panel.render(
            presentation(
                policy = policy,
                draft = edited.last(),
                validation = invalid,
                dirty = true,
            ))
        assertTrue(panel.validationStatus.isVisible)
        assertTrue(panel.validationStatus.text.contains("1..65535"))
        assertFalse(panel.saveButton.isEnabled)
        assertFalse(panel.importImageButton.isEnabled)

        val dark =
            initialTokens.copy(
                surface = Color(0x222222),
                secondaryText = Color(0xDDDDDD),
                warning = Color(0xFFE070),
                danger = Color(0xFF9088),
            )
        panel.desktopThemeChanged(dark)
        assertEquals(dark.surface, panel.background)
        assertEquals(dark.danger, panel.validationStatus.foreground)
      }

  @Test
  fun `host retains one view and applies the two runtime consent gates separately`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(customConfiguration())
    val decisions = RecordingDecisions()
    val fixture = onEdt { HostFixture(coordinator, bus, decisions) }
    try {
      onEdt {
        fixture.host.showOrRaise()
        fixture.host.show()
      }
      assertEquals(1, fixture.factoryCalls)
      assertEquals(2, fixture.view.showCalls)

      decisions.networkAllowed = false
      onEdt { fixture.view.actions.setNetworkConsent(true) }
      assertFalse(coordinator.snapshot().networkConsent)
      assertEquals(1, decisions.networkRequests)

      decisions.networkAllowed = true
      onEdt { fixture.view.actions.setNetworkConsent(true) }
      assertTrue(coordinator.snapshot().networkConsent)
      assertFalse(coordinator.snapshot().privateLocalDevelopment)
      assertEquals(2, decisions.networkRequests)
      assertEquals(0, decisions.privateRequests)

      decisions.privateAllowed = true
      onEdt { fixture.view.actions.setPrivateLocalDevelopment(true) }
      assertTrue(coordinator.snapshot().privateLocalDevelopment)
      assertEquals(1, decisions.privateRequests)
      val summary = onEdt { fixture.host.currentSummary() }
      assertEquals(MobileAdapterNetworkMode.CUSTOM_SERVER, summary.mode)
      assertEquals(2, summary.portMappingCount)
      assertTrue(summary.preferencesText().contains("private/LAN development allowed"))
      assertFalse(summary.preferencesText().contains("service.example"))
      assertFalse(summary.preferencesText().contains("192.0.2.53"))

      onEdt { fixture.view.actions.setNetworkConsent(false) }
      assertFalse(coordinator.snapshot().networkConsent)
      assertFalse(coordinator.snapshot().privateLocalDevelopment)
      assertEquals(2, decisions.networkRequests, "revocation never opens a consent decision")
      assertEquals(1, decisions.privateRequests)
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `network status is correlated inline and Cancel posts only for cancellable work`() {
    val bus = EventBusImpl()
    val cancellations = AtomicInteger()
    bus.register<Controller.CancelMobileAdapterNetworkEvent> { cancellations.incrementAndGet() }
    val coordinator = coordinator(customConfiguration())
    val fixture = onEdt { HostFixture(coordinator, bus, RecordingDecisions()) }
    try {
      onEdt { fixture.host.show() }
      bus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 4,
              policyRevision = coordinator.snapshot().revision,
              phase = Controller.MobileAdapterNetworkPhase.RESOLVING,
          ))
      flushEdt()
      var current = onEdt { fixture.host.currentPresentation() }
      assertTrue(current.session.cancelEnabled)
      assertTrue(current.session.summary.contains("Resolving"))

      onEdt { fixture.view.actions.cancelNetwork() }
      assertEquals(1, cancellations.get())

      bus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 5,
              policyRevision = coordinator.snapshot().revision,
              phase = Controller.MobileAdapterNetworkPhase.FAILED,
              slot = 1,
              activeConnections = 1,
              error = Controller.MobileAdapterNetworkError.REMOTE_CLOSED,
          ))
      bus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 3,
              policyRevision = coordinator.snapshot().revision,
              phase = Controller.MobileAdapterNetworkPhase.READY,
          ))
      flushEdt()
      current = onEdt { fixture.host.currentPresentation() }
      assertEquals(MobileAdapterStatusTone.ERROR, current.session.tone)
      assertTrue(current.session.summary.contains("REMOTE_CLOSED"))
      assertTrue(current.session.summary.contains("Connection slot 2"))
      assertTrue(current.session.cancelEnabled)
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `revision mismatch keeps the draft stale until explicit Reload`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(offlineConfiguration())
    val fixture = onEdt { HostFixture(coordinator, bus, RecordingDecisions()) }
    try {
      onEdt {
        fixture.host.show()
        fixture.view.actions.draftChanged(customDraft())
      }
      assertTrue(onEdt { fixture.host.currentPresentation().canSave })

      coordinator.applyRuntimeAuthorization(
          coordinator.snapshot().revision,
          networkConsent = false,
          privateLocalDevelopment = false,
          eventBus = bus,
      )
      onEdt { fixture.view.actions.savePolicy() }

      var current = onEdt { fixture.host.currentPresentation() }
      assertTrue(current.stale)
      assertTrue(current.policyDirty)
      assertFalse(current.canSave)
      assertTrue(current.policyStatus.contains("Reload"))
      assertEquals(MobileAdapterNetworkMode.CUSTOM_SERVER, current.draft.mode)

      onEdt { fixture.view.actions.reloadPolicy() }
      current = onEdt { fixture.host.currentPresentation() }
      assertFalse(current.stale)
      assertFalse(current.policyDirty)
      assertEquals(MobileAdapterNetworkMode.OFFLINE, current.draft.mode)
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `successful save completes inline revokes permission and keeps the window open`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(offlineConfiguration())
    val saved = CountDownLatch(1)
    val latest = AtomicReference<MobileAdapterConfigurationPresentation>()
    val fixture =
        onEdt {
          HostFixture(coordinator, bus, RecordingDecisions()) { presentation ->
            latest.set(presentation)
            if (presentation.policyStatusTone == MobileAdapterStatusTone.SUCCESS) saved.countDown()
          }
        }
    try {
      onEdt {
        fixture.host.show()
        fixture.view.actions.draftChanged(customDraft())
        fixture.view.actions.savePolicy()
      }
      assertTrue(saved.await(5, TimeUnit.SECONDS))
      flushEdt()

      val current = latest.get()
      assertIs<MobileAdapterNetworkPolicy.CustomServer>(current.baselinePolicy)
      assertFalse(current.policyDirty)
      assertFalse(current.networkConsent)
      assertFalse(current.privateLocalDevelopment)
      assertEquals(0, fixture.view.hideCalls)
      assertEquals(0, fixture.view.closeCalls)
      assertTrue(current.policyStatus.contains("Policy saved"))
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `adapter image import preserves policy and device while revoking session permissions`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(customConfiguration())
    val source = Files.createTempFile("coffee-gb-mobile-window-import", ".bin")
    val image = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { index ->
      (index xor 0x5a).toByte()
    }
    Files.write(source, image)
    val selected = AtomicInteger()
    val imported = CountDownLatch(1)
    val latest = AtomicReference<MobileAdapterConfigurationPresentation>()
    val fixture =
        onEdt {
          HostFixture(
              coordinator,
              bus,
              RecordingDecisions(),
              imageSelector = {
                selected.incrementAndGet()
                source
              },
          ) { presentation ->
            latest.set(presentation)
            if (presentation.policyStatusTone == MobileAdapterStatusTone.SUCCESS &&
                presentation.policyStatus.startsWith("Adapter image imported")) {
              imported.countDown()
            }
          }
        }
    try {
      onEdt {
        fixture.host.show()
        fixture.view.actions.setNetworkConsent(true)
        fixture.view.actions.setPrivateLocalDevelopment(true)
      }
      assertTrue(coordinator.snapshot().networkConsent)
      assertTrue(coordinator.snapshot().privateLocalDevelopment)

      onEdt { fixture.view.actions.importConfigurationImage() }
      assertTrue(imported.await(5, TimeUnit.SECONDS))
      flushEdt()

      val runtime = coordinator.snapshot()
      assertEquals(0x08, runtime.configuration.deviceId)
      assertEquals(customPolicy(), runtime.configuration.networkPolicy)
      assertContentEquals(image, runtime.configuration.configurationBytes())
      assertFalse(runtime.networkConsent)
      assertFalse(runtime.privateLocalDevelopment)
      assertEquals(1, selected.get())
      assertFalse(latest.get().policyStatus.contains(source.toString()))
      assertTrue(latest.get().policyStatus.contains("host metadata was ignored"))
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `chooser approval after host shutdown does not start an import`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(customConfiguration())
    val source = Files.createTempFile("coffee-gb-mobile-window-late-import", ".bin")
    Files.write(
        source,
        ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { 0x5a.toByte() },
    )
    val selected = AtomicInteger()
    lateinit var fixture: HostFixture
    fixture =
        onEdt {
          HostFixture(
              coordinator,
              bus,
              RecordingDecisions(),
              imageSelector = {
                selected.incrementAndGet()
                fixture.host.close()
                coordinator.close()
                source
              },
          )
        }
    val before = coordinator.snapshot()
    try {
      onEdt {
        fixture.host.show()
        val presentationsBeforeSelection = fixture.view.presentations.toList()

        fixture.view.actions.importConfigurationImage()

        assertEquals(presentationsBeforeSelection, fixture.view.presentations)
      }
      assertEquals(1, selected.get())
      assertEquals(1, fixture.view.closeCalls)
      assertEquals(before, coordinator.snapshot())
      assertTrue(
          fixture.view.presentations.none { it.savePhase == MobileAdapterSavePhase.IMPORTING })
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  @Test
  fun `dirty close offers discard or keep editing while clean close only hides`() {
    val bus = EventBusImpl()
    val coordinator = coordinator(offlineConfiguration())
    val decisions = RecordingDecisions(discardAllowed = false)
    val fixture = onEdt { HostFixture(coordinator, bus, decisions) }
    try {
      onEdt {
        fixture.host.show()
        fixture.view.actions.draftChanged(customDraft())
        fixture.view.actions.hide()
      }
      assertEquals(1, decisions.discardRequests)
      assertEquals(0, fixture.view.hideCalls)
      assertTrue(onEdt { fixture.host.currentPresentation().policyDirty })

      decisions.discardAllowed = true
      onEdt { fixture.view.actions.hide() }
      assertEquals(2, decisions.discardRequests)
      assertEquals(1, fixture.view.hideCalls)
      assertFalse(onEdt { fixture.host.currentPresentation().policyDirty })

      onEdt { fixture.view.actions.hide() }
      assertEquals(2, decisions.discardRequests)
      assertEquals(2, fixture.view.hideCalls)
    } finally {
      onEdt { fixture.host.close() }
      coordinator.close()
      bus.close()
    }
  }

  private class HostFixture(
      coordinator: MobileAdapterConfigurationCoordinator,
      eventBus: EventBusImpl,
      decisions: RecordingDecisions,
      imageSelector: () -> Path? = { null },
      onPresentation: (MobileAdapterConfigurationPresentation) -> Unit = {},
  ) {
    lateinit var view: RecordingView
    var factoryCalls = 0
    val host =
        MobileAdapterConfigurationWindowHost(
            coordinator,
            eventBus,
            MobileAdapterConfigurationUiState(
                source = MobileAdapterConfigurationSource.PERSISTED,
                deviceId = 0x08,
                error = null,
                recoveryPerformed = false,
            ),
            MobileAdapterConfigurationWindowViewFactory { actions ->
              factoryCalls++
              RecordingView(actions, onPresentation).also { view = it }
            },
            decisions,
            onSummary = {},
            imageSelector = MobileAdapterConfigurationImageSelector(imageSelector),
        )
  }

  private class RecordingView(
      val actions: MobileAdapterConfigurationWindowActions,
      private val onPresentation: (MobileAdapterConfigurationPresentation) -> Unit,
  ) :
      MobileAdapterConfigurationWindowView {
    val presentations = mutableListOf<MobileAdapterConfigurationPresentation>()
    var showCalls = 0
    var hideCalls = 0
    var closeCalls = 0

    override fun render(presentation: MobileAdapterConfigurationPresentation) {
      assertTrue(SwingUtilities.isEventDispatchThread())
      presentations += presentation
      onPresentation(presentation)
    }

    override fun showOrRaise() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      showCalls++
    }

    override fun hide() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      hideCalls++
    }

    override fun close() {
      assertTrue(SwingUtilities.isEventDispatchThread())
      closeCalls++
    }
  }

  private class RecordingDecisions(
      var networkAllowed: Boolean = true,
      var privateAllowed: Boolean = true,
      var discardAllowed: Boolean = true,
  ) : MobileAdapterConfigurationDecisionPrompter {
    var networkRequests = 0
    var privateRequests = 0
    var discardRequests = 0

    override fun allowSessionNetworking(): Boolean {
      networkRequests++
      return networkAllowed
    }

    override fun allowPrivateLocalDevelopment(): Boolean {
      privateRequests++
      return privateAllowed
    }

    override fun discardPolicyChanges(): Boolean {
      discardRequests++
      return discardAllowed
    }
  }

  private fun coordinator(initial: MobileAdapterConfiguration): MobileAdapterConfigurationCoordinator {
    val directory = Files.createTempDirectory("coffee-gb-mobile-window")
    return MobileAdapterConfigurationCoordinator(
        initial,
        MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
    )
  }

  private fun offlineConfiguration(): MobileAdapterConfiguration =
      MobileAdapterConfiguration(
          0x08,
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE),
          MobileAdapterNetworkPolicy.Offline,
      )

  private fun customConfiguration(): MobileAdapterConfiguration =
      MobileAdapterConfiguration(
          0x08,
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE),
          customPolicy(),
      )

  private fun customPolicy(): MobileAdapterNetworkPolicy.CustomServer =
      MobileAdapterNetworkPolicy.CustomServer(
          "service.example",
          "192.0.2.53",
          5353,
          listOf(
              MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080),
              MobileAdapterPortMapping(MobileAdapterTransport.UDP, 53, 15353),
          ),
      )

  private fun customDraft(
      mappings: List<MobileAdapterMappingDraft> =
          listOf(MobileAdapterMappingDraft(MobileAdapterTransport.TCP, "80", "18080"))
  ): MobileAdapterPolicyDraft =
      MobileAdapterPolicyDraft(
          MobileAdapterNetworkMode.CUSTOM_SERVER,
          "service.example",
          "192.0.2.53",
          "5353",
          mappings,
      )

  private fun presentation(
      policy: MobileAdapterNetworkPolicy,
      draft: MobileAdapterPolicyDraft,
      validation: MobileAdapterPolicyValidation = validateMobileAdapterPolicyDraft(draft),
      dirty: Boolean = false,
      policyStatus: String = "Ready.",
      policyTone: MobileAdapterStatusTone = MobileAdapterStatusTone.NEUTRAL,
      session: MobileAdapterSessionPresentation = MobileAdapterSessionPresentation("Ready.", false),
  ) =
      MobileAdapterConfigurationPresentation(
          revision = 1,
          launcherSummary = launcherState().startupSummaryText(),
          baselinePolicy = policy,
          draft = draft,
          validation = validation,
          policyDirty = dirty,
          stale = false,
          savePhase = MobileAdapterSavePhase.IDLE,
          networkConsent = false,
          privateLocalDevelopment = false,
          policyStatus = policyStatus,
          policyStatusTone = policyTone,
          session = session,
      )

  private fun launcherState() =
      MobileAdapterConfigurationUiState(
          source = MobileAdapterConfigurationSource.PERSISTED,
          deviceId = 0x08,
          error = null,
          recoveryPerformed = false,
      )

  private fun noOpActions() =
      MobileAdapterConfigurationWindowActions({}, {}, {}, {}, {}, {}, {}, {})

  private fun tokens() =
      DesktopThemeTokens(
          surface = Color(0xF5F5F5),
          elevatedSurface = Color.WHITE,
          primaryText = Color.BLACK,
          secondaryText = Color(0x333333),
          border = Color(0x666666),
          focus = Color(0x005FCC),
          accent = Color(0x8C2F1B),
          onAccent = Color.WHITE,
          success = Color(0x176B2C),
          warning = Color(0x7A4D00),
          danger = Color(0xB00020),
      )

  private fun descendants(component: Component): List<Component> =
      buildList {
        add(component)
        if (component is Container) component.components.forEach { addAll(descendants(it)) }
      }

  private fun flushEdt() {
    onEdt { Unit }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
