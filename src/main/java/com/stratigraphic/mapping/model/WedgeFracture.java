package com.stratigraphic.mapping.model;

/**
 * 楔形体裂缝。
 *
 * <p>角度单位为度 (°)，深度单位为米 (m)，电阻率单位为 Ω·m。
 * 该对象只用于井壁展开图，不参与岩性剖面图绘制。
 *
 * @param dielectricConstant 相对介电常数
 */
public record WedgeFracture(double vertexAzimuthDeg,
                            double vertexDepth,
                            double leftAzimuthDeg,
                            double leftDepth,
                            double rightAzimuthDeg,
                            double rightDepth,
                            double resistivity,
                            double dielectricConstant) {

    public WedgeFracture(double vertexAzimuthDeg,
                         double vertexDepth,
                         double leftAzimuthDeg,
                         double leftDepth,
                         double rightAzimuthDeg,
                         double rightDepth,
                         double resistivity) {
        this(vertexAzimuthDeg, vertexDepth, leftAzimuthDeg, leftDepth,
                rightAzimuthDeg, rightDepth, resistivity, DielectricConstant.VALUE);
    }

    public WedgeFracture {
        if (!Double.isFinite(vertexAzimuthDeg)) {
            throw new IllegalArgumentException("楔形体顶点方位角必须是有效数字");
        }
        if (!Double.isFinite(vertexDepth)) {
            throw new IllegalArgumentException("楔形体顶点深度必须是有效数字");
        }
        if (!Double.isFinite(leftAzimuthDeg)) {
            throw new IllegalArgumentException("楔形体左底方位角必须是有效数字");
        }
        if (!Double.isFinite(leftDepth)) {
            throw new IllegalArgumentException("楔形体左底深度必须是有效数字");
        }
        if (!Double.isFinite(rightAzimuthDeg)) {
            throw new IllegalArgumentException("楔形体右底方位角必须是有效数字");
        }
        if (!Double.isFinite(rightDepth)) {
            throw new IllegalArgumentException("楔形体右底深度必须是有效数字");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("楔形体电阻率必须大于 0");
        }
        if (!Double.isFinite(dielectricConstant) || dielectricConstant <= 0) {
            throw new IllegalArgumentException("楔形体介电常数 εr 必须大于 0");
        }
    }

    /** 顶点方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedVertexAzimuthDeg() {
        return normalize(vertexAzimuthDeg);
    }

    /** 左底方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedLeftAzimuthDeg() {
        return normalize(leftAzimuthDeg);
    }

    /** 右底方位角按 360° 周期归一化到 [0°, 360°)。 */
    public double normalizedRightAzimuthDeg() {
        return normalize(rightAzimuthDeg);
    }

    private static double normalize(double angle) {
        double result = angle % 360;
        return result < 0 ? result + 360 : result;
    }
}
