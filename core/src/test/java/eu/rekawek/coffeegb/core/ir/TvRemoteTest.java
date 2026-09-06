package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TvRemoteTest {

    private final SpeedMode speedMode = new SpeedMode(true);

    private final InfraredPort port = new InfraredPort(true, speedMode);

    @Test
    public void sendsValidNecFrameAfterFirstRpPoll() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        port.init(bus);
        port.setByte(0xff56, 0xc0);
        bus.post(new TvRemote.SendSignalEvent());

        int[] durations = captureEnvelope();
        assertEquals(TvRemote.LEADER_BURST_CYCLES, durations[0]);
        assertEquals(TvRemote.LEADER_SPACE_CYCLES, durations[1]);

        int frame = 0;
        for (int bit = 0; bit < 32; bit++) {
            assertEquals(TvRemote.BIT_BURST_CYCLES, durations[2 + bit * 2]);
            int space = durations[3 + bit * 2];
            if (space == TvRemote.ONE_SPACE_CYCLES) {
                frame |= 1 << bit;
            } else {
                assertEquals(TvRemote.ZERO_SPACE_CYCLES, space);
            }
        }
        assertEquals(TvRemote.BIT_BURST_CYCLES, durations[66]);

        int address = frame & 0xff;
        int inverseAddress = frame >> 8 & 0xff;
        int command = frame >> 16 & 0xff;
        int inverseCommand = frame >> 24 & 0xff;
        assertEquals(0x00, address);
        assertEquals(0x10, command);
        assertEquals(0xff, address ^ inverseAddress);
        assertEquals(0xff, command ^ inverseCommand);
    }

    @Test
    public void stateRoundTripContinuesSameEnvelope() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        port.init(bus);
        port.setByte(0xff56, 0xc0);
        bus.post(new TvRemote.SendSignalEvent());
        port.getByte(0xff56);
        for (int i = 0; i < TvRemote.LEADER_BURST_CYCLES + 1234; i++) {
            port.tick();
        }
        ComponentState<InfraredPort> state = port.captureState();

        InfraredPort restored = new InfraredPort(true, speedMode);
        restored.restoreState(state);
        for (int i = 0; i < 150_000; i++) {
            assertEquals(port.getByte(0xff56), restored.getByte(0xff56));
            port.tick();
            restored.tick();
        }
    }

    @Test
    public void ignoresRemoteActionInDmgCompatibilityMode() {
        speedMode.setDmgCompat(true);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        port.init(bus);
        bus.post(new TvRemote.SendSignalEvent());

        speedMode.setDmgCompat(false);
        port.setByte(0xff56, 0xc0);
        assertEquals(false, isLightOn());
    }

    private int[] captureEnvelope() {
        int[] durations = new int[67];
        boolean lightOn = isLightOn(); // the first read starts the leader burst
        assertEquals(true, lightOn);
        for (int segment = 0; segment < durations.length; segment++) {
            int ticks = 0;
            do {
                port.tick();
                ticks++;
            } while (isLightOn() == lightOn && ticks < 100_000);
            durations[segment] = ticks;
            lightOn = !lightOn;
        }
        assertEquals(false, isLightOn());
        return durations;
    }

    private boolean isLightOn() {
        return (port.getByte(0xff56) & 0x02) == 0;
    }
}
