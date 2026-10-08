package com.stratigraphic.mapping;

import javafx.application.Application;

/**
 * 非 JavaFX 的启动类：从 IDE 或命令行直接跑这个 main 可以绕开模块路径的限制。
 */
public class Launcher {

    public static void main(String[] args) {
        Application.launch(StratigraphicMappingApplication.class, args);
    }
}
