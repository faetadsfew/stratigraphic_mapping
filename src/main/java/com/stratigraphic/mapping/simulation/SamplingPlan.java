package com.stratigraphic.mapping.simulation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A batch's measured interval and explicit endpoint extension, saved with its immutable inputs. */
public record SamplingPlan(double begin, double requestedStep, double end, double step,
                           double fullBegin, double fullEnd) {
    public static final double MAX_STEP = 0.00254;
    private static final long MAX_POINTS = 2_000_000;

    public record InputParameters(double frequency, double diameterInches, double offset,
                                  double mudCakeRho, double mudCakeEps) {
        public InputParameters {
            for (double value : new double[]{frequency, diameterInches, offset, mudCakeRho, mudCakeEps})
                if (!Double.isFinite(value)) throw new IllegalArgumentException("输入文件参数必须为有限数字");
            if (frequency <= 0) throw new IllegalArgumentException("频率必须大于 0");
            if (diameterInches <= 0) throw new IllegalArgumentException("井径必须大于 0");
            if (offset < 0) throw new IllegalArgumentException("油膜厚度不能小于 0");
            if (mudCakeRho <= 0) throw new IllegalArgumentException("泥饼电阻率必须大于 0");
            if (mudCakeEps <= 0) throw new IllegalArgumentException("泥饼介电常数必须大于 0");
        }

        public static InputParameters from(ModelInputs.Model model) {
            return new InputParameters(model.frequency(), model.diameterInches(), model.offset(), model.mudRho(), model.mudEps());
        }
    }

    public static SamplingPlan create(double begin, double requestedStep, double end, double fullBegin, double fullEnd) {
        for (double value : new double[]{begin, requestedStep, end, fullBegin, fullEnd})
            if (!Double.isFinite(value)) throw new IllegalArgumentException("采样参数必须为有限数字");
        if (requestedStep <= 0 || requestedStep > MAX_STEP)
            throw new IllegalArgumentException("采样间距必须大于 0 且不超过 2.54 毫米");
        if (fullEnd <= fullBegin || begin < fullBegin || end > fullEnd || end <= begin)
            throw new IllegalArgumentException("起测点必须小于停止点，且计算区间应位于模型 MD " + fullBegin + "～" + fullEnd + " m 内");
        long intervals = (long)Math.ceil((end - begin) / requestedStep - 1e-10);
        intervals = Math.max(1, intervals);
        if (intervals + 1 > MAX_POINTS) throw new IllegalArgumentException("计算深度点超过 200 万，请增大采样间距");
        double step = Math.abs(intervals * requestedStep - (end - begin)) <= 1e-12
                ? requestedStep : Math.nextDown((end - begin) / intervals);
        if (Math.ceil((fullEnd - fullBegin) / step) + 3 > MAX_POINTS)
            throw new IllegalArgumentException("含延拓的深度点超过 200 万，请增大采样间距");
        return new SamplingPlan(begin, requestedStep, end, step, fullBegin, fullEnd);
    }

    public boolean extended() { return begin > fullBegin || end < fullEnd; }
    public long computedPoints() { return (long)Math.floor((end - begin) / step + 1e-7) + 1; }
    public String description() {
        String text = "实际计算 MD：" + begin + "～" + end + " m\n请求采样间距：" + requestedStep * 1000
                + " mm\n实际采样间距：" + String.format(Locale.ROOT, "%.9g", step * 1000) + " mm";
        if (extended()) {
            text += "\n区间外为端点延拓（非求解值）：";
            if (begin > fullBegin) text += fullBegin + "～" + begin + " m 复制各电极起测点";
            if (begin > fullBegin && end < fullEnd) text += "；";
            if (end < fullEnd) text += end + "～" + fullEnd + " m 复制各电极停止点";
        } else text += "\n全深度计算，未延拓";
        return text;
    }

    private void store(Properties properties) {
        properties.setProperty("sampling.version", "1");
        properties.setProperty("sampling.begin", Double.toString(begin));
        properties.setProperty("sampling.requested.step", Double.toString(requestedStep));
        properties.setProperty("sampling.end", Double.toString(end));
        properties.setProperty("sampling.step", Double.toString(step));
        properties.setProperty("sampling.full.begin", Double.toString(fullBegin));
        properties.setProperty("sampling.full.end", Double.toString(fullEnd));
        properties.setProperty("sampling.computed.points", Long.toString(computedPoints()));
        properties.setProperty("sampling.extended", Boolean.toString(extended()));
        properties.setProperty("sampling.description", description());
    }

    private static void store(InputParameters parameters, Properties properties) {
        properties.setProperty("input.parameters.version", "1");
        properties.setProperty("input.frequency", Double.toString(parameters.frequency()));
        properties.setProperty("input.diameter.inches", Double.toString(parameters.diameterInches()));
        properties.setProperty("input.offset", Double.toString(parameters.offset()));
        properties.setProperty("input.mud.cake.rho", Double.toString(parameters.mudCakeRho()));
        properties.setProperty("input.mud.cake.eps", Double.toString(parameters.mudCakeEps()));
    }

    private static InputParameters readInputParameters(Properties properties) throws IOException {
        if (properties.getProperty("input.parameters.version") == null) return null;
        if (!properties.getProperty("input.parameters.version").equals("1")) throw new IOException("不支持的输入文件参数版本");
        try {
            return new InputParameters(Double.parseDouble(properties.getProperty("input.frequency")),
                    Double.parseDouble(properties.getProperty("input.diameter.inches")),
                    Double.parseDouble(properties.getProperty("input.offset")),
                    Double.parseDouble(properties.getProperty("input.mud.cake.rho")),
                    Double.parseDouble(properties.getProperty("input.mud.cake.eps")));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IOException("输入文件参数无效：" + ex.getMessage(), ex);
        }
    }
    static SamplingPlan read(Path snapshot) throws IOException {
        Properties properties = properties(snapshot.resolve("inputs.properties"));
        if (properties.getProperty("sampling.version") == null) return null; // Old batches keep their original behavior.
        if (!properties.getProperty("sampling.version").equals("1")) throw new IOException("不支持的采样快照版本");
        try {
            SamplingPlan plan = create(Double.parseDouble(properties.getProperty("sampling.begin")),
                    Double.parseDouble(properties.getProperty("sampling.requested.step")), Double.parseDouble(properties.getProperty("sampling.end")),
                    Double.parseDouble(properties.getProperty("sampling.full.begin")), Double.parseDouble(properties.getProperty("sampling.full.end")));
            if (Double.parseDouble(properties.getProperty("sampling.step")) != plan.step()) throw new IllegalArgumentException("实际采样间距与记录不一致");
            return plan;
        } catch (IllegalArgumentException | NullPointerException ex) { throw new IOException("采样快照参数无效：" + ex.getMessage(), ex); }
    }
    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }
    private ModelInputs.Model apply(ModelInputs.Model original) {
        return new ModelInputs.Model(original.frequency(), original.diameterInches(), original.offset(), original.mudRho(), original.mudEps(),
                begin, step, end, original.layers(), original.fractures());
    }

    private ModelInputs.Model apply(ModelInputs.Model original, InputParameters parameters) {
        ModelInputs.Model sampled = apply(original);
        if (parameters == null) return sampled;
        return new ModelInputs.Model(parameters.frequency(), parameters.diameterInches(), parameters.offset(),
                parameters.mudCakeRho(), parameters.mudCakeEps(), sampled.begin(), sampled.step(), sampled.end(),
                sampled.layers(), sampled.fractures());
    }
    private static String encode(ModelInputs.Model model) {
        StringBuilder text = new StringBuilder();
        text.append(model.frequency()).append(' ').append(model.diameterInches()).append('\n');
        text.append(model.offset()).append(' ').append(model.mudRho()).append(' ').append(model.mudEps()).append('\n');
        text.append(model.begin()).append(' ').append(model.step()).append(' ').append(model.end()).append('\n');
        text.append(model.layers().size()).append('\n');
        for (var row : model.layers()) append(text, row);
        text.append(model.fractures().size()).append('\n');
        for (var row : model.fractures()) append(text, row);
        return text.toString();
    }
    private static void append(StringBuilder text, List<Double> row) {
        for (int i = 0; i < row.size(); i++) { if (i > 0) text.append(' '); text.append(row.get(i)); }
        text.append('\n');
    }
    private static void requireRange(ModelInputs inputs, double fullBegin, double fullEnd) throws IOException {
        for (var card : inputs.cards())
            if (card.model().begin() != fullBegin || card.model().end() != fullEnd)
                throw new IOException(card.name() + "：原始模型深度范围不一致，不能应用统一计算区间");
    }
    public static ModelInputs prepare(ModelInputs original, Path cache, double begin, double stepMm, double end) throws IOException {
        return prepare(original, cache, begin, stepMm, end, InputParameters.from(original.cards().get(0).model()));
    }

    public static ModelInputs prepare(ModelInputs original, Path cache, double begin, double stepMm, double end,
                                      InputParameters inputParameters) throws IOException {
        InputPreparation.verifySnapshot(original);
        var model = original.cards().get(0).model();
        requireRange(original, model.begin(), model.end());
        SamplingPlan plan = create(begin, stepMm / 1000, end, model.begin(), model.end());
        Files.createDirectories(cache);
        Path snapshot = Files.createTempDirectory(cache, "sampling-");
        original.copySnapshotTo(Files.createDirectory(snapshot.resolve("sampling-original")));
        Properties manifest = new Properties();
        manifest.setProperty("source", properties(original.directory().resolve("inputs.properties")).getProperty("source"));
        plan.store(manifest);
        store(Objects.requireNonNull(inputParameters), manifest);
        for (var card : original.cards()) {
            var sampled = plan.apply(card.model(), inputParameters);
            Files.writeString(snapshot.resolve(card.name()), encode(sampled), StandardOpenOption.CREATE_NEW);
            manifest.setProperty("card." + card.id(), card.name());
            manifest.setProperty("model." + card.id(), sampled.toString());
            manifest.setProperty("sampling.original.sha256." + card.id(), SimulationHistory.sha256(card.snapshot()));
        }
        try (var out = Files.newOutputStream(snapshot.resolve("inputs.properties"))) { manifest.store(out, "Sampling snapshot; original model retained"); }
        ModelInputs inputs = ModelInputs.restoreSnapshot(snapshot);
        verifySnapshot(inputs);
        return inputs;
    }
    static void verifySnapshot(ModelInputs inputs) throws IOException {
        SamplingPlan plan = Objects.requireNonNull(read(inputs.directory()));
        Path directory = inputs.directory().resolve("sampling-original");
        Properties originalManifest = properties(directory.resolve("inputs.properties"));
        if (originalManifest.containsKey("sampling.version")) throw new IOException("采样快照不允许嵌套改写");
        ModelInputs original = ModelInputs.restoreSnapshot(directory);
        InputPreparation.verifySnapshot(original);
        requireRange(original, plan.fullBegin(), plan.fullEnd());
        Properties manifest = properties(inputs.directory().resolve("inputs.properties"));
        InputParameters inputParameters = readInputParameters(manifest);
        for (int i = 0; i < ModelInputs.CHANNELS; i++) {
            var card = inputs.cards().get(i); var base = original.cards().get(i);
            if (!card.name().equals(base.name()) || !SimulationHistory.sha256(base.snapshot()).equals(manifest.getProperty("sampling.original.sha256." + card.id()))
                    || !plan.apply(base.model(), inputParameters).equals(card.model()) || !card.model().equals(ModelInputs.parse(Files.readString(card.snapshot()))))
                throw new IOException(card.name() + "：采样输入与原始模型/本批采样参数不一致，不能开始或恢复计算");
        }
    }
    static void record(ModelInputs inputs, Properties record) throws IOException {
        SamplingPlan plan = read(inputs.directory());
        if (plan != null) plan.store(record);
    }
    private static ResponseData.Sample at(ResponseData.Sample endpoint, double md) {
        return new ResponseData.Sample(md, endpoint.electrode(), endpoint.real(), endpoint.imaginary(), endpoint.amplitude(), endpoint.phase());
    }
    public List<ResponseData.Sample> extend(List<ResponseData.Sample> computed) throws IOException {
        if (computed.isEmpty()) throw new IOException("不能延拓空响应");
        if (Math.abs(computed.get(0).md() - begin) > 5.1e-6 || Math.abs(computed.get(computed.size() - 1).md() - end) > 5.1e-6)
            throw new IOException("计算结果未覆盖本批起止端点，不能延拓");
        if (!extended()) return computed;
        var result = new ArrayList<ResponseData.Sample>();
        var first = computed.get(0); var last = computed.get(computed.size() - 1);
        int before = (int)Math.ceil((begin - fullBegin) / step - 1e-8);
        for (int i = before; i >= 1; i--) {
            double md = Math.max(fullBegin, offset(begin, -i));
            if (md < first.md()) result.add(at(first, md));
        }
        result.addAll(computed);
        int after = (int)Math.ceil((fullEnd - end) / step - 1e-8);
        for (int i = 1; i <= after; i++) {
            double md = Math.min(fullEnd, offset(end, i));
            if (md > last.md()) result.add(at(last, md));
        }
        return List.copyOf(result);
    }
    private double offset(double origin, int count) {
        return BigDecimal.valueOf(origin).add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(count))).doubleValue();
    }
    static void writeResult(Path staged, NavigableMap<Integer, List<ResponseData.Sample>> results,
                            Properties record, BooleanSupplier stop) throws IOException {
        SamplingPlan plan = null;
        if (record.containsKey("sampling.version")) plan = create(Double.parseDouble(record.getProperty("sampling.begin")),
                Double.parseDouble(record.getProperty("sampling.requested.step")), Double.parseDouble(record.getProperty("sampling.end")),
                Double.parseDouble(record.getProperty("sampling.full.begin")), Double.parseDouble(record.getProperty("sampling.full.end")));
        NavigableMap<Integer, List<ResponseData.Sample>> published = new TreeMap<>();
        for (var entry : results.entrySet()) {
            if (stop.getAsBoolean()) throw new SimulationRunner.StoppedException("用户已停止计算");
            published.put(entry.getKey(), plan == null ? entry.getValue() : plan.extend(entry.getValue()));
        }
        var reference = published.firstEntry().getValue();
        try (var writer = Files.newBufferedWriter(staged)) {
            for (int depth = 0; depth < reference.size(); depth++) {
                if (stop.getAsBoolean()) throw new SimulationRunner.StoppedException("用户已停止计算");
                for (var entry : published.entrySet()) {
                    var sample = entry.getValue().get(depth);
                    writer.write(new ResponseData.Sample(reference.get(depth).md(), entry.getKey(), sample.real(), sample.imaginary(), sample.amplitude(), sample.phase()).line());
                    writer.newLine();
                }
            }
        }
        record.setProperty("result.depth.points", Integer.toString(reference.size()));
        record.setProperty("result.records", Long.toString((long)reference.size() * published.size()));
    }
}
