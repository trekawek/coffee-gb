package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Joypad implements AddressSpace, Serializable, Originator<Joypad> {

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);

    /** Number of JOYP clock samples used by the hardware's input glitch filter. */
    private static final int INPUT_FILTER_SAMPLES = 4;

    private static final int INPUT_FILTER_MASK = (1 << INPUT_FILTER_SAMPLES) - 1;

    private final Set<Button> buttons = new CopyOnWriteArraySet<>();
    private final InterruptManager interruptManager;
    private final boolean isSgb;
    private final EventBus sgbBus;

    private int p1;
    private long tick;
    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private int inputHistory;

    /** Filtered electrical level of the four shared P10-P13 input lines. */
    private int filteredInputLines = 0x0f;

    private volatile boolean inputChangedSinceLastTick;

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
        this.interruptManager = interruptManager;
        this.isSgb = isSgb;
        this.sgbBus = sgbBus;
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
        }
    }

    private void onRelease(Button button) {
        LOG.atDebug().log("Released button {} at tick {}", button, tick);
        if (buttons.remove(button)) {
            inputChangedSinceLastTick = true;
        }
    }

    /**
     * The set of currently-held buttons. Intentionally not part of the memento (see
     * {@link #saveToMemento()}); rollback netplay snapshots and restores it separately so a
     * held button survives a rebase whose base frame is past the original press.
     */
    public Set<Button> getPressedButtons() {
        return new java.util.HashSet<>(buttons);
    }

    public void setPressedButtons(java.util.Collection<Button> pressed) {
        if (buttons.equals(Set.copyOf(pressed))) {
            return;
        }
        buttons.clear();
        buttons.addAll(pressed);
        inputChangedSinceLastTick = true;
    }

    public void tick() {
        tick++;
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
            transferInProgress = true;
            transferReadyForData = false;
            pendingTransferBit = -1;
            currentByte = 0;
            currentByteIndex = 0;
            currentPacketIndex = 0;
            Arrays.fill(currentPacket, 0);
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

        int result = 0x0f;
        if (currentPlayer > 0) {
            // Only support controller for player 0
            return result;
        }

        for (Button b : buttons) {
            if ((b.getLine() & p1) == 0) {
                result &= 0xff & ~b.getMask();
            }
        }
        return result;
    }

    @Override
    public Memento<Joypad> saveToMemento() {
        // the pressed-buttons set is live physical input, not machine state - it is
        // deliberately left out of the memento so that restoring a state (rewind, save
        // slot) keeps whatever the player is physically holding right now. Otherwise a
        // button held in a rewound-past frame would be re-applied and, with no matching
        // release event ever arriving, stick when forward emulation resumes (issue: rewind
        // replays past button presses).
        return new JoypadMemento(p1, tick, inputHistory, filteredInputLines,
                inputChangedSinceLastTick, players, currentPlayer, transferInProgress,
                transferReadyForData, pendingTransferBit, currentByte, currentPacket.clone(),
                currentByteIndex, currentPacketIndex);
    }

    @Override
    public void restoreFromMemento(Memento<Joypad> memento) {
        if (!(memento instanceof JoypadMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
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
        if (mem.currentPacket.length != this.currentPacket.length) {
            throw new IllegalArgumentException("Invalid memento length");
        }
        System.arraycopy(mem.currentPacket, 0, this.currentPacket, 0, mem.currentPacket.length);
        this.currentByteIndex = mem.currentByteIndex;
        this.currentPacketIndex = mem.currentPacketIndex;

    }

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
