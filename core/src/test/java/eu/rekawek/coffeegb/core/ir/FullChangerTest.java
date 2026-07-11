package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The CGB IR port and the Full Changer (issue #94). The decoding test replicates the
 * measurement loop Zok Zok Heroes runs: it busy-polls RP (0xFF56) and counts loop
 * iterations for each ON+OFF light pulse, building 18 delay bytes that carry a sync
 * pulse, a checksum byte and the complemented Cosmic Character ID (LSB-first; short
 * pulse = 0 bit, long pulse = 1 bit).
 */
public class FullChangerTest {

    // one iteration of the game's polling loop costs ~20 double-speed cycles - the
    // constant GBE+'s calibrated pulse timings are built around
    private static final int LOOP_CYCLES = 20;

    private final SpeedMode speedMode = new SpeedMode(true);

    private final InfraredPort port = new InfraredPort(true, speedMode);

    private int readRp() {
        int value = port.getByte(0xff56);
        for (int i = 0; i < LOOP_CYCLES; i++) {
            port.tick();
        }
        return value;
    }

    /** Replicates the game's capture: waits for light, then measures 18 ON+OFF delays. */
    private int[] captureDelays() {
        int timeout = 0xffff;
        while ((readRp() & 0x02) != 0) {
            if (--timeout == 0) {
                throw new AssertionError("IR light never came on");
            }
        }
        int[] delays = new int[18];
        for (int pulse = 0; pulse < 18; pulse++) {
            int length = 0;
            while ((readRp() & 0x02) == 0) { // light on
                length++;
                assertTrue("ON pulse too long", length < 0x100);
            }
            while ((readRp() & 0x02) != 0) { // light off
                length++;
                if (pulse == 17 && length > 0x30) {
                    break; // transmission over; the final off period just ends
                }
                assertTrue("OFF pulse too long", length < 0x100);
            }
            delays[pulse] = length;
        }
        return delays;
    }

    private int decodeByte(int[] delays, int offset) {
        int value = 0;
        for (int bit = 0; bit < 8; bit++) {
            int delay = delays[offset + bit];
            assertTrue("delay out of range: " + delay, delay <= 0x20);
            if (delay >= 0x14) {
                value |= 1 << bit;
            }
        }
        return value;
    }

    private void transformAndVerify(int id) {
        port.setByte(0xff56, 0xc0); // read enable
        new FullChangerTrigger(port).transform(id);
        int[] delays = captureDelays();
        assertTrue("sync pulse must be long, got " + delays[0], delays[0] > 0x20);
        int checksum = decodeByte(delays, 1);
        int data = decodeByte(delays, 9);
        assertEquals("checksum", 0xff, (checksum + data) & 0xff);
        assertEquals("character ID", id, (~data) & 0xff);
    }

    // posts the event the same way the UI does
    private record FullChangerTrigger(InfraredPort port) {
        void transform(int id) {
            EventBusImpl bus = new EventBusImpl(null, null, false);
            port.init(bus);
            bus.post(new FullChanger.TransformEvent(id));
        }
    }

    @Test
    public void decodesFirstCharacter() {
        transformAndVerify(0x01);
    }

    @Test
    public void decodesLastCharacter() {
        transformAndVerify(FullChanger.CHARACTERS);
    }

    @Test
    public void decodesAllCharacters() {
        for (int id = 1; id <= FullChanger.CHARACTERS; id++) {
            transformAndVerify(id);
        }
    }

    @Test
    public void sensorGatedByReadEnable() {
        new FullChangerTrigger(port).transform(1);
        port.setByte(0xff56, 0x00); // read disabled: bit 1 always 1
        port.getByte(0xff56); // arms + starts the transmission
        boolean sawLight = false;
        for (int i = 0; i < 10000; i++) {
            port.tick();
            if ((port.getByte(0xff56) & 0x02) == 0) {
                sawLight = true;
            }
        }
        assertEquals(false, sawLight);
    }

    @Test
    public void writtenBitsReadBack() {
        port.setByte(0xff56, 0xc1);
        assertEquals(0xff, port.getByte(0xff56)); // 0x3c pulled + bit1 (no light) + written c1
        port.setByte(0xff56, 0x00);
        assertEquals(0x3e, port.getByte(0xff56));
    }

    @Test
    public void mementoRoundTrip() {
        port.setByte(0xff56, 0xc0);
        new FullChangerTrigger(port).transform(5);
        port.getByte(0xff56); // start transmitting
        for (int i = 0; i < 30; i++) {
            port.tick();
        }
        Memento<InfraredPort> memento = port.saveToMemento();

        InfraredPort other = new InfraredPort(true, speedMode);
        other.restoreFromMemento(memento);
        // both ports continue the same pulse train in lockstep
        for (int i = 0; i < 5000; i++) {
            assertEquals(port.getByte(0xff56), other.getByte(0xff56));
            port.tick();
            other.tick();
        }
    }
}
