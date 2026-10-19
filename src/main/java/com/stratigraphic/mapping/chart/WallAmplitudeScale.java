package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;

/**
 * 模值对数色标：低值淡黄，中值橙色，高值红色。
 */
public final class WallAmplitudeScale {

    private static final Color[] PALETTE = {
            Color.web("#FFF7B2"),
            Color.web("#FDE68A"),
            Color.web("#FBBF24"),
            Color.web("#F97316"),
            Color.web("#EA580C"),
            Color.web("#C81E1E"),
            Color.web("#7F1D1D")
    };

    private final double min;
    private final double max;
    private final double logMin;
    private final double logSpan;

    public WallAmplitudeScale(double min, double max) {

        if (!(min > 0) || !Double.isFinite(min)) {
            throw new IllegalArgumentException("模值色标下限必须大于 0");
        }
        if (!(max > min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("模值色标上限必须大于下限");
        }

        this.min = min;
        this.max = max;
        this.logMin = Math.log10(min);
        this.logSpan = Math.log10(max) - logMin;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public Color colorOf(double value) {

        if (!(value > 0) || !Double.isFinite(value)) {
            return Color.WHITE;
        }

        double t = (Math.log10(value) - logMin) / logSpan;
        t = Math.max(0, Math.min(1, t));

        double scaled = t * (PALETTE.length - 1);
        int lower = Math.min(PALETTE.length - 2, (int) Math.floor(scaled));
        return PALETTE[lower].interpolate(PALETTE[lower + 1], scaled - lower);
    }
}
