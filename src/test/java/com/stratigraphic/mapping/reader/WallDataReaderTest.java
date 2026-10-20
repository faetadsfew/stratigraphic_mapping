package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.wall.WallData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WallDataReaderTest {

    private static final double SAMPLE_INTERVAL = 0.00254;

    @TempDir
    Path tempDirectory;

    @Test
    void readsBothSheetsAndInterpolatesBetweenDepthSamples() throws Exception {

        Path excel = writeWorkbook(false);
        WallData data = WallDataReader.read(excel);

        assertEquals(3, data.rowCount());
        assertEquals(3000, data.minDepth(), 1e-12);
        assertEquals(3000 + 2 * SAMPLE_INTERVAL, data.maxDepth(), 1e-12);
        assertEquals(SAMPLE_INTERVAL, data.sampleInterval(), 1e-12);
        assertEquals(10.5, data.amplitudeAt(0, 3000 + SAMPLE_INTERVAL / 2), 1e-7);
        assertEquals(-20.5, data.phaseAt(0, 3000 + SAMPLE_INTERVAL / 2), 1e-7);
    }

    @Test
    void rejectsPositivePhaseValues() throws Exception {

        Path excel = writeWorkbook(true);
        assertThrows(IllegalArgumentException.class, () -> WallDataReader.read(excel));
    }

    private Path writeWorkbook(boolean positivePhase) throws Exception {

        Path file = tempDirectory.resolve(
                positivePhase ? "positive-phase.xlsx" : "valid.xlsx");

        try (Workbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook.createSheet("模值"), false, false);
            writeSheet(workbook.createSheet("相位"), true, positivePhase);

            try (OutputStream output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        return file;
    }

    private static void writeSheet(Sheet sheet, boolean phase, boolean positivePhase) {

        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("depth");
        for (int channel = 0; channel < WallData.ELECTRODE_COUNT; channel++) {
            header.createCell(channel + 1).setCellValue(channel + 1);
        }

        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            row.createCell(0).setCellValue(3000 + rowIndex * SAMPLE_INTERVAL);

            for (int channel = 0; channel < WallData.ELECTRODE_COUNT; channel++) {
                double magnitude = 10 + rowIndex + channel / 100.0;
                double value = phase
                        ? (positivePhase ? magnitude + 10 : -magnitude - 10)
                        : magnitude;
                row.createCell(channel + 1).setCellValue(value);
            }
        }
    }
}
