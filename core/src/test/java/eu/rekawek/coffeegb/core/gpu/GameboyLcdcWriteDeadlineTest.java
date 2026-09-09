package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.*;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.*;
import eu.rekawek.coffeegb.core.joypad.*;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.*;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

/** Real peripheral deadlines exclude their event dot after a multi-write LCDC timeline. */
public class GameboyLcdcWriteDeadlineTest {
    private static final int PREFIX = 39;
    private enum Deadline { AUDIO, TIMER, SERIAL, INPUT }

    @Test public void synchronousPcmBufferCallbackWaitsUntilAfterTheTimeline() throws Exception {
        check(Deadline.AUDIO);
    }
    @Test public void timaOverflowReloadAndIfPreserveTheEndpointAndRestore() throws Exception {
        check(Deadline.TIMER);
    }
    @Test public void realSerialByteAndHeldHubPollPreserveCallbacksAndRestore() throws Exception {
        check(Deadline.SERIAL);
        check(Deadline.INPUT);
    }

    private static void check(Deadline deadline) throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0})
            for (int phase = 0; phase < 2; phase++)
                for (boolean restoredEntry : new boolean[]{false, true})
                    try (Fixture a = new Fixture(deadline, profile, 300 + phase);
                         Fixture b = new Fixture(deadline, profile, 300 + phase)) {
                        String label = deadline + " " + profile.id() + " phase=" + phase + " restored=" + restoredEntry;
                        if (restoredEntry) {
                            // Each capture is restored exactly once: restored array members may become live.
                            a.restore(a.capture()); b.restore(b.capture());
                            a.gb.tick(); b.gb.tick(); // Settle derived STAT timing without moving the deadline.
                        }
                        int prefix = PREFIX - (restoredEntry ? 1 : 0);
                        assertEquals(label + " real peripheral horizon", prefix, b.horizon());
                        equivalent(label + " entry", a, b);
                        long writes = b.gb.getPerformanceLcdcWriteReplayWrites();
                        int elapsed = owner(b.gb, 54);
                        assertEquals(label + " owner must consume the complete safe prefix", prefix, elapsed);
                        long actualWrites = b.gb.getPerformanceLcdcWriteReplayWrites() - writes;
                        assertTrue(label + " must queue multiple actual LCDC writes, got " + actualWrites, actualWrites > 1);
                        for (int tick = 0; tick < elapsed; tick++) a.gb.tick();
                        equivalent(label + " before deadline", a, b);
                        b.beforeEvent();
                        assertEquals(label + " expired peripheral proof", 0, b.horizon());
                        var before = b.gb.captureStateWithoutTimeSource();
                        assertEquals(label + " owner cannot cross the event", 0, owner(b.gb, 54));
                        assertStateEquals(label + " rejected owner does not mutate state", before, b.gb.captureStateWithoutTimeSource());
                        assertEquals(label + " timeline must be cleared on return", 0, b.gb.getCpu().replayPerformanceLcdcWritesAtDot(0));

                        // Restore real port/endpoint/audio/input state at the excluded boundary.
                        // Endpoint state is separately owned by the controller, not Gameboy's memento.
                        a.restore(a.capture()); b.restore(b.capture());
                        int eventTicks = deadline == Deadline.SERIAL ? 4 : 1;
                        for (int tick = 1; tick <= eventTicks; tick++) {
                            a.gb.tick(); b.gb.tick();
                            equivalent(label + " excluded scalar dot " + tick, a, b);
                            if (tick < eventTicks) b.beforeEvent();
                        }
                        b.afterEvent();
                        if (deadline == Deadline.TIMER) {
                            for (int tick = 0; tick < 2; tick++) { a.gb.tick(); b.gb.tick(); }
                            equivalent(label + " TIMA reload", a, b);
                            assertEquals(label + " reloaded TMA", 0xa7, b.timer.getByte(0xff05));
                        }
                        for (int tick = 0; tick < 16; tick++) { a.gb.tick(); b.gb.tick(); }
                        equivalent(label + " event continuation", a, b);
                        if (deadline == Deadline.TIMER) {
                            assertFalse(b.timer.isDebugOverflowPending());
                            assertNotEquals(label + " timer IF delivered", 0, b.gb.getAddressSpace().getByte(0xff0f) & 4);
                        }
                        if (deadline == Deadline.INPUT) {
                            assertEquals(label + " held direction filter delivered", 0, b.joypad.getByte(0xff00) & 1);
                            assertNotEquals(label + " joypad IF delivered", 0, b.gb.getAddressSpace().getByte(0xff0f) & 0x10);
                        }
                    }
    }

    private static int owner(Gameboy gb, int budget) throws Exception {
        gb.getGpu().setPerformanceScanlineEnabled(true);
        Method method = Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch", long.class);
        method.setAccessible(true);
        return (int) method.invoke(gb, (long) budget);
    }
    private static void equivalent(String label, Fixture a, Fixture b) throws Exception {
        assertStateEquals(label, a.gb.captureStateWithoutTimeSource(), b.gb.captureStateWithoutTimeSource());
        assertStateEquals(label + " separately owned serial endpoint", a.endpoint.captureState(), b.endpoint.captureState());
        assertEquals(label + " audio callback count", a.audio.size(), b.audio.size());
        for (int i = 0; i < a.audio.size(); i++) assertArrayEquals(label + " PCM " + i, a.audio.get(i), b.audio.get(i));
        assertEquals(label + " synchronous PCM callback machine boundary", a.audioBoundaries, b.audioBoundaries);
        assertEquals(label + " actual byte callback machine boundary", a.serialEvents, b.serialEvents);
    }
    private record Saved(ComponentState<Gameboy> machine, ComponentState<SerialEndpoint> endpoint) { }

    private static final class Fixture implements AutoCloseable {
        final Deadline deadline;
        final Gameboy gb;
        final Timer timer;
        final Joypad joypad;
        final SerialPort port;
        final PlayerInputHub hub = new PlayerInputHub();
        final PlayerInputHub.SourceHandle input = hub.openSource(0);
        final EventBusImpl bus = new EventBusImpl(null, null, false);
        final List<int[]> audio = new ArrayList<>();
        final List<List<Integer>> audioBoundaries = new ArrayList<>();
        final List<List<Integer>> serialEvents = new ArrayList<>();
        final ByteReceivingSerialEndpoint endpoint;

        Fixture(Deadline deadline, HardwareProfile profile, int dot) throws Exception {
            this.deadline = deadline;
            byte[] image = new byte[0x8000];
            image[0x100] = (byte) 0xc3; image[0x101] = 0x50; image[0x102] = 1; image[0x143] = (byte) 0x80;
            int[] loop = {0x3e, 0x93, 0xe0, 0x40, 0x3e, 0xb3, 0xe0, 0x40, 0xc3, 0x50, 1};
            for (int i = 0; i < loop.length; i++) image[0x150 + i] = (byte) loop[i];
            gb = new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setPlayerInputSource(deadline == Deadline.INPUT ? hub : PlayerInputSource.RELEASED)
                    .setRtcTimeSource(() -> 0L).setSupportBatterySave(false).build();
            gb.getAddressSpace().setByte(0xff00, 0x20);
            GameboyLcdcWritePacketTest.prepare(gb, dot);
            timer = (Timer) field(gb, "timer"); joypad = (Joypad) field(gb, "joypad");
            port = (SerialPort) field(gb, "serialPort");
            endpoint = new ByteReceivingSerialEndpoint(value -> serialEvents.add(List.of(value,
                    gb.getGpu().getTicksInLine(), gb.getCpu().getRegisters().getPC(), timer.getDivCounter())));
            timer.setByte(0xff07, 0); timer.presetDiv(0);
            Sound sound = gb.getSound(); sound.materializePendingPerformanceTicks();
            sound.init(bus);
            bus.register((Sound.SoundSampleEvent event) -> {
                audio.add(event.buffer().clone());
                audioBoundaries.add(List.of(gb.getGpu().getTicksInLine(), gb.getCpu().getRegisters().getPC(), timer.getDivCounter()));
            }, Sound.SoundSampleEvent.class);
            switch (deadline) {
                case AUDIO -> {
                    sound.setByte(0xff26, 0x80); sound.setByte(0xff24, 0x77); sound.setByte(0xff25, 0x22);
                    sound.setByte(0xff16, 0x80); sound.setByte(0xff17, 0xf0);
                    sound.setByte(0xff18, 0x20); sound.setByte(0xff19, 0x80);
                    int decimation = (int) field(sound, "performanceAudioDecimation");
                    set(sound, "performanceSamplePhase", decimation - PREFIX - 1);
                    int[] buffer = (int[]) field(sound, "buffer"); Arrays.fill(buffer, 0);
                    set(sound, "i", buffer.length - 2);
                }
                case TIMER -> {
                    timer.setByte(0xff06, 0xa7); timer.setByte(0xff05, 0xfb);
                    timer.setByte(0xff07, 0x05); // Five divider falling edges; overflow at master tick40.
                }
                case SERIAL -> {
                    gb.setSerialEndpoint(endpoint); port.setByte(0xff01, 0xa6); port.setByte(0xff02, 0x81);
                    // Phase-seed seven real falling edges. Every edge still executes the complete
                    // scalar machine and the production receiver; no test endpoint promises are added.
                    for (int bit = 0; bit < 7; bit++) {
                        set(port, "serialClocks", 254); set(port, "serialClockSignal", true); gb.tick();
                    }
                    assertEquals(7, field(port, "receivedBits")); assertTrue(serialEvents.isEmpty());
                    // The final-bit acknowledgement guard starts four master ticks before the edge.
                    set(port, "serialClocks", 256 - 2 * (PREFIX + 4));
                    set(port, "serialClockSignal", true);
                }
                case INPUT -> {
                    set(joypad, "tick", 64L - PREFIX);
                    input.update(Set.of(Button.RIGHT));
                }
            }
            sound.tickFrameSequencer(false); sound.commitFrameSequencerClock();
            gb.getAddressSpace().setByte(0xff0f, 0);
        }
        int horizon() {
            return switch (deadline) {
                case AUDIO -> gb.getSound().performanceFencedEpochSpanLimit(54);
                case TIMER -> timer.performanceEpochSpanLimit(54);
                case SERIAL -> port.performanceEventHorizon(54);
                case INPUT -> joypad.performanceSettledHaltSpanLimit(54);
            };
        }
        Saved capture() { return new Saved(gb.captureStateWithoutTimeSource(), endpoint.captureState()); }
        void restore(Saved saved) { gb.restoreStateSilently(saved.machine()); endpoint.restoreState(saved.endpoint()); }
        void beforeEvent() throws Exception {
            switch (deadline) {
                case AUDIO -> assertTrue(audio.isEmpty());
                case TIMER -> assertFalse(timer.isDebugOverflowPending());
                case SERIAL -> { assertTrue(serialEvents.isEmpty()); assertEquals(0, gb.getAddressSpace().getByte(0xff0f) & 8); }
                case INPUT -> {
                    assertEquals(Set.of(), ((PlayerInputSnapshot) field(joypad, "sampledInput")).buttons(0));
                    assertEquals(1, joypad.getByte(0xff00) & 1);
                }
            }
        }
        void afterEvent() throws Exception {
            switch (deadline) {
                case AUDIO -> {
                    assertEquals(1, audio.size()); int[] pcm = audio.get(0);
                    assertTrue("real nonzero PCM", pcm[pcm.length - 1] != 0 || pcm[pcm.length - 2] != 0);
                }
                case TIMER -> assertTrue(timer.isDebugOverflowPending());
                case SERIAL -> {
                    assertEquals(1, serialEvents.size()); assertEquals(0xa6, (int) serialEvents.get(0).get(0));
                    assertEquals(0, port.getByte(0xff02) & 0x80);
                    assertNotEquals(0, gb.getAddressSpace().getByte(0xff0f) & 8);
                }
                case INPUT -> assertEquals(Set.of(Button.RIGHT), ((PlayerInputSnapshot) field(joypad, "sampledInput")).buttons(0));
            }
        }
        public void close() { gb.close(); bus.close(); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
