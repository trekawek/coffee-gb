package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import org.slf4j.Logger;

import java.util.Arrays;

import static org.slf4j.LoggerFactory.getLogger;

public class SuperGameboy implements Originator<SuperGameboy> {

    private static final Logger LOG = getLogger(SuperGameboy.class);

    private final EventBus sgbBus;

    private int multipacketIndex;
    private int multipacketLength;
    private final int[][] multipacket = new int[7][16];

    public SuperGameboy(EventBus sgbBus) {
        this.sgbBus = sgbBus;
        sgbBus.register(event -> handlePacket(event.packet()), PacketReceivedEvent.class);
    }

    private void handlePacket(int[] packet) {
        if (multipacketIndex == 0) {
            var command = packet[0] / 8;
            if (command >= 0x1a && command <= 0x1f) {
                multipacketLength = 1;
            } else {
                multipacketLength = packet[0] % 8;
            }
        }
        System.arraycopy(packet, 0, multipacket[multipacketIndex++], 0, 16);
        if (multipacketIndex == multipacketLength) {
            handleMultipacket();
            multipacketIndex = 0;
            multipacketLength = 0;
        }
    }

    private void handleMultipacket() {
        int[] transfer = new int[16 * multipacketLength];
        for (int i = 0; i < multipacketLength; i++) {
            System.arraycopy(multipacket[i], 0, transfer, i * 16, 16);
        }
        var cmd = Commands.toCommand(transfer);
        if (cmd == null) {
            LOG.warn("Unknown command: {} {}", Integer.toHexString(transfer[0] / 8), Arrays.toString(transfer));
        }
        if (cmd != null) {
            LOG.atInfo().log("Received command: {}", cmd);
            sgbBus.post(cmd);
        }
    }

    @Override
    public Memento<SuperGameboy> saveToMemento() {
        return new SuperGameboyMemento(multipacket, multipacketIndex, multipacketLength);
    }

    @Override
    public void restoreFromMemento(Memento<SuperGameboy> memento) {
        if (!(memento instanceof SuperGameboyMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.multipacket.length != mem.multipacket.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        this.multipacketIndex = mem.multipacketIndex;
        this.multipacketLength = mem.multipacketLength;
        for (int i = 0; i < this.multipacket.length; i++) {
            System.arraycopy(mem.multipacket[i], 0, this.multipacket[i], 0, 16);
        }
    }

    private record SuperGameboyMemento(int[][] multipacket, int multipacketIndex,
                                       int multipacketLength) implements Memento<SuperGameboy> {
    }

    public record PacketReceivedEvent(int[] packet) implements Event {
    }
}
