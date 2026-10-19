package com.stratigraphic.mapping.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewNavigationTest {

    @Test
    void mainViewAndWallViewSwitchWithinTheSameScene() throws Exception {

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

                Parent mainRoot = scene.getRoot();
                Button wallUnfoldButton = findButton(root, "井壁展开图");
                assertNotNull(wallUnfoldButton, "第一层页面未找到井壁展开图按钮");
                writeSnapshot(scene, "target/ui-main-snapshot.png");

                wallUnfoldButton.fire();

                Parent wallRoot = scene.getRoot();
                assertNotSame(mainRoot, wallRoot, "点击后没有在当前窗口中切换页面");
                assertNotNull(findButton(wallRoot, "井壁地层剖面图"),
                        "井壁展开图标题旁未找到地层剖面图按钮");
                wallRoot.applyCss();
                wallRoot.layout();
                writeSnapshot(scene, "target/ui-unfold-snapshot.png");

                Button backButton = findButton(wallRoot, "井壁地层剖面图");
                backButton.fire();

                assertNotSame(wallRoot, scene.getRoot(),
                        "返回后没有在当前窗口中切换回默认页面");
                assertNotNull(findButton(scene.getRoot(), "井壁展开图"),
                        "返回默认页面后未找到井壁展开图按钮");
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                checked.countDown();
            }
        });

        assertTrue(checked.await(15, TimeUnit.SECONDS), "第一层页面检查超时");
        if (failure.get() != null) {
            throw new AssertionError("第一层页面跳转检查失败", failure.get());
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
