package eu.rekawek.coffeegb.core.integration.gbchw;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.GbcHwTestRunner;
import eu.rekawek.coffeegb.core.integration.support.GbcHwTestRunner.CompletionMode;
import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.fail;

/** Evaluates every automated gbc-hw-tests ROM/model verdict against pinned upstream data. */
@RunWith(Parameterized.class)
public class GbcHwRomTest {

    private static final String ARCHIVE = "/roms/gbc-hw-tests/gbc-hw-tests-631e600.zip";

    private static final String TAC_GBC_DOCUMENTATION =
            "timers/tac_set_everything/GBC_1.txt";

    private static final String CORRUPTED_STOP_ROM =
            "cpu/corrupted_stop/stop_corrupted.gbc";

    private static final String DMA_TIMING_LCD_ON_ROM =
            "dma/dma_timing_lcd_on/dma_timing_lcd_on.gbc";

    private static final int DMA_TIMING_LCD_ON_RESULT_LENGTH = 2_564;

    private static final int DMA_TIMING_LCD_ON_MONOCHROME_DIFFERENCES = 31;

    private static final int CORRUPTED_STOP_COMPLETION_PC = 0x01bd;

    private static final String HDMA_HALT_ROM = "dma/hdma_halt/hdma_halt.gbc";

    private static final int HDMA_HALT_WAIT_CHECKPOINT = (0x99 << 16) | 0x0375;

    private static final byte[] HDMA_HALT_CGB_PREFIX = {
            0x13, 0x13, 0x13, 0x12, 0x69, 0x0d, 0x13, 0x12, (byte) 0x94};

    private static final byte[] HDMA_HALT_GBA_SP_PREFIX = {
            0x13, 0x13, 0x13, 0x13, 0x46, 0x0e, 0x13, 0x12, (byte) 0x94};

    /**
     * Documented selections for raw captures whose metadata is stale or whose physical
     * result is console-instance-specific. Every selected byte is still compared strictly;
     * no result byte is masked and no test is suppressed.
     */
    private static final Map<ReferenceKey, ReferenceSelection> REFERENCE_SELECTIONS = Map.of(
            new ReferenceKey("timers/tac_set_enabled/tac_set_enabled.gbc", GameboyType.CGB),
            // real_gbc contains impossible post-`and 4` IF bytes ($95/$c8). The
            // independent GB and GBP captures are byte-identical for this old-enabled
            // TAC transition, which AntonioND documents as common to DMG and CGB.
            new ReferenceSelection("real_gb.sav", "real_gbp.sav", null, null,
                    CompletionMode.RESULT_MAGIC, null,
                    ReferenceKind.DOCUMENTED_CROSS_MODEL_CONSENSUS),
            new ReferenceKey("timers/tma_set/tma_set.gbc", GameboyType.DMG),
            // real_gb was captured with A=$11 and contains the CGB-only second pass;
            // the GBP capture has the single-speed DMG completion marker.
            new ReferenceSelection("real_gbp.sav", null, null, null,
                    CompletionMode.RESULT_MAGIC, null, ReferenceKind.RAW_CAPTURE),
            new ReferenceKey("timers/timer_reset_2/timer_reset_2.gbc", GameboyType.DMG),
            // This ROM does not clear SRAM. Later magic sequences are stale save data;
            // the DMG pass writes 320 result bytes before its marker.
            new ReferenceSelection(null, null, 320, null, CompletionMode.RESULT_MAGIC,
                    null, ReferenceKind.RAW_CAPTURE),
            new ReferenceKey("timers/timer_reset_2/timer_reset_2.gbc", GameboyType.CGB),
            // CGB appends its double-speed pass and writes the final marker at 640.
            new ReferenceSelection(null, null, 640, null, CompletionMode.RESULT_MAGIC,
                    null, ReferenceKind.RAW_CAPTURE),
            new ReferenceKey(DMA_TIMING_LCD_ON_ROM, GameboyType.DMG),
            // OAM row-boundary read corruption is physically instance-specific. The
            // deterministic model matches the complete raw GBP monochrome capture.
            new ReferenceSelection("real_gbp.sav", null, null, null,
                    CompletionMode.RESULT_MAGIC, null, ReferenceKind.RAW_CAPTURE),
            new ReferenceKey(CORRUPTED_STOP_ROM, GameboyType.DMG),
            // The raw capture contains only the first STOP checkpoint. Bytes 5..7
            // are stale SRAM that accidentally complete the runner's usual magic
            // sequence, so compare only the five bytes actually written by the ROM.
            new ReferenceSelection(null, null, null, 5,
                    CompletionMode.NEXT_STOP_AFTER_WAKE, CORRUPTED_STOP_COMPLETION_PC,
                    ReferenceKind.RAW_CAPTURE),
            new ReferenceKey(HDMA_HALT_ROM, GameboyType.CGB),
            // The CGB capture stops in wait_ly with B=$99. Only bytes 0..8 have
            // been written at that checkpoint; byte 9 and the apparent marker after
            // it are stale cartridge SRAM (the GBA-SP capture has a different byte 9).
            new ReferenceSelection(null, null, null, 9,
                    CompletionMode.PERSISTENT_REGISTER_CHECKPOINT, HDMA_HALT_WAIT_CHECKPOINT,
                    ReferenceKind.RAW_CAPTURE));

    /**
     * A number of the upstream ROMs deliberately emit one SRAM sample per frame. The
     * largest automated mode timing cases therefore need more than 90 million ticks,
     * while other cases spend over five million ticks clearing banked SRAM before they
     * emit their first sample. Scale the emulated-time budget with the reference result
     * instead of silently cutting the longer hardware captures short.
     */
    private static final long STARTUP_TICK_BUDGET = 10_000_000L;

    private static final long TICKS_PER_RESULT_BYTE = 75_000L;

    /**
     * Input state used while each hardware reference was captured. The upstream joy
     * interrupt ROM explicitly requires one button to be held before execution; A is
     * selected because all eight buttons exercise the same P10-P13 falling-edge path.
     */
    private static final Map<String, List<Button>> INITIAL_BUTTONS = Map.of(
            "interrupts/joy_interrupt_manual_delay/joy_interrupt_manual_delay.gbc",
            List.of(Button.A),
            "dma/dma_halt_stop_speedchange/dma_halt_stop_speedchange.gbc",
            List.of(Button.A));

    /**
     * Manual STOP wake-up used for the upstream hardware capture. Unlike the tests
     * above, hdma_halt records how far the LCD/HDMA domains run while the CPU is
     * stopped, so its button must be pressed at the scanline encoded in real_gbc.sav.
     */
    private static final Map<String, GbcHwTestRunner.StopWake> STOP_WAKE = Map.of(
            "dma/hdma_halt/hdma_halt.gbc", new GbcHwTestRunner.StopWake(0x68, 410),
            // The hardware save contains the first ordinary STOP checkpoint. Release
            // the wake button and stop at the following STOP, exactly where that
            // partial capture ends.
            CORRUPTED_STOP_ROM, GbcHwTestRunner.StopWake.immediate());

    private final String name;

    private final GameboyType gameboyType;

    private final byte[] rom;

    private final byte[] expected;

    private final List<Button> initialButtons;

    private final GbcHwTestRunner.StopWake stopWake;

    private final CompletionMode completionMode;

    private final int completionPc;

    public GbcHwRomTest(String name, GameboyType gameboyType, byte[] rom, byte[] expected,
                        List<Button> initialButtons, GbcHwTestRunner.StopWake stopWake,
                        CompletionMode completionMode, int completionPc) {
        this.name = name;
        this.gameboyType = gameboyType;
        this.rom = rom;
        this.expected = expected;
        this.initialButtons = initialButtons;
        this.stopWake = stopWake;
        this.completionMode = completionMode;
        this.completionPc = completionPc;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        Map<String, byte[]> entries = readArchive();
        validateDerivedExpectationDocumentation(entries);
        List<Object[]> parameters = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".gb") && !path.endsWith(".gbc")) {
                continue;
            }
            int separator = path.lastIndexOf('/');
            String directory = separator < 0 ? "" : path.substring(0, separator + 1);
            addReference(parameters, entries, path, directory + "real_gb.sav",
                    GameboyType.DMG, entry.getValue());
            addReference(parameters, entries, path, directory + "real_gbc.sav",
                    GameboyType.CGB, entry.getValue());
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        if (parameters.size() != 221) {
            throw new IOException("Expected 221 gbc-hw-tests strict verdicts, found "
                    + parameters.size());
        }
        return parameters;
    }

    private static void addReference(List<Object[]> parameters, Map<String, byte[]> entries,
                                     String romPath, String referencePath, GameboyType type, byte[] rom)
            throws IOException {
        ReferenceSelection selection = REFERENCE_SELECTIONS.get(new ReferenceKey(romPath, type));
        int separator = referencePath.lastIndexOf('/');
        String directory = separator < 0 ? "" : referencePath.substring(0, separator + 1);
        String selectedPath = selection != null && selection.fileName() != null
                ? directory + selection.fileName() : referencePath;
        byte[] reference = entries.get(selectedPath);
        if (selection != null && reference == null) {
            throw new IOException("Missing corrected gbc-hw reference: " + selectedPath);
        }
        CompletionMode completionMode = selection == null
                ? CompletionMode.RESULT_MAGIC : selection.completionMode();
        int completionPc = selection == null || selection.completionPc() == null
                ? -1 : selection.completionPc();
        if (completionMode == CompletionMode.NEXT_STOP_AFTER_WAKE) {
            validateCorruptedStopCheckpoint(romPath, rom, completionPc);
        } else if (completionMode == CompletionMode.PERSISTENT_REGISTER_CHECKPOINT) {
            validateHdmaHaltCheckpoint(entries, romPath, rom, completionPc);
        }
        byte[] expectedReference;
        if (selection != null && selection.resultLength() != null) {
            if (reference == null || reference.length < selection.resultLength()) {
                throw new IOException("gbc-hw reference is shorter than corrected result: "
                        + selectedPath);
            }
            expectedReference = Arrays.copyOf(reference, selection.resultLength());
        } else {
            int magicOffset = selection != null && selection.magicOffset() != null
                    ? selection.magicOffset() : findLastMagic(reference);
            if (magicOffset < 0) {
                return;
            }
            if (!hasMagicAt(reference, magicOffset)) {
                throw new IOException("gbc-hw reference completion marker moved: " + selectedPath
                        + " at $" + Integer.toHexString(magicOffset));
            }
            expectedReference = Arrays.copyOf(reference,
                    magicOffset + GbcHwTestRunner.RESULT_MAGIC.length);
        }
        if (selection != null && selection.corroboratingFileName() != null) {
            String corroboratingPath = directory + selection.corroboratingFileName();
            byte[] corroborating = entries.get(corroboratingPath);
            int resultLength = expectedReference.length;
            if (corroborating == null || corroborating.length < resultLength
                    || !Arrays.equals(Arrays.copyOf(reference, resultLength),
                    Arrays.copyOf(corroborating, resultLength))) {
                throw new IOException("gbc-hw consensus references disagree: " + selectedPath
                        + " and " + corroboratingPath);
            }
        }
        String referenceLabel;
        if (selection != null
                && selection.referenceKind() == ReferenceKind.DOCUMENTED_CROSS_MODEL_CONSENSUS) {
            referenceLabel = type + "; documented GB/GBP consensus";
        } else if (selection != null && "real_gbp.sav".equals(selection.fileName())) {
            referenceLabel = type + "; raw GBP/monochrome-family capture";
        } else {
            referenceLabel = type.toString();
        }
        String name = romPath + " [" + referenceLabel + "]";
        parameters.add(new Object[]{name, type, rom, expectedReference,
                INITIAL_BUTTONS.getOrDefault(romPath, List.of()), STOP_WAKE.get(romPath),
                completionMode, completionPc});
    }

    private static void validateDerivedExpectationDocumentation(Map<String, byte[]> entries)
            throws IOException {
        byte[] documentation = entries.get(TAC_GBC_DOCUMENTATION);
        if (documentation == null || !new String(documentation, StandardCharsets.UTF_8)
                .contains("else // Same as DMG")) {
            throw new IOException("Missing documented GBC old-TAC invariant in "
                    + TAC_GBC_DOCUMENTATION);
        }
        long derivedCount = REFERENCE_SELECTIONS.values().stream()
                .filter(selection -> selection.referenceKind()
                        == ReferenceKind.DOCUMENTED_CROSS_MODEL_CONSENSUS)
                .count();
        if (derivedCount != 1) {
            throw new IOException("Expected one documented cross-model consensus, found "
                    + derivedCount);
        }
        validateDmaTimingLcdOnReferences(entries);
    }

    private static void validateDmaTimingLcdOnReferences(Map<String, byte[]> entries)
            throws IOException {
        int separator = DMA_TIMING_LCD_ON_ROM.lastIndexOf('/');
        String directory = DMA_TIMING_LCD_ON_ROM.substring(0, separator + 1);
        byte[] gb = entries.get(directory + "real_gb.sav");
        byte[] gbp = entries.get(directory + "real_gbp.sav");
        int expectedMagicOffset = DMA_TIMING_LCD_ON_RESULT_LENGTH
                - GbcHwTestRunner.RESULT_MAGIC.length;
        int gbMagicOffset = findLastMagic(gb);
        int gbpMagicOffset = findLastMagic(gbp);
        if (gb == null || gbp == null
                || gbMagicOffset != expectedMagicOffset
                || gbpMagicOffset != expectedMagicOffset) {
            throw new IOException("dma_timing_lcd_on GB/GBP references no longer share the "
                    + DMA_TIMING_LCD_ON_RESULT_LENGTH + "-byte completion length");
        }
        int differences = 0;
        for (int i = 0; i < DMA_TIMING_LCD_ON_RESULT_LENGTH; i++) {
            if (gb[i] != gbp[i]) {
                differences++;
            }
        }
        if (differences != DMA_TIMING_LCD_ON_MONOCHROME_DIFFERENCES) {
            throw new IOException("dma_timing_lcd_on GB/GBP reference difference count moved: "
                    + differences);
        }
    }

    private static void validateCorruptedStopCheckpoint(String romPath, byte[] rom,
                                                         int completionPc) throws IOException {
        if (!CORRUPTED_STOP_ROM.equals(romPath)
                || completionPc != CORRUPTED_STOP_COMPLETION_PC
                || rom.length <= 0x01bc
                || (rom[0x01a9] & 0xff) != 0x10 || (rom[0x01aa] & 0xff) != 0x00
                || (rom[0x01bb] & 0xff) != 0x10 || (rom[0x01bc] & 0xff) != 0x01) {
            throw new IOException("corrupted_stop checkpoint addresses changed in pinned ROM");
        }
    }

    private static void validateHdmaHaltCheckpoint(Map<String, byte[]> entries, String romPath, byte[] rom,
                                                    int completionPc) throws IOException {
        byte[] gbcCapture = entries.get("dma/hdma_halt/real_gbc.sav");
        byte[] gbaSpCapture = entries.get("dma/hdma_halt/real_gba_sp.sav");
        if (!HDMA_HALT_ROM.equals(romPath)
                || completionPc != HDMA_HALT_WAIT_CHECKPOINT
                || rom.length <= 0x0379
                || (rom[0x02b7] & 0xff) != 0xc6 || (rom[0x02b8] & 0xff) != 0x05
                || (rom[0x02b9] & 0xff) != 0x47 || (rom[0x02ba] & 0xff) != 0xcd
                || (rom[0x02bb] & 0xff) != 0x73 || (rom[0x02bc] & 0xff) != 0x03
                || (rom[0x0373] & 0xff) != 0x0e || (rom[0x0374] & 0xff) != 0x44
                || (rom[0x0375] & 0xff) != 0xf2 || (rom[0x0376] & 0xff) != 0xb8
                || (rom[0x0377] & 0xff) != 0x20 || (rom[0x0378] & 0xff) != 0xfc
                || (rom[0x0379] & 0xff) != 0xc9
                || gbcCapture == null || gbcCapture.length < 14
                || gbaSpCapture == null || gbaSpCapture.length < 14
                || !Arrays.equals(HDMA_HALT_CGB_PREFIX, Arrays.copyOf(gbcCapture, 9))
                || !Arrays.equals(HDMA_HALT_GBA_SP_PREFIX, Arrays.copyOf(gbaSpCapture, 9))
                || (gbcCapture[9] & 0xff) != 0x78
                || (gbaSpCapture[9] & 0xff) != 0xff
                || !Arrays.equals(GbcHwTestRunner.RESULT_MAGIC,
                Arrays.copyOfRange(gbcCapture, 10, 14))
                || !Arrays.equals(GbcHwTestRunner.RESULT_MAGIC,
                Arrays.copyOfRange(gbaSpCapture, 10, 14))) {
            throw new IOException("hdma_halt wait_ly checkpoint addresses changed in pinned ROM");
        }
    }

    private static int findLastMagic(byte[] data) {
        if (data == null) {
            return -1;
        }
        int result = -1;
        int limit = Math.min(data.length, 0x2000);
        for (int offset = 0; offset <= limit - GbcHwTestRunner.RESULT_MAGIC.length; offset++) {
            boolean matches = true;
            for (int i = 0; i < GbcHwTestRunner.RESULT_MAGIC.length; i++) {
                if (data[offset + i] != GbcHwTestRunner.RESULT_MAGIC[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result = offset;
            }
        }
        return result;
    }

    private static boolean hasMagicAt(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + GbcHwTestRunner.RESULT_MAGIC.length > data.length) {
            return false;
        }
        for (int i = 0; i < GbcHwTestRunner.RESULT_MAGIC.length; i++) {
            if (data[offset + i] != GbcHwTestRunner.RESULT_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, byte[]> readArchive() throws IOException {
        InputStream input = GbcHwRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing gbc-hw-tests archive: " + ARCHIVE);
        }
        Map<String, byte[]> entries = new HashMap<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    @Test(timeout = 30000)
    public void test() throws IOException {
        GbcHwTestRunner.TestResult result =
                new GbcHwTestRunner(rom, gameboyType, initialButtons, stopWake)
                        .runTest(expected.length, maxTicksForResult(expected.length),
                                completionMode, completionPc);
        if (!result.completed()) {
            fail(name + " did not write its complete SRAM result: " + result.cpuState());
        }
        int mismatch = firstMismatch(expected, result.actual());
        if (mismatch >= 0) {
            fail(String.format("%s differs at SRAM+$%04x: expected $%02x, actual $%02x",
                    name, mismatch, expected[mismatch] & 0xff, result.actual()[mismatch] & 0xff));
        }
    }

    static long maxTicksForResult(int resultLength) {
        return STARTUP_TICK_BUDGET + TICKS_PER_RESULT_BYTE * resultLength;
    }

    private static int firstMismatch(byte[] expected, byte[] actual) {
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return -1;
    }

    private record ReferenceKey(String romPath, GameboyType type) {
    }

    private record ReferenceSelection(String fileName, String corroboratingFileName,
                                      Integer magicOffset, Integer resultLength,
                                      CompletionMode completionMode, Integer completionPc,
                                      ReferenceKind referenceKind) {
    }

    private enum ReferenceKind {
        RAW_CAPTURE,
        DOCUMENTED_CROSS_MODEL_CONSENSUS
    }

}
