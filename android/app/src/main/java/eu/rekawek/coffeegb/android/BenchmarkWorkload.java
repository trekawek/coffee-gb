package eu.rekawek.coffeegb.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Identity-free workload contracts used by the Android goal matrix.
 *
 * <p>A workload is deliberately independent from a hardware row.  The same immutable timeline
 * and app-owned nonce are therefore used for every cell belonging to a workload.  The slot is an
 * opaque app-catalog selection; it is never a ROM name, path, hash, or header value.</p>
 */
final class BenchmarkWorkload {

    static final String MATRIX_VERSION = "goal-matrix-v1";

    /** The four app-owned catalog slots in the goal matrix. */
    enum Slot {
        D("d", "d-v1", 0),
        U("u", "u-v1", 1),
        C1("c1", "c1-v1", 2),
        C2("c2", "c2-v1", 3);

        private final String externalValue;
        private final String contractId;
        /** Stable app-catalog position; nonce identity is generated per persisted recent entry. */
        private final int recentSlot;

        Slot(String externalValue, String contractId, int recentSlot) {
            this.externalValue = externalValue;
            this.contractId = contractId;
            this.recentSlot = recentSlot;
        }

        String externalValue() {
            return externalValue;
        }

        String contractId() {
            return contractId;
        }

        int recentSlot() {
            return recentSlot;
        }

        static Slot fromExternalValue(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Slot slot : values()) {
                if (slot.externalValue.equals(normalized)
                        || slot.contractId.equals(normalized)) {
                    return slot;
                }
            }
            return null;
        }
    }

    /** The effective hardware mode a cell must report after boot has settled. */
    enum EffectiveProfile {
        DMG("dmg"),
        CGB_COMPAT("cgb-compat"),
        CGB_NATIVE("cgb-native"),
        SGB("sgb");

        private final String externalValue;

        EffectiveProfile(String externalValue) {
            this.externalValue = externalValue;
        }

        String externalValue() {
            return externalValue;
        }
    }

    /** The exact eight cells; SGB2 is intentionally not represented. */
    enum Cell {
        D_DMG("d-dmg", Slot.D, DiagnosticsOptions.Hardware.DMG, EffectiveProfile.DMG),
        D_CGB_COMPAT("d-cgb-compat", Slot.D, DiagnosticsOptions.Hardware.CGB,
                EffectiveProfile.CGB_COMPAT),
        D_SGB("d-sgb", Slot.D, DiagnosticsOptions.Hardware.SGB, EffectiveProfile.SGB),
        U_DMG("u-dmg", Slot.U, DiagnosticsOptions.Hardware.DMG, EffectiveProfile.DMG),
        U_CGB_NATIVE("u-cgb-native", Slot.U, DiagnosticsOptions.Hardware.CGB,
                EffectiveProfile.CGB_NATIVE),
        U_SGB("u-sgb", Slot.U, DiagnosticsOptions.Hardware.SGB, EffectiveProfile.SGB),
        C1_CGB_NATIVE("c1-cgb-native", Slot.C1, DiagnosticsOptions.Hardware.CGB,
                EffectiveProfile.CGB_NATIVE),
        C2_CGB_NATIVE("c2-cgb-native", Slot.C2, DiagnosticsOptions.Hardware.CGB,
                EffectiveProfile.CGB_NATIVE);

        private final String externalValue;
        private final Slot workload;
        private final DiagnosticsOptions.Hardware requestedHardware;
        private final EffectiveProfile effectiveProfile;

        Cell(String externalValue, Slot workload,
                DiagnosticsOptions.Hardware requestedHardware, EffectiveProfile effectiveProfile) {
            this.externalValue = externalValue;
            this.workload = workload;
            this.requestedHardware = requestedHardware;
            this.effectiveProfile = effectiveProfile;
        }

        String externalValue() {
            return externalValue;
        }

        Slot workload() {
            return workload;
        }

        DiagnosticsOptions.Hardware requestedHardware() {
            return requestedHardware;
        }

        EffectiveProfile effectiveProfile() {
            return effectiveProfile;
        }

        static Cell fromExternalValue(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Cell cell : values()) {
                if (cell.externalValue.equals(normalized)) {
                    return cell;
                }
            }
            return null;
        }

        static List<Cell> all() {
            ArrayList<Cell> cells = new ArrayList<>();
            Collections.addAll(cells, values());
            return Collections.unmodifiableList(cells);
        }
    }

    /** One contiguous, immutable button-mask interval. */
    static final class Segment {
        private final int mask;
        private final int frames;

        Segment(int mask, int frames) {
            if (mask < 0 || frames <= 0) {
                throw new IllegalArgumentException("Timeline segment is invalid");
            }
            this.mask = mask;
            this.frames = frames;
        }

        int mask() {
            return mask;
        }

        int frames() {
            return frames;
        }
    }

    /**
     * A versioned input timeline.  {@code advanceCount} describes callbacks after the initial
     * cartridge frame; consequently a complete timeline's endpoint is advanceCount + 1.
     */
    static final class Timeline {
        private final String id;
        private final List<Segment> segments;
        private final int advanceCount;
        private final boolean complete;

        private Timeline(String id, List<Segment> segments, int advanceCount, boolean complete) {
            this.id = id;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
            this.advanceCount = advanceCount;
            this.complete = complete;
        }

        static Timeline complete(String id, List<Segment> segments) {
            if (id == null || id.isBlank() || segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("Complete timeline is invalid");
            }
            long total = 0L;
            for (Segment segment : segments) {
                total += segment.frames();
                if (total > Integer.MAX_VALUE - 1L) {
                    throw new IllegalArgumentException("Timeline is too long");
                }
            }
            return new Timeline(id, segments, (int) total, true);
        }

        String id() {
            return id;
        }

        List<Segment> segments() {
            return segments;
        }

        boolean complete() {
            return complete;
        }

        int advanceCount() {
            return advanceCount;
        }

        int endpointFrame() {
            return complete ? advanceCount + 1 : 0;
        }

        /** Returns the mask for a 1-based native frame, including the initial frame. */
        int maskForFrame(int nativeFrame) {
            if (!complete || nativeFrame <= 0 || nativeFrame > endpointFrame()) {
                return BenchmarkGameplayScenario.NONE_MASK;
            }
            int cursor = nativeFrame - 1;
            for (Segment segment : segments) {
                if (cursor < segment.frames()) {
                    return segment.mask();
                }
                cursor -= segment.frames();
            }
            return BenchmarkGameplayScenario.NONE_MASK;
        }
    }

    private static final Map<Slot, Timeline> TIMELINES = createTimelines();

    static Timeline timeline(Slot slot) {
        return slot == null ? null : TIMELINES.get(slot);
    }

    private static Map<Slot, Timeline> createTimelines() {
        LinkedHashMap<Slot, Timeline> timelines = new LinkedHashMap<>();
        timelines.put(Slot.D, Timeline.complete("d-v1", List.of(
                // Initial cartridge capture is frame 1.  D-v1 has 312 post-capture advances,
                // so the endpoint callback is frame 313 (release through frame 119).
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 119),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.RIGHT_MASK, 120),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 10))));
        timelines.put(Slot.U, Timeline.complete("u-v1", List.of(
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 180),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 30),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 60),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 90),
                new Segment(BenchmarkGameplayScenario.START_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120))));
        timelines.put(Slot.C1, Timeline.complete("c1-v1", List.of(
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 669),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 90),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 90),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                new Segment(BenchmarkGameplayScenario.A_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120),
                // The four validated cartridge variants have different transition lengths;
                // retain the shared identity-free trace through the common frame-1582 endpoint.
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 360))));
        timelines.put(Slot.C2, Timeline.complete("c2-v1", List.of(
                // Initial cartridge capture is frame 1; B is held on frames 961..963 and
                // the endpoint is the active-gameplay frame 1084.
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 960),
                new Segment(BenchmarkGameplayScenario.B_MASK, 3),
                new Segment(BenchmarkGameplayScenario.NONE_MASK, 120))));
        return Collections.unmodifiableMap(timelines);
    }

    static Timeline timelineForCell(Cell cell) {
        return cell == null ? null : timeline(cell.workload());
    }

    private BenchmarkWorkload() {
    }
}
