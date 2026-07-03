package eu.rekawek.coffeegb.core.memory.cart;

import java.util.regex.Pattern;

public enum CartridgeType {
    ROM(0x00),
    ROM_MBC1(0x01),
    ROM_MBC1_RAM(0x02),
    ROM_MBC1_RAM_BATTERY(0x03),
    ROM_MBC2(0x05),
    ROM_MBC2_BATTERY(0x06),
    ROM_RAM(0x08),
    ROM_RAM_BATTERY(0x09),
    ROM_MMM01(0x0b),
    ROM_MMM01_SRAM(0x0c),
    ROM_MMM01_SRAM_BATTERY(0x0d),
    ROM_MBC3_TIMER_BATTERY(0x0f),
    ROM_MBC3_TIMER_RAM_BATTERY(0x10),
    ROM_MBC3(0x11),
    ROM_MBC3_RAM(0x12),
    ROM_MBC3_RAM_BATTERY(0x13),
    ROM_MBC5(0x19),
    ROM_MBC5_RAM(0x1a),
    ROM_MBC5_RAM_BATTERY(0x01b),
    ROM_MBC5_RUMBLE(0x1c),
    ROM_MBC5_RUMBLE_SRAM(0x1d),
    ROM_MBC5_RUMBLE_SRAM_BATTERY(0x1e),
    ROM_MBC6(0x20),
    ROM_MBC7_SENSOR_RUMBLE_RAM_BATTERY(0x22),
    ROM_POCKET_CAMERA(0xfc),
    ROM_TAMA5_RAM_BATTERY(0xfd),
    ROM_HUC3_RAM_BATTERY(0xfe),
    ROM_HUC1_RAM_BATTERY(0xff);

    private final int id;

    CartridgeType(int id) {
        this.id = id;
    }

    public boolean isPocketCamera() {
        return nameContainsSegment("POCKET") ;
    }

    public boolean isMbc1() {
        return nameContainsSegment("MBC1");
    }

    public boolean isMbc2() {
        return nameContainsSegment("MBC2");
    }

    public boolean isMbc3() {
        return nameContainsSegment("MBC3");
    }

    public boolean isMbc5() {
        return nameContainsSegment("MBC5");
    }

    public boolean isMbc6() {
        return nameContainsSegment("MBC6");
    }

    public boolean isMbc7() {
        return nameContainsSegment("MBC7");
    }

    public boolean isMmm01() {
        return nameContainsSegment("MMM01");
    }

    public boolean isHuc1() {
        return nameContainsSegment("HUC1");
    }

    public boolean isHuc3() {
        return nameContainsSegment("HUC3");
    }

    public boolean isTama5() {
        return nameContainsSegment("TAMA5");
    }

    public boolean isRam() {
        return nameContainsSegment("RAM");
    }

    public boolean isSram() {
        return nameContainsSegment("SRAM");
    }

    public boolean isTimer() {
        return nameContainsSegment("TIMER");
    }

    public boolean isBattery() {
        return nameContainsSegment("BATTERY");
    }

    public boolean isRumble() {
        return nameContainsSegment("RUMBLE");
    }

    private boolean nameContainsSegment(String segment) {
        Pattern p = Pattern.compile("(^|_)" + Pattern.quote(segment) + "($|_)");
        return p.matcher(name()).find();
    }

    public static CartridgeType getById(int id) {
        for (CartridgeType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported cartridge type: " + Integer.toHexString(id));
    }
}
