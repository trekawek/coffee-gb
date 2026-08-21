package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
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
 * Unlicensed multicart controller sold on 256 Mbit flash cartridges.
 *
 * <p>The menu uses MBC5-compatible banking. Before starting a game it writes the low eight bits
 * of a 32 KiB base page to {@code 7000}, an inverted page-count mask to {@code 7001}, and the
 * upper base-page bits plus board flags to {@code 7002}. The final write commits a virtual
 * cartridge view whose header selects the embedded game's mapper.</p>
 */
public final class Unlicensed256M implements MemoryController {

    private final int[] cartridge;

    private final Mbc5 menu;

    private final TimeSource timeSource;

    private final ClockSpec clockSpec;

    private final TimeSource guardedTimeSource;

    private int selectedPage;

    private int pageMask;

    private int configuration = -1;

    private MemoryController selectedGame;

    private boolean clockPaused;

    private boolean stateTimeSourceAccessSuppressed;

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private transient DebugHooks debugHooks;

    public Unlicensed256M(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource(), ClockSpec.LEGACY);
    }

    public Unlicensed256M(
            Rom rom,
            Battery battery,
            TimeSource timeSource,
            ClockSpec clockSpec) {
        this.cartridge = rom.getRom();
        this.menu = new Mbc5(rom, battery);
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
        if (address == 0x7000) {
            selectedPage = (selectedPage & 0x300) | value;
        } else if (address == 0x7001) {
            pageMask = ~value & 0xff;
        } else if (address == 0x7002) {
            selectedPage = (selectedPage & 0xff) | ((value & 0x03) << 8);
            selectGame(value);
        } else {
            menu.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        return selectedGame == null ? menu.getByte(address) : selectedGame.getByte(address);
    }

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

    private void selectGame(int value) {
        configuration = value;
        selectedGame = createGame();
        selectedGame.init(eventBus);
        selectedGame.setDebugHooks(debugHooks);
        selectedGame.setStateTimeSourceAccessSuppressed(stateTimeSourceAccessSuppressed);
        selectedGame.setClockPaused(clockPaused);
    }

    private MemoryController createGame() {
        int romBanks = (pageMask + 1) << 1;
        Rom gameRom;
        try {
            gameRom = new Rom(createGameImage(selectedPage << 1, romBanks));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create selected multicart view", e);
        }
        if (gameRom.getType().isMbc1()) {
            return new Mbc1(gameRom, Battery.NULL_BATTERY);
        } else if (gameRom.getType().isMbc2()) {
            return new Mbc2(gameRom, Battery.NULL_BATTERY);
        } else if (gameRom.getType().isMbc3()) {
            return new Mbc3(gameRom, Battery.NULL_BATTERY, guardedTimeSource, clockSpec);
        } else if (gameRom.getType().isMbc5()) {
            return new Mbc5(gameRom, Battery.NULL_BATTERY);
        } else {
            return new BasicRom(gameRom, Battery.NULL_BATTERY);
        }
    }

    private byte[] createGameImage(int baseRomBank, int romBanks) {
        byte[] image = new byte[romBanks * 0x4000];
        Arrays.fill(image, (byte) 0xff);
        int physicalBanks = Math.max(1, (cartridge.length + 0x3fff) / 0x4000);
        for (int bank = 0; bank < romBanks; bank++) {
            int sourceBank = Math.floorMod(baseRomBank + bank, physicalBanks);
            int source = sourceBank * 0x4000;
            int target = bank * 0x4000;
            int copied = Math.min(0x4000, cartridge.length - source);
            for (int i = 0; i < copied; i++) {
                image[target + i] = (byte) cartridge[source + i];
            }
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
        return new Unlicensed256MState(
                menu.captureState(),
                selectedPage,
                pageMask,
                configuration,
                selectedGame == null ? null : selectedGame.captureState());
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Unlicensed256MState(
                menu.captureState(capture),
                selectedPage,
                pageMask,
                configuration,
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
        if (!(state instanceof Unlicensed256MState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        menu.restoreState(mem.menuState);
        selectedPage = mem.selectedPage;
        pageMask = mem.pageMask;
        configuration = mem.configuration;
        if (mem.selectedGameState == null) {
            selectedGame = null;
            return;
        }
        selectedGame = createGame();
        selectedGame.init(eventBus);
        selectedGame.setDebugHooks(debugHooks);
        selectedGame.setStateTimeSourceAccessSuppressed(stateTimeSourceAccessSuppressed);
        selectedGame.setClockPaused(clockPaused);
        selectedGame.restoreState(mem.selectedGameState);
    }

    private record Unlicensed256MState(
            ComponentState<MemoryController> menuState,
            int selectedPage,
            int pageMask,
            int configuration,
            ComponentState<MemoryController> selectedGameState)
            implements ComponentState<MemoryController> {
    }
}
