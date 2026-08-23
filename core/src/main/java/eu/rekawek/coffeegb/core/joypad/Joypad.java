package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.debug.DebugButton;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
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

    /** Keep the component-side bulk contract bounded like the normal-speed CPU phase. */
    private static final int PERFORMANCE_MAX_QUIET_SPAN = 3;

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);

    /** Number of JOYP clock samples used by the hardware's input glitch filter. */
    private static final int INPUT_FILTER_SAMPLES = 4;

    private static final int INPUT_FILTER_MASK = (1 << INPUT_FILTER_SAMPLES) - 1;

    /** BOGA clocks the DMG JOYP receiver once per four 4.194304 MHz master ticks. */
    static final int JOYP_CLOCK_TICKS = 4;

    /**
     * Host input is stable between GUI updates, so the thread-safe live hub needs polling only at
     * this master-tick cadence. The initial tick is a poll boundary and later boundaries reuse the
     * persisted {@link #tick} phase rather than storing a second cadence counter.
     */
    static final int PLAYER_INPUT_HUB_POLL_TICKS = 64;

    /** Fully settled four-sample history for each active-low input-line level. */
    private static final int[] SETTLED_HISTORY = createSettledHistory();

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

    /**
     * Derived state for the default released-input fast path. It is deliberately transient and
     * excluded from portable state: state restore recomputes it from the fields it summarizes.
     * Volatile invalidation lets a UI input thread conservatively publish a pending mutation
     * before the emulation thread considers skipping its regular sample path.
     */
    private transient volatile boolean releasedInputFastPathEligible;

    /**
     * Derived settled-state shortcut for the default thread-safe desktop input hub. Unlike the
     * released-only shortcut, this permits a held physical input once its JOYP filter has settled.
     */
    private transient volatile boolean playerInputHubFastPathEligible;

    /**
     * Complete cached eligibility for a PERFORMANCE span. The ordinary input shortcuts above
     * intentionally cover a few states (for example a settled legacy button) which still need
     * scalar packet observation. Keeping that distinction here lets the hot scheduler perform
     * one volatile read instead of walking the {@link CopyOnWriteArraySet} and observer guards
     * for every epoch.
     */
    private transient volatile boolean performanceSpanFastPathEligible;

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
            invalidateInputFastPaths();
            players = event.getMultiplayerControl() & 0x03;
            if (players == 2) {
                // Undocumented MLT_REQ value 2 keeps the ICD2 in a distinct state:
                // player IDs 1 and 2 read as 2, while IDs 0 and 3 read as 0.
                currentPlayer = currentPlayer == 1 || currentPlayer == 2 ? 2 : 0;
            } else {
                currentPlayer &= players;
            }
            LOG.atDebug().log("Players: {}, current player: {}", players, currentPlayer);
            invalidateInputFastPaths();
        }, Commands.MltReqCmd.class);
        refreshReleasedInputFastPathEligibility();
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(event -> onPress(event.button()), ButtonPressEvent.class);
        eventBus.register(event -> onRelease(event.button()), ButtonReleaseEvent.class);
    }

    private void onPress(Button button) {
        invalidateInputFastPaths();
        if (eventBus != null) {
            eventBus.post(new JoypadPressEvent(button, tick));
        }
        LOG.atDebug().log("Pressed button {} at tick {}", button, tick);
        if (buttons.add(button)) {
            inputChangedSinceLastTick = true;
            notifyDebugInputChange();
            notifyLegacyInputChange();
        }
        invalidateInputFastPaths();
    }

    private void onRelease(Button button) {
        invalidateInputFastPaths();
        LOG.atDebug().log("Released button {} at tick {}", button, tick);
        if (buttons.remove(button)) {
            inputChangedSinceLastTick = true;
            notifyDebugInputChange();
            notifyLegacyInputChange();
        }
        invalidateInputFastPaths();
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

    /**
     * Installs the external-input baseline paired with a deterministic replay checkpoint.
     *
     * <p>Legacy buttons and the last physical sample are deliberately absent from portable
     * machine state. A replay target must therefore supply them separately before executing its
     * first tick. This method changes only those two external values: the checkpoint already owns
     * the JOYP filter, interrupt, and {@code inputChangedSinceLastTick} state. Synthetic alignment
     * is silent and cannot enter a debug or input timeline.</p>
     */
    public void seedDeterministicReplayInput(
            Collection<Button> legacyButtons,
            PlayerInputSnapshot sampledPhysicalInput) {
        Set<Button> legacyCopy = Set.copyOf(
                Objects.requireNonNull(legacyButtons, "legacyButtons"));
        PlayerInputSnapshot physical = Objects.requireNonNull(
                sampledPhysicalInput, "sampledPhysicalInput");
        invalidateInputFastPaths();
        buttons.clear();
        buttons.addAll(legacyCopy);
        sampledInput = physical;
        alignDebugInput();
        alignInputTimeline();
        refreshReleasedInputFastPathEligibility();
    }

    /**
     * Applies one absolute legacy-P1 transition during deterministic replay without host output.
     *
     * <p>The guest-visible change flag matches the ordinary event-driven input path, while event
     * bus, debug-hook, and timeline notifications stay silent because they belong to the already
     * recorded source timeline.</p>
     */
    public void applyDeterministicReplayLegacyInput(Collection<Button> legacyButtons) {
        Set<Button> replayButtons = Set.copyOf(
                Objects.requireNonNull(legacyButtons, "legacyButtons"));
        if (buttons.equals(replayButtons)) {
            return;
        }
        invalidateInputFastPaths();
        buttons.clear();
        buttons.addAll(replayButtons);
        inputChangedSinceLastTick = true;
        alignDebugInput();
        alignInputTimeline();
        invalidateInputFastPaths();
    }

    public void setPressedButtons(Collection<Button> pressed) {
        Set<Button> pressedCopy = Set.copyOf(pressed);
        if (buttons.equals(pressedCopy)) {
            return;
        }
        invalidateInputFastPaths();
        buttons.clear();
        buttons.addAll(pressedCopy);
        inputChangedSinceLastTick = true;
        notifyDebugInputChange();
        notifyLegacyInputChange();
        invalidateInputFastPaths();
    }

    public void tick() {
        tick++;
        if (releasedInputFastPathEligible) {
            return;
        }
        if (playerInputHubFastPathEligible && !isPlayerInputHubPollDue()) {
            return;
        }
        tickInputSlowPath();
    }

    /**
     * Returns the largest exact PERFORMANCE span for a settled released-input JOYP receiver.
     *
     * <p>The released default source cannot produce a poll or a physical transition, and a
     * settled four-sample receiver has no electrical edge to process.  Only the free-running
     * BOGA clock phase changes, so it can be advanced arithmetically.  Custom sources, SGB packet
     * receivers, debug/timeline observers, and any pending input mutation stay on the scalar
     * path.</p>
     */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0 || inputChangedSinceLastTick
                || !performanceSpanFastPathEligible) {
            return 0;
        }
        if (releasedInputFastPathEligible && playerInputSource == PlayerInputSource.RELEASED) {
            return Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN);
        }
        if (playerInputHubFastPathEligible && playerInputSource instanceof PlayerInputHub) {
            // The hub is sampled only on the post-increment residue 1.  The snapshot may change
            // between polls, but that change is intentionally invisible until the next poll, so
            // a span may advance only to the tick immediately before that residue.
            long residue = tick & (PLAYER_INPUT_HUB_POLL_TICKS - 1L);
            long distance = (1L - residue) & (PLAYER_INPUT_HUB_POLL_TICKS - 1L);
            if (distance == 0) {
                distance = PLAYER_INPUT_HUB_POLL_TICKS;
            }
            return Math.min(Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN),
                    (int) Math.max(0, distance - 1));
        }
        return 0;
    }

    /**
     * Same settled released-input/PlayerInputHub horizon for a HALT packet, without the normal
     * three-tick scheduler cap.  A hub poll remains the hard endpoint; a host update is sampled
     * only by that poll, preserving the scalar visibility contract.
     */
    public int performanceSettledHaltSpanLimit(int requested) {
        if (requested <= 0 || inputChangedSinceLastTick
                || !performanceSpanFastPathEligible) {
            return 0;
        }
        if (releasedInputFastPathEligible && playerInputSource == PlayerInputSource.RELEASED) {
            return requested;
        }
        if (playerInputHubFastPathEligible && playerInputSource instanceof PlayerInputHub) {
            long residue = tick & (PLAYER_INPUT_HUB_POLL_TICKS - 1L);
            long distance = (1L - residue) & (PLAYER_INPUT_HUB_POLL_TICKS - 1L);
            if (distance == 0) {
                distance = PLAYER_INPUT_HUB_POLL_TICKS;
            }
            return (int) Math.min((long) requested, Math.max(0, distance - 1));
        }
        return 0;
    }

    /** Returns the largest safe span using the scheduler's normal three-clock bound. */
    public int performanceQuietSpanLimit() {
        return performanceQuietSpanLimit(PERFORMANCE_MAX_QUIET_SPAN);
    }

    /** True when the requested span can be applied by {@link #tickPerformanceQuietSpan(int)}. */
    public boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    /**
     * Advances a settled released-input JOYP receiver without polling or filtering each tick.
     *
     * @return false without mutation when a host input, observer, SGB, or debug edge could make
     *         a scalar callback observable
     */
    public boolean tickPerformanceQuietSpan(int ticks) {
        if (!canTickPerformanceQuietSpan(ticks)) {
            return false;
        }
        tick += ticks;
        return true;
    }

    /** Applies a span after the caller has already passed {@link #canTickPerformanceQuietSpan(int)}. */
    public void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        // Gameboy has already preflighted the full packet and performs one final volatile
        // eligibility read immediately before beginning the trusted packet commit.
        tick += ticks;
    }

    /** One cheap owner-thread commit guard for the packet scheduler. */
    public boolean isPerformanceQuietSpanStillEligible() {
        return !inputChangedSinceLastTick && performanceSpanFastPathEligible;
    }

    /** Naming alias for schedulers which use the GPU's advance-oriented bulk vocabulary. */
    public boolean advancePerformanceQuietSpan(int ticks) {
        return tickPerformanceQuietSpan(ticks);
    }

    /** Trusted naming alias for schedulers which use the GPU's advance-oriented vocabulary. */
    public void advancePerformanceQuietSpanTrusted(int ticks) {
        tickPerformanceQuietSpanTrusted(ticks);
    }

    private void tickInputSlowPath() {
        PlayerInputSnapshot nextInput = sampleInputForTick();
        if (sampledInput != nextInput) {
            boolean physicalButtonsChanged =
                    sampledInput.packedButtonMasks != nextInput.packedButtonMasks;
            sampledInput = nextInput;
            if (physicalButtonsChanged) {
                inputChangedSinceLastTick = true;
                notifyDebugInputChange();
                notifyPhysicalInputChanges();
            }
        }
        // JOYP writes happen after the joypad clock edge represented by this emulator
        // tick. Start sampling a changed input on the following tick. BATU, ACEF, AGEM,
        // and APUG are all clocked by BOGA's 1 MHz output, rather than by every master tick.
        if (inputChangedSinceLastTick) {
            inputChangedSinceLastTick = false;
            refreshReleasedInputFastPathEligibility();
            return;
        }
        int inputLines;
        if (players > 0 && (p1 & 0x30) == 0x30) {
            LOG.atDebug().log("Returning player {} as current player", currentPlayer);
            inputLines = 0x0f - currentPlayer;
        } else {
            int pressedButtonMask = (sampledInput.packedButtonMasks >>> (currentPlayer * Byte.SIZE))
                    & JoypadButtonMask.ALL;
            int pressedInputLines = 0;
            if ((p1 & 0x10) == 0) {
                pressedInputLines |= pressedButtonMask & 0x0f;
            }
            if ((p1 & 0x20) == 0) {
                pressedInputLines |= (pressedButtonMask >>> 4) & 0x0f;
            }
            inputLines = 0x0f & ~pressedInputLines & 0x0f;
            // The historical event-driven API remains P1-only for controller/netplay replay.
            if (currentPlayer == 0 && !buttons.isEmpty()) {
                inputLines = applyButtons(inputLines, buttons);
            }
        }
        if (!isJoypadClockRising()) {
            refreshReleasedInputFastPathEligibility();
            refreshPlayerInputHubFastPathEligibility(inputLines);
            return;
        }
        if (inputHistory == SETTLED_HISTORY[inputLines]
                && filteredInputLines == inputLines) {
            refreshReleasedInputFastPathEligibility();
            refreshPlayerInputHubFastPathEligibility(inputLines);
            return;
        }
        boolean oldInterruptLine = joypadInterruptLine(inputHistory);
        int nextFilteredInputLines = filteredInputLines;
        inputHistory = sampleInputHistory(inputHistory, inputLines);
        for (int line = 0; line < 4; line++) {
            int shift = line * INPUT_FILTER_SAMPLES;
            int history = (inputHistory >>> shift) & INPUT_FILTER_MASK;
            if (history == INPUT_FILTER_MASK) {
                nextFilteredInputLines &= ~(1 << line);
            } else if (history == 0) {
                nextFilteredInputLines |= 1 << line;
            }
        }
        filteredInputLines = nextFilteredInputLines;
        // KERY ORs all four active-low pad inputs before the four-stage receiver. ASOK is
        // BATU & APUG, so pad identity is gone before interrupt filtering and the middle
        // two samples are not part of its Boolean equation. ORing the four packed per-line
        // histories reconstructs that aggregate receiver without changing portable state.
        boolean interruptLine = joypadInterruptLine(inputHistory);
        if (!oldInterruptLine && interruptLine) {
            interruptManager.requestInterrupt(InterruptManager.InterruptType.P10_13);
        }
        refreshReleasedInputFastPathEligibility();
        refreshPlayerInputHubFastPathEligibility(inputLines);
    }

    private boolean isJoypadClockRising() {
        // BOGA/CLK_1MHz is anchored to the fixed master-clock/reset grid. Do not derive this
        // phase from Cpu.clockCycle: interrupt-entry and CGB clock-mux compensation can rephase
        // Coffee's semantic CPU boundary without moving the physical JOYP receiver clock.
        return (tick & (JOYP_CLOCK_TICKS - 1L)) == 0;
    }

    static int sampleInputHistory(int histories, int inputLines) {
        int sampled = histories;
        for (int line = 0; line < 4; line++) {
            int shift = line * INPUT_FILTER_SAMPLES;
            int history = (histories >>> shift) & INPUT_FILTER_MASK;
            boolean inputLow = (inputLines & (1 << line)) == 0;
            history = ((history << 1) | (inputLow ? 1 : 0)) & INPUT_FILTER_MASK;
            sampled = (sampled & ~(INPUT_FILTER_MASK << shift)) | (history << shift);
        }
        return sampled;
    }

    static int aggregateInputHistory(int histories) {
        int aggregateHistory = histories
                | histories >>> INPUT_FILTER_SAMPLES
                | histories >>> (2 * INPUT_FILTER_SAMPLES)
                | histories >>> (3 * INPUT_FILTER_SAMPLES);
        return aggregateHistory & INPUT_FILTER_MASK;
    }

    static boolean joypadInterruptLine(int histories) {
        int aggregateHistory = aggregateInputHistory(histories);
        // Bit 0 is BATU's current KERY sample; bit 3 is APUG's three-edge-old sample.
        return (aggregateHistory & 0b1001) == 0b1001;
    }

    private PlayerInputSnapshot sampleInputForTick() {
        if (playerInputSource == PlayerInputSource.RELEASED) {
            return PlayerInputSnapshot.RELEASED;
        }
        if (playerInputSource instanceof PlayerInputHub && !isPlayerInputHubPollDue()) {
            return sampledInput;
        }
        return Objects.requireNonNull(playerInputSource.sample(), "PlayerInputSource returned null");
    }

    private boolean isPlayerInputHubPollDue() {
        // tick is incremented before sampling. Poll the first clock edge and then every 64th
        // edge, preserving that phase through portable state capture/restore without extra state.
        return (tick & (PLAYER_INPUT_HUB_POLL_TICKS - 1L)) == 1;
    }

    private void invalidateInputFastPaths() {
        releasedInputFastPathEligible = false;
        playerInputHubFastPathEligible = false;
        performanceSpanFastPathEligible = false;
    }

    private void refreshReleasedInputFastPathEligibility() {
        releasedInputFastPathEligible = playerInputSource == PlayerInputSource.RELEASED
                && sampledInput == PlayerInputSnapshot.RELEASED
                && players == 0
                && !inputChangedSinceLastTick
                && buttons.isEmpty()
                && inputHistory == SETTLED_HISTORY[0x0f]
                && filteredInputLines == 0x0f;
        performanceSpanFastPathEligible = releasedInputFastPathEligible
                && inputTimelineObserver == null
                && debugHooks == null
                && !isSgb;
        // Hub eligibility additionally depends on the current selector and filtered electrical
        // lines. It is recomputed only after this tick has calculated those values.
        playerInputHubFastPathEligible = false;
    }

    private void refreshPlayerInputHubFastPathEligibility(int inputLines) {
        playerInputHubFastPathEligible = playerInputSource instanceof PlayerInputHub
                && !inputChangedSinceLastTick
                && inputHistory == SETTLED_HISTORY[inputLines]
                && filteredInputLines == inputLines;
        performanceSpanFastPathEligible = performanceSpanFastPathEligible
                || playerInputHubFastPathEligible
                && players == 0
                && buttons.isEmpty()
                && inputTimelineObserver == null
                && debugHooks == null
                && !isSgb;
    }

    private static int[] createSettledHistory() {
        int[] settledHistory = new int[1 << 4];
        for (int inputLines = 0; inputLines < settledHistory.length; inputLines++) {
            int history = 0;
            for (int line = 0; line < 4; line++) {
                if ((inputLines & (1 << line)) == 0) {
                    history |= INPUT_FILTER_MASK << (line * INPUT_FILTER_SAMPLES);
                }
            }
            settledHistory[inputLines] = history;
        }
        return settledHistory;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff00;
    }

    @Override
    public void setByte(int address, int value) {
        int previousSelection = p1;
        int nextSelection = value & 0x30;
        if (nextSelection != previousSelection) {
            invalidateInputFastPaths();
        }
        if (isSgb) {
            int input = nextSelection;
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
        p1 = nextSelection;
        if (p1 != previousSelection) {
            inputChangedSinceLastTick = true;
            invalidateInputFastPaths();
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

    /** Captures current JOYP and ICD2 state without entering the address-space read path. */
    public DebugHardwareInspection.Joypad captureDebugJoypadInspection(
            boolean superGameBoyAvailable) {
        int joyp = p1 | 0b11000000 | getInputLines();
        if (!superGameBoyAvailable) {
            return new DebugHardwareInspection.Joypad(
                    joyp, getDebugButtonMask(), filteredInputLines,
                    false, 0, -1, false, -1);
        }
        SgbMultiplayerStatus multiplayer = getSgbMultiplayerStatus();
        return new DebugHardwareInspection.Joypad(
                joyp, getDebugButtonMask(), filteredInputLines,
                true, multiplayer.playerCount(), multiplayer.selectedPlayer(),
                transferInProgress, currentPacketIndex);
    }

    private int getInputLines() {
        if (players > 0 && (p1 & 0x30) == 0x30) {
            LOG.atDebug().log("Returning player {} as current player", currentPlayer);
            return 0x0f - currentPlayer;
        }

        int pressedButtonMask = (sampledInput.packedButtonMasks >>> (currentPlayer * Byte.SIZE))
                & JoypadButtonMask.ALL;
        int pressedInputLines = 0;
        if ((p1 & 0x10) == 0) {
            pressedInputLines |= pressedButtonMask & 0x0f;
        }
        if ((p1 & 0x20) == 0) {
            pressedInputLines |= (pressedButtonMask >>> 4) & 0x0f;
        }
        int result = 0x0f & ~pressedInputLines & 0x0f;
        // The historical event-driven API remains P1-only for controller/netplay replay.
        if (currentPlayer == 0 && !buttons.isEmpty()) {
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
        invalidateInputFastPaths();
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
        refreshReleasedInputFastPathEligibility();
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
        invalidateInputFastPaths();
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
        invalidateInputFastPaths();
        alignInputTimeline();
        inputTimelineObserver = null;
        return true;
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        invalidateInputFastPaths();
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
