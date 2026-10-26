package com.stratigraphic.mapping.ui;

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
 *   <li>地层数据：界面个数 n、n 组「界面与井轴的交点深度 / 夹角」、n+1 层层电阻率。</li>
 * </ul>
 *
 * <p>改动界面个数 n 后按回车或让输入框失去焦点，下方地层数据表会自动按新的条数增减；
 * 也可以直接用「＋ 增加界面」「－ 删除界面」按钮，每加减一条界面，
 * 层电阻率会跟着加减一条（层数恒为 n + 1）。
 *
 * <p>面板最下面是「确认数据」按钮，点了会把当前数据（含增删过的界面）画成中间的线条图。
 */
public final class ParameterPane extends BorderPane {

    /** 界面个数的上限，与 {@link StratumModel#MAX_INTERFACES} 保持一致。 */
    private static final int MAX_INTERFACES = StratumModel.MAX_INTERFACES;

    private final TextField countField = new TextField();
    private final TextField radiusField = new TextField();
    private final TextField wellRhoField = new TextField();

    private final GridPane interfaceGrid = new GridPane();
    private final GridPane layerGrid = new GridPane();

    private final Label sourceLabel = new Label("（尚未载入参数文件）");
    private final Label messageLabel = new Label();

    private final List<TextField> depthFields = new ArrayList<>();
    private final List<TextField> angleFields = new ArrayList<>();
    private final List<TextField> rhoFields = new ArrayList<>();

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
        radiusField.setText(plain(model.wellRadius()));
        wellRhoField.setText(plain(model.wellResistivity()));

        List<String> depths = new ArrayList<>();
        List<String> angles = new ArrayList<>();
        for (StratumInterface s : model.interfaces()) {
            depths.add(plain(s.depth()));
            angles.add(plain(s.angle()));
        }

        List<String> rhos = new ArrayList<>();
        for (double rho : model.resistivities()) {
            rhos.add(plain(rho));
        }

        rebuildRows(model.interfaceCount(), depths, angles, rhos);
    }

    /** 把面板里当前填的内容读成一个模型；有问题就抛 {@link IllegalArgumentException}。 */
    public StratumModel toModel() {

        int count = readCount();

        // n 改过但还没同步时，先把两张表的行数对齐
        if (count != depthFields.size()) {
            rebuildRows(count, currentTexts(depthFields),
                    currentTexts(angleFields), currentTexts(rhoFields));
        }

        double radius = readNumber(radiusField.getText(), "井眼半径");
        double wellRho = readNumber(wellRhoField.getText(), "井眼电阻率");

        List<StratumInterface> interfaces = new ArrayList<>();
        for (int i = 0; i < depthFields.size(); i++) {
            interfaces.add(new StratumInterface(
                    readNumber(depthFields.get(i).getText(), "第 " + (i + 1) + " 条界面的交点深度"),
                    readNumber(angleFields.get(i).getText(), "第 " + (i + 1) + " 条界面的夹角")));
        }

        List<Double> resistivities = new ArrayList<>();
        for (int i = 0; i < rhoFields.size(); i++) {
            resistivities.add(readNumber(rhoFields.get(i).getText(), "第 " + (i + 1) + " 层电阻率"));
        }

        return new StratumModel(radius, wellRho, interfaces, resistivities);
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

    /** 点底部「确认数据」时回调：按面板里当前的数据重画中间的线条图。 */
    public void setOnConfirmData(Runnable action) {
        this.onConfirmData = action;
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
        }

        // 井眼半径 / 电阻率改一下就刷新状态栏
        radiusField.textProperty().addListener((o, was, is) -> onDataChanged.run());
        wellRhoField.textProperty().addListener((o, was, is) -> onDataChanged.run());

        GridPane formationSettings = new GridPane();
        formationSettings.setHgap(8);
        formationSettings.setVgap(7);
        formationSettings.getColumnConstraints().addAll(
                column(104, HPos.LEFT), column(120, HPos.LEFT));

        formationSettings.add(caption("界面个数 n"), 0, 0);
        formationSettings.add(countField, 1, 0);

        countField.setPrefWidth(120);
        countField.getStyleClass().add("form-input");
        restrictToNumber(countField);

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

        // 增减界面的按钮跟「界面个数 n」放在一起，界面表上方只留标题，
        // 这样界面表和电阻率表的顶部（含表头）才能对齐
        formationSettings.add(interfaceActions, 0, 1, 2, 1);

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

        Accordion accordion = new Accordion(wellSection, formationSection);

        // TitledPane 默认是展开的，这里显式收起来：一进来是折叠状态，点标题才展开
        wellSection.setExpanded(false);
        formationSection.setExpanded(false);
        accordion.setExpandedPane(null);

        VBox form = new VBox(4, accordion);
        form.setPadding(new Insets(0, 14, 18, 14));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");
        // 窗口拉得很窄时允许横向滚动，别把右边的输入框裁掉
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /** 底部：提示信息 + 「确认数据」按钮，按钮固定在面板最下面。 */
    private Region buildFooter() {

        messageLabel.setWrapText(true);
        messageLabel.setPadding(new Insets(8, 14, 4, 14));
        messageLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button confirmDataButton = new Button("确认数据");
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

        int count;
        try {
            count = readCount();
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }

        if (count != depthFields.size()) {
            rebuildRows(count, currentTexts(depthFields),
                    currentTexts(angleFields), currentTexts(rhoFields));
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
        List<String> angles = currentTexts(angleFields);
        List<String> rhos = currentTexts(rhoFields);

        int count = depths.size();

        if (count >= MAX_INTERFACES) {
            showError("界面最多 " + MAX_INTERFACES + " 条");
            return;
        }

        // 新界面默认接在最后一条下面 0.2 m，夹角沿用上一条
        depths.add(plain(round(lastNumber(depths, 1.0) + 0.2)));
        angles.add(plain(lastNumber(angles, 90)));
        // 新层默认沿用最深那层的电阻率
        rhos.add(rhos.isEmpty() ? "10" : rhos.get(rhos.size() - 1));

        countField.setText(String.valueOf(count + 1));
        rebuildRows(count + 1, depths, angles, rhos);
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
        List<String> angles = currentTexts(angleFields);
        List<String> rhos = currentTexts(rhoFields);

        depths.remove(count - 1);
        angles.remove(count - 1);
        if (!rhos.isEmpty()) {
            rhos.remove(rhos.size() - 1);
        }

        countField.setText(String.valueOf(count - 1));
        rebuildRows(count - 1, depths, angles, rhos);
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

    private void rebuildRows(int count, List<String> depths, List<String> angles, List<String> rhos) {

        depthFields.clear();
        angleFields.clear();
        rhoFields.clear();

        rebuildInterfaceGrid(count, depths, angles);
        rebuildLayerGrid(count, rhos);
    }

    /** 界面表：序号 / 交点深度 / 夹角。 */
    private void rebuildInterfaceGrid(int count, List<String> depths, List<String> angles) {

        interfaceGrid.getChildren().clear();
        interfaceGrid.setHgap(8);
        interfaceGrid.setVgap(5);
        interfaceGrid.getColumnConstraints().setAll(
                column(36, HPos.LEFT), flexColumn(70, HPos.LEFT), flexColumn(62, HPos.LEFT));

        interfaceGrid.add(header("序号"), 0, 0);
        interfaceGrid.add(header("交点深度 z / m"), 1, 0);
        interfaceGrid.add(header("夹角 θ / °"), 2, 0);

        for (int i = 0; i < count; i++) {

            TextField depth = numberField(at(depths, i), 88);
            TextField angle = numberField(at(angles, i), 76);

            depthFields.add(depth);
            angleFields.add(angle);

            interfaceGrid.add(indexLabel(String.valueOf(i + 1)), 0, i + 1);
            interfaceGrid.add(depth, 1, i + 1);
            interfaceGrid.add(angle, 2, i + 1);
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
}
