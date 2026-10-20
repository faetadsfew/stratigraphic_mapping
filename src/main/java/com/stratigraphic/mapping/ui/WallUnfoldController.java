package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.WallAmplitudeScale;
import com.stratigraphic.mapping.chart.WallUnfoldChart;
import com.stratigraphic.mapping.reader.WallDataReader;
import com.stratigraphic.mapping.wall.WallData;
import com.stratigraphic.mapping.wall.WallGeometry;
import com.stratigraphic.mapping.wall.WallParameters;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 井壁展开页面控制器：读取 Excel，按三维几何模型生成井壁模值展开图。
 */
public final class WallUnfoldController {

    @FXML
    private BorderPane appRoot;
    @FXML
    private VBox parameterSlot;
    @FXML
    private StackPane chartBox;

    @FXML
    private Label statusData;
    @FXML
    private Label statusRange;
    @FXML
    private Label statusZoom;
    @FXML
    private Label statusMessage;

    private final WallParameterPane parameterPane = new WallParameterPane();
    private final FileChooser fileChooser = new FileChooser();
    private final Label chartHint = new Label(
            "请先打开包含「模值」和「相位」两个 Sheet 的 Excel 文件，"
                    + "检查参数后生成井壁展开图。");

    private WallData currentData;
    private FitPane fitPane;

    @FXML
    private void initialize() {

        parameterSlot.setMinSize(0, 0);
        parameterSlot.getChildren().setAll(parameterPane);

        chartBox.setMinSize(0, 0);
        showChartHint();

        parameterPane.setOnOpenFile(this::openExcel);
        parameterPane.setOnGenerate(this::generateChart);

        fileChooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Excel 工作簿 (*.xlsx, *.xls)",
                        "*.xlsx", "*.xls"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
    }

    @FXML
    private void openExcel() {

        fileChooser.setTitle("选择 Excel 数据文件");
        if (fileChooser.getInitialDirectory() == null) {
            File workingDirectory = new File(System.getProperty("user.dir"));
            if (workingDirectory.isDirectory()) {
                fileChooser.setInitialDirectory(workingDirectory);
            }
        }

        File file = fileChooser.showOpenDialog(
                appRoot.getScene() == null ? null : appRoot.getScene().getWindow());
        if (file == null) {
            return;
        }

        try {
            WallData data = WallDataReader.read(Path.of(file.getAbsolutePath()));
            currentData = data;
            fitPane = null;
            parameterPane.setData(data, file.getName() + "    " + file.getAbsolutePath());
            statusData.setText(String.format(Locale.ROOT,
                    "数据：%d 行 · %.5f～%.5f m",
                    data.rowCount(), data.minDepth(), data.maxDepth()));
            statusRange.setText("取井区域：待生成");
            statusZoom.setText("缩放 1.00×");
            showChartHint();
            info("Excel 检查通过，已载入 " + data.rowCount() + " 行数据");
        } catch (IOException ex) {
            error("读取 Excel 失败：" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error("Excel 数据不符合要求：" + ex.getMessage());
        }
    }

    @FXML
    private void generateChart() {

        if (currentData == null) {
            error("请先打开 Excel 数据文件");
            return;
        }

        try {
            WallParameters parameters = parameterPane.toParameters();
            WallGeometry geometry = new WallGeometry(currentData, parameters);
            WallParameterPane.ColorRange colorRange = parameterPane.colorRange();
            WallAmplitudeScale scale = new WallAmplitudeScale(
                    colorRange.min(), colorRange.max());
            WallUnfoldChart chart = new WallUnfoldChart(geometry, scale);
            chart.setOnDataSelected(statusMessage::setText);

            installChart(chart);

            statusRange.setText(String.format(Locale.ROOT,
                    "实际 MD %.5f～%.5f m · 椭圆周长 %.4f m",
                    geometry.requiredMinMd(),
                    geometry.requiredMaxMd(),
                    geometry.ellipsePerimeter()));
            info("井壁展开图已按当前几何参数生成");
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
        }
    }

    private void installChart(WallUnfoldChart chart) {

        FitPane fit = new FitPane(chart);
        fit.setMinSize(0, 0);

        Button reset = new Button("重置");
        reset.getStyleClass().add("overlay-button");
        reset.setOnAction(event -> fit.resetZoom());
        reset.visibleProperty().bind(fit.zoomFactorProperty().greaterThan(1.001));

        StackPane wrapper = new StackPane(fit, reset);
        StackPane.setAlignment(reset, Pos.TOP_LEFT);
        StackPane.setMargin(reset, new Insets(10));
        chartBox.getChildren().setAll(wrapper);

        fit.zoomFactorProperty().addListener((observable, oldValue, newValue) ->
                statusZoom.setText(String.format(
                        Locale.ROOT, "缩放 %.2f×", newValue.doubleValue())));
        statusZoom.setText("缩放 1.00×");
        fitPane = fit;
    }

    @FXML
    private void resetView() {
        if (fitPane != null) {
            fitPane.resetZoom();
            statusZoom.setText("缩放 1.00×");
        }
    }

    @FXML
    private void exportPng() {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出井壁展开图 PNG");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("PNG 图片 (*.png)", "*.png"));

        File file = chooser.showSaveDialog(
                appRoot.getScene() == null ? null : appRoot.getScene().getWindow());
        if (file == null) {
            return;
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            file = new File(file.getAbsolutePath() + ".png");
        }

        try {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.WHITE);
            WritableImage image = appRoot.snapshot(parameters, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            info("已导出 " + file.getName());
        } catch (IOException ex) {
            error("导出 PNG 失败：" + ex.getMessage());
        }
    }

    @FXML
    private void showMainView() {

        try {
            FXMLLoader loader = new FXMLLoader(WallUnfoldController.class.getResource(
                    "/com/stratigraphic/mapping/main-view.fxml"));
            Parent root = loader.load();
            javafx.scene.Scene scene = appRoot.getScene();
            if (scene == null) {
                throw new IllegalStateException("当前页面尚未挂载到窗口");
            }
            scene.setRoot(root);
            if (scene.getWindow() instanceof Stage stage) {
                stage.setTitle("井壁地层剖面图");
            }
        } catch (IOException | IllegalStateException ex) {
            error("打开井壁地层剖面图失败：" + ex.getMessage());
        }
    }

    private void showChartHint() {
        chartHint.setWrapText(true);
        chartHint.setMaxWidth(420);
        chartHint.setAlignment(Pos.CENTER);
        if (!chartHint.getStyleClass().contains("chart-hint")) {
            chartHint.getStyleClass().add("chart-hint");
        }
        chartBox.getChildren().setAll(chartHint);
    }

    private void info(String message) {
        parameterPane.showInfo(message);
        statusMessage.setText(message);
    }

    private void error(String message) {
        parameterPane.showError(message);
        statusMessage.setText(message);
    }
}
