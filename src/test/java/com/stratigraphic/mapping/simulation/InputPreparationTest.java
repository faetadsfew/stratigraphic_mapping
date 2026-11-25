package com.stratigraphic.mapping.simulation;

import com.stratigraphic.mapping.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputPreparationTest {
    @TempDir Path temp;
    private static final String SCRIPT="""
        freq_all = [2.5e6,40e6];
        freq = freq_all(1);
        cali = 8;
        sd_off = 0.005;
        rm = 1000;
        epsr_m = 6;
        epsr_all = 10;
        n_card = 192;
        input_folder = '192_Slices_Output';
        output_folder = '192_NGI_Input';
        script_dir = fileparts(mfilename('fullpath'));
        for jj = 1:nl-1
          fprintf(fid,'%.10g %.10g %.10g\\n',layer_data(jj+1,1),layer_data(jj,2),epsr_all);
        end
        fprintf(fid,'%.10g %.10g %.10g\\n',layer_data(nl,1)+1,layer_data(nl,2),epsr_all);
        fprintf(fid,'%.10g %.10g %.10g %.10g %.10g\\n',fra_data(jj,1),0,fra_data(jj,3),epsr_all,fra_data(jj,5));
        """;
    private static final String SLICE="0 0.001 1.5\n4\n0 100 1\n0.2 30 1\n0.4 50 1\n1.5 90 1\n";
    private Path setup()throws IOException {
        Files.writeString(temp.resolve("call.m"),SCRIPT);Files.writeString(temp.resolve("transf.m"),"% Fortran reconstruction reference\n");
        Path slices=Files.createDirectory(temp.resolve("192_Slices_Output"));Path inputs=Files.createDirectory(temp.resolve("192_NGI_Input"));
        for(int electrode=192;electrode>=1;electrode--) {
            String name=String.format("Card_%03d_ang_model.txt",electrode);
            String raw=SLICE+(electrode%2==0?"0\n":"1\n0.6 45 0.5 180 0.002\n");
            Files.writeString(slices.resolve(name),raw);Files.writeString(inputs.resolve(name),"2500000 8\n0.005 1000 6\n"+raw);
        }
        return inputs;
    }
    @Test void finalCalculationDirectoryMatchesEverySampledInputAndPreservesSource()throws Exception {
        Path destination=setup();
        String previous=Files.readString(destination.resolve("Card_001_ang_model.txt"));
        Files.writeString(destination.resolve("notes.csv"),"keep this file");
        Files.writeString(destination.resolve("Card_001_obsolete.txt"),"stale electrode");
        var sourceHashes=new LinkedHashMap<Path,String>();
        try(var paths=Files.list(temp.resolve("192_Slices_Output"))) {
            for(Path path:paths.toList())sourceHashes.put(path,SimulationHistory.sha256(path));
        }
        var parameters=new SamplingPlan.InputParameters(40_000_000,9.5,0.007,800,7.5);
        var prepared=InputPreparation.prepareForCalculation(temp,temp.resolve("cache"),0,2.54,1.5,parameters,(done,message)->{});
        assertEquals(192,prepared.updated());assertNotNull(prepared.backup());
        assertEquals(previous,Files.readString(prepared.backup().resolve("Card_001_ang_model.txt")));
        assertTrue(Files.isRegularFile(prepared.backup().resolve("Card_001_obsolete.txt")));
        assertFalse(Files.exists(destination.resolve("Card_001_obsolete.txt")));
        assertEquals("keep this file",Files.readString(destination.resolve("notes.csv")));
        assertFalse(Files.exists(destination.resolve("conversion.properties")));
        ModelInputs published=ModelInputs.restoreSnapshot(destination);
        ModelInputs original=ModelInputs.restoreSnapshot(destination.resolve("sampling-original"));
        assertEquals(192,ModelInputs.indexFiles(destination).size());
        for(int index=0;index<192;index++) {
            var card=prepared.inputs().cards().get(index);
            assertEquals(-1,Files.mismatch(card.snapshot(),destination.resolve(card.name())));
            assertEquals(card.model(),published.cards().get(index).model());
            assertEquals(40_000_000,card.model().frequency());assertEquals(9.5,card.model().diameterInches());
            assertEquals(0.007,card.model().offset());assertEquals(800,card.model().mudRho());assertEquals(7.5,card.model().mudEps());
            assertEquals(Math.nextDown(1.5/591),card.model().step());
            assertEquals(original.cards().get(index).model().layers(),card.model().layers());
            assertEquals(original.cards().get(index).model().fractures(),card.model().fractures());
            assertEquals(0.001,original.cards().get(index).model().step());
        }
        assertEquals(0.00254,SamplingPlan.read(destination).requestedStep());
        assertEquals(592,SamplingPlan.read(destination).computedPoints());
        for(var entry:sourceHashes.entrySet())assertEquals(entry.getValue(),SimulationHistory.sha256(entry.getKey()));
        InputPreparation.verifySnapshot(published);
        InputPreparation.verifyCurrentInputs(prepared.inputs(),temp,null);
        Path archived=Files.createDirectory(temp.resolve("batch-inputs"));published.copySnapshotTo(archived);
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(archived));
    }
    @Test void anotherCalculationReplacesFinalParametersButKeepsPreviousSnapshotAndBackup()throws Exception {
        Path destination=setup();Path cache=temp.resolve("cache");
        var firstParameters=new SamplingPlan.InputParameters(2_500_000,8,0.005,1000,6);
        var first=InputPreparation.prepareForCalculation(temp,cache,0,2.54,1.5,firstParameters,(done,message)->{});
        var nextParameters=new SamplingPlan.InputParameters(40_000_000,10,0.006,900,8);
        var next=InputPreparation.prepareForCalculation(temp,cache,0.2,1,0.9,nextParameters,(done,message)->{});
        assertNotEquals(first.inputs().directory(),next.inputs().directory());
        assertEquals(0.00254,SamplingPlan.read(first.inputs().directory()).requestedStep());
        assertEquals(0.00254,SamplingPlan.read(next.backup()).requestedStep());
        assertEquals(0.001,SamplingPlan.read(destination).requestedStep());
        for(var card:next.inputs().cards()) {
            assertEquals(0.2,card.model().begin());assertEquals(0.9,card.model().end());assertEquals(0.001,card.model().step());
            assertEquals(40_000_000,card.model().frequency());assertEquals(10,card.model().diameterInches());
            assertEquals(-1,Files.mismatch(card.snapshot(),destination.resolve(card.name())));
            assertEquals(-1,Files.mismatch(first.inputs().cards().get(card.id()-1).snapshot(),next.backup().resolve(card.name())));
        }
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(next.backup()));
        InputPreparation.verifyCurrentInputs(first.inputs(),temp,null);
        InputPreparation.verifyCurrentInputs(next.inputs(),temp,null);
    }
    @Test void invalidSamplingLeavesTheEntirePreviousCalculationDirectoryUntouched()throws Exception {
        Path destination=setup();Path cache=temp.resolve("cache");
        var parameters=new SamplingPlan.InputParameters(2_500_000,8,0.005,1000,6);
        InputPreparation.prepareForCalculation(temp,cache,0,2.54,1.5,parameters,(done,message)->{});
        Map<Path,String> before=new LinkedHashMap<>();
        try(var paths=Files.walk(destination)) {
            for(Path path:paths.filter(Files::isRegularFile).toList())before.put(path,SimulationHistory.sha256(path));
        }
        long backups;
        try(var paths=Files.list(temp.resolve("input_backups"))){backups=paths.count();}
        assertThrows(IllegalArgumentException.class,()->InputPreparation.prepareForCalculation(temp,cache,0,3,1.5,parameters,(done,message)->{}));
        assertThrows(IllegalArgumentException.class,()->InputPreparation.prepareForCalculation(temp,cache,0,2.54,2,parameters,(done,message)->{}));
        for(var entry:before.entrySet())assertEquals(entry.getValue(),SimulationHistory.sha256(entry.getKey()));
        try(var paths=Files.list(temp.resolve("input_backups"))){assertEquals(backups,paths.count());}
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(destination));
    }
    @Test void finalPublishedBytesReachTheSolverAndTheBatchArchive()throws Exception {
        Path destination=setup();
        var parameters=new SamplingPlan.InputParameters(40_000_000,9,0.006,800,7);
        var prepared=InputPreparation.prepareForCalculation(temp,temp.resolve("cache"),0,2.54,1.5,parameters,(done,message)->{});
        Files.writeString(temp.resolve(SimulationRunner.EXE),"fake executable");Files.writeString(temp.resolve(SimulationRunner.DLL),"fake DLL");
        SamplingPlan plan=SamplingPlan.read(destination);
        var runner=new ParallelSimulationRunner((work,console)->{
            assertEquals(-1,Files.mismatch(destination.resolve("Card_001_ang_model.txt"),work.resolve("NGI_2D_INP.txt")));
            var actual=ModelInputs.parse(Files.readString(work.resolve("NGI_2D_INP.txt")));
            assertEquals(prepared.inputs().cards().get(0).model(),actual);
            StringBuilder response=new StringBuilder();
            for(int index=0;index<plan.computedPoints();index++)response.append(actual.begin()+index*actual.step()).append(" 3 4 5 53.13010235415598\n");
            Files.writeString(work.resolve(SimulationRunner.LOG),response);Files.writeString(console,response);
            return new Process() {
                public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
                public InputStream getInputStream(){return InputStream.nullInputStream();}
                public InputStream getErrorStream(){return InputStream.nullInputStream();}
                public int waitFor(){return 0;}
                public int exitValue(){return 0;}
                public void destroy(){ }
            };
        });
        var outcome=runner.run(prepared.inputs(),temp,temp.resolve("output"),1,1,progress->{},()->false);
        assertEquals(1,outcome.completed());
        Path archived=outcome.batch().resolve("inputs");
        assertEquals(-1,Files.mismatch(destination.resolve("Card_001_ang_model.txt"),archived.resolve("Card_001_ang_model.txt")));
        assertEquals(plan,SamplingPlan.read(archived));
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(archived));
    }
    @Test void finalPublicationRetainsAllThreeAnomaliesAndTheirPermittivities()throws Exception {
        var model=new StratumModel(0.1016,100,1,90,List.of(new StratumInterface(0.5)),
                List.of(20.0,40.0),List.of(3.0,37.0),List.of(new Fracture(0.75,30,0,120,41)),
                List.of(new SphereFracture(0.55,0,0.1016,0.04,80,17)),
                List.of(new WedgeFracture(330,0.8,30,0.9,30,0.7,90,23)),List.of(),
                List.of(new CubeAnomaly(0.3,0,0.1016,0.08,70,13)));
        var generated=new SliceGenerationService(temp).generate(model,0,1);
        MaterialPipelineTest.writeReferences(temp);
        var parameters=new SamplingPlan.InputParameters(2_500_000,8,0.005,1000,6);
        var prepared=InputPreparation.prepareForCalculation(temp,temp.resolve("cache"),0,2.54,1,parameters,(done,message)->{});
        Path destination=temp.resolve("192_NGI_Input");
        assertNull(prepared.backup());
        for(var card:prepared.inputs().cards()) {
            var raw=ModelInputs.parse("1 1\n0 1 1\n"+Files.readString(generated.output().resolve(card.name())));
            assertEquals(-1,Files.mismatch(card.snapshot(),destination.resolve(card.name())));
            assertEquals(raw.layers().size(),card.model().layers().size());
            for(int index=0;index<raw.layers().size();index++) {
                assertEquals(raw.layers().get(index).get(1),card.model().layers().get(index).get(1));
                assertEquals(raw.layers().get(index).get(2),card.model().layers().get(index).get(2));
            }
        }
        var first=prepared.inputs().cards().get(0).model();
        for(var expected:List.of(List.of(0.3,70.0,13.0),List.of(0.55,80.0,17.0),List.of(0.8,90.0,23.0))) {
            var actual=first.layers().stream().filter(layer->expected.get(0)<layer.get(0)).findFirst().orElseThrow();
            assertEquals(expected.get(1),actual.get(1));assertEquals(expected.get(2),actual.get(2));
        }
        assertTrue(first.fractures().stream().allMatch(fracture->fracture.get(3)==41));
        InputPreparation.verifyCurrentInputs(prepared.inputs(),temp,generated.generation());
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(destination));
    }
    @Test void correctedLayerBoundariesProfilesBackupsAndIdempotentRegeneration()throws Exception {
        Path destination=setup();Path first=destination.resolve("Card_001_ang_model.txt");String original=Files.readString(first);
        Files.writeString(destination.resolve("transf-result.csv"),"example");Files.write(destination.resolve("transf-result.png"),new byte[]{1,2});
        var completed=new ArrayList<Integer>();
        var prepared=InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->completed.add(done));
        assertEquals(192,prepared.updated());assertNotNull(prepared.backup());assertEquals(original,Files.readString(prepared.backup().resolve(first.getFileName())));
        var inputs=prepared.inputs();assertEquals(192,inputs.cards().size());assertEquals(1,inputs.cards().get(0).id());
        var model=inputs.cards().get(0).model();assertEquals(List.of(0.2,0.4,1.5,2.5),model.layers().stream().map(layer->layer.get(0)).toList());
        assertEquals(0,model.fractures().get(0).get(1));assertEquals(10,model.fractures().get(0).get(3));
        assertEquals(0,inputs.cards().get(1).model().fractures().size());
        assertEquals(100,InputPreparation.resistivity(model,0.199,true));assertEquals(30,InputPreparation.resistivity(model,0.2,true));
        assertEquals(50,InputPreparation.resistivity(model,0.4,true));assertEquals(90,InputPreparation.resistivity(model,1.5,true));
        assertEquals(0.5,InputPreparation.resistivity(model,0.6,true));
        assertEquals(384,completed.get(completed.size()-1));
        for(int index=1;index<completed.size();index++)assertTrue(completed.get(index)>=completed.get(index-1));
        Properties manifest=new Properties();
        try(var input=Files.newInputStream(inputs.directory().resolve("inputs.properties"))){manifest.load(input);}
        assertEquals(destination.toAbsolutePath().toString(),manifest.getProperty("source"));assertEquals("288192",manifest.getProperty("conversion.validated.records"));
        InputPreparation.verifySnapshot(inputs);
        InputPreparation.verifySnapshot(ModelInputs.load(destination,temp.resolve("import-cache"),(done,message)->{}));
        Path archived=Files.createDirectory(temp.resolve("archived"));inputs.copyProvenanceTo(archived);
        assertTrue(Files.isRegularFile(archived.resolve("conversion-source/Card_001_ang_model.txt")));
        String corrected=Files.readString(first);
        var repeated=InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{});
        assertEquals(0,repeated.updated());assertNull(repeated.backup());assertEquals(corrected,Files.readString(first));
        Files.writeString(first,"changed external input");InputPreparation.verifySnapshot(inputs);
        Files.writeString(inputs.cards().get(0).snapshot(),"tampered cached input");
        assertTrue(assertThrows(IOException.class,()->InputPreparation.verifySnapshot(inputs)).getMessage().contains("Card_001"));
    }
    @Test void invalidSlicesPreventAllPublicationAndNameSpecificFiles()throws Exception {
        Path destination=setup();String previous=Files.readString(destination.resolve("Card_001_ang_model.txt"));
        Files.writeString(temp.resolve("192_Slices_Output/Card_002_ang_model.txt"),SLICE.replace("0.001","0")+"0\n");
        Files.writeString(temp.resolve("192_Slices_Output/Card_003_ang_model.txt"),SLICE+"2\n");
        String error=assertThrows(IOException.class,()->InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{})).getMessage();
        assertTrue(error.contains("Card_002"));assertTrue(error.contains("Card_003"));
        assertEquals(previous,Files.readString(destination.resolve("Card_001_ang_model.txt")));assertFalse(Files.exists(temp.resolve("input_backups")));
    }
    @Test void samplingKeepsConvertedProvenanceAndVerifiesItAfterBatchCopy()throws Exception {
        setup();ModelInputs original=InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{}).inputs();
        ModelInputs sampled=SamplingPlan.prepare(original,temp.resolve("cache"),0.2,2.54,0.9);
        Path archived=Files.createDirectory(temp.resolve("sampled-archive"));sampled.copySnapshotTo(archived);
        ModelInputs restored=ModelInputs.restoreSnapshot(archived);InputPreparation.verifySnapshot(restored);
        assertTrue(Files.isRegularFile(archived.resolve("sampling-original/conversion-source/call.m")));
        assertEquals(original.cards().get(0).model().layers(),restored.cards().get(0).model().layers());
        Files.writeString(archived.resolve("sampling-original/conversion-source/Card_001_ang_model.txt"),"changed raw model");
        assertThrows(IOException.class,()->InputPreparation.verifySnapshot(restored));
    }
    @Test void oldMappingAndAlteredFractureOrParametersAreRejectedWithCorrectionHints()throws Exception {
        Path destination=setup();var settings=InputPreparation.settings(temp);
        String raw=Files.readString(temp.resolve("192_Slices_Output/Card_001_ang_model.txt"));
        var slice=ModelInputs.parse("1 1\n0 1 1\n"+raw);
        var old=ModelInputs.parse(Files.readString(destination.resolve("Card_001_ang_model.txt")));
        assertTrue(assertThrows(IOException.class,()->InputPreparation.validate(slice,old,settings,"Card_001")).getMessage().contains("下一层"));
        String correct=InputPreparation.convert(slice,settings);
        assertThrows(IOException.class,()->InputPreparation.validate(slice,ModelInputs.parse(correct.replace("2500000","4000000")),settings,"Card_001"));
        var altered=new ModelInputs.Model(old.frequency(),old.diameterInches(),old.offset(),old.mudRho(),old.mudEps(),old.begin(),old.step(),old.end(),ModelInputs.parse(correct).layers(),old.fractures());
        assertTrue(assertThrows(IOException.class,()->InputPreparation.validate(slice,altered,settings,"Card_001")).getMessage().contains("裂缝"));
    }
    @Test void missingDuplicateAndInconsistentDepthsAreRejected()throws Exception {
        setup();Path source=temp.resolve("192_Slices_Output");
        Files.delete(source.resolve("Card_192_ang_model.txt"));Files.writeString(source.resolve("Card_001_duplicate.txt"),SLICE+"0\n");
        String error=assertThrows(IOException.class,()->InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{})).getMessage();
        assertTrue(error.contains("Card_192"));assertTrue(error.contains("重复编号"));
        Files.delete(source.resolve("Card_001_duplicate.txt"));Files.writeString(source.resolve("Card_192_ang_model.txt"),SLICE.replace("0.001","0.002")+"0\n");
        assertTrue(assertThrows(IOException.class,()->InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{})).getMessage().contains("采样间隔"));
    }
    @Test void settingsFollowFrequencySelectionAndRejectUnsupportedOrEscapingConfiguration()throws Exception {
        setup();Files.writeString(temp.resolve("call.m"),SCRIPT.replace("freq_all(1)","freq_all(2)"));assertEquals(40e6,InputPreparation.settings(temp).frequency());
        Files.writeString(temp.resolve("call.m"),SCRIPT.replace("freq_all(1)","system('bad')"));assertThrows(IOException.class,()->InputPreparation.settings(temp));
        Files.writeString(temp.resolve("call.m"),SCRIPT.replace("'192_NGI_Input'","'../escape'"));assertThrows(IOException.class,()->InputPreparation.settings(temp));
        Files.writeString(temp.resolve("call.m"),SCRIPT.replace("layer_data(nl,1)+1","layer_data(nl,1)+2"));assertThrows(IOException.class,()->InputPreparation.settings(temp));
    }
    @Test void convertedSnapshotReachesSolverAndArchivedProvenanceCanBeRecovered()throws Exception {
        setup();var prepared=InputPreparation.regenerate(temp,temp.resolve("cache"),(done,message)->{});
        Files.writeString(temp.resolve(SimulationRunner.EXE),"fake executable");Files.writeString(temp.resolve(SimulationRunner.DLL),"fake DLL");
        var runner=new ParallelSimulationRunner((work,console)->{
            var actual=ModelInputs.parse(Files.readString(work.resolve("NGI_2D_INP.txt")));
            assertEquals(prepared.inputs().cards().get(0).model(),actual);
            StringBuilder response=new StringBuilder();
            for(int index=0;index<=1500;index++)response.append(index*0.001).append(" 3 4 5 53.13010235415598\n");
            Files.writeString(work.resolve(SimulationRunner.LOG),response);Files.writeString(console,response);
            return new Process() {
                public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
                public InputStream getInputStream(){return InputStream.nullInputStream();}
                public InputStream getErrorStream(){return InputStream.nullInputStream();}
                public int waitFor(){return 0;}
                public int exitValue(){return 0;}
                public void destroy(){ }
            };
        });
        var outcome=runner.run(prepared.inputs(),temp,temp.resolve("output"),1,1,progress->{},()->false);
        assertEquals(1,outcome.completed());assertNull(outcome.result());
        InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(outcome.batch().resolve("inputs")));
        assertTrue(Files.exists(outcome.batch().resolve("inputs/conversion-source/transf.m")));
    }
    @Test void singleLayerAndZeroFracturesRemainValid()throws Exception {
        setup();var settings=InputPreparation.settings(temp);
        var slice=ModelInputs.parse("1 1\n0 1 1\n0 0.001 0.002\n1\n0 100 1\n0\n");
        var converted=ModelInputs.parse(InputPreparation.convert(slice,settings));
        assertEquals(1,converted.layers().size());assertEquals(1,converted.layers().get(0).get(0));
        InputPreparation.validate(slice,converted,settings,"Card_001");
    }
}
