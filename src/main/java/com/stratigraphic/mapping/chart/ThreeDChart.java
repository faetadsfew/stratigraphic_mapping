package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.WedgeFracture;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Cursor;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.Line;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** 与二维剖面一致的剖切式长方体地层块 + 中心井眼三维视图。 */
public final class ThreeDChart extends StackPane {

    private static final double DEFAULT_YAW = -22;
    private static final double DEFAULT_PITCH = 4;
    private static final double MIN_PITCH = -18;
    private static final double MAX_PITCH = 18;
    private static final double MIN_ZOOM = 0.55;
    private static final double MAX_ZOOM = 2.5;
    /** 地层块半宽与实体后半深度（显示单位，1 = 井半径）。 */
    private static final double BLOCK_HALF_WIDTH = 4.2;
    private static final double BLOCK_DEPTH = 2.4;
    private static final double BLOCK_EDGE_WIDTH = 0.022;
    private static final double WELL_Z = 0;

    private final StratumModel model;
    private final double zTop;
    private final double zBottom;
    private final double zMid;
    private final double scale;
    private final ColorScale colorScale;

    private final Group world = new Group();
    private final Group modelGroup = new Group();
    private final Rotate yawRotate = new Rotate(DEFAULT_YAW, Rotate.Y_AXIS);
    private final Rotate pitchRotate = new Rotate(DEFAULT_PITCH, Rotate.X_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene subScene;
    private final BorderPane hud = new BorderPane();
    private final Label hintLabel = new Label("拖动旋转 · 滚轮缩放 · 双击复位");
    private final FadeTransition hintFade;
    private Timeline viewResetTimeline;

    private IntConsumer onFractureSelected = index -> {
    };
    private IntConsumer onSphereSelected = index -> {
    };
    private IntConsumer onWedgeSelected = index -> {
    };
    private IntConsumer onCubeSelected = index -> {
    };
    private IntConsumer onLayerSelected = index -> {
    };
    private double zoomFactor = 1;
    private double baseDistance = 25;
    private double dragStartX;
    private double dragStartY;
    private double lastX;
    private double lastY;
    private boolean dragged;

    private record PickTarget(Kind kind, int index) {
    }

    private enum Kind {
        FRACTURE, SPHERE, WEDGE, CUBE, LAYER
    }

    public ThreeDChart(StratumModel model, double zTop, double zBottom) {
        this.model = model;
        this.zTop = Math.min(zTop, zBottom);
        this.zBottom = Math.max(zTop, zBottom);
        this.zMid = (this.zTop + this.zBottom) / 2;
        this.scale = 1.0 / model.wellRadius();
        this.colorScale = new ColorScale(model.minResistivity(), model.maxResistivity());

        getStyleClass().add("three-d-chart");
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        world.getTransforms().addAll(yawRotate, pitchRotate);
        world.getChildren().add(modelGroup);
        buildModel();
        buildLights();
        buildCamera();
        Group sceneRoot = new Group(camera, world);
        sceneRoot.getChildren().addAll(sceneLights);
        subScene = new SubScene(sceneRoot, 1, 1, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);
        subScene.setCamera(camera);
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());

        Region viewport = new Region();
        viewport.getStyleClass().add("three-d-viewport");
        viewport.setMouseTransparent(true);

        Region vignette = new Region();
        vignette.getStyleClass().add("three-d-vignette");
        vignette.setMouseTransparent(true);

        buildHud();
        hud.setPickOnBounds(false);

        getChildren().addAll(viewport, subScene, vignette, hud);
        setupInteraction();

        widthProperty().addListener((o, oldValue, newValue) -> fitCamera());
        heightProperty().addListener((o, oldValue, newValue) -> fitCamera());

hintFade = new FadeTransition(Duration.seconds(1), hintLabel);
        hintFade.setDelay(Duration.seconds(5));
        hintFade.setToValue(0.35);
        hintFade.play();
    }

    public void setOnFractureSelected(IntConsumer action) {
        onFractureSelected = action == null ? index -> {
        } : action;
    }

    public void setOnSphereSelected(IntConsumer action) {
        onSphereSelected = action == null ? index -> {
        } : action;
    }

    public void setOnWedgeSelected(IntConsumer action) {
        onWedgeSelected = action == null ? index -> {
        } : action;
    }

    public void setOnCubeSelected(IntConsumer action) {
        onCubeSelected = action == null ? index -> {
        } : action;
    }

    public void setOnLayerSelected(IntConsumer action) {
        onLayerSelected = action == null ? index -> {
        } : action;
    }

    private void buildModel() {
        buildBlock();
        buildFractures();
        buildWellBore();
        buildSpheres();
        buildWedges();
        buildCubes();
        buildDepthAxis();
    }

    /** 按剖面图的倾角裁剪每一层，再沿深度方向拉伸成长方体地层块。 */
    private void buildBlock() {
        List<StratumInterface> interfaces = model.interfaces();
        double yTop = (zTop - zMid) * scale;
        double yBottom = (zBottom - zMid) * scale;
        double slope = -ThreeDGeometry.cot(model.dipAngle());

        for (int layer = 0; layer < model.layerCount(); layer++) {
            double topDepth = clampDepth(layer == 0 ? zTop : interfaces.get(layer - 1).depth());
            double bottomDepth = clampDepth(layer == model.layerCount() - 1
                    ? zBottom : interfaces.get(layer).depth());
            if (bottomDepth - topDepth < 1e-6) {
                continue;
            }

            double topY0 = (topDepth - zMid) * scale;
            double bottomY0 = (bottomDepth - zMid) * scale;
            List<Point2D> poly = List.of(
                    new Point2D(-BLOCK_HALF_WIDTH, yTop),
                    new Point2D(BLOCK_HALF_WIDTH, yTop),
                    new Point2D(BLOCK_HALF_WIDTH, yBottom),
                    new Point2D(-BLOCK_HALF_WIDTH, yBottom));

            if (layer > 0) {
                // y >= topY0 + cot*x，保留界面更深的一侧
                poly = ThreeDGeometry.clipHalfPlane(poly, -slope, 1, -topY0);
            }
            if (layer < model.layerCount() - 1) {
                // y <= bottomY0 + cot*x，保留界面更浅的一侧
                poly = ThreeDGeometry.clipHalfPlane(poly, slope, -1, bottomY0);
            }
            if (poly.size() < 3) {
                continue;
            }

            Color base = colorScale.colorOf(model.resistivityOf(layer));
            Color face = base.deriveColor(0, 1.16, 0.94, 1);
            MeshView prism = ThreeDGeometry.prism(
                    poly, 0, BLOCK_DEPTH, BLOCK_HALF_WIDTH, yTop, yBottom, face, 0.96);
            prism.setMaterial(blockMaterial(face));
            prism.setCullFace(CullFace.NONE);
            prism.setDepthTest(DepthTest.ENABLE);
            prism.setMouseTransparent(false);
            prism.setUserData(new PickTarget(Kind.LAYER, layer));
            modelGroup.getChildren().add(prism);
        }

        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 0, 1}) {
                double z = sz * BLOCK_DEPTH;
                Group edge = ThreeDGeometry.lineStrip(List.of(
                                new Point3D(sx * BLOCK_HALF_WIDTH, yTop, z),
                                new Point3D(sx * BLOCK_HALF_WIDTH, yBottom, z)),
                        Color.web("#BAE6FD", 0.58), 0.018);
                edge.setDepthTest(DepthTest.ENABLE);
                edge.setMouseTransparent(true);
                modelGroup.getChildren().add(edge);
            }
        }
        modelGroup.getChildren().add(blockLoop(yTop, Color.web("#E0F2FE", 0.62), 0.018));
        modelGroup.getChildren().add(blockLoop(yBottom, Color.web("#7DD3FC", 0.34), 0.014));

        modelGroup.getChildren().add(frontWireframe(yTop, yBottom));

        for (StratumInterface stratum : interfaces) {
            if (!inInterval(stratum.depth())) {
                continue;
            }
            double y0 = (stratum.depth() - zMid) * scale;
            List<Point3D> segment = interfaceSegment(y0, slope,
                    0.006, yTop, yBottom);
            if (segment.size() < 2) {
                continue;
            }
            Group glow = ThreeDGeometry.lineStrip(segment,
                    Color.web("#FFFFFF", 0.52), 0.032);
            glow.setDepthTest(DepthTest.ENABLE);
            glow.setMouseTransparent(true);
            modelGroup.getChildren().add(glow);

            Group line = ThreeDGeometry.lineStrip(segment,
                    Color.web("#0F172A", 0.90), 0.012);
            line.setDepthTest(DepthTest.ENABLE);
            line.setMouseTransparent(true);
            modelGroup.getChildren().add(line);
        }
    }

    /** 把某条倾斜界面裁到长方体正面的矩形范围内，返回两个端点。 */
    private List<Point3D> interfaceSegment(double y0,
                                           double slope,
                                           double z,
                                           double yTop,
                                           double yBottom) {
        List<Point2D> hits = new ArrayList<>();
        addIfInside(hits, new Point2D(-BLOCK_HALF_WIDTH, y0 - slope * BLOCK_HALF_WIDTH), yTop, yBottom);
        addIfInside(hits, new Point2D(BLOCK_HALF_WIDTH, y0 + slope * BLOCK_HALF_WIDTH), yTop, yBottom);
        if (Math.abs(slope) > 1e-9) {
            double xTop = (yTop - y0) / slope;
            double xBottom = (yBottom - y0) / slope;
            if (xTop >= -BLOCK_HALF_WIDTH && xTop <= BLOCK_HALF_WIDTH) {
                hits.add(new Point2D(xTop, yTop));
            }
            if (xBottom >= -BLOCK_HALF_WIDTH && xBottom <= BLOCK_HALF_WIDTH) {
                hits.add(new Point2D(xBottom, yBottom));
            }
        }
        List<Point2D> unique = new ArrayList<>();
        for (Point2D point : hits) {
            boolean exists = unique.stream().anyMatch(p ->
                    Math.hypot(p.getX() - point.getX(), p.getY() - point.getY()) < 1e-6);
            if (!exists) {
                unique.add(point);
            }
        }
        if (unique.size() < 2) {
            return List.of();
        }
        Point2D bestA = unique.get(0);
        Point2D bestB = unique.get(1);
        double bestDistance = -1;
        for (int i = 0; i < unique.size(); i++) {
            for (int j = i + 1; j < unique.size(); j++) {
                double distance = unique.get(i).distance(unique.get(j));
                if (distance > bestDistance) {
                    bestDistance = distance;
                    bestA = unique.get(i);
                    bestB = unique.get(j);
                }
            }
        }
        return List.of(
                new Point3D(bestA.getX(), bestA.getY(), z),
                new Point3D(bestB.getX(), bestB.getY(), z));
    }

    private static void addIfInside(List<Point2D> hits,
                                    Point2D point,
                                    double yTop,
                                    double yBottom) {
        if (point.getY() >= yTop - 1e-9 && point.getY() <= yBottom + 1e-9) {
            hits.add(point);
        }
    }

    private static javafx.scene.paint.PhongMaterial blockMaterial(Color color) {
        javafx.scene.paint.PhongMaterial material = new javafx.scene.paint.PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1.16, 0.94, 1.0));
        material.setSpecularColor(Color.web("#FFFFFF", 0.05));
        material.setSpecularPower(20);
        return material;
    }

    /** 前半个长方体仅保留线框，交代完整块体范围。 */
    private Group frontWireframe(double yTop, double yBottom) {
        Group frame = new Group();
        double z = -BLOCK_DEPTH;
        List<Point3D> frontLoop = List.of(
                new Point3D(-BLOCK_HALF_WIDTH, yTop, z),
                new Point3D(BLOCK_HALF_WIDTH, yTop, z),
                new Point3D(BLOCK_HALF_WIDTH, yBottom, z),
                new Point3D(-BLOCK_HALF_WIDTH, yBottom, z),
                new Point3D(-BLOCK_HALF_WIDTH, yTop, z));
        frame.getChildren().add(ThreeDGeometry.lineStrip(
                frontLoop, Color.web("#93C5FD", 0.36), 0.016));
        for (int sx : new int[]{-1, 1}) {
            frame.getChildren().add(ThreeDGeometry.lineStrip(List.of(
                            new Point3D(sx * BLOCK_HALF_WIDTH, yTop, z),
                            new Point3D(sx * BLOCK_HALF_WIDTH, yTop, 0)),
                    Color.web("#93C5FD", 0.26), 0.012));
            frame.getChildren().add(ThreeDGeometry.lineStrip(List.of(
                            new Point3D(sx * BLOCK_HALF_WIDTH, yBottom, z),
                            new Point3D(sx * BLOCK_HALF_WIDTH, yBottom, 0)),
                    Color.web("#93C5FD", 0.22), 0.012));
        }
        frame.setDepthTest(DepthTest.ENABLE);
        frame.setMouseTransparent(true);
        return frame;
    }

    /** 长方体某个高度上的水平矩形轮廓。 */
    private Group blockLoop(double y, Color color, double width) {
        List<Point3D> points = List.of(
                new Point3D(-BLOCK_HALF_WIDTH, y, 0),
                new Point3D(BLOCK_HALF_WIDTH, y, 0),
                new Point3D(BLOCK_HALF_WIDTH, y, BLOCK_DEPTH),
                new Point3D(-BLOCK_HALF_WIDTH, y, BLOCK_DEPTH),
                new Point3D(-BLOCK_HALF_WIDTH, y, 0));
        Group group = ThreeDGeometry.lineStrip(points, color, width);
        group.setMouseTransparent(true);
        group.setDepthTest(DepthTest.ENABLE);
        return group;
    }

    private static javafx.scene.paint.PhongMaterial wellMaterial(Color color) {
        javafx.scene.paint.PhongMaterial material = new javafx.scene.paint.PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1.05, 0.78, 1.0));
        material.setSpecularColor(Color.web("#FFFFFF", 0.08));
        material.setSpecularPower(28);
        return material;
    }

    /** 中心井眼：无顶盖的空心井筒。 */
    private void buildWellBore() {
        Color wellColor = colorScale.colorOf(model.wellResistivity());
        MeshView body = ThreeDGeometry.tubeBand(
                1.0, zMid, scale,
                theta -> zTop, theta -> zBottom,
                wellColor, 1.0);
        body.setTranslateZ(WELL_Z);
        body.setMaterial(wellMaterial(wellColor));
        body.setCullFace(CullFace.BACK);
        body.setDepthTest(DepthTest.DISABLE);
        body.setMouseTransparent(true);
        modelGroup.getChildren().add(body);

    }

    private void buildFractures() {
        double yTop = (zTop - zMid) * scale;
        double yBottom = (zBottom - zMid) * scale;
        for (int i = 0; i < model.fractures().size(); i++) {
            Fracture fracture = model.fractures().get(i);
            if (!inInterval(fracture.depth())) {
                continue;
            }

            // 2D 剖面坐标 (x, 深度, 出图方向) 映射到 3D 的 (x, y, z)
            double nx = FractureGeometry.normalX(fracture);
            double ny = FractureGeometry.normalY(fracture);
            double nz = FractureGeometry.normalZ(fracture);
            Point3D normal = new Point3D(nx, nz, ny).normalize();
            Point3D origin = new Point3D(0, (fracture.depth() - zMid) * scale, 0);
            List<Point3D> polygon = ThreeDGeometry.planePolygonInBox(
                    origin, normal, BLOCK_HALF_WIDTH, yTop, yBottom, 0, BLOCK_DEPTH);
            if (polygon.size() < 3) {
                continue;
            }

            Color color = colorScale.colorOf(fracture.resistivity());
            MeshView plane = ThreeDGeometry.polygonMesh(
                    polygon, normal, color, 0.30);
            plane.setMaterial(planeMaterial(color, 0.30));
            plane.setCullFace(CullFace.NONE);
            plane.setDepthTest(DepthTest.DISABLE);
            plane.setMouseTransparent(false);
            plane.setUserData(new PickTarget(Kind.FRACTURE, i));
            modelGroup.getChildren().add(plane);

            List<Point3D> outlinePoints = new ArrayList<>(polygon);
            outlinePoints.add(polygon.get(0));
            Group outline = ThreeDGeometry.lineStrip(
                    outlinePoints, color.deriveColor(0, 1, 1, 0.90), 0.022);
            outline.setDepthTest(DepthTest.DISABLE);
            outline.setMouseTransparent(false);
            outline.setUserData(new PickTarget(Kind.FRACTURE, i));
            modelGroup.getChildren().add(outline);

            List<Point3D> frontTrace = frontEdges(polygon);
            if (frontTrace.size() >= 2) {
                Group glow = ThreeDGeometry.lineStrip(
                        frontTrace, Color.web("#FFFFFF", 0.78), 0.052);
                glow.setDepthTest(DepthTest.DISABLE);
                glow.setMouseTransparent(false);
                glow.setUserData(new PickTarget(Kind.FRACTURE, i));
                modelGroup.getChildren().add(glow);

                Group trace = ThreeDGeometry.lineStrip(
                        frontTrace, color.deriveColor(0, 1, 1, 0.96), 0.030);
                trace.setDepthTest(DepthTest.DISABLE);
                trace.setMouseTransparent(false);
                trace.setUserData(new PickTarget(Kind.FRACTURE, i));
                modelGroup.getChildren().add(trace);
            }
        }
    }

    /** 取平面多边形与块体正面的交线端点。 */
    private static List<Point3D> frontEdges(List<Point3D> polygon) {
        List<Point3D> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Point3D a = polygon.get(i);
            Point3D b = polygon.get((i + 1) % polygon.size());
            if (Math.abs(a.getZ()) > 1e-5 || Math.abs(b.getZ()) > 1e-5) {
                continue;
            }
            addUniquePoint(result, a);
            addUniquePoint(result, b);
        }
        return result;
    }

    private static void addUniquePoint(List<Point3D> points, Point3D candidate) {
        for (Point3D point : points) {
            if (point.distance(candidate) < 1e-6) {
                return;
            }
        }
        points.add(candidate);
    }

    private void buildSpheres() {
        for (int i = 0; i < model.sphereFractures().size(); i++) {
            SphereFracture sphere = model.sphereFractures().get(i);
            if (!overlapsInterval(sphere.centerDepth(), sphere.radius())) {
                continue;
            }
            double radius = sphere.radius() * scale;
            Point3D center = ThreeDGeometry.worldPoint(
                    sphere.distanceFromAxis() * scale, sphere.azimuthDeg(),
                    sphere.centerDepth(), zMid, scale);
            Color color = colorScale.colorOf(sphere.resistivity());
            addSphereWellTrace(sphere, color);

            Sphere halo = new Sphere(radius * 1.22);
            halo.setMaterial(featureMaterial(color, 0.20));
            halo.setCullFace(CullFace.NONE);
            halo.setTranslateX(center.getX());
            halo.setTranslateY(center.getY());
            halo.setTranslateZ(center.getZ());
            halo.setMouseTransparent(true);
            halo.setDepthTest(DepthTest.DISABLE);
            modelGroup.getChildren().add(halo);

            Sphere body = new Sphere(radius);
            body.setMaterial(featureMaterial(color, 1.0));
            body.setCullFace(CullFace.NONE);
            body.setTranslateX(center.getX());
            body.setTranslateY(center.getY());
            body.setTranslateZ(center.getZ());
            body.setUserData(new PickTarget(Kind.SPHERE, i));
            body.setDepthTest(DepthTest.DISABLE);
            modelGroup.getChildren().add(body);

        }
    }

    /** 球面与井壁的交线，相当于把井壁展开图上的球体轮廓绕回圆柱面。 */
    private void addSphereWellTrace(SphereFracture sphere, Color color) {
        SphereTrace.Trace trace = SphereTrace.of(
                model.wellRadius(), sphere.centerDepth(), sphere.azimuthDeg(),
                sphere.distanceFromAxis(), sphere.radius());
        if (trace.isEmpty()) {
            return;
        }
        List<SphereTrace.Point> raw = new ArrayList<>(trace.upper());
        List<SphereTrace.Point> lower = new ArrayList<>(trace.lower());
        java.util.Collections.reverse(lower);
        raw.addAll(lower);
        int stride = Math.max(1, raw.size() / 180);
        List<Point3D> points = new ArrayList<>();
        for (int i = 0; i < raw.size(); i += stride) {
            SphereTrace.Point p = raw.get(i);
            points.add(ThreeDGeometry.worldPoint(
                    1.012, p.angleDeg(), p.depth(), zMid, scale));
        }
        if (trace.kind() == SphereTrace.Kind.RING && !points.isEmpty()) {
            points.add(points.get(0));
        }
        if (points.size() < 2) {
            return;
        }
        int index = model.sphereFractures().indexOf(sphere);
        Group glow = ThreeDGeometry.lineStrip(points, Color.web("#FFFFFF", 0.72), 0.030);
        glow.setDepthTest(DepthTest.DISABLE);
        glow.setMouseTransparent(false);
        glow.setUserData(new PickTarget(Kind.SPHERE, index));
        modelGroup.getChildren().add(glow);

        Group line = ThreeDGeometry.lineStrip(
                points, color.deriveColor(0, 1, 0.82, 0.96), 0.014);
        line.setDepthTest(DepthTest.DISABLE);
        line.setMouseTransparent(false);
        line.setUserData(new PickTarget(Kind.SPHERE, index));
        modelGroup.getChildren().add(line);
    }

    private static javafx.scene.paint.PhongMaterial planeMaterial(Color color, double opacity) {
        javafx.scene.paint.PhongMaterial material = new javafx.scene.paint.PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1.22, 0.78, opacity));
        material.setSpecularColor(Color.web("#FFFFFF", 0.02));
        material.setSpecularPower(8);
        return material;
    }

    private static javafx.scene.paint.PhongMaterial featureMaterial(Color color, double opacity) {
        javafx.scene.paint.PhongMaterial material = new javafx.scene.paint.PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1.10, 1.0, opacity));
        material.setSpecularColor(Color.web("#FFFFFF", 0.03));
        material.setSpecularPower(10);
        return material;
    }

    /** 楔形体与井壁的交线轮廓，对应井壁展开图上的三角形边界。 */
    private void addWedgeWellTrace(List<WedgeTrace.Point> triangle, int index, Color color) {
        List<Point3D> points = new ArrayList<>();
        for (int edge = 0; edge < 3; edge++) {
            WedgeTrace.Point a = triangle.get(edge);
            WedgeTrace.Point b = triangle.get((edge + 1) % 3);
            for (int step = 0; step < 16; step++) {
                double t = step / 16.0;
                double angle = a.angleDeg() + t * (b.angleDeg() - a.angleDeg());
                double depth = a.depth() + t * (b.depth() - a.depth());
                points.add(ThreeDGeometry.worldPoint(1.012, angle, depth, zMid, scale));
            }
        }
        points.add(points.get(0));

        Group glow = ThreeDGeometry.lineStrip(points, Color.web("#FFFFFF", 0.74), 0.032);
        glow.setDepthTest(DepthTest.DISABLE);
        glow.setMouseTransparent(false);
        glow.setUserData(new PickTarget(Kind.WEDGE, index));
        modelGroup.getChildren().add(glow);

        Group line = ThreeDGeometry.lineStrip(
                points, color.deriveColor(0, 1, 0.82, 0.96), 0.016);
        line.setDepthTest(DepthTest.DISABLE);
        line.setMouseTransparent(false);
        line.setUserData(new PickTarget(Kind.WEDGE, index));
        modelGroup.getChildren().add(line);
    }

    private void buildWedges() {
        for (int i = 0; i < model.wedgeFractures().size(); i++) {
            WedgeFracture wedge = model.wedgeFractures().get(i);
            List<WedgeTrace.Point> triangle = WedgeTrace.polygonPoints(
                    wedge.vertexAzimuthDeg(), wedge.vertexDepth(),
                    wedge.leftAzimuthDeg(), wedge.leftDepth(),
                    wedge.rightAzimuthDeg(), wedge.rightDepth());
            if (triangle.isEmpty() || !triangleOverlapsInterval(triangle)) {
                continue;
            }

            Color color = colorScale.colorOf(wedge.resistivity());
            Group solid = ThreeDGeometry.wedgePrism(
                    triangle, zMid, scale, 0.78, 2.2, color, 0.82);
            if (solid.getChildren().isEmpty()) {
                continue;
            }
            for (Node node : solid.getChildren()) {
                node.setMouseTransparent(false);
                node.setUserData(new PickTarget(Kind.WEDGE, i));
                node.setDepthTest(DepthTest.DISABLE);
            }
            modelGroup.getChildren().add(solid);
            addWedgeWellTrace(triangle, i, color);

        }
    }

    /** 正方体异常体：按方位角对齐径向/切向，并画出它与井壁的交线。 */
    private void buildCubes() {
        for (int i = 0; i < model.cubeAnomalies().size(); i++) {
            CubeAnomaly cube = model.cubeAnomalies().get(i);
            if (!overlapsInterval(cube.centerDepth(), cube.halfSide())) {
                continue;
            }

            double theta = Math.toRadians(cube.normalizedAzimuthDeg());
            double side = cube.sideLength() * scale;
            double half = side / 2;
            Point3D center = ThreeDGeometry.worldPoint(
                    cube.distanceFromAxis() * scale, cube.normalizedAzimuthDeg(),
                    cube.centerDepth(), zMid, scale);
            Point3D radial = new Point3D(Math.cos(theta), 0, Math.sin(theta));
            Point3D tangential = new Point3D(-Math.sin(theta), 0, Math.cos(theta));
            Point3D vertical = new Point3D(0, 1, 0);
            Color color = colorScale.colorOf(cube.resistivity());

            Box halo = new Box(side * 1.18, side * 1.18, side * 1.18);
            halo.setMaterial(featureMaterial(color, 0.18));
            halo.setCullFace(CullFace.NONE);
            halo.setTranslateX(center.getX());
            halo.setTranslateY(center.getY());
            halo.setTranslateZ(center.getZ());
            halo.getTransforms().add(new Rotate(-cube.normalizedAzimuthDeg(), Rotate.Y_AXIS));
            halo.setMouseTransparent(true);
            halo.setDepthTest(DepthTest.DISABLE);
            modelGroup.getChildren().add(halo);

            Box body = new Box(side, side, side);
            body.setMaterial(featureMaterial(color, 0.92));
            body.setCullFace(CullFace.NONE);
            body.setTranslateX(center.getX());
            body.setTranslateY(center.getY());
            body.setTranslateZ(center.getZ());
            body.getTransforms().add(new Rotate(-cube.normalizedAzimuthDeg(), Rotate.Y_AXIS));
            body.setUserData(new PickTarget(Kind.CUBE, i));
            body.setDepthTest(DepthTest.DISABLE);
            modelGroup.getChildren().add(body);

            modelGroup.getChildren().add(cubeEdges(
                    center, radial, tangential, vertical, half, color));
            addCubeWellTrace(CubeTrace.of(model.wellRadius(), cube), i, color);
        }
    }

    private Group cubeEdges(Point3D center,
                            Point3D radial,
                            Point3D tangential,
                            Point3D vertical,
                            double half,
                            Color color) {
        Point3D[] corners = new Point3D[8];
        for (int i = 0; i < 8; i++) {
            double su = (i & 1) == 0 ? -half : half;
            double sv = (i & 2) == 0 ? -half : half;
            double sw = (i & 4) == 0 ? -half : half;
            corners[i] = center
                    .add(radial.multiply(su))
                    .add(tangential.multiply(sv))
                    .add(vertical.multiply(sw));
        }

        int[][] edges = {
                {0, 1}, {1, 3}, {3, 2}, {2, 0},
                {4, 5}, {5, 7}, {7, 6}, {6, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        Group group = new Group();
        for (int[] edge : edges) {
            List<Point3D> segment = List.of(corners[edge[0]], corners[edge[1]]);
            group.getChildren().add(ThreeDGeometry.lineStrip(
                    segment, Color.web("#FFFFFF", 0.68), 0.024));
            group.getChildren().add(ThreeDGeometry.lineStrip(
                    segment, color.deriveColor(0, 1, 0.72, 0.96), 0.012));
        }
        group.setMouseTransparent(true);
        group.setDepthTest(DepthTest.DISABLE);
        return group;
    }

    /** 正方体与井壁交线的三维轮廓，对应井壁展开图上的矩形区域边界。 */
    private void addCubeWellTrace(CubeTrace.Trace trace, int index, Color color) {
        if (trace.isEmpty()) {
            return;
        }
        for (CubeTrace.Span span : trace.spans()) {
            int steps = Math.max(2, (int) Math.ceil(span.lengthDeg() / 2.0));
            List<Point3D> points = new ArrayList<>();
            for (int step = 0; step <= steps; step++) {
                double angle = span.startDeg() + span.lengthDeg() * step / steps;
                points.add(ThreeDGeometry.worldPoint(
                        1.012, angle, trace.depthMax(), zMid, scale));
            }
            for (int step = steps; step >= 0; step--) {
                double angle = span.startDeg() + span.lengthDeg() * step / steps;
                points.add(ThreeDGeometry.worldPoint(
                        1.012, angle, trace.depthMin(), zMid, scale));
            }
            points.add(points.get(0));

            Group glow = ThreeDGeometry.lineStrip(points, Color.web("#FFFFFF", 0.70), 0.030);
            glow.setDepthTest(DepthTest.DISABLE);
            glow.setMouseTransparent(false);
            glow.setUserData(new PickTarget(Kind.CUBE, index));
            modelGroup.getChildren().add(glow);

            Group line = ThreeDGeometry.lineStrip(
                    points, color.deriveColor(0, 1, 0.84, 0.96), 0.014);
            line.setDepthTest(DepthTest.DISABLE);
            line.setMouseTransparent(false);
            line.setUserData(new PickTarget(Kind.CUBE, index));
            modelGroup.getChildren().add(line);
        }
    }

    private void buildDepthAxis() {
        double x = -BLOCK_HALF_WIDTH - 0.72;
        double z = 0;
        List<Point3D> axisPoints = List.of(
                new Point3D(x, (zTop - zMid) * scale, z),
                new Point3D(x, (zBottom - zMid) * scale, z));
        Group axis = ThreeDGeometry.lineStrip(axisPoints, Color.web("#38BDF8", 0.58), 0.012);
        axis.setMouseTransparent(true);
        axis.setDepthTest(DepthTest.ENABLE);
        modelGroup.getChildren().add(axis);

        for (StratumInterface stratum : model.interfaces()) {
            if (!inInterval(stratum.depth())) {
                continue;
            }
            double y = (stratum.depth() - zMid) * scale;
            Group tick = ThreeDGeometry.lineStrip(
                    List.of(new Point3D(x - 0.14, y, z), new Point3D(x + 0.14, y, z)),
                    Color.web("#38BDF8", 0.52), 0.012);
            tick.setMouseTransparent(true);
            tick.setDepthTest(DepthTest.ENABLE);
            modelGroup.getChildren().add(tick);
        }
    }

    private void buildLights() {
        AmbientLight ambient = new AmbientLight(Color.web("#CBD5E1", 0.38));

        PointLight warm = new PointLight(Color.web("#FFE7C2", 0.42));
        warm.setTranslateX(-6);
        warm.setTranslateY(-8);
        warm.setTranslateZ(-8);

        PointLight cool = new PointLight(Color.web("#8EC5FF", 0.34));
        cool.setTranslateX(7);
        cool.setTranslateY(6);
        cool.setTranslateZ(6);

        PointLight rim = new PointLight(Color.web("#6EA8FF", 0.22));
        rim.setTranslateX(0);
        rim.setTranslateY(2);
        rim.setTranslateZ(10);

        sceneLights.addAll(List.of(ambient, warm, cool, rim));
    }

    private final List<javafx.scene.LightBase> sceneLights = new ArrayList<>();

    private List<javafx.scene.LightBase> getSceneLights() {
        return sceneLights;
    }

    private void buildCamera() {
        camera.setFieldOfView(32);
        camera.setVerticalFieldOfView(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        fitCamera();
    }

    private void fitCamera() {
        double width = Math.max(1, getWidth());
        double height = Math.max(1, getHeight());
        double aspect = width / height;
        double halfDiagonal = Math.hypot(BLOCK_HALF_WIDTH, BLOCK_DEPTH);
        double modelRadius = Math.hypot(halfDiagonal,
                Math.max(1, (zBottom - zTop) * scale * 0.5));
        double fov = Math.toRadians(32);
        double distance = modelRadius / Math.sin(fov / 2) * 1.06;
        if (aspect < 0.75) {
            distance /= Math.max(0.5, aspect / 0.75);
        }
        baseDistance = distance;
        camera.setTranslateZ(-baseDistance / zoomFactor);
    }

    private boolean inInterval(double depth) {
        return depth >= zTop - 1e-9 && depth <= zBottom + 1e-9;
    }

    private boolean overlapsInterval(double centerDepth, double radiusMeters) {
        return centerDepth + radiusMeters >= zTop && centerDepth - radiusMeters <= zBottom;
    }

    private boolean triangleOverlapsInterval(List<WedgeTrace.Point> triangle) {
        double min = triangle.stream().mapToDouble(WedgeTrace.Point::depth).min().orElse(zTop);
        double max = triangle.stream().mapToDouble(WedgeTrace.Point::depth).max().orElse(zBottom);
        return max >= zTop && min <= zBottom;
    }

    private double clampDepth(double depth) {
        return Math.max(zTop, Math.min(zBottom, depth));
    }

    private void buildHud() {
        hud.getStyleClass().add("three-d-hud-layer");
        hud.setPadding(new Insets(10));

        VBox stats = new VBox(2,
                hudLabel(String.format(Locale.ROOT, "z %.2f – %.2f m", zTop, zBottom), false),
                hudLabel(String.format(Locale.ROOT, "%d 层 · 倾角 %.1f°",
                        model.layerCount(), model.dipAngle()), true));
        stats.getStyleClass().add("three-d-hud");
        stats.setPadding(new Insets(7, 10, 7, 10));
        stats.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        HBox top = new HBox(10, stats, new Region(), buildCompass());
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.TOP_LEFT);
        hud.setTop(top);

        VBox chips = buildLegendChips();
        HBox bottomRow = new HBox(10, buildColorBar(), new Region(), buildControls());
        HBox.setHgrow(bottomRow.getChildren().get(1), Priority.ALWAYS);
        bottomRow.setAlignment(Pos.BOTTOM_CENTER);

        hintLabel.getStyleClass().add("three-d-hint");
        hintLabel.setMaxWidth(Region.USE_PREF_SIZE);
        hintLabel.setOpacity(1);
        VBox bottom = new VBox(6, chips, hintLabel, bottomRow);
        bottom.setAlignment(Pos.BOTTOM_CENTER);
        hud.setBottom(bottom);
    }

    private Label hudLabel(String text, boolean secondary) {
        Label label = new Label(text);
        label.getStyleClass().add(secondary ? "three-d-hud-secondary" : "three-d-hud-text");
        return label;
    }

    private StackPane buildCompass() {
        StackPane compass = new StackPane();
        compass.getStyleClass().add("three-d-hud");
        compass.setPrefSize(52, 52);
        compass.setMaxSize(52, 52);

        Pane pane = new Pane();
        pane.setPrefSize(52, 52);
        Circle ring = new Circle(26, 26, 21, Color.TRANSPARENT);
        ring.setStroke(Color.web("#38BDF8", 0.55));
        ring.setStrokeWidth(1);
        pane.getChildren().add(ring);

        Group dial = new Group();
        dial.getChildren().add(line(26, 26, 26, 7, "#67E8F9", 1.2));
        dial.getChildren().add(line(26, 26, 45, 26, "#38BDF8", 0.7));
        dial.getChildren().add(line(26, 26, 26, 45, "#38BDF8", 0.7));
        dial.getChildren().add(line(26, 26, 7, 26, "#38BDF8", 0.7));
        dial.getChildren().add(compassLabel("0°", 22, 1));
        dial.getChildren().add(compassLabel("90°", 36, 20));
        dial.getChildren().add(compassLabel("180°", 17, 35));
        dial.getChildren().add(compassLabel("270°", 1, 20));
        pane.getChildren().add(dial);
        dial.rotateProperty().bind(yawRotate.angleProperty().multiply(-1));
        compass.getChildren().add(pane);
        return compass;
    }

    private static Line line(double x1, double y1, double x2, double y2,
                             String color, double width) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Color.web(color));
        line.setStrokeWidth(width);
        return line;
    }

    private static Label compassLabel(String text, double x, double y) {
        Label label = new Label(text);
        label.setLayoutX(x);
        label.setLayoutY(y);
        label.setStyle("-fx-font-size: 7px; -fx-text-fill: #94A3B8;");
        return label;
    }

    private VBox buildLegendChips() {
        HBox first = new HBox(6,
                chip(model.layerCount() + " 层电阻率分块", firstColor(
                        colorScale.colorOf(model.resistivityOf(0)), "#FBBF24")),
                chip("中心井眼", firstColor(colorScale.colorOf(model.wellResistivity()), "#F97316")));
        HBox second = new HBox(6,
                chip("裂缝 " + model.fractures().size(), "#F97316"),
                chip("球体 " + model.sphereFractures().size(), firstColor(
                        model.sphereFractures().isEmpty() ? null
                                : colorScale.colorOf(model.sphereFractures().get(0).resistivity()),
                        "#FDE68A")),
                chip("楔形 " + model.wedgeFractures().size(), firstColor(
                        model.wedgeFractures().isEmpty() ? null
                                : colorScale.colorOf(model.wedgeFractures().get(0).resistivity()),
                        "#FACC15")),
                chip("正方体 " + model.cubeAnomalies().size(), firstColor(
                        model.cubeAnomalies().isEmpty() ? null
                                : colorScale.colorOf(model.cubeAnomalies().get(0).resistivity()),
                        "#FB7185")));
        first.setAlignment(Pos.CENTER);
        second.setAlignment(Pos.CENTER);
        VBox box = new VBox(4, first, second);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private HBox chip(String text, String color) {
        Region dot = new Region();
        dot.setMinSize(6, 6);
        dot.setPrefSize(6, 6);
        dot.setMaxSize(6, 6);
        dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 50%;");
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 9.5px; -fx-text-fill: #E2E8F0;");
        HBox chip = new HBox(5, dot, label);
        chip.getStyleClass().add("three-d-chip");
        chip.setAlignment(Pos.CENTER);
        return chip;
    }

    private VBox buildColorBar() {
        Region bar = new Region();
        bar.setPrefSize(110, 6);
        bar.setMinSize(110, 6);
        bar.setMaxSize(110, 6);
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            double t = i / 6.0;
            double rho = colorScale.min()
                    * Math.pow(colorScale.max() / colorScale.min(), t);
            stops.add(new Stop(t, colorScale.colorOf(rho)));
        }
        bar.setBackground(new javafx.scene.layout.Background(
                new javafx.scene.layout.BackgroundFill(new LinearGradient(
                        0, 0, 1, 0, true, CycleMethod.NO_CYCLE, stops),
                        null, null)));
        Label min = new Label(String.format(Locale.ROOT, "%.0f", colorScale.min()));
        Label max = new Label(String.format(Locale.ROOT, "%.0f", colorScale.max()));
        min.setStyle("-fx-font-size: 9px; -fx-text-fill: #94A3B8;");
        max.setStyle("-fx-font-size: 9px; -fx-text-fill: #94A3B8;");
        HBox labels = new HBox(4, min, new Region(), max);
        HBox.setHgrow(labels.getChildren().get(1), Priority.ALWAYS);
        labels.setPrefWidth(110);
        VBox box = new VBox(3, bar, labels);
        box.getStyleClass().add("three-d-hud");
        box.setPadding(new Insets(6, 8, 6, 8));
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }

    private HBox buildControls() {
        Button reset = new Button("复位");
        reset.getStyleClass().add("three-d-hud-button");
        reset.setOnAction(e -> resetView());
        HBox controls = new HBox(6, reset);
        controls.setAlignment(Pos.BOTTOM_RIGHT);
        return controls;
    }

    private static String firstColor(Color color, String fallback) {
        return color == null ? fallback : toWeb(color);
    }

    private static String toWeb(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private void setupInteraction() {
        setCursor(Cursor.OPEN_HAND);
        subScene.setOnMousePressed(this::onPressed);
        subScene.setOnMouseDragged(this::onDragged);
        subScene.setOnMouseReleased(this::onReleased);
        subScene.setOnMouseClicked(this::onClicked);
        subScene.setOnScroll(this::onScroll);
    }

    private void onPressed(MouseEvent event) {
        dragStartX = event.getSceneX();
        dragStartY = event.getSceneY();
        lastX = dragStartX;
        lastY = dragStartY;
        dragged = false;
    }

    private void onDragged(MouseEvent event) {
        double dx = event.getSceneX() - lastX;
        double dy = event.getSceneY() - lastY;
        if (!dragged && Math.hypot(event.getSceneX() - dragStartX,
                event.getSceneY() - dragStartY) <= 2) {
            return;
        }
        if (!dragged) {
            dragged = true;
            setCursor(Cursor.CLOSED_HAND);
        }

        yawRotate.setAngle(yawRotate.getAngle() + dx * 0.35);
        pitchRotate.setAngle(clamp(pitchRotate.getAngle() - dy * 0.30,
                MIN_PITCH, MAX_PITCH));
        lastX = event.getSceneX();
        lastY = event.getSceneY();
        event.consume();
    }

    private void onReleased(MouseEvent event) {
        if (dragged) {
            setCursor(Cursor.OPEN_HAND);
        }
    }

    private void onClicked(MouseEvent event) {
        if (dragged) {
            return;
        }
        if (event.getClickCount() == 2) {
            resetView();
            return;
        }
        Node picked = event.getPickResult().getIntersectedNode();
        while (picked != null && !(picked.getUserData() instanceof PickTarget)) {
            picked = picked.getParent();
        }
        if (picked == null || !(picked.getUserData() instanceof PickTarget target)) {
            return;
        }
        switch (target.kind()) {
            case FRACTURE -> onFractureSelected.accept(target.index());
            case SPHERE -> onSphereSelected.accept(target.index());
            case WEDGE -> onWedgeSelected.accept(target.index());
            case CUBE -> onCubeSelected.accept(target.index());
            case LAYER -> onLayerSelected.accept(target.index());
        }
    }

    private void onScroll(ScrollEvent event) {
        double factor = Math.exp(-event.getDeltaY() * 0.0015);
        zoomFactor = clamp(zoomFactor * factor, MIN_ZOOM, MAX_ZOOM);
        camera.setTranslateZ(-baseDistance / zoomFactor);
        event.consume();
    }

    public void resetView() {
        if (viewResetTimeline != null) {
            viewResetTimeline.stop();
        }
        zoomFactor = 1;
        viewResetTimeline = new Timeline(new KeyFrame(Duration.millis(200),
                new KeyValue(yawRotate.angleProperty(), DEFAULT_YAW, Interpolator.EASE_BOTH),
                new KeyValue(pitchRotate.angleProperty(), DEFAULT_PITCH, Interpolator.EASE_BOTH),
                new KeyValue(camera.translateZProperty(), -baseDistance, Interpolator.EASE_BOTH)));
        viewResetTimeline.play();
    }

    public void stopAnimation() {
        hintFade.stop();
        if (viewResetTimeline != null) {
            viewResetTimeline.stop();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
