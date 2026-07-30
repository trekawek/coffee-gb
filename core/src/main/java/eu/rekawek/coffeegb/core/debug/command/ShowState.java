package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.DebugApuState;
import eu.rekawek.coffeegb.core.debug.DebugExecutionState;
import eu.rekawek.coffeegb.core.debug.DebugInterruptState;
import eu.rekawek.coffeegb.core.debug.DebugMapperState;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugPpuState;
import eu.rekawek.coffeegb.core.debug.DebugRegisters;
import eu.rekawek.coffeegb.core.debug.DebugResult;
import eu.rekawek.coffeegb.core.debug.DebugSnapshot;
import eu.rekawek.coffeegb.core.debug.DebugTimerState;

import java.io.PrintStream;
import java.util.function.Supplier;

public final class ShowState extends DebugPortCommand {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("show state", "state")
                    .withDescription("shows one coherent immutable machine snapshot")
                    .build();

    public ShowState(
            Supplier<DebugPort> debugPortSupplier,
            long timeoutMillis,
            PrintStream output,
            PrintStream error) {
        super(debugPortSupplier, timeoutMillis, output, error);
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        requireNoExtraArguments(commandLine);
        DebugPort port = activePort();
        if (port == null) return;
        DebugResult<DebugSnapshot> result = await(port.snapshot());
        if (result == null) return;
        print(result.value());
    }

    private void print(DebugSnapshot snapshot) {
        DebugRegisters r = snapshot.registers();
        DebugExecutionState cpu = snapshot.execution();
        DebugInterruptState interrupts = snapshot.interrupts();
        DebugTimerState timer = snapshot.timer();
        DebugPpuState ppu = snapshot.ppu();
        DebugApuState apu = snapshot.apu();
        DebugMapperState mapper = snapshot.mapper();

        output.printf(
                "session=%d sequence=%d tick=%d frame=%d+%d paused=%s%n",
                snapshot.sessionGeneration(),
                snapshot.sequence(),
                snapshot.masterTick(),
                snapshot.frame(),
                snapshot.framePosition(),
                snapshot.paused());
        output.printf(
                "AF=%04X BC=%04X DE=%04X HL=%04X SP=%04X PC=%04X%n",
                r.af(), r.bc(), r.de(), r.hl(), r.sp(), r.pc());
        output.printf(
                "CPU=%s opcode=%s extended=%s cycle=%d doubleSpeed=%s haltBug=%s retired=%d%n",
                cpu.cpuState(),
                optionalByte(cpu.opcode()),
                optionalByte(cpu.extendedOpcode()),
                cpu.machineCycle(),
                cpu.doubleSpeed(),
                cpu.haltBug(),
                cpu.retiredInstructions());
        output.printf(
                "IME=%s pendingEnable=%s IF=%02X IE=%02X pending=%02X%n",
                interrupts.ime(),
                interrupts.imeEnablePending(),
                interrupts.requestFlags(),
                interrupts.enableFlags(),
                interrupts.pendingFlags());
        output.printf(
                "TIMER DIV=%04X TIMA=%02X TMA=%02X TAC=%02X overflow=%s delay=%d%n",
                timer.dividerCounter(),
                timer.tima(),
                timer.tma(),
                timer.tac(),
                timer.overflowPending(),
                timer.overflowDelayTicks());
        output.printf(
                "PPU=%s LCD=%s LY=%d dot=%d LCDC=%02X STAT=%02X SCY=%02X SCX=%02X "
                        + "LYC=%02X WY=%02X WX=%02X%n",
                ppu.mode(),
                ppu.lcdEnabled(),
                ppu.line(),
                ppu.dot(),
                ppu.lcdc(),
                ppu.stat(),
                ppu.scy(),
                ppu.scx(),
                ppu.lyc(),
                ppu.wy(),
                ppu.wx());
        output.printf(
                "APU=%s sequencer=%d channels=%s%s%s%s NR50=%02X NR51=%02X NR52=%02X%n",
                apu.enabled(),
                apu.frameSequencerStep(),
                apu.channel1Enabled() ? "1" : "-",
                apu.channel2Enabled() ? "2" : "-",
                apu.channel3Enabled() ? "3" : "-",
                apu.channel4Enabled() ? "4" : "-",
                apu.nr50(),
                apu.nr51(),
                apu.nr52());
        output.printf(
                "MAPPER=%s ROM=%d RAM=%d ramEnabled=%s rtcSelected=%s rumble=%s%n",
                mapper.mapperId(),
                mapper.romBank(),
                mapper.ramBank(),
                mapper.ramEnabled(),
                mapper.rtcSelected(),
                mapper.rumbleEnabled());
    }

    private String optionalByte(int value) {
        return value < 0 ? "--" : String.format("%02X", value);
    }
}
