package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

import java.io.IOException;
import java.util.Arrays;

/**
 * A multi-MBC board with an MBC5 menu and a configuration port in external-RAM space.
 *
 * <p>The menu runs through ordinary MBC5 banking. Before jumping to a selected game it writes a
 * 32 KiB page number, an inverted page mask and a mapper kind to {@code b000}, {@code b100} and
 * {@code b200}. The last write commits a virtual cartridge view; the game then sees its own MBC1,
 * MBC2, MBC3 or MBC5 controller from address zero.</p>
 */
public class Mbc5Multicart implements MemoryController {

    private static final int MBC2_OR_MBC3_MODE = 0xa0;

    private static final int MBC5_MODE = 0xc0;

    private static final int MBC1_MODE = 0xe0;

    private final int[] cartridge;

    private final LoaderMbc5 menu;

    private final TimeSource timeSource;

    private final ClockSpec clockSpec;

    private final TimeSource guardedTimeSource;

    private int selectedPage;

    private int pageMask;

    private int selectedMapperMode = -1;

    private MemoryController selectedGame;

    private boolean clockPaused;

    private boolean stateTimeSourceAccessSuppressed;

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private transient DebugHooks debugHooks;

    public Mbc5Multicart(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource(), ClockSpec.LEGACY);
    }

    public Mbc5Multicart(Rom rom, Battery battery, TimeSource timeSource, ClockSpec clockSpec) {
        this.cartridge = rom.getRom();
        this.menu = new LoaderMbc5(rom, battery);
        this.timeSource = timeSource;
        this.clockSpec = clockSpec;
        this.guardedTimeSource = () -> stateTimeSourceAccessSuppressed
                ? 0 : this.timeSource.currentTimeMillis();
    }

    @Override
    public boolean accepts(int address) {
        return menu.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (selectedGame != null) {
            selectedGame.setByte(address, value);
            return;
        }

        if (address == 0xb000) {
            selectedPage = value;
        } else if (address == 0xb100) {
            pageMask = ~value & 0xff;
        } else if (address == 0xb200) {
            selectGame(value);
        } else {
            menu.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        return selectedGame == null ? menu.getByte(address) : selectedGame.getByte(address);
    }

    /**
     * This controller may switch to MBC3 after the {@link Cartridge} has cached this value, so it
     * must remain clocked from power-on even while the menu is active.
     */
    @Override
    public boolean isClocked() {
        return true;
    }

    @Override
    public void tick() {
        if (selectedGame != null) {
            selectedGame.tick();
        }
    }

    @Override
    public void setClockPaused(boolean paused) {
        clockPaused = paused;
        if (selectedGame != null) {
            selectedGame.setClockPaused(paused);
        }
    }

    @Override
    public void reanchorClockAfterRestore(boolean paused) {
        if (selectedGame != null) {
            selectedGame.reanchorClockAfterRestore(paused);
        }
    }

    @Override
    public void setStateTimeSourceAccessSuppressed(boolean suppressed) {
        stateTimeSourceAccessSuppressed = suppressed;
        if (selectedGame != null) {
            selectedGame.setStateTimeSourceAccessSuppressed(suppressed);
        }
    }

    @Override
    public boolean isRumbleActive() {
        return selectedGame == null ? menu.isRumbleActive() : selectedGame.isRumbleActive();
    }

    @Override
    public void skipBoot() {
        menu.skipBoot();
        if (selectedGame != null) {
            selectedGame.skipBoot();
        }
    }

    @Override
    public void flushRam() {
        menu.flushRam();
        if (selectedGame != null) {
            selectedGame.flushRam();
        }
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus == null ? EventBus.NULL_EVENT_BUS : eventBus;
        menu.init(this.eventBus);
        if (selectedGame != null) {
            selectedGame.init(this.eventBus);
        }
    }

    @Override
    public void setDebugHooks(DebugHooks hooks) {
        debugHooks = hooks;
        menu.setDebugHooks(hooks);
        if (selectedGame != null) {
            selectedGame.setDebugHooks(hooks);
        }
    }

    /** Returns the selected MBC3 controller when the active game uses one. */
    public Mbc3 getActiveMbc3() {
        return selectedGame instanceof Mbc3 mbc3 ? mbc3 : null;
    }

    private void selectGame(int mapperMode) {
        MemoryController game = createGame(mapperMode);
        if (game == null) {
            return;
        }
        selectedMapperMode = mapperMode;
        selectedGame = game;
        selectedGame.init(eventBus);
        selectedGame.setDebugHooks(debugHooks);
        selectedGame.setStateTimeSourceAccessSuppressed(stateTimeSourceAccessSuppressed);
        selectedGame.setClockPaused(clockPaused);
    }

    private MemoryController createGame(int mapperMode) {
        if (mapperMode != MBC1_MODE
                && mapperMode != MBC2_OR_MBC3_MODE
                && mapperMode != MBC5_MODE) {
            return null;
        }
        int romBanks = (pageMask + 1) << 1;
        int baseRomBank = selectedPage << 1;
        Rom gameRom;
        try {
            gameRom = new Rom(createGameImage(baseRomBank, romBanks));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create selected multicart view", e);
        }
        return switch (mapperMode) {
            case MBC1_MODE -> new Mbc1(gameRom, Battery.NULL_BATTERY);
            case MBC2_OR_MBC3_MODE -> gameRom.getType().isMbc2()
                    ? new Mbc2(gameRom, Battery.NULL_BATTERY)
                    : new Mbc3(gameRom, Battery.NULL_BATTERY, guardedTimeSource, clockSpec);
            case MBC5_MODE -> new Mbc5(gameRom, Battery.NULL_BATTERY);
            default -> throw new IllegalStateException("Checked mapper mode is unsupported");
        };
    }

    private byte[] createGameImage(int baseRomBank, int romBanks) {
        byte[] image = new byte[romBanks * 0x4000];
        Arrays.fill(image, (byte) 0xff);
        int source = baseRomBank * 0x4000;
        int copied = Math.max(0, Math.min(image.length, cartridge.length - source));
        for (int i = 0; i < copied; i++) {
            image[i] = (byte) cartridge[source + i];
        }
        Integer sizeCode = romSizeCode(romBanks);
        if (sizeCode != null) {
            image[0x0148] = sizeCode.byteValue();
        }
        return image;
    }

    private static Integer romSizeCode(int banks) {
        if (banks < 2 || banks > 512 || Integer.bitCount(banks) != 1) {
            return null;
        }
        return Integer.numberOfTrailingZeros(banks) - 1;
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new Mbc5MulticartState(
                menu.captureState(),
                selectedPage,
                pageMask,
                selectedMapperMode,
                selectedGame == null ? null : selectedGame.captureState());
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Mbc5MulticartState(
                menu.captureState(capture),
                selectedPage,
                pageMask,
                selectedMapperMode,
                selectedGame == null ? null : selectedGame.captureState(capture));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        menu.declareMachineStatePayloads(capture);
        if (selectedGame != null) {
            selectedGame.declareMachineStatePayloads(capture);
        }
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof Mbc5MulticartState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        menu.restoreState(mem.menuState);
        selectedPage = mem.selectedPage;
        pageMask = mem.pageMask;
        selectedMapperMode = mem.selectedMapperMode;
        if (mem.selectedGameState == null) {
            selectedGame = null;
            return;
        }
        selectedGame = createGame(selectedMapperMode);
        if (selectedGame == null) {
            throw new IllegalArgumentException("Invalid selected multicart mapper mode");
        }
        selectedGame.init(eventBus);
        selectedGame.setDebugHooks(debugHooks);
        selectedGame.setStateTimeSourceAccessSuppressed(stateTimeSourceAccessSuppressed);
        selectedGame.setClockPaused(clockPaused);
        selectedGame.restoreState(mem.selectedGameState);
    }

    private record Mbc5MulticartState(
            ComponentState<MemoryController> menuState,
            int selectedPage,
            int pageMask,
            int selectedMapperMode,
            ComponentState<MemoryController> selectedGameState)
            implements ComponentState<MemoryController> {
    }

    /**
     * The menu first switches a two-bank loader window before that loader writes the external
     * configuration registers. It remains ordinary MBC5 until the {@code aa} strobe arms it.
     */
    private static final class LoaderMbc5 extends Mbc5 {

        private static final int LOADER_BASE_BANK = 0x14;

        private boolean loaderSelectArmed;

        private int loaderBaseBank = -1;

        private LoaderMbc5(Rom rom, Battery battery) {
            super(rom, battery);
        }

        @Override
        public void setByte(int address, int value) {
            value &= 0xff;
            if (address >= 0x7000 && address < 0x8000 && value == 0xaa) {
                loaderSelectArmed = true;
                return;
            }
            if (loaderSelectArmed && address >= 0x2000 && address < 0x3000) {
                loaderBaseBank = LOADER_BASE_BANK + (value << 1);
                setSelectedRomBank(1);
                loaderSelectArmed = false;
                return;
            }
            super.setByte(address, value);
        }

        @Override
        protected int getRomBankFor0x0000() {
            return loaderBaseBank < 0 ? 0 : normalizeRomBank(loaderBaseBank);
        }

        @Override
        protected int getRomBankFor0x4000() {
            return loaderBaseBank < 0
                    ? super.getRomBankFor0x4000()
                    : normalizeRomBank(loaderBaseBank + getSelectedRomBank());
        }

        @Override
        public ComponentState<MemoryController> captureState() {
            return new LoaderMbc5State(super.captureState(), loaderSelectArmed, loaderBaseBank);
        }

        @Override
        public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
            return new LoaderMbc5State(
                    super.captureState(capture), loaderSelectArmed, loaderBaseBank);
        }

        @Override
        public void declareMachineStatePayloads(MachineStateCapture capture) {
            super.declareMachineStatePayloads(capture);
        }

        @Override
        public void restoreState(ComponentState<MemoryController> state) {
            if (!(state instanceof LoaderMbc5State mem)) {
                throw new IllegalArgumentException("Invalid state type");
            }
            super.restoreState(mem.mbc5State);
            loaderSelectArmed = mem.loaderSelectArmed;
            loaderBaseBank = mem.loaderBaseBank;
        }

        private record LoaderMbc5State(
                ComponentState<MemoryController> mbc5State,
                boolean loaderSelectArmed,
                int loaderBaseBank)
                implements ComponentState<MemoryController> {
        }
    }
}
