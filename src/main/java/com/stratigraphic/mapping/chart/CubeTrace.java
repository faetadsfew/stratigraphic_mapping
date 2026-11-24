package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.CubeAnomaly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 正方体异常体与井壁圆柱面的交线区域。
 *
 * <p>正方体的一条水平轴沿径向、另一条沿切向，因此交线在展开图上正好是
 * 若干“方位角区间 × 深度区间”的矩形区域。这里只做纯几何计算，不涉及绘制。
 */
final class CubeTrace {

    private static final double EPS = 1e-9;

    /** 一段方位角区间；允许跨过 0°（此时 end 可能大于 360°）。 */
    record Span(double startDeg, double endDeg) {

        double lengthDeg() {
            return endDeg - startDeg;
        }
    }

    record Trace(double depthMin, double depthMax, List<Span> spans) {

        boolean isEmpty() {
            return spans.isEmpty() || depthMax <= depthMin;
        }

        boolean contains(double angleDeg, double depth) {
            if (depth < depthMin - EPS || depth > depthMax + EPS) {
                return false;
            }
            double angle = normalize(angleDeg);
            for (Span span : spans) {
                for (int turn = -1; turn <= 1; turn++) {
                    double candidate = angle + turn * 360.0;
                    if (candidate >= span.startDeg() - EPS
                            && candidate <= span.endDeg() + EPS) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private CubeTrace() {
    }

    static Trace of(double wellRadius, CubeAnomaly cube) {

        require(wellRadius > 0 && Double.isFinite(wellRadius), "井眼半径必须大于 0");
        require(cube != null, "正方体异常体不能为空");

        double r = wellRadius;
        double d = cube.distanceFromAxis();
        double h = cube.halfSide();
        double phi = cube.normalizedAzimuthDeg();
        double depthMin = cube.centerDepth() - h;
        double depthMax = cube.centerDepth() + h;

        // 切向约束 |r*sin(theta-phi)| <= h
        List<Span> tangential;
        if (h >= r - EPS) {
            tangential = List.of(new Span(0, 360));
        } else {
            double halfWidth = Math.toDegrees(Math.asin(h / r));
            tangential = List.of(
                    new Span(phi - halfWidth, phi + halfWidth),
                    new Span(phi + 180 - halfWidth, phi + 180 + halfWidth));
        }

        // 径向约束 |r*cos(theta-phi) - d| <= h，等价于 cos(theta-phi) 落在 [c0, c1]
        double c0 = (d - h) / r;
        double c1 = (d + h) / r;
        if (c1 < -1 - EPS || c0 > 1 + EPS) {
            return new Trace(depthMin, depthMax, List.of());
        }
        double alpha0 = Math.toDegrees(Math.acos(clamp(c1, -1, 1)));
        double alpha1 = Math.toDegrees(Math.acos(clamp(c0, -1, 1)));
        List<Span> radial = List.of(
                new Span(phi - alpha1, phi - alpha0),
                new Span(phi + alpha0, phi + alpha1));

        List<Span> spans = intersect(normalizeSpans(radial), normalizeSpans(tangential));
        return new Trace(depthMin, depthMax, spans);
    }

    private static List<Span> intersect(List<Span> first, List<Span> second) {

        List<Span> a = splitAtSeam(first);
        List<Span> b = splitAtSeam(second);
        List<Span> result = new ArrayList<>();
        for (Span x : a) {
            for (Span y : b) {
                double start = Math.max(x.startDeg(), y.startDeg());
                double end = Math.min(x.endDeg(), y.endDeg());
                if (end > start + EPS) {
                    result.add(new Span(start, end));
                }
            }
        }
        return mergeAndWrap(result);
    }

    private static List<Span> splitAtSeam(List<Span> spans) {
        List<Span> result = new ArrayList<>();
        for (Span span : spans) {
            if (span.startDeg() < -EPS || span.endDeg() > 360 + EPS
                    || span.endDeg() < span.startDeg()) {
                double start = normalize(span.startDeg());
                double end = normalize(span.endDeg());
                if (end <= EPS && start > EPS) {
                    end += 360;
                }
                if (end > start) {
                    result.add(new Span(start, Math.min(360, end)));
                    if (end > 360 + EPS) {
                        result.add(new Span(0, end - 360));
                    }
                } else if (start > end) {
                    result.add(new Span(start, 360));
                    result.add(new Span(0, end));
                }
            } else {
                result.add(span);
            }
        }
        return result;
    }

    private static List<Span> normalizeSpans(List<Span> raw) {

        List<Span> plain = new ArrayList<>();
        for (Span span : raw) {
            double length = span.lengthDeg();
            if (length <= EPS) {
                continue;
            }
            if (length >= 360 - EPS) {
                return List.of(new Span(0, 360));
            }
            double start = normalize(span.startDeg());
            double end = start + length;
            if (end > 360 + EPS) {
                plain.add(new Span(start, 360));
                plain.add(new Span(0, end - 360));
            } else {
                plain.add(new Span(start, end));
            }
        }
        return mergeAndWrap(plain);
    }

    private static List<Span> mergeAndWrap(List<Span> spans) {

        if (spans.isEmpty()) {
            return List.of();
        }
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingDouble(Span::startDeg));

        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            Span last = merged.get(merged.size() - 1);
            if (span.startDeg() <= last.endDeg() + EPS) {
                merged.set(merged.size() - 1,
                        new Span(last.startDeg(), Math.max(last.endDeg(), span.endDeg())));
            } else {
                merged.add(span);
            }
        }

        if (merged.size() > 1) {
            Span first = merged.get(0);
            Span last = merged.get(merged.size() - 1);
            if (first.startDeg() <= EPS && last.endDeg() >= 360 - EPS) {
                merged.remove(merged.size() - 1);
                merged.remove(0);
                merged.add(new Span(last.startDeg(), first.endDeg() + 360));
            }
        }
        return List.copyOf(merged);
    }

    private static double normalize(double angle) {
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
