package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.SectionColorChart;
import com.stratigraphic.mapping.chart.WallLineUnfoldChart;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.WedgeFracture;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractureClickInteractionTest {

    @Test
    void clickingWedgeInteriorOpensItsParameters() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");

                WedgeFracture wedge = new WedgeFracture(
                        120, 5.6, 240, 5.5, 240, 5.7, 10);
                StratumModel model = new StratumModel(
                        0.1, 100, 90,
                        List.of(new StratumInterface(5.0),
                                new StratumInterface(5.8)),
                        List.of(20.0, 35.0, 60.0),
                        List.of(), List.of(), List.of(wedge));
                parameterPane.setModel(model, "wedge click test");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                WallLineUnfoldChart chart = findNode(root, WallLineUnfoldChart.class);
                assertNotNull(chart, "未找到井壁展开图");
                double insideX = 78 + 200.0 / 360.0 * 600;
                double insideY = 42 + (5.6 - 4.9) / 1.0 * 700;
                chart.getOnMouseClicked().handle(mouseClick(insideX, insideY));

                PauseTransition settle = new PauseTransition(Duration.millis(1000));
                settle.setOnFinished(event -> {
                    try {
                        TitledPane fracturePane = findTitledPane(root, "裂缝");
                        assertNotNull(fracturePane, "未找到裂缝参数分组");
                        assertTrue(fracturePane.isExpanded(), "点击楔形体后裂缝分组未展开");

                        @SuppressWarnings("unchecked")
                        List<Object> wedgeRows = (List<Object>) readField(
                                parameterPane, "wedgeRows");
                        assertFalse(wedgeRows.isEmpty(), "未找到楔形体参数卡");
                        Node wedgeCard = (Node) readField(wedgeRows.get(0), "card");
                        assertTrue(wedgeCard.getStyleClass().contains("fracture-card-selected"),
                                "楔形体参数卡没有高亮");

                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                                new File("target/ui-wedge-selection.png"));
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        checked.countDown();
                    }
                });
                settle.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                checked.countDown();
            }
        });

        assertTrue(checked.await(20, TimeUnit.SECONDS), "楔形体点击交互检查超时");
        if (failure.get() != null) {
            throw new AssertionError("楔形体点击交互检查失败", failure.get());
        }
    }

    @Test
    void clickingSphereFractureOpensItsOwnParameters() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                StratumModel model;
                try (InputStream input = FractureClickInteractionTest.class.getResourceAsStream(
                        "/com/stratigraphic/mapping/formation_G.txt")) {
                    assertNotNull(input);
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    model = StratumModelReader.parse(text.lines().toList());
                }

                FXMLLoader loader = new FXMLLoader(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");
                parameterPane.setModel(model, "formation_G.txt");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                assertEquals(3, root.lookupAll(".sphere-fracture").size(),
                        "球体交线应包含 0°/360° 接缝的三份绘制");

                WallLineUnfoldChart chart = findNode(root, WallLineUnfoldChart.class);
                assertNotNull(chart, "未找到井壁展开图");
                double sphereX = 78 + 90.0 / 360.0 * 600;
                double halfHeight = Math.sqrt(
                        0.08 * 0.08 - 0.10 * 0.10 - 0.15 * 0.15
                                + 2 * 0.10 * 0.15);
                double sphereY = 42 + (5.6 + halfHeight - 4.9) / 1.0 * 700;
                chart.getOnMouseClicked().handle(mouseClick(sphereX, sphereY));

                PauseTransition settle = new PauseTransition(Duration.millis(1000));
                settle.setOnFinished(event -> {
                    try {
                        TitledPane fracturePane = findTitledPane(root, "裂缝");
                        assertNotNull(fracturePane, "未找到裂缝参数分组");
                        assertTrue(fracturePane.isExpanded(), "点击球体后裂缝参数分组未展开");

                        Node sphereCard = root.lookup(".sphere-fracture-card");
                        assertNotNull(sphereCard, "未找到球体参数卡");
                        assertTrue(sphereCard.getStyleClass().contains("fracture-card-selected"),
                                "球体参数卡没有高亮");

                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                                new File("target/ui-sphere-fracture-selection.png"));
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        checked.countDown();
                    }
                });
                settle.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                checked.countDown();
            }
        });

        assertTrue(checked.await(20, TimeUnit.SECONDS), "球体裂缝交互检查超时");
        if (failure.get() != null) {
            throw new AssertionError("球体裂缝交互检查失败", failure.get());
        }
    }

    @Test
    void clickingFractureOpensAndHighlightsItsParameters() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");

                StratumModel model = new StratumModel(
                        0.1, 100, 90,
                        List.of(new StratumInterface(0.8),
                                new StratumInterface(1.2)),
                        List.of(20.0, 35.0, 60.0),
                        List.of(new Fracture(1.0, 0, 30, 100),
                                new Fracture(1.15, 60, 120, 60)));
                parameterPane.setModel(model, "fracture click test");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                SectionColorChart chart = findNode(root, SectionColorChart.class);
                assertNotNull(chart, "未找到彩色地层剖面图");

                double axisX = 46 + 0.1 * 600 * 6 / 2;
                double fractureY = 30 + (1.0 - (0.8 - 0.1)) * 600;
                assertNotNull(chart.getOnMouseClicked(), "图表未安装点击处理器");
                chart.getOnMouseClicked().handle(mouseClick(axisX, fractureY));

                PauseTransition settle = new PauseTransition(Duration.millis(1000));
                settle.setOnFinished(event -> {
                    try {
                        TitledPane fracturePane = findTitledPane(root, "裂缝");
                        assertNotNull(fracturePane, "未找到裂缝参数分组");
                        assertTrue(fracturePane.isExpanded(), "点击裂缝后裂缝参数分组未展开");
                        Accordion accordion = findNode(root, Accordion.class);
                        assertNotNull(accordion, "未找到参数折叠容器");
                        assertTrue(accordion.getExpandedPane() == fracturePane,
                                "裂缝分组不是参数折叠容器当前展开项");
                        assertTrue(fracturePane.getBoundsInParent().getHeight() > 200,
                                "裂缝参数内容没有展开高度");

                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        ImageIO.write(SwingFXUtils.fromFXImage(image, null),
                                "png", new File("target/ui-fracture-selection.png"));

                        List<Node> cards = findNodesWithStyleClass(root, "fracture-card");
                        assertTrue(cards.size() >= 2, "裂缝参数卡片数量不正确");
                        assertTrue(cards.get(0).getStyleClass().contains("fracture-card-selected"),
                                "点击的裂缝参数卡片没有高亮");
                        assertFalse(cards.get(1).getStyleClass().contains("fracture-card-selected"),
                                "未点击的裂缝参数卡片不应高亮");
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        checked.countDown();
                    }
                });
                settle.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                checked.countDown();
            }
        });

        assertTrue(checked.await(15, TimeUnit.SECONDS), "裂缝点击交互检查超时");
        if (failure.get() != null) {
            throw new AssertionError("裂缝点击交互检查失败", failure.get());
        }
    }

    @Test
    void clickingLayerAndInterfaceHighlightsFormationParameters() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");

                StratumModel model = new StratumModel(
                        0.1, 100, 90,
                        List.of(new StratumInterface(0.8),
                                new StratumInterface(1.2)),
                        List.of(20.0, 35.0, 60.0),
                        List.of());
                parameterPane.setModel(model, "formation click test");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                SectionColorChart chart = findNode(root, SectionColorChart.class);
                assertNotNull(chart, "未找到彩色地层剖面图");

                @SuppressWarnings("unchecked")
                List<TextField> depthFields = (List<TextField>) readField(
                        parameterPane, "depthFields");
                @SuppressWarnings("unchecked")
                List<TextField> rhoFields = (List<TextField>) readField(
                        parameterPane, "rhoFields");

                double axisX = 46 + 0.1 * 600 * 6 / 2;
                double layerY = 30 + (1.0 - (0.8 - 0.1)) * 600;
                chart.getOnMouseClicked().handle(mouseClick(axisX + 80, layerY));

                PauseTransition layerSettled = new PauseTransition(Duration.millis(1000));
                layerSettled.setOnFinished(event -> {
                    try {
                        TitledPane formationPane = findTitledPane(root, "地层数据");
                        assertNotNull(formationPane, "未找到地层参数分组");
                        assertTrue(formationPane.isExpanded(), "点击地层后地层参数分组未展开");
                        assertTrue(rhoFields.get(1).getStyleClass().contains("parameter-highlight"),
                                "点击的地层电阻率参数没有高亮");

                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        ImageIO.write(SwingFXUtils.fromFXImage(image, null),
                                "png", new File("target/ui-layer-selection.png"));

                        double interfaceY = 30 + (0.8 - (0.8 - 0.1)) * 600;
                        chart.getOnMouseClicked().handle(mouseClick(axisX + 80, interfaceY));

                        PauseTransition interfaceSettled =
                                new PauseTransition(Duration.millis(1000));
                        interfaceSettled.setOnFinished(interfaceEvent -> {
                            try {
                                assertTrue(depthFields.get(0).getStyleClass()
                                                .contains("parameter-highlight"),
                                        "点击的地层界面深度参数没有高亮");
                                assertFalse(rhoFields.get(1).getStyleClass()
                                                .contains("parameter-highlight"),
                                        "切换选择后旧的地层参数仍然高亮");

                                root.applyCss();
                                root.layout();
                                WritableImage interfaceImage = scene.snapshot(null);
                                ImageIO.write(SwingFXUtils.fromFXImage(interfaceImage, null),
                                        "png", new File("target/ui-interface-selection.png"));
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            } finally {
                                checked.countDown();
                            }
                        });
                        interfaceSettled.play();
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                        checked.countDown();
                    }
                });
                layerSettled.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                checked.countDown();
            }
        });

        assertTrue(checked.await(20, TimeUnit.SECONDS), "地层点击交互检查超时");
        if (failure.get() != null) {
            throw new AssertionError("地层点击交互检查失败", failure.get());
        }
    }

    @Test
    void clickingWallUnfoldLayerAndInterfaceHighlightsFormationParameters() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();
                MainController controller = loader.getController();
                ParameterPane parameterPane = (ParameterPane) readField(
                        controller, "parameterPane");

                StratumModel model = new StratumModel(
                        0.1, 100, 90,
                        List.of(new StratumInterface(0.8),
                                new StratumInterface(1.2)),
                        List.of(20.0, 35.0, 60.0),
                        List.of());
                parameterPane.setModel(model, "wall formation click test");

                Method generate = MainController.class.getDeclaredMethod("generateCharts");
                generate.setAccessible(true);
                generate.invoke(controller);

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        FractureClickInteractionTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                WallLineUnfoldChart chart = findNode(root, WallLineUnfoldChart.class);
                assertNotNull(chart, "未找到井壁展开图");

                @SuppressWarnings("unchecked")
                List<TextField> depthFields = (List<TextField>) readField(
                        parameterPane, "depthFields");
                @SuppressWarnings("unchecked")
                List<TextField> rhoFields = (List<TextField>) readField(
                        parameterPane, "rhoFields");

                double angleX = 78 + 90.0 / 360.0 * 600;
                double layerY = 42 + (1.0 - 0.7) / 0.6 * 700;
                chart.getOnMouseClicked().handle(mouseClick(angleX, layerY));

                PauseTransition layerSettled = new PauseTransition(Duration.millis(1000));
                layerSettled.setOnFinished(event -> {
                    try {
                        TitledPane formationPane = findTitledPane(root, "地层数据");
                        assertNotNull(formationPane, "未找到地层参数分组");
                        assertTrue(formationPane.isExpanded(), "点击展开图地层后参数分组未展开");
                        assertTrue(rhoFields.get(1).getStyleClass().contains("parameter-highlight"),
                                "展开图中点击的地层电阻率参数没有高亮");

                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        ImageIO.write(SwingFXUtils.fromFXImage(image, null),
                                "png", new File("target/ui-wall-layer-selection.png"));

                        double interfaceY = 42 + (0.8 - 0.7) / 0.6 * 700;
                        chart.getOnMouseClicked().handle(mouseClick(angleX, interfaceY));

                        PauseTransition interfaceSettled =
                                new PauseTransition(Duration.millis(1000));
                        interfaceSettled.setOnFinished(interfaceEvent -> {
                            try {
                                assertTrue(depthFields.get(0).getStyleClass()
                                                .contains("parameter-highlight"),
                                        "展开图中点击的地层界面参数没有高亮");
                                assertFalse(rhoFields.get(1).getStyleClass()
                                                .contains("parameter-highlight"),
                                        "展开图切换选择后旧地层参数仍然高亮");

                                root.applyCss();
                                root.layout();
                                WritableImage interfaceImage = scene.snapshot(null);
                                ImageIO.write(SwingFXUtils.fromFXImage(interfaceImage, null),
                                        "png", new File("target/ui-wall-interface-selection.png"));
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            } finally {
                                checked.countDown();
                            }
                        });
                        interfaceSettled.play();
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                        checked.countDown();
                    }
                });
                layerSettled.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                checked.countDown();
            }
        });

        assertTrue(checked.await(20, TimeUnit.SECONDS), "井壁展开图点击交互检查超时");
        if (failure.get() != null) {
            throw new AssertionError("井壁展开图点击交互检查失败", failure.get());
        }
    }

    private static MouseEvent mouseClick(double x, double y) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                x, y, x, y,
                MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false,
                false, false, false, null);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static TitledPane findTitledPane(Parent parent, String title) {
        for (Node node : parent.getChildrenUnmodifiable()) {
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

    private static List<Node> findNodesWithStyleClass(Parent parent, String styleClass) {
        List<Node> result = new ArrayList<>();
        collectNodesWithStyleClass(parent, styleClass, result);
        return result;
    }

    private static void collectNodesWithStyleClass(
            Parent parent, String styleClass, List<Node> result) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains(styleClass)) {
                result.add(node);
            }
            if (node instanceof Parent child) {
                collectNodesWithStyleClass(child, styleClass, result);
            }
        }
    }

    private static <T extends Node> T findNode(Parent parent, Class<T> type) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (type.isInstance(node)) {
                return type.cast(node);
            }
            if (node instanceof Parent child) {
                T found = findNode(child, type);
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
