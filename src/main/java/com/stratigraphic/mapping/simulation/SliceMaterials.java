package com.stratigraphic.mapping.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class SliceMaterials {
    static final String FILE = "slice-materials.properties";
    static final String RULE = "next-layer-boundary-materials-v2";

    record Data(String generation, Map<Integer, List<Double>> fracturePermittivities) {
        List<Double> fractures(int electrode, int count) throws IOException {
            List<Double> values = fracturePermittivities.get(electrode);
            if (values == null || values.size() != count)
                throw new IOException("电极 " + electrode + " 的裂缝介电常数与原始切片不匹配，请在第一页重新生成切片");
            return values;
        }
    }

    private SliceMaterials() { }

    static String write(Path directory, Map<Integer, List<Double>> fractures) throws IOException {
        String generation = UUID.randomUUID().toString();
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("generation", generation);
        for (var entry : ModelInputs.indexFiles(directory).entrySet()) {
            int electrode = entry.getKey();
            List<Double> values = fractures.get(electrode);
            properties.setProperty("card." + electrode, entry.getValue().getFileName().toString());
            properties.setProperty("sha256." + electrode, SimulationHistory.sha256(entry.getValue()));
            properties.setProperty("fracture.count." + electrode, Integer.toString(values.size()));
            for (int index = 0; index < values.size(); index++)
                properties.setProperty("fracture.eps." + electrode + "." + index, Double.toString(values.get(index)));
        }
        try (var output = Files.newOutputStream(directory.resolve(FILE))) {
            properties.store(output, "Precise material permittivities for Java-generated slices");
        }
        return generation;
    }

    static Data read(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.exists(file)) return null;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        if (!"1".equals(properties.getProperty("version")))
            throw new IOException("不支持的切片材料版本，请在第一页重新生成切片");
        String generation = properties.getProperty("generation");
        try { UUID.fromString(generation); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("切片版本记录无效，请在第一页重新生成切片", error);
        }
        Map<Integer, List<Double>> values = new LinkedHashMap<>();
        for (var entry : ModelInputs.indexFiles(directory).entrySet()) {
            int electrode = entry.getKey();
            if (!entry.getValue().getFileName().toString().equals(properties.getProperty("card." + electrode))
                    || !SimulationHistory.sha256(entry.getValue()).equals(properties.getProperty("sha256." + electrode)))
                throw new IOException("电极 " + electrode + " 的切片与材料记录不一致，请在第一页重新生成切片");
            try {
                int count = Integer.parseInt(properties.getProperty("fracture.count." + electrode));
                if (count < 0 || count > 100000) throw new IllegalArgumentException("裂缝数量无效");
                List<Double> eps = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    double value = Double.parseDouble(properties.getProperty("fracture.eps." + electrode + "." + index));
                    if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("介电常数无效");
                    eps.add(value);
                }
                values.put(electrode, List.copyOf(eps));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IOException("电极 " + electrode + " 的裂缝材料记录无效", error);
            }
        }
        return new Data(generation, Map.copyOf(values));
    }
}
