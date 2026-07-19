package eu.rekawek.coffeegb.core.integration.gbmicrotest;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.integration.support.GbMicrotestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class GbMicrotestRomTest {

    private static final String ARCHIVE = "/roms/gbmicrotest/gbmicrotest-v7.zip";

    private static final int ARCHIVE_ROM_COUNT = 513;

    private static final int VERDICT_ROM_COUNT = 482;

    /**
     * Upstream's interactive diagnostics, visual test benches, and incomplete
     * experiments do not implement the FF80-FF82 terminal verdict protocol. Pin the
     * exact archive membership so an unrelated opcode byte cannot silently move a ROM
     * into or out of the automated suite while the aggregate count stays unchanged.
     */
    private static final Set<String> NON_VERDICT_ROMS = Set.of(
            "gbmicrotest/ly_while_lcd_off.gb",
            "gbmicrotest/ppu_win_vs_wx.gb",
            "gbmicrotest/oam_sprite_trashing.gb",
            "gbmicrotest/801-ppu-latch-scy.gb",
            "gbmicrotest/004-tima_cycle_timer.gb",
            "gbmicrotest/toggle_lcdc.gb",
            "gbmicrotest/000-write_to_x8000.gb",
            "gbmicrotest/004-tima_boot_phase.gb",
            "gbmicrotest/lcdon_write_timing.gb",
            "gbmicrotest/wave_write_to_0xC003.gb",
            "gbmicrotest/400-dma.gb",
            "gbmicrotest/ppu_spritex_vs_scx.gb",
            "gbmicrotest/000-oam_lock.gb",
            "gbmicrotest/ppu_wx_early.gb",
            "gbmicrotest/803-ppu-latch-bgdisplay.gb",
            "gbmicrotest/minimal.gb",
            "gbmicrotest/audio_testbench.gb",
            "gbmicrotest/dma_basic.gb",
            "gbmicrotest/001-vram_unlocked.gb",
            "gbmicrotest/800-ppu-latch-scx.gb",
            "gbmicrotest/500-scx-timing.gb",
            "gbmicrotest/mode2_stat_int_to_oam_unlock.gb",
            "gbmicrotest/007-lcd_on_stat.gb",
            "gbmicrotest/flood_vram.gb",
            "gbmicrotest/poweron.gb",
            "gbmicrotest/cpu_bus_1.gb",
            "gbmicrotest/802-ppu-latch-tileselect.gb",
            "gbmicrotest/002-vram_locked.gb",
            "gbmicrotest/ppu_scx_vs_bgp.gb",
            "gbmicrotest/ppu_sprite_testbench.gb",
            "gbmicrotest/temp.gb");

    private final String name;

    private final byte[] rom;

    public GbMicrotestRomTest(String name, byte[] rom) {
        this.name = name;
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        InputStream input = GbMicrotestRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing GBMicrotest archive: " + ARCHIVE);
        }

        List<Object[]> parameters = new ArrayList<>();
        Set<String> archiveRomPaths = new HashSet<>();
        Set<String> verdictNames = new HashSet<>();
        int archiveRomCount = 0;
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".gb")) {
                    archiveRomCount++;
                    String path = entry.getName();
                    if (!archiveRomPaths.add(path)) {
                        throw new IOException("Duplicate GBMicrotest archive path: " + path);
                    }
                    String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    byte[] rom = zip.readAllBytes();
                    boolean hasVerdictOpcode = usesHramVerdictProtocol(rom);
                    if (NON_VERDICT_ROMS.contains(path)) {
                        if (hasVerdictOpcode) {
                            throw new IOException("Non-verdict GBMicrotest now contains the terminal "
                                    + "protocol opcode: " + path);
                        }
                    } else {
                        if (!hasVerdictOpcode) {
                            throw new IOException("Automated GBMicrotest lost its terminal protocol: "
                                    + path);
                        }
                        if (!verdictNames.add(name)) {
                            throw new IOException("Duplicate GBMicrotest verdict name: " + name);
                        }
                        parameters.add(new Object[]{name, rom});
                    }
                }
                zip.closeEntry();
            }
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        if (archiveRomCount != ARCHIVE_ROM_COUNT) {
            throw new IOException("Expected " + ARCHIVE_ROM_COUNT + " GBMicrotest ROMs, found "
                    + archiveRomCount);
        }
        if (!archiveRomPaths.containsAll(NON_VERDICT_ROMS)) {
            Set<String> missing = new HashSet<>(NON_VERDICT_ROMS);
            missing.removeAll(archiveRomPaths);
            throw new IOException("Pinned non-verdict GBMicrotests missing from archive: " + missing);
        }
        if (parameters.size() != VERDICT_ROM_COUNT) {
            throw new IOException("Expected " + VERDICT_ROM_COUNT
                    + " GBMicrotest ROMs using the HRAM verdict protocol, found "
                    + parameters.size());
        }
        return parameters;
    }

    /**
     * Automated GBMicrotests finish by writing their status byte to FF82 with
     * {@code ldh ($82), a}. The remaining archive entries are interactive diagnostics
     * and test benches without a machine-readable pass/fail result.
     */
    private static boolean usesHramVerdictProtocol(byte[] rom) {
        for (int i = 0; i + 1 < rom.length; i++) {
            if ((rom[i] & 0xff) == 0xe0 && (rom[i + 1] & 0xff) == 0x82) {
                return true;
            }
        }
        return false;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        GbMicrotestRunner.TestResult result = new GbMicrotestRunner(rom)
                .runTest(Gameboy.TICKS_PER_SEC / 2L);
        assertEquals(name + ": " + result, 0x01, result.status());
    }
}
