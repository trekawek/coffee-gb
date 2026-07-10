package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.PauseEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.ResumeEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.SaveSnapshotEvent
import eu.rekawek.coffeegb.controller.Controller.SessionSnapshotSupportEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.SnapshotSupport
import eu.rekawek.coffeegb.controller.events.register
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
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties.Key.CgbGamesType
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties.Key.DmgGamesType
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.genie.AddPatches
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
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.KeyStroke

class SwingMenu(
    private val properties: EmulatorProperties,
    private val window: JFrame,
    private val eventBus: EventBus,
) {

  private var stateSlot = 0

  private var snapshotSupport: SnapshotSupport? = null

  private var pauseSupport: Boolean = false

  private var webcamSource: WebcamCameraSource? = null

  // the link port carries one device at a time: the netplay cable, the Barcode Boy or the
  // printer. These are the menu toggles for each; enabling one disconnects the rest.
  private lateinit var barcodeBoyItem: JCheckBoxMenuItem

  private lateinit var printerItem: JCheckBoxMenuItem

  private lateinit var startServerItem: JCheckBoxMenuItem

  private lateinit var connectToServerItem: JCheckBoxMenuItem

  init {
    eventBus.register<SessionSnapshotSupportEvent> { snapshotSupport = it.snapshotSupport }
    eventBus.register<Controller.SessionPauseSupportEvent> { pauseSupport = it.enabled }
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
      loadSnapshot.isEnabled = snapshotSupport?.snapshotAvailable(stateSlot) == true
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

    val gameGenie = JMenuItem("Cheat code")
    gameGenie.isEnabled = false
    gameMenu.add(gameGenie)
    gameGenie.addActionListener {
      val code: String? = JOptionPane.showInputDialog(window, "Please GameGenie / GameShark code")
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
        disconnectOtherLinkPeripherals(startServer)
        eventBus.post(StartServerEvent())
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
    val setConnected =
        fun(state: Boolean) {
          if (state) {
            connected.text = "\uD83D\uDFE2  Connected"
          } else {
            connected.text = "\uD83D\uDD34  Disconnected"
          }
        }
    connected.isEnabled = false
    setConnected(false)
    linkMenu.add(connected)

    eventBus.register<ClientConnectedToServerEvent> { setConnected(true) }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      setConnected(false)
      connectToServer.state = false
    }
    eventBus.register<ServerStartedEvent> { connectToServer.isEnabled = false }
    eventBus.register<ServerStoppedEvent> {
      startServer.state = false
      connectToServer.isEnabled = true
    }
    eventBus.register<ServerGotConnectionEvent> { setConnected(true) }
    eventBus.register<ServerLostConnectionEvent> { setConnected(false) }
    return linkMenu
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
