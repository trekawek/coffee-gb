package eu.rekawek.coffeegb.core.rumble;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.Mmu;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CodeBreakerRumbleTest {

    private final List<Boolean> motorLog = new ArrayList<>();

    private final CodeBreakerRumble rumble = new CodeBreakerRumble();

    private final Mmu mmu = new Mmu(true);

    @Before
    public void setUp() {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        eventBus.register(event -> motorLog.add(event.on()), RumbleEvent.class);
        rumble.init(eventBus);
        mmu.setCodeBreakerRumble(rumble);
        mmu.indexSpaces();
    }

    @Test
    public void bit7OfFffeControlsMotorAndValueRemainsInHram() {
        mmu.setByte(0xfffe, 0x86);
        mmu.setByte(0xfffe, 0x85);
        mmu.setByte(0xfffe, 0x06);

        assertEquals(0x06, mmu.getByte(0xfffe));
        assertEquals(List.of(true, false), motorLog);
    }

    @Test
    public void otherHramWritesDoNotControlMotor() {
        mmu.setByte(0xfffd, 0x80);

        assertEquals(List.of(), motorLog);
    }

    @Test
    public void mementoRestoresMotorState() {
        mmu.setByte(0xfffe, 0x80);
        Memento<CodeBreakerRumble> motorOn = rumble.saveToMemento();
        mmu.setByte(0xfffe, 0x00);

        rumble.restoreFromMemento(motorOn);

        assertEquals(List.of(true, false, true), motorLog);
    }

    @Test
    public void closingAccessoryStopsMotor() {
        mmu.setByte(0xfffe, 0x80);

        rumble.close();

        assertEquals(List.of(true, false), motorLog);
    }
}
