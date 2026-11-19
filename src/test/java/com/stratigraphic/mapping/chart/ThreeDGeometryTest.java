package com.stratigraphic.mapping.chart;

import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.paint.Color;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeDGeometryTest {

    @Test
    void interfaceDepthTiltsTowardPositiveX() {

        double atZero = ThreeDGeometry.interfaceDepth(5.0, 120, 0.1, 0);
        double atNinety = ThreeDGeometry.interfaceDepth(5.0, 120, 0.1, 90);
        double atOneEighty = ThreeDGeometry.interfaceDepth(5.0, 120, 0.1, 180);

        assertEquals(4.942264973081038, atZero, 1e-12);
        assertEquals(5.0, atNinety, 1e-12);
        assertEquals(5.057735026918962, atOneEighty, 1e-12);
    }

    @Test
    void worldPointMapsAzimuthToXZPlane() {

        Point3D zero = ThreeDGeometry.worldPoint(1, 0, 5.6, 5.5, 10);
        Point3D ninety = ThreeDGeometry.worldPoint(1, 90, 5.6, 5.5, 10);

        assertEquals(1, zero.getX(), 1e-12);
        assertEquals(1, zero.getY(), 1e-12);
        assertEquals(0, zero.getZ(), 1e-12);
        assertEquals(0, ninety.getX(), 1e-12);
        assertEquals(1, ninety.getY(), 1e-12);
        assertEquals(1, ninety.getZ(), 1e-12);
    }

    @Test
    void generatedMeshesHaveFiniteCoordinates() {

        MeshView tube = ThreeDGeometry.tubeBand(
                1, 5.5, 10, theta -> 4.9, theta -> 5.5, Color.ORANGE, 0.8);
        MeshView annulus = ThreeDGeometry.annulus(
                5.0, 120, 0.1, 5.5, 10, 1.02, 2.6, Color.SKYBLUE, 0.08);
        MeshView disc = ThreeDGeometry.disc(
                5.3, 30, 30, 5.5, 10, 2.25, Color.RED, 0.24);
        List<WedgeTrace.Point> triangle = WedgeTrace.polygonPoints(
                120, 5.6, 240, 5.5, 240, 5.7);
        javafx.scene.Group prism = ThreeDGeometry.wedgeSolid(
                triangle, 5.5, 10, Color.ORANGE, 0.72);

        assertFinite(tube);
        assertFinite(annulus);
        assertFinite(disc);
        assertTrue(!prism.getChildren().isEmpty());
        for (javafx.scene.Node node : prism.getChildren()) {
            assertFinite((MeshView) node);
        }
        MeshView solid = (MeshView) prism.getChildren().get(0);
        TriangleMesh solidMesh = (TriangleMesh) solid.getMesh();
        assertEquals(18, solidMesh.getPoints().size() / 3);
        assertEquals(6, solidMesh.getFaces().size() / 6);
        assertEquals(8, ThreeDGeometry.wedgeOutline(
                triangle, 5.5, 10, Color.ORANGE, 0.01).getChildren().size());
        assertTrue(ThreeDGeometry.wedgeSolid(
                List.of(), 5.5, 10, Color.ORANGE, 0.7).getChildren().isEmpty());
    }


    @Test
    void prismPlaneAndWedgeMeshesAreFinite() {

        MeshView prism = ThreeDGeometry.prism(
                List.of(new Point2D(-4, -5), new Point2D(4, -5),
                        new Point2D(4, 5), new Point2D(-4, 5)),
                0, 2.4, 4, -5, 5, Color.ORANGE, 0.9);
        assertFinite(prism);

        List<Point3D> plane = ThreeDGeometry.planePolygonInBox(
                new Point3D(0, 0, 0), new Point3D(1, 1, 0.2).normalize(),
                4, -5, 5, 0, 2.4);
        assertTrue(plane.size() >= 3);

        javafx.scene.Group wedge = ThreeDGeometry.wedgePrism(
                WedgeTrace.polygonPoints(120, 5.6, 240, 5.8, 240, 5.4),
                5.6, 10, 0.78, 2.2, Color.GREEN, 0.8);
        assertTrue(!wedge.getChildren().isEmpty());
        for (javafx.scene.Node node : wedge.getChildren()) {
            if (node instanceof MeshView mesh) {
                assertFinite(mesh);
            }
        }
    }

    private static void assertFinite(MeshView view) {
        TriangleMesh mesh = (TriangleMesh) view.getMesh();
        for (int i = 0; i < mesh.getPoints().size(); i++) {
            assertTrue(Double.isFinite(mesh.getPoints().get(i)));
        }
        for (int i = 0; i < mesh.getNormals().size(); i++) {
            assertTrue(Double.isFinite(mesh.getNormals().get(i)));
        }
        for (int i = 0; i < mesh.getTexCoords().size(); i++) {
            assertTrue(Double.isFinite(mesh.getTexCoords().get(i)));
        }
        assertEquals(0, mesh.getFaces().size() % 6);
    }
}
