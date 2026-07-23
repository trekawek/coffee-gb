package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.Serializable;

/**
 * The CGB infrared port - the RP register at 0xFF56 (issue #94).
 *
 * <p>Bit 0 drives the console's own IR LED (write). Bit 1 reads the light sensor,
 * inverted: 0 means IR light is being received. The sensor only reports light while both
 * read-enable bits 6-7 are set; otherwise bit 1 reads 1. The register does not exist in
 * DMG-compatibility mode (reads 0xFF), matching the other CGB-only registers.
 *
 * <p>Received light comes from a pluggable external device. Supported sources are another
 * linked Game Boy and the {@link FullChanger} (Zok Zok Heroes).
 */
public class InfraredPort implements AddressSpace, Serializable, Originator<InfraredPort> {

    private final boolean gbc;

    private final SpeedMode speedMode;

    private final FullChanger fullChanger = new FullChanger();

    private transient InfraredEndpoint endpoint = InfraredEndpoint.NULL_ENDPOINT;

    private transient SerialEndpoint serialEndpoint = SerialEndpoint.NULL_ENDPOINT;

    // the written bits of RP: bit 0 (own LED) and bits 6-7 (read enable)
    private int rp;

    public InfraredPort(boolean gbc, SpeedMode speedMode) {
        this.gbc = gbc;
        this.speedMode = speedMode;
    }

    public void init(EventBus eventBus) {
        init(eventBus, InfraredEndpoint.NULL_ENDPOINT);
    }

    public void init(EventBus eventBus, InfraredEndpoint endpoint) {
        this.endpoint.setLightOn(false);
        this.endpoint = endpoint;
        endpoint.setLightOn((rp & 0x01) != 0);
        eventBus.register(e -> fullChanger.transform(e.characterId()), FullChanger.TransformEvent.class);
    }

    /** Connects RP bit 4 to the CGB link port's serial-input pin. */
    public void setSerialEndpoint(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint == null
                ? SerialEndpoint.NULL_ENDPOINT
                : serialEndpoint;
    }

    public void close() {
        endpoint.setLightOn(false);
        endpoint = InfraredEndpoint.NULL_ENDPOINT;
        serialEndpoint = SerialEndpoint.NULL_ENDPOINT;
    }

    public void tick() {
        // the pulse timings are defined in double-speed cycles; advance twice as fast in
        // double speed so a game sees the same delays regardless of its speed setting
        fullChanger.tick(speedMode.getSpeedMode());
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff56;
    }

    @Override
    public void setByte(int address, int value) {
        rp = value & 0xc1;
        endpoint.setLightOn((rp & 0x01) != 0);
    }

    @Override
    public int getByte(int address) {
        if (!gbc || speedMode.isDmgCompat()) {
            return 0xff;
        }
        // an armed device starts transmitting at a poll of the register, so the polling
        // loop observes the first pulse from its beginning
        fullChanger.onRpRead();
        // Bits 2, 3 and 5 are pulled high. Bit 4 is not unused on CGB hardware: it
        // exposes link-port pin 4 as a raw digital input for software UARTs.
        int result = rp | 0x2c | 0x02;
        if (serialEndpoint.isSerialInputHigh()) {
            result |= 0x10;
        }
        int readMode = rp & 0xc0;
        // The intermediate $80 mode pulls the sensor bit low even without a
        // light source. In the normal $C0 receive mode it is active-low only
        // while infrared light is present.
        if (readMode == 0x80
                || (readMode == 0xc0 && (fullChanger.isLightOn() || endpoint.isLightOn()))) {
            result &= ~0x02;
        }
        return result;
    }

    @Override
    public Memento<InfraredPort> saveToMemento() {
        return new InfraredPortMemento(rp, fullChanger.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<InfraredPort> memento) {
        if (!(memento instanceof InfraredPortMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.rp = mem.rp;
        endpoint.setLightOn((rp & 0x01) != 0);
        fullChanger.restoreFromMemento(mem.fullChangerMemento);
    }

    private record InfraredPortMemento(int rp, Memento<FullChanger> fullChangerMemento)
            implements Memento<InfraredPort> {
    }
}
