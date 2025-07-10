package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.sound.Sound
import eu.rekawek.coffeegb.swing.emulator.SerialController
import eu.rekawek.coffeegb.swing.emulator.SerialController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.ServerLostConnectionEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.ServerStartedEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.ServerStoppedEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.StartClientEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.StartServerEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.StopClientEvent
import eu.rekawek.coffeegb.swing.emulator.SerialController.StopServerEvent
import eu.rekawek.coffeegb.swing.emulator.SnapshotManager
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.EmulationStoppedEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.PauseEmulationEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.ResetEmulationEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.RestoreSnapshotEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.ResumeEmulationEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.SaveSnapshotEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.SetGameboyType
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.StartEmulationEvent
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.StopEmulationEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetGrayscaleEvent
import eu.rekawek.coffeegb.swing.io.SwingDisplay.SetScaleEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*

class SwingMenu(
    private val properties: EmulatorProperties,
    private val window: JFrame,
    private val eventBus: EventBus,
    private val snapshotManager: SnapshotManager,
) {

  private var stateSlot = 0

  fun addMenu() {
    val menuBar = JMenuBar()

    menuBar.add(createFileMenu())
    menuBar.add(createGameMenu())
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
    gameMenu.add(pauseGame)
    pauseGame.addActionListener {
      if (pauseGame.state) {
        eventBus.post(PauseEmulationEvent())
      } else {
        eventBus.post(ResumeEmulationEvent())
      }
    }
    enableWhenEmulationActive(pauseGame)
    eventBus.register<EmulationStartedEvent> {
      pauseGame.isEnabled = true
      pauseGame.state = false
    }
    eventBus.register<EmulationStoppedEvent> { pauseGame.isEnabled = false }

    val saveSnapshot = JMenuItem("Save state")
    val loadSnapshot = JMenuItem("Load state")
    saveSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)
    gameMenu.add(saveSnapshot)
    saveSnapshot.addActionListener {
      eventBus.post(SaveSnapshotEvent(stateSlot))
      loadSnapshot.isEnabled = snapshotManager.snapshotAvailable(stateSlot)
    }
    enableWhenEmulationActive(saveSnapshot)

    loadSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0)
    gameMenu.add(loadSnapshot)
    loadSnapshot.addActionListener { eventBus.post(RestoreSnapshotEvent(stateSlot)) }
    loadSnapshot.isEnabled = false

    eventBus.register<EmulationStartedEvent> {
      loadSnapshot.isEnabled = snapshotManager.snapshotAvailable(stateSlot)
    }

    val slotMenu = JMenu("State slot")
    gameMenu.add(slotMenu)
    for (i in (0..9)) {
      val slotItem = JCheckBoxMenuItem("Slot $i", i == stateSlot)
      slotItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, KEY_MODIFIER)
      slotItem.addActionListener {
        stateSlot = i
        loadSnapshot.isEnabled = snapshotManager.snapshotAvailable(i)
        uncheckAllBut(slotMenu, slotItem)
      }
      slotMenu.add(slotItem)
    }

    val typeMenu = JMenu("GameBoy type")
    gameMenu.add(typeMenu)

    for (type in Cartridge.GameboyType.entries) {
      val item = JCheckBoxMenuItem(type.label, type == properties.gameboy.gameboyType)
      item.addActionListener {
        eventBus.post(SetGameboyType(type))
        uncheckAllBut(typeMenu, item)
        properties.setProperty(EmulatorProperties.Key.GameboyType, type.name)
      }
      typeMenu.add(item)
    }

    val resetGame = JMenuItem("Reset")
    resetGame.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_R, KEY_MODIFIER)
    gameMenu.add(resetGame)
    resetGame.addActionListener { eventBus.post(ResetEmulationEvent()) }
    enableWhenEmulationActive(resetGame)

    val stop = JMenuItem("Stop")
    stop.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, KEY_MODIFIER)
    gameMenu.add(stop)
    stop.addActionListener { eventBus.post(StopEmulationEvent()) }
    enableWhenEmulationActive(stop)

    return gameMenu
  }

  private fun createScreenMenu(): JMenu {
    val screenMenu = JMenu("Screen")

    val scale = JMenu("Scale")
    screenMenu.add(scale)

    for (s in mutableListOf(1, 2, 4)) {
      val item = JCheckBoxMenuItem(s.toString() + "x", s == properties.display.scale)
      item.addActionListener {
        eventBus.post(SetScaleEvent(s))
        window.pack()
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
        val host =
            JOptionPane.showInputDialog(window, "Please enter server IP address", "127.0.0.1")
        eventBus.post(StartClientEvent(host))
      } else {
        eventBus.post(StopClientEvent())
      }
    }

    val connected = JCheckBoxMenuItem("Connected")
    connected.isEnabled = false
    linkMenu.add(connected)

    eventBus.register<ClientConnectedToServerEvent> { connected.state = true }
    eventBus.register<SerialController.ClientDisconnectedFromServerEvent> {
      connected.state = false
      connectToServer.state = false
    }
    eventBus.register<ServerStartedEvent> { connectToServer.isEnabled = false }
    eventBus.register<ServerStoppedEvent> {
      startServer.state = false
      connectToServer.isEnabled = true
    }
    eventBus.register<ServerGotConnectionEvent> { connected.state = true }
    eventBus.register<ServerLostConnectionEvent> { connected.state = false }
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
      eventBus.post(StartEmulationEvent(rom))
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
