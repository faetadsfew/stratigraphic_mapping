package com.stratigraphic.mapping.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    @TempDir Path temp;
    static final String INPUT="2500000 8\n0.005 1000 6\n0 0.001 1.5\n2\n0 100 10\n1.5 100 10\n0\n";
    static final String SHORT_INPUT=INPUT.replace("0 0.001 1.5","0 0.1 0.1");
    @Test void variableRecordsAndZeroFractures() {
        assertEquals(0,ModelInputs.parse(INPUT).fractures().size());
        assertEquals(1,ModelInputs.parse(INPUT.substring(0,INPUT.length()-2)+"1\n0.7 0 0.5 10 0.002\n").fractures().size());
        assertThrows(IllegalArgumentException.class,()->ModelInputs.parse(INPUT.replace("0.001","0")));
        assertThrows(IllegalArgumentException.class,()->ModelInputs.parse(INPUT+"9\n"));
        assertThrows(IllegalArgumentException.class,()->ModelInputs.parse(INPUT.replace("2500000","NaN")));
        assertThrows(IllegalArgumentException.class,()->ModelInputs.parse(INPUT.replace("1.5 100 10","-1 100 10")));
    }
    private ModelInputs snapshot() throws Exception {
        Path dir=Files.createDirectory(temp.resolve("source"));
        for(int i=192;i>=1;i--) Files.writeString(dir.resolve(String.format("Card_%03d_model.txt",i)),SHORT_INPUT);
        return ModelInputs.load(dir,temp.resolve("cache"),(n,s)->{});
    }
    @Test void snapshotOrderingMissingDuplicateAndMalformed() throws Exception {
        ModelInputs inputs=snapshot();assertEquals(1,inputs.cards().get(0).id());assertEquals(192,inputs.cards().get(191).id());
        Path source=temp.resolve("source");
        Files.writeString(source.resolve("Card_001_model.txt"),"broken");
        assertEquals(SHORT_INPUT,Files.readString(inputs.cards().get(0).snapshot()));
        assertThrows(java.io.IOException.class,()->ModelInputs.load(source,temp.resolve("cache"),(n,s)->{}));
        Files.delete(source.resolve("Card_002_model.txt"));
        Files.writeString(source.resolve("Card_003_duplicate.txt"),INPUT);
        String message=assertThrows(java.io.IOException.class,()->ModelInputs.load(source,temp.resolve("cache"),(n,s)->{})).getMessage();
        assertTrue(message.contains("重复编号"));assertTrue(message.contains("Card_002"));
    }
    public static Path fixture(Path file) throws Exception {
        StringBuilder out=new StringBuilder();
        for(double depth:new double[]{0.7,0,0.1}) for(int e=192;e>=1;e--) out.append(depth).append('\t').append(e).append("\t3\t-4\t5\t-53.13010235415598\n");
        return Files.writeString(file,out);
    }
    @Test void unorderedSixColumnsIndexedWithoutOldSpacing() throws Exception {
        Path file=fixture(temp.resolve("result.txt"));ResponseData data=ResponseData.read(file,(a,b)->{});
        assertEquals(3,data.size());assertEquals(0,data.depth(0));assertEquals(0.7,data.depth(2));assertEquals(192,data.sample(1,192).electrode());
        assertTrue(data.summary().contains("未提供"));
        Files.writeString(file,"0 1 3 -4 5 -53.13010235415598\n",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,()->ResponseData.read(file,(a,b)->{}));
    }
    @Test void invalidAndIncompleteResults() throws Exception {
        for(String text:List.of("0 193 3 4 5 53.1301\n","0 1 NaN 4 5 53\n","0 1 3 4 99 53\n","0 1 3 4 5 -53\n","0 1 3 4 5 53.1301\n","")) {
            Path f=Files.writeString(temp.resolve("bad.txt"),text);
            assertThrows(IllegalArgumentException.class,()->ResponseData.read(f,(a,b)->{}));
        }
        assertDoesNotThrow(()->ResponseData.validate(3,4,5,413.13010235415598,"wrapped"));
        assertDoesNotThrow(()->ResponseData.validate(0,0,0,17,"zero"));
    }
    @Test void nativeOutputAndMissingLibraryPreserveFailure() throws Exception {
        Path nativeFile=Files.writeString(temp.resolve("native.txt"),"0 3 4 5 53.130102\n0.1 3 4 5 53.130102\n");
        assertEquals(2,SimulationRunner.readNative(nativeFile,17).size());
        Files.writeString(nativeFile,"0 3 4 5 53.130102\n0 3 4 5 53.130102\n");
        assertThrows(IllegalArgumentException.class,()->SimulationRunner.readNative(nativeFile,17));
        ModelInputs inputs=snapshot();Path solver=Files.createDirectory(temp.resolve("solver"));Files.writeString(solver.resolve(SimulationRunner.EXE),"");
        var ex=assertThrows(java.io.IOException.class,()->new SimulationRunner().run(inputs,solver,temp.resolve("out"),1,(n,s)->{}));
        assertTrue(ex.getMessage().contains(SimulationRunner.DLL));
        try(var files=Files.walk(temp.resolve("out"))) {assertTrue(files.anyMatch(p->p.getFileName().toString().equals("batch.properties")));}
    }
    @Test void optionalRealSolverTrial() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.realTrial"));
        Path root=SimulationRunner.DEFAULT_ROOT;
        Path work=Path.of("target","real solver verification").toAbsolutePath();Files.createDirectories(work);
        // Validate all delivered originals, then explicitly create a separate short-depth TEST fixture.
        ModelInputs originals=ModelInputs.load(root.resolve("192_NGI_Input"),work.resolve("original-cache"),(n,s)->{});
        Path trialSource=Files.createTempDirectory(work,"short-depth-TEST-inputs-");
        for(var card:originals.cards()) {
            var lines=new ArrayList<>(Files.readAllLines(card.snapshot()));
            lines.set(2,"0 0.001 0.02");
            Files.write(trialSource.resolve(card.name()),lines);
        }
        ModelInputs inputs=ModelInputs.load(trialSource,work.resolve("trial-cache"),(n,s)->{});
        var observed=new ArrayList<SimulationRunner.Progress>();
        var outcome=new SimulationRunner().runWithProgress(inputs,root,work.resolve("output"),2,p->{observed.add(p);System.out.println(p.message());},()->false);
        assertEquals(2,outcome.completed());assertNull(outcome.result());
        for(int channel=1;channel<=2;channel++) {
            int current=channel;
            assertTrue(observed.stream().anyMatch(p->p.current()==current&&p.depthPoints()>0),"Must observe actual depth progress in channel "+channel);
            assertEquals(21,SimulationRunner.readNative(outcome.batch().resolve(String.format("logs/Card_%03d_logs.txt",channel)),channel).size());
        }
        System.out.println("TRIAL_ARCHIVE="+outcome.batch());
    }
    private Path fakeSolverFiles() throws Exception {
        Path solver=Files.createDirectory(temp.resolve("solver"));
        Files.writeString(solver.resolve(SimulationRunner.EXE),"test placeholder");
        Files.writeString(solver.resolve(SimulationRunner.DLL),"test placeholder");return solver;
    }
    @Test void fullSizedResultValidatesEveryDepthAndElectrode() throws Exception {
        Path file=temp.resolve("full-size-synthetic.txt");
        try(var writer=Files.newBufferedWriter(file)) {
            for(int d=0;d<=1500;d++) for(int e=1;e<=192;e++) {
                writer.write((d/1000.0)+"\t"+e+"\t3\t4\t5\t53.13010235415598\n");
            }
        }
        var data=ResponseData.read(file,(a,b)->{});assertEquals(1501,data.size());
        assertEquals(1.5,data.sample(1500,192).md());assertTrue(data.summary().contains("288192"));
    }
    private static Process exited(int code) {
        return new Process() {
            public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public InputStream getInputStream(){return InputStream.nullInputStream();}
            public InputStream getErrorStream(){return InputStream.nullInputStream();}
            public int waitFor(){return code;}
            public int exitValue(){return code;}
            public void destroy(){}
        };
    }
    @Test void publishesSorted192ChannelBatchAndDoesNotOverwriteHistory() throws Exception {
        ModelInputs inputs=snapshot();Path solver=fakeSolverFiles();AtomicInteger calls=new AtomicInteger();
        SimulationRunner runner=new SimulationRunner((work,console)->{
            assertFalse(Files.exists(work.resolve(SimulationRunner.LOG)),"previous channel output must be deleted");
            assertEquals(SHORT_INPUT,Files.readString(work.resolve("NGI_2D_INP.txt")));
            calls.incrementAndGet();Files.writeString(work.resolve(SimulationRunner.LOG),"0 3 4 5 53.130102\n0.1 3 4 5 53.130102\n");return exited(0);
        });
        var first=runner.run(inputs,solver,temp.resolve("output"),192,(n,s)->{});
        assertEquals(192,calls.get());assertNotNull(first.result());
        var data=ResponseData.read(first.result(),(a,b)->{});assertEquals(2,data.size());
        List<String> lines=Files.readAllLines(first.result());assertEquals(384,lines.size());
        assertTrue(lines.get(0).startsWith("0.0\t1\t"));assertTrue(lines.get(191).startsWith("0.0\t192\t"));assertTrue(lines.get(192).startsWith("0.1\t1\t"));
        assertTrue(data.summary().contains("2500000"));
        var second=runner.run(inputs,solver,temp.resolve("output"),192,(n,s)->{});
        assertNotEquals(first.result(),second.result());assertTrue(Files.exists(first.result()));
    }
    @Test void rejectsMissingFreshOutputExitFailureAndDepthMismatch() throws Exception {
        ModelInputs inputs=snapshot();Path solver=fakeSolverFiles();
        for(String mode:List.of("missing","exit","depth")) {
            AtomicInteger count=new AtomicInteger();
            SimulationRunner runner=new SimulationRunner((work,console)->{
                int channel=count.incrementAndGet();
                assertFalse(Files.exists(work.resolve(SimulationRunner.LOG)));
                if(channel==1||mode.equals("depth")) Files.writeString(work.resolve(SimulationRunner.LOG),
                    channel==2?"0 3 4 5 53.130102\n0.2 3 4 5 53.130102\n":"0 3 4 5 53.130102\n0.1 3 4 5 53.130102\n");
                return exited(channel==2&&mode.equals("exit")?17:0);
            });
            Path out=temp.resolve(mode);
            var ex=assertThrows(IOException.class,()->runner.run(inputs,solver,out,192,(n,s)->{}));
            assertEquals(2,count.get());assertTrue(ex.getMessage().contains("失败通道 2"));
            try(var stream=Files.list(out)) {assertFalse(stream.anyMatch(p->p.toString().endsWith(".txt")));}
            try(var stream=Files.walk(out)) {assertTrue(stream.anyMatch(p->p.toString().endsWith(".partial")));}
        }
    }
    @Test void stoppingActiveProcessKillsItAndRecordsIncompleteState() throws Exception {
        ModelInputs inputs=snapshot();Path solver=fakeSolverFiles();
        AtomicBoolean stop=new AtomicBoolean(),killed=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
        SimulationRunner runner=new SimulationRunner((work,console)->{
            calls.incrementAndGet();
            return new Process() {
                public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
                public InputStream getInputStream(){return InputStream.nullInputStream();}
                public InputStream getErrorStream(){return InputStream.nullInputStream();}
                public int waitFor(){return -1;}
                public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){stop.set(true);return false;}
                public int exitValue(){if(!killed.get())throw new IllegalThreadStateException();return -1;}
                public void destroy(){killed.set(true);}
            };
        });
        Path output=temp.resolve("stopped");
        assertThrows(SimulationRunner.StoppedException.class,()->runner.runWithProgress(inputs,solver,output,192,p->{},stop::get));
        assertTrue(killed.get());assertEquals(1,calls.get());
        try(var stream=Files.walk(output)) {
            Path record=stream.filter(p->p.getFileName().toString().equals("batch.properties")).findFirst().orElseThrow();
            Properties p=new Properties();try(var in=Files.newInputStream(record)){p.load(in);}
            assertEquals("STOPPED_INCOMPLETE",p.getProperty("state"));
        }
    }
}
