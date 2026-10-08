package com.example.demo.ui;

import com.example.demo.chart.SectionChart;
import com.example.demo.chart.SectionColorChart;
import com.example.demo.chart.SectionLineChart;
import com.example.demo.io.StratumModelReader;
import com.example.demo.model.StratumModel;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 主窗口装配：左边 30% 参数面板，右边 70% 并排两张图，下面一条通栏横条；
 * 最上面是工具栏，最下面是状态栏。所有样式都在 app.css 里。
 *
 * <p>出图分两步：
 * <ol>
 *   <li>左侧面板底部的「确认数据」——把面板里当前填的（增删改过的）数据画成中间线条图；</li>
 *   <li>横条左边的「确认」——再画出右边的完整彩色地层剖面图。</li>
 * </ol>
 *
 * <p>两张图都支持滚轮缩放、按住拖动平移，放大后右上角会出现「重置」浮层按钮。
 *
 * <p>启动时不预读任何数据，左侧是空白表单，点「打开参数文件…」或直接填数都可以。
 */
public final class MainController {

    /** 视图模式：并排 / 只看线框 / 只看剖面。 */
    private enum ViewMode {
        SPLIT, LINE, COLOR
    }

    @FXML
    private BorderPane appRoot;
    @FXML
    private VBox parameterSlot;
    @FXML
    private VBox lineChartSlot;
    @FXML
    private VBox colorChartSlot;
    @FXML
    private GridPane mainGrid;
    @FXML
    private GridPane bottomBar;
    @FXML
    private HBox confirmCell;
    @FXML
    private HBox spareCell;

    @FXML
    private Button splitViewButton;
    @FXML
    private Button lineViewButton;
    @FXML
    private Button colorViewButton;

    @FXML
    private Label statusDepth;
    @FXML
    private Label statusLayers;
    @FXML
    private Label statusZoom;
    @FXML
    private Label statusMessage;

    private final ParameterPane parameterPane = new ParameterPane();
    private final Button confirmButton = new Button("确认");

    private final StackPane lineChartBox = new StackPane();
    private final StackPane colorChartBox = new StackPane();

    private final Label lineChartHint = new Label("点左侧「确认数据」后，在此显示地层界面线条图");
    private final Label colorChartHint = new Label("编辑左侧参数后，点击「确认」在此显示完整地层剖面图");

    /** 线条图有没有经过一次「确认数据」；没确认过就只显示提示。 */
    private boolean lineChartConfirmed;

    private final FileChooser fileChooser = new FileChooser();

    /** 两张图当前的可缩放面板，重置视图时要一起还原。 */
    private FitPane lineFitPane;
    private FitPane colorFitPane;

    @FXML
    private void initialize() {

        // ---- 左 30%：参数面板 ----
        parameterPane.setMinSize(0, 0);
        parameterSlot.setMinSize(0, 0);
        parameterSlot.getChildren().add(parameterPane);

        // ---- 右 70% 左半：线条图 ----
        lineChartSlot.setMinSize(0, 0);
        lineChartSlot.getStyleClass().add("line-chart-slot");
        prepareChartBox(lineChartBox);
        lineChartSlot.getChildren().add(
                wrapWithHeader("地层界面（线框）", "GEOMETRY", lineChartBox));

        // ---- 右 70% 右半：完整剖面图，确认后才画 ----
        colorChartSlot.setMinSize(0, 0);
        colorChartSlot.getStyleClass().add("chart-slot");
        prepareChartBox(colorChartBox);
        colorChartSlot.getChildren().add(
                wrapWithHeader("岩性剖面", "COLOR", colorChartBox));

        buildBottomBar();
        showLineChartHint();
        showColorChartHint();

        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> confirm());

        parameterPane.setOnOpenFile(this::openFile);
        parameterPane.setOnConfirmData(this::confirmData);
        parameterPane.setOnDataChanged(this::refreshStatus);

        // 不预读任何文件：这里就是空白状态，等用户打开参数文件或手工填
        applyViewMode(ViewMode.SPLIT);
    }

    private void prepareChartBox(StackPane box) {
        box.setMinSize(0, 0);
        box.getStyleClass().add("chart-box");
        VBox.setVgrow(box, Priority.ALWAYS);
    }

    /** 给一张图套上固定的标题栏；标题栏不参与缩放，永远贴在顶上。 */
    private VBox wrapWithHeader(String title, String tag, StackPane chartBox) {

        Label t = new Label(title);
        t.getStyleClass().add("ch-title");

        Label g = new Label(tag);
        g.getStyleClass().add("ch-tag");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, t, spacer, g);
        header.getStyleClass().add("chart-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(header, chartBox);
        VBox.setVgrow(chartBox, Priority.ALWAYS);
        return box;
    }

    /**
     * 底部通栏横条：左边放「确认」按钮，右半边留空，以后要加别的东西放那儿。
     * 横条高度由 FXML 里那一行的 RowConstraints 固定，两张图的下边因此严格对齐。
     */
    private void buildBottomBar() {

        bottomBar.getStyleClass().add("chart-footer");

        confirmButton.getStyleClass().add("primary-button");
        confirmButton.setPrefWidth(120);

        Label hint = new Label("滚轮缩放 · 按住拖动平移");
        hint.getStyleClass().add("hint");

        VBox left = new VBox(4, confirmButton, hint);
        left.setAlignment(Pos.CENTER);

        confirmCell.setAlignment(Pos.CENTER);
        confirmCell.getChildren().setAll(left);

        // 右半边是预留位，先不放东西
        spareCell.setAlignment(Pos.CENTER);
    }

    // =========================================================
    // 视图切换
    // =========================================================

    @FXML
    private void showSplitView() {
        applyViewMode(ViewMode.SPLIT);
    }

    @FXML
    private void showLineView() {
        applyViewMode(ViewMode.LINE);
    }

    @FXML
    private void showColorView() {
        applyViewMode(ViewMode.COLOR);
    }

    /** 并排显示两张图，或者让其中一张占满右侧区域。 */
    private void applyViewMode(ViewMode mode) {

        boolean showLine = mode != ViewMode.COLOR;
        boolean showColor = mode != ViewMode.LINE;

        lineChartSlot.setVisible(showLine);
        lineChartSlot.setManaged(showLine);
        colorChartSlot.setVisible(showColor);
        colorChartSlot.setManaged(showColor);

        List<ColumnConstraints> columns = mainGrid.getColumnConstraints();
        columns.get(1).setPercentWidth(showLine ? (showColor ? 35 : 70) : 0);
        columns.get(2).setPercentWidth(showColor ? (showLine ? 35 : 70) : 0);

        GridPane.setColumnIndex(lineChartSlot, 1);
        GridPane.setColumnIndex(colorChartSlot, showLine ? 2 : 1);
        GridPane.setColumnSpan(lineChartSlot, showColor ? 1 : 2);
        GridPane.setColumnSpan(colorChartSlot, showLine ? 1 : 2);

        markActive(splitViewButton, mode == ViewMode.SPLIT);
        markActive(lineViewButton, mode == ViewMode.LINE);
        markActive(colorViewButton, mode == ViewMode.COLOR);
    }

    private static void markActive(Button button, boolean active) {
        button.getStyleClass().remove("active");
        if (active) {
            button.getStyleClass().add("active");
        }
    }

    // =========================================================
    // 数据来源
    // =========================================================

    @FXML
    private void openFile() {

        fileChooser.setTitle("选择参数文件");
        fileChooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));

        if (fileChooser.getInitialDirectory() == null) {
            File workingDir = new File(System.getProperty("user.dir"));
            if (workingDir.isDirectory()) {
                fileChooser.setInitialDirectory(workingDir);
            }
        }

        File file = fileChooser.showOpenDialog(window());
        if (file == null) {
            return;
        }

        try {
            showModel(StratumModelReader.read(Path.of(file.getAbsolutePath())),
                    file.getName() + "    " + file.getAbsolutePath());
        } catch (IOException ex) {
            error("读取文件失败：" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error("参数文件有问题：" + ex.getMessage());
        }
    }

    /** 把当前窗口截一张 PNG 存下来。 */
    @FXML
    private void exportPng() {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出 PNG");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("PNG 图片 (*.png)", "*.png"));

        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        if (!file.getName().toLowerCase().endsWith(".png")) {
            file = new File(file.getAbsolutePath() + ".png");
        }

        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            WritableImage image = appRoot.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            info("已导出 " + file.getName());
        } catch (IOException ex) {
            error("导出失败：" + ex.getMessage());
        }
    }

    /** 载入一份新参数：刷新左侧面板与线条图，并把右侧彩图清空等确认。 */
    private void showModel(StratumModel model, String source) {

        parameterPane.setModel(model, source);
        info("已载入参数，点「确认数据」显示线条图");

        updateStatus(model);

        // 数据确认之前，两张图都只显示提示
        lineChartConfirmed = false;
        showLineChartHint();
        showColorChartHint();
    }

    // =========================================================
    // 第一步：确认数据 → 中间的线条图
    // =========================================================

    /** 左侧「确认数据」：按面板里当前的数据（含增删的界面）重画线条图。 */
    private void confirmData() {

        StratumModel model;
        try {
            model = parameterPane.toModel();
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
            return;
        }

        lineChartConfirmed = true;
        drawLineChart(model);
        updateStatus(model);
        info("线条图已按当前数据更新");
    }

    // =========================================================
    // 第二步：确认 → 右侧的完整剖面图
    // =========================================================

    private void confirm() {

        StratumModel model;
        try {
            model = parameterPane.toModel();
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
            return;
        }

        // 线条图确认过才跟着一起刷新，避免两张图对不上；没确认过就还是提示
        if (lineChartConfirmed) {
            drawLineChart(model);
        }
        drawColorChart(model);

        updateStatus(model);

        info("已按当前参数出图");
    }

    private void drawLineChart(StratumModel model) {
        // 右侧同样留出「色标」那一列，两张图的可用区一样大，图面才会等大
        lineFitPane = installChart(new SectionLineChart(model), lineChartBox, null);
    }

    private void drawColorChart(StratumModel model) {
        colorFitPane = installChart(new SectionColorChart(model), colorChartBox,
                new ColorBarPane(model.maxResistivity()));
    }

    /**
     * 把一张图装进可缩放的面板：右侧留出固定一列，可以在里面挂一条不缩放的色标，
     * 右上角再挂一个「重置」浮层按钮。
     */
    private FitPane installChart(SectionChart chart, StackPane chartBox, ColorBarPane colorBar) {

        FitPane fit = new FitPane(chart);
        fit.setRightInset(ColorBarPane.WIDTH);

        if (colorBar != null) {
            fit.setRightAccessory(colorBar, chart.figureTop(), chart.figureHeight());
        }

        Button reset = new Button("重置");
        reset.getStyleClass().add("overlay-button");
        reset.setOnAction(e -> fit.resetZoom());
        reset.visibleProperty().bind(fit.zoomFactorProperty().greaterThan(1.001));

        StackPane wrapper = new StackPane(fit, reset);
        // 靠左上角摆，免得压住右侧固定列里的色标标题
        StackPane.setAlignment(reset, Pos.TOP_LEFT);
        StackPane.setMargin(reset, new Insets(10));
        chartBox.getChildren().setAll(wrapper);

        fit.zoomFactorProperty().addListener((o, ov, nv) ->
                statusZoom.setText(String.format("缩放 %.2f×", nv.doubleValue())));

        statusZoom.setText(String.format("缩放 %.2f×", 1.0));
        return fit;
    }

    /** 工具栏「重置视图」：两张图都回到刚放得下的比例。 */
    @FXML
    private void resetView() {

        if (lineFitPane != null) {
            lineFitPane.resetZoom();
        }
        if (colorFitPane != null) {
            colorFitPane.resetZoom();
        }
        statusZoom.setText(String.format("缩放 %.2f×", 1.0));
    }

    // =========================================================
    // 底部状态栏
    // =========================================================

    /** 深度范围 / 层数 / 界面个数，只要数据变了就整块刷新。 */
    private void updateStatus(StratumModel model) {

        statusDepth.setText(String.format("深度 %.2f – %.2f m", model.minDepth(), model.maxDepth()));
        statusLayers.setText(model.interfaceCount() + " 界面 · " + model.layerCount() + " 层");
    }

    /**
     * 左侧面板里任何一个数或条数变了都会走到这里：
     * 静默重算一次状态栏，数据还没填完整就保持上一次的读数。
     */
    private void refreshStatus() {

        StratumModel model = parameterPane.tryToModel();
        if (model != null) {
            updateStatus(model);
        }
    }

    /** 线条图还没出图时，格子里放一句提示。 */
    private void showLineChartHint() {
        showHint(lineChartBox, lineChartHint);
        lineFitPane = null;
    }

    private void showColorChartHint() {
        showHint(colorChartBox, colorChartHint);
        colorFitPane = null;
    }

    /** 把提示文字放进格子；样式类只加一次，反复调用不会重复堆叠。 */
    private static void showHint(StackPane box, Label hint) {

        hint.setWrapText(true);
        hint.setMaxWidth(320);
        hint.setAlignment(Pos.CENTER);

        if (!hint.getStyleClass().contains("chart-hint")) {
            hint.getStyleClass().add("chart-hint");
        }
        box.getChildren().setAll(hint);
    }

    // =========================================================
    // 提示信息：面板提示和状态栏一起更新
    // =========================================================

    private void info(String message) {
        parameterPane.showInfo(message);
        statusMessage.setText(message);
    }

    private void error(String message) {
        parameterPane.showError(message);
        statusMessage.setText(message);
    }

    private javafx.stage.Window window() {
        return appRoot.getScene() == null ? null : appRoot.getScene().getWindow();
    }
}
