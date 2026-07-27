package eu.rekawek.coffeegb.swing.io;

import org.junit.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.Point2D;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DisplayViewportTest {

    private static final double EPSILON = 1e-9;

    @Test
    public void integerFitUsesLargestWholeDmgScaleAndCentersOddRemainders() {
        DisplayViewport viewport = DisplayViewport.calculate(
                805, 725, 160, 144, 0, DisplayScaleMode.INTEGER_FIT);

        assertEquals(5.0, viewport.scale(), EPSILON);
        assertEquals(2, viewport.x());
        assertEquals(2, viewport.y());
        assertEquals(800.0, viewport.width(), EPSILON);
        assertEquals(720.0, viewport.height(), EPSILON);
        assertEquals(new Rectangle(2, 2, 800, 720), viewport.paintBounds());
        // The odd spare pixels deliberately remain on the trailing edges.
        assertEquals(3, 805 - viewport.paintBounds().x - viewport.paintBounds().width);
        assertEquals(3, 725 - viewport.paintBounds().y - viewport.paintBounds().height);
    }

    @Test
    public void integerFitUsesSgbBorderDimensions() {
        DisplayViewport viewport = DisplayViewport.calculate(
                1_030, 900, 256, 224, 0, DisplayScaleMode.INTEGER_FIT);

        assertEquals(4.0, viewport.scale(), EPSILON);
        assertEquals(new Rectangle(3, 2, 1_024, 896), viewport.paintBounds());
    }

    @Test
    public void undersizedIntegerFitFallsBackToUniformFractionalFit() {
        DisplayViewport viewport = DisplayViewport.calculate(
                100, 100, 160, 144, 0, DisplayScaleMode.INTEGER_FIT);

        assertEquals(0.625, viewport.scale(), EPSILON);
        assertEquals(100.0, viewport.width(), EPSILON);
        assertEquals(90.0, viewport.height(), EPSILON);
        assertEquals(new Rectangle(0, 5, 100, 90), viewport.paintBounds());
    }

    @Test
    public void aspectFitKeepsOneExactScaleAndReportsConservativePixelBounds() {
        DisplayViewport viewport = DisplayViewport.calculate(
                1_000, 700, 160, 144, 0, DisplayScaleMode.ASPECT_FIT);

        assertEquals(700.0 / 144.0, viewport.scale(), EPSILON);
        assertEquals(160 * viewport.scale(), viewport.width(), EPSILON);
        assertEquals(144 * viewport.scale(), viewport.height(), EPSILON);
        assertEquals(111, viewport.x());
        assertEquals(0, viewport.y());
        assertEquals(new Rectangle(111, 0, 778, 700), viewport.paintBounds());
        assertUniformTransform(viewport, 160, 144);
    }

    @Test
    public void aspectFitHandlesExactSgbPillarboxGeometry() {
        DisplayViewport viewport = DisplayViewport.calculate(
                1_000, 700, 256, 224, 0, DisplayScaleMode.ASPECT_FIT);

        assertEquals(3.125, viewport.scale(), EPSILON);
        assertEquals(800.0, viewport.width(), EPSILON);
        assertEquals(700.0, viewport.height(), EPSILON);
        assertEquals(new Rectangle(100, 0, 800, 700), viewport.paintBounds());
    }

    @Test
    public void ninetyDegreeRotationFitsUsingSwappedSourceDimensions() {
        DisplayViewport viewport = DisplayViewport.calculate(
                300, 333, 160, 144, 90, DisplayScaleMode.INTEGER_FIT);

        assertEquals(2.0, viewport.scale(), EPSILON);
        assertEquals(144, viewport.rotatedSourceWidth());
        assertEquals(160, viewport.rotatedSourceHeight());
        assertEquals(new Rectangle(6, 6, 288, 320), viewport.paintBounds());
        assertRotatedCornersStayInsideExactViewport(viewport, 160, 144);
    }

    @Test
    public void twoHundredSeventyDegreeAspectFitUsesSwappedSgbDimensions() {
        DisplayViewport viewport = DisplayViewport.calculate(
                701, 900, 256, 224, 270, DisplayScaleMode.ASPECT_FIT);

        assertEquals(701.0 / 224.0, viewport.scale(), EPSILON);
        assertEquals(701.0, viewport.width(), EPSILON);
        assertEquals(256 * viewport.scale(), viewport.height(), EPSILON);
        assertEquals(0, viewport.x());
        assertEquals(49, viewport.y());
        assertEquals(new Rectangle(0, 49, 701, 802), viewport.paintBounds());
        assertRotatedCornersStayInsideExactViewport(viewport, 256, 224);
    }

    @Test
    public void oneHundredEightyDegreeTransformPreservesTheDmgViewport() {
        DisplayViewport viewport = DisplayViewport.calculate(
                321, 289, 160, 144, 180, DisplayScaleMode.EXPLICIT_2X);

        assertEquals(0, viewport.x());
        assertEquals(0, viewport.y());
        assertEquals(new Rectangle(0, 0, 320, 288), viewport.paintBounds());
        Point2D mappedOrigin = viewport.sourceToComponentTransform()
                .transform(new Point2D.Double(0, 0), null);
        Point2D mappedOpposite = viewport.sourceToComponentTransform()
                .transform(new Point2D.Double(160, 144), null);
        assertEquals(320.0, mappedOrigin.getX(), EPSILON);
        assertEquals(288.0, mappedOrigin.getY(), EPSILON);
        assertEquals(0.0, mappedOpposite.getX(), EPSILON);
        assertEquals(0.0, mappedOpposite.getY(), EPSILON);
    }

    @Test
    public void eachExplicitScaleIsExactAndCenteredWithoutRescalingToFit() {
        DisplayScaleMode[] modes = {
                DisplayScaleMode.EXPLICIT_1X,
                DisplayScaleMode.EXPLICIT_2X,
                DisplayScaleMode.EXPLICIT_3X,
                DisplayScaleMode.EXPLICIT_4X
        };
        for (int i = 0; i < modes.length; i++) {
            int factor = i + 1;
            DisplayViewport viewport = DisplayViewport.calculate(
                    1_001, 901, 160, 144, 0, modes[i]);

            assertEquals(factor, viewport.scale(), EPSILON);
            assertEquals(160.0 * factor, viewport.width(), EPSILON);
            assertEquals(144.0 * factor, viewport.height(), EPSILON);
            assertEquals((int) Math.floor((1_001 - 160.0 * factor) / 2.0), viewport.x());
            assertEquals((int) Math.floor((901 - 144.0 * factor) / 2.0), viewport.y());
        }
    }

    @Test
    public void explicitScaleDefinesDeterministicLeadingEdgeCropWhenComponentIsTooSmall() {
        DisplayViewport viewport = DisplayViewport.calculate(
                319, 287, 160, 144, 0, DisplayScaleMode.EXPLICIT_2X);

        assertEquals(-1, viewport.x());
        assertEquals(-1, viewport.y());
        assertEquals(new Rectangle(0, 0, 319, 287), viewport.paintBounds());
    }

    @Test
    public void zeroSizedComponentProducesAnEmptyPaintBound() {
        DisplayViewport viewport = DisplayViewport.calculate(
                0, 0, 160, 144, 0, DisplayScaleMode.ASPECT_FIT);

        assertEquals(0.0, viewport.scale(), EPSILON);
        assertEquals(new Rectangle(), viewport.paintBounds());
    }

    @Test
    public void preferredSizeUsesIntrinsicSizeForFitAndExplicitFactorOtherwise() {
        assertEquals(
                new Dimension(160, 144),
                DisplayViewport.preferredSize(
                        160, 144, 0, DisplayScaleMode.INTEGER_FIT));
        assertEquals(
                new Dimension(224, 256),
                DisplayViewport.preferredSize(
                        256, 224, 90, DisplayScaleMode.ASPECT_FIT));
        assertEquals(
                new Dimension(576, 640),
                DisplayViewport.preferredSize(
                        160, 144, 270, DisplayScaleMode.EXPLICIT_4X));
    }

    @Test
    public void invalidDimensionsAndNonQuarterTurnRotationsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayViewport.calculate(
                        -1, 10, 160, 144, 0, DisplayScaleMode.ASPECT_FIT));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayViewport.calculate(
                        10, 10, 0, 144, 0, DisplayScaleMode.ASPECT_FIT));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayViewport.calculate(
                        10, 10, 160, 144, 45, DisplayScaleMode.ASPECT_FIT));
    }

    @Test
    public void explicitScaleFactoryCoversOnlyTheSupportedOneThroughFourRange() {
        assertEquals(DisplayScaleMode.EXPLICIT_1X, DisplayScaleMode.explicit(1));
        assertEquals(DisplayScaleMode.EXPLICIT_2X, DisplayScaleMode.explicit(2));
        assertEquals(DisplayScaleMode.EXPLICIT_3X, DisplayScaleMode.explicit(3));
        assertEquals(DisplayScaleMode.EXPLICIT_4X, DisplayScaleMode.explicit(4));
        assertThrows(IllegalArgumentException.class, () -> DisplayScaleMode.explicit(0));
        assertThrows(IllegalArgumentException.class, () -> DisplayScaleMode.explicit(5));
    }

    private static void assertUniformTransform(
            DisplayViewport viewport, int sourceWidth, int sourceHeight) {
        Point2D origin = viewport.sourceToComponentTransform()
                .transform(new Point2D.Double(0, 0), null);
        Point2D horizontal = viewport.sourceToComponentTransform()
                .transform(new Point2D.Double(sourceWidth, 0), null);
        Point2D vertical = viewport.sourceToComponentTransform()
                .transform(new Point2D.Double(0, sourceHeight), null);

        assertEquals(sourceWidth * viewport.scale(), origin.distance(horizontal), EPSILON);
        assertEquals(sourceHeight * viewport.scale(), origin.distance(vertical), EPSILON);
    }

    private static void assertRotatedCornersStayInsideExactViewport(
            DisplayViewport viewport, int sourceWidth, int sourceHeight) {
        Point2D[] corners = {
                new Point2D.Double(0, 0),
                new Point2D.Double(sourceWidth, 0),
                new Point2D.Double(0, sourceHeight),
                new Point2D.Double(sourceWidth, sourceHeight)
        };
        for (Point2D corner : corners) {
            Point2D mapped = viewport.sourceToComponentTransform().transform(corner, null);
            assertTrue(mapped.getX() >= viewport.x() - EPSILON);
            assertTrue(mapped.getX() <= viewport.x() + viewport.width() + EPSILON);
            assertTrue(mapped.getY() >= viewport.y() - EPSILON);
            assertTrue(mapped.getY() <= viewport.y() + viewport.height() + EPSILON);
        }
        assertUniformTransform(viewport, sourceWidth, sourceHeight);
    }
}
