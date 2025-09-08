package eu.rekawek.coffeegb.joypad;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.BitUtils;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.sgb.Commands;
import eu.rekawek.coffeegb.sgb.SuperGameboy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Joypad implements AddressSpace, Serializable, Originator<Joypad> {

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);
    private final Set<Button> buttons = new CopyOnWriteArraySet<>();
    private final InterruptManager interruptManager;
    private final boolean isSgb;
    private final EventBus sgbBus;

    private int p1;
    private long tick;
    private EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private int players;
    private int currentPlayer;

    private boolean transferInProgress;
    private int currentByte;
    private final int[] currentPacket = new int[16];
    private int currentByteIndex;
    private int currentPacketIndex;

    public Joypad(InterruptManager interruptManager, EventBus sgbBus, boolean isSgb) {
        this.interruptManager = interruptManager;
        this.isSgb = isSgb;
        this.sgbBus = sgbBus;
        sgbBus.register(event -> {
            players = event.getMultiplayerControl();
            currentPlayer = currentPlayer & players;
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
        interruptManager.requestInterrupt(InterruptManager.InterruptType.P10_13);
        buttons.add(button);
    }

    private void onRelease(Button button) {
        LOG.atDebug().log("Released button {} at tick {}", button, tick);
        buttons.remove(button);
    }

    public void tick() {
        tick++;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff00;
    }

    @Override
    public void setByte(int address, int value) {
        if (isSgb) {
            boolean bit4 = BitUtils.getBit(value, 4);
            boolean bit5 = BitUtils.getBit(value, 5);

            if (!bit4 && !bit5) {
                transferInProgress = true;
                currentByte = 0;
                currentByteIndex = 0;
                currentPacketIndex = 0;
                Arrays.fill(currentPacket, 0);
            } else if (transferInProgress && (!bit4 || !bit5)) {
                if (!bit5) {
                    currentByte |= (1 << currentByteIndex);
                }
                currentByteIndex++;
                if (currentByteIndex == 8) {
                    if (currentPacketIndex < 16) {
                        currentPacket[currentPacketIndex++] = currentByte;
                        if (currentPacketIndex == 16) {
                            transferInProgress = false;
                            sgbBus.post(new SuperGameboy.PacketReceivedEvent(currentPacket.clone()));
                        }
                    }
                    currentByteIndex = 0;
                    currentByte = 0;
                }
            }
        }
        if (players > 0 && !BitUtils.getBit(p1, 5) && BitUtils.getBit(value, 5)) {
            currentPlayer++;
            if (currentPlayer > players) {
                currentPlayer = 0;
            }
            LOG.atDebug().log("Player changed to {}", currentPlayer);
        }
        p1 = value & 0b00110000;
    }

    @Override
    public int getByte(int address) {
        if (players > 0 && (p1 & 0x30) == 0x30) {
            LOG.atDebug().log("Returning player {} as current player", currentPlayer);
            return 0xf0 | (0x0f - currentPlayer);
        }

        int result = p1 | 0b11001111;
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
        return new JoypadMemento(new HashSet<>(buttons), p1, tick, players, currentPlayer, transferInProgress, currentByte, currentPacket, currentByteIndex, currentPacketIndex);
    }

    @Override
    public void restoreFromMemento(Memento<Joypad> memento) {
        if (!(memento instanceof JoypadMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.buttons.clear();
        this.buttons.addAll(mem.buttons);
        this.p1 = mem.p1;
        this.tick = mem.tick;
        this.players = mem.players;
        this.currentPlayer = mem.currentPlayer;
        this.transferInProgress = mem.transferInProgress;
        this.currentByte = mem.currentByte;
        if (mem.currentPacket.length != this.currentPacket.length) {
            throw new IllegalArgumentException("Invalid memento length");
        }
        System.arraycopy(mem.currentPacket, 0, this.currentPacket, 0, mem.currentPacket.length);
        this.currentByteIndex = mem.currentByteIndex;
        this.currentPacketIndex = mem.currentPacketIndex;

    }

    private record JoypadMemento(Set<Button> buttons, int p1, long tick, int players, int currentPlayer, boolean transferInProgress, int currentByte, int[] currentPacket, int currentByteIndex, int currentPacketIndex) implements Memento<Joypad> {
    }

    public record JoypadPressEvent(Button button, long tick) implements Event {
    }
}
