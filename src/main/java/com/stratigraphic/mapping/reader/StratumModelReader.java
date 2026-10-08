package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;

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
 *   wellRadius = 0.1                    井眼半径 (m)
 *   wellRho    = 100                    井眼电阻率 (Ω·m)
 *   rho        = 20 15 10 30 40 50      由浅到深 n+1 层电阻率 (Ω·m)
 *   0.80 90                             界面行：交点深度 (m) 与夹角 (°)
 *   1.00 94
 *   ...
 * </pre>
 *
 * <p>界面行不带等号，每行两个数，可写成 {@code 0.80 90}、{@code 0.80, 90} 或 {@code 0.80,90}。
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

        Integer declaredCount = null;
        Double wellRadius = null;
        Double wellResistivity = null;
        List<Double> resistivities = null;
        List<StratumInterface> interfaces = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {

            int lineNo = i + 1;
            String text = stripComment(lines.get(i)).trim();

            if (text.isEmpty()) {
                continue;
            }

            int eq = text.indexOf('=');

            if (eq < 0) {
                // 不带等号：界面行，两个数分别是交点深度和夹角
                double[] pair = parsePair(text, lineNo);
                interfaces.add(new StratumInterface(pair[0], pair[1]));
                continue;
            }

            String key = text.substring(0, eq).trim();
            String value = text.substring(eq + 1).trim();

            switch (key) {
                case "n" -> declaredCount = (int) parseNumber(value, lineNo, "界面个数 n");
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

        return new StratumModel(wellRadius, wellResistivity, interfaces, resistivities);
    }

    // 去掉 # 之后的内容
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    // 一行两个数：深度 夹角
    private static double[] parsePair(String text, int lineNo) {
        String[] parts = text.replace(',', ' ').trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：界面行应写成「交点深度 夹角」两个数，实际是「" + text + "」");
        }
        return new double[]{
                parseNumber(parts[0], lineNo, "交点深度"),
                parseNumber(parts[1], lineNo, "夹角")
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

    private static double parseNumber(String text, int lineNo, String what) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行：" + what + "「" + text + "」不是有效数字");
        }
    }
}
