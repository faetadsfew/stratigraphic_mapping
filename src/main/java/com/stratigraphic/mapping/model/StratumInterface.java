package com.stratigraphic.mapping.model;

/**
 * 一条地层界面。
 *
 * <p>界面只记录它与井轴的交点深度。所有界面共用
 * {@link StratumModel#dipAngle()} 给出的统一倾角。
 *
 * @param depth 界面与井轴的交点深度 (m)
 */
public record StratumInterface(double depth) {
}
