package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.CubeAnomaly;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeTraceTest {

    @Test
    void formationGCubeProducesAngularBand() {

        CubeTrace.Trace trace = CubeTrace.of(
                0.10, new CubeAnomaly(5.45, 210, 0.16, 0.14, 350));

        assertFalse(trace.isEmpty());
        assertEquals(1, trace.spans().size());
        assertEquals(184.16, trace.spans().get(0).startDeg(), 0.01);
        assertEquals(235.84, trace.spans().get(0).endDeg(), 0.01);
        assertEquals(5.38, trace.depthMin(), 1e-12);
        assertEquals(5.52, trace.depthMax(), 1e-12);
        assertTrue(trace.contains(210, 5.45));
        assertFalse(trace.contains(0, 5.45));
        assertFalse(trace.contains(210, 5.60));
    }

    @Test
    void cubeOutsideWellHasNoTrace() {
        CubeTrace.Trace trace = CubeTrace.of(
                0.10, new CubeAnomaly(5.45, 210, 0.30, 0.02, 350));
        assertTrue(trace.isEmpty());
        assertTrue(trace.spans().isEmpty());
    }

    @Test
    void cubeEnclosingWellProducesFullBand() {
        CubeTrace.Trace trace = CubeTrace.of(
                0.10, new CubeAnomaly(5.45, 210, 0, 0.40, 350));
        assertEquals(1, trace.spans().size());
        assertEquals(0, trace.spans().get(0).startDeg(), 1e-12);
        assertEquals(360, trace.spans().get(0).endDeg(), 1e-12);
        assertTrue(trace.contains(123, 5.45));
    }
}
