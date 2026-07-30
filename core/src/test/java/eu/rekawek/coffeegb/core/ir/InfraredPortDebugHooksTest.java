package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class InfraredPortDebugHooksTest {

    @Test
    public void reportsCombinedOutputAndReceivedSignalsWithoutRepeats() {
        MutableEndpoint endpoint = new MutableEndpoint();
        endpoint.receivedLight = true;
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));
        port.init(EventBus.NULL_EVENT_BUS, endpoint);
        port.setByte(0xff56, 0x01);
        RecordingHooks hooks = new RecordingHooks();

        port.setDebugHooks(hooks);
        port.setByte(0xff56, 0x01);
        port.setByte(0xff56, 0x00);
        endpoint.receivedLight = false;
        port.tick();
        port.tick();

        assertEquals(List.of(2, 0), hooks.values);
    }

    @Test
    public void restoreRealignsTheSignalWithoutSyntheticEvents() {
        MutableEndpoint endpoint = new MutableEndpoint();
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));
        port.init(EventBus.NULL_EVENT_BUS, endpoint);
        ComponentState<InfraredPort> state = port.captureState();
        RecordingHooks hooks = new RecordingHooks();
        port.setDebugHooks(hooks);
        port.setByte(0xff56, 0x01);
        hooks.values.clear();

        port.restoreState(state);
        port.setByte(0xff56, 0x00);

        assertEquals(List.of(), hooks.values);
        assertEquals(false, endpoint.localOutput);
    }

    private static final class RecordingHooks implements DebugHooks {

        private final List<Integer> values = new ArrayList<>();

        @Override
        public void onSerialIrEvent(
                SerialIrTrace.Endpoint endpoint, SerialIrTrace.Kind kind, int value) {
            if (endpoint == SerialIrTrace.Endpoint.INFRARED
                    && kind == SerialIrTrace.Kind.SIGNAL_CHANGED) {
                values.add(value);
            }
        }

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }

    private static final class MutableEndpoint implements InfraredEndpoint {

        private boolean localOutput;

        private boolean receivedLight;

        @Override
        public void setLightOn(boolean lightOn) {
            localOutput = lightOn;
        }

        @Override
        public boolean isLightOn() {
            return receivedLight;
        }
    }
}
