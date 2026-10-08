package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ColorScale;
import com.stratigraphic.mapping.chart.SectionStyle;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * 电阻率色标：固定画在图表右侧那一列里，不参与滚轮缩放，也不跟着拖动平移。
 *
 * <p>高度由 {@link FitPane} 按「刚好放得下」的图面高度给定，所以色标永远和图面一样高；
 * 两张图都留出同样宽的一列，可用区一样大，图面也就始终等大。
 */
public final class ColorBarPane extends Pane {

    /** 这一列的总宽度：色标带 + 刻度线 + 刻度数字。 */
    public static final double WIDTH = 78;

    private static final double BAR_W = 22;
    private static final double TICK_LEN = 5;
    private static final double LABEL_X = BAR_W + 9;

    /** 色标等分几段。 */
    private static final int STEPS = 5;

    private final ColorScale scale;

    private final Label title = new Label("电阻率");
    private final Rectangle bar = new Rectangle(BAR_W, 0);
    private final List<Line> ticks = new ArrayList<>();
    private final List<Label> tickLabels = new ArrayList<>();

    public ColorBarPane(double maxResistivity) {

        this.scale = new ColorScale(maxResistivity);
        getStyleClass().add("color-bar");
        setMouseTransparent(true);

        // 标题摆在图面上沿的上方，跟原来的位置一致；
        // 用带底的样式，放大后图面压在下面也看得清
        title.setLayoutX(-6);
        title.setLayoutY(-26);
        title.setStyle(SectionStyle.chip(12));
        getChildren().add(title);

        bar.setFill(gradient());
        bar.setStroke(SectionStyle.COLOR_BAR_BORDER);
        bar.setStrokeWidth(0.8);
        getChildren().add(bar);

        double step = scale.max() / STEPS;
        int decimals = SectionStyle.decimalsFor(step);

        for (int k = 0; k <= STEPS; k++) {

            Line tick = new Line(0, 0, TICK_LEN, 0);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            ticks.add(tick);
            getChildren().add(tick);

            Label label = new Label(SectionStyle.formatTick(step * k, decimals));
            label.setStyle(SectionStyle.muted(10));
            tickLabels.add(label);
            getChildren().add(label);
        }
    }

    /** 色标带：低阻（浅）在下，高阻（深）在上。 */
    private LinearGradient gradient() {

        Stop[] stops = new Stop[11];
        for (int i = 0; i <= 10; i++) {
            stops[10 - i] = new Stop(1 - i / 10.0, scale.colorOf(scale.max() * i / 10.0));
        }
        return new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, stops);
    }

    @Override
    protected double computePrefWidth(double height) {
        return WIDTH;
    }

    @Override
    protected double computeMinWidth(double height) {
        return WIDTH;
    }

    /** 高度由 FitPane 定，这里只把色标带和刻度按当前高度摆好。 */
    @Override
    protected void layoutChildren() {

        title.autosize();

        double h = getHeight();
        bar.setHeight(h);

        for (int k = 0; k <= STEPS; k++) {

            double y = h - h * k / STEPS;

            Line tick = ticks.get(k);
            tick.setStartX(0);
            tick.setEndX(TICK_LEN);
            tick.setStartY(0);
            tick.setEndY(0);
            tick.relocate(BAR_W, y);

            Label label = tickLabels.get(k);
            label.autosize();
            label.relocate(LABEL_X, y - label.getHeight() / 2);
        }
    }
}
