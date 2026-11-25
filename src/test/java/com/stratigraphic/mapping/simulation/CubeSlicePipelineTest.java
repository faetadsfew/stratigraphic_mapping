package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CubeSlicePipelineTest {
    @TempDir Path temp;

    @Test
    void cubeSurvivesInputConversionAndSampling() throws Exception {
        CubeAnomaly cube = new CubeAnomaly(0.45, 210, 0.16, 0.14, 70);
        StratumModel model = model(List.of(cube), 20, 30);
        var generated = new SliceGenerationService(temp).generate(model, 0, 1);
        var settings = new InputPreparation.Settings(2.5e6, 8, 0.005, 1000, 6, 10,
                generated.output(), temp.resolve("192_NGI_Input"), temp.resolve("call.m"), temp.resolve("transf.m"));
        int changed = 0;
        for (var entry : ModelInputs.indexFiles(generated.output()).entrySet()) {
            ModelInputs.Model raw = raw(entry.getValue());
            double angle = (entry.getKey() - 1) * 360.0 / ModelInputs.CHANNELS;
            double relative = Math.toRadians(angle - cube.normalizedAzimuthDeg());
            boolean intersects = Math.abs(model.wellRadius() * Math.cos(relative) - cube.distanceFromAxis()) <= cube.halfSide()
                    && Math.abs(model.wellRadius() * Math.sin(relative)) <= cube.halfSide();
            if (intersects) changed++;
            ModelInputs.Model converted = ModelInputs.parse(InputPreparation.convert(raw, settings));
            InputPreparation.validate(raw, converted, settings, entry.getValue().toString());
            for (double depth : new double[]{0.37, 0.39, 0.41, 0.45, 0.51, 0.53}) {
                double expected = intersects && depth >= 0.38 && depth < 0.52 ? 70 : depth < 0.4 ? 20 : 30;
                assertEquals(expected, InputPreparation.resistivity(raw, depth, false), "raw electrode=" + entry.getKey() + " MD=" + depth);
                assertEquals(expected, InputPreparation.resistivity(converted, depth, true), "converted electrode=" + entry.getKey() + " MD=" + depth);
            }
        }
        assertTrue(changed > 0 && changed < ModelInputs.CHANNELS);
        assertTrue(Files.readString(temp.resolve("NGI_PLOT_INP.txt")).contains("% ===== 7. 正方体 =====\n1\n"));
        writeReferences(temp);
        ModelInputs inputs = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        ModelInputs sampled = SamplingPlan.prepare(inputs, temp.resolve("sampled"), 0, 2.54, 1);
        InputPreparation.verifySnapshot(sampled);
        SamplingPlan.verifySnapshot(sampled);
        for (int index = 0; index < ModelInputs.CHANNELS; index++) {
            ModelInputs.Model original = raw(generated.output().resolve(inputs.cards().get(index).name()));
            ModelInputs.Model actual = sampled.cards().get(index).model();
            assertEquals(inputs.cards().get(index).model().layers(), actual.layers());
            for (int point = 0; point < 395; point++) {
                double depth = actual.begin() + point * actual.step();
                if (depth > actual.end()) break;
                assertEquals(InputPreparation.resistivity(original, depth, false),
                        InputPreparation.resistivity(actual, depth, true), "sampled electrode=" + (index + 1) + " MD=" + depth);
            }
        }
    }

    @Test
    void seamCrossingAndNonIntersectingCubesAreHandled() throws Exception {
        List<CubeAnomaly> cubes = List.of(
                new CubeAnomaly(0.45, 359, 0.1016, 0.08, 70),
                new CubeAnomaly(0.45, 180, 0.8, 0.02, 700),
                new CubeAnomaly(2, 0, 0, 0.4, 800));
        var output = new SliceGenerationService(temp).generate(model(cubes, 20, 20), 0, 1).output();
        var indexed = ModelInputs.indexFiles(output);
        assertEquals(70, InputPreparation.resistivity(raw(indexed.get(1)), 0.45, false));
        assertEquals(70, InputPreparation.resistivity(raw(indexed.get(192)), 0.45, false));
        assertEquals(20, InputPreparation.resistivity(raw(indexed.get(97)), 0.45, false));
        for (Path path : indexed.values()) {
            assertTrue(raw(path).layers().stream().allMatch(row -> row.get(1) == 20 || row.get(1) == 70));
        }
    }

    @Test
    void cubesAtIntervalEndsAreClippedAndTheLastMaterialContinues() throws Exception {
        List<CubeAnomaly> cubes = List.of(new CubeAnomaly(0.02, 0, 0, 0.4, 70),
                new CubeAnomaly(1, 0, 0, 0.4, 90));
        var output = new SliceGenerationService(temp).generate(model(cubes, 20, 20), 0, 1).output();
        for (Path path : ModelInputs.indexFiles(output).values()) {
            var slice = raw(path);
            assertEquals(List.of(0.0, 0.22, 0.8, 1.0), slice.layers().stream().map(row -> row.get(0)).toList());
            assertEquals(List.of(70.0, 20.0, 90.0, 90.0), slice.layers().stream().map(row -> row.get(1)).toList());
            assertEquals(70, InputPreparation.resistivity(slice, 0, false));
            assertEquals(90, InputPreparation.resistivity(slice, 1, false));
        }
    }

    @Test
    void optionalRealSolverRespondsToCubeInBothAmplitudeAndPhase() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.cubeTrial"));
        assertTrue(Files.isRegularFile(SimulationRunner.DEFAULT_ROOT.resolve(SimulationRunner.EXE)));
        var baseline = realTrial("baseline", List.of());
        var withCube = realTrial("cube", List.of(new CubeAnomaly(0.45, 0, 0.1016, 0.1, 500)));
        assertEquals(3, baseline.size());assertEquals(3, withCube.size());
        StringBuilder report = new StringBuilder("MD,baselineAmplitude,cubeAmplitude,baselinePhase,cubePhase\n");
        double amplitudeDifference = 0, phaseDifference = 0;
        for (int index = 0; index < baseline.size(); index++) {
            var before = baseline.get(index);var after = withCube.get(index);
            assertEquals(before.md(), after.md(), 1e-7);
            amplitudeDifference = Math.max(amplitudeDifference, Math.abs(before.amplitude() - after.amplitude()));
            phaseDifference = Math.max(phaseDifference, Math.abs(Math.IEEEremainder(before.phase() - after.phase(), 360)));
            report.append(String.format(Locale.ROOT, "%.9g,%.12g,%.12g,%.12g,%.12g%n",
                    before.md(), before.amplitude(), after.amplitude(), before.phase(), after.phase()));
        }
        Path reportPath = Path.of("target", "cube-response-verification.csv");
        Files.writeString(reportPath, report);
        System.out.println("CUBE_RESPONSE_REPORT=" + reportPath.toAbsolutePath());
        assertTrue(amplitudeDifference > 1e-6, "Cube must change the native amplitude response");
        assertTrue(phaseDifference > 1e-6, "Cube must change the native phase response");
    }

    private List<ResponseData.Sample> realTrial(String name, List<CubeAnomaly> cubes) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        new SliceGenerationService(root).generate(model(cubes, 20, 20), 0, 1);
        writeReferences(root);
        ModelInputs inputs = InputPreparation.regenerate(root, root.resolve("cache"), (done, message) -> {}).inputs();
        ModelInputs sampled = SamplingPlan.prepare(inputs, root.resolve("sampled"), 0.449, 1, 0.451);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        var outcome = new ParallelSimulationRunner().run(sampled, SimulationRunner.DEFAULT_ROOT,
                root.resolve("results"), 1, 1, progress -> {}, () -> System.nanoTime() > deadline);
        assertEquals(1, outcome.completed());
        return SimulationRunner.readNative(outcome.batch().resolve("logs/Card_001_logs.txt"), 1);
    }

    private static StratumModel model(List<CubeAnomaly> cubes, double upperRho, double lowerRho) {
        return new StratumModel(0.1016, 100, 90, List.of(new StratumInterface(0.4)),
                List.of(upperRho, lowerRho), List.of(), List.of(), List.of(), List.of(), cubes);
    }

    private static ModelInputs.Model raw(Path path) throws Exception {
        return ModelInputs.parse("1 1\n0 1 1\n" + Files.readString(path));
    }

    private static void writeReferences(Path root) throws Exception {
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
        Files.writeString(root.resolve("transf.m"), "% Fortran reconstruction reference\n");
    }
}
