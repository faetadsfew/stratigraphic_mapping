package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * 井壁展开线框图。
 *
 * <p>只用当前地层模型、井眼半径、统一倾角和两个展开点绘制，不读取模值/相位
 * 或其他井壁测量数据。横坐标是沿井壁展开的方位角，纵坐标是两点之间的深度；
 * 每条地层界面在展开图上显示为一条正弦状曲线。
 */
public final class WallLineUnfoldChart extends Pane {

    public record Sample(double depth, double angleDeg, double resistivity) { }

    private record ResistivityPatch(Shape shape, double resistivity) { }

    static final double MARGIN_LEFT = 78;
    static final double MARGIN_TOP = 42;
    static final double MARGIN_RIGHT = 42;
    static final double MARGIN_BOTTOM = 62;
    static final double PLOT_WIDTH = 600;
    static final double PLOT_HEIGHT = 700;

    private static final int TRACE_STEPS = 720;
    private static final Color TRACE_COLOR = Color.BLACK;
    private static final double FRACTURE_HIT_TOLERANCE = 10;
    private static final double INTERFACE_HIT_TOLERANCE = 8;
    private static final double SPHERE_HIT_TOLERANCE = 6;
    private static final double[] WRAP_SHIFTS = {-360, 0, 360};

    private final StratumModel model;
    private final ColorScale resistivityScale;
    private final double zTop;
    private final double zBottom;
    private final Canvas canvas = new Canvas(chartWidth(), chartHeight());
    private final List<ResistivityPatch> resistivityPatches = new ArrayList<>();
    private IntConsumer onFractureSelected = index -> {
    };
    private IntConsumer onInterfaceSelected = index -> {
    };
    private IntConsumer onLayerSelected = index -> {
    };
    private IntConsumer onSphereSelected = index -> {
    };
    private IntConsumer onWedgeSelected = index -> {
    };
    private IntConsumer onCubeSelected = index -> {
    };
    private IntConsumer onTextSelected = index -> {
    };

    public WallLineUnfoldChart(StratumModel model, double pointA, double pointB) {
        this(model, pointA, pointB, true);
    }

    WallLineUnfoldChart(StratumModel model, double pointA, double pointB, boolean showLegend) {

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
        addCubeAnomalies();
        addWedgeFractures();
        addSphereFractures();
        if (showLegend) {
            addFractureLegend();
        }
        setOnMouseClicked(this::handleClick);
    }

    /** 设置裂缝点击回调；参数是裂缝在模型裂缝列表中的序号。 */
    public void setOnFractureSelected(IntConsumer action) {
        this.onFractureSelected = action == null ? index -> {
        } : action;
    }

    /** 设置界面点击回调；参数是界面在模型界面列表中的序号。 */
    public void setOnInterfaceSelected(IntConsumer action) {
        this.onInterfaceSelected = action == null ? index -> {
        } : action;
    }

    /** 设置地层色块点击回调；参数是地层在模型中的层号。 */
    public void setOnLayerSelected(IntConsumer action) {
        this.onLayerSelected = action == null ? index -> {
        } : action;
    }

    /** 设置球体裂缝点击回调；参数是球体在模型球体列表中的序号。 */
    public void setOnSphereSelected(IntConsumer action) {
        this.onSphereSelected = action == null ? index -> {
        } : action;
    }

    /** 设置楔形体裂缝点击回调；参数是楔形体在模型列表中的序号。 */
    public void setOnWedgeSelected(IntConsumer action) {
        this.onWedgeSelected = action == null ? index -> {
        } : action;
    }

    /** 设置正方体异常体点击回调；参数是正方体在模型列表中的序号。 */
    public void setOnCubeSelected(IntConsumer action) {
        this.onCubeSelected = action == null ? index -> {
        } : action;
    }

    /** 设置曲面文字点击回调；参数是文字在模型文字列表中的序号。 */
    public void setOnTextSelected(IntConsumer action) {
        this.onTextSelected = action == null ? index -> {
        } : action;
    }

    private void draw() {

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, chartWidth(), chartHeight());
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, chartWidth(), chartHeight());

        drawLayerColors(gc);
        drawTraces(gc);
        drawFractures(gc);
        drawSurfaceTexts(gc);
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
            resistivityPatches.add(new ResistivityPatch(fracturePath(fracture), fracture.resistivity()));
        }

        gc.restore();
    }

    /** 在方位角—深度平面上绘制带电阻率底色的曲面文字。 */
    private void drawSurfaceTexts(GraphicsContext gc) {
        for (SurfaceText text : model.surfaceTexts()) {
            double centerX = angleToX(text.normalizedCenterAzimuthDeg());
            double centerY = zToY(text.centerDepth());
            double width = text.angularSpanDeg() / 360.0 * PLOT_WIDTH;
            double height = text.depthSpanM() / (zBottom - zTop) * PLOT_HEIGHT;
            if (width < 6 || height < 6) {
                continue;
            }
            double left = centerX - width / 2;
            double top = centerY - height / 2;
            Color color = resistivityScale.colorOf(text.resistivity());

            gc.save();
            gc.beginPath();
            gc.rect(MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT);
            gc.clip();

            String content = text.content();
            int count = content.length();
            double heightLimited = height * 0.14;
            double widthLimited = width * 0.24 / Math.max(1, count);
            double fontSize = Math.max(5, Math.min(36,
                    Math.min(heightLimited, widthLimited)));
            Font textFont = Font.font("Microsoft YaHei UI", FontWeight.BOLD, fontSize);
            gc.setFont(textFont);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);

            double usableWidth = Math.max(fontSize, width * 0.82);
            for (int i = 0; i < count; i++) {
                double t = (i + 0.5) / Math.max(1, count);
                double x = left + width * 0.09 + t * usableWidth;
                double arch = Math.sin(Math.PI * t) * Math.min(8, height * 0.14);
                double rotation = (t - 0.5) * Math.min(18, width / 16);
                gc.save();
                gc.translate(x, centerY - arch);
                gc.rotate(rotation);
                gc.setFill(color);
                gc.fillText(String.valueOf(content.charAt(i)), 0, 0);
                gc.restore();
                Text glyph = new Text(String.valueOf(content.charAt(i)));
                glyph.setFont(textFont);
                glyph.setTextOrigin(VPos.CENTER);
                glyph.setX(-glyph.getLayoutBounds().getWidth() / 2);
                glyph.getTransforms().addAll(new Translate(x, centerY - arch), new Rotate(rotation));
                Shape outline = Shape.union(glyph, glyph);
                outline.setStroke(null);
                resistivityPatches.add(new ResistivityPatch(outline, text.resistivity()));
            }
            gc.restore();
        }
    }

    private int textAt(double x, double y) {
        for (int i = 0; i < model.surfaceTexts().size(); i++) {
            SurfaceText text = model.surfaceTexts().get(i);
            double centerX = angleToX(text.normalizedCenterAzimuthDeg());
            double centerY = zToY(text.centerDepth());
            double width = text.angularSpanDeg() / 360.0 * PLOT_WIDTH;
            double height = text.depthSpanM() / (zBottom - zTop) * PLOT_HEIGHT;
            if (x >= centerX - width / 2 - 3 && x <= centerX + width / 2 + 3
                    && y >= centerY - height / 2 - 3 && y <= centerY + height / 2 + 3) {
                return i;
            }
        }
        return -1;
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

    private Path fracturePath(Fracture fracture) {
        Path path = new Path();
        path.setFill(null);
        path.setStroke(Color.BLACK);
        path.setStrokeWidth(4.6);
        if (Math.abs(FractureGeometry.normalZ(fracture)) < 1e-9) {
            double direction = Math.toDegrees(Math.atan2(
                    FractureGeometry.normalY(fracture), FractureGeometry.normalX(fracture)));
            for (double offset : new double[]{90, 270}) {
                double position = angleToX(normalizeAngle(direction + offset));
                path.getElements().addAll(new MoveTo(position, MARGIN_TOP),
                        new LineTo(position, MARGIN_TOP + PLOT_HEIGHT));
            }
        } else {
            for (int step = 0; step <= TRACE_STEPS; step++) {
                double angle = 360.0 * step / TRACE_STEPS;
                double position = angleToX(angle);
                double depth = zToY(FractureGeometry.traceDepth(fracture, model.wellRadius(), angle));
                path.getElements().add(step == 0 ? new MoveTo(position, depth) : new LineTo(position, depth));
            }
        }
        return path;
    }

    public Sample sampleAt(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < MARGIN_LEFT || x > MARGIN_LEFT + PLOT_WIDTH
                || y < MARGIN_TOP || y > MARGIN_TOP + PLOT_HEIGHT) {
            return null;
        }
        double angle = xToAngle(x);
        double depth = yToZ(y);
        for (int index = resistivityPatches.size() - 1; index >= 0; index--) {
            ResistivityPatch patch = resistivityPatches.get(index);
            if (patch.shape().contains(patch.shape().parentToLocal(x, y))) {
                return new Sample(depth, angle, patch.resistivity());
            }
        }
        return new Sample(depth, angle, model.resistivityOf(layerAt(angle, depth)));
    }

    /** 在井壁展开图上绘制楔形体填充三角形。 */
    private void addWedgeFractures() {

        if (model.wedgeFractures().isEmpty()) {
            return;
        }

        Group wedgeGroup = new Group();
        wedgeGroup.setClip(new Rectangle(
                MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT));

        for (com.stratigraphic.mapping.model.WedgeFracture wedge : model.wedgeFractures()) {
            List<WedgeTrace.Point> points = WedgeTrace.polygonPoints(
                    wedge.vertexAzimuthDeg(), wedge.vertexDepth(),
                    wedge.leftAzimuthDeg(), wedge.leftDepth(),
                    wedge.rightAzimuthDeg(), wedge.rightDepth());
            if (points.isEmpty()) {
                continue;
            }

            Color color = resistivityScale.colorOf(wedge.resistivity());
            for (double shift : WRAP_SHIFTS) {
                Polygon polygon = new Polygon();
                for (WedgeTrace.Point point : points) {
                    polygon.getPoints().addAll(
                            angleToX(point.angleDeg() + shift), zToY(point.depth()));
                }
                polygon.setFill(color);
                polygon.setStroke(color.deriveColor(0, 1, 0.70, 0.95));
                polygon.setStrokeWidth(1.4);
                polygon.setMouseTransparent(true);
                polygon.getStyleClass().add("wedge-fracture");
                wedgeGroup.getChildren().add(polygon);
                resistivityPatches.add(new ResistivityPatch(polygon, wedge.resistivity()));
            }
        }
        getChildren().add(wedgeGroup);
    }

    private int wedgeAt(double x, double y) {

        for (int i = 0; i < model.wedgeFractures().size(); i++) {
            com.stratigraphic.mapping.model.WedgeFracture wedge =
                    model.wedgeFractures().get(i);
            List<WedgeTrace.Point> points = WedgeTrace.polygonPoints(
                    wedge.vertexAzimuthDeg(), wedge.vertexDepth(),
                    wedge.leftAzimuthDeg(), wedge.leftDepth(),
                    wedge.rightAzimuthDeg(), wedge.rightDepth());
            if (points.isEmpty()) {
                continue;
            }

            for (double shift : WRAP_SHIFTS) {
                Polygon polygon = new Polygon();
                for (WedgeTrace.Point point : points) {
                    polygon.getPoints().addAll(
                            angleToX(point.angleDeg() + shift), zToY(point.depth()));
                }
                if (polygon.contains(x, y)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 球面与井壁圆柱面的交线。 */
    private void addSphereFractures() {

        if (model.sphereFractures().isEmpty()) {
            return;
        }

        Group sphereGroup = new Group();
        sphereGroup.setClip(new Rectangle(
                MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT));

        for (SphereFracture sphere : model.sphereFractures()) {
            SphereTrace.Trace trace = SphereTrace.of(
                    model.wellRadius(), sphere.centerDepth(), sphere.azimuthDeg(),
                    sphere.distanceFromAxis(), sphere.radius());
            if (trace.isEmpty()) {
                continue;
            }

            Color color = resistivityScale.colorOf(sphere.resistivity());
            for (double shift : WRAP_SHIFTS) {
                Path path = spherePath(trace, shift, color);
                sphereGroup.getChildren().add(path);
                resistivityPatches.add(new ResistivityPatch(path, sphere.resistivity()));
            }
        }
        getChildren().add(sphereGroup);
    }

    /** 正方体与井壁的交线区域：方位角—深度平面上的矩形色块。 */
    private void addCubeAnomalies() {

        if (model.cubeAnomalies().isEmpty()) {
            return;
        }

        Group cubeGroup = new Group();
        cubeGroup.setClip(new Rectangle(
                MARGIN_LEFT, MARGIN_TOP, PLOT_WIDTH, PLOT_HEIGHT));

        for (CubeAnomaly cube : model.cubeAnomalies()) {
            CubeTrace.Trace trace = CubeTrace.of(model.wellRadius(), cube);
            if (trace.isEmpty()) {
                continue;
            }

            Color color = resistivityScale.colorOf(cube.resistivity());
            for (CubeTrace.Span span : trace.spans()) {
                for (double shift : WRAP_SHIFTS) {
                    Polygon polygon = new Polygon();
                    polygon.getPoints().addAll(
                            angleToX(span.startDeg() + shift), zToY(trace.depthMin()),
                            angleToX(span.endDeg() + shift), zToY(trace.depthMin()),
                            angleToX(span.endDeg() + shift), zToY(trace.depthMax()),
                            angleToX(span.startDeg() + shift), zToY(trace.depthMax()));
                    polygon.setFill(color.deriveColor(0, 1, 0.95, 0.76));
                    polygon.setStroke(color.deriveColor(0, 1, 0.70, 0.95));
                    polygon.setStrokeWidth(1.5);
                    polygon.setMouseTransparent(true);
                    polygon.getStyleClass().add("cube-anomaly");
                    cubeGroup.getChildren().add(polygon);
                    resistivityPatches.add(new ResistivityPatch(polygon, cube.resistivity()));
                }
            }
        }
        getChildren().add(cubeGroup);
    }

    /** 返回点击位置命中的正方体序号；不在交线区域内时返回 -1。 */
    private int cubeAt(double x, double y) {
        double angle = xToAngle(x);
        double depth = yToZ(y);
        for (int i = 0; i < model.cubeAnomalies().size(); i++) {
            CubeTrace.Trace trace = CubeTrace.of(model.wellRadius(),
                    model.cubeAnomalies().get(i));
            if (!trace.isEmpty() && trace.contains(angle, depth)) {
                return i;
            }
        }
        return -1;
    }

    private Path spherePath(SphereTrace.Trace trace, double angleShift, Color color) {

        Path path = new Path();
        path.setFill(trace.kind() == SphereTrace.Kind.RING
                ? color : Color.TRANSPARENT);
        path.setStroke(color.deriveColor(0, 1, 0.70, 0.9));
        path.setStrokeWidth(1.3);
        path.setMouseTransparent(true);
        path.getStyleClass().add("sphere-fracture");

        appendSubpath(path, trace.upper(), angleShift, true);
        if (trace.kind() == SphereTrace.Kind.RING) {
            List<SphereTrace.Point> lower = new ArrayList<>(trace.lower());
            Collections.reverse(lower);
            appendSubpath(path, lower, angleShift, false);
            path.getElements().add(new ClosePath());
        } else {
            appendSubpath(path, trace.lower(), angleShift, true);
        }
        return path;
    }

    private void appendSubpath(Path path,
                               List<SphereTrace.Point> points,
                               double angleShift,
                               boolean moveFirst) {
        for (int i = 0; i < points.size(); i++) {
            SphereTrace.Point point = points.get(i);
            double x = angleToX(point.angleDeg() + angleShift);
            double y = zToY(point.depth());
            if (i == 0 && moveFirst) {
                path.getElements().add(new MoveTo(x, y));
            } else {
                path.getElements().add(new LineTo(x, y));
            }
        }
    }

    private int sphereAt(double x, double y) {

        for (int i = 0; i < model.sphereFractures().size(); i++) {
            SphereFracture sphere = model.sphereFractures().get(i);
            SphereTrace.Trace trace = SphereTrace.of(
                    model.wellRadius(), sphere.centerDepth(), sphere.azimuthDeg(),
                    sphere.distanceFromAxis(), sphere.radius());
            if (trace.isEmpty()) {
                continue;
            }

            for (double shift : WRAP_SHIFTS) {
                if (distanceToPolyline(x, y, trace.upper(), shift) <= SPHERE_HIT_TOLERANCE
                        || distanceToPolyline(x, y, trace.lower(), shift)
                        <= SPHERE_HIT_TOLERANCE) {
                    return i;
                }
            }
        }
        return -1;
    }

    private double distanceToPolyline(double x,
                                      double y,
                                      List<SphereTrace.Point> points,
                                      double angleShift) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 1; i < points.size(); i++) {
            SphereTrace.Point a = points.get(i - 1);
            SphereTrace.Point b = points.get(i);
            nearest = Math.min(nearest, distanceToSegment(
                    x, y,
                    angleToX(a.angleDeg() + angleShift), zToY(a.depth()),
                    angleToX(b.angleDeg() + angleShift), zToY(b.depth())));
        }
        return nearest;
    }

    private static double distanceToSegment(double px, double py,
                                            double x1, double y1,
                                            double x2, double y2) {
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
            return;
        }

        double angle = xToAngle(x);
        double depth = yToZ(y);

        int sphereIndex = sphereAt(x, y);
        if (sphereIndex >= 0) {
            onSphereSelected.accept(sphereIndex);
            return;
        }

        int wedgeIndex = wedgeAt(x, y);
        if (wedgeIndex >= 0) {
            onWedgeSelected.accept(wedgeIndex);
            return;
        }

        int textIndex = textAt(x, y);
        if (textIndex >= 0) {
            onTextSelected.accept(textIndex);
            return;
        }

        int cubeIndex = cubeAt(x, y);
        if (cubeIndex >= 0) {
            onCubeSelected.accept(cubeIndex);
            return;
        }

        int fractureIndex = fractureAt(angle, x, y);
        if (fractureIndex >= 0) {
            onFractureSelected.accept(fractureIndex);
            return;
        }

        int interfaceIndex = -1;
        double nearestDistance = INTERFACE_HIT_TOLERANCE;
        for (int i = 0; i < model.interfaces().size(); i++) {
            double traceY = zToY(traceDepthAt(model.interfaces().get(i), angle));
            double distance = Math.abs(y - traceY);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                interfaceIndex = i;
            }
        }

        if (interfaceIndex >= 0) {
            onInterfaceSelected.accept(interfaceIndex);
        } else {
            int layer = layerAt(angle, depth);
            onLayerSelected.accept(layer);
        }
    }

    /** 点击深度落在第几层；界面按当前方位角展开后的深度参与比较。 */
    private int layerAt(double angle, double depth) {

        int layer = 0;
        for (StratumInterface stratum : model.interfaces()) {
            if (traceDepthAt(stratum, angle) <= depth) {
                layer++;
            }
        }
        return Math.max(0, Math.min(model.layerCount() - 1, layer));
    }

    /** 判断点击位置是否落在某条裂缝轨迹附近，返回最近的裂缝序号。 */
    private int fractureAt(double angle, double x, double y) {

        int hit = -1;
        double nearest = FRACTURE_HIT_TOLERANCE;

        for (int i = 0; i < model.fractures().size(); i++) {
            Fracture fracture = model.fractures().get(i);
            double distance;

            if (Math.abs(FractureGeometry.normalZ(fracture)) < 1e-9) {
                double direction = Math.toDegrees(Math.atan2(
                        FractureGeometry.normalY(fracture),
                        FractureGeometry.normalX(fracture)));
                double first = angleToX(normalizeAngle(direction + 90));
                double second = angleToX(normalizeAngle(direction + 270));
                distance = Math.min(Math.abs(x - first), Math.abs(x - second));
            } else {
                double traceDepth = FractureGeometry.traceDepth(
                        fracture, model.wellRadius(), angle);
                distance = Math.abs(y - zToY(traceDepth));
            }

            if (distance <= nearest) {
                nearest = distance;
                hit = i;
            }
        }
        return hit;
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
