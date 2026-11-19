package com.stratigraphic.mapping.model;

/**
 * 一条裂缝。
 *
 * @param depth 裂缝与井轴的交点深度 (m)
 * @param normalElevationDeg 裂缝法向仰角 (°)，允许任意有限值；按 180° 周期等价
 * @param normalAzimuthDeg 裂缝法向方位角 (°)，允许任意有限值；按 360° 周期等价
 * @param resistivity 裂缝电阻率 R1 (Ω·m)，同时用于裂缝颜色分级
 */
public record Fracture(double depth,
                       double normalElevationDeg,
                       double normalAzimuthDeg,
                       double resistivity) {

    public Fracture {
        if (!Double.isFinite(depth)) {
            throw new IllegalArgumentException("裂缝交点深度必须是有效数字");
        }
        if (!Double.isFinite(normalElevationDeg)) {
            throw new IllegalArgumentException("裂缝法向仰角必须是有效数字");
        }
        if (!Double.isFinite(normalAzimuthDeg)) {
            throw new IllegalArgumentException("裂缝法向方位角必须是有效数字");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("裂缝电阻率 R1 必须大于 0");
        }
    }

    /** 仰角按 180° 周期归一化到 [0°, 180°)。 */
    public double normalizedElevationDeg() {
        return normalize(normalElevationDeg, 180);
    }

    /** 方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedAzimuthDeg() {
        return normalize(normalAzimuthDeg, 360);
    }

    private static double normalize(double angle, double period) {
        double result = angle % period;
        return result < 0 ? result + period : result;
    }
}
