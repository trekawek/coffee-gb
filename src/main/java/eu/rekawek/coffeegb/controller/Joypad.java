package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Joypad implements AddressSpace, Serializable, Originator<Joypad> {

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);
    private final Set<Button> buttons = new CopyOnWriteArraySet<>();
    private final InterruptManager interruptManager;
    private int p1;
    private long tick;
    private EventBus eventBus;

    public Joypad(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
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
        interruptManager.requestInterrupt(InterruptManager.InterruptType.P10_13);
        buttons.add(button);
    }

    private void onRelease(Button button) {
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
        p1 = value & 0b00110000;
    }

    @Override
    public int getByte(int address) {
        int result = p1 | 0b11001111;
        for (Button b : buttons) {
            if ((b.getLine() & p1) == 0) {
                result &= 0xff & ~b.getMask();
            }
        }
        return result;
    }

    @Override
    public Memento<Joypad> saveToMemento() {
        return new JoypadMemento(new HashSet<>(buttons), p1, tick);
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
    }

    private record JoypadMemento(Set<Button> buttons, int p1, long tick) implements Memento<Joypad> {
    }

    public record JoypadPressEvent(Button button, long tick) implements Event {
    }
}
