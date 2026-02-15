package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.cpu.Opcodes
import eu.rekawek.coffeegb.core.cpu.Registers
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.sound.Sound
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class Agent(romFile: File) {
    private val rom = Rom(romFile)
    private val config = Gameboy.GameboyConfiguration(rom)
    private val eventBus = EventBusImpl(null, null, false)
    private val gameboy = config.build()

    private val frameQueue = LinkedBlockingQueue<IntArray>()
    private val soundQueue = LinkedBlockingQueue<IntArray>()

    init {
        gameboy.init(eventBus, eu.rekawek.coffeegb.core.serial.SerialEndpoint.NULL_ENDPOINT, null)
        eventBus.register({ event ->
            val pixels = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
            event.toRgb(pixels, false)
            frameQueue.put(pixels)
            if (frameQueue.size > 10) frameQueue.poll()
        }, Display.DmgFrameReadyEvent::class.java)
        eventBus.register({ event ->
            val pixels = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
            event.toRgb(pixels)
            frameQueue.put(pixels)
            if (frameQueue.size > 10) frameQueue.poll()
        }, Display.GbcFrameReadyEvent::class.java)
        eventBus.register({ event ->
            soundQueue.put(event.buffer.clone())
            if (soundQueue.size > 100) soundQueue.poll()
        }, Sound.SoundSampleEvent::class.java)
    }

    fun tick() {
        gameboy.tick()
    }

    fun step() {
        val cpu = gameboy.cpu
        // Tick at least once to move away from current state
        gameboy.tick()
        // Continue ticking until we reach the start of the next instruction or a halt state
        while (cpu.state != eu.rekawek.coffeegb.core.cpu.Cpu.State.OPCODE &&
            cpu.state != eu.rekawek.coffeegb.core.cpu.Cpu.State.HALTED &&
            cpu.state != eu.rekawek.coffeegb.core.cpu.Cpu.State.STOPPED
        ) {
            gameboy.tick()
        }
    }

    fun runUntilFrame(maxTicks: Int = Gameboy.TICKS_PER_SEC) {
        var ticks = 0
        while (!gameboy.tick() && ticks < maxTicks) {
            ticks++
        }
    }

    fun runTicks(ticks: Int) {
        repeat(ticks) {
            gameboy.tick()
        }
    }

    fun isLcdEnabled(): Boolean {
        return gameboy.gpu.isLcdEnabled
    }

    fun getLcdc(): Int {
        return gameboy.gpu.lcdc.get()
    }
    
    fun getLY(): Int {
        return gameboy.gpu.registers.get(eu.rekawek.coffeegb.core.gpu.GpuRegister.LY)
    }

    fun getFrame(): BufferedImage? {
        val pixels = frameQueue.poll() ?: return null
        val img = BufferedImage(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, pixels, 0, Display.DISPLAY_WIDTH)
        return img
    }

    fun getAudio(): List<IntArray> {
        val samples = mutableListOf<IntArray>()
        soundQueue.drainTo(samples)
        return samples
    }

    fun getRegisters(): Registers {
        return gameboy.cpu.registers
    }

    fun getRegistersObj(): eu.rekawek.coffeegb.core.cpu.Registers {
        return gameboy.cpu.registers
    }

    fun getSP(): Int {
        return gameboy.cpu.registers.sp
    }

    fun getByte(address: Int): Int {
        return gameboy.addressSpace.getByte(address)
    }

    fun pressButton(button: Button) {
        eventBus.post(ButtonPressEvent(button))
    }

    fun releaseButton(button: Button) {
        eventBus.post(ButtonReleaseEvent(button))
    }

    fun disassemble(address: Int): String {
        val mmu = gameboy.addressSpace
        val opcode1 = mmu.getByte(address)
        if (opcode1 == 0xcb) {
            val opcode2 = mmu.getByte(address + 1)
            val opcode = Opcodes.EXT_COMMANDS[opcode2]
            return String.format("%04X: CB %02X %s", address, opcode2, opcode?.label ?: "UNKNOWN")
        } else {
            val opcode = Opcodes.COMMANDS[opcode1]
            var label = opcode?.label ?: "UNKNOWN"
            val length = opcode?.operandLength ?: 0
            val bytes = mutableListOf<String>()
            bytes.add(String.format("%02X", opcode1))
            if (length >= 1) {
                val v = mmu.getByte(address + 1)
                bytes.add(String.format("%02X", v))
                label = label.replace("d8", String.format("%02X", v))
                label = label.replace("r8", String.format("%02X", v))
                label = label.replace("a8", String.format("%02X", v))
            }
            if (length >= 2) {
                val v1 = mmu.getByte(address + 1)
                val v2 = mmu.getByte(address + 2)
                val v = v2 shl 8 or v1
                bytes.add(String.format("%02X", v2))
                label = label.replace("d16", String.format("%04X", v))
                label = label.replace("a16", String.format("%04X", v))
            }
            return String.format("%04X: %-11s %s", address, bytes.joinToString(" "), label)
        }
    }

    fun getMemory(address: Int, length: Int): IntArray {
        val data = IntArray(length)
        for (i in 0 until length) {
            data[i] = gameboy.addressSpace.getByte(address + i)
        }
        return data
    }

    fun writeMemory(address: Int, value: Int) {
        gameboy.addressSpace.setByte(address, value)
    }

    fun isCpuHalted(): Boolean {
        return gameboy.cpu.state == eu.rekawek.coffeegb.core.cpu.Cpu.State.HALTED
    }

    fun isCpuStopped(): Boolean {
        return gameboy.cpu.state == eu.rekawek.coffeegb.core.cpu.Cpu.State.STOPPED
    }

    fun getCpuState(): String {
        return gameboy.cpu.state.name
    }

    fun getCpuClockCycle(): Int {
        // Use reflection since clockCycle is private
        val field = gameboy.cpu.javaClass.getDeclaredField("clockCycle")
        field.isAccessible = true
        return field.get(gameboy.cpu) as Int
    }

    fun isImeEnabled(): Boolean {
        val field = gameboy.javaClass.getDeclaredField("interruptManager")
        field.isAccessible = true
        val im = field.get(gameboy) as eu.rekawek.coffeegb.core.cpu.InterruptManager
        return im.isIme
    }

    fun getIF(): Int {
        val field = gameboy.javaClass.getDeclaredField("interruptManager")
        field.isAccessible = true
        val im = field.get(gameboy) as eu.rekawek.coffeegb.core.cpu.InterruptManager
        return im.getByte(0xff0f)
    }

    fun getIE(): Int {
        val field = gameboy.javaClass.getDeclaredField("interruptManager")
        field.isAccessible = true
        val im = field.get(gameboy) as eu.rekawek.coffeegb.core.cpu.InterruptManager
        return im.getByte(0xffff)
    }
}
