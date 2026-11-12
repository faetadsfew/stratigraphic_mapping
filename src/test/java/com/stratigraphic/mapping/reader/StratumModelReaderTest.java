package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.StratumModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StratumModelReaderTest {

    @Test
    void readsSingleDepthPerInterfaceAndUnifiedDip() {

        StratumModel model = StratumModelReader.parse(List.of(
                "n = 2",
                "dip = 92",
                "wellRadius = 0.1",
                "wellRho = 100",
                "rho = 20 15 10",
                "0.8",
                "1.1"));

        assertEquals(2, model.interfaceCount());
        assertEquals(3, model.layerCount());
        assertEquals(92, model.dipAngle(), 1e-12);
        assertEquals(0.8, model.interfaces().get(0).depth(), 1e-12);
        assertEquals(1.1, model.interfaces().get(1).depth(), 1e-12);
    }

    @Test
    void legacyTwoColumnInterfacesBecomeOneUniformDip() {

        StratumModel model = StratumModelReader.parse(List.of(
                "wellRadius = 0.1",
                "wellRho = 100",
                "rho = 20 15 10",
                "0.8 94",
                "1.1 105"));

        assertEquals(94, model.dipAngle(), 1e-12);
        assertEquals(0.8, model.interfaces().get(0).depth(), 1e-12);
        assertEquals(1.1, model.interfaces().get(1).depth(), 1e-12);
    }

    @Test
    void readsFormationGInputWithFractures() throws Exception {

        try (InputStream input = StratumModelReaderTest.class.getResourceAsStream(
                "/com/stratigraphic/mapping/formation_G.txt")) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            StratumModel model = StratumModelReader.parse(text.lines().toList());

            assertEquals(5, model.interfaceCount());
            assertEquals(6, model.layerCount());
            assertEquals(120, model.dipAngle(), 1e-12);
            assertEquals(0.1, model.wellRadius(), 1e-12);
            assertEquals(2, model.fractures().size());
            assertEquals(5.3, model.fractures().get(0).depth(), 1e-12);
            assertEquals(30, model.fractures().get(0).normalElevationDeg(), 1e-12);
            assertEquals(30, model.fractures().get(0).normalAzimuthDeg(), 1e-12);
            assertEquals(100, model.fractures().get(0).resistivity(), 1e-12);
        }
    }

    @Test
    void rejectsDipOutsideOpenZeroTo180Range() {

        assertThrows(IllegalArgumentException.class,
                () -> StratumModelReader.parse(List.of(
                        "dip = 180",
                        "wellRadius = 0.1",
                        "wellRho = 100",
                        "rho = 20 15",
                        "0.8")));
    }
}
