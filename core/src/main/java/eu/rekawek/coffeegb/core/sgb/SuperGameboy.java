package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.sgb.Commands.TransferCommand;
import org.slf4j.Logger;

import java.util.Arrays;

import static org.slf4j.LoggerFactory.getLogger;

public class SuperGameboy implements StatefulComponent<SuperGameboy> {

    private static final Logger LOG = getLogger(SuperGameboy.class);

    public static final int SGB_DISPLAY_WIDTH = 256;

    public static final int SGB_DISPLAY_HEIGHT = 224;

    private final EventBus sgbBus;

    private int multipacketIndex;
    private int multipacketLength;
    private final int[][] multipacket = new int[7][16];

    private int transferCountdown;
    private TransferCommand waitingTransferCommand;

    public SuperGameboy(EventBus sgbBus) {
        this.sgbBus = sgbBus;
        sgbBus.register(event -> handlePacket(event.packet()), PacketReceivedEvent.class);
        sgbBus.register(event -> abortMultipacket(), PacketTransferAbortedEvent.class);
        sgbBus.register(event -> {
            handleVBlank(event.buffer());
        }, VRamTransfer.VRamTransferComplete.class);

    }

    private void handleVBlank(int[] buffer) {
        if (waitingTransferCommand != null && --transferCountdown == 0) {
            TransferCommand command = waitingTransferCommand;
            waitingTransferCommand = null;
            transferCountdown = 0;
            try {
                String violation = Commands.validateTransferCommitData(command, buffer);
                if (violation != null) {
                    throw new IllegalArgumentException(violation);
                }
                command.setDataTransfer(buffer);
                LOG.atInfo().log("Transfer command: {}", command);
                sgbBus.post(command);
            } catch (IllegalArgumentException e) {
                LOG.warn("Rejected SGB transfer payload for command 0x{}: {}",
                        Integer.toHexString(command.getCode()), e.getMessage());
            }
        }
    }

    private void handlePacket(int[] packet) {
        if (!isPacket(packet)) {
            LOG.warn("Rejected malformed SGB transport packet");
            abortMultipacket();
            return;
        }
        if (multipacketIndex == 0) {
            int declaredLength = packet[0] & 7;
            if (declaredLength < 1 || declaredLength > multipacket.length) {
                LOG.warn("Rejected SGB command 0x{} with packet count {}",
                        Integer.toHexString(packet[0] >>> 3), declaredLength);
                abortMultipacket();
                return;
            }
            int commandId = packet[0] >>> 3;
            // Preserve the established ICD compatibility rule for the six reserved command IDs:
            // each physical row is independently ignored, regardless of its count bits. The
            // CasualPokePlayer protocol probe depends on this recovery behavior, and the only
            // public evidence for these IDs is revision-specific firmware disassembly.
            multipacketLength = commandId >= 0x1a ? 1 : declaredLength;
        }
        if (multipacketIndex < 0 || multipacketIndex >= multipacketLength
                || multipacketIndex >= multipacket.length) {
            LOG.warn("Rejected SGB command after invalid multipacket collector state");
            abortMultipacket();
            return;
        }
        System.arraycopy(packet, 0, multipacket[multipacketIndex++], 0, 16);
        if (multipacketIndex == multipacketLength) {
            handleMultipacket();
            abortMultipacket();
        }
    }

    private void handleMultipacket() {
        int[] transfer = new int[16 * multipacketLength];
        for (int i = 0; i < multipacketLength; i++) {
            System.arraycopy(multipacket[i], 0, transfer, i * 16, 16);
        }
        Commands.ParseResult result = Commands.parse(transfer);
        if (result.disposition() == Commands.Disposition.INVALID) {
            LOG.warn("Rejected malformed SGB command 0x{}: {}",
                    Integer.toHexString(transfer[0] >>> 3), result.reason());
            return;
        }
        if (result.disposition() == Commands.Disposition.UNKNOWN) {
            LOG.warn("Rejected unknown SGB command 0x{}",
                    Integer.toHexString(transfer[0] >>> 3));
            return;
        }
        if (result.disposition() == Commands.Disposition.UNSUPPORTED) {
            LOG.warn("Ignoring unsupported SGB command 0x{}",
                    Integer.toHexString(transfer[0] >>> 3));
            return;
        }

        Commands.AbstractCommand cmd = result.command();
        if (cmd instanceof TransferCommand transferCommand) {
            // A newly accepted practical transfer deliberately replaces an older pending
            // transfer and restarts the three-frame ICD2 capture countdown. Malformed,
            // unsupported, and receiver-abort events never disturb an accepted transfer.
            waitingTransferCommand = transferCommand;
            transferCountdown = 3;
        } else {
            LOG.atInfo().log("SGB command: {}", cmd);
            sgbBus.post(cmd);
        }
    }

    private void abortMultipacket() {
        multipacketIndex = 0;
        multipacketLength = 0;
        for (int[] packet : multipacket) {
            Arrays.fill(packet, 0);
        }
    }

    private static boolean isPacket(int[] packet) {
        if (packet == null || packet.length != Commands.PACKET_SIZE) {
            return false;
        }
        return Arrays.stream(packet).allMatch(value -> value >= 0 && value <= 0xff);
    }

    @Override
    public ComponentState<SuperGameboy> captureState() {
        int[][] multipacketCopy = Arrays.stream(multipacket).map(int[]::clone).toArray(int[][]::new);
        return new SuperGameboyState(multipacketCopy, multipacketIndex, multipacketLength, transferCountdown,
                waitingTransferCommand == null ? null : waitingTransferCommand.captureState());
    }

    @Override
    public ComponentState<SuperGameboy> captureState(MachineStateCapture capture) {
        return new SuperGameboyState(
                capture.ints2(multipacket),
                multipacketIndex,
                multipacketLength,
                transferCountdown,
                waitingTransferCommand == null
                        ? null
                        : waitingTransferCommand.captureState(capture));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts2(multipacket);
        if (waitingTransferCommand != null) {
            waitingTransferCommand.declareMachineStatePayloads(capture);
        }
    }

    @Override
    public void restoreState(ComponentState<SuperGameboy> state) {
        if (!(state instanceof SuperGameboyState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.multipacket.length != mem.multipacket.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        for (int[] packet : mem.multipacket) {
            if (!isPacket(packet)) {
                throw new IllegalArgumentException("Invalid multipacket row");
            }
        }
        if (mem.multipacketLength < 0 || mem.multipacketLength > mem.multipacket.length
                || mem.multipacketIndex < 0 || mem.multipacketIndex > mem.multipacket.length
                || (mem.multipacketLength == 0 && mem.multipacketIndex != 0)
                || (mem.multipacketLength > 0
                && (mem.multipacketIndex < 1 || mem.multipacketIndex >= mem.multipacketLength
                || (mem.multipacket[0][0] & 7) != mem.multipacketLength))) {
            throw new IllegalArgumentException("Invalid multipacket collector state");
        }
        TransferCommand restoredTransfer = mem.waitingTransferCommandMemento == null
                ? null
                : TransferCommand.restoreState(mem.waitingTransferCommandMemento);
        if (restoredTransfer != null) {
            String violation = Commands.validatePendingTransferState(restoredTransfer);
            if (violation != null) {
                throw new IllegalArgumentException("Invalid delayed transfer state: " + violation);
            }
        }
        if ((restoredTransfer == null && mem.transferCountdown != 0)
                || (restoredTransfer != null && (mem.transferCountdown < 1 || mem.transferCountdown > 3))) {
            throw new IllegalArgumentException("Invalid delayed transfer state");
        }
        this.multipacketIndex = mem.multipacketIndex;
        this.multipacketLength = mem.multipacketLength;
        for (int i = 0; i < this.multipacket.length; i++) {
            System.arraycopy(mem.multipacket[i], 0, this.multipacket[i], 0, 16);
        }
        this.transferCountdown = mem.transferCountdown;
        this.waitingTransferCommand = restoredTransfer;
    }

    private record SuperGameboyState(int[][] multipacket, int multipacketIndex,
                                       int multipacketLength, int transferCountdown,
                                       ComponentState<TransferCommand> waitingTransferCommandMemento) implements ComponentState<SuperGameboy> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SuperGameboyMemento(int[][] multipacket, int multipacketIndex,
                                       int multipacketLength, int transferCountdown,
                                       Memento<TransferCommand> waitingTransferCommandMemento) implements Memento<SuperGameboy> {
    }

    public record PacketReceivedEvent(int[] packet) implements Event {
    }

    /** Explicit transport-level restart; it aborts packet assembly, not an accepted VRAM transfer. */
    public record PacketTransferAbortedEvent() implements Event {
    }
}
