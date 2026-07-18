package eu.rekawek.coffeegb.core.memory.cart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Compatibility properties for cartridges whose hardware or software does not match the
 * standard header. All ROM-specific signatures and non-standard mapper detection live here;
 * the emulator components only consume the resulting mapper and feature flags.
 */
public final class CartridgeProperties {

    public enum Mapper {
        STANDARD,
        BUNG_EMS,
        HIDDEN_MMM01,
        MANI_32K_MULTICART,
        DUZ_MULTICART,
        BHGOS_MULTICART,
        MAKON_NT_OLD_2,
        SACHEN_MMC1,
        SACHEN_MMC2,
        SACHEN_MMC2_LINEAR,
        SACHEN_COOKED,
        DATEL,
        WISDOM_TREE,
        MBC1
    }

    public enum Feature {
        POCKET_VOICE,
        FORCE_DMG,
        LEGACY_SPEED_SWITCH,
        BLANK_CGB_BOOT_TILE,
        CLEAR_BOOT_TILEMAP,
        DATEL_CGB_HEADER,
        SCRAMBLED_SACHEN_HEADER,
        MBC1_MULTICART,
        MBC1_FULL_BANK_REGISTER,
        MBC1_ALWAYS_ENABLED_RAM,
        MBC2_EXTENDED_BANKING
    }

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    private static final int[] SACHEN_COOKED_HEADER = {
            0x11, 0x23, 0xf1, 0x1e, 0x01, 0x22, 0xf0, 0x00,
            0x08, 0x99, 0x78, 0x00, 0x08, 0x11, 0x9a, 0x48
    };

    /**
     * Ordered so a more specific mapper wins before a broad fallback. Feature-only profiles
     * may still match the same ROM and add their flags.
     */
    private static final List<Profile> PROFILES = List.of(
            features("Pocket Voice V2.0", CartridgeProperties::isPocketVoice,
                    Feature.POCKET_VOICE),
            mapper("Bung/EMS flash cartridge", CartridgeProperties::isBungEms,
                    Mapper.BUNG_EMS),
            mapper("hidden MMM01 multicart", CartridgeProperties::isHiddenMmm01,
                    Mapper.HIDDEN_MMM01),
            mapper("Mani 32 KiB multicart", CartridgeProperties::isMani32kMulticart,
                    Mapper.MANI_32K_MULTICART),
            mapper("Duz multicart", CartridgeProperties::isDuzMulticart,
                    Mapper.DUZ_MULTICART),
            mapper("Blue Hippo G.B.O.S. multicart", CartridgeProperties::isBhgosMulticart,
                    Mapper.BHGOS_MULTICART),
            mapper("Makon/NT old type 2 multicart", CartridgeProperties::isMakonNtOld2,
                    Mapper.MAKON_NT_OLD_2),
            profile("raw Sachen MMC1", CartridgeProperties::isRawSachenMmc1,
                    Mapper.SACHEN_MMC1, Feature.SCRAMBLED_SACHEN_HEADER),
            profile("raw Sachen MMC2", CartridgeProperties::isRawSachenMmc2,
                    Mapper.SACHEN_MMC2, Feature.SCRAMBLED_SACHEN_HEADER),
            mapper("linear-header Sachen MMC2", CartridgeProperties::isLinearSachenMmc2,
                    Mapper.SACHEN_MMC2_LINEAR),
            mapper("cooked Sachen MMC", CartridgeProperties::isCookedSachen,
                    Mapper.SACHEN_COOKED),
            mapper("Datel Action Replay/GameShark", CartridgeProperties::isDatel,
                    Mapper.DATEL),
            features("Datel colour header", CartridgeProperties::hasDatelCgbHeader,
                    Feature.DATEL_CGB_HEADER),
            mapper("Wisdom Tree 32 KiB banking", CartridgeProperties::isWisdomTree,
                    Mapper.WISDOM_TREE),
            mapper("oversized ROM-only image", CartridgeProperties::isOversizedRomOnly,
                    Mapper.MBC1),
            features("Crazy Castle 3 trainer", CartridgeProperties::isCrazyCastleTrainer,
                    Feature.FORCE_DMG),
            features("DarkFader Threads demo", CartridgeProperties::isDarkFaderThreadsDemo,
                    Feature.FORCE_DMG),
            features("Namco Gallery Vol. 2 trainer", CartridgeProperties::isNamcoGallery2Trainer,
                    Feature.LEGACY_SPEED_SWITCH),
            features("Smurfs Lightforce trainer", CartridgeProperties::isSmurfsTrainer,
                    Feature.BLANK_CGB_BOOT_TILE),
            features("AudioArts Gameboy Music V1", CartridgeProperties::isAudioArtsMusicV1,
                    Feature.CLEAR_BOOT_TILEMAP),
            features("MBC1 multicart", CartridgeProperties::isMbc1Multicart,
                    Feature.MBC1_MULTICART),
            features("Hong Kong Pokemon Red", CartridgeProperties::isHongKongPokemonRed,
                    Feature.MBC1_FULL_BANK_REGISTER),
            features("Work Master flash cartridge", CartridgeProperties::isWorkMaster,
                    Feature.MBC1_ALWAYS_ENABLED_RAM),
            features("Gokuu Hishouden Chinese translation", CartridgeProperties::isGokuuTranslation,
                    Feature.MBC2_EXTENDED_BANKING)
    );

    private final Mapper mapper;

    private final Set<Feature> features;

    private final List<String> profiles;

    private CartridgeProperties(Mapper mapper, Set<Feature> features, List<String> profiles) {
        this.mapper = mapper;
        this.features = Collections.unmodifiableSet(features);
        this.profiles = Collections.unmodifiableList(profiles);
    }

    public static CartridgeProperties detect(int[] rom) {
        RomInfo info = new RomInfo(rom);
        Mapper mapper = Mapper.STANDARD;
        EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
        List<String> profiles = new ArrayList<>();
        for (Profile profile : PROFILES) {
            if (!profile.matcher.matches(info)) {
                continue;
            }
            profiles.add(profile.name);
            features.addAll(profile.features);
            if (mapper == Mapper.STANDARD && profile.mapper != Mapper.STANDARD) {
                mapper = profile.mapper;
            }
        }
        return new CartridgeProperties(mapper, features, profiles);
    }

    public Mapper getMapper() {
        return mapper;
    }

    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    public List<String> getProfiles() {
        return profiles;
    }

    int[] getHeader(int[] rom) {
        if (!has(Feature.SCRAMBLED_SACHEN_HEADER)) {
            return rom;
        }
        int[] header = new int[0x150];
        for (int address = 0x100; address < header.length; address++) {
            header[address] = rom[unscrambleSachenAddress(address)];
        }
        return header;
    }

    private static Profile mapper(String name, Matcher matcher, Mapper mapper) {
        return profile(name, matcher, mapper);
    }

    private static Profile features(String name, Matcher matcher, Feature... features) {
        return profile(name, matcher, Mapper.STANDARD, features);
    }

    private static Profile profile(String name, Matcher matcher, Mapper mapper,
                                   Feature... features) {
        EnumSet<Feature> featureSet = EnumSet.noneOf(Feature.class);
        Collections.addAll(featureSet, features);
        return new Profile(name, matcher, mapper, featureSet);
    }

    private static boolean isPocketVoice(RomInfo info) {
        return info.rawType() == 0xbe && info.title().startsWith("Pocket Voice");
    }

    private static boolean isBungEms(RomInfo info) {
        if (isPocketVoice(info)) {
            return false;
        }
        int crc = info.crc32();
        return crc == 0x2ed509d9 // Green Beret
                || crc == 0xf004440c // Cube Raider
                || crc == 0xfdc1483a // Bugs Bunny - Crazy Castle 3 trainer
                || crc == 0x96c29163 // MainBlow
                || info.hasNullTerminatedTitle("EMSMENU")
                || info.hasNullTerminatedTitle("GB16M")
                || info.rawType() == 0xbe
                || (info.rawType() == 0x1b && info.byteAt(0x014a) == 0xe1);
    }

    private static boolean isHiddenMmm01(RomInfo info) {
        int[] data = info.data;
        if (info.rawType() >= 0x0b && info.rawType() <= 0x0d) {
            return false;
        }
        int menuBase = data.length - 0x8000;
        if (menuBase <= 0) {
            return false;
        }
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            if (data[0x104 + i] != data[menuBase + 0x104 + i]) {
                return false;
            }
        }
        int menuType = data[menuBase + 0x147];
        return menuType == 0x0b || menuType == 0x0c || menuType == 0x0d || menuType == 0x11;
    }

    private static boolean isMani32kMulticart(RomInfo info) {
        int type = info.rawType();
        if (isPlainRomType(type) || isMbc1Type(type) || info.data.length < 0x10000) {
            return false;
        }
        int subGames = 0;
        for (int block = 1; block * 0x8000 + 0x150 <= info.data.length; block++) {
            int base = block * 0x8000;
            if (!hasLogoAt(info.data, base + 0x104)) {
                break;
            }
            if (info.data[base + 0x147] != 0x00) {
                return false;
            }
            subGames++;
        }
        return subGames >= 2;
    }

    private static boolean isDuzMulticart(RomInfo info) {
        if (!isMbc3Type(info.rawType()) || info.data.length < 0x10000) {
            return false;
        }
        for (int bank = 2; bank * 0x4000 + 0x134 < info.data.length; bank += 2) {
            if (hasLogoAt(info.data, bank * 0x4000 + 0x104)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBhgosMulticart(RomInfo info) {
        return "MultiCart".equals(info.title())
                && isMbc5Type(info.rawType())
                && info.data.length > 0x8000;
    }

    private static boolean isMakonNtOld2(RomInfo info) {
        return info.data.length > 0x80000
                && "POKEBOM USA".equals(info.title())
                && info.byteAt(0x0102) == 0xe0;
    }

    private static boolean isRawSachenMmc1(RomInfo info) {
        return info.data.length >= 0x10000
                && info.byteAt(0x104) == 0xce
                && info.byteAt(0x144) == 0xed
                && info.byteAt(0x114) == 0x66;
    }

    private static boolean isRawSachenMmc2(RomInfo info) {
        return info.data.length >= 0x10000
                && info.byteAt(0x184) == 0xce
                && info.byteAt(0x1c4) == 0xed
                && info.byteAt(0x194) == 0x66;
    }

    private static boolean isLinearSachenMmc2(RomInfo info) {
        return info.data.length >= 0x10000
                && info.byteAt(0x104) == 0x7c
                && info.byteAt(0x105) == 0xe7
                && info.byteAt(0x106) == 0xc0
                && info.byteAt(0x107) == 0x00
                && hasLogoAt(info.data, 0x184);
    }

    private static boolean isCookedSachen(RomInfo info) {
        if (info.data.length < 0x8000 || info.hasValidLogo()) {
            return false;
        }
        return matches(info.data, 0x104, SACHEN_COOKED_HEADER);
    }

    private static boolean isDatel(RomInfo info) {
        return info.data.length > 0x8000
                && isPlainRomType(info.rawType())
                && !info.hasValidLogo()
                && !isSachen(info);
    }

    private static boolean hasDatelCgbHeader(RomInfo info) {
        return info.data.length > 0x8000
                && info.rawType() == 0x00
                && !info.hasValidLogo()
                && !isSachen(info);
    }

    private static boolean isSachen(RomInfo info) {
        return isRawSachenMmc1(info)
                || isRawSachenMmc2(info)
                || isLinearSachenMmc2(info)
                || isCookedSachen(info);
    }

    private static boolean isWisdomTree(RomInfo info) {
        return info.data.length >= 0x20000
                && isPlainRomType(info.rawType())
                && info.hasValidLogo();
    }

    private static boolean isOversizedRomOnly(RomInfo info) {
        return info.data.length > 0x8000
                && info.data.length < 0x20000
                && isPlainRomType(info.rawType())
                && info.hasValidLogo();
    }

    private static boolean isCrazyCastleTrainer(RomInfo info) {
        int[] entryStub = {0xf5, 0x3e, 0x03, 0xea, 0x00, 0x21, 0xf1, 0xc3, 0x00, 0x68};
        return info.data.length == 0x100000
                && "BUGS CC3 CRACK".equals(info.title())
                && info.byteAt(0x0143) == 0x80
                && info.rawType() == 0x19
                && matches(info.data, 0x00e0, entryStub);
    }

    private static boolean isDarkFaderThreadsDemo(RomInfo info) {
        return info.data.length == 0x10000
                && "OS".equals(info.title())
                && info.byteAt(0x0100) == 0x00 && info.byteAt(0x0101) == 0xc3
                && info.byteAt(0x0102) == 0x91 && info.byteAt(0x0103) == 0x09
                && info.byteAt(0x0143) == 0x80 && info.rawType() == 0x01
                && info.byteAt(0x0149) == 0x00 && info.byteAt(0x014c) == 0x01
                && info.byteAt(0x014e) == 0x7b && info.byteAt(0x014f) == 0x1e;
    }

    private static boolean isNamcoGallery2Trainer(RomInfo info) {
        int[] speedSwitchStub = {
                0x3e, 0x01, 0xe0, 0x4d, 0x10, 0x00, 0x00, 0x00,
                0xcd, 0x0d, 0x71, 0xfa, 0xf0, 0xdf, 0xc3, 0x50, 0x01
        };
        return info.data.length == 0x80000
                && "GALLERY2 +4".equals(info.title())
                && info.byteAt(0x0143) == 0x00
                && info.rawType() == 0x01
                && matches(info.data, 0x3f0fc, speedSwitchStub);
    }

    private static boolean isSmurfsTrainer(RomInfo info) {
        int[] trainerSignature = {
                0x7d, 0xea, 0xa1, 0xc0, 0xe6, 0x03, 0x6f, 0x01,
                0xe0, 0x01, 0xcb, 0x25, 0xcb, 0x25, 0x09, 0xe9
        };
        return info.data.length == 0x100000
                && info.byteAt(0x0143) == 0xc0
                && info.rawType() == 0x1b
                && matches(info.data, 0xddea4, trainerSignature);
    }

    private static boolean isAudioArtsMusicV1(RomInfo info) {
        int[] entryStub = {
                0xf3, 0x31, 0xf4, 0xff, 0xc3, 0x69, 0x01, 0x33,
                0x15, 0x00, 0x40, 0x4b, 0x47, 0x6b, 0x48, 0x00
        };
        return info.data.length == 0x8000
                && "Gameboy Music V1".equals(info.title())
                && info.rawType() == 0x00
                && info.byteAt(0x0148) == 0x00
                && info.byteAt(0x0149) == 0x00
                && info.byteAt(0x014a) == 0xde
                && info.byteAt(0x014b) == 0xc0
                && info.byteAt(0x014c) == 0x03
                && info.byteAt(0x014d) == 0xba
                && info.byteAt(0x014e) == 0x0f
                && info.byteAt(0x014f) == 0x84
                && matches(info.data, 0x0150, entryStub);
    }

    private static boolean isMbc1Multicart(RomInfo info) {
        if (!isMbc1Type(info.rawType()) || info.romBanks() != 64) {
            return false;
        }
        int logoCount = 0;
        for (int bank = 0; bank * 0x4000 < info.data.length; bank++) {
            if (hasLogoAt(info.data, bank * 0x4000 + 0x104)) {
                logoCount++;
            }
        }
        return logoCount > 1;
    }

    private static boolean isHongKongPokemonRed(RomInfo info) {
        return info.romBanks() == 64
                && "POCKETMON BE".equals(info.title())
                && info.byteAt(0x014e) == 0x9c
                && info.byteAt(0x014f) == 0x8c;
    }

    private static boolean isWorkMaster(RomInfo info) {
        return info.data.length == 0x80000 && "WORK MASTER 1.00".equals(info.title());
    }

    private static boolean isGokuuTranslation(RomInfo info) {
        int[] entryStub = {0x00, 0xc3, 0xf0, 0x3f};
        int[] bankStub = {0xf5, 0x3e, 0x0c, 0xea, 0x00, 0x20, 0xf1, 0xc3, 0x00, 0x70};
        return info.data.length == 0x80000
                && "GB DBZ GOKOU".equals(info.title())
                && info.rawType() == 0x06
                && info.byteAt(0x0148) == 0x04
                && matches(info.data, 0x0100, entryStub)
                && matches(info.data, 0x3ff0, bankStub);
    }

    private static boolean isPlainRomType(int type) {
        return type == 0x00 || type == 0x08 || type == 0x09;
    }

    private static boolean isMbc1Type(int type) {
        return type >= 0x01 && type <= 0x03;
    }

    private static boolean isMbc3Type(int type) {
        return type >= 0x0f && type <= 0x13;
    }

    private static boolean isMbc5Type(int type) {
        return type >= 0x19 && type <= 0x1e;
    }

    private static boolean hasLogoAt(int[] data, int offset) {
        return matches(data, offset, NINTENDO_LOGO);
    }

    private static boolean matches(int[] data, int offset, int[] expected) {
        if (offset < 0 || offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int unscrambleSachenAddress(int address) {
        int unscrambled = address & 0xffac;
        unscrambled |= (address & 0x40) >> 6;
        unscrambled |= (address & 0x10) >> 3;
        unscrambled |= (address & 0x02) << 3;
        unscrambled |= (address & 0x01) << 6;
        return unscrambled;
    }

    private interface Matcher {
        boolean matches(RomInfo info);
    }

    private record Profile(String name, Matcher matcher, Mapper mapper, Set<Feature> features) {
    }

    private static final class RomInfo {

        private final int[] data;

        private Integer crc32;

        private RomInfo(int[] data) {
            this.data = data;
        }

        private int byteAt(int address) {
            return address >= 0 && address < data.length ? data[address] : -1;
        }

        private int rawType() {
            return byteAt(0x0147);
        }

        private String title() {
            StringBuilder title = new StringBuilder();
            for (int address = 0x0134; address <= 0x0143 && address < data.length; address++) {
                int value = data[address];
                if (value == 0) {
                    break;
                }
                title.append((char) value);
            }
            return title.toString();
        }

        private boolean hasNullTerminatedTitle(String title) {
            if (0x0134 + title.length() >= data.length) {
                return false;
            }
            for (int i = 0; i < title.length(); i++) {
                if (data[0x0134 + i] != title.charAt(i)) {
                    return false;
                }
            }
            return data[0x0134 + title.length()] == 0;
        }

        private boolean hasValidLogo() {
            return hasLogoAt(data, 0x104);
        }

        private int romBanks() {
            int declaredBanks = switch (byteAt(0x0148)) {
                case 0 -> 2;
                case 1 -> 4;
                case 2 -> 8;
                case 3 -> 16;
                case 4 -> 32;
                case 5 -> 64;
                case 6 -> 128;
                case 7 -> 256;
                case 8 -> 512;
                case 0x52 -> 72;
                case 0x53 -> 80;
                case 0x54 -> 96;
                default -> 2;
            };
            int physicalBanks = Math.max(2, (data.length + 0x3fff) / 0x4000);
            return Math.max(declaredBanks, physicalBanks);
        }

        private int crc32() {
            if (crc32 == null) {
                CRC32 value = new CRC32();
                for (int b : data) {
                    value.update(b);
                }
                crc32 = (int) value.getValue();
            }
            return crc32;
        }
    }
}
