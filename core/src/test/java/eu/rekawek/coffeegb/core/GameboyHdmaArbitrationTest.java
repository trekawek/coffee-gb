package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameboyHdmaArbitrationTest {

    private static final int PROGRAM = 0xc000;

    @Test
    public void lateNonHaltOpcodeRetiresBeforeHdma() throws IOException {
        try (Fixture fixture = new Fixture(0x04, 0x00)) { // INC B; NOP
            fixture.advanceCpuTicks(2);
            fixture.startHdma(0xc100);

            fixture.gameboy.tick();
            assertEquals(PROGRAM, fixture.cpu().getRegisters().getPC());
            assertEquals(Cpu.State.OPCODE, fixture.cpu().getState());
            assertEquals(0, fixture.cpu().getRegisters().getB());

            fixture.gameboy.tick();
            assertEquals(1, fixture.cpu().getRegisters().getB());
            assertEquals(Cpu.State.OPCODE, fixture.cpu().getState());
            assertEquals(0, fixture.read(0xff55) & 0x80);
        }
    }

    @Test
    public void lateHaltOpcodeIsHeldUntilHdmaFinishes() throws IOException {
        try (Fixture fixture = new Fixture(0x76, 0x00)) { // HALT; NOP
            fixture.advanceCpuTicks(2);
            fixture.startHdma(0xc100);

            fixture.gameboy.tick();
            assertEquals(PROGRAM + 1, fixture.cpu().getRegisters().getPC());
            assertEquals(Cpu.State.OPERAND, fixture.cpu().getState());

            fixture.finishHdma();
            for (int i = 0; i < 4 && fixture.cpu().getState() != Cpu.State.HALTED; i++) {
                fixture.gameboy.tick();
            }
            assertEquals(Cpu.State.HALTED, fixture.cpu().getState());
        }
    }

    @Test
    public void interruptAcceptedFromALateOpcodeSlotPushesBeforeHdma() throws IOException {
        try (Fixture fixture = new Fixture(0xfb, 0x04, 0x00)) { // EI; INC B; NOP
            fixture.cpu().getRegisters().setSP(0xdffe);
            fixture.advanceCpuTicks(10);
            assertEquals(PROGRAM + 2, fixture.cpu().getRegisters().getPC());
            fixture.startHdma(0xdff0);

            // The non-HALT opcode first claims the late fetch slot. An interrupt
            // arriving in the remaining half-cycle must retain that CPU ownership.
            fixture.gameboy.tick();
            fixture.write(0xffff, 0x01);
            fixture.write(0xff0f, 0x01);

            fixture.gameboy.tick();
            assertEquals(Cpu.State.IRQ_WAIT_2, fixture.cpu().getState());

            for (int i = 0; i < 16 && fixture.cpu().getState() != Cpu.State.IRQ_JUMP; i++) {
                fixture.gameboy.tick();
            }
            assertEquals(Cpu.State.IRQ_JUMP, fixture.cpu().getState());
            assertEquals(0xdffc, fixture.cpu().getRegisters().getSP());
            assertEquals((PROGRAM + 2) & 0xff, fixture.read(0xdffc));
            assertEquals(PROGRAM >> 8, fixture.read(0xdffd));

            fixture.finishHdma();
            assertEquals((PROGRAM + 2) & 0xff, fixture.readVram(0x800c));
            assertEquals(PROGRAM >> 8, fixture.readVram(0x800d));
        }
    }

    @Test
    public void lateStopKeepsItsPaddingAcrossTheHdmaSpeedSwitchBurst() throws IOException {
        try (Fixture fixture = new Fixture(0x10, 0x3c, 0x00)) { // STOP $3c; NOP
            fixture.cpu().getRegisters().setA(0);
            fixture.advanceCpuTicks(2);
            fixture.write(0xff4d, 0x01);
            fixture.startHdma(0xc100);

            for (int i = 0; i < 12 && !fixture.cpu().isSpeedSwitching(); i++) {
                fixture.gameboy.tick();
            }
            assertTrue(fixture.cpu().isSpeedSwitching());
            assertEquals(2, fixture.gameboy.getSpeedMode().getSpeedMode());
            assertEquals(PROGRAM + 2, fixture.cpu().getRegisters().getPC());

            for (int i = 0; i < 70_000
                    && (fixture.cpu().isSpeedSwitching()
                    || fixture.gameboy.isSpeedSwitchTailActive()); i++) {
                fixture.gameboy.tick();
            }
            assertFalse(fixture.cpu().isSpeedSwitching());
            assertFalse(fixture.gameboy.isSpeedSwitchTailActive());
            fixture.finishHdma();
            for (int i = 0; i < 4 && fixture.cpu().getRegisters().getA() == 0; i++) {
                fixture.gameboy.tick();
            }

            assertEquals(1, fixture.cpu().getRegisters().getA());
            assertEquals(PROGRAM + 2, fixture.cpu().getRegisters().getPC());
            assertEquals(0x80, fixture.read(0xff55));
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final Gameboy gameboy;

        private Fixture(int... program) throws IOException {
            byte[] rom = new byte[0x8000];
            rom[0x143] = (byte) 0x80;
            rom[0x147] = 0;
            gameboy = new Gameboy.GameboyConfiguration(new Rom(rom))
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setGameboyType(GameboyType.CGB)
                    .setSupportBatterySave(false)
                    .build();
            gameboy.getGpu().setByte(0xff40, 0);
            gameboy.tick();
            advanceCpuTicks(3);
            assertEquals(Cpu.State.OPCODE, cpu().getState());
            cpu().getRegisters().setPC(PROGRAM);
            write(0xff0f, 0);
            write(0xffff, 0);
            for (int i = 0; i < program.length; i++) {
                write(PROGRAM + i, program[i]);
            }
            for (int i = 0; i < 0x10; i++) {
                write(0xc100 + i, 0xa0 + i);
            }
        }

        private Cpu cpu() {
            return gameboy.getCpu();
        }

        private void advanceCpuTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                cpu().tick();
            }
        }

        private void startHdma(int source) {
            write(0xff51, source >> 8);
            write(0xff52, source & 0xf0);
            write(0xff53, 0);
            write(0xff54, 0);
            write(0xff55, 0x80);
            assertTrue(gameboy.getHdma().isTransferInProgress());
        }

        private void finishHdma() {
            for (int i = 0; i < 48 && gameboy.getHdma().isTransferInProgress(); i++) {
                gameboy.tick();
            }
            assertFalse(gameboy.getHdma().isTransferInProgress());
        }

        private void write(int address, int value) {
            gameboy.getAddressSpace().setByte(address, value);
        }

        private int read(int address) {
            return gameboy.getAddressSpace().getByte(address);
        }

        private int readVram(int address) {
            return gameboy.getGpu().getVideoRam().getByte(address);
        }

        @Override
        public void close() throws IOException {
            gameboy.close();
        }
    }
}
