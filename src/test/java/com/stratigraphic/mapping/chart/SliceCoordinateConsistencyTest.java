package com.stratigraphic.mapping.chart;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.reader.StratumModelReader;
import com.stratigraphic.mapping.simulation.ModelInputs;
import com.stratigraphic.mapping.simulation.SliceGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SliceCoordinateConsistencyTest {
    @TempDir Path temp;

    @Test
    void twoLayerSliceBoundariesMatchWallAnglesForAll192Electrodes() throws Exception {
        var model = StratumModelReader.read(Path.of(getClass().getResource("/formation-layers-only.txt").toURI()));
        var generated = generator().generate(model, 0, 1.5);
        for (int electrode = 1; electrode <= ModelInputs.CHANNELS; electrode++) {
            double angle = (electrode - 1) * 360.0 / ModelInputs.CHANNELS;
            var raw = raw(slicePath(generated.output(), electrode, angle));
            double horizontalOffset = model.wellRadius() * Math.cos(Math.toRadians(angle));
            double expectedDepth = model.interfaces().get(0).depth() + horizontalOffset * SectionGeometry.cot(model.dipAngle());
            assertEquals(expectedDepth, raw.layers().get(1).get(0), 0.00005, "electrode=" + electrode);
            assertEquals(List.of(20.0, 15.0, 15.0), raw.layers().stream().map(row -> row.get(1)).toList());
            assertTrue(raw.fractures().isEmpty());
        }
    }

    @Test
    void fractureIntersectionsAndSlopesMatchDisplayedPlaneForAll192Electrodes() throws Exception {
        var fracture = new Fracture(0.75, 30, 30, 100);
        var model = new StratumModel(0.1, 100, 90, List.of(), List.of(20.0), List.of(fracture));
        var generated = generator().generate(model, 0, 1.5);
        for (int electrode = 1; electrode <= ModelInputs.CHANNELS; electrode++) {
            double angle = (electrode - 1) * 360.0 / ModelInputs.CHANNELS;
            var actual = raw(slicePath(generated.output(), electrode, angle)).fractures().get(0);
            assertEquals(FractureGeometry.traceDepth(fracture, model.wellRadius(), angle), actual.get(0),
                    0.00005, "fracture depth electrode=" + electrode);
            double delta = 0.0001;
            double rise = FractureGeometry.traceDepth(fracture, model.wellRadius(), angle + delta)
                    - FractureGeometry.traceDepth(fracture, model.wellRadius(), angle - delta);
            double run = model.wellRadius() * Math.toRadians(2 * delta);
            assertEquals(Math.toDegrees(Math.atan(rise / run)), actual.get(1), 0.00005,
                    "fracture slope electrode=" + electrode);
        }
    }

    @Test
    void currentShallowDipThreeLayerInputClipsBoundariesWithoutChangingMaterials() throws Exception {
        var model = new StratumModel(0.1, 100, 30,
                List.of(new StratumInterface(0.1),
                        new StratumInterface(0.2)),
                List.of(20.0, 15.0, 20.0));
        var generated = generator().generate(model, 0, 1.5);
        for (int electrode = 1; electrode <= ModelInputs.CHANNELS; electrode++) {
            double angle = (electrode - 1) * 360.0 / ModelInputs.CHANNELS;
            var actual = raw(slicePath(generated.output(), electrode, angle));
            double offset = model.wellRadius() * Math.cos(Math.toRadians(angle)) * SectionGeometry.cot(model.dipAngle());
            for (int point = 1; point < 300; point++) {
                double depth = point * 0.005;
                double firstBoundary = 0.1 + offset;
                double secondBoundary = 0.2 + offset;
                if (Math.abs(depth - firstBoundary) < 0.0001 || Math.abs(depth - secondBoundary) < 0.0001) continue;
                double expected = depth < firstBoundary ? 20 : depth < secondBoundary ? 15 : 20;
                double resistivity = actual.layers().get(0).get(1);
                for (var layer : actual.layers()) if (depth >= layer.get(0)) resistivity = layer.get(1);
                assertEquals(expected, resistivity, "electrode=" + electrode + " MD=" + depth);
            }
            assertTrue(actual.fractures().isEmpty());
        }
    }

    private static Path slicePath(Path output, int electrode, double angle) {
        return output.resolve(String.format(Locale.ROOT, "Card_%03d_ang_%06.2f.txt", electrode, angle));
    }

    private SliceGenerationService generator() throws Exception {
        var constructor = SliceGenerationService.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(temp);
    }

    private static ModelInputs.Model raw(Path path) throws Exception {
        return ModelInputs.parse("1 1\n0 1 1\n" + Files.readString(path));
    }
}
