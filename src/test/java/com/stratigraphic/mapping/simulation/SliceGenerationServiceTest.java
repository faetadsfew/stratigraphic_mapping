package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceGenerationServiceTest {
    @TempDir
    Path temp;

    @Test
    void generatesAllSlicesAndBacksUpPreviousData() throws Exception {
        Path oldOutput = Files.createDirectory(temp.resolve("192_Slices_Output"));
        Files.writeString(oldOutput.resolve("old.txt"), "old slices");
        Files.writeString(temp.resolve("NGI_PLOT_INP.txt"), "old model");

        SliceGenerationService.Result result = new SliceGenerationService(temp)
                .generate(referenceModel(), 0, 1.5);

        assertEquals(192, ModelInputs.indexFiles(result.output()).size());
        assertNotNull(result.backup());
        assertEquals("old slices", Files.readString(
                result.backup().resolve("192_Slices_Output/old.txt")));
        assertEquals("old model", Files.readString(result.backup().resolve("NGI_PLOT_INP.txt")));

        Path first = result.output().resolve("Card_001_ang_000.00.txt");
        String firstText = Files.readString(first);
        assertEquals("""
                0.0000    0.0010    1.5000
                6
                0.0000    100.0000    1.0000
                0.1413    30.0000    1.0000
                0.1863    100.0000    1.0000
                0.2963    30.0000    1.0000
                0.3213    100.0000    1.0000
                1.5000    100.0000    1.0000
                1
                0.8587    -0.0000    0.5000    180.0000    0.0020
                """, firstText);
        ModelInputs.Model slice = ModelInputs.parse("1 1\n0 1 1\n" + firstText);
        assertEquals(0, slice.begin());
        assertEquals(0.001, slice.step());
        assertEquals(1.5, slice.end());
        assertEquals(List.of(0.0, 0.1413, 0.1863, 0.2963, 0.3213, 1.5),
                slice.layers().stream().map(row -> row.get(0)).toList());
        assertEquals(100, slice.layers().get(0).get(1));
        assertEquals(1, slice.fractures().size());
        assertEquals(0.8587, slice.fractures().get(0).get(0), 0.00005);
        assertEquals(0, slice.fractures().get(0).get(1), 0.00005);
        assertEquals(0.5, slice.fractures().get(0).get(2));
        assertEquals(180, slice.fractures().get(0).get(3));
        assertEquals(0.002, slice.fractures().get(0).get(4));

        String modelInput = Files.readString(temp.resolve("NGI_PLOT_INP.txt"));
        assertTrue(modelInput.contains("% ===== 4. 球体 ====="));
        assertTrue(modelInput.contains("% ===== 5. 楔形体 ====="));
        assertTrue(modelInput.contains("% ===== 6. 曲面文字 ====="));
    }

    @Test
    void acceptsZeroAzimuthAndKeepsExistingOutputWhenValidationFails() throws Exception {
        Path oldOutput = Files.createDirectory(temp.resolve("192_Slices_Output"));
        Files.writeString(oldOutput.resolve("old.txt"), "untouched");
        StratumModel model = new StratumModel(
                0.1016, 100, 90,
                List.of(new StratumInterface(0.2)),
                List.of(100.0, 30.0),
                List.of(new Fracture(0.1, 0, 0, 0.5)));

        assertThrows(IllegalArgumentException.class,
                () -> new SliceGenerationService(temp).generate(model, 0.3, 1.5));
        assertEquals("untouched", Files.readString(oldOutput.resolve("old.txt")));

        SliceGenerationService.Result result = new SliceGenerationService(temp).generate(model, 0, 1.5);
        String first = Files.readString(result.output().resolve("Card_001_ang_000.00.txt"));
        assertEquals(0, ModelInputs.parse("1 1\n0 1 1\n" + first).fractures().get(0).get(3));
    }

    @Test
    void sixLayersAndTwoFracturesNeverAcquireAnArtificialBottomLayer() throws Exception {
        StratumModel model = new StratumModel(
                0.1, 100, 120,
                List.of(new StratumInterface(0.1), new StratumInterface(0.4),
                        new StratumInterface(0.7), new StratumInterface(1.0),
                        new StratumInterface(1.4)),
                List.of(20.0, 15.0, 10.0, 30.0, 40.0, 50.0),
                List.of(new Fracture(0.3, 30, 30, 100),
                        new Fracture(0.7, 60, 30, 100)));
        SliceGenerationService.Result result = new SliceGenerationService(temp).generate(model, 0, 1.5);
        InputPreparation.Settings settings = new InputPreparation.Settings(
                2500000, 8, 0.005, 1000, 6, 10,
                result.output(), temp.resolve("192_NGI_Input"),
                temp.resolve("call.m"), temp.resolve("transf.m"));

        for (var entry : ModelInputs.indexFiles(result.output()).entrySet()) {
            String raw = Files.readString(entry.getValue());
            List<String> lines = raw.lines().toList();
            assertEquals("7", lines.get(1), entry.getValue().toString());
            assertEquals("2", lines.get(9), entry.getValue().toString());
            assertEquals(12, lines.size());
            ModelInputs.Model slice = ModelInputs.parse("1 1\n0 1 1\n" + raw);
            assertEquals(7, slice.layers().size());
            assertEquals(2, slice.fractures().size());
            assertEquals(List.of(20.0, 15.0, 10.0, 30.0, 40.0, 50.0, 50.0),
                    slice.layers().stream().map(row -> row.get(1)).toList());
            assertEquals(List.of(1.5, 50.0, 1.0), slice.layers().get(6));
            double angle = (entry.getKey() - 1) * 360.0 / ModelInputs.CHANNELS;
            for (int index = 0; index < model.interfaceCount(); index++) {
                double expectedDepth = model.interfaces().get(index).depth()
                        - model.wellRadius() * Math.tan(Math.toRadians(30))
                        * Math.cos(Math.toRadians(angle));
                assertEquals(expectedDepth, slice.layers().get(index + 1).get(0), 0.00005);
            }
            for (int index = 0; index < model.fractures().size(); index++) {
                Fracture fracture = model.fractures().get(index);
                double expectedDepth = fracture.depth() - model.wellRadius()
                        * Math.tan(Math.toRadians(fracture.normalElevationDeg()))
                        * Math.cos(Math.toRadians(angle - fracture.normalAzimuthDeg()));
                assertEquals(expectedDepth, slice.fractures().get(index).get(0), 0.00005);
            }
            if (entry.getKey() == 1) {
                assertEquals(0.2500, slice.fractures().get(0).get(0), 0.00005);
                assertEquals(-16.1021, slice.fractures().get(0).get(1), 0.00005);
                assertEquals(0.5500, slice.fractures().get(1).get(0), 0.00005);
                assertEquals(-40.8934, slice.fractures().get(1).get(1), 0.00005);
            }
            ModelInputs.Model converted = ModelInputs.parse(InputPreparation.convert(slice, settings));
            InputPreparation.validate(slice, converted, settings, entry.getValue().getFileName().toString());
        }
    }

    @Test
    void lowestFormationContinuesWhenItsTopIsAboveTheOutputInterval() throws Exception {
        StratumModel model = new StratumModel(
                0.2, 100, 135,
                List.of(new StratumInterface(0.05)), List.of(20.0, 50.0));
        SliceGenerationService.Result result = new SliceGenerationService(temp).generate(model, 0, 1.5);
        String raw = Files.readString(result.output().resolve("Card_001_ang_000.00.txt"));
        ModelInputs.Model slice = ModelInputs.parse("1 1\n0 1 1\n" + raw);
        assertEquals(List.of(List.of(0.0, 50.0, 1.0), List.of(1.5, 50.0, 1.0)), slice.layers());
        assertTrue(slice.fractures().isEmpty());
    }

    @Test
    void sphereKeepsLegitimateExtraLayerAndBothFractures() throws Exception {
        StratumModel model = new StratumModel(
                0.1, 100, 90,
                List.of(new StratumInterface(0.1), new StratumInterface(0.4),
                        new StratumInterface(0.7), new StratumInterface(1.0),
                        new StratumInterface(1.4)),
                List.of(20.0, 15.0, 10.0, 30.0, 40.0, 50.0),
                List.of(new Fracture(0.3, 30, 30, 100),
                        new Fracture(0.7, 60, 30, 100)),
                List.of(new SphereFracture(0.4, 0, 0, Math.sqrt(0.02), 500)));
        SliceGenerationService.Result result = new SliceGenerationService(temp).generate(model, 0, 1.5);

        for (Path file : ModelInputs.indexFiles(result.output()).values()) {
            String raw = Files.readString(file);
            ModelInputs.Model slice = ModelInputs.parse("1 1\n0 1 1\n" + raw);
            assertEquals(8, slice.layers().size());
            assertEquals(2, slice.fractures().size());
            assertEquals(List.of(0.0, 0.1, 0.3, 0.5, 0.7, 1.0, 1.4, 1.5),
                    slice.layers().stream().map(row -> row.get(0)).toList());
            assertEquals(500, slice.layers().get(2).get(1));
            assertEquals(List.of(1.5, 50.0, 1.0), slice.layers().get(7));
        }
    }

    private static StratumModel referenceModel() {
        return new StratumModel(
                0.1016,
                100,
                120,
                List.of(
                        new StratumInterface(0.2),
                        new StratumInterface(0.245),
                        new StratumInterface(0.355),
                        new StratumInterface(0.38)),
                List.of(100.0, 30.0, 100.0, 30.0, 100.0),
                List.of(new Fracture(0.8, 30, 180, 0.5)),
                List.of(new SphereFracture(0.6, 90, 0.15, 0.08, 500)),
                List.of(new WedgeFracture(120, 0.6, 240, 0.8, 240, 0.4, 10)),
                List.of(new SurfaceText("TEST", 180, 1.0, 30, 0.1, 20)));
    }
}
