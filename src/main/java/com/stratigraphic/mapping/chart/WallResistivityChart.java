package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.StratumModel;
import javafx.beans.value.ChangeListener;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;

import java.util.Locale;

public final class WallResistivityChart extends Region {
    private static final double LEFT = ResponseChart.LEFT;
    private static final double TOP = ResponseChart.TOP;
    private static final double RIGHT = ResponseChart.RIGHT;
    private static final double FOOT = ResponseChart.FOOT;

    private final WallLineUnfoldChart source;
    private final ResponseViewport viewport;
    private final ColorScale scale;
    private final Canvas canvas = new Canvas();
    private final Label inspector = new Label();
    private final Rectangle plotClip = new Rectangle();
    private final Affine transform = new Affine();
    private final ChangeListener<ResponseViewport.Window> viewportListener = (observable, previous, current) -> draw();
    private WallLineUnfoldChart.Sample selection;
    private ResponseViewport.Window dragOrigin;
    private double dragX;
    private double dragY;
    private boolean dragged;

    public WallResistivityChart(StratumModel model, double pointA, double pointB, ResponseViewport viewport) {
        this.viewport = viewport;
        source = new WallLineUnfoldChart(model, pointA, pointB, false);
        source.setManaged(false);
        source.resize(source.prefWidth(-1), source.prefHeight(-1));
        source.getTransforms().add(transform);
        scale = new ColorScale(model.minResistivity(), model.maxResistivity());
        Group plot = new Group(source);
        plot.setAutoSizeChildren(false);
        plot.setManaged(false);
        plot.setMouseTransparent(true);
        plot.setClip(plotClip);
        setMinSize(0, 0);
        setPrefSize(300, 650);
        setStyle("-fx-background-color: #FFFFFF;");
        getStyleClass().add("wall-resistivity-chart");
        inspector.getStyleClass().addAll("response-inspector", "wall-inspector");
        inspector.setWrapText(true);
        inspector.setMinWidth(0);
        inspector.setManaged(false);
        inspector.setMouseTransparent(true);
        inspector.setVisible(false);
        getChildren().addAll(plot, canvas, inspector);
        viewport.windowProperty().addListener(viewportListener);
        canvas.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || dragged) {
                return;
            }
            selection = sampleAt(event.getX(), event.getY());
            if (selection != null) {
                inspector.setText(String.format(Locale.ROOT,
                        "深度：%.8f m%n方位角：%.5f°%n电阻率：%.8g Ω·m",
                        selection.depth(), selection.angleDeg(), selection.resistivity()));
            }
            draw();
        });
        canvas.setOnScroll(event -> {
            if (!insidePlot(event.getX(), event.getY()) || event.getDeltaY() == 0) {
                return;
            }
            viewport.zoom((event.getX() - LEFT) / plotWidth(), (event.getY() - TOP) / plotHeight(),
                    Math.pow(1.25, event.getDeltaY() / 40.0));
            event.consume();
        });
        canvas.setOnMousePressed(event -> {
            dragOrigin = event.getButton() == MouseButton.PRIMARY && insidePlot(event.getX(), event.getY())
                    ? viewport.window() : null;
            dragX = event.getX();
            dragY = event.getY();
            dragged = false;
        });
        canvas.setOnMouseDragged(event -> {
            if (dragOrigin == null || !event.isPrimaryButtonDown()) {
                return;
            }
            if (Math.hypot(event.getX() - dragX, event.getY() - dragY) > 3) {
                dragged = true;
            }
            if (dragged) {
                viewport.pan(dragOrigin, (event.getX() - dragX) / plotWidth(),
                        (event.getY() - dragY) / plotHeight());
            }
            event.consume();
        });
    }

    public void dispose() {
        viewport.windowProperty().removeListener(viewportListener);
    }

    public WallLineUnfoldChart.Sample sampleAt(double x, double y) {
        if (!insidePlot(x, y)) {
            return null;
        }
        var window = viewport.window();
        return source.sampleAt(
                WallLineUnfoldChart.MARGIN_LEFT + (window.left() + (x - LEFT) / plotWidth() * window.span())
                        * WallLineUnfoldChart.PLOT_WIDTH,
                WallLineUnfoldChart.MARGIN_TOP + (window.top() + (y - TOP) / plotHeight() * window.span())
                        * WallLineUnfoldChart.PLOT_HEIGHT);
    }

    private double plotWidth() {
        return Math.max(1, getWidth() - LEFT - RIGHT);
    }

    private double plotHeight() {
        return Math.max(1, getHeight() - TOP - FOOT);
    }

    private boolean insidePlot(double x, double y) {
        return x >= LEFT && x <= LEFT + plotWidth() && y >= TOP && y <= TOP + plotHeight();
    }

    private double depthAt(double fraction) {
        var window = viewport.window();
        return source.pointA() + (window.top() + fraction * window.span()) * (source.pointB() - source.pointA());
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        if (getWidth() < 1 || getHeight() < 1) {
            return;
        }
        var window = viewport.window();
        double scaleX = plotWidth() / (WallLineUnfoldChart.PLOT_WIDTH * window.span());
        double scaleY = plotHeight() / (WallLineUnfoldChart.PLOT_HEIGHT * window.span());
        transform.setToTransform(scaleX, 0,
                LEFT - scaleX * (WallLineUnfoldChart.MARGIN_LEFT + window.left() * WallLineUnfoldChart.PLOT_WIDTH),
                0, scaleY,
                TOP - scaleY * (WallLineUnfoldChart.MARGIN_TOP + window.top() * WallLineUnfoldChart.PLOT_HEIGHT));
        plotClip.setX(LEFT);
        plotClip.setY(TOP);
        plotClip.setWidth(plotWidth());
        plotClip.setHeight(plotHeight());
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, getWidth(), getHeight());
        graphics.setStroke(Color.BLACK);
        graphics.setFill(Color.BLACK);
        graphics.setLineWidth(1);
        graphics.strokeRect(LEFT, TOP, plotWidth(), plotHeight());
        graphics.setFont(Font.font(10));
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.fillText("深度 (m)", 4, 16);
        double spacing = (depthAt(1) - depthAt(0)) / 5;
        int decimals = Math.max(3, Math.min(12, (int) Math.ceil(-Math.log10(spacing)) + 1));
        for (int tick = 0; tick <= 5; tick++) {
            double position = TOP + plotHeight() * tick / 5;
            graphics.fillText(String.format(Locale.ROOT, "%." + decimals + "f", depthAt(tick / 5.0)),
                    2, position + 4, LEFT - 7);
            graphics.strokeLine(LEFT - 3, position, LEFT, position);
        }
        graphics.setTextAlign(TextAlignment.CENTER);
        for (int tick = 0; tick <= 4; tick++) {
            double position = LEFT + plotWidth() * tick / 4;
            double angle = (window.left() + tick / 4.0 * window.span()) * 360;
            graphics.strokeLine(position, TOP + plotHeight(), position, TOP + plotHeight() + 3);
            graphics.fillText(String.format(Locale.ROOT, "%.1f", angle), position, TOP + plotHeight() + 16);
        }
        graphics.fillText("方位角 (°)", LEFT + plotWidth() / 2, TOP + plotHeight() + 43);
        drawColorBar(graphics);
        positionInspector(graphics);
    }

    private void drawColorBar(GraphicsContext graphics) {
        double position = getHeight() - 37;
        for (int segment = 0; segment < 100; segment++) {
            double fraction = segment / 99.0;
            double resistivity = Math.exp(Math.log(scale.min()) + fraction * Math.log(scale.max() / scale.min()));
            graphics.setFill(scale.colorOf(resistivity));
            graphics.fillRect(LEFT + plotWidth() * segment / 100, position, plotWidth() / 100 + 1, 9);
        }
        graphics.setFill(Color.web("#344054"));
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.fillText(String.format(Locale.ROOT, "%.4g", scale.min()), LEFT, position + 22);
        graphics.setTextAlign(TextAlignment.RIGHT);
        graphics.fillText(String.format(Locale.ROOT, "%.4g", scale.max()), LEFT + plotWidth(), position + 22);
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.fillText("电阻率 (Ω·m) · 对数", LEFT, position - 6, plotWidth());
    }

    private void positionInspector(GraphicsContext graphics) {
        if (selection == null) {
            inspector.setVisible(false);
            return;
        }
        var window = viewport.window();
        double anchorX = LEFT + (selection.angleDeg() / 360 - window.left()) / window.span() * plotWidth();
        double fraction = (selection.depth() - source.pointA()) / (source.pointB() - source.pointA());
        double anchorY = TOP + (fraction - window.top()) / window.span() * plotHeight();
        if (!insidePlot(anchorX, anchorY)) {
            inspector.setVisible(false);
            return;
        }
        graphics.setFill(Color.WHITE);
        graphics.setStroke(Color.BLACK);
        graphics.fillOval(anchorX - 3, anchorY - 3, 6, 6);
        graphics.strokeOval(anchorX - 3, anchorY - 3, 6, 6);
        inspector.setVisible(true);
        double width = Math.min(216, Math.max(1, plotWidth() - 12));
        double height = Math.min(inspector.prefHeight(width), plotHeight());
        double left = anchorX + 12;
        double top = anchorY + 12;
        if (left + width > LEFT + plotWidth() - 4) {
            left = anchorX - width - 12;
        }
        if (top + height > TOP + plotHeight() - 4) {
            top = anchorY - height - 12;
        }
        inspector.resizeRelocate(Math.max(LEFT + 4, left), Math.max(TOP, top), width, height);
    }
}
