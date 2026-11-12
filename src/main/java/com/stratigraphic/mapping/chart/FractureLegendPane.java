package com.stratigraphic.mapping.chart;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

import java.util.Locale;

/** 与地层剖面共用的统一电阻率图例。 */
final class FractureLegendPane extends Pane {

    static final double WIDTH = 188;
    static final double HEIGHT = 62;

    FractureLegendPane(ColorScale scale, int fractureCount) {

        setPrefSize(WIDTH, HEIGHT);
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setMouseTransparent(true);

        Rectangle background = new Rectangle(WIDTH, HEIGHT);
        background.setFill(javafx.scene.paint.Color.web("#FFFFFF", 0.92));
        background.setStroke(javafx.scene.paint.Color.web("#DDE4EB"));
        background.setArcWidth(6);
        background.setArcHeight(6);

        Label title = new Label("统一电阻率 / Ω·m");
        title.setLayoutX(9);
        title.setLayoutY(5);
        title.setStyle(SectionStyle.bold(11));

        Rectangle bar = new Rectangle(160, 10);
        bar.setLayoutX(9);
        bar.setLayoutY(27);
        bar.setStroke(SectionStyle.COLOR_BAR_BORDER);
        bar.setStrokeWidth(0.6);
        bar.setFill(new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, scale.colorOf(scale.min())),
                new Stop(0.5, scale.colorOf(Math.sqrt(scale.min() * scale.max()))),
                new Stop(1, scale.colorOf(scale.max()))));

        Label min = new Label(format(scale.min()));
        min.setLayoutX(8);
        min.setLayoutY(42);
        min.setStyle(SectionStyle.muted(10));

        Label max = new Label(format(scale.max()));
        max.setLayoutX(132);
        max.setLayoutY(42);
        max.setStyle(SectionStyle.muted(10));

        Label count = new Label(fractureCount + " 条");
        count.setLayoutX(143);
        count.setLayoutY(6);
        count.setStyle(SectionStyle.muted(10));

        getChildren().addAll(background, title, count, bar, min, max);
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
