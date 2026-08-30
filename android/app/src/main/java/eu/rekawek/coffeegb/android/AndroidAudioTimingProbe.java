package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;

/**
 * Benchmark-only, allocation-free cumulative timing attribution for the Android PCM hand-off.
 * Every hot-path update mutates primitives in this preallocated object; {@link Snapshot} is
 * allocated only at the 60-frame diagnostic boundary.
 */
final class AndroidAudioTimingProbe implements BoundedPcmQueue.TimingProbe {

    static final long STALL_NANOS = 20_000_000L;

    private volatile long generation;

    private long producerIntervals;
    private long producerMaxGapUs;
    private long sourceCadenceDebtPeakUs;
    private long producerGaps20Millis;
    private long eventToPublishMaxUs;
    private long publishToDequeuePollMaxUs;
    private long publishToDequeueWriteMaxUs;
    private long publishToDequeueOtherMaxUs;
    private long publishToDequeue20Millis;
    private long dequeueToReadyMaxUs;
    private long readyToFirstWriteMaxUs;
    private long writeCallMaxUs;
    private long writeCalls20Millis;
    private long publishToWriteCompleteMaxUs;

    private long outputUnderrunDelta;
    private long underrunQueueNonempty;
    private long underrunProducerSilence20Millis;
    private long underrunAfterWrite20Millis;
    private long underrunUnclassified;
    private long underrunMaxProducerSilenceUs;
    private long underrunMaxQueuedFrames;

    private long previousProducerNanos = -1L;
    private int previousSourceTicks;
    private ClockSpec previousSourceClock;
    /** Exact debt represented as whole nanoseconds plus numerator/denominator remainder. */
    private long cadenceDebtNanos;
    private long cadenceDebtRemainder;
    private long cadenceDebtDenominator = 1L;
    private boolean longWritePendingSample;
    private long attributedOutputIdentity;
    private long attributedOutputUnderruns = -1L;

    synchronized long reset(long outputIdentity, long outputUnderruns) {
        long next = generation + 1L;
        if (next <= 0L) {
            next = 1L;
        }
        generation = next;
        producerIntervals = 0L;
        producerMaxGapUs = 0L;
        sourceCadenceDebtPeakUs = 0L;
        producerGaps20Millis = 0L;
        eventToPublishMaxUs = 0L;
        publishToDequeuePollMaxUs = 0L;
        publishToDequeueWriteMaxUs = 0L;
        publishToDequeueOtherMaxUs = 0L;
        publishToDequeue20Millis = 0L;
        dequeueToReadyMaxUs = 0L;
        readyToFirstWriteMaxUs = 0L;
        writeCallMaxUs = 0L;
        writeCalls20Millis = 0L;
        publishToWriteCompleteMaxUs = 0L;
        outputUnderrunDelta = 0L;
        underrunQueueNonempty = 0L;
        underrunProducerSilence20Millis = 0L;
        underrunAfterWrite20Millis = 0L;
        underrunUnclassified = 0L;
        underrunMaxProducerSilenceUs = 0L;
        underrunMaxQueuedFrames = 0L;
        previousProducerNanos = -1L;
        previousSourceTicks = 0;
        previousSourceClock = null;
        cadenceDebtNanos = 0L;
        cadenceDebtRemainder = 0L;
        cadenceDebtDenominator = 1L;
        longWritePendingSample = false;
        attributedOutputIdentity = outputIdentity;
        attributedOutputUnderruns = outputUnderruns;
        return next;
    }

    long generation() {
        return generation;
    }

    @Override
    public synchronized void published(BoundedPcmQueue.Frame frame, ClockSpec sourceClock,
            int sourceTicks) {
        if (!current(frame)) {
            return;
        }
        long eventToPublish = elapsed(frame.eventNanos(), frame.publishNanos());
        if (eventToPublish >= 0L) {
            eventToPublishMaxUs = Math.max(eventToPublishMaxUs, microsCeiling(eventToPublish));
        }
        long eventNanos = frame.eventNanos();
        if (eventNanos < 0L || sourceClock == null || sourceTicks <= 0) {
            resetProducerAnchor();
            return;
        }
        if (previousProducerNanos >= 0L && sourceClock.equals(previousSourceClock)
                && eventNanos >= previousProducerNanos) {
            long gap = eventNanos - previousProducerNanos;
            producerIntervals++;
            producerMaxGapUs = Math.max(producerMaxGapUs, microsCeiling(gap));
            if (gap >= STALL_NANOS) {
                producerGaps20Millis++;
            }
            advanceCadenceDebt(gap, previousSourceTicks, previousSourceClock);
        } else if (previousSourceClock != null && !sourceClock.equals(previousSourceClock)) {
            // The first packet after a profile transition establishes the new exact cadence.
            cadenceDebtNanos = 0L;
            cadenceDebtRemainder = 0L;
            cadenceDebtDenominator = 1L;
        }
        previousProducerNanos = eventNanos;
        previousSourceTicks = sourceTicks;
        previousSourceClock = sourceClock;
    }

    @Override
    public synchronized void ready(BoundedPcmQueue.Frame frame) {
        if (!current(frame)) {
            return;
        }
        long publishToDequeue = elapsed(frame.publishNanos(), frame.dequeueNanos());
        if (publishToDequeue >= 0L) {
            long micros = microsCeiling(publishToDequeue);
            switch (frame.publicationWorkerPhase()) {
                case AndroidAudioSink.WORKER_PHASE_POLL ->
                        publishToDequeuePollMaxUs = Math.max(publishToDequeuePollMaxUs, micros);
                case AndroidAudioSink.WORKER_PHASE_WRITE ->
                        publishToDequeueWriteMaxUs = Math.max(publishToDequeueWriteMaxUs, micros);
                default -> publishToDequeueOtherMaxUs = Math.max(
                        publishToDequeueOtherMaxUs, micros);
            }
            if (publishToDequeue >= STALL_NANOS) {
                publishToDequeue20Millis++;
            }
        }
        long dequeueToReady = elapsed(frame.dequeueNanos(), frame.pcmReadyNanos());
        if (dequeueToReady >= 0L) {
            dequeueToReadyMaxUs = Math.max(dequeueToReadyMaxUs,
                    microsCeiling(dequeueToReady));
        }
    }

    synchronized void writeStarted(BoundedPcmQueue.Frame frame, long nowNanos) {
        if (!current(frame) || nowNanos < 0L || frame.firstWriteNanos() >= 0L) {
            return;
        }
        frame.setFirstWriteNanos(nowNanos);
        long readyToWrite = elapsed(frame.pcmReadyNanos(), nowNanos);
        if (readyToWrite >= 0L) {
            readyToFirstWriteMaxUs = Math.max(readyToFirstWriteMaxUs,
                    microsCeiling(readyToWrite));
        }
    }

    synchronized void writeCall(BoundedPcmQueue.Frame frame, long startNanos, long endNanos) {
        if (!current(frame)) {
            return;
        }
        long duration = elapsed(startNanos, endNanos);
        if (duration >= 0L) {
            writeCallMaxUs = Math.max(writeCallMaxUs, microsCeiling(duration));
            if (duration >= STALL_NANOS) {
                writeCalls20Millis++;
            }
            // The next authoritative AudioTrack counter read is the after-write observation.
            longWritePendingSample = duration >= STALL_NANOS;
        }
    }

    synchronized void writeComplete(BoundedPcmQueue.Frame frame, long nowNanos) {
        if (!current(frame) || frame.writeCompleteNanos() >= 0L || nowNanos < 0L) {
            return;
        }
        frame.setWriteCompleteNanos(nowNanos);
        long publishToComplete = elapsed(frame.publishNanos(), nowNanos);
        if (publishToComplete >= 0L) {
            publishToWriteCompleteMaxUs = Math.max(publishToWriteCompleteMaxUs,
                    microsCeiling(publishToComplete));
        }
    }

    synchronized void outputUnderruns(long outputIdentity, long sampledUnderruns,
            long nowNanos, int queuedFrames) {
        if (generation <= 0L || outputIdentity <= 0L || sampledUnderruns < 0L) {
            return;
        }
        boolean afterLongWrite = longWritePendingSample;
        longWritePendingSample = false;
        if (outputIdentity != attributedOutputIdentity || attributedOutputUnderruns < 0L
                || sampledUnderruns < attributedOutputUnderruns) {
            attributedOutputIdentity = outputIdentity;
            attributedOutputUnderruns = sampledUnderruns;
            return;
        }
        long delta = sampledUnderruns - attributedOutputUnderruns;
        if (delta <= 0L) {
            return;
        }
        attributedOutputUnderruns = sampledUnderruns;
        outputUnderrunDelta += delta;
        int boundedQueuedFrames = Math.max(0, queuedFrames);
        underrunMaxQueuedFrames = Math.max(underrunMaxQueuedFrames, boundedQueuedFrames);
        long producerSilence = elapsed(previousProducerNanos, nowNanos);
        if (producerSilence >= 0L) {
            underrunMaxProducerSilenceUs = Math.max(underrunMaxProducerSilenceUs,
                    microsCeiling(producerSilence));
        }
        if (boundedQueuedFrames > 0) {
            underrunQueueNonempty += delta;
        } else if (producerSilence >= STALL_NANOS) {
            underrunProducerSilence20Millis += delta;
        } else if (afterLongWrite) {
            underrunAfterWrite20Millis += delta;
        } else {
            underrunUnclassified += delta;
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(generation, producerIntervals, producerMaxGapUs,
                sourceCadenceDebtPeakUs, producerGaps20Millis, eventToPublishMaxUs,
                publishToDequeuePollMaxUs, publishToDequeueWriteMaxUs,
                publishToDequeueOtherMaxUs, publishToDequeue20Millis,
                dequeueToReadyMaxUs, readyToFirstWriteMaxUs, writeCallMaxUs,
                writeCalls20Millis, publishToWriteCompleteMaxUs, outputUnderrunDelta,
                underrunQueueNonempty, underrunProducerSilence20Millis,
                underrunAfterWrite20Millis, underrunUnclassified,
                underrunMaxProducerSilenceUs, underrunMaxQueuedFrames);
    }

    synchronized long currentCadenceDebtUsForTesting() {
        return microsCeiling(cadenceDebtNanos, cadenceDebtRemainder);
    }

    private boolean current(BoundedPcmQueue.Frame frame) {
        return frame != null && generation > 0L && frame.timingGeneration() == generation;
    }

    private void advanceCadenceDebt(long gapNanos, int sourceTicks, ClockSpec sourceClock) {
        if (sourceTicks <= 0 || sourceClock == null) {
            cadenceDebtNanos = 0L;
            cadenceDebtRemainder = 0L;
            cadenceDebtDenominator = 1L;
            return;
        }
        long denominator = sourceClock.ticksPerSecondNumerator();
        long expectedNumerator = (long) sourceTicks * sourceClock.ticksPerSecondDenominator()
                * 1_000_000_000L;
        long expectedNanos = expectedNumerator / denominator;
        long expectedRemainder = expectedNumerator % denominator;
        if (cadenceDebtDenominator != denominator) {
            cadenceDebtNanos = 0L;
            cadenceDebtRemainder = 0L;
            cadenceDebtDenominator = denominator;
        }
        long nextNanos = cadenceDebtNanos + gapNanos - expectedNanos;
        long nextRemainder = cadenceDebtRemainder - expectedRemainder;
        if (nextRemainder < 0L) {
            nextRemainder += denominator;
            nextNanos--;
        }
        if (nextNanos < 0L) {
            cadenceDebtNanos = 0L;
            cadenceDebtRemainder = 0L;
        } else {
            cadenceDebtNanos = nextNanos;
            cadenceDebtRemainder = nextRemainder;
        }
        sourceCadenceDebtPeakUs = Math.max(sourceCadenceDebtPeakUs,
                microsCeiling(cadenceDebtNanos, cadenceDebtRemainder));
    }

    private void resetProducerAnchor() {
        previousProducerNanos = -1L;
        previousSourceTicks = 0;
        previousSourceClock = null;
        cadenceDebtNanos = 0L;
        cadenceDebtRemainder = 0L;
        cadenceDebtDenominator = 1L;
    }

    private static long elapsed(long start, long end) {
        return start >= 0L && end >= start ? end - start : -1L;
    }

    private static long microsCeiling(long nanos) {
        return nanos / 1_000L + (nanos % 1_000L == 0L ? 0L : 1L);
    }

    private static long microsCeiling(long nanos, long fractionalNumerator) {
        return nanos / 1_000L
                + (nanos % 1_000L == 0L && fractionalNumerator == 0L ? 0L : 1L);
    }

    record Snapshot(long generation, long producerIntervals, long producerMaxGapUs,
            long sourceCadenceDebtPeakUs, long producerGaps20Millis,
            long eventToPublishMaxUs, long publishToDequeuePollMaxUs,
            long publishToDequeueWriteMaxUs, long publishToDequeueOtherMaxUs,
            long publishToDequeue20Millis, long dequeueToReadyMaxUs,
            long readyToFirstWriteMaxUs, long writeCallMaxUs, long writeCalls20Millis,
            long publishToWriteCompleteMaxUs, long outputUnderrunDelta,
            long underrunQueueNonempty, long underrunProducerSilence20Millis,
            long underrunAfterWrite20Millis, long underrunUnclassified,
            long underrunMaxProducerSilenceUs, long underrunMaxQueuedFrames) {

        static Snapshot unavailable() {
            return new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }
}
