package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPipelineTest {
    @TempDir Path temp;

    @Test
    void materialsSurviveAllElectrodesSamplingAndArchivedSnapshots() throws Exception {
        StratumModel model = detailedModel();
        var generation = new SliceGenerationService(temp).generate(model, 0, 1);
        writeReferences(temp);
        ModelInputs converted = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        ModelInputs sampled = SamplingPlan.prepare(converted, temp.resolve("sampled"), 0, 2.54, 1);
        InputPreparation.verifyCurrentInputs(sampled, temp, generation.generation());
        int minimumFractures = 2, maximumFractures = 0;
        for (var card : sampled.cards()) {
            var raw = raw(generation.output().resolve(card.name()));
            var actual = card.model();
            assertEquals(raw.layers().size(), actual.layers().size());
            for (int index = 0; index < raw.layers().size(); index++) {
                assertEquals(raw.layers().get(index).get(1), actual.layers().get(index).get(1));
                assertEquals(raw.layers().get(index).get(2), actual.layers().get(index).get(2));
            }
            double angle = (card.id() - 1) * 360.0 / 192;
            List<Double> eps = new ArrayList<>();
            for (Fracture fracture : model.fractures()) {
                double depth = fracture.depth() - model.wellRadius() * Math.tan(Math.toRadians(fracture.normalElevationDeg()))
                        * Math.cos(Math.toRadians(angle - fracture.normalAzimuthDeg()));
                if (depth >= 0 && depth <= 1) eps.add(fracture.dielectricConstant());
            }
            assertEquals(eps, actual.fractures().stream().map(row -> row.get(3)).toList());
            minimumFractures = Math.min(minimumFractures, eps.size());
            maximumFractures = Math.max(maximumFractures, eps.size());
        }
        assertTrue(minimumFractures < maximumFractures, "Filtered fractures must retain their own epsilon mapping");
        var first = sampled.cards().get(0).model();
        assertEquals(13, layerAt(first, 0.3).get(2));
        assertEquals(17, layerAt(first, 0.55).get(2));
        assertEquals(23, layerAt(first, 0.8).get(2));
        assertEquals(3, layerAt(first, 0.15).get(2));
        assertEquals(37, layerAt(first, 0.98).get(2));
        assertEquals(8, first.diameterInches());
        Path archive = Files.createDirectory(temp.resolve("archive"));
        sampled.copySnapshotTo(archive);
        ModelInputs restored = ModelInputs.restoreSnapshot(archive);
        InputPreparation.verifySnapshot(restored);
        assertEquals(first, restored.cards().get(0).model());
        Path materials = archive.resolve("sampling-original/conversion-source/" + SliceMaterials.FILE);
        assertTrue(Files.isRegularFile(materials));
        Files.writeString(materials, Files.readString(materials) + System.lineSeparator() + "generation=tampered");
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifySnapshot(restored));
    }

    @Test
    void equalResistivityDoesNotEraseDielectricOnlyBoundaries() throws Exception {
        var cube = new CubeAnomaly(0.3, 0, 0.1016, 0.08, 20, 13);
        var model = new StratumModel(0.1016, 100, 1, 90, List.of(new StratumInterface(0.5)),
                List.of(20.0, 20.0), List.of(3.0, 37.0), List.of(), List.of(), List.of(), List.of(), List.of(cube));
        new SliceGenerationService(temp).generate(model, 0, 1);
        writeReferences(temp);
        var inputs = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        var actual = inputs.cards().get(0).model();
        assertEquals(3, layerAt(actual, 0.2).get(2));
        assertEquals(13, layerAt(actual, 0.3).get(2));
        assertEquals(3, layerAt(actual, 0.4).get(2));
        assertEquals(37, layerAt(actual, 0.8).get(2));
        assertTrue(actual.layers().stream().allMatch(row -> row.get(1) == 20));
    }

    @Test
    void newGenerationEvenWithIdenticalValuesInvalidatesOldInputs() throws Exception {
        var first = new SliceGenerationService(temp).generate(detailedModel(), 0, 1);
        writeReferences(temp);
        var converted = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        var sampled = SamplingPlan.prepare(converted, temp.resolve("sampled"), 0, 2.54, 1);
        InputPreparation.verifyCurrentInputs(sampled, temp, first.generation());
        var next = new SliceGenerationService(temp).generate(detailedModel(), 0, 1);
        assertNotEquals(first.generation(), next.generation());
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifyCurrentInputs(sampled, temp, first.generation()));
        var current = InputPreparation.regenerate(temp, temp.resolve("next-cache"), (done, message) -> {}).inputs();
        InputPreparation.verifyCurrentInputs(current, temp, next.generation());
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifyCurrentInputs(current, temp, first.generation()));
        Files.writeString(temp.resolve("call.m"), Files.readString(temp.resolve("call.m")) + System.lineSeparator() + "% changed");
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifyCurrentInputs(current, temp, next.generation()));
    }

    @Test
    void missingMaterialsAndChangedCardsCannotSilentlyBecomeLegacyInputs() throws Exception {
        var generation = new SliceGenerationService(temp).generate(detailedModel(), 0, 1);
        writeReferences(temp);
        var inputs = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        Path card = ModelInputs.indexFiles(generation.output()).get(1);
        Files.writeString(card, Files.readString(card).replace("70.0000", "71.0000"));
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifyCurrentInputs(inputs, temp, generation.generation()));
        assertThrows(java.io.IOException.class, () -> InputPreparation.regenerate(temp, temp.resolve("tampered-cache"), (done, message) -> {}));
        Files.delete(generation.output().resolve(SliceMaterials.FILE));
        assertThrows(java.io.IOException.class, () -> InputPreparation.regenerate(temp, temp.resolve("missing-cache"), (done, message) -> {}));
        InputPreparation.verifySnapshot(inputs);
    }

    @Test
    void directInputFilesAreCheckedAgainImmediatelyBeforeNewComputation() throws Exception {
        Path source = Files.createDirectory(temp.resolve("direct"));
        String text = "2500000 8" + System.lineSeparator() + "0.005 1000 6" + System.lineSeparator()
                + "0 0.001 1" + System.lineSeparator() + "1" + System.lineSeparator()
                + "2 20 3" + System.lineSeparator() + "0" + System.lineSeparator();
        for (int id = 1; id <= 192; id++) Files.writeString(source.resolve(String.format("Card_%03d.txt", id)), text);
        var inputs = ModelInputs.load(source, temp.resolve("cache"), (done, message) -> {});
        InputPreparation.verifyCurrentInputs(inputs, temp, null);
        Files.writeString(source.resolve("Card_001.txt"), text.replace("2 20 3", "2 70 3"));
        assertThrows(java.io.IOException.class, () -> InputPreparation.verifyCurrentInputs(inputs, temp, null));
    }

    @Test
    void optionalRealSolverRespondsToEachAnomalyAndToPermittivityAlone() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.materialTrial"));
        List<ResponseData.Sample> baseline = realTrial("baseline", nativeModel(3, List.of(), List.of(), List.of()));
        Map<String, StratumModel> variants = new LinkedHashMap<>();
        variants.put("epsilon_only", nativeModel(37, List.of(), List.of(), List.of()));
        variants.put("cube", nativeModel(3, List.of(new CubeAnomaly(0.45, 0, 0.1016, 0.1, 500, 19)), List.of(), List.of()));
        variants.put("sphere", nativeModel(3, List.of(), List.of(new SphereFracture(0.45, 0, 0.1016, 0.05, 500, 23)), List.of()));
        variants.put("wedge", nativeModel(3, List.of(), List.of(), List.of(new WedgeFracture(330, 0.45, 30, 0.55, 30, 0.35, 500, 29))));
        StringBuilder report = new StringBuilder("variant,MD,baselineAmplitude,actualAmplitude,baselinePhase,actualPhase");
        report.append(System.lineSeparator());
        for (var variant : variants.entrySet()) {
            var actual = realTrial(variant.getKey(), variant.getValue());
            double amplitudeDifference = 0, phaseDifference = 0;
            assertEquals(baseline.size(), actual.size());
            for (int index = 0; index < actual.size(); index++) {
                var before = baseline.get(index);var after = actual.get(index);
                assertEquals(before.md(), after.md(), 1e-7);
                amplitudeDifference = Math.max(amplitudeDifference, Math.abs(before.amplitude() - after.amplitude()));
                phaseDifference = Math.max(phaseDifference, Math.abs(Math.IEEEremainder(before.phase() - after.phase(), 360)));
                report.append(String.format(Locale.ROOT, "%s,%.9g,%.12g,%.12g,%.12g,%.12g%n",
                        variant.getKey(), before.md(), before.amplitude(), after.amplitude(), before.phase(), after.phase()));
            }
            assertTrue(amplitudeDifference > 1e-6, variant.getKey() + " must change native amplitude");
            assertTrue(phaseDifference > 1e-6, variant.getKey() + " must change native phase");
        }
        Path reportPath = Path.of("target", "material-response-verification.csv");
        Files.writeString(reportPath, report);
        System.out.println("MATERIAL_RESPONSE_REPORT=" + reportPath.toAbsolutePath());
    }

    private List<ResponseData.Sample> realTrial(String name, StratumModel model) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        var generation = new SliceGenerationService(root).generate(model, 0, 1);
        writeReferences(root);
        var inputs = InputPreparation.regenerate(root, root.resolve("cache"), (done, message) -> {}).inputs();
        var sampled = SamplingPlan.prepare(inputs, root.resolve("sampled"), 0.449, 1, 0.451);
        InputPreparation.verifyCurrentInputs(sampled, root, generation.generation());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        var outcome = new ParallelSimulationRunner().run(sampled, SimulationRunner.DEFAULT_ROOT,
                root.resolve("results"), 1, 1, progress -> {}, () -> System.nanoTime() > deadline);
        assertEquals(1, outcome.completed());
        return SimulationRunner.readNative(outcome.batch().resolve("logs/Card_001_logs.txt"), 1);
    }

    private static StratumModel nativeModel(double epsilon, List<CubeAnomaly> cubes, List<SphereFracture> spheres, List<WedgeFracture> wedges) {
        return new StratumModel(0.1016, 100, 1, 90, List.of(new StratumInterface(0.4)), List.of(20.0, 20.0),
                List.of(epsilon, epsilon), List.of(), spheres, wedges, List.of(), cubes);
    }

    private static StratumModel detailedModel() {
        return new StratumModel(0.1016, 100, 1, 90, List.of(new StratumInterface(0.5)),
                List.of(20.0, 40.0), List.of(3.0, 37.0),
                List.of(new Fracture(0.02, 60, 0, 120, 41), new Fracture(0.95, 60, 180, 130, 47)),
                List.of(new SphereFracture(0.55, 0, 0.1016, 0.04, 80, 17)),
                List.of(new WedgeFracture(330, 0.8, 30, 0.9, 30, 0.7, 90, 23)), List.of(),
                List.of(new CubeAnomaly(0.3, 0, 0.1016, 0.08, 70, 13)));
    }

    private static List<Double> layerAt(ModelInputs.Model model, double depth) {
        for (var layer : model.layers()) if (depth < layer.get(0)) return layer;
        return model.layers().get(model.layers().size() - 1);
    }

    private static ModelInputs.Model raw(Path file) throws Exception {
        return ModelInputs.parse("1 1" + System.lineSeparator() + "0 1 1" + System.lineSeparator() + Files.readString(file));
    }

    static void writeReferences(Path root) throws Exception {
        Files.writeString(root.resolve("call.m"), """
                freq = 2.5e6;
                cali = 8;
                sd_off = 0.005;
                rm = 1000;
                epsr_m = 6;
                epsr_all = 10;
                n_card = 192;
                input_folder = '192_Slices_Output';
                output_folder = '192_NGI_Input';
                script_dir = fileparts(mfilename('fullpath'));
                layer_data(jj+1,1),layer_data(jj,2),epsr_all
                layer_data(nl,1)+1,layer_data(nl,2),epsr_all
                fra_data(jj,1),0,fra_data(jj,3),epsr_all,fra_data(jj,5)
                """);
        Files.writeString(root.resolve("transf.m"), "% Fortran reconstruction reference");
    }
}
