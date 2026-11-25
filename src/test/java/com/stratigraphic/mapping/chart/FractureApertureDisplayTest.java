package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 张开度必须改变裂缝在二维图上的可见厚度，而不只是写入模型。 */
class FractureApertureDisplayTest {

    @Test
    void largerApertureDrawsThickerFractureInBothCharts() throws Exception {

        startToolkit();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                StratumModel model = new StratumModel(
                        0.1, 100, 90,
                        List.of(new StratumInterface(0.5)),
                        List.of(20.0, 40.0),
                        List.of(
                                new Fracture(0.45, 30, 90, 100, 1.0, 0.002),
                                new Fracture(0.55, 30, 270, 100, 1.0, 0.050)));

                WallLineUnfoldChart wall = new WallLineUnfoldChart(model, 0, 1);
                SectionColorChart section = new SectionColorChart(model);

                double thinWall = wall.fractureLineWidth(model.fractures().get(0));
                double thickWall = wall.fractureLineWidth(model.fractures().get(1));
                double thinSection = section.fractureLineWidth(model.fractures().get(0));
                double thickSection = section.fractureLineWidth(model.fractures().get(1));

                assertTrue(thickWall > thinWall + 10,
                        "井壁展开图：50 mm 裂缝应明显比 2 mm 裂缝厚，实际 "
                                + thinWall + " / " + thickWall);
                assertTrue(thickSection > thinSection + 10,
                        "岩性剖面：50 mm 裂缝应明显比 2 mm 裂缝厚，实际 "
                                + thinSection + " / " + thickSection);

                HBox preview = new HBox(12, wall, section);
                Scene scene = new Scene(preview, 1480, 830);
                scene.getStylesheets().add(FractureApertureDisplayTest.class.getResource(
                        "/com/stratigraphic/mapping/app.css").toExternalForm());
                preview.applyCss();
                preview.layout();

                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.WHITE);
                WritableImage image = scene.snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File("target/fracture-aperture-preview.png"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(20, TimeUnit.SECONDS), "裂缝厚度预览渲染超时");
        if (failure.get() != null) {
            throw new AssertionError("裂缝厚度预览渲染失败", failure.get());
        }
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