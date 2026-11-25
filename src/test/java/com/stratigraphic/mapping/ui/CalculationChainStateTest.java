package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.WallLineUnfoldChart;
import com.stratigraphic.mapping.model.*;
import com.stratigraphic.mapping.simulation.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CalculationChainStateTest {
    @TempDir Path temp;

    @Test
    void overlappingAnomalySlicesMatchTheActuallyDisplayedMaterial() throws Exception {
        var model = model();
        var generation = service(temp).generate(model, 0, 1);
        List<Path> indexed;
        try (var files = Files.list(generation.output())) {
            indexed = files.filter(file -> file.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
        fx(() -> {
            var chart = new WallLineUnfoldChart(model, 0, 1);
            int[] counts = new int[3];
            for (int electrode = 1; electrode <= 192; electrode++) {
                double angle = (electrode - 1) * 360.0 / 192;
                var raw = ModelInputs.parse("1 1" + System.lineSeparator() + "0 1 1" + System.lineSeparator() + Files.readString(indexed.get(electrode - 1)));
                for (double depth : new double[]{0.21, 0.35, 0.43, 0.5, 0.57, 0.66, 0.79}) {
                    if (raw.layers().stream().anyMatch(layer -> Math.abs(depth - layer.get(0)) < 2.0 / 700)) continue;
                    var displayed = chart.sampleAt(78 + 600 * angle / 360, 42 + 700 * depth);
                    var material = raw.layers().get(0);
                    for (var layer : raw.layers()) {
                        if (depth < layer.get(0)) break;
                        material = layer;
                    }
                    assertEquals(displayed.resistivity(), material.get(1),
                            "electrode=" + electrode + " depth=" + depth);
                    if (material.get(1) == 90) {counts[0]++; assertEquals(13, material.get(2));}
                    if (material.get(1) == 30) {counts[1]++; assertEquals(6, material.get(2));}
                    if (material.get(1) == 70) {counts[2]++; assertEquals(5, material.get(2));}
                }
            }
            for (int count : counts) assertTrue(count > 0);
            assertEquals(70, chart.sampleAt(78 + 300, 42 + 350).resistivity());
            return null;
        });
    }

    @Test
    void newModelClearsPreviousInputsAndPlotsAndBlocksUntilItsOwnSlicesAreReady() throws Exception {
        var generated = service(temp).generate(model(), 0, 1);
        writeReferences(temp);
        var inputs = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        Path resultFile = temp.resolve("response.txt");
        StringBuilder rows = new StringBuilder();
        for (int point = 0; point <= 1; point++) for (int electrode = 1; electrode <= 192; electrode++)
            rows.append(point).append(' ').append(electrode).append(" 3 4 5 53.13010235415598").append(System.lineSeparator());
        Files.writeString(resultFile, rows);
        var response = ResponseData.read(resultFile, (done, total) -> {});
        fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
            var controller = new WallUnfoldController(temp);
            loader.setControllerFactory(type -> controller);
            Parent root = loader.load();
            var scene = new Scene(root, 1440, 900);
            var original = new WallChartState.Snapshot(model(), 0, 1);
            WallChartState.in(scene).set(original);
            var ready = new WallChartState.Generation(original, generated.generation(), WallChartState.Status.READY, "ready");
            WallChartState.generationIn(scene).set(ready);
            set(controller, "inputs", inputs);
            set(controller, "computedResult", resultFile);
            controller.showResult(response);
            var amplitude = (StackPane) root.lookup("#amplitudeBox");
            var phase = (StackPane) root.lookup("#phaseBox");
            assertFalse(amplitude.getChildren().isEmpty());
            var updated = new WallChartState.Snapshot(model(), 0, 1);
            WallChartState.in(scene).set(updated);
            assertNull(get(controller, "inputs"));
            assertNull(get(controller, "computedResult"));
            assertTrue(amplitude.getChildren().isEmpty());
            assertTrue(phase.getChildren().isEmpty());
            Button load = (Button) get(controller, "loadButton");
            Button compute = (Button) get(controller, "computeButton");
            assertTrue(load.isDisabled());
            WallChartState.generationIn(scene).set(new WallChartState.Generation(updated, null, WallChartState.Status.GENERATING, "generating"));
            assertTrue(load.isDisabled());
            WallChartState.generationIn(scene).set(new WallChartState.Generation(updated, null, WallChartState.Status.FAILED, "failed"));
            assertTrue(load.isDisabled());
            WallChartState.generationIn(scene).set(new WallChartState.Generation(updated, null, WallChartState.Status.READY, "no ID"));
            assertTrue(load.isDisabled());
            WallChartState.generationIn(scene).set(new WallChartState.Generation(updated, generated.generation(), WallChartState.Status.READY, "ready"));
            assertFalse(load.isDisabled());
            assertTrue(compute.isDisabled());
            set(controller, "inputs", inputs);
            controller.showResult(response);
            WallChartState.generationIn(scene).set(WallChartState.generationIn(scene).get());
            assertSame(inputs, get(controller, "inputs"));
            ((TextField) get(controller, "frequencyMHz")).setText("3");
            assertNull(get(controller, "inputs"));
            assertTrue(amplitude.getChildren().isEmpty());
            assertTrue(phase.getChildren().isEmpty());
            return null;
        });
    }

    @Test
    void inputLoadAcceptsCurrentGenerationButDiscardsItIfModelChangesWhileLoading() throws Exception {
        var generated = service(temp).generate(model(), 0, 1);
        writeReferences(temp);
        WallUnfoldController controller = fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
            var target = new WallUnfoldController(temp);
            loader.setControllerFactory(type -> target);
            Parent root = loader.load();
            Scene scene = new Scene(root, 1440, 900);
            var snapshot = new WallChartState.Snapshot(model(), 0, 1);
            WallChartState.in(scene).set(snapshot);
            WallChartState.generationIn(scene).set(new WallChartState.Generation(snapshot, generated.generation(), WallChartState.Status.READY, "ready"));
            ((TextField) get(target, "samplingEnd")).setText("1");
            ((Button) get(target, "loadButton")).fire();
            assertEquals(true, get(target, "busy"));
            return target;
        });
        awaitIdle(controller);
        fx(() -> {
            assertNotNull(get(controller, "inputs"));
            assertFalse(((Button) get(controller, "computeButton")).isDisabled());
            ((Button) get(controller, "loadButton")).fire();
            assertEquals(true, get(controller, "busy"));
            Parent root = (Parent) get(controller, "appRoot");
            Scene scene = root.getScene();
            var updated = new WallChartState.Snapshot(model(), 0, 1);
            WallChartState.in(scene).set(updated);
            WallChartState.generationIn(scene).set(new WallChartState.Generation(updated, null, WallChartState.Status.GENERATING, "generating"));
            return null;
        });
        awaitIdle(controller);
        fx(() -> {
            assertNull(get(controller, "inputs"));
            assertTrue(((Button) get(controller, "computeButton")).isDisabled());
            assertTrue(((Button) get(controller, "loadButton")).isDisabled());
            return null;
        });
    }

    private static void awaitIdle(WallUnfoldController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> (boolean) get(controller, "busy"))) {
            assertTrue(System.nanoTime() < deadline, "Input loading did not finish");
            Thread.sleep(50);
        }
    }

    private static StratumModel model() {
        return new StratumModel(0.1, 100, 1, 90, List.of(new StratumInterface(0.3)),
                List.of(20.0, 40.0), List.of(3.0, 7.0), List.of(),
                List.of(new SphereFracture(0.5, 180, 0.1, 0.08, 70, 5)),
                List.of(new WedgeFracture(119, 0.5, 241, 0.8, 241, 0.2, 30, 6)), List.of(),
                List.of(new CubeAnomaly(0.5, 180, 0.1, 0.2, 90, 13)));
    }

    private static SliceGenerationService service(Path root) throws Exception {
        var constructor = SliceGenerationService.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(root);
    }

    private static void writeReferences(Path root) throws Exception {
        Files.writeString(root.resolve("call.m"), """
                freq = 2.5e6;
                cali = 8;
                sd_off = 0.005;
                rm = 1000;
                epsr_m = 6;
                epsr_all = 10;
                n_card = 192;
                input_folder = '192_Slices_Output';
                output_folder = '192_NGI_Input';
                script_dir = fileparts(mfilename('fullpath'));
                layer_data(jj+1,1),layer_data(jj,2),epsr_all
                layer_data(nl,1)+1,layer_data(nl,2),epsr_all
                fra_data(jj,1),0,fra_data(jj,3),epsr_all,fra_data(jj,5)
                """);
        Files.writeString(root.resolve("transf.m"), "% Fortran reconstruction reference");
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
