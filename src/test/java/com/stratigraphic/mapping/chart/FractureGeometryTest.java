package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractureGeometryTest {

    @Test
    void zeroElevationProducesHorizontalFracture() {
        Fracture fracture = new Fracture(5.3, 0, 30, 100);

        assertEquals(1, FractureGeometry.normalZ(fracture), 1e-12);
        assertEquals(0, FractureGeometry.normalX(fracture), 1e-12);
        assertEquals(0, FractureGeometry.normalY(fracture), 1e-12);
        assertEquals(5.3, FractureGeometry.traceDepth(fracture, 0.1, 0), 1e-12);
        assertEquals(5.3, FractureGeometry.traceDepth(fracture, 0.1, 180), 1e-12);
    }

    @Test
    void ninetyDegreeElevationProducesVerticalFracture() {
        Fracture fracture = new Fracture(5.3, 90, 30, 100);
        assertEquals(0, FractureGeometry.normalZ(fracture), 1e-12);
    }

    @Test
    void azimuthMovesPeakToPositiveAngle() {
        Fracture fracture = new Fracture(5.3, 45, 30, 100);

        double atPhi = FractureGeometry.traceDepth(fracture, 0.1, 30);
        double opposite = FractureGeometry.traceDepth(fracture, 0.1, 210);

        assertTrue(atPhi < opposite,
                "φ=30° 时最高点应在 α=30°，而不是 α=210°");
        assertEquals(5.2, atPhi, 1e-9);
    }

    @Test
    void negativeAndWrappedAnglesAreEquivalent() {
        Fracture canonical = new Fracture(5.3, 150, 30, 100);
        Fracture wrapped = new Fracture(5.3, -30, -330, 100);

        assertEquals(150, wrapped.normalizedElevationDeg(), 1e-12);
        assertEquals(30, wrapped.normalizedAzimuthDeg(), 1e-12);
        assertEquals(FractureGeometry.normalX(canonical),
                FractureGeometry.normalX(wrapped), 1e-12);
        assertEquals(FractureGeometry.normalY(canonical),
                FractureGeometry.normalY(wrapped), 1e-12);
        assertEquals(FractureGeometry.normalZ(canonical),
                FractureGeometry.normalZ(wrapped), 1e-12);
        assertEquals(FractureGeometry.sectionDepth(canonical, 0.25),
                FractureGeometry.sectionDepth(wrapped, 0.25), 1e-12);
    }
}
