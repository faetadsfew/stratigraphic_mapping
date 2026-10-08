package com.stratigraphic.mapping;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * 程序入口：装配主窗口，启动时直接最大化铺满可视区。
 *
 * <p>尺寸不写死：还原尺寸取「理想尺寸」和「所在屏幕可视区的 92%」里小的那个，
 * 最小尺寸也不会超过还原尺寸，所以在小屏、2K/4K 屏、带系统缩放的屏上都不会被顶出可视区。
 * 窗口开在鼠标所在的那块屏上，多显示器时不用手动搬。
 *
 * <p>按 F11 切真全屏（连任务栏一起盖住），Esc 退出全屏。
 */
public class StratigraphicMappingApplication extends Application {

    /** 屏幕够大时「还原」用的理想尺寸；屏幕不够大就按比例缩。 */
    private static final double PREFERRED_WIDTH = 1440;
    private static final double PREFERRED_HEIGHT = 900;

    /** 还原尺寸最多占屏幕可视区域的多少。 */
    private static final double SCREEN_FILL = 0.92;

    /** 窗口能缩到的最小尺寸——再小两张图就没法看了。 */
    private static final double MIN_WIDTH = 1000;
    private static final double MIN_HEIGHT = 620;

    @Override
    public void start(Stage stage) throws IOException {

        FXMLLoader loader = new FXMLLoader(
                StratigraphicMappingApplication.class.getResource("main-view.fxml"));

        Parent root = loader.load();

        // 用可视区域（扣掉任务栏）而不是整块屏幕，免得窗口被任务栏盖住
        Rectangle2D screen = currentScreen().getVisualBounds();
        double width = Math.min(PREFERRED_WIDTH, screen.getWidth() * SCREEN_FILL);
        double height = Math.min(PREFERRED_HEIGHT, screen.getHeight() * SCREEN_FILL);

        Scene scene = new Scene(root, width, height);

        // 所有样式集中在 app.css，界面上不写内联样式
        scene.getStylesheets().add(
                StratigraphicMappingApplication.class.getResource("app.css").toExternalForm());

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });

        stage.setScene(scene);
        stage.setTitle("井壁地层剖面图");
        stage.setFullScreenExitHint("按 Esc 退出全屏");

        // 最小尺寸不能超过屏幕放得下的尺寸，否则小屏上窗口会被顶出可视区
        stage.setMinWidth(Math.min(MIN_WIDTH, width));
        stage.setMinHeight(Math.min(MIN_HEIGHT, height));

        // 先摆好还原时的位置（所在屏幕居中），再最大化
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);

        // 启动就最大化：占满可视区，任务栏仍然可见
        stage.setMaximized(true);
        stage.show();
    }

    /** 鼠标当前所在的那块屏；取不到（远程桌面、无鼠标等）就用主屏。 */
    private static Screen currentScreen() {

        try {
            Point2D pointer = new Robot().getMousePosition();
            for (Screen screen : Screen.getScreensForRectangle(pointer.getX(), pointer.getY(), 1, 1)) {
                return screen;
            }
        } catch (RuntimeException ignored) {
            // 拿不到鼠标位置就退回主屏
        }
        return Screen.getPrimary();
    }
}
