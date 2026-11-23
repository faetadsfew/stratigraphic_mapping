package com.stratigraphic.mapping.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SolverProgressTest {
    @TempDir Path temp;
    @Test void onlyCompleteValidNewDepthLinesAdvanceProgress() throws Exception {
        Path console=temp.resolve("console.log");
        SolverProgress monitor=new SolverProgress(console,ModelInputs.parse(SimulationTest.INPUT));
        monitor.poll();assertEquals(0,monitor.points());assertEquals(1501,monitor.expectedPoints());
        Files.writeString(console,"MESH DATA\n0 3 4 5 53.130102\n0.001 3 4");
        monitor.poll();assertEquals(1,monitor.points());
        monitor.poll();assertEquals(1,monitor.points());
        Files.writeString(console," 5 53.130102\n",StandardOpenOption.APPEND);
        monitor.poll();assertEquals(2,monitor.points());assertEquals(0.001,monitor.lastDepth());
        Files.writeString(console,"0.001 3 4 5 53.130102\n0.002 3 4 99 53.130102\nheader\n",StandardOpenOption.APPEND);
        monitor.poll();assertEquals(2,monitor.points());
        Files.writeString(console,"0.002 3 4 5 53.130102\n",StandardOpenOption.APPEND);
        monitor.poll();assertEquals(3,monitor.points());
    }
    @Test void progressShowsRealWithinChannelCountsAndFinalValidationStage() {
        var p=new SimulationRunner.Progress(0,1,700,1501,0.699,752,752,0);
        assertTrue(p.fraction()>0);assertTrue(p.fraction()<1.0/192);
        assertTrue(p.message().contains("700/1501"));assertTrue(p.message().contains("0.69900"));
        assertTrue(p.message().contains("本通道估计剩余"));
        assertTrue(new SimulationRunner.Progress(0,1,1501,1501,1.5,1600,1600,0).message().contains("等待程序结束"));
        assertTrue(new SimulationRunner.Progress(0,1,0,1501,Double.NaN,70,70,70).message().contains("70 秒未收到"));
        assertEquals(1,new SimulationRunner.Progress(192,0,0,0,Double.NaN,99,0,0).fraction());
    }
    @Test void cannotAcceptTruncatedFirstChannelEvenWithValidNumbers() {
        var samples=List.of(new ResponseData.Sample(0,1,3,4,5,53.130102));
        assertThrows(java.io.IOException.class,()->SimulationRunner.validateRequestedDepths(samples,ModelInputs.parse(SimulationTest.INPUT)));
    }
}
