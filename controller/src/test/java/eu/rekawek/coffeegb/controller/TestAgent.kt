package eu.rekawek.coffeegb.controller

import java.io.File
import javax.imageio.ImageIO

fun main() {
    val romFile = File("core/src/test/resources/roms/blargg/cpu_instrs.gb")
    if (!romFile.exists()) {
        println("ROM not found: ${romFile.absolutePath}")
        return
    }

    val agent = Agent(romFile)
    println("ROM loaded: ${romFile.name}")

    // Run for 100 frames
    println("Running for 100 frames...")
    repeat(100) {
        agent.runUntilFrame()
        if (it % 10 == 0) {
            val registers = agent.getRegisters()
            println("Frame $it: PC=${String.format("%04X", registers.pc)}, instruction: ${agent.disassemble(registers.pc)}")
        }
    }

    val frame = agent.getFrame()
    if (frame != null) {
        val outputFile = File("screenshot.png")
        ImageIO.write(frame, "png", outputFile)
        println("Screenshot saved to ${outputFile.absolutePath}")
    } else {
        println("No frame captured")
    }
}
