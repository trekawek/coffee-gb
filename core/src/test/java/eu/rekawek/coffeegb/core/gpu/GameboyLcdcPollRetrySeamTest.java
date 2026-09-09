package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the bounded PlayerInputHub poll seam. The scheduler consumes only
 * scalar dots before the poll, then reruns the existing strict owner from scratch.
 */
public class GameboyLcdcPollRetrySeamTest {
    private static final int HUB_POLL_TICK_RESIDUE_BEFORE_SEAM = 57;
    private static final int POLL_DISTANCE = 8;
    private static final HardwareProfile[] NATIVE_PROFILES = {
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0};

    @Test
    public void hubDistanceCoversOnlyOnePollInterval() throws Exception {
        PlayerInputHub hub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle source = hub.openSource(0);
                Gameboy gameboy = session(hub)) {
            settle(gameboy);
            Joypad joypad = joypad(gameboy);
            for (int residue = 57; residue < 64; residue++) {
                set(joypad, "tick", (long) residue);
                assertEquals("distance at residue " + residue,
                        64 - residue + 1, joypad.performanceLcdcWriteReplayPollDistance(63));
            }
            set(joypad, "tick", 0L);
            assertEquals("distance at poll boundary", 1,
                    joypad.performanceLcdcWriteReplayPollDistance(63));
        }

        try (Gameboy released = session(PlayerInputSource.RELEASED)) {
            settle(released);
            assertEquals("released source has no live hub poll", 0,
                    joypad(released).performanceLcdcWriteReplayPollDistance(63));
        }
    }

    @Test
    public void firstArmConsumesUnsplitEightDotRequest() throws Exception {
        for (HardwareProfile profile : NATIVE_PROFILES) {
            PlayerInputHub hubA = new PlayerInputHub();
            PlayerInputHub hubB = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle sourceA = hubA.openSource(0);
                    PlayerInputHub.SourceHandle sourceB = hubB.openSource(0);
                    Gameboy scalar = session(hubA, profile);
                    Gameboy candidate = session(hubB, profile)) {
                prepare(scalar);
                prepare(candidate);
                setHubTick(scalar, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                setHubTick(candidate, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                var checkpoint = candidate.captureStateWithoutTimeSource();
                int witness = (int) method("tryPerformanceLcdcWriteReplayEpoch")
                        .invoke(candidate, 54L);
                assertEquals(profile.id() + " pure input-only preflight rejects", 0, witness);
                assertEquals(profile.id() + " pure preflight arms eight-dot witness",
                        POLL_DISTANCE, pollTicks(candidate));
                assertStateEquals(profile.id() + " pure preflight is state-free", checkpoint,
                        candidate.captureStateWithoutTimeSource());
                hintField().setBoolean(candidate, false);
                pollField(candidate).setInt(candidate, 0);
                assertStateEquals(profile.id() + " reset transient witness", checkpoint,
                        candidate.captureStateWithoutTimeSource());
                armRetryOnly(candidate);
                candidate.setPerformanceBatchingEnabled(true);
                long replayBefore = candidate.getPerformanceLcdcWriteReplayTicks();

                // A single eight-dot request gives the first strict preflight enough budget to
                // discover the seven-dot prefix and arm the deadline itself. The first scalar
                // dot must be consumed in that same scheduler iteration.
                scalar.runTicks(POLL_DISTANCE);
                candidate.runTicks(POLL_DISTANCE);
                assertStateEquals(profile.id() + " first-arm prefix",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertTrue(profile.id() + " first-arm retry survives the poll boundary", hint(candidate));
                assertEquals("first-arm consumes the complete deadline", 0, pollTicks(candidate));
                assertEquals("first-arm prefix cannot run the strict owner", replayBefore,
                        candidate.getPerformanceLcdcWriteReplayTicks());

                // The next call is a fresh owner attempt. It may admit a packet only after
                // Joypad's new horizon is re-read; the old eight-dot permission is never reused.
                scalar.runTicks(54);
                candidate.runTicks(54);
                assertStateEquals(profile.id() + " fresh strict post-poll owner",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertTrue("fresh owner should exercise the existing LCDC route",
                        candidate.getPerformanceLcdcWriteReplayTicks() > replayBefore);
            }
        }
    }

    @Test
    public void preArmedDeadlineSurvivesThreePlusFivePartition() throws Exception {
        for (HardwareProfile profile : NATIVE_PROFILES) {
            PlayerInputHub hubA = new PlayerInputHub();
            PlayerInputHub hubB = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle sourceA = hubA.openSource(0);
                    PlayerInputHub.SourceHandle sourceB = hubB.openSource(0);
                    Gameboy scalar = session(hubA, profile);
                    Gameboy candidate = session(hubB, profile)) {
                prepare(scalar);
                prepare(candidate);
                setHubTick(scalar, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                setHubTick(candidate, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                armRetryThroughPrivatePreflight(candidate);
                candidate.setPerformanceBatchingEnabled(true);
                long replayBefore = candidate.getPerformanceLcdcWriteReplayTicks();

                // This separate test starts with a genuinely armed deadline. Splitting the
                // prefix proves that scheduler-call boundaries do not reset its exact count.
                scalar.runTicks(3);
                candidate.runTicks(3);
                assertStateEquals(profile.id() + " pre-armed 3-dot prefix",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertTrue("retry affinity remains through the first prefix partition", hint(candidate));
                assertEquals("three dots leave five poll dots", POLL_DISTANCE - 3, pollTicks(candidate));
                assertEquals("poll prefix cannot run the strict owner", replayBefore,
                        candidate.getPerformanceLcdcWriteReplayTicks());
                scalar.runTicks(5);
                candidate.runTicks(5);
                assertStateEquals(profile.id() + " pre-armed 5-dot suffix",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertTrue("hint survives only to the fresh post-poll attempt", hint(candidate));
                assertEquals("pre-armed deadline is exhausted", 0, pollTicks(candidate));

                scalar.runTicks(54);
                candidate.runTicks(54);
                assertStateEquals(profile.id() + " pre-armed fresh owner",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertTrue("fresh owner should exercise the existing LCDC route",
                        candidate.getPerformanceLcdcWriteReplayTicks() > replayBefore);
            }
        }
    }

    @Test
    public void timerDeadlineDoesNotArmPollOnlyHint() throws Exception {
        for (HardwareProfile profile : NATIVE_PROFILES) {
            PlayerInputHub hub = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle source = hub.openSource(0);
                    Gameboy gameboy = session(hub, profile)) {
                prepare(gameboy);
                setHubTick(gameboy, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                Timer timer = (Timer) field(gameboy, "timer");
                timer.presetDiv(0x000f);
                timer.setByte(0xff05, 0xff);
                timer.setByte(0xff07, 0x05);
                timer.tick();
                hintField().setBoolean(gameboy, true);
                int result = (int) method("tryPerformanceEpochOrDetailedReplay")
                        .invoke(gameboy, 54L);
                assertEquals(profile.id() + " timer is the independent short fence", 0, result);
                assertFalse(profile.id() + " timer rejection cannot arm an input-only deadline",
                        hint(gameboy));
                assertEquals(profile.id() + " timer rejection clears deadline", 0, pollTicks(gameboy));
            }
        }
    }

    @Test
    public void inputMutationExpiresSeamBeforeFreshOwner() throws Exception {
        for (HardwareProfile profile : NATIVE_PROFILES) {
            PlayerInputHub hubA = new PlayerInputHub();
            PlayerInputHub hubB = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle sourceA = hubA.openSource(0);
                    PlayerInputHub.SourceHandle sourceB = hubB.openSource(0);
                    Gameboy scalar = session(hubA, profile);
                    Gameboy candidate = session(hubB, profile)) {
                prepare(scalar);
                prepare(candidate);
                setHubTick(scalar, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                setHubTick(candidate, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                armRetryOnly(candidate);
                sourceA.update(Set.of(Button.RIGHT));
                sourceB.update(Set.of(Button.RIGHT));
                candidate.setPerformanceBatchingEnabled(true);
                scalar.runTicks(POLL_DISTANCE);
                candidate.runTicks(POLL_DISTANCE);
                assertStateEquals(profile.id() + " input mutation at poll",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                assertFalse(profile.id() + " host mutation revokes retry affinity", hint(candidate));
                assertEquals(profile.id() + " host mutation clears deadline", 0, pollTicks(candidate));
            }
        }
    }

    @Test
    public void restoreDiscardsPollDeadlineAndKeepsCanonicalContinuation() throws Exception {
        for (HardwareProfile profile : NATIVE_PROFILES) {
            PlayerInputHub hubA = new PlayerInputHub();
            PlayerInputHub hubB = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle sourceA = hubA.openSource(0);
                    PlayerInputHub.SourceHandle sourceB = hubB.openSource(0);
                    Gameboy scalar = session(hubA, profile);
                    Gameboy candidate = session(hubB, profile)) {
                prepare(scalar);
                prepare(candidate);
                setHubTick(scalar, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                setHubTick(candidate, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
                armRetryOnly(scalar);
                armRetryOnly(candidate);
                pollField(scalar).setInt(scalar, POLL_DISTANCE);
                pollField(candidate).setInt(candidate, POLL_DISTANCE);
                var state = scalar.captureStateWithoutTimeSource();
                scalar.restoreStateSilently(state);
                candidate.restoreStateSilently(state);
                candidate.setPerformanceBatchingEnabled(true);
                assertFalse(profile.id() + " restore clears scalar retry affinity", hint(scalar));
                assertEquals(profile.id() + " restore clears scalar poll deadline", 0,
                        pollTicks(scalar));
                assertFalse(profile.id() + " restore clears retry affinity", hint(candidate));
                assertEquals(profile.id() + " restore clears poll deadline", 0, pollTicks(candidate));
                scalar.runTicks(128);
                candidate.runTicks(128);
                assertStateEquals(profile.id() + " restored canonical continuation",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
            }
        }
    }

    private static void armRetryOnly(Gameboy gameboy) throws Exception {
        setHubTick(gameboy, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
        hintField().setBoolean(gameboy, true);
        assertEquals("retry-only seed has no pre-armed deadline", 0, pollTicks(gameboy));
    }

    private static void armRetryThroughPrivatePreflight(Gameboy gameboy) throws Exception {
        setHubTick(gameboy, HUB_POLL_TICK_RESIDUE_BEFORE_SEAM);
        hintField().setBoolean(gameboy, true);
        int result = (int) method("tryPerformanceLcdcWriteReplayEpoch")
                .invoke(gameboy, 54L);
        assertEquals("strict owner must defer to the input poll", 0, result);
        assertTrue("poll-only failure retains affinity", hint(gameboy));
        assertEquals("private preflight arms the full poll deadline", POLL_DISTANCE,
                pollTicks(gameboy));
    }

    private static void prepare(Gameboy gameboy) throws Exception {
        GameboyLcdcWritePacketTest.prepare(gameboy, 300);
        gameboy.setPerformanceBatchingEnabled(false);
    }

    private static void settle(Gameboy gameboy) {
        gameboy.runTicks(32);
    }

    private static Gameboy session(PlayerInputSource source) throws Exception {
        return session(source, HardwareProfileRegistry.CGB);
    }

    private static Gameboy session(PlayerInputSource source, HardwareProfile profile)
            throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        int pc = 0x150;
        int[] loop = {0x3e, 0x93, 0xe0, 0x40, 0x3e, 0xb3, 0xe0, 0x40, 0xc3, 0x50, 1};
        for (int opcode : loop) {
            image[pc++] = (byte) opcode;
        }
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(source)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .build();
    }

    private static Joypad joypad(Gameboy gameboy) throws Exception {
        return (Joypad) field(gameboy, "joypad");
    }

    private static void setHubTick(Gameboy gameboy, long tick) throws Exception {
        set(joypad(gameboy), "tick", tick);
    }

    private static boolean hint(Gameboy gameboy) throws Exception {
        return hintField().getBoolean(gameboy);
    }

    private static int pollTicks(Gameboy gameboy) throws Exception {
        return (int) field(gameboy, "performanceLcdcWriteReplayPollTicks");
    }

    private static Field pollField(Gameboy gameboy) throws Exception {
        Field field = Gameboy.class.getDeclaredField("performanceLcdcWriteReplayPollTicks");
        field.setAccessible(true);
        return field;
    }

    private static Field hintField() throws Exception {
        Field field = Gameboy.class.getDeclaredField("performanceLcdcWriteReplayRetry");
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name) throws Exception {
        Method method = Gameboy.class.getDeclaredMethod(name, long.class);
        method.setAccessible(true);
        return method;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
