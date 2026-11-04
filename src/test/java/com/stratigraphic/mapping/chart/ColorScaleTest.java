package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorScaleTest {

    @Test
    void resistivityUsesTheSameYellowToRedPaletteAsWallAmplitude() {
        ColorScale scale = new ColorScale(1, 100);
        WallAmplitudeScale reference = new WallAmplitudeScale(1, 100);
        assertEquals(Color.web("#FFF7B2"), scale.colorOf(1));
        assertEquals(Color.web("#7F1D1D"), scale.colorOf(100));
        for (double value : new double[]{1, 2, 5, 10, 20, 50, 100}) {
            assertEquals(reference.colorOf(value), scale.colorOf(value));
        }
    }

    @Test
    void boundsRoundToNiceNumbersAndTheLowerBoundStaysAboveZero() {

        ColorScale scale = new ColorScale(18, 90);

        assertEquals(10, scale.min(), 1e-12);
        assertEquals(100, scale.max(), 1e-12);
        assertTrue(scale.min() > 0, "色标下界必须大于 0");
    }

    @Test
    void aSingleResistivityStillGetsAUsableSpan() {

        ColorScale scale = new ColorScale(20, 20);

        assertEquals(20, scale.min(), 1e-12);
        assertEquals(200, scale.max(), 1e-12);
    }

    @Test
    void colorDependsOnTheLogarithmOfResistivity() {

        ColorScale decade = new ColorScale(1, 10);
        ColorScale twoDecades = new ColorScale(1, 100);

        // 两个色标的对数中点分别是 √10 与 10，应当落到同一个颜色上；
        // 若按线性插值，这两个位置会不一样
        assertEquals(decade.colorOf(Math.sqrt(10)), twoDecades.colorOf(10));
        assertEquals(decade.colorOf(1), twoDecades.colorOf(1));
        assertEquals(decade.colorOf(10), twoDecades.colorOf(100));
    }

    @Test
    void lowResistivityIsLightAndHighResistivityIsDark() {

        ColorScale scale = new ColorScale(1, 100);

        double light = brightness(scale.colorOf(1));
        double mid = brightness(scale.colorOf(10));
        double dark = brightness(scale.colorOf(100));

        assertTrue(light > mid && mid > dark, "颜色应随电阻率由浅到深");
        assertNotEquals(scale.colorOf(1), scale.colorOf(100));
    }

    @Test
    void outliersClampToTheEndsAndNonPositiveValuesFallBackToTheLightest() {

        ColorScale scale = new ColorScale(1, 100);

        assertEquals(scale.colorOf(1), scale.colorOf(0.01));
        assertEquals(scale.colorOf(100), scale.colorOf(10000));

        Color lightest = scale.colorOf(1);
        assertEquals(lightest, scale.colorOf(0));
        assertEquals(lightest, scale.colorOf(-5));
        assertEquals(lightest, scale.colorOf(Double.NaN));
    }

    private static double brightness(Color color) {
        return color.getRed() + color.getGreen() + color.getBlue();
    }
}
