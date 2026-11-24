package com.stratigraphic.mapping.model;

/**
 * 正方体异常体。
 *
 * <p>正方体的一条水平轴沿井轴径向，另一条水平轴沿切向，竖轴与井轴平行，
 * 因此方位角既决定正方体中心的位置，也决定它的朝向。边长为正方体棱长。
 *
 * @param centerDepth 中心深度 (m)
 * @param azimuthDeg 中心方位角 (°)，允许任意有限值，按 360° 周期等价
 * @param distanceFromAxis 中心距井轴的水平距离 (m)
 * @param sideLength 正方体棱长 (m)
 * @param resistivity 电阻率 (Ω·m)
 * @param dielectricConstant 相对介电常数
 */
public record CubeAnomaly(double centerDepth,
                          double azimuthDeg,
                          double distanceFromAxis,
                          double sideLength,
                          double resistivity,
                          double dielectricConstant) {

    public CubeAnomaly(double centerDepth,
                       double azimuthDeg,
                       double distanceFromAxis,
                       double sideLength,
                       double resistivity) {
        this(centerDepth, azimuthDeg, distanceFromAxis, sideLength, resistivity,
                DielectricConstant.VALUE);
    }

    public CubeAnomaly {
        if (!Double.isFinite(centerDepth)) {
            throw new IllegalArgumentException("正方体中心深度必须是有效数字");
        }
        if (!Double.isFinite(azimuthDeg)) {
            throw new IllegalArgumentException("正方体方位角必须是有效数字");
        }
        if (!Double.isFinite(distanceFromAxis) || distanceFromAxis < 0) {
            throw new IllegalArgumentException("正方体距井轴水平距离必须是非负数");
        }
        if (!Double.isFinite(sideLength) || sideLength <= 0) {
            throw new IllegalArgumentException("正方体棱长必须大于 0");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("正方体电阻率必须大于 0");
        }
        if (!Double.isFinite(dielectricConstant) || dielectricConstant <= 0) {
            throw new IllegalArgumentException("正方体介电常数 εr 必须大于 0");
        }
    }

    /** 半个棱长 (m)。 */
    public double halfSide() {
        return sideLength / 2;
    }

    /** 方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedAzimuthDeg() {
        double result = azimuthDeg % 360;
        return result < 0 ? result + 360 : result;
    }
}
