package com.stratigraphic.mapping.simulation;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ParallelSimulationRunner {
    private final SimulationRunner.ProcessFactory factory;
    public ParallelSimulationRunner() {this.factory=null;}
    ParallelSimulationRunner(SimulationRunner.ProcessFactory factory) {this.factory=factory;}
    public static int maxParallelism() {return Math.max(1,Math.min(192,Runtime.getRuntime().availableProcessors()));}
    public static int defaultParallelism() {return maxParallelism();}

    public static final class Control {
        private final AtomicBoolean paused=new AtomicBoolean(),stopped=new AtomicBoolean();
        public void pause() {paused.set(true);}
        public void resume() {paused.set(false);}
        public void stop() {stopped.set(true);}
        public boolean isPauseRequested() {return paused.get();}
        public boolean isStopped() {return stopped.get();}
    }
    public record Progress(int completed,int parallelism,List<SimulationRunner.Progress> running,double elapsedSeconds,boolean paused) {
        public Progress {running=List.copyOf(running);}
        public Progress(int completed,int parallelism,List<SimulationRunner.Progress> running,double elapsedSeconds) {this(completed,parallelism,running,elapsedSeconds,false);}
        public double fraction() {
            return (completed+running.stream().mapToDouble(progress->progress.expectedPoints()>0?Math.min(1,(double)progress.depthPoints()/progress.expectedPoints()):0).sum())/192;
        }
        public String message() {
            if(paused)return "计算已暂停\n已完成电极已保存；继续时重算未完成电极";
            var electrode=running.stream().min(Comparator.comparingInt(SimulationRunner.Progress::current));
            if(electrode.isEmpty()) {
                if(completed>=192)return "电极计算已完成，正在整理结果";
                return completed==0?"正在准备电极计算…":"正在核验电极结果…";
            }
            var current=electrode.get();
            return "当前电极：第 "+current.current()+" 个（共 192 个）\n已计算深度点："+current.depthPoints()+" / "+current.expectedPoints();
        }
    }
    private record Running(ModelInputs.Card card,Path work,Path console,Process process,SolverProgress monitor,long started) { }
    public SimulationRunner.Outcome run(ModelInputs inputs,Path solverRoot,Path output,int limit,int parallelism,Consumer<Progress> progress,BooleanSupplier stopRequested)throws IOException,InterruptedException {
        return execute(inputs,solverRoot,output,limit,parallelism,progress,new Control(),stopRequested,null);
    }
    public SimulationRunner.Outcome run(ModelInputs inputs,Path solverRoot,Path output,int limit,int parallelism,Consumer<Progress> progress,Control control)throws IOException,InterruptedException {
        return execute(inputs,solverRoot,output,limit,parallelism,progress,control,control::isStopped,null);
    }
    public SimulationRunner.Outcome resume(Path batch,Path solverRoot,int parallelism,Consumer<Progress> progress,Control control)throws IOException,InterruptedException {
        return resume(batch,solverRoot,192,parallelism,progress,control);
    }
    SimulationRunner.Outcome resume(Path batch,Path solverRoot,int limit,int parallelism,Consumer<Progress> progress,Control control)throws IOException,InterruptedException {
        Path directory=batch.toAbsolutePath().normalize();
        if(!Files.isDirectory(directory)||!directory.getFileName().toString().endsWith("_batch"))throw new IOException("不是有效的未完成批次目录："+batch);
        return execute(null,solverRoot,directory.getParent(),limit,parallelism,progress,control,control::isStopped,directory);
    }
    private SimulationRunner.Outcome execute(ModelInputs initialInputs,Path solverRoot,Path output,int limit,int parallelism,Consumer<Progress> progress,Control control,BooleanSupplier stopRequested,Path existingBatch)throws IOException,InterruptedException {
        if(limit<1||limit>192)throw new IllegalArgumentException("试算数量应为 1～192");
        if(parallelism<1||parallelism>192)throw new IllegalArgumentException("并行通道数应为 1～192");
        Files.createDirectories(output);
        boolean resuming=existingBatch!=null;
        String name=resuming?existingBatch.getFileName().toString().replaceFirst("_batch$",""):
            "NGI_192_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+"_"+UUID.randomUUID().toString().substring(0,8);
        Path batch=resuming?existingBatch:Files.createDirectory(output.resolve(name+"_batch"));
        try(var lockChannel=FileChannel.open(batch.resolve("batch.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var batchLock=claim(lockChannel,batch)) {
            Properties record=resuming?SimulationHistory.readRecord(batch):new Properties();
            if(resuming&&(SimulationHistory.isComplete(batch,record)||SimulationHistory.hasLiveSolver(batch,record)))throw new IOException("该批次已完成或仍在其他进程中计算，不能重复恢复："+batch);
            SimulationHistory.Checkpoint checkpoint=resuming?SimulationHistory.inspect(batch,record):null;
            ModelInputs inputs=resuming?checkpoint.inputs():Objects.requireNonNull(initialInputs);
            InputPreparation.verifySnapshot(inputs);
            Path workRoot=Files.createDirectories(batch.resolve("work")),runtime=Files.createDirectories(batch.resolve("runtime"));
            Path snapshots=Files.createDirectories(batch.resolve("inputs")),logs=Files.createDirectories(batch.resolve("logs"));
            Path partial=batch.resolve(name+".partial");
            NavigableMap<Integer,List<ResponseData.Sample>> results=new TreeMap<>();
            if(resuming)results.putAll(checkpoint.responses());
            if(results.size()>limit)throw new IOException("恢复试算范围小于已归档电极数量");
            NavigableSet<Integer> pending=new TreeSet<>();
            for(int id=1;id<=limit;id++)if(!results.containsKey(id))pending.add(id);
            if(resuming) {
                Path backup=Files.createDirectories(batch.resolve("recovery")).resolve("resume_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8));
                Files.createDirectories(backup);Files.copy(batch.resolve("batch.properties"),backup.resolve("batch.properties"));
                if(Files.isRegularFile(partial))Files.copy(partial,backup.resolve(partial.getFileName()));
                record.setProperty("resumed",Instant.now().toString());
                record.setProperty("resume.count",Integer.toString(Integer.parseInt(record.getProperty("resume.count","0"))+1));
                record.setProperty("reused",Integer.toString(results.size()));record.setProperty("recovery.warnings",String.join("\n",checkpoint.warnings()));record.setProperty("recovery.backup",backup.toString());
            } else {
                record.setProperty("started",Instant.now().toString());record.setProperty("input.summary",inputs.summary());record.setProperty("snapshot",snapshots.toString());
            }
            record.setProperty("parallelism",Integer.toString(parallelism));record.setProperty("native.threads",parallelism>1?"OMP/MKL/OPENBLAS=1":"inherited");
            SamplingPlan.record(inputs,record);
            record.setProperty("state","RUNNING");record.setProperty("completed",Integer.toString(results.size()));
            record.remove("error");record.remove("failed.channel");record.remove("finished");
            for(var card:inputs.cards())record.setProperty("channel."+card.id(),results.containsKey(card.id())?"SUCCESS: recovered; input="+card.name():"PENDING");
            Map<Integer,Running> active=new TreeMap<>();Object processLock=new Object();
            int[] current={0};boolean[] closing={false};long started=System.nanoTime();
            Thread shutdown=new Thread(()->{
                synchronized(processLock) {
                    closing[0]=true;if(record.getProperty("state","").equals("COMPLETE"))return;
                    for(Running job:active.values()) {
                        if(job.process().isAlive())job.process().destroyForcibly();record.setProperty("channel."+job.card().id(),"INTERRUPTED");
                    }
                    record.setProperty("state","INTERRUPTED_INCOMPLETE");record.setProperty("finished",Instant.now().toString());
                    try {SimulationHistory.save(batch,record);}catch(IOException ignored) { }
                }
            },"parallel-solver-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                SimulationHistory.save(batch,record);
                if(!resuming) {
                    Files.copy(inputs.directory().resolve("inputs.properties"),snapshots.resolve("inputs.properties"));
                    inputs.copyProvenanceTo(snapshots);
                    for(var card:inputs.cards())Files.copy(card.snapshot(),snapshots.resolve(card.name()));
                }
                for(var card:inputs.cards())record.setProperty("input.sha256."+card.id(),SimulationHistory.sha256(snapshots.resolve(card.name())));
                if(resuming)rebuildPartial(partial,results);
                List<Path> runtimeFiles=pending.isEmpty()?List.of():deployRuntime(solverRoot,runtime);
                for(Path dependency:runtimeFiles) {
                    String key="runtime.sha256."+dependency.getFileName(),hash=SimulationHistory.sha256(dependency);
                    if(resuming&&record.containsKey(key)&&!record.getProperty(key).equals(hash))throw new IOException("批次运行文件已变化，不能混合恢复计算："+dependency);
                    record.setProperty(key,hash);
                }
                SimulationHistory.save(batch,record);
                int capacity=Math.min(limit,parallelism);
                while(results.size()<limit) {
                    checkStop(stopRequested);
                    while(!control.isPauseRequested()&&active.size()<capacity&&!pending.isEmpty()) {
                        checkStop(stopRequested);
                        int id=pending.pollFirst();var card=inputs.cards().get(id-1);current[0]=id;
                        Path work=workRoot.resolve(String.format("Card_%03d",id));
                        if(Files.exists(work))work=Files.createTempDirectory(workRoot,String.format("Card_%03d_retry_",id));else Files.createDirectory(work);
                        for(Path dependency:runtimeFiles)Files.copy(dependency,work.resolve(dependency.getFileName()));
                        Files.copy(snapshots.resolve(card.name()),work.resolve("NGI_2D_INP.txt"));
                        Path console=logs.resolve(String.format("Card_%03d_console.log",id));
                        if(Files.exists(console))Files.move(console,logs.resolve(String.format("Card_%03d_console_%s.log",id,UUID.randomUUID())),StandardCopyOption.REPLACE_EXISTING);
                        synchronized(processLock) {
                            if(closing[0])throw new SimulationRunner.StoppedException("程序正在关闭");
                            Process process=factory==null?nativeBuilder(work,console,parallelism).start():factory.start(work,console);
                            active.put(id,new Running(card,work,console,process,new SolverProgress(console,card.model()),System.nanoTime()));
                            record.setProperty("channel."+id,"RUNNING");record.setProperty("work."+id,batch.relativize(work).toString());
                            try {record.setProperty("pid."+id,Long.toString(process.pid()));}catch(UnsupportedOperationException ignored) { }
                            process.getOutputStream().close();
                        }
                        SimulationHistory.save(batch,record);
                    }
                    for(Running job:new ArrayList<>(active.values())) {
                        checkStop(stopRequested);current[0]=job.card().id();job.monitor().poll();
                        if(!job.process().waitFor(1,TimeUnit.MILLISECONDS))continue;
                        checkStop(stopRequested);
                        if(job.process().exitValue()!=0)throw new IOException("EXE 退出码 "+job.process().exitValue()+"；日志："+job.console()+"；运行库查找目录："+job.work()+consoleTail(job.console()));
                        Path nativeLog=job.work().resolve(SimulationRunner.LOG);
                        if(!Files.isRegularFile(nativeLog))throw new IOException("通道 "+current[0]+" 未生成 "+SimulationRunner.LOG+"；日志："+job.console()+consoleTail(job.console()));
                        var channel=SimulationRunner.readNative(nativeLog,current[0]);SimulationRunner.validateRequestedDepths(channel,job.card().model());
                        if(!results.isEmpty()&&!SimulationHistory.sameDepths(results.firstEntry().getValue(),channel))throw new IOException("深度向量与已完成通道不一致");
                        Path archived=logs.resolve(String.format("Card_%03d_logs.txt",current[0]));
                        if(Files.exists(archived))Files.copy(archived,logs.resolve(String.format("Card_%03d_previous_%s.txt",current[0],UUID.randomUUID())));
                        Path staged=logs.resolve(archived.getFileName()+".tmp");Files.copy(nativeLog,staged,StandardCopyOption.REPLACE_EXISTING);SimulationHistory.move(staged,archived);
                        try(var writer=Files.newBufferedWriter(partial,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
                            for(var sample:channel){writer.write(sample.line());writer.newLine();}
                        }
                        results.put(current[0],channel);synchronized(processLock){active.remove(current[0]);}
                        record.setProperty("channel."+current[0],"SUCCESS: "+channel.size()+" rows; input="+job.card().name());
                        record.setProperty("completed",Integer.toString(results.size()));SimulationHistory.save(batch,record);cleanSuccessfulScratch(job.work(),runtimeFiles);
                    }
                    if(control.isPauseRequested()&&results.size()<limit) {
                        List<Integer> interrupted=new ArrayList<>(active.keySet());terminateAll(active,processLock,record,"PAUSED_PENDING");pending.addAll(interrupted);
                        record.setProperty("state","PAUSED_INCOMPLETE");record.setProperty("paused",Instant.now().toString());SimulationHistory.save(batch,record);
                        progress.accept(new Progress(results.size(),capacity,List.of(),(System.nanoTime()-started)/1e9,true));
                        while(control.isPauseRequested()){checkStop(stopRequested);Thread.sleep(100);}
                        checkStop(stopRequested);record.setProperty("state","RUNNING");SimulationHistory.save(batch,record);
                    }
                    progress.accept(observe(results.size(),capacity,active.values(),started));
                    if(!active.isEmpty()&&results.size()<limit)Thread.sleep(200);
                }
                current[0]=0;checkStop(stopRequested);Path result=null;
                if(results.size()==192)result=publish(batch,output,name,results,record,stopRequested);else record.setProperty("state","TRIAL_INCOMPLETE");
                record.setProperty("finished",Instant.now().toString());SimulationHistory.save(batch,record);
                return new SimulationRunner.Outcome(batch,result,results.size());
            }catch(IOException|InterruptedException|RuntimeException ex) {
                boolean stopped=ex instanceof SimulationRunner.StoppedException||ex instanceof InterruptedException;
                try {terminateAll(active,processLock,record,stopped?"STOPPED":"ABORTED");}catch(IOException cleanup){ex.addSuppressed(cleanup);}
                if(!stopped&&current[0]>0)record.setProperty("channel."+current[0],"FAILED: "+ex.getMessage());
                record.setProperty("state",stopped?"STOPPED_INCOMPLETE":"FAILED_INCOMPLETE");record.setProperty("failed.channel",Integer.toString(current[0]));
                record.setProperty("completed",Integer.toString(results.size()));record.setProperty("error",ex.toString());record.setProperty("finished",Instant.now().toString());SimulationHistory.save(batch,record);
                if(stopped)throw new SimulationRunner.StoppedException("计算已停止，已完成电极已保存，可从未完成计算中继续\n保留目录："+batch);
                throw new IOException("并行批次未完成，失败通道 "+current[0]+"，已完成 "+results.size()+"/192\n"+ex.getMessage()+"\n已保存数据，可恢复；保留目录："+batch,ex);
            }finally {
                try {terminateAll(active,processLock,record,"ABORTED");}catch(IOException ignored) { }
                try {Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored) { }
            }
        }
    }
    private static FileLock claim(FileChannel channel,Path batch)throws IOException {
        try {FileLock lock=channel.tryLock();if(lock!=null)return lock;}catch(OverlappingFileLockException ignored) { }
        throw new IOException("该批次正在计算或恢复，请勿重复启动："+batch);
    }
    private static List<Path> deployRuntime(Path solverRoot,Path runtime)throws IOException {
        boolean ready=Files.isRegularFile(runtime.resolve(SimulationRunner.EXE))&&Files.isRegularFile(runtime.resolve(SimulationRunner.DLL));
        if(!ready) {
            for(String required:List.of(SimulationRunner.EXE,SimulationRunner.DLL))if(!Files.isRegularFile(runtime.resolve(required))&&!Files.isRegularFile(solverRoot.resolve(required)))throw new IOException("缺少运行文件："+solverRoot.resolve(required)+"；批次备份："+runtime.resolve(required));
            try(var files=Files.list(solverRoot)) {
                for(Path file:files.filter(Files::isRegularFile).toList()) {
                    String name=file.getFileName().toString();
                    if(name.equalsIgnoreCase("call.m")||name.equalsIgnoreCase(SimulationRunner.LOG)||name.equalsIgnoreCase("NGI_2D_INP.txt")||name.startsWith("mesh_output"))continue;
                    if(!Files.exists(runtime.resolve(name)))Files.copy(file,runtime.resolve(name));
                }
            }
        }
        try(var files=Files.list(runtime)){return files.filter(Files::isRegularFile).toList();}
    }
    private static void rebuildPartial(Path partial,NavigableMap<Integer,List<ResponseData.Sample>> results)throws IOException {
        Path staged=partial.resolveSibling(partial.getFileName()+".tmp");
        try(var writer=Files.newBufferedWriter(staged)){for(var channel:results.values())for(var sample:channel){writer.write(sample.line());writer.newLine();}}
        SimulationHistory.move(staged,partial);
    }
    private static Path publish(Path batch,Path output,String name,NavigableMap<Integer,List<ResponseData.Sample>> results,Properties record,BooleanSupplier stop)throws IOException {
        Path staged=batch.resolve("complete.staged"),result=output.resolve(name+".txt");
        SamplingPlan.writeResult(staged,results,record,stop);
        ResponseData.read(staged,(done,total)->{});checkStop(stop);
        if(Files.exists(result)) {
            if(Files.mismatch(staged,result)!=-1)throw new IOException("结果位置已有不同文件，保留原文件："+result);Files.delete(staged);
        } else Files.move(staged,result);
        record.setProperty("result",result.toString());record.setProperty("state","COMPLETE");record.setProperty("finished",Instant.now().toString());
        Path metadata=output.resolve(name+".txt.properties"),metadataTemp=batch.resolve("result.properties.tmp");
        try(var stream=Files.newOutputStream(metadataTemp)){record.store(stream,"Batch metadata");}SimulationHistory.move(metadataTemp,metadata);
        return result;
    }
    static ProcessBuilder nativeBuilder(Path work,Path console,int parallelism) {
        ProcessBuilder builder=new ProcessBuilder(work.resolve(SimulationRunner.EXE).toAbsolutePath().toString()).directory(work.toFile()).redirectErrorStream(true).redirectOutput(console.toFile());
        if(parallelism>1)for(String key:List.of("OMP_NUM_THREADS","MKL_NUM_THREADS","OPENBLAS_NUM_THREADS"))builder.environment().put(key,"1");return builder;
    }
    private static void checkStop(BooleanSupplier stop)throws SimulationRunner.StoppedException {
        if(stop.getAsBoolean()||Thread.currentThread().isInterrupted())throw new SimulationRunner.StoppedException("用户已停止计算");
    }
    private static Progress observe(int completed,int capacity,Collection<Running> active,long started) {
        long now=System.nanoTime();List<SimulationRunner.Progress> details=new ArrayList<>();
        for(Running running:active)details.add(new SimulationRunner.Progress(completed,running.card().id(),running.monitor().points(),running.monitor().expectedPoints(),running.monitor().lastDepth(),(now-started)/1e9,(now-running.started())/1e9,running.monitor().quietSeconds()));
        return new Progress(completed,capacity,details,(now-started)/1e9);
    }
    private static void terminateAll(Map<Integer,Running> active,Object lock,Properties record,String state)throws IOException {
        synchronized(lock) {
            boolean interrupted=Thread.interrupted();IOException failure=null;
            try {
                for(Running running:active.values())if(running.process().isAlive())running.process().destroyForcibly();
                for(Running running:active.values()) {
                    try {if(!running.process().waitFor(10,TimeUnit.SECONDS))failure=new IOException("无法终止电极 "+running.card().id()+" 的求解进程，请检查："+running.work());}
                    catch(InterruptedException ex){interrupted=true;failure=new IOException("等待求解进程停止时中断",ex);}
                    record.setProperty("channel."+running.card().id(),state);
                }
                if(failure!=null)throw failure;active.clear();
            }finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private static void cleanSuccessfulScratch(Path work,List<Path> runtimeFiles) {
        for(Path binary:runtimeFiles)try {Files.deleteIfExists(work.resolve(binary.getFileName()));}catch(IOException ignored) { }
        try(var files=Files.list(work)) {
            for(Path file:files.filter(path->path.getFileName().toString().startsWith("mesh_output")&&Files.isRegularFile(path)).toList())Files.deleteIfExists(file);
        }catch(IOException ignored) { }
    }
    private static String consoleTail(Path console) {
        try(var reader=Files.newBufferedReader(console)) {
            Deque<String> tail=new ArrayDeque<>();String line;
            while((line=reader.readLine())!=null){if(tail.size()==8)tail.removeFirst();tail.addLast(line);}return "\n程序输出：\n"+String.join("\n",tail);
        }catch(IOException ex){return "";}
    }
}
