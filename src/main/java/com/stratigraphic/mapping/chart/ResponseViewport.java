package com.stratigraphic.mapping.chart;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

public final class ResponseViewport {
    public record Window(double left, double top, double span) { }

    private final ReadOnlyObjectWrapper<Window> window = new ReadOnlyObjectWrapper<>(new Window(0, 0, 1));

    public Window window() { return window.get(); }
    public ReadOnlyObjectProperty<Window> windowProperty() { return window.getReadOnlyProperty(); }
    public void reset() { window.set(new Window(0, 0, 1)); }

    public void zoom(double horizontalFraction, double verticalFraction, double factor) {
        if (!Double.isFinite(factor) || factor <= 0) return;
        Window previous = window();
        double span = Math.max(1.0 / 128, Math.min(1, previous.span() / factor));
        double anchorX = previous.left() + horizontalFraction * previous.span();
        double anchorY = previous.top() + verticalFraction * previous.span();
        move(anchorX - horizontalFraction * span, anchorY - verticalFraction * span, span);
    }

    public void pan(Window origin, double horizontalFraction, double verticalFraction) {
        move(origin.left() - horizontalFraction * origin.span(), origin.top() - verticalFraction * origin.span(), origin.span());
    }

    private void move(double left, double top, double span) {
        window.set(new Window(Math.max(0, Math.min(1 - span, left)), Math.max(0, Math.min(1 - span, top)), span));
    }
}
