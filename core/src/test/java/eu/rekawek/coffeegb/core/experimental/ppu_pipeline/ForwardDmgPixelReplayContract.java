package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import java.util.EnumMap;
import java.util.Map;

import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION;

/**
 * Typed boundary between a recorded production-PixelTransfer trace and the detached forward
 * pipeline. Keeping the boundary executable prevents an integration image produced by the
 * unchanged renderer from being mistaken for a shadow comparison of this candidate.
 */
final class ForwardDmgPixelReplayContract {

    enum RequiredTraceSignal {
        /** LCDC.5 removed the window source after the window path had become active. */
        WINDOW_SOURCE_DEACTIVATE
    }

    record UnsupportedCone(
            RequiredTraceSignal signal,
            int incompleteBehaviorBit,
            String requiredCandidateInterface) {

        UnsupportedCone {
            if (signal == null) {
                throw new IllegalArgumentException("signal");
            }
            if (Integer.bitCount(incompleteBehaviorBit) != 1) {
                throw new IllegalArgumentException("one incomplete-behavior bit is required");
            }
            if (requiredCandidateInterface == null || requiredCandidateInterface.isBlank()) {
                throw new IllegalArgumentException("required candidate interface");
            }
        }
    }

    private static final Map<RequiredTraceSignal, UnsupportedCone> UNSUPPORTED = unsupported();

    private ForwardDmgPixelReplayContract() {
    }

    static void requireRepresentable(RequiredTraceSignal signal) {
        UnsupportedCone cone = UNSUPPORTED.get(signal);
        if (cone != null) {
            throw new UnsupportedReplaySignalException(cone);
        }
        throw new IllegalArgumentException("unclassified replay signal: " + signal);
    }

    private static Map<RequiredTraceSignal, UnsupportedCone> unsupported() {
        EnumMap<RequiredTraceSignal, UnsupportedCone> cones =
                new EnumMap<>(RequiredTraceSignal.class);
        cones.put(
                RequiredTraceSignal.WINDOW_SOURCE_DEACTIVATE,
                new UnsupportedCone(
                        RequiredTraceSignal.WINDOW_SOURCE_DEACTIVATE,
                        OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION,
                        "an asynchronous window-source reset wired to active fetch-stage "
                                + "validity without rewinding committed scanout tokens"));
        return Map.copyOf(cones);
    }

    static final class UnsupportedReplaySignalException extends UnsupportedOperationException {

        private final UnsupportedCone cone;

        private UnsupportedReplaySignalException(UnsupportedCone cone) {
            super(cone.signal() + " requires " + cone.requiredCandidateInterface());
            this.cone = cone;
        }

        UnsupportedCone cone() {
            return cone;
        }
    }
}
