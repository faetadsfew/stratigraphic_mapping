package com.stratigraphic.mapping.simulation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Six-column response index. No normalization or interpolation of source measurements. */
public final class ResponseData {
    public record Sample(double md,int electrode,double real,double imaginary,double amplitude,double phase) {
        public String line() { return md+"\t"+electrode+"\t"+real+"\t"+imaginary+"\t"+amplitude+"\t"+phase; }
    }
    private final Path source;
    private final NavigableMap<Double,Sample[]> rows;
    private final double[] depths;
    public ResponseData(Path source,NavigableMap<Double,Sample[]> rows) {
        this.source=source; this.rows=rows; this.depths=rows.keySet().stream().mapToDouble(Double::doubleValue).toArray();
    }
    public Path source() { return source; }
    public int size() { return depths.length; }
    public double depth(int index) { return depths[index]; }
    public Sample sample(int index,int electrode) { return rows.get(depths[index])[electrode-1]; }
    public double min(boolean phase) { return rows.values().stream().flatMap(Arrays::stream).mapToDouble(s->phase?s.phase():s.amplitude()).min().orElseThrow(); }
    public double max(boolean phase) { return rows.values().stream().flatMap(Arrays::stream).mapToDouble(s->phase?s.phase():s.amplitude()).max().orElseThrow(); }
    public double minPositive() { return rows.values().stream().flatMap(Arrays::stream).mapToDouble(Sample::amplitude).filter(v->v>0).min().orElse(Double.NaN); }
    public long nonPositive() { return rows.values().stream().flatMap(Arrays::stream).filter(s->s.amplitude()<=0).count(); }
    public int nearestDepth(double md) {
        int i=Arrays.binarySearch(depths,md);
        if(i>=0) return i;
        i=-i-1;
        if(i==0) return 0;
        if(i==depths.length) return i-1;
        return md-depths[i-1]<=depths[i]-md?i-1:i;
    }
    public static ResponseData read(Path file,BiConsumer<Long,Long> progress) throws IOException {
        var rows=new TreeMap<Double,Sample[]>();
        long bytes=Files.size(file), read=0, count=0;
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            String line;
            while((line=reader.readLine())!=null) {
                count++; read+=line.getBytes(StandardCharsets.UTF_8).length+1;
                double[] v=numbers(line,6,"第 "+count+" 行");
                if(v[1]!=Math.rint(v[1])||v[1]<1||v[1]>192) throw new IllegalArgumentException("第 "+count+" 行：电极编号必须为 1～192 整数");
                validate(v[2],v[3],v[4],v[5],"第 "+count+" 行");
                double md=v[0]==0?0:v[0];
                int id=(int)v[1];
                Sample[] row=rows.computeIfAbsent(md,k->new Sample[192]);
                if(row[id-1]!=null) throw new IllegalArgumentException("重复记录：MD="+md+"，电极="+id);
                row[id-1]=new Sample(md,id,v[2],v[3],v[4],v[5]);
                if(count%1024==0) progress.accept(Math.min(read,bytes),bytes);
            }
        }
        if(rows.isEmpty()) throw new IllegalArgumentException("结果文件为空");
        for(var entry:rows.entrySet()) for(int i=0;i<192;i++) if(entry.getValue()[i]==null)
            throw new IllegalArgumentException("MD="+entry.getKey()+" 缺少电极 "+(i+1));
        progress.accept(bytes,bytes);
        return new ResponseData(file,rows);
    }
    public static double[] numbers(String line,int count,String where) {
        String[] parts=line.trim().split("\\s+");
        if(parts.length!=count) throw new IllegalArgumentException(where+"：应有 "+count+" 列，实际 "+parts.length);
        double[] values=new double[count];
        for(int i=0;i<count;i++) {
            try { values[i]=Double.parseDouble(parts[i].replace('D','E').replace('d','e')); }
            catch(NumberFormatException ex) { throw new IllegalArgumentException(where+"：非法数字 "+parts[i]); }
            if(!Double.isFinite(values[i])) throw new IllegalArgumentException(where+"：存在 NaN/Inf");
        }
        return values;
    }
    // Native text rounding: relative 5e-4 for magnitude, 0.05 degree for phase.
    public static void validate(double real,double imaginary,double amplitude,double phase,String where) {
        double expected=Math.hypot(real,imaginary);
        if(!Double.isFinite(expected)||Math.abs(amplitude-expected)>Math.max(1e-9,expected*5e-4))
            throw new IllegalArgumentException(where+"：模值与实部/虚部不一致");
        if(amplitude!=0 && expected!=0) {
            double delta=Math.IEEEremainder(phase-Math.toDegrees(Math.atan2(imaginary,real)),360);
            if(Math.abs(delta)>0.05) throw new IllegalArgumentException(where+"：相位与 atan2 不一致");
        }
    }
    public String summary() {
        return describe(true);
    }
    public String resultSummary() {return describe(false);}
    private String describe(boolean includeModel) {
        String metadata="未提供";
        String samplingMetadata="";
        Path sidecar=source.resolveSibling(source.getFileName()+".properties");
        if(Files.isRegularFile(sidecar)) {
            Properties p=new Properties();
            try(var in=Files.newInputStream(sidecar)) { p.load(in); metadata=p.getProperty("input.summary","未提供"); samplingMetadata=p.getProperty("sampling.description",""); }
            catch(IOException ex) { metadata="关联记录不可读："+ex.getMessage(); }
        }
        String warning="";
        if(nonPositive()>0) {
            Sample first=rows.values().stream().flatMap(Arrays::stream).filter(s->s.amplitude()<=0).findFirst().orElseThrow();
            warning="\n非正模值 "+nonPositive()+" 个，以灰色标记；首个 MD="+first.md()+" 电极="+first.electrode();
        }
        return "结果文件："+source+"\nMD："+depths[0]+"～"+depths[depths.length-1]+" m"
            +"\n深度点："+size()+" · 电极：192\n记录数："+(long)size()*192
            +"\n实际模值："+min(false)+"～"+max(false)+" Ω·m\n实际相位："+min(true)+"～"+max(true)+"°"+warning
            +(samplingMetadata.isEmpty()?"":"\n"+samplingMetadata)+(includeModel?"\n关联模型参数：\n"+metadata:"");
    }
}
