package com.stratigraphic.mapping.reader;

import com.stratigraphic.mapping.model.StratumModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数量为 0 时，即使文件里还留着旧的数据行，也应按“没有”处理：
 * 0 个界面 = 不分层（保留第一行背景电阻率）；
 * 0 条裂缝 / 0 个球体 / 0 个正方体 / 0 个楔形体 / 0 条文字 = 对应列表为空。
 *
 * <p>两个 fixture 来自用户桌面的两份实际输入，保留了旧的遗留行。
 */
class TolerantZeroCountInputTest {

    @Test
    void zeroInterfaceIgnoresLeftoverDepthRowsAndExtraResistivities() throws Exception {

        StratumModel model = fixture("formation-zero-interface-stale-rows.txt");

        assertEquals(0, model.interfaceCount());
        assertEquals(1, model.layerCount());
        assertEquals(List.of(20.0), model.resistivities(),
                "0 个界面时只保留第一行作为背景层电阻率");
        assertTrue(model.fractures().isEmpty(), "裂缝个数为 0 时应忽略旧裂缝行");
        assertEquals(1, model.sphereFractures().size());
        assertEquals(1, model.cubeAnomalies().size());
        assertEquals(1, model.wedgeFractures().size());
        assertTrue(model.surfaceTexts().isEmpty(), "文字个数为 0 时应忽略旧文字行");

        assertEquals(5.15, model.sphereFractures().get(0).centerDepth(), 1e-12);
        assertEquals(5.11, model.minDepth(), 1e-12);
        assertEquals(5.375, model.maxDepth(), 1e-12);
    }

    @Test
    void zeroAnomaliesFracturesAndTextIgnoreTheirLeftoverRows() throws Exception {

        StratumModel model = fixture("formation-zero-anomalies-stale-rows.txt");

        assertEquals(2, model.interfaceCount());
        assertEquals(List.of(20.0, 15.0, 10.0), model.resistivities());
        assertTrue(model.fractures().isEmpty(), "裂缝个数为 0 时应忽略旧裂缝行");
        assertTrue(model.sphereFractures().isEmpty(), "球体个数为 0 时应忽略旧球体行");
        assertTrue(model.cubeAnomalies().isEmpty(), "正方体个数为 0 时应忽略旧正方体行");
        assertTrue(model.wedgeFractures().isEmpty(), "楔形体个数为 0 时应忽略旧楔形体行");
        assertTrue(model.surfaceTexts().isEmpty(), "文字个数为 0 时应忽略旧文字行");

        assertEquals(0.1, model.minDepth(), 1e-12);
        assertEquals(0.4, model.maxDepth(), 1e-12);
    }

    private StratumModel fixture(String name) throws Exception {
        return StratumModelReader.read(Path.of(getClass().getResource("/" + name).toURI()));
    }
}
