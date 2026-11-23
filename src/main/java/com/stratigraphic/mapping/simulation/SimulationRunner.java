package com.stratigraphic.mapping.simulation;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial fixed-filename solver protocol, isolated per batch, with durable partial archives. */
public final class SimulationRunner {
    @FunctionalInterface interface ProcessFactory { Process start(Path work,Path console) throws IOException; }
    private final ProcessFactory factory;
    public SimulationRunner() {this((work,console)->new ProcessBuilder(work.resolve(EXE).toAbsolutePath().toString())
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(console.toFile()).start());}
    SimulationRunner(ProcessFactory factory) {this.factory=factory;}
    public static final Path DEFAULT_ROOT=Path.of("C:\\Stratigraphic information");
    public static final String EXE="NGI_2D_MFS_V2.exe", DLL="SOLVER_MUMPS_DLL.dll", LOG="NGI_2D_LOGS.txt";
    public record Outcome(Path batch,Path result,int completed) { }
    public record Progress(int completed,int current,long depthPoints,long expectedPoints,double lastDepth,
                           double elapsedSeconds,double channelSeconds,long quietSeconds) {
        public double fraction() {return (completed+(expectedPoints>0?Math.min(1,(double)depthPoints/expectedPoints):0))/192.0;}
        public String message() {
            String text="已完成 "+completed+"/192";
            if(current>0) {
                text+=" · 正在计算通道 "+current+"\n当前通道深度点 "+depthPoints+"/"+expectedPoints;
                if(depthPoints>0) text+=String.format(Locale.ROOT," · MD %.5f m",lastDepth);
                text+="\n本通道耗时 "+SolverProgress.duration(channelSeconds);
                if(depthPoints>1 && depthPoints<expectedPoints) text+=" · 本通道估计剩余 "+SolverProgress.duration(channelSeconds/depthPoints*(expectedPoints-depthPoints));
                if(depthPoints==expectedPoints) text+=" · 等待程序结束并核验结果";
                if(quietSeconds>=60) text+="\n已有 "+quietSeconds+" 秒未收到新深度响应；进程仍在运行，可停止计算并检查日志";
            }
            text+="\n总耗时 "+SolverProgress.duration(elapsedSeconds);
            if(completed>0 && completed<192) text+=" · 全批估计剩余 "+SolverProgress.duration(Math.max(0,(elapsedSeconds-channelSeconds)/completed*(192-completed)-channelSeconds));
            return text;
        }
    }
    public static final class StoppedException extends IOException {
        StoppedException(String message) {super(message);}
    }
    public Outcome run(ModelInputs inputs,Path solverRoot,Path output,int limit,
                       BiConsumer<Integer,String> progress) throws IOException,InterruptedException {
        return runWithProgress(inputs,solverRoot,output,limit,p->progress.accept(p.completed(),p.message()),()->false);
    }
    public Outcome runWithProgress(ModelInputs inputs,Path solverRoot,Path output,int limit,
                       Consumer<Progress> progress,BooleanSupplier stopRequested) throws IOException,InterruptedException {
        if(limit<1||limit>192) throw new IllegalArgumentException("试算数量应为 1～192");
        InputPreparation.verifySnapshot(inputs);
        Files.createDirectories(output);
        String name="NGI_192_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+"_"+UUID.randomUUID().toString().substring(0,8);
        Path batch=Files.createDirectory(output.resolve(name+"_batch"));
        Path work=Files.createDirectory(batch.resolve("work"));
        Path snapshots=Files.createDirectory(batch.resolve("inputs"));
        Path logs=Files.createDirectory(batch.resolve("logs"));
        Path partial=batch.resolve(name+".partial");
        Properties record=new Properties();
        record.setProperty("started",Instant.now().toString());
        record.setProperty("input.summary",inputs.summary());
        SamplingPlan.record(inputs,record);
        record.setProperty("snapshot",snapshots.toString());
        record.setProperty("state","RUNNING");
        for(var card:inputs.cards()) record.setProperty("channel."+card.id(),"PENDING");
        int current=0,completed=0;
        long start=System.nanoTime();
        List<List<ResponseData.Sample>> responses=new ArrayList<>();
        try {
            save(batch,record);
            for(String required:List.of(EXE,DLL)) if(!Files.isRegularFile(solverRoot.resolve(required)))
                throw new IOException("缺少运行文件："+solverRoot.resolve(required));
            // Deploy sibling dependencies as well as EXE/DLL. Never execute or rewrite call.m.
            try(var stream=Files.list(solverRoot)) {
                for(Path file:stream.filter(Files::isRegularFile).toList()) {
                    String n=file.getFileName().toString();
                    if(!n.equalsIgnoreCase("call.m")&&!n.equalsIgnoreCase(LOG)&&!n.equalsIgnoreCase("NGI_2D_INP.txt"))
                        Files.copy(file,work.resolve(n));
                }
            }
            Files.copy(inputs.directory().resolve("inputs.properties"),snapshots.resolve("inputs.properties"));
            inputs.copyProvenanceTo(snapshots);
            for(var card:inputs.cards()) Files.copy(card.snapshot(),snapshots.resolve(card.name()));
            for(var card:inputs.cards().subList(0,limit)) {
                if(stopRequested.getAsBoolean()) throw new StoppedException("用户已停止计算");
                current=card.id();
                record.setProperty("channel."+current,"RUNNING"); save(batch,record);
                Files.copy(snapshots.resolve(card.name()),work.resolve("NGI_2D_INP.txt"),StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(work.resolve(LOG));
                Path console=logs.resolve(String.format("Card_%03d_console.log",current));
                long channelStart=System.nanoTime();
                SolverProgress monitor=new SolverProgress(console,card.model());
                Process process=factory.start(work,console);
                try {record.setProperty("process.pid",Long.toString(process.pid()));} catch(UnsupportedOperationException ignored) { }
                record.setProperty("console",console.toString());
                Thread cleanup=new Thread(()->{
                    if(process.isAlive()) {
                        process.destroyForcibly();
                        synchronized(record) {
                            record.setProperty("state","INTERRUPTED_INCOMPLETE");
                            record.setProperty("channel."+card.id(),"INTERRUPTED");
                            record.setProperty("finished",Instant.now().toString());
                            try {save(batch,record);} catch(IOException ignored) { }
                        }
                    }
                },"solver-shutdown");
                Runtime.getRuntime().addShutdownHook(cleanup);
                try {
                    save(batch,record);
                    process.getOutputStream().close();
                    progress.accept(observe(completed,current,start,channelStart,monitor));
                    while(!process.waitFor(1,TimeUnit.SECONDS)) {
                        if(stopRequested.getAsBoolean()) throw new StoppedException("用户已停止计算");
                        monitor.poll();
                        progress.accept(observe(completed,current,start,channelStart,monitor));
                    }
                    if(stopRequested.getAsBoolean()) throw new StoppedException("用户已停止计算");
                    monitor.poll();
                } finally {
                    if(process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
                    try {Runtime.getRuntime().removeShutdownHook(cleanup);} catch(IllegalStateException ignored) { }
                }
                if(process.exitValue()!=0) throw new IOException("EXE 退出码 "+process.exitValue()+"；控制台日志："+console+"；运行库查找目录："+work+consoleTail(console));
                Path nativeLog=work.resolve(LOG);
                if(!Files.isRegularFile(nativeLog)) throw new IOException("未生成本轮 "+LOG+"；日志："+console+consoleTail(console));
                Path archived=logs.resolve(String.format("Card_%03d_logs.txt",current));
                Files.copy(nativeLog,archived);
                List<ResponseData.Sample> channel=readNative(archived,current);
                validateRequestedDepths(channel,card.model());
                if(!responses.isEmpty()) {
                    var first=responses.get(0);
                    if(first.size()!=channel.size()) throw new IOException("深度点数量与首个成功通道不一致");
                    for(int i=0;i<first.size();i++) if(Math.abs(first.get(i).md()-channel.get(i).md())>1e-7)
                        throw new IOException("第 "+(i+1)+" 点深度与首个通道不一致："+channel.get(i).md()+" / "+first.get(i).md());
                }
                try(var writer=Files.newBufferedWriter(partial,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
                    for(var sample:channel) { writer.write(sample.line()); writer.newLine(); }
                }
                responses.add(channel); completed++;
                record.setProperty("channel."+current,"SUCCESS: "+channel.size()+" rows; input="+card.name());
                record.setProperty("completed",""+completed); save(batch,record);
                progress.accept(new Progress(completed,0,0,0,Double.NaN,(System.nanoTime()-start)/1e9,0,0));
            }
            Path result=null;
            if(stopRequested.getAsBoolean()) throw new StoppedException("用户已停止计算");
            if(completed==192) {
                Path staged=batch.resolve("complete.staged");
                var indexed=new TreeMap<Integer,List<ResponseData.Sample>>();
                for(var channel:responses)indexed.put(channel.get(0).electrode(),channel);
                SamplingPlan.writeResult(staged,indexed,record,stopRequested);
                ResponseData.read(staged,(a,b)->{});
                result=output.resolve(name+".txt");
                record.setProperty("result",result.toString());
                record.setProperty("state","COMPLETE");
                record.setProperty("finished",Instant.now().toString());
                try(var out=Files.newOutputStream(output.resolve(name+".txt.properties"),StandardOpenOption.CREATE_NEW)) { record.store(out,"Batch metadata"); }
                Files.move(staged,result); // only validated complete data becomes selectable TXT
            } else record.setProperty("state","TRIAL_INCOMPLETE");
            record.setProperty("finished",Instant.now().toString()); save(batch,record);
            return new Outcome(batch,result,completed);
        } catch(IOException|InterruptedException|RuntimeException ex) {
            boolean stopped=ex instanceof StoppedException;
            record.setProperty("state",stopped?"STOPPED_INCOMPLETE":"FAILED_INCOMPLETE");
            if(current>0) record.setProperty("channel."+current,(stopped?"STOPPED: ":"FAILED: ")+ex.getMessage());
            record.setProperty("failed.channel",""+current);
            record.setProperty("error",ex.toString());
            record.setProperty("finished",Instant.now().toString());
            save(batch,record);
            if(stopped) throw new StoppedException("计算已停止，已完成 "+completed+"/192；未发布完整结果\n保留目录："+batch);
            throw new IOException("批次未完成，失败通道 "+current+"，已完成 "+completed+"/192\n"+ex.getMessage()+"\n保留目录："+batch,ex);
        }
    }
    public static List<ResponseData.Sample> readNative(Path path,int id) throws IOException {
        var samples=new ArrayList<ResponseData.Sample>(); double previous=Double.NEGATIVE_INFINITY;
        try(var reader=Files.newBufferedReader(path)) {
            String line; int n=0;
            while((line=reader.readLine())!=null) {
                double[] v=ResponseData.numbers(line,5,"通道 "+id+" 第 "+(++n)+" 行");
                if(v[0]<=previous) throw new IllegalArgumentException("通道 "+id+" 深度未严格递增");
                previous=v[0]; ResponseData.validate(v[1],v[2],v[3],v[4],"通道 "+id+" 第 "+n+" 行");
                samples.add(new ResponseData.Sample(v[0],id,v[1],v[2],v[3],v[4]));
            }
        }
        if(samples.isEmpty()) throw new IOException("通道 "+id+" 输出为空");
        return samples;
    }
    static void validateRequestedDepths(List<ResponseData.Sample> channel,ModelInputs.Model model) throws IOException {
        long expected=(long)Math.floor((model.end()-model.begin())/model.step()+1e-7)+1;
        if(channel.size()!=expected) throw new IOException("输出深度点不完整：需要 "+expected+"，实际 "+channel.size());
        for(int i=0;i<channel.size();i++) if(Math.abs(channel.get(i).md()-(model.begin()+i*model.step()))>5.1e-6)
            throw new IOException("输出第 "+(i+1)+" 点深度与本通道输入采样位置不一致");
    }
    private static void save(Path batch,Properties record) throws IOException {
        synchronized(record) {
        try(var out=Files.newOutputStream(batch.resolve("batch.properties"))) { record.store(out,"Simulation batch"); }
        }
    }
    private static String consoleTail(Path console) {
        if(!Files.isRegularFile(console)) return "";
        try {
            Deque<String> tail=new ArrayDeque<>();
            try(var reader=Files.newBufferedReader(console)) {
                String line;while((line=reader.readLine())!=null) {if(tail.size()==8)tail.removeFirst();tail.addLast(line);}
            }
            return "\n程序输出（末尾）：\n"+String.join("\n",tail);
        } catch(IOException ex) {return "\n程序日志请在上述路径查看";}
    }
    private static Progress observe(int completed,int current,long start,long channelStart,SolverProgress monitor) {
        long now=System.nanoTime();
        return new Progress(completed,current,monitor.points(),monitor.expectedPoints(),monitor.lastDepth(),
            (now-start)/1e9,(now-channelStart)/1e9,monitor.quietSeconds());
    }
}
