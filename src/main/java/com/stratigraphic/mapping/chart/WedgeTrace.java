package com.stratigraphic.mapping.chart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 楔形体在井壁展开图上的纯几何计算。 */
final class WedgeTrace {

    record Point(double angleDeg, double depth) {
    }

    private WedgeTrace() {
    }

    static List<Point> polygonPoints(double vertexAzimuthDeg,
                                     double vertexDepth,
                                     double leftAzimuthDeg,
                                     double leftDepth,
                                     double rightAzimuthDeg,
                                     double rightDepth) {

        double vertexAngle = normalize(vertexAzimuthDeg);
        double leftAngle = normalize(leftAzimuthDeg);
        double rightAngle = normalize(rightAzimuthDeg);

        List<Double> sorted = new ArrayList<>(List.of(vertexAngle, leftAngle, rightAngle));
        sorted.sort(Comparator.naturalOrder());

        double largestGap = -1;
        double start = sorted.get(0);
        for (int i = 0; i < sorted.size(); i++) {
            double from = sorted.get(i);
            double to = sorted.get((i + 1) % sorted.size());
            double gap = i == sorted.size() - 1 ? to + 360 - from : to - from;
            if (gap > largestGap) {
                largestGap = gap;
                start = to;
            }
        }

        List<Point> points = List.of(
                new Point(unwrap(vertexAngle, start), vertexDepth),
                new Point(unwrap(leftAngle, start), leftDepth),
                new Point(unwrap(rightAngle, start), rightDepth));

        return area(points) <= 1e-9 ? List.of() : points;
    }

    private static double unwrap(double angle, double start) {
        return start + ((angle - start + 360) % 360);
    }

    private static double normalize(double angle) {
        double result = angle % 360;
        return result < 0 ? result + 360 : result;
    }

    private static double area(List<Point> points) {
        double sum = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % points.size());
            sum += a.angleDeg() * b.depth() - b.angleDeg() * a.depth();
        }
        return Math.abs(sum) / 2;
    }
}
