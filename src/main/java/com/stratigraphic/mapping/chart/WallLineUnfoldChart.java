package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * 井壁展开线框图。
 *
 * <p>只用当前地层模型、井眼半径、统一倾角和两个展开点绘制，不读取模值/相位
 * 或其他井壁测量数据。横坐标是沿井壁展开的方位角，纵坐标是两点之间的深度；
 * 每条地层界面在展开图上显示为一条正弦状曲线。
 */
public final class WallLineUnfoldChart extends Pane {

    static final double MARGIN_LEFT = 78;
    static final double MARGIN_TOP = 42;
    static final double MARGIN_RIGHT = 42;
    static final double MARGIN_BOTTOM = 62;
    static final double PLOT_WIDTH = 600;
    static final double PLOT_HEIGHT = 700;

    private static final int TRACE_STEPS = 720;
    private static final Color TRACE_COLOR = Color.BLACK;

    private final StratumModel model;
    private final ColorScale resistivityScale;
    private final double zTop;
    private final double zBottom;
    private final Canvas canvas = new Canvas(chartWidth(), chartHeight());
    private final ChartPopup popup = new ChartPopup();

    public WallLineUnfoldChart(StratumModel model, double pointA, double pointB) {

        if (!Double.isFinite(pointA) || !Double.isFinite(pointB)) {
            throw new IllegalArgumentException("展开点 A、B 必须是有效数字");
        }
        if (Math.abs(pointB - pointA) < 1e-9) {
            throw new IllegalArgumentException("展开点 A、B 不能相同");
        }

        this.model = model;
        this.resistivityScale = new ColorScale(
                model.minResistivity(), model.maxResistivity());
        this.zTop = Math.min(pointA, pointB);
        this.zBottom = Math.max(pointA, pointB);

        setPrefSize(chartWidth(), chartHeight());
        setMinSize(chartWidth(), chartHeight());
        setMaxSize(chartWidth(), chartHeight());
        getStyleClass().add("chart-canvas");
        getChildren().add(canvas);

        draw();
        addFractureLegend();
        setOnMouseClicked(this::handleClick);
    }

    private void draw() {

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, chartWidth(), chartHeight());
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, chartWidth(), chartHeight());

        drawLayerColors(gc);
        drawTraces(gc);
        drawFractures(gc);
        drawFrameAndTicks(gc);
    }

    /** 按统一电阻率色带填充两相邻界面之间的地层。 */
    private void drawLayerColors(GraphicsContext gc) {

        gc.save();
        gc.beginPath();
        gc.rect(MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT);
        gc.clip();

        int columns = (int) Math.ceil(PLOT_WIDTH);
        double plotBottom = MARGIN_TOP + PLOT_HEIGHT;

        for (int column = 0; column < columns; column++) {
            double x = MARGIN_LEFT + column;
            double angle = (column + 0.5) / PLOT_WIDTH * 360.0;

            for (int layer = 0; layer < model.layerCount(); layer++) {
                double topY = layer == 0
                        ? MARGIN_TOP
                        : zToY(traceDepthAt(model.interfaces().get(layer - 1), angle));
                double bottomY = layer == model.layerCount() - 1
                        ? plotBottom
                        : zToY(traceDepthAt(model.interfaces().get(layer), angle));

                double clippedTop = Math.max(MARGIN_TOP, Math.min(plotBottom, topY));
                double clippedBottom = Math.max(MARGIN_TOP, Math.min(plotBottom, bottomY));
                if (clippedBottom <= clippedTop) {
                    continue;
                }

                gc.setFill(resistivityScale.colorOf(model.resistivityOf(layer)));
                gc.fillRect(x, clippedTop, 1.5, clippedBottom - clippedTop);
            }
        }

        gc.restore();
    }

    private void drawTraces(GraphicsContext gc) {

        gc.save();
        gc.beginPath();
        gc.rect(MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT);
        gc.clip();

        for (int i = 0; i < model.interfaces().size(); i++) {
            StratumInterface stratum = model.interfaces().get(i);

            gc.setStroke(Color.web("#FFFFFF", 0.9));
            gc.setLineWidth(4.2);
            strokeTrace(gc, stratum);

            gc.setStroke(TRACE_COLOR);
            gc.setLineWidth(2.0);
            strokeTrace(gc, stratum);
        }

        gc.restore();

        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.NORMAL, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < model.interfaces().size(); i++) {
            StratumInterface stratum = model.interfaces().get(i);
            double y = zToY(stratum.depth());
            if (y < MARGIN_TOP - 4 || y > MARGIN_TOP + PLOT_HEIGHT + 4) {
                continue;
            }
            String text = String.format(Locale.ROOT, "z %.2f m", stratum.depth());
            gc.setFill(Color.web("#FFFFFF", 0.88));
            gc.fillRoundRect(MARGIN_LEFT + 6, y - 15, 62, 18, 4, 4);
            gc.setFill(TRACE_COLOR);
            gc.fillText(text, MARGIN_LEFT + 10, y - 2);
        }
    }

    private void strokeTrace(GraphicsContext gc, StratumInterface stratum) {

        gc.beginPath();
        for (int step = 0; step <= TRACE_STEPS; step++) {
            double angle = 360.0 * step / TRACE_STEPS;
            double traceDepth = traceDepthAt(stratum, angle);
            double x = angleToX(angle);
            double y = zToY(traceDepth);
            if (step == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void drawFractures(GraphicsContext gc) {

        if (model.fractures().isEmpty()) {
            return;
        }

        gc.save();
        gc.beginPath();
        gc.rect(MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT);
        gc.clip();

        for (Fracture fracture : model.fractures()) {
            gc.setStroke(Color.web("#FFFFFF", 0.8));
            gc.setLineWidth(7.5);
            strokeFracture(gc, fracture);

            gc.setStroke(resistivityScale.colorOf(fracture.resistivity()));
            gc.setLineWidth(4.6);
            strokeFracture(gc, fracture);
        }

        gc.restore();
    }

    private void strokeFracture(GraphicsContext gc, Fracture fracture) {

        double nx = FractureGeometry.normalX(fracture);
        double ny = FractureGeometry.normalY(fracture);
        double nz = FractureGeometry.normalZ(fracture);

        if (Math.abs(nz) < 1e-9) {
            double direction = Math.toDegrees(Math.atan2(ny, nx));
            drawVerticalFractureLine(gc, fracture, normalizeAngle(direction + 90));
            drawVerticalFractureLine(gc, fracture, normalizeAngle(direction + 270));
            return;
        }

        gc.beginPath();
        for (int step = 0; step <= TRACE_STEPS; step++) {
            double angle = 360.0 * step / TRACE_STEPS;
            double depth = FractureGeometry.traceDepth(
                    fracture, model.wellRadius(), angle);
            double x = angleToX(angle);
            double y = zToY(depth);
            if (step == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void drawVerticalFractureLine(
            GraphicsContext gc, Fracture fracture, double angle) {
        double x = angleToX(angle);
        gc.strokeLine(x, MARGIN_TOP, x, MARGIN_TOP + PLOT_HEIGHT);
    }

    private static double normalizeAngle(double angle) {
        double result = angle % 360;
        return result < 0 ? result + 360 : result;
    }

    private void addFractureLegend() {

        if (model.fractures().isEmpty()) {
            return;
        }

        FractureLegendPane legend = new FractureLegendPane(
                resistivityScale, model.fractures().size());
        legend.relocate(
                MARGIN_LEFT + PLOT_WIDTH - FractureLegendPane.WIDTH - 8,
                MARGIN_TOP + 8);
        getChildren().add(legend);
    }

    private void drawFrameAndTicks(GraphicsContext gc) {

        gc.setStroke(SectionStyle.FRAME);
        gc.setLineWidth(1.3);
        gc.strokeRect(MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT);

        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.NORMAL, 11));
        gc.setFill(SectionStyle.TICK);
        gc.setTextAlign(TextAlignment.CENTER);

        for (int angle = 0; angle <= 360; angle += 90) {
            double x = angleToX(angle);
            gc.strokeLine(x, MARGIN_TOP + PLOT_HEIGHT,
                    x, MARGIN_TOP + PLOT_HEIGHT + 5);
            gc.fillText(angle + "°", x, MARGIN_TOP + PLOT_HEIGHT + 19);
        }

        gc.setTextAlign(TextAlignment.RIGHT);
        double span = zBottom - zTop;
        double step = SectionStyle.niceStep(span / 6);
        int decimals = SectionStyle.decimalsFor(step);
        double first = Math.ceil(zTop / step) * step;
        for (double depth = first; depth <= zBottom + 1e-9; depth += step) {
            double y = zToY(depth);
            gc.strokeLine(MARGIN_LEFT - 5, y, MARGIN_LEFT, y);
            gc.fillText(SectionStyle.formatTick(depth, decimals),
                    MARGIN_LEFT - 9, y + 4);
        }

        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("井壁展开方位角 / °",
                MARGIN_LEFT + PLOT_WIDTH / 2,
                MARGIN_TOP + PLOT_HEIGHT + 46);

        gc.save();
        gc.translate(21, MARGIN_TOP + PLOT_HEIGHT / 2);
        gc.rotate(-90);
        gc.fillText("深度 z / m", 0, 0);
        gc.restore();
    }

    private void handleClick(MouseEvent event) {

        double x = event.getX();
        double y = event.getY();
        if (x < MARGIN_LEFT || x > MARGIN_LEFT + PLOT_WIDTH
                || y < MARGIN_TOP || y > MARGIN_TOP + PLOT_HEIGHT) {
            popup.hide();
            return;
        }

        double angle = xToAngle(x);
        double depth = yToZ(y);

        StratumInterface nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (StratumInterface stratum : model.interfaces()) {
            double distance = Math.abs(traceDepthAt(stratum, angle) - depth);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = stratum;
            }
        }

        String content = String.format(Locale.ROOT,
                "展开方位角：%.1f°%n深度 z：%.3f m%n统一倾角：%.1f°",
                angle, depth, model.dipAngle());
        if (nearest != null) {
            content += String.format(Locale.ROOT,
                    "%n最近界面深度：%.3f m", nearest.depth());
        }

        if (popup.getParent() == null) {
            getChildren().add(popup);
        }
        popup.show(content, x, y, chartWidth(), chartHeight());
    }

    private double traceDepthAt(StratumInterface stratum, double angleDegrees) {

        double radialOffset = model.wellRadius()
                * Math.cos(Math.toRadians(angleDegrees))
                * SectionGeometry.cot(model.dipAngle());
        return stratum.depth() + radialOffset;
    }

    private double angleToX(double angleDegrees) {
        return MARGIN_LEFT + angleDegrees / 360.0 * PLOT_WIDTH;
    }

    private double xToAngle(double x) {
        return (x - MARGIN_LEFT) / PLOT_WIDTH * 360.0;
    }

    private double zToY(double depth) {
        return MARGIN_TOP + (depth - zTop) / (zBottom - zTop) * PLOT_HEIGHT;
    }

    private double yToZ(double y) {
        return zTop + (y - MARGIN_TOP) / PLOT_HEIGHT * (zBottom - zTop);
    }

    public double pointA() {
        return zTop;
    }

    public double pointB() {
        return zBottom;
    }

    private static double chartWidth() {
        return MARGIN_LEFT + PLOT_WIDTH + MARGIN_RIGHT;
    }

    private static double chartHeight() {
        return MARGIN_TOP + PLOT_HEIGHT + MARGIN_BOTTOM;
    }
}
