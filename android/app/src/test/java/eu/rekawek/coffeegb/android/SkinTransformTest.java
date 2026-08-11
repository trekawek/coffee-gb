package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkinTransformTest {

    private static final float DELTA = 0.001f;

    @Test
    public void nativeSizeUsesIdentityTransform() {
        SkinTransform transform = SkinTransform.aspectFit(941, 1672, 941, 1672);

        assertEquals(1f, transform.scale(), DELTA);
        assertBounds(transform.skinBounds(), 0f, 0f, 941f, 1672f);
    }

    @Test
    public void portraitViewPreservesAspectAndCentersVerticalLetterbox() {
        SkinTransform transform = SkinTransform.aspectFit(941, 1672, 920, 1884);
        float scale = 920f / 941f;
        float top = (1884f - 1672f * scale) / 2f;

        assertEquals(scale, transform.scale(), DELTA);
        assertBounds(transform.skinBounds(), 0f, top, 920f, top + 1672f * scale);
    }

    @Test
    public void landscapeViewPreservesAspectAndCentersHorizontalLetterbox() {
        SkinTransform transform = SkinTransform.aspectFit(1672, 941, 1884, 920);
        float scale = 920f / 941f;
        float left = (1884f - 1672f * scale) / 2f;

        assertEquals(scale, transform.scale(), DELTA);
        assertBounds(transform.skinBounds(), left, 0f, left + 1672f * scale, 920f);
        assertFalse(transform.containsViewPoint(left - 1f, 460f));
    }

    @Test
    public void displayAndPointsUseTheSameMappingAndLetterboxIsExcluded() {
        SkinTransform transform = SkinTransform.aspectFit(941, 1672, 920, 1884);
        SkinTransform.Bounds display = transform.mapBounds(91f, 203f, 849f, 888f);
        SkinTransform.Point viewPoint = transform.mapPoint(707f, 1215f);
        SkinTransform.Point roundTrip = transform.inversePoint(viewPoint.x(), viewPoint.y());

        assertEquals(transform.skinBounds().left() + 91f * transform.scale(),
                display.left(), DELTA);
        assertEquals(transform.skinBounds().top() + 203f * transform.scale(),
                display.top(), DELTA);
        assertEquals(707f, roundTrip.x(), DELTA);
        assertEquals(1215f, roundTrip.y(), DELTA);
        assertTrue(transform.containsViewPoint(viewPoint.x(), viewPoint.y()));
        assertFalse(transform.containsViewPoint(460f, transform.skinBounds().top() - 1f));
        assertFalse(transform.containsViewPoint(
                transform.skinBounds().right(), transform.skinBounds().top()));
    }

    private static void assertBounds(SkinTransform.Bounds actual,
            float left, float top, float right, float bottom) {
        assertEquals(left, actual.left(), DELTA);
        assertEquals(top, actual.top(), DELTA);
        assertEquals(right, actual.right(), DELTA);
        assertEquals(bottom, actual.bottom(), DELTA);
    }
}
