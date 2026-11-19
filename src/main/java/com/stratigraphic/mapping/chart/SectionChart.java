package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

import java.util.function.IntConsumer;

/**
 * 两张剖面图的公共部分：图面几何、图框、深度/水平距离刻度、图上文字。
 *
 * <p>子类只负责各自的内容（线条、色块……），构造时给出上边留白和下边留白。
 * 电阻率色标不在这里画——它是 {@code ColorBarPane}，挂在 FitPane 右侧的固定列里。
 */
public abstract class SectionChart extends Pane {

    /** 图面左边的留白，放深度刻度。 */
    static final double MARGIN_LEFT = 46;

    /** 图面右边的留白。 */
    static final double MARGIN_RIGHT = 26;

    final StratumModel model;
    final SectionGeometry geo;

    SectionChart(StratumModel model, double titleHeight, double marginBottom) {

        this.model = model;
        this.geo = new SectionGeometry(model, MARGIN_LEFT, titleHeight);

        setPrefSize(
                MARGIN_LEFT + geo.figureWidth() + MARGIN_RIGHT,
                titleHeight + geo.figureHeight() + marginBottom);

        getStyleClass().add("chart-canvas");

        // 两张图都支持点界面线看数据
        setOnMouseClicked(this::handleClick);
    }

    /** 图面上沿在内容坐标里的 y（也就是上留白）。 */
    public double figureTop() {
        return geo.figureTop();
    }

    /** 图面高度（像素）。 */
    public double figureHeight() {
        return geo.figureHeight();
    }

    /** 设置裂缝点击回调；参数是裂缝在模型裂缝列表中的序号。 */
    public final void setOnFractureSelected(IntConsumer action) {
        this.onFractureSelected = action == null ? index -> {
        } : action;
    }

    /** 设置界面点击回调；参数是界面在模型界面列表中的序号。 */
    public final void setOnInterfaceSelected(IntConsumer action) {
        this.onInterfaceSelected = action == null ? index -> {
        } : action;
    }

    /** 设置地层色块点击回调；参数是地层在模型中的层号。 */
    public final void setOnLayerSelected(IntConsumer action) {
        this.onLayerSelected = action == null ? index -> {
        } : action;
    }

    // ---------------- 点击图面看数据 ----------------

    /** 点到界面线的容差（图面坐标像素）。 */
    private static final double HIT_TOLERANCE = 8;

    /** 点中的那条界面线上面再压一条黑色粗线，一眼看出点的是哪条。 */
    private static final Color HIGHLIGHT_COLOR = Color.BLACK;
    private static final double HIGHLIGHT_WIDTH = 3;

    private final Line highlight = new Line();

    /** 点击裂缝时通知外部（例如左侧参数面板定位并高亮裂缝参数）。 */
    private IntConsumer onFractureSelected = index -> {
    };

    private IntConsumer onInterfaceSelected = index -> {
    };

    private IntConsumer onLayerSelected = index -> {
    };

    /** 点图面：识别裂缝、界面或地层，并通知外部高亮对应参数。 */
    private void handleClick(MouseEvent e) {

        ensureHighlight();

        double x = e.getX();
        double y = e.getY();

        boolean inside = geo.insideFigure(x, y);
        int fracture = inside ? fractureAt(x, y) : -1;

        if (fracture >= 0) {
            highlightInterface(-1);
            onFractureSelected.accept(fracture);
        } else {
            int hit = inside ? geo.interfaceAt(x, y, HIT_TOLERANCE) : -1;
            highlightInterface(hit);
            if (hit >= 0) {
                onInterfaceSelected.accept(hit);
            } else if (inside) {
                if (Math.abs(x - geo.axisX()) > geo.wellRadiusPx()) {
                    onLayerSelected.accept(geo.layerAt(x, y));
                }
            }
        }
    }

    /** 高亮线第一次使用时挂到图面上。 */
    private void ensureHighlight() {

        if (highlight.getParent() == null) {
            highlight.setStroke(HIGHLIGHT_COLOR);
            highlight.setStrokeWidth(HIGHLIGHT_WIDTH);
            highlight.setMouseTransparent(true);
            highlight.setVisible(false);
            getChildren().add(highlight);
        }
    }

    /** 高亮第 index 条界面线；-1 表示取消高亮。 */
    private void highlightInterface(int index) {

        if (index < 0) {
            highlight.setVisible(false);
            return;
        }

        StratumInterface s = model.interfaces().get(index);
        Line line = geo.createLine(geo.axisX(), geo.zToY(s.depth()), model.dipAngle());

        highlight.setStartX(line.getStartX());
        highlight.setStartY(line.getStartY());
        highlight.setEndX(line.getEndX());
        highlight.setEndY(line.getEndY());
        highlight.setVisible(true);
    }

    /** 返回点击位置命中的裂缝序号；没有命中返回 -1。子类按自己的裂缝几何实现。 */
    protected int fractureAt(double x, double y) {
        return -1;
    }

    // ---------------- 图框 ----------------

    final void drawFrame() {

        Rectangle frame = new Rectangle(
                geo.figureLeft(), geo.figureTop(),
                geo.figureWidth(), geo.figureHeight());

        frame.setFill(null);
        frame.setStroke(SectionStyle.FRAME);
        frame.setStrokeWidth(1.5);

        getChildren().add(frame);
    }

    // ---------------- 边框刻度 ----------------

    /** 左侧深度刻度 + 底部水平距离刻度，以井轴为 0。 */
    final void drawAxes() {

        drawDepthTicks();
        drawOffsetTicks();
    }

    private void drawDepthTicks() {

        double step = SectionStyle.niceStep((geo.zBottom() - geo.zTop()) / 10);
        int decimals = SectionStyle.decimalsFor(step);

        int first = (int) Math.ceil(geo.zTop() / step - 1e-9);
        int last = (int) Math.floor(geo.zBottom() / step + 1e-9);

        for (int k = first; k <= last; k++) {

            double z = k * step;
            double y = geo.zToY(z);

            Line tick = new Line(geo.figureLeft() - 6, y, geo.figureLeft(), y);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            getChildren().add(tick);

            getChildren().add(
                    label(SectionStyle.formatTick(z, decimals),
                            geo.figureLeft() - 40, y - 7, SectionStyle.muted(10)));
        }

        getChildren().add(
                label("深度 / m", geo.figureLeft() - 44, geo.figureTop() - 20,
                        SectionStyle.muted(10)));
    }

    private void drawOffsetTicks() {

        double left = geo.leftOffsetM();
        double right = geo.rightOffsetM();

        double step = SectionStyle.niceStep((right - left) / 6);
        int decimals = SectionStyle.decimalsFor(step);

        int first = (int) Math.ceil(left / step - 1e-9);
        int last = (int) Math.floor(right / step + 1e-9);

        double bottom = geo.figureBottom();

        for (int k = first; k <= last; k++) {

            double offset = k * step;
            double x = geo.axisX() + offset * SectionGeometry.PX_PER_M;

            Line tick = new Line(x, bottom, x, bottom + 6);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            getChildren().add(tick);

            getChildren().add(
                    label(SectionStyle.formatTick(offset, decimals),
                            x - 11, bottom + 8, SectionStyle.muted(10)));
        }

        getChildren().add(
                label("水平距离 / m", geo.axisX() - 34, bottom + 26,
                        SectionStyle.muted(10)));
    }

    // ---------------- 小工具 ----------------

    /** 图上的一行文字，按图面坐标摆放。 */
    static Label label(String text, double x, double y, String style) {

        Label label = new Label(text);
        label.setLayoutX(x);
        label.setLayoutY(y);
        label.setStyle(style);
        return label;
    }
}
