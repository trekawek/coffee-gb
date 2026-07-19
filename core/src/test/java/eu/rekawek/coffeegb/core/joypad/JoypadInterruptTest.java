package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.P10_13;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JoypadInterruptTest {

    private InterruptManager interrupts;

    private EventBusImpl eventBus;

    private Joypad joypad;

    @Before
    public void setUp() {
        interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        eventBus = new EventBusImpl(null, null, false);
        joypad = new Joypad(interrupts, EventBus.NULL_EVENT_BUS, false);
        joypad.init(eventBus);
    }

    @Test
    public void pressingASelectedButtonRequestsInterrupt() {
        joypad.setByte(0xff00, 0x10);

        eventBus.post(new ButtonPressEvent(Button.A));
        for (int i = 0; i < 4; i++) {
            joypad.tick();
        }
        assertFalse(interrupts.isInterruptFlagSet(P10_13));
        joypad.tick();

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void pressingAButtonInDeselectedRowDoesNotRequestInterrupt() {
        joypad.setByte(0xff00, 0x20);

        eventBus.post(new ButtonPressEvent(Button.A));
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void selectingRowWithHeldButtonRequestsInterrupt() {
        joypad.setByte(0xff00, 0x30);
        joypad.setPressedButtons(Collections.singleton(Button.A));

        joypad.setByte(0xff00, 0x10);
        tickThroughInputFilter();

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void switchingBetweenRowsOnlyRequestsInterruptForHeldSelectedRow() {
        joypad.setByte(0xff00, 0x20);
        joypad.setPressedButtons(Collections.singleton(Button.A));
        interrupts.setByte(0xff0f, 0);

        joypad.setByte(0xff00, 0x10);
        tickThroughInputFilter();

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void deselectingOrReleasingAButtonDoesNotRequestInterrupt() {
        joypad.setByte(0xff00, 0x10);
        eventBus.post(new ButtonPressEvent(Button.A));
        interrupts.setByte(0xff0f, 0);

        joypad.setByte(0xff00, 0x30);
        eventBus.post(new ButtonReleaseEvent(Button.A));
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void duplicatePressHasNoSecondFallingEdge() {
        joypad.setByte(0xff00, 0x10);
        eventBus.post(new ButtonPressEvent(Button.A));
        tickThroughInputFilter();
        interrupts.setByte(0xff0f, 0);

        eventBus.post(new ButtonPressEvent(Button.A));
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void pressingASecondInputLineRequestsAnotherInterrupt() {
        joypad.setByte(0xff00, 0x10);
        eventBus.post(new ButtonPressEvent(Button.A));
        tickThroughInputFilter();
        interrupts.setByte(0xff0f, 0);

        eventBus.post(new ButtonPressEvent(Button.B));
        tickThroughInputFilter();

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void shortSelectorPulseIsRejectedByInputFilter() {
        joypad.setByte(0xff00, 0x30);
        joypad.setPressedButtons(Collections.singleton(Button.A));

        joypad.setByte(0xff00, 0x10);
        joypad.tick();
        joypad.tick();
        joypad.setByte(0xff00, 0x30);
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void mementoRestoresPartialInputFilterPipeline() {
        joypad.setByte(0xff00, 0x30);
        joypad.setPressedButtons(Collections.singleton(Button.A));
        joypad.setByte(0xff00, 0x10);
        joypad.tick(); // skip the clock edge on which JOYP changed
        joypad.tick();
        joypad.tick();
        var memento = joypad.saveToMemento();

        joypad.tick();
        joypad.tick();
        assertTrue(interrupts.isInterruptFlagSet(P10_13));
        interrupts.setByte(0xff0f, 0);

        joypad.restoreFromMemento(memento);
        joypad.tick();
        joypad.tick();

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    private void tickThroughInputFilter() {
        for (int i = 0; i < 5; i++) {
            joypad.tick();
        }
    }
}
