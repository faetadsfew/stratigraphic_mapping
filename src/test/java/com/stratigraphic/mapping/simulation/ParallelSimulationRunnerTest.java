package com.stratigraphic.mapping.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelSimulationRunnerTest {
    @TempDir Path temp;
    private ModelInputs snapshot() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));
        for(int id=192;id>=1;id--)Files.writeString(source.resolve(String.format("Card_%03d.txt",id)),SimulationTest.SHORT_INPUT.replace("2500000",Integer.toString(2500000+id)));
        return ModelInputs.load(source,temp.resolve("cache"),(a,b)->{});
    }
    private Path solver() throws Exception {
        Path solver=Files.createDirectory(temp.resolve("solver with spaces"));
        Files.writeString(solver.resolve(SimulationRunner.EXE),"fake EXE");
        Files.writeString(solver.resolve(SimulationRunner.DLL),"fake DLL");
        // Stale delivery-package output must never be deployed as a new channel's response.
        Files.writeString(solver.resolve(SimulationRunner.LOG),"stale output");return solver;
    }
    private static int id(Path work){return Integer.parseInt(work.getFileName().toString().substring(5));}
    private static String response(int id){return "0 "+id+" 0 "+id+" 0\n0.1 "+id+" 0 "+id+" 0\n";}
    private static class FakeProcess extends Process {
        int polls;final int code;boolean finished,killed;final AtomicInteger active;
        FakeProcess(int polls,int code,AtomicInteger active){this.polls=polls;this.code=code;this.active=active;active.incrementAndGet();}
        private void finish(){if(!finished){finished=true;active.decrementAndGet();}}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public boolean waitFor(long timeout,TimeUnit unit){if(--polls<=0)finish();return finished;}
        public int waitFor(){finish();return code;}
        public int exitValue(){if(!finished)throw new IllegalThreadStateException();return code;}
        public boolean isAlive(){return !finished;}
        public void destroy(){killed=true;finish();}
    }
    private static Properties record(Path output)throws Exception {
        try(var paths=Files.walk(output)){
            Path file=paths.filter(p->p.getFileName().toString().equals("batch.properties")).findFirst().orElseThrow();
            Properties properties=new Properties();try(var in=Files.newInputStream(file)){properties.load(in);}return properties;
        }
    }
    private static void noFinalTxt(Path output)throws Exception {
        try(var files=Files.list(output)){assertFalse(files.anyMatch(p->p.toString().endsWith(".txt")));}
    }
    @Test void boundedOutOfOrderProcessesHaveIsolatedFilesAndOneSortedCompleteResult()throws Exception {
        ModelInputs inputs=snapshot();Path solver=solver();Set<Path> directories=new HashSet<>();
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();
        var runner=new ParallelSimulationRunner((work,console)->{
            int id=id(work);calls.incrementAndGet();assertTrue(directories.add(work));
            assertFalse(Files.exists(work.resolve(SimulationRunner.LOG)));
            assertTrue(Files.readString(work.resolve("NGI_2D_INP.txt")).startsWith(Integer.toString(2500000+id)));
            assertEquals("fake DLL",Files.readString(work.resolve(SimulationRunner.DLL)));
            Files.writeString(work.resolve(SimulationRunner.LOG),response(id));Files.writeString(console,response(id));
            Files.writeString(work.resolve("mesh_output_test.txt"),"scratch");
            var process=new FakeProcess(id==1?2:1,0,active);peak.accumulateAndGet(active.get(),Math::max);return process;
        });
        List<ParallelSimulationRunner.Progress> observed=new ArrayList<>();
        var first=runner.run(inputs,solver,temp.resolve("output"),192,4,observed::add,()->false);
        assertEquals(4,peak.get());assertEquals(0,active.get());assertEquals(192,calls.get());
        assertEquals(192,first.completed());assertNotNull(first.result());
        assertTrue(observed.stream().anyMatch(p->p.completed()==3&&p.running().get(0).current()==1));
        var data=ResponseData.read(first.result(),(a,b)->{});assertEquals(2,data.size());
        for(int e=1;e<=192;e++)assertEquals(e,data.sample(1,e).amplitude());
        var lines=Files.readAllLines(first.result());assertEquals(384,lines.size());
        assertTrue(lines.get(0).startsWith("0.0\t1\t"));assertTrue(lines.get(191).startsWith("0.0\t192\t"));assertTrue(lines.get(192).startsWith("0.1\t1\t"));
        try(var files=Files.list(first.batch())){
            var partial=files.filter(p->p.toString().endsWith(".partial")).findFirst().orElseThrow();
            assertTrue(Files.readAllLines(partial).get(0).startsWith("0.0\t2\t"),"Completion order must differ from published sorting");
        }
        for(Path work:directories){assertFalse(Files.exists(work.resolve("mesh_output_test.txt")));assertTrue(Files.exists(work.resolve("NGI_2D_INP.txt")));}
        assertTrue(Files.exists(first.batch().resolve("runtime").resolve(SimulationRunner.DLL)));
        Properties p=record(first.batch());assertEquals("COMPLETE",p.getProperty("state"));assertEquals("4",p.getProperty("parallelism"));
        for(int id=1;id<=192;id++)assertTrue(p.getProperty("channel."+id).startsWith("SUCCESS"));
        var second=runner.run(inputs,solver,temp.resolve("output"),192,4,x->{},()->false);
        assertNotEquals(first.result(),second.result());assertTrue(Files.exists(first.result()));
    }
    @Test void failureStopsAllConcurrentProcessesWithoutLaunchingFurtherChannels()throws Exception {
        ModelInputs inputs=snapshot();Path solver=solver();
        for(String mode:List.of("exit","missing","depth","truncated","complex")){
            AtomicInteger active=new AtomicInteger(),calls=new AtomicInteger();List<FakeProcess> processes=new ArrayList<>();
            var runner=new ParallelSimulationRunner((work,console)->{
                int id=id(work);calls.incrementAndGet();
                if(id!=2||!mode.equals("missing")){
                    String text=response(id);
                    if(id==2&&mode.equals("depth"))text=text.replace("0.1 ","0.100001 ");
                    if(id==2&&mode.equals("truncated"))text=text.lines().findFirst().orElseThrow()+"\n";
                    if(id==2&&mode.equals("complex"))text="0 3 4 99 53\n0.1 3 4 99 53\n";
                    Files.writeString(work.resolve(SimulationRunner.LOG),text);
                }
                var process=new FakeProcess(id>=3?100:1,id==2&&mode.equals("exit")?17:0,active);processes.add(process);return process;
            });
            Path output=temp.resolve(mode);
            var ex=assertThrows(IOException.class,()->runner.run(inputs,solver,output,192,4,p->{},()->false));
            assertTrue(ex.getMessage().contains("失败通道 2"),ex.getMessage());assertEquals(4,calls.get());assertEquals(0,active.get());
            assertTrue(processes.get(2).killed);assertTrue(processes.get(3).killed);noFinalTxt(output);
            var p=record(output);assertEquals("FAILED_INCOMPLETE",p.getProperty("state"));assertEquals("1",p.getProperty("completed"));
            assertTrue(p.getProperty("channel.1").startsWith("SUCCESS"));assertTrue(p.getProperty("channel.2").startsWith("FAILED"));assertEquals("PENDING",p.getProperty("channel.5"));
            try(var files=Files.walk(output)){assertTrue(files.anyMatch(f->f.toString().endsWith(".partial")));}
        }
    }
    @Test void stopTerminatesEveryActiveProcessAndKeepsIncompleteState()throws Exception {
        ModelInputs inputs=snapshot();Path solver=solver();AtomicInteger active=new AtomicInteger();AtomicBoolean stop=new AtomicBoolean();
        List<FakeProcess> processes=new ArrayList<>();
        var runner=new ParallelSimulationRunner((work,console)->{var p=new FakeProcess(100,0,active);processes.add(p);return p;});
        Path output=temp.resolve("stopped");
        assertThrows(SimulationRunner.StoppedException.class,()->runner.run(inputs,solver,output,192,4,p->{assertEquals(4,p.running().size());stop.set(true);},stop::get));
        assertEquals(4,processes.size());assertEquals(0,active.get());assertTrue(processes.stream().allMatch(p->p.killed));
        assertEquals("STOPPED_INCOMPLETE",record(output).getProperty("state"));noFinalTxt(output);
    }
    @Test void missingLibraryIsReportedBeforeAnyProcessStarts()throws Exception {
        ModelInputs inputs=snapshot();Path solver=solver();Files.delete(solver.resolve(SimulationRunner.DLL));AtomicInteger calls=new AtomicInteger();
        var runner=new ParallelSimulationRunner((work,console)->{calls.incrementAndGet();throw new IOException("Must not start");});
        var ex=assertThrows(IOException.class,()->runner.run(inputs,solver,temp.resolve("missing-dll"),192,4,p->{},()->false));
        assertTrue(ex.getMessage().contains(solver.resolve(SimulationRunner.DLL).toString()));assertEquals(0,calls.get());
    }
    @Test void progressCombinesOnlyActualPointsAndBuilderLimitsNestedThreads() {
        var running=List.of(new SimulationRunner.Progress(2,3,5,10,.4,20,10,0),new SimulationRunner.Progress(2,4,0,10,Double.NaN,20,10,0));
        var p=new ParallelSimulationRunner.Progress(2,4,running,20);
        assertEquals(2.5/192,p.fraction(),1e-12);assertEquals("当前电极：第 3 个（共 192 个）\n已计算深度点：5 / 10",p.message());
        Path work=temp.resolve("with spaces"),console=temp.resolve("log.txt");var builder=ParallelSimulationRunner.nativeBuilder(work,console,4);
        assertEquals(List.of(work.resolve(SimulationRunner.EXE).toAbsolutePath().toString()),builder.command());assertEquals(work.toFile(),builder.directory());
        for(String key:List.of("OMP_NUM_THREADS","MKL_NUM_THREADS","OPENBLAS_NUM_THREADS"))assertEquals("1",builder.environment().get(key));
    }
    @Test void displayedElectrodeKeepsItsOwnDepthProgressAcrossOutOfOrderCompletion() {
        var laterElectrode=new SimulationRunner.Progress(2,17,1200,1501,1.199,20,10,0);
        var firstUnfinished=new SimulationRunner.Progress(2,9,680,1501,.679,20,10,0);
        var progress=new ParallelSimulationRunner.Progress(2,8,List.of(laterElectrode,firstUnfinished),20);
        assertEquals("当前电极：第 9 个（共 192 个）\n已计算深度点：680 / 1501",progress.message());
        var next=new ParallelSimulationRunner.Progress(3,8,List.of(laterElectrode),21);
        assertEquals("当前电极：第 17 个（共 192 个）\n已计算深度点：1200 / 1501",next.message());
        var waiting=new SimulationRunner.Progress(0,1,0,1501,Double.NaN,0,0,0);
        assertEquals("当前电极：第 1 个（共 192 个）\n已计算深度点：0 / 1501",new ParallelSimulationRunner.Progress(0,8,List.of(waiting),0).message());
        assertEquals("正在准备电极计算…",new ParallelSimulationRunner.Progress(0,8,List.of(),0).message());
        assertEquals("正在核验电极结果…",new ParallelSimulationRunner.Progress(8,8,List.of(),20).message());
        assertEquals("电极计算已完成，正在整理结果",new ParallelSimulationRunner.Progress(192,8,List.of(),20).message());
    }
    @Test void optionalRealParallelBenchmark()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.parallelTrial"));
        Path root=SimulationRunner.DEFAULT_ROOT;
        Path work=Path.of("target","real parallel verification").toAbsolutePath();Files.createDirectories(work);
        var originals=ModelInputs.load(root.resolve("192_NGI_Input"),work.resolve("original-cache"),(a,b)->{});
        Path testSource=Files.createTempDirectory(work,"short-depth-TEST-inputs-");
        for(var card:originals.cards()){
            var lines=new ArrayList<>(Files.readAllLines(card.snapshot()));lines.set(2,"0 0.001 0.01");Files.write(testSource.resolve(card.name()),lines);
        }
        var inputs=ModelInputs.load(testSource,work.resolve("trial-cache"),(a,b)->{});
        Path report=work.resolve("benchmark-"+System.currentTimeMillis()+".txt");
        SimulationRunner.Outcome baseline=null;
        for(int concurrency:new int[]{1,4,8}) {
            int[] peak={0};boolean[] depthProgress={false};long started=System.nanoTime();
            var outcome=new ParallelSimulationRunner().run(inputs,root,work.resolve("output"),8,concurrency,p->{peak[0]=Math.max(peak[0],p.running().size());depthProgress[0]|=p.running().stream().anyMatch(r->r.depthPoints()>0);},()->false);
            double elapsed=(System.nanoTime()-started)/1e9;
            assertEquals(8,outcome.completed());assertNull(outcome.result());assertTrue(depthProgress[0]);
            assertEquals(concurrency,peak[0]);assertEquals("TRIAL_INCOMPLETE",record(outcome.batch()).getProperty("state"));
            if(baseline==null)baseline=outcome;
            double maxDifference=0;
            for(int id=1;id<=8;id++){
                Path relative=Path.of("logs",String.format("Card_%03d_logs.txt",id));
                var expected=SimulationRunner.readNative(baseline.batch().resolve(relative),id);var actual=SimulationRunner.readNative(outcome.batch().resolve(relative),id);
                assertEquals(11,actual.size());
                for(int i=0;i<actual.size();i++){
                    var a=expected.get(i);var b=actual.get(i);
                    assertEquals(a.md(),b.md(),1e-7);assertEquals(a.real(),b.real(),Math.max(1e-9,Math.abs(a.real())*5e-4));assertEquals(a.imaginary(),b.imaginary(),Math.max(1e-9,Math.abs(a.imaginary())*5e-4));
                    assertEquals(a.amplitude(),b.amplitude(),Math.max(1e-9,a.amplitude()*5e-4));assertEquals(a.phase(),b.phase(),.05);
                    maxDifference=Math.max(maxDifference,Math.max(Math.abs(a.real()-b.real()),Math.abs(a.imaginary()-b.imaginary())));
                }
            }
            String line=String.format(Locale.ROOT,"parallelism=%d channels=8 points=11 elapsedSeconds=%.3f peakActive=%d maxRealImagDifference=%.9g batch=%s%n",concurrency,elapsed,peak[0],maxDifference,outcome.batch());
            Files.writeString(report,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.print(line);
        }
        System.out.println("BENCHMARK_REPORT="+report);
    }
}
