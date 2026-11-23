package com.stratigraphic.mapping.simulation;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Incrementally observes complete response lines from solver stdout, never treating them as final results. */
final class SolverProgress {
    private final Path console;
    private final ModelInputs.Model model;
    private long position;
    private int points;
    private double lastDepth=Double.NaN;
    private final StringBuilder pending=new StringBuilder();
    private long lastResponse=System.nanoTime();

    SolverProgress(Path console,ModelInputs.Model model) {this.console=console;this.model=model;}

    void poll() throws IOException {
        if(!Files.isRegularFile(console)) return;
        try(RandomAccessFile file=new RandomAccessFile(console.toFile(),"r")) {
            file.seek(position);
            byte[] buffer=new byte[8192];int read;
            while((read=file.read(buffer))>0) {
                for(int i=0;i<read;i++) {
                    char c=(char)(buffer[i]&0xff);
                    if(c=='\n') {accept(pending.toString());pending.setLength(0);}
                    else if(c!='\r' && pending.length()<4096) pending.append(c);
                }
            }
            position=file.getFilePointer();
        }
    }

    private void accept(String line) {
        try {
            double[] v=ResponseData.numbers(line,5,"实时进度");
            ResponseData.validate(v[1],v[2],v[3],v[4],"实时进度");
            // Match the next requested depth; ignore headings, repeated lines and arbitrary numbers.
            double expected=model.begin()+points*model.step();
            if(Math.abs(v[0]-expected)>5.1e-6 || points>=expectedPoints()) return;
            points++;lastDepth=v[0];lastResponse=System.nanoTime();
        } catch(IllegalArgumentException ignored) { /* Final native file is validated independently. */ }
    }

    long expectedPoints() {return Math.max(1,(long)Math.floor((model.end()-model.begin())/model.step()+1e-7)+1);}
    int points() {return points;}
    double lastDepth() {return lastDepth;}
    long quietSeconds() {return (System.nanoTime()-lastResponse)/1_000_000_000;}

    static String duration(double seconds) {
        long s=Math.max(0,(long)seconds);
        if(s>=3600) return String.format(Locale.ROOT,"%d 小时 %d 分",s/3600,s%3600/60);
        return String.format(Locale.ROOT,"%d 分 %d 秒",s/60,s%60);
    }
}
