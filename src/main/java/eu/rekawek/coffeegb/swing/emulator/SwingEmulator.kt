package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.controller.ButtonListener
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.swing.io.AudioSystemSoundOutput
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.serial.SerialEndpointWrapper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import javax.swing.JFrame

class SwingEmulator(
        private val console: Console?,
        controllerProperties: Map<Int, ButtonListener.Button>,
) {
    private val display: SwingDisplay = SwingDisplay(1, false)
    private val controller: SwingController = SwingController(controllerProperties)
    private val sound: AudioSystemSoundOutput = AudioSystemSoundOutput()
    private val serial: SerialEndpointWrapper = SerialEndpointWrapper()

    val displayController = DisplayController(display)
    val soundController = SoundController(sound)
    val serialController = SerialController(serial)

    private val emulatorStateListeners: MutableList<EmulatorStateListener> = mutableListOf()
    private var gameboy: Gameboy? = null
    private var cart: Cartridge? = null
    private var currentRom: File? = null
    var isRunning = false

    fun bind(jFrame: JFrame) {
        jFrame.contentPane = display
        jFrame.addKeyListener(controller)
    }

    fun addEmulatorStateListener(listener: EmulatorStateListener) {
        emulatorStateListeners.add(listener)
    }

    fun startEmulation(rom: File?, gameboySnapshot: Gameboy? = null) {
        val newCart = Cartridge(rom, true, gameboyType, false)
        stopEmulation()
        cart = newCart
        gameboy = gameboySnapshot ?: Gameboy(cart)
        gameboy!!.init(display, sound, controller, serial, console)
        gameboy!!.registerTickListener(TimingTicker())
        Thread(display).start()
        Thread(sound).start()
        Thread(gameboy).start()
        isRunning = true
        currentRom = rom
        console?.setGameboy(gameboy)
        emulatorStateListeners.forEach { it.onEmulationStart(cart!!.title) }
    }

    fun stopEmulation() {
        if (!isRunning) {
            return
        }
        isRunning = false
        gameboy?.stop()
        gameboy = null
        cart?.flushBattery()
        cart = null
        sound.stopThread()
        display.stop()
        console?.setGameboy(null)
        emulatorStateListeners.forEach { it.onEmulationStop() }
    }

    fun snapshotAvailable(slot: Int) = getSnapshotFile(slot)?.exists() ?: false

    fun saveSnapshot(slot: Int) {
        if (!isRunning) {
            return
        }
        val gameboy = this.gameboy ?: return
        val originalPauseState = gameboy.isPaused
        if (!gameboy.isPaused) {
            gameboy.pause()
        }
        val snapshotFile = getSnapshotFile(slot) ?: return
        ObjectOutputStream(FileOutputStream(snapshotFile)).use {
            it.writeObject(gameboy)
        }
        if (!originalPauseState) {
            gameboy.resume()
        }
    }

    fun restoreSnapshot(slot: Int) {
        if (currentRom == null) {
            return
        }
        val snapshotFile = getSnapshotFile(slot) ?: return
        if (!snapshotFile.exists()) {
            return
        }
        stopEmulation()
        val gameboy = ObjectInputStream(FileInputStream(snapshotFile)).use {
            it.readObject() as Gameboy
        }
        startEmulation(currentRom, gameboy)
    }

    private fun getSnapshotFile(slot: Int): File? {
        val rom = currentRom ?: return null
        val parentDir = rom.parentFile
        val name = rom.nameWithoutExtension + ".sn${slot}"
        return parentDir.resolve(name)
    }

    var gameboyType: GameboyType = GameboyType.AUTOMATIC
        set(value) {
            field = value
            if (isRunning) {
                reset();
            }
        }

    var paused: Boolean
        get() = gameboy?.isPaused ?: false
        set(value) {
            if (value) {
                gameboy?.pause()
            } else {
                gameboy?.resume()
            }
        }

    fun reset() {
        stopEmulation()
        if (currentRom != null) {
            startEmulation(currentRom)
        }
    }
}