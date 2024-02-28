package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.controller.ButtonListener
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.io.AudioSystemSoundOutput
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.io.File
import javax.swing.JFrame
import javax.swing.JOptionPane

class SwingEmulator(
        private val console: Console?,
        controllerProperties: Map<Int, ButtonListener.Button>,
) {
    private val display: SwingDisplay = SwingDisplay(1, false)
    private val controller: SwingController = SwingController(controllerProperties)
    private val sound: AudioSystemSoundOutput = AudioSystemSoundOutput()

    val displayController = DisplayController(display)
    val soundController = SoundController(sound)

    private val emulatorStateListeners: List<EmulatorStateListener> = mutableListOf()
    private var gameboy: Gameboy? = null
    private var cart: Cartridge? = null
    private var currentRom: File? = null
    var isRunning = false

    fun bind(jFrame: JFrame) {
        jFrame.contentPane = display
        jFrame.addKeyListener(controller)
    }

    fun addEmulatorStateListener(listener: EmulatorStateListener) {
        emulatorStateListeners.addFirst(listener)
    }

    fun startEmulation(rom: File?) {
        val newCart = Cartridge(rom, true, gameboyType, false)
        stopEmulation()
        cart = newCart
        gameboy = Gameboy(cart, display, controller, sound, SerialEndpoint.NULL_ENDPOINT, console)
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
            gameboy?.setPaused(value)
        }

    fun reset() {
        stopEmulation()
        if (currentRom != null) {
            startEmulation(currentRom)
        }
    }
}