package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.CheatDatabase
import eu.rekawek.coffeegb.core.genie.PatchFactory
import eu.rekawek.coffeegb.core.ir.FullChanger
import eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera
import eu.rekawek.coffeegb.swing.io.WebcamCameraSource
import java.awt.Component
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal data class ManagedStateMenuAvailability(
    val quickCommandsAvailable: Boolean = false,
    val localSessionActive: Boolean = false,
)

internal data class DebuggerMenuActions(
    val showTool: (DebuggerWorkspaceTool) -> Unit,
    val applyLayout: (DebuggerWorkspaceLayout) -> Unit,
)

private enum class StopGameDecision {
  STOP,
  KEEP_PLAYING,
}

private enum class PersistenceRetryDecision {
  RETRY,
  CANCEL,
}

/** Keeps modeless managed-state controls aligned across local/link ownership transitions. */
internal class ManagedStateMenuAvailabilityBinding(
    eventBus: EventBus,
    private val apply: (ManagedStateMenuAvailability) -> Unit,
) {
  private var current = ManagedStateMenuAvailability()

  init {
    eventBus.register<StateUxSessionEvent> { session ->
      dispatchSwingMutation {
        current =
            ManagedStateMenuAvailability(
                quickCommandsAvailable = session.available,
                localSessionActive = session.available || session.unavailableReason != null,
            )
        apply(current)
      }
    }
    eventBus.register<ControllerOwnershipChangingEvent> {
      dispatchSwingMutation {
        current = current.copy(quickCommandsAvailable = false)
        apply(current)
      }
    }
  }
}

internal fun mobileAdapterStateBoundaryMessage(event: Controller.MobileAdapterStateBoundaryEvent): String =
    when (event.boundary) {
        Controller.MobileAdapterStateBoundary.SAVE ->
            "State saved. The current custom-server connection remains active, but loading this state will restore the Mobile Adapter disconnected."
        Controller.MobileAdapterStateBoundary.LOAD ->
            "Live Mobile Adapter custom-server work was disconnected while loading state. Host connections are never restored from state."
        Controller.MobileAdapterStateBoundary.REWIND ->
            "Live Mobile Adapter custom-server work was disconnected while rewinding. Host connections are never restored from state."
        Controller.MobileAdapterStateBoundary.RESET ->
            "Live Mobile Adapter custom-server work was disconnected while resetting. Host connections are never restored from state."
      }

internal class SwingMenu(
    private val properties: EmulatorProperties,
    private val window: JFrame,
    private val eventBus: EventBus,
    private val displayController: DesktopDisplayController,
    private val onOpenRom: (path: Path, source: RomOpenSource) -> Unit,
    private val acceptRomLifecycle: (Long?) -> Boolean,
    private val onPreferencesCategory: (PreferencesCategory) -> Unit,
    private val debuggerActions: DebuggerMenuActions,
    private val desktopActions: DesktopActionRegistry,
    private val mobileAdapterConfigurationUiState: MobileAdapterConfigurationUiState,
    private val isLinkedControllerActive: () -> Boolean,
    currentThemeTokens: () -> DesktopThemeTokens,
    private val onDesktopStatus: (String) -> Unit = {},
    private val onMobileAdapterConfiguration: () -> Unit,
) {
  private var cameraDeviceIndex = properties.applicationSettings.peripherals.cameraDeviceIndex

  private lateinit var cameraController: CameraPeripheralController<WebcamCameraSource>

  private lateinit var cameraShutdown: BoundedCameraShutdown

  private var currentRomFileName: String? = null

  private var currentRomTitle: String? = null

  private val cheatDatabase: CheatDatabase by lazy { CheatDatabase.loadBundled() }

  private val desktopDialogFactory = DesktopDialogFactory(currentThemeTokens)

  private val barcodeBoyDialog = BarcodeBoyDialog(desktopDialogFactory)

  private val fullChangerDialog = FullChangerDialog(desktopDialogFactory)

  private val actionReplaySlotDialog = ActionReplaySlotDialog(desktopDialogFactory)

  private val cheatsDialog = DesktopCheatsDialog(currentThemeTokens)

  private val helpDialogs = DesktopHelpDialogs(desktopDialogFactory)

  // One radio-group binding mirrors the controller's exclusive serial-port owner.
  private lateinit var serialPeripheralBinding: SerialPeripheralMenuBinding

  private lateinit var recentRomsMenu: JMenu

  init {
    PersistenceFailureHandler(
        eventBus,
        showError = { title, message ->
          SwingUtilities.invokeLater {
            desktopDialogFactory.showError(
                window,
                DesktopErrorSpec(
                    title = title,
                    summary = message,
                    recovery =
                        "Coffee GB kept the current session open. Review the related storage settings before trying again.",
                    buttons =
                        DesktopDialogButtons(
                            cancel = DesktopDialogAction("Close", Unit),
                        ),
                ),
            )
          }
        },
        requestRetryOrCancel = { title, message, decide ->
          SwingUtilities.invokeLater {
            val decision =
                desktopDialogFactory.showDecision(
                    window,
                    DesktopDecisionSpec(
                        title = title,
                        heading = message,
                        message =
                            "Retry the persistence operation now, or cancel and keep the current session open.",
                        buttons =
                            DesktopDialogButtons(
                                primary =
                                    DesktopDialogAction(
                                        "Retry",
                                        PersistenceRetryDecision.RETRY,
                                    ),
                                cancel =
                                    DesktopDialogAction(
                                        "Cancel",
                                        PersistenceRetryDecision.CANCEL,
                                    ),
                                defaultButton = DesktopDialogDefaultButton.CANCEL,
                            ),
                    ),
                )
            decide(decision == PersistenceRetryDecision.RETRY)
          }
        },
        handleReplacement = { it.openRequestId == null },
    )
    eventBus.register<EmulationStartedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, acceptRomLifecycle) {
        currentRomFileName =
            event.origin
                ?.displayName()
                ?.let { name -> name.substringBeforeLast('.', name) }
                ?: currentRomFileName
        currentRomTitle = event.romName
      }
    }
    eventBus.register<LoadRomFailedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, acceptRomLifecycle) {
        if (event.openRequestId == null) {
          desktopDialogFactory.showError(
              window,
              DesktopErrorSpec(
                  title = "Unable to open ROM",
                  summary = "Coffee GB could not open ${event.rom.name}.",
                  recovery = event.message,
                  buttons =
                      DesktopDialogButtons(
                          cancel = DesktopDialogAction("Close", Unit),
                      ),
              ),
          )
        }
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      dispatchAcceptedRomLifecycle(null, acceptRomLifecycle) {
        currentRomFileName = null
        currentRomTitle = null
      }
    }
    eventBus.register<Controller.MobileAdapterStateBoundaryEvent> { event ->
      dispatchSwingMutation { onDesktopStatus(mobileAdapterStateBoundaryMessage(event)) }
    }
  }

  fun addMenu() {
    val menuBar = JMenuBar()

    menuBar.add(createFileMenu())
    val gameMenu = createGameMenu()
    gameMenu.addSeparator()
    gameMenu.add(JMenuItem(desktopActions[DesktopCommand.NETPLAY]))
    gameMenu.addSeparator()
    val audioMenu = createAudioMenu()
    while (audioMenu.itemCount > 0) {
      gameMenu.add(audioMenu.getItem(0))
    }
    menuBar.add(gameMenu)
    menuBar.add(createScreenMenu().apply { text = "View" })
    menuBar.add(createPeripheralsMenu())
    menuBar.add(createDebugMenu(debuggerActions))
    menuBar.add(createHelpMenu())
    window.jMenuBar = menuBar
  }

  fun closeCameraAfterSuccessfulStop(timeoutMillis: Long) {
    if (::cameraShutdown.isInitialized) {
      cameraShutdown.closeAndAwait(timeoutMillis)
    }
  }

  fun applyCameraSettings(peripherals: ApplicationSettings.Peripherals) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Camera preferences must be applied from the Event Dispatch Thread"
    }
    cameraDeviceIndex = peripherals.cameraDeviceIndex
    if (::cameraController.isInitialized) {
      cameraController.selectDevice(peripherals.cameraDeviceIndex)
    }
  }

  private fun createFileMenu(): JMenu {
    val fileMenu = JMenu("File")

    val load = JMenuItem(desktopActions[DesktopCommand.OPEN_ROM])
    load.accessibleContext.accessibleDescription =
        "Open a Game Boy ROM or bounded ZIP or 7z archive"
    fileMenu.add(load)

    recentRomsMenu = JMenu("Recent ROMs")
    fileMenu.add(recentRomsMenu)
    updateRecentRoms()

    fileMenu.addSeparator()
    val closeGame = JMenuItem(desktopActions[DesktopCommand.CLOSE_GAME])
    fileMenu.add(closeGame)

    val openSaveFolder = JMenuItem(desktopActions[DesktopCommand.OPEN_SAVE_FOLDER])
    fileMenu.add(openSaveFolder)

    fileMenu.addSeparator()
    val preferences = JMenuItem(desktopActions[DesktopCommand.PREFERENCES])
    fileMenu.add(preferences)

    fileMenu.addSeparator()
    val quit = JMenuItem(desktopActions[DesktopCommand.QUIT])
    fileMenu.add(quit)

    return fileMenu
  }

  internal fun openRomChooser() {
    val chooser = RomFileChooser()
    chooser.dialogTitle = "Open Game Boy ROM"
    chooser.fileFilter =
        FileNameExtensionFilter(
            "Game Boy ROMs and archives (*.gb, *.gbc, *.rom, *.zip, *.7z)",
            "gb",
            "gbc",
            "rom",
            "zip",
            "7z",
        )
    chooser.isAcceptAllFileFilterUsed = false
    chooser.accessibleContext.accessibleName = "Choose a Game Boy ROM or ZIP or 7z archive"
    openRomChooser(chooser, chooser.currentDirectory, window)
  }

  private fun openRomChooser(
      chooser: RomFileChooser,
      systemDefaultRomDirectory: File,
      parent: Component,
  ) {
    properties.applicationSettings.general.romDirectory?.let(chooser::useConfiguredDirectory)
        ?: run { chooser.currentDirectory = systemDefaultRomDirectory }
    val code = chooser.showOpenDialog(parent)
    if (code == JFileChooser.APPROVE_OPTION) {
      val rom = chooser.selectedFile
      launchRom(rom, RomOpenSource.CHOOSER)
    }
  }

  private fun createGameMenu(): JMenu {
    val gameMenu = JMenu("Game")

    gameMenu.add(JCheckBoxMenuItem(desktopActions[DesktopCommand.PAUSE]))
    gameMenu.add(JMenuItem(desktopActions[DesktopCommand.RESET]))

    gameMenu.addSeparator()

    val saveSnapshot = JMenuItem(desktopActions[DesktopCommand.SAVE_STATE])
    saveSnapshot.text = "Save State"
    gameMenu.add(saveSnapshot)

    val loadSnapshot = JMenuItem(desktopActions[DesktopCommand.LOAD_STATE])
    loadSnapshot.text = "Load State"
    gameMenu.add(loadSnapshot)

    val slotMenu = JMenu("State Slot")
    gameMenu.add(slotMenu)
    val slotGroup = ButtonGroup()
    desktopActions.stateSlotActions.forEach { action ->
      val slotItem = JRadioButtonMenuItem(action)
      slotGroup.add(slotItem)
      slotMenu.add(slotItem)
    }

    val manageStates = JMenuItem(desktopActions[DesktopCommand.MANAGE_STATES])
    manageStates.mnemonic = KeyEvent.VK_M
    gameMenu.add(manageStates)

    gameMenu.addSeparator()
    val cheats = JMenuItem("Cheats…")
    cheats.accessibleContext.accessibleDescription =
        "Browse the bundled database or add a Game Genie or GameShark code"
    cheats.isEnabled = false
    cheats.addActionListener { showCheats(DesktopCheatsPage.DATABASE) }
    enableWhenEmulationActive(cheats)
    gameMenu.add(cheats)

    return gameMenu
  }

  private fun showCheats(initialPage: DesktopCheatsPage) {
    val databasePage =
        DesktopCheatDatabasePage(
            suggestedTitle = currentRomFileName ?: currentRomTitle.orEmpty(),
            findGames = { title, limit -> cheatDatabase.findCheatLists(listOf(title), limit) },
            onAddCodes = ::addCheatCodes,
        )
    cheatsDialog.show(
        owner = window,
        databasePage = databasePage,
        initialPage = initialPage,
        onManualCode = { code -> addCheatCodes(listOf(code)) },
    )
  }

  private fun addCheatCodes(codes: List<String>) {
    val patches = codes.flatMap(PatchFactory::createPatches)
    eventBus.post(AddPatches(patches))
  }

  private fun createPeripheralsMenu(): JMenu {
    val peripheralsMenu = JMenu("Peripherals")

    // the Game Boy Camera's webcam source is a cartridge sensor, not a link-port device, so
    // it is independent of the netplay/Barcode Boy/printer/GPS group below
    val camera = JCheckBoxMenuItem("Enable Game Boy Camera", false)
    peripheralsMenu.add(camera)
    cameraController =
        CameraPeripheralController(
            opener = WebcamCameraSource::open,
            initialDeviceIndex = cameraDeviceIndex,
            sourceCloser = WebcamCameraSource::close,
            publisher = PocketCamera::setCameraSource,
            stateConsumer = { state ->
              camera.text =
                  if (state == CameraPeripheralUiState.Opening) {
                    "Opening Game Boy Camera…"
                  } else {
                    "Enable Game Boy Camera"
                  }
              camera.state =
                  state == CameraPeripheralUiState.Opening ||
                      state == CameraPeripheralUiState.Enabled
              if (state == CameraPeripheralUiState.OpenFailed && window.isDisplayable) {
                onDesktopStatus(
                    "Camera ${cameraDeviceIndex + 1} could not be opened. " +
                        "Check the camera selection in Preferences.",
                )
              }
            },
        )
    cameraShutdown =
        BoundedCameraShutdown(
            cameraController::close,
            cameraController::awaitTermination,
        )
    camera.addActionListener {
      cameraController.requestEnabled(camera.state)
    }

    serialPeripheralBinding =
        SerialPeripheralMenuBinding(
            eventBus,
            transitionPrerequisites = { selection ->
              serialPeripheralTransitionPrerequisites(
                  selection,
                  serverSelected = false,
                  clientSelected = false,
                  linkedControllerActive = isLinkedControllerActive(),
              )
            },
        )
    peripheralsMenu.add(serialPeripheralBinding.menu)

    val mobileAdapterDetails = JMenuItem("Mobile Adapter GB configuration…")
    mobileAdapterDetails.accessibleContext.accessibleDescription =
        "Edit the private custom-service policy and session-only network permissions"
    mobileAdapterDetails.addActionListener { onMobileAdapterConfiguration() }
    peripheralsMenu.add(mobileAdapterDetails)

    val actionReplaySlot = JMenuItem("Action Replay Slot…")
    actionReplaySlot.accessibleContext.accessibleDescription =
        "Review, choose, or remove the cartridge attached to the Action Replay slot"
    peripheralsMenu.add(actionReplaySlot)
    actionReplaySlot.addActionListener { showActionReplaySlot() }

    // the Full Changer, the IR toy of Zok Zok Heroes: picking a Cosmic Character sends
    // its transformation over the CGB infrared port (issue #94)
    val fullChanger = JMenuItem("Full Changer…")
    peripheralsMenu.add(fullChanger)
    fullChanger.isEnabled = false
    enableWhenEmulationActive(fullChanger)
    fullChanger.addActionListener {
      fullChangerDialog.show(
          owner = window,
          choices = COSMIC_CHARACTERS.toList(),
          currentChoice =
              properties.getProperty(
                  EmulatorProperties.Key.FullChangerCharacter,
                  COSMIC_CHARACTERS[0],
              ),
          onApply = { choice ->
            properties.setProperty(EmulatorProperties.Key.FullChangerCharacter, choice)
            eventBus.post(FullChanger.TransformEvent(COSMIC_CHARACTERS.indexOf(choice) + 1))
          },
      )
    }

    val scanBarcode = JMenuItem("Barcode Boy…")
    scanBarcode.accessibleContext.accessibleDescription =
        "Enter and send a 13-digit barcode to the Barcode Boy peripheral"
    peripheralsMenu.add(scanBarcode)
    scanBarcode.addActionListener {
      barcodeBoyDialog.show(
          owner = window,
          barcodeBoySelected =
              serialPeripheralBinding.isSelected(SerialPeripheralSelection.BARCODE_BOY),
          onSelectBarcodeBoy = ::selectBarcodeBoy,
          onScan = { code -> eventBus.post(Controller.ScanBarcodeEvent(code)) },
      )
    }
    enableWhenEmulationActive(scanBarcode)

    return peripheralsMenu
  }

  private fun showActionReplaySlot() {
    actionReplaySlotDialog.show(
        owner = window,
        currentFile = currentActionReplaySlot(),
        browseForFile = ::browseForActionReplaySlot,
        onApply = { selected ->
          properties.setProperty(
              EmulatorProperties.Key.DatelSlotRom,
              selected?.toAbsolutePath()?.toString().orEmpty(),
          )
        },
    )
  }

  private fun currentActionReplaySlot(): Path? =
      properties
          .getProperty(EmulatorProperties.Key.DatelSlotRom, null)
          ?.takeIf(String::isNotBlank)
          ?.let { runCatching { Path.of(it) }.getOrNull() }

  private fun browseForActionReplaySlot(): Path? {
    val chooser = RomFileChooser()
    chooser.dialogTitle = "Choose an Action Replay slot cartridge"
    chooser.fileFilter =
        FileNameExtensionFilter(
            "Game Boy ROMs and archives (*.gb, *.gbc, *.rom, *.zip, *.7z)",
            "gb",
            "gbc",
            "rom",
            "zip",
            "7z",
        )
    chooser.isAcceptAllFileFilterUsed = false
    properties.applicationSettings.general.romDirectory?.let(chooser::useConfiguredDirectory)
    return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
      chooser.selectedFile.toPath()
    } else {
      null
    }
  }

  private fun selectBarcodeBoy(): Boolean {
    if (
        serialPeripheralBinding.snapshot().selection != SerialPeripheralSelection.BARCODE_BOY &&
            serialPeripheralBinding.menu.isEnabled) {
      serialPeripheralBinding.items.getValue(SerialPeripheralSelection.BARCODE_BOY).doClick()
    }
    return serialPeripheralBinding.snapshot().selection == SerialPeripheralSelection.BARCODE_BOY
  }

  private fun createScreenMenu(): JMenu {
    val screenMenu =
        createScreenMenu(
            displayController,
            eventBus,
            keyboardBindings = { properties.applicationSettings.input.keyboard.values },
            desktopActions = desktopActions,
        )
    val screenshot = JMenuItem(desktopActions[DesktopCommand.SCREENSHOT])
    screenshot.mnemonic = KeyEvent.VK_T
    screenMenu.insert(screenshot, 1)
    screenMenu.insert(JCheckBoxMenuItem(desktopActions[DesktopCommand.SHOW_COMMAND_BAR]), 2)
    screenMenu.addSeparator()
    screenMenu.add(
        JMenuItem("More Display Settings…").apply {
          getAccessibleContext().accessibleDescription =
              "Open the Display category in Preferences"
          addActionListener { onPreferencesCategory(PreferencesCategory.DISPLAY) }
        })
    return screenMenu
  }

  private fun createAudioMenu(): JMenu {
    val audioMenu = JMenu("Audio")

    val mute = JCheckBoxMenuItem(desktopActions[DesktopCommand.MUTE])
    mute.accessibleContext.accessibleName = "Mute or unmute audio"
    audioMenu.add(mute)
    return audioMenu
  }

  private fun createHelpMenu(): JMenu =
      JMenu("Help").apply {
        mnemonic = KeyEvent.VK_H
        add(
            JMenuItem("Keyboard Shortcuts").apply {
              mnemonic = KeyEvent.VK_K
              getAccessibleContext().accessibleDescription =
                  "Show shortcuts grouped by main window, gameplay, and debugger context"
              addActionListener { helpDialogs.showShortcuts(window, desktopActions) }
            })
        add(
            JMenuItem("About Coffee GB").apply {
              mnemonic = KeyEvent.VK_A
              getAccessibleContext().accessibleDescription =
                  "Show Coffee GB version, license, and source information"
              addActionListener {
                val version =
                    SwingMenu::class.java.`package`.implementationVersion ?: "development build"
                helpDialogs.showAbout(window, version)
              }
            })
      }

  private fun enableWhenEmulationActive(item: JMenuItem) {
    eventBus.register<EmulationStartedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, acceptRomLifecycle) {
        item.isEnabled = true
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      dispatchAcceptedRomLifecycle(null, acceptRomLifecycle) {
        item.isEnabled = false
      }
    }
  }

  internal fun updateRecentRoms() {
    recentRomsMenu.removeAll()
    for (romPath in properties.recentRoms.getPaths()) {
      val rom = romPath.toFile()
      val item = JMenuItem(rom.name)
      item.putClientProperty("html.disable", true)
      item.toolTipText = "<html>${escapeMenuHtml(romPath.toString())}</html>"
      item.accessibleContext.accessibleName = "Open recent ROM ${rom.name}"
      item.accessibleContext.accessibleDescription = romPath.toString()
      item.addActionListener { launchRom(rom, RomOpenSource.RECENT) }
      recentRomsMenu.add(item)
    }
    recentRomsMenu.isEnabled = recentRomsMenu.itemCount > 0
  }

  private fun launchRom(rom: File, source: RomOpenSource) =
      onOpenRom(rom.toPath(), source)

  private fun escapeMenuHtml(value: String): String =
      value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private fun confirmStopGame(): Boolean =
      proceedWithRomChange(properties.romChangeConfirmationPolicy, isRomRunning = true) {
        desktopDialogFactory.showDecision(
            window,
            DesktopDecisionSpec(
                title = "Stop game",
                heading = "Stop the running game?",
                message =
                    "Coffee GB will finish the current save operation before closing the game.",
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction(
                                "Stop game",
                                StopGameDecision.STOP,
                                mnemonic = KeyEvent.VK_S,
                                destructive = true,
                            ),
                        cancel =
                            DesktopDialogAction(
                                "Keep playing",
                                StopGameDecision.KEEP_PLAYING,
                            ),
                        defaultButton = DesktopDialogDefaultButton.CANCEL,
                    ),
                modality = DesktopOwnedDialogModality.DOCUMENT,
            )) == StopGameDecision.STOP
      }

  internal fun requestCloseGame() {
    if (confirmStopGame()) {
      eventBus.post(StopEmulationEvent())
    }
  }

  private companion object {
    // the 70 Cosmic Characters of Zok Zok Heroes, in Full Changer ID order (1-70)
    val COSMIC_CHARACTERS =
        arrayOf(
            "01 \u3042 Alkaline Powered",
            "02 \u3044 In Water",
            "03 \u3046 Ultra Runner",
            "04 \u3048 Aero Power",
            "05 \u304a Ochaapa",
            "06 \u304b Kaizer Edge",
            "07 \u304d King Batter",
            "08 \u304f Crash Car",
            "09 \u3051 Cellphone Tiger",
            "10 \u3053 Cup Ace",
            "11 \u3055 Sakanard",
            "12 \u3057 Thin Delta",
            "13 \u3059 Skateboard Rider",
            "14 \u305b Celery Star",
            "15 \u305d Cleaning Killer",
            "16 \u305f Takoyaki Kid",
            "17 \u3061 Chinkoman",
            "18 \u3064 Tsukai Stater",
            "19 \u3066 Teppangar",
            "20 \u3068 Tongararin",
            "21 \u306a Nagashiman",
            "22 \u306b Ninja",
            "23 \u306c Plushy-chan",
            "24 \u306d Screw Razor",
            "25 \u306e Nobel Brain",
            "26 \u306f Hard Hammer",
            "27 \u3072 Heat Man",
            "28 \u3075 Flame Gourmet",
            "29 \u3078 Hercules Army",
            "30 \u307b Hot Card",
            "31 \u307e Mr. Muscle",
            "32 \u307f Mist Water",
            "33 \u3080 Mushimushi Man",
            "34 \u3081 Megaaten",
            "35 \u3082 Mobile Robot X",
            "36 \u3084 Yaki Bird",
            "37 \u3086 Utron",
            "38 \u3088 Yo-Yo Mask",
            "39 \u3089 Radial Road",
            "40 \u308a Remote-Control Man",
            "41 \u308b Ruby Hook",
            "42 \u308c Retro Sounder",
            "43 \u308d Rocket Bastard",
            "44 \u308f Wild Sword",
            "45 \u304c Guts Lago",
            "46 \u304e Giniun",
            "47 \u3050 Great Fire",
            "48 \u3052 Gamemark",
            "49 \u3054 Gorilla Killa",
            "50 \u3056 The Climber",
            "51 \u3058 G Shark",
            "52 \u305a Zoom Laser",
            "53 \u305c Zenmai",
            "54 \u305e Elephant Shower",
            "55 \u3060 Diamond Mall",
            "56 \u3062 Digronyan",
            "57 \u3065 Ziza One",
            "58 \u3067 Danger Red",
            "59 \u3069 Dohatsuten",
            "60 \u3070 Balloon",
            "61 \u3073 Videoja",
            "62 \u3076 Boo Boo",
            "63 \u3079 Belt Jain",
            "64 \u307c Boat Ron",
            "65 \u3071 Perfect Sun",
            "66 \u3074 Pinspawn",
            "67 \u3077 Press Arm",
            "68 \u307a Pegasus Boy",
            "69 \u307d Pop Thunder",
            "70 \u3093 Ndjamenas",
        )
  }
}

/**
 * Builds the display controls around the same coordinator used by Preferences.
 *
 * Kept as a small seam so radio grouping, accelerators, and event-driven synchronization can be
 * exercised without constructing the rest of the desktop menu or touching the filesystem.
 */
internal fun createScreenMenu(
    displayController: DesktopDisplayController,
    eventBus: EventBus,
    keyboardBindings: () -> Collection<ApplicationSettings.KeyboardKey>,
    desktopActions: DesktopActionRegistry? = null,
): JMenu {
  check(SwingUtilities.isEventDispatchThread()) {
    "The Screen menu must be created on the Event Dispatch Thread"
  }
  val screenMenu = JMenu("Screen")
  val initial = displayController.current()

  /**
   * F11 is conventional and modifier-free, but emulator bindings are user-owned. If it is mapped
   * to a Game Boy button, the menu remains available and the accelerator is disabled instead of
   * delivering one physical press to two owners.
   */
  fun fullscreenAccelerator(): KeyStroke? =
      if (keyboardBindings().none { it.code == KeyEvent.VK_F11 }) {
        KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0)
      } else {
        null
      }

  val fullscreen =
      desktopActions?.let { JCheckBoxMenuItem(it[DesktopCommand.FULLSCREEN]) }
          ?: JCheckBoxMenuItem("Full Screen", initial.fullscreen).apply {
            accelerator = fullscreenAccelerator()
            addActionListener { displayController.setFullscreen(state) }
          }
  fullscreen.accessibleContext.accessibleName = "Full Screen"
  screenMenu.add(fullscreen)
  screenMenu.addSeparator()

  val scale = JMenu("Scale")
  screenMenu.add(scale)
  val scaleGroup = ButtonGroup()
  val supportedScales = listOf(1, 2, 4)
  val initialScale = supportedScales.minBy { kotlin.math.abs(it - initial.explicitScale) }
  val scaleItems = mutableMapOf<Int, JRadioButtonMenuItem>()

  fun addScale(explicitScale: Int) {
    val item = JRadioButtonMenuItem("${explicitScale}x", explicitScale == initialScale)
    item.addActionListener { displayController.selectWindowScale(explicitScale) }
    scaleGroup.add(item)
    scale.add(item)
    scaleItems[explicitScale] = item
  }
  supportedScales.forEach(::addScale)

  val rotate = JMenu("Rotate")
  screenMenu.add(rotate)
  val rotateGroup = ButtonGroup()
  val rotateItems = mutableMapOf<Int, JRadioButtonMenuItem>()
  for (rotation in ApplicationSettings.Rotation.entries) {
    val degrees = rotation.degrees
    val label = if (degrees == 0) "None" else "$degrees°"
    val item = JRadioButtonMenuItem(label, rotation == initial.rotation)
    item.addActionListener {
      displayController.update { current -> current.copy(rotation = rotation) }
    }
    rotateGroup.add(item)
    rotate.add(item)
    rotateItems[degrees] = item
  }

  val grayscale = JCheckBoxMenuItem("DMG grayscale", initial.grayscale)
  screenMenu.add(grayscale)
  grayscale.addActionListener {
    displayController.update { it.copy(grayscale = grayscale.state) }
  }

  val blending = JCheckBoxMenuItem("Blend adjacent frames", initial.blending)
  screenMenu.add(blending)
  blending.accessibleContext.accessibleDescription =
      "Blend consecutive display frames to approximate LCD persistence"
  blending.addActionListener {
    displayController.update { it.copy(blending = blending.state) }
  }

  val colorCorrection = JCheckBoxMenuItem("CGB color correction", initial.colorCorrection)
  screenMenu.add(colorCorrection)
  colorCorrection.accessibleContext.accessibleDescription =
      "Apply Game Boy Color display color correction"
  colorCorrection.addActionListener {
    displayController.update { it.copy(colorCorrection = colorCorrection.state) }
  }

  val showSgbBorder = JCheckBoxMenuItem("Show SGB border", initial.showSgbBorder)
  screenMenu.add(showSgbBorder)
  showSgbBorder.addActionListener {
    displayController.update { it.copy(showSgbBorder = showSgbBorder.state) }
  }

  fun synchronize(display: ApplicationSettings.Display) {
    val scaleKey = supportedScales.minBy { kotlin.math.abs(it - display.explicitScale) }
    scaleItems[scaleKey]?.isSelected = true
    rotateItems[display.rotation.degrees]?.isSelected = true
    if (desktopActions == null) fullscreen.accelerator = fullscreenAccelerator()
    fullscreen.state = display.fullscreen
    grayscale.state = display.grayscale
    blending.state = display.blending
    colorCorrection.state = display.colorCorrection
    showSgbBorder.state = display.showSgbBorder
  }
  eventBus.register<DisplaySettingsChangedEvent> { event ->
    dispatchSwingMutation { synchronize(event.display) }
  }

  return screenMenu
}

/** Builds the always-available debugger navigation without depending on an active ROM. */
internal fun createDebugMenu(actions: DebuggerMenuActions): JMenu {
  check(SwingUtilities.isEventDispatchThread()) {
    "The Debug menu must be created on the Event Dispatch Thread"
  }
  return JMenu("Debug").apply {
    mnemonic = KeyEvent.VK_D
    DebuggerWorkspaceTool.entries.forEach { tool ->
      add(
          JMenuItem(tool.title).apply {
            getAccessibleContext().accessibleDescription =
                "Show or raise the ${tool.title} debugger window"
            addActionListener { actions.showTool(tool) }
          })
    }
    addSeparator()
    add(
        JMenu("Layout").apply {
          mnemonic = KeyEvent.VK_L
          getAccessibleContext().accessibleDescription =
              "Show and arrange a built-in debugger window layout"
          DebuggerWorkspaceLayout.entries.forEach { layout ->
            add(
                JMenuItem(layout.title).apply {
                  getAccessibleContext().accessibleDescription =
                      "Show and arrange the ${layout.title} layout"
                  addActionListener { actions.applyLayout(layout) }
                })
          }
        })
  }
}
