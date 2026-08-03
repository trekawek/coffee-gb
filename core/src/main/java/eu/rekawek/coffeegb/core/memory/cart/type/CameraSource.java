package eu.rekawek.coffeegb.core.memory.cart.type;

/**
 * A live source of immutable RGB frames for the Pocket Camera sensor. A front end registers one
 * through {@link PocketCamera#setCameraSource}; the camera scales each returned frame to the
 * sensor's 128x112 and dithers it like the real ASIC. Capture, files, and image decoding belong
 * to the host adapter rather than the emulation core.
 */
public interface CameraSource {

    /**
     * @return the most recent caller-owned camera frame, or {@code null} if none is available yet
     * (the camera then falls back to its synthetic test pattern)
     */
    CameraFrame getFrame();
}
