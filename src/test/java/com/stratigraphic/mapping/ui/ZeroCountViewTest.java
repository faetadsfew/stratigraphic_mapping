package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.SectionColorChart;
import com.stratigraphic.mapping.chart.WallLineUnfoldChart;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ZeroCountViewTest {
    @Test
    void anomalyOnlyInputRendersEveryAnomalyWithoutInventingInterfaces() throws Exception {
        var model = StratumModelReader.read(Path.of(getClass().getResource("/formation-anomalies-only.txt").toURI()));
        fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/main-view.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            ParameterPane pane = (ParameterPane) field(controller, "parameterPane");
            pane.setModel(model, "anomalies only");
            assertEquals(0, pane.toModel().interfaceCount());
            assertEquals(1, ((List<?>) field(pane, "rhoFields")).size());
            assertTrue(((List<?>) field(pane, "depthFields")).isEmpty());
            var interval = pane.toUnfoldInterval();
            assertEquals(0, interval.start());
            assertEquals(1.5, interval.end());
            new Scene(root, 1440, 900);
            invoke(controller, "generateCharts");
            root.applyCss();root.layout();
            var wall = new WallLineUnfoldChart(pane.toModel(), interval.start(), interval.end());
            assertFalse(wall.lookupAll(".sphere-fracture").isEmpty());
            assertFalse(wall.lookupAll(".cube-anomaly").isEmpty());
            assertFalse(wall.lookupAll(".wedge-fracture").isEmpty());
            assertEquals(50, wall.sampleAt(78 + 600 * 90.0 / 360, 42 + 700 * 0.6 / 1.5).resistivity());
            assertEquals(70, wall.sampleAt(78 + 600 * 210.0 / 360, 42 + 700 * 0.45 / 1.5).resistivity());
            assertEquals(40, wall.sampleAt(78 + 600 * 200.0 / 360, 42 + 700 * 0.7 / 1.5).resistivity());
            assertEquals(20, wall.sampleAt(78, 42 + 700 * 0.2 / 1.5).resistivity());
            assertFalse(((StackPane) field(controller, "colorChartBox")).getChildren().isEmpty());
            assertFalse(((StackPane) field(controller, "wallChartBox")).getChildren().isEmpty());
            assertNotNull(field(controller, "threeDChart"));
            return null;
        });
    }

    @Test
    void interfaceCountCanBeEditedOrReducedToZeroWhileOneBackgroundRowRemains() throws Exception {
        var model = StratumModelReader.read(Path.of(getClass().getResource("/formation-layers-only.txt").toURI()));
        fx(() -> {
            ParameterPane pane = new ParameterPane();
            pane.setModel(model, "two layers");
            invoke(pane, "removeInterface");
            assertEquals(0, pane.toModel().interfaceCount());
            assertEquals(List.of(20.0), pane.toModel().resistivities());
            var count = (TextField) field(pane, "countField");
            count.setText("2");invoke(pane, "applyCount");
            assertEquals(2, ((List<?>) field(pane, "depthFields")).size());
            count.setText("0");invoke(pane, "applyCount");
            assertEquals(0, pane.toModel().interfaceCount());
            assertEquals(1, ((List<?>) field(pane, "rhoFields")).size());
            assertEquals(1, ((List<?>) field(pane, "dielectricFields")).size());
            assertDoesNotThrow(() -> new SectionColorChart(pane.toModel()));
            return null;
        });
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static <Value> Value fx(Callable<Value> action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {Platform.startup(started::countDown);} catch (IllegalStateException alreadyStarted) {started.countDown();}
        assertTrue(started.await(10, TimeUnit.SECONDS));
        FutureTask<Value> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }
}
