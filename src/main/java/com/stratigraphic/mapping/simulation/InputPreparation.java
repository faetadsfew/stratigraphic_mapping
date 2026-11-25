package com.stratigraphic.mapping.simulation;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.*;

public final class InputPreparation {
    private InputPreparation() { }
    public record Settings(double frequency,double diameter,double offset,double mudRho,double mudEps,double layerEps,
                           Path source,Path destination,Path script,Path checker) { }
    public record Prepared(ModelInputs inputs,int updated,Path backup) { }
    private record CalculationSampling(double begin,double stepMm,double end,SamplingPlan.InputParameters parameters) { }
    private record Converted(int electrode,String name,String text,ModelInputs.Model slice,ModelInputs.Model model,byte[] sourceBytes) { }

    public static Settings settings(Path root)throws IOException {
        Path script=root.resolve("call.m"),checker=root.resolve("transf.m");
        if(!Files.isRegularFile(script)||!Files.isRegularFile(checker))throw new IOException("转换参考文件缺失："+script+" 或 "+checker);
        String text=Files.readString(script);
        String active=text.lines().filter(line->!line.stripLeading().startsWith("%")).reduce("",(left,right)->left+right+"\n");
        String compact=active.replaceAll("\\s+","");
        if(!compact.contains("layer_data(jj+1,1),layer_data(jj,2),epsr_all")||!compact.contains("layer_data(nl,1)+1,layer_data(nl,2),epsr_all")
            ||!compact.contains("fra_data(jj,1),0,fra_data(jj,3),epsr_all,fra_data(jj,5)"))
            throw new IOException("call.m 的分层或裂缝转换规则不受当前版本支持，请核对脚本后更新转换实现，未改写输入文件。");
        String header=active.split("script_dir\\s*=",2)[0];
        if(scalar(assignment(header,"n_card"),"n_card")!=192)throw new IOException("call.m 的 n_card 必须为 192");
        String frequency=assignment(header,"freq");
        Matcher selection=Pattern.compile("([A-Za-z]\\w*)\\(\\s*(\\d+)\\s*\\)").matcher(frequency);
        if(selection.matches()) {
            String array=assignment(header,selection.group(1));
            if(!array.startsWith("[")||!array.endsWith("]"))throw new IOException("call.m 频率数组格式无效");
            String[] values=array.substring(1,array.length()-1).trim().split("[\\s,;]+");
            int index=Integer.parseInt(selection.group(2))-1;
            if(index<0||index>=values.length)throw new IOException("call.m 频率索引越界");
            frequency=values[index];
        }
        Path source=folder(root,assignment(header,"input_folder")),destination=folder(root,assignment(header,"output_folder"));
        if(source.equals(destination))throw new IOException("原始切片目录与转换目录不能相同");
        return new Settings(scalar(frequency,"freq"),scalar(assignment(header,"cali"),"cali"),scalar(assignment(header,"sd_off"),"sd_off"),
            scalar(assignment(header,"rm"),"rm"),scalar(assignment(header,"epsr_m"),"epsr_m"),scalar(assignment(header,"epsr_all"),"epsr_all"),source,destination,script,checker);
    }
    private static String assignment(String header,String name)throws IOException {
        Matcher matcher=Pattern.compile("(?m)^\\s*"+Pattern.quote(name)+"\\s*=\\s*([^;\\r\\n]+)\\s*;").matcher(header);
        if(!matcher.find())throw new IOException("call.m 缺少或无法解析参数："+name+"（支持数值、频率数组索引及相对目录）");
        return matcher.group(1).trim();
    }
    private static double scalar(String text,String name)throws IOException {
        try {
            if(!text.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eEdD][+-]?\\d+)?"))throw new NumberFormatException();
            double value=Double.parseDouble(text.replace('D','E').replace('d','e'));
            if(!Double.isFinite(value))throw new NumberFormatException();
            return value;
        }catch(NumberFormatException error){throw new IOException("call.m 参数不是支持的有限数值："+name+"="+text,error);}
    }
    private static Path folder(Path root,String value)throws IOException {
        if(!value.matches("'[^']+'"))throw new IOException("call.m 目录必须为单引号包围的相对路径："+value);
        Path base=root.toAbsolutePath().normalize(),relative=Path.of(value.substring(1,value.length()-1));
        Path resolved=base.resolve(relative).normalize();
        if(relative.isAbsolute()||!resolved.startsWith(base)||resolved.equals(base))throw new IOException("call.m 目录必须位于工作根目录内："+value);
        return resolved;
    }
    static String convert(ModelInputs.Model slice,Settings settings) {
        return convertMaterials(slice,settings,null);
    }
    static String convert(ModelInputs.Model slice,Settings settings,SliceMaterials.Data materials,int electrode)throws IOException {
        return convertMaterials(slice,settings,materials==null?null:materials.fractures(electrode,slice.fractures().size()));
    }
    private static String convertMaterials(ModelInputs.Model slice,Settings settings,List<Double> fracturePermittivities) {
        StringBuilder text=new StringBuilder();
        row(text,settings.frequency(),settings.diameter());row(text,settings.offset(),settings.mudRho(),settings.mudEps());
        row(text,slice.begin(),slice.step(),slice.end());text.append(slice.layers().size()).append('\n');
        for(int index=0;index<slice.layers().size();index++) {
            var layer=slice.layers().get(index);
            double boundary=index+1<slice.layers().size()?slice.layers().get(index+1).get(0):layer.get(0)+1;
            row(text,boundary,layer.get(1),fracturePermittivities==null?settings.layerEps():layer.get(2));
        }
        text.append(slice.fractures().size()).append('\n');
        for(int index=0;index<slice.fractures().size();index++) {
            var fracture=slice.fractures().get(index);
            row(text,fracture.get(0),0,fracture.get(2),fracturePermittivities==null?settings.layerEps():fracturePermittivities.get(index),fracture.get(4));
        }
        return text.toString();
    }
    private static void row(StringBuilder text,double... values) {
        for(int index=0;index<values.length;index++) {
            if(index>0)text.append("    ");
            text.append(String.format(Locale.ROOT,"%.10g",values[index]));
        }
        text.append('\n');
    }
    static void validate(ModelInputs.Model slice,ModelInputs.Model actual,Settings settings,String name)throws IOException {
        validate(slice,actual,settings,name,null,0);
    }
    static void validate(ModelInputs.Model slice,ModelInputs.Model actual,Settings settings,String name,SliceMaterials.Data materials,int electrode)throws IOException {
        ModelInputs.Model expected=ModelInputs.parse(convert(slice,settings,materials,electrode));
        if(!expected.equals(actual)) {
            String reason=!expected.layers().equals(actual.layers())?"层界面/电阻率对应关系不一致；应使用下一层起点作为当前层界面，末层界面为末层起点+1 m":
                !expected.fractures().equals(actual.fractures())?"裂缝位置、电阻率、张开度、0° 倾角或介电常数与 call.m 不一致":"频率、井径、泥饼参数或深度采样参数与 call.m/原始切片不一致";
            throw new IOException(name+"："+reason+"。请修改原始切片或 call.m 参数后重新获得输入数据。");
        }
        int points=pointCount(slice);
        for(int index=0;index<points;index++) {
            double depth=slice.begin()+index*slice.step();
            double original=resistivity(slice,depth,false),converted=resistivity(actual,depth,true);
            if(Math.abs(original-converted)>Math.max(1e-10,Math.abs(original)*1e-8))
                throw new IOException(name+"：MD="+depth+" m 的模型不一致，原始电阻率="+original+"，转换后="+converted+"；请检查层界面和裂缝参数。");
        }
    }
    static double resistivity(ModelInputs.Model model,double depth,boolean fortran) {
        double value=model.layers().get(0).get(1);
        for(int index=1;index<model.layers().size();index++) {
            double boundary=model.layers().get(fortran?index-1:index).get(0);
            if(depth>=boundary)value=model.layers().get(index).get(1);
        }
        for(var fracture:model.fractures())if(depth>=fracture.get(0)-fracture.get(4)/2&&depth<=fracture.get(0)+fracture.get(4)/2)value=fracture.get(2);
        return value;
    }
    private static int pointCount(ModelInputs.Model model)throws IOException {
        double points=Math.floor((model.end()-model.begin())/model.step())+1;
        if(!Double.isFinite(points)||points>2_000_000)throw new IOException("深度采样点数超过 200 万，请检查采样间隔与深度范围");
        return (int)points;
    }
    private static List<Converted> prepare(Settings settings,SliceMaterials.Data materials,BiConsumer<Integer,String> progress)throws IOException {
        var indexed=ModelInputs.indexFiles(settings.source());var cards=new ArrayList<Converted>();var errors=new ArrayList<String>();
        ModelInputs.Model reference=null;
        for(var entry:indexed.entrySet()) {
            String name=entry.getValue().getFileName().toString();
            try {
                byte[] sourceBytes=Files.readAllBytes(entry.getValue());
                ModelInputs.Model slice=ModelInputs.parse("1 1\n0 1 1\n"+new String(sourceBytes,java.nio.charset.StandardCharsets.UTF_8));
                if(reference!=null&&(Double.compare(slice.begin(),reference.begin())!=0||Double.compare(slice.step(),reference.step())!=0||Double.compare(slice.end(),reference.end())!=0))
                    throw new IOException("深度范围/采样间隔与 "+cards.get(0).name()+" 不一致");
                String text=convert(slice,settings,materials,entry.getKey());ModelInputs.Model model=ModelInputs.parse(text);
                validate(slice,model,settings,name,materials,entry.getKey());
                cards.add(new Converted(entry.getKey(),name,text,slice,model,sourceBytes));reference=slice;
            }catch(IllegalArgumentException|IOException error){errors.add(name+"："+error.getMessage());}
            progress.accept(entry.getKey(),"已检查原始切片 "+entry.getKey()+"/192 · "+name);
        }
        if(!errors.isEmpty())throw new IOException(String.join("\n",errors));
        return List.copyOf(cards);
    }
    public static Prepared regenerate(Path root,Path cacheRoot,BiConsumer<Integer,String> progress)throws IOException {
        return regenerate(root,cacheRoot,progress,null);
    }
    public static Prepared prepareForCalculation(Path root,Path cacheRoot,double begin,double stepMm,double end,
                                                SamplingPlan.InputParameters parameters,BiConsumer<Integer,String> progress)throws IOException {
        return regenerate(root,cacheRoot,progress,new CalculationSampling(begin,stepMm,end,Objects.requireNonNull(parameters)));
    }
    private static Prepared regenerate(Path root,Path cacheRoot,BiConsumer<Integer,String> progress,CalculationSampling sampling)throws IOException {
        Settings settings=settings(root);
        Files.createDirectories(cacheRoot);
        Path transaction=Files.createTempDirectory(cacheRoot,"conversion-");
        try(var channel=FileChannel.open(root.resolve("input-preparation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock;
            try {lock=channel.tryLock();}catch(OverlappingFileLockException error){throw new IOException("已有输入转换任务正在运行",error);}
            if(lock==null)throw new IOException("已有输入转换任务正在运行");
            try(lock) {
                String scriptHash=SimulationHistory.sha256(settings.script()),checkerHash=SimulationHistory.sha256(settings.checker());
                SliceMaterials.Data materials=SliceMaterials.read(settings.source());
                Path modelRecord=root.resolve("NGI_PLOT_INP.txt");
                if(materials==null&&Files.isRegularFile(modelRecord)&&Files.readString(modelRecord).contains("% slice_materials_version=1"))
                    throw new IOException("当前模型的切片材料记录缺失，请在第一页重新生成切片");
                List<Converted> cards=prepare(settings,materials,progress);
                Path staged=Files.createDirectory(transaction.resolve("converted"));
                for(var card:cards)Files.writeString(staged.resolve(card.name()),card.text(),StandardOpenOption.CREATE_NEW);
                var manifest=new Properties();
                manifest.setProperty("rule",materials==null?"next-layer-boundary-v1":SliceMaterials.RULE);manifest.setProperty("source",settings.source().toString());
                if(materials!=null) {
                    manifest.setProperty("materials.sha256",SimulationHistory.sha256(settings.source().resolve(SliceMaterials.FILE)));
                    manifest.setProperty("generation",materials.generation());
                }
                manifest.setProperty("call.sha256",scriptHash);manifest.setProperty("transf.sha256",checkerHash);
                manifest.setProperty("call.path",settings.script().toAbsolutePath().toString());manifest.setProperty("transf.path",settings.checker().toAbsolutePath().toString());
                manifest.setProperty("validated.records",Long.toString((long)pointCount(cards.get(0).slice())*192));
                manifest.setProperty("validation","192 electrodes: parameters and reconstructed resistivity profiles verified");
                Path originals=Files.createDirectory(staged.resolve("conversion-source"));
                Files.copy(settings.script(),originals.resolve("call.m"));Files.copy(settings.checker(),originals.resolve("transf.m"));
                if(materials!=null)Files.copy(settings.source().resolve(SliceMaterials.FILE),originals.resolve(SliceMaterials.FILE));
                for(var card:cards) {
                    Files.write(originals.resolve(card.name()),card.sourceBytes());
                    manifest.setProperty("slice.sha256."+card.electrode(),SimulationHistory.sha256(originals.resolve(card.name())));
                    manifest.setProperty("converted.sha256."+card.electrode(),SimulationHistory.sha256(staged.resolve(card.name())));
                }
                int updated=0;
                for(var card:cards)if(!Files.isRegularFile(settings.destination().resolve(card.name()))||Files.mismatch(staged.resolve(card.name()),settings.destination().resolve(card.name()))!=-1)updated++;
                List<Path> existing;
                if(Files.isDirectory(settings.destination()))try(var stream=Files.list(settings.destination())){existing=stream.filter(Files::isRegularFile).filter(file->file.getFileName().toString().matches("(?i)Card_.*\\.txt")).toList();}
                else existing=List.of();
                Set<String> names=new HashSet<>();cards.forEach(card->names.add(card.name()));
                boolean stale=existing.stream().anyMatch(file->!names.contains(file.getFileName().toString()));
                Path backup=null;
                if(sampling==null&&(updated>0||stale)) {
                    Path backups=Files.createDirectories(root.resolve("input_backups"));
                    backup=Files.createDirectory(backups.resolve("NGI_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+"_"+UUID.randomUUID().toString().substring(0,8)));
                    for(Path file:existing)Files.copy(file,backup.resolve(file.getFileName()));
                    Files.copy(settings.script(),backup.resolve("call.m"));Files.copy(settings.checker(),backup.resolve("transf.m"));
                    manifest.setProperty("backup",backup.toString());
                }
                try(var output=Files.newOutputStream(staged.resolve("conversion.properties"))){manifest.store(output,"Validated Java conversion from call.m and transf.m");}
                ModelInputs verified=ModelInputs.load(staged,transaction.resolve("verification"),(done,message)->{});
                for(int index=0;index<cards.size();index++)validate(cards.get(index).slice(),verified.cards().get(index).model(),settings,cards.get(index).name(),materials,cards.get(index).electrode());
                ModelInputs snapshot=ModelInputs.load(staged,cacheRoot,(done,message)->progress.accept(192+done,"已加载并校验 "+done+"/192"),settings.destination());
                if(!scriptHash.equals(SimulationHistory.sha256(settings.script()))||!checkerHash.equals(SimulationHistory.sha256(settings.checker())))
                    throw new IOException("转换期间参考脚本发生变化，请重新获得输入数据；未替换原有输入。");
                verifySnapshot(snapshot);
                if(sampling!=null) {
                    ModelInputs sampled=SamplingPlan.prepare(snapshot,cacheRoot,sampling.begin(),sampling.stepMm(),sampling.end(),sampling.parameters());
                    return CalculationInputPublication.publish(root,settings.destination(),sampled);
                }
                publish(settings.destination(),staged,existing,cards,backup);
                return new Prepared(snapshot,updated,backup);
            }
        }finally {
            if(!transaction.toAbsolutePath().normalize().startsWith(cacheRoot.toAbsolutePath().normalize())||transaction.toAbsolutePath().normalize().equals(cacheRoot.toAbsolutePath().normalize()))
                throw new IOException("转换临时目录不在指定缓存目录内，停止清理："+transaction);
            try(var paths=Files.walk(transaction)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    static void attachProvenance(ModelInputs inputs,Path saved,Properties manifest)throws IOException {
        Path target=Files.createDirectory(inputs.directory().resolve("conversion-source"));
        Path source=Files.isDirectory(saved)?saved:Path.of(manifest.getProperty("conversion.source"));
        for(var card:inputs.cards())Files.copy(source.resolve(card.name()),target.resolve(card.name()));
        if(manifest.containsKey("conversion.materials.sha256"))Files.copy(source.resolve(SliceMaterials.FILE),target.resolve(SliceMaterials.FILE));
        for(String name:List.of("call.m","transf.m")) {
            Path script=Files.isDirectory(saved)?saved.resolve(name):Path.of(manifest.getProperty("conversion."+name.replace(".m","")+".path",source.getParent().resolve(name).toString()));
            Files.copy(script,target.resolve(name));
        }
        verifySnapshot(inputs);
    }
    static void verifySnapshot(ModelInputs inputs)throws IOException {
        Properties manifest=new Properties();
        try(var input=Files.newInputStream(inputs.directory().resolve("inputs.properties"))){manifest.load(input);}
        if(manifest.containsKey("sampling.version")){SamplingPlan.verifySnapshot(inputs);return;}
        if(manifest.getProperty("conversion.rule")==null)return;
        Path provenance=inputs.directory().resolve("conversion-source");Settings settings=settings(provenance);
        String rule=manifest.getProperty("conversion.rule");
        if(!"next-layer-boundary-v1".equals(rule)&&!SliceMaterials.RULE.equals(rule))throw new IOException("不支持的转换快照版本，请重新获得输入数据");
        SliceMaterials.Data materials=null;
        if(SliceMaterials.RULE.equals(rule)) {
            Path materialFile=provenance.resolve(SliceMaterials.FILE);
            if(!Files.isRegularFile(materialFile)||!SimulationHistory.sha256(materialFile).equals(manifest.getProperty("conversion.materials.sha256")))
                throw new IOException("输入快照的材料记录缺失或已被修改，请重新获得输入数据");
            materials=SliceMaterials.read(provenance);
            if(!materials.generation().equals(manifest.getProperty("conversion.generation")))throw new IOException("输入快照的切片版本不一致");
        }
        if(!SimulationHistory.sha256(settings.script()).equals(manifest.getProperty("conversion.call.sha256"))
            ||!SimulationHistory.sha256(settings.checker()).equals(manifest.getProperty("conversion.transf.sha256")))throw new IOException("输入快照中的转换脚本校验失败，请重新获得输入数据");
        for(var card:inputs.cards()) {
            Path source=provenance.resolve(card.name());
            if(!SimulationHistory.sha256(source).equals(manifest.getProperty("conversion.slice.sha256."+card.id()))
                ||!SimulationHistory.sha256(card.snapshot()).equals(manifest.getProperty("conversion.converted.sha256."+card.id())))
                throw new IOException(card.name()+"：输入快照或原始切片被修改，请重新获得输入数据");
            ModelInputs.Model slice=ModelInputs.parse("1 1\n0 1 1\n"+Files.readString(source));
            validate(slice,ModelInputs.parse(Files.readString(card.snapshot())),settings,card.name(),materials,card.id());
        }
    }
    public static void verifyCurrentInputs(ModelInputs inputs,Path root,String expectedGeneration)throws IOException {
        verifySnapshot(inputs);
        ModelInputs original=inputs;
        Path sampled=inputs.directory().resolve("sampling-original");
        if(Files.isDirectory(sampled))original=ModelInputs.restoreSnapshot(sampled);
        Properties manifest=new Properties();
        try(var input=Files.newInputStream(original.directory().resolve("inputs.properties"))){manifest.load(input);}
        try(var channel=FileChannel.open(root.resolve("input-preparation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock;
            try {lock=channel.tryLock();}catch(OverlappingFileLockException error){throw new IOException("切片或输入正在更新，请完成后重新获得输入数据",error);}
            if(lock==null)throw new IOException("切片或输入正在更新，请完成后重新获得输入数据");
            try(lock) {
                if(manifest.getProperty("conversion.rule")==null) {
                    if(expectedGeneration!=null)throw new IOException("当前输入不是本次第一页生成的切片，请重新获得输入数据");
                    Path source=Path.of(manifest.getProperty("source"));
                    for(var card:original.cards()) {
                        Path current=source.resolve(card.name());
                        if(!Files.isRegularFile(current)||Files.mismatch(current,card.snapshot())!=-1)
                            throw new IOException(card.name()+" 已改变，请重新获得输入数据后再正演计算");
                    }
                    return;
                }
                Settings current=settings(root);
                if(!SimulationHistory.sha256(current.script()).equals(manifest.getProperty("conversion.call.sha256"))
                        ||!SimulationHistory.sha256(current.checker()).equals(manifest.getProperty("conversion.transf.sha256")))
                    throw new IOException("输入转换参数已改变，请重新获得输入数据");
                if(expectedGeneration!=null&&!expectedGeneration.equals(manifest.getProperty("conversion.generation")))
                    throw new IOException("输入不属于本次第一页生成的模型，请重新获得输入数据");
                Path materialFile=current.source().resolve(SliceMaterials.FILE);
                String materialHash=manifest.getProperty("conversion.materials.sha256");
                if((materialHash==null&&Files.exists(materialFile))||(materialHash!=null&&(!Files.isRegularFile(materialFile)
                        ||!materialHash.equals(SimulationHistory.sha256(materialFile)))))
                    throw new IOException("切片材料版本已改变，请重新获得输入数据");
                var indexed=ModelInputs.indexFiles(current.source());
                for(var card:original.cards()) {
                    Path file=indexed.get(card.id());
                    if(!file.getFileName().toString().equals(card.name())||!SimulationHistory.sha256(file).equals(manifest.getProperty("conversion.slice.sha256."+card.id())))
                        throw new IOException(card.name()+" 的原始切片已改变，请重新获得输入数据后再正演计算");
                }
            }
        }
    }
    private static void publish(Path destination,Path staged,List<Path> existing,List<Converted> cards,Path backup)throws IOException {
        Files.createDirectories(destination);
        Path oldManifest=staged.resolve("previous-conversion.properties");
        if(Files.exists(destination.resolve("conversion.properties")))Files.copy(destination.resolve("conversion.properties"),oldManifest);
        var touched=new ArrayList<Path>();
        try {
            for(var card:cards) {
                Path target=destination.resolve(card.name());
                if(Files.isRegularFile(target)&&Files.mismatch(staged.resolve(card.name()),target)==-1)continue;
                touched.add(target);replace(staged.resolve(card.name()),target);
            }
            Set<String> names=new HashSet<>();cards.forEach(card->names.add(card.name()));
            for(Path file:existing)if(!names.contains(file.getFileName().toString())){touched.add(file);Files.delete(file);}
            replace(staged.resolve("conversion.properties"),destination.resolve("conversion.properties"));
        }catch(IOException error) {
            for(Path target:touched)try {
                Path original=backup==null?null:backup.resolve(target.getFileName());
                if(original!=null&&Files.isRegularFile(original))Files.copy(original,target,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(target);
            }catch(IOException restore){error.addSuppressed(restore);}
            try {if(Files.exists(oldManifest))Files.copy(oldManifest,destination.resolve("conversion.properties"),StandardCopyOption.REPLACE_EXISTING);}catch(IOException restore){error.addSuppressed(restore);}
            throw new IOException("发布转换输入失败，已尝试恢复原文件；备份："+backup+"；"+error.getMessage(),error);
        }
    }
    private static void replace(Path source,Path destination)throws IOException {
        Path temporary=destination.resolveSibling(destination.getFileName()+".conversion-"+UUID.randomUUID()+".tmp");
        try {
            Files.copy(source,temporary);
            try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException error){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
        }finally {Files.deleteIfExists(temporary);}
    }
    public static void main(String[] arguments)throws IOException {
        if(arguments.length!=2)throw new IllegalArgumentException("参数：工作根目录 缓存目录");
        Prepared result=regenerate(Path.of(arguments[0]),Path.of(arguments[1]),(done,message)->{if(done%24==0)System.out.println(message);});
        System.out.println("Updated inputs: "+result.updated()+"; backup: "+result.backup()+"; snapshot: "+result.inputs().directory());
    }
}
