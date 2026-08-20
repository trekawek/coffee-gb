package eu.rekawek.coffeegb.android;

import android.os.Build;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SurfaceRatePolicyTest {

    @Test
    public void frameRateHintsSelectTheAvailableNonDisruptiveApi() {
        assertEquals(CoffeeGbSurfaceView.SurfaceRatePolicy.Request.UNSUPPORTED,
                CoffeeGbSurfaceView.SurfaceRatePolicy.requestAt(Build.VERSION_CODES.Q));
        assertEquals(CoffeeGbSurfaceView.SurfaceRatePolicy.Request.DEFAULT_COMPATIBILITY,
                CoffeeGbSurfaceView.SurfaceRatePolicy.requestAt(Build.VERSION_CODES.R));
        assertEquals(CoffeeGbSurfaceView.SurfaceRatePolicy.Request.DEFAULT_COMPATIBILITY_SEAMLESS_ONLY,
                CoffeeGbSurfaceView.SurfaceRatePolicy.requestAt(Build.VERSION_CODES.S));
    }
}
