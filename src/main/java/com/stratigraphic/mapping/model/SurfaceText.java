package com.stratigraphic.mapping.model;

/**
 * 井壁展开图上的曲面文字。
 *
 * <p>方位角和跨度单位为度，深度和纵向跨度单位为米，电阻率单位为 Ω·m。
 */
public record SurfaceText(String content,
                          double centerAzimuthDeg,
                          double centerDepth,
                          double angularSpanDeg,
                          double depthSpanM,
                          double resistivity) {

    public SurfaceText {
        if (content == null || content.isBlank()) {
            content = "文字";
        }
        if (!Double.isFinite(centerAzimuthDeg)) {
            throw new IllegalArgumentException("文字中心方位角必须是有效数字");
        }
        if (!Double.isFinite(centerDepth)) {
            throw new IllegalArgumentException("文字中心深度必须是有效数字");
        }
        if (!Double.isFinite(angularSpanDeg) || angularSpanDeg <= 0) {
            throw new IllegalArgumentException("文字横向跨度角度必须大于 0");
        }
        if (!Double.isFinite(depthSpanM) || depthSpanM <= 0) {
            throw new IllegalArgumentException("文字纵向跨度高度必须大于 0");
        }
        if (!Double.isFinite(resistivity) || resistivity <= 0) {
            throw new IllegalArgumentException("文字电阻率必须大于 0");
        }
    }

    public double normalizedCenterAzimuthDeg() {
        double result = centerAzimuthDeg % 360;
        return result < 0 ? result + 360 : result;
    }
}
