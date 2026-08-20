package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoGeometryTest {

    @Test
    public void nearestFitUsesTheLargestIntegerScaleAndLetterboxesIt() {
        VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(160, 144, 1080, 1920);

        assertEquals(60, viewport.left());
        assertEquals(528, viewport.top());
        assertEquals(960, viewport.width());
        assertEquals(864, viewport.height());
    }

    @Test
    public void nearestFitTopLeavesTheLowerPortraitSpaceForControls() {
        VideoGeometry.Viewport viewport = VideoGeometry.nearestFitTop(160, 144, 1080, 1920);

        assertEquals(60, viewport.left());
        assertEquals(0, viewport.top());
        assertEquals(960, viewport.width());
        assertEquals(864, viewport.height());
    }

    @Test
    public void nearestFitFallsBackToAspectFitWhenTheSurfaceIsSmallerThanTheFrame() {
        VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(256, 224, 200, 100);

        assertEquals(43, viewport.left());
        assertEquals(0, viewport.top());
        assertEquals(114, viewport.width());
        assertEquals(100, viewport.height());
    }

    @Test
    public void superGameboyBorderKeepsItsNativeAspectInsideTheFixedSkinWindow() {
        VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(256, 224, 800, 720);

        assertEquals(16, viewport.left());
        assertEquals(24, viewport.top());
        assertEquals(768, viewport.width());
        assertEquals(672, viewport.height());
    }

    @Test
    public void invalidDimensionsProduceNoDrawableViewport() {
        VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(160, 144, 0, 1080);

        assertEquals(0, viewport.width());
        assertEquals(0, viewport.height());
    }
}
