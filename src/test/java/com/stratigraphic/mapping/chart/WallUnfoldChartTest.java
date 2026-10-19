package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.wall.CompassDirection;
import com.stratigraphic.mapping.wall.UnfoldDirection;
import com.stratigraphic.mapping.wall.WallData;
import com.stratigraphic.mapping.wall.WallGeometry;
import com.stratigraphic.mapping.wall.WallParameters;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WallUnfoldChartTest {

    private static final double SAMPLE_INTERVAL = 0.00254;

    @Test
    void inclinedChartRendersColorMapAndPlateSeparators() throws Exception {

        startToolkit();

        WallData data = syntheticData(999.5, 1001.5);
        WallParameters parameters = new WallParameters(
                30, 1000.2, 0.5,
                CompassDirection.NORTH, UnfoldDirection.CLOCKWISE);
        WallGeometry geometry = new WallGeometry(data, parameters);
        WallUnfoldChart chart = new WallUnfoldChart(
                geometry, new WallAmplitudeScale(24, 27));

        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                Scene scene = new Scene(chart, chart.prefWidth(-1), chart.prefHeight(-1));
                chart.applyCss();
                chart.layout();

                WritableImage image = scene.snapshot(null);
                long coloredPixels = 0;
                for (int y = 0; y < image.getHeight(); y += 2) {
                    for (int x = 0; x < image.getWidth(); x += 2) {
                        Color color = image.getPixelReader().getColor(x, y);
                        if (color.getOpacity() > 0.9
                                && !(color.getRed() > 0.98
                                && color.getGreen() > 0.98
                                && color.getBlue() > 0.98)) {
                            coloredPixels++;
                        }
                    }
                }
                assertTrue(coloredPixels > 20_000, "展开图颜色区域为空或渲染异常");

                double scaleX = image.getWidth() / chart.prefWidth(-1);
                double scaleY = image.getHeight() / chart.prefHeight(-1);
                double perimeter = geometry.ellipsePerimeter();
                for (double boundary : geometry.plateBoundaryArcs()) {
                    int expectedX = (int) Math.round(
                            (WallUnfoldChart.MARGIN_LEFT
                                    + boundary / perimeter * WallUnfoldChart.DATA_WIDTH)
                                    * scaleX);
                    int darkRows = 0;
                    for (int y = (int) Math.round(
                            WallUnfoldChart.MARGIN_TOP * scaleY);
                         y <= (int) Math.round(
                                 (WallUnfoldChart.MARGIN_TOP
                                         + WallUnfoldChart.DATA_HEIGHT) * scaleY);
                         y++) {
                        boolean dark = false;
                        for (int dx = -2; dx <= 2; dx++) {
                            int x = expectedX + dx;
                            if (x < 0 || x >= image.getWidth()) {
                                continue;
                            }
                            Color color = image.getPixelReader().getColor(x, y);
                            if (color.getRed() < 0.25
                                    && color.getGreen() < 0.25
                                    && color.getBlue() < 0.25) {
                                dark = true;
                                break;
                            }
                        }
                        if (dark) {
                            darkRows++;
                        }
                    }
                    assertTrue(darkRows > WallUnfoldChart.DATA_HEIGHT * 0.9,
                            "极板边界黑线未贯穿展开图纵向高度");
                }

                File output = new File("target/ui-chart-snapshot.png");
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                rendered.countDown();
            }
        });

        assertTrue(rendered.await(15, TimeUnit.SECONDS), "展开图渲染超时");
        if (failure.get() != null) {
            throw new AssertionError("展开图渲染失败", failure.get());
        }
    }

    private static WallData syntheticData(double minDepth, double maxDepth) {

        int rowCount = (int) Math.round((maxDepth - minDepth) / SAMPLE_INTERVAL) + 1;
        double[] depths = new double[rowCount];
        double[][] amplitude = new double[rowCount][WallData.ELECTRODE_COUNT];
        double[][] phase = new double[rowCount][WallData.ELECTRODE_COUNT];

        for (int row = 0; row < rowCount; row++) {
            depths[row] = minDepth + row * SAMPLE_INTERVAL;
            for (int electrode = 0; electrode < WallData.ELECTRODE_COUNT; electrode++) {
                double wave = Math.sin(electrode * 0.13 + row * 0.01);
                amplitude[row][electrode] = 12 + row * 0.4 + electrode * 0.08 + wave * 3;
                phase[row][electrode] = -25 - row * 0.2 - electrode * 0.03 + wave;
            }
        }
        return new WallData(Path.of("synthetic-chart.xlsx"), depths, amplitude, phase);
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
