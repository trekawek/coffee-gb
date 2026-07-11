package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

/**
 * The Full Changer, the IR toy bundled with Zok Zok Heroes (issue #94). The player
 * "draws" a Cosmic Character by waving it, then holds it to the GBC's IR port; the toy
 * transmits the character as a series of light pulses and the game transforms the hero.
 *
 * <p>Protocol (reverse-engineered by Shonumi for GBE+): 18 ON/OFF pulse pairs. The game
 * busy-polls RP and counts loop iterations for each ON+OFF pair as one delay byte. The
 * first pulse is a long sync (delay &gt; 0x20). The next 16 pulses carry two bytes,
 * LSB-first: a short pulse (&le; 0x13) is a 0 bit, a long pulse (0x14-0x20) a 1 bit.
 * Byte 1 is the character ID, byte 2 its complement (their sum must be 0xFF); the 18th
 * pulse is required but unused. Pulse durations below are GBE+'s calibrated cycle counts
 * (in double-speed cycles): the first ON pulse takes 74 + 20*(len-2), later ON pulses
 * 78 + 20*(len-2), OFF pulses 38 + 20*(len-2), where the ON and OFF lens of a pair sum
 * to the delay byte the game should measure.
 *
 * <p>There are 70 Cosmic Characters, IDs 0x01-0x46.
 */
public class FullChanger implements Serializable, Originator<FullChanger> {

    /** The player finished drawing a Cosmic Character and pointed the toy at the IR port. */
    public record TransformEvent(int characterId) implements Event {
    }

    public static final int CHARACTERS = 70;

    // total delay bytes the game should measure: a long sync, data bits and the final pulse
    private static final int SYNC_DELAY = 0x2e;

    private static final int LONG_DELAY = 0x1a; // a 1 bit

    private static final int SHORT_DELAY = 0x0a; // a 0 bit

    // alternating ON/OFF durations in double-speed cycles; empty = nothing to send
    private int[] schedule = new int[0];

    // a transformation waits for the game to poll RP before the light fires, so the
    // measuring loop sees the first pulse from its start
    private boolean armed;

    private boolean running;

    private int index;

    private int remaining;

    /** Queues the transmission of the given Cosmic Character (1-70). */
    public void transform(int characterId) {
        if (characterId < 1 || characterId > CHARACTERS) {
            throw new IllegalArgumentException("Cosmic Character ID out of range: " + characterId);
        }
        schedule = buildSchedule(characterId);
        armed = true;
        running = false;
    }

    void onRpRead() {
        if (armed) {
            armed = false;
            running = true;
            index = 0;
            remaining = schedule[0];
        }
    }

    void tick(int cycles) {
        if (!running) {
            return;
        }
        remaining -= cycles;
        while (remaining <= 0) {
            if (++index >= schedule.length) {
                running = false;
                return;
            }
            remaining += schedule[index];
        }
    }

    boolean isLightOn() {
        return running && (index & 1) == 0; // even entries are the ON periods
    }

    private static int[] buildSchedule(int characterId) {
        int[] delays = new int[18];
        delays[0] = SYNC_DELAY;
        int checksum = characterId & 0xff; // byte 1 + byte 2 = 0xFF
        int data = (~characterId) & 0xff; // the ID is transmitted complemented
        for (int bit = 0; bit < 8; bit++) {
            delays[1 + bit] = ((checksum >> bit) & 1) != 0 ? LONG_DELAY : SHORT_DELAY;
            delays[9 + bit] = ((data >> bit) & 1) != 0 ? LONG_DELAY : SHORT_DELAY;
        }
        delays[17] = SHORT_DELAY; // required by the software, not part of the data

        int[] schedule = new int[36];
        for (int i = 0; i < 18; i++) {
            int on = delays[i] / 2;
            int off = delays[i] - on;
            schedule[2 * i] = (i == 0 ? 74 : 78) + 20 * (on - 2);
            schedule[2 * i + 1] = 38 + 20 * (off - 2);
        }
        return schedule;
    }

    @Override
    public Memento<FullChanger> saveToMemento() {
        return new FullChangerMemento(schedule.clone(), armed, running, index, remaining);
    }

    @Override
    public void restoreFromMemento(Memento<FullChanger> memento) {
        if (!(memento instanceof FullChangerMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.schedule = mem.schedule.clone();
        this.armed = mem.armed;
        this.running = mem.running;
        this.index = mem.index;
        this.remaining = mem.remaining;
    }

    private record FullChangerMemento(int[] schedule, boolean armed, boolean running, int index, int remaining)
            implements Memento<FullChanger> {
    }
}
