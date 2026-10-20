package com.stratigraphic.mapping.wall;

/**
 * 井壁展开计算参数。井筒半径按仪器定义固定为 0.1 m。
 */
public final class WallParameters {

    public static final double WELL_RADIUS_M = 0.1;

    private final double inclinationDeg;
    private final double upperCenterMd;
    private final double intervalLength;
    private final CompassDirection firstElectrodeDirection;
    private final UnfoldDirection unfoldDirection;

    public WallParameters(double inclinationDeg,
                          double upperCenterMd,
                          double intervalLength,
                          CompassDirection firstElectrodeDirection,
                          UnfoldDirection unfoldDirection) {

        check(Double.isFinite(inclinationDeg)
                        && inclinationDeg >= 0 && inclinationDeg < 90,
                "井斜角必须在 0°（含）到 90°（不含）之间");
        check(Double.isFinite(upperCenterMd), "上截面中心深度必须是有效数字");
        check(Double.isFinite(intervalLength)
                        && intervalLength >= 0.3 && intervalLength <= 1.0,
                "取井长度必须在 0.3～1.0 m 之间");
        check(firstElectrodeDirection != null, "请选择第 1 电极方向");
        check(unfoldDirection != null, "请选择电极展开方向");

        this.inclinationDeg = inclinationDeg;
        this.upperCenterMd = upperCenterMd;
        this.intervalLength = intervalLength;
        this.firstElectrodeDirection = firstElectrodeDirection;
        this.unfoldDirection = unfoldDirection;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public double inclinationDeg() {
        return inclinationDeg;
    }

    public double upperCenterMd() {
        return upperCenterMd;
    }

    public double lowerCenterMd() {
        return upperCenterMd + intervalLength;
    }

    public double intervalLength() {
        return intervalLength;
    }

    public CompassDirection firstElectrodeDirection() {
        return firstElectrodeDirection;
    }

    public UnfoldDirection unfoldDirection() {
        return unfoldDirection;
    }
}
