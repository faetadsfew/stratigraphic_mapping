package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.SectionChart;
import com.stratigraphic.mapping.chart.SectionColorChart;
import com.stratigraphic.mapping.chart.ThreeDChart;
import com.stratigraphic.mapping.chart.WallLineUnfoldChart;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.reader.StratumModelReader;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
import java.util.Locale;

/**
 * 主窗口：左侧统一参数，右侧依次为三维图、彩色地层剖面和彩色井壁展开图。
 *
 * <p>右侧只使用当前地层模型、统一倾角和左侧输入的两个展开点 A/B，
 * 不读取模值、相位或其他额外井壁测量数据。
 */
public final class MainController {

    @FXML
    private BorderPane appRoot;
    @FXML
    private VBox parameterSlot;
    @FXML
    private VBox threeDChartSlot;
    @FXML
    private VBox colorChartSlot;
    @FXML
    private VBox wallChartSlot;

    @FXML
    private Label statusDepth;
    @FXML
    private Label statusLayers;
    @FXML
    private Label statusWall;
    @FXML
    private Label statusZoom;
    @FXML
    private Label statusMessage;

    private final ParameterPane parameterPane = new ParameterPane();

    private final StackPane colorChartBox = new StackPane();
    private final StackPane wallChartBox = new StackPane();
    private final StackPane threeDChartBox = new StackPane();

    private final Label colorChartHint = new Label(
            "载入参数或直接填写左侧数据后，点击「生成图表」显示彩色地层剖面图。");
    private final Label wallChartHint = new Label(
            "填写展开点 A/B 后点击「生成图表」，显示两点之间的彩色井壁展开图。");

    private final FileChooser fileChooser = new FileChooser();

    private FitPane colorFitPane;
    private FitPane wallFitPane;
    private ThreeDChart threeDChart;
    private WallChartState.Snapshot wallSnapshot;

    @FXML
    private void initialize() {

        appRoot.sceneProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                WallChartState.in(current).set(wallSnapshot);
            }
        });

        parameterPane.setMinSize(0, 0);
        parameterSlot.setMinSize(0, 0);
        parameterSlot.getChildren().setAll(parameterPane);

        threeDChartSlot.setMinSize(0, 0);
        threeDChartSlot.getStyleClass().add("chart-slot");
        prepareChartBox(threeDChartBox);
        VBox threeDWrapper = wrapWithHeader("三维图", "3D", threeDChartBox);
        VBox.setVgrow(threeDWrapper, Priority.ALWAYS);
        threeDChartSlot.getChildren().setAll(threeDWrapper);

        colorChartSlot.setMinSize(0, 0);
        colorChartSlot.getStyleClass().add("chart-slot");
        prepareChartBox(colorChartBox);
        colorChartSlot.getChildren().setAll(
                wrapWithHeader("岩性剖面", "COLOR", colorChartBox));

        wallChartSlot.setMinSize(0, 0);
        wallChartSlot.getStyleClass().add("chart-slot");
        prepareChartBox(wallChartBox);
        wallChartSlot.getChildren().setAll(
                wrapWithHeader("井壁展开图（彩色）", "UNFOLD", wallChartBox));

        parameterPane.setOnOpenFile(this::openFile);
        parameterPane.setOnConfirmData(this::generateCharts);
        parameterPane.setOnDataChanged(this::refreshStatus);

        showColorChartHint();
        showWallChartHint();
        showThreeDChartHint();
    }

    private void prepareChartBox(StackPane box) {
        box.setMinSize(0, 0);
        box.getStyleClass().add("chart-box");
        VBox.setVgrow(box, Priority.ALWAYS);
    }

    private VBox wrapWithHeader(String title, String tag, StackPane chartBox) {

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("ch-title");

        Label tagLabel = new Label(tag);
        tagLabel.getStyleClass().add("ch-tag");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, titleLabel, spacer, tagLabel);
        header.getStyleClass().add("chart-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(header, chartBox);
        VBox.setVgrow(chartBox, Priority.ALWAYS);
        return box;
    }

    @FXML
    private void openWallResistivityView() {

        try {
            PageNavigation.show(appRoot,
                    "main-view.fxml",
                    "wall-unfold-view.fxml",
                    "电成像响应模拟");
        } catch (IOException | IllegalStateException ex) {
            error("打开电成像响应模拟失败：" + ex.getMessage());
        }
    }

    @FXML
    private void openFile() {

        fileChooser.setTitle("选择地层剖面参数文件");
        fileChooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));

        if (fileChooser.getInitialDirectory() == null) {
            File workingDirectory = new File(System.getProperty("user.dir"));
            if (workingDirectory.isDirectory()) {
                fileChooser.setInitialDirectory(workingDirectory);
            }
        }

        File file = fileChooser.showOpenDialog(window());
        if (file == null) {
            return;
        }

        try {
            StratumModel model = StratumModelReader.read(Path.of(file.getAbsolutePath()));
            parameterPane.setModel(model,
                    file.getName() + "    " + file.getAbsolutePath());
            updateStatus(model);

            // 载入只更新输入，不自动出图；等待用户点击「生成图表」。
            showColorChartHint();
            showWallChartHint();
            showThreeDChartHint();
            statusZoom.setText("缩放 1.00×");
            info("已载入地层参数，请检查输入后点击「生成图表」");
        } catch (IOException ex) {
            error("读取参数文件失败：" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error("参数文件有问题：" + ex.getMessage());
        }
    }

    private void generateCharts() {

        StratumModel model;
        try {
            model = parameterPane.toModel();
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
            return;
        }

        drawColorChart(model);
        updateStatus(model);

        boolean wallOk = true;
        try {
            drawWallChart(model);
        } catch (IllegalArgumentException ex) {
            wallOk = false;
            showWallChartHint();
            error("彩色剖面已更新；井壁展开图未生成：" + ex.getMessage());
        }

        try {
            drawThreeDChart(model);
            if (wallOk) {
                info("三维图、彩色剖面和彩色井壁展开图已按当前参数更新");
            }
        } catch (RuntimeException ex) {
            showThreeDChartHint();
            error("三维图未生成：" + ex.getMessage());
        }
    }

    private void drawWallChart(StratumModel model) {

        ParameterPane.UnfoldInterval interval = parameterPane.toUnfoldInterval();
        WallLineUnfoldChart chart = new WallLineUnfoldChart(
                model, interval.start(), interval.end());
        chart.setOnFractureSelected(parameterPane::revealFracture);
        chart.setOnSphereSelected(parameterPane::revealSphere);
        chart.setOnWedgeSelected(parameterPane::revealWedge);
        chart.setOnCubeSelected(parameterPane::revealCube);
        chart.setOnTextSelected(parameterPane::revealText);
        chart.setOnInterfaceSelected(parameterPane::revealInterface);
        chart.setOnLayerSelected(parameterPane::revealLayer);
        wallFitPane = installChart(chart, wallChartBox, null);
        publishWallChart(new WallChartState.Snapshot(model, chart.pointA(), chart.pointB()));

        statusWall.setText(String.format(Locale.ROOT,
                "展开 %.2f – %.2f m · 彩色", interval.start(), interval.end()));
    }

    private void drawColorChart(StratumModel model) {
        SectionColorChart chart = new SectionColorChart(model);
        chart.setOnFractureSelected(parameterPane::revealFracture);
        chart.setOnInterfaceSelected(parameterPane::revealInterface);
        chart.setOnLayerSelected(parameterPane::revealLayer);
        colorFitPane = installChart(
                chart,
                colorChartBox,
                new ColorBarPane(
                        model.minSectionResistivity(), model.maxSectionResistivity()));
    }

    private void drawThreeDChart(StratumModel model) {
        ParameterPane.UnfoldInterval interval = parameterPane.toUnfoldInterval();
        ThreeDChart chart = new ThreeDChart(model, interval.start(), interval.end());
        chart.setOnFractureSelected(parameterPane::revealFracture);
        chart.setOnSphereSelected(parameterPane::revealSphere);
        chart.setOnWedgeSelected(parameterPane::revealWedge);
        chart.setOnCubeSelected(parameterPane::revealCube);
        chart.setOnLayerSelected(parameterPane::revealLayer);
        if (threeDChart != null) {
            threeDChart.stopAnimation();
        }
        threeDChart = chart;
        threeDChartBox.getChildren().setAll(chart);
    }

    /** 把图装进可缩放面板；右侧可挂不参与缩放的色标。 */
    private FitPane installChart(Pane chart, StackPane chartBox, ColorBarPane colorBar) {

        FitPane fit = new FitPane(chart);
        fit.setMinSize(0, 0);

        if (colorBar != null) {
            fit.setRightInset(ColorBarPane.WIDTH);
            if (chart instanceof SectionChart sectionChart) {
                fit.setRightAccessory(colorBar,
                        sectionChart.figureTop(), sectionChart.figureHeight());
            }
        }

        Button reset = new Button("重置");
        reset.getStyleClass().add("overlay-button");
        reset.setOnAction(e -> fit.resetZoom());
        reset.visibleProperty().bind(fit.zoomFactorProperty().greaterThan(1.001));

        StackPane wrapper = new StackPane(fit, reset);
        StackPane.setAlignment(reset, Pos.TOP_LEFT);
        StackPane.setMargin(reset, new Insets(10));
        chartBox.getChildren().setAll(wrapper);

        fit.zoomFactorProperty().addListener((o, oldValue, newValue) ->
                statusZoom.setText(String.format(Locale.ROOT,
                        "缩放 %.2f×", newValue.doubleValue())));
        statusZoom.setText("缩放 1.00×");
        return fit;
    }

    @FXML
    private void resetView() {

        if (threeDChart != null) {
            threeDChart.resetView();
        }
        if (colorFitPane != null) {
            colorFitPane.resetZoom();
        }
        if (wallFitPane != null) {
            wallFitPane.resetZoom();
        }
        statusZoom.setText("缩放 1.00×");
    }

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
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            file = new File(file.getAbsolutePath() + ".png");
        }

        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            params.setDepthBuffer(true);
            WritableImage image = appRoot.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            info("已导出 " + file.getName());
        } catch (IOException ex) {
            error("导出失败：" + ex.getMessage());
        }
    }

    private void updateStatus(StratumModel model) {
        statusDepth.setText(String.format(Locale.ROOT,
                "深度 %.2f – %.2f m", model.minDepth(), model.maxDepth()));
        statusLayers.setText(String.format(Locale.ROOT,
                "%d 界面 · %d 层 · 倾角 %.1f°",
                model.interfaceCount(), model.layerCount(), model.dipAngle()));
    }

    private void refreshStatus() {
        StratumModel model = parameterPane.tryToModel();
        if (model != null) {
            updateStatus(model);
        }
    }

    private void showColorChartHint() {
        showHint(colorChartBox, colorChartHint);
        colorFitPane = null;
    }

    private void showThreeDChartHint() {
        if (threeDChart != null) {
            threeDChart.stopAnimation();
            threeDChart = null;
        }
        Label hint = new Label(
                "点击「生成图表」查看三维地层模型\n拖动旋转 · 滚轮缩放 · 双击复位");
        hint.setWrapText(true);
        hint.setAlignment(Pos.CENTER);
        hint.getStyleClass().add("three-d-hint");
        StackPane placeholder = new StackPane(hint);
        placeholder.getStyleClass().add("three-d-placeholder");
        threeDChartBox.getChildren().setAll(placeholder);
    }

    private void showWallChartHint() {
        showHint(wallChartBox, wallChartHint);
        wallFitPane = null;
        publishWallChart(null);
        statusWall.setText("展开 A/B —");
    }

    private void publishWallChart(WallChartState.Snapshot snapshot) {
        wallSnapshot = snapshot;
        if (appRoot.getScene() != null) {
            WallChartState.in(appRoot.getScene()).set(snapshot);
        }
    }

    private static void showHint(StackPane box, Label hint) {

        hint.setWrapText(true);
        hint.setMaxWidth(360);
        hint.setAlignment(Pos.CENTER);

        if (!hint.getStyleClass().contains("chart-hint")) {
            hint.getStyleClass().add("chart-hint");
        }
        box.getChildren().setAll(hint);
    }

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
