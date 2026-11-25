package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.StratumModel;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;

final class WallChartState {
    record Snapshot(StratumModel model, double pointA, double pointB) { }
    enum Status { GENERATING, READY, FAILED }
    record Generation(Snapshot snapshot, String id, Status status, String message) { }

    private WallChartState() {
    }

    @SuppressWarnings("unchecked")
    static ObjectProperty<Generation> generationIn(Scene scene) {
        return (ObjectProperty<Generation>) scene.getProperties().computeIfAbsent(
                WallChartState.class.getName() + ".generation", key -> new SimpleObjectProperty<Generation>());
    }

    @SuppressWarnings("unchecked")
    static ObjectProperty<Snapshot> in(Scene scene) {
        return (ObjectProperty<Snapshot>) scene.getProperties().computeIfAbsent(
                WallChartState.class, key -> new SimpleObjectProperty<Snapshot>());
    }
}
