package com.stratigraphic.mapping.wall;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 井壁电性数据。行按测量深度 MD 从浅到深排列，列为 1～192 号电极。
 */
public final class WallData {

    public static final int ELECTRODE_COUNT = 192;

    private final Path source;
    private final double[] depths;
    private final double[][] amplitude;
    private final double[][] phase;
    private final double sampleInterval;
    private final double amplitudeMin;
    private final double amplitudeMax;
    private final double phaseMin;
    private final double phaseMax;

    public WallData(Path source,
                    double[] depths,
                    double[][] amplitude,
                    double[][] phase) {

        if (source == null) {
            throw new IllegalArgumentException("数据文件路径不能为空");
        }
        if (depths == null || depths.length < 2) {
            throw new IllegalArgumentException("至少需要两行深度数据");
        }
        if (amplitude == null || phase == null
                || amplitude.length != depths.length
                || phase.length != depths.length) {
            throw new IllegalArgumentException("模值、相位与深度的行数不一致");
        }

        this.source = source;
        this.depths = depths.clone();
        this.amplitude = copyMatrix(amplitude);
        this.phase = copyMatrix(phase);

        for (int row = 0; row < depths.length; row++) {
            if (!Double.isFinite(depths[row])) {
                throw new IllegalArgumentException("第 " + (row + 1) + " 行深度不是有效数字");
            }
            if (row > 0 && !(depths[row] > depths[row - 1])) {
                throw new IllegalArgumentException("深度必须从浅到深严格递增，第 "
                        + (row + 1) + " 行顺序异常");
            }
            if (this.amplitude[row].length != ELECTRODE_COUNT
                    || this.phase[row].length != ELECTRODE_COUNT) {
                throw new IllegalArgumentException("第 " + (row + 1)
                        + " 行不是完整的 192 电极数据");
            }
        }

        this.sampleInterval = estimateSampleInterval(this.depths);
        this.amplitudeMin = min(this.amplitude, "模值");
        this.amplitudeMax = max(this.amplitude, "模值");
        this.phaseMin = min(this.phase, "相位");
        this.phaseMax = max(this.phase, "相位");

        if (!(amplitudeMin > 0)) {
            throw new IllegalArgumentException("模值必须全部大于 0，才能使用对数颜色映射");
        }
        if (phaseMax > 0) {
            throw new IllegalArgumentException(
                    "相位数据必须为负值，检测到最大相位 " + phaseMax);
        }
    }

    private static double[][] copyMatrix(double[][] values) {

        double[][] copy = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行数据为空");
            }
            copy[i] = values[i].clone();
        }
        return copy;
    }

    private static double estimateSampleInterval(double[] depths) {

        double[] intervals = new double[depths.length - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = depths[i + 1] - depths[i];
            if (!(intervals[i] > 0) || !Double.isFinite(intervals[i])) {
                throw new IllegalArgumentException("第 " + (i + 2) + " 行深度采样间隔无效");
            }
        }

        double[] sorted = intervals.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        for (double interval : intervals) {
            double tolerance = Math.max(1e-7, Math.abs(median) * 0.01);
            if (Math.abs(interval - median) > tolerance) {
                throw new IllegalArgumentException(String.format(
                        "深度采样间隔不一致：中位间隔 %.8f m，发现 %.8f m",
                        median, interval));
            }
        }
        return median;
    }

    private static double min(double[][] values, String name) {

        double result = Double.POSITIVE_INFINITY;
        for (double[] row : values) {
            for (double value : row) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(name + "包含空白或非数字");
                }
                result = Math.min(result, value);
            }
        }
        return result;
    }

    private static double max(double[][] values, String name) {

        double result = Double.NEGATIVE_INFINITY;
        for (double[] row : values) {
            for (double value : row) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(name + "包含空白或非数字");
                }
                result = Math.max(result, value);
            }
        }
        return result;
    }

    public Path source() {
        return source;
    }

    public int rowCount() {
        return depths.length;
    }

    public double minDepth() {
        return depths[0];
    }

    public double maxDepth() {
        return depths[depths.length - 1];
    }

    public double sampleInterval() {
        return sampleInterval;
    }

    public double amplitudeMin() {
        return amplitudeMin;
    }

    public double amplitudeMax() {
        return amplitudeMax;
    }

    public double phaseMin() {
        return phaseMin;
    }

    public double phaseMax() {
        return phaseMax;
    }

    public double depthAt(int row) {
        return depths[row];
    }

    /** 按 MD 线性插值读取模值。 */
    public double amplitudeAt(int electrodeIndex, double md) {
        return interpolate(amplitude, electrodeIndex, md);
    }

    /** 按 MD 线性插值读取相位。 */
    public double phaseAt(int electrodeIndex, double md) {
        return interpolate(phase, electrodeIndex, md);
    }

    private double interpolate(double[][] values, int electrodeIndex, double md) {

        if (electrodeIndex < 0 || electrodeIndex >= ELECTRODE_COUNT) {
            throw new IllegalArgumentException("电极编号超出 1～192 范围");
        }
        if (md < minDepth() - 1e-9 || md > maxDepth() + 1e-9) {
            throw new IllegalArgumentException(String.format(
                    "查询深度 %.8f m 超出 Excel 有效范围 %.8f～%.8f m",
                    md, minDepth(), maxDepth()));
        }

        int found = Arrays.binarySearch(depths, md);
        if (found >= 0) {
            return values[found][electrodeIndex];
        }

        int insertion = -found - 1;
        if (insertion <= 0) {
            return values[0][electrodeIndex];
        }
        if (insertion >= depths.length) {
            return values[depths.length - 1][electrodeIndex];
        }

        int lower = insertion - 1;
        int upper = insertion;
        double span = depths[upper] - depths[lower];
        double t = (md - depths[lower]) / span;

        return values[lower][electrodeIndex] * (1 - t)
                + values[upper][electrodeIndex] * t;
    }
}
