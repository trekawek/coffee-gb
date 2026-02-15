# Gemini CLI Emulator Instrumentation

This project includes a programmatic interface (`Agent`) designed for automated debugging, testing, and AI-assisted troubleshooting of Game Boy ROMs.

## Agent API

The `eu.rekawek.coffeegb.controller.Agent` class provides the following capabilities:

### Execution Control
- `tick()`: Run the emulator for one M-cycle (4 clock cycles).
- `step()`: Run until the next instruction starts or the CPU enters a HALT/STOP state.
- `runUntilFrame(maxTicks)`: Run until a new frame is rendered or `maxTicks` is reached.
- `runTicks(n)`: Run for exactly `n` M-cycles.

### State Access
- `getRegisters()`: Access CPU registers (PC, AF, BC, DE, HL, SP).
- `getByte(address)` / `getMemory(address, length)`: Read from memory.
- `writeMemory(address, value)`: Write to memory.
- `getRomBank()`: Get the currently active switchable ROM bank.
- `getCpuState()`: Get internal CPU state (OPCODE, RUNNING, HALTED, etc.).
- `isImeEnabled()` / `getIF()` / `getIE()`: Access interrupt controller state.

### Media & Input
- `getFrame()`: Retrieve the current screen as a `BufferedImage`.
- `getAudio()`: Collect all audio samples generated since the last call.
- `pressButton(button)` / `releaseButton(button)`: Simulate joypad input.

### Debugging
- `disassemble(address)`: Get a human-readable Z80 disassembly of the instruction at the given address.

## Running Tests

You can create Kotlin scripts in `controller/src/test/java` to automate debugging sessions.

### Example: TestAgent
A basic example that runs a ROM for 100 frames and saves a screenshot:
```bash
mvn test-compile -pl controller && 
mvn exec:java -pl controller 
  -Dexec.mainClass="eu.rekawek.coffeegb.controller.TestAgentKt" 
  -Dexec.classpathScope=test
```

### Manual Execution (Bypassing Maven Cache)
If `mvn exec:java` provides stale results, use a direct `java` command:
```bash
# Generate classpath
mvn dependency:build-classpath -pl controller | grep -A 1 "Dependencies classpath:" | grep -v "Dependencies classpath:" | grep -v "\[INFO\]" > cp.txt

# Run
java -cp $(cat cp.txt):core/target/classes:controller/target/classes:controller/target/test-classes 
  eu.rekawek.coffeegb.controller.YourTestClassNameKt
```

## Case Study: Zerd no Densetsu (Legend of Zerd)
This game was fixed by addressing the **STAT IRQ Bug**. The emulator was triggering an immediate STAT interrupt when the game enabled it via a write to `0xFF41` while the condition (`LY=LYC`) was already true. On real hardware, this rising edge is missed or suppressed. 

The fix in `StatRegister.java` updates the internal triggered state during the write to prevent this spurious interrupt, allowing the game to safely switch ROM banks before the handler runs.
