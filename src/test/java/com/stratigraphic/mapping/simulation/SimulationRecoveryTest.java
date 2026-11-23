package com.stratigraphic.mapping.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationRecoveryTest {
    @TempDir Path temp;
    private ModelInputs inputs()throws Exception {
        Path source=Files.createTempDirectory(temp,"source-");
        for(int id=1;id<=192;id++)Files.writeString(source.resolve(String.format("Card_%03d.txt",id)),SimulationTest.SHORT_INPUT.replace("2500000",Integer.toString(2500000+id)));
        return ModelInputs.load(source,temp.resolve("cache"),(done,message)->{});
    }
    private Path solver()throws IOException {
        Path root=Files.createTempDirectory(temp,"solver-");Files.writeString(root.resolve(SimulationRunner.EXE),"fake exe");Files.writeString(root.resolve(SimulationRunner.DLL),"fake dll");return root;
    }
    private Path seed(String name,int complete,String state)throws Exception {
        Path output=Files.createDirectories(temp.resolve("output")),batch=Files.createDirectory(output.resolve(name+"_batch"));
        Path snapshot=Files.createDirectory(batch.resolve("inputs")),logs=Files.createDirectory(batch.resolve("logs"));
        var inputs=inputs();Files.copy(inputs.directory().resolve("inputs.properties"),snapshot.resolve("inputs.properties"));
        for(var card:inputs.cards())Files.copy(card.snapshot(),snapshot.resolve(card.name()));
        Path runtime=Files.createDirectory(batch.resolve("runtime"));Files.writeString(runtime.resolve(SimulationRunner.EXE),"fake exe");Files.writeString(runtime.resolve(SimulationRunner.DLL),"fake dll");
        Properties record=new Properties();record.setProperty("state",state);record.setProperty("started",Instant.now().toString());record.setProperty("completed",Integer.toString(complete));
        for(int id=1;id<=192;id++) {
            record.setProperty("channel."+id,id<=complete?"SUCCESS: 2 rows":"INTERRUPTED");
            if(id<=complete)Files.writeString(logs.resolve(String.format("Card_%03d_logs.txt",id)),response(id));
        }
        SimulationHistory.save(batch,record);return batch;
    }
    private static int electrode(Path work){return Integer.parseInt(work.getFileName().toString().substring(5,8));}
    private static String response(int id){return "0 "+id+" 0 "+id+" 0\n0.1 "+id+" 0 "+id+" 0\n";}
    private static final class ProcessStub extends Process {
        int polls;final AtomicInteger active;boolean finished,killed;
        ProcessStub(int polls,AtomicInteger active){this.polls=polls;this.active=active;active.incrementAndGet();}
        private void finish(){if(!finished){finished=true;active.decrementAndGet();}}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor(){finish();return 0;}
        public boolean waitFor(long timeout,TimeUnit unit){if(--polls<=0)finish();return finished;}
        public int exitValue(){if(!finished)throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return !finished;}
        public void destroy(){killed=true;finish();}
    }
    private ParallelSimulationRunner runner(List<Integer> called,AtomicInteger active) {
        return new ParallelSimulationRunner((work,console)->{
            int id=electrode(work);called.add(id);assertFalse(Files.exists(work.resolve(SimulationRunner.LOG)));
            assertTrue(Files.readString(work.resolve("NGI_2D_INP.txt")).startsWith(Integer.toString(2500000+id)));
            Files.writeString(work.resolve(SimulationRunner.LOG),response(id));return new ProcessStub(1,active);
        });
    }
    @Test void legacy176ElectrodesSurviveRestartAndOnlyRemaining16AreComputed()throws Exception {
        Path batch=seed("legacy176",176,"INTERRUPTED_INCOMPLETE");
        byte[] original=Files.readAllBytes(batch.resolve("logs/Card_001_logs.txt"));
        List<Integer> called=new ArrayList<>();AtomicInteger active=new AtomicInteger();
        var outcome=runner(called,active).resume(batch,temp.resolve("delivery-no-longer-exists"),8,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(16,called.size());assertEquals(177,called.get(0));assertEquals(192,outcome.completed());assertEquals(batch.toAbsolutePath(),outcome.batch());
        assertArrayEquals(original,Files.readAllBytes(batch.resolve("logs/Card_001_logs.txt")));
        var data=ResponseData.read(outcome.result(),(done,total)->{});assertEquals(2,data.size());
        for(int id=1;id<=192;id++)assertEquals(id,data.sample(1,id).amplitude());
        var record=SimulationHistory.readRecord(batch);assertEquals("176",record.getProperty("reused"));assertEquals("COMPLETE",record.getProperty("state"));
        assertEquals(0,active.get());assertTrue(SimulationHistory.recent(temp.resolve("output"),3).isEmpty());
    }
    @Test void pauseKillsUnfinishedProcessesButContinueNeverRepeatsCompletedElectrodes()throws Exception {
        var inputs=inputs();Path solver=solver();AtomicInteger active=new AtomicInteger();List<Integer> called=new ArrayList<>();List<ProcessStub> processes=new ArrayList<>();
        var control=new ParallelSimulationRunner.Control();AtomicBoolean paused=new AtomicBoolean();
        var runner=new ParallelSimulationRunner((work,console)->{
            int id=electrode(work);called.add(id);Files.writeString(work.resolve(SimulationRunner.LOG),response(id));
            var process=new ProcessStub(id==1||paused.get()?1:50,active);processes.add(process);return process;
        });
        var outcome=runner.run(inputs,solver,temp.resolve("output"),4,4,progress->{
            if(progress.completed()==1&&!paused.get())control.pause();
            if(progress.paused()) {
                assertEquals(0,active.get());assertEquals(1,progress.completed());assertTrue(progress.message().contains("计算已暂停"));
                try {
                    Path batch=SimulationHistory.recent(temp.resolve("output"),3).stream().map(SimulationHistory.Entry::batch).findFirst().orElse(null);
                    assertNull(batch,"A paused batch is still held by its current task");
                }catch(IOException ex){throw new UncheckedIOException(ex);}
                paused.set(true);control.resume();
            }
        },control);
        assertTrue(paused.get());assertEquals(4,outcome.completed());assertNull(outcome.result());
        assertEquals(1,Collections.frequency(called,1));for(int id=2;id<=4;id++)assertEquals(2,Collections.frequency(called,id));
        assertTrue(processes.subList(1,4).stream().allMatch(process->process.killed));assertEquals(0,active.get());
    }
    @Test void stopWhilePausedCanResumeInANewRunnerAndFreshWorkDirectory()throws Exception {
        Path batch=seed("pausedStop",1,"STOPPED_INCOMPLETE");AtomicInteger active=new AtomicInteger();var control=new ParallelSimulationRunner.Control();
        var first=new ParallelSimulationRunner((work,console)->new ProcessStub(50,active));
        AtomicBoolean asked=new AtomicBoolean();
        assertThrows(SimulationRunner.StoppedException.class,()->first.resume(batch,solver(),4,progress->{
            if(!asked.getAndSet(true))control.pause();
            if(progress.paused()){assertEquals(0,active.get());control.stop();}
        },control));
        assertEquals("STOPPED_INCOMPLETE",SimulationHistory.readRecord(batch).getProperty("state"));
        assertEquals(1,SimulationHistory.inspect(batch).responses().size());
        List<Integer> called=new ArrayList<>();var outcome=runner(called,active).resume(batch,solver(),2,4,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(List.of(2),called);assertEquals(2,outcome.completed());assertEquals(0,active.get());
        try(var work=Files.list(batch.resolve("work"))){assertTrue(work.anyMatch(path->path.getFileName().toString().startsWith("Card_002_retry_")));}
    }
    @Test void recoveryUsesPartialWhenArchiveIsMissingAndRecomputesTruncatedElectrode()throws Exception {
        Path batch=seed("partialRecovery",1,"RUNNING");
        Files.delete(batch.resolve("logs/Card_001_logs.txt"));
        String name=batch.getFileName().toString().replaceFirst("_batch$","");
        Files.writeString(batch.resolve(name+".partial"),"0 1 1 0 1 0\n0.1 1 1 0 1 0\n0 2 2 0 2 0\n0.1 2 trunca");
        var checkpoint=SimulationHistory.inspect(batch);assertEquals(Set.of(1),checkpoint.responses().keySet());assertFalse(checkpoint.warnings().isEmpty());
        List<Integer> called=new ArrayList<>();var outcome=runner(called,new AtomicInteger()).resume(batch,solver(),2,4,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(List.of(2),called);assertEquals(2,outcome.completed());
        assertEquals(4,Files.readAllLines(batch.resolve(name+".partial")).size());assertTrue(Files.exists(Path.of(SimulationHistory.readRecord(batch).getProperty("recovery.backup")).resolve(name+".partial")));
    }
    @Test void changedInputAndPathTraversalAreRejectedBeforeAnySolverRuns()throws Exception {
        Path batch=seed("changedInput",1,"INTERRUPTED_INCOMPLETE");var record=SimulationHistory.readRecord(batch);
        Path input=batch.resolve("inputs/Card_001.txt");record.setProperty("input.sha256.1",SimulationHistory.sha256(input));SimulationHistory.save(batch,record);
        Files.writeString(input,Files.readString(input).replace("2500001","3500001"));
        AtomicInteger calls=new AtomicInteger();var runner=new ParallelSimulationRunner((work,console)->{calls.incrementAndGet();throw new IOException("Must not start");});
        assertThrows(IOException.class,()->runner.resume(batch,solver(),4,progress->{},new ParallelSimulationRunner.Control()));assertEquals(0,calls.get());
        Properties manifest=new Properties();try(var stream=Files.newInputStream(batch.resolve("inputs/inputs.properties"))){manifest.load(stream);}
        manifest.setProperty("card.1","../Card_001.txt");try(var stream=Files.newOutputStream(batch.resolve("inputs/inputs.properties"))){manifest.store(stream,"");}
        assertThrows(IOException.class,()->ModelInputs.restoreSnapshot(batch.resolve("inputs")));
    }
    @Test void historyOffersOnlyLatestThreeAndLocksPreventDuplicateRecovery()throws Exception {
        for(int index=0;index<5;index++) {
            Path batch=seed("history"+index,index,index==4?"PAUSED_INCOMPLETE":"RUNNING");
            var record=SimulationHistory.readRecord(batch);record.setProperty("started",Instant.parse("2026-11-16T00:00:00Z").plusSeconds(index).toString());SimulationHistory.save(batch,record);
        }
        var recent=SimulationHistory.recent(temp.resolve("output"),3);assertEquals(List.of(4,3,2),recent.stream().map(SimulationHistory.Entry::completed).toList());
        Path locked=recent.get(0).batch();
        try(var channel=FileChannel.open(locked.resolve("batch.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
            assertEquals(List.of(3,2,1),SimulationHistory.recent(temp.resolve("output"),3).stream().map(SimulationHistory.Entry::completed).toList());
            var runner=runner(new ArrayList<>(),new AtomicInteger());
            assertThrows(IOException.class,()->runner.resume(locked,solver(),4,progress->{},new ParallelSimulationRunner.Control()));
        }
    }
    @Test void allHistoryIncludesCompletedAndOlderBatchesButExcludesLockedTasks()throws Exception {
        for(int index=0;index<5;index++) {
            Path batch=seed("allHistory"+index,index==4?192:index,index==4?"COMPLETE":"STOPPED_INCOMPLETE");
            var record=SimulationHistory.readRecord(batch);
            record.setProperty("started",Instant.parse("2026-11-16T00:00:00Z").plusSeconds(index).toString());
            SimulationHistory.save(batch,record);
        }
        Path result=temp.resolve("output/allHistory4.txt");Files.writeString(result,"completed fixture");
        var history=SimulationHistory.all(temp.resolve("output"));
        assertEquals(List.of(192,3,2,1,0),history.stream().map(SimulationHistory.Entry::completed).toList());
        assertFalse(history.get(0).resumable());assertTrue(history.get(0).toString().contains("已完成"));
        assertTrue(history.subList(1,5).stream().allMatch(SimulationHistory.Entry::resumable));
        Path locked=history.get(1).batch();
        try(var channel=FileChannel.open(locked.resolve("batch.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
            assertEquals(4,SimulationHistory.all(temp.resolve("output")).size());
        }
        Files.delete(result);
        assertTrue(SimulationHistory.all(temp.resolve("output")).get(0).resumable(),"Missing publication can be recovered from archived electrodes");
    }
    @Test void completeArchivesCanBePublishedWithoutRecalculatingAfterInterruptedPublication()throws Exception {
        Path batch=seed("allComputed",192,"RUNNING");List<Integer> called=new ArrayList<>();
        Files.delete(batch.resolve("runtime").resolve(SimulationRunner.EXE));Files.delete(batch.resolve("runtime").resolve(SimulationRunner.DLL));
        var outcome=runner(called,new AtomicInteger()).resume(batch,temp.resolve("missing-delivery"),4,progress->{},new ParallelSimulationRunner.Control());
        assertTrue(called.isEmpty());assertNotNull(outcome.result());
        var record=SimulationHistory.readRecord(batch);record.setProperty("state","RUNNING");SimulationHistory.save(batch,record);
        var republished=runner(called,new AtomicInteger()).resume(batch,temp.resolve("missing-delivery"),4,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(outcome.result(),republished.result());assertTrue(called.isEmpty());
    }
    @Test void invalidArchivedElectrodeIsRecomputedWithoutDiscardingOther175()throws Exception {
        Path batch=seed("oneInvalid",176,"FAILED_INCOMPLETE");
        Files.writeString(batch.resolve("logs/Card_050_logs.txt"),"0 3 4 99 53\n0.1 3 4 99 53\n");
        assertEquals(175,SimulationHistory.inspect(batch).responses().size());
        List<Integer> called=new ArrayList<>();
        var outcome=runner(called,new AtomicInteger()).resume(batch,temp.resolve("missing-delivery"),8,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(17,called.size());assertTrue(called.contains(50));assertFalse(called.contains(49));assertEquals(192,outcome.completed());
        assertFalse(SimulationHistory.readRecord(batch).getProperty("recovery.warnings").isBlank());
    }
    @Test void optionalRealPauseStopAndResume()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.resumeTrial"));
        Path work=Files.createDirectories(Path.of("target","real recovery verification").toAbsolutePath());
        Path root=SimulationRunner.DEFAULT_ROOT;
        var originals=ModelInputs.load(root.resolve("192_NGI_Input"),work.resolve("original-cache"),(done,message)->{});
        Path source=Files.createTempDirectory(work,"short-depth-TEST-");
        for(var card:originals.cards()) {
            var lines=new ArrayList<>(Files.readAllLines(card.snapshot()));lines.set(2,"0 0.001 0.002");Files.write(source.resolve(card.name()),lines);
        }
        var inputs=ModelInputs.load(source,work.resolve("test-cache"),(done,message)->{});
        var control=new ParallelSimulationRunner.Control();AtomicBoolean paused=new AtomicBoolean();
        Path output=Files.createTempDirectory(work,"output-");
        assertThrows(SimulationRunner.StoppedException.class,()->new ParallelSimulationRunner().run(inputs,root,output,2,1,progress->{
            if(progress.completed()==1&&progress.running().stream().anyMatch(electrode->electrode.depthPoints()>0))control.pause();
            if(progress.paused()){assertEquals(1,progress.completed());paused.set(true);control.stop();}
        },control));
        assertTrue(paused.get());Path batch=SimulationHistory.recent(output,3).get(0).batch();
        assertEquals(1,SimulationHistory.inspect(batch).responses().size());
        String firstHash=SimulationHistory.sha256(batch.resolve("logs/Card_001_logs.txt"));
        var outcome=new ParallelSimulationRunner().resume(batch,work.resolve("no-delivery-needed"),2,1,progress->{},new ParallelSimulationRunner.Control());
        assertEquals(2,outcome.completed());assertNull(outcome.result());assertEquals(firstHash,SimulationHistory.sha256(batch.resolve("logs/Card_001_logs.txt")));
        assertEquals("1",SimulationHistory.readRecord(batch).getProperty("reused"));
        assertEquals(3,SimulationRunner.readNative(batch.resolve("logs/Card_002_logs.txt"),2).size());
        System.out.println("REAL_RECOVERY_TRIAL completed=2 reused=1 pointsPerElectrode=3 batch="+batch);
    }
    @Test void inspectActualInterruptedBatchReadOnly()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("simulation.inspectHistory"));
        Path batch=SimulationRunner.DEFAULT_ROOT.resolve("output/NGI_192_20261116_094012_679d9985_batch");
        var checkpoint=SimulationHistory.inspect(batch);
        assertEquals(176,checkpoint.responses().size());assertTrue(checkpoint.warnings().isEmpty(),checkpoint.warnings().toString());
        assertEquals(1501,checkpoint.responses().get(176).size());
        System.out.println("READ_ONLY_RECOVERY_CHECK batch="+batch+" completeElectrodes="+checkpoint.responses().size()+" remaining="+(192-checkpoint.responses().size()));
    }
}
