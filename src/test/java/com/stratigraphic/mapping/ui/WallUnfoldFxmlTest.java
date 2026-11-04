package com.stratigraphic.mapping.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import com.stratigraphic.mapping.wall.WallData;
import com.stratigraphic.mapping.wall.WallGeometry;
import com.stratigraphic.mapping.wall.WallParameters;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallUnfoldFxmlTest {

    @Test
    void secondLayerFxmlLoadsWithItsController() throws Exception {

        startToolkit();

        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        WallUnfoldFxmlTest.class.getResource(
                                "/com/stratigraphic/mapping/wall-unfold-view.fxml"));
                Parent root = loader.load();
                assertNotNull(root);
                assertNotNull(loader.getController());
                assertNotNull(findButton(root, "井壁地层剖面图"));
                assertNull(findButton(root, "返回井壁地层剖面图"));

                WallParameterPane parameterPane = findParameterPane(root);
                assertNotNull(parameterPane);
                WallParameterPane.ColorRange range = parameterPane.colorRange();
                assertEquals(24, range.min(), 1e-12);
                assertEquals(27, range.max(), 1e-12);
                new Scene(root, 1440, 900);
                root.applyCss();
                Accordion accordion = (Accordion) parameterPane.lookup(".accordion");
                assertNotNull(accordion);
                assertEquals(3, accordion.getPanes().size());
                assertTrue(accordion.getPanes().stream()
                        .noneMatch(pane -> pane.getText().equals("几何模型说明")));
                GridPane geometryGrid = (GridPane) accordion.getPanes().get(1).getContent();
                assertTrue(geometryGrid.getChildren().stream()
                        .filter(node -> node instanceof Label)
                        .map(node -> ((Label) node).getText())
                        .noneMatch(text -> text.contains("井斜")));
                double[][] amplitude = new double[2][WallData.ELECTRODE_COUNT];
                for (double[] row : amplitude) {
                    java.util.Arrays.fill(row, 25.0);
                }
                WallData data = new WallData(Path.of("test.xlsx"),
                        new double[]{100, 101},
                        amplitude,
                        new double[2][WallData.ELECTRODE_COUNT]);
                parameterPane.setData(data, "test.xlsx");
                WallParameters parameters = parameterPane.toParameters();
                assertEquals(0.0, parameters.inclinationDeg());
                WallGeometry geometry = new WallGeometry(data, parameters);
                for (int electrode = 0; electrode < WallData.ELECTRODE_COUNT; electrode++) {
                    assertEquals(parameters.upperCenterMd(), geometry.topMdAtElectrode(electrode), 1e-10);
                    assertEquals(parameters.lowerCenterMd(), geometry.bottomMdAtElectrode(electrode), 1e-10);
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                loaded.countDown();
            }
        });

        assertTrue(loaded.await(10, TimeUnit.SECONDS), "FXML 加载超时");
        if (failure.get() != null) {
            throw new AssertionError("第二层 FXML 加载失败", failure.get());
        }
    }

    private static Button findButton(Node node, String text) {

        if (node instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static WallParameterPane findParameterPane(Node node) {

        if (node instanceof WallParameterPane pane) {
            return pane;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                WallParameterPane found = findParameterPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
