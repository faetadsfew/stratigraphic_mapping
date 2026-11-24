package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.StratumModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertEquals(1, model.sphereFractures().size());
            assertEquals(5.6, model.sphereFractures().get(0).centerDepth(), 1e-12);
            assertEquals(90, model.sphereFractures().get(0).azimuthDeg(), 1e-12);
            assertEquals(0.15, model.sphereFractures().get(0).distanceFromAxis(), 1e-12);
            assertEquals(0.08, model.sphereFractures().get(0).radius(), 1e-12);
            assertEquals(50, model.sphereFractures().get(0).resistivity(), 1e-12);
            assertEquals(1, model.cubeAnomalies().size());
            assertEquals(5.45, model.cubeAnomalies().get(0).centerDepth(), 1e-12);
            assertEquals(210, model.cubeAnomalies().get(0).azimuthDeg(), 1e-12);
            assertEquals(0.16, model.cubeAnomalies().get(0).distanceFromAxis(), 1e-12);
            assertEquals(0.14, model.cubeAnomalies().get(0).sideLength(), 1e-12);
            assertEquals(70, model.cubeAnomalies().get(0).resistivity(), 1e-12);
            assertEquals(100, model.maxResistivity(), 1e-12);
            assertEquals(100, model.maxSectionResistivity(), 1e-12);
            assertEquals(1, model.wedgeFractures().size());
            assertEquals(120, model.wedgeFractures().get(0).vertexAzimuthDeg(), 1e-12);
            assertEquals(5.6, model.wedgeFractures().get(0).vertexDepth(), 1e-12);
            assertEquals(1, model.surfaceTexts().size());
            assertEquals("文字", model.surfaceTexts().get(0).content());
            assertEquals(180, model.surfaceTexts().get(0).centerAzimuthDeg(), 1e-12);
            assertEquals(5.9, model.surfaceTexts().get(0).centerDepth(), 1e-12);
            assertEquals(120, model.surfaceTexts().get(0).angularSpanDeg(), 1e-12);
            assertEquals(0.25, model.surfaceTexts().get(0).depthSpanM(), 1e-12);
            assertEquals(3, model.surfaceTexts().get(0).resistivity(), 1e-12);
            assertEquals(10, model.minResistivity(), 1e-12,
                    "曲面文字电阻率不应改变三维/井壁图的统一色标范围");
            assertEquals(1, model.dielectricConstant(), 1e-12);
            assertEquals(1, model.fractures().get(0).dielectricConstant(), 1e-12);
            assertEquals(1, model.sphereFractures().get(0).dielectricConstant(), 1e-12);
            assertEquals(1, model.cubeAnomalies().get(0).dielectricConstant(), 1e-12);
            assertEquals(1, model.wedgeFractures().get(0).dielectricConstant(), 1e-12);
            assertEquals(1, model.surfaceTexts().get(0).dielectricConstant(), 1e-12);
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

    @Test
    void readsFormationGWedgeSection() {

        StratumModel model = StratumModelReader.parse(formationGWithWedge(
                "1\n120 0.6 240 0.8 240 0.4 10"));

        assertEquals(1, model.wedgeFractures().size());
        assertEquals(120, model.wedgeFractures().get(0).vertexAzimuthDeg(), 1e-12);
        assertEquals(0.6, model.wedgeFractures().get(0).vertexDepth(), 1e-12);
        assertEquals(240, model.wedgeFractures().get(0).leftAzimuthDeg(), 1e-12);
        assertEquals(0.8, model.wedgeFractures().get(0).leftDepth(), 1e-12);
        assertEquals(240, model.wedgeFractures().get(0).rightAzimuthDeg(), 1e-12);
        assertEquals(0.4, model.wedgeFractures().get(0).rightDepth(), 1e-12);
        assertEquals(10, model.wedgeFractures().get(0).resistivity(), 1e-12);
    }

    @Test
    void rejectsWedgeCountMismatchWithLineAndCount() {

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StratumModelReader.parse(formationGWithWedge(
                        "2\n120 0.6 240 0.8 240 0.4 10")));

        assertTrue(error.getMessage().contains("楔形体个数为 2"));
        assertTrue(error.getMessage().contains("第 "));
    }

    private static List<String> formationGWithWedge(String wedgeRows) {
        String text = """
                ---------------------------------------------------------------------
                地层模型参数
                ---------------------------------------------------------------------
                界面个数n
                    1
                ---------------------------------------------------------------------
                界面和井轴的夹角°    井眼半径a/m       井眼泥浆电阻率R0/Ω·m
                   90                    0.1                     100
                ---------------------------------------------------------------------
                界面和井轴的交点深度z/m
                   5.0
                ---------------------------------------------------------------------
                          层数 n+1                   层电阻率R0/Ω·m
                   1                                20
                   2                                50
                ---------------------------------------------------------------------
                裂缝个数n2
                   0
                ---------------------------------------------------------------------
                % ===== 5. 楔形体 =====
                """ + wedgeRows + "\n";
        return text.lines().toList();
    }
}
