package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.StratumModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZeroCountInputTest {
    @Test
    void twoLayersHaveOneInterfaceAndNoAnomalies() throws Exception {
        var model = fixture("formation-layers-only.txt");
        assertEquals(1, model.interfaceCount());
        assertEquals(List.of(20.0, 15.0), model.resistivities());
        assertEquals(0.1, model.interfaces().get(0).depth());
        assertTrue(model.fractures().isEmpty());
        assertTrue(model.sphereFractures().isEmpty());
        assertTrue(model.cubeAnomalies().isEmpty());
        assertTrue(model.wedgeFractures().isEmpty());
        assertTrue(model.surfaceTexts().isEmpty());
    }

    @Test
    void zeroInterfacesHaveOneBackgroundAndExactlyThreeAnomalies() throws Exception {
        var model = fixture("formation-anomalies-only.txt");
        assertEquals(0, model.interfaceCount());
        assertEquals(List.of(20.0), model.resistivities());
        assertEquals(List.of(1.0), model.layerDielectricConstants());
        assertTrue(model.fractures().isEmpty());
        assertEquals(1, model.sphereFractures().size());
        assertEquals(1, model.cubeAnomalies().size());
        assertEquals(1, model.wedgeFractures().size());
        assertTrue(model.surfaceTexts().isEmpty());
        assertEquals(0.38, model.minDepth(), 1e-12);
        assertEquals(0.8, model.maxDepth(), 1e-12);
    }

    @Test
    void keyValueFormatAlsoAcceptsZeroInterfacesAndOneResistivity() {
        var model = StratumModelReader.parse(List.of("n = 0", "dip = 90", "wellRadius = 0.1", "wellRho = 100", "rho = 20"));
        assertEquals(0, model.interfaceCount());
        assertEquals(1, model.layerCount());
        assertEquals(0, model.minDepth());
        assertEquals(1, model.maxDepth());
        assertThrows(IllegalArgumentException.class, () -> StratumModelReader.parse(
                List.of("n = 0", "wellRadius = 0.1", "wellRho = 100", "rho = 20 30")));
        assertThrows(IllegalArgumentException.class, () -> StratumModelReader.parse(
                List.of("n = -1", "wellRadius = 0.1", "wellRho = 100", "rho = 20")));
    }

    private StratumModel fixture(String name) throws Exception {
        return StratumModelReader.read(Path.of(getClass().getResource("/" + name).toURI()));
    }
}
