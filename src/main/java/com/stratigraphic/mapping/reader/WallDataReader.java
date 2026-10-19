package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.wall.WallData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取井壁电性 Excel。要求工作簿包含「模值」「相位」两个 Sheet，
 * 第一张表的第一行依次为 depth、1～192，后续每行给出对应 MD 数据。
 */
public final class WallDataReader {

    private static final int CHANNEL_COUNT = WallData.ELECTRODE_COUNT;
    private static final double EXPECTED_INTERVAL = 0.00254;

    private WallDataReader() {
    }

    public static WallData read(Path path) throws IOException {

        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Excel 文件不存在");
        }

        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet amplitudeSheet = requireSheet(workbook, "模值");
            Sheet phaseSheet = requireSheet(workbook, "相位");

            SheetData amplitude = readSheet(amplitudeSheet, evaluator, "模值");
            SheetData phase = readSheet(phaseSheet, evaluator, "相位");

            if (amplitude.depth.length != phase.depth.length) {
                throw new IllegalArgumentException(String.format(
                        "「模值」和「相位」行数不一致：%d 与 %d",
                        amplitude.depth.length, phase.depth.length));
            }
            for (int i = 0; i < amplitude.depth.length; i++) {
                if (Math.abs(amplitude.depth[i] - phase.depth[i]) > 1e-9) {
                    throw new IllegalArgumentException(String.format(
                            "「模值」和「相位」第 %d 行深度不一致：%.8f 与 %.8f",
                            i + 2, amplitude.depth[i], phase.depth[i]));
                }
            }

            WallData data = new WallData(
                    path.toAbsolutePath(),
                    amplitude.depth,
                    amplitude.values,
                    phase.values);

            if (Math.abs(data.sampleInterval() - EXPECTED_INTERVAL)
                    > Math.max(1e-7, EXPECTED_INTERVAL * 0.01)) {
                throw new IllegalArgumentException(String.format(
                        "深度采样间隔不符合仪器定义：实际 %.8f m，要求 %.8f m",
                        data.sampleInterval(), EXPECTED_INTERVAL));
            }

            return data;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("无法读取 Excel：" + ex.getMessage(), ex);
        }
    }

    private static Sheet requireSheet(Workbook workbook, String name) {

        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            throw new IllegalArgumentException(
                    "Excel 缺少 Sheet「" + name + "」。当前 Sheet："
                            + String.join("、", names));
        }
        return sheet;
    }

    private static SheetData readSheet(Sheet sheet,
                                       FormulaEvaluator evaluator,
                                       String sheetName) {

        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw new IllegalArgumentException("Sheet「" + sheetName + "」为空");
        }

        String depthHeader = text(header.getCell(0), evaluator);
        if (!"depth".equalsIgnoreCase(depthHeader.trim())) {
            throw new IllegalArgumentException(
                    "Sheet「" + sheetName + "」A1 应为 depth，实际为「"
                            + depthHeader + "」");
        }

        for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
            double value = number(header.getCell(channel + 1), evaluator,
                    sheetName + " 表头第 " + (channel + 2) + " 列");
            if (Math.abs(value - (channel + 1)) > 1e-9) {
                throw new IllegalArgumentException(String.format(
                        "Sheet「%s」表头应为 1～192，第 %d 列实际为 %.0f",
                        sheetName, channel + 2, value));
            }
        }

        List<Double> depths = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();

        for (int rowIndex = header.getRowNum() + 1;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {

            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlank(row)) {
                continue;
            }

            double depth = number(
                    row.getCell(0), evaluator,
                    sheetName + " 第 " + (rowIndex + 1) + " 行 depth");
            if (!Double.isFinite(depth)) {
                throw new IllegalArgumentException(
                        sheetName + " 第 " + (rowIndex + 1) + " 行 depth 不是有效数字");
            }

            double[] values = new double[CHANNEL_COUNT];
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                values[channel] = number(
                        row.getCell(channel + 1), evaluator,
                        sheetName + " 第 " + (rowIndex + 1)
                                + " 行第 " + (channel + 1) + " 电极");
            }

            depths.add(depth);
            rows.add(values);
        }

        if (depths.size() < 2) {
            throw new IllegalArgumentException(
                    "Sheet「" + sheetName + "」至少需要两行有效数据");
        }

        double[] depthArray = new double[depths.size()];
        double[][] valueArray = new double[rows.size()][];
        for (int i = 0; i < depths.size(); i++) {
            depthArray[i] = depths.get(i);
            valueArray[i] = rows.get(i);
        }

        return new SheetData(depthArray, valueArray);
    }

    private static boolean isBlank(Row row) {

        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static String text(Cell cell, FormulaEvaluator evaluator) {

        if (cell == null) {
            return "";
        }

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = evaluator.evaluateFormulaCell(cell);
        }

        if (type == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (type == CellType.NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    private static double number(Cell cell, FormulaEvaluator evaluator, String location) {

        if (cell == null) {
            throw new IllegalArgumentException(location + " 为空白");
        }

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = evaluator.evaluateFormulaCell(cell);
        }

        double value;
        if (type == CellType.NUMERIC) {
            value = cell.getNumericCellValue();
        } else if (type == CellType.STRING) {
            String text = cell.getStringCellValue().trim();
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(location + "「" + text + "」不是数字");
            }
        } else {
            throw new IllegalArgumentException(location + " 不是数字");
        }

        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(location + " 不是有限数字");
        }
        return value;
    }

    private record SheetData(double[] depth, double[][] values) {
    }
}
