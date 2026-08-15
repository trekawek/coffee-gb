package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.P10_13;
import static org.junit.Assert.assertEquals;
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
        for (int i = 0; i < 4 * Joypad.JOYP_CLOCK_TICKS - 1; i++) {
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
    public void pressingASecondInputLineDoesNotRetriggerTheAggregateInterrupt() {
        joypad.setByte(0xff00, 0x10);
        eventBus.post(new ButtonPressEvent(Button.A));
        tickThroughInputFilter();
        interrupts.setByte(0xff0f, 0);

        eventBus.post(new ButtonPressEvent(Button.B));
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void aggregateEndpointGateDoesNotRequireFourConsecutiveLowSamples() {
        joypad.setByte(0xff00, 0x10);
        eventBus.post(new ButtonPressEvent(Button.A));
        tickOneJoypadClock(); // BATU samples one low aggregate level.

        eventBus.post(new ButtonReleaseEvent(Button.A));
        tickOneJoypadClock();
        tickOneJoypadClock();

        eventBus.post(new ButtonPressEvent(Button.B));
        tickOneJoypadClock(); // APUG has the first low while BATU samples the fourth.

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void packedPerLineStateReconstructsEveryAggregateReceiverTransition() {
        for (int histories = 0; histories <= 0xffff; histories++) {
            int expectedAggregate = 0;
            for (int line = 0; line < 4; line++) {
                expectedAggregate |= histories >>> (line * 4) & 0x0f;
            }
            assertEquals(expectedAggregate, Joypad.aggregateInputHistory(histories));

            for (int inputLines = 0; inputLines < 0x10; inputLines++) {
                int nextHistories = Joypad.sampleInputHistory(histories, inputLines);
                int nextAggregate = (expectedAggregate << 1
                        | (inputLines == 0x0f ? 0 : 1)) & 0x0f;
                assertEquals(nextAggregate, Joypad.aggregateInputHistory(nextHistories));
                assertEquals((nextAggregate & 0b1001) == 0b1001,
                        Joypad.joypadInterruptLine(nextHistories));
            }
        }
    }

    @Test
    public void shortSelectorPulseIsRejectedByInputFilter() {
        joypad.setByte(0xff00, 0x30);
        joypad.setPressedButtons(Collections.singleton(Button.A));

        joypad.setByte(0xff00, 0x10);
        // Three low samples do not reach APUG. The fourth 1 MHz edge sees the row released.
        for (int tick = 0; tick < 3 * Joypad.JOYP_CLOCK_TICKS; tick++) {
            joypad.tick();
        }
        joypad.setByte(0xff00, 0x30);
        tickThroughInputFilter();

        assertFalse(interrupts.isInterruptFlagSet(P10_13));
    }

    @Test
    public void mementoRestoresPartialInputFilterPipeline() {
        joypad.setByte(0xff00, 0x30);
        joypad.setPressedButtons(Collections.singleton(Button.A));
        joypad.setByte(0xff00, 0x10);
        for (int tick = 0; tick < 2 * Joypad.JOYP_CLOCK_TICKS; tick++) {
            joypad.tick();
        }
        var memento = joypad.captureState();

        for (int tick = 0; tick < 2 * Joypad.JOYP_CLOCK_TICKS; tick++) {
            joypad.tick();
        }
        assertTrue(interrupts.isInterruptFlagSet(P10_13));
        interrupts.setByte(0xff0f, 0);

        joypad.restoreState(memento);
        for (int tick = 0; tick < 2 * Joypad.JOYP_CLOCK_TICKS; tick++) {
            joypad.tick();
        }

        assertTrue(interrupts.isInterruptFlagSet(P10_13));
    }

    private void tickThroughInputFilter() {
        for (int i = 0; i < 4 * Joypad.JOYP_CLOCK_TICKS; i++) {
            joypad.tick();
        }
    }

    private void tickOneJoypadClock() {
        for (int tick = 0; tick < Joypad.JOYP_CLOCK_TICKS; tick++) {
            joypad.tick();
        }
    }
}
