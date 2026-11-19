package com.stratigraphic.mapping.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereTraceTest {

    @Test
    void formationGSphereProducesVerticalRing() {

        SphereTrace.Trace trace = SphereTrace.of(0.10, 5.6, 90, 0.15, 0.08);

        assertEquals(SphereTrace.Kind.RING, trace.kind());
        assertEquals(60.46, trace.thetaMinDeg(), 0.01);
        assertEquals(119.54, trace.thetaMaxDeg(), 0.01);
        assertEquals(5.5376, trace.minDepth(), 1e-4);
        assertEquals(5.6624, trace.maxDepth(), 1e-4);

        double arcWidth = 0.10 * Math.toRadians(trace.thetaMaxDeg() - trace.thetaMinDeg());
        double depthHeight = trace.maxDepth() - trace.minDepth();
        assertEquals(0.1031, arcWidth, 1e-4);
        assertEquals(0.1249, depthHeight, 1e-4);
        assertEquals(0.825, arcWidth / depthHeight, 0.005);

        int pointCount = SphereTrace.points(0.10, 5.6, 90, 0.15, 0.08).size();
        assertTrue(pointCount >= 230 && pointCount <= 245,
                "0.5° 采样时闭合环点数应约为 240，实际为 " + pointCount);
    }

    @Test
    void centeredSphereProducesTwoHorizontalLines() {

        SphereTrace.Trace trace = SphereTrace.of(0.10, 5.6, 90, 0, 0.18);
        double expectedHalfHeight = Math.sqrt(0.18 * 0.18 - 0.10 * 0.10);

        assertEquals(SphereTrace.Kind.TWO_LINES, trace.kind());
        assertEquals(5.6 + expectedHalfHeight, trace.maxDepth(), 1e-12);
        assertEquals(5.6 - expectedHalfHeight, trace.minDepth(), 1e-12);
        assertEquals(0.1497, expectedHalfHeight, 1e-4);
    }

    @Test
    void classifiesMissAndFullBandCases() {

        assertEquals(SphereTrace.Kind.NONE,
                SphereTrace.of(0.10, 5.6, 90, 0.15, 0.04).kind());
        assertEquals(SphereTrace.Kind.FULL_BAND,
                SphereTrace.of(0.10, 5.6, 90, 0.15, 0.30).kind());
    }
}
