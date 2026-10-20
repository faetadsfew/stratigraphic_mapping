package com.stratigraphic.mapping.wall;

import javafx.geometry.Point3D;

/**
 * 井壁水平截面的三维几何映射。
 *
 * <p>坐标约定：X 正东、Y 正北、Z 向上；井轴方向 {@code d} 指向 MD 增加方向，
 * 即沿井向下的单位向量。对每个电极，先在垂直于井轴的圆形截面上建立正交基，
 * 再将其转换到地面坐标。用户选择的上、下截面始终是水平面。
 *
 * <p>横坐标采用水平椭圆截面的真实弧长。192 个电极仍位于圆周等角位置，
 * 但投影到水平截面椭圆后，弧长间距一般不再相等。
 */
public final class WallGeometry {

    private static final int ELECTRODE_COUNT = WallData.ELECTRODE_COUNT;
    public static final int PLATE_COUNT = 8;
    public static final int CHANNELS_PER_PLATE = 24;
    private static final double TWO_PI = Math.PI * 2;

    private final WallParameters parameters;
    private final WallData data;

    private final double axisX;
    private final double axisY;
    private final double axisZ;
    private final double cosInclination;
    private final double radialExtentZ;

    private final double[] electrodeRadialX = new double[ELECTRODE_COUNT];
    private final double[] electrodeRadialY = new double[ELECTRODE_COUNT];
    private final double[] electrodeRadialZ = new double[ELECTRODE_COUNT];
    private final double[] horizontalRadialX = new double[ELECTRODE_COUNT];
    private final double[] horizontalRadialY = new double[ELECTRODE_COUNT];
    private final double[] electrodeArc = new double[ELECTRODE_COUNT];

    private double ellipsePerimeter;
    private final double zTop;
    private final double zBottom;
    private final double requiredMinMd;
    private final double requiredMaxMd;

    public WallGeometry(WallData data, WallParameters parameters) {

        this.data = data;
        this.parameters = parameters;

        double inclination = Math.toRadians(parameters.inclinationDeg());

        // 当前版本取消方位角输入，井轴暂取在南北竖直面内并向正北倾斜。
        // 井轴指向 MD 增加方向，Z 分量为负表示越深 Z 越小。
        this.axisX = 0;
        this.axisY = Math.sin(inclination);
        this.axisZ = -Math.cos(inclination);
        this.cosInclination = Math.cos(inclination);

        CompassDirection compass = parameters.firstElectrodeDirection();
        double dot = compass.x() * axisX + compass.y() * axisY + compass.z() * axisZ;

        double e1x = compass.x() - dot * axisX;
        double e1y = compass.y() - dot * axisY;
        double e1z = compass.z() - dot * axisZ;
        double e1Length = Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z);

        if (e1Length < 1e-9) {
            throw new IllegalArgumentException(
                    "第 1 电极方向与井轴方向重合，无法确定圆周起始方向");
        }
        e1x /= e1Length;
        e1y /= e1Length;
        e1z /= e1Length;

        // e2 = d × e1，二者一起构成井筒横截面的正交基。
        double e2x = axisY * e1z - axisZ * e1y;
        double e2y = axisZ * e1x - axisX * e1z;
        double e2z = axisX * e1y - axisY * e1x;

        double step = TWO_PI / ELECTRODE_COUNT;
        if (parameters.unfoldDirection() == UnfoldDirection.COUNTERCLOCKWISE) {
            step = -step;
        }

        for (int electrode = 0; electrode < ELECTRODE_COUNT; electrode++) {

            double angle = step * electrode;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            electrodeRadialX[electrode] = cos * e1x + sin * e2x;
            electrodeRadialY[electrode] = cos * e1y + sin * e2y;
            electrodeRadialZ[electrode] = cos * e1z + sin * e2z;

            // 水平截面相对井轴中心点的径向向量：
            // r_H = R * [n_xy + n_z * tan(i) * d_xy/|d_xy|]。
            // 这里使用的是水平面与倾斜圆柱面的真实交线，而不是圆柱在水平面的
            // 正交投影；两者在斜井时具有不同的椭圆半轴。
            horizontalRadialX[electrode] = WallParameters.WELL_RADIUS_M
                    * (electrodeRadialX[electrode]
                    + electrodeRadialZ[electrode] * axisX / cosInclination);
            horizontalRadialY[electrode] = WallParameters.WELL_RADIUS_M
                    * (electrodeRadialY[electrode]
                    + electrodeRadialZ[electrode] * axisY / cosInclination);
        }

        this.radialExtentZ = Math.sqrt(e1z * e1z + e2z * e2z);
        this.zTop = zAtCenterMd(parameters.upperCenterMd());
        this.zBottom = zAtCenterMd(parameters.lowerCenterMd());

        this.requiredMinMd = (-WallParameters.WELL_RADIUS_M * radialExtentZ - zTop)
                / cosInclination;
        this.requiredMaxMd = (WallParameters.WELL_RADIUS_M * radialExtentZ - zBottom)
                / cosInclination;

        checkDataRange();
        calculateEllipseArcs();
    }

    private void calculateEllipseArcs() {

        double[] x = new double[ELECTRODE_COUNT];
        double[] y = new double[ELECTRODE_COUNT];

        for (int i = 0; i < ELECTRODE_COUNT; i++) {
            x[i] = horizontalRadialX[i];
            y[i] = horizontalRadialY[i];
        }

        double perimeter = 0;
        for (int i = 0; i < ELECTRODE_COUNT; i++) {
            int next = (i + 1) % ELECTRODE_COUNT;
            perimeter += Math.hypot(x[next] - x[i], y[next] - y[i]);
        }

        electrodeArc[0] = 0;
        for (int i = 1; i < ELECTRODE_COUNT; i++) {
            electrodeArc[i] = electrodeArc[i - 1]
                    + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
        this.ellipsePerimeter = perimeter;
    }

    private void checkDataRange() {

        if (requiredMinMd < data.minDepth() - 1e-9) {
            throw new IllegalArgumentException(String.format(
                    "参数错误：%n"
                            + "当前取井区域需要的最浅 MD：%.8f m%n"
                            + "Excel 有效最浅 MD：%.8f m%n"
                            + "请调整井斜角、上截面深度或取井长度。",
                    requiredMinMd, data.minDepth()));
        }

        if (requiredMaxMd > data.maxDepth() + 1e-9) {
            throw new IllegalArgumentException(String.format(
                    "参数错误：%n"
                            + "当前取井区域需要的最深 MD：%.8f m%n"
                            + "Excel 有效最深 MD：%.8f m%n"
                            + "请调整井斜角、上截面深度或取井长度。",
                    requiredMaxMd, data.maxDepth()));
        }
    }

    public WallData data() {
        return data;
    }

    public WallParameters parameters() {
        return parameters;
    }

    public double zTop() {
        return zTop;
    }

    public double zBottom() {
        return zBottom;
    }

    public double requiredMinMd() {
        return requiredMinMd;
    }

    public double requiredMaxMd() {
        return requiredMaxMd;
    }

    public double requiredMdSpan() {
        return requiredMaxMd - requiredMinMd;
    }

    public double ellipsePerimeter() {
        return ellipsePerimeter;
    }

    public double zAtCenterMd(double md) {
        return -md * cosInclination;
    }

    public double centerMdAtZ(double z) {
        return -z / cosInclination;
    }

    /** 电极编号为 0～191；返回该电极所在的水平椭圆展开弧长。 */
    public double electrodeArc(int electrodeIndex) {
        return electrodeArc[electrodeIndex];
    }

    /** 返回第 {@code electrodeIndex} 条电极带的左边界。 */
    public double stripLeft(int electrodeIndex) {
        if (electrodeIndex == 0) {
            return 0;
        }
        return (electrodeArc[electrodeIndex - 1] + electrodeArc[electrodeIndex]) / 2;
    }

    /** 返回第 {@code electrodeIndex} 条电极带的右边界。 */
    public double stripRight(int electrodeIndex) {
        if (electrodeIndex == ELECTRODE_COUNT - 1) {
            return ellipsePerimeter;
        }
        return (electrodeArc[electrodeIndex] + electrodeArc[electrodeIndex + 1]) / 2;
    }

    /**
     * 返回累计第 {@code channelNumber} 个通道结束处的真实弧长边界。
     * 编号为 0 时返回展开图左端，编号为 192 时返回展开图右端。
     */
    public double channelBoundaryArc(int channelNumber) {

        if (channelNumber < 0
                || channelNumber > ELECTRODE_COUNT
                || channelNumber % CHANNELS_PER_PLATE != 0) {
            throw new IllegalArgumentException(
                    "通道边界必须是 0、24、48、...、192");
        }
        if (channelNumber == 0) {
            return 0;
        }
        if (channelNumber == ELECTRODE_COUNT) {
            return ellipsePerimeter;
        }
        return (electrodeArc[channelNumber - 1]
                + electrodeArc[channelNumber]) / 2;
    }

    /** 返回 8 个极板区域末端的真实弧长边界，最后一项为展开图右端。 */
    public double[] plateBoundaryArcs() {

        double[] boundaries = new double[PLATE_COUNT];
        for (int plate = 0; plate < PLATE_COUNT; plate++) {
            boundaries[plate] = plateBoundaryArc(plate);
        }
        return boundaries;
    }

    /** 返回指定极板区域末端的真实弧长边界。 */
    public double plateBoundaryArc(int plateIndex) {

        if (plateIndex < 0 || plateIndex >= PLATE_COUNT) {
            throw new IllegalArgumentException("极板编号必须在 0～7 范围内");
        }
        return channelBoundaryArc((plateIndex + 1) * CHANNELS_PER_PLATE);
    }

    /** 返回指定极板区域左端的真实弧长边界。 */
    public double plateStartArc(int plateIndex) {

        if (plateIndex < 0 || plateIndex >= PLATE_COUNT) {
            throw new IllegalArgumentException("极板编号必须在 0～7 范围内");
        }
        return plateIndex == 0 ? 0 : plateBoundaryArc(plateIndex - 1);
    }

    /** 返回指定极板区域在展开图横坐标上的真实中心弧长。 */
    public double plateCenterArc(int plateIndex) {
        return (plateStartArc(plateIndex) + plateBoundaryArc(plateIndex)) / 2;
    }

    /** 8 个极板区域的弧长分隔位置。 */
    public double[] plateSeparatorArcs() {

        double[] separators = new double[PLATE_COUNT - 1];
        for (int plate = 0; plate < separators.length; plate++) {
            separators[plate] = plateBoundaryArc(plate);
        }
        return separators;
    }

    public double mdAtElectrodeAndZ(int electrodeIndex, double z) {
        return (WallParameters.WELL_RADIUS_M * electrodeRadialZ[electrodeIndex] - z)
                / cosInclination;
    }

    /** 指定电极处，上水平截面交线对应的 MD。 */
    public double topMdAtElectrode(int electrodeIndex) {
        return mdAtElectrodeAndZ(electrodeIndex, zTop);
    }

    /** 指定电极处，下水平截面交线对应的 MD。 */
    public double bottomMdAtElectrode(int electrodeIndex) {
        return mdAtElectrodeAndZ(electrodeIndex, zBottom);
    }

    /**
     * 指定水平截面在展开弧长位置处的 MD 边界。
     *
     * <p>电极圆周方向按相邻电极线性插值，因此倾斜井的水平截面边界会呈现连续
     * 正弦/余弦形状，而不是固定矩形边界。
     */
    public double mdBoundaryAtArc(double arc, double z) {

        double wrappedArc = Math.max(0, Math.min(ellipsePerimeter, arc));
        for (int electrode = 0; electrode < ELECTRODE_COUNT - 1; electrode++) {
            double left = electrodeArc[electrode];
            double right = electrodeArc[electrode + 1];
            if (wrappedArc <= right) {
                double fraction = right - left < 1e-12
                        ? 0
                        : (wrappedArc - left) / (right - left);
                return lerp(mdAtElectrodeAndZ(electrode, z),
                        mdAtElectrodeAndZ(electrode + 1, z), fraction);
            }
        }

        double left = electrodeArc[ELECTRODE_COUNT - 1];
        double fraction = ellipsePerimeter - left < 1e-12
                ? 0
                : (wrappedArc - left) / (ellipsePerimeter - left);
        return lerp(mdAtElectrodeAndZ(ELECTRODE_COUNT - 1, z),
                mdAtElectrodeAndZ(0, z), fraction);
    }

    public double amplitudeAtElectrodeAndZ(int electrodeIndex, double z) {
        return data.amplitudeAt(electrodeIndex, mdAtElectrodeAndZ(electrodeIndex, z));
    }

    public double phaseAtElectrodeAndZ(int electrodeIndex, double z) {
        return data.phaseAt(electrodeIndex, mdAtElectrodeAndZ(electrodeIndex, z));
    }

    /** 给定电极和水平面高度，返回其地面三维坐标，原点取 MD=0 的井轴中心。 */
    public Point3D pointAtElectrodeAndZ(int electrodeIndex, double z) {

        double md = mdAtElectrodeAndZ(electrodeIndex, z);
        return new Point3D(
                md * axisX + WallParameters.WELL_RADIUS_M * electrodeRadialX[electrodeIndex],
                md * axisY + WallParameters.WELL_RADIUS_M * electrodeRadialY[electrodeIndex],
                md * axisZ + WallParameters.WELL_RADIUS_M * electrodeRadialZ[electrodeIndex]);
    }

    /** 给定电极和 MD，返回其地面三维坐标。 */
    public Point3D pointAtElectrodeAndMd(int electrodeIndex, double md) {

        return new Point3D(
                md * axisX + WallParameters.WELL_RADIUS_M * electrodeRadialX[electrodeIndex],
                md * axisY + WallParameters.WELL_RADIUS_M * electrodeRadialY[electrodeIndex],
                md * axisZ + WallParameters.WELL_RADIUS_M * electrodeRadialZ[electrodeIndex]);
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }
}
