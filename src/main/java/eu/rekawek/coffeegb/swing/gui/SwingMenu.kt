package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.GameboyType
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.sgb.SgbDisplay
import eu.rekawek.coffeegb.sound.Sound
import eu.rekawek.coffeegb.swing.controller.Controller
import eu.rekawek.coffeegb.swing.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.swing.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.swing.controller.Controller.PauseEmulationEvent
import eu.rekawek.coffeegb.swing.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.swing.controller.Controller.RestoreSnapshotEvent
import eu.rekawek.coffeegb.swing.controller.Controller.ResumeEmulationEvent
import eu.rekawek.coffeegb.swing.controller.Controller.SaveSnapshotEvent
import eu.rekawek.coffeegb.swing.controller.Controller.SessionPauseSupportEvent
import eu.rekawek.coffeegb.swing.controller.Controller.SessionSnapshotSupportEvent
import eu.rekawek.coffeegb.swing.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.swing.controller.SnapshotSupport
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetGrayscaleEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetScaleEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerLostConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerStartedEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerStoppedEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StartClientEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StartServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.swing.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.properties.EmulatorProperties.Key.CgbGamesType
import eu.rekawek.coffeegb.swing.properties.EmulatorProperties.Key.DmgGamesType
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

  init {
    eventBus.register<SessionSnapshotSupportEvent> { snapshotSupport = it.snapshotSupport }
    eventBus.register<SessionPauseSupportEvent> { pauseSupport = it.enabled }
  }

  fun addMenu() {
    val menuBar = JMenuBar()

    menuBar.add(createFileMenu())
    menuBar.add(createGameMenu())
    menuBar.add(createSystemMenu())
    menuBar.add(createScreenMenu())
    menuBar.add(createAudioMenu())
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
    loadSnapshot.addActionListener { eventBus.post(RestoreSnapshotEvent(stateSlot)) }
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

    return gameMenu
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

    val grayscale = JCheckBoxMenuItem("DMG grayscale", properties.display.grayscale)
    screenMenu.add(grayscale)
    grayscale.addActionListener {
      eventBus.post(SetGrayscaleEvent(grayscale.state))
      properties.setProperty(EmulatorProperties.Key.DisplayGrayscale, grayscale.state.toString())
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
    linkMenu.add(startServer)
    startServer.addActionListener {
      if (startServer.state) {
        eventBus.post(StartServerEvent())
      } else {
        eventBus.post(StopServerEvent())
      }
    }

    val connectToServer = JCheckBoxMenuItem("Connect to server")
    linkMenu.add(connectToServer)
    connectToServer.addActionListener {
      if (connectToServer.state) {
        val host: String? =
            JOptionPane.showInputDialog(window, "Please enter server IP address", "127.0.0.1")
        if (host != null) {
          eventBus.post(StartClientEvent(host))
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
          window, "Can't open ${rom.name}: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
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
