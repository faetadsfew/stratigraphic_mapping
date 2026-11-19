package com.stratigraphic.mapping.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 球面与井壁圆柱面交线在展开图坐标中的纯几何计算。 */
final class SphereTrace {

    static final double STEP_DEG = 0.5;
    private static final double EPS = 1e-12;

    enum Kind {
        NONE,
        RING,
        FULL_BAND,
        TWO_LINES
    }

    record Point(double angleDeg, double depth) {
    }

    record Trace(Kind kind,
                 List<Point> upper,
                 List<Point> lower,
                 double thetaMinDeg,
                 double thetaMaxDeg) {

        boolean isEmpty() {
            return kind == Kind.NONE;
        }

        double minDepth() {
            return allPoints().stream().mapToDouble(Point::depth).min().orElse(Double.NaN);
        }

        double maxDepth() {
            return allPoints().stream().mapToDouble(Point::depth).max().orElse(Double.NaN);
        }

        List<Point> allPoints() {
            List<Point> points = new ArrayList<>(upper.size() + lower.size());
            points.addAll(upper);
            points.addAll(lower);
            return points;
        }
    }

    private SphereTrace() {
    }

    /** 返回闭合曲线点列；无法相交时返回空列表。 */
    static List<Point> points(double wellRadius,
                              double centerDepth,
                              double azimuthDeg,
                              double distanceFromAxis,
                              double sphereRadius) {
        Trace trace = of(wellRadius, centerDepth, azimuthDeg, distanceFromAxis, sphereRadius);
        if (trace.isEmpty()) {
            return List.of();
        }

        List<Point> points = new ArrayList<>(trace.upper());
        List<Point> lower = new ArrayList<>(trace.lower());
        Collections.reverse(lower);
        points.addAll(lower);
        return points;
    }

    static Trace of(double wellRadius,
                    double centerDepth,
                    double azimuthDeg,
                    double distanceFromAxis,
                    double sphereRadius) {

        require(wellRadius > 0 && Double.isFinite(wellRadius), "井眼半径必须大于 0");
        require(Double.isFinite(centerDepth), "球体中心深度必须是有效数字");
        require(Double.isFinite(azimuthDeg), "球体方位角必须是有效数字");
        require(distanceFromAxis >= 0 && Double.isFinite(distanceFromAxis),
                "球体距井轴水平距离必须是非负数");
        require(sphereRadius > 0 && Double.isFinite(sphereRadius), "球体半径必须大于 0");

        double phi0 = normalizeAzimuth(azimuthDeg);
        double r = wellRadius;
        double d = distanceFromAxis;
        double radius = sphereRadius;

        if (radius < Math.abs(r - d) - EPS) {
            return new Trace(Kind.NONE, List.of(), List.of(), Double.NaN, Double.NaN);
        }

        if (d < EPS) {
            double halfHeightSquared = radius * radius - r * r;
            if (halfHeightSquared < -EPS) {
                return new Trace(Kind.NONE, List.of(), List.of(), Double.NaN, Double.NaN);
            }
            double halfHeight = Math.sqrt(Math.max(0, halfHeightSquared));
            List<Point> upper = List.of(
                    new Point(0, centerDepth + halfHeight),
                    new Point(360, centerDepth + halfHeight));
            List<Point> lower = List.of(
                    new Point(0, centerDepth - halfHeight),
                    new Point(360, centerDepth - halfHeight));
            return new Trace(Kind.TWO_LINES, upper, lower, 0, 360);
        }

        double cosTheta = (r * r + d * d - radius * radius) / (2 * r * d);
        if (radius > r + d + EPS) {
            return fullBand(r, centerDepth, phi0, d, radius);
        }

        cosTheta = clamp(cosTheta, -1, 1);
        double thetaC = Math.toDegrees(Math.acos(cosTheta));
        double start = phi0 - thetaC;
        double end = phi0 + thetaC;

        List<Point> upper = sampleBranch(r, centerDepth, phi0, d, radius, start, end, true);
        List<Point> lower = sampleBranch(r, centerDepth, phi0, d, radius, start, end, false);
        return new Trace(Kind.RING, upper, lower, start, end);
    }

    private static Trace fullBand(double r,
                                  double centerDepth,
                                  double phi0,
                                  double d,
                                  double radius) {
        double start = phi0;
        double end = phi0 + 360;
        List<Point> upper = sampleBranch(r, centerDepth, phi0, d, radius, start, end, true);
        List<Point> lower = sampleBranch(r, centerDepth, phi0, d, radius, start, end, false);
        return new Trace(Kind.FULL_BAND, upper, lower, start, end);
    }

    private static List<Point> sampleBranch(double r,
                                            double centerDepth,
                                            double phi0,
                                            double d,
                                            double radius,
                                            double start,
                                            double end,
                                            boolean upper) {
        List<Point> points = new ArrayList<>();
        int steps = Math.max(1, (int) Math.ceil((end - start) / STEP_DEG));
        double sign = upper ? 1 : -1;

        for (int i = 0; i <= steps; i++) {
            double angle = start + (end - start) * i / steps;
            double f = radius * radius - r * r - d * d
                    + 2 * r * d * Math.cos(Math.toRadians(angle - phi0));
            double halfHeight = Math.sqrt(Math.max(0, f));
            points.add(new Point(angle, centerDepth + sign * halfHeight));
        }
        return points;
    }

    private static double normalizeAzimuth(double angle) {
        double result = angle % 360;
        return result < 0 ? result + 360 : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
