# 地层剖面与井壁展开图

JavaFX 桌面程序：读入井眼与地层参数，绘制三维地层模型、岩性剖面和井壁展开图，并提供电成像响应模拟页面。

## 快速运行

需要 JDK 17（推荐 Microsoft OpenJDK 17），Maven 和 JavaFX 不需要单独安装：

```powershell
.\mvnw.cmd clean javafx:run
```

## 打包给他人演示

已生成免安装 Windows 演示包：

```text
target\StratigraphicMapping-Windows-x64.zip
```

对方解压后双击 `启动程序.bat` 即可，不需要安装 Java、Maven 或 JavaFX。

完整的环境、数据和求解器配置说明见：

- [运行与演示环境说明](docs/运行与演示环境说明.md)

## 测试

```powershell
.\mvnw.cmd test
```

## 说明

本项目仅用作实验室项目。
