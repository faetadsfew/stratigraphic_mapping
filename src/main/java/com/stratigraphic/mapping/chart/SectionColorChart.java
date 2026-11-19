package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整的地层剖面图：地层色块 + 界面线 + 井筒 + 边框刻度。
 *
 * <p>颜色表示电阻率：浅（低阻）→ 深（高阻）；对应的色标是 {@code ColorBarPane}，
 * 挂在图面右侧的固定列里，不参与这里的缩放。
 * 点击裂缝、地层界面或地层色块会联动左侧参数面板定位并高亮。
 */
public final class SectionColorChart extends SectionChart {

    /** 上留白 / 下留白和线框图取一样的值，两张图的图面才是同一个尺寸。 */
    private static final double TITLE_H = 30;
    private static final double MARGIN_BOTTOM = 44;

    /** 点击裂缝的容差（图面坐标像素），比细线本身宽一些，方便选中。 */
    private static final double FRACTURE_HIT_TOLERANCE = 10;

    private final ColorScale scale;

    public SectionColorChart(StratumModel model) {

        super(model, TITLE_H, MARGIN_BOTTOM);

        this.scale = new ColorScale(
                model.minSectionResistivity(), model.maxSectionResistivity());

        // 按工程图顺序绘制，后画的盖住先画的
        drawLayers();
        drawInterfaces();
        drawFractures();
        // 井筒最后绘制，保证井内电阻率色带不会被裂缝线切开
        drawWell();
        drawDepthLabels();
        drawFrame();
        drawAxes();

    }

    // ---------------- 地层色块 ----------------

    private void drawLayers() {

        double[] cuts = geo.layerCuts();

        Shape[] shapes = new Shape[model.layerCount()];

        for (int i = 0; i < cuts.length - 1; i++) {

            double xl = cuts[i];
            double xr = cuts[i + 1];
            double xm = (xl + xr) / 2;          // 层序在这一竖条的中点取样

            int[] order = geo.orderAt(xm);

            for (int layer = 0; layer < model.layerCount(); layer++) {

                Polygon piece = slabPolygon(xl, xr, order, layer);

                if (piece == null) {
                    continue;                    // 这一竖条内该层尖灭了
                }

                shapes[layer] = shapes[layer] == null
                        ? piece
                        : Shape.union(shapes[layer], piece);
            }
        }

        for (int layer = 0; layer < model.layerCount(); layer++) {

            if (shapes[layer] == null) {
                continue;
            }

            shapes[layer].setFill(scale.colorOf(model.resistivityOf(layer)));
            getChildren().add(shapes[layer]);
        }
    }

    /** 竖条 [xl, xr] 内第 layer 层的多边形；尖灭时返回 null。 */
    private Polygon slabPolygon(double xl, double xr, int[] order, int layer) {

        List<Point2D> poly = new ArrayList<>();
        poly.add(new Point2D(xl, geo.figureTop()));
        poly.add(new Point2D(xr, geo.figureTop()));
        poly.add(new Point2D(xr, geo.figureBottom()));
        poly.add(new Point2D(xl, geo.figureBottom()));

        List<StratumInterface> interfaces = model.interfaces();

        // 层的上界：上面那条界面，保留更深的一侧
        if (layer > 0) {
            poly = clipHalfPlane(poly, interfaces.get(order[layer - 1]), true);
        }

        // 层的下界：下面那条界面，保留更浅的一侧
        if (layer < interfaces.size()) {
            poly = clipHalfPlane(poly, interfaces.get(order[layer]), false);
        }

        return poly.size() < 3 ? null : new Polygon(SectionGeometry.flatten(poly));
    }

    /** 用一条界面把多边形裁成两半，保留 keepDeeper 指定的一侧。 */
    private List<Point2D> clipHalfPlane(List<Point2D> poly, StratumInterface s, boolean keepDeeper) {

        double px = geo.axisX();
        double py = geo.zToY(s.depth());
        double nx = SectionGeometry.normalX(geo.dipAngle());
        double ny = SectionGeometry.normalY(geo.dipAngle());

        List<Point2D> out = new ArrayList<>();
        int n = poly.size();

        for (int i = 0; i < n; i++) {

            Point2D cur = poly.get(i);
            Point2D nxt = poly.get((i + 1) % n);

            double dc = (cur.getX() - px) * nx + (cur.getY() - py) * ny;
            double dn = (nxt.getX() - px) * nx + (nxt.getY() - py) * ny;

            boolean curIn = keepDeeper ? dc >= 0 : dc <= 0;
            boolean nxtIn = keepDeeper ? dn >= 0 : dn <= 0;

            if (curIn) {
                out.add(cur);
            }
            if (curIn != nxtIn) {
                double t = dc / (dc - dn);
                out.add(new Point2D(
                        cur.getX() + t * (nxt.getX() - cur.getX()),
                        cur.getY() + t * (nxt.getY() - cur.getY())));
            }
        }
        return out;
    }

    // ---------------- 界面线 ----------------

    private void drawInterfaces() {

        List<StratumInterface> interfaces = model.interfaces();

        for (int i = 0; i < interfaces.size(); i++) {

            StratumInterface s = interfaces.get(i);

            // 先铺一条半透明白晕，界面线压在深色地层上也看得清
            Line glow = geo.createLine(geo.axisX(), geo.zToY(s.depth()), geo.dipAngle());
            glow.setStroke(Color.web("#FFFFFF", 0.5));
            glow.setStrokeWidth(2.4);
            getChildren().add(glow);

            Line line = geo.createLine(geo.axisX(), geo.zToY(s.depth()), geo.dipAngle());
            line.setStroke(SectionStyle.INTERFACE_LINE);
            line.setStrokeWidth(1.2);

            getChildren().add(line);
        }
    }

    // ---------------- 井筒 ----------------

    private void drawWell() {

        Rectangle well = new Rectangle(
                geo.axisX() - geo.wellRadiusPx(),
                geo.figureTop(),
                2 * geo.wellRadiusPx(),
                geo.figureHeight());

        well.setFill(scale.colorOf(model.wellResistivity()));
        well.setStroke(SectionStyle.WELL_OUTLINE);
        well.setStrokeWidth(0.8);

        getChildren().add(well);
    }

    // ---------------- 裂缝 ----------------

    private void drawFractures() {

        if (model.fractures().isEmpty()) {
            return;
        }

        Group fractureGroup = new Group();
        fractureGroup.setClip(new Rectangle(
                geo.figureLeft(), geo.figureTop(),
                geo.figureWidth(), geo.figureHeight()));

        for (Fracture fracture : model.fractures()) {
            Line glow = fractureLine(fracture);
            glow.setStroke(Color.web("#FFFFFF", 0.75));
            glow.setStrokeWidth(7.5);
            fractureGroup.getChildren().add(glow);

            Line line = fractureLine(fracture);
            line.setStroke(scale.colorOf(fracture.resistivity()));
            line.setStrokeWidth(4.6);
            fractureGroup.getChildren().add(line);
        }

        getChildren().add(fractureGroup);
    }

    @Override
    protected int fractureAt(double x, double y) {

        int hit = -1;
        double nearest = FRACTURE_HIT_TOLERANCE;

        for (int i = 0; i < model.fractures().size(); i++) {
            double distance = distanceToSegment(x, y, fractureLine(model.fractures().get(i)));
            if (distance <= nearest) {
                nearest = distance;
                hit = i;
            }
        }
        return hit;
    }

    /** 点到裂缝线段的最短距离，用于点击命中判断。 */
    private static double distanceToSegment(double px, double py, Line line) {

        double x1 = line.getStartX();
        double y1 = line.getStartY();
        double x2 = line.getEndX();
        double y2 = line.getEndY();
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;

        if (lengthSquared < 1e-12) {
            return Math.hypot(px - x1, py - y1);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    /** 裂缝平面与剖面 y=0 的交线。 */
    private Line fractureLine(Fracture fracture) {

        if (Math.abs(FractureGeometry.normalZ(fracture)) < 1e-9) {
            double x = geo.axisX();
            return new Line(x, geo.figureTop(), x, geo.figureBottom());
        }

        double zLeft = FractureGeometry.sectionDepth(fracture, geo.leftOffsetM());
        double zRight = FractureGeometry.sectionDepth(fracture, geo.rightOffsetM());
        return new Line(
                geo.figureLeft(), geo.zToY(zLeft),
                geo.figureRight(), geo.zToY(zRight));
    }

    // ---------------- 井轴处的深度标注 ----------------

    private void drawDepthLabels() {

        double x = geo.axisX() + geo.wellRadiusPx() + 8;

        for (StratumInterface s : model.interfaces()) {
            getChildren().add(
                    label(String.format("%.2f m", s.depth()),
                            x, geo.zToY(s.depth()) - 19, SectionStyle.chip(11)));
        }
    }

}
