package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.CubeAnomaly;
import com.stratigraphic.mapping.model.DielectricConstant;
import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.SphereFracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import com.stratigraphic.mapping.model.SurfaceText;
import com.stratigraphic.mapping.model.WedgeFracture;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>异常体数据：球形异常体、正方体异常体和楔形体异常体；</li>
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
    private static final BigDecimal METERS_PER_INCH = new BigDecimal("0.0254");
    private final TextField radiusField = new TextField();
    private double radiusMeters = Double.NaN;
    private boolean radiusEdited;
    private boolean updatingRadiusDisplay;
    private final TextField wellRhoField = new TextField();
    private final TextField wellDielectricField = new TextField();

    private final TextField unfoldPointAField = new TextField();
    private final TextField unfoldPointBField = new TextField();

    private final GridPane interfaceGrid = new GridPane();
    private final GridPane layerGrid = new GridPane();
    private final VBox fractureRowsBox = new VBox(8);
    private final VBox sphereRowsBox = new VBox(8);
    private final VBox cubeRowsBox = new VBox(8);
    private final VBox wedgeRowsBox = new VBox(8);
    private final VBox textRowsBox = new VBox(8);

    private ScrollPane formScroll;
    private Accordion formAccordion;
    private TitledPane formationSection;
    private TitledPane fractureSection;
    private TitledPane anomalySection;
    private TitledPane textSection;
    private TextField selectedFormationField;
    private FractureRow selectedFractureRow;
    private SphereRow selectedSphereRow;
    private CubeRow selectedCubeRow;
    private WedgeRow selectedWedgeRow;
    private TextRow selectedTextRow;
    private final PauseTransition selectionScrollDelay =
            new PauseTransition(Duration.millis(350));

    private final Label sourceLabel = new Label("（尚未载入参数文件）");
    private final Label messageLabel = new Label();

    private final List<TextField> depthFields = new ArrayList<>();
    private final List<TextField> rhoFields = new ArrayList<>();
    private final List<TextField> dielectricFields = new ArrayList<>();
    private final List<FractureRow> fractureRows = new ArrayList<>();
    private final List<SphereRow> sphereRows = new ArrayList<>();
    private final List<CubeRow> cubeRows = new ArrayList<>();
    private final List<WedgeRow> wedgeRows = new ArrayList<>();
    private final List<TextRow> textRows = new ArrayList<>();

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
        setRadiusMeters(model.wellRadius());
        wellRhoField.setText(plain(model.wellResistivity()));
        wellDielectricField.setText(plain(model.dielectricConstant()));

        List<String> depths = new ArrayList<>();
        for (StratumInterface s : model.interfaces()) {
            depths.add(plain(s.depth()));
        }

        List<String> rhos = new ArrayList<>();
        for (double rho : model.resistivities()) {
            rhos.add(plain(rho));
        }

        List<String> dielectricConstants = new ArrayList<>();
        for (double epsilon : model.layerDielectricConstants()) {
            dielectricConstants.add(plain(epsilon));
        }

        double pointA = round(model.minDepth() - 0.1);
        double pointB = round(model.maxDepth() + 0.1);
        if (model.interfaceCount() == 0) {
            pointA = Math.min(0, pointA);
            pointB = Math.max(1.5, pointB);
        }
        unfoldPointAField.setText(plain(pointA));
        unfoldPointBField.setText(plain(pointB));
        rebuildRows(model.interfaceCount(), depths, rhos, dielectricConstants);
        rebuildFractureRows(model.fractures());
        rebuildSphereRows(model.sphereFractures());
        rebuildCubeRows(model.cubeAnomalies());
        rebuildWedgeRows(model.wedgeFractures());
        rebuildTextRows(model.surfaceTexts());
    }

    /** 把面板里当前填的内容读成一个模型；有问题就抛 {@link IllegalArgumentException}。 */
    public StratumModel toModel() {

        int count = readCount();

        // n 改过但还没同步时，先把两张表的行数对齐
        if (count != depthFields.size()) {
            rebuildRows(count, currentTexts(depthFields), currentTexts(rhoFields),
                    currentTexts(dielectricFields));
        }

        double radius = readRadiusMeters();
        double wellRho = readNumber(wellRhoField.getText(), "井眼电阻率");
        double wellDielectric = readPositiveNumber(
                wellDielectricField.getText(), "井眼介电常数 εr");
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

        List<Double> layerDielectricConstants = new ArrayList<>();
        for (int i = 0; i < dielectricFields.size(); i++) {
            layerDielectricConstants.add(readPositiveNumber(
                    dielectricFields.get(i).getText(),
                    "第 " + (i + 1) + " 层介电常数 εr"));
        }

        return new StratumModel(radius, wellRho, wellDielectric, dipAngle,
                interfaces, resistivities, layerDielectricConstants,
                readFractures(), readSphereFractures(),
                readWedgeFractures(), readTexts(), readCubeAnomalies());
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

    /**
     * 从图表中选中一条裂缝时调用：展开左侧「裂缝」分组，
     * 高亮对应参数卡片，并滚动到该卡片。
     *
     * @param visibleIndex 裂缝在有效裂缝列表中的序号，从 0 开始
     */
    public void revealFracture(int visibleIndex) {

        clearSphereSelection();
        clearCubeSelection();
        clearWedgeSelection();
        clearTextSelection();
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
            selectedFormationField = null;
        }
        FractureRow row = fractureRowAt(visibleIndex);
        selectFractureRow(row);
        if (row == null) {
            return;
        }

        if (formAccordion != null && fractureSection != null) {
            fractureSection.setExpanded(true);
            formAccordion.setExpandedPane(fractureSection);
        }
        row.depthField.requestFocus();
        scrollToNode(row.card);
    }

    /** 从井壁展开图选中球形异常体时，展开并高亮对应参数卡。 */
    public void revealSphere(int sphereIndex) {

        SphereRow row = sphereRowAt(sphereIndex);
        clearCubeSelection();
        clearWedgeSelection();
        clearTextSelection();
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
            selectedFormationField = null;
        }
        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
            selectedFractureRow = null;
        }
        selectSphereRow(row);
        if (row == null) {
            return;
        }
        if (formAccordion != null && anomalySection != null) {
            anomalySection.setExpanded(true);
            formAccordion.setExpandedPane(anomalySection);
        }
        row.depthField.requestFocus();
        scrollToNode(row.card);
    }

    /** 从三维图/井壁展开图选中正方体异常体时，展开并高亮对应参数卡。 */
    public void revealCube(int cubeIndex) {

        CubeRow row = cubeRowAt(cubeIndex);
        clearSphereSelection();
        clearWedgeSelection();
        clearTextSelection();
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
            selectedFormationField = null;
        }
        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
            selectedFractureRow = null;
        }
        selectCubeRow(row);
        if (row == null) {
            return;
        }
        if (formAccordion != null && anomalySection != null) {
            anomalySection.setExpanded(true);
            formAccordion.setExpandedPane(anomalySection);
        }
        row.depthField.requestFocus();
        scrollToNode(row.card);
    }

    /** 从井壁展开图选中楔形体异常体时，展开并高亮对应参数卡。 */
    public void revealWedge(int wedgeIndex) {

        clearSphereSelection();
        clearCubeSelection();
        clearTextSelection();
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
            selectedFormationField = null;
        }
        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
            selectedFractureRow = null;
        }

        WedgeRow row = wedgeRowAt(wedgeIndex);
        selectWedgeRow(row);
        if (row == null) {
            return;
        }
        if (formAccordion != null && anomalySection != null) {
            anomalySection.setExpanded(true);
            formAccordion.setExpandedPane(anomalySection);
        }
        row.vertexAzimuthField.requestFocus();
        scrollToNode(row.card);
    }

    /** 从井壁展开图选中曲面文字时，展开并高亮对应参数卡。 */
    public void revealText(int textIndex) {

        clearSphereSelection();
        clearCubeSelection();
        clearWedgeSelection();
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
            selectedFormationField = null;
        }
        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
            selectedFractureRow = null;
        }

        TextRow row = textRowAt(textIndex);
        selectTextRow(row);
        if (row == null) {
            return;
        }
        if (formAccordion != null && textSection != null) {
            textSection.setExpanded(true);
            formAccordion.setExpandedPane(textSection);
        }
        row.contentField.requestFocus();
        scrollToNode(row.card);
    }

    /** 从图表中选中地层界面时调用：展开「地层数据」并高亮对应深度输入框。 */
    public void revealInterface(int interfaceIndex) {

        if (interfaceIndex < 0 || interfaceIndex >= depthFields.size()) {
            return;
        }
        revealFormationField(depthFields.get(interfaceIndex));
    }

    /** 从图表中选中地层色块时调用：展开「地层数据」并高亮对应层电阻率输入框。 */
    public void revealLayer(int layerIndex) {

        if (layerIndex < 0 || layerIndex >= rhoFields.size()) {
            return;
        }
        revealFormationField(rhoFields.get(layerIndex));
    }

    private void revealFormationField(TextField field) {

        clearSphereSelection();
        clearCubeSelection();
        clearWedgeSelection();
        clearTextSelection();
        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
            selectedFractureRow = null;
        }
        if (selectedFormationField != null) {
            selectedFormationField.getStyleClass().remove("parameter-highlight");
        }
        selectedFormationField = field;
        if (!field.getStyleClass().contains("parameter-highlight")) {
            field.getStyleClass().add("parameter-highlight");
        }

        if (formAccordion != null && formationSection != null) {
            formationSection.setExpanded(true);
            formAccordion.setExpandedPane(formationSection);
        }
        field.requestFocus();
        scrollToNode(field);
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

        wellGrid.add(caption("井眼半径 / in"), 0, 0);
        wellGrid.add(radiusField, 1, 0);
        radiusField.setId("wellRadiusInches");
        radiusField.setTooltip(new Tooltip("半径仅按整数英寸显示，计算保留精确米制值；手动修改按实际输入的英寸换算，显示取整不改变计算值；第二页井径为直径"));
        wellGrid.add(caption("井眼电阻率 / Ω·m"), 0, 1);
        wellGrid.add(wellRhoField, 1, 1);
        wellGrid.add(caption("介电常数 εr"), 0, 2);
        wellGrid.add(wellDielectricField, 1, 2);

        for (TextField field : new TextField[]{radiusField, wellRhoField, wellDielectricField}) {
            field.setPrefWidth(120);
            field.getStyleClass().add("form-input");
            restrictToNumber(field);
            field.textProperty().addListener((o, was, is) -> {
                if (field == radiusField && !updatingRadiusDisplay) {
                    radiusEdited = true;
                }
                onDataChanged.run();
            });
        }

        radiusField.setOnAction(event -> roundRadiusInput());
        radiusField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                roundRadiusInput();
            }
        });

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
        interfaceBlock.setMinWidth(Region.USE_PREF_SIZE);
        rhoBlock.setMinWidth(Region.USE_PREF_SIZE);

        // 地层界面表与层电阻率表并排放到同一行，顶部对齐
        HBox tablesRow = new HBox(10, interfaceBlock, rhoBlock);
        tablesRow.setAlignment(Pos.TOP_LEFT);

        VBox formationContent = new VBox(10, formationSettings, tablesRow);
        formationContent.setMinWidth(Region.USE_PREF_SIZE);
        ScrollPane formationScroll = new ScrollPane(formationContent);
        formationScroll.setId("formationScroll");
        formationScroll.setMinWidth(0);
        formationScroll.setMinHeight(Region.USE_PREF_SIZE);
        formationScroll.setFitToHeight(true);
        formationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        formationScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formationScroll.getStyleClass().add("form-scroll");

        TitledPane wellSection = new TitledPane("井数据", wellGrid);
        wellSection.getStyleClass().add("titled-pane");

        formationSection = new TitledPane("地层数据", formationScroll);
        formationSection.getStyleClass().add("titled-pane");

        fractureSection = buildFractureSection();
        anomalySection = buildAnomalySection();
        textSection = buildTextSection();
        TitledPane wallSection = buildUnfoldSection();

        Accordion accordion = new Accordion(
                wellSection, formationSection, fractureSection, anomalySection,
                textSection, wallSection);
        formAccordion = accordion;
        accordion.setMinWidth(0);
        // 展开的分组必须按内容高度撑开，再由外层 ScrollPane 负责滚动，不能被视口压扁裁切。
        accordion.setMinHeight(Region.USE_PREF_SIZE);

        // 一进来保持折叠，参数较多时不会把左侧面板撑满
        wellSection.setExpanded(false);
        formationSection.setExpanded(false);
        fractureSection.setExpanded(false);
        anomalySection.setExpanded(false);
        textSection.setExpanded(false);
        wallSection.setExpanded(false);
        accordion.setExpandedPane(null);

        VBox form = new VBox(4, accordion);
        form.setMinWidth(0);
        form.setPadding(new Insets(0, 14, 18, 14));

        ScrollPane scroll = new ScrollPane(form);
        formScroll = scroll;
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
                        + "R1 同时用于两幅图中的裂缝颜色分级。"
                        + "仰角和方位角可输入负数或任意周期等价角度。");
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

        FlowPane actions = new FlowPane(6, 6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, hint, actions, fractureRowsBox);
        TitledPane pane = new TitledPane("裂缝", content);
        pane.getStyleClass().add("titled-pane");

        if (fractureRows.isEmpty()) {
            addFractureRow(null);
        }
        return pane;
    }

    /** 独立的异常体参数分组，包含球形、正方体和楔形体异常体。 */
    private TitledPane buildAnomalySection() {

        Label hint = new Label(
                "对应 formation_G 第 4、5、6 节，分别录入球形、正方体和楔形体异常体；"
                        + "三者均绘制在三维图和井壁展开图中。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(8, hint,
                buildSphereSection(), new Separator(), buildCubeSection(),
                new Separator(), buildWedgeSection());
        TitledPane pane = new TitledPane("异常体", content);
        pane.getStyleClass().add("titled-pane");
        return pane;
    }

    /** 异常体分组中的球形异常体输入区，可添加多条。 */
    private VBox buildSphereSection() {

        Label title = new Label("球形异常体（圆形）");
        title.getStyleClass().add("section-title");

        Label hint = new Label(
                "对应 formation_G 第 4 节，每条球体同时绘制在三维图和井壁展开图。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        Button addButton = smallButton("＋ 添加球形异常体");
        addButton.setOnAction(e -> {
            if (sphereRows.size() >= 20) {
                showError("球形异常体最多 20 个");
                return;
            }
            addSphereRow(null);
            onDataChanged.run();
        });

        Button removeButton = smallButton("－ 删除最后一个球形异常体");
        removeButton.setOnAction(e -> {
            if (!sphereRows.isEmpty()) {
                removeSphereRow(sphereRows.get(sphereRows.size() - 1));
            }
        });

        FlowPane actions = new FlowPane(6, 6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, title, hint, actions, sphereRowsBox);
    }

    private void rebuildSphereRows(List<SphereFracture> spheres) {

        sphereRows.clear();
        sphereRowsBox.getChildren().clear();
        selectedSphereRow = null;

        for (SphereFracture sphere : spheres) {
            addSphereRow(sphere);
        }
        renumberSphereRows();
    }

    private List<SphereFracture> readSphereFractures() {

        List<SphereFracture> spheres = new ArrayList<>();
        for (int i = 0; i < sphereRows.size(); i++) {
            SphereFracture sphere = sphereRows.get(i).toSphere(i + 1);
            if (sphere != null) {
                spheres.add(sphere);
            }
        }
        return spheres;
    }

    private void addSphereRow(SphereFracture sphere) {

        SphereRow row = new SphereRow(sphere);
        row.deleteButton.setOnAction(e -> removeSphereRow(row));
        sphereRows.add(row);
        sphereRowsBox.getChildren().add(row.card);
        renumberSphereRows();
    }

    private void removeSphereRow(SphereRow row) {

        sphereRows.remove(row);
        sphereRowsBox.getChildren().remove(row.card);
        if (selectedSphereRow == row) {
            selectedSphereRow = null;
        }
        renumberSphereRows();
        onDataChanged.run();
    }

    private void renumberSphereRows() {
        for (int i = 0; i < sphereRows.size(); i++) {
            sphereRows.get(i).title.setText("球形异常体 " + (i + 1));
        }
    }

    private SphereRow sphereRowAt(int visibleIndex) {

        if (visibleIndex < 0) {
            return null;
        }
        int current = 0;
        for (SphereRow row : sphereRows) {
            if (row.allBlank()) {
                continue;
            }
            if (current == visibleIndex) {
                return row;
            }
            current++;
        }
        return null;
    }

    private void selectSphereRow(SphereRow selected) {

        clearSphereSelection();
        selectedSphereRow = selected;
        if (selected != null
                && !selected.card.getStyleClass().contains("fracture-card-selected")) {
            selected.card.getStyleClass().add("fracture-card-selected");
        }
    }

    private void clearSphereSelection() {
        if (selectedSphereRow != null) {
            selectedSphereRow.card.getStyleClass().remove("fracture-card-selected");
            selectedSphereRow = null;
        }
    }

    /** 异常体分组中的正方体异常体输入区。 */
    private VBox buildCubeSection() {

        Label title = new Label("正方体异常体");
        title.getStyleClass().add("section-title");

        Label hint = new Label(
                "对应 formation_G 第 5 节，5 个数依次为：中心深度、方位角、"
                        + "距井轴水平距离、棱长、电阻率；同时绘制在三维图和井壁展开图。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        Button addButton = smallButton("＋ 添加正方体异常体");
        addButton.setOnAction(e -> {
            if (cubeRows.size() >= 20) {
                showError("正方体异常体最多 20 个");
                return;
            }
            addCubeRow(null);
            onDataChanged.run();
        });

        Button removeButton = smallButton("－ 删除最后一个正方体异常体");
        removeButton.setOnAction(e -> {
            if (!cubeRows.isEmpty()) {
                removeCubeRow(cubeRows.get(cubeRows.size() - 1));
            }
        });

        FlowPane actions = new FlowPane(6, 6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, title, hint, actions, cubeRowsBox);
    }

    private void rebuildCubeRows(List<CubeAnomaly> cubes) {

        cubeRows.clear();
        cubeRowsBox.getChildren().clear();
        selectedCubeRow = null;

        for (CubeAnomaly cube : cubes) {
            addCubeRow(cube);
        }
        renumberCubeRows();
    }

    private List<CubeAnomaly> readCubeAnomalies() {

        List<CubeAnomaly> cubes = new ArrayList<>();
        for (int i = 0; i < cubeRows.size(); i++) {
            CubeAnomaly cube = cubeRows.get(i).toCube(i + 1);
            if (cube != null) {
                cubes.add(cube);
            }
        }
        return cubes;
    }

    private void addCubeRow(CubeAnomaly cube) {

        CubeRow row = new CubeRow(cube);
        row.deleteButton.setOnAction(e -> removeCubeRow(row));
        cubeRows.add(row);
        cubeRowsBox.getChildren().add(row.card);
        renumberCubeRows();
    }

    private void removeCubeRow(CubeRow row) {

        cubeRows.remove(row);
        cubeRowsBox.getChildren().remove(row.card);
        if (selectedCubeRow == row) {
            selectedCubeRow = null;
        }
        renumberCubeRows();
        onDataChanged.run();
    }

    private void renumberCubeRows() {
        for (int i = 0; i < cubeRows.size(); i++) {
            cubeRows.get(i).title.setText("正方体异常体 " + (i + 1));
        }
    }

    private CubeRow cubeRowAt(int visibleIndex) {

        if (visibleIndex < 0) {
            return null;
        }
        int current = 0;
        for (CubeRow row : cubeRows) {
            if (row.allBlank()) {
                continue;
            }
            if (current == visibleIndex) {
                return row;
            }
            current++;
        }
        return null;
    }

    private void selectCubeRow(CubeRow selected) {

        clearCubeSelection();
        selectedCubeRow = selected;
        if (selected != null
                && !selected.card.getStyleClass().contains("fracture-card-selected")) {
            selected.card.getStyleClass().add("fracture-card-selected");
        }
    }

    private void clearCubeSelection() {
        if (selectedCubeRow != null) {
            selectedCubeRow.card.getStyleClass().remove("fracture-card-selected");
            selectedCubeRow = null;
        }
    }

    /** 异常体分组中的楔形体异常体输入区。 */
    private VBox buildWedgeSection() {

        Label title = new Label("楔形体异常体（三角）");
        title.getStyleClass().add("section-title");

        Label hint = new Label(
                "对应 formation_G 第 6 节，7 个数依次为顶点方位角/深度、"
                        + "左底方位角/深度、右底方位角/深度、电阻率；同时绘制在三维图和井壁展开图。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        Button addButton = smallButton("＋ 添加楔形体异常体");
        addButton.setOnAction(e -> {
            if (wedgeRows.size() >= 20) {
                showError("楔形体异常体最多 20 个");
                return;
            }
            addWedgeRow(null);
            onDataChanged.run();
        });

        Button removeButton = smallButton("－ 删除最后一个楔形体异常体");
        removeButton.setOnAction(e -> {
            if (!wedgeRows.isEmpty()) {
                removeWedgeRow(wedgeRows.get(wedgeRows.size() - 1));
            }
        });

        HBox actions = new HBox(6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, title, hint, actions, wedgeRowsBox);
    }

    private void rebuildWedgeRows(List<WedgeFracture> wedges) {

        wedgeRows.clear();
        wedgeRowsBox.getChildren().clear();
        selectedWedgeRow = null;

        for (WedgeFracture wedge : wedges) {
            addWedgeRow(wedge);
        }
        renumberWedgeRows();
    }

    private List<WedgeFracture> readWedgeFractures() {

        List<WedgeFracture> wedges = new ArrayList<>();
        for (int i = 0; i < wedgeRows.size(); i++) {
            WedgeFracture wedge = wedgeRows.get(i).toWedge(i + 1);
            if (wedge != null) {
                wedges.add(wedge);
            }
        }
        return wedges;
    }

    private void addWedgeRow(WedgeFracture wedge) {

        WedgeRow row = new WedgeRow(wedge);
        row.deleteButton.setOnAction(e -> removeWedgeRow(row));
        wedgeRows.add(row);
        wedgeRowsBox.getChildren().add(row.card);
        renumberWedgeRows();
    }

    private void removeWedgeRow(WedgeRow row) {

        wedgeRows.remove(row);
        wedgeRowsBox.getChildren().remove(row.card);
        if (selectedWedgeRow == row) {
            selectedWedgeRow = null;
        }
        renumberWedgeRows();
        onDataChanged.run();
    }

    private void renumberWedgeRows() {
        for (int i = 0; i < wedgeRows.size(); i++) {
            wedgeRows.get(i).title.setText("楔形体异常体 " + (i + 1));
        }
    }

    private WedgeRow wedgeRowAt(int visibleIndex) {

        if (visibleIndex < 0) {
            return null;
        }
        int current = 0;
        for (WedgeRow row : wedgeRows) {
            if (row.allBlank()) {
                continue;
            }
            if (current == visibleIndex) {
                return row;
            }
            current++;
        }
        return null;
    }

    private void selectWedgeRow(WedgeRow selected) {

        clearWedgeSelection();
        selectedWedgeRow = selected;
        if (selected != null
                && !selected.card.getStyleClass().contains("fracture-card-selected")) {
            selected.card.getStyleClass().add("fracture-card-selected");
        }
    }

    private void clearWedgeSelection() {
        if (selectedWedgeRow != null) {
            selectedWedgeRow.card.getStyleClass().remove("fracture-card-selected");
            selectedWedgeRow = null;
        }
    }

    /** 独立的曲面文字参数分组。 */
    private TitledPane buildTextSection() {

        Label hint = new Label(
                "对应 formation_G 第 7 节：中心方位角、中心深度、横向跨度角度、"
                        + "纵向跨度高度、电阻率；可选文字内容写在五个数字之后。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        Button addButton = smallButton("＋ 添加文字");
        addButton.setOnAction(e -> {
            if (textRows.size() >= 20) {
                showError("曲面文字最多 20 条");
                return;
            }
            addTextRow(null);
            onDataChanged.run();
        });

        Button removeButton = smallButton("－ 删除最后一条文字");
        removeButton.setOnAction(e -> {
            if (!textRows.isEmpty()) {
                removeTextRow(textRows.get(textRows.size() - 1));
            }
        });

        HBox actions = new HBox(6, addButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, hint, actions, textRowsBox);
        TitledPane pane = new TitledPane("曲面文字", content);
        pane.getStyleClass().add("titled-pane");
        return pane;
    }

    private void rebuildTextRows(List<SurfaceText> texts) {

        textRows.clear();
        textRowsBox.getChildren().clear();
        selectedTextRow = null;

        for (SurfaceText text : texts) {
            addTextRow(text);
        }
        renumberTextRows();
    }

    private List<SurfaceText> readTexts() {

        List<SurfaceText> texts = new ArrayList<>();
        for (int i = 0; i < textRows.size(); i++) {
            SurfaceText text = textRows.get(i).toText(i + 1);
            if (text != null) {
                texts.add(text);
            }
        }
        return texts;
    }

    private void addTextRow(SurfaceText text) {

        TextRow row = new TextRow(text);
        row.deleteButton.setOnAction(e -> removeTextRow(row));
        textRows.add(row);
        textRowsBox.getChildren().add(row.card);
        renumberTextRows();
    }

    private void removeTextRow(TextRow row) {

        textRows.remove(row);
        textRowsBox.getChildren().remove(row.card);
        if (selectedTextRow == row) {
            selectedTextRow = null;
        }
        renumberTextRows();
        onDataChanged.run();
    }

    private void renumberTextRows() {
        for (int i = 0; i < textRows.size(); i++) {
            textRows.get(i).title.setText("文字 " + (i + 1));
        }
    }

    private TextRow textRowAt(int visibleIndex) {

        if (visibleIndex < 0) {
            return null;
        }
        int current = 0;
        for (TextRow row : textRows) {
            if (row.allBlank()) {
                continue;
            }
            if (current == visibleIndex) {
                return row;
            }
            current++;
        }
        return null;
    }

    private void selectTextRow(TextRow selected) {

        clearTextSelection();
        selectedTextRow = selected;
        if (selected != null
                && !selected.card.getStyleClass().contains("fracture-card-selected")) {
            selected.card.getStyleClass().add("fracture-card-selected");
        }
    }

    private void clearTextSelection() {
        if (selectedTextRow != null) {
            selectedTextRow.card.getStyleClass().remove("fracture-card-selected");
            selectedTextRow = null;
        }
    }

    private void rebuildFractureRows(List<Fracture> fractures) {

        fractureRows.clear();
        fractureRowsBox.getChildren().clear();
        selectedFractureRow = null;

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
        if (selectedFractureRow == row) {
            selectedFractureRow = null;
        }
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

    /** 找到第 visibleIndex 条非空裂缝对应的参数卡片。 */
    private FractureRow fractureRowAt(int visibleIndex) {

        if (visibleIndex < 0) {
            return null;
        }

        int current = 0;
        for (FractureRow row : fractureRows) {
            if (row.allBlank()) {
                continue;
            }
            if (current == visibleIndex) {
                return row;
            }
            current++;
        }
        return null;
    }

    private void selectFractureRow(FractureRow selected) {

        if (selectedFractureRow != null) {
            selectedFractureRow.card.getStyleClass().remove("fracture-card-selected");
        }
        selectedFractureRow = selected;
        if (selected != null
                && !selected.card.getStyleClass().contains("fracture-card-selected")) {
            selected.card.getStyleClass().add("fracture-card-selected");
        }
    }

    /** 等分组完成展开和布局后，再把目标参数滚到左侧面板可视区内。 */
    private void scrollToNode(Node node) {

        selectionScrollDelay.stop();
        selectionScrollDelay.setOnFinished(event -> Platform.runLater(() -> {
            if (formScroll == null || formScroll.getContent() == null
                    || node.getScene() == null) {
                return;
            }

            Node content = formScroll.getContent();
            formScroll.applyCss();
            formScroll.layout();

            Bounds nodeBounds = node.localToScene(node.getBoundsInLocal());
            Bounds contentBounds = content.localToScene(content.getBoundsInLocal());
            double contentHeight = contentBounds.getHeight();
            double viewportHeight = formScroll.getViewportBounds().getHeight();
            if (contentHeight <= viewportHeight || viewportHeight <= 0) {
                return;
            }

            double nodeTop = nodeBounds.getMinY() - contentBounds.getMinY();
            double target = (nodeTop - viewportHeight * 0.25)
                    / (contentHeight - viewportHeight);
            formScroll.setVvalue(Math.max(0, Math.min(1, target)));
        }));
        selectionScrollDelay.play();
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
        confirmDataButton.setOnAction(e -> {
            roundRadiusInput();
            onConfirmData.run();
        });

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
            rebuildRows(count, currentTexts(depthFields), currentTexts(rhoFields),
                    currentTexts(dielectricFields));
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
        List<String> dielectricConstants = currentTexts(dielectricFields);

        int count = depths.size();

        if (count >= MAX_INTERFACES) {
            showError("界面最多 " + MAX_INTERFACES + " 条");
            return;
        }

        // 新界面默认接在最后一条下面 0.2 m
        depths.add(plain(round(lastNumber(depths, 1.0) + 0.2)));
        // 新层默认沿用最深那层的电阻率和介电常数
        rhos.add(rhos.isEmpty() ? "10" : rhos.get(rhos.size() - 1));
        dielectricConstants.add(dielectricConstants.isEmpty()
                ? plain(DielectricConstant.VALUE)
                : dielectricConstants.get(dielectricConstants.size() - 1));

        countField.setText(String.valueOf(count + 1));
        rebuildRows(count + 1, depths, rhos, dielectricConstants);
        clearMessage();
        onDataChanged.run();
    }

    /** 删掉最后一条界面，同时删掉最深的那一层电阻率。 */
    private void removeInterface() {

        int count = depthFields.size();

        if (count == 0) {
            showError("当前没有可删除的界面");
            return;
        }

        List<String> depths = currentTexts(depthFields);
        List<String> rhos = currentTexts(rhoFields);
        List<String> dielectricConstants = currentTexts(dielectricFields);

        depths.remove(count - 1);
        if (!rhos.isEmpty()) {
            rhos.remove(rhos.size() - 1);
        }
        if (!dielectricConstants.isEmpty()) {
            dielectricConstants.remove(dielectricConstants.size() - 1);
        }

        countField.setText(String.valueOf(count - 1));
        rebuildRows(count - 1, depths, rhos, dielectricConstants);
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

    private void rebuildRows(int count, List<String> depths, List<String> rhos,
                             List<String> dielectricConstants) {

        depthFields.clear();
        rhoFields.clear();
        dielectricFields.clear();
        selectedFormationField = null;

        rebuildInterfaceGrid(count, depths);
        rebuildLayerGrid(count, rhos, dielectricConstants);
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

    /** 电阻率表：层号 / R0 / εr，层数恒为 n + 1。 */
    private void rebuildLayerGrid(int count, List<String> rhos,
                                  List<String> dielectricConstants) {

        layerGrid.getChildren().clear();
        layerGrid.setHgap(8);
        layerGrid.setVgap(5);
        layerGrid.getColumnConstraints().setAll(
                column(36, HPos.LEFT), flexColumn(80, HPos.LEFT),
                column(54, HPos.LEFT));

        layerGrid.add(header("层号"), 0, 0);
        layerGrid.add(header("R0 / (Ω·m)"), 1, 0);
        layerGrid.add(header("εr"), 2, 0);

        for (int i = 0; i < count + 1; i++) {

            TextField rho = numberField(at(rhos, i), 96);
            TextField dielectric = numberField(at(dielectricConstants, i), 60);
            rhoFields.add(rho);
            dielectricFields.add(dielectric);

            layerGrid.add(indexLabel(String.valueOf(i + 1)), 0, i + 1);
            layerGrid.add(rho, 1, i + 1);
            layerGrid.add(dielectric, 2, i + 1);
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

        if (value != Math.rint(value) || value < 0 || value > MAX_INTERFACES) {
            throw new IllegalArgumentException(
                    "界面个数 n 需要是 0 ~ " + MAX_INTERFACES + " 之间的整数");
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

    private static double readPositiveNumber(String text, String what) {
        double value = readNumber(text, what);
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(what + " 必须是大于 0 的数");
        }
        return value;
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
        field.setMinWidth(0);
        field.setPrefWidth(width);
        field.setMaxWidth(Double.MAX_VALUE);
        field.getStyleClass().add("form-input");
        restrictToNumber(field);

        // 新建时文本已经填好了，所以这里才挂监听，不会在建表时白跑一趟
        field.textProperty().addListener((o, was, is) -> onDataChanged.run());
        return field;
    }

    private double readRadiusMeters() {
        readPositiveNumber(radiusField.getText(), "井眼半径 / in");
        if (radiusEdited || !Double.isFinite(radiusMeters)) {
            return new BigDecimal(radiusField.getText().trim())
                    .multiply(METERS_PER_INCH).doubleValue();
        }
        return radiusMeters;
    }

    private void setRadiusMeters(double value) {
        String displayedInches = BigDecimal.valueOf(value)
                .divide(METERS_PER_INCH, 0, RoundingMode.CEILING).toPlainString();
        radiusMeters = value;
        radiusEdited = false;
        updatingRadiusDisplay = true;
        try {
            radiusField.getTooltip().setText("显示值向上取整，实际计算半径：" + plain(value)
                    + " m；手动修改按实际输入的英寸换算，显示取整不改变计算值；第二页井径为直径");
            radiusField.setText(displayedInches);
        } finally {
            updatingRadiusDisplay = false;
        }
    }

    private void roundRadiusInput() {
        double value = parseOrNaN(radiusField.getText());
        if (Double.isFinite(value) && value > 0) {
            setRadiusMeters(readRadiusMeters());
        }
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
        private final TextField dielectricField;

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
            dielectricField = numberField(fracture == null
                    ? plain(DielectricConstant.VALUE)
                    : plain(fracture.dielectricConstant()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(90, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "交点深度 / m", depthField);
            addRow(grid, 1, "法向仰角 / °", elevationField);
            addRow(grid, 2, "法向方位角 / °", azimuthField);
            addRow(grid, 3, "R1 / (Ω·m)", resistivityField);
            addRow(grid, 4, "介电常数 εr", dielectricField);

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
                            "第 " + index + " 条裂缝电阻率 R1"),
                    readPositiveNumber(dielectricField.getText(),
                            "第 " + index + " 条裂缝介电常数 εr"));
        }

        private boolean allBlank() {
            return depthField.getText().isBlank()
                    && elevationField.getText().isBlank()
                    && azimuthField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    private final class SphereRow {

        private final VBox card = new VBox(6);
        private final Label title = new Label();
        private final Button deleteButton = smallButton("删除");

        private final TextField depthField;
        private final TextField azimuthField;
        private final TextField distanceField;
        private final TextField radiusField;
        private final TextField resistivityField;
        private final TextField dielectricField;

        private SphereRow(SphereFracture sphere) {

            title.getStyleClass().add("section-title");

            HBox header = new HBox(6, title, new Region(), deleteButton);
            HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);

            depthField = numberField(
                    sphere == null ? "" : plain(sphere.centerDepth()), 86);
            azimuthField = numberField(
                    sphere == null ? "" : plain(sphere.azimuthDeg()), 86);
            distanceField = numberField(
                    sphere == null ? "" : plain(sphere.distanceFromAxis()), 86);
            radiusField = numberField(
                    sphere == null ? "" : plain(sphere.radius()), 86);
            resistivityField = numberField(
                    sphere == null ? "" : plain(sphere.resistivity()), 86);
            dielectricField = numberField(sphere == null
                    ? plain(DielectricConstant.VALUE)
                    : plain(sphere.dielectricConstant()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(118, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "中心深度 / m", depthField);
            addRow(grid, 1, "方位角 / °", azimuthField);
            addRow(grid, 2, "距井轴水平距离 / m", distanceField);
            addRow(grid, 3, "球半径 / m", radiusField);
            addRow(grid, 4, "电阻率 / (Ω·m)", resistivityField);
            addRow(grid, 5, "介电常数 εr", dielectricField);

            card.getStyleClass().addAll("fracture-card", "sphere-fracture-card");
            card.getChildren().addAll(header, grid);
        }

        private SphereFracture toSphere(int index) {

            if (allBlank()) {
                return null;
            }
            return new SphereFracture(
                    readNumber(depthField.getText(),
                            "第 " + index + " 个球体中心深度"),
                    readNumber(azimuthField.getText(),
                            "第 " + index + " 个球体方位角"),
                    readNumber(distanceField.getText(),
                            "第 " + index + " 个球体距井轴水平距离"),
                    readNumber(radiusField.getText(),
                            "第 " + index + " 个球体半径"),
                    readNumber(resistivityField.getText(),
                            "第 " + index + " 个球体电阻率"),
                    readPositiveNumber(dielectricField.getText(),
                            "第 " + index + " 个球体介电常数 εr"));
        }

        private boolean allBlank() {
            return depthField.getText().isBlank()
                    && azimuthField.getText().isBlank()
                    && distanceField.getText().isBlank()
                    && radiusField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    private final class CubeRow {

        private final VBox card = new VBox(6);
        private final Label title = new Label();
        private final Button deleteButton = smallButton("删除");

        private final TextField depthField;
        private final TextField azimuthField;
        private final TextField distanceField;
        private final TextField sideLengthField;
        private final TextField resistivityField;
        private final TextField dielectricField;

        private CubeRow(CubeAnomaly cube) {

            title.getStyleClass().add("section-title");

            HBox header = new HBox(6, title, new Region(), deleteButton);
            HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);

            depthField = numberField(
                    cube == null ? "" : plain(cube.centerDepth()), 86);
            azimuthField = numberField(
                    cube == null ? "" : plain(cube.azimuthDeg()), 86);
            distanceField = numberField(
                    cube == null ? "" : plain(cube.distanceFromAxis()), 86);
            sideLengthField = numberField(
                    cube == null ? "" : plain(cube.sideLength()), 86);
            resistivityField = numberField(
                    cube == null ? "" : plain(cube.resistivity()), 86);
            dielectricField = numberField(cube == null
                    ? plain(DielectricConstant.VALUE)
                    : plain(cube.dielectricConstant()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(118, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "中心深度 / m", depthField);
            addRow(grid, 1, "方位角 / °", azimuthField);
            addRow(grid, 2, "距井轴水平距离 / m", distanceField);
            addRow(grid, 3, "棱长 / m", sideLengthField);
            addRow(grid, 4, "电阻率 / (Ω·m)", resistivityField);
            addRow(grid, 5, "介电常数 εr", dielectricField);

            card.getStyleClass().add("fracture-card");
            card.getChildren().addAll(header, grid);
        }

        private CubeAnomaly toCube(int index) {

            if (allBlank()) {
                return null;
            }
            return new CubeAnomaly(
                    readNumber(depthField.getText(),
                            "第 " + index + " 个正方体中心深度"),
                    readNumber(azimuthField.getText(),
                            "第 " + index + " 个正方体方位角"),
                    readNumber(distanceField.getText(),
                            "第 " + index + " 个正方体距井轴水平距离"),
                    readNumber(sideLengthField.getText(),
                            "第 " + index + " 个正方体棱长"),
                    readNumber(resistivityField.getText(),
                            "第 " + index + " 个正方体电阻率"),
                    readPositiveNumber(dielectricField.getText(),
                            "第 " + index + " 个正方体介电常数 εr"));
        }

        private boolean allBlank() {
            return depthField.getText().isBlank()
                    && azimuthField.getText().isBlank()
                    && distanceField.getText().isBlank()
                    && sideLengthField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    private final class WedgeRow {

        private final VBox card = new VBox(6);
        private final Label title = new Label();
        private final Button deleteButton = smallButton("删除");

        private final TextField vertexAzimuthField;
        private final TextField vertexDepthField;
        private final TextField leftAzimuthField;
        private final TextField leftDepthField;
        private final TextField rightAzimuthField;
        private final TextField rightDepthField;
        private final TextField resistivityField;
        private final TextField dielectricField;

        private WedgeRow(WedgeFracture wedge) {

            title.getStyleClass().add("section-title");

            HBox header = new HBox(6, title, new Region(), deleteButton);
            HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);

            vertexAzimuthField = numberField(
                    wedge == null ? "" : plain(wedge.vertexAzimuthDeg()), 86);
            vertexDepthField = numberField(
                    wedge == null ? "" : plain(wedge.vertexDepth()), 86);
            leftAzimuthField = numberField(
                    wedge == null ? "" : plain(wedge.leftAzimuthDeg()), 86);
            leftDepthField = numberField(
                    wedge == null ? "" : plain(wedge.leftDepth()), 86);
            rightAzimuthField = numberField(
                    wedge == null ? "" : plain(wedge.rightAzimuthDeg()), 86);
            rightDepthField = numberField(
                    wedge == null ? "" : plain(wedge.rightDepth()), 86);
            resistivityField = numberField(
                    wedge == null ? "" : plain(wedge.resistivity()), 86);
            dielectricField = numberField(wedge == null
                    ? plain(DielectricConstant.VALUE)
                    : plain(wedge.dielectricConstant()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(112, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "顶点方位角 / °", vertexAzimuthField);
            addRow(grid, 1, "顶点深度 / m", vertexDepthField);
            addRow(grid, 2, "左底方位角 / °", leftAzimuthField);
            addRow(grid, 3, "左底深度 / m", leftDepthField);
            addRow(grid, 4, "右底方位角 / °", rightAzimuthField);
            addRow(grid, 5, "右底深度 / m", rightDepthField);
            addRow(grid, 6, "电阻率 / (Ω·m)", resistivityField);
            addRow(grid, 7, "介电常数 εr", dielectricField);

            card.getStyleClass().add("fracture-card");
            card.getChildren().addAll(header, grid);
        }

        private WedgeFracture toWedge(int index) {

            if (allBlank()) {
                return null;
            }
            return new WedgeFracture(
                    readNumber(vertexAzimuthField.getText(),
                            "第 " + index + " 个楔形体顶点方位角"),
                    readNumber(vertexDepthField.getText(),
                            "第 " + index + " 个楔形体顶点深度"),
                    readNumber(leftAzimuthField.getText(),
                            "第 " + index + " 个楔形体左底方位角"),
                    readNumber(leftDepthField.getText(),
                            "第 " + index + " 个楔形体左底深度"),
                    readNumber(rightAzimuthField.getText(),
                            "第 " + index + " 个楔形体右底方位角"),
                    readNumber(rightDepthField.getText(),
                            "第 " + index + " 个楔形体右底深度"),
                    readNumber(resistivityField.getText(),
                            "第 " + index + " 个楔形体电阻率"),
                    readPositiveNumber(dielectricField.getText(),
                            "第 " + index + " 个楔形体介电常数 εr"));
        }

        private boolean allBlank() {
            return vertexAzimuthField.getText().isBlank()
                    && vertexDepthField.getText().isBlank()
                    && leftAzimuthField.getText().isBlank()
                    && leftDepthField.getText().isBlank()
                    && rightAzimuthField.getText().isBlank()
                    && rightDepthField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    private final class TextRow {

        private final VBox card = new VBox(6);
        private final Label title = new Label();
        private final Button deleteButton = smallButton("删除");

        private final TextField contentField = new TextField();
        private final TextField azimuthField;
        private final TextField depthField;
        private final TextField angularSpanField;
        private final TextField depthSpanField;
        private final TextField resistivityField;
        private final TextField dielectricField;

        private TextRow(SurfaceText text) {

            title.getStyleClass().add("section-title");

            HBox header = new HBox(6, title, new Region(), deleteButton);
            HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);

            contentField.setText(text == null ? "" : text.content());
            contentField.getStyleClass().add("form-input");
            contentField.setPrefWidth(180);
            contentField.textProperty().addListener((o, was, is) -> onDataChanged.run());

            azimuthField = numberField(
                    text == null ? "" : plain(text.centerAzimuthDeg()), 86);
            depthField = numberField(
                    text == null ? "" : plain(text.centerDepth()), 86);
            angularSpanField = numberField(
                    text == null ? "" : plain(text.angularSpanDeg()), 86);
            depthSpanField = numberField(
                    text == null ? "" : plain(text.depthSpanM()), 86);
            resistivityField = numberField(
                    text == null ? "" : plain(text.resistivity()), 86);
            dielectricField = numberField(text == null
                    ? plain(DielectricConstant.VALUE)
                    : plain(text.dielectricConstant()), 86);

            GridPane grid = new GridPane();
            grid.setHgap(7);
            grid.setVgap(6);
            grid.getColumnConstraints().addAll(
                    column(112, HPos.LEFT), column(86, HPos.LEFT));

            addRow(grid, 0, "文字内容", contentField);
            addRow(grid, 1, "中心方位角 / °", azimuthField);
            addRow(grid, 2, "中心深度 / m", depthField);
            addRow(grid, 3, "横向跨度角度 / °", angularSpanField);
            addRow(grid, 4, "纵向跨度高度 / m", depthSpanField);
            addRow(grid, 5, "电阻率 / (Ω·m)", resistivityField);
            addRow(grid, 6, "介电常数 εr", dielectricField);

            card.getStyleClass().add("fracture-card");
            card.getChildren().addAll(header, grid);
        }

        private SurfaceText toText(int index) {

            if (allBlank()) {
                return null;
            }
            String content = contentField.getText().isBlank()
                    ? "文字" : contentField.getText().trim();
            return new SurfaceText(
                    content,
                    readNumber(azimuthField.getText(),
                            "第 " + index + " 条文字中心方位角"),
                    readNumber(depthField.getText(),
                            "第 " + index + " 条文字中心深度"),
                    readNumber(angularSpanField.getText(),
                            "第 " + index + " 条文字横向跨度角度"),
                    readNumber(depthSpanField.getText(),
                            "第 " + index + " 条文字纵向跨度高度"),
                    readNumber(resistivityField.getText(),
                            "第 " + index + " 条文字电阻率"),
                    readPositiveNumber(dielectricField.getText(),
                            "第 " + index + " 条文字介电常数 εr"));
        }

        private boolean allBlank() {
            return contentField.getText().isBlank()
                    && azimuthField.getText().isBlank()
                    && depthField.getText().isBlank()
                    && angularSpanField.getText().isBlank()
                    && depthSpanField.getText().isBlank()
                    && resistivityField.getText().isBlank();
        }
    }

    public record UnfoldInterval(double start, double end) {
    }
}
