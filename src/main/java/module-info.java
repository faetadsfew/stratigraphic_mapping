module com.stratigraphic.mapping {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires java.desktop;

    // GBK 等扩展字符集在这个模块里，读 GBK 编码的参数文件要用到；
    // 写在这里 jlink 打包时才会带上（否则打包后的程序读不了 GBK 文件）
    requires jdk.charsets;

    // 启动类由 JavaFX 反射实例化，所在包需要导出
    exports com.stratigraphic.mapping;

    // FXML 控制器与带 fx:id 的字段靠反射注入，需要向 javafx.fxml 开放
    opens com.stratigraphic.mapping.ui to javafx.fxml;
}
