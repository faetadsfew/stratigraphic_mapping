package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;
import com.stratigraphic.mapping.simulation.SliceGenerationService;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HomepageStyleSyncTest {
    @Test
    void integerInchDisplayPreservesOriginalMeterRadius() throws Exception {
        onFxThread(() -> {
            ParameterPane pane = new ParameterPane();
            for (double[] sample : new double[][]{
                    {0.1, 4}, {0.12, 5}, {0.12345678901234567, 5},
                    {0.1016, 4}, {0.2032, 8}, {0.0254, 1},
                    {0.025400001, 2}, {0.0001, 1}}) {
                StratumModel original = new StratumModel(sample[0], 100, 30,
                        List.of(new StratumInterface(1)), List.of(20.0, 30.0));
                pane.setModel(original, "radius regression");
                TextField input = (TextField) readField(pane, "radiusField");
                assertEquals(Integer.toString((int) sample[1]), input.getText());
                assertModelEquals(original, pane.toModel());
                input.fireEvent(new javafx.event.ActionEvent());
                assertModelEquals(original, pane.toModel());
                pane.setModel(pane.toModel(), "meter-radius reload");
                assertEquals(Integer.toString((int) sample[1]), input.getText());
                assertModelEquals(original, pane.toModel());
            }
        });
    }

    @Test
    void editedInchesConvertPreciselyBeforeDisplayRounding() throws Exception {
        onFxThread(() -> {
            ParameterPane pane = new ParameterPane();
            pane.setModel(model(), "manual inch input");
            TextField input = (TextField) readField(pane, "radiusField");
            input.clear();
            input.setText("4");
            assertEquals(0.1016, pane.toModel().wellRadius());
            input.setText("8");
            assertEquals(0.2032, pane.toModel().wellRadius());
            input.setText("3.94");
            assertEquals(0.100076, pane.toModel().wellRadius());
            assertEquals("3.94", input.getText());
            input.fireEvent(new javafx.event.ActionEvent());
            assertEquals("4", input.getText());
            assertEquals(0.100076, pane.toModel().wellRadius());
            input.fireEvent(new javafx.event.ActionEvent());
            assertEquals(0.100076, pane.toModel().wellRadius());
            input.setText("8.01");
            input.fireEvent(new javafx.event.ActionEvent());
            assertEquals("9", input.getText());
            assertEquals(0.203454, pane.toModel().wellRadius());
            pane.setModel(model(), "reload with same rounded display");
            assertEquals("4", input.getText());
            assertEquals(0.1, pane.toModel().wellRadius());
            input.clear();
            input.setText("4");
            assertEquals(0.1016, pane.toModel().wellRadius());
            for (String invalid : new String[]{"0", "-1", "", "1e309"}) {
                input.setText(invalid);
                assertThrows(IllegalArgumentException.class, pane::toModel);
                assertNull(pane.tryToModel());
            }
        });
    }

    @Test
    void radiusRoundingDoesNotInterruptEditingOrReenterStatusUpdates() throws Exception {
        onFxThread(() -> {
            ParameterPane pane = new ParameterPane();
            pane.setModel(model(), "editing regression");
            TextField input = (TextField) readField(pane, "radiusField");
            AtomicInteger changes = new AtomicInteger();
            pane.setOnDataChanged(() -> {
                changes.incrementAndGet();
                pane.tryToModel();
            });
            input.setText("3.94");
            assertEquals(1, changes.get());
            assertEquals("3.94", input.getText());
            assertEquals(0.100076, pane.toModel().wellRadius());
            assertEquals(1, changes.get());
            input.fireEvent(new javafx.event.ActionEvent());
            assertEquals("4", input.getText());
            assertEquals(2, changes.get());
            AtomicInteger confirmed = new AtomicInteger();
            pane.setOnConfirmData(() -> {
                assertEquals("4", input.getText());
                assertEquals(0.076454, pane.toModel().wellRadius());
                confirmed.incrementAndGet();
            });
            input.setText("3.01");
            Parent footer = (Parent) pane.getBottom();
            ((Button) footer.lookup(".primary-button")).fire();
            ((Button) footer.lookup(".primary-button")).fire();
            assertEquals(2, confirmed.get());
        });
    }

    @Test
    void roundedRadiusDisplayProducesUnchangedSlices(@TempDir Path temp) throws Exception {
        StratumModel original = model();
        AtomicReference<StratumModel> confirmed = new AtomicReference<>();
        onFxThread(() -> {
            ParameterPane pane = new ParameterPane();
            pane.setModel(original, "slice radius regression");
            TextField input = (TextField) readField(pane, "radiusField");
            assertEquals("4", input.getText());
            ((TextField) readField(pane, "wellRhoField")).setText("101");
            ((TextField) readField(pane, "wellRhoField")).setText("100");
            pane.setOnConfirmData(() -> confirmed.set(pane.toModel()));
            Parent footer = (Parent) pane.getBottom();
            ((Button) footer.lookup(".primary-button")).fire();
            assertModelEquals(original, confirmed.get());
        });
        var constructor = SliceGenerationService.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        Path expected = constructor.newInstance(temp.resolve("original"))
                .generate(original, 0, 1.5).output();
        Path actual = constructor.newInstance(temp.resolve("displayed"))
                .generate(confirmed.get(), 0, 1.5).output();
        try (var files = Files.list(expected)) {
            List<Path> slices = files.filter(file -> file.getFileName().toString().endsWith(".txt")).toList();
            assertEquals(192, slices.size());
            for (Path slice : slices) {
                assertEquals(Files.readString(slice),
                        Files.readString(actual.resolve(slice.getFileName())), slice.getFileName().toString());
            }
        }
    }

    @Test
    void formationSectionScrollsHorizontallyWithoutShrinkingDataColumns() throws Exception {
        onFxThread(() -> {
            for (int width : new int[]{360, 275}) {
                ParameterPane pane = new ParameterPane();
                pane.setModel(model(), "scroll regression");
                Scene scene = new Scene(pane, width, 1000);
                scene.getStylesheets().add(HomepageStyleSyncTest.class.getResource(
                        "/com/stratigraphic/mapping/app.css").toExternalForm());
                Accordion accordion = (Accordion) readField(pane, "formAccordion");
                TitledPane formation = accordion.getPanes().get(1);
                formation.setAnimated(false);
                accordion.setExpandedPane(formation);
                pane.applyCss();
                pane.layout();
                ScrollPane scroll = (ScrollPane) formation.getContent();
                assertEquals("formationScroll", scroll.getId());
                assertEquals(ScrollPane.ScrollBarPolicy.ALWAYS, scroll.getHbarPolicy());
                assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy());
                assertTrue(scroll.getContent().getLayoutBounds().getWidth() > scroll.getViewportBounds().getWidth());
                assertTrue(scroll.lookupAll(".scroll-bar").stream().anyMatch(node ->
                        node instanceof javafx.scene.control.ScrollBar bar
                                && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL && bar.isVisible()));
                List<?> fields = (List<?>) readField(pane, "dielectricFields");
                TextField lastField = (TextField) fields.get(fields.size() - 1);
                double originalX = lastField.localToScene(lastField.getBoundsInLocal()).getMaxX();
                ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                        new File("target/ui-formation-scroll-" + width + "-left.png"));
                scroll.setHvalue(scroll.getHmax());
                pane.layout();
                Bounds fieldBounds = lastField.localToScene(lastField.getBoundsInLocal());
                Node viewport = scroll.lookup(".viewport");
                Bounds viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
                assertTrue(fieldBounds.getMaxX() < originalX);
                assertTrue(fieldBounds.getMinX() >= viewportBounds.getMinX() - 1);
                assertTrue(fieldBounds.getMaxX() <= viewportBounds.getMaxX() + 1);
                assertTrue(scroll.getContent().getLayoutBounds().getHeight() <= scroll.getViewportBounds().getHeight() + 1);
                assertModelEquals(model(), pane.toModel());
                ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                        new File("target/ui-formation-scroll-" + width + "-right.png"));
            }
        });
    }

    @Test
    void groupingPreservesEveryModelValueAndExistingCallbacks() throws Exception {
        onFxThread(() -> {
            ParameterPane pane = new ParameterPane();
            StratumModel original = model();
            pane.setModel(original, "style-only regression");
            Scene scene = new Scene(pane, 360, 900);
            scene.getStylesheets().add(HomepageStyleSyncTest.class.getResource(
                    "/com/stratigraphic/mapping/app.css").toExternalForm());
            pane.applyCss();
            pane.layout();
            Accordion accordion = (Accordion) readField(pane, "formAccordion");
            assertEquals(List.of("井数据", "地层数据", "裂缝", "异常体", "曲面文字", "井壁展开区间"),
                    accordion.getPanes().stream().map(TitledPane::getText).toList());
            assertNull(accordion.getExpandedPane());
            TitledPane anomaly = accordion.getPanes().get(3);
            assertTrue(isInside((Node) readField(pane, "sphereRowsBox"), anomaly.getContent()));
            assertTrue(isInside((Node) readField(pane, "wedgeRowsBox"), anomaly.getContent()));
            assertTrue(isInside((Node) readField(pane, "fractureRowsBox"),
                    accordion.getPanes().get(2).getContent()));

            pane.revealSphere(0);
            assertSame(anomaly, accordion.getExpandedPane());
            pane.revealWedge(0);
            assertSame(anomaly, accordion.getExpandedPane());
            pane.revealFracture(0);
            assertSame(accordion.getPanes().get(2), accordion.getExpandedPane());
            assertModelEquals(original, pane.toModel());
            assertEquals(new ParameterPane.UnfoldInterval(0, 1.5), pane.toUnfoldInterval());

            AtomicInteger generated = new AtomicInteger();
            AtomicInteger opened = new AtomicInteger();
            pane.setOnConfirmData(generated::incrementAndGet);
            pane.setOnOpenFile(opened::incrementAndGet);
            ((Button) pane.lookup(".primary-button")).fire();
            ((Button) pane.lookup(".ghost-button")).fire();
            assertEquals(1, generated.get());
            assertEquals(1, opened.get());
            assertModelEquals(original, pane.toModel());
        });
    }

    @Test
    void homepageRendersTheUpdatedGroupAtBothWindowSizes() throws Exception {
        onFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(HomepageStyleSyncTest.class.getResource(
                    "/com/stratigraphic/mapping/main-view.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            ParameterPane pane = (ParameterPane) readField(controller, "parameterPane");
            StratumModel original = model();
            pane.setModel(original, "style-only regression");
            Method generate = MainController.class.getDeclaredMethod("generateCharts");
            generate.setAccessible(true);
            generate.invoke(controller);
            Scene scene = new Scene(root, 1440, 900);
            scene.getStylesheets().add(HomepageStyleSyncTest.class.getResource(
                    "/com/stratigraphic/mapping/app.css").toExternalForm());
            pane.revealSphere(0);
            root.applyCss();
            root.layout();

            for (double width : new double[]{1440, 1100}) {
                double height = width == 1440 ? 900 : 720;
                root.resize(width, height);
                root.applyCss();
                root.layout();
                Node slot = (Node) loader.getNamespace().get("parameterSlot");
                assertEquals(width / 4, slot.getLayoutBounds().getWidth(), 1);
                Accordion accordion = (Accordion) readField(pane, "formAccordion");
                assertEquals("异常体", accordion.getExpandedPane().getText());
                ScrollPane scroll = (ScrollPane) readField(pane, "formScroll");
                Node viewport = scroll.lookup(".viewport");
                Bounds visible = viewport.localToScene(viewport.getBoundsInLocal());
                for (Node candidate : accordion.getExpandedPane().getContent().lookupAll(".button")) {
                    if (candidate.getParent() instanceof FlowPane) {
                        Bounds button = candidate.localToScene(candidate.getBoundsInLocal());
                        assertTrue(button.getMinX() >= visible.getMinX() - 1);
                        assertTrue(button.getMaxX() <= visible.getMaxX() + 1,
                                "Anomaly action clipped at width " + width);
                    }
                }
                for (Node input : accordion.getExpandedPane().getContent().lookupAll(".form-input")) {
                    Node card = input.getParent();
                    while (card != null && !card.getStyleClass().contains("fracture-card")) {
                        card = card.getParent();
                    }
                    assertTrue(card != null);
                    Bounds inputBounds = input.localToScene(input.getBoundsInLocal());
                    Bounds cardBounds = card.localToScene(card.getBoundsInLocal());
                    assertTrue(inputBounds.getMaxX() <= cardBounds.getMaxX() + 1,
                            "Anomaly input overflows its card at width " + width);
                }
                ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png",
                        new File("target/ui-homepage-style-" + (int) width + ".png"));
                assertModelEquals(original, pane.toModel());
            }
        });
    }

    private static StratumModel model() {
        return new StratumModel(0.1, 100, 120,
                List.of(new StratumInterface(0.1), new StratumInterface(0.4),
                        new StratumInterface(0.7), new StratumInterface(1.0),
                        new StratumInterface(1.4)),
                List.of(20.0, 15.0, 10.0, 30.0, 40.0, 50.0),
                List.of(new Fracture(0.3, 30, 30, 100), new Fracture(0.7, 60, 30, 100)),
                List.of(new SphereFracture(0.6, 90, 0.15, 0.08, 50)),
                List.of(new WedgeFracture(120, 0.6, 240, 0.8, 240, 0.4, 30)),
                List.of(new SurfaceText("TEST", 180, 0.9, 120, 0.25, 3)));
    }

    private static void assertModelEquals(StratumModel expected, StratumModel actual) {
        assertEquals(expected.wellRadius(), actual.wellRadius());
        assertEquals(expected.wellResistivity(), actual.wellResistivity());
        assertEquals(expected.dipAngle(), actual.dipAngle());
        assertEquals(expected.interfaces(), actual.interfaces());
        assertEquals(expected.resistivities(), actual.resistivities());
        assertEquals(expected.fractures(), actual.fractures());
        assertEquals(expected.sphereFractures(), actual.sphereFractures());
        assertEquals(expected.wedgeFractures(), actual.wedgeFractures());
        assertEquals(expected.surfaceTexts(), actual.surfaceTexts());
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void onFxThread(CheckedAction action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("Homepage style regression", failure.get());
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
