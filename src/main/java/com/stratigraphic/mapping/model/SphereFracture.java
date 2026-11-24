package com.stratigraphic.mapping.model;

/**
 * 圆形裂缝（球体）数据。
 *
 * @param centerDepth 球心深度 (m)
 * @param azimuthDeg 方位角 (°)，允许任意有限值，按 360° 周期等价
 * @param distanceFromAxis 球心距井轴的水平距离 (m)
 * @param radius 球半径 (m)
 * @param resistivity 电阻率 (Ω·m)
 * @param dielectricConstant 相对介电常数
 */
public record SphereFracture(double centerDepth,
                             double azimuthDeg,
                             double distanceFromAxis,
                             double radius,
                             double resistivity,
                             double dielectricConstant) {

    public SphereFracture(double centerDepth,
                          double azimuthDeg,
                          double distanceFromAxis,
                          double radius,
                          double resistivity) {
        this(centerDepth, azimuthDeg, distanceFromAxis, radius, resistivity,
                DielectricConstant.VALUE);
    }

    public SphereFracture {
        if (!Double.isFinite(centerDepth)) {
            throw new IllegalArgumentException("球体中心深度必须是有效数字");
        }
        if (!Double.isFinite(azimuthDeg)) {
            throw new IllegalArgumentException("球体方位角必须是有效数字");
        }
        if (!Double.isFinite(distanceFromAxis) || distanceFromAxis < 0) {
            throw new IllegalArgumentException("球体距井轴水平距离必须是非负数");
        }
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("球体半径必须大于 0");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("球体电阻率必须大于 0");
        }
        if (!Double.isFinite(dielectricConstant) || dielectricConstant <= 0) {
            throw new IllegalArgumentException("球体介电常数 εr 必须大于 0");
        }
    }

    /** 方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedAzimuthDeg() {
        double result = azimuthDeg % 360;
        return result < 0 ? result + 360 : result;
    }
}
