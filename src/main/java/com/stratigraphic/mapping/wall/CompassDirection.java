package com.stratigraphic.mapping.wall;

/**
 * 1 号电极的罗盘初始方向。向量定义为 X 正东、Y 正北、Z 向上。
 */
public enum CompassDirection {
    NORTH("正北", 0, 1, 0),
    EAST("正东", 1, 0, 0),
    SOUTH("正南", 0, -1, 0),
    WEST("正西", -1, 0, 0);

    private final String label;
    private final double x;
    private final double y;
    private final double z;

    CompassDirection(String label, double x, double y, double z) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String label() {
        return label;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    @Override
    public String toString() {
        return label;
    }
}
