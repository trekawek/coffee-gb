package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/** Crosses the desktop source boundary and the default boot path with a nonstandard mapper. */
public class HomebrewRomLaunchTest {

    private static final int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC,
            0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E
    };

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void directInvalidHeaderRawSachenLaunchesWhenBootIsSkipped() throws Exception {
        Path source = temporaryFolder.newFile("custom-mapper.gb").toPath();
        Files.write(source, rawSachenMmc1Rom());

        assertLaunches(source, RomOrigin.Kind.DIRECT_FILE);
    }

    @Test
    public void zippedInvalidHeaderRawSachenLaunchesWhenBootIsSkipped() throws Exception {
        File source = temporaryFolder.newFile("custom-mapper.zip");
        byte[] rom = rawSachenMmc1Rom();
        try (ZipOutputStream output = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(source.toPath())))) {
            output.putNextEntry(new ZipEntry("homebrew/custom-mapper.gb"));
            output.write(rom);
            output.closeEntry();
        }

        assertLaunches(source.toPath(), RomOrigin.Kind.ARCHIVE_ENTRY);
    }

    @Test
    public void oversizedInvalidHeaderHomebrewIsNotMistakenForDatel() throws Exception {
        byte[] bytes = oversizedHeaderlessHomebrewRom();
        assertFalse(RomHeaderInspector.inspect(bytes).hasCartridgeShape());

        Rom rom = new Rom(bytes);
        assertNotEquals(CartridgeProperties.Mapper.DATEL,
                rom.getCartridgeProperties().getMapper());
        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.DATEL_CGB_HEADER));
        assertEquals(Rom.GameboyColorFlag.NON_CGB, rom.getGameboyColorFlag());

        assertProgramRuns(rom);
    }

    private static void assertLaunches(Path source, RomOrigin.Kind expectedOrigin)
            throws Exception {
        try (RomSourceSnapshot snapshot = RomSourceSnapshot.open(source)) {
            RomImage image = snapshot.loadSingle();
            assertEquals(expectedOrigin, image.origin().kind());
            assertFalse(RomHeaderInspector.inspect(image).hasCartridgeShape());

            Rom rom = new Rom(image);
            assertEquals(CartridgeProperties.Mapper.SACHEN_MMC1,
                    rom.getCartridgeProperties().getMapper());
            assertEquals("HOMEBREW", rom.getTitle());

            assertProgramRuns(rom);
        }
    }

    private static void assertProgramRuns(Rom rom) throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "homebrew-launch-test", false);
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            gameboy.getAddressSpace().setByte(0xc000, 0);
            for (int tick = 0;
                 tick < 20_000 && gameboy.getAddressSpace().getByte(0xc000) != 0x42;
                 tick++) {
                gameboy.tick();
            }
            assertEquals("the program at the logical post-boot entry must run",
                    0x42, gameboy.getAddressSpace().getByte(0xc000));
        } finally {
            eventBus.close();
        }
    }

    private static byte[] oversizedHeaderlessHomebrewRom() {
        byte[] rom = new byte[0x20000];
        rom[0x0100] = 0x00;              // NOP
        rom[0x0101] = (byte) 0xC3;       // JP 0x0150
        rom[0x0102] = 0x50;
        rom[0x0103] = 0x01;
        byte[] title = "HOMEBREW".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0147] = 0x00;              // executable/header garbage happens to say ROM-only
        rom[0x0148] = 0x02;
        rom[0x014d] = 0x7f;              // deliberately invalid header checksum
        rom[0x0150] = 0x3E;              // LD A, 0x42
        rom[0x0151] = 0x42;
        rom[0x0152] = (byte) 0xEA;       // LD (0xC000), A
        rom[0x0153] = 0x00;
        rom[0x0154] = (byte) 0xC0;
        rom[0x0155] = 0x18;              // JR -2
        rom[0x0156] = (byte) 0xFE;
        return rom;
    }

    private static byte[] rawSachenMmc1Rom() {
        byte[] rom = new byte[0x80000];

        // The logical entry is address-scrambled in a raw dump. If the mapper is incorrectly
        // left boot-locked, the first fetch is redirected to 0x0180 and loops at 0x0200.
        putLogical(rom, 0x0100, 0xC3, 0x50, 0x01); // JP 0x0150
        putLogical(rom, 0x0150,
                0x3E, 0x42,             // LD A, 0x42
                0xEA, 0x00, 0xC0,       // LD (0xC000), A
                0x18, 0xFE);            // JR -2
        putLogical(rom, 0x0180, 0xC3, 0x00, 0x02); // locked-path JP 0x0200
        rom[0x0200] = 0x18;
        rom[0x0201] = (byte) 0xFE;

        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            putLogical(rom, 0x0104 + i, NINTENDO_LOGO[i]);
        }
        byte[] title = "HOMEBREW".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < title.length; i++) {
            putLogical(rom, 0x0134 + i, title[i]);
        }
        putLogical(rom, 0x0143, 0x00);
        putLogical(rom, 0x0147, 0xAB); // deliberately unknown/custom mapper byte
        putLogical(rom, 0x0148, 0xFF); // deliberately nonstandard size code
        putLogical(rom, 0x0149, 0xFF); // deliberately nonstandard RAM code
        // The physical header is intentionally not Nintendo-shaped. This byte is the logical
        // checksum too, but the skipped-boot path does not use the console's header verifier.
        rom[0x014d] = 0x7F;
        assertFalse(RomHeaderInspector.inspect(rom).hasCartridgeShape());
        return rom;
    }

    private static void putLogical(byte[] rom, int logicalAddress, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            rom[unscramble(logicalAddress + i)] = (byte) bytes[i];
        }
    }

    private static int unscramble(int address) {
        int result = address & 0xffac;
        result |= (address & 0x40) >> 6;
        result |= (address & 0x10) >> 3;
        result |= (address & 0x02) << 3;
        result |= (address & 0x01) << 6;
        return result;
    }
}
