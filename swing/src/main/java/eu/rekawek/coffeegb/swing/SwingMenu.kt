package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.PauseEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.ResumeEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.SaveSnapshotEvent
import eu.rekawek.coffeegb.controller.Controller.SnapshotSavedEvent
import eu.rekawek.coffeegb.controller.Controller.SessionSnapshotSupportEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.SnapshotSupport
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerLostConnectionEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStartedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerStoppedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties.Key.BootstrapMode
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties.Key.CgbGamesType
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties.Key.DmgGamesType
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode.FAST_FORWARD
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode.NORMAL
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode.SKIP
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.CheatDatabase
import eu.rekawek.coffeegb.core.ir.FullChanger
import eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera
import eu.rekawek.coffeegb.swing.io.WebcamCameraSource
import eu.rekawek.coffeegb.core.genie.PatchFactory
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetBlendingEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetColorCorrectionEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetGrayscaleEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetRotationEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetScaleEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SwingMenu(
    private val properties: EmulatorProperties,
    private val window: JFrame,
    private val eventBus: EventBus,
) {

  private var stateSlot = 0

  private var snapshotSupport: SnapshotSupport? = null

  private var pauseSupport: Boolean = false

  private var webcamSource: WebcamCameraSource? = null

  private var currentRomFileName: String? = null

  private var pendingRomFileName: String? = null

  private var currentRomTitle: String? = null

  private val cheatDatabase: CheatDatabase by lazy { CheatDatabase.loadBundled() }

  // the link port carries one device at a time: the netplay cable, the Barcode Boy or the
  // printer. These are the menu toggles for each; enabling one disconnects the rest.
  private lateinit var barcodeBoyItem: JCheckBoxMenuItem

  private lateinit var printerItem: JCheckBoxMenuItem

  private lateinit var startServerItem: JCheckBoxMenuItem

  private lateinit var connectToServerItem: JCheckBoxMenuItem

  init {
    eventBus.register<SessionSnapshotSupportEvent> { snapshotSupport = it.snapshotSupport }
    eventBus.register<Controller.SessionPauseSupportEvent> { pauseSupport = it.enabled }
    eventBus.register<LoadRomEvent> { pendingRomFileName = it.rom.nameWithoutExtension }
    eventBus.register<EmulationStartedEvent> {
      currentRomFileName = pendingRomFileName ?: currentRomFileName
      pendingRomFileName = null
      currentRomTitle = it.romName
    }
    eventBus.register<LoadRomFailedEvent> {
      pendingRomFileName = null
      SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(
            window,
            "Can't open ${it.rom.name}: ${it.message}",
            "Error",
            JOptionPane.ERROR_MESSAGE,
        )
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      currentRomFileName = null
      currentRomTitle = null
    }
  }

  fun addMenu() {
    val menuBar = JMenuBar()

    menuBar.add(createFileMenu())
    menuBar.add(createGameMenu())
    menuBar.add(createSystemMenu())
    menuBar.add(createScreenMenu())
    menuBar.add(createAudioMenu())
    menuBar.add(createPeripheralsMenu())
    menuBar.add(createLinkMenu())
    window.jMenuBar = menuBar
  }

  private fun createFileMenu(): JMenu {
    val fileMenu = JMenu("File")

    val load = JMenuItem("Load ROM")
    load.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_L, KEY_MODIFIER)
    fileMenu.add(load)

    val recentRomsMenu = JMenu("Recent ROMs")
    fileMenu.add(recentRomsMenu)
    updateRecentRoms(recentRomsMenu)

    val fc = JFileChooser()
    properties.getProperty(EmulatorProperties.Key.RomDirectory, null)?.let {
      fc.currentDirectory = File(it)
    }

    load.addActionListener {
      val code = fc.showOpenDialog(load)
      if (code == JFileChooser.APPROVE_OPTION) {
        val rom = fc.selectedFile
        properties.setProperty(EmulatorProperties.Key.RomDirectory, rom.parent)
        launchRom(recentRomsMenu, rom)
      }
    }

    val quit = JMenuItem("Quit")
    quit.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Q, KEY_MODIFIER)
    fileMenu.add(quit)
    quit.addActionListener { window.dispose() }

    return fileMenu
  }

  private fun createGameMenu(): JMenu {
    val gameMenu = JMenu("Game")

    val pauseGame = JCheckBoxMenuItem("Pause", false)
    pauseGame.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)
    pauseGame.state = false
    pauseGame.isEnabled = false
    gameMenu.add(pauseGame)
    pauseGame.addActionListener {
      if (pauseGame.state) {
        eventBus.post(PauseEmulationEvent())
      } else {
        eventBus.post(ResumeEmulationEvent())
      }
    }
    eventBus.register<EmulationStartedEvent> {
      pauseGame.isEnabled = pauseSupport
      pauseGame.state = false
    }
    eventBus.register<EmulationStoppedEvent> { pauseGame.isEnabled = false }

    val saveSnapshot = JMenuItem("Save state")
    val loadSnapshot = JMenuItem("Load state")
    saveSnapshot.isEnabled = false
    saveSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)
    gameMenu.add(saveSnapshot)
    saveSnapshot.addActionListener {
      eventBus.post(SaveSnapshotEvent(stateSlot))
    }
    eventBus.register<SnapshotSavedEvent> {
      if (it.slot == stateSlot) {
        loadSnapshot.isEnabled = true
      }
    }
    eventBus.register<EmulationStartedEvent> { saveSnapshot.isEnabled = snapshotSupport != null }
    eventBus.register<EmulationStoppedEvent> { saveSnapshot.isEnabled = false }

    loadSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0)
    gameMenu.add(loadSnapshot)
    loadSnapshot.addActionListener { eventBus.post(Controller.RestoreSnapshotEvent(stateSlot)) }
    loadSnapshot.isEnabled = false

    eventBus.register<EmulationStartedEvent> {
      loadSnapshot.isEnabled = snapshotSupport?.snapshotAvailable(stateSlot) == true
    }
    eventBus.register<EmulationStoppedEvent> { loadSnapshot.isEnabled = false }

    val slotMenu = JMenu("State slot")
    gameMenu.add(slotMenu)
    for (i in (0..9)) {
      val slotItem = JCheckBoxMenuItem("Slot $i", i == stateSlot)
      slotItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, KEY_MODIFIER)
      slotItem.addActionListener {
        stateSlot = i
        loadSnapshot.isEnabled = snapshotSupport?.snapshotAvailable(i) == true
        uncheckAllBut(slotMenu, slotItem)
      }
      slotMenu.add(slotItem)
    }
    slotMenu.isEnabled = false
    eventBus.register<EmulationStartedEvent> { slotMenu.isEnabled = snapshotSupport != null }
    eventBus.register<EmulationStoppedEvent> { slotMenu.isEnabled = false }

    val resetGame = JMenuItem("Reset")
    resetGame.isEnabled = false
    resetGame.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_R, KEY_MODIFIER)
    gameMenu.add(resetGame)
    resetGame.addActionListener { eventBus.post(ResetEmulationEvent()) }
    enableWhenEmulationActive(resetGame)

    val stop = JMenuItem("Stop")
    stop.isEnabled = false
    stop.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, KEY_MODIFIER)
    gameMenu.add(stop)
    stop.addActionListener { eventBus.post(StopEmulationEvent()) }
    enableWhenEmulationActive(stop)

    val cheatsMenu = JMenu("Cheats")
    gameMenu.add(cheatsMenu)

    val cheatDatabaseItem = JMenuItem("Browse database")
    cheatDatabaseItem.isEnabled = false
    cheatsMenu.add(cheatDatabaseItem)
    cheatDatabaseItem.addActionListener { showCheatDatabase() }
    enableWhenEmulationActive(cheatDatabaseItem)

    val gameGenie = JMenuItem("Enter code")
    gameGenie.isEnabled = false
    cheatsMenu.add(gameGenie)
    gameGenie.addActionListener {
      val code: String? =
          JOptionPane.showInputDialog(
              window,
              "Enter a Game Genie or GameShark code (applies to the game in an Action Replay slot too):",
          )
      if (code != null) {
        try {
          val patches = PatchFactory.createPatches(code)
          eventBus.post(AddPatches(patches))
        } catch (e: Exception) {
          JOptionPane.showMessageDialog(
              window,
              "${e.message}",
              "Error",
              JOptionPane.ERROR_MESSAGE,
          )
        }
      }
    }
    enableWhenEmulationActive(gameGenie)

    return gameMenu
  }

  private fun showCheatDatabase() {
    try {
      val suggestedTitle = currentRomFileName ?: currentRomTitle ?: ""
      val gameTitle = JTextField(suggestedTitle, 32)
      val gameListModel = DefaultListModel<CheatDatabase.CheatList>()
      val gameList = JList(gameListModel)
      gameList.selectionMode = ListSelectionModel.SINGLE_SELECTION
      gameList.visibleRowCount = 10

      fun refreshGameList() {
        gameListModel.clear()
        val title = gameTitle.text.trim()
        if (title.isNotEmpty()) {
          cheatDatabase.findCheatLists(listOf(title), 25).forEach(gameListModel::addElement)
        }
        if (!gameListModel.isEmpty) {
          gameList.selectedIndex = 0
        }
      }

      gameTitle.document.addDocumentListener(
          object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshGameList()

            override fun removeUpdate(event: DocumentEvent) = refreshGameList()

            override fun changedUpdate(event: DocumentEvent) = refreshGameList()
          })

      val titlePanel = JPanel(BorderLayout(8, 0))
      titlePanel.add(JLabel("Game title:"), BorderLayout.WEST)
      titlePanel.add(gameTitle, BorderLayout.CENTER)

      val gameListScrollPane = JScrollPane(gameList)
      gameListScrollPane.preferredSize = Dimension(CHEAT_LIST_WIDTH, 220)
      installDoubleClickConfirm(gameList)

      val gamePicker = JPanel(BorderLayout(0, 8))
      gamePicker.add(titlePanel, BorderLayout.NORTH)
      gamePicker.add(gameListScrollPane, BorderLayout.CENTER)
      refreshGameList()

      val gamePickerResult =
          showListConfirmDialog(
              gamePicker,
              gameList,
              "Cheat database",
              gameTitle,
          )
      if (gamePickerResult != JOptionPane.OK_OPTION) {
        return
      }
      val selectedList = gameList.selectedValue ?: return

      val supportedCheats =
          selectedList.cheats().filter { cheat ->
            runCatching { PatchFactory.createPatches(cheat.code()) }.isSuccess
          }
      if (supportedCheats.isEmpty()) {
        JOptionPane.showMessageDialog(
            window,
            "This list contains no supported Game Genie or GameShark codes.",
            "Cheat database",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return
      }

      val cheatChoices =
          ToggleSelectionList(supportedCheats)
      cheatChoices.visibleRowCount = minOf(12, supportedCheats.size)
      cheatChoices.selectedIndex = 0
      cheatChoices.cellRenderer =
          object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
              val component =
                  super.getListCellRendererComponent(
                      list,
                      value,
                      index,
                      isSelected,
                      cellHasFocus,
                  )
              if (component is JLabel && value is CheatDatabase.Cheat) {
                component.text = cheatLabel(value)
                component.toolTipText = "${value.description()} (${value.code()})"
              }
              return component
            }
          }
      val scrollPane = JScrollPane(cheatChoices)
      scrollPane.preferredSize =
          Dimension(
              CHEAT_LIST_WIDTH,
              maxOf(80, minOf(CHEAT_LIST_MAX_HEIGHT, supportedCheats.size * 24 + 8)),
          )
      val cheatPickerResult =
          showListConfirmDialog(
              scrollPane,
              cheatChoices,
              "${selectedList.name()} — select cheats",
              cheatChoices,
          )
      if (
          cheatPickerResult == JOptionPane.OK_OPTION &&
              cheatChoices.selectedValuesList.isNotEmpty()) {
        val patches =
            cheatChoices.selectedValuesList.flatMap { PatchFactory.createPatches(it.code()) }
        eventBus.post(AddPatches(patches))
      }
    } catch (e: Exception) {
      JOptionPane.showMessageDialog(
          window,
          "Can't load the cheat database: ${e.message}",
          "Cheat database",
          JOptionPane.ERROR_MESSAGE,
      )
    }
  }

  private fun cheatLabel(cheat: CheatDatabase.Cheat): String {
    val code = cheat.code()
    val visibleCode =
        if (code.length <= CHEAT_CODE_MAX_LENGTH) code
        else "${code.take(CHEAT_CODE_MAX_LENGTH - 1)}…"
    return "${cheat.description()} ($visibleCode)"
  }

  private fun installDoubleClickConfirm(list: JList<*>) {
    list.addMouseListener(
        object : MouseAdapter() {
          override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount != 2 || !SwingUtilities.isLeftMouseButton(event)) {
              return
            }
            val index = list.locationToIndex(event.point)
            if (index < 0 || !list.getCellBounds(index, index).contains(event.point)) {
              return
            }
            val optionPane =
                SwingUtilities.getAncestorOfClass(JOptionPane::class.java, list) as? JOptionPane
                    ?: return
            optionPane.value = JOptionPane.OK_OPTION
          }
        })
  }

  private fun showListConfirmDialog(
      content: Component,
      list: JList<*>,
      title: String,
      initialFocus: Component,
  ): Int {
    val optionPane =
        JOptionPane(
            content,
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
        )
    val dialog = optionPane.createDialog(window, title)
    var initialFocusPending = true
    dialog.addWindowFocusListener(
        object : WindowAdapter() {
          override fun windowGainedFocus(event: WindowEvent) {
            if (initialFocusPending) {
              initialFocusPending = false
              SwingUtilities.invokeLater { initialFocus.requestFocusInWindow() }
            }
          }
        })
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val arrowKeyDispatcher =
        KeyEventDispatcher { event ->
          if (
              SwingUtilities.getWindowAncestor(event.component) !== dialog ||
                  (event.keyCode != KeyEvent.VK_UP && event.keyCode != KeyEvent.VK_DOWN)) {
            false
          } else {
            if (event.id == KeyEvent.KEY_PRESSED) {
              moveListSelection(list, if (event.keyCode == KeyEvent.VK_UP) -1 else 1)
            }
            true
          }
        }
    focusManager.addKeyEventDispatcher(arrowKeyDispatcher)
    try {
      dialog.isVisible = true
    } finally {
      focusManager.removeKeyEventDispatcher(arrowKeyDispatcher)
      dialog.dispose()
    }
    return optionPane.value as? Int ?: JOptionPane.CLOSED_OPTION
  }

  private fun moveListSelection(list: JList<*>, offset: Int) {
    if (list.model.size == 0) {
      return
    }
    val currentIndex = list.selectedIndex
    val nextIndex =
        if (currentIndex < 0) {
          if (offset > 0) 0 else list.model.size - 1
        } else {
          (currentIndex + offset).coerceIn(0, list.model.size - 1)
        }
    list.selectedIndex = nextIndex
    list.ensureIndexIsVisible(nextIndex)
  }

  private fun createPeripheralsMenu(): JMenu {
    val peripheralsMenu = JMenu("Peripherals")

    // the Game Boy Camera's webcam source is a cartridge sensor, not a link-port device, so
    // it is independent of the netplay/Barcode Boy/printer group below
    val camera = JCheckBoxMenuItem("Enable Game Boy Camera", false)
    peripheralsMenu.add(camera)
    camera.addActionListener {
      if (camera.state) {
        val source = WebcamCameraSource.open()
        if (source == null) {
          camera.state = false
          JOptionPane.showMessageDialog(
              window,
              "No webcam could be opened.",
              "Game Boy Camera",
              JOptionPane.ERROR_MESSAGE,
          )
        } else {
          webcamSource = source
          PocketCamera.setCameraSource(source)
        }
      } else {
        PocketCamera.setCameraSource(null)
        webcamSource?.close()
        webcamSource = null
      }
    }

    val printer = JCheckBoxMenuItem("Enable Game Boy Printer", false)
    printerItem = printer
    peripheralsMenu.add(printer)
    printer.addActionListener {
      eventBus.post(Controller.SetPrinterEvent(printer.state))
      if (printer.state) {
        disconnectOtherLinkPeripherals(printer)
      }
    }

    val arMenu = JMenu("Action Replay")
    peripheralsMenu.add(arMenu)

    val arGame = JMenuItem(arGameLabel())
    arMenu.add(arGame)
    arGame.addActionListener {
      val fc = JFileChooser()
      fc.dialogTitle = "Select the game cartridge for the Action Replay's slot"
      properties.getProperty(EmulatorProperties.Key.RomDirectory, null)?.let {
        fc.currentDirectory = File(it)
      }
      if (fc.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
        properties.setProperty(EmulatorProperties.Key.DatelSlotRom, fc.selectedFile.absolutePath)
        arGame.text = arGameLabel()
        JOptionPane.showMessageDialog(
            window,
            "The game will be inserted in the Action Replay's slot the next time an\n" +
                "Action Replay cartridge is loaded (File > Load ROM).",
            "Action Replay",
            JOptionPane.INFORMATION_MESSAGE,
        )
      }
    }

    val arClear = JMenuItem("Remove slot game")
    arMenu.add(arClear)
    arClear.addActionListener {
      properties.setProperty(EmulatorProperties.Key.DatelSlotRom, "")
      arGame.text = arGameLabel()
    }

    // the Full Changer, the IR toy of Zok Zok Heroes: picking a Cosmic Character sends
    // its transformation over the CGB infrared port (issue #94)
    val fullChanger = JMenuItem("Full Changer transformation...")
    peripheralsMenu.add(fullChanger)
    fullChanger.isEnabled = false
    enableWhenEmulationActive(fullChanger)
    fullChanger.addActionListener {
      val choice =
          JOptionPane.showInputDialog(
              window,
              "Cosmic Character to transform into:",
              "Full Changer",
              JOptionPane.PLAIN_MESSAGE,
              null,
              COSMIC_CHARACTERS,
              properties.getProperty(EmulatorProperties.Key.FullChangerCharacter, COSMIC_CHARACTERS[0]),
          )
      if (choice != null) {
        properties.setProperty(EmulatorProperties.Key.FullChangerCharacter, choice as String)
        eventBus.post(FullChanger.TransformEvent(COSMIC_CHARACTERS.indexOf(choice) + 1))
      }
    }

    val barcodeMenu = JMenu("Barcode Boy")
    peripheralsMenu.add(barcodeMenu)

    val barcodeBoy = JCheckBoxMenuItem("Enable Barcode Boy", false)
    barcodeBoyItem = barcodeBoy
    barcodeMenu.add(barcodeBoy)
    barcodeBoy.addActionListener {
      eventBus.post(Controller.SetBarcodeBoyEvent(barcodeBoy.state))
      if (barcodeBoy.state) {
        disconnectOtherLinkPeripherals(barcodeBoy)
      }
    }

    val scanBarcode = JMenuItem("Scan Barcode…")
    barcodeMenu.add(scanBarcode)
    scanBarcode.addActionListener {
      if (!barcodeBoy.state) {
        JOptionPane.showMessageDialog(
            window,
            "Enable \"Enable Barcode Boy\" first.",
            "Barcode Boy",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return@addActionListener
      }
      val code: String? =
          JOptionPane.showInputDialog(window, "Enter the 13-digit barcode number (JAN-13):")
      if (code != null) {
        val trimmed = code.trim()
        if (trimmed.length != 13 || !trimmed.all { it.isDigit() }) {
          JOptionPane.showMessageDialog(
              window,
              "The barcode must be exactly 13 digits.",
              "Barcode Boy",
              JOptionPane.ERROR_MESSAGE,
          )
        } else {
          eventBus.post(Controller.ScanBarcodeEvent(trimmed))
        }
      }
    }
    enableWhenEmulationActive(scanBarcode)

    return peripheralsMenu
  }

  /**
   * Only one device can sit on the link port at a time, so turning one on unplugs the others:
   * the netplay cable (server/client), the Barcode Boy and the printer.
   */
  private fun disconnectOtherLinkPeripherals(keep: JCheckBoxMenuItem) {
    if (barcodeBoyItem !== keep && barcodeBoyItem.state) {
      barcodeBoyItem.state = false
      eventBus.post(Controller.SetBarcodeBoyEvent(false))
    }
    if (printerItem !== keep && printerItem.state) {
      printerItem.state = false
      eventBus.post(Controller.SetPrinterEvent(false))
    }
    if (startServerItem !== keep && startServerItem.state) {
      startServerItem.state = false
      eventBus.post(StopServerEvent())
    }
    if (connectToServerItem !== keep && connectToServerItem.state) {
      connectToServerItem.state = false
      eventBus.post(StopClientEvent())
    }
  }

  private fun createSystemMenu(): JMenu {
    val systemMenu = JMenu("System")

    for (gameType in listOf(DmgGamesType, CgbGamesType)) {
      val (title, value) =
          when (gameType) {
            DmgGamesType -> "DMG games" to properties.system.dmgGamesType
            CgbGamesType -> "CGB games" to properties.system.cgbGamesType
            else -> throw IllegalStateException()
          }

      val menu = JMenu(title)
      systemMenu.add(menu)

      for (systemType in GameboyType.entries) {
        val item = JCheckBoxMenuItem(systemType.name, systemType == value)
        menu.add(item)
        item.addActionListener {
          properties.setProperty(gameType, systemType.name)
          uncheckAllBut(menu, item)
          eventBus.post(Controller.UpdatedSystemMappingEvent())
        }
      }
    }

    val bootstrapMenu = JMenu("Bootstrap")
    systemMenu.add(bootstrapMenu)
    for ((mode, title) in
        listOf(
            SKIP to "Skip",
            FAST_FORWARD to "Fast-forward",
            NORMAL to "Full",
        )) {
      val item = JCheckBoxMenuItem(title, mode == properties.system.bootstrapMode)
      bootstrapMenu.add(item)
      item.addActionListener {
        properties.setProperty(BootstrapMode, mode.name)
        uncheckAllBut(bootstrapMenu, item)
        eventBus.post(Controller.UpdatedSystemMappingEvent())
      }
    }

    return systemMenu
  }

  private fun createScreenMenu(): JMenu {
    val screenMenu = JMenu("Screen")

    val scale = JMenu("Scale")
    screenMenu.add(scale)

    for (s in mutableListOf(1, 2, 4)) {
      val item = JCheckBoxMenuItem(s.toString() + "x", s == properties.display.scale)
      item.addActionListener {
        eventBus.post(SetScaleEvent(s))
        uncheckAllBut(scale, item)
        properties.setProperty(EmulatorProperties.Key.DisplayScale, s.toString())
      }
      scale.add(item)
    }

    val rotate = JMenu("Rotate")
    screenMenu.add(rotate)

    for (deg in mutableListOf(0, 90, 180, 270)) {
      val label = if (deg == 0) "None" else "$deg°"
      val item = JCheckBoxMenuItem(label, deg == properties.display.rotation)
      item.addActionListener {
        eventBus.post(SetRotationEvent(deg))
        uncheckAllBut(rotate, item)
        properties.setProperty(EmulatorProperties.Key.DisplayRotation, deg.toString())
      }
      rotate.add(item)
    }

    val grayscale = JCheckBoxMenuItem("DMG grayscale", properties.display.grayscale)
    screenMenu.add(grayscale)
    grayscale.addActionListener {
      eventBus.post(SetGrayscaleEvent(grayscale.state))
      properties.setProperty(EmulatorProperties.Key.DisplayGrayscale, grayscale.state.toString())
    }

    val colorCorrection =
        JCheckBoxMenuItem("CGB color correction", properties.display.colorCorrection)
    screenMenu.add(colorCorrection)
    colorCorrection.addActionListener {
      eventBus.post(SetColorCorrectionEvent(colorCorrection.state))
      properties.setProperty(
          EmulatorProperties.Key.DisplayColorCorrection, colorCorrection.state.toString())
    }

    val blending = JCheckBoxMenuItem("LCD ghosting (frame blend)", properties.display.blending)
    screenMenu.add(blending)
    blending.addActionListener {
      eventBus.post(SetBlendingEvent(blending.state))
      properties.setProperty(EmulatorProperties.Key.DisplayBlending, blending.state.toString())
    }

    val showSgbBorder = JCheckBoxMenuItem("Show SGB border", properties.display.showSgbBorder)
    screenMenu.add(showSgbBorder)
    showSgbBorder.addActionListener {
      properties.setProperty(EmulatorProperties.Key.ShowSgbBorder, showSgbBorder.state.toString())
      eventBus.post(SgbDisplay.SetSgbBorder(showSgbBorder.state))
    }

    return screenMenu
  }

  private fun createAudioMenu(): JMenu {
    val audioMenu = JMenu("Audio")

    val enableSound = JCheckBoxMenuItem("Enable", properties.sound.soundEnabled)
    enableSound.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, KEY_MODIFIER)
    audioMenu.add(enableSound)

    enableSound.addActionListener {
      eventBus.post(Sound.SoundEnabledEvent(enableSound.state))
      properties.setProperty(EmulatorProperties.Key.SoundEnabled, enableSound.state.toString())
    }
    return audioMenu
  }

  private fun createLinkMenu(): JMenu {
    val linkMenu = JMenu("Link")

    val startServer = JCheckBoxMenuItem("Start server")
    startServerItem = startServer
    linkMenu.add(startServer)
    startServer.addActionListener {
      if (startServer.state) {
        val choices = arrayOf("Normal link (2 players)", "Four-player adapter (4 players)")
        val selected =
            JOptionPane.showOptionDialog(
                window,
                "Select the link hardware to emulate",
                "Start link server",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                choices,
                choices[0],
            )
        val mode =
            when (selected) {
              0 -> LinkMode.NORMAL
              1 -> LinkMode.FOUR_PLAYER_ADAPTER
              else -> null
            }
        if (mode != null) {
          disconnectOtherLinkPeripherals(startServer)
          eventBus.post(StartServerEvent(mode))
        } else {
          startServer.state = false
        }
      } else {
        eventBus.post(StopServerEvent())
      }
    }

    val connectToServer = JCheckBoxMenuItem("Connect to server")
    connectToServerItem = connectToServer
    linkMenu.add(connectToServer)
    connectToServer.addActionListener {
      if (connectToServer.state) {
        val host: String? =
            JOptionPane.showInputDialog(window, "Please enter server IP address", "127.0.0.1")
        if (host != null) {
          disconnectOtherLinkPeripherals(connectToServer)
          eventBus.post(StartClientEvent(host))
        } else {
          connectToServer.state = false
        }
      } else {
        eventBus.post(StopClientEvent())
      }
    }

    val connected = JCheckBoxMenuItem()
    val setStatus =
        fun(text: String, active: Boolean) {
          connected.text = if (active) "\uD83D\uDFE2  $text" else "\uD83D\uDD34  $text"
        }
    connected.isEnabled = false
    setStatus("Disconnected", false)
    linkMenu.add(connected)

    eventBus.register<ConnectionController.ClientHandshakeCompletedEvent> {
      val mode = if (it.mode == LinkMode.NORMAL) "normal link" else "four-player adapter"
      setStatus("Waiting as Player ${it.player + 1} ($mode)", false)
    }
    eventBus.register<ClientConnectedToServerEvent> {
      val mode = if (it.mode == LinkMode.NORMAL) "normal link" else "four-player adapter"
      setStatus("Connected as Player ${it.player + 1} ($mode)", true)
    }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      setStatus("Disconnected", false)
      connectToServer.state = false
    }
    eventBus.register<ServerStartedEvent> {
      connectToServer.isEnabled = false
      setStatus("Waiting for players (0/${it.mode.playerCount - 1})", false)
    }
    eventBus.register<ServerStoppedEvent> {
      startServer.state = false
      connectToServer.isEnabled = true
      setStatus("Disconnected", false)
    }
    eventBus.register<ConnectionController.ServerPlayerCountEvent> {
      if (it.connected < it.required) {
        setStatus("Waiting for players (${it.connected}/${it.required})", false)
      }
    }
    eventBus.register<ServerGotConnectionEvent> {
      val mode = if (it.mode == LinkMode.NORMAL) "normal link" else "four-player adapter"
      setStatus("Connected as Player 1 ($mode)", true)
    }
    eventBus.register<ServerLostConnectionEvent> { setStatus("Disconnected", false) }
    return linkMenu
  }

  private fun arGameLabel(): String {
    val path = properties.getProperty(EmulatorProperties.Key.DatelSlotRom, null)
    val name = if (path.isNullOrEmpty()) "(none)" else File(path).name
    return "Slot game: $name…"
  }

  private fun enableWhenEmulationActive(item: JMenuItem) {
    eventBus.register<EmulationStartedEvent> { item.isEnabled = true }
    eventBus.register<EmulationStoppedEvent> { item.isEnabled = false }
  }

  private fun updateRecentRoms(recentRomsMenu: JMenu) {
    recentRomsMenu.removeAll()
    for (romPath in properties.recentRoms.getRoms()) {
      val rom = File(romPath)
      val item = JMenuItem(rom.name)
      item.addActionListener { launchRom(recentRomsMenu, rom) }
      recentRomsMenu.add(item)
    }
  }

  private fun launchRom(recentRomsMenu: JMenu, rom: File) {
    properties.recentRoms.addRom(rom.absolutePath)
    updateRecentRoms(recentRomsMenu)
    try {
      eventBus.post(LoadRomEvent(rom))
    } catch (e: Exception) {
      e.printStackTrace()
      JOptionPane.showMessageDialog(
          window,
          "Can't open ${rom.name}: ${e.message}",
          "Error",
          JOptionPane.ERROR_MESSAGE,
      )
    }
  }

  private companion object {
    const val CHEAT_CODE_MAX_LENGTH = 36

    const val CHEAT_LIST_WIDTH = 600

    const val CHEAT_LIST_MAX_HEIGHT = 300

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

    val KEY_MODIFIER: Int =
        if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
          KeyEvent.META_DOWN_MASK
        } else {
          KeyEvent.CTRL_DOWN_MASK
        }

    fun uncheckAllBut(parent: JMenu, selectedItem: JCheckBoxMenuItem) {
      for (i in 0 until parent.itemCount) {
        val item = parent.getItem(i)
        if (item === selectedItem) {
          continue
        }
        (item as JCheckBoxMenuItem).state = false
      }
    }
  }
}
