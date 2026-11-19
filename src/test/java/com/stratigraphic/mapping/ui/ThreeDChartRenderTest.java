package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ThreeDChart;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeDChartRenderTest {

    @Test
    void formationGRendersThreeDGlassModelAndHud() throws Exception {
        startToolkit();

        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                StratumModel model;
                try (InputStream input = ThreeDChartRenderTest.class.getResourceAsStream(
                        "/com/stratigraphic/mapping/formation_G.txt")) {
                    assertNotNull(input);
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    model = StratumModelReader.parse(text.lines().toList());
                }

                ThreeDChart chart = new ThreeDChart(model, 4.9, 5.9);
                chart.stopAnimation();
                Scene scene = new Scene(chart, 480, 600);
                scene.getStylesheets().add(ThreeDChartRenderTest.class.getResource(
                        "/com/stratigraphic/mapping/app.css").toExternalForm());
                chart.applyCss();
                chart.layout();

                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                parameters.setDepthBuffer(true);
                WritableImage image = chart.snapshot(parameters, null);
                File output = new File("target/three-d-snapshot.png");
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output);

                long colored = 0;
                long centerColored = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        Color color = image.getPixelReader().getColor(x, y);
                        if (color.getOpacity() > 0.5
                                && Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) > 0.42) {
                            colored++;
                            if (x >= image.getWidth() / 2 - 24 && x <= image.getWidth() / 2 + 24) {
                                centerColored++;
                            }
                        }
                    }
                }

                assertTrue(colored > image.getWidth() * image.getHeight() * 0.08,
                        "三维模型非背景像素不足 8%");
                assertTrue(centerColored > image.getHeight() * 24,
                        "模型中间竖条没有明显颜色");
                assertTrue(!chart.lookupAll(".three-d-hud").isEmpty(),
                        "HUD 节点缺失");
                assertTrue(!chart.lookupAll(".three-d-chip").isEmpty(),
                        "图例 chips 缺失");
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                rendered.countDown();
            }
        });

        assertTrue(rendered.await(20, TimeUnit.SECONDS), "三维图渲染测试超时");
        if (failure.get() != null) {
            throw new AssertionError("三维图渲染失败", failure.get());
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
