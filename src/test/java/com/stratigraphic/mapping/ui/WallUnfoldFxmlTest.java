package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ResponseChart;
import com.stratigraphic.mapping.simulation.ResponseData;
import com.stratigraphic.mapping.simulation.SimulationHistory;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.time.Instant;
import java.util.Properties;
import java.util.List;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class WallUnfoldFxmlTest {
    @Test void secondPageLoadsHistoryRendersGroupsAndPreservesNavigation() throws Exception {
        Path fixture=Path.of("target","synthetic-six-column-ui-fixture.txt");
        StringBuilder text=new StringBuilder();
        for(double md:new double[]{0,0.1,0.7,1.5}) for(int id=192;id>=1;id--) {
            double real=id,imaginary=(id-96)/2.0;
            if(md==0&&id==1) {real=0;imaginary=0;}
            text.append(md).append(' ').append(id).append(' ').append(real).append(' ').append(imaginary).append(' ').append(Math.hypot(real,imaginary)).append(' ').append(Math.toDegrees(Math.atan2(imaginary,real))).append('\n');
        }
        Files.writeString(fixture,text);ResponseData data=ResponseData.read(fixture,(a,b)->{});
        CountDownLatch start=new CountDownLatch(1);
        try {Platform.startup(start::countDown);assertTrue(start.await(10,TimeUnit.SECONDS));} catch(IllegalStateException ignored) { }
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(()->{
            try {
                FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
                Parent root=loader.load();Scene scene=new Scene(root,1440,900);
                scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
                root.applyCss();root.layout();
                assertTrue(root.lookupAll(".titled-pane").stream().anyMatch(n->n instanceof TitledPane t&&t.getText().equals("有关计算结果信息")));
                assertFalse(root.lookupAll(".titled-pane").stream().anyMatch(n->n instanceof TitledPane t&&t.getText().equals("输入模型信息")));
                assertFalse(root.lookupAll(".titled-pane").stream().anyMatch(n->n instanceof TitledPane t&&t.getText().equals("各通道计算进度")));
                assertFalse(root.lookupAll(".label").stream().anyMatch(n->n instanceof Label label&&label.getText().equals("模拟计算与结果参数")));
                assertEquals(2,root.lookupAll(".separator").size());
                var resultPane=root.lookupAll(".titled-pane").stream().filter(n->n instanceof TitledPane pane&&pane.getText().equals("有关计算结果信息")).findFirst().orElseThrow();
                assertSame(root.lookup("#imagingStep3"),resultPane.getParent());
                assertSame(root.lookup("#imagingStep1"),button(root,"获得输入数据").getParent());
                assertSame(root.lookup("#imagingStep2"),button(root,"计算输出数据").getParent());
                assertTrue(root.lookupAll(".label").stream().filter(n->n instanceof Label).map(n->((Label)n).getText()).noneMatch(t->t.contains("可直接选择历史")||t.contains("选择六列")));
                for(String name:new String[]{"获得输入数据","计算输出数据","生成图片"}) assertNotNull(button(root,name));
                assertTrue(button(root,"计算输出数据").isDisabled());assertTrue(button(root,"导出 PNG").isDisabled());
                WallUnfoldController controller=loader.getController();controller.showResult(data);root.applyCss();root.layout();
                assertFalse(button(root,"导出 PNG").isDisabled());
                StackPane a=(StackPane)root.lookup("#amplitudeBox"),p=(StackPane)root.lookup("#phaseBox");
                assertTrue(a.localToScene(a.getBoundsInLocal()).getMinX()<p.localToScene(p.getBoundsInLocal()).getMinX());
                assertEquals(a.getHeight(),p.getHeight(),0.1);
                assertTrue(root.lookup("#parameterSlot").getBoundsInParent().getWidth()<=238.1);
                ResponseChart chart=(ResponseChart)a.getChildren().get(0);
                double width=chart.getWidth()-66;
                assertEquals(1,chart.sampleAt(54+width*0.5/213,28).electrode());
                assertEquals(0,chart.sampleAt(54+width*0.5/213,28).amplitude());
                assertNull(chart.sampleAt(54+width*25/213,30));
                assertEquals(192,chart.sampleAt(54+width*212.5/213,28).electrode());
                var canvas=(javafx.scene.canvas.Canvas)chart.getChildrenUnmodifiable().get(0);
                ResponseChart phaseChart=(ResponseChart)p.getChildren().get(0);
                Node phaseCanvas=phaseChart.getChildrenUnmodifiable().get(0);
                double phaseWidth=phaseChart.getWidth()-66;
                double probeX=54+width*0.42,phaseProbeX=54+phaseWidth*0.42;
                var originalAmplitude=chart.sampleAt(probeX,28);
                var originalPhase=phaseChart.sampleAt(phaseProbeX,28);
                wheel(canvas,54+width/2,28+(chart.getHeight()-130)/2,80);
                assertNotEquals(originalAmplitude,chart.sampleAt(probeX,28));
                assertEquals(originalPhase,phaseChart.sampleAt(phaseProbeX,28));
                ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",Path.of("target","ui-independent-zoom-1440.png").toFile());
                var zoomedAmplitude=chart.sampleAt(probeX,28);
                wheel(phaseCanvas,54+phaseWidth/2,28+(phaseChart.getHeight()-130)/2,40);
                assertNotEquals(originalPhase,phaseChart.sampleAt(phaseProbeX,28));
                assertEquals(zoomedAmplitude,chart.sampleAt(probeX,28));
                button(root,"重置视图").fire();
                assertEquals(originalAmplitude,chart.sampleAt(probeX,28));
                assertEquals(originalPhase,phaseChart.sampleAt(phaseProbeX,28));
                double clickX=54+width*0.5/213;
                javafx.event.Event.fireEvent(canvas,new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    clickX,28,clickX,28,javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,false,false,false,false,false,true,
                    new javafx.scene.input.PickResult(canvas,new javafx.geometry.Point3D(clickX,28,0),0)));
                Label inspector=(Label)chart.lookup(".response-inspector");
                assertTrue(inspector.isVisible());assertEquals("深度 MD：0.0 m\n电极编号：1\n模值：0.0 Ω·m",inspector.getText());
                assertNull(root.lookup("#selectionMessage"));
                ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",Path.of("target","ui-simulation-1440.png").toFile());
                root.resize(1100,720);root.layout();
                ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",Path.of("target","ui-simulation-1100.png").toFile());
                PageNavigation.show(root,"wall-unfold-view.fxml","main-view.fxml","first");
                PageNavigation.show(scene.getRoot(),"main-view.fxml","wall-unfold-view.fxml","second");
                assertSame(root,scene.getRoot());
            } catch(Throwable ex) {error.set(ex);} finally {done.countDown();}
        });
        assertTrue(done.await(30,TimeUnit.SECONDS));if(error.get()!=null) throw new AssertionError(error.get());
    }
    private static void wheel(Node canvas,double x,double y,double delta) {
        javafx.event.Event.fireEvent(canvas,new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
            x,y,x,y,false,false,false,false,false,false,0,delta,0,delta,
            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,0,javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE,0,0,
            new javafx.scene.input.PickResult(canvas,new javafx.geometry.Point3D(x,y,0),0)));
    }
    private static Button button(Node node,String name) {
        if(node instanceof Button b&&b.getText().equals(name)) return b;
        if(node instanceof Parent parent) for(Node child:parent.getChildrenUnmodifiable()) {Button b=button(child,name);if(b!=null)return b;}
        return null;
    }
    @Test void everyGenerateChoosesFileWithoutCalculationAndCancelOrInvalidFilePreservesPlot() throws Exception {
        Path file=Path.of("target","selected-result-TEST.txt"),computed=Path.of("target","new-computed-result-TEST.txt"),invalid=Path.of("target","invalid-result-TEST.txt");
        StringBuilder content=new StringBuilder();for(int e=1;e<=192;e++)content.append("0 ").append(e).append(" 3 4 5 53.13010235415598\n");
        Files.writeString(file,content);Files.writeString(computed,content.toString().replace("3 4 5","6 8 10"));Files.writeString(invalid,"incomplete result");
        ResponseData data=ResponseData.read(computed,(a,b)->{});
        CountDownLatch started=new CountDownLatch(1);
        try{Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored){}
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
        Parent root=fx(()->{Parent r=loader.load();new Scene(r,1100,720);r.applyCss();r.layout();return r;});
        WallUnfoldController controller=loader.getController();
        java.util.concurrent.atomic.AtomicInteger selections=new java.util.concurrent.atomic.AtomicInteger();
        // First load needs neither an input snapshot nor any solver run.
        fx(()->{controller.resultFilePicker=(chooser,owner)->{selections.incrementAndGet();return file.toFile();};return null;});
        generateAndWait(root);
        fx(()->{
            assertTrue(button(root,"计算输出数据").isDisabled());assertFalse(button(root,"导出 PNG").isDisabled());
            root.layout();var chart=(ResponseChart)((StackPane)root.lookup("#amplitudeBox")).getChildren().get(0);assertEquals(5,chart.sampleAt(55,29).amplitude());
            // A new computed result must not bypass the next file selection.
            controller.acceptComputedResult(data);return null;
        });
        generateAndWait(root);assertEquals(2,selections.get());
        Node retained=fx(()->{
            root.layout();var chart=(ResponseChart)((StackPane)root.lookup("#amplitudeBox")).getChildren().get(0);assertEquals(5,chart.sampleAt(55,29).amplitude());
            controller.resultFilePicker=(chooser,owner)->{selections.incrementAndGet();return null;};
            button(root,"生成图片").fire();assertSame(chart,((StackPane)root.lookup("#amplitudeBox")).getChildren().get(0));
            controller.resultFilePicker=(chooser,owner)->{selections.incrementAndGet();return invalid.toFile();};return chart;
        });
        generateAndWait(root);assertEquals(4,selections.get());
        fx(()->{
            assertSame(retained,((StackPane)root.lookup("#amplitudeBox")).getChildren().get(0));assertFalse(button(root,"导出 PNG").isDisabled());
            Label status=(Label)root.lookup("#statusMessage");assertTrue(status.getText().contains("错误详情"));assertEquals(status.getText(),status.getTooltip().getText());
            assertTrue(root.lookupAll(".label").stream().filter(n->n instanceof Label).map(n->((Label)n).getText()).anyMatch(s->s.contains(file.getFileName().toString())));return null;
        });
    }
    private static <T> T fx(Callable<T> action)throws Exception {
        FutureTask<T> task=new FutureTask<>(action);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);
    }
    @Test void startupOffersAllHistoryAndOnlyUnfinishedBatchesCanResume(@TempDir Path workspace)throws Exception {
        for(int index=0;index<5;index++) {
            Path batch=Files.createDirectories(workspace.resolve("output/fixture_"+index+"_batch"));
            Files.createDirectories(batch.resolve("inputs"));Files.writeString(batch.resolve("inputs/inputs.properties"),"");
            Properties record=new Properties();record.setProperty("state",index==4?"COMPLETE":"INTERRUPTED_INCOMPLETE");
            if(index==4)Files.writeString(workspace.resolve("output/fixture_4.txt"),"completed result fixture");
            record.setProperty("started",Instant.parse("2026-11-16T09:00:00Z").plusSeconds(index*60).toString());
            for(int id=1;id<=(index==4?192:index==3?176:index);id++)record.setProperty("channel."+id,"SUCCESS");
            try(var output=Files.newOutputStream(batch.resolve("batch.properties"))){record.store(output,"");}
        }
        CountDownLatch started=new CountDownLatch(1);
        try {Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored) { }
        CountDownLatch scanned=new CountDownLatch(1);
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
        loader.setControllerFactory(type->new WallUnfoldController(workspace));
        Parent root=fx(()->{
            Parent view=loader.load();Scene scene=new Scene(view,1100,720);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());view.applyCss();view.layout();
            ListView<?> list=(ListView<?>)view.lookup("#unfinishedBatches");
            list.getItems().addListener((javafx.collections.ListChangeListener<Object>)change->{if(list.getItems().size()==5)scanned.countDown();});
            if(list.getItems().size()==5)scanned.countDown();return view;
        });
        assertTrue(scanned.await(10,TimeUnit.SECONDS));
        fx(()->{
            ListView<?> list=(ListView<?>)root.lookup("#unfinishedBatches");
            assertEquals(List.of(192,176,2,1,0),list.getItems().stream().map(item->((SimulationHistory.Entry)item).completed()).toList());
            assertTrue(button(root,"继续所选计算").isDisabled(),"Completed batches cannot be resumed");
            list.getSelectionModel().select(1);
            assertFalse(button(root,"继续所选计算").isDisabled());assertTrue(button(root,"计算输出数据").isDisabled());
            Spinner<?> concurrency=(Spinner<?>)root.lookup("#parallelism");
            assertEquals(Math.min(192,Runtime.getRuntime().availableProcessors()),concurrency.getValue());
            assertTrue(root.lookup("#parallelismSettings").isVisible());assertTrue(root.lookup("#parallelismSettings").isManaged());
            var historyPane=root.lookupAll(".titled-pane").stream().filter(node->node instanceof TitledPane pane&&pane.getText().equals("历史记录")).findFirst().orElseThrow();
            assertSame(root.lookup("#imagingStep2"),historyPane.getParent());
            assertNotNull(button(root,"暂停计算"));assertSame(button(root,"暂停计算").getParent(),button(root,"停止计算").getParent());
            list.getSelectionModel().select(2);assertEquals(2,((SimulationHistory.Entry)list.getSelectionModel().getSelectedItem()).completed());
            list.getSelectionModel().select(1);
            root.applyCss();root.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",Path.of("target","ui-recovery-1100.png").toFile());
            root.resize(1440,900);root.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",Path.of("target","ui-recovery-1440.png").toFile());return null;
        });
        Path selectedResult=workspace.resolve("output/selected-result.txt");
        StringBuilder content=new StringBuilder();for(int electrode=1;electrode<=192;electrode++)content.append("0 ").append(electrode).append(" 3 4 5 53.13010235415598\n");
        Files.writeString(selectedResult,content);
        Object selectedBatch=fx(()->{
            WallUnfoldController controller=loader.getController();controller.resultFilePicker=(chooser,owner)->selectedResult.toFile();
            return ((ListView<?>)root.lookup("#unfinishedBatches")).getSelectionModel().getSelectedItem();
        });
        generateAndWait(root,()->{
            Node pane=root.lookupAll(".titled-pane").stream().filter(node->node instanceof TitledPane titled&&titled.getText().equals("历史记录")).findFirst().orElseThrow();
            assertTrue(pane.isVisible());assertTrue(pane.isManaged());assertTrue(pane.isDisabled());
            ListView<?> list=(ListView<?>)root.lookup("#unfinishedBatches");assertEquals(5,list.getItems().size());assertSame(selectedBatch,list.getSelectionModel().getSelectedItem());
            assertTrue(((ProgressBar)root.lookup("#resultProgress")).progressProperty().isBound());
        });
        fx(()->{
            Node pane=root.lookupAll(".titled-pane").stream().filter(node->node instanceof TitledPane titled&&titled.getText().equals("历史记录")).findFirst().orElseThrow();
            assertTrue(pane.isVisible());assertFalse(pane.isDisabled());assertFalse(button(root,"继续所选计算").isDisabled());
            ListView<?> list=(ListView<?>)root.lookup("#unfinishedBatches");assertEquals(5,list.getItems().size());assertSame(selectedBatch,list.getSelectionModel().getSelectedItem());
            ProgressBar bar=(ProgressBar)root.lookup("#resultProgress");assertEquals(1,bar.getProgress());assertFalse(bar.progressProperty().isBound());
            root.resize(1100,720);root.applyCss();root.layout();
            ResponseChart chart=(ResponseChart)((StackPane)root.lookup("#amplitudeBox")).getChildren().get(0);
            var canvas=(javafx.scene.canvas.Canvas)chart.getChildrenUnmodifiable().get(0);
            double clickX=54+(chart.getWidth()-66)*0.45,clickY=28+(chart.getHeight()-130)*0.3;
            javafx.event.Event.fireEvent(canvas,new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                clickX,clickY,clickX,clickY,javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,false,false,false,false,false,true,
                new javafx.scene.input.PickResult(canvas,new javafx.geometry.Point3D(clickX,clickY,0),0)));
            assertTrue(((Label)chart.lookup(".response-inspector")).isVisible());
            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",Path.of("target","ui-loaded-history-popup-1100.png").toFile());return null;
        });
        // This fixture has no model inputs, so resuming fails validation before any solver starts.
        CountDownLatch computationEnded=new CountDownLatch(1);
        fx(()->{
            Button plot=button(root,"生成图片");
            plot.disabledProperty().addListener((observable,previous,disabled)->{if(!disabled)computationEnded.countDown();});
            button(root,"继续所选计算").fire();
            Node settings=root.lookup("#parallelismSettings");
            assertTrue(settings.isVisible());assertTrue(settings.isManaged());
            assertTrue(root.lookup("#parallelism").isDisabled());
            assertTrue(button(root,"停止计算").isVisible());return null;
        });
        assertTrue(computationEnded.await(10,TimeUnit.SECONDS));
        fx(()->{assertFalse(root.lookup("#parallelism").isDisabled());assertTrue(root.lookup("#parallelismSettings").isVisible());return null;});
    }
    @Test void allProgressBarsRenderVisibleBlueFillAtTheirActualFractions(@TempDir Path workspace)throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try {Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored) { }
        fx(()->{
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
            loader.setControllerFactory(type->new WallUnfoldController(workspace));
            Parent root=loader.load();Scene scene=new Scene(root,1100,720);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
            root.applyCss();root.layout();
            int index=0;
            for(String name:new String[]{"inputProgress","calculationProgress","resultProgress"}) {
                ProgressBar bar=(ProgressBar)root.lookup("#"+name);
                for(double fraction:new double[]{0,0.25,0.75,1}) {
                    bar.setProgress(fraction);root.applyCss();root.layout();
                    var image=bar.snapshot(null,null);assertTrue(image.getHeight()>=8);
                    double[] locations={0.125,0.5,0.875};
                    for(double location:locations) {
                        var color=image.getPixelReader().getColor((int)(image.getWidth()*location),(int)image.getHeight()/2);
                        boolean blue=color.getBlue()-color.getRed()>0.3&&color.getBlue()-color.getGreen()>0.3;
                        assertEquals(location<fraction,blue,name+" fraction="+fraction+" location="+location+" color="+color);
                    }
                }
                bar.setProgress(++index/4.0);
            }
            root.layout();ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",Path.of("target","ui-progress-bars.png").toFile());return null;
        });
    }
    private static void generateAndWait(Parent root)throws Exception {
        generateAndWait(root,()->{});
    }
    @Test void samplingInputsAreAboveLoadButtonAndEditingRequiresReload(@TempDir Path workspace)throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try{Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored){}
        Path converted=Files.createDirectory(workspace.resolve("converted"));
        for(int id=1;id<=192;id++)Files.writeString(converted.resolve(String.format("Card_%03d.txt",id)),
            "2500000 8\n0.005 1000 6\n0 0.001 1.5\n1\n2.5 100 10\n0\n");
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
        loader.setControllerFactory(type->new WallUnfoldController(workspace));
        Parent root=fx(()->{Parent view=loader.load();new Scene(view,1100,720);view.applyCss();view.layout();return view;});
        WallUnfoldController controller=loader.getController();
        var directory=WallUnfoldController.class.getDeclaredField("inputDirectory");directory.setAccessible(true);directory.set(controller,converted);
        CountDownLatch loaded=new CountDownLatch(1);
        fx(()->{
            TextField begin=(TextField)root.lookup("#samplingBegin"),step=(TextField)root.lookup("#samplingStep"),end=(TextField)root.lookup("#samplingEnd");
            assertEquals("0",begin.getText());assertEquals("1",step.getText());assertEquals("1.5",end.getText());
            assertTrue(begin.localToScene(begin.getBoundsInLocal()).getMinY()<button(root,"获得输入数据").localToScene(button(root,"获得输入数据").getBoundsInLocal()).getMinY());
            step.setText("2.55");button(root,"获得输入数据").fire();assertTrue(button(root,"计算输出数据").isDisabled());
            assertTrue(((Label)root.lookup("#statusMessage")).getText().contains("2.54"));
            begin.setText("0.2");step.setText("2.54");end.setText("0.9");
            button(root,"计算输出数据").disabledProperty().addListener((observable,oldValue,disabled)->{if(!disabled)loaded.countDown();});
            button(root,"获得输入数据").fire();assertTrue(begin.isDisabled());assertTrue(step.isDisabled());assertTrue(end.isDisabled());return null;
        });
        assertTrue(loaded.await(30,TimeUnit.SECONDS),"Valid sampling input should load without executing solver");
        fx(()->{
            assertFalse(button(root,"计算输出数据").isDisabled());
            Label status=(Label)root.lookup("#statusMessage");assertTrue(status.getText().contains("输入数据已就绪"));assertEquals(status.getText(),status.getTooltip().getText());
            var field=WallUnfoldController.class.getDeclaredField("inputs");field.setAccessible(true);
            var inputs=(com.stratigraphic.mapping.simulation.ModelInputs)field.get(controller);
            assertEquals(0.2,inputs.cards().get(0).model().begin());assertEquals(0.9,inputs.cards().get(0).model().end());
            assertTrue(inputs.cards().get(0).model().step()<=0.00254);
            ((TextField)root.lookup("#samplingStep")).setText("1");assertTrue(button(root,"计算输出数据").isDisabled());assertNull(field.get(controller));return null;
        });
    }
    @Test void calculationProgressShowsDepthOnANewLineWithoutClipping(@TempDir Path workspace)throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try{Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored){}
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
        loader.setControllerFactory(type->new WallUnfoldController(workspace));
        Parent root=fx(()->{
            Parent view=loader.load();Scene scene=new Scene(view,1100,720);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
            view.applyCss();view.layout();return view;
        });
        String message="当前电极：第 192 个（共 192 个）\n已计算深度点：1501 / 1501";
        CountDownLatch activity=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        var task=new javafx.concurrent.Task<String>() {
            @Override protected String call()throws Exception {
                updateMessage(message);
                if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Test did not release calculation task");
                return "computed";
            }
        };
        try {
            fx(()->{
                Label progressMessage=(Label)root.lookup("#calculationMessage");
                progressMessage.textProperty().addListener((observable,previous,current)->{if(message.equals(current))activity.countDown();});
                var run=WallUnfoldController.class.getDeclaredMethod("runTask",javafx.concurrent.Task.class,int.class,java.util.function.Consumer.class);run.setAccessible(true);
                run.invoke(loader.getController(),task,1,(java.util.function.Consumer<String>)value->finished.countDown());return null;
            });
            assertTrue(activity.await(10,TimeUnit.SECONDS));
            fx(()->{
                for(double width:new double[]{1100,900}) {
                    root.resize(width,720);root.applyCss();root.layout();
                    Label progressMessage=(Label)root.lookup("#calculationMessage");
                    assertEquals(message,progressMessage.getText());assertTrue(progressMessage.isWrapText());
                    var rendered=(javafx.scene.text.Text)progressMessage.lookup(".text");
                    assertEquals(message,rendered.getText());
                    assertTrue(progressMessage.getHeight()>=rendered.getLayoutBounds().getHeight()+progressMessage.getInsets().getTop()+progressMessage.getInsets().getBottom());
                    assertTrue(rendered.getLayoutBounds().getWidth()<=progressMessage.getWidth());
                    assertFalse(((Label)root.lookup("#statusMessage")).getText().contains("\n"));
                }
                return null;
            });
        } finally {release.countDown();assertTrue(finished.await(10,TimeUnit.SECONDS));}
    }
    @Test void inputActivityKeepsSidebarGeometryScrollAndExpandedState(@TempDir Path workspace)throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try{Platform.startup(started::countDown);assertTrue(started.await(10,TimeUnit.SECONDS));}catch(IllegalStateException ignored){}
        FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/stratigraphic/mapping/wall-unfold-view.fxml"));
        loader.setControllerFactory(type->new WallUnfoldController(workspace));
        Parent root=fx(()->{
            Parent view=loader.load();Scene scene=new Scene(view,1100,420);
            scene.getStylesheets().add(getClass().getResource("/com/stratigraphic/mapping/app.css").toExternalForm());
            view.applyCss();view.layout();return view;
        });
        WallUnfoldController controller=loader.getController();
        CountDownLatch firstActivity=new CountDownLatch(1),secondActivity=new CountDownLatch(1),next=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        var task=new javafx.concurrent.Task<String>() {
            @Override protected String call()throws Exception {
                updateProgress(80,192);updateMessage("已加载 80/192 · Card_080_"+"long_filename_".repeat(12)+".txt");
                if(!next.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Test did not advance input activity");
                updateMessage("正在应用采样参数\n正在校验 192 个电极的独立输入快照\n原始模型保留");
                if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Test did not release input task");
                return "loaded";
            }
        };
        double[] before=fx(()->{
            for(Node node:root.lookupAll(".titled-pane"))if(node instanceof TitledPane pane){pane.setAnimated(false);if(pane.getText().equals("有关计算结果信息"))pane.setExpanded(false);}
            root.layout();ScrollPane scroll=(ScrollPane)root.lookup("#parameterScroll");
            assertTrue(scroll.getContent().getBoundsInLocal().getHeight()>scroll.getViewportBounds().getHeight());
            button(root,"获得输入数据").requestFocus();scroll.setVvalue(0.47);root.layout();
            Label status=(Label)root.lookup("#statusMessage");
            status.textProperty().addListener((observable,old,value)->{if(value.contains("已加载 80/192"))firstActivity.countDown();if(value.contains("正在应用采样参数"))secondActivity.countDown();});
            double[] state={scroll.getContent().localToScene(0,0).getY(),scroll.getContent().getLayoutBounds().getWidth(),button(root,"计算输出数据").localToScene(0,0).getY(),button(root,"生成图片").localToScene(0,0).getY()};
            var run=WallUnfoldController.class.getDeclaredMethod("runTask",javafx.concurrent.Task.class,int.class,java.util.function.Consumer.class);run.setAccessible(true);
            run.invoke(controller,task,0,(java.util.function.Consumer<String>)value->{((Label)root.lookup("#inputMessage")).setText("已加载并校验 192/192\n计算 MD：0.2～0.9 m\n实际间距：2.54 mm");finished.countDown();});
            assertTrue(status.getText().contains("正在获得输入数据"));return state;
        });
        try {
            assertTrue(firstActivity.await(10,TimeUnit.SECONDS));assertStableInputSidebar(root,before);
            next.countDown();assertTrue(secondActivity.await(10,TimeUnit.SECONDS));assertStableInputSidebar(root,before);
            fx(()->{Label status=(Label)root.lookup("#statusMessage");assertTrue(status.getText().contains("192 个电极"));assertFalse(status.getText().contains("\n"));assertEquals(status.getText(),status.getTooltip().getText());
                ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",Path.of("target","ui-input-loading-stable-1100.png").toFile());return null;});
            release.countDown();assertTrue(finished.await(10,TimeUnit.SECONDS));assertStableInputSidebar(root,before);
        } finally {next.countDown();release.countDown();}
    }
    private static void assertStableInputSidebar(Parent root,double[] before)throws Exception {
        fx(()->{
            root.applyCss();root.layout();ScrollPane scroll=(ScrollPane)root.lookup("#parameterScroll");
            // Compare displayed pixels: ScrollPane may adjust its normalized vvalue when child bounds change.
            assertEquals(before[0],scroll.getContent().localToScene(0,0).getY(),0.1);assertEquals(before[1],scroll.getContent().getLayoutBounds().getWidth(),0.1);
            assertEquals(before[2],button(root,"计算输出数据").localToScene(0,0).getY(),0.1);assertEquals(before[3],button(root,"生成图片").localToScene(0,0).getY(),0.1);
            assertEquals(22,((Label)root.lookup("#inputMessage")).getHeight(),0.1);
            for(Node node:root.lookupAll(".titled-pane"))if(node instanceof TitledPane pane){if(pane.getText().equals("有关计算结果信息"))assertFalse(pane.isExpanded());if(pane.getText().equals("历史记录"))assertTrue(pane.isExpanded());}
            return null;
        });
    }
    private static void generateAndWait(Parent root,Runnable whileLoading)throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        fx(()->{
            Button generate=button(root,"生成图片");
            javafx.beans.value.ChangeListener<Boolean> listener=new javafx.beans.value.ChangeListener<>() {
                public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable,Boolean old,Boolean disabled){
                    if(!disabled){generate.disabledProperty().removeListener(this);done.countDown();}
                }
            };
            generate.disabledProperty().addListener(listener);generate.fire();whileLoading.run();return null;
        });
        assertTrue(done.await(20,TimeUnit.SECONDS),"Selected result should finish loading");
    }
}
