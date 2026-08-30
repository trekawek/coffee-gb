package eu.rekawek.coffeegb.android;

/** Allocation-free monotonic clock seam for benchmark-only audio timing attribution. */
@FunctionalInterface
interface NanoClock {

    NanoClock SYSTEM = System::nanoTime;

    long nanoTime();
}
