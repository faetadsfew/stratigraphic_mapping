package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WallResistivityChartTest {
    @Test
    void responsivePlotSamplesColorsAndKeepsCursorAnchoredWhileZooming() throws Exception {
        checkFx(() -> {
            StratumModel model = new StratumModel(0.1, 100, 90,
                    List.of(new StratumInterface(1)), List.of(10.0, 100.0));
            ResponseViewport viewport = new ResponseViewport();
            WallResistivityChart chart = new WallResistivityChart(model, 2, 0, viewport);
            Scene scene = new Scene(chart, 360, 700);
            chart.applyCss();
            chart.layout();
            double width = chart.getWidth() - ResponseChart.LEFT - ResponseChart.RIGHT;
            double height = chart.getHeight() - ResponseChart.TOP - ResponseChart.FOOT;
            double positionX = ResponseChart.LEFT + width * 0.25;
            double positionY = ResponseChart.TOP + height * 0.25;
            var sample = chart.sampleAt(positionX, positionY);
            assertEquals(90, sample.angleDeg(), 1e-9);
            assertEquals(0.5, sample.depth(), 1e-9);
            assertEquals(10, sample.resistivity());
            assertEquals(100, chart.sampleAt(positionX, ResponseChart.TOP + height * 0.75).resistivity());
            Color expected = new ColorScale(10, 100).colorOf(10);
            Color rendered = scene.snapshot(null).getPixelReader().getColor((int) positionX, (int) positionY);
            assertEquals(expected.getRed(), rendered.getRed(), 0.01);
            assertEquals(expected.getGreen(), rendered.getGreen(), 0.01);
            assertEquals(expected.getBlue(), rendered.getBlue(), 0.01);
            Label inspector = (Label) chart.lookup(".wall-inspector");
            assertFalse(inspector.isVisible());
            Canvas canvas = chart.getChildrenUnmodifiable().stream().filter(Canvas.class::isInstance)
                    .map(Canvas.class::cast).findFirst().orElseThrow();
            click(canvas, positionX, positionY);
            assertTrue(inspector.isVisible());
            assertTrue(inspector.getText().contains("深度：0.50000000 m"));
            assertTrue(inspector.getText().contains("方位角：90.00000°"));
            assertTrue(inspector.getText().contains("电阻率：10.000000 Ω·m"));
            wheel(canvas, positionX, positionY, 40);
            assertEquals(0.8, viewport.window().span(), 1e-12);
            var zoomed = chart.sampleAt(positionX, positionY);
            assertEquals(sample.depth(), zoomed.depth(), 1e-9);
            assertEquals(sample.angleDeg(), zoomed.angleDeg(), 1e-9);
            assertEquals(sample.resistivity(), zoomed.resistivity());
            var beforeOutside = viewport.window();
            wheel(canvas, 1, 1, 40);
            assertEquals(beforeOutside, viewport.window());
            click(canvas, 1, 1);
            assertFalse(inspector.isVisible());
            assertNull(chart.sampleAt(Double.NaN, positionY));
            assertNull(chart.sampleAt(positionX, chart.getHeight() - 20));
            viewport.reset();
            assertEquals(sample, chart.sampleAt(positionX, positionY));
            chart.dispose();
        });
    }

    @Test
    void samplerUsesVisibleFracturesSpheresAndWrappedWedgesRatherThanBackgroundLayers() throws Exception {
        checkFx(() -> {
            StratumModel model = new StratumModel(0.1, 100, 90,
                    List.of(new StratumInterface(1)), List.of(10.0, 100.0),
                    List.of(new Fracture(0.5, 0, 0, 77)),
                    List.of(new SphereFracture(0.8, 90, 0.1, 0.05, 500),
                            new SphereFracture(1.4, 0, 0.1, 0.05, 600)),
                    List.of(new WedgeFracture(180, 1.1, 120, 1.7, 240, 1.7, 30),
                            new WedgeFracture(0, 0.9, 330, 1.8, 30, 1.8, 25)));
            WallLineUnfoldChart source = new WallLineUnfoldChart(model, 0, 2, false);
            assertEquals(77, at(source, 200, 0.5).resistivity());
            assertEquals(10, at(source, 200, 0.52).resistivity());
            assertEquals(500, at(source, 90, 0.8).resistivity());
            assertEquals(10, at(source, 130, 0.8).resistivity());
            assertEquals(30, at(source, 180, 1.4).resistivity());
            assertEquals(25, at(source, 340, 1.65).resistivity());
            assertEquals(600, at(source, 0, 1.4).resistivity());
            assertEquals(600, at(source, 360, 1.4).resistivity());
            assertNull(source.sampleAt(Double.NaN, 100));
            assertNull(source.sampleAt(1, 1));
            assertNull(source.lookup(".wall-inspector"));
        });
    }

    private static WallLineUnfoldChart.Sample at(WallLineUnfoldChart source, double angle, double depth) {
        return source.sampleAt(WallLineUnfoldChart.MARGIN_LEFT + angle / 360 * WallLineUnfoldChart.PLOT_WIDTH,
                WallLineUnfoldChart.MARGIN_TOP + depth / 2 * WallLineUnfoldChart.PLOT_HEIGHT);
    }

    @Test
    void surfaceTextSamplingFollowsGlyphsAndDoesNotTreatTheirEntireBoxAsResistiveMaterial() throws Exception {
        checkFx(() -> {
            StratumModel model = new StratumModel(0.1, 100, 90,
                    List.of(new StratumInterface(1)), List.of(10.0, 100.0),
                    List.of(), List.of(), List.of(), List.of(new SurfaceText("M", 180, 0.5, 180, 1.2, 900)));
            WallLineUnfoldChart source = new WallLineUnfoldChart(model, 0, 2, false);
            Scene scene = new Scene(source);
            source.applyCss();
            source.layout();
            var pixels = scene.snapshot(null).getPixelReader();
            ColorScale scale = new ColorScale(10, 900);
            Color background = scale.colorOf(10);
            int glyphs = 0;
            int blank = 0;
            for (int positionX = 340; positionX < 416; positionX++) {
                for (int positionY = 185; positionY < 240; positionY++) {
                    Color color = pixels.getColor(positionX, positionY);
                    var sample = source.sampleAt(positionX + 0.5, positionY + 0.5);
                    if (sample.resistivity() == 900) {
                        assertFalse(sameColor(color, background));
                        glyphs++;
                    } else if (sample.resistivity() == 10) {
                        assertEquals(10, sample.resistivity());
                        blank++;
                    }
                }
            }
            assertTrue(glyphs > 10);
            assertTrue(blank > 300);
        });
    }

    private static boolean sameColor(Color first, Color second) {
        return Math.abs(first.getRed() - second.getRed()) < 0.005
                && Math.abs(first.getGreen() - second.getGreen()) < 0.005
                && Math.abs(first.getBlue() - second.getBlue()) < 0.005;
    }

    private static void checkFx(Runnable action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            assertTrue(started.await(10, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyStarted) {
        }
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private static void click(Canvas canvas, double positionX, double positionY) {
        Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, positionX, positionY,
                positionX, positionY, MouseButton.PRIMARY, 1, false, false, false, false,
                false, false, false, false, false, true,
                new PickResult(canvas, new javafx.geometry.Point3D(positionX, positionY, 0), 0)));
    }

    private static void wheel(Canvas canvas, double positionX, double positionY, double delta) {
        Event.fireEvent(canvas, new ScrollEvent(ScrollEvent.SCROLL, positionX, positionY, positionX, positionY,
                false, false, false, false, false, false, 0, delta, 0, delta,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                new PickResult(canvas, new javafx.geometry.Point3D(positionX, positionY, 0), 0)));
    }
}
