package com.stratigraphic.mapping.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewNavigationTest {

    @Test
    void mainViewShowsColorSectionAndWallUnfoldSideBySide() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        MainViewNavigationTest.class.getResource(
                                "/com/stratigraphic/mapping/main-view.fxml"));
                Parent root = loader.load();

                Scene scene = new Scene(root, 1440, 900);
                scene.getStylesheets().add(
                        MainViewNavigationTest.class.getResource(
                                "/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                Label colorTitle = findLabel(root, "岩性剖面");
                Label wallTitle = findLabel(root, "井壁展开图（彩色）");
                assertNotNull(colorTitle, "主界面未找到彩色剖面标题");
                assertNotNull(wallTitle, "主界面未找到井壁展开图标题");

                Bounds colorBounds = colorTitle.localToScene(colorTitle.getBoundsInLocal());
                Bounds wallBounds = wallTitle.localToScene(wallTitle.getBoundsInLocal());
                assertTrue(colorBounds.getMinX() < wallBounds.getMinX(),
                        "彩色剖面应位于井壁展开图左侧");
                assertTrue(wallBounds.getMinX() < scene.getWidth(),
                        "井壁展开图应位于主窗口内");

                writeSnapshot(scene, "target/ui-main-snapshot.png");
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                checked.countDown();
            }
        });

        assertTrue(checked.await(15, TimeUnit.SECONDS), "主界面检查超时");
        if (failure.get() != null) {
            throw new AssertionError("主界面三栏布局检查失败", failure.get());
        }
    }

    private static Label findLabel(Node node, String text) {

        if (node instanceof Label label && text.equals(label.getText())) {
            return label;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Label found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void writeSnapshot(Scene scene, String path) throws Exception {

        WritableImage image = scene.snapshot(null);
        File output = new File(path);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建快照目录：" + parent);
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);
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
