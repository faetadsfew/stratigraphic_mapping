package com.stratigraphic.mapping.chart;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponseViewportTest {
    @Test void zoomPreservesCursorAnchorAndTheSameScaleOnBothAxes() {
        var viewport = new ResponseViewport();
        viewport.zoom(0.3, 0.7, 2);
        var window = viewport.window();
        assertEquals(0.5, window.span(), 1e-12);
        assertEquals(0.3, window.left() + 0.3 * window.span(), 1e-12);
        assertEquals(0.7, window.top() + 0.7 * window.span(), 1e-12);
        viewport.zoom(0.3, 0.7, 0.5);
        assertEquals(new ResponseViewport.Window(0, 0, 1), viewport.window());
    }

    @Test void panningAndZoomLimitsNeverStretchOrLeaveTheDataBounds() {
        var viewport = new ResponseViewport();
        viewport.zoom(0, 1, 1e8);
        assertEquals(1.0 / 128, viewport.window().span());
        viewport.pan(viewport.window(), -1000, 1000);
        assertEquals(1 - 1.0 / 128, viewport.window().left());
        assertEquals(0, viewport.window().top());
        viewport.pan(viewport.window(), 1000, -1000);
        assertEquals(0, viewport.window().left());
        assertEquals(1 - 1.0 / 128, viewport.window().top());
        viewport.reset();
        assertEquals(new ResponseViewport.Window(0, 0, 1), viewport.window());
    }
}
