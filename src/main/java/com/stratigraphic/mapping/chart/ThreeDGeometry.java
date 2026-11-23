package com.stratigraphic.mapping.chart;

import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;

/** 三维图使用的纯几何换算与 JavaFX 网格构造工具。 */
final class ThreeDGeometry {

    static final int TUBE_SEGMENTS = 120;
    static final int ANNULUS_SEGMENTS = 96;
    static final int DISC_SEGMENTS = 72;
    /** 楔形体沿截面法向的总厚度（显示单位，1 = 井半径）。 */
    static final double WEDGE_HALF_THICKNESS = 0.4;

    private ThreeDGeometry() {
    }

    static double interfaceDepth(double z0, double dipDeg, double radius, double azimuthDeg) {
        return z0 + radius * cot(dipDeg) * Math.cos(Math.toRadians(azimuthDeg));
    }

    static Point3D worldPoint(double radius,
                              double azimuthDeg,
                              double depth,
                              double zMid,
                              double scale) {
        double theta = Math.toRadians(azimuthDeg);
        return new Point3D(
                radius * Math.cos(theta),
                (depth - zMid) * scale,
                radius * Math.sin(theta));
    }

    static Point3D interfaceNormal(double dipDeg) {
        return new Point3D(-cot(dipDeg), 1, 0).normalize();
    }

    static Point3D discNormal(double elevationDeg, double azimuthDeg) {
        double beta = Math.toRadians(elevationDeg);
        double phi = Math.toRadians(azimuthDeg);
        return new Point3D(
                Math.sin(beta) * Math.cos(phi),
                -Math.cos(beta),
                Math.sin(beta) * Math.sin(phi)).normalize();
    }

    /** 二维半平面裁剪：保留 a*x + b*y + c >= 0 的一侧。 */
    static List<Point2D> clipHalfPlane(List<Point2D> polygon,
                                       double a,
                                       double b,
                                       double c) {
        List<Point2D> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Point2D cur = polygon.get(i);
            Point2D next = polygon.get((i + 1) % polygon.size());
            double fc = a * cur.getX() + b * cur.getY() + c;
            double fn = a * next.getX() + b * next.getY() + c;
            boolean curIn = fc >= -1e-9;
            boolean nextIn = fn >= -1e-9;
            if (curIn) {
                out.add(cur);
            }
            if (curIn != nextIn) {
                double t = fc / (fc - fn);
                out.add(new Point2D(
                        cur.getX() + t * (next.getX() - cur.getX()),
                        cur.getY() + t * (next.getY() - cur.getY())));
            }
        }
        return out;
    }

    /** 把 x-y 截面多边形沿 z 方向拉伸成实体层块。 */
    static MeshView prism(List<Point2D> section,
                          double z0,
                          double z1,
                          double halfWidth,
                          double yTop,
                          double yBottom,
                          Color color,
                          double opacity) {
        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        int n = section.size();
        if (n < 3) {
            return meshView(points, normals, texCoords, triangles, color, opacity, 48);
        }

        double cx = section.stream().mapToDouble(Point2D::getX).average().orElse(0);
        double cy = section.stream().mapToDouble(Point2D::getY).average().orElse(0);

        int frontCenter = addVertex(points, normals, texCoords,
                new Point3D(cx, cy, z0), new Point3D(0, 0, -1), 0.5, 0.5);
        int[] front = new int[n];
        for (int i = 0; i < n; i++) {
            Point2D p = section.get(i);
            front[i] = addVertex(points, normals, texCoords,
                    new Point3D(p.getX(), p.getY(), z0),
                    new Point3D(0, 0, -1),
                    (p.getX() - cx) / 10 + 0.5, (p.getY() - cy) / 10 + 0.5);
        }
        for (int i = 0; i < n; i++) {
            triangles.add(new int[]{frontCenter, front[i], front[(i + 1) % n]});
        }

        int backCenter = addVertex(points, normals, texCoords,
                new Point3D(cx, cy, z1), new Point3D(0, 0, 1), 0.5, 0.5);
        int[] back = new int[n];
        for (int i = 0; i < n; i++) {
            Point2D p = section.get(i);
            back[i] = addVertex(points, normals, texCoords,
                    new Point3D(p.getX(), p.getY(), z1),
                    new Point3D(0, 0, 1),
                    (p.getX() - cx) / 10 + 0.5, (p.getY() - cy) / 10 + 0.5);
        }
        for (int i = 0; i < n; i++) {
            triangles.add(new int[]{backCenter, back[(i + 1) % n], back[i]});
        }

        for (int i = 0; i < n; i++) {
            Point2D a = section.get(i);
            Point2D b = section.get((i + 1) % n);
            if (!isOuterSectionEdge(a, b, halfWidth, yTop, yBottom)) {
                continue;
            }
            double dx = b.getX() - a.getX();
            double dy = b.getY() - a.getY();
            double length = Math.hypot(dx, dy);
            if (length < 1e-12) {
                continue;
            }
            double nx = dy / length;
            double ny = -dx / length;
            double mx = (a.getX() + b.getX()) / 2 - cx;
            double my = (a.getY() + b.getY()) / 2 - cy;
            if (mx * nx + my * ny < 0) {
                nx = -nx;
                ny = -ny;
            }
            Point3D normal = new Point3D(nx, ny, 0);
            int i0 = addVertex(points, normals, texCoords,
                    new Point3D(a.getX(), a.getY(), z0), normal, 0, 0);
            int i1 = addVertex(points, normals, texCoords,
                    new Point3D(b.getX(), b.getY(), z0), normal, 1, 0);
            int i2 = addVertex(points, normals, texCoords,
                    new Point3D(b.getX(), b.getY(), z1), normal, 1, 1);
            int i3 = addVertex(points, normals, texCoords,
                    new Point3D(a.getX(), a.getY(), z1), normal, 0, 1);
            triangles.add(new int[]{i0, i1, i2});
            triangles.add(new int[]{i0, i2, i3});
        }
        return meshView(points, normals, texCoords, triangles, color, opacity, 48);
    }

    private static boolean isOuterSectionEdge(Point2D a,
                                             Point2D b,
                                             double halfWidth,
                                             double yTop,
                                             double yBottom) {
        boolean leftRight = Math.abs(a.getX() - b.getX()) < 1e-7
                && (Math.abs(a.getX() + halfWidth) < 1e-6
                || Math.abs(a.getX() - halfWidth) < 1e-6);
        boolean topBottom = Math.abs(a.getY() - b.getY()) < 1e-7
                && (Math.abs(a.getY() - yTop) < 1e-6
                || Math.abs(a.getY() - yBottom) < 1e-6);
        return leftRight || topBottom;
    }

    /** 用长方体六个半空间裁剪一个无限平面，得到平面在块体内的多边形。 */
    static List<Point3D> planePolygonInBox(Point3D origin,
                                           Point3D normal,
                                           double halfWidth,
                                           double yTop,
                                           double yBottom,
                                           double zMin,
                                           double zMax) {
        Point3D n = normal.normalize();
        Point3D reference = Math.abs(n.getY()) < 0.9
                ? new Point3D(0, 1, 0) : new Point3D(1, 0, 0);
        Point3D u = n.crossProduct(reference).normalize();
        Point3D v = n.crossProduct(u).normalize();
        double size = 4 * (halfWidth + (yBottom - yTop) + (zMax - zMin)) + 20;
        List<Point3D> poly = new ArrayList<>(List.of(
                origin.add(u.multiply(size)).add(v.multiply(size)),
                origin.subtract(u.multiply(size)).add(v.multiply(size)),
                origin.subtract(u.multiply(size)).subtract(v.multiply(size)),
                origin.add(u.multiply(size)).subtract(v.multiply(size))));

        poly = clipPolygon3D(poly, p -> p.getX() + halfWidth);
        poly = clipPolygon3D(poly, p -> halfWidth - p.getX());
        poly = clipPolygon3D(poly, p -> p.getY() - yTop);
        poly = clipPolygon3D(poly, p -> yBottom - p.getY());
        poly = clipPolygon3D(poly, p -> p.getZ() - zMin);
        poly = clipPolygon3D(poly, p -> zMax - p.getZ());
        return poly;
    }

    /** 把三维平面多边形做成双面半透明网格。 */
    static MeshView polygonMesh(List<Point3D> polygon,
                                Point3D normal,
                                Color color,
                                double opacity) {
        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        if (polygon.size() < 3) {
            return meshView(points, normals, texCoords, triangles, color, opacity, 24);
        }
        double cx = polygon.stream().mapToDouble(Point3D::getX).average().orElse(0);
        double cy = polygon.stream().mapToDouble(Point3D::getY).average().orElse(0);
        double cz = polygon.stream().mapToDouble(Point3D::getZ).average().orElse(0);
        Point3D n = normal.normalize();
        int center = addVertex(points, normals, texCoords,
                new Point3D(cx, cy, cz), n, 0.5, 0.5);
        int[] idx = new int[polygon.size()];
        for (int i = 0; i < polygon.size(); i++) {
            Point3D p = polygon.get(i);
            idx[i] = addVertex(points, normals, texCoords, p, n, 0, 0);
        }
        for (int i = 0; i < idx.length; i++) {
            triangles.add(new int[]{center, idx[i], idx[(i + 1) % idx.length]});
        }
        return meshView(points, normals, texCoords, triangles, color, opacity, 24);
    }

    private static List<Point3D> clipPolygon3D(List<Point3D> polygon,
                                               ToDoubleFunction<Point3D> halfSpace) {
        List<Point3D> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Point3D cur = polygon.get(i);
            Point3D next = polygon.get((i + 1) % polygon.size());
            double fc = halfSpace.applyAsDouble(cur);
            double fn = halfSpace.applyAsDouble(next);
            boolean curIn = fc >= -1e-9;
            boolean nextIn = fn >= -1e-9;
            if (curIn) {
                out.add(cur);
            }
            if (curIn != nextIn) {
                double t = fc / (fc - fn);
                out.add(cur.add(next.subtract(cur).multiply(t)));
            }
        }
        return out;
    }

    private static int addVertex(List<Point3D> points,
                                 List<Point3D> normals,
                                 List<Point2D> texCoords,
                                 Point3D point,
                                 Point3D normal,
                                 double u,
                                 double v) {
        points.add(point);
        normals.add(normal);
        texCoords.add(new Point2D(u, v));
        return points.size() - 1;
    }

    static MeshView tubeBand(double radius,
                             double zMid,
                             double scale,
                             DoubleUnaryOperator topDepthAt,
                             DoubleUnaryOperator bottomDepthAt,
                             Color color,
                             double opacity) {

        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();

        for (int i = 0; i < TUBE_SEGMENTS; i++) {
            double a0 = 360.0 * i / TUBE_SEGMENTS;
            double a1 = 360.0 * (i + 1) / TUBE_SEGMENTS;
            double top0 = topDepthAt.applyAsDouble(a0);
            double bottom0 = bottomDepthAt.applyAsDouble(a0);
            double top1 = topDepthAt.applyAsDouble(a1);
            double bottom1 = bottomDepthAt.applyAsDouble(a1);
            if (bottom0 <= top0 || bottom1 <= top1) {
                continue;
            }

            int base = points.size();
            points.add(worldPoint(radius, a0, top0, zMid, scale));
            points.add(worldPoint(radius, a0, bottom0, zMid, scale));
            points.add(worldPoint(radius, a1, top1, zMid, scale));
            points.add(worldPoint(radius, a1, bottom1, zMid, scale));
            normals.add(radialNormal(a0));
            normals.add(radialNormal(a0));
            normals.add(radialNormal(a1));
            normals.add(radialNormal(a1));
            texCoords.add(new Point2D(i / (double) TUBE_SEGMENTS, 0));
            texCoords.add(new Point2D(i / (double) TUBE_SEGMENTS, 1));
            texCoords.add(new Point2D((i + 1) / (double) TUBE_SEGMENTS, 0));
            texCoords.add(new Point2D((i + 1) / (double) TUBE_SEGMENTS, 1));
            triangles.add(new int[]{base, base + 1, base + 2});
            triangles.add(new int[]{base + 2, base + 1, base + 3});
        }
        return meshView(points, normals, texCoords, triangles, color, opacity, 48);
    }

    /** 三角楔形体：以三个井壁点为三角截面，沿截面法向拉出厚度，形成实体楔形。 */
    static Group wedgeSolid(List<WedgeTrace.Point> triangle,
                            double zMid,
                            double scale,
                            Color color,
                            double opacity) {
        Group group = new Group();
        if (triangle == null || triangle.size() != 3 || area3D(triangle) <= 1e-9) {
            return group;
        }

        Point3D apex = worldPoint(1.0, triangle.get(0).angleDeg(),
                triangle.get(0).depth(), zMid, scale);
        Point3D baseA = worldPoint(1.0, triangle.get(1).angleDeg(),
                triangle.get(1).depth(), zMid, scale);
        Point3D baseB = worldPoint(1.0, triangle.get(2).angleDeg(),
                triangle.get(2).depth(), zMid, scale);

        Point3D normal = baseA.subtract(apex).crossProduct(baseB.subtract(apex));
        if (normal.magnitude() < 1e-9) {
            return group;
        }
        Point3D offset = normal.normalize().multiply(WEDGE_HALF_THICKNESS);

        Point3D b1 = baseA.subtract(offset);
        Point3D b2 = baseA.add(offset);
        Point3D b3 = baseB.add(offset);
        Point3D b4 = baseB.subtract(offset);
        Point3D center = b1.add(b2).add(b3).add(b4).add(apex).multiply(1.0 / 5.0);

        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();

        addOrientedTriangle(points, normals, texCoords, triangles, b1, b2, b3, center);
        addOrientedTriangle(points, normals, texCoords, triangles, b1, b3, b4, center);
        addOrientedTriangle(points, normals, texCoords, triangles, b1, b2, apex, center);
        addOrientedTriangle(points, normals, texCoords, triangles, b2, b3, apex, center);
        addOrientedTriangle(points, normals, texCoords, triangles, b3, b4, apex, center);
        addOrientedTriangle(points, normals, texCoords, triangles, b4, b1, apex, center);

        MeshView solid = meshView(
                points, normals, texCoords, triangles, color, opacity, 48);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1, 1, opacity));
        material.setSpecularColor(Color.web("#FFFFFF", 0.18));
        material.setSpecularPower(12);
        solid.setMaterial(material);
        group.getChildren().add(solid);
        return group;
    }

    /**
     * 从井壁三角形向外拉出的实体楔形：内表面贴在井壁上，外表面沿径向
     * 推到 outerRadius，侧面加斜线纹理，形成类似三维异常体的楔状体。
     */
    static Group wedgePrism(List<WedgeTrace.Point> triangle,
                            double zMid,
                            double scale,
                            double innerRadius,
                            double outerRadius,
                            Color color,
                            double opacity) {
        Group group = new Group();
        if (triangle == null || triangle.size() != 3 || area3D(triangle) <= 1e-9) {
            return group;
        }

        Point3D apex = worldPoint(innerRadius, triangle.get(0).angleDeg(),
                triangle.get(0).depth(), zMid, scale);
        Point3D baseA = worldPoint(innerRadius, triangle.get(1).angleDeg(),
                triangle.get(1).depth(), zMid, scale);
        Point3D baseB = worldPoint(innerRadius, triangle.get(2).angleDeg(),
                triangle.get(2).depth(), zMid, scale);
        Point3D centroid = apex.add(baseA).add(baseB).multiply(1.0 / 3.0);
        Point3D radial = new Point3D(centroid.getX(), 0, centroid.getZ());
        if (radial.magnitude() < 1e-9) {
            radial = new Point3D(1, 0, 0);
        }
        Point3D offset = radial.normalize().multiply(outerRadius - innerRadius);

        Point3D apexOuter = apex.add(offset);
        Point3D baseAOuter = baseA.add(offset);
        Point3D baseBOuter = baseB.add(offset);
        Point3D reference = centroid.add(offset.multiply(0.35));

        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();

        addTriangleWithReference(points, normals, texCoords, triangles,
                apex, baseA, baseB, reference);
        addTriangleWithReference(points, normals, texCoords, triangles,
                apexOuter, baseBOuter, baseAOuter, reference);
        addQuadFace(points, normals, texCoords, triangles,
                apex, baseA, baseAOuter, apexOuter, reference);
        addQuadFace(points, normals, texCoords, triangles,
                baseA, baseB, baseBOuter, baseAOuter, reference);
        addQuadFace(points, normals, texCoords, triangles,
                baseB, apex, apexOuter, baseBOuter, reference);

        MeshView solid = meshView(points, normals, texCoords, triangles, color, opacity, 48);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1.20, 0.88, opacity));
        material.setSpecularColor(Color.web("#FFFFFF", 0.10));
        material.setSpecularPower(18);
        solid.setMaterial(material);
        group.getChildren().add(solid);

        Color hatch = color.deriveColor(0, 0.90, 0.34, 0.90);
        for (int k = 1; k <= 10; k++) {
            double t = k / 11.0;
            Point3D a = lerp(apex, baseA, t);
            Point3D b = lerp(apexOuter, baseAOuter, t);
            Group line = lineStrip(List.of(a, b), hatch, 0.010);
            line.setMouseTransparent(true);
            group.getChildren().add(line);
        }

        Group outline = new Group();
        outline.getChildren().add(lineStrip(List.of(apex, baseA, baseB, apex),
                Color.web("#FFFFFF", 0.55), 0.016));
        outline.getChildren().add(lineStrip(List.of(apexOuter, baseAOuter, baseBOuter, apexOuter),
                color.interpolate(Color.WHITE, 0.42), 0.018));
        for (Point3D[] pair : new Point3D[][]{
                {apex, apexOuter}, {baseA, baseAOuter}, {baseB, baseBOuter}}) {
            outline.getChildren().add(lineStrip(List.of(pair[0], pair[1]),
                    color.interpolate(Color.WHITE, 0.35), 0.014));
        }
        outline.setMouseTransparent(true);
        group.getChildren().add(outline);
        return group;
    }

    private static Point3D lerp(Point3D a, Point3D b, double t) {
        return a.add(b.subtract(a).multiply(t));
    }

    private static void addTriangleWithReference(List<Point3D> points,
                                                 List<Point3D> normals,
                                                 List<Point2D> texCoords,
                                                 List<int[]> triangles,
                                                 Point3D a,
                                                 Point3D b,
                                                 Point3D c,
                                                 Point3D reference) {
        Point3D normal = b.subtract(a).crossProduct(c.subtract(a));
        if (normal.magnitude() < 1e-9) {
            return;
        }
        normal = normal.normalize();
        Point3D faceCenter = a.add(b).add(c).multiply(1.0 / 3.0);
        if (normal.dotProduct(faceCenter.subtract(reference)) < 0) {
            normal = normal.multiply(-1);
            Point3D swap = b;
            b = c;
            c = swap;
        }
        int base = points.size();
        points.add(a);
        points.add(b);
        points.add(c);
        normals.add(normal);
        normals.add(normal);
        normals.add(normal);
        texCoords.add(new Point2D(0, 0));
        texCoords.add(new Point2D(1, 0));
        texCoords.add(new Point2D(0, 1));
        triangles.add(new int[]{base, base + 1, base + 2});
    }

    private static void addQuadFace(List<Point3D> points,
                                    List<Point3D> normals,
                                    List<Point2D> texCoords,
                                    List<int[]> triangles,
                                    Point3D a,
                                    Point3D b,
                                    Point3D c,
                                    Point3D d,
                                    Point3D reference) {
        addTriangleWithReference(points, normals, texCoords, triangles, a, b, c, reference);
        addTriangleWithReference(points, normals, texCoords, triangles, a, c, d, reference);
    }

    /** 楔形体的 8 条棱线：底部矩形 4 条 + 顶点到 4 个底角。 */
    static Group wedgeOutline(List<WedgeTrace.Point> triangle,
                              double zMid,
                              double scale,
                              Color color,
                              double width) {
        Group group = new Group();
        if (triangle == null || triangle.size() != 3 || area3D(triangle) <= 1e-9) {
            return group;
        }

        Point3D apex = worldPoint(1.0, triangle.get(0).angleDeg(),
                triangle.get(0).depth(), zMid, scale);
        Point3D baseA = worldPoint(1.0, triangle.get(1).angleDeg(),
                triangle.get(1).depth(), zMid, scale);
        Point3D baseB = worldPoint(1.0, triangle.get(2).angleDeg(),
                triangle.get(2).depth(), zMid, scale);

        Point3D normal = baseA.subtract(apex).crossProduct(baseB.subtract(apex));
        if (normal.magnitude() < 1e-9) {
            return group;
        }
        Point3D offset = normal.normalize().multiply(WEDGE_HALF_THICKNESS);

        Point3D b1 = baseA.subtract(offset);
        Point3D b2 = baseA.add(offset);
        Point3D b3 = baseB.add(offset);
        Point3D b4 = baseB.subtract(offset);

        addEdge(group, b1, b2, color, width);
        addEdge(group, b2, b3, color, width);
        addEdge(group, b3, b4, color, width);
        addEdge(group, b4, b1, color, width);
        addEdge(group, apex, b1, color, width);
        addEdge(group, apex, b2, color, width);
        addEdge(group, apex, b3, color, width);
        addEdge(group, apex, b4, color, width);
        return group;
    }

    private static void addEdge(Group group, Point3D a, Point3D b, Color color, double width) {
        group.getChildren().add(lineStrip(List.of(a, b), color, width));
    }

    private static void addOrientedTriangle(List<Point3D> points,
                                            List<Point3D> normals,
                                            List<Point2D> texCoords,
                                            List<int[]> triangles,
                                            Point3D a,
                                            Point3D b,
                                            Point3D c,
                                            Point3D center) {
        Point3D normal = b.subtract(a).crossProduct(c.subtract(a));
        if (normal.magnitude() < 1e-9) {
            return;
        }
        normal = normal.normalize();
        Point3D faceCenter = a.add(b).add(c).multiply(1.0 / 3.0);
        if (normal.dotProduct(faceCenter.subtract(center)) < 0) {
            normal = normal.multiply(-1);
            Point3D swap = b;
            b = c;
            c = swap;
        }
        int base = points.size();
        points.add(a);
        points.add(b);
        points.add(c);
        normals.add(normal);
        normals.add(normal);
        normals.add(normal);
        texCoords.add(new Point2D(0, 0));
        texCoords.add(new Point2D(1, 0));
        texCoords.add(new Point2D(0, 1));
        triangles.add(new int[]{base, base + 1, base + 2});
    }

    private static double area3D(List<WedgeTrace.Point> triangle) {
        return Math.abs(
                (triangle.get(1).angleDeg() - triangle.get(0).angleDeg())
                        * (triangle.get(2).depth() - triangle.get(0).depth())
                        - (triangle.get(2).angleDeg() - triangle.get(0).angleDeg())
                        * (triangle.get(1).depth() - triangle.get(0).depth())) / 2;
    }

    static Group lineStrip(List<Point3D> points, Color color, double width) {
        Group group = new Group();
        PhongMaterial material = material(color, 1, 64);
        for (int i = 1; i < points.size(); i++) {
            Point3D a = points.get(i - 1);
            Point3D b = points.get(i);
            Point3D direction = b.subtract(a);
            double length = direction.magnitude();
            if (length < 1e-9) {
                continue;
            }
            Point3D unit = direction.normalize();
            Cylinder segment = new Cylinder(width / 2, length);
            segment.setMaterial(material);
            segment.setDrawMode(DrawMode.FILL);
            segment.setTranslateX((a.getX() + b.getX()) / 2);
            segment.setTranslateY((a.getY() + b.getY()) / 2);
            segment.setTranslateZ((a.getZ() + b.getZ()) / 2);
            Point3D axis = Rotate.Y_AXIS.crossProduct(unit);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                    Rotate.Y_AXIS.dotProduct(unit)))));
            if (axis.magnitude() > 1e-9) {
                segment.getTransforms().add(new Rotate(angle, axis.normalize()));
            } else if (unit.getY() < 0) {
                segment.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
            }
            group.getChildren().add(segment);
        }
        return group;
    }

    static List<Point3D> discBoundary(double z0,
                                      double elevationDeg,
                                      double azimuthDeg,
                                      double zMid,
                                      double scale,
                                      double radius) {
        Point3D normal = discNormal(elevationDeg, azimuthDeg);
        Point3D center = new Point3D(0, (z0 - zMid) * scale, 0);
        Point3D[] basis = discBasis(normal);
        List<Point3D> points = new ArrayList<>(DISC_SEGMENTS + 1);
        for (int i = 0; i <= DISC_SEGMENTS; i++) {
            double angle = Math.toRadians(360.0 * i / DISC_SEGMENTS);
            points.add(center.add(basis[0].multiply(radius * Math.cos(angle)))
                    .add(basis[1].multiply(radius * Math.sin(angle))));
        }
        return points;
    }

    static PhongMaterial material(Color color, double opacity, double specularPower) {
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(color.deriveColor(0, 1, 1, opacity));
        material.setSpecularColor(Color.web("#FFFFFF", Math.min(1, opacity)));
        material.setSpecularPower(specularPower);
        return material;
    }

    private static MeshView meshView(List<Point3D> points,
                                     List<Point3D> normals,
                                     List<Point2D> texCoords,
                                     List<int[]> triangles,
                                     Color color,
                                     double opacity,
                                     double specularPower) {
        TriangleMesh mesh = new TriangleMesh();
        float[] pointArray = new float[points.size() * 3];
        float[] normalArray = new float[points.size() * 3];
        float[] texArray = new float[texCoords.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            Point3D p = points.get(i);
            Point3D n = normals.get(i);
            pointArray[i * 3] = (float) p.getX();
            pointArray[i * 3 + 1] = (float) p.getY();
            pointArray[i * 3 + 2] = (float) p.getZ();
            normalArray[i * 3] = (float) n.getX();
            normalArray[i * 3 + 1] = (float) n.getY();
            normalArray[i * 3 + 2] = (float) n.getZ();
            Point2D t = texCoords.get(i);
            texArray[i * 2] = (float) t.getX();
            texArray[i * 2 + 1] = (float) t.getY();
        }
        int[] faceArray = new int[triangles.size() * 6];
        for (int i = 0; i < triangles.size(); i++) {
            int[] tri = triangles.get(i);
            faceArray[i * 6] = tri[0];
            faceArray[i * 6 + 1] = tri[0];
            faceArray[i * 6 + 2] = tri[1];
            faceArray[i * 6 + 3] = tri[1];
            faceArray[i * 6 + 4] = tri[2];
            faceArray[i * 6 + 5] = tri[2];
        }
        mesh.getPoints().setAll(pointArray);
        mesh.getNormals().setAll(normalArray);
        mesh.getTexCoords().setAll(texArray);
        mesh.getFaces().setAll(faceArray);

        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material(color, opacity, specularPower));
        view.setMouseTransparent(true);
        return view;
    }

    private static Point3D radialNormal(double azimuthDeg) {
        double theta = Math.toRadians(azimuthDeg);
        return new Point3D(Math.cos(theta), 0, Math.sin(theta));
    }

    private static Point3D[] discBasis(Point3D normal) {
        Point3D reference = Math.abs(normal.getY()) < 0.9
                ? new Point3D(0, 1, 0) : new Point3D(1, 0, 0);
        Point3D u = normal.crossProduct(reference).normalize();
        Point3D v = normal.crossProduct(u).normalize();
        return new Point3D[]{u, v};
    }

    static double cot(double angleDeg) {
        return 1 / Math.tan(Math.toRadians(angleDeg));
    }

    static MeshView annulus(double z0,
                            double dipDeg,
                            double wellRadius,
                            double zMid,
                            double scale,
                            double innerRadius,
                            double outerRadius,
                            Color color,
                            double opacity) {

        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        Point3D normal = interfaceNormal(dipDeg);

        for (int i = 0; i < ANNULUS_SEGMENTS; i++) {
            double a0 = 360.0 * i / ANNULUS_SEGMENTS;
            double a1 = 360.0 * (i + 1) / ANNULUS_SEGMENTS;
            double zo0 = interfaceDepth(z0, dipDeg, outerRadius * wellRadius, a0);
            double zo1 = interfaceDepth(z0, dipDeg, outerRadius * wellRadius, a1);
            double zi0 = interfaceDepth(z0, dipDeg, innerRadius * wellRadius, a0);
            double zi1 = interfaceDepth(z0, dipDeg, innerRadius * wellRadius, a1);

            int base = points.size();
            points.add(worldPoint(innerRadius, a0, zi0, zMid, scale));
            points.add(worldPoint(outerRadius, a0, zo0, zMid, scale));
            points.add(worldPoint(innerRadius, a1, zi1, zMid, scale));
            points.add(worldPoint(outerRadius, a1, zo1, zMid, scale));
            for (int k = 0; k < 4; k++) {
                normals.add(normal);
                texCoords.add(new Point2D(k % 2, k / 2.0));
            }
            triangles.add(new int[]{base, base + 1, base + 2});
            triangles.add(new int[]{base + 2, base + 1, base + 3});
        }
        return meshView(points, normals, texCoords, triangles, color, opacity, 48);
    }

    static MeshView disc(double z0,
                         double elevationDeg,
                         double azimuthDeg,
                         double zMid,
                         double scale,
                         double radius,
                         Color color,
                         double opacity) {
        Point3D normal = discNormal(elevationDeg, azimuthDeg);
        Point3D center = new Point3D(0, (z0 - zMid) * scale, 0);
        Point3D[] basis = discBasis(normal);

        List<Point3D> points = new ArrayList<>();
        List<Point3D> normals = new ArrayList<>();
        List<Point2D> texCoords = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        points.add(center);
        normals.add(normal);
        texCoords.add(new Point2D(0.5, 0.5));

        for (int i = 0; i < DISC_SEGMENTS; i++) {
            double angle = Math.toRadians(360.0 * i / DISC_SEGMENTS);
            Point3D rim = center.add(basis[0].multiply(radius * Math.cos(angle)))
                    .add(basis[1].multiply(radius * Math.sin(angle)));
            points.add(rim);
            normals.add(normal);
            texCoords.add(new Point2D(0.5 + 0.5 * Math.cos(angle),
                    0.5 + 0.5 * Math.sin(angle)));
        }
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            triangles.add(new int[]{0, 1 + i, 1 + (i + 1) % DISC_SEGMENTS});
        }
        return meshView(points, normals, texCoords, triangles, color, opacity, 48);
    }
}
