package eu.rekawek.coffeegb.controller

import java.io.File
import eu.rekawek.coffeegb.controller.state.StatePngCodec
import java.nio.file.Files

fun main() {
    val romFile = File("core/src/test/resources/roms/blargg/cpu_instrs.gb")
    if (!romFile.exists()) {
        println("ROM not found: ${romFile.absolutePath}")
        return
    }

    Agent(romFile).use { agent ->
        println("ROM loaded: ${romFile.name}")

        // Run for 100 frames
        println("Running for 100 frames...")
        repeat(100) {
            agent.runUntilFrame()
            if (it % 10 == 0) {
                val registers = agent.getRegisters()
                println("Frame $it: PC=${String.format("%04X", registers.pc())}")
            }
        }

        val frame = agent.getFrameImage()
        if (frame != null) {
            val outputFile = File("screenshot.png")
            Files.write(outputFile.toPath(), StatePngCodec.encode(frame))
            println("Screenshot saved to ${outputFile.absolutePath}")
        } else {
            println("No frame captured")
        }
    }
}
