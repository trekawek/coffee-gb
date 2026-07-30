package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.debug.DebugButton;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.InputTrace;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Joypad implements AddressSpace, StatefulComponent<Joypad> {

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);

    /** Number of JOYP clock samples used by the hardware's input glitch filter. */
    private static final int INPUT_FILTER_SAMPLES = 4;

    private static final int INPUT_FILTER_MASK = (1 << INPUT_FILTER_SAMPLES) - 1;

    private final Set<Button> buttons = new CopyOnWriteArraySet<>();
    private final InterruptManager interruptManager;
    private final boolean isSgb;
    private final EventBus sgbBus;
    private final PlayerInputSource playerInputSource;

    /** Immutable physical input latched at the most recent Joypad clock boundary. */
    private PlayerInputSnapshot sampledInput = PlayerInputSnapshot.released();

    private int p1;
    private long tick;
    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private int inputHistory;

    /** Filtered electrical level of the four shared P10-P13 input lines. */
    private int filteredInputLines = 0x0f;

    private volatile boolean inputChangedSinceLastTick;

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    /** Stable {@link DebugButton#ordinal()} mask for the effective P1 input union. */
    private transient int observedDebugButtonMask;

    /** Owner-thread replay/input observer; deliberately absent from portable machine state. */
    private transient InputTimelineObserver inputTimelineObserver;

    /** Source-local alignment state used only while an input timeline observer is attached. */
    private transient int observedLegacyButtonMask;

    private final transient int[] observedPhysicalButtonMasks =
            new int[PlayerInputSource.PLAYER_COUNT];

    private int players;
    private int currentPlayer;

    private boolean transferInProgress;
    private boolean transferReadyForData;
    private int pendingTransferBit = -1;
    private int currentByte;
    private final int[] currentPacket = new int[16];
    private int currentByteIndex;
    private int currentPacketIndex;

    public Joypad(InterruptManager interruptManager, EventBus sgbBus, boolean isSgb) {
        this(interruptManager, sgbBus, isSgb, PlayerInputSource.RELEASED);
    }

    public Joypad(InterruptManager interruptManager, EventBus sgbBus, boolean isSgb,
                  PlayerInputSource playerInputSource) {
        this.interruptManager = interruptManager;
        this.isSgb = isSgb;
        this.sgbBus = sgbBus;
        this.playerInputSource = Objects.requireNonNull(playerInputSource, "playerInputSource");
        // JOYP powers on with both selector lines low. On an SGB, that level has already
        // reset the ICD2 receiver, so the first transition to idle-high may release the
        // start pulse without software having to create another falling edge first.
        transferInProgress = isSgb;
        transferReadyForData = false;
        sgbBus.register(event -> {
            players = event.getMultiplayerControl() & 0x03;
            if (players == 2) {
                // Undocumented MLT_REQ value 2 keeps the ICD2 in a distinct state:
                // player IDs 1 and 2 read as 2, while IDs 0 and 3 read as 0.
                currentPlayer = currentPlayer == 1 || currentPlayer == 2 ? 2 : 0;
            } else {
                currentPlayer &= players;
            }
            LOG.atDebug().log("Players: {}, current player: {}", players, currentPlayer);
        }, Commands.MltReqCmd.class);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(event -> onPress(event.button()), ButtonPressEvent.class);
        eventBus.register(event -> onRelease(event.button()), ButtonReleaseEvent.class);
    }

    private void onPress(Button button) {
        if (eventBus != null) {
            eventBus.post(new JoypadPressEvent(button, tick));
        }
        LOG.atDebug().log("Pressed button {} at tick {}", button, tick);
        if (buttons.add(button)) {
            inputChangedSinceLastTick = true;
            notifyDebugInputChange();
            notifyLegacyInputChange();
        }
    }

    private void onRelease(Button button) {
        LOG.atDebug().log("Released button {} at tick {}", button, tick);
        if (buttons.remove(button)) {
            inputChangedSinceLastTick = true;
            notifyDebugInputChange();
            notifyLegacyInputChange();
        }
    }

    /**
     * The effective P1 set of currently-held buttons. It combines the historical event stream
     * with the latest live-source latch and is intentionally not part of machine state (see
     * {@link #captureState()}). Rollback netplay snapshots only the event-owned subset; the live
     * service remains current across restore.
     */
    public Set<Button> getPressedButtons() {
        Set<Button> pressed = new java.util.HashSet<>(buttons);
        pressed.addAll(sampledInput.buttons(0));
        return pressed;
    }

    /**
     * P1 buttons owned by the historical event/session stream. Unlike {@link #getPressedButtons()},
     * this excludes the live input service so detached session state cannot resurrect a physical
     * press after that source has released it.
     */
    public Set<Button> getLegacyPressedButtons() {
        return new java.util.HashSet<>(buttons);
    }

    /**
     * Immutable physical input latched at the most recent joypad sample boundary.
     *
     * <p>This is an observation-only replay preflight seam. The returned value owns immutable
     * button sets and cannot mutate the live input service or joypad.</p>
     */
    public PlayerInputSnapshot getSampledInput() {
        return sampledInput;
    }

    public void setPressedButtons(Collection<Button> pressed) {
        if (buttons.equals(Set.copyOf(pressed))) {
            return;
        }
        buttons.clear();
        buttons.addAll(pressed);
        inputChangedSinceLastTick = true;
        notifyDebugInputChange();
        notifyLegacyInputChange();
    }

    public void tick() {
        tick++;
        PlayerInputSnapshot nextInput = Objects.requireNonNull(
                playerInputSource.sample(), "PlayerInputSource returned null");
        if (!sampledInput.equals(nextInput)) {
            sampledInput = nextInput;
            inputChangedSinceLastTick = true;
            notifyDebugInputChange();
            notifyPhysicalInputChanges();
        }
        // JOYP writes happen after the joypad clock edge represented by this emulator
        // tick. Start sampling a changed input on the following tick, then require four
        // consecutive samples. This models the four flip-flop input filter visible in
        // the DMG joypad circuit and keeps short selector glitches from raising IF.
        if (inputChangedSinceLastTick) {
            inputChangedSinceLastTick = false;
            return;
        }
        int inputLines = getInputLines();
        int nextFilteredInputLines = filteredInputLines;
        for (int line = 0; line < 4; line++) {
            int shift = line * INPUT_FILTER_SAMPLES;
            int history = (inputHistory >>> shift) & INPUT_FILTER_MASK;
            boolean inputLow = (inputLines & (1 << line)) == 0;
            history = ((history << 1) | (inputLow ? 1 : 0)) & INPUT_FILTER_MASK;
            inputHistory = (inputHistory & ~(INPUT_FILTER_MASK << shift)) | (history << shift);
            if (history == INPUT_FILTER_MASK) {
                nextFilteredInputLines &= ~(1 << line);
            } else if (history == 0) {
                nextFilteredInputLines |= 1 << line;
            }
        }
        int fallingEdges = filteredInputLines & ~nextFilteredInputLines & 0x0f;
        filteredInputLines = nextFilteredInputLines;
        if (fallingEdges != 0) {
            interruptManager.requestInterrupt(InterruptManager.InterruptType.P10_13);
        }
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff00;
    }

    @Override
    public void setByte(int address, int value) {
        int previousSelection = p1;
        if (isSgb) {
            int input = value & 0x30;
            // The ICD2 receiver reacts to line transitions. Rewriting the level that is
            // already on JOYP must neither add a bit nor abort an in-flight packet.
            if (input != p1) {
                receiveSgbPacketPulse(input);
            }
        }
        if (players > 0 && players != 2
                && !BitUtils.getBit(p1, 5) && BitUtils.getBit(value, 5)) {
            currentPlayer++;
            if (currentPlayer > players) {
                currentPlayer = 0;
            }
            LOG.atDebug().log("Player changed to {}", currentPlayer);
        }
        p1 = value & 0b00110000;
        if (p1 != previousSelection) {
            inputChangedSinceLastTick = true;
        }
    }

    private void receiveSgbPacketPulse(int input) {
        if (input == 0x00) {
            // Both lines low reset the receiver and start a fresh 16-byte packet.
            // If a packet was already in progress, this is also an unambiguous transport abort.
            // The command collector needs that explicit signal because a complete continuation
            // packet's payload header is otherwise indistinguishable from a new command header.
            boolean abortedTransfer = transferInProgress;
            transferInProgress = true;
            transferReadyForData = false;
            pendingTransferBit = -1;
            currentByte = 0;
            currentByteIndex = 0;
            currentPacketIndex = 0;
            Arrays.fill(currentPacket, 0);
            if (abortedTransfer) {
                sgbBus.post(new SuperGameboy.PacketTransferAbortedEvent());
            }
            return;
        }
        if (!transferInProgress) {
            return;
        }
        if (!transferReadyForData) {
            if (input == 0x30) {
                transferReadyForData = true;
            }
            return;
        }

        if (input == 0x10 || input == 0x20) {
            // ICD2 samples a pulse when both selector lines return high. If software
            // switches directly between the two low levels, the last level wins.
            pendingTransferBit = input == 0x10 ? 1 : 0;
            return;
        }
        if (input != 0x30 || pendingTransferBit < 0) {
            return;
        }

        if (currentPacketIndex == currentPacket.length) {
            // The 129th pulse terminates a packet. Hardware ignores its bit value.
            sgbBus.post(new SuperGameboy.PacketReceivedEvent(currentPacket.clone()));
            abortSgbPacket();
            return;
        }

        if (pendingTransferBit != 0) {
            currentByte |= 1 << currentByteIndex;
        }
        pendingTransferBit = -1;
        currentByteIndex++;
        if (currentByteIndex == 8) {
            currentPacket[currentPacketIndex++] = currentByte;
            currentByteIndex = 0;
            currentByte = 0;
        }
    }

    private void abortSgbPacket() {
        transferInProgress = false;
        transferReadyForData = false;
        pendingTransferBit = -1;
    }

    @Override
    public int getByte(int address) {
        return p1 | 0b11000000 | getInputLines();
    }

    private int getInputLines() {
        if (players > 0 && (p1 & 0x30) == 0x30) {
            LOG.atDebug().log("Returning player {} as current player", currentPlayer);
            return 0x0f - currentPlayer;
        }

        int result = applyButtons(0x0f, sampledInput.buttons(currentPlayer));
        // The historical event-driven API remains P1-only for controller/netplay replay.
        if (currentPlayer == 0) {
            result = applyButtons(result, buttons);
        }
        return result;
    }

    private int applyButtons(int inputLines, Collection<Button> pressedButtons) {
        int result = inputLines;
        for (Button button : pressedButtons) {
            if ((button.getLine() & p1) == 0) {
                result &= 0xff & ~button.getMask();
            }
        }
        return result;
    }

    @Override
    public ComponentState<Joypad> captureState() {
        // the pressed-buttons set is live physical input, not machine state - it is
        // deliberately left out of component state so that restoring a state (rewind, save
        // slot) keeps whatever the player is physically holding right now. Otherwise a
        // button held in a rewound-past frame would be re-applied and, with no matching
        // release event ever arriving, stick when forward emulation resumes (issue: rewind
        // replays past button presses).
        return new JoypadState(p1, tick, inputHistory, filteredInputLines,
                inputChangedSinceLastTick, players, currentPlayer, transferInProgress,
                transferReadyForData, pendingTransferBit, currentByte, currentPacket.clone(),
                currentByteIndex, currentPacketIndex);
    }

    @Override
    public ComponentState<Joypad> captureState(MachineStateCapture capture) {
        return new JoypadState(p1, tick, inputHistory, filteredInputLines,
                inputChangedSinceLastTick, players, currentPlayer, transferInProgress,
                transferReadyForData, pendingTransferBit, currentByte, capture.ints(currentPacket),
                currentByteIndex, currentPacketIndex);
    }

    @Override
    public void restoreState(ComponentState<Joypad> state) {
        if (!(state instanceof JoypadState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        validateState(mem);
        this.p1 = mem.p1;
        this.tick = mem.tick;
        this.inputHistory = mem.inputHistory;
        this.filteredInputLines = mem.filteredInputLines;
        this.inputChangedSinceLastTick = mem.inputChangedSinceLastTick;
        this.players = mem.players;
        this.currentPlayer = mem.currentPlayer;
        this.transferInProgress = mem.transferInProgress;
        this.transferReadyForData = mem.transferReadyForData;
        this.pendingTransferBit = mem.pendingTransferBit;
        this.currentByte = mem.currentByte;
        System.arraycopy(mem.currentPacket, 0, this.currentPacket, 0, mem.currentPacket.length);
        this.currentByteIndex = mem.currentByteIndex;
        this.currentPacketIndex = mem.currentPacketIndex;
        alignDebugInput();
        alignInputTimeline();
    }

    /**
     * Exclusively installs an owner-thread observer without emitting alignment events.
     *
     * @return false when another capture already owns the observation seam
     */
    public boolean attachInputTimelineObserver(InputTimelineObserver observer) {
        Objects.requireNonNull(observer, "observer");
        if (inputTimelineObserver != null) {
            return false;
        }
        alignInputTimeline();
        this.inputTimelineObserver = observer;
        return true;
    }

    /** Clears the observer only when the caller still owns the installed instance. */
    public boolean detachInputTimelineObserver(InputTimelineObserver observer) {
        Objects.requireNonNull(observer, "observer");
        if (inputTimelineObserver != observer) {
            return false;
        }
        alignInputTimeline();
        inputTimelineObserver = null;
        return true;
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        alignDebugInput();
        this.debugHooks = debugHooks;
    }

    private void notifyDebugInputChange() {
        int buttonMask = getDebugButtonMask();
        int changedMask = buttonMask ^ observedDebugButtonMask;
        if (changedMask == 0) {
            return;
        }
        int pressedMask = buttonMask & changedMask;
        int releasedMask = observedDebugButtonMask & changedMask;
        observedDebugButtonMask = buttonMask;
        DebugHooks hooks = debugHooks;
        if (hooks == null) {
            return;
        }
        InputTrace.Kind kind;
        if (releasedMask == 0) {
            kind = InputTrace.Kind.PRESSED;
        } else if (pressedMask == 0) {
            kind = InputTrace.Kind.RELEASED;
        } else {
            kind = InputTrace.Kind.STATE_CHANGED;
        }
        hooks.onInputEvent(kind, buttonMask, changedMask);
    }

    private void alignDebugInput() {
        observedDebugButtonMask = getDebugButtonMask();
    }

    private void notifyLegacyInputChange() {
        InputTimelineObserver observer = inputTimelineObserver;
        if (observer == null) {
            return;
        }
        int buttonMask = JoypadButtonMask.fromButtons(buttons);
        int changedMask = buttonMask ^ observedLegacyButtonMask;
        observedLegacyButtonMask = buttonMask;
        if (changedMask != 0) {
            observer.onInputChanged(InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK,
                    0, buttonMask, changedMask);
        }
    }

    private void notifyPhysicalInputChanges() {
        InputTimelineObserver observer = inputTimelineObserver;
        if (observer == null) {
            return;
        }
        for (int player = 0; player < PlayerInputSource.PLAYER_COUNT; player++) {
            int buttonMask = JoypadButtonMask.fromButtons(sampledInput.buttons(player));
            int changedMask = buttonMask ^ observedPhysicalButtonMasks[player];
            observedPhysicalButtonMasks[player] = buttonMask;
            if (changedMask != 0) {
                observer.onInputChanged(InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE,
                        player, buttonMask, changedMask);
            }
        }
    }

    private void alignInputTimeline() {
        observedLegacyButtonMask = JoypadButtonMask.fromButtons(buttons);
        for (int player = 0; player < PlayerInputSource.PLAYER_COUNT; player++) {
            observedPhysicalButtonMasks[player] =
                    JoypadButtonMask.fromButtons(sampledInput.buttons(player));
        }
    }

    private int getDebugButtonMask() {
        int result = 0;
        for (Button button : buttons) {
            result |= getDebugButtonMask(button);
        }
        for (Button button : sampledInput.buttons(0)) {
            result |= getDebugButtonMask(button);
        }
        return result;
    }

    private static int getDebugButtonMask(Button button) {
        DebugButton debugButton = switch (button) {
            case RIGHT -> DebugButton.RIGHT;
            case LEFT -> DebugButton.LEFT;
            case UP -> DebugButton.UP;
            case DOWN -> DebugButton.DOWN;
            case A -> DebugButton.A;
            case B -> DebugButton.B;
            case SELECT -> DebugButton.SELECT;
            case START -> DebugButton.START;
        };
        return 1 << debugButton.ordinal();
    }

    private static void validateState(JoypadState state) {
        if ((state.p1 & 0xcf) != 0) {
            throw new IllegalArgumentException("Invalid JOYP selector state");
        }
        if (state.players < 0 || state.players > 3) {
            throw new IllegalArgumentException("Invalid SGB multiplayer control");
        }
        boolean validPlayer = switch (state.players) {
            case 0 -> state.currentPlayer == 0;
            case 1 -> state.currentPlayer >= 0 && state.currentPlayer <= 1;
            case 2 -> state.currentPlayer == 0 || state.currentPlayer == 2;
            case 3 -> state.currentPlayer >= 0 && state.currentPlayer <= 3;
            default -> false;
        };
        if (!validPlayer) {
            throw new IllegalArgumentException("Invalid selected SGB player");
        }
        if ((state.inputHistory & ~0xffff) != 0
                || (state.filteredInputLines & ~0x0f) != 0) {
            throw new IllegalArgumentException("Invalid JOYP filter state");
        }
        if (state.pendingTransferBit < -1 || state.pendingTransferBit > 1
                || state.currentByte < 0 || state.currentByte > 0xff
                || state.currentByteIndex < 0 || state.currentByteIndex > 7) {
            throw new IllegalArgumentException("Invalid SGB receiver state");
        }
        if (state.currentPacket == null || state.currentPacket.length != 16
                || state.currentPacketIndex < 0
                || state.currentPacketIndex > state.currentPacket.length) {
            throw new IllegalArgumentException("Invalid SGB packet state");
        }
        for (int value : state.currentPacket) {
            if (value < 0 || value > 0xff) {
                throw new IllegalArgumentException("Invalid SGB packet byte");
            }
        }
        if ((!state.transferInProgress
                && (state.transferReadyForData || state.pendingTransferBit != -1))
                || (state.pendingTransferBit != -1 && !state.transferReadyForData)) {
            throw new IllegalArgumentException("Incoherent SGB receiver state");
        }
    }

    /** Stable platform-neutral SGB multiplayer diagnostics for UI/status consumers. */
    public SgbMultiplayerStatus getSgbMultiplayerStatus() {
        return new SgbMultiplayerStatus(SgbMultiplayerMode.fromControl(players), currentPlayer);
    }

    public enum SgbMultiplayerMode {
        ONE_PLAYER(0, 1),
        TWO_PLAYER(1, 2),
        CONTROL_2_COMPATIBILITY(2, 2),
        FOUR_PLAYER(3, 4);

        private final int control;
        private final int playerCount;

        SgbMultiplayerMode(int control, int playerCount) {
            this.control = control;
            this.playerCount = playerCount;
        }

        public int control() {
            return control;
        }

        public int playerCount() {
            return playerCount;
        }

        private static SgbMultiplayerMode fromControl(int control) {
            return values()[control];
        }
    }

    public record SgbMultiplayerStatus(SgbMultiplayerMode mode, int selectedPlayer) {
        public SgbMultiplayerStatus {
            Objects.requireNonNull(mode, "mode");
            PlayerInputSnapshot.checkPlayer(selectedPlayer);
            boolean coherent = switch (mode) {
                case ONE_PLAYER -> selectedPlayer == 0;
                case TWO_PLAYER -> selectedPlayer <= 1;
                case CONTROL_2_COMPATIBILITY -> selectedPlayer == 0 || selectedPlayer == 2;
                case FOUR_PLAYER -> true;
            };
            if (!coherent) {
                throw new IllegalArgumentException("Selected player is invalid for " + mode);
            }
        }

        public int playerCount() {
            return mode.playerCount();
        }
    }

    private record JoypadState(int p1, long tick, int inputHistory,
                                int filteredInputLines,
                                boolean inputChangedSinceLastTick,
                                int players, int currentPlayer,
                                boolean transferInProgress, boolean transferReadyForData,
                                int pendingTransferBit, int currentByte, int[] currentPacket,
                                int currentByteIndex, int currentPacketIndex) implements ComponentState<Joypad> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record JoypadMemento(int p1, long tick, int inputHistory,
                                int filteredInputLines,
                                boolean inputChangedSinceLastTick,
                                int players, int currentPlayer,
                                boolean transferInProgress, boolean transferReadyForData,
                                int pendingTransferBit, int currentByte, int[] currentPacket,
                                int currentByteIndex, int currentPacketIndex) implements Memento<Joypad> {
    }

    public record JoypadPressEvent(Button button, long tick) implements Event {
    }
}
