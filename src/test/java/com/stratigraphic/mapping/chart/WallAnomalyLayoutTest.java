package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.WallAnomalyLayout;
import com.stratigraphic.mapping.model.WedgeFracture;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对 formation_G 中的三个异常体在井壁展开图上的实际尺寸：
 * 圆直径 8 cm、方形边长 8 cm、三角形底边 12 cm / 高 5 cm，
 * 以及球心到三角形高中点的 20 cm 间距。
 */
class WallAnomalyLayoutTest {

    private static final double CM = 0.01;

    @Test
    void formationFileMatchesSketchDimensions() throws Exception {

        StratumModel model = readFormation();

        assertEquals(1, model.sphereFractures().size());
        assertEquals(1, model.cubeAnomalies().size());
        assertEquals(1, model.wedgeFractures().size());

        SphereFracture sphere = model.sphereFractures().get(0);
        assertEquals(5.15, sphere.centerDepth(), 1e-12);
        assertEquals(90, sphere.azimuthDeg(), 1e-12);
        assertEquals(0.10, sphere.distanceFromAxis(), 1e-12);
        assertEquals(4 * CM, sphere.radius(), 1e-12);

        double wellRadius = model.wellRadius();
        SphereTrace.Trace sphereTrace = SphereTrace.of(wellRadius,
                sphere.centerDepth(), sphere.azimuthDeg(),
                sphere.distanceFromAxis(), sphere.radius());
        assertEquals(SphereTrace.Kind.RING, sphereTrace.kind(),
                "球心位于井壁时应得到闭合圆");
        double sphereArcWidth = wellRadius * Math.toRadians(
                sphereTrace.thetaMaxDeg() - sphereTrace.thetaMinDeg());
        assertEquals(8 * CM, sphereArcWidth, 0.001,
                "展开图上的圆直径应为 8 cm");
        assertEquals(8 * CM, sphereTrace.maxDepth() - sphereTrace.minDepth(), 1e-4,
                "展开图上的圆高度应为 8 cm");
        assertEquals(90,
                (sphereTrace.thetaMinDeg() + sphereTrace.thetaMaxDeg()) / 2, 1e-9);
        assertEquals(5.15,
                (sphereTrace.maxDepth() + sphereTrace.minDepth()) / 2, 1e-9);

        CubeAnomaly cube = model.cubeAnomalies().get(0);
        assertEquals(5.15, cube.centerDepth(), 1e-12);
        assertEquals(270, cube.azimuthDeg(), 1e-12);
        assertEquals(0.10, cube.distanceFromAxis(), 1e-12);
        assertEquals(8 * CM, cube.sideLength(), 1e-12);

        CubeTrace.Trace cubeTrace = CubeTrace.of(wellRadius, cube);
        assertFalse(cubeTrace.isEmpty(), "正方体应与井壁相交");
        double cubeArcWidth = wellRadius * Math.toRadians(
                cubeTrace.spans().stream().mapToDouble(CubeTrace.Span::lengthDeg).sum());
        assertEquals(8 * CM, cubeArcWidth, 0.005,
                "展开图上的方形边长应约为 8 cm");
        assertEquals(8 * CM, cubeTrace.depthMax() - cubeTrace.depthMin(), 1e-9,
                "展开图上的方形高度应为 8 cm");

        WedgeFracture wedge = model.wedgeFractures().get(0);
        assertEquals(180, wedge.vertexAzimuthDeg(), 1e-12);
        List<WedgeTrace.Point> triangle = WedgeTrace.polygonPoints(
                wedge.vertexAzimuthDeg(), wedge.vertexDepth(),
                wedge.leftAzimuthDeg(), wedge.leftDepth(),
                wedge.rightAzimuthDeg(), wedge.rightDepth());
        assertEquals(3, triangle.size());

        WedgeTrace.Point apex = triangle.get(0);
        WedgeTrace.Point baseA = triangle.get(1);
        WedgeTrace.Point baseB = triangle.get(2);
        double baseArcLength = wellRadius * Math.toRadians(
                Math.abs(baseB.angleDeg() - baseA.angleDeg()));
        assertEquals(12 * CM, baseArcLength, 1e-6,
                "三角形底边弧长应为 12 cm");
        assertEquals(5 * CM, apex.depth() - baseA.depth(), 1e-9,
                "三角形高应为 5 cm");
        assertEquals(0, baseA.depth() - baseB.depth(), 1e-9,
                "底边应在同一深度");
        assertTrue(apex.depth() > baseA.depth(), "顶角应朝下");

        double midDepth = (apex.depth() + baseA.depth()) / 2;
        assertEquals(0.20, midDepth - sphere.centerDepth(), 1e-9,
                "球心到三角形高中点应为 20 cm");
        assertEquals(180, (baseA.angleDeg() + baseB.angleDeg()) / 2, 1e-9);
    }

    @Test
    void layoutFactoryReproducesFormationFile() throws Exception {

        StratumModel model = readFormation();
        double wellRadius = model.wellRadius();

        SphereFracture sphere = model.sphereFractures().get(0);
        assertEquals(WallAnomalyLayout.circleOnWall(
                        5.15, 90, wellRadius, 8 * CM, 50),
                sphere);

        CubeAnomaly cube = model.cubeAnomalies().get(0);
        assertEquals(WallAnomalyLayout.squareOnWall(
                        5.15, 270, wellRadius, 8 * CM, 70),
                cube);

        WedgeFracture expected = WallAnomalyLayout.downTriangleOnWall(
                180, 5.15 + 0.20, wellRadius, 12 * CM, 5 * CM, 40);
        WedgeFracture actual = model.wedgeFractures().get(0);
        assertEquals(expected.vertexDepth(), actual.vertexDepth(), 1e-9);
        assertEquals(expected.leftDepth(), actual.leftDepth(), 1e-9);
        assertEquals(expected.rightDepth(), actual.rightDepth(), 1e-9);
        assertEquals(expected.leftAzimuthDeg(), actual.leftAzimuthDeg(), 1e-5);
        assertEquals(expected.rightAzimuthDeg(), actual.rightAzimuthDeg(), 1e-5);
        assertEquals(expected.resistivity(), actual.resistivity(), 1e-12);
    }

    @Test
    void rendersSketchLayoutForVisualCheck() throws Exception {

        startToolkit();

        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                StratumModel model = readFormation();
                WallLineUnfoldChart chart = new WallLineUnfoldChart(model, 4.9, 5.9);
                Scene scene = new Scene(chart,
                        chart.prefWidth(-1), chart.prefHeight(-1));
                scene.getStylesheets().add(WallAnomalyLayoutTest.class.getResource(
                        "/com/stratigraphic/mapping/app.css").toExternalForm());
                chart.applyCss();
                chart.layout();

                WritableImage image = scene.snapshot(null);
                assertNotNull(image);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File("target/wall-anomaly-layout.png"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                rendered.countDown();
            }
        });

        assertTrue(rendered.await(20, TimeUnit.SECONDS), "井壁展开图渲染超时");
        if (failure.get() != null) {
            throw new AssertionError("井壁展开图渲染失败", failure.get());
        }
    }

    private static StratumModel readFormation() throws Exception {

        try (InputStream input = WallAnomalyLayoutTest.class.getResourceAsStream(
                "/com/stratigraphic/mapping/formation_G.txt")) {
            assertNotNull(input, "缺少 formation_G.txt");
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return StratumModelReader.parse(text.lines().toList());
        }
    }

    private static void startToolkit() throws InterruptedException {

        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            return;
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX 工具包启动超时");
    }
}
