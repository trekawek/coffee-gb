package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.swing.emulator.EmulatorStateListener
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*

class SwingMenu(private val emulator: SwingEmulator, private val properties: EmulatorProperties) {
    fun addMenu(window: JFrame) {
        val menuBar = JMenuBar()
        window.jMenuBar = menuBar

        menuBar.add(createFileMenu(window))
        menuBar.add(createGameMenu())
        menuBar.add(createScreenMenu(window))
        menuBar.add(createAudioMenu())
    }

    private fun createFileMenu(window: JFrame): JMenu {
        val fileMenu = JMenu("File")

        val load = JMenuItem("Load ROM")
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
        gameMenu.add(resetGame)
        resetGame.addActionListener { emulator.reset() }
        enableWhenEmulationActive(resetGame)

        val stop = JMenuItem("Stop")
        gameMenu.add(stop)
        stop.addActionListener { emulator.stopEmulation() }
        enableWhenEmulationActive(stop)

        return gameMenu
    }

    private fun createScreenMenu(window: JFrame): JMenu {
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
        audioMenu.add(enableSound)

        enableSound.addActionListener {
            emulator.soundController.enabled = enableSound.state
            properties.setProperty(EmulatorProperties.Key.SoundEnabled, enableSound.state.toString())
        }
        return audioMenu
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
        emulator.startEmulation(rom)
    }

    companion object {
        private fun uncheckAllBut(parent: JMenu, selectedItem: JCheckBoxMenuItem) {
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