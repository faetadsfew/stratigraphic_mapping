package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.StratumModel;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;

final class WallChartState {
    record Snapshot(StratumModel model, double pointA, double pointB) { }

    private WallChartState() {
    }

    @SuppressWarnings("unchecked")
    static ObjectProperty<Snapshot> in(Scene scene) {
        return (ObjectProperty<Snapshot>) scene.getProperties().computeIfAbsent(
                WallChartState.class, key -> new SimpleObjectProperty<Snapshot>());
    }
}
