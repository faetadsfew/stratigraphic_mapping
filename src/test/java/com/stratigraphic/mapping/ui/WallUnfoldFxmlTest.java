package com.stratigraphic.mapping.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
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
