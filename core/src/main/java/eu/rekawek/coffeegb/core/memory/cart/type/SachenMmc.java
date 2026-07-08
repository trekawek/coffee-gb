package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Sachen MMC1/MMC2 multicart mapper ("4B-xxx" 4-in-1 collections and the Rocket Games
 * two-in-one carts, issues #73/#75), modelled after mGBA's reverse-engineered
 * implementation (src/gb/mbc/unlicensed.c).
 *
 * <p>Registers (all gated behaviours as on hardware):
 *
 * <ul>
 *   <li>{@code 0x0000-0x1FFF} - base ROM bank, accepted only while bits 4-5 of the last
 *       unmasked bank value are both set; the 0x0000-0x3FFF window maps
 *       {@code base & mask};</li>
 *   <li>{@code 0x2000-0x3FFF} - ROM bank (0 becomes 1); the switchable window maps
 *       {@code (bank & ~mask) | (base & mask)};</li>
 *   <li>{@code 0x4000-0x5FFF} - bank mask, gated like the base register.</li>
 * </ul>
 *
 * <p>The 0x0100-0x01FF page is address-scrambled on the cartridge bus (A0 swaps with A6
 * and A1 with A4), permanently. On top of that sits the boot-logo lockout: while locked,
 * reads of that page are counted and (in the CGB-locked state) redirected to 0x0180-0x01FF,
 * where the carts store the logo the boot ROM must see; the 0x31st read advances the lock
 * state. The MMC2 additionally starts in a DMG-locked state and moves to the CGB-locked
 * state on the first bus read at or above 0xC000 (the CGB boot ROM touches WRAM before
 * reading the logo, the DMG one does not - that is how the cart tells the consoles apart
 * and serves the right logo to each).
 */
public class SachenMmc implements MemoryController {

    private static final int LOCKED_DMG = 0;
    private static final int LOCKED_CGB = 1;
    private static final int UNLOCKED = 2;

    private final int[] rom;

    private final int romBanks;

    private final boolean mmc2;

    private int unmaskedBank = 1;

    private int mask;

    private int base;

    private int lockState;

    private int transition;

    // false for post-unlock ("cooked") dumps stored with a linear 0x0100-0x01FF page
    private final boolean scrambled;

    // while true, reads of the 0x0104-0x0133 logo window return the Nintendo logo instead of
    // the linear ROM data. Cooked dumps have no logo stored (the raw carts serve it through
    // the scrambling/lockout); this reproduces that so the authentic boot ROM's logo check
    // passes. It is cleared on the first cartridge write - the boot ROM never writes the
    // cart, so the first write means the game has taken over and wants the real bytes.
    private boolean serveBootLogo;

    /**
     * The Nintendo logo the boot ROM verifies (0x0104-0x0133). The real Sachen mapper
     * descrambles this for the boot ROM; a cooked dump stores game code here instead.
     */
    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89,
            0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    public SachenMmc(Rom rom, boolean mmc2) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, this.rom.length / 0x4000);
        this.mmc2 = mmc2;
        this.scrambled = true;
        // the MMC1 has no DMG/CGB detection phase; it starts in the counting state
        this.lockState = mmc2 ? LOCKED_DMG : LOCKED_CGB;
    }

    /** Cooked (post-unlock) dump: no scrambling/lockout; boots at the given base bank. */
    public SachenMmc(Rom rom, int initialBase) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, this.rom.length / 0x4000);
        this.mmc2 = true;
        this.scrambled = false;
        this.lockState = UNLOCKED;
        this.base = initialBase;
        this.serveBootLogo = true;
    }

    @Override
    public boolean accepts(int address) {
        return address >= 0x0000 && address < 0x8000;
    }

    /** Notified by the MMU of reads at 0xC000 and above (visible on the cartridge bus). */
    public void onHighBusRead() {
        if (mmc2 && lockState == LOCKED_DMG) {
            lockState = LOCKED_CGB;
            transition = 0;
        }
    }

    @Override
    public void setByte(int address, int value) {
        // the boot ROM never writes the cart; the first write is the game taking over, so the
        // cooked logo shim steps aside and the real ROM bytes become visible
        serveBootLogo = false;
        switch (address >> 13) {
            case 0:
                if ((unmaskedBank & 0x30) == 0x30) {
                    base = value & 0xff;
                }
                break;
            case 1:
                unmaskedBank = (value & 0xff) == 0 ? 1 : (value & 0xff);
                break;
            case 2:
                if ((unmaskedBank & 0x30) == 0x30) {
                    mask = value & 0xff;
                }
                break;
            default:
                break;
        }
    }

    /** The 0x0100-0x01FF page is wired with A0<->A6 and A1<->A4 swapped. */
    private static int unscramble(int address) {
        int unscrambled = address & 0xffac;
        unscrambled |= (address & 0x40) >> 6;
        unscrambled |= (address & 0x10) >> 3;
        unscrambled |= (address & 0x02) << 3;
        unscrambled |= (address & 0x01) << 6;
        return unscrambled;
    }

    @Override
    public int getByte(int address) {
        if (serveBootLogo && address >= 0x0104 && address < 0x0134) {
            return NINTENDO_LOGO[address - 0x0104];
        }
        if (scrambled) {
            address = lockAndUnscramble(address);
        }
        if (address < 0x4000) {
            // the cooked dumps hold the menu at its physical bank; the raw carts map
            // the masked base like mGBA does
            int bank0 = scrambled ? (base & mask) : base;
            return rom[(bank0 % romBanks) * 0x4000 + address];
        } else if (address < 0x8000) {
            int effective = ((unmaskedBank & ~mask) | (base & mask)) % romBanks;
            return rom[effective * 0x4000 + (address - 0x4000)];
        }
        return 0xff;
    }

    private int lockAndUnscramble(int address) {
        if (lockState != UNLOCKED && (address & 0xff00) == 0x0100) {
            transition++;
            if (transition == 0x31) {
                lockState++;
                transition = 0;
            } else if (!mmc2 || lockState == LOCKED_CGB) {
                // while locked the page serves the 0x0180-0x01FF logo copy
                address |= 0x80;
            }
        }
        if ((address & 0xff00) == 0x0100) {
            address = unscramble(address);
        }
        return address;
    }

    @Override
    public void flushRam() {
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new SachenMemento(unmaskedBank, mask, base, lockState, transition, serveBootLogo);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof SachenMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.unmaskedBank = mem.unmaskedBank;
        this.mask = mem.mask;
        this.base = mem.base;
        this.lockState = mem.lockState;
        this.transition = mem.transition;
        this.serveBootLogo = mem.serveBootLogo;
    }

    private record SachenMemento(int unmaskedBank, int mask, int base, int lockState,
                                 int transition, boolean serveBootLogo) implements Memento<MemoryController> {
    }
}
