package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sgb.Background;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * O1's test-only duplicate-reference timeline oracle.
 *
 * <p>This deliberately does not add an emulator hook. It drives two ordinary, service-free
 * {@link Gameboy} instances with the same generated cartridge and scalar action stream. Event
 * payloads are hashed synchronously at the event boundary and are never retained. Checkpoints
 * are kept only inside one run while the restore action is exercised; they are deep-owned machine
 * states, not {@link eu.rekawek.coffeegb.core.state.MachineStateCapture} views.</p>
 */
public final class SyntheticTimelineRecorderTest {

    private static final long TRACE_TICKS = 72_500;

    private static final int MAX_TIMELINE_EVENTS = 4_096;

    private static final int CHECKPOINT_ID = 1;

    private static final int FAULT_TICK = 317;

    @Test
    public void repeatedReferenceTracesMatchForEveryRequiredProfileRow() throws Exception {
        for (Row row : Row.values()) {
            DuplicateTrace duplicate = duplicate(row, Fault.NONE);
            Timeline first = duplicate.reference();
            Timeline second = duplicate.candidate();

            assertNull(row.id() + " repeated reference divergence",
                    firstDivergence(first, second));
            assertTrue(row.id() + " should exercise at least one checkpoint",
                    first.checkpointEvents() > 0);
            assertTrue(row.id() + " should contain a machine event",
                    first.events().stream().anyMatch(event -> event.phase() == Phase.MACHINE));
            assertTrue(row.id() + " timeline must remain bounded",
                    first.events().size() <= MAX_TIMELINE_EVENTS);
            assertTrue(row.id() + " candidate timeline must remain bounded",
                    second.events().size() <= MAX_TIMELINE_EVENTS);
        }
    }

    @Test
    public void checkpointsRestoreTheDeepOwnedMachineAndExternalInputBaseline() throws Exception {
        for (Row row : Row.values()) {
            DuplicateTrace duplicate = duplicate(row, Fault.NONE);
            Timeline restored = duplicate.reference();
            Timeline repeated = duplicate.candidate();

            // The scenario saves before it mutates timer, PPU, mapper, serial, and input state,
            // then restores before the final action block. The complete trace is therefore also
            // a continuation check for every service-free checkpoint restore.
            assertNull(row.id() + " checkpoint continuation divergence",
                    firstDivergence(restored, repeated));
            assertTrue(row.id() + " must contain a restore action",
                    restored.restoreEvents() > 0);
            assertTrue(row.id() + " must restore the external action cursor",
                    restored.events().stream().anyMatch(event ->
                            event.kind().equals("restore-cursor") && event.payloadLength() > 0));
        }
    }

    @Test
    public void sameTickOrdinalsAreContiguousAndObservable() throws Exception {
        Timeline timeline = run(Row.DMG, Fault.NONE);
        Map<Long, Integer> nextOrdinal = new HashMap<>();
        Map<Long, Phase> lastPhase = new HashMap<>();
        boolean sawMultipleEventsAtOneTick = false;
        for (TimelineEvent event : timeline.events()) {
            int expected = nextOrdinal.getOrDefault(event.tick(), 0);
            assertEquals("same-tick event ordinal at tick " + event.tick(), expected,
                    event.ordinal());
            Phase previousPhase = lastPhase.get(event.tick());
            if (previousPhase != null) {
                assertTrue("same-tick phases must be monotonic at tick " + event.tick(),
                        event.phase().ordinal() >= previousPhase.ordinal());
            }
            if (expected > 0) {
                sawMultipleEventsAtOneTick = true;
            }
            nextOrdinal.put(event.tick(), expected + 1);
            lastPhase.put(event.tick(), event.phase());
        }
        assertTrue("the fixture must exercise same-tick ordering", sawMultipleEventsAtOneTick);
    }

    @Test
    public void selectedBoundaryCoverageIsAssertedPerProfile() throws Exception {
        for (Row row : Row.values()) {
            Timeline timeline = run(row, Fault.NONE);
            assertHasKind(row, timeline, "mmio-read");
            assertHasKind(row, timeline, "audio-sample");
            if (row.nativeCgb() || row == Row.CGB_COMPAT) {
                assertHasKind(row, timeline, "gbc-frame-ready");
                if (row.nativeCgb()) {
                    assertHasKind(row, timeline, "hdma-status-read");
                    TimelineEvent hdmaDestination = timeline.events().stream()
                            .filter(event -> event.kind().equals("hdma-destination-read"))
                            .findFirst().orElse(null);
                    assertNotNull(row.id() + " must expose the copied HDMA destination byte",
                            hdmaDestination);
                    assertEquals(row.id() + " HDMA destination byte " + timeline.events().stream()
                                    .filter(event -> event.kind().startsWith("hdma-"))
                                    .toList(),
                            0xa0, hdmaDestination.value());
                    assertTrue(row.id() + " must expose the synthetic HDMA source byte",
                            timeline.events().stream().anyMatch(event ->
                                    event.kind().equals("hdma-source-read")
                                            && event.address() == 0xc100
                                            && event.value() == 0xa0));
                    assertTrue(row.id() + " must expose terminal FF55 after HDMA completion",
                            timeline.events().stream().anyMatch(event ->
                                    event.kind().equals("hdma-status-read")
                                            && event.address() == 0xff55
                                            && event.value() == 0xff));
                    assertTrue(row.id() + " must execute STOP and enter double speed",
                            timeline.events().stream().anyMatch(event ->
                                    event.kind().equals("speed-switch-transition")
                                            && event.value() == 2));
                }
            } else if (row.sgb()) {
                assertHasKind(row, timeline, "sgb-frame-ready");
                TimelineEvent packet = timeline.events().stream()
                        .filter(event -> event.kind().equals("sgb-packet-received"))
                        .findFirst().orElse(null);
                assertNotNull(row.id() + " must record a received SGB packet", packet);
                assertEquals(row.id() + " packet callback phase", Phase.BEFORE_TICK,
                        packet.phase());
                assertEquals(row.id() + " packet callback origin", Origin.CPU,
                        packet.origin());
                TimelineEvent packetTrigger = timeline.events().stream()
                        .filter(event -> event.tick() == packet.tick()
                                && event.kind().equals("mmio-write")
                                && event.address() == 0xff00)
                        .findFirst().orElse(null);
                assertNotNull(row.id() + " packet must have a same-tick JOYP trigger",
                        packetTrigger);
                assertTrue(row.id() + " packet callback must precede its trigger record",
                        packet.ordinal() < packetTrigger.ordinal());
                assertTrue(row.id() + " must accept the SGB packet through its public status",
                        timeline.events().stream().anyMatch(event ->
                                event.kind().equals("sgb-multiplayer-status")
                                        && event.value() == 3));
            } else {
                assertHasKind(row, timeline, "dmg-frame-ready");
            }
        }
    }

    @Test
    public void unsupportedFingerprintShapeFailsClosed() {
        try {
            visitRecordGraph(new Object(), new Digest(), new IdentityHashMap<>());
            throw new AssertionError("unsupported state shape was silently accepted");
        } catch (UnsupportedStateShape expected) {
            assertTrue(expected.getMessage().contains("java.lang.Object"));
        }
    }

    private static void assertHasKind(Row row, Timeline timeline, String kind) {
        assertTrue(row.id() + " must record " + kind,
                timeline.events().stream().anyMatch(event -> event.kind().equals(kind)));
    }

    @Test
    public void candidateFaultIsReportedAtTheFirstTimestampAndOrdinal() throws Exception {
        for (Row row : Row.values()) {
            DuplicateTrace duplicate = duplicate(row, Fault.candidateWrite(FAULT_TICK));
            Timeline reference = duplicate.reference();
            Timeline candidate = duplicate.candidate();

            Divergence divergence = firstDivergence(reference, candidate);
            assertNotNull(row.id() + " candidate fault must be visible", divergence);
            assertEquals(row.id() + " candidate fault timestamp", FAULT_TICK,
                    divergence.actual().tick());
            assertEquals("the fault must surface through the ordinary MMIO oracle",
                    Origin.DEBUGGER, divergence.actual().origin());
            assertEquals("mmio-read", divergence.actual().kind());
            assertEquals(0xff00, divergence.actual().address());
            assertEquals(Phase.AFTER_TICK, divergence.actual().phase());
            assertEquals(divergence.expected().phase(), divergence.actual().phase());
            assertEquals(divergence.expected().ordinal(), divergence.actual().ordinal());
            assertTrue("candidate fault must change the observed JOYP value",
                    divergence.expected().value() != divergence.actual().value());
            assertEquals("the injected event must be the first mismatch",
                    divergence.expectedIndex(), divergence.actualIndex());
        }
    }

    /** Required rows are intentionally separate even when two rows share a core family. */
    private enum Row {
        DMG("dmg", HardwareProfileRegistry.DMG, false, false),
        MGB("mgb", HardwareProfileRegistry.MGB, false, false),
        CGB("cgb", HardwareProfileRegistry.CGB, true, false),
        CGB0("cgb0", HardwareProfileRegistry.CGB0, true, false),
        CGB_COMPAT("cgb-compat", HardwareProfileRegistry.CGB, false, false),
        SGB("sgb", HardwareProfileRegistry.SGB, false, true),
        SGB2("sgb2", HardwareProfileRegistry.SGB2, false, true);

        private final String id;
        private final HardwareProfile hardwareProfile;
        private final boolean colorCartridge;
        private final boolean sgb;

        Row(String id, HardwareProfile hardwareProfile, boolean colorCartridge, boolean sgb) {
            this.id = id;
            this.hardwareProfile = hardwareProfile;
            this.colorCartridge = colorCartridge;
            this.sgb = sgb;
        }

        String id() {
            return id;
        }

        HardwareProfile hardwareProfile() {
            return hardwareProfile;
        }

        boolean colorCartridge() {
            return colorCartridge;
        }

        boolean sgb() {
            return sgb;
        }

        boolean nativeCgb() {
            return hardwareProfile.capabilities().cgbMode() && colorCartridge;
        }
    }

    private enum Phase {
        BEFORE_TICK,
        MACHINE,
        AFTER_TICK
    }

    private enum Origin {
        FIXTURE,
        CPU,
        DMA,
        BOOT,
        DEBUGGER,
        INPUT,
        STATE,
        INJECTED_CANDIDATE
    }

    private enum ActionKind {
        WRITE,
        READ,
        INPUT,
        CHECKPOINT,
        RESTORE
    }

    private record Action(
            long tick,
            Phase phase,
            Origin origin,
            ActionKind kind,
            int address,
            int value,
            int auxiliary) {

        private Action {
            if (tick < 0) {
                throw new IllegalArgumentException("Action tick must not be negative");
            }
            if (phase == Phase.MACHINE) {
                throw new IllegalArgumentException("Actions cannot use the machine phase");
            }
            if (kind == ActionKind.WRITE && (address < 0 || address > 0xffff)) {
                throw new IllegalArgumentException("MMIO write address is outside the bus");
            }
            if (kind == ActionKind.READ && (address < 0 || address > 0xffff)) {
                throw new IllegalArgumentException("MMIO read address is outside the bus");
            }
        }

        static Action write(long tick, Phase phase, Origin origin, int address, int value) {
            return new Action(tick, phase, origin, ActionKind.WRITE, address, value & 0xff, 0);
        }

        static Action read(long tick, Phase phase, Origin origin, int address) {
            return new Action(tick, phase, origin, ActionKind.READ, address, 0, 0);
        }

        static Action input(long tick, Phase phase, int buttonMask) {
            return new Action(tick, phase, Origin.INPUT, ActionKind.INPUT, -1,
                    buttonMask & 0xff, 0);
        }

        static Action checkpoint(long tick) {
            return new Action(tick, Phase.AFTER_TICK, Origin.STATE, ActionKind.CHECKPOINT,
                    -1, CHECKPOINT_ID, 0);
        }

        static Action restore(long tick) {
            return new Action(tick, Phase.BEFORE_TICK, Origin.STATE, ActionKind.RESTORE,
                    -1, CHECKPOINT_ID, 0);
        }
    }

    /** Compact event value: no frame, audio, packet, ROM, or save payload is retained. */
    private record TimelineEvent(
            long tick,
            Phase phase,
            Origin origin,
            String kind,
            int address,
            int value,
            long payloadHash,
            int payloadLength,
            int ordinal) {
    }

    private record Timeline(List<TimelineEvent> events, int checkpointEvents, int restoreEvents) {
        private Timeline {
            events = List.copyOf(events);
        }
    }

    private record DuplicateTrace(Timeline reference, Timeline candidate) {
    }

    private record Fault(long candidateWriteTick) {
        static final Fault NONE = new Fault(-1);

        static Fault candidateWrite(long tick) {
            return new Fault(tick);
        }
    }

    private record Divergence(
            int expectedIndex,
            int actualIndex,
            TimelineEvent expected,
            TimelineEvent actual) {
    }

    /**
     * Owner-thread action cursor. A restore rewinds this cursor, replays the finite interval from
     * the checkpoint, and then advances past the restore control action. This makes the external
     * cursor part of the continuation rather than a passive value in the checkpoint.
     */
    private static final class ActionRunner {
        private final List<Action> actions;
        private int actionIndex;

        private ActionRunner(List<Action> actions) {
            this.actions = actions;
        }

        private int cursor() {
            return actionIndex;
        }

        private boolean hasActionAt(long tick, Phase phase) {
            if (actionIndex >= actions.size()) {
                return false;
            }
            Action next = actions.get(actionIndex);
            if (next.tick() < tick) {
                throw new IllegalStateException("Action cursor fell behind tick " + tick
                        + " at action " + actionIndex + ": " + next);
            }
            return next.tick() == tick && next.phase() == phase;
        }

        private Action take() {
            if (actionIndex >= actions.size()) {
                throw new IllegalStateException("Action cursor is past the script");
            }
            return actions.get(actionIndex++);
        }

        private void restoreCursor(int cursor) {
            if (cursor < 0 || cursor > actions.size()) {
                throw new IllegalArgumentException("Invalid action cursor: " + cursor);
            }
            actionIndex = cursor;
        }

        private long replayFromCheckpoint(
                Checkpoint checkpoint,
                long targetTick,
                int targetCursor,
                Fault fault,
                Gameboy gameboy,
                EventRecorder recorder,
                Map<Integer, Checkpoint> checkpoints) {
            int restoreIndex = targetCursor - 1;
            if (restoreIndex < 0 || restoreIndex >= actions.size()
                    || actions.get(restoreIndex).kind() != ActionKind.RESTORE) {
                throw new IllegalStateException("Restore cursor does not end at its control action");
            }
            if (checkpoint.tick() >= targetTick) {
                throw new IllegalStateException("Restore target is not after checkpoint");
            }

            restoreCursor(checkpoint.actionCursor());
            int restoredCursor = cursor();
            if (restoredCursor != checkpoint.actionCursor()) {
                throw new IllegalStateException("Runner did not restore the checkpoint cursor");
            }
            recorder.suspend();
            try {
                for (long replayTick = checkpoint.tick() + 1;
                    replayTick < targetTick; replayTick++) {
                    replayPhase(replayTick, Phase.BEFORE_TICK, restoreIndex,
                            fault, gameboy, recorder, checkpoints);
                    if (fault.candidateWriteTick() == replayTick) {
                        gameboy.getAddressSpace().setByte(0xff00, 0x20);
                    }
                    gameboy.tick();
                    replayPhase(replayTick, Phase.AFTER_TICK, restoreIndex,
                            fault, gameboy, recorder, checkpoints);
                }
            } finally {
                recorder.resume();
            }
            if (cursor() != restoreIndex) {
                throw new IllegalStateException("Checkpoint replay did not reach restore action: "
                        + cursor() + " != " + restoreIndex);
            }
            restoreCursor(targetCursor);
            return fingerprint(gameboy, targetCursor);
        }

        private void replayPhase(
                long tick,
                Phase phase,
                int stopCursor,
                Fault fault,
                Gameboy gameboy,
                EventRecorder recorder,
                Map<Integer, Checkpoint> checkpoints) {
            while (actionIndex < stopCursor && hasActionAt(tick, phase)) {
                Action action = take();
                if (action.kind() == ActionKind.CHECKPOINT
                        || action.kind() == ActionKind.RESTORE) {
                    throw new IllegalStateException("Nested state action in replay window: "
                            + action);
                }
                execute(action, this, cursor(), fault, gameboy, recorder, checkpoints);
            }
        }
    }

    private static DuplicateTrace duplicate(Row row, Fault fault) throws Exception {
        // Both sides are constructed from fresh copies of the generated image and consume the
        // same immutable action list. Keeping this small orchestration object explicit makes the
        // O1 reference/candidate boundary available to the later DIFFERENTIAL runner without
        // putting any hook or policy into production Gameboy.
        return new DuplicateTrace(run(row, Fault.NONE), run(row, fault));
    }

    private static Timeline run(Row row, Fault fault) throws Exception {
        List<Action> actions = actions(row);
        assertMonotonicActions(actions);
        byte[] generatedRom = generatedRom(row);
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(generatedRom))
                .setHardwareProfile(row.hardwareProfile())
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .setRtcTimeSource(() -> 0L)
                .setPlayerInputSource(eu.rekawek.coffeegb.core.joypad.PlayerInputSource.RELEASED)
                .setDisplaySgbBorder(false);

        // Do not expose generatedRom, Rom, or a checkpoint map through Timeline. The generated
        // machine-owned bytes die with this runner; only scalar events escape the recorder.
        Gameboy gameboy = configuration.build();
        EventRecorder recorder = new EventRecorder(row);
        EventBusImpl eventBus = installSynchronousRecorder(gameboy, recorder);
        Map<Integer, Checkpoint> checkpoints = new HashMap<>();
        ActionRunner runner = new ActionRunner(actions);
        int checkpointEvents = 0;
        int restoreEvents = 0;
        try {
            assertEffectiveProfile(row, gameboy);

            for (long tick = 0; tick < TRACE_TICKS; tick++) {
                recorder.beginTick(tick);
                if (tick == 0) {
                    recorder.record(0, Phase.BEFORE_TICK, Origin.STATE,
                            "profile:" + row.id(), -1,
                            gameboy.getSpeedMode().isDmgCompat() ? 1 : 0,
                            row.nativeCgb() ? 1 : 0, 0);
                }
                while (runner.hasActionAt(tick, Phase.BEFORE_TICK)) {
                    Action action = runner.take();
                    execute(action, runner, runner.cursor(), fault, gameboy, recorder, checkpoints);
                    if (action.kind() == ActionKind.RESTORE) {
                        restoreEvents++;
                    }
                }

                if (fault.candidateWriteTick() == tick) {
                    // Deliberately mutate a live JOYP selector, not an unused scratch byte. The
                    // ordinary AFTER_TICK FF00 read at this same timestamp is the first oracle
                    // mismatch; no synthetic fault marker can mask the machine divergence.
                    gameboy.getAddressSpace().setByte(0xff00, 0x20);
                }

                int speedBeforeTick = gameboy.getSpeedMode().getSpeedMode();
                boolean frameReady = gameboy.tick();
                int speedAfterTick = gameboy.getSpeedMode().getSpeedMode();
                if (speedAfterTick != speedBeforeTick) {
                    recorder.record(tick, Phase.MACHINE, Origin.STATE,
                            "speed-switch-transition", -1, speedAfterTick, speedBeforeTick, 0);
                }
                if (frameReady) {
                    recorder.record(tick, Phase.MACHINE, Origin.STATE,
                            "frame-ready-return", -1, 1, 0, 0);
                }
                if (row.sgb() && tick == 1_300) {
                    Joypad.SgbMultiplayerStatus status = gameboy.getSgbMultiplayerStatus();
                    recorder.record(tick, Phase.MACHINE, Origin.STATE,
                            "sgb-multiplayer-status", status.selectedPlayer(),
                            status.mode().control(), 0, 0);
                }

                while (runner.hasActionAt(tick, Phase.AFTER_TICK)) {
                    Action action = runner.take();
                    execute(action, runner, runner.cursor(), fault, gameboy, recorder, checkpoints);
                    if (action.kind() == ActionKind.CHECKPOINT) {
                        checkpointEvents++;
                    }
                }
            }

            // The tail read proves that immediate MMIO observations remain an explicit action,
            // not a delayed set comparison.
            recorder.beginTick(TRACE_TICKS);
            int stat = gameboy.getAddressSpace().getByte(0xff41);
            recorder.record(TRACE_TICKS, Phase.AFTER_TICK, Origin.FIXTURE,
                    "tail-mmio-read", 0xff41, stat, 0, 0);
            return new Timeline(recorder.events(), checkpointEvents, restoreEvents);
        } finally {
            gameboy.closeSilently();
            eventBus.close();
        }
    }

    private static EventBusImpl installSynchronousRecorder(
            Gameboy gameboy, EventRecorder recorder) {
        EventBusImpl eventBus = new EventBusImpl(null, "synthetic-o1-" + recorder.row.id(), false);
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, new eu.rekawek.coffeegb.core.debug.Console());

        eventBus.register(event -> recorder.recordArrayEvent(
                "gbc-frame-ready", event.pixels(), 0, 0), Display.GbcFrameReadyEvent.class);
        eventBus.register(event -> recorder.recordArrayEvent(
                "dmg-frame-ready", event.pixels(), event.lcdBlank() ? 1 : 0, 0),
                Display.DmgFrameReadyEvent.class);
        eventBus.register(event -> recorder.recordArrayEvent(
                "audio-sample", event.buffer(), event.clockSpec().hashCode(), 0),
                Sound.SoundSampleEvent.class);
        eventBus.register(event -> recorder.recordSynchronous(
                "audio-enabled", -1, event.enabled() ? 1 : 0, 0, 0),
                Sound.SoundEnabledEvent.class);
        eventBus.register(event -> recorder.recordArrayEvent(
                "vram-transfer-complete", event.buffer(), 0, 0),
                VRamTransfer.VRamTransferComplete.class);
        eventBus.register(event -> recorder.recordArrayEvent(
                "sgb-frame-ready", event.buffer(), event.includeBorder() ? 1 : 0, 0),
                SgbDisplay.SgbFrameReadyEvent.class);
        eventBus.register(event -> recorder.recordArrayEvent(
                "sgb-background-ready", event.buffer(), event.mask()),
                Background.SgbBackgroundReadyEvent.class);
        eventBus.register(event -> recorder.recordSynchronous(
                "joypad-press", event.button().ordinal(), (int) event.tick(), 0, 0),
                Joypad.JoypadPressEvent.class);
        if (recorder.row.sgb()) {
            registerSynchronousSgbRecorder(gameboy, recorder);
        }
        return eventBus;
    }

    /** Gameboy keeps the SGB transport bus private and separate from the host display bus. */
    private static void registerSynchronousSgbRecorder(Gameboy gameboy, EventRecorder recorder) {
        try {
            var field = Gameboy.class.getDeclaredField("sgbBus");
            if (!field.trySetAccessible()) {
                throw new AssertionError("SGB bus is not reflectively accessible to the test");
            }
            EventBus sgbBus = (EventBus) field.get(gameboy);
            sgbBus.register(event -> recorder.recordArrayEvent(
                    "sgb-packet-received", event.packet(), 0, 0),
                    SuperGameboy.PacketReceivedEvent.class);
            sgbBus.register(event -> recorder.recordSynchronous(
                    "sgb-packet-aborted", -1, 0, 0, 0),
                    SuperGameboy.PacketTransferAbortedEvent.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to attach the synchronous SGB recorder", e);
        }
    }

    private static void execute(
            Action action,
            ActionRunner runner,
            int actionCursor,
            Fault fault,
            Gameboy gameboy,
            EventRecorder recorder,
            Map<Integer, Checkpoint> checkpoints) {
        recorder.enterAction(action);
        try {
            switch (action.kind()) {
            case WRITE -> {
                if (action.origin() == Origin.CPU) {
                    gameboy.getAddressSpace().setByteFromCpu(action.address(), action.value());
                } else {
                    gameboy.getAddressSpace().setByte(action.address(), action.value());
                }
                recorder.record(action.tick(), action.phase(), action.origin(),
                        "mmio-write", action.address(), action.value(), 0, 0);
            }
            case READ -> {
                int value = gameboy.getAddressSpace().getByte(action.address());
                String kind = action.address() == 0xff55 ? "hdma-status-read"
                        : action.address() == 0x8000 ? "hdma-destination-read"
                        : action.address() == 0xc100 ? "hdma-source-read" : "mmio-read";
                recorder.record(action.tick(), action.phase(), action.origin(),
                        kind, action.address(), value, 0, 0);
            }
            case INPUT -> {
                gameboy.setPressedButtons(buttonsForMask(action.value()));
                recorder.record(action.tick(), action.phase(), action.origin(),
                        "input-transition", -1, action.value(), 0, 0);
            }
            case CHECKPOINT -> {
                Checkpoint checkpoint = Checkpoint.capture(gameboy, action.tick(), actionCursor);
                checkpoints.put(action.value(), checkpoint);
                recorder.record(action.tick(), action.phase(), action.origin(),
                        "checkpoint", -1, action.value(), checkpoint.fingerprint(), 0);
            }
            case RESTORE -> {
                Checkpoint checkpoint = checkpoints.get(action.value());
                if (checkpoint == null) {
                    throw new AssertionError("Restore action has no checkpoint: " + action.value());
                }
                long expectedContinuation = fingerprint(gameboy, actionCursor);
                checkpoint.restore(gameboy);
                long replayedContinuation = runner.replayFromCheckpoint(
                        checkpoint, action.tick(), actionCursor, fault,
                        gameboy, recorder, checkpoints);
                if (expectedContinuation != replayedContinuation) {
                    throw new AssertionError("checkpoint continuation expected "
                            + expectedContinuation + " actual " + replayedContinuation);
                }
                recorder.record(action.tick(), action.phase(), action.origin(),
                        "restore", -1, action.value(), checkpoint.fingerprint(),
                        checkpoint.actionCursor());
                recorder.record(action.tick(), action.phase(), action.origin(),
                        "restore-cursor", -1, checkpoint.actionCursor(), replayedContinuation,
                        actionCursor);
            }
            }
        } finally {
            recorder.exitAction(action);
        }
    }

    private static void assertEffectiveProfile(Row row, Gameboy gameboy) {
        assertEquals(row.id() + " requested profile", row.hardwareProfile().id(),
                gameboy.getHardwareProfile().id());
        boolean expectedCompat = row == Row.CGB_COMPAT;
        assertEquals(row.id() + " effective DMG compatibility", expectedCompat,
                gameboy.getSpeedMode().isDmgCompat());
        assertEquals(row.id() + " profile family", row.hardwareProfile().family(),
                gameboy.getHardwareProfile().family());
        boolean expectedGpuCgb = row.hardwareProfile().capabilities().cgbMode();
        assertEquals(row.id() + " effective GPU CGB mode", expectedGpuCgb,
                gameboy.getGpu().isGbc());
        assertEquals(row.id() + " effective GPU DMG-compat mode", expectedCompat,
                gameboy.getGpu().isDmgCompatMode());
    }

    private static List<Action> actions(Row row) {
        List<Action> actions = new ArrayList<>();
        add(actions, Action.write(0, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff40, 0x91));
        add(actions, Action.write(1, Phase.BEFORE_TICK, Origin.CPU, 0xff07, 0x05));
        add(actions, Action.write(1, Phase.BEFORE_TICK, Origin.CPU, 0xff06, 0xa5));
        add(actions, Action.write(1, Phase.BEFORE_TICK, Origin.CPU, 0xff05, 0xfe));
        add(actions, Action.read(1, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff04));
        add(actions, Action.read(2, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff05));

        // STAT/PPU writes cross mode-2, mode-3, and line-boundary observations.
        add(actions, Action.write(8, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff41, 0x78));
        add(actions, Action.write(8, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff42, 0x17));
        add(actions, Action.write(8, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff43, 0x03));
        add(actions, Action.write(9, Phase.BEFORE_TICK, Origin.CPU, 0xff4a, 0x10));
        add(actions, Action.write(9, Phase.BEFORE_TICK, Origin.CPU, 0xff4b, 0x07));
        add(actions, Action.write(10, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff45, 0x40));
        add(actions, Action.read(10, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff41));

        // APU trigger/length/frame-sequencer edges and an internal serial transfer.
        add(actions, Action.write(16, Phase.BEFORE_TICK, Origin.CPU, 0xff26, 0x80));
        add(actions, Action.write(16, Phase.BEFORE_TICK, Origin.CPU, 0xff24, 0x77));
        add(actions, Action.write(16, Phase.BEFORE_TICK, Origin.CPU, 0xff25, 0xf3));
        add(actions, Action.write(17, Phase.BEFORE_TICK, Origin.CPU, 0xff10, 0x16));
        add(actions, Action.write(17, Phase.BEFORE_TICK, Origin.CPU, 0xff11, 0x80));
        add(actions, Action.write(17, Phase.BEFORE_TICK, Origin.CPU, 0xff12, 0xf3));
        add(actions, Action.write(18, Phase.BEFORE_TICK, Origin.CPU, 0xff13, 0x40));
        add(actions, Action.write(18, Phase.BEFORE_TICK, Origin.CPU, 0xff14, 0xc3));
        add(actions, Action.write(24, Phase.BEFORE_TICK, Origin.CPU, 0xff01, 0x5a));
        add(actions, Action.write(24, Phase.BEFORE_TICK, Origin.CPU, 0xff02, 0x81));
        add(actions, Action.read(25, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff02));

        // Service-free MBC3 clock latch/register read. Battery persistence remains disabled.
        add(actions, Action.write(32, Phase.BEFORE_TICK, Origin.CPU, 0x0000, 0x0a));
        add(actions, Action.write(32, Phase.BEFORE_TICK, Origin.CPU, 0x4000, 0x08));
        add(actions, Action.write(32, Phase.BEFORE_TICK, Origin.CPU, 0x6000, 0x00));
        add(actions, Action.read(32, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xa000));
        if (row.hardwareProfile().capabilities().cgbMode() && row != Row.CGB_COMPAT) {
            // Native CGB and CGB0 exercise the CGB-only DMA/speed registers. Compatibility mode
            // deliberately skips these accesses because those registers are unavailable there.
            for (int i = 0; i < 0x10; i++) {
                add(actions, Action.write(36, Phase.BEFORE_TICK, Origin.FIXTURE,
                        0xc100 + i, 0xa0 ^ i));
            }
            add(actions, Action.write(37, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff51, 0xc1));
            add(actions, Action.write(37, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff52, 0x00));
            add(actions, Action.write(37, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff53, 0x80));
            add(actions, Action.write(37, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff54, 0x00));
            add(actions, Action.read(37, Phase.AFTER_TICK, Origin.DEBUGGER, 0xc100));
            // Turn the LCD off before starting a one-block general VRAM DMA. This is the
            // deterministic HDMA register path: the block completes without waiting for an
            // HBlank, so FF55's terminal value and the copied destination byte are observable
            // even in this short synthetic trace.
            add(actions, Action.write(38, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff40, 0x00));
            add(actions, Action.write(39, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff55, 0x00));
            add(actions, Action.read(90, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff55));
            add(actions, Action.write(94, Phase.BEFORE_TICK, Origin.CPU, 0xff4d, 0x01));
            add(actions, Action.read(95, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff4d));
        }

        add(actions, Action.checkpoint(100));

        // OAM DMA is available on all profiles. The source bytes are synthetic machine-owned
        // WRAM values and are not part of the compact recorder output.
        for (int i = 0; i < 0x10; i++) {
            add(actions, Action.write(110, Phase.BEFORE_TICK, Origin.FIXTURE,
                    0xc000 + i, 0x40 + i * 3));
        }
        add(actions, Action.write(111, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff46, 0xc0));
        add(actions, Action.read(112, Phase.AFTER_TICK, Origin.DEBUGGER, 0xfe00));

        add(actions, Action.input(300, Phase.BEFORE_TICK, 1 << Button.A.ordinal()));
        add(actions, Action.read(301, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff00));
        add(actions, Action.input(316, Phase.BEFORE_TICK,
                (1 << Button.START.ordinal()) | (1 << Button.RIGHT.ordinal())));
        add(actions, Action.read(317, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff00));
        add(actions, Action.input(332, Phase.BEFORE_TICK, 0));

        // The restore follows several mutations, including input and DMA state. The action
        // cursor is captured in the immutable checkpoint and is visible on the restore event.
        add(actions, Action.restore(900));
        add(actions, Action.read(901, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff04));
        add(actions, Action.read(902, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff41));
        if (row.nativeCgb()) {
            // Turn the LCD off before observing the VRAM destination so the read is not hidden
            // by a mode-3 lock. The one-block transfer must have completed before this point.
            add(actions, Action.write(920, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff40, 0x00));
            add(actions, Action.read(921, Phase.AFTER_TICK, Origin.DEBUGGER, 0x8000));
            add(actions, Action.read(922, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff55));
            add(actions, Action.write(923, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff40, 0x91));
        }

        if (row.sgb()) {
            addSgbPacket(actions, 1_000);
        }
        add(actions, Action.read(1_400, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff0f));
        add(actions, Action.write(1_401, Phase.BEFORE_TICK, Origin.DEBUGGER, 0xff0f, 0x00));
        add(actions, Action.read(1_402, Phase.AFTER_TICK, Origin.DEBUGGER, 0xff0f));
        return List.copyOf(actions);
    }

    private static void addSgbPacket(List<Action> actions, long startTick) {
        long tick = startTick;
        add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x30));
        add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x00));
        add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x30));
        // A valid one-packet MLT_REQ command (command 0x11, packet count 1, four players).
        // Generate each bit directly so no packet array is ever retained by the recorder.
        int header = (0x11 << 3) | 1;
        int multiplayerControl = 3;
        for (int bit = 0; bit < 128; bit++) {
            int byteIndex = bit / 8;
            int bitIndex = bit % 8;
            int source = byteIndex == 0 ? header
                    : byteIndex == 1 ? multiplayerControl : 0;
            int selector = ((source >>> bitIndex) & 1) != 0 ? 0x10 : 0x20;
            add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, selector));
            add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x30));
        }
        add(actions, Action.write(tick++, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x20));
        add(actions, Action.write(tick, Phase.BEFORE_TICK, Origin.CPU, 0xff00, 0x30));
    }

    private static void add(List<Action> actions, Action action) {
        actions.add(action);
    }

    private static void assertMonotonicActions(List<Action> actions) {
        long previous = -1;
        int previousPhase = -1;
        for (Action action : actions) {
            assertTrue("action timestamps must be monotonic", action.tick() >= previous);
            if (action.tick() == previous) {
                assertTrue("same-tick actions must follow BEFORE/MACHINE/AFTER phases",
                        action.phase().ordinal() >= previousPhase);
            } else {
                previousPhase = -1;
            }
            previous = action.tick();
            previousPhase = action.phase().ordinal();
        }
    }

    private static byte[] generatedRom(Row row) {
        byte[] rom = new byte[0x8000];
        rom[0x143] = (byte) (row.colorCartridge() ? 0x80 : 0x00);
        rom[0x146] = (byte) (row.sgb() ? 0x03 : 0x00);
        rom[0x147] = 0x11; // MBC3 + timer, no battery; the test supplies a fixed TimeSource.
        rom[0x148] = 0x00; // 32 KiB, enough for the generated bank-0 program.
        rom[0x149] = 0x00; // MBC3 allocates its deterministic internal timer window.
        rom[0x134] = 'S';
        rom[0x135] = 'Y';
        rom[0x136] = 'N';
        rom[0x137] = 'T';
        rom[0x138] = 'H';
        rom[0x139] = 'O';
        rom[0x13a] = '1';

        Bytecode code = new Bytecode(rom, 0x100);
        code.byteValue(0x31).byteValue(0xf0).byteValue(0xdf); // LD SP,DFF0
        code.ldh(0x40, 0x91);
        code.ldh(0x07, 0x05);
        code.ldh(0x06, 0xa5);
        code.ldh(0x05, 0xfe);
        code.ldh(0x26, 0x80);
        code.ldh(0x24, 0x77);
        code.ldh(0x25, 0xf3);
        code.ldh(0x01, 0x5a);
        code.ldh(0x02, 0x81);
        if (row.hardwareProfile().capabilities().cgbMode() && row != Row.CGB_COMPAT) {
            code.ldh(0x4d, 0x01);
            code.byteValue(0x10).byteValue(0x00); // STOP: reaches the CGB speed-switch seam.
        }
        int loop = code.position();
        code.byteValue(0xf0).byteValue(0x00); // LDH A,(00): stable joypad polling.
        code.byteValue(0x3c); // INC A
        code.byteValue(0x18);
        code.byteValue(loop - (code.position() + 1));
        return rom;
    }

    private static Set<Button> buttonsForMask(int mask) {
        EnumSet<Button> result = EnumSet.noneOf(Button.class);
        for (Button button : Button.values()) {
            if ((mask & (1 << button.ordinal())) != 0) {
                result.add(button);
            }
        }
        return result;
    }

    private static Divergence firstDivergence(Timeline expected, Timeline actual) {
        int common = Math.min(expected.events().size(), actual.events().size());
        for (int i = 0; i < common; i++) {
            TimelineEvent left = expected.events().get(i);
            TimelineEvent right = actual.events().get(i);
            if (!left.equals(right)) {
                return new Divergence(i, i, left, right);
            }
        }
        if (expected.events().size() == actual.events().size()) {
            return null;
        }
        TimelineEvent missing = expected.events().size() > common
                ? expected.events().get(common) : null;
        TimelineEvent extra = actual.events().size() > common
                ? actual.events().get(common) : null;
        return new Divergence(common, common, missing, extra);
    }

    private static final class EventRecorder {
        private record ActionContext(long tick, Phase phase, Origin origin) {
        }

        private final Row row;
        private final List<TimelineEvent> events = new ArrayList<>();
        private final ArrayDeque<ActionContext> actionContexts = new ArrayDeque<>();
        private long tick = -1;
        private int ordinal;
        private boolean recording = true;

        private EventRecorder(Row row) {
            this.row = row;
        }

        private void beginTick(long tick) {
            if (tick < 0 || tick < this.tick) {
                throw new IllegalArgumentException("Recorder ticks must be monotonic");
            }
            this.tick = tick;
            ordinal = 0;
        }

        private long currentTick() {
            return tick;
        }

        /**
         * Enters the causal context of an externally scripted action. EventBus callbacks run
         * synchronously, so a callback emitted by a CPU-origin FF00 write must inherit that
         * action's phase/origin rather than being mislabeled as a machine event. A stack keeps
         * restore replay exception-safe and correctly nested while the outer RESTORE action is
         * still active.
         */
        private void enterAction(Action action) {
            actionContexts.push(new ActionContext(action.tick(), action.phase(), action.origin()));
        }

        private void exitAction(Action action) {
            if (actionContexts.isEmpty()) {
                throw new IllegalStateException("Action context is not active: " + action);
            }
            ActionContext context = actionContexts.pop();
            if (context.tick() != action.tick()
                    || context.phase() != action.phase()
                    || context.origin() != action.origin()) {
                throw new IllegalStateException("Action context stack mismatch: " + action);
            }
        }

        private void suspend() {
            if (!recording) {
                throw new IllegalStateException("Recorder is already suspended");
            }
            recording = false;
        }

        private void resume() {
            if (recording) {
                throw new IllegalStateException("Recorder is not suspended");
            }
            recording = true;
        }

        private void record(
                long tick,
                Phase phase,
                Origin origin,
                String kind,
                int address,
                int value,
                long payloadHash,
                int payloadLength) {
            if (!recording) {
                return;
            }
            if (tick != this.tick) {
                throw new IllegalStateException("Event timestamp is outside the current tick");
            }
            if (events.size() >= MAX_TIMELINE_EVENTS) {
                throw new AssertionError("Synthetic O1 timeline exceeded "
                        + MAX_TIMELINE_EVENTS + " events for " + row.id());
            }
            events.add(new TimelineEvent(tick, phase, origin, kind, address, value,
                    payloadHash, payloadLength, ordinal++));
        }

        /** Records an event emitted synchronously by the machine or host event bus. */
        private void recordSynchronous(
                String kind,
                int address,
                int value,
                long payloadHash,
                int payloadLength) {
            if (!recording) {
                return;
            }
            ActionContext context = actionContexts.peek();
            if (context == null) {
                record(tick, Phase.MACHINE, Origin.STATE, kind, address, value,
                        payloadHash, payloadLength);
            } else {
                record(context.tick(), context.phase(), context.origin(), kind, address, value,
                        payloadHash, payloadLength);
            }
        }

        private void recordArrayEvent(String kind, int[] payload, int value, int auxiliary) {
            if (!recording) {
                return;
            }
            recordSynchronous(kind, auxiliary, value,
                    hashInts(payload), payload == null ? -1 : payload.length);
        }

        private void recordArrayEvent(String kind, int[] payload, int[] secondPayload) {
            if (!recording) {
                return;
            }
            long hash = hashInts(payload);
            hash = mix(hash, hashInts(secondPayload));
            recordSynchronous(kind,
                    secondPayload == null ? -1 : secondPayload.length,
                    payload == null ? -1 : payload.length, hash,
                    (payload == null ? 0 : payload.length)
                            + (secondPayload == null ? 0 : secondPayload.length));
        }

        private List<TimelineEvent> events() {
            return List.copyOf(events);
        }
    }

    /** Immutable deep-owned rollback object. It is deliberately not part of TimelineEvent. */
    private record Checkpoint(
            ComponentState<Gameboy> machineState,
            Gameboy.RtcRuntimeState rtcRuntimeState,
            Gameboy.WallClockRuntimeState wallClockRuntimeState,
            eu.rekawek.coffeegb.core.gpu.Gpu.DmgFifoRuntimeState dmgFifoRuntimeState,
            Set<Button> legacyButtons,
            PlayerInputSnapshot sampledInput,
            long tick,
            int actionCursor,
            long fingerprint) {

        private Checkpoint {
            if (machineState == null || legacyButtons == null || sampledInput == null) {
                throw new IllegalArgumentException("Checkpoint state is incomplete");
            }
            legacyButtons = Set.copyOf(legacyButtons);
        }

        static Checkpoint capture(Gameboy gameboy, long tick, int actionCursor) {
            ComponentState<Gameboy> state = gameboy.captureStateWithoutTimeSource();
            Gameboy.RtcRuntimeState rtc = gameboy.captureRtcRuntimeStateWithoutTimeSource();
            Gameboy.WallClockRuntimeState wall = gameboy.captureWallClockRuntimeStateWithoutTimeSource();
            eu.rekawek.coffeegb.core.gpu.Gpu.DmgFifoRuntimeState fifo =
                    gameboy.captureDmgFifoRuntimeState();
            Set<Button> legacy = gameboy.getLegacyPressedButtons();
            PlayerInputSnapshot sampled = gameboy.getSampledPlayerInput();
            long fingerprint = SyntheticTimelineRecorderTest.fingerprint(gameboy, actionCursor);
            return new Checkpoint(state, rtc, wall, fifo, legacy, sampled,
                    tick, actionCursor, fingerprint);
        }

        void restore(Gameboy gameboy) {
            gameboy.restoreStateSilently(machineState);
            gameboy.restoreRtcRuntimeState(rtcRuntimeState);
            gameboy.restoreWallClockRuntimeState(wallClockRuntimeState);
            gameboy.restoreDmgFifoRuntimeState(dmgFifoRuntimeState);
            gameboy.seedDeterministicReplayInput(legacyButtons, sampledInput);
            long restored = SyntheticTimelineRecorderTest.fingerprint(gameboy, actionCursor);
            if (restored != fingerprint) {
                throw new AssertionError("checkpoint fingerprint expected " + fingerprint
                        + " actual " + restored);
            }
        }
    }

    private static long fingerprint(Gameboy gameboy, int actionCursor) {
        long result = gameboy.withMachineStateCapture((state, capture) -> {
            Digest digest = new Digest();
            visitRecordGraph(state, digest, new IdentityHashMap<>(), capture);
            digest.mix(capture.getVerifiedPayloadArrays());
            digest.mix(capture.getVerifiedPayloadBytes());
            return digest.value();
        });
        Digest digest = new Digest(result);
        digest.mix(actionCursor);
        digest.mix(gameboy.getHardwareProfile().id().hashCode());
        digest.mix(gameboy.getSpeedMode().isDmgCompat() ? 1 : 0);
        digest.mix(gameboy.getSpeedMode().getSpeedMode());
        digest.mix(gameboy.getLegacyPressedButtons().hashCode());
        digest.mix(gameboy.getSampledPlayerInput().hashCode());
        visitRecordGraph(gameboy.captureRtcRuntimeStateWithoutTimeSource(), digest,
                new IdentityHashMap<>());
        visitRecordGraph(gameboy.captureWallClockRuntimeStateWithoutTimeSource(), digest,
                new IdentityHashMap<>());
        visitRecordGraph(gameboy.captureDmgFifoRuntimeState(), digest,
                new IdentityHashMap<>());
        return digest.value();
    }

    private static final class UnsupportedStateShape extends RuntimeException {
        private UnsupportedStateShape(String message) {
            super(message);
        }
    }

    private static void visitRecordGraph(
            Object value,
            Digest digest,
            IdentityHashMap<Object, Boolean> seen) {
        visitRecordGraph(value, digest, seen, null);
    }

    private static void visitRecordGraph(
            Object value,
            Digest digest,
            IdentityHashMap<Object, Boolean> seen,
            MachineStateCapture capture) {
        if (value == null) {
            digest.mix(0);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = logicalArrayLength(value, capture);
            digest.mix(type.getName().hashCode());
            digest.mix(length);
            if (type.getComponentType().isPrimitive()) {
                for (int i = 0; i < length; i++) {
                    digest.mix(Array.get(value, i).hashCode());
                }
            } else {
                for (int i = 0; i < length; i++) {
                    visitRecordGraph(Array.get(value, i), digest, seen, capture);
                }
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?>) {
            digest.mix(type.getName().hashCode());
            digest.mix(value.hashCode());
            return;
        }
        if (value instanceof List<?> list) {
            // Released state records contain immutable lists for a few mapper/debug queues. The
            // list itself is an allowed container; its elements still pass through this walker.
            digest.mix(type.getName().hashCode());
            digest.mix(list.size());
            for (Object child : list) {
                visitRecordGraph(child, digest, seen, capture);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            // Maps are allowed only through a deterministic key order. Keys are scalar state
            // values; a mutable/opaque key fails closed instead of being reduced to identity.
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((left, right) -> stableMapKey(left.getKey()).compareTo(
                    stableMapKey(right.getKey())));
            digest.mix(type.getName().hashCode());
            digest.mix(entries.size());
            for (Map.Entry<?, ?> entry : entries) {
                digest.mix(stableMapKey(entry.getKey()).hashCode());
                visitRecordGraph(entry.getValue(), digest, seen, capture);
            }
            return;
        }
        if (!type.isRecord()) {
            // Component state is record-shaped. Do not silently hash an arbitrary host/service
            // object by class name: an uncaptured mutable component would otherwise look equal
            // across two runs. O1 deopts this shape and the direct test below pins that rule.
            throw new UnsupportedStateShape("Unsupported state shape: " + type.getName());
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            digest.mix(0x51ed270b);
            return;
        }
        digest.mix(type.getName().hashCode());
        for (RecordComponent component : type.getRecordComponents()) {
            digest.mix(component.getName().hashCode());
            try {
                var accessor = component.getAccessor();
                if (!accessor.trySetAccessible()) {
                    throw new AssertionError("State accessor is not accessible: " + component);
                }
                Object child = accessor.invoke(value);
                visitRecordGraph(child, digest, seen, capture);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError("Unable to hash state component " + component, e);
            }
        }
    }

    private static String stableMapKey(Object key) {
        if (key == null) {
            return "<null>";
        }
        if (key instanceof Number || key instanceof Boolean || key instanceof Character
                || key instanceof String || key instanceof Enum<?>) {
            return key.getClass().getName() + ":" + key;
        }
        throw new UnsupportedStateShape("Unsupported map key shape: " + key.getClass().getName());
    }

    private static int logicalArrayLength(Object array, MachineStateCapture capture) {
        if (capture == null) {
            return Array.getLength(array);
        }
        try {
            return capture.requireLength(array);
        } catch (IllegalStateException notARegisteredPayload) {
            // A non-dominant array may legitimately use an ordinary deep-owned state path.
            // It has no borrowed logical prefix, so its physical length is the correct bound.
            return Array.getLength(array);
        }
    }

    private static long hashInts(int[] values) {
        if (values == null) {
            return 0;
        }
        long hash = 0xcbf29ce484222325L;
        for (int value : values) {
            hash ^= value & 0xffffffffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    private static final class Digest {
        private long value;

        private Digest() {
            this(0xcbf29ce484222325L);
        }

        private Digest(long value) {
            this.value = value;
        }

        private void mix(long next) {
            value = SyntheticTimelineRecorderTest.mix(value, next);
        }

        private long value() {
            return value;
        }
    }

    private static final class Bytecode {
        private final byte[] rom;
        private int position;

        private Bytecode(byte[] rom, int position) {
            this.rom = rom;
            this.position = position;
        }

        private Bytecode byteValue(int value) {
            rom[position++] = (byte) value;
            return this;
        }

        private void ldh(int offset, int value) {
            byteValue(0x3e).byteValue(value).byteValue(0xe0).byteValue(offset);
        }

        private int position() {
            return position;
        }
    }
}
