package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class RomRamSizeTest {

    @Test
    public void supportsMbc30RamSize() throws IOException {
        byte[] data = new byte[0x8000];
        data[0x147] = 0x13; // MBC3 + RAM + battery
        data[0x149] = 0x05; // 64 KiB / eight banks

        assertEquals(8, new Rom(data).getRamBanks());
    }

    @Test
    public void ignoresInstructionByteInUnlicensedRamSizeField() throws IOException {
        byte[] data = new byte[0x40000];
        data[0x147] = (byte) 0xea; // instruction byte in place of cartridge type
        data[0x149] = 0x20; // JR NZ operand in place of RAM size (Sonic 3D Blast 5, #186)

        Rom rom = new Rom(data);

        assertEquals(0, rom.getRamSize());
        assertEquals(0, rom.getRamBanks());
    }

    @Test
    public void derivesBanksWhenExecutableCodeLooksLikeSmallerRomSize() throws IOException {
        byte[] data = new byte[0x40000];
        data[0x148] = 0x00; // nominally two banks; actually an instruction operand (#186)

        assertEquals(16, new Rom(data).getRomBanks());
    }
}
