package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ResponseChart;
import com.stratigraphic.mapping.chart.ResponseViewport;
import com.stratigraphic.mapping.chart.WallResistivityChart;
import com.stratigraphic.mapping.simulation.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** 电成像计算独立，彩色井壁图与首页生成结果同步。 */
public final class WallUnfoldController {
    @FXML private BorderPane appRoot;
    @FXML private VBox parameterSlot;
    @FXML private StackPane amplitudeBox,phaseBox,wallChartBox;
    @FXML private Label statusMessage;
    @FXML private Button exportButton,resetButton;
    private final Button loadButton=new Button("获得输入数据"),computeButton=new Button("计算输出数据"),plotButton=new Button("生成图片");
    private final ProgressBar[] progress={new ProgressBar(0),new ProgressBar(0),new ProgressBar(0)};
    private final Label[] messages={label("等待加载输入数据"),label("等待输入数据就绪"),label("等待生成图片")};
    private final Label resultInfo=label("暂无计算结果");
    private final TextField samplingBegin=new TextField("0"),samplingStep=new TextField("1"),samplingEnd=new TextField("1.5");
    private final Button stopButton=new Button("停止计算");
    private final Button pauseButton=new Button("暂停计算"),resumeBatchButton=new Button("继续所选计算");
    private final ListView<SimulationHistory.Entry> unfinishedBatches=new ListView<>();
    private final TitledPane historyPane=new TitledPane("历史记录",new VBox(8,unfinishedBatches,resumeBatchButton));
    private List<SimulationHistory.Entry> history=List.of();
    private int historyRevision;
    private ParallelSimulationRunner.Control calculationControl;
    private final Path workspaceRoot;
    private Path computedResult;
    private Path resultDirectory=SimulationRunner.DEFAULT_ROOT.resolve("output");
    // Allows integration tests to exercise dialog choices without opening a native modal window.
    BiFunction<FileChooser,Window,File> resultFilePicker=FileChooser::showOpenDialog;
    private final Spinner<Integer> parallelism=new Spinner<>(1,ParallelSimulationRunner.maxParallelism(),ParallelSimulationRunner.defaultParallelism());
    private final HBox parallelismSettings=new HBox(8,label("同时计算通道数"),parallelism);
    private record Computed(SimulationRunner.Outcome outcome,ResponseData data) { }
    private final TextArea errorDetails=new TextArea();
    private final TitledPane errorPane=new TitledPane("错误详情",errorDetails);
    private final TextField amplitudeMin=new TextField(),amplitudeMax=new TextField(),phaseMin=new TextField(),phaseMax=new TextField();
    private final Button applyColors=new Button("应用色标");
    private final ResponseViewport amplitudeViewport=new ResponseViewport(),phaseViewport=new ResponseViewport();
    private final ResponseViewport wallViewport=new ResponseViewport();
    private final ChangeListener<WallChartState.Snapshot> wallStateListener=(observable,previous,current)->showWallChart(current);
    private ObjectProperty<WallChartState.Snapshot> wallState;
    private WallChartState.Snapshot wallSnapshot;
    private WallResistivityChart wallChart;
    private ModelInputs inputs;
    private ResponseData result;
    private ResponseChart amplitudeChart,phaseChart;
    private boolean busy;
    private ScrollPane parameterScroll;
    private Path inputDirectory=SimulationRunner.DEFAULT_ROOT.resolve("192_NGI_Input");
    public WallUnfoldController() {this(SimulationRunner.DEFAULT_ROOT);}
    WallUnfoldController(Path workspaceRoot) {
        this.workspaceRoot=workspaceRoot;
        inputDirectory=workspaceRoot.resolve("192_NGI_Input");resultDirectory=workspaceRoot.resolve("output");
    }
    @FXML private void initialize() {
        appRoot.sceneProperty().addListener((observable,previous,current)->{
            if(current==null) return;
            ObjectProperty<WallChartState.Snapshot> shared=WallChartState.in(current);
            if(wallState!=shared) {
                if(wallState!=null) wallState.removeListener(wallStateListener);
                wallState=shared;wallState.addListener(wallStateListener);
            }
            showWallChart(shared.get());
        });
        computeButton.setDisable(true); applyColors.setDisable(true);statusMessage.getStyleClass().add("msg");
        statusMessage.setMinWidth(0);statusMessage.setWrapText(false);
        Tooltip statusDetails=new Tooltip();statusDetails.textProperty().bind(statusMessage.textProperty());statusMessage.setTooltip(statusDetails);
        VBox form=new VBox(10);form.setPadding(new Insets(8));form.getStyleClass().addAll("parameter-pane","imaging-parameters");
        VBox[] sections={new VBox(5),new VBox(5),new VBox(5)};
        GridPane samplingGrid=new GridPane();samplingGrid.setHgap(6);samplingGrid.setVgap(5);
        ColumnConstraints samplingLabel=new ColumnConstraints();samplingLabel.setPercentWidth(55);
        ColumnConstraints samplingValue=new ColumnConstraints();samplingValue.setPercentWidth(45);
        samplingGrid.getColumnConstraints().addAll(samplingLabel,samplingValue);
        TextField[] samplingFields={samplingBegin,samplingStep,samplingEnd};
        String[] samplingNames={"起测点 (m)","采样间距 (mm)","停止点 (m)"};
        String[] samplingIds={"samplingBegin","samplingStep","samplingEnd"};
        for(int i=0;i<3;i++) {
            TextField field=samplingFields[i];field.setId(samplingIds[i]);field.setMinWidth(0);field.setPrefColumnCount(6);field.setMaxWidth(Double.MAX_VALUE);
            samplingGrid.add(label(samplingNames[i]),0,i);samplingGrid.add(field,1,i);
            field.textProperty().addListener((observable,oldValue,newValue)->{
                inputs=null;computeButton.setDisable(true);messages[0].setText("采样参数已更改，请重新获得输入数据");
            });
        }
        samplingStep.setTooltip(new Tooltip("大于 0，最大 2.54 mm；不能整除计算区间时，略减间距以包含两个端点，并显示实际值"));
        samplingGrid.setPadding(new Insets(6));samplingGrid.setStyle("-fx-border-color: #d8e1ea; -fx-border-radius: 4;");
        sections[0].getChildren().addAll(samplingGrid,label("间距 ≤ 2.54 mm；区间外按各电极端点延拓。"));
        for(int index=0;index<sections.length;index++) sections[index].setId("imagingStep"+(index+1));
        form.getChildren().addAll(sections[0],new Separator(),sections[1],new Separator(),sections[2]);
        Button[] buttons={loadButton,computeButton,plotButton};
        stopButton.setVisible(false);stopButton.managedProperty().bind(stopButton.visibleProperty());
        stopButton.setOnAction(e->{if(calculationControl!=null)calculationControl.stop();stopButton.setDisable(true);pauseButton.setDisable(true);setStatus("正在停止计算，正在保存已完成电极…");});
        pauseButton.setOnAction(e->{
            if(calculationControl==null)return;
            if(calculationControl.isPauseRequested()) {calculationControl.resume();pauseButton.setText("暂停计算");setStatus("正在继续计算，正在准备未完成电极…");}
            else {calculationControl.pause();pauseButton.setText("继续计算");setStatus("正在暂停；已完成电极保留，未完成电极继续时重算");}
        });
        HBox calculationButtons=new HBox(8,pauseButton,stopButton);
        calculationButtons.visibleProperty().bind(stopButton.visibleProperty());calculationButtons.managedProperty().bind(calculationButtons.visibleProperty());
        parallelism.setId("parallelism");parallelism.setPrefWidth(85);
        parallelismSettings.setId("parallelismSettings");
        parallelism.setTooltip(new Tooltip("默认使用本机可用逻辑处理器数；运行期间保持显示，结束后可调整"));
        for(int i=0;i<3;i++) {
            progress[i].setId(new String[]{"inputProgress","calculationProgress","resultProgress"}[i]);
            messages[i].setId(new String[]{"inputMessage","calculationMessage","resultMessage"}[i]);
            messages[i].setWrapText(i==1);messages[i].setAlignment(Pos.TOP_LEFT);
            messages[i].setPrefHeight(i==1?Region.USE_COMPUTED_SIZE:22);
            messages[i].setMinHeight(i==1?44:22);messages[i].setMaxHeight(i==1?Double.MAX_VALUE:22);
            messages[i].setMaxWidth(Double.MAX_VALUE);
            Tooltip details=new Tooltip();details.textProperty().bind(messages[i].textProperty());messages[i].setTooltip(details);
            buttons[i].getStyleClass().add("ghost-button");buttons[i].setMaxWidth(Double.MAX_VALUE);progress[i].setMaxWidth(Double.MAX_VALUE);
            sections[i].getChildren().addAll(buttons[i],progress[i],messages[i]);
            if(i==1)sections[i].getChildren().addAll(parallelismSettings,calculationButtons);
        }
        unfinishedBatches.setId("unfinishedBatches");unfinishedBatches.setPrefHeight(138);
        unfinishedBatches.setPlaceholder(label("暂无历史记录"));
        unfinishedBatches.setCellFactory(view->new ListCell<>() {
            @Override protected void updateItem(SimulationHistory.Entry entry,boolean empty) {
                super.updateItem(entry,empty);setText(empty||entry==null?null:entry.toString());
                setTooltip(empty||entry==null?null:new Tooltip(entry.batch().toString()));
            }
        });
        resumeBatchButton.setMaxWidth(Double.MAX_VALUE);resumeBatchButton.setDisable(true);
        resumeBatchButton.setOnAction(e->{var entry=unfinishedBatches.getSelectionModel().getSelectedItem();if(entry!=null&&entry.resumable()&&!busy)startComputation(entry.batch());});
        unfinishedBatches.getSelectionModel().selectedItemProperty().addListener((observable,previous,current)->controls());
        sections[1].getChildren().add(historyPane);
        GridPane colors=new GridPane();colors.setHgap(6);colors.setVgap(6);
        colors.getColumnConstraints().addAll(new ColumnConstraints(),new ColumnConstraints());
        TextField[] fields={amplitudeMin,amplitudeMax,phaseMin,phaseMax};
        String[] names={"模值下限 Ω·m（>0）","模值上限 Ω·m","相位下限 °","相位上限 °"};
        for(int i=0;i<4;i++) {fields[i].setPrefColumnCount(7);fields[i].setMinWidth(0);colors.add(label(names[i]),0,i);colors.add(fields[i],1,i);}
        TitledPane colorPane=new TitledPane("色标设置",new VBox(6,colors,applyColors));colorPane.setExpanded(false);
        TitledPane resultPane=new TitledPane("有关计算结果信息",new VBox(6,resultInfo,colorPane));
        resultPane.setExpanded(true);sections[2].getChildren().add(resultPane);
        errorDetails.setEditable(false);errorDetails.setWrapText(true);errorDetails.setPrefRowCount(7);
        errorPane.setExpanded(false);errorPane.setVisible(false);errorPane.managedProperty().bind(errorPane.visibleProperty());form.getChildren().add(errorPane);
        parameterScroll=new ScrollPane(form);parameterScroll.setId("parameterScroll");parameterScroll.setFitToWidth(true);parameterScroll.setMinWidth(0);VBox.setVgrow(parameterScroll,Priority.ALWAYS);parameterSlot.getChildren().setAll(parameterScroll);
        loadButton.setOnAction(e->loadInputs());computeButton.setOnAction(e->compute());plotButton.setOnAction(e->selectResult());applyColors.setOnAction(e->applyColors());
        MenuItem chooseInput=new MenuItem("选择输入目录…");chooseInput.setOnAction(e->{if(!busy)chooseInput();});
        loadButton.setContextMenu(new ContextMenu(chooseInput));
        refreshHistory();
    }
    private static Label label(String text) {Label l=new Label(text);l.setWrapText(true);l.setMinWidth(0);return l;}
    private void showWallChart(WallChartState.Snapshot snapshot) {
        if(wallSnapshot==snapshot) return;
        wallSnapshot=snapshot;
        if(wallChart!=null) wallChart.dispose();
        wallChart=null;wallViewport.reset();
        if(snapshot==null) {
            Label hint=label("请先在第一页生成井壁展开图（彩色）");
            hint.getStyleClass().add("chart-hint");wallChartBox.getChildren().setAll(hint);
        } else {
            wallChart=new WallResistivityChart(snapshot.model(),snapshot.pointA(),snapshot.pointB(),wallViewport);
            wallChartBox.getChildren().setAll(wallChart);
        }
        updateChartButtons();
    }
    private void updateChartButtons() {
        boolean empty=result==null&&wallChart==null;
        exportButton.setDisable(empty);resetButton.setDisable(empty);
    }
    private static String oneLine(String text) {return text==null?"":text.replaceAll("\\s*\\R\\s*"," · ");}
    private void setStatus(String text) {statusMessage.setText(text==null?"就绪":oneLine(text));}
    private Window window() {return appRoot.getScene()==null?null:appRoot.getScene().getWindow();}
    private void chooseInput() {
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("选择包含 Card_001～Card_192 的输入目录");
        if(Files.isDirectory(inputDirectory)) chooser.setInitialDirectory(inputDirectory.toFile());
        File selected=chooser.showDialog(window());if(selected!=null) {inputDirectory=selected.toPath();loadInputs();}
    }
    private void loadInputs() {
        if(busy) return;
        final double begin,stepMm,end;
        try {
            begin=Double.parseDouble(samplingBegin.getText().trim());stepMm=Double.parseDouble(samplingStep.getText().trim());end=Double.parseDouble(samplingEnd.getText().trim());
            if(!Double.isFinite(begin)||!Double.isFinite(end)||!Double.isFinite(stepMm)||stepMm<=0||stepMm>2.54||begin>=end)
                throw new IllegalArgumentException("起测点应小于停止点；采样间距必须大于 0 且不超过 2.54 毫米，所有参数须为有限数字");
        } catch(IllegalArgumentException ex) {error("采样参数无效："+ex.getMessage());return;}
        boolean convertedFromSlices=inputDirectory.toAbsolutePath().normalize().equals(workspaceRoot.resolve("192_NGI_Input").toAbsolutePath().normalize());
        if(!convertedFromSlices&&!Files.isDirectory(inputDirectory)) {error("输入目录不可用："+inputDirectory+"。请重新选择目录。");chooseInput();return;}
        inputs=null;computeButton.setDisable(true);
        Task<ModelInputs> task=new Task<>() {
            @Override protected ModelInputs call() throws Exception {
                Path cache=Path.of(System.getProperty("java.io.tmpdir"),"stratigraphic-input-cache");
                ModelInputs original;
                if(convertedFromSlices) {
                    updateMessage("正在从原始切片转换并校验输入…");
                    var prepared=InputPreparation.regenerate(workspaceRoot,cache,(done,message)->{updateProgress(done,384);updateMessage(message);});
                    original=prepared.inputs();
                } else original=ModelInputs.load(inputDirectory,cache,(n,s)->{updateProgress(n,192);updateMessage(s);});
                updateMessage("正在应用采样参数并校验计算快照…");updateProgress(ProgressIndicator.INDETERMINATE_PROGRESS,1);
                return SamplingPlan.prepare(original,cache,begin,stepMm,end);
            }
        };
        runTask(task,0,value->{
            inputs=value;var model=value.cards().get(0).model();
            messages[0].setText("已加载并校验 192/192 · 计算 MD："+model.begin()+"～"+model.end()+" m · 实际间距："+String.format(Locale.ROOT,"%.9g",model.step()*1000)+" mm"
                +(Math.abs(model.step()-stepMm/1000)>1e-12?"（为包含起止点已调整）":""));
            setStatus("输入数据已就绪 · 192 个电极 · 计算 MD："+model.begin()+"～"+model.end()+" m · 间距："+String.format(Locale.ROOT,"%.9g",model.step()*1000)+" mm");
        });
    }
    private void compute() {
        startComputation(null);
    }
    private void startComputation(Path batch) {
        if(busy||(batch==null&&inputs==null))return;
        ModelInputs snapshot=inputs;
        int concurrentChannels=parallelism.getValue();
        var control=new ParallelSimulationRunner.Control();calculationControl=control;
        stopButton.setDisable(false);stopButton.setVisible(true);pauseButton.setText("暂停计算");pauseButton.setDisable(false);
        Task<Computed> task=new Task<>() {
            @Override protected Computed call() throws Exception {
                updateMessage(batch==null?"正在准备电极计算…":"正在校验并恢复已保存电极…");
                Consumer<ParallelSimulationRunner.Progress> display=p->{
                    double fraction=p.fraction();
                    updateProgress(fraction==0&&!p.paused()?ProgressIndicator.INDETERMINATE_PROGRESS:fraction,1);updateMessage(p.message());
                };
                var runner=new ParallelSimulationRunner();
                var outcome=batch==null?runner.run(snapshot,workspaceRoot,workspaceRoot.resolve("output"),192,concurrentChannels,display,control):
                    runner.resume(batch,workspaceRoot,concurrentChannels,display,control);
                updateMessage("192 个电极计算完成，正在校验汇总结果…");
                return new Computed(outcome,ResponseData.read(outcome.result(),(a,b)->{}));
            }
        };
        runTask(task,1,value->{acceptComputedResult(value.data());messages[1].setText("已完成 192/192，结果已保存 · "+computedResult);messages[2].setText("结果已保存，可生成图片");setStatus("计算完成，结果已保存，可点击「生成图片」 · 文件来自："+computedResult);});
    }
    private void refreshHistory() {
        int revision=++historyRevision;
        Task<List<SimulationHistory.Entry>> scan=new Task<>() {
            @Override protected List<SimulationHistory.Entry> call()throws IOException {return SimulationHistory.all(workspaceRoot.resolve("output"));}
        };
        scan.setOnSucceeded(event->{
            if(revision!=historyRevision)return;
            history=scan.getValue();var selected=unfinishedBatches.getSelectionModel().getSelectedItem();unfinishedBatches.getItems().setAll(history);
            var retained=selected==null?null:history.stream().filter(entry->entry.batch().equals(selected.batch())).findFirst().orElse(null);
            if(retained!=null)unfinishedBatches.getSelectionModel().select(retained);else unfinishedBatches.getSelectionModel().selectFirst();
            controls();
        });
        scan.setOnFailed(event->{if(revision==historyRevision&&!busy)setStatus("历史记录读取失败："+scan.getException().getMessage());});
        Thread thread=new Thread(scan,"simulation-history");thread.setDaemon(true);thread.start();
    }
    void acceptComputedResult(ResponseData data) {
        computedResult=data.source();resultDirectory=computedResult.toAbsolutePath().getParent();resultInfo.setText(data.resultSummary());
        if(amplitudeChart!=null)amplitudeChart.dispose();if(phaseChart!=null)phaseChart.dispose();
        amplitudeChart=null;phaseChart=null;result=null;
        amplitudeBox.getChildren().clear();phaseBox.getChildren().clear();
        updateChartButtons();applyColors.setDisable(true);
    }
    private void selectResult() {
        if(busy) return;
        FileChooser chooser=new FileChooser();chooser.setTitle("选择计算结果文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("计算结果 TXT","*.txt"));
        if(Files.isDirectory(resultDirectory))chooser.setInitialDirectory(resultDirectory.toFile());
        File file=resultFilePicker.apply(chooser,window());if(file==null)return;
        Path selectedResult=file.toPath();
        Task<ResponseData> task=new Task<>() {
            @Override protected ResponseData call() throws Exception {
                updateMessage("读取并校验："+selectedResult.getFileName());
                ResponseData data=ResponseData.read(selectedResult,(done,total)->{updateProgress(done,total);updateMessage("已读取 "+done+" / "+total+" 字节，校验深度与电极");});
                updateMessage("结果校验通过，正在生成模值图和相位图…");return data;
            }
        };
        runTask(task,2,this::showResult);
    }
    // Package-visible for integration tests with real validated files.
    void showResult(ResponseData value) {
        resultDirectory=value.source().toAbsolutePath().getParent();
        double amin=value.minPositive(),amax=value.max(false);
        if(!Double.isFinite(amin)) {amin=1;amax=10;}
        if(amax<=amin) {amin/=Math.sqrt(10);amax*=Math.sqrt(10);}
        double pmin=value.min(true),pmax=value.max(true);if(pmax<=pmin) {pmin-=1;pmax+=1;}
        if(amplitudeChart!=null) amplitudeChart.dispose();if(phaseChart!=null) phaseChart.dispose();
        amplitudeViewport.reset();phaseViewport.reset();
        ResponseChart a=new ResponseChart(value,false,amin,amax,amplitudeViewport);
        ResponseChart p=new ResponseChart(value,true,pmin,pmax,phaseViewport);
        result=value;amplitudeChart=a;phaseChart=p;
        amplitudeBox.getChildren().setAll(a);phaseBox.getChildren().setAll(p);
        amplitudeMin.setText(""+amin);amplitudeMax.setText(""+amax);phaseMin.setText(""+pmin);phaseMax.setText(""+pmax);
        resultInfo.setText(value.resultSummary());messages[2].setText("已生成本文件全部 192 个电极的图片 · "+(long)value.size()*192+" 条记录");
        if(!progress[2].progressProperty().isBound()) progress[2].setProgress(1);
        setStatus("图片已生成 · 文件来自："+value.source());
        exportButton.setDisable(false);resetButton.setDisable(false);applyColors.setDisable(false);
    }
    private void applyColors() {
        if(result==null) return;
        try {
            double a=Double.parseDouble(amplitudeMin.getText()),b=Double.parseDouble(amplitudeMax.getText()),c=Double.parseDouble(phaseMin.getText()),d=Double.parseDouble(phaseMax.getText());
            if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(c)||!Double.isFinite(d)||a<=0||b<=a||d<=c) throw new IllegalArgumentException("色标需要有限数值，模值下限 >0，且各上限大于下限");
            amplitudeChart.setRange(a,b);phaseChart.setRange(c,d);
            setStatus("色标已更新 · 文件来自："+result.source());
        } catch(IllegalArgumentException ex) {error("色标错误："+ex.getMessage());}
    }
    private <T> void runTask(Task<T> task,int index,Consumer<T> success) {
        double scrollPosition=parameterScroll.getVvalue(),horizontalPosition=parameterScroll.getHvalue();
        // Move focus outside the scroll content before disabling its focused button.
        if(index==0)appRoot.requestFocus();
        busy=true;controls();progress[index].progressProperty().bind(task.progressProperty());
        messages[index].textProperty().bind(Bindings.createStringBinding(()->index==1?task.getMessage():oneLine(task.getMessage()),task.messageProperty()));
        String operation=new String[]{"正在获得输入数据","正在计算输出数据","正在读取结果并生成图片"}[index];
        ChangeListener<String> activity=(observable,previous,current)->{
            if(current!=null&&!current.isBlank()) {
                if(index==1&&calculationControl!=null&&calculationControl.isStopped())setStatus("正在停止计算，正在保存已完成电极…");
                else if(index==1&&current.startsWith("计算已暂停"))setStatus(current);
                else setStatus(operation+" · "+current);
            }
        };
        task.messageProperty().addListener(activity);setStatus(operation+"…");
        if(index==0) {
            appRoot.applyCss();appRoot.layout();parameterScroll.setVvalue(scrollPosition);parameterScroll.setHvalue(horizontalPosition);
            // ScrollPane defers focus-reveal scrolling; restore after that queued action too.
            Platform.runLater(()->{parameterScroll.setVvalue(scrollPosition);parameterScroll.setHvalue(horizontalPosition);});
        }
        task.setOnSucceeded(e->{task.messageProperty().removeListener(activity);unbind(index);progress[index].setProgress(1);busy=false;finishCompute(index);try {success.accept(task.getValue());} catch(RuntimeException ex){error(ex.getMessage());}controls();});
        task.setOnFailed(e->{
            task.messageProperty().removeListener(activity);
            unbind(index);if(progress[index].getProgress()<0)progress[index].setProgress(0);busy=false;finishCompute(index);
            if(index==1&&task.getException() instanceof SimulationRunner.StoppedException) {
                messages[index].setText("计算已停止，已完成电极已保存，可从历史记录选择继续");setStatus(task.getException().getMessage());
            } else {messages[index].setText(firstLine(task.getException().getMessage())+"（见错误详情）");error(task.getException().getMessage());}
            controls();
        });
        Thread thread=new Thread(task,"imaging-task");thread.setDaemon(true);thread.start();
    }
    private void unbind(int index) {progress[index].progressProperty().unbind();messages[index].textProperty().unbind();}
    private void finishCompute(int index) {if(index==1){stopButton.setVisible(false);calculationControl=null;refreshHistory();}}
    private void controls() {
        samplingBegin.setDisable(busy);samplingStep.setDisable(busy);samplingEnd.setDisable(busy);
        loadButton.setDisable(busy);computeButton.setDisable(busy||inputs==null);plotButton.setDisable(busy);parallelism.setDisable(busy);applyColors.setDisable(busy||result==null);
        historyPane.setDisable(busy);
        var selected=unfinishedBatches.getSelectionModel().getSelectedItem();
        resumeBatchButton.setDisable(busy||selected==null||!selected.resumable());
    }
    private static String firstLine(String message) {return message==null?"未知错误":message.lines().findFirst().orElse("未知错误");}
    private void error(String message) {setStatus(firstLine(message)+" · 详细信息见左侧「错误详情」");errorDetails.setText(message);errorPane.setVisible(true);}
    @FXML private void resetView() {
        amplitudeViewport.reset();phaseViewport.reset();wallViewport.reset();
        if(result!=null||wallChart!=null) setStatus("视图已重置"+(result==null?"":" · 文件来自："+result.source()));
    }
    @FXML private void exportPng() {
        if(result==null&&wallChart==null) return;
        FileChooser chooser=new FileChooser();chooser.setTitle("导出当前结果页面 PNG");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG","*.png"));
        File file=chooser.showSaveDialog(window());if(file==null) return;
        if(!file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) file=new File(file+".png");
        try {SnapshotParameters p=new SnapshotParameters();p.setFill(Color.WHITE);ImageIO.write(SwingFXUtils.fromFXImage(appRoot.snapshot(p,null),null),"png",file);setStatus("PNG 已导出："+file);}
        catch(IOException ex) {error("导出失败："+ex.getMessage());}
    }
    @FXML private void showMainView() {
        try {PageNavigation.show(appRoot,"wall-unfold-view.fxml","main-view.fxml","地层剖面与井壁展开图");}
        catch(IOException|IllegalStateException ex) {error(ex.getMessage());}
    }
}
