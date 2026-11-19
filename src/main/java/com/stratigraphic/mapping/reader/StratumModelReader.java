package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取井眼 / 地层参数文本文件。
 *
 * <p>编码可以是 UTF-8、带 BOM 的 UTF-8、UTF-16，或 Windows 记事本默认的 GBK，自动识别。
 *
 * <p>文件格式（{@code #} 起为注释，空行忽略）：
 * <pre>
 *   n          = 5                      界面个数
 *   dip        = 90                     统一地层倾角 (°)
 *   wellRadius = 0.1                    井眼半径 (m)
 *   wellRho    = 100                    井眼电阻率 (Ω·m)
 *   rho        = 20 15 10 30 40 50      由浅到深 n+1 层电阻率 (Ω·m)
 *   0.80                                界面行：与井轴的交点深度 (m)
 *   1.00
 *   ...
 * </pre>
 *
 * <p>界面行不带等号，每行一个深度。为了兼容旧文件，也接受「深度 夹角」两列；
 * 此时文件里没有 {@code dip} 时采用第一条界面的夹角，并统一应用到全部界面。
 * {@code n} 可以不写，此时按实际界面行数推断。
 */
public final class StratumModelReader {

    private StratumModelReader() {
    }

    /** 从磁盘读。文件可以是 UTF-8 / GBK / 带 BOM 的 UTF-8 / UTF-16，自动识别。 */
    public static StratumModel read(Path path) throws IOException {
        return parse(decode(Files.readAllBytes(path), "文件「" + path.getFileName() + "」"));
    }

    // =========================================================
    // 编码识别
    //
    // Windows 记事本存中文文本默认是 GBK，直接按 UTF-8 读会抛
    // MalformedInputException: Input length = 1，所以这里先看 BOM，
    // 没有 BOM 就先按 UTF-8 严格解一遍，解不通再按 GBK 解。
    // =========================================================

    private static List<String> decode(byte[] bytes, String what) throws IOException {

        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return split(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return split(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return split(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE));
        }

        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return split(utf8);
        }

        Charset gbk = gbkCharset();
        if (gbk != null) {
            String decoded = strictDecode(bytes, gbk);
            if (decoded != null) {
                return split(decoded);
            }
        }

        throw new IOException(what + "既不是 UTF-8 也不是 GBK 编码，"
                + "请用记事本「另存为」时把编码选成 UTF-8 再试");
    }

    /** 严格解码：遇到非法字节返回 null，而不是悄悄塞一个替换字符。 */
    private static String strictDecode(byte[] bytes, Charset charset) {

        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    /** GBK 是 JDK 的扩展字符集，拿不到就返回 null（jlink 打包时要 requires jdk.charsets）。 */
    private static Charset gbkCharset() {
        try {
            return Charset.forName("GBK");
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<String> split(String text) {
        // String.lines() 认 \n、\r\n、\r 三种换行
        return text.lines().toList();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {

        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 逐行解析。出错时抛出 {@link IllegalArgumentException}，消息里带行号，可直接显示给用户。 */
    public static StratumModel parse(List<String> lines) {

        if (isFormationGFormat(lines)) {
            return parseFormationG(lines);
        }

        Integer declaredCount = null;
        Double dipAngle = null;
        Double wellRadius = null;
        Double wellResistivity = null;
        List<Double> resistivities = null;
        List<StratumInterface> interfaces = new ArrayList<>();
        List<Double> legacyAngles = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {

            int lineNo = i + 1;
            String text = stripComment(lines.get(i)).trim();

            if (text.isEmpty()) {
                continue;
            }

            int eq = text.indexOf('=');

            if (eq < 0) {
                // 不带等号：界面行。新格式只有一个深度，旧格式可带第二个夹角值。
                double[] values = parseInterfaceLine(text, lineNo);
                interfaces.add(new StratumInterface(values[0]));
                if (!Double.isNaN(values[1])) {
                    legacyAngles.add(values[1]);
                }
                continue;
            }

            String key = text.substring(0, eq).trim();
            String value = text.substring(eq + 1).trim();

            switch (key) {
                case "n" -> declaredCount = parseCount(value, lineNo);
                case "dip", "dipAngle", "angle" ->
                        dipAngle = parseNumber(value, lineNo, "统一地层倾角 dip");
                case "wellRadius" -> wellRadius = parseNumber(value, lineNo, "井眼半径 wellRadius");
                case "wellRho" -> wellResistivity = parseNumber(value, lineNo, "井眼电阻率 wellRho");
                case "rho" -> resistivities = parseList(value, lineNo, "层电阻率 rho");
                default -> throw new IllegalArgumentException(
                        "第 " + lineNo + " 行：无法识别的参数名「" + key + "」");
            }
        }

        if (wellRadius == null) {
            throw new IllegalArgumentException("缺少井眼半径 wellRadius");
        }
        if (wellResistivity == null) {
            throw new IllegalArgumentException("缺少井眼电阻率 wellRho");
        }
        if (resistivities == null) {
            throw new IllegalArgumentException("缺少层电阻率 rho");
        }

        if (declaredCount != null && declaredCount != interfaces.size()) {
            throw new IllegalArgumentException(
                    "文件里写的界面个数 n = " + declaredCount
                            + "，实际列出 " + interfaces.size() + " 条界面，两者不一致");
        }

        double effectiveDip = dipAngle != null
                ? dipAngle
                : (legacyAngles.isEmpty() ? 90 : legacyAngles.get(0));
        if (!Double.isFinite(effectiveDip)
                || effectiveDip <= 0 || effectiveDip >= 180) {
            throw new IllegalArgumentException(
                    "统一地层倾角必须在 0° 与 180° 之间，实际为 " + effectiveDip);
        }

        return new StratumModel(
                wellRadius, wellResistivity, effectiveDip, interfaces, resistivities);
    }


    // =========================================================
    // formation_G.txt 固定格式
    // =========================================================

    private static boolean isFormationGFormat(List<String> lines) {
        for (String line : lines) {
            String compact = compact(line);
            if (compact.contains("地层模型参数") || compact.contains("裂缝个数n2")) {
                return true;
            }
        }
        return false;
    }

    private static StratumModel parseFormationG(List<String> lines) {

        Integer interfaceCount = null;
        Integer fractureCount = null;
        Double dipAngle = null;
        Double wellRadius = null;
        Double wellResistivity = null;
        List<Double> depths = new ArrayList<>();
        List<Double> resistivities = new ArrayList<>();
        List<Fracture> fractures = new ArrayList<>();
        List<SphereFracture> sphereFractures = new ArrayList<>();
        List<WedgeFracture> wedgeFractures = new ArrayList<>();
        List<SurfaceText> surfaceTexts = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {

            String header = compact(lines.get(i));
            if (header.isEmpty() || isSeparator(header)) {
                continue;
            }

            if (header.equals("界面个数n")) {
                double[] row = firstNumericRowAfter(lines, i + 1);
                interfaceCount = parseInteger(row[0], i + 1, "界面个数 n");
            } else if (header.contains("界面和井轴的夹角")) {
                double[] row = firstNumericRowAfter(lines, i + 1);
                if (row.length < 3) {
                    throw new IllegalArgumentException(
                            "第 " + (i + 1) + " 行：界面夹角、井眼半径和泥浆电阻率需要三个数");
                }
                dipAngle = row[0];
                wellRadius = row[1];
                wellResistivity = row[2];
            } else if (header.contains("界面和井轴的交点深度")) {
                depths = firstColumnAfter(lines, i + 1);
            } else if (header.startsWith("层数")) {
                resistivities = lastColumnAfter(lines, i + 1);
            } else if (header.contains("裂缝个数n2")) {
                double[] row = firstNumericRowAfter(lines, i + 1);
                fractureCount = parseInteger(row[0], i + 1, "裂缝个数 n2");
            } else if (header.contains("裂缝和井轴的交点深度")) {
                for (double[] row : numericRowsAfter(lines, i + 1)) {
                    if (row.length < 4) {
                        throw new IllegalArgumentException(
                                "第 " + (i + 1) + " 行：裂缝行至少需要深度、法向仰角、法向方位角和 R1 四个数");
                    }
                    fractures.add(new Fracture(row[0], row[1], row[2], row[3]));
                }
            } else if (header.contains("球体") && header.contains("=====")) {
                sphereFractures = parseSphereSection(lines, i + 1);
            } else if (header.contains("楔形体个数")) {
                wedgeFractures = parseWedgeSection(lines, i);
            } else if (header.contains("楔形") && header.contains("=====")) {
                wedgeFractures = parseWedgeSection(lines, i + 1);
            } else if (header.contains("文字") && header.contains("=====")) {
                surfaceTexts = parseTextSection(lines, i + 1);
            }
        }

        if (interfaceCount == null || dipAngle == null
                || wellRadius == null || wellResistivity == null) {
            throw new IllegalArgumentException("formation_G 文件缺少地层模型参数");
        }
        if (depths.size() != interfaceCount) {
            throw new IllegalArgumentException(
                    "formation_G 文件写的界面个数为 " + interfaceCount
                            + "，实际列出 " + depths.size() + " 个深度");
        }
        if (resistivities.size() != interfaceCount + 1) {
            throw new IllegalArgumentException(
                    "formation_G 文件应有 " + (interfaceCount + 1)
                            + " 个层电阻率，实际列出 " + resistivities.size() + " 个");
        }
        if (fractureCount != null && fractures.size() != fractureCount) {
            throw new IllegalArgumentException(
                    "formation_G 文件写的裂缝个数为 " + fractureCount
                            + "，实际列出 " + fractures.size() + " 条裂缝");
        }

        List<StratumInterface> interfaces = new ArrayList<>(depths.size());
        for (double depth : depths) {
            interfaces.add(new StratumInterface(depth));
        }

        return new StratumModel(wellRadius, wellResistivity, dipAngle,
                interfaces, resistivities, fractures, sphereFractures, wedgeFractures,
                surfaceTexts);
    }

    private static List<SphereFracture> parseSphereSection(List<String> lines, int start) {

        List<double[]> rows = numericRowsAfter(lines, start);
        if (rows.isEmpty()) {
            return List.of();
        }

        int count = parseInteger(rows.get(0)[0], start, "球体个数");
        if (rows.size() - 1 != count) {
            throw new IllegalArgumentException(
                    "formation_G 文件写的球体个数为 " + count
                            + "，实际列出 " + (rows.size() - 1) + " 个球体");
        }

        List<SphereFracture> spheres = new ArrayList<>(count);
        for (int i = 1; i < rows.size(); i++) {
            double[] row = rows.get(i);
            if (row.length < 5) {
                throw new IllegalArgumentException(
                        "球体行需要「中心深度 方位角 距井轴水平距离 球半径 电阻率」五个数");
            }
            spheres.add(new SphereFracture(row[0], row[1], row[2], row[3], row[4]));
        }
        return spheres;
    }

    private static List<WedgeFracture> parseWedgeSection(List<String> lines, int start) {

        List<double[]> rows = numericRowsAfter(lines, start);
        if (rows.isEmpty()) {
            return List.of();
        }

        int count = parseInteger(rows.get(0)[0], start, "楔形体个数");
        if (rows.size() - 1 != count) {
            throw new IllegalArgumentException(
                    "第 " + start + " 行：formation_G 文件写的楔形体个数为 " + count
                            + "，实际列出 " + (rows.size() - 1) + " 个楔形体");
        }

        List<WedgeFracture> wedges = new ArrayList<>(count);
        for (int i = 1; i < rows.size(); i++) {
            double[] row = rows.get(i);
            if (row.length < 7) {
                throw new IllegalArgumentException(
                        "楔形体行需要「顶点方位角 顶点深度 左底方位角 左底深度 "
                                + "右底方位角 右底深度 电阻率」七个数");
            }
            wedges.add(new WedgeFracture(
                    row[0], row[1], row[2], row[3], row[4], row[5], row[6]));
        }
        return wedges;
    }

    private static List<SurfaceText> parseTextSection(List<String> lines, int start) {

        int count = -1;
        int countLine = start;
        List<SurfaceText> texts = new ArrayList<>();

        for (int i = start; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.stripLeading().startsWith("%") && raw.contains("=====")) {
                if (count >= 0) {
                    break;
                }
                continue;
            }
            String text = stripComment(raw).trim();
            if (text.isEmpty()) {
                continue;
            }
            String[] parts = text.split("\\s+");
            if (count < 0) {
                countLine = i + 1;
                count = parseInteger(parseNumber(parts[0], i + 1, "文字总个数"),
                        i + 1, "文字总个数");
                continue;
            }
            if (parts.length < 5) {
                throw new IllegalArgumentException(
                        "文字行需要「中心方位角 中心深度 横向跨度角度 纵向跨度高度 电阻率 [文字内容]」");
            }

            String content = parts.length > 5
                    ? String.join(" ", java.util.Arrays.copyOfRange(parts, 5, parts.length))
                    : "文字";
            content = content.replaceAll("^[\"']|[\"']$", "");
            texts.add(new SurfaceText(
                    content,
                    parseNumber(parts[0], i + 1, "文字中心方位角"),
                    parseNumber(parts[1], i + 1, "文字中心深度"),
                    parseNumber(parts[2], i + 1, "文字横向跨度角度"),
                    parseNumber(parts[3], i + 1, "文字纵向跨度高度"),
                    parseNumber(parts[4], i + 1, "文字电阻率")));
        }

        if (count >= 0 && texts.size() != count) {
            throw new IllegalArgumentException(
                    "第 " + countLine + " 行：formation_G 文件写的文字总个数为 " + count
                            + "，实际列出 " + texts.size() + " 条文字");
        }
        return texts;
    }

    private static double[] firstNumericRowAfter(List<String> lines, int start) {
        List<double[]> rows = numericRowsAfter(lines, start);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("第 " + start + " 行之后缺少数值数据");
        }
        return rows.get(0);
    }

    private static List<Double> firstColumnAfter(List<String> lines, int start) {
        List<Double> values = new ArrayList<>();
        for (double[] row : numericRowsAfter(lines, start)) {
            if (row.length > 0) {
                values.add(row[0]);
            }
        }
        return values;
    }

    private static List<Double> lastColumnAfter(List<String> lines, int start) {
        List<Double> values = new ArrayList<>();
        for (double[] row : numericRowsAfter(lines, start)) {
            if (row.length > 0) {
                values.add(row[row.length - 1]);
            }
        }
        return values;
    }

    private static List<double[]> numericRowsAfter(List<String> lines, int start) {

        List<double[]> rows = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (!rows.isEmpty() && raw.contains("楔形体个数")) {
                break;
            }
            if (raw.stripLeading().startsWith("%") && raw.contains("=====")) {
                if (!rows.isEmpty()) {
                    break;
                }
                continue;
            }
            String text = stripComment(raw).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (isSeparator(text)) {
                if (!rows.isEmpty()) {
                    break;
                }
                continue;
            }
            try {
                rows.add(parseNumericRow(text));
            } catch (NumberFormatException ex) {
                if (!rows.isEmpty()) {
                    break;
                }
            }
        }
        return rows;
    }

    private static double[] parseNumericRow(String text) {
        String[] parts = text.replace(',', ' ').trim().split("\s+");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i]);
        }
        return values;
    }

    private static int parseInteger(double value, int lineNo, String what) {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：" + what + "必须是非负整数");
        }
        return (int) value;
    }

    private static boolean isSeparator(String text) {
        String compact = compact(text);
        return compact.matches("-+");
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    // 去掉 # 之后的内容
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int percent = line.indexOf('%');
        int cut = hash < 0 ? percent : percent < 0 ? hash : Math.min(hash, percent);
        return cut < 0 ? line : line.substring(0, cut);
    }

    // 一行一个深度；兼容旧格式的「深度 夹角」两列。
    private static double[] parseInterfaceLine(String text, int lineNo) {
        String[] parts = text.replace(',', ' ').trim().split("\\s+");
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：界面行应写成「交点深度」，"
                            + "或兼容旧格式的「交点深度 夹角」，实际是「" + text + "」");
        }
        return new double[]{
                parseNumber(parts[0], lineNo, "交点深度"),
                parts.length == 2 ? parseNumber(parts[1], lineNo, "夹角") : Double.NaN
        };
    }

    // 一行若干个数
    private static List<Double> parseList(String value, int lineNo, String what) {
        String[] parts = value.replace(',', ' ').trim().split("\\s+");
        List<Double> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                out.add(parseNumber(part, lineNo, what));
            }
        }
        return out;
    }

    /** 解析界面个数 n：必须是 1 ~ {@link StratumModel#MAX_INTERFACES} 之间的整数。 */
    private static int parseCount(String value, int lineNo) {

        double v = parseNumber(value, lineNo, "界面个数 n");

        if (!Double.isFinite(v) || v != Math.rint(v)
                || v < 1 || v > StratumModel.MAX_INTERFACES) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：界面个数 n 需要是 1 ~ "
                            + StratumModel.MAX_INTERFACES + " 之间的整数");
        }
        return (int) v;
    }

    private static double parseNumber(String text, int lineNo, String what) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：" + what + "「" + text + "」不是有效数字");
        }
    }
}
