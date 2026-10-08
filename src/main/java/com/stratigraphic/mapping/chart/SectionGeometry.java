package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.geometry.Point2D;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 剖面图的几何换算：比例尺、图面范围、深度与像素的互转，以及界面直线相关的计算。
 *
 * <p>整张图按真实物理尺寸出图：
 * <ul>
 *   <li>图面宽 = 6 × 井眼半径，也就是井径恰好占图面宽的三分之一；</li>
 *   <li>图面上沿 / 下沿的深度 = 最浅 / 最深界面的深度，再上下各留 {@link #DEPTH_MARGIN} 的余量。</li>
 * </ul>
 *
 * <p>坐标约定：x 向右为正，y 向下为正（屏幕坐标）。
 */
public final class SectionGeometry {

    /** 比例尺：1 m 对应多少像素（即 0.1 m 用 60 px 表示）。 */
    public static final double PX_PER_M = 600;

    /** 图面上下各留的深度余量 (m)。 */
    public static final double DEPTH_MARGIN = 0.1;

    /** 图面宽度 / 井眼半径。 */
    private static final double FIGURE_WIDTH_OVER_RADIUS = 6;

    private final List<StratumInterface> interfaces;
    private final double wellRadiusM;

    private final double zTop;
    private final double zBottom;

    private final double figureLeft;
    private final double figureTop;
    private final double figureWidth;
    private final double figureHeight;

    private final double axisX;
    private final double wellRadiusPx;

    public SectionGeometry(StratumModel model, double figureLeft, double figureTop) {

        this.interfaces = model.interfaces();
        this.wellRadiusM = model.wellRadius();

        this.zTop = model.minDepth() - DEPTH_MARGIN;
        this.zBottom = model.maxDepth() + DEPTH_MARGIN;

        this.figureLeft = figureLeft;
        this.figureTop = figureTop;
        this.figureWidth = FIGURE_WIDTH_OVER_RADIUS * wellRadiusM * PX_PER_M;
        this.figureHeight = (zBottom - zTop) * PX_PER_M;

        this.axisX = figureLeft + figureWidth / 2;
        this.wellRadiusPx = wellRadiusM * PX_PER_M;
    }

    // ---------------- 图面范围 ----------------

    public double figureLeft() {
        return figureLeft;
    }

    public double figureTop() {
        return figureTop;
    }

    public double figureWidth() {
        return figureWidth;
    }

    public double figureHeight() {
        return figureHeight;
    }

    public double figureRight() {
        return figureLeft + figureWidth;
    }

    public double figureBottom() {
        return figureTop + figureHeight;
    }

    /** 井轴（图面中垂线）的横坐标。 */
    public double axisX() {
        return axisX;
    }

    /** 井半径对应的像素数。 */
    public double wellRadiusPx() {
        return wellRadiusPx;
    }

    public double zTop() {
        return zTop;
    }

    public double zBottom() {
        return zBottom;
    }

    /** 图面左边缘对应的水平距离（以井轴为 0），单位 m。 */
    public double leftOffsetM() {
        return (figureLeft - axisX) / PX_PER_M;
    }

    /** 图面右边缘对应的水平距离，单位 m。 */
    public double rightOffsetM() {
        return (figureRight() - axisX) / PX_PER_M;
    }

    // ---------------- 坐标换算 ----------------

    public double zToY(double z) {
        return figureTop + (z - zTop) * PX_PER_M;
    }

    public double yToZ(double y) {
        return zTop + (y - figureTop) / PX_PER_M;
    }

    /** 界面在横坐标 {@code x} 处的屏幕纵坐标。 */
    public double interfaceY(StratumInterface s, double x) {
        return zToY(s.depth()) - (x - axisX) * cot(s.angle());
    }

    /** 界面横坐标每走 1 px 的竖直落差。 */
    public static double cot(double angle) {
        return 1 / Math.tan(Math.toRadians(angle));
    }

    /** 界面法向量，指向更深的一侧。 */
    public static double normalX(double angle) {
        return Math.cos(Math.toRadians(angle));
    }

    public static double normalY(double angle) {
        return Math.sin(Math.toRadians(angle));
    }

    // ---------------- 界面几何 ----------------

    /**
     * 过 (centerX, centerY)、与井轴向上方向成 {@code angle} 度的直线，
     * 两端裁剪到图面边界。
     */
    public Line createLine(double centerX, double centerY, double angle) {

        double radians = Math.toRadians(angle);
        double dirX = Math.sin(radians);
        double dirY = -Math.cos(radians);

        double left = figureLeft;
        double right = figureRight();
        double top = figureTop;
        double bottom = figureBottom();

        // 正向参数 t > 0
        double tForward = Double.MAX_VALUE;
        if (dirX > 0) tForward = Math.min(tForward, (right - centerX) / dirX);
        if (dirX < 0) tForward = Math.min(tForward, (left - centerX) / dirX);
        if (dirY > 0) tForward = Math.min(tForward, (bottom - centerY) / dirY);
        if (dirY < 0) tForward = Math.min(tForward, (top - centerY) / dirY);

        // 反向参数 t < 0
        double tBackward = -Double.MAX_VALUE;
        if (dirX > 0) tBackward = Math.max(tBackward, (left - centerX) / dirX);
        if (dirX < 0) tBackward = Math.max(tBackward, (right - centerX) / dirX);
        if (dirY > 0) tBackward = Math.max(tBackward, (top - centerY) / dirY);
        if (dirY < 0) tBackward = Math.max(tBackward, (bottom - centerY) / dirY);

        return new Line(
                centerX + tForward * dirX,
                centerY + tForward * dirY,
                centerX + tBackward * dirX,
                centerY + tBackward * dirY
        );
    }

    /** 两条界面的交点横坐标；平行（夹角相同）时返回 {@link Double#NaN}。 */
    private double crossingX(StratumInterface a, StratumInterface b) {

        double ca = cot(a.angle());
        double cb = cot(b.angle());

        if (Math.abs(ca - cb) < 1e-12) {
            return Double.NaN;
        }
        return axisX + (a.depth() - b.depth()) * PX_PER_M / (ca - cb);
    }

    /** 该横坐标处各界面按深度由浅到深的顺序（存的是界面下标）。 */
    public int[] orderAt(double x) {

        Integer[] idx = new Integer[interfaces.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }

        Arrays.sort(idx, Comparator.comparingDouble(i -> interfaceY(interfaces.get(i), x)));

        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /** 点 (x, y) 到第 index 条界面线的垂直距离（像素）。 */
    public double distanceToInterface(int index, double x, double y) {

        StratumInterface s = interfaces.get(index);
        double dx = x - axisX;
        double dy = y - zToY(s.depth());

        return Math.abs(dx * normalX(s.angle()) + dy * normalY(s.angle()));
    }

    /**
     * 点 (x, y) 附近的那条界面线（取最近的一条）。
     *
     * @param tolerance 容差（像素），超过就不算命中
     * @return 界面下标；没命中返回 -1
     */
    public int interfaceAt(double x, double y, double tolerance) {

        int hit = -1;
        double best = tolerance;

        for (int i = 0; i < interfaces.size(); i++) {
            double distance = distanceToInterface(i, x, y);
            if (distance < best) {
                best = distance;
                hit = i;
            }
        }
        return hit;
    }

    /** 点 (x, y) 落在第几层（0 表示最浅的一层）。 */
    public int layerAt(double x, double y) {

        int count = 0;
        for (StratumInterface s : interfaces) {
            if (interfaceY(s, x) < y) {
                count++;
            }
        }
        return count;
    }

    /** 点 (x, y) 所在层的下界界面夹角（度）；没有界面时返回 NaN。 */
    public double layerAngle(int layer, double x) {

        if (interfaces.isEmpty()) {
            return Double.NaN;
        }

        int[] order = orderAt(x);
        int idx = layer <= 0 ? order[0] : order[layer - 1];
        return interfaces.get(idx).angle();
    }

    /**
     * 图面需要竖直切分的 x：界面两两相交处 + 图面左右边缘。
     * 色块必须按这些切点分条绘制，否则相邻层的边界对不齐。
     */
    public double[] layerCuts() {

        List<Double> cuts = new ArrayList<>();
        cuts.add(figureLeft);

        for (int i = 0; i < interfaces.size(); i++) {
            for (int j = i + 1; j < interfaces.size(); j++) {

                double x = crossingX(interfaces.get(i), interfaces.get(j));

                // 只保留落在图面内、不在边缘上的
                if (x > figureLeft + 1e-6 && x < figureRight() - 1e-6) {
                    cuts.add(x);
                }
            }
        }

        cuts.add(figureRight());
        Collections.sort(cuts);

        // 合并靠得过近的切点，避免出现零宽竖条
        List<Double> merged = new ArrayList<>();
        for (double x : cuts) {
            if (merged.isEmpty() || x - merged.get(merged.size() - 1) > 1e-3) {
                merged.add(x);
            }
        }

        double[] out = new double[merged.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = merged.get(i);
        }
        return out;
    }

    /** 点是否落在图面内。 */
    public boolean insideFigure(double x, double y) {
        return x >= figureLeft && x <= figureRight()
                && y >= figureTop && y <= figureBottom();
    }

    /** 供子类/调用方使用的点工具：把多边形顶点转成 JavaFX 的坐标数组。 */
    public static double[] flatten(List<Point2D> points) {

        double[] coords = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            coords[2 * i] = points.get(i).getX();
            coords[2 * i + 1] = points.get(i).getY();
        }
        return coords;
    }
}
