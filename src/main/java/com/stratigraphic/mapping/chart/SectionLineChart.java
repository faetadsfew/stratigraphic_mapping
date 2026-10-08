package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.scene.shape.Line;

/**
 * 只画线条的地层界面图：图框 + 深度/水平距离刻度 + 井壁轮廓 + 各条界面直线。
 *
 * <p>不含地层色块和色标，用来单独看界面的几何关系。
 */
public final class SectionLineChart extends SectionChart {

    private static final double TITLE_H = 30;
    private static final double MARGIN_BOTTOM = 44;

    /** 井壁虚线的短划长度。 */
    private static final double DASH = 6;

    public SectionLineChart(StratumModel model) {

        super(model, TITLE_H, MARGIN_BOTTOM);

        drawAxes();
        drawFrame();
        drawWell();
        drawInterfaces();
        drawDepthLabels();
    }

    // ---------------- 井筒轮廓 ----------------

    private void drawWell() {

        double axisX = geo.axisX();
        double top = geo.figureTop();
        double bottom = geo.figureBottom();
        double r = geo.wellRadiusPx();

        // 井壁：左右两条虚线
        for (double x : new double[]{axisX - r, axisX + r}) {

            Line wall = new Line(x, top, x, bottom);
            wall.setStroke(SectionStyle.WELL_OUTLINE);
            wall.setStrokeWidth(1);
            wall.getStrokeDashArray().addAll(DASH, DASH);

            getChildren().add(wall);
        }

        // 井轴：更浅的一条点划线
        Line axis = new Line(axisX, top, axisX, bottom);
        axis.setStroke(SectionStyle.WELL_AXIS);
        axis.setStrokeWidth(0.8);
        axis.getStrokeDashArray().addAll(2.0, 4.0);

        getChildren().add(axis);
    }

    // ---------------- 界面直线 ----------------

    private void drawInterfaces() {

        for (StratumInterface s : model.interfaces()) {

            Line line = geo.createLine(geo.axisX(), geo.zToY(s.depth()), s.angle());

            line.setStroke(SectionStyle.INTERFACE_LINE);
            line.setStrokeWidth(1.4);

            getChildren().add(line);
        }
    }

    // ---------------- 井轴处的交点标注 ----------------

    private void drawDepthLabels() {

        double x = geo.axisX() + geo.wellRadiusPx() + 8;

        for (StratumInterface s : model.interfaces()) {

            String text = String.format("%.2f m / %.0f°", s.depth(), s.angle());

            getChildren().add(
                    label(text, x, geo.zToY(s.depth()) - 19, SectionStyle.chip(11)));
        }
    }
}
