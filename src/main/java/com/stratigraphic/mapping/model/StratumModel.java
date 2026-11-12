package com.stratigraphic.mapping.model;

import java.util.List;

/**
 * 地层剖面模型：井眼参数 + 统一倾角 + n 条界面 + n+1 层电阻率 + 裂缝。
 *
 * <p>界面把井轴切成 n + 1 段，所以电阻率的个数恒为 n + 1。
 * 电阻率列表按深度由浅到深排列，第 i 层位于第 i 条界面之上。
 */
public final class StratumModel {

    /** 界面个数上限：界面太多会让绘制明显变慢，也防止误输入。 */
    public static final int MAX_INTERFACES = 60;

    private final double wellRadius;
    private final double wellResistivity;
    private final double dipAngle;
    private final List<StratumInterface> interfaces;
    private final List<Double> resistivities;
    private final List<Fracture> fractures;

    public StratumModel(double wellRadius,
                        double wellResistivity,
                        double dipAngle,
                        List<StratumInterface> interfaces,
                        List<Double> resistivities) {
        this(wellRadius, wellResistivity, dipAngle,
                interfaces, resistivities, List.of());
    }

    public StratumModel(double wellRadius,
                        double wellResistivity,
                        double dipAngle,
                        List<StratumInterface> interfaces,
                        List<Double> resistivities,
                        List<Fracture> fractures) {

        check(wellRadius > 0 && Double.isFinite(wellRadius),
                "井眼半径必须是大于 0 的数");

        check(wellResistivity > 0 && Double.isFinite(wellResistivity),
                "井眼电阻率必须是大于 0 的数");

        check(Double.isFinite(dipAngle) && dipAngle > 0 && dipAngle < 180,
                "统一倾角必须在 0° 与 180° 之间");

        check(!interfaces.isEmpty(),
                "至少需要 1 条界面");

        check(interfaces.size() <= MAX_INTERFACES,
                "界面最多 " + MAX_INTERFACES + " 条，实际给了 " + interfaces.size() + " 条");

        for (int i = 0; i < interfaces.size(); i++) {
            StratumInterface s = interfaces.get(i);
            check(Double.isFinite(s.depth()),
                    "第 " + (i + 1) + " 条界面的交点深度不是有效数字");
        }

        check(resistivities.size() == interfaces.size() + 1,
                "层电阻率应有 " + (interfaces.size() + 1) + " 个，实际给了 "
                        + resistivities.size() + " 个");

        for (int i = 0; i < resistivities.size(); i++) {
            double rho = resistivities.get(i);
            check(rho > 0 && Double.isFinite(rho),
                    "第 " + (i + 1) + " 层电阻率必须是大于 0 的数");
        }

        this.wellRadius = wellRadius;
        this.wellResistivity = wellResistivity;
        this.dipAngle = dipAngle;
        this.interfaces = List.copyOf(interfaces);
        this.resistivities = List.copyOf(resistivities);
        this.fractures = List.copyOf(fractures);
    }

    /** 兼容旧调用：未给出统一倾角时按水平界面处理。 */
    public StratumModel(double wellRadius,
                        double wellResistivity,
                        List<StratumInterface> interfaces,
                        List<Double> resistivities) {
        this(wellRadius, wellResistivity, 90, interfaces, resistivities);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 井眼半径 (m)。 */
    public double wellRadius() {
        return wellRadius;
    }

    /** 井眼电阻率 (Ω·m)。 */
    public double wellResistivity() {
        return wellResistivity;
    }

    /** 全部地层界面共用的倾角 (°)；90° 表示水平。 */
    public double dipAngle() {
        return dipAngle;
    }

    /** 界面列表，顺序与输入一致。 */
    public List<StratumInterface> interfaces() {
        return interfaces;
    }

    /** 各层电阻率 (Ω·m)，长度 = 界面数 + 1。 */
    public List<Double> resistivities() {
        return resistivities;
    }

    /** 裂缝列表。 */
    public List<Fracture> fractures() {
        return fractures;
    }

    /** 界面个数 n。 */
    public int interfaceCount() {
        return interfaces.size();
    }

    /** 层数，恒为 n + 1。 */
    public int layerCount() {
        return resistivities.size();
    }

    public double resistivityOf(int layer) {
        return resistivities.get(layer);
    }

    /** 最浅界面的深度 (m)。 */
    public double minDepth() {
        return interfaces.stream().mapToDouble(StratumInterface::depth).min().orElse(0);
    }

    /** 最深界面的深度 (m)。 */
    public double maxDepth() {
        return interfaces.stream().mapToDouble(StratumInterface::depth).max().orElse(0);
    }

    /** 全部电阻率里的最大值（含井眼、地层和裂缝），用来定统一色标范围。 */
    public double maxResistivity() {
        return Math.max(wellResistivity,
                Math.max(
                        resistivities.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                        fractures.stream().mapToDouble(Fracture::resistivity).max().orElse(0)));
    }

    /** 全部电阻率里的最小值（含井眼、地层和裂缝），用作统一对数色标的下界。 */
    public double minResistivity() {
        return Math.min(wellResistivity,
                Math.min(
                        resistivities.stream().mapToDouble(Double::doubleValue)
                                .min().orElse(wellResistivity),
                        fractures.stream().mapToDouble(Fracture::resistivity)
                                .min().orElse(wellResistivity)));
    }
}
