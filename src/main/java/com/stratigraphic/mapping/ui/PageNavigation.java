package com.stratigraphic.mapping.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

final class PageNavigation {
    private PageNavigation() {
    }

    static void show(Parent currentRoot, String currentPage, String targetPage, String title)
            throws IOException {
        Scene scene = currentRoot.getScene();
        if (scene == null) {
            throw new IllegalStateException("当前页面尚未挂载到窗口");
        }
        String currentKey = PageNavigation.class.getName() + ":" + currentPage;
        String targetKey = PageNavigation.class.getName() + ":" + targetPage;
        scene.getProperties().put(currentKey, currentRoot);
        Parent targetRoot = (Parent) scene.getProperties().get(targetKey);
        if (targetRoot == null) {
            targetRoot = FXMLLoader.load(PageNavigation.class.getResource(
                    "/com/stratigraphic/mapping/" + targetPage));
            scene.getProperties().put(targetKey, targetRoot);
        }
        scene.setRoot(targetRoot);
        if (scene.getWindow() instanceof Stage stage) {
            stage.setTitle(title);
        }
    }
}
