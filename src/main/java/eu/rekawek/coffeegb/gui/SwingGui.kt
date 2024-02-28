package eu.rekawek.coffeegb.gui

import eu.rekawek.coffeegb.CartridgeOptions
import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.serial.SerialEndpoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.*
import javax.swing.*

class SwingGui(private val options: CartridgeOptions, debug: Boolean, private var currentRom: File?) {
    private val recentRoms: RecentRoms

    private val display: SwingDisplay

    private val controller: SwingController

    private val sound: AudioSystemSoundOutput

    private val properties: Properties

    private val console: Console?

    private var mainWindow: JFrame? = null

    private var pauseGame: JCheckBoxMenuItem? = null

    private var resetGame: JMenuItem? = null

    private var cart: Cartridge? = null

    private var gameboy: Gameboy? = null

    private var isRunning = false

    private var type: GameboyType

    init {
        properties = loadProperties()
        recentRoms = RecentRoms(properties)

        val displayScale = properties.getProperty("display.scale", "2").toInt()
        val displayGrayscale = properties.getProperty("display.grayscale", "false").toBoolean()
        val soundEnabled = properties.getProperty("sound.enabled", "true").toBoolean()
        type = GameboyType.valueOf(properties.getProperty("gameBoy.type", GameboyType.AUTOMATIC.name))

        display = SwingDisplay(displayScale, displayGrayscale)
        controller = SwingController(properties)
        sound = AudioSystemSoundOutput()
        sound.isEnabled = soundEnabled
        console = if (debug) Console() else null
    }

    @Throws(Exception::class)
    fun run() {
        System.setProperty("apple.awt.application.name", "Coffee GB")
        System.setProperty("sun.java2d.opengl", "true")
        SwingUtilities.invokeLater { this.startGui() }
    }

    fun startEmulation(rom: File?) {
        val newCart: Cartridge
        try {
            newCart = loadRom(rom)
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(mainWindow, e.message, "Can't load ROM", JOptionPane.ERROR_MESSAGE)
            return
        }
        stopEmulation()
        cart = newCart
        mainWindow!!.title = "Coffee GB: " + cart!!.title
        gameboy = Gameboy(cart, display, controller, sound, SerialEndpoint.NULL_ENDPOINT, console)
        gameboy!!.registerTickListener(TimingTicker())
        console?.setGameboy(gameboy)
        Thread(display).start()
        Thread(sound).start()
        Thread(gameboy).start()
        isRunning = true
        pauseGame!!.isEnabled = true
        pauseGame!!.state = false
        resetGame!!.isEnabled = true
        currentRom = rom
    }

    private fun stopEmulation() {
        if (!isRunning) {
            return
        }
        isRunning = false
        if (gameboy != null) {
            gameboy!!.stop()
            gameboy = null
        }
        if (cart != null) {
            cart!!.flushBattery()
            cart = null
        }
        sound.stopThread()
        display.stop()
        console?.setGameboy(null)
        pauseGame!!.isEnabled = false
        pauseGame!!.state = false
        resetGame!!.isEnabled = false
    }

    @Throws(IOException::class)
    private fun loadRom(rom: File?): Cartridge {
        return Cartridge(rom, options.isSupportBatterySaves, type, options.isUsingBootstrap)
    }

    private fun startGui() {
        display.scale = display.scale
        mainWindow = JFrame("Coffee GB")

        val fc = JFileChooser()
        if (properties.containsKey("rom_dir")) {
            fc.currentDirectory = File(properties.getProperty("rom_dir"))
        }

        val menuBar = JMenuBar()
        mainWindow!!.jMenuBar = menuBar

        val fileMenu = JMenu("File")
        menuBar.add(fileMenu)

        val load = JMenuItem("Load ROM")
        fileMenu.add(load)

        val recentRomsMenu = JMenu("Recent ROMs")
        fileMenu.add(recentRomsMenu)

        load.addActionListener { actionEvent: ActionEvent? ->
            val code = fc.showOpenDialog(load)
            if (code == JFileChooser.APPROVE_OPTION) {
                val rom = fc.selectedFile
                properties.setProperty("rom_dir", rom.parent)
                launchRom(recentRomsMenu, rom)
            }
        }

        updateRecentRoms(recentRomsMenu)

        val gameMenu = JMenu("Game")
        menuBar.add(gameMenu)

        pauseGame = JCheckBoxMenuItem("Pause", false)
        pauseGame!!.isEnabled = false
        pauseGame!!.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)
        gameMenu.add(pauseGame)
        pauseGame!!.addActionListener { actionEvent: ActionEvent? ->
            if (gameboy != null) {
                gameboy!!.setPaused(pauseGame!!.state)
            }
        }

        val typeMenu = JMenu("GameBoy type")
        gameMenu.add(typeMenu)

        for (type in GameboyType.entries.toTypedArray()) {
            val item = JCheckBoxMenuItem(type.label, type == this.type)
            item.addActionListener { actionEvent: ActionEvent? ->
                this.type = type
                if (isRunning) {
                    startEmulation(currentRom)
                }
                uncheckAllBut(typeMenu, item)
                properties.setProperty("gameBoy.type", type.name)
                saveProperties()
            }
            typeMenu.add(item)
        }

        resetGame = JMenuItem("Reset")
        gameMenu.add(resetGame)
        resetGame!!.addActionListener { actionEvent: ActionEvent? ->
            if (currentRom != null) {
                startEmulation(currentRom)
            }
        }
        resetGame!!.isEnabled = false

        val screenMenu = JMenu("Screen")
        menuBar.add(screenMenu)

        val scale = JMenu("Scale")
        screenMenu.add(scale)

        for (s in mutableListOf<Int>(1, 2, 4)) {
            val item = JCheckBoxMenuItem(s.toString() + "x", s == display.scale)
            item.addActionListener { actionEvent: ActionEvent? ->
                display.scale = s
                mainWindow!!.pack()
                uncheckAllBut(scale, item)
                properties.setProperty("display.scale", s.toString())
                saveProperties()
            }
            scale.add(item)
        }

        val grayscale = JCheckBoxMenuItem("DMG grayscale", display.isGrayscale)
        screenMenu.add(grayscale)
        grayscale.addActionListener { actionEvent: ActionEvent? ->
            display.isGrayscale = grayscale.state
            properties.setProperty("display.grayscale", grayscale.state.toString())
            saveProperties()
        }

        val audioMenu = JMenu("Audio")
        menuBar.add(audioMenu)

        val enableSound = JCheckBoxMenuItem("Enable", sound.isEnabled)
        audioMenu.add(enableSound)

        enableSound.addActionListener { actionEvent: ActionEvent? ->
            sound.isEnabled = enableSound.state
            properties.setProperty("sound.enabled", enableSound.state.toString())
            saveProperties()
        }

        mainWindow!!.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        mainWindow!!.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(windowEvent: WindowEvent) {
                stopGui()
            }
        })
        mainWindow!!.setLocationRelativeTo(null)

        mainWindow!!.contentPane = display
        mainWindow!!.isResizable = false
        mainWindow!!.isVisible = true
        mainWindow!!.pack()
        mainWindow!!.addKeyListener(controller)
        if (console != null) {
            Thread(console).start()
        }
        if (currentRom != null) {
            startEmulation(currentRom)
        }
    }

    private fun stopGui() {
        stopEmulation()
        console?.stop()
        System.exit(0)
    }

    private fun saveProperties() {
        try {
            FileWriter(PROPERTIES_FILE).use { writer ->
                properties.store(writer, "")
            }
        } catch (e: IOException) {
            LOG.error("Can't store properties", e)
        }
    }

    private fun launchRom(recentRomsMenu: JMenu, rom: File) {
        recentRoms.addRom(rom.absolutePath)
        saveProperties()
        updateRecentRoms(recentRomsMenu)
        startEmulation(rom)
    }

    private fun updateRecentRoms(recentRomsMenu: JMenu) {
        recentRomsMenu.removeAll()
        for (romPath in recentRoms.roms) {
            val rom = File(romPath)
            val item = JMenuItem(rom.name)
            item.addActionListener { actionEvent: ActionEvent? ->
                launchRom(recentRomsMenu, rom)
            }
            recentRomsMenu.add(item)
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(SwingGui::class.java)

        private val PROPERTIES_FILE = File(File(System.getProperty("user.home")), ".coffeegb.properties")

        @Throws(IOException::class)
        private fun loadProperties(): Properties {
            val props = Properties()
            if (PROPERTIES_FILE.exists()) {
                FileReader(PROPERTIES_FILE).use { reader ->
                    props.load(reader)
                }
            }
            return props
        }

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
