package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;

/**
 * 两张图的统一配色与文字样式。
 */
public final class SectionStyle {

    private SectionStyle() {
    }

    // ---------------- 颜色 ----------------

    /** 图面边框。 */
    public static final Color FRAME = Color.web("#2D3138");

    /** 刻度线与刻度数字。 */
    public static final Color TICK = Color.web("#555555");

    /** 井壁轮廓（线框图用）。 */
    public static final Color WELL_OUTLINE = Color.web("#7A7A7A");

    /** 井轴（线框图用）。 */
    public static final Color WELL_AXIS = Color.web("#BBBBBB");

    /** 界面直线（线框图用）。 */
    public static final Color INTERFACE_LINE = Color.web("#2D3138");

    /** 色标带描边。 */
    public static final Color COLOR_BAR_BORDER = Color.web("#8A8A8A");

    // ---------------- 文字样式 ----------------

    /** 轴标题等加粗小字。 */
    public static String bold(int size) {
        return "-fx-font-size: " + size + "px;"
                + "-fx-font-weight: bold;"
                + "-fx-text-fill: #1a1a1a;";
    }

    /** 刻度数字等弱化文字。 */
    public static String muted(int size) {
        return "-fx-font-size: " + size + "px;"
                + "-fx-text-fill: #777777;";
    }

    /** 压在图形上的标注，带半透明白底。 */
    public static String chip(int size) {
        return "-fx-font-size: " + size + "px;"
                + "-fx-text-fill: #2B2B2B;"
                + "-fx-background-color: rgba(255,255,255,0.78);"
                + "-fx-background-radius: 3;"
                + "-fx-padding: 1 5 1 5;";
    }

    // ---------------- 刻度辅助 ----------------

    /**
     * 取一个「好看」的刻度间隔：在 10 的整数次幂的 1 / 2 / 5 倍里，
     * 选不超过 {@code span} 的最大者。
     */
    public static double niceStep(double span) {

        if (!(span > 0) || !Double.isFinite(span)) {
            return 1;
        }

        double base = Math.pow(10, Math.floor(Math.log10(span)));

        for (double multiple : new double[]{10, 5, 2, 1}) {
            if (base * multiple <= span * (1 + 1e-9)) {
                return base * multiple;
            }
        }
        return base;
    }

    /** 刻度间隔决定的小数位数。 */
    public static int decimalsFor(double step) {
        if (!(step > 0) || !Double.isFinite(step) || step >= 1) {
            return 0;
        }
        return (int) Math.ceil(-Math.log10(step) - 1e-9);
    }

    /** 按小数位数格式化刻度数字。 */
    public static String formatTick(double value, int decimals) {
        return String.format("%." + decimals + "f", value);
    }
}
