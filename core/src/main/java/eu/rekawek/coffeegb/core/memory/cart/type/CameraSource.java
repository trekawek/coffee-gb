package eu.rekawek.coffeegb.core.memory.cart.type;

import java.awt.image.BufferedImage;

/**
 * A live source of frames for the Pocket Camera sensor. A front end (e.g. the Swing UI
 * with a real webcam) registers one through {@link PocketCamera#setCameraSource}; the
 * camera scales each returned frame to the sensor's 128x112 and dithers it like the real
 * ASIC. Keeping this out of the core means the core stays free of any capture dependency.
 */
public interface CameraSource {

    /**
     * @return the most recent camera frame, or {@code null} if none is available yet
     * (the camera then falls back to the image file / test pattern)
     */
    BufferedImage getFrame();
}
