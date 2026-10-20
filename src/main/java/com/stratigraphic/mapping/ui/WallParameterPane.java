package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.wall.CompassDirection;
import com.stratigraphic.mapping.wall.UnfoldDirection;
import com.stratigraphic.mapping.wall.WallData;
import com.stratigraphic.mapping.wall.WallParameters;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * 第二层页面的左侧参数面板，沿用第一层页面的折叠分组与表单样式。
 */
public final class WallParameterPane extends BorderPane {

    public static final double DEFAULT_AMPLITUDE_COLOR_MIN = 24.0;
    public static final double DEFAULT_AMPLITUDE_COLOR_MAX = 27.0;

    private final TextField inclinationField = new TextField("0");
    private final TextField upperMdField = new TextField();
    private final TextField intervalField = new TextField("0.5");
    private final ComboBox<CompassDirection> firstElectrodeBox =
            new ComboBox<>();
    private final ComboBox<UnfoldDirection> unfoldDirectionBox =
            new ComboBox<>();

    private final TextField colorMinField = new TextField();
    private final TextField colorMaxField = new TextField();

    private final Label sourceLabel = new Label("（尚未载入 Excel）");
    private final Label dataSummary = new Label("请选择包含「模值」「相位」两个 Sheet 的 Excel 文件。");
    private final Label messageLabel = new Label();

    private Runnable onOpenFile = () -> {
    };
    private Runnable onGenerate = () -> {
    };

    public WallParameterPane() {

        getStyleClass().add("parameter-pane");
        resetColorRange();
        setTop(buildHeader());
        setCenter(buildForm());
        setBottom(buildFooter());
    }

    public void setOnOpenFile(Runnable action) {
        this.onOpenFile = action;
    }

    public void setOnGenerate(Runnable action) {
        this.onGenerate = action;
    }

    public void setData(WallData data, String source) {

        sourceLabel.setText(source);
        dataSummary.setText(String.format(Locale.ROOT,
                "数据行数：%d%n"
                        + "深度：%.5f ～ %.5f m%n"
                        + "实际采样间隔：%.8f m%n"
                        + "模值：%.4f ～ %.4f Ω·m%n"
                        + "相位：%.4f ～ %.4f°",
                data.rowCount(),
                data.minDepth(), data.maxDepth(),
                data.sampleInterval(),
                data.amplitudeMin(), data.amplitudeMax(),
                data.phaseMin(), data.phaseMax()));

        upperMdField.setText(format(data.minDepth() + 0.1, 6));
        resetColorRange();
    }

    public WallParameters toParameters() {
        return new WallParameters(
                readNumber(inclinationField, "井斜角"),
                readNumber(upperMdField, "上截面中心深度"),
                readNumber(intervalField, "取井长度"),
                firstElectrodeBox.getValue(),
                unfoldDirectionBox.getValue());
    }

    public ColorRange colorRange() {

        double min = readNumber(colorMinField, "模值色标下限");
        double max = readNumber(colorMaxField, "模值色标上限");
        if (!(min > 0)) {
            throw new IllegalArgumentException("模值色标下限必须大于 0");
        }
        if (!(max > min)) {
            throw new IllegalArgumentException("模值色标上限必须大于下限");
        }
        return new ColorRange(min, max);
    }

    public void showError(String message) {
        messageLabel.setText(message);
        setMessageStyle("msg-error");
    }

    public void showInfo(String message) {
        messageLabel.setText(message);
        setMessageStyle("msg-info");
    }

    private void setMessageStyle(String styleClass) {
        messageLabel.getStyleClass().removeAll("msg-error", "msg-info");
        if (styleClass != null) {
            messageLabel.getStyleClass().add(styleClass);
        }
    }

    private Region buildHeader() {

        Label title = new Label("数据与几何参数");
        title.getStyleClass().add("title");

        sourceLabel.getStyleClass().add("source");
        sourceLabel.setWrapText(true);

        Button openButton = new Button("打开 Excel…");
        openButton.getStyleClass().add("ghost-button");
        openButton.setMaxWidth(Double.MAX_VALUE);
        openButton.setOnAction(event -> onOpenFile.run());

        VBox header = new VBox(8, title, sourceLabel, openButton);
        header.getStyleClass().add("pane-header");
        return header;
    }

    private Region buildForm() {

        dataSummary.setWrapText(true);
        dataSummary.getStyleClass().add("data-summary");

        TitledPane dataSection = new TitledPane("Excel 数据检查", dataSummary);
        dataSection.getStyleClass().add("titled-pane");

        TitledPane geometrySection = new TitledPane(
                "井筒与截面", buildGeometryGrid());
        geometrySection.getStyleClass().add("titled-pane");

        TitledPane colorSection = new TitledPane(
                "模值颜色范围", buildColorGrid());
        colorSection.getStyleClass().add("titled-pane");

        TitledPane modelSection = new TitledPane(
                "几何模型说明", buildModelNote());
        modelSection.getStyleClass().add("titled-pane");

        Accordion accordion = new Accordion(
                dataSection, geometrySection, colorSection, modelSection);
        dataSection.setExpanded(true);
        geometrySection.setExpanded(false);
        colorSection.setExpanded(false);
        modelSection.setExpanded(false);

        VBox form = new VBox(6, accordion);
        form.setPadding(new Insets(0, 14, 18, 14));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static Label buildModelNote() {

        Label note = new Label(
                "坐标约定：X 正东、Y 正北、Z 向上，MD 沿井轴向下。"
                        + "井轴取在南北竖直面内，"
                        + "方向 d=(0, sin i, -cos i)，其中 i 为井斜角。"
                        + "第 1 电极方向取所选罗盘方向在井筒横截面上的正交投影，"
                        + "192 个电极在井筒圆周上等角分布（相邻 1.875°）。\n\n"
                        + "水平截面与井筒的真实交线按 "
                        + "r_H=R[n_xy+n_z·tan i·(0, 1)] 计算，"
                        + "对应 MD=(R·n_z-z)/cos i；横向位置使用该椭圆的真实弧长，"
                        + "不将 192 个点强制等分到椭圆上。像素点击时按电极和水平面高度"
                        + "反算 MD，再从 Excel 做线性插值。上下水平截面在展开图中的"
                        + "MD 边界随井壁方向呈正弦/余弦变化，斜井不会显示为固定矩形。\n\n"
                        + "模型假设：当前取井段为直线井段，井筒半径为 R=0.1 m；"
                        + "电极圆周等角、井径规则，未建模仪器旋转、"
                        + "偏心、井径变化和井斜随深度变化。弧长按 192 个离散点计算，"
                        + "对本项目当前展开图精度影响可忽略。");
        note.setWrapText(true);
        note.getStyleClass().add("model-note");
        return note;
    }

    private GridPane buildGeometryGrid() {

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.getColumnConstraints().addAll(
                column(112, HPos.LEFT), column(132, HPos.LEFT));

        firstElectrodeBox.getItems().setAll(CompassDirection.values());
        firstElectrodeBox.setValue(CompassDirection.NORTH);
        firstElectrodeBox.getStyleClass().add("form-input");

        unfoldDirectionBox.getItems().setAll(UnfoldDirection.values());
        unfoldDirectionBox.setValue(UnfoldDirection.CLOCKWISE);
        unfoldDirectionBox.getStyleClass().add("form-input");

        int row = 0;
        addRow(grid, row++, "井斜角 / °", inclinationField);
        addRow(grid, row++, "上截面中心 MD / m", upperMdField);
        addRow(grid, row++, "取井长度 / m", intervalField);
        addRow(grid, row++, "第 1 电极方向", firstElectrodeBox);
        addRow(grid, row, "电极展开方向", unfoldDirectionBox);

        for (TextField field : new TextField[]{
                inclinationField, upperMdField, intervalField}) {
            field.setPrefWidth(132);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
        }

        return grid;
    }

    private GridPane buildColorGrid() {

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.getColumnConstraints().addAll(
                column(112, HPos.LEFT), column(132, HPos.LEFT));

        for (TextField field : new TextField[]{colorMinField, colorMaxField}) {
            field.setPrefWidth(132);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
        }

        grid.add(caption("下限 / Ω·m"), 0, 0);
        grid.add(colorMinField, 1, 0);
        grid.add(caption("上限 / Ω·m"), 0, 1);
        grid.add(colorMaxField, 1, 1);
        return grid;
    }

    private void resetColorRange() {
        colorMinField.setText(format(DEFAULT_AMPLITUDE_COLOR_MIN, 6));
        colorMaxField.setText(format(DEFAULT_AMPLITUDE_COLOR_MAX, 6));
    }

    private Region buildFooter() {

        messageLabel.setWrapText(true);
        messageLabel.setPadding(new Insets(8, 14, 4, 14));
        messageLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button generate = new Button("生成井壁展开图");
        generate.getStyleClass().add("primary-button");
        generate.setMaxWidth(Double.MAX_VALUE);
        generate.setOnAction(event -> onGenerate.run());

        HBox buttons = new HBox(generate);
        HBox.setHgrow(generate, Priority.ALWAYS);
        buttons.setPadding(new Insets(6, 14, 14, 14));
        buttons.setAlignment(Pos.CENTER);

        return new VBox(messageLabel, buttons);
    }

    private static void addRow(GridPane grid,
                               int row,
                               String caption,
                               javafx.scene.Node control) {
        grid.add(caption(caption), 0, row);
        grid.add(control, 1, row);
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-caption");
        return label;
    }

    private static ColumnConstraints column(double width, HPos alignment) {
        ColumnConstraints constraints = new ColumnConstraints(width);
        constraints.setHalignment(alignment);
        return constraints;
    }

    private static double readNumber(TextField field, String name) {

        String text = field.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + "「" + text.trim() + "」不是有效数字");
        }
    }

    private static void restrictToNumber(TextField field) {
        field.setTextFormatter(new TextFormatter<>(change -> {
            String text = change.getControlNewText();
            return text.matches("-?[0-9]*[.]?[0-9]*([eE][-+]?[0-9]*)?")
                    ? change
                    : null;
        }));
    }

    private static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    public record ColorRange(double min, double max) {
    }
}
