package com.stratigraphic.mapping.chart;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * 图上点击后弹出的小数据窗：白底圆角 + 几行字。
 *
 * <p>窗口按文字自己量尺寸，弹在点击点旁边，并且始终压在图面上、不会跑出图面。
 */
final class ChartPopup extends Pane {

    private static final double PAD_X = 12;
    private static final double PAD_Y = 8;

    /** 弹窗离点击点多远。 */
    private static final double GAP = 14;

    private final Rectangle background = new Rectangle();
    private final Label text = new Label();

    ChartPopup() {

        setMouseTransparent(true);
        setVisible(false);

        background.setFill(Color.rgb(255, 255, 255, 0.94));
        background.setStroke(SectionStyle.FRAME);
        background.setStrokeWidth(1);
        background.setArcWidth(6);
        background.setArcHeight(6);

        text.getStyleClass().add("chart-info-text");

        getChildren().addAll(background, text);
    }

    /** 在 (x, y) 旁边弹出，内容不会超出 [0, width] × [0, height] 的图面。 */
    void show(String content, double x, double y, double width, double height) {

        text.setText(content);
        text.applyCss();
        text.autosize();

        double w = text.getWidth() + 2 * PAD_X;
        double h = text.getHeight() + 2 * PAD_Y;

        background.setWidth(w);
        background.setHeight(h);

        text.setLayoutX(PAD_X);
        text.setLayoutY(PAD_Y);

        // 默认弹在右下，贴边就翻到另一侧
        double px = x + GAP;
        double py = y + GAP;

        if (px + w > width) {
            px = x - w - GAP;
        }
        if (py + h > height) {
            py = y - h - GAP;
        }

        setLayoutX(Math.max(0, Math.min(px, width - w)));
        setLayoutY(Math.max(0, Math.min(py, height - h)));
        setVisible(true);
    }

    void hide() {
        setVisible(false);
    }
}
