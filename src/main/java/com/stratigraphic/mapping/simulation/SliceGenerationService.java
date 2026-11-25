package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** Generates the 192 raw slices consumed by the electrical-imaging input conversion. */
public final class SliceGenerationService {
    public static final Path DEFAULT_ROOT = Path.of("C:\\Stratigraphic information");
    private static final String MODEL_INPUT = "NGI_PLOT_INP.txt";
    private static final String OUTPUT_DIRECTORY = "192_Slices_Output";
    private static final double SLICE_STEP = 0.001;

    public record Result(Path output, Path backup, String generation) { }

    private final Path root;

    public SliceGenerationService() {
        this(DEFAULT_ROOT);
    }

    SliceGenerationService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Result generate(StratumModel model, double start, double end) throws IOException {
        validateModel(model, start, end);
        Files.createDirectories(root);
        try (FileChannel channel = FileChannel.open(root.resolve("input-preparation.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException error) { throw new IOException("已有输入或切片生成任务正在运行", error); }
            if (lock == null) throw new IOException("已有输入或切片生成任务正在运行");
            try (lock) { return generateLocked(model, start, end); }
        }
    }

    private Result generateLocked(StratumModel model, double start, double end) throws IOException {
        Path output = root.resolve(OUTPUT_DIRECTORY);
        Path input = root.resolve(MODEL_INPUT);
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw new IOException("切片输出路径不是目录：" + output);
        }
        if (Files.exists(input) && !Files.isRegularFile(input)) {
            throw new IOException("模型输入路径不是文件：" + input);
        }
        boolean hadOutput = Files.isDirectory(output);
        boolean hadInput = Files.isRegularFile(input);
        Path backup = null;
        if (hadOutput || hadInput) {
            Path backups = Files.createDirectories(root.resolve("slice_backups"));
            backup = Files.createDirectory(backups.resolve("Slices_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    + "_" + UUID.randomUUID().toString().substring(0, 8)));
        }
        Path oldOutput = backup == null ? null : backup.resolve(OUTPUT_DIRECTORY);
        Path oldInput = backup == null ? null : backup.resolve(MODEL_INPUT);
        boolean outputMoved = false;
        boolean success = false;
        try {
            if (hadOutput) {
                move(output, oldOutput);
                outputMoved = true;
            }
            if (hadInput) Files.copy(input, oldInput);
            Files.createDirectory(output);
            writeAtomically(input, encode(model, start, end));
            String generation = writeSlices(model, start, end, output);
            validateOutput(output, start, end);
            SliceMaterials.read(output);
            success = true;
            return new Result(output, backup, generation);
        } finally {
            if (!success) {
                deleteOutput(output);
                if (outputMoved && Files.isDirectory(oldOutput)) move(oldOutput, output);
                if (hadInput && Files.isRegularFile(oldInput)) Files.copy(oldInput, input, StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(input);
            }
        }
    }

    private static String writeSlices(StratumModel model, double start, double end, Path output) throws IOException {
        Map<Integer, List<Double>> fracturePermittivities = new LinkedHashMap<>();
        for (int index = 0; index < ModelInputs.CHANNELS; index++) {
            double angle = index * 360.0 / ModelInputs.CHANNELS;
            Slice slice = slice(model, start, end, angle);
            StringBuilder text = new StringBuilder();
            row4(text, start, SLICE_STEP, end);
            text.append(slice.layers().size()).append('\n');
            for (double[] layer : slice.layers()) row4(text, layer);
            text.append(slice.fractures().size()).append('\n');
            for (double[] fracture : slice.fractures()) row4(text, fracture);
            String name = String.format(Locale.ROOT, "Card_%03d_ang_%06.2f.txt", index + 1, angle);
            Files.writeString(output.resolve(name), text, StandardOpenOption.CREATE_NEW);
            fracturePermittivities.put(index + 1, slice.fracturePermittivities());
        }
        return SliceMaterials.write(output, fracturePermittivities);
    }

    private static Slice slice(StratumModel model, double start, double end, double angle) {
        double dip = Math.toRadians(model.dipAngle() - 90);
        double[] layerBounds = new double[model.interfaceCount()];
        List<Double> breaks = new ArrayList<>();
        breaks.add(start);breaks.add(end);
        for (int index = 0; index < layerBounds.length; index++) {
            double bottom = model.interfaces().get(index).depth();
            layerBounds[index] = bottom - model.wellRadius() * Math.tan(dip) * Math.cos(Math.toRadians(angle));
            addBreak(breaks, layerBounds[index], start, end);
        }
        for (CubeAnomaly cube : model.cubeAnomalies()) {
            if (cube.intersectsWellAt(model.wellRadius(), angle)) {
                addBreak(breaks, cube.centerDepth() - cube.halfSide(), start, end);
                addBreak(breaks, cube.centerDepth() + cube.halfSide(), start, end);
            }
        }
        for (SphereFracture sphere : model.sphereFractures()) {
            double distance = Math.sqrt(square(sphere.distanceFromAxis()) + square(model.wellRadius())
                    - 2 * sphere.distanceFromAxis() * model.wellRadius()
                    * Math.cos(Math.toRadians(angle - sphere.azimuthDeg())));
            if (distance <= sphere.radius()) {
                double halfHeight = Math.sqrt(Math.max(0, square(sphere.radius()) - square(distance)));
                addBreak(breaks, sphere.centerDepth() - halfHeight, start, end);
                addBreak(breaks, sphere.centerDepth() + halfHeight, start, end);
            }
        }
        for (WedgeFracture wedge : model.wedgeFractures()) {
            double[] range = wedgeRange(wedge, angle);
            if (range != null) { addBreak(breaks, range[0], start, end);addBreak(breaks, range[1], start, end); }
        }
        breaks.sort(Double::compare);
        List<Double> unique = new ArrayList<>();
        for (double value : breaks)
            if (unique.isEmpty() || Math.abs(value - unique.get(unique.size() - 1)) > 1e-9) unique.add(value);
        List<double[]> layers = new ArrayList<>();
        for (int index = 0; index + 1 < unique.size(); index++) {
            double top = unique.get(index), bottom = unique.get(index + 1);
            if (bottom - top < 1e-4) continue;
            double middle = (top + bottom) / 2;
            int materialLayer = model.layerCount() - 1;
            for (int layer = 0; layer < layerBounds.length; layer++) {
                if (middle <= layerBounds[layer]) { materialLayer = layer;break; }
            }
            double resistivity = model.resistivityOf(materialLayer);
            double permittivity = model.dielectricConstantOf(materialLayer);
            for (CubeAnomaly cube : model.cubeAnomalies()) {
                if (cube.intersectsWellAt(model.wellRadius(), angle)
                        && middle >= cube.centerDepth() - cube.halfSide()
                        && middle <= cube.centerDepth() + cube.halfSide()) {
                    resistivity = cube.resistivity();
                    permittivity = cube.dielectricConstant();
                }
            }
            for (WedgeFracture wedge : model.wedgeFractures()) {
                double[] range = wedgeRange(wedge, angle);
                if (range != null && middle >= range[0] && middle <= range[1]) {
                    resistivity = wedge.resistivity();
                    permittivity = wedge.dielectricConstant();
                }
            }
            for (SphereFracture sphere : model.sphereFractures()) {
                double distance = Math.sqrt(square(sphere.distanceFromAxis()) + square(model.wellRadius())
                        - 2 * sphere.distanceFromAxis() * model.wellRadius()
                        * Math.cos(Math.toRadians(angle - sphere.azimuthDeg())));
                if (distance <= sphere.radius()) {
                    double halfHeight = Math.sqrt(Math.max(0, square(sphere.radius()) - square(distance)));
                    if (middle >= sphere.centerDepth() - halfHeight && middle <= sphere.centerDepth() + halfHeight) {
                        resistivity = sphere.resistivity();
                        permittivity = sphere.dielectricConstant();
                    }
                }
            }
            if (layers.isEmpty() || Math.abs(layers.get(layers.size() - 1)[1] - resistivity) > 1e-6
                    || Math.abs(layers.get(layers.size() - 1)[2] - permittivity) > 1e-6)
                layers.add(new double[]{top, resistivity, permittivity});
        }
        if (layers.isEmpty()) throw new IllegalArgumentException("当前模型没有可输出的有效层段");
        double[] last = layers.get(layers.size() - 1);
        layers.add(new double[]{end, last[1], last[2]});
        List<double[]> fractures = new ArrayList<>();
        List<Double> fracturePermittivities = new ArrayList<>();
        for (Fracture fracture : model.fractures()) {
            double tangent = Math.tan(Math.toRadians(fracture.normalElevationDeg()));
            double intersection = fracture.depth() - model.wellRadius() * tangent
                    * Math.cos(Math.toRadians(angle - fracture.normalAzimuthDeg()));
            if (intersection < start || intersection > end) continue;
            double slope = tangent * Math.sin(Math.toRadians(angle - fracture.normalAzimuthDeg()));
            fractures.add(new double[]{intersection, Math.toDegrees(Math.atan(slope)), fracture.resistivity(),
                    fracture.normalAzimuthDeg(), fracture.aperture()});
            fracturePermittivities.add(fracture.dielectricConstant());
        }
        return new Slice(List.copyOf(layers), List.copyOf(fractures), List.copyOf(fracturePermittivities));
    }

    private static double[] wedgeRange(WedgeFracture wedge, double angle) {
        double[] azimuths = {Math.toRadians(wedge.vertexAzimuthDeg()), Math.toRadians(wedge.leftAzimuthDeg()),
                Math.toRadians(wedge.rightAzimuthDeg())};
        double[] depths = {wedge.vertexDepth(), wedge.leftDepth(), wedge.rightDepth()};
        for (int index = 1; index < azimuths.length; index++) azimuths[index] = unwrap(azimuths[index], azimuths[index - 1]);
        double target = unwrap(Math.toRadians(angle), azimuths[0]);
        double minimum = Math.min(azimuths[0], Math.min(azimuths[1], azimuths[2]));
        double maximum = Math.max(azimuths[0], Math.max(azimuths[1], azimuths[2]));
        if (target < minimum - 1e-5 || target > maximum + 1e-5) return null;
        List<Double> intersections = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int next = (index + 1) % 3;
            double first = azimuths[index], second = azimuths[next];
            if (target < Math.min(first, second) - 1e-5 || target > Math.max(first, second) + 1e-5) continue;
            if (Math.abs(second - first) < 1e-6) {
                intersections.add(depths[index]);intersections.add(depths[next]);
            } else intersections.add(depths[index] + (target - first) / (second - first) * (depths[next] - depths[index]));
        }
        if (intersections.isEmpty()) return null;
        return new double[]{intersections.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                intersections.stream().mapToDouble(Double::doubleValue).max().orElseThrow()};
    }

    private static double unwrap(double angle, double reference) {
        while (angle - reference > Math.PI) angle -= 2 * Math.PI;
        while (angle - reference < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    private static double square(double value) { return value * value; }

    private static void addBreak(List<Double> breaks, double value, double start, double end) {
        if (Double.isFinite(value) && value > start && value < end) breaks.add(value);
    }

    private record Slice(List<double[]> layers, List<double[]> fractures, List<Double> fracturePermittivities) { }

    static String encode(StratumModel model, double start, double end) {
        StringBuilder text = new StringBuilder();
        text.append("% slice_materials_version=1\n");
        text.append("% ===== 1. 整体深度范围 =====\n");
        row(text, start, SLICE_STEP);
        text.append("% ===== 2. 层状地层 =====\n");
        text.append(model.layerCount()).append('\n');
        double matlabDip = model.dipAngle() - 90;
        for (int index = 0; index < model.layerCount(); index++) {
            double bottom = index < model.interfaceCount() ? model.interfaces().get(index).depth() : end;
            row(text, bottom, model.resistivityOf(index), model.dielectricConstantOf(index), matlabDip, 90);
        }
        text.append("% ===== 3. 裂缝 =====\n");
        text.append(model.fractures().size()).append('\n');
        for (Fracture fracture : model.fractures())
            row(text, fracture.depth(), fracture.normalElevationDeg(), fracture.resistivity(),
                    fracture.normalAzimuthDeg(), fracture.aperture());
        text.append("% ===== 4. 球体 =====\n");
        text.append(model.sphereFractures().size()).append('\n');
        for (SphereFracture sphere : model.sphereFractures())
            row(text, sphere.centerDepth(), sphere.azimuthDeg(), sphere.distanceFromAxis(), sphere.radius(), sphere.resistivity());
        text.append("% ===== 5. 楔形体 =====\n");
        text.append(model.wedgeFractures().size()).append('\n');
        for (WedgeFracture wedge : model.wedgeFractures())
            row(text, wedge.vertexAzimuthDeg(), wedge.vertexDepth(), wedge.leftAzimuthDeg(), wedge.leftDepth(),
                    wedge.rightAzimuthDeg(), wedge.rightDepth(), wedge.resistivity());
        text.append("% ===== 6. 曲面文字 =====\n");
        text.append(model.surfaceTexts().size()).append('\n');
        for (SurfaceText surfaceText : model.surfaceTexts())
            row(text, surfaceText.centerAzimuthDeg(), surfaceText.centerDepth(), surfaceText.angularSpanDeg(),
                    surfaceText.depthSpanM(), surfaceText.resistivity());
        text.append("% ===== 7. 正方体 =====\n");
        text.append(model.cubeAnomalies().size()).append('\n');
        for (CubeAnomaly cube : model.cubeAnomalies())
            row(text, cube.centerDepth(), cube.azimuthDeg(), cube.distanceFromAxis(), cube.sideLength(),
                    cube.resistivity(), cube.dielectricConstant());
        return text.toString();
    }

    private static void validateModel(StratumModel model, double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start >= end)
            throw new IllegalArgumentException("切片起始深度必须小于终止深度");
        double previous = start;
        for (int index = 0; index < model.interfaces().size(); index++) {
            double depth = model.interfaces().get(index).depth();
            if (depth <= previous || depth >= end)
                throw new IllegalArgumentException("第 " + (index + 1) + " 条界面深度必须严格位于切片起止深度之间并递增");
            previous = depth;
        }
    }

    private static void validateOutput(Path output, double start, double end) throws IOException {
        var indexed = ModelInputs.indexFiles(output);
        for (var entry : indexed.entrySet()) {
            ModelInputs.Model slice;
            try { slice = ModelInputs.parse("1 1\n0 1 1\n" + Files.readString(entry.getValue())); }
            catch (IllegalArgumentException error) { throw new IOException(entry.getValue().getFileName() + " 格式无效：" + error.getMessage(), error); }
            if (Math.abs(slice.begin() - start) > 5e-5 || Math.abs(slice.end() - end) > 5e-5
                    || Math.abs(slice.step() - SLICE_STEP) > 5e-7)
                throw new IOException(entry.getValue().getFileName() + " 的深度范围或采样间隔与当前模型不一致");
        }
    }

    private static void row(StringBuilder text, double... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) text.append("    ");
            text.append(String.format(Locale.ROOT, "%.10g", values[index]));
        }
        text.append('\n');
    }

    private static void row4(StringBuilder text, double... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) text.append("    ");
            text.append(String.format(Locale.ROOT, "%.4f", values[index]));
        }
        text.append('\n');
    }

    private static void writeAtomically(Path destination, String text) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, text, StandardOpenOption.CREATE_NEW);
            move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void move(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void deleteOutput(Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(root) || !normalized.getFileName().toString().equals(OUTPUT_DIRECTORY))
            throw new IOException("拒绝清理非切片输出目录：" + normalized);
        if (!Files.exists(normalized)) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
