package com.stratigraphic.mapping.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.*;

/** Validated immutable input snapshot. Original bytes are retained without conversion. */
public final class ModelInputs {
    public static final int CHANNELS = 192;
    public record Model(double frequency, double diameterInches, double offset, double mudRho,
                        double mudEps, double begin, double step, double end,
                        List<List<Double>> layers, List<List<Double>> fractures) { }
    public record Card(int id, String name, Path snapshot, Model model) { }
    private final Path source, directory;
    private final List<Card> cards;
    private ModelInputs(Path source, Path directory, List<Card> cards) {
        this.source = source; this.directory = directory; this.cards = List.copyOf(cards);
    }
    public List<Card> cards() { return cards; }
    public Path directory() { return directory; }
    public static ModelInputs restoreSnapshot(Path snapshot) throws IOException {
        Path directory=snapshot.toAbsolutePath().normalize();
        Properties manifest=new Properties();
        try(var input=Files.newInputStream(directory.resolve("inputs.properties"))) {manifest.load(input);}
        var cards=new ArrayList<Card>();
        for(int id=1;id<=CHANNELS;id++) {
            String name=manifest.getProperty("card."+id);
            if(name==null||!name.matches("(?i)Card_"+String.format("%03d",id)+"(?:_|\\.).*")||!Path.of(name).getFileName().toString().equals(name))
                throw new IOException("批次输入编号映射无效：电极 "+id);
            Path file=directory.resolve(name).normalize();
            if(!file.getParent().equals(directory)||!Files.isRegularFile(file))throw new IOException("批次输入快照缺失："+file);
            try {
                Model model=parse(Files.readString(file));
                String expected=manifest.getProperty("model."+id);
                if(expected!=null&&!expected.equals(model.toString()))throw new IOException("电极 "+id+" 输入快照与原批次参数记录不一致");
                cards.add(new Card(id,name,file,model));
            }
            catch(IllegalArgumentException ex){throw new IOException("批次输入快照无效："+file+"；"+ex.getMessage(),ex);}
        }
        return new ModelInputs(Path.of(manifest.getProperty("source",directory.toString())),directory,cards);
    }
    public static ModelInputs load(Path source, Path cacheRoot, BiConsumer<Integer,String> progress) throws IOException {
        return load(source,cacheRoot,progress,source);
    }
    static ModelInputs load(Path source,Path cacheRoot,BiConsumer<Integer,String> progress,Path logicalSource)throws IOException {
        var indexed = indexFiles(source);
        Files.createDirectories(cacheRoot);
        Path snapshot = Files.createTempDirectory(cacheRoot,"inputs-");
        var cards = new ArrayList<Card>();
        var errors = new ArrayList<String>();
        var manifest = new Properties();
        manifest.setProperty("source", logicalSource.toAbsolutePath().toString());
        for (var entry : indexed.entrySet()) {
            Path file = entry.getValue();
            byte[] bytes = Files.readAllBytes(file);
            try {
                Model model = parse(new String(bytes, StandardCharsets.UTF_8));
                Path copy = snapshot.resolve(file.getFileName());
                Files.write(copy, bytes, StandardOpenOption.CREATE_NEW);
                cards.add(new Card(entry.getKey(), file.getFileName().toString(), copy, model));
                manifest.setProperty("card."+entry.getKey(), file.getFileName().toString());
                manifest.setProperty("model."+entry.getKey(), model.toString());
            } catch (IllegalArgumentException ex) { errors.add(file.getFileName()+"："+ex.getMessage()); }
            progress.accept(cards.size(), "已加载 " + cards.size() + "/192 · " + file.getFileName());
        }
        if (!errors.isEmpty()) throw new IOException(String.join("\n",errors));
        Path conversion=source.resolve("conversion.properties");
        if(Files.isRegularFile(conversion)) {
            Properties provenance=new Properties();
            try(var input=Files.newInputStream(conversion)){provenance.load(input);}
            provenance.forEach((key,value)->manifest.setProperty("conversion."+key,value.toString()));
        }
        try (var out=Files.newOutputStream(snapshot.resolve("inputs.properties"))) { manifest.store(out,"Input snapshot"); }
        ModelInputs inputs=new ModelInputs(logicalSource,snapshot,cards);
        if(Files.isRegularFile(conversion))InputPreparation.attachProvenance(inputs,source.resolve("conversion-source"),manifest);
        return inputs;
    }
    void copyProvenanceTo(Path destination)throws IOException {
        Path samplingOriginal=directory.resolve("sampling-original");
        if(Files.isDirectory(samplingOriginal))restoreSnapshot(samplingOriginal).copySnapshotTo(Files.createDirectories(destination.resolve("sampling-original")));
        Path provenance=directory.resolve("conversion-source");
        if(!Files.isDirectory(provenance))return;
        Path target=Files.createDirectories(destination.resolve("conversion-source"));
        try(var files=Files.list(provenance)) {
            for(Path file:files.filter(Files::isRegularFile).toList())Files.copy(file,target.resolve(file.getFileName()));
        }
    }
    void copySnapshotTo(Path destination)throws IOException {
        Files.copy(directory.resolve("inputs.properties"),destination.resolve("inputs.properties"));
        for(var card:cards)Files.copy(card.snapshot(),destination.resolve(card.name()));
        copyProvenanceTo(destination);
    }
    static NavigableMap<Integer,Path> indexFiles(Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("输入目录不可用：" + source);
        var indexed = new TreeMap<Integer,Path>();
        var errors = new ArrayList<String>();
        Pattern pattern = Pattern.compile("(?i)^Card_(\\d{3})(?:_|\\.).*");
        try (var stream = Files.list(source)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(path->path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")).toList()) {
                Matcher m = pattern.matcher(file.getFileName().toString());
                if (!m.matches()) { errors.add("无法识别通道文件：" + file.getFileName()); continue; }
                int id = Integer.parseInt(m.group(1));
                if (id < 1 || id > CHANNELS) errors.add("编号越界：" + file.getFileName());
                else if (indexed.containsKey(id)) errors.add("重复编号：" + indexed.get(id).getFileName() + "、" + file.getFileName());
                else indexed.put(id, file);
            }
        }
        for (int id=1; id<=CHANNELS; id++) if (!indexed.containsKey(id)) errors.add("缺少 Card_" + String.format("%03d",id));
        if (!errors.isEmpty()) throw new IOException(String.join("\n", errors));
        return indexed;
    }
    public static Model parse(String text) {
        List<String> lines=text.replace("\uFEFF","").lines().map(s -> s.split("[!#]",2)[0].trim()).filter(s->!s.isEmpty()).toList();
        int[] cursor={0};
        double[] a=row(lines,cursor,2), b=row(lines,cursor,3), c=row(lines,cursor,3);
        positive(a[0],"频率"); positive(a[1],"井径");
        if(b[0]<0) throw new IllegalArgumentException("油膜厚度不能为负");
        positive(b[1],"泥浆电阻率"); positive(b[2],"泥浆介电常数");
        positive(c[1],"采样间隔");
        if(c[2]<c[0]) throw new IllegalArgumentException("终止深度小于起始深度");
        int nl=count(row(lines,cursor,1)[0],false);
        var layers=new ArrayList<List<Double>>();
        double previous=Double.NEGATIVE_INFINITY;
        for(int i=0;i<nl;i++) {
            double[] r=row(lines,cursor,3);
            if(r[0]<=previous) throw new IllegalArgumentException("层界面深度必须严格递增");
            previous=r[0]; positive(r[1],"层电阻率"); positive(r[2],"层介电常数");
            layers.add(Arrays.stream(r).boxed().toList());
        }
        int nf=count(row(lines,cursor,1)[0],true);
        var fractures=new ArrayList<List<Double>>();
        for(int i=0;i<nf;i++) {
            double[] r=row(lines,cursor,5);
            if(r[1]<-90 || r[1]>90) throw new IllegalArgumentException("裂缝倾角应在 -90～90° 内");
            positive(r[2],"裂缝电阻率"); positive(r[3],"裂缝介电常数"); positive(r[4],"裂缝张开度");
            fractures.add(Arrays.stream(r).boxed().toList());
        }
        if(cursor[0]!=lines.size()) throw new IllegalArgumentException("记录数量与 nl/nf 不一致，存在多余行");
        return new Model(a[0],a[1],b[0],b[1],b[2],c[0],c[1],c[2],List.copyOf(layers),List.copyOf(fractures));
    }
    private static int count(double n, boolean zero) {
        if(n!=Math.rint(n)||n<(zero?0:1)||n>100000) throw new IllegalArgumentException("层数/裂缝数不是合法整数");
        return (int)n;
    }
    private static void positive(double n,String name) { if(n<=0) throw new IllegalArgumentException(name+"必须大于零"); }
    private static double[] row(List<String> lines,int[] cursor,int columns) {
        if(cursor[0]>=lines.size()) throw new IllegalArgumentException("记录不足");
        return ResponseData.numbers(lines.get(cursor[0]++),columns,"第 "+cursor[0]+" 行");
    }
    private String values(String label, java.util.function.Function<Model,String> get) {
        Map<String,List<String>> groups=new LinkedHashMap<>();
        for(Card card:cards) groups.computeIfAbsent(get.apply(card.model()),k->new ArrayList<>()).add(card.name());
        if(groups.size()==1) return label+"："+groups.keySet().iterator().next()+"\n";
        StringBuilder out=new StringBuilder(label+"：多值/不一致\n");
        groups.forEach((v,names)->out.append(v).append(" ← ").append(String.join(", ",names)).append('\n'));
        return out.toString();
    }
    public String summary() {
        return "目录："+source+"\n192 通道模型输入（尚未计算响应）\n编号：1～192\n"
            + values("频率 Hz",m->""+m.frequency())
            + values("井径 in / 半径 m",m->m.diameterInches()+" / "+m.diameterInches()*0.0254/2)
            + values("油膜厚度 m",m->""+m.offset())
            + values("泥浆电阻率 Ω·m / 相对介电常数",m->m.mudRho()+" / "+m.mudEps())
            + values("局部 MD m",m->m.begin()+"～"+m.end())
            + values("采样间隔 m",m->""+m.step())
            + values("层数 / 裂缝数",m->m.layers().size()+" / "+m.fractures().size())
            + "层记录："+(cards.stream().map(c->c.model().layers()).distinct().count()>1?"多值/不一致（见详情）":"一致")
            + "\n裂缝记录："+(cards.stream().map(c->c.model().fractures()).distinct().count()>1?"多值/不一致（见详情）":"一致");
    }
    public String details() {return values("层记录 [MD, 电阻率, 介电常数]",m->m.layers().toString())
        +values("裂缝记录 [MD, 倾角, 电阻率, 介电常数, 张开度]",m->m.fractures().toString());}
}
