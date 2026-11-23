package com.stratigraphic.mapping.simulation;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class SimulationHistory {
    private SimulationHistory() { }

    public record Entry(Path batch,int completed,String state,Instant updated) {
        public boolean resumable() {return !state.equals("COMPLETE");}
        @Override public String toString() {
            String date=DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(updated);
            return date+" · "+(resumable()?"未完成":"已完成")+"\n已归档 "+completed+" / 192 个电极";
        }
    }
    public record Checkpoint(ModelInputs inputs,NavigableMap<Integer,List<ResponseData.Sample>> responses,List<String> warnings) { }

    public static List<Entry> recent(Path output,int limit)throws IOException {
        return scan(output,limit,false);
    }
    public static List<Entry> all(Path output)throws IOException {
        return scan(output,Integer.MAX_VALUE,true);
    }
    private static List<Entry> scan(Path output,int limit,boolean includeComplete)throws IOException {
        if(limit<1||!Files.isDirectory(output))return List.of();
        List<Entry> entries=new ArrayList<>();
        try(var directories=Files.list(output)) {
            for(Path batch:directories.filter(Files::isDirectory).filter(path->path.getFileName().toString().endsWith("_batch")).toList()) {
                try {
                    Properties record=readRecord(batch);
                    boolean complete=isComplete(batch,record);
                    if((complete&&!includeComplete)||(!complete&&!Files.isRegularFile(batch.resolve("inputs/inputs.properties")))||isLocked(batch)||hasLiveSolver(batch,record))continue;
                    int completed=0;
                    for(int id=1;id<=192;id++)if(record.getProperty("channel."+id,"").startsWith("SUCCESS"))completed++;
                    Instant updated=Files.getLastModifiedTime(batch.resolve("batch.properties")).toInstant();
                    for(String key:List.of("finished","resumed","started")) {
                        try {updated=Instant.parse(record.getProperty(key));break;}catch(RuntimeException ignored) { }
                    }
                    String state=record.getProperty("state","UNKNOWN");
                    if(!complete&&state.equals("COMPLETE"))state="PUBLICATION_INCOMPLETE";
                    entries.add(new Entry(batch.toAbsolutePath().normalize(),completed,state,updated));
                }catch(IOException|RuntimeException ignored) { }
            }
        }
        entries.sort(Comparator.comparing(Entry::updated).reversed().thenComparing(entry->entry.batch().toString()));
        return List.copyOf(entries.subList(0,Math.min(limit,entries.size())));
    }
    public static Properties readRecord(Path batch)throws IOException {
        Properties record=new Properties();
        try(var input=Files.newInputStream(batch.resolve("batch.properties"))){record.load(input);}
        return record;
    }
    static boolean isComplete(Path batch,Properties record) {
        String name=batch.getFileName().toString();
        return record.getProperty("state","").equals("COMPLETE")&&name.endsWith("_batch")&&Files.isRegularFile(batch.resolveSibling(name.substring(0,name.length()-6)+".txt"));
    }
    private static boolean isLocked(Path batch)throws IOException {
        Path file=batch.resolve("batch.lock");if(!Files.exists(file))return false;
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)) {
            try(var lock=channel.tryLock()){return lock==null;}
            catch(OverlappingFileLockException ex){return true;}
        }
    }
    static boolean hasLiveSolver(Path batch,Properties record) {
        Path directory=batch.toAbsolutePath().normalize();
        for(int id=1;id<=192;id++) {
            try {
                long pid=Long.parseLong(record.getProperty("pid."+id,"0"));
                if(pid<=0)continue;
                var process=ProcessHandle.of(pid);
                if(process.isEmpty()||!process.get().isAlive())continue;
                var command=process.get().info().command();
                if(command.isPresent()) {
                    Path executable=Path.of(command.get()).toAbsolutePath().normalize();
                    if(executable.startsWith(directory)&&executable.getFileName().toString().equalsIgnoreCase(SimulationRunner.EXE))return true;
                }
            }catch(RuntimeException ignored) { }
        }
        return false;
    }
    public static Checkpoint inspect(Path batch)throws IOException {
        return inspect(batch,readRecord(batch));
    }
    static Checkpoint inspect(Path batch,Properties record)throws IOException {
        ModelInputs inputs=ModelInputs.restoreSnapshot(batch.resolve("inputs"));
        NavigableMap<Integer,List<ResponseData.Sample>> responses=new TreeMap<>();
        List<String> warnings=new ArrayList<>();
        for(var card:inputs.cards()) {
            String expectedHash=record.getProperty("input.sha256."+card.id());
            if(expectedHash!=null&&!expectedHash.equals(sha256(card.snapshot())))throw new IOException("电极 "+card.id()+" 输入快照已变化，不能混合恢复计算："+card.snapshot());
            Path archived=batch.resolve("logs").resolve(String.format("Card_%03d_logs.txt",card.id()));
            if(!Files.isRegularFile(archived))continue;
            try {
                var channel=SimulationRunner.readNative(archived,card.id());
                SimulationRunner.validateRequestedDepths(channel,card.model());
                responses.put(card.id(),channel);
            }catch(IOException|IllegalArgumentException ex){warnings.add("电极 "+card.id()+" 归档不能复用："+ex.getMessage());}
        }
        if(responses.size()<192)recoverPartial(batch,inputs,responses,warnings);
        if(!responses.isEmpty()) {
            var reference=responses.firstEntry().getValue();
            for(var iterator=responses.entrySet().iterator();iterator.hasNext();) {
                var entry=iterator.next();
                if(!sameDepths(reference,entry.getValue())) {
                    warnings.add("电极 "+entry.getKey()+" 深度向量与已归档电极不一致，将重算");iterator.remove();
                }
            }
        }
        return new Checkpoint(inputs,responses,List.copyOf(warnings));
    }
    private static void recoverPartial(Path batch,ModelInputs inputs,NavigableMap<Integer,List<ResponseData.Sample>> responses,List<String> warnings)throws IOException {
        String name=batch.getFileName().toString();
        Path partial=batch.resolve(name.substring(0,name.length()-6)+".partial");
        if(!Files.isRegularFile(partial))return;
        Map<Integer,List<ResponseData.Sample>> grouped=new TreeMap<>();Set<Integer> invalid=new HashSet<>();
        try(var reader=Files.newBufferedReader(partial)) {
            String line;int row=0;
            while((line=reader.readLine())!=null) {
                row++;int electrode=0;
                try {
                    double[] values=ResponseData.numbers(line,6,"未完成汇总第 "+row+" 行");
                    if(values[1]!=Math.rint(values[1])||values[1]<1||values[1]>192)throw new IllegalArgumentException("电极编号无效");
                    electrode=(int)values[1];if(responses.containsKey(electrode))continue;
                    ResponseData.validate(values[2],values[3],values[4],values[5],"电极 "+electrode);
                    grouped.computeIfAbsent(electrode,key->new ArrayList<>()).add(new ResponseData.Sample(values[0],electrode,values[2],values[3],values[4],values[5]));
                }catch(IllegalArgumentException ex) {
                    if(electrode>0)invalid.add(electrode);
                    if(warnings.size()<200)warnings.add("忽略未完成汇总第 "+row+" 行："+ex.getMessage());
                }
            }
        }
        for(var entry:grouped.entrySet()) {
            if(invalid.contains(entry.getKey()))continue;
            var channel=entry.getValue();channel.sort(Comparator.comparingDouble(ResponseData.Sample::md));
            try {
                for(int depth=1;depth<channel.size();depth++)if(channel.get(depth).md()<=channel.get(depth-1).md())throw new IOException("存在重复或无序深度记录");
                SimulationRunner.validateRequestedDepths(channel,inputs.cards().get(entry.getKey()-1).model());
                responses.put(entry.getKey(),List.copyOf(channel));
            }catch(IOException ex){warnings.add("电极 "+entry.getKey()+" 未完成汇总不完整，将重算："+ex.getMessage());}
        }
    }
    static boolean sameDepths(List<ResponseData.Sample> first,List<ResponseData.Sample> other) {
        if(first.size()!=other.size())return false;
        for(int depth=0;depth<first.size();depth++)if(Math.abs(first.get(depth).md()-other.get(depth).md())>1e-7)return false;
        return true;
    }
    static String sha256(Path file)throws IOException {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    static void save(Path batch,Properties record)throws IOException {
        synchronized(record) {
            Path staged=batch.resolve("batch.properties.tmp");
            try(var output=Files.newOutputStream(staged)){record.store(output,"Simulation checkpoint");}
            move(staged,batch.resolve("batch.properties"));
        }
    }
    static void move(Path source,Path destination)throws IOException {
        try {Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException ex){Files.move(source,destination,StandardCopyOption.REPLACE_EXISTING);}
    }
}
