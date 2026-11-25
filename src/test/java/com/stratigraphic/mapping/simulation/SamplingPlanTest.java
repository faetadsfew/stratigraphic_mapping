package com.stratigraphic.mapping.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SamplingPlanTest {
    @TempDir Path temp;
    private ModelInputs base() throws Exception {
        Path source=Files.createDirectories(temp.resolve("source"));
        for(int id=1;id<=192;id++) Files.writeString(source.resolve(String.format("Card_%03d.txt",id)),
                "2500000 8\n0.005 1000 6\n0 0.001 1.5\n2\n0.4 100 10\n2.5 30 10\n1\n0.7 0 0.5 10 0.002\n");
        return ModelInputs.load(source,temp.resolve("cache"),(done,message)->{});
    }
    private static List<ResponseData.Sample> samples(SamplingPlan plan,int id) {
        List<ResponseData.Sample> rows=new ArrayList<>();
        for(int d=0;d<plan.computedPoints();d++) {
            double md=plan.begin()+d*plan.step(),real=id+md*10,imaginary=-id*0.1;
            rows.add(new ResponseData.Sample(md,id,real,imaginary,Math.hypot(real,imaginary),Math.toDegrees(Math.atan2(imaginary,real))));
        }
        return rows;
    }
    @Test void sampledInputsKeepOriginalGeometryAndSurviveArchivingButRejectTampering() throws Exception {
        ModelInputs original=base();byte[] old=Files.readAllBytes(original.cards().get(0).snapshot());
        var parameters=new SamplingPlan.InputParameters(40_000_000,9.5,0.007,800,7.5);
        ModelInputs inputs=SamplingPlan.prepare(original,temp.resolve("cache"),0.2,2.5,0.9,parameters);
        assertArrayEquals(old,Files.readAllBytes(original.cards().get(0).snapshot()));
        assertArrayEquals(old,Files.readAllBytes(temp.resolve("source/Card_001.txt")));
        for(var card:inputs.cards()) {
            assertEquals(0.2,card.model().begin());assertEquals(0.9,card.model().end());assertEquals(0.0025,card.model().step());
            assertEquals(40_000_000,card.model().frequency());assertEquals(9.5,card.model().diameterInches());
            assertEquals(0.007,card.model().offset());assertEquals(800,card.model().mudRho());assertEquals(7.5,card.model().mudEps());
            assertEquals(original.cards().get(card.id()-1).model().layers(),card.model().layers());
            assertEquals(original.cards().get(card.id()-1).model().fractures(),card.model().fractures());
        }
        assertTrue(Files.readString(inputs.cards().get(0).snapshot()).startsWith("4.0E7 9.5\n0.007 800.0 7.5\n"));
        Path archived=Files.createDirectory(temp.resolve("archived"));inputs.copySnapshotTo(archived);
        ModelInputs restored=ModelInputs.restoreSnapshot(archived);InputPreparation.verifySnapshot(restored);
        assertEquals(SamplingPlan.read(inputs.directory()),SamplingPlan.read(archived));
        Files.writeString(restored.cards().get(0).snapshot(),Files.readString(restored.cards().get(0).snapshot()).replace("0.2 0.0025 0.9","0.3 0.0025 0.9"));
        assertThrows(IOException.class,()->InputPreparation.verifySnapshot(restored));
    }
    @Test void extensionPreservesEveryElectrodesOwnEndpointsAndAllComputedValues() throws Exception {
        SamplingPlan plan=SamplingPlan.create(0.2,0.001,0.9,0,1.5);
        for(int id:new int[]{1,2,192}) {
            var computed=samples(plan,id);var extended=plan.extend(computed);
            assertEquals(1501,extended.size());assertEquals(0,extended.get(0).md());assertEquals(1.5,extended.get(1500).md());
            for(int d=0;d<extended.size();d++) {
                var actual=extended.get(d);
                var expected=d<200?computed.get(0):d>900?computed.get(computed.size()-1):computed.get(d-200);
                assertEquals(id,actual.electrode());assertEquals(d*0.001,actual.md(),1e-12);
                assertEquals(expected.real(),actual.real());assertEquals(expected.imaginary(),actual.imaginary());
                assertEquals(expected.amplitude(),actual.amplitude());assertEquals(expected.phase(),actual.phase());
                if(d>0)assertTrue(actual.md()>extended.get(d-1).md());
            }
        }
        SamplingPlan full=SamplingPlan.create(0,0.001,1.5,0,1.5);
        var all=samples(full,1);assertFalse(full.extended());assertEquals(1501,full.computedPoints());assertSame(all,full.extend(all));
    }
    @Test void maximumSpacingIncludesBothEndpointsWithoutExceedingRequestedSpacing() throws Exception {
        SamplingPlan partial=SamplingPlan.create(0.2,0.00254,0.9,0,1.5);
        assertEquals(277,partial.computedPoints());assertTrue(partial.step()<=0.00254);
        var computed=samples(partial,1);assertEquals(0.9,computed.get(computed.size()-1).md(),1e-12);
        var extended=partial.extend(computed);assertEquals(0,extended.get(0).md());assertEquals(1.5,extended.get(extended.size()-1).md());
        for(int i=1;i<extended.size();i++) {double gap=extended.get(i).md()-extended.get(i-1).md();assertTrue(gap>0 && gap<=0.00254+1e-12);}
        SamplingPlan full=SamplingPlan.create(0,0.00254,1.5,0,1.5);assertFalse(full.extended());assertEquals(592,full.computedPoints());
        assertEquals(1.5,samples(full,1).get(591).md(),1e-12);
        assertThrows(IOException.class,()->partial.extend(computed.subList(0,computed.size()-1)));
    }
    @Test void rejectsInvalidOrExcessiveSamplingBeforeStartingAnyProcess() {
        for(double step:new double[]{0,-0.001,0.002541,Double.NaN,Double.POSITIVE_INFINITY,1e-12})
            assertThrows(IllegalArgumentException.class,()->SamplingPlan.create(0.2,step,0.9,0,1.5));
        assertThrows(IllegalArgumentException.class,()->SamplingPlan.create(-0.1,0.001,0.9,0,1.5));
        assertThrows(IllegalArgumentException.class,()->SamplingPlan.create(0.2,0.001,1.6,0,1.5));
        assertThrows(IllegalArgumentException.class,()->SamplingPlan.create(0.9,0.001,0.2,0,1.5));
        assertThrows(IllegalArgumentException.class,()->new SamplingPlan.InputParameters(0,8,0.005,1000,6));
        assertThrows(IllegalArgumentException.class,()->new SamplingPlan.InputParameters(2_500_000,0,0.005,1000,6));
        assertThrows(IllegalArgumentException.class,()->new SamplingPlan.InputParameters(2_500_000,8,-0.005,1000,6));
    }
    private Path solver() throws Exception {
        Path solver=Files.createDirectories(temp.resolve("solver"));Files.writeString(solver.resolve(SimulationRunner.EXE),"test executable");
        Files.writeString(solver.resolve(SimulationRunner.DLL),"test library");return solver;
    }
    private static Process exited() {
        return new Process() {
            public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public InputStream getInputStream(){return InputStream.nullInputStream();}
            public InputStream getErrorStream(){return InputStream.nullInputStream();}
            public int waitFor(){return 0;} public boolean waitFor(long timeout,TimeUnit unit){return true;}
            public int exitValue(){return 0;} public void destroy(){} public boolean isAlive(){return false;}
        };
    }
    private static void log(Path work,SamplingPlan plan,int id) throws Exception {
        StringBuilder log=new StringBuilder();
        for(var sample:samples(plan,id))log.append(sample.md()).append(' ').append(sample.real()).append(' ').append(sample.imaginary()).append(' ')
                .append(sample.amplitude()).append(' ').append(sample.phase()).append('\n');
        Files.writeString(work.resolve(SimulationRunner.LOG),log);
    }
    @Test void unfinishedBatchResumesOriginalSamplingAndPublishesFull192ElectrodesWithExplicitMetadata() throws Exception {
        ModelInputs inputs=SamplingPlan.prepare(base(),temp.resolve("cache"),0.2,2.5,0.205);
        SamplingPlan plan=SamplingPlan.read(inputs.directory());AtomicInteger calls=new AtomicInteger();
        var runner=new ParallelSimulationRunner((work,console)->{
            int id=Integer.parseInt(work.getFileName().toString().substring(5));calls.incrementAndGet();
            assertEquals(inputs.cards().get(id-1).model(),ModelInputs.parse(Files.readString(work.resolve("NGI_2D_INP.txt"))));
            try {log(work,plan,id);}catch(Exception error){throw new IOException(error);}return exited();
        });
        Path solver=solver();var trial=runner.run(inputs,solver,temp.resolve("output"),191,192,p->{},()->false);
        assertNull(trial.result());assertEquals(191,calls.get());
        assertEquals(3,SimulationRunner.readNative(trial.batch().resolve("logs/Card_001_logs.txt"),1).size());
        // Restoration uses the batch's snapshot, independent of any later settings or input loading.
        var complete=runner.resume(trial.batch(),solver,192,p->{},new ParallelSimulationRunner.Control());
        assertEquals(192,calls.get());assertEquals(192,complete.completed());
        var result=ResponseData.read(complete.result(),(done,total)->{});
        assertEquals(601,result.size());assertEquals(0,result.depth(0));assertEquals(1.5,result.depth(600));
        for(int id=1;id<=192;id++) {
            assertEquals(result.sample(80,id).amplitude(),result.sample(0,id).amplitude());
            assertEquals(result.sample(82,id).phase(),result.sample(600,id).phase());
            assertNotEquals(result.sample(0,id).amplitude(),result.sample(600,id).amplitude());
        }
        assertTrue(result.resultSummary().contains("端点延拓（非求解值）"));assertTrue(result.resultSummary().contains("0.2～0.205"));
        assertEquals("191",SimulationHistory.readRecord(complete.batch()).getProperty("reused"));
        assertEquals("3",SimulationHistory.readRecord(complete.batch()).getProperty("sampling.computed.points"));
    }
    @Test void serialPublicationUsesTheSameExtensionAndKeepsNativeLogsUnextended() throws Exception {
        ModelInputs inputs=SamplingPlan.prepare(base(),temp.resolve("cache"),0.2,2.5,0.205);
        SamplingPlan plan=SamplingPlan.read(inputs.directory());AtomicInteger calls=new AtomicInteger();
        var runner=new SimulationRunner((work,console)->{try {log(work,plan,calls.incrementAndGet());}catch(Exception error){throw new IOException(error);}return exited();});
        var complete=runner.run(inputs,solver(),temp.resolve("serial"),192,(done,message)->{});
        var result=ResponseData.read(complete.result(),(done,total)->{});assertEquals(601,result.size());
        assertEquals(3,SimulationRunner.readNative(complete.batch().resolve("logs/Card_192_logs.txt"),192).size());
        assertEquals(result.sample(80,192).real(),result.sample(0,192).real());assertEquals(result.sample(82,192).imaginary(),result.sample(600,192).imaginary());
    }
}
