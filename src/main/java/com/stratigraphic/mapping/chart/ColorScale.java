package com.stratigraphic.mapping.chart;

import javafx.scene.paint.Color;

/**
 * 电阻率 → 颜色的顺序色标：浅（低阻）→ 深（高阻）。
 *
 * <p>电阻率往往跨好几个数量级，所以色标按对数取样：下界取一个大于 0 的整齐数
 * （由数据最小值向下取整），上界由数据最大值向上取整，颜色在 log10(下界) 与
 * log10(上界) 之间均匀分布，换一组电阻率也不用改代码。
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

    private final double min;
    private final double max;
    private final double logMin;
    private final double logSpan;

    /**
     * @param min 数据里的最小电阻率 (Ω·m)，必须大于 0
     * @param max 数据里的最大电阻率 (Ω·m)
     */
    public ColorScale(double min, double max) {

        double lo = niceFloor(min);
        double hi = niceCeil(max);

        // 数据只有一个值、或上下界挤到一起时，把上界抬一个数量级，色标才有跨度
        if (!(hi > lo)) {
            hi = lo * 10;
        }

        this.min = lo;
        this.max = hi;
        this.logMin = Math.log10(lo);
        this.logSpan = Math.log10(hi) - this.logMin;
    }

    /** 色标下界 (Ω·m)，恒大于 0。 */
    public double min() {
        return min;
    }

    /** 色标上界 (Ω·m)。 */
    public double max() {
        return max;
    }

    /** 电阻率 → 颜色：下界最浅，上界最深，中间按对数插值；坏值按最浅处理。 */
    public Color colorOf(double rho) {

        // 对数的定义域是正数，0 / 负数 / NaN 都退回最浅
        if (!(rho > 0) || !Double.isFinite(rho)) {
            return PALETTE[0];
        }

        double t = (Math.log10(rho) - logMin) / logSpan;

        if (t <= 0) {
            return PALETTE[0];
        }
        if (t >= 1) {
            return PALETTE[PALETTE.length - 1];
        }

        double scaled = t * (PALETTE.length - 1);
        int i = (int) Math.floor(scaled);
        return PALETTE[i].interpolate(PALETTE[i + 1], scaled - i);
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

    /** 把下界压到 1 / 2 / 2.5 / 5 的整数次幂倍数，且保证结果大于 0。 */
    private static double niceFloor(double value) {

        if (!(value > 0) || !Double.isFinite(value)) {
            return 1;
        }

        double base = Math.pow(10, Math.floor(Math.log10(value)));

        for (double multiple : new double[]{10, 5, 2.5, 2, 1}) {
            if (base * multiple <= value * (1 + 1e-9)) {
                return base * multiple;
            }
        }
        return base;
    }
}
