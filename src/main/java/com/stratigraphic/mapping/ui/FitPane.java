package com.stratigraphic.mapping.ui;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * 把一张固定尺寸的图按比例缩放后居中放进可用区域，并支持滚轮缩放、按住拖动平移。
 *
 * <p>图面本身仍用自己的原始坐标，鼠标事件落到图面上时拿到的也是图面坐标，
 * 所以缩放和平移都不影响图内的点击交互——拖动之后松手的那一次点击会被吃掉。
 *
 * <p>缩放范围是「刚好放得下」到它的 {@value #MAX_ZOOM} 倍，缩到最小时自动回正居中。
 */
public final class FitPane extends Region {

    /** 图面四周留白。 */
    private static final double PADDING = 12;

    /** 滚轮一格（deltaY 约 40）对应的缩放倍数。 */
    private static final double ZOOM_BASE = 1.15;

    /** 相对「刚好放得下」最多放大到几倍。 */
    private static final double MAX_ZOOM = 12;

    /** 单次滚轮事件的缩放倍数上限，防止触摸板一下冲太远。 */
    private static final double MAX_STEP = 2;

    /** 拖动超过这么多像素才算平移，否则当成点击。 */
    private static final double DRAG_THRESHOLD = 4;

    private final Group group = new Group();
    private final Scale scale = new Scale(1, 1);
    private final Translate translate = new Translate();

    /** 视口裁剪范围：放大或拖动后的画面只允许显示在本组件内。 */
    private final Rectangle viewportClip = new Rectangle();

    /** 当前缩放倍数，供外部（状态栏、重置按钮）监听。 */
    private final ReadOnlyDoubleWrapper zoomFactor = new ReadOnlyDoubleWrapper(1);

    public ReadOnlyDoubleProperty zoomFactorProperty() {
        return zoomFactor.getReadOnlyProperty();
    }

    /** 右侧留给固定配件的宽度；缩放和平移只在剩下的可用区里进行。 */
    private double rightInset;

    /** 右侧不参与缩放平移的配件，以及它要对齐的图面上沿 / 图面高。 */
    private Region rightAccessory;
    private double accessoryTop;
    private double accessoryHeight;

    private final double contentWidth;
    private final double contentHeight;

    /** 刚好把图放进可用区域的比例。 */
    private double fitScale = 1;

    /** 在 fitScale 基础上的放大倍数，范围 [1, MAX_ZOOM]。 */
    private double zoom = 1;

    /** 图面左上角在 FitPane 里的位置。 */
    private double offsetX = PADDING;
    private double offsetY = PADDING;

    private double dragFromX;
    private double dragFromY;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragged;

    public FitPane(Node content) {

        this.contentWidth = Math.max(1, content.prefWidth(-1));
        this.contentHeight = Math.max(1, content.prefHeight(-1));

        group.getChildren().add(content);
        // 先缩放再平移，缩放的基准点是图面左上角
        group.getTransforms().addAll(translate, scale);

        getChildren().add(group);

        // Region 默认不裁剪子节点，放大的图形会溢出边界并盖住左侧参数面板，
        // 所以这里把显示范围限制在 FitPane 自身的大小内。
        viewportClip.widthProperty().bind(widthProperty());
        viewportClip.heightProperty().bind(heightProperty());
        setClip(viewportClip);
        setMinSize(0, 0);
        // 必须允许被拉满，否则父容器只会按图面原始尺寸摆放，缩放就没机会生效
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        content.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        content.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
        content.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        content.addEventFilter(MouseEvent.MOUSE_CLICKED, this::onClicked);
    }

    /** 缩回「刚好放得下」。 */
    public void resetZoom() {
        zoom = 1;
        applyTransform();
    }

    /**
     * 在右侧让出一列给固定配件（比如电阻率色带）。
     *
     * <p>两张图给同样的宽度，可用区就一样大，「刚好放得下」的比例也一样，图面自然等大。
     */
    public void setRightInset(double rightInset) {
        this.rightInset = Math.max(0, rightInset);
        requestLayout();
    }

    /**
     * 在右侧挂一个不参与缩放平移的固定配件：竖直方向按「刚好放得下」的图面对齐，
     * 所以色带永远和图面一样高，位置也不会被拖动带走。
     *
     * @param accessory    配件
     * @param figureTop    图面上沿在内容坐标里的 y
     * @param figureHeight 图面高度（内容坐标）
     */
    public void setRightAccessory(Region accessory, double figureTop, double figureHeight) {

        this.rightAccessory = accessory;
        this.accessoryTop = figureTop;
        this.accessoryHeight = figureHeight;

        getChildren().add(accessory);
        requestLayout();
    }

    // =========================================================
    // 布局与变换
    // =========================================================

    @Override
    protected void layoutChildren() {

        double fit = Math.min(
                availableWidth() / contentWidth,
                availableHeight() / contentHeight);

        fitScale = (Double.isFinite(fit) && fit > 0) ? fit : 1;

        applyTransform();
    }

    private void applyTransform() {

        double s = fitScale * zoom;

        scale.setX(s);
        scale.setY(s);

        // 图比可用区域小的时候自动居中，大的时候限制在图内滑动
        offsetX = clampOffset(offsetX, contentWidth * s, availableWidth());
        offsetY = clampOffset(offsetY, contentHeight * s, availableHeight());

        translate.setX(offsetX);
        translate.setY(offsetY);

        zoomFactor.set(zoom);

        layoutRightAccessory();
    }

    /** 右侧配件固定摆在图面右边那一列，只跟「刚好放得下」的比例走，不随缩放平移动。 */
    private void layoutRightAccessory() {

        if (rightAccessory == null) {
            return;
        }

        double contentTop = PADDING
                + Math.max(0, (availableHeight() - contentHeight * fitScale) / 2);

        double top = contentTop + accessoryTop * fitScale;
        double height = accessoryHeight * fitScale;
        double width = rightAccessory.prefWidth(height);

        rightAccessory.resizeRelocate(getWidth() - PADDING - width, top, width, height);
    }

    private static double clampOffset(double offset, double contentPx, double available) {

        if (contentPx <= available) {
            return PADDING + (available - contentPx) / 2;
        }
        return Math.max(PADDING + available - contentPx, Math.min(offset, PADDING));
    }

    private double availableWidth() {
        return Math.max(0, getWidth() - 2 * PADDING - rightInset);
    }

    private double availableHeight() {
        return Math.max(0, getHeight() - 2 * PADDING);
    }

    // =========================================================
    // 滚轮缩放
    // =========================================================

    private void onScroll(ScrollEvent e) {

        double factor = Math.pow(ZOOM_BASE, e.getDeltaY() / 40.0);
        factor = clamp(factor, 1 / MAX_STEP, MAX_STEP);

        double next = clamp(zoom * factor, 1, MAX_ZOOM);

        if (next == zoom) {
            e.consume();
            return;
        }

        // 让鼠标指着的那一点在缩放前后停在原地
        Point2D pointer = sceneToLocal(e.getSceneX(), e.getSceneY());

        double oldScale = fitScale * zoom;
        double newScale = fitScale * next;

        double contentX = (pointer.getX() - offsetX) / oldScale;
        double contentY = (pointer.getY() - offsetY) / oldScale;

        zoom = next;
        offsetX = pointer.getX() - contentX * newScale;
        offsetY = pointer.getY() - contentY * newScale;

        applyTransform();
        e.consume();
    }

    // =========================================================
    // 拖动平移
    // =========================================================

    private void onPressed(MouseEvent e) {

        Point2D p = sceneToLocal(e.getSceneX(), e.getSceneY());

        dragFromX = p.getX();
        dragFromY = p.getY();
        dragOffsetX = offsetX;
        dragOffsetY = offsetY;
        dragged = false;
    }

    private void onDragged(MouseEvent e) {

        Point2D p = sceneToLocal(e.getSceneX(), e.getSceneY());

        double dx = p.getX() - dragFromX;
        double dy = p.getY() - dragFromY;

        if (!dragged && Math.hypot(dx, dy) < DRAG_THRESHOLD) {
            return;
        }
        dragged = true;

        offsetX = dragOffsetX + dx;
        offsetY = dragOffsetY + dy;

        applyTransform();
        e.consume();
    }

    private void onClicked(MouseEvent e) {
        // 拖动过就不算点击，免得平移完顺手弹出图上的信息框
        if (dragged) {
            e.consume();
        }
    }

    // =========================================================
    // 尺寸
    // =========================================================

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }

    @Override
    protected double computePrefWidth(double height) {
        return contentWidth;
    }

    @Override
    protected double computePrefHeight(double width) {
        return contentHeight;
    }
}
