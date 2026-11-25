package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;

/**
 * 裂缝法向角到绘图坐标的转换。
 *
 * <p>示意图约定：仰角 beta=0° 为水平裂缝，beta=90° 为垂直裂缝；
 * 方位角与输入定义同向展开。
 */
final class FractureGeometry {

    private FractureGeometry() {
    }

    static double normalX(Fracture fracture) {
        double elevation = Math.toRadians(90 - fracture.normalizedElevationDeg());
        double azimuth = Math.toRadians(fracture.normalizedAzimuthDeg());
        return Math.cos(elevation) * Math.cos(azimuth);
    }

    static double normalY(Fracture fracture) {
        double elevation = Math.toRadians(90 - fracture.normalizedElevationDeg());
        double azimuth = Math.toRadians(fracture.normalizedAzimuthDeg());
        return Math.cos(elevation) * Math.sin(azimuth);
    }

    static double normalZ(Fracture fracture) {
        return Math.sin(Math.toRadians(90 - fracture.normalizedElevationDeg()));
    }

    static double traceDepth(Fracture fracture, double wellRadius, double angleDeg) {
        double nz = normalZ(fracture);
        if (Math.abs(nz) < 1e-9) {
            return fracture.depth();
        }
        double angle = Math.toRadians(angleDeg);
        return fracture.depth() - wellRadius
                * (normalX(fracture) * Math.cos(angle)
                + normalY(fracture) * Math.sin(angle)) / nz;
    }

    static double sectionDepth(Fracture fracture, double horizontalOffsetM) {
        double nz = normalZ(fracture);
        if (Math.abs(nz) < 1e-9) {
            return fracture.depth();
        }
        return fracture.depth() - horizontalOffsetM * normalX(fracture) / nz;
    }

    /**
     * 裂缝在深度方向的视厚度 (m)：两条平行边界面的深度差。
     * 对近垂直裂缝没有意义，返回 NaN。
     */
    static double depthThicknessM(Fracture fracture) {
        double nz = Math.abs(normalZ(fracture));
        return nz < 1e-6 ? Double.NaN : fracture.aperture() / nz;
    }

    /**
     * 近垂直裂缝在井壁展开图横坐标（弧长）方向的视宽度 (m)。
     * 非垂直裂缝返回 NaN。
     */
    static double arcThicknessM(Fracture fracture) {
        double horizontal = Math.hypot(normalX(fracture), normalY(fracture));
        return horizontal < 1e-6 ? Double.NaN : fracture.aperture() / horizontal;
    }
}
