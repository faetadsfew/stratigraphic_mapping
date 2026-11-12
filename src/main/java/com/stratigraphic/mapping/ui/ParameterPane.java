package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 左侧参数面板：显示（并可修改）.txt 文件里的全部输入量。
 *
 * <p>显示的内容分为两组：
 * <ul>
 *   <li>井数据：井眼半径、井眼电阻率；</li>
 *   <li>地层数据：界面个数 n、统一倾角、n 个界面深度、n+1 层层电阻率；</li>
 *   <li>裂缝数据：交点深度、法向仰角、法向方位角和裂缝电阻率 R1；</li>
 *   <li>井壁展开：两个展开点的深度位置。</li>
 * </ul>
 *
 * <p>改动界面个数 n 后按回车或让输入框失去焦点，下方地层数据表会自动按新的条数增减；
 * 也可以直接用「＋ 增加界面」「－ 删除界面」按钮，每加减一条界面，
 * 层电阻率会跟着加减一条（层数恒为 n + 1）。
 *
 * <p>面板最下面是「生成图表」按钮，点了会更新中间的彩色剖面和右侧井壁展开图。
 */
public final class ParameterPane extends BorderPane {

    /** 界面个数的上限，与 {@link StratumModel#MAX_INTERFACES} 保持一致。 */
    private static final int MAX_INTERFACES = StratumModel.MAX_INTERFACES;

    private final TextField countField = new TextField();
    private final TextField dipAngleField = new TextField();
    private final TextField radiusField = new TextField();
    private final TextField wellRhoField = new TextField();

    private final TextField unfoldPointAField = new TextField();
    private final TextField unfoldPointBField = new TextField();

    private final GridPane interfaceGrid = new GridPane();
    private final GridPane layerGrid = new GridPane();
    private final VBox fractureRowsBox = new VBox(8);

    private final Label sourceLabel = new Label("（尚未载入参数文件）");
    private final Label messageLabel = new Label();

    private final List<TextField> depthFields = new ArrayList<>();
    private final List<TextField> rhoFields = new ArrayList<>();
    private final List<FractureRow> fractureRows = new ArrayList<>();

    private Runnable onOpenFile = () -> {
    };
    private Runnable onConfirmData = () -> {
    };
    private Runnable onDataChanged = () -> {
    };

    public ParameterPane() {

        getStyleClass().add("parameter-pane");

        setTop(buildHeader());
        setCenter(buildForm());
        setBottom(buildFooter());
    }

    // =========================================================
    // 对外接口
    // =========================================================

    /** 用一份参数刷新整个面板。 */
    public void setModel(StratumModel model, String source) {

        sourceLabel.setText(source);

        countField.setText(plain(model.interfaceCount()));
        dipAngleField.setText(plain(model.dipAngle()));
        radiusField.setText(plain(model.wellRadius()));
        wellRhoField.setText(plain(model.wellResistivity()));

        List<String> depths = new ArrayList<>();
        for (StratumInterface s : model.interfaces()) {
            depths.add(plain(s.depth()));
        }

        List<String> rhos = new ArrayList<>();
        for (double rho : model.resistivities()) {
            rhos.add(plain(rho));
        }

        unfoldPointAField.setText(plain(round(model.minDepth() - 0.1)));
        unfoldPointBField.setText(plain(round(model.maxDepth() + 0.1)));
        rebuildRows(model.interfaceCount(), depths, rhos);
        rebuildFractureRows(model.fractures());
    }

    /** 把面板里当前填的内容读成一个模型；有问题就抛 {@link IllegalArgumentException}。 */
    public StratumModel toModel() {

        int count = readCount();

        // n 改过但还没同步时，先把两张表的行数对齐
        if (count != depthFields.size()) {
            rebuildRows(count, currentTexts(depthFields), currentTexts(rhoFields));
        }

        double radius = readNumber(radiusField.getText(), "井眼半径");
        double wellRho = readNumber(wellRhoField.getText(), "井眼电阻率");
        double dipAngle = readNumber(dipAngleField.getText(), "统一倾角");

        List<StratumInterface> interfaces = new ArrayList<>();
        for (int i = 0; i < depthFields.size(); i++) {
            interfaces.add(new StratumInterface(
                    readNumber(depthFields.get(i).getText(),
                            "第 " + (i + 1) + " 条界面的交点深度")));
        }

        List<Double> resistivities = new ArrayList<>();
        for (int i = 0; i < rhoFields.size(); i++) {
            resistivities.add(readNumber(rhoFields.get(i).getText(), "第 " + (i + 1) + " 层电阻率"));
        }

        return new StratumModel(radius, wellRho, dipAngle,
                interfaces, resistivities, readFractures());
    }

    /**
     * 安静地读一遍当前数据：只在表格条数和 n 对得上、每个数都能解析时才返回模型，
     * 否则返回 null。不弹错、也不重排表格，适合边编辑边刷新状态栏。
     */
    public StratumModel tryToModel() {

        int count;
        try {
            count = readCount();
        } catch (IllegalArgumentException ex) {
            return null;
        }

        // 条数对不上说明 n 改了还没生效，这时不要顺手重排表格
        if (count != depthFields.size()) {
            return null;
        }

        try {
            return toModel();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 面板里任何一个数或条数变了就回调，用来刷新底部状态栏。 */
    public void setOnDataChanged(Runnable action) {
        this.onDataChanged = action;
    }

    public void setOnOpenFile(Runnable action) {
        this.onOpenFile = action;
    }

    /** 点底部「生成图表」时回调：更新彩色剖面和井壁展开图。 */
    public void setOnConfirmData(Runnable action) {
        this.onConfirmData = action;
    }

    /** 读取两个展开点的位置。A、B 可以倒序输入。 */
    public UnfoldInterval toUnfoldInterval() {

        double pointA = readNumber(unfoldPointAField.getText(), "展开点 A");
        double pointB = readNumber(unfoldPointBField.getText(), "展开点 B");
        if (Math.abs(pointB - pointA) < 1e-9) {
            throw new IllegalArgumentException("展开点 A、B 不能相同");
        }
        return new UnfoldInterval(Math.min(pointA, pointB), Math.max(pointA, pointB));
    }

    public void showError(String message) {
        messageLabel.setText(message);
        setMessageStyle("msg-error");
    }

    public void showInfo(String message) {
        messageLabel.setText(message);
        setMessageStyle("msg-info");
    }

    public void clearMessage() {
        messageLabel.setText("");
        setMessageStyle(null);
    }

    /** 提示颜色靠样式类切换，面板里不写内联样式。 */
    private void setMessageStyle(String styleClass) {

        messageLabel.getStyleClass().removeAll("msg-error", "msg-info");
        if (styleClass != null) {
            messageLabel.getStyleClass().add(styleClass);
        }
    }

    // =========================================================
    // 顶部：标题 + 文件
    // =========================================================

    private Region buildHeader() {

        Label title = new Label("输入参数");
        title.getStyleClass().add("title");

        sourceLabel.getStyleClass().add("source");
        sourceLabel.setWrapText(true);

        Button openButton = new Button("打开参数文件…");
        openButton.getStyleClass().add("ghost-button");
        openButton.setMaxWidth(Double.MAX_VALUE);
        openButton.setOnAction(e -> onOpenFile.run());

        VBox header = new VBox(8, title, sourceLabel, openButton);
        header.getStyleClass().add("pane-header");

        return header;
    }

    // =========================================================
    // 中部：表单
    // =========================================================

    private Region buildForm() {

        GridPane wellGrid = new GridPane();
        wellGrid.setHgap(8);
        wellGrid.setVgap(7);
        wellGrid.getColumnConstraints().addAll(
                column(104, HPos.LEFT), column(120, HPos.LEFT));

        wellGrid.add(caption("井眼半径 / m"), 0, 0);
        wellGrid.add(radiusField, 1, 0);
        wellGrid.add(caption("井眼电阻率 / Ω·m"), 0, 1);
        wellGrid.add(wellRhoField, 1, 1);

        for (TextField field : new TextField[]{radiusField, wellRhoField}) {
            field.setPrefWidth(120);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
            field.textProperty().addListener((o, was, is) -> onDataChanged.run());
        }

        GridPane formationSettings = new GridPane();
        formationSettings.setHgap(6);
        formationSettings.setVgap(7);
        formationSettings.getColumnConstraints().addAll(
                column(58, HPos.LEFT), column(66, HPos.LEFT),
                column(82, HPos.LEFT), column(66, HPos.LEFT));

        formationSettings.add(caption("界面数 n"), 0, 0);
        formationSettings.add(countField, 1, 0);
        formationSettings.add(caption("统一倾角 / °"), 2, 0);
        formationSettings.add(dipAngleField, 3, 0);

        for (TextField field : new TextField[]{countField, dipAngleField}) {
            field.setPrefWidth(66);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
            field.textProperty().addListener((o, was, is) -> onDataChanged.run());
        }

        // 改完 n 之后按回车、或点到别处，就按新的条数重排下面的表
        countField.setOnAction(e -> applyCount());
        countField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                applyCount();
            }
        });

        Button addInterfaceButton = smallButton("＋ 增加界面");
        addInterfaceButton.setOnAction(e -> addInterface());

        Button removeInterfaceButton = smallButton("－ 删除界面");
        removeInterfaceButton.setOnAction(e -> removeInterface());

        HBox interfaceActions = new HBox(6, addInterfaceButton, removeInterfaceButton);
        interfaceActions.setAlignment(Pos.CENTER_LEFT);
        formationSettings.add(interfaceActions, 0, 1, 4, 1);

        Label interfaceTitle = new Label("地层界面");
        interfaceTitle.getStyleClass().add("section-title");

        Label rhoTitle = new Label("层电阻率");
        rhoTitle.getStyleClass().add("section-title");

        VBox interfaceBlock = new VBox(6, interfaceTitle, interfaceGrid);
        VBox rhoBlock = new VBox(6, rhoTitle, layerGrid);

        // 地层界面表与层电阻率表并排放到同一行，顶部对齐
        HBox tablesRow = new HBox(10, interfaceBlock, rhoBlock);
        tablesRow.setAlignment(Pos.TOP_LEFT);

        VBox formationContent = new VBox(10, formationSettings, tablesRow);

        TitledPane wellSection = new TitledPane("井数据", wellGrid);
        wellSection.getStyleClass().add("titled-pane");

        TitledPane formationSection = new TitledPane("地层数据", formationContent);
        formationSection.getStyleClass().add("titled-pane");

        TitledPane fractureSection = buildFractureSection();
        TitledPane wallSection = buildUnfoldSection();

        Accordion accordion = new Accordion(
                wellSection, formationSection, fractureSection, wallSection);

        // 一进来保持折叠，参数较多时不会把左侧面板撑满
        wellSection.setExpanded(false);
        formationSection.setExpanded(false);
        fractureSection.setExpanded(false);
        wallSection.setExpanded(false);
        accordion.setExpandedPane(null);

        VBox form = new VBox(4, accordion);
        form.setPadding(new Insets(0, 14, 18, 14));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");
        // 左栏宽度固定，内容随栏宽收缩，不显示横向滚动条
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /** 裂缝参数：深度、法向角和 R1。 */
    private TitledPane buildFractureSection() {

        Label hint = new Label(
                "裂缝采用 formation_G 格式：交点深度、法向仰角、法向方位角和 R1；"
                        + "R1 同时用于两幅图中的裂缝颜色分级。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        Button addButton = smallButton("＋ 添加裂缝");
        addButton.setOnAction(e -> {
            if (fractureRows.size() >= 20) {
                showError("裂缝最多 20 条");
                return;
            }
            addFractureRow(null);
            onDataChanged.run();
        });

        Button removeButton = smallButton("－ 删除最后一条");
        removeButton.setOnAction(e -> {
            if (!fractureRows.isEmpty()) {
                removeFractureRow(fractureRows.get(fractureRows.size() - 1));
            }
        });

        HBox actions = new HBox(6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, hint, actions, fractureRowsBox);
        TitledPane pane = new TitledPane("裂缝", content);
        pane.getStyleClass().add("titled-pane");

        if (fractureRows.isEmpty()) {
            addFractureRow(null);
        }
        return pane;
    }

    private void rebuildFractureRows(List<Fracture> fractures) {

        fractureRows.clear();
        fractureRowsBox.getChildren().clear();

        if (fractures.isEmpty()) {
            addFractureRow(null);
        } else {
            for (Fracture fracture : fractures) {
                addFractureRow(fracture);
            }
        }
        renumberFractureRows();
    }

    private void addFractureRow(Fracture fracture) {

        FractureRow row = new FractureRow(fracture);
        row.deleteButton.setOnAction(e -> removeFractureRow(row));
        fractureRows.add(row);
        fractureRowsBox.getChildren().add(row.card);
        renumberFractureRows();
    }

    private void removeFractureRow(FractureRow row) {

        fractureRows.remove(row);
        fractureRowsBox.getChildren().remove(row.card);
        if (fractureRows.isEmpty()) {
            addFractureRow(null);
        }
        renumberFractureRows();
        onDataChanged.run();
    }

    private void renumberFractureRows() {
        for (int i = 0; i < fractureRows.size(); i++) {
            fractureRows.get(i).title.setText("裂缝 " + (i + 1));
        }
    }

    private List<Fracture> readFractures() {

        List<Fracture> fractures = new ArrayList<>();
        for (int i = 0; i < fractureRows.size(); i++) {
            Fracture fracture = fractureRows.get(i).toFracture(i + 1);
            if (fracture != null) {
                fractures.add(fracture);
            }
        }
        return fractures;
    }

    /** 井壁展开参数：仅输入两个点的位置。 */
    private TitledPane buildUnfoldSection() {

        Label hint = new Label(
                "展开图只使用当前井眼半径、统一倾角和界面深度，"
                        + "绘制 A、B 两点之间的彩色井壁展开图。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.getColumnConstraints().addAll(
                column(112, HPos.LEFT), column(132, HPos.LEFT));

        addRow(grid, 0, "展开点 A / m", unfoldPointAField);
        addRow(grid, 1, "展开点 B / m", unfoldPointBField);

        for (TextField field : new TextField[]{unfoldPointAField, unfoldPointBField}) {
            field.setPrefWidth(132);
            field.setMaxWidth(Double.MAX_VALUE);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
        }

        VBox content = new VBox(8, hint, grid);
        TitledPane pane = new TitledPane("井壁展开区间", content);
        pane.getStyleClass().add("titled-pane");
        return pane;
    }

    /** 底部：提示信息 + 「生成图表」按钮，按钮固定在面板最下面。 */
    private Region buildFooter() {

        messageLabel.setWrapText(true);
        messageLabel.setPadding(new Insets(8, 14, 4, 14));
        messageLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button confirmDataButton = new Button("生成图表");
        confirmDataButton.getStyleClass().add("primary-button");
        confirmDataButton.setMaxWidth(Double.MAX_VALUE);
        confirmDataButton.setOnAction(e -> onConfirmData.run());

        HBox buttons = new HBox(confirmDataButton);
        HBox.setHgrow(confirmDataButton, Priority.ALWAYS);
        buttons.setPadding(new Insets(6, 14, 14, 14));

        return new VBox(messageLabel, buttons);
    }

    // =========================================================
    // 地层数据表格的生成
    // =========================================================

    private void applyCount() {

        // 初始空白状态不因焦点离开而弹错
        if (countField.getText().isBlank() && depthFields.isEmpty()) {
            onDataChanged.run();
            return;
        }

        int count;
        try {
            count = readCount();
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }

        if (count != depthFields.size()) {
            rebuildRows(count, currentTexts(depthFields), currentTexts(rhoFields));
            clearMessage();
        }

        onDataChanged.run();
    }

    // =========================================================
    // 增减界面
    //
    // 层数恒为 n + 1，所以每加减一条界面，电阻率也要跟着加减一条，
    // 否则 StratumModel 的校验会直接报「层电阻率应有 x 个」。
    // =========================================================

    /** 末尾追加一条界面，同时在末尾追加一层电阻率。 */
    private void addInterface() {

        List<String> depths = currentTexts(depthFields);
        List<String> rhos = currentTexts(rhoFields);

        int count = depths.size();

        if (count >= MAX_INTERFACES) {
            showError("界面最多 " + MAX_INTERFACES + " 条");
            return;
        }

        // 新界面默认接在最后一条下面 0.2 m
        depths.add(plain(round(lastNumber(depths, 1.0) + 0.2)));
        // 新层默认沿用最深那层的电阻率
        rhos.add(rhos.isEmpty() ? "10" : rhos.get(rhos.size() - 1));

        countField.setText(String.valueOf(count + 1));
        rebuildRows(count + 1, depths, rhos);
        clearMessage();
        onDataChanged.run();
    }

    /** 删掉最后一条界面，同时删掉最深的那一层电阻率。 */
    private void removeInterface() {

        int count = depthFields.size();

        if (count <= 1) {
            showError("至少要保留 1 条界面");
            return;
        }

        List<String> depths = currentTexts(depthFields);
        List<String> rhos = currentTexts(rhoFields);

        depths.remove(count - 1);
        if (!rhos.isEmpty()) {
            rhos.remove(rhos.size() - 1);
        }

        countField.setText(String.valueOf(count - 1));
        rebuildRows(count - 1, depths, rhos);
        clearMessage();
        onDataChanged.run();
    }

    /** 从后往前找第一个能解析的数，找不到就用兜底值。 */
    private static double lastNumber(List<String> values, double fallback) {

        for (int i = values.size() - 1; i >= 0; i--) {
            double value = parseOrNaN(values.get(i));
            if (!Double.isNaN(value)) {
                return value;
            }
        }
        return fallback;
    }

    /** 抹掉浮点误差，免得出现 1.2000000000000002 这种数。 */
    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    private void rebuildRows(int count, List<String> depths, List<String> rhos) {

        depthFields.clear();
        rhoFields.clear();

        rebuildInterfaceGrid(count, depths);
        rebuildLayerGrid(count, rhos);
    }

    /** 界面表：序号 / 交点深度；倾角由上方统一设置。 */
    private void rebuildInterfaceGrid(int count, List<String> depths) {

        interfaceGrid.getChildren().clear();
        interfaceGrid.setHgap(8);
        interfaceGrid.setVgap(5);
        interfaceGrid.getColumnConstraints().setAll(
                column(36, HPos.LEFT), flexColumn(88, HPos.LEFT));

        interfaceGrid.add(header("序号"), 0, 0);
        interfaceGrid.add(header("交点深度 z / m"), 1, 0);

        for (int i = 0; i < count; i++) {

            TextField depth = numberField(at(depths, i), 100);
            depthFields.add(depth);

            interfaceGrid.add(indexLabel(String.valueOf(i + 1)), 0, i + 1);
            interfaceGrid.add(depth, 1, i + 1);
        }
    }

    /** 电阻率表：层号 / R0，层数恒为 n + 1。 */
    private void rebuildLayerGrid(int count, List<String> rhos) {

        layerGrid.getChildren().clear();
        layerGrid.setHgap(8);
        layerGrid.setVgap(5);
        layerGrid.getColumnConstraints().setAll(
                column(36, HPos.LEFT), flexColumn(80, HPos.LEFT));

        layerGrid.add(header("层号"), 0, 0);
        layerGrid.add(header("R0 / (Ω·m)"), 1, 0);

        for (int i = 0; i < count + 1; i++) {

            TextField rho = numberField(at(rhos, i), 96);
            rhoFields.add(rho);

            layerGrid.add(indexLabel(String.valueOf(i + 1)), 0, i + 1);
            layerGrid.add(rho, 1, i + 1);
        }
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "";
    }

    /**
     * 取一组输入框当前的文本。直接搬文本而不是解析成数，
     * 这样加删界面重排表格时，用户敲了一半的内容也原样留着。
     */
    private static List<String> currentTexts(List<TextField> fields) {

        List<String> out = new ArrayList<>(fields.size());
        for (TextField field : fields) {
            out.add(field.getText());
        }
        return out;
    }

    // =========================================================
    // 数值读写
    // =========================================================

    private int readCount() {

        double value = readNumber(countField.getText(), "界面个数 n");

        if (value != Math.rint(value) || value < 1 || value > MAX_INTERFACES) {
            throw new IllegalArgumentException(
                    "界面个数 n 需要是 1 ~ " + MAX_INTERFACES + " 之间的整数");
        }
        return (int) value;
    }

    private static double readNumber(String text, String what) {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(what + " 不能为空");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(what + "「" + text.trim() + "」不是有效数字");
        }
    }

    private static double parseOrNaN(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    /** 把数值写成输入框里的文本：整数不带小数点。 */
    private static String plain(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    // =========================================================
    // 小控件
    // =========================================================

    private TextField numberField(String value, double width) {

        TextField field = new TextField(value == null ? "" : value);
        field.setPrefWidth(width);
        field.setMaxWidth(Double.MAX_VALUE);
        field.getStyleClass().add("form-input");
        restrictToNumber(field);

        // 新建时文本已经填好了，所以这里才挂监听，不会在建表时白跑一趟
        field.textProperty().addListener((o, was, is) -> onDataChanged.run());
        return field;
    }

    /** 只让输入合法的十进制数字（含 1.0E-4 这种写法），别的字符直接吃掉。 */
    private static void restrictToNumber(TextField field) {

        field.setTextFormatter(new TextFormatter<>(change -> {
            String text = change.getControlNewText();
            return text.matches("-?[0-9]*[.]?[0-9]*([eE][-+]?[0-9]*)?") ? change : null;
        }));
    }

    private static void addRow(GridPane grid, int row, String caption, javafx.scene.Node control) {
        grid.add(caption(caption), 0, row);
        grid.add(control, 1, row);
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-caption");
        return label;
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header");
        return label;
    }

    private static Label indexLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("row-index");
        return label;
    }

    private static Button smallButton(String text) {

        Button button = new Button(text);
        button.getStyleClass().add("mini-btn");
        return button;
    }

    private static ColumnConstraints column(double width, HPos alignment) {

        ColumnConstraints constraints = new ColumnConstraints(width);
        constraints.setHalignment(alignment);
        return constraints;
    }

    /**
     * 数值列：只给最小宽度，多出来的宽度由它撑开；面板变窄时优先压缩它，
     * 这样两张表并排也不会把输入框挤出可视区。
     */
    private static ColumnConstraints flexColumn(double minWidth, HPos alignment) {

        ColumnConstraints constraints = new ColumnConstraints();
        constraints.setMinWidth(minWidth);
        constraints.setHgrow(Priority.ALWAYS);
        constraints.setHalignment(alignment);
        return constraints;
    }

    private final class FractureRow {

        private final VBox card = new VBox(6);
        private final Label title = new Label();
        private final Button deleteButton = smallButton("删除");

        private final TextField depthField;
        private final TextField elevationField;
        private final TextField azimuthField;
        private final TextField resistivityField;

        private FractureRow(Fracture fracture) {

            title.getStyleClass().add("section-title");

            HBox header = new HBox(6, title, new Region(), deleteButton);
            HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);

            depthField = numberField(
                    fracture == null ? "" : plain(fracture.depth()), 86);
            elevationField = numberField(
                    fracture == null ? "" : plain(fracture.normalElevationDeg()), 86);
            azimuthField = numberField(
                    fracture == null ? "" : plain(fracture.normalAzimuthDeg()), 86);
            resistivityField = numberField(
                    fracture == null ? "" : plain(fracture.resistivity()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(90, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "交点深度 / m", depthField);
            addRow(grid, 1, "法向仰角 / °", elevationField);
            addRow(grid, 2, "法向方位角 / °", azimuthField);
            addRow(grid, 3, "R1 / (Ω·m)", resistivityField);

            card.getStyleClass().add("fracture-card");
            card.getChildren().addAll(header, grid);
        }

        private Fracture toFracture(int index) {

            if (allBlank()) {
                return null;
            }
            return new Fracture(
                    readNumber(depthField.getText(),
                            "第 " + index + " 条裂缝交点深度"),
                    readNumber(elevationField.getText(),
                            "第 " + index + " 条裂缝法向仰角"),
                    readNumber(azimuthField.getText(),
                            "第 " + index + " 条裂缝法向方位角"),
                    readNumber(resistivityField.getText(),
                            "第 " + index + " 条裂缝电阻率 R1"));
        }

        private boolean allBlank() {
            return depthField.getText().isBlank()
                    && elevationField.getText().isBlank()
                    && azimuthField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    public record UnfoldInterval(double start, double end) {
    }
}
