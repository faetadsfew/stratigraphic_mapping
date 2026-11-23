package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ResponseChart;
import com.stratigraphic.mapping.chart.WallResistivityChart;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.simulation.ResponseData;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WallChartSynchronizationTest {
    @TempDir Path temp;

    @Test
    void cachedSecondPageUpdatesImmediatelyAndAllThreeChartsStayEqualAndIndependent() throws Exception {
        Path file = temp.resolve("response.txt");
        StringBuilder records = new StringBuilder();
        for (int depth = 0; depth <= 2; depth++) {
            for (int electrode = 1; electrode <= 192; electrode++) {
                records.append(depth).append(' ').append(electrode).append(" 3 4 5 53.13010235415598\n");
            }
        }
        Files.writeString(file, records);
        ResponseData data = ResponseData.read(file, (done, total) -> { });
        checkFx(() -> {
            FXMLLoader loader = loader("wall-unfold-view.fxml");
            Parent second = loader.load();
            WallUnfoldController controller = loader.getController();
            Scene scene = new Scene(second, 1440, 900);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
            controller.showResult(data);
            second.applyCss();
            second.layout();
            StackPane amplitudeBox = (StackPane) second.lookup("#amplitudeBox");
            StackPane phaseBox = (StackPane) second.lookup("#phaseBox");
            StackPane wallBox = (StackPane) second.lookup("#wallChartBox");
            ResponseChart amplitude = (ResponseChart) amplitudeBox.getChildren().get(0);
            ResponseChart phase = (ResponseChart) phaseBox.getChildren().get(0);
            assertTrue(wallBox.getChildren().get(0) instanceof Label);
            assertTrue(second.lookupAll(".ch-title").stream().anyMatch(node -> node instanceof Label label
                    && "井壁展开图（彩色）".equals(label.getText())));
            button(second, "地层剖面与井壁展开图").fire();
            Parent first = scene.getRoot();
            ParameterPane parameters = parameters(first);
            generate(first, parameters, 10);
            WallResistivityChart wall = (WallResistivityChart) wallBox.getChildren().get(0);
            assertNull(second.getScene());
            assertNull(first.lookup(".wall-inspector"));
            button(first, "电成像响应模拟").fire();
            assertSame(second, scene.getRoot());
            second.applyCss();
            second.layout();
            assertSame(amplitude, amplitudeBox.getChildren().get(0));
            assertSame(phase, phaseBox.getChildren().get(0));
            assertEqualSizes(amplitude, phase, wall);
            for (int width : new int[]{1440, 1100}) {
                second.resize(width, width == 1440 ? 900 : 720);
                second.layout();
                assertEqualSizes(amplitude, phase, wall);
                ImageIO.write(SwingFXUtils.fromFXImage(second.snapshot(null, null), null), "png",
                        Path.of("target", "ui-three-synchronized-" + width + ".png").toFile());
            }
            double positionX = 54 + (wall.getWidth() - 66) * 0.25;
            double positionY = 28 + (wall.getHeight() - 130) * 0.25;
            var original = wall.sampleAt(positionX, positionY);
            var amplitudeSample = amplitude.sampleAt(positionX, positionY);
            var phaseSample = phase.sampleAt(positionX, positionY);
            Canvas canvas = wall.getChildrenUnmodifiable().stream().filter(Canvas.class::isInstance)
                    .map(Canvas.class::cast).findFirst().orElseThrow();
            wheel(canvas, 54 + (wall.getWidth() - 66) / 2, 28 + (wall.getHeight() - 130) / 2);
            assertNotEquals(original, wall.sampleAt(positionX, positionY));
            assertEquals(amplitudeSample, amplitude.sampleAt(positionX, positionY));
            assertEquals(phaseSample, phase.sampleAt(positionX, positionY));
            var zoomed = wall.sampleAt(positionX, positionY);
            button(second, "地层剖面与井壁展开图").fire();
            button(first, "电成像响应模拟").fire();
            second.applyCss();
            second.layout();
            assertSame(wall, wallBox.getChildren().get(0));
            assertEquals(zoomed.resistivity(), wall.sampleAt(54 + (wall.getWidth() - 66) * 0.25,
                    28 + (wall.getHeight() - 130) * 0.25).resistivity());
            button(second, "重置视图").fire();
            var reset = wall.sampleAt(54 + (wall.getWidth() - 66) * 0.25, 28 + (wall.getHeight() - 130) * 0.25);
            assertEquals(original.depth(), reset.depth(), 1e-9);
            assertEquals(original.angleDeg(), reset.angleDeg(), 1e-9);
            button(second, "地层剖面与井壁展开图").fire();
            generate(first, parameters, 20);
            WallResistivityChart updated = (WallResistivityChart) wallBox.getChildren().get(0);
            assertNotSame(wall, updated);
            button(first, "电成像响应模拟").fire();
            second.applyCss();
            second.layout();
            assertEquals(20, updated.sampleAt(54 + (updated.getWidth() - 66) * 0.25,
                    28 + (updated.getHeight() - 130) * 0.25).resistivity());
            assertSame(amplitude, amplitudeBox.getChildren().get(0));
            assertSame(phase, phaseBox.getChildren().get(0));
            button(second, "地层剖面与井壁展开图").fire();
            setPoint(parameters, "unfoldPointBField", "0");
            button(first, "生成图表").fire();
            assertTrue(wallBox.getChildren().get(0) instanceof Label, "生成失败时不能保留过期模型图");
            return null;
        });
    }

    @Test
    void generationBeforeSceneAndBeforeSecondPageCreationIsDeliveredWithoutSharingAcrossWindows() throws Exception {
        checkFx(() -> {
            Parent first = loader("main-view.fxml").load();
            generate(first, parameters(first), 10);
            Scene scene = new Scene(first, 1440, 900);
            button(first, "电成像响应模拟").fire();
            Parent second = scene.getRoot();
            assertTrue(((StackPane) second.lookup("#wallChartBox")).getChildren().get(0) instanceof WallResistivityChart);
            assertFalse(button(second, "重置视图").isDisabled());
            assertFalse(button(second, "导出 PNG").isDisabled());
            Parent isolated = loader("wall-unfold-view.fxml").load();
            new Scene(isolated, 1440, 900);
            assertTrue(((StackPane) isolated.lookup("#wallChartBox")).getChildren().get(0) instanceof Label);
            assertTrue(button(isolated, "重置视图").isDisabled());
            return null;
        });
    }

    private static FXMLLoader loader(String file) {
        return new FXMLLoader(WallChartSynchronizationTest.class.getResource("/com/stratigraphic/mapping/" + file));
    }

    private static ParameterPane parameters(Parent root) {
        return (ParameterPane) ((VBox) root.lookup("#parameterSlot")).getChildren().get(0);
    }

    private static void generate(Parent root, ParameterPane parameters, double resistivity) throws Exception {
        parameters.setModel(new StratumModel(0.1, 100, 90, List.of(new StratumInterface(1)),
                List.of(resistivity, resistivity * 10)), "synchronized model");
        setPoint(parameters, "unfoldPointAField", "0");
        setPoint(parameters, "unfoldPointBField", "2");
        button(root, "生成图表").fire();
    }

    private static void setPoint(ParameterPane parameters, String name, String value) throws Exception {
        Field field = ParameterPane.class.getDeclaredField(name);
        field.setAccessible(true);
        ((TextField) field.get(parameters)).setText(value);
    }

    private static void assertEqualSizes(Region amplitude, Region phase, Region wall) {
        assertEquals(amplitude.getWidth(), phase.getWidth(), 1);
        assertEquals(amplitude.getWidth(), wall.getWidth(), 1);
        assertEquals(amplitude.getHeight(), phase.getHeight(), 0.1);
        assertEquals(amplitude.getHeight(), wall.getHeight(), 0.1);
    }

    private static Button button(Node node, String text) {
        if (node instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = button(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void wheel(Canvas canvas, double positionX, double positionY) {
        Event.fireEvent(canvas, new ScrollEvent(ScrollEvent.SCROLL, positionX, positionY, positionX, positionY,
                false, false, false, false, false, false, 0, 40, 0, 40,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                new PickResult(canvas, new javafx.geometry.Point3D(positionX, positionY, 0), 0)));
    }

    private static void checkFx(Callable<Void> action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            assertTrue(started.await(10, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyStarted) {
        }
        FutureTask<Void> task = new FutureTask<>(action);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
