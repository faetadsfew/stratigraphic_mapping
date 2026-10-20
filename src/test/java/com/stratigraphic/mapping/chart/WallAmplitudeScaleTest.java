package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallAmplitudeScaleTest {

    @Test
    void logarithmicScaleUsesFixedBoundsAndClampsOutliers() {

        WallAmplitudeScale scale = new WallAmplitudeScale(24, 27);

        assertEquals(24, scale.min(), 1e-12);
        assertEquals(27, scale.max(), 1e-12);
        assertEquals(scale.colorOf(24), scale.colorOf(1));
        assertEquals(scale.colorOf(27), scale.colorOf(1000));
        assertNotEquals(scale.colorOf(24), scale.colorOf(27));
    }

    @Test
    void nonPositiveAndInvalidAmplitudesRenderAsWhite() {

        WallAmplitudeScale scale = new WallAmplitudeScale(24, 27);

        assertEquals(Color.WHITE, scale.colorOf(0));
        assertEquals(Color.WHITE, scale.colorOf(-1));
        assertEquals(Color.WHITE, scale.colorOf(Double.NaN));
        assertEquals(Color.WHITE, scale.colorOf(Double.POSITIVE_INFINITY));
        assertTrue(scale.colorOf(25.5).getOpacity() > 0);
    }
}
