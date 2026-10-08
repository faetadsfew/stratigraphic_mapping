module com.example.demo {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires java.desktop;

    // GBK 等扩展字符集在这个模块里，读 GBK 编码的参数文件要用到；
    // 写在这里 jlink 打包时才会带上（否则打包后的程序读不了 GBK 文件）
    requires jdk.charsets;

    exports com.example.demo;
    exports com.example.demo.model;
    exports com.example.demo.io;
    exports com.example.demo.chart;
    exports com.example.demo.ui;

    // FXML 控制器与带 fx:id 的字段靠反射注入，需要向 javafx.fxml 开放
    opens com.example.demo.ui to javafx.fxml;
}
