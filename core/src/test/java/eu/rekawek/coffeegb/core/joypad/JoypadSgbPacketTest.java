package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JoypadSgbPacketTest {

    private EventBusImpl sgbBus;
    private Joypad joypad;
    private List<int[]> receivedPackets;

    @Before
    public void setUp() {
        sgbBus = new EventBusImpl(null, null, false);
        joypad = new Joypad(new InterruptManager(false), sgbBus, true);
        receivedPackets = new ArrayList<>();
        sgbBus.register(event -> receivedPackets.add(event.packet()),
                SuperGameboy.PacketReceivedEvent.class);
    }

    @Test
    public void completePacketIsPostedWhenStopPulseReturnsHigh() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 128, false);

        assertTrue(receivedPackets.isEmpty());
        writeSelector(0x20);
        assertTrue(receivedPackets.isEmpty());
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void firstPacketCanUsePowerOnLowAsStartPulse() {
        int[] packet = patternedPacket();
        writeSelector(0x00); // JOYP already powers on at this level
        writeSelector(0x30);
        writeDataBits(packet, 0, 128, false);
        writeSelector(0x20);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void stopPulseValueIsIgnored() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 128, false);
        writeSelector(0x10);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void dataPulseImmediatelyAfterResetRejectsPacket() {
        writeSelector(0x30);
        writeSelector(0x00);
        writeSelector(0x10); // the start pulse was never released to idle-high

        writeDataBits(patternedPacket(), 1, 128, false);
        writeSelector(0x20);

        assertTrue(receivedPackets.isEmpty());
    }

    @Test
    public void alternatingControllerPollingCannotBecomeAaPacket() {
        writeSelector(0x30);
        writeSelector(0x00);
        for (int i = 0; i < 256; i++) {
            // Ordinary polling can alternate the two selector lines, but without the
            // idle-high spaces this is not an SGB transmission.
            writeSelector((i & 1) == 0 ? 0x20 : 0x10);
        }

        assertTrue(receivedPackets.isEmpty());
    }

    @Test
    public void repeatedJoypLevelsDoNotDuplicateBits() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 128, true);
        writeSelector(0x20);
        writeSelector(0x20);
        writeSelector(0x30);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void midPacketMementoRestoresReceiverPhaseAndData() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 37, false);
        Memento<Joypad> memento = joypad.saveToMemento();

        // Mutate every receiver field, including the packet array retained by the
        // snapshot, before restoring the partial transfer.
        startPacket();
        writeDataBits(new int[16], 0, 19, false);
        joypad.restoreFromMemento(memento);

        writeDataBits(packet, 37, 128, false);
        writeSelector(0x20);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void mementoRestoresPendingIdleHighPhase() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 37, false);
        writeSelector(selectorForBit(packet, 37));
        Memento<Joypad> memento = joypad.saveToMemento();

        startPacket();
        joypad.restoreFromMemento(memento);
        writeSelector(0x30);
        writeDataBits(packet, 38, 128, false);
        writeSelector(0x20);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void mementoRestoresPacketAwaitingStopBit() {
        int[] packet = patternedPacket();
        startPacket();
        writeDataBits(packet, 0, 128, false);
        Memento<Joypad> memento = joypad.saveToMemento();

        startPacket();
        joypad.restoreFromMemento(memento);
        writeSelector(0x20);
        writeSelector(0x30);

        assertEquals(1, receivedPackets.size());
        assertArrayEquals(packet, receivedPackets.get(0));
    }

    @Test
    public void malformedPollingCannotPoisonFollowingSgbCommand() {
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.Pal01Cmd> command = new AtomicReference<>();
        sgbBus.register(command::set, Commands.Pal01Cmd.class);

        writeSelector(0x30);
        writeSelector(0x00);
        for (int i = 0; i < 128; i++) {
            writeSelector((i & 1) == 0 ? 0x20 : 0x10);
        }

        int[] packet = new int[16];
        packet[0] = 1; // PAL01, one packet
        packet[1] = 0x34;
        packet[2] = 0x12;
        sendPacket(packet);

        assertNotNull(command.get());
        assertEquals(0x1234, command.get().getPalette0()[0]);
    }

    @Test
    public void undocumentedMltReqTwoKeepsItsSpecialPlayerIdState() {
        postMltReq(3);
        selectNextPlayer();
        assertEquals(0xfe, joypad.getByte(0xff00));

        postMltReq(2);
        assertEquals(0xfd, joypad.getByte(0xff00));
        selectNextPlayer();
        assertEquals(0xfd, joypad.getByte(0xff00));

        postMltReq(3);
        selectNextPlayer();
        assertEquals(0xfc, joypad.getByte(0xff00));
        postMltReq(2);
        assertEquals(0xff, joypad.getByte(0xff00));
    }

    private void postMltReq(int multiplayerControl) {
        int[] packet = new int[16];
        packet[0] = 0x11 * 8 + 1;
        packet[1] = multiplayerControl;
        sgbBus.post(Commands.toCommand(packet));
    }

    private void selectNextPlayer() {
        writeSelector(0x10);
        writeSelector(0x30);
    }

    private void sendPacket(int[] packet) {
        startPacket();
        writeDataBits(packet, 0, 128, false);
        writeSelector(0x20);
        writeSelector(0x30);
    }

    private void startPacket() {
        writeSelector(0x30);
        writeSelector(0x00);
        writeSelector(0x30);
    }

    private void writeDataBits(int[] packet, int from, int to, boolean repeatLevels) {
        for (int bitIndex = from; bitIndex < to; bitIndex++) {
            int selector = selectorForBit(packet, bitIndex);
            writeSelector(selector);
            if (repeatLevels) {
                writeSelector(selector);
            }
            writeSelector(0x30);
            if (repeatLevels) {
                writeSelector(0x30);
            }
        }
    }

    private static int selectorForBit(int[] packet, int bitIndex) {
        int bit = (packet[bitIndex / 8] >> (bitIndex & 7)) & 1;
        return bit == 0 ? 0x20 : 0x10;
    }

    private void writeSelector(int selector) {
        joypad.setByte(0xff00, selector);
    }

    private static int[] patternedPacket() {
        int[] packet = new int[16];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (i * 37 + 0x51) & 0xff;
        }
        return packet;
    }
}
