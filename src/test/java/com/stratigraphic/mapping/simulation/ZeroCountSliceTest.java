package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.reader.StratumModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZeroCountSliceTest {
    @TempDir Path temp;

    @Test
    void allAnomaliesReachConvertedInputsWithNoLayerInterfaces() throws Exception {
        var model = StratumModelReader.read(Path.of(getClass().getResource("/formation-anomalies-only.txt").toURI()));
        var generated = new SliceGenerationService(temp).generate(model, 0, 1.5);
        var indexed = ModelInputs.indexFiles(generated.output());
        MaterialPipelineTest.writeReferences(temp);
        var converted = InputPreparation.regenerate(temp, temp.resolve("cache"), (done, message) -> {}).inputs();
        var sampled = SamplingPlan.prepare(converted, temp.resolve("sampled"), 0, 2.54, 1.5);
        InputPreparation.verifyCurrentInputs(sampled, temp, generated.generation());
        assertEquals(192, sampled.cards().size());
        for (var card : sampled.cards()) {
            var raw = ModelInputs.parse("1 1" + System.lineSeparator() + "0 1 1" + System.lineSeparator() + Files.readString(indexed.get(card.id())));
            assertTrue(raw.fractures().isEmpty());
            assertTrue(card.model().fractures().isEmpty());
            for (double depth : new double[]{0.2, 0.45, 0.6, 0.7, 1.4}) {
                assertEquals(InputPreparation.resistivity(raw, depth, false),
                        InputPreparation.resistivity(card.model(), depth, true), "electrode=" + card.id() + " MD=" + depth);
            }
        }
        assertEquals(50, InputPreparation.resistivity(sampled.cards().get(48).model(), 0.6, true));
        assertEquals(70, InputPreparation.resistivity(sampled.cards().get(112).model(), 0.45, true));
        assertEquals(40, InputPreparation.resistivity(sampled.cards().get(106).model(), 0.7, true));
        assertEquals(20, InputPreparation.resistivity(sampled.cards().get(0).model(), 0.2, true));
    }

    @Test
    void zeroInterfacesAndZeroAnomaliesGenerateUniformBackgroundSlices() throws Exception {
        var model = new StratumModel(0.1, 100, 90, List.of(), List.of(20.0));
        var generated = new SliceGenerationService(temp).generate(model, 0, 1.5);
        for (var file : ModelInputs.indexFiles(generated.output()).values()) {
            var raw = ModelInputs.parse("1 1" + System.lineSeparator() + "0 1 1" + System.lineSeparator() + Files.readString(file));
            assertTrue(raw.fractures().isEmpty());
            assertEquals(20, InputPreparation.resistivity(raw, 0.7, false));
            assertTrue(raw.layers().stream().allMatch(row -> row.get(1) == 20));
        }
    }
}
