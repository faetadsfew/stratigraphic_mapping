package com.stratigraphic.mapping.wall;

import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallGeometryTest {

    private static final double SAMPLE_INTERVAL = 0.00254;

    @Test
    void verticalWellProducesUniformCircularArcsAndDirectMdMapping() {

        WallData data = syntheticData(999.5, 1001.5);
        WallParameters parameters = new WallParameters(
                0, 1000.2, 0.5,
                CompassDirection.NORTH, UnfoldDirection.CLOCKWISE);

        WallGeometry geometry = new WallGeometry(data, parameters);

        assertEquals(2 * Math.PI * WallParameters.WELL_RADIUS_M,
                geometry.ellipsePerimeter(), 1e-4);
        assertEquals(geometry.electrodeArc(1) - geometry.electrodeArc(0),
                geometry.electrodeArc(2) - geometry.electrodeArc(1), 1e-12);
        assertEquals(1000.2, geometry.requiredMinMd(), 1e-9);
        assertEquals(1000.7, geometry.requiredMaxMd(), 1e-9);
        assertEquals(1000.43, geometry.mdAtElectrodeAndZ(0, -1000.43), 1e-12);

        Point3D point = geometry.pointAtElectrodeAndZ(0, -1000.43);
        assertEquals(0, point.getX(), 1e-12);
        assertEquals(WallParameters.WELL_RADIUS_M, point.getY(), 1e-12);
        assertEquals(-1000.43, point.getZ(), 1e-12);
    }

    @Test
    void inclinedWellUsesNonUniformEllipseArcAndExpandedMdRange() {

        WallData data = syntheticData(999.5, 1001.5);
        WallParameters parameters = new WallParameters(
                30, 1000.2, 0.5,
                CompassDirection.NORTH, UnfoldDirection.CLOCKWISE);

        WallGeometry geometry = new WallGeometry(data, parameters);

        double firstGap = geometry.electrodeArc(1) - geometry.electrodeArc(0);
        double secondGap = geometry.electrodeArc(2) - geometry.electrodeArc(1);
        assertNotEquals(firstGap, secondGap, 1e-9);
        assertTrue(geometry.ellipsePerimeter()
                > 2 * Math.PI * WallParameters.WELL_RADIUS_M);

        double radialMd = WallParameters.WELL_RADIUS_M * 0.5
                / Math.cos(Math.toRadians(30));
        assertEquals(1000.2 - radialMd, geometry.requiredMinMd(), 1e-9);
        assertEquals(1000.7 + radialMd, geometry.requiredMaxMd(), 1e-9);

        assertTrue(geometry.requiredMinMd() < parameters.upperCenterMd());
        assertTrue(geometry.requiredMaxMd() > parameters.lowerCenterMd());
        assertNotEquals(geometry.topMdAtElectrode(0),
                geometry.topMdAtElectrode(48), 1e-9);
        assertEquals(geometry.topMdAtElectrode(0),
                geometry.mdBoundaryAtArc(geometry.electrodeArc(0),
                        geometry.zTop()), 1e-12);
        assertEquals(geometry.bottomMdAtElectrode(0)
                        - geometry.topMdAtElectrode(0),
                parameters.intervalLength(),
                1e-12);
        double minBoundary = Double.POSITIVE_INFINITY;
        double maxBoundary = Double.NEGATIVE_INFINITY;
        for (int electrode = 0;
             electrode < WallData.ELECTRODE_COUNT;
             electrode++) {
            minBoundary = Math.min(minBoundary, geometry.topMdAtElectrode(electrode));
            maxBoundary = Math.max(maxBoundary, geometry.topMdAtElectrode(electrode));
        }
        assertTrue(maxBoundary - minBoundary
                > 2 * WallParameters.WELL_RADIUS_M * 0.49
                / Math.cos(Math.toRadians(30)));

        // 30° 斜井、正北方向的水平截面精确椭圆半轴应为 R 与 R/cos(i)。
        // 若误用圆柱在水平面上的正交投影，半轴会变成 R 与 R*cos(i)。
        double centerMd = geometry.centerMdAtZ(-1000.43);
        Point3D northPoint = geometry.pointAtElectrodeAndZ(0, -1000.43);
        assertEquals(0, northPoint.getX(), 1e-12);
        assertEquals(WallParameters.WELL_RADIUS_M / Math.cos(Math.toRadians(30)),
                northPoint.getY() - centerMd * Math.sin(Math.toRadians(30)), 1e-12);

        double eastMd = geometry.mdAtElectrodeAndZ(48, -1000.43);
        Point3D eastPoint = geometry.pointAtElectrodeAndZ(48, -1000.43);
        assertEquals(WallParameters.WELL_RADIUS_M, eastPoint.getX(), 1e-12);
        assertEquals(0, eastPoint.getY() - eastMd * Math.sin(Math.toRadians(30)), 1e-12);
    }

    @Test
    void mdBetweenSamplesIsLinearlyInterpolated() {

        WallData data = syntheticData(1000, 1001);

        assertEquals(10.5, data.amplitudeAt(0, 1000 + SAMPLE_INTERVAL / 2), 1e-12);
        assertEquals(-20.5, data.phaseAt(0, 1000 + SAMPLE_INTERVAL / 2), 1e-12);
    }

    @Test
    void outOfRangeGeometryIsRejectedBeforeRendering() {

        WallData data = syntheticData(1000, 1001);
        WallParameters parameters = new WallParameters(
                30, 999.5, 0.5,
                CompassDirection.NORTH, UnfoldDirection.CLOCKWISE);

        assertThrows(IllegalArgumentException.class,
                () -> new WallGeometry(data, parameters));
    }

    @Test
    void plateBoundariesUseRealChannelEdgesAndIncludeRightPlotEnd() {

        WallData data = syntheticData(999.5, 1001.5);
        WallParameters parameters = new WallParameters(
                30, 1000.2, 0.5,
                CompassDirection.NORTH, UnfoldDirection.CLOCKWISE);
        WallGeometry geometry = new WallGeometry(data, parameters);

        double[] boundaries = geometry.plateBoundaryArcs();
        assertEquals(WallGeometry.PLATE_COUNT, boundaries.length);
        assertEquals(geometry.ellipsePerimeter(),
                boundaries[WallGeometry.PLATE_COUNT - 1], 1e-12);

        for (int plate = 0; plate < WallGeometry.PLATE_COUNT - 1; plate++) {
            int channelAfterBoundary =
                    (plate + 1) * WallGeometry.CHANNELS_PER_PLATE;
            double expected = (geometry.electrodeArc(channelAfterBoundary - 1)
                    + geometry.electrodeArc(channelAfterBoundary)) / 2;
            assertEquals(expected, boundaries[plate], 1e-12);
            assertEquals(plate == 0 ? 0 : boundaries[plate - 1],
                    geometry.plateStartArc(plate), 1e-12);
            assertEquals((geometry.plateStartArc(plate) + boundaries[plate]) / 2,
                    geometry.plateCenterArc(plate), 1e-12);
        }

        double uniformBoundary = geometry.ellipsePerimeter() / WallGeometry.PLATE_COUNT;
        boolean hasNonUniformSpacing = false;
        for (double boundary : boundaries) {
            hasNonUniformSpacing |= Math.abs(boundary - uniformBoundary) > 1e-5;
        }
        assertTrue(hasNonUniformSpacing,
                "斜井展开图的极板边界必须按真实椭圆弧长分布");
        assertThrows(IllegalArgumentException.class,
                () -> geometry.channelBoundaryArc(25));
    }

    private static WallData syntheticData(double minDepth, double maxDepth) {

        int rowCount = (int) Math.round((maxDepth - minDepth) / SAMPLE_INTERVAL) + 1;
        double[] depths = new double[rowCount];
        double[][] amplitude = new double[rowCount][WallData.ELECTRODE_COUNT];
        double[][] phase = new double[rowCount][WallData.ELECTRODE_COUNT];

        for (int row = 0; row < rowCount; row++) {
            depths[row] = minDepth + row * SAMPLE_INTERVAL;
            for (int electrode = 0; electrode < WallData.ELECTRODE_COUNT; electrode++) {
                amplitude[row][electrode] = 10 + row + electrode / 100.0;
                phase[row][electrode] = -20 - row - electrode / 100.0;
            }
        }

        return new WallData(Path.of("synthetic-test.xlsx"),
                depths, amplitude, phase);
    }
}
