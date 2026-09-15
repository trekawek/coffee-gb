package eu.rekawek.coffeegb.core.ir;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GbKissWireTest {
    @Test
    public void rejectedByteCannotCompleteAnAbandonedStream() {
        GbKissWire sender = new GbKissWire(message -> fail(message));
        GbKissWire receiver = new GbKissWire(message -> fail(message));
        boolean[] rejected = {false};
        receiver.receive(value -> {
            rejected[0] = true;
            receiver.clear();
            receiver.receive(ignored -> true, () -> fail("Unexpected second packet"));
            return true;
        }, () -> fail("The rejected packet must not complete"));
        sender.send(new byte[]{42}, () -> {});
        int tail = 0;
        for (int i = 0; i < 100_000; i++) {
            sender.input(receiver.output());
            receiver.input(sender.output());
            sender.tick();
            receiver.tick();
            if (rejected[0] && ++tail > 1000) break;
        }
        assertTrue(rejected[0]);
    }

    @Test
    public void acceptsCartridgeHandshakeWithoutAnExtraLeadingDummyPulse() {
        List<String> failures = new ArrayList<>();
        GbKissWire wire = new GbKissWire(failures::add);
        wire.receive(value -> true, () -> {});
        wire.tick();
        // Measured cartridge AA framing: eight data periods, stop, trailing zero.
        for (int bit = 7; bit >= 0; bit--) {
            boolean one = (0xaa & (1 << bit)) != 0;
            pulse(wire, one ? 300 : 172, one ? 716 : 460);
        }
        pulse(wire, 572, 1220);
        wire.input(true);
        for (int i = 0; i < 172; i++) wire.tick();
        wire.input(false);
        for (int i = 0; i < 10; i++) wire.tick();
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue("The receiver must start its 55 acknowledgement", wire.output());
    }

    private void pulse(GbKissWire wire, int high, int period) {
        wire.input(true);
        for (int i = 0; i < high; i++) wire.tick();
        wire.input(false);
        for (int i = high; i < period; i++) wire.tick();
    }
}
