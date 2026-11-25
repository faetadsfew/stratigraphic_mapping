package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegratedChartsRenderTest {

    @Test
    void colorSectionAndWallLineChartRenderTogether() throws Exception {

        startToolkit();

        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        IntegratedChartsRenderTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();

                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");

                StratumModel model = new StratumModel(
                        0.1, 100, 92,
                        List.of(new StratumInterface(0.8),
                                new StratumInterface(1.0),
                                new StratumInterface(1.2),
                                new StratumInterface(1.4)),
                        List.of(20.0, 16.0, 12.0, 28.0, 40.0),
                        List.of(new Fracture(0.9, 30, 30, 20),
                                new Fracture(1.3, 60, 30, 200)),
                        List.of(), List.of(), List.of(),
                        List.of(new CubeAnomaly(1.05, 45, 0.12, 0.12, 120)));
                parameterPane.setModel(model, "synthetic parameters");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        IntegratedChartsRenderTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();
                TitledPane fracturePane = findTitledPane(root, "裂缝");
                if (fracturePane != null) {
                    fracturePane.setExpanded(true);
                    root.applyCss();
                    root.layout();
                }

                WritableImage image = scene.snapshot(null);
                File output = new File("target/ui-integrated-snapshot.png");
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);

                long centerColored = coloredPixels(image, 740, 1060, 130, 820);
                long rightColored = coloredPixels(image, 1100, 1438, 130, 820);
                long rightLines = darkPixels(image, 1100, 1438, 130, 820);
                assertTrue(centerColored > 5_000, "中间彩色剖面没有正常渲染");
                assertTrue(rightColored > 5_000, "右侧井壁展开图没有正常上色");
                assertTrue(rightLines > 500, "右侧井壁展开图没有正常渲染");

                Node threeDChart = root.lookup(".three-d-chart");
                assertNotNull(threeDChart, "首页应包含已生成的三维图");
                Button simulationButton = root.lookupAll(".tool-button").stream()
                        .filter(node -> node instanceof Button button
                                && "电成像响应模拟".equals(button.getText()))
                        .map(node -> (Button) node).findFirst().orElseThrow();
                simulationButton.fire();
                Parent simulationRoot = scene.getRoot();
                assertNotSame(root, simulationRoot);
                assertTrue(simulationRoot.getStyleClass().contains("imaging-page"));
                simulationRoot.applyCss();
                simulationRoot.layout();
                assertNotNull(simulationRoot.lookup("#imagingStep2"), "应保留本地模拟计算流程");
                Button backButton = simulationRoot.lookupAll(".tool-button").stream()
                        .filter(node -> node instanceof Button button
                                && "地层剖面与井壁展开图".equals(button.getText()))
                        .map(node -> (Button) node).findFirst().orElseThrow();
                backButton.fire();
                assertSame(root, scene.getRoot(), "返回首页不应重建页面");
                assertSame(threeDChart, root.lookup(".three-d-chart"), "页面切换应保留三维图");
                assertEquals(model.resistivities(), parameterPane.toModel().resistivities());
                assertEquals(model.fractures(), parameterPane.toModel().fractures());
                simulationButton.fire();
                assertSame(simulationRoot, scene.getRoot(), "再次进入模拟页应保留页面状态");
                backButton.fire();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                rendered.countDown();
            }
        });

        assertTrue(rendered.await(20, TimeUnit.SECONDS), "集成图表渲染超时");
        if (failure.get() != null) {
            throw new AssertionError("集成图表渲染失败", failure.get());
        }
    }

    @Test
    void formationGInputRoundTripsThroughParameterPane() throws Exception {

        startToolkit();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                StratumModel model;
                try (InputStream input = IntegratedChartsRenderTest.class.getResourceAsStream(
                        "/com/stratigraphic/mapping/formation_G.txt")) {
                    assertNotNull(input);
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    model = StratumModelReader.parse(text.lines().toList());
                }

                ParameterPane pane = new ParameterPane();
                pane.setModel(model, "formation_G.txt");
                StratumModel result = pane.toModel();

                assertEquals(5, result.interfaceCount());
                assertEquals(120, result.dipAngle(), 1e-12);
                assertEquals(2, result.fractures().size());
                assertEquals(5.3, result.fractures().get(0).depth(), 1e-12);
                assertEquals(60, result.fractures().get(1).normalElevationDeg(), 1e-12);
                assertEquals(1, result.sphereFractures().size());
                assertEquals(5.15, result.sphereFractures().get(0).centerDepth(), 1e-12);
                assertEquals(0.04, result.sphereFractures().get(0).radius(), 1e-12);
                assertEquals(50, result.sphereFractures().get(0).resistivity(), 1e-12);
                assertEquals(1, result.cubeAnomalies().size());
                assertEquals(5.15, result.cubeAnomalies().get(0).centerDepth(), 1e-12);
                assertEquals(0.08, result.cubeAnomalies().get(0).sideLength(), 1e-12);
                assertEquals(70, result.cubeAnomalies().get(0).resistivity(), 1e-12);
                assertEquals(1, result.surfaceTexts().size());
                assertEquals("文字", result.surfaceTexts().get(0).content());
                assertEquals(5.9, result.surfaceTexts().get(0).centerDepth(), 1e-12);

                StratumModel twoSpheres = new StratumModel(
                        model.wellRadius(), model.wellResistivity(), model.dipAngle(),
                        model.interfaces(), model.resistivities(), model.fractures(),
                        List.of(model.sphereFractures().get(0),
                                new SphereFracture(5.5, 270, 0.12, 0.05, 80)));
                pane.setModel(twoSpheres, "two spheres");
                assertEquals(2, pane.toModel().sphereFractures().size());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                checked.countDown();
            }
        });

        assertTrue(checked.await(15, TimeUnit.SECONDS), "formation_G 输入面板检查超时");
        if (failure.get() != null) {
            throw new AssertionError("formation_G 输入面板回读失败", failure.get());
        }
    }

    private static long coloredPixels(WritableImage image,
                                      int x0, int x1, int y0, int y1) {
        long count = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                Color color = image.getPixelReader().getColor(x, y);
                if (!(color.getRed() > 0.97
                        && color.getGreen() > 0.97
                        && color.getBlue() > 0.97)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long darkPixels(WritableImage image,
                                   int x0, int x1, int y0, int y1) {
        long count = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                Color color = image.getPixelReader().getColor(x, y);
                if (color.getBrightness() < 0.72) {
                    count++;
                }
            }
        }
        return count;
    }

    private static TitledPane findTitledPane(Parent parent, String title) {
        for (javafx.scene.Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof TitledPane pane && title.equals(pane.getText())) {
                return pane;
            }
            if (node instanceof Parent child) {
                TitledPane found = findTitledPane(child, title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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
