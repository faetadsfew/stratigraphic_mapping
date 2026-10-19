package com.stratigraphic.mapping.wall;

/**
 * 从 1 号电极开始，电极编号沿圆周增加的方向。
 */
public enum UnfoldDirection {
    CLOCKWISE("顺时针"),
    COUNTERCLOCKWISE("逆时针");

    private final String label;

    UnfoldDirection(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
