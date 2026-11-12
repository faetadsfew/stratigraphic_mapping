package com.stratigraphic.mapping.model;

/**
 * 一条裂缝。
 *
 * @param depth 裂缝与井轴的交点深度 (m)
 * @param normalElevationDeg 裂缝法向仰角 (°)，范围 [0, 180)
 * @param normalAzimuthDeg 裂缝法向方位角 (°)，范围 [0, 360)
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
        if (!Double.isFinite(normalElevationDeg)
                || normalElevationDeg < 0 || normalElevationDeg >= 180) {
            throw new IllegalArgumentException("裂缝法向仰角必须在 [0°, 180°) 范围内");
        }
        if (!Double.isFinite(normalAzimuthDeg)
                || normalAzimuthDeg < 0 || normalAzimuthDeg >= 360) {
            throw new IllegalArgumentException("裂缝法向方位角必须在 [0°, 360°) 范围内");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("裂缝电阻率 R1 必须大于 0");
        }
    }
}
