package com.stratigraphic.mapping.model;

/**
 * 按井壁展开图上的实物尺寸生成异常体参数。
 *
 * <p>模型、切片和正演统一使用米和度；手绘展开图上量到的却是厘米。
 * 本类只做「展开图尺寸 → {@link SphereFracture} / {@link CubeAnomaly} /
 * {@link WedgeFracture}」的换算，避免在输入文件里手算方位角。
 *
 * <p>约定：
 * <ul>
 *   <li>球心和正方体中心放在井壁上（距井轴 = 井眼半径），这样展开图上的
 *       球体交线约等于直径 {@code diameter} 的圆，正方体交线高度等于棱长；</li>
 *   <li>三角形底边是沿井壁的弧长，按 {@code 弧长 / 井眼半径} 换算成方位角跨度；</li>
 *   <li>{@code midDepth} 指三角形高的中点，不是三角形重心。</li>
 * </ul>
 */
public final class WallAnomalyLayout {

    /** 1 cm 对应的米数。 */
    public static final double CM = 0.01;

    private WallAnomalyLayout() {
    }

    /** 把井壁展开图上的弧长 (m) 换算成方位角跨度 (°)。 */
    public static double arcSpanDeg(double arcLengthM, double wellRadiusM) {

        require(wellRadiusM > 0 && Double.isFinite(wellRadiusM),
                "井眼半径必须大于 0");
        require(arcLengthM > 0 && Double.isFinite(arcLengthM),
                "弧长必须大于 0");
        return Math.toDegrees(arcLengthM / wellRadiusM);
    }

    /** 球心位于井壁、展开图为直径 {@code diameterM} 的圆。 */
    public static SphereFracture circleOnWall(double centerDepth,
                                              double centerAzimuthDeg,
                                              double wellRadiusM,
                                              double diameterM,
                                              double resistivity) {
        require(wellRadiusM > 0 && Double.isFinite(wellRadiusM),
                "井眼半径必须大于 0");
        require(diameterM > 0 && Double.isFinite(diameterM),
                "圆直径必须大于 0");
        return new SphereFracture(centerDepth, centerAzimuthDeg, wellRadiusM,
                diameterM / 2, resistivity);
    }

    /** 中心位于井壁、展开图为边长 {@code sideM} 的方形。 */
    public static CubeAnomaly squareOnWall(double centerDepth,
                                           double centerAzimuthDeg,
                                           double wellRadiusM,
                                           double sideM,
                                           double resistivity) {
        require(wellRadiusM > 0 && Double.isFinite(wellRadiusM),
                "井眼半径必须大于 0");
        require(sideM > 0 && Double.isFinite(sideM),
                "正方体棱长必须大于 0");
        return new CubeAnomaly(centerDepth, centerAzimuthDeg, wellRadiusM,
                sideM, resistivity);
    }

    /**
     * 顶角朝下的三角形：底边 {@code baseLengthM} 为沿井壁的弧长，
     * 高为 {@code heightM}，三角形高的中点位于 {@code midDepth}。
     */
    public static WedgeFracture downTriangleOnWall(double centerAzimuthDeg,
                                                   double midDepth,
                                                   double wellRadiusM,
                                                   double baseLengthM,
                                                   double heightM,
                                                   double resistivity) {

        require(Double.isFinite(midDepth), "三角形高的中点深度必须是有效数字");
        require(heightM > 0 && Double.isFinite(heightM), "三角形高必须大于 0");

        double halfSpan = arcSpanDeg(baseLengthM, wellRadiusM) / 2;
        double baseDepth = midDepth - heightM / 2;
        double vertexDepth = midDepth + heightM / 2;

        return new WedgeFracture(centerAzimuthDeg, vertexDepth,
                centerAzimuthDeg - halfSpan, baseDepth,
                centerAzimuthDeg + halfSpan, baseDepth,
                resistivity);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
