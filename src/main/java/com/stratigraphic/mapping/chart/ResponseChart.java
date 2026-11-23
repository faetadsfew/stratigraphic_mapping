package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.simulation.ResponseData;
import javafx.beans.value.ChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import java.util.Locale;

public final class ResponseChart extends Region {
    private static final int WIDTH = 213;
    static final double LEFT = 54, TOP = 28, RIGHT = 12, FOOT = 102;
    private final ResponseData data;
    private final boolean phase;
    private final ResponseViewport viewport;
    private final Canvas canvas = new Canvas();
    private final Label inspector = new Label();
    private final ChangeListener<ResponseViewport.Window> viewportListener = (observable, previous, current) -> draw();
    private WritableImage image;
    private ResponseData.Sample selection;
    private ResponseViewport.Window dragOrigin;
    private double min, max, dragX, dragY, selectionColumn, selectionDepth;
    private boolean dragged;

    public ResponseChart(ResponseData data, boolean phase, double min, double max, ResponseViewport viewport) {
        this.data = data;
        this.phase = phase;
        this.viewport = viewport;
        setMinSize(0, 0);
        setPrefSize(300, 650);
        inspector.getStyleClass().add("response-inspector");
        inspector.setWrapText(true);
        inspector.setMinWidth(0);
        inspector.setManaged(false);
        inspector.setMouseTransparent(true);
        inspector.setVisible(false);
        getChildren().addAll(canvas, inspector);
        setRange(min, max);
        viewport.windowProperty().addListener(viewportListener);
        canvas.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || dragged) return;
            selection = sampleAt(event.getX(), event.getY());
            if (selection != null) {
                selectionColumn = (viewport.window().left() + (event.getX() - LEFT) / plotWidth() * viewport.window().span()) * WIDTH;
                selectionDepth = depthAt((event.getY() - TOP) / plotHeight());
                inspector.setText("深度 MD：" + selection.md() + " m\n电极编号：" + selection.electrode()
                        + (phase ? "\n相位：" + selection.phase() + "°" : "\n模值：" + selection.amplitude() + " Ω·m"));
            }
            draw();
        });
        canvas.setOnScroll(event -> {
            if (!insidePlot(event.getX(), event.getY()) || event.getDeltaY() == 0) return;
            viewport.zoom((event.getX() - LEFT) / plotWidth(), (event.getY() - TOP) / plotHeight(),
                    Math.pow(1.25, event.getDeltaY() / 40.0));
            event.consume();
        });
        canvas.setOnMousePressed(event -> {
            dragOrigin = event.getButton() == MouseButton.PRIMARY && insidePlot(event.getX(), event.getY()) ? viewport.window() : null;
            dragX = event.getX();
            dragY = event.getY();
            dragged = false;
        });
        canvas.setOnMouseDragged(event -> {
            if (dragOrigin == null || !event.isPrimaryButtonDown()) return;
            if (Math.hypot(event.getX() - dragX, event.getY() - dragY) > 3) dragged = true;
            if (dragged) viewport.pan(dragOrigin, (event.getX() - dragX) / plotWidth(), (event.getY() - dragY) / plotHeight());
            event.consume();
        });
    }

    public double fullTop() { return data.depth(0); }
    public double fullBottom() { return data.size() == 1 ? data.depth(0) + 1 : data.depth(data.size() - 1); }
    public void dispose() { viewport.windowProperty().removeListener(viewportListener); }

    public void setRange(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min || (!phase && min <= 0)) {
            throw new IllegalArgumentException("色标上下限不合法");
        }
        this.min = min;
        this.max = max;
        image = new WritableImage(WIDTH, data.size());
        var pixels = image.getPixelWriter();
        for (int row = 0; row < data.size(); row++) {
            for (int electrode = 1; electrode <= 192; electrode++) {
                var sample = data.sample(row, electrode);
                int column = electrode - 1 + (electrode - 1) / 24 * 3;
                pixels.setColor(column, row, color(phase ? sample.phase() : sample.amplitude()));
            }
        }
        draw();
    }

    private Color color(double value) {
        if (!phase && value <= 0) return Color.GRAY;
        double fraction = phase ? (value - min) / (max - min) : (Math.log(value) - Math.log(min)) / (Math.log(max) - Math.log(min));
        fraction = Math.max(0, Math.min(1, fraction));
        return phase ? Color.web("#eff6ff").interpolate(Color.web("#174cb5"), fraction)
                : Color.web("#fff5b0").interpolate(Color.web("#d7191c"), fraction);
    }

    private double plotWidth() { return Math.max(1, getWidth() - LEFT - RIGHT); }
    private double plotHeight() { return Math.max(1, getHeight() - TOP - FOOT); }
    private boolean insidePlot(double x, double y) {
        return x >= LEFT && x < LEFT + plotWidth() && y >= TOP && y <= TOP + plotHeight();
    }
    private double screenX(double column) {
        var window = viewport.window();
        return LEFT + (column / WIDTH - window.left()) / window.span() * plotWidth();
    }
    private double screenY(double md) {
        var window = viewport.window();
        return TOP + ((md - fullTop()) / (fullBottom() - fullTop()) - window.top()) / window.span() * plotHeight();
    }
    private double depthAt(double fraction) {
        var window = viewport.window();
        return fullTop() + (window.top() + fraction * window.span()) * (fullBottom() - fullTop());
    }

    String depthLabelAt(double fraction) {
        double tickSpacing = viewport.window().span() * (fullBottom() - fullTop()) / 5;
        int decimals = Math.max(3, Math.min(12, (int) Math.ceil(-Math.log10(tickSpacing)) + 1));
        return String.format(Locale.ROOT, "%." + decimals + "f", depthAt(fraction));
    }

    public ResponseData.Sample sampleAt(double x, double y) {
        if (!insidePlot(x, y)) return null;
        var window = viewport.window();
        int column = (int) Math.floor((window.left() + (x - LEFT) / plotWidth() * window.span()) * WIDTH);
        int group = column / 27, within = column % 27;
        if (group > 7 || within >= 24) return null;
        return data.sample(data.nearestDepth(depthAt((y - TOP) / plotHeight())), group * 24 + within + 1);
    }

    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        if (image == null || getWidth() < 1 || getHeight() < 1) return;
        var graphics = canvas.getGraphicsContext2D();
        graphics.setImageSmoothing(false);
        graphics.setFill(Color.WHITE);
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.save();
        graphics.beginPath();
        graphics.rect(LEFT, TOP, plotWidth(), plotHeight());
        graphics.clip();
        double startDepth = depthAt(0), endDepth = depthAt(1);
        for (int row = 0; row < data.size(); row++) {
            double shallow = row == 0 ? fullTop() : (data.depth(row - 1) + data.depth(row)) / 2;
            double deep = row == data.size() - 1 ? fullBottom() : (data.depth(row) + data.depth(row + 1)) / 2;
            if (deep < startDepth || shallow > endDepth) continue;
            graphics.drawImage(image, 0, row, WIDTH, 1, screenX(0), screenY(shallow),
                    plotWidth() / viewport.window().span(), screenY(deep) - screenY(shallow));
        }
        graphics.setStroke(Color.BLACK);
        graphics.setLineWidth(1);
        for (int group = 0; group < 7; group++) {
            double gapStart = screenX(group * 27 + 24), gapEnd = screenX(group * 27 + 27);
            graphics.setFill(Color.WHITE);
            graphics.fillRect(gapStart, TOP, gapEnd - gapStart, plotHeight());
            double divider = (gapStart + gapEnd) / 2;
            graphics.strokeLine(divider, TOP, divider, TOP + plotHeight());
        }
        drawSelection(graphics);
        graphics.restore();
        drawAxes(graphics);
        drawColorBar(graphics);
        positionInspector();
    }

    private void positionInspector() {
        double anchorX = screenX(selectionColumn), anchorY = screenY(selectionDepth);
        if (selection == null || !insidePlot(anchorX, anchorY)) {
            inspector.setVisible(false);
            return;
        }
        inspector.setVisible(true);
        double width = Math.min(216, Math.max(1, plotWidth() - 12));
        double height = inspector.prefHeight(width);
        double left = anchorX + 12, top = anchorY + 12;
        if (left + width > LEFT + plotWidth() - 4) left = anchorX - width - 12;
        if (top + height > TOP + plotHeight() - 4) top = anchorY - height - 12;
        left = Math.max(LEFT + 4, Math.min(LEFT + plotWidth() - width - 4, left));
        top = Math.max(TOP + 4, Math.min(TOP + plotHeight() - height - 4, top));
        inspector.resizeRelocate(left, top, width, height);
    }

    private void drawSelection(GraphicsContext graphics) {
        if (selection == null) return;
        double centerX = screenX(selectionColumn), centerY = screenY(selectionDepth);
        graphics.setStroke(Color.web("#142d45"));
        graphics.setLineWidth(1.5);
        graphics.strokeOval(centerX - 4, centerY - 4, 8, 8);
        graphics.strokeLine(centerX - 8, centerY, centerX + 8, centerY);
        graphics.strokeLine(centerX, centerY - 8, centerX, centerY + 8);
    }

    private void drawAxes(GraphicsContext graphics) {
        graphics.setStroke(Color.BLACK);
        graphics.setLineWidth(1);
        graphics.strokeRect(LEFT, TOP, plotWidth(), plotHeight());
        graphics.setFill(Color.web("#344054"));
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.setFont(Font.font(10));
        graphics.fillText("MD (m)", 4, 16);
        for (int tick = 0; tick <= 5; tick++) {
            double position = TOP + plotHeight() * tick / 5;
            graphics.fillText(depthLabelAt(tick / 5.0), 2, position + 4, LEFT - 7);
            graphics.strokeLine(LEFT - 3, position, LEFT, position);
        }
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.setFont(Font.font(9));
        if (viewport.window().span() == 1) {
            for (int group = 0; group < 8; group++) {
                double center = screenX(group * 27 + 12);
                if (center >= LEFT && center <= LEFT + plotWidth()) {
                    graphics.fillText((group * 24 + 1) + "–" + (group * 24 + 24), center, TOP + plotHeight() + 13 + (group % 2) * 13);
                }
            }
        } else {
            int step = Math.max(1, (int) Math.ceil(34 * WIDTH * viewport.window().span() / plotWidth()));
            for (int electrode = 1; electrode <= 192; electrode++) {
                double center = screenX(electrode - 1 + (electrode - 1) / 24 * 3 + 0.5);
                if ((electrode - 1) % step == 0 && center >= LEFT + 10 && center <= LEFT + plotWidth() - 10) {
                    graphics.strokeLine(center, TOP + plotHeight(), center, TOP + plotHeight() + 3);
                    graphics.fillText(Integer.toString(electrode), center, TOP + plotHeight() + 16);
                }
            }
        }
        graphics.fillText("电极编号", LEFT + plotWidth() / 2, TOP + plotHeight() + 43);
        graphics.setTextAlign(TextAlignment.LEFT);
    }

    private void drawColorBar(GraphicsContext graphics) {
        double barY = getHeight() - 37;
        for (int segment = 0; segment < 100; segment++) {
            double fraction = segment / 99.0;
            graphics.setFill(color(phase ? min + fraction * (max - min) : Math.exp(Math.log(min) + fraction * (Math.log(max) - Math.log(min)))));
            graphics.fillRect(LEFT + plotWidth() * segment / 100, barY, plotWidth() / 100 + 1, 9);
        }
        graphics.setFill(Color.web("#344054"));
        graphics.setFont(Font.font(10));
        graphics.fillText(String.format(Locale.ROOT, "%.4g", min), LEFT, barY + 22);
        graphics.setTextAlign(TextAlignment.RIGHT);
        graphics.fillText(String.format(Locale.ROOT, "%.4g", max), LEFT + plotWidth(), barY + 22);
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.fillText(phase ? "相位 (°) · 线性" : "模值 (Ω·m) · 对数", LEFT, barY - 6, plotWidth());
    }
}
