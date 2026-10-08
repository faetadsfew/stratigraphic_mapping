package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;

/**
 * 电阻率 → 颜色的顺序色标：浅（低阻）→ 深（高阻）。
 *
 * <p>色标下界固定为 0，上界按输入数据取一个整齐的整数，从 0 到上界均匀分布，
 * 这样换一组电阻率也不用改代码。
 */
public final class ColorScale {

    /** 11 级调色板，浅 → 深。 */
    private static final Color[] PALETTE = {
            Color.web("#FEEFE0"),
            Color.web("#F7D0A6"),
            Color.web("#E3B482"),
            Color.web("#CC9961"),
            Color.web("#B48044"),
            Color.web("#9B682B"),
            Color.web("#805216"),
            Color.web("#643E0A"),
            Color.web("#492B05"),
            Color.web("#2F1A01"),
            Color.web("#180900")
    };

    private final double max;

    public ColorScale(double max) {
        this.max = niceCeil(max);
    }

    /** 色标上界 (Ω·m)。 */
    public double max() {
        return max;
    }

    /** 电阻率 → 颜色：0 取最浅，上界取最深，中间线性插值。 */
    public Color colorOf(double rho) {

        double t = rho / max * (PALETTE.length - 1);

        if (t <= 0) {
            return PALETTE[0];
        }
        if (t >= PALETTE.length - 1) {
            return PALETTE[PALETTE.length - 1];
        }

        int i = (int) Math.floor(t);
        return PALETTE[i].interpolate(PALETTE[i + 1], t - i);
    }

    /** 把上界抬到 1 / 2 / 2.5 / 5 的整数次幂倍数，让刻度读数好看。 */
    private static double niceCeil(double value) {

        if (!(value > 0) || !Double.isFinite(value)) {
            return 10;
        }

        double base = Math.pow(10, Math.floor(Math.log10(value)));

        for (double multiple : new double[]{1, 2, 2.5, 5}) {
            if (value <= base * multiple * (1 + 1e-9)) {
                return base * multiple;
            }
        }
        return base * 10;
    }
}
