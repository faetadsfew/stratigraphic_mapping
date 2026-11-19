package com.stratigraphic.mapping.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WedgeTraceTest {

    @Test
    void keepsSampleShortestArcInOriginalPointOrder() {

        List<WedgeTrace.Point> points = WedgeTrace.polygonPoints(
                120, 0.6, 240, 0.4, 240, 0.8);

        assertEquals(List.of(
                new WedgeTrace.Point(120, 0.6),
                new WedgeTrace.Point(240, 0.4),
                new WedgeTrace.Point(240, 0.8)), points);
    }

    @Test
    void unwrapsAcrossZeroDegreeSeam() {

        List<WedgeTrace.Point> points = WedgeTrace.polygonPoints(
                350, 0.6, 10, 0.4, 10, 0.8);

        assertEquals(List.of(
                new WedgeTrace.Point(350, 0.6),
                new WedgeTrace.Point(370, 0.4),
                new WedgeTrace.Point(370, 0.8)), points);
    }

    @Test
    void rejectsDegenerateTriangle() {

        assertTrue(WedgeTrace.polygonPoints(
                120, 0.6, 240, 0.4, 360, 0.2).isEmpty());
    }
}
