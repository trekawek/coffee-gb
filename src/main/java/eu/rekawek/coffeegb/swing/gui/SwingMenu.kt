package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.swing.emulator.EmulatorStateListener
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*

class SwingMenu(private val emulator: SwingEmulator, private val properties: EmulatorProperties, private val window: JFrame) {

    private var stateSlot = 0

    fun addMenu() {
        val menuBar = JMenuBar()
        window.jMenuBar = menuBar

        menuBar.add(createFileMenu())
        menuBar.add(createGameMenu())
        menuBar.add(createScreenMenu())
        menuBar.add(createAudioMenu())
        menuBar.add(createLinkMenu())
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
        properties.getProperty(EmulatorProperties.Key.RomDirectory, null)?.let { fc.currentDirectory = File(it) }

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
        pauseGame.addActionListener { emulator.paused = pauseGame.state }
        enableWhenEmulationActive(pauseGame)
        emulator.addEmulatorStateListener(object : EmulatorStateListener {
            override fun onEmulationStart(cartTitle: String) {
                pauseGame.isEnabled = true
                pauseGame.state = false
            }

            override fun onEmulationStop() {
                pauseGame.isEnabled = false
            }
        })

        val saveSnapshot = JMenuItem("Save state")
        val loadSnapshot = JMenuItem("Load state")
        saveSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)
        gameMenu.add(saveSnapshot)
        saveSnapshot.addActionListener {
            emulator.saveSnapshot(stateSlot)
            loadSnapshot.isEnabled = emulator.snapshotAvailable(stateSlot)
        }
        enableWhenEmulationActive(saveSnapshot)

        loadSnapshot.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0)
        gameMenu.add(loadSnapshot)
        loadSnapshot.addActionListener { emulator.restoreSnapshot(stateSlot) }
        loadSnapshot.isEnabled = false
        emulator.addEmulatorStateListener(object : EmulatorStateListener {
            override fun onEmulationStart(cartTitle: String) {
                loadSnapshot.isEnabled = emulator.snapshotAvailable(stateSlot)
            }
        })

        val slotMenu = JMenu("State slot")
        gameMenu.add(slotMenu)
        for (i in (0..9)) {
            val slotItem = JCheckBoxMenuItem("Slot $i", i == stateSlot)
            slotItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, KEY_MODIFIER)
            slotItem.addActionListener {
                stateSlot = i
                loadSnapshot.isEnabled = emulator.snapshotAvailable(i)
                uncheckAllBut(slotMenu, slotItem)
            }
            slotMenu.add(slotItem)
        }

        val typeMenu = JMenu("GameBoy type")
        gameMenu.add(typeMenu)

        for (type in Cartridge.GameboyType.entries) {
            val item = JCheckBoxMenuItem(type.label, type == emulator.gameboyType)
            item.addActionListener {
                emulator.gameboyType = type
                uncheckAllBut(typeMenu, item)
                properties.setProperty(EmulatorProperties.Key.GameboyType, type.name)
            }
            typeMenu.add(item)
        }

        val resetGame = JMenuItem("Reset")
        resetGame.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_R, KEY_MODIFIER)
        gameMenu.add(resetGame)
        resetGame.addActionListener { emulator.reset() }
        enableWhenEmulationActive(resetGame)

        val stop = JMenuItem("Stop")
        stop.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, KEY_MODIFIER)
        gameMenu.add(stop)
        stop.addActionListener { emulator.stopEmulation() }
        enableWhenEmulationActive(stop)

        return gameMenu
    }

    private fun createScreenMenu(): JMenu {
        val screenMenu = JMenu("Screen")

        val scale = JMenu("Scale")
        screenMenu.add(scale)

        for (s in mutableListOf(1, 2, 4)) {
            val item = JCheckBoxMenuItem(s.toString() + "x", s == emulator.displayController.scale)
            item.addActionListener {
                emulator.displayController.scale = s
                window.pack()
                uncheckAllBut(scale, item)
                properties.setProperty(EmulatorProperties.Key.DisplayScale, s.toString())
            }
            scale.add(item)
        }

        val grayscale = JCheckBoxMenuItem("DMG grayscale", emulator.displayController.grayscale)
        screenMenu.add(grayscale)
        grayscale.addActionListener {
            emulator.displayController.grayscale = grayscale.state
            properties.setProperty(EmulatorProperties.Key.DisplayGrayscale, grayscale.state.toString())
        }
        return screenMenu
    }

    private fun createAudioMenu(): JMenu {
        val audioMenu = JMenu("Audio")

        val enableSound = JCheckBoxMenuItem("Enable", emulator.soundController.enabled)
        enableSound.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, KEY_MODIFIER)
        audioMenu.add(enableSound)

        enableSound.addActionListener {
            emulator.soundController.enabled = enableSound.state
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
                emulator.serialController.startServer()
            } else {
                emulator.serialController.stop()
            }
        }

        val connectToServer = JCheckBoxMenuItem("Connect to server")
        linkMenu.add(connectToServer)
        connectToServer.addActionListener {
            if (connectToServer.state) {
                val host = JOptionPane.showInputDialog(window, "Please enter server IP address", "127.0.0.1")
                emulator.serialController.startClient(host)
            } else {
                emulator.serialController.stop()
            }
        }

        return linkMenu
    }

    private fun enableWhenEmulationActive(item: JMenuItem) {
        item.isEnabled = emulator.isRunning
        emulator.addEmulatorStateListener(object : EmulatorStateListener {
            override fun onEmulationStart(cartTitle: String) {
                item.isEnabled = true
            }

            override fun onEmulationStop() {
                item.isEnabled = false
            }
        })
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
            emulator.startEmulation(rom)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(window, "Can't open ${rom.name}: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private companion object {
        val KEY_MODIFIER: Int = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
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