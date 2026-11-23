package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.simulation.ResponseData;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ResponseChartInteractionTest {
    @Test void wheelPanAndClickKeepChartsIndependentAndUseTheirRenderedCoordinates() throws Exception {
        Path fixture = Path.of("target", "interaction-six-column.txt");
        StringBuilder content = new StringBuilder();
        for (double md : new double[]{10, 10.1, 10.7, 11.5}) {
            for (int electrode = 192; electrode >= 1; electrode--) {
                double real = electrode + md, imaginary = -electrode / 2.0;
                content.append(md).append(' ').append(electrode).append(' ').append(real).append(' ').append(imaginary)
                        .append(' ').append(Math.hypot(real, imaginary)).append(' ').append(Math.toDegrees(Math.atan2(imaginary, real))).append('\n');
            }
        }
        Files.writeString(fixture, content);
        ResponseData data = ResponseData.read(fixture, (done, total) -> {});
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); assertTrue(started.await(10, TimeUnit.SECONDS)); }
        catch (IllegalStateException ignored) { }
        FutureTask<Void> check = new FutureTask<>(() -> {
            var viewport = new ResponseViewport();
            var phaseViewport = new ResponseViewport();
            var amplitude = new ResponseChart(data, false, data.minPositive(), data.max(false), viewport);
            var phase = new ResponseChart(data, true, data.min(true), data.max(true), phaseViewport);
            HBox root = new HBox(amplitude, phase);
            HBox.setHgrow(amplitude, Priority.ALWAYS); HBox.setHgrow(phase, Priority.ALWAYS);
            Scene scene = new Scene(root, 900, 750);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
            root.applyCss(); root.layout();
            Canvas canvas = (Canvas) amplitude.getChildrenUnmodifiable().get(0);
            double width = amplitude.getWidth() - 66, height = amplitude.getHeight() - 130;
            double anchorX = 54 + width * 0.42, anchorY = 28 + height * 0.4;
            var original = amplitude.sampleAt(anchorX, anchorY);
            var originalColor = amplitude.snapshot(null, null).getPixelReader().getColor((int) anchorX, (int) anchorY);
            assertEquals(81, original.electrode()); assertEquals(10.7, original.md());
            wheel(canvas, anchorX, anchorY, 40);
            assertEquals(0.8, viewport.window().span(), 1e-12);
            assertEquals(original, amplitude.sampleAt(anchorX, anchorY));
            assertEquals(original, phase.sampleAt(anchorX, anchorY));
            wheel(canvas, anchorX, anchorY, 80);
            double span = viewport.window().span();
            assertEquals(0.512, span, 1e-12);
            assertEquals(10.1, amplitude.sampleAt(anchorX, 28).md());
            assertEquals(10.7, amplitude.sampleAt(anchorX, 28 + height).md());
            assertEquals(10 + viewport.window().top() * 1.5, Double.parseDouble(amplitude.depthLabelAt(0)), 0.0005);
            assertEquals(10 + (viewport.window().top() + span) * 1.5, Double.parseDouble(amplitude.depthLabelAt(1)), 0.0005);
            assertEquals(new ResponseViewport.Window(0, 0, 1), phaseViewport.window());
            assertEquals(10, Double.parseDouble(phase.depthLabelAt(0)));
            assertEquals(11.5, Double.parseDouble(phase.depthLabelAt(1)));
            var enlargedColor = amplitude.snapshot(null, null).getPixelReader().getColor((int) anchorX, (int) anchorY);
            assertEquals(originalColor, enlargedColor);
            double columnCenter = 80 + 3 * 3 + 0.5;
            double selectedX = 54 + (columnCenter / 213 - viewport.window().left()) / span * width;
            double selectedY = 28 + ((10.7 - 10) / 1.5 - viewport.window().top()) / span * height;
            assertEquals(original, amplitude.sampleAt(selectedX, selectedY));
            double phaseSelectedX = 54 + columnCenter / 213 * width;
            double phaseSelectedY = 28 + (10.7 - 10) / 1.5 * height;
            assertEquals(original, phase.sampleAt(phaseSelectedX, phaseSelectedY));
            click(canvas, selectedX, selectedY);
            Label inspector = (Label) amplitude.lookup(".response-inspector");
            assertTrue(inspector.isVisible());
            assertEquals("深度 MD：10.7 m\n电极编号：81\n模值：" + original.amplitude() + " Ω·m", inspector.getText());
            assertFalse(inspector.getText().contains("相位"));
            assertTrue(inspector.getLayoutX() >= 54 && inspector.getLayoutX() + inspector.getWidth() <= 54 + width);
            assertTrue(inspector.getLayoutY() >= 28 && inspector.getLayoutY() + inspector.getHeight() <= 28 + height);
            Canvas phaseCanvas = (Canvas) phase.getChildrenUnmodifiable().get(0);
            click(phaseCanvas, phaseSelectedX, phaseSelectedY);
            Label phaseInspector = (Label) phase.lookup(".response-inspector");
            assertTrue(phaseInspector.isVisible());
            assertEquals("深度 MD：10.7 m\n电极编号：81\n相位：" + original.phase() + "°", phaseInspector.getText());
            assertFalse(phaseInspector.getText().contains("模值"));
            ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", Path.of("target", "ui-interaction-zoom.png").toFile());
            double gapX = 54 + (79.5 / 213 - viewport.window().left()) / span * width;
            assertNull(amplitude.sampleAt(gapX, selectedY));
            click(canvas, gapX, selectedY);
            assertFalse(inspector.isVisible());
            var gapColor = amplitude.snapshot(null, null).getPixelReader().getColor((int) (gapX - 2), (int) selectedY);
            assertTrue(gapColor.getRed() > 0.99 && gapColor.getGreen() > 0.99 && gapColor.getBlue() > 0.99);
            var beforeOutsideScroll = viewport.window();
            wheel(canvas, 20, anchorY, 40);
            assertEquals(beforeOutsideScroll, viewport.window());
            mouse(canvas, MouseEvent.MOUSE_PRESSED, anchorX, anchorY, true);
            mouse(canvas, MouseEvent.MOUSE_DRAGGED, anchorX + 20, anchorY + 30, true);
            assertEquals(beforeOutsideScroll.left() - 20 / width * span, viewport.window().left(), 1e-12);
            assertEquals(beforeOutsideScroll.top() - 30 / height * span, viewport.window().top(), 1e-12);
            assertEquals(new ResponseViewport.Window(0, 0, 1), phaseViewport.window());
            assertEquals(original, phase.sampleAt(phaseSelectedX, phaseSelectedY));
            var amplitudeBeforePhaseWheel = viewport.window();
            wheel(phaseCanvas, anchorX, anchorY, 40);
            assertEquals(0.8, phaseViewport.window().span(), 1e-12);
            assertEquals(amplitudeBeforePhaseWheel, viewport.window());
            wheel(phaseCanvas, anchorX, anchorY, -40);
            assertEquals(new ResponseViewport.Window(0, 0, 1), phaseViewport.window());
            assertEquals(amplitudeBeforePhaseWheel, viewport.window());
            viewport.reset();
            assertEquals(1, amplitude.sampleAt(54 + width * 0.5 / 213, 28).electrode());
            assertEquals(192, phase.sampleAt(54 + width * 212.5 / 213, 28 + height).electrode());
            assertEquals(11.5, phase.sampleAt(54 + width * 212.5 / 213, 28 + height).md());
            var shiftedRows = new java.util.TreeMap<Double, ResponseData.Sample[]>();
            for (int row = 0; row < data.size(); row++) {
                var samples = new ResponseData.Sample[192];
                for (int electrode = 1; electrode <= 192; electrode++) {
                    var sample = data.sample(row, electrode);
                    samples[electrode - 1] = new ResponseData.Sample(sample.md() + 2990, electrode, sample.real(), sample.imaginary(), sample.amplitude(), sample.phase());
                }
                shiftedRows.put(data.depth(row) + 2990, samples);
            }
            var shifted = new ResponseChart(new ResponseData(fixture, shiftedRows), false, data.minPositive(), data.max(false), viewport);
            viewport.zoom(0.5, 0.5, 128);
            double lastTick = Double.parseDouble(shifted.depthLabelAt(0));
            for (int tick = 1; tick <= 5; tick++) {
                double currentTick = Double.parseDouble(shifted.depthLabelAt(tick / 5.0));
                assertTrue(currentTick > lastTick);
                assertEquals(3000 + (viewport.window().top() + tick / 5.0 * viewport.window().span()) * 1.5, currentTick, 0.00005);
                lastTick = currentTick;
            }
            shifted.dispose();
            amplitude.dispose(); phase.dispose();
            return null;
        });
        Platform.runLater(check); check.get(20, TimeUnit.SECONDS);
    }

    private static void wheel(Canvas canvas, double x, double y, double delta) {
        Event.fireEvent(canvas, new ScrollEvent(ScrollEvent.SCROLL, x, y, x, y, false, false, false, false,
                false, false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(canvas, new javafx.geometry.Point3D(x, y, 0), 0)));
    }
    private static void click(Canvas canvas, double x, double y) {
        mouse(canvas, MouseEvent.MOUSE_PRESSED, x, y, true);
        mouse(canvas, MouseEvent.MOUSE_CLICKED, x, y, false);
    }
    private static void mouse(Canvas canvas, javafx.event.EventType<MouseEvent> type, double x, double y, boolean pressed) {
        Event.fireEvent(canvas, new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, pressed, false, false, false, false, true, new PickResult(canvas, new javafx.geometry.Point3D(x, y, 0), 0)));
    }
}
