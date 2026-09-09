package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.util.ArrayList;
import java.util.List;

/** Repository-authored workloads. No commercial cartridge data or game-specific signatures. */
public final class PerformanceWorkloads {
    public enum Scenario {
        CPU, MASKED_IRQ, STAT_POLL, LY_POLL, LYC_WRITES, RASTER_WRITES, LCDC_WRITES,
        SERIAL, TIMER, AUDIO, SRAM, LCD_OFF, LCD_OFF_HALT, HBLANK_DMA, OAM_DMA, HELD_INPUT,
        SPEED_SWITCH,
        IF_POLL, IE_POLL, DIV_POLL, TIMA_POLL, JOYP_POLL, NR52_POLL,
        CONTROL_LINK_IO, OAM_ROM_DMA, OVERLAP_DMA, MBC3_RTC_WINDOW,
        LYC_POLL
    }

    /** The mixed form is the default measurement workload; individual forms aid differential tests. */
    public enum PollingForm { LDH_IMMEDIATE, LDH_C, ABSOLUTE, INDIRECT, MIXED }

    public enum Profile {
        DMG(HardwareProfileRegistry.DMG, false, false),
        MGB(HardwareProfileRegistry.MGB, false, false),
        CGB(HardwareProfileRegistry.CGB, true, false),
        CGB_X2(HardwareProfileRegistry.CGB, true, true),
        CGB0(HardwareProfileRegistry.CGB0, true, false),
        CGB0_X2(HardwareProfileRegistry.CGB0, true, true),
        CGB_COMPAT(HardwareProfileRegistry.CGB, false, false),
        CGB0_COMPAT(HardwareProfileRegistry.CGB0, false, false),
        SGB(HardwareProfileRegistry.SGB, false, false),
        SGB2(HardwareProfileRegistry.SGB2, false, false);

        public final HardwareProfile hardware;
        public final boolean color;
        public final boolean doubleSpeed;

        Profile(HardwareProfile hardware, boolean color, boolean doubleSpeed) {
            this.hardware = hardware;
            this.color = color;
            this.doubleSpeed = doubleSpeed;
        }
    }

    private PerformanceWorkloads() {}

    public static Gameboy session(Scenario scenario, Profile profile) throws java.io.IOException {
        return session(scenario, profile, 0, 0);
    }

    public static Gameboy session(Scenario scenario, Profile profile, int statMask, int lyc)
            throws java.io.IOException {
        return new Gameboy.GameboyConfiguration(new Rom(image(scenario, profile, statMask, lyc)))
                .setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false)
                .build();
    }

    public static byte[] image(Scenario scenario, Profile profile) {
        return image(scenario, profile, 0, 0);
    }

    private static byte[] image(Scenario scenario, Profile profile, int statMask, int lyc) {
        if (isRetainedFenceScenario(scenario)) {
            return retainedFenceImage(scenario, profile, -1);
        }
        if (pollingAddress(scenario) >= 0) {
            return pollingImage(scenario, profile, PollingForm.MIXED, 0);
        }
        byte[] image = new byte[32768];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = profile.color ? (byte) 0x80 : 0;
        if (scenario == Scenario.SRAM) {
            image[0x147] = 3; // MBC1 with ordinary SRAM; persistence remains disabled.
            image[0x149] = 2;
        }
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; stack in HRAM.
        p.write(0xffff, 0);
        p.io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        if (statMask != 0 || lyc != 0) p.io(0x41, statMask).io(0x45, lyc);
        switch (scenario) {
            case MASKED_IRQ -> { p.write(0xffff, 1); p.io(0x0f, 1); }
            case LCD_OFF, LCD_OFF_HALT -> p.io(0x40, 0x11);
            case TIMER -> { p.io(0x06, 0xff); p.io(0x07, 5); }
            case AUDIO -> {
                p.io(0x26, 0x80).io(0x24, 0x77).io(0x25, 0xff);
                p.io(0x11, 0x80).io(0x12, 0xf0).io(0x13, 0xff).io(0x14, 0x87);
                p.io(0x1a, 0x80).io(0x1c, 0x20).io(0x1d, 0xff).io(0x1e, 0x87);
            }
            case SRAM -> p.write(0x0000, 0x0a);
            case HBLANK_DMA -> p.io(0x40, 0x11).io(0x40, 0x91);
            default -> { }
        }
        int loop = p.address();
        switch (scenario) {
            case STAT_POLL -> p.emit(0xf0, 0x41, 0xea, 0x00, 0xc0);
            case LY_POLL -> p.emit(0xf0, 0x44, 0xea, 0x00, 0xc0);
            case LYC_WRITES -> p.emit(0x3c, 0xe0, 0x45);
            case RASTER_WRITES -> p.emit(0x3c, 0xe0, 0x43, 0xe0, 0x47);
            case LCDC_WRITES -> p.io(0x40, 0x91).io(0x40, 0xb1);
            case SERIAL -> {
                p.io(0x01, 0x55).io(0x02, profile.color ? 0x83 : 0x81);
                int wait = p.address();
                p.emit(0xf0, 0x02, 0xe6, 0x80, 0xc2, wait & 255, wait >> 8);
            }
            case AUDIO -> p.emit(0x3c, 0xe0, 0x24, 0xe0, 0x30);
            case SRAM -> p.emit(0x21, 0x00, 0xa0, 0x34, 0x7e, 0xea, 0x00, 0xc0);
            case LCD_OFF -> p.emit(0x21, 0x00, 0x80, 0x34, 0x7e, 0xea, 0x00, 0xc0);
            case LCD_OFF_HALT -> p.emit(0x76);
            case SPEED_SWITCH -> {
                if (!profile.color) throw new IllegalArgumentException("Speed switch requires native CGB");
                p.io(0x4d, 1).emit(0x10, 0);
                // Leave substantial work at each speed within one runTicks call.
                p.emit(0x06, 0x40, 0x05, 0x20, 0xfd);
            }
            case HBLANK_DMA -> {
                if (!profile.color) throw new IllegalArgumentException("HDMA requires native CGB");
                p.io(0x51, 0xc0).io(0x52, 0).io(0x53, 0).io(0x54, 0).io(0x55, 0xff);
                int wait = p.address();
                p.emit(0xf0, 0x55, 0xe6, 0x80, 0xca, wait & 255, wait >> 8);
            }
            case OAM_DMA -> {
                // HRAM transfer routine executes legally during DMG OAM DMA.
                int[] routine = {0x3e, 0xc0, 0xe0, 0x46, 0x06, 0x28, 0x05, 0x20, 0xfd, 0xc9};
                for (int i = 0; i < routine.length; i++) p.write(0xff80 + i, routine[i]);
                p.emit(0xcd, 0x80, 0xff);
            }
            case HELD_INPUT -> p.emit(0xf0, 0x00, 0xea, 0x00, 0xc0);
            default -> p.emit(0x21, 0x00, 0xc0, 0x34, 0x7e);
        }
        p.emit(0xc3, loop & 255, loop >> 8);
        p.copyTo(image);
        return image;
    }

    /**
     * Builds one of the retained-fence fixtures with a finite burst followed by a quiet loop.
     * A negative burst count selects the persistent image used by the cost runner.  A positive
     * count selects the finite form so correctness tests can observe recovery after the last
     * changing access without waiting for a fixed warmup budget.
     */
    public static byte[] retainedFenceImage(Scenario scenario, Profile profile, int finiteBurst) {
        if (!isRetainedFenceScenario(scenario) || finiteBurst < -1
                || finiteBurst == 0 || finiteBurst > 0xff) {
            throw new IllegalArgumentException(
                    "retained-fence scenario and -1 or 1..255 burst count required");
        }
        return switch (scenario) {
            case CONTROL_LINK_IO -> controlLinkImage(profile, finiteBurst);
            case OAM_ROM_DMA -> oamRomDmaImage(profile, finiteBurst);
            case OVERLAP_DMA -> overlapDmaImage(profile, finiteBurst);
            case MBC3_RTC_WINDOW -> mbc3RtcImage(profile, finiteBurst);
            default -> throw new IllegalArgumentException("Not a retained-fence scenario: " + scenario);
        };
    }

    static boolean isRetainedFenceScenario(Scenario scenario) {
        return switch (scenario) {
            case CONTROL_LINK_IO, OAM_ROM_DMA, OVERLAP_DMA, MBC3_RTC_WINDOW -> true;
            default -> false;
        };
    }

    /** HRAM byte incremented after each completed retained-fence operation. */
    // HRAM remains CPU-readable while OAM DMA owns the external bus, so progress cannot be
    // confused with the DMA bus residue when the test seeks the first completed operation.
    static final int RETAINED_PROGRESS = 0xffe0;

    /** Phase witness: 1 means the finite operation completed, 2 means the quiet loop was entered. */
    static final int RETAINED_PHASE = 0xffe1;

    /** First result byte used by the link, OAM and mapper fixtures. */
    static final int RETAINED_RESULT = 0xc001;

    private static byte[] retainedBase(Profile profile) {
        byte[] image = new byte[32768];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = profile.color ? (byte) 0x80 : 0;
        return image;
    }

    private static byte[] controlLinkImage(Profile profile, int finiteBurst) {
        byte[] image = retainedBase(profile);
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; stack in HRAM.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        // A short real timer period makes the serial completion loop observe a timer IF edge.
        p.io(0x06, 0xa5).io(0x05, 0xfe).io(0x07, 0x05);
        p.write(RETAINED_PROGRESS, 0).write(RETAINED_PHASE, 0).write(RETAINED_RESULT, 0);
        p.write(0xc002, 0).write(0xc003, 0);
        p.write(0xc005, 0).write(0xc006, 0);
        if (finiteBurst >= 0) p.write(0xc004, finiteBurst);
        int loop = p.address();
        p.io(0x01, 0x55).io(0x02, profile.color ? 0x83 : 0x81);
        // CB memory operations exercise the actual FF07 fence and retain the live TAC value.
        p.emit(0x21, 0x07, 0xff, 0xcb, 0x46, 0xcb, 0x96, 0xcb, 0xd6);
        p.emit(0xf0, 0x06, 0xea, 0x05, 0xc0); // read back TMA through the CPU MMIO path
        p.emit(0xf0, 0x07, 0xea, 0x06, 0xc0); // read back TAC, including its fixed high bits
        p.io(0x06, 0xa5); // repeat the TMA configuration on every retained loop
        int wait = p.address();
        p.emit(0xf0, 0x02, 0xe6, 0x80, 0xc2, wait & 255, wait >> 8);
        // Completed SC/SB and IF values are real bus results, not host-side observations.
        p.emit(0xf0, 0x02, 0xea, 0x02, 0xc0);
        p.emit(0xf0, 0x01, 0xea, 0x01, 0xc0);
        p.emit(0xf0, 0x0f, 0xea, 0x03, 0xc0);
        p.emit(0x21, 0xe0, 0xff, 0x34); // completed transfer count
        p.io(0xe1, 1); // finite-burst phase witness: changing operation has completed
        if (finiteBurst >= 0) {
            p.emit(0x21, 0x04, 0xc0, 0x35); // finite transfer count
            p.emit(0xc2, loop & 255, loop >> 8);
        } else {
            p.emit(0xc3, loop & 255, loop >> 8);
        }
        int quiet = p.address();
        p.emit(0x06, 0xe0, 0x05, 0x20, 0xfd); // leave a visible finite recovery interval
        p.io(0xe1, 2); // explicit finite-burst recovery witness
        p.emit(0xc3, quiet & 255, quiet >> 8);
        p.copyTo(image);
        return image;
    }

    private static byte[] oamRomDmaImage(Profile profile, int finiteBurst) {
        byte[] image = retainedBase(profile);
        for (int offset = 0; offset < 0x100; offset++) {
            image[0x4000 + offset] = (byte) ((offset * 37 + 0x5a) & 0xff);
        }
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; stack in HRAM.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        p.write(RETAINED_PROGRESS, 0).write(RETAINED_PHASE, 0).write(RETAINED_RESULT, 0);
        if (finiteBurst >= 0) p.write(0xc002, finiteBurst);
        // This routine is the only code fetched while OAM DMA owns the CPU bus.
        int[] routine = {0x3e, 0x40, 0xe0, 0x46, 0x06, 0x28, 0x05, 0x20, 0xfd, 0xc9};
        for (int i = 0; i < routine.length; i++) p.write(0xff80 + i, routine[i]);
        int loop = p.address();
        p.emit(0xcd, 0x80, 0xff); // call the legal HRAM DMA launcher and wait before returning
        p.emit(0xfa, 0x00, 0x40, 0xea, 0x01, 0xc0); // retain the ROM source byte
        if (finiteBurst >= 0) {
            // VBlank provides a stable CPU-readable OAM window. Capture the transferred bytes
            // through that real bus path so the correctness proof does not depend on a
            // debugger-side OAM alias or on the current PPU mode at a test seam.
            int waitVblank = p.address();
            p.emit(0xf0, 0x41, 0xe6, 0x03, 0xfe, 0x01,
                    0xc2, waitVblank & 255, waitVblank >> 8);
            for (int offset = 0; offset < 4; offset++) {
                p.emit(0xfa, offset, 0xfe, 0xea, 0x05 + offset, 0xc0);
            }
        }
        p.emit(0x21, 0xe0, 0xff, 0x34); // completed DMA count
        p.io(0xe1, 1); // finite-burst phase witness: DMA operation has completed
        if (finiteBurst >= 0) {
            p.emit(0x21, 0x02, 0xc0, 0x35);
            p.emit(0xc2, loop & 255, loop >> 8);
        } else {
            p.emit(0xc3, loop & 255, loop >> 8);
        }
        int quiet = p.address();
        p.emit(0x06, 0xe0, 0x05, 0x20, 0xfd); // leave a visible finite recovery interval
        p.io(0xe1, 2); // explicit finite-burst recovery witness
        p.emit(0xc3, quiet & 255, quiet >> 8);
        p.copyTo(image);
        return image;
    }

    private static byte[] overlapDmaImage(Profile profile, int finiteBurst) {
        if (!profile.color) {
            throw new IllegalArgumentException("Overlapping VRAM/OAM DMA requires native CGB");
        }
        byte[] image = retainedBase(profile);
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; stack in HRAM.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        p.write(RETAINED_PROGRESS, 0).write(RETAINED_PHASE, 0).write(RETAINED_RESULT, 0);
        p.write(0xc004, finiteBurst < 0 ? 0 : finiteBurst);
        // The same physical source page feeds HDMA and OAM, so payload checks cover both owners.
        for (int offset = 0; offset < 0x10; offset++) {
            p.write(0xc100 + offset, (offset * 19 + 0x31) & 0xff);
        }
        int[] gdma = overlapRoutine(0x00, 0xff80);
        int[] hdma = overlapRoutine(0x80, 0xffa0);
        for (int i = 0; i < gdma.length; i++) p.write(0xff80 + i, gdma[i]);
        for (int i = 0; i < hdma.length; i++) p.write(0xffa0 + i, hdma[i]);
        int loop = p.address();
        p.emit(0xcd, 0x80, 0xff);
        p.emit(0xcd, 0xa0, 0xff);
        p.emit(0x21, 0xe0, 0xff, 0x34); // one completed GDMA+HDMA/OAM cycle
        p.io(0xe1, 1); // finite-burst phase witness: both DMA requests have completed
        if (finiteBurst >= 0) {
            p.emit(0x21, 0x04, 0xc0, 0x35);
            p.emit(0xc2, loop & 255, loop >> 8);
        } else {
            p.emit(0xc3, loop & 255, loop >> 8);
        }
        int quiet = p.address();
        p.emit(0x06, 0xe0, 0x05, 0x20, 0xfd); // leave a visible finite recovery interval
        p.io(0xe1, 2); // explicit finite-burst recovery witness
        p.emit(0xc3, quiet & 255, quiet >> 8);
        p.copyTo(image);
        return image;
    }

    private static int[] overlapRoutine(int hdmaMode, int base) {
        // Start OAM first, then request one CGB VRAM-DMA block while OAM owns its bus.
        int[] prefix = {
                0x3e, 0xc1, 0xe0, 0x46,
                0x3e, 0xc1, 0xe0, 0x51,
                0x3e, 0x00, 0xe0, 0x52,
                0x3e, 0x00, 0xe0, 0x53,
                0x3e, 0x00, 0xe0, 0x54,
                0x3e, hdmaMode, 0xe0, 0x55};
        int wait = base + prefix.length + 5;
        int[] suffix = hdmaMode == 0x80
                ? new int[]{0x06, 0xa0, 0x05, 0x20, 0xfd,
                        0xf0, 0x55, 0xe6, 0x80, 0xca, wait & 255, wait >> 8, 0xc9}
                : new int[]{0x06, 0xa0, 0x05, 0x20, 0xfd, 0xc9};
        int[] routine = new int[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, routine, 0, prefix.length);
        System.arraycopy(suffix, 0, routine, prefix.length, suffix.length);
        return routine;
    }

    private static byte[] mbc3RtcImage(Profile profile, int finiteBurst) {
        byte[] image = retainedBase(profile);
        image[0x147] = 0x10; // MBC3 + timer + battery; persistence is disabled by the session.
        image[0x149] = 2;    // one ordinary 8 KiB SRAM bank is enough for this fixture.
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; stack in HRAM.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        p.write(0x0000, 0x0a); // enable the MBC3 window
        p.write(0x4000, 0x00);
        p.write(0xa000, 0x20);
        p.write(RETAINED_PROGRESS, 0).write(RETAINED_PHASE, 0).write(RETAINED_RESULT, 0);
        if (finiteBurst >= 0) p.write(0xc004, finiteBurst);
        int loop = p.address();
        // Ordinary SRAM bank 0 uses a real CPU RMW and leaves a value witness in WRAM.
        p.write(0x4000, 0x00);
        p.emit(0x21, 0x00, 0xa0, 0x34, 0x7e, 0xea, 0x01, 0xc0);
        // Select RTC minute (09), read and write it, then latch and read seconds (08).
        p.write(0x4000, 0x09);
        p.emit(0xfa, 0x00, 0xa0, 0xea, 0x02, 0xc0);
        p.emit(0x3e, 0x12, 0xea, 0x00, 0xa0);
        p.write(0x6000, 0x00).write(0x6000, 0x01);
        p.write(0x4000, 0x08);
        p.emit(0xfa, 0x00, 0xa0, 0xea, 0x03, 0xc0);
        p.emit(0x21, 0xe0, 0xff, 0x34);
        p.io(0xe1, 1); // finite-burst phase witness: mapper accesses have completed
        if (finiteBurst >= 0) {
            p.emit(0x21, 0x04, 0xc0, 0x35);
            p.emit(0xc2, loop & 255, loop >> 8);
        } else {
            p.emit(0xc3, loop & 255, loop >> 8);
        }
        int quiet = p.address();
        p.emit(0x06, 0xe0, 0x05, 0x20, 0xfd); // leave a visible finite recovery interval
        p.io(0xe1, 2); // explicit finite-burst recovery witness
        p.emit(0xc3, quiet & 255, quiet >> 8);
        p.copyTo(image);
        return image;
    }

    /**
     * Persistent legal MMIO reads, with real WRAM result sinks and a completed-loop counter.
     * NOP spacing changes event density without changing the accessed register or read form.
     * This fixture measures the existing fenced path; it grants no new emulation capability.
     */
    public static byte[] pollingImage(
            Scenario scenario, Profile profile, PollingForm form, int spacingNops) {
        int address = pollingAddress(scenario);
        if (address < 0 || form == null || spacingNops < 0 || spacingNops > 32) {
            throw new IllegalArgumentException("A polling scenario, form and 0..32 NOP spacing are required");
        }
        byte[] image = new byte[32768];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = profile.color ? (byte) 0x80 : 0;
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; ordinary HRAM stack.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        switch (scenario) {
            case IF_POLL, TIMA_POLL -> {
                // Frequent real overflow/reload/IF events; DI keeps dispatch outside the loop.
                p.io(0x06, 0xf0).io(0x05, 0xf0).io(0x07, 5);
                if (scenario == Scenario.IF_POLL) p.write(0xffff, 0x1f);
            }
            case IE_POLL -> p.write(0xffff, 0x1f);
            case JOYP_POLL -> p.io(0x00, 0x20); // Select directions; the input hub owns changes.
            case NR52_POLL -> {
                p.io(0x26, 0x80).io(0x24, 0x77).io(0x25, 0x11);
                p.io(0x11, 0x3f).io(0x12, 0xf0).io(0x13, 0).io(0x14, 0xc0);
            }
            case LYC_POLL -> p.io(0x45, 0x20);
            default -> { }
        }
        p.emit(0x0e, address & 255, 0x21, address & 255, address >> 8, 0x11, 0, 0);
        int loop = p.address();
        for (int i = 0; i < 4; i++) {
            PollingForm selected = form == PollingForm.MIXED ? PollingForm.values()[i] : form;
            switch (selected) {
                case LDH_IMMEDIATE -> p.emit(0xf0, address & 255);
                case LDH_C -> p.emit(0xf2);
                case ABSOLUTE -> p.emit(0xfa, address & 255, address >> 8);
                case INDIRECT -> p.emit(0x7e);
                default -> throw new IllegalArgumentException("Unresolved polling form");
            }
            p.emit(0xea, i, 0xc0); // Store each real result; reads must not be optimized away.
            for (int n = 0; n < spacingNops; n++) p.emit(0x00);
        }
        p.emit(0x13, 0x7b, 0xea, 0x10, 0xc0, 0x7a, 0xea, 0x11, 0xc0);
        p.emit(0xc3, loop & 255, loop >> 8);
        p.copyTo(image);
        return image;
    }

    private static int pollingAddress(Scenario scenario) {
        return switch (scenario) {
            case IF_POLL -> 0xff0f;
            case IE_POLL -> 0xffff;
            case DIV_POLL -> 0xff04;
            case TIMA_POLL -> 0xff05;
            case JOYP_POLL -> 0xff00;
            case NR52_POLL -> 0xff26;
            case LYC_POLL -> 0xff45;
            default -> -1;
        };
    }

    private static final class Program {
        private final List<Integer> bytes = new ArrayList<>();
        int address() { return 0x150 + bytes.size(); }
        Program emit(int... data) { for (int value : data) bytes.add(value); return this; }
        Program io(int address, int value) { return emit(0x3e, value, 0xe0, address); }
        Program write(int address, int value) {
            return emit(0x3e, value, 0xea, address & 255, address >> 8);
        }
        void copyTo(byte[] image) {
            for (int i = 0; i < bytes.size(); i++) image[0x150 + i] = bytes.get(i).byteValue();
        }
    }
}
