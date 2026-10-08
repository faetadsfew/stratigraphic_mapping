package com.stratigraphic.mapping.model;

/**
 * 一条地层界面。
 *
 * <p>界面用两个量描述：它与井轴的交点深度，以及它与井轴竖直向上方向的夹角。
 * 夹角 θ 取 (0, 180)：θ = 90° 表示界面水平，θ &lt; 90° 表示界面向右上方倾斜，
 * θ &gt; 90° 表示界面向左上方倾斜。
 *
 * @param depth 界面与井轴的交点深度 (m)
 * @param angle 界面与井轴向上方向的夹角 (°)
 */
public record StratumInterface(double depth, double angle) {
}
