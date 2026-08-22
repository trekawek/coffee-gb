package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.serial.SerialCompatibilityProfile;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JantakuBoyFourPlayerPatchTest {

    @Test
    public void detectsOnlyTheKnownFourPlayerTransition() throws IOException {
        Rom rom = new Rom(jantakuBoyRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.JANTAKU_BOY_FOUR_PLAYER_PATCH));
        assertEquals(0xf0, rom.getRom()[0x0395]);
    }

    @Test
    public void rejectsARevisionWithoutTheKnownHandler() throws IOException {
        byte[] data = jantakuBoyRom();
        data[0x0395] = 0;

        Rom rom = new Rom(data);

        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.JANTAKU_BOY_FOUR_PLAYER_PATCH));
        assertEquals(0x00, rom.getRom()[0x0395]);
    }

    @Test
    public void enablesTheSerialWorkaroundOnlyForTheDetectedRom() throws IOException {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(jantakuBoyRom()))
                .setSupportBatterySave(false)
                .build()) {
            RecordingEndpoint endpoint = new RecordingEndpoint();

            gameboy.init(EventBus.NULL_EVENT_BUS, endpoint, null);

            assertEquals(SerialCompatibilityProfile.JANTAKU_BOY_FOUR_PLAYER_CONTROL_PACKET,
                    endpoint.profile);
        }
    }

    private static final class RecordingEndpoint implements SerialEndpoint {

        private SerialCompatibilityProfile profile;

        @Override
        public void enableCompatibilityProfile(SerialCompatibilityProfile profile) {
            this.profile = profile;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
        }

        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            return 1;
        }
    }

    private static byte[] jantakuBoyRom() {
        byte[] data = new byte[0x20000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "JANTAKUBOY".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x00;
        data[0x0147] = 0x01;
        data[0x0148] = 0x02;
        data[0x0149] = 0x00;
        int[] fourPlayerWait = {
                0xf0, 0x9b, 0x47, 0x0e, 0x00, 0x21, 0x9d, 0xff,
                0x3e, 0xfd, 0xbe, 0xc0, 0x71, 0x3e, 0xff, 0x23,
                0x05, 0x20, 0xf7, 0x3e, 0x02, 0xe0, 0x99, 0x3e,
                0x01, 0xe0, 0xa8
        };
        for (int i = 0; i < fourPlayerWait.length; i++) {
            data[0x0395 + i] = (byte) fourPlayerWait[i];
        }
        return data;
    }
}
