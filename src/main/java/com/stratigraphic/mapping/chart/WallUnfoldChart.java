package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.wall.WallGeometry;
import com.stratigraphic.mapping.wall.WallData;
import javafx.geometry.Point3D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 井壁模值展开图。每个像素只映射到一个真实电极带和一个水平截面高度，
 * 再由几何模型反算 MD 并从 Excel 线性插值取值。
 */
public final class WallUnfoldChart extends Pane {

    static final double MARGIN_LEFT = 82;
    static final double MARGIN_TOP = 48;
    private static final double MARGIN_RIGHT = 166;
    private static final double MARGIN_BOTTOM = 70;

    static final int DATA_WIDTH = 1000;
    static final int DATA_HEIGHT = 530;
    private static final double SEPARATOR_LINE_WIDTH = 1.6;
    private static final double SEPARATOR_HIT_HALF_WIDTH_PX = 2.0;

    private static final double LEGEND_X = MARGIN_LEFT + DATA_WIDTH + 58;
    private static final double LEGEND_Y = MARGIN_TOP + 86;
    private static final double LEGEND_WIDTH = 22;
    private static final double LEGEND_HEIGHT = 330;

    private final WallGeometry geometry;
    private final WallAmplitudeScale scale;
    private final ImageView imageView = new ImageView();
    private final Canvas overlay = new Canvas(chartWidth(), chartHeight());
    private final ChartPopup popup = new ChartPopup();

    private Consumer<String> onDataSelected = message -> {
    };

    public WallUnfoldChart(WallGeometry geometry, WallAmplitudeScale scale) {

        this.geometry = geometry;
        this.scale = scale;

        setPrefSize(chartWidth(), chartHeight());
        setMinSize(chartWidth(), chartHeight());
        setMaxSize(chartWidth(), chartHeight());
        getStyleClass().add("chart-canvas");

        imageView.setImage(renderDataImage());
        imageView.setLayoutX(MARGIN_LEFT);
        imageView.setLayoutY(MARGIN_TOP);
        imageView.setMouseTransparent(true);

        overlay.setMouseTransparent(true);
        drawOverlay();

        getChildren().addAll(imageView, overlay);
        setOnMouseClicked(this::handleClick);
    }

    public void setOnDataSelected(Consumer<String> listener) {
        this.onDataSelected = listener == null ? message -> {
        } : listener;
    }

    private WritableImage renderDataImage() {

        WritableImage image = new WritableImage(DATA_WIDTH, DATA_HEIGHT);
        PixelWriter writer = image.getPixelWriter();

        double perimeter = geometry.ellipsePerimeter();
        double zTop = geometry.zTop();
        double zBottom = geometry.zBottom();
        double minMd = geometry.requiredMinMd();
        double mdSpan = geometry.requiredMdSpan();

        // 横坐标是井壁展开弧长，纵坐标是沿井轴的 MD。
        // 对每个像素先判断它是否位于上下水平截面之间的倾斜圆柱面上，
        // 因此斜井的有效颜色区域会按两条正弦/余弦曲线裁剪。
        for (int x = 0; x < DATA_WIDTH; x++) {

            double arc = (x + 0.5) / DATA_WIDTH * perimeter;
            int electrode = electrodeAtArc(arc);
            double topMd = geometry.mdBoundaryAtArc(arc, zTop);
            double bottomMd = geometry.mdBoundaryAtArc(arc, zBottom);

            for (int y = 0; y < DATA_HEIGHT; y++) {

                double fraction = DATA_HEIGHT == 1
                        ? 0
                        : y / (double) (DATA_HEIGHT - 1);
                double md = minMd + mdSpan * fraction;
                if (md < topMd - 1e-12 || md > bottomMd + 1e-12) {
                    continue;
                }

                double amplitude = geometry.data().amplitudeAt(electrode, md);
                writer.setArgb(x, y, colorToArgb(scale.colorOf(amplitude)));
            }
        }

        return image;
    }

    private void drawOverlay() {

        GraphicsContext gc = overlay.getGraphicsContext2D();
        double plotRight = MARGIN_LEFT + DATA_WIDTH;
        double plotBottom = MARGIN_TOP + DATA_HEIGHT;

        gc.setStroke(SectionStyle.FRAME);
        gc.setLineWidth(1.4);
        gc.strokeLine(MARGIN_LEFT, MARGIN_TOP, MARGIN_LEFT, plotBottom);
        gc.strokeLine(MARGIN_LEFT, plotBottom, plotRight, plotBottom);
        drawSectionBoundary(gc, geometry.zTop());
        drawSectionBoundary(gc, geometry.zBottom());
        drawPlateBoundaries(gc, plotBottom);

        drawDepthAxis(gc, plotBottom);
        drawPlateAxis(gc, plotBottom);
        drawLegend(gc);
        drawHeader(gc, plotRight);
    }

    private void drawPlateBoundaries(GraphicsContext gc, double plotBottom) {

        gc.setStroke(Color.BLACK);
        gc.setLineWidth(SEPARATOR_LINE_WIDTH);
        for (double boundary : geometry.plateBoundaryArcs()) {
            double x = arcToX(boundary);
            gc.strokeLine(x, MARGIN_TOP, x, plotBottom);
        }
    }

    private void drawDepthAxis(GraphicsContext gc, double plotBottom) {

        double minMd = geometry.requiredMinMd();
        double maxMd = geometry.requiredMaxMd();
        double step = SectionStyle.niceStep((maxMd - minMd) / 8);
        int decimals = Math.max(3, SectionStyle.decimalsFor(step));

        gc.setFont(Font.font("Microsoft YaHei UI", 10));
        gc.setTextAlign(TextAlignment.RIGHT);

        double firstMd = Math.ceil(minMd / step) * step;
        for (double md = firstMd; md <= maxMd + step * 1e-9; md += step) {

            double y = mdToY(md);

            gc.setStroke(SectionStyle.TICK);
            gc.strokeLine(MARGIN_LEFT - 6, y, MARGIN_LEFT, y);
            gc.setFill(SectionStyle.TICK);
            gc.fillText(String.format(Locale.ROOT, "%." + decimals + "f", md),
                    MARGIN_LEFT - 9, y + 3.5);
        }

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(SectionStyle.FRAME);
        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 10));
        gc.fillText("MD / m", 8, MARGIN_TOP + 16);
        gc.setFont(Font.font("Microsoft YaHei UI", 9));
        gc.setFill(SectionStyle.TICK);
        gc.fillText(String.format(Locale.ROOT, "实际数据 %.5f～%.5f m",
                        geometry.requiredMinMd(), geometry.requiredMaxMd()),
                8, plotBottom + 42);
    }

    private void drawPlateAxis(GraphicsContext gc, double plotBottom) {

        gc.setFont(Font.font("Microsoft YaHei UI", 9.5));
        gc.setFill(SectionStyle.TICK);
        gc.setTextAlign(TextAlignment.CENTER);

        for (int plate = 0; plate < WallGeometry.PLATE_COUNT; plate++) {
            double x = arcToX(geometry.plateCenterArc(plate));
            int first = plate * WallGeometry.CHANNELS_PER_PLATE + 1;
            int last = first + WallGeometry.CHANNELS_PER_PLATE - 1;

            gc.strokeLine(x, plotBottom, x, plotBottom + 6);
            gc.fillText(first + "～" + last, x, plotBottom + 21);
        }

        gc.setFill(SectionStyle.FRAME);
        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 11));
        gc.fillText("水平椭圆展开弧长（8 个极板区）",
                MARGIN_LEFT + DATA_WIDTH / 2.0, plotBottom + 48);
    }

    private void drawLegend(GraphicsContext gc) {

        int steps = 160;
        for (int i = 0; i < steps; i++) {
            double t = 1 - i / (double) (steps - 1);
            double value = Math.pow(10,
                    Math.log10(scale.min())
                            + t * (Math.log10(scale.max()) - Math.log10(scale.min())));
            gc.setFill(scale.colorOf(value));
            gc.fillRect(LEGEND_X, LEGEND_Y + i * LEGEND_HEIGHT / steps,
                    LEGEND_WIDTH, LEGEND_HEIGHT / steps + 1);
        }

        gc.setStroke(SectionStyle.COLOR_BAR_BORDER);
        gc.setLineWidth(0.8);
        gc.strokeRect(LEGEND_X, LEGEND_Y, LEGEND_WIDTH, LEGEND_HEIGHT);

        gc.setFill(SectionStyle.FRAME);
        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("模值 / Ω·m", LEGEND_X - 4, LEGEND_Y - 12);

        gc.setFill(SectionStyle.TICK);
        gc.setFont(Font.font("Microsoft YaHei UI", 9));

        for (int i = 0; i <= 5; i++) {
            double t = i / 5.0;
            double value = Math.pow(10,
                    Math.log10(scale.min())
                            + t * (Math.log10(scale.max()) - Math.log10(scale.min())));
            double y = LEGEND_Y + LEGEND_HEIGHT * (1 - t);

            gc.strokeLine(LEGEND_X + LEGEND_WIDTH, y,
                    LEGEND_X + LEGEND_WIDTH + 5, y);
            gc.fillText(formatLogValue(value), LEGEND_X + LEGEND_WIDTH + 9, y + 3.5);
        }
    }

    private void drawHeader(GraphicsContext gc, double plotRight) {

        gc.setFill(SectionStyle.FRAME);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 12));
        gc.fillText(String.format(Locale.ROOT,
                        "井斜 %.2f°  ·  1号电极 %s  ·  %s展开",
                        geometry.parameters().inclinationDeg(),
                        geometry.parameters().firstElectrodeDirection(),
                        geometry.parameters().unfoldDirection()),
                MARGIN_LEFT, 24);

        gc.setFill(SectionStyle.TICK);
        gc.setFont(Font.font("Microsoft YaHei UI", 9));
        gc.fillText("上下边界为两个水平截面与倾斜井壁的真实交线",
                MARGIN_LEFT, 40);
    }

    private void drawSectionBoundary(GraphicsContext gc, double z) {

        gc.setStroke(SectionStyle.FRAME);
        gc.setLineWidth(1.1);
        gc.beginPath();

        for (int electrode = 0;
             electrode < WallData.ELECTRODE_COUNT;
             electrode++) {
            double x = arcToX(geometry.electrodeArc(electrode));
            double y = mdToY(geometry.mdAtElectrodeAndZ(electrode, z));
            if (electrode == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }

        double firstY = mdToY(geometry.mdAtElectrodeAndZ(0, z));
        gc.lineTo(MARGIN_LEFT + DATA_WIDTH, firstY);
        gc.stroke();
    }

    private void handleClick(MouseEvent event) {

        if (popup.getParent() == null) {
            getChildren().add(popup);
        }

        double x = event.getX();
        double y = event.getY();
        double plotRight = MARGIN_LEFT + DATA_WIDTH;
        double plotBottom = MARGIN_TOP + DATA_HEIGHT;

        if (x < MARGIN_LEFT || x > plotRight || y < MARGIN_TOP || y > plotBottom) {
            popup.hide();
            return;
        }

        double arc = (x - MARGIN_LEFT) / DATA_WIDTH * geometry.ellipsePerimeter();
        if (isSeparator(arc)) {
            String message = "该位置不是有效测量区域。";
            popup.show(message, x, y, chartWidth(), chartHeight());
            onDataSelected.accept(message);
            return;
        }

        int electrode = electrodeAtArc(arc);
        double fraction = (y - MARGIN_TOP) / DATA_HEIGHT;
        double md = geometry.requiredMinMd()
                + geometry.requiredMdSpan() * fraction;
        double topMd = geometry.mdBoundaryAtArc(arc, geometry.zTop());
        double bottomMd = geometry.mdBoundaryAtArc(arc, geometry.zBottom());
        if (md < topMd || md > bottomMd) {
            String message = "该位置不是有效测量区域。";
            popup.show(message, x, y, chartWidth(), chartHeight());
            onDataSelected.accept(message);
            return;
        }

        double amplitude = geometry.data().amplitudeAt(electrode, md);
        double phase = geometry.data().phaseAt(electrode, md);
        Point3D point = geometry.pointAtElectrodeAndMd(electrode, md);

        String content = String.format(Locale.ROOT,
                "深度 MD：%.8f m%n"
                        + "电极：%d%n"
                        + "模值：%.4f Ω·m%n"
                        + "相位：%.4f°%n"
                        + "X/Y/Z：%.4f / %.4f / %.4f m",
                md, electrode + 1, amplitude, phase,
                point.getX(), point.getY(), point.getZ());

        popup.show(content, x, y, chartWidth(), chartHeight());
        onDataSelected.accept(String.format(Locale.ROOT,
                "电极 %d · MD %.5f m · 模值 %.3f Ω·m · 相位 %.3f°",
                electrode + 1, md, amplitude, phase));
    }

    private boolean isSeparator(double arc) {

        double tolerance = SEPARATOR_HIT_HALF_WIDTH_PX / DATA_WIDTH
                * geometry.ellipsePerimeter();
        for (double separator : geometry.plateSeparatorArcs()) {
            if (Math.abs(arc - separator) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    private double arcToX(double arc) {
        return MARGIN_LEFT + arc / geometry.ellipsePerimeter() * DATA_WIDTH;
    }

    private int electrodeAtArc(double arc) {

        for (int electrode = 0;
             electrode < WallData.ELECTRODE_COUNT;
             electrode++) {
            if (arc <= geometry.stripRight(electrode) + 1e-12) {
                return electrode;
            }
        }
        return WallData.ELECTRODE_COUNT - 1;
    }

    private double mdToY(double md) {
        return MARGIN_TOP
                + (md - geometry.requiredMinMd())
                / geometry.requiredMdSpan() * DATA_HEIGHT;
    }

    private static int colorToArgb(Color color) {
        int a = (int) Math.round(color.getOpacity() * 255);
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String formatLogValue(double value) {

        if (value >= 100) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        if (value >= 1) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double chartWidth() {
        return MARGIN_LEFT + DATA_WIDTH + MARGIN_RIGHT;
    }

    private static double chartHeight() {
        return MARGIN_TOP + DATA_HEIGHT + MARGIN_BOTTOM;
    }
}
