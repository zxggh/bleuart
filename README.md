# 蓝牙串口调试助手 (Bluetooth Serial Assistant)

一款面向 **RS485 / 串口设备调试** 的 Android 蓝牙调试工具，同时支持 **经典蓝牙 (SPP)** 与 **BLE (低功耗蓝牙 UART)**，适用于各类蓝牙转串口 / 蓝牙转 RS485 模块（如 HC-05、HC-06、JDY-31、JDY-08、HM-10、JDY-23 等）。

## 功能特性

- **双协议支持**
  - 经典蓝牙：SPP (RFCOMM) 客户端，标准串口服务 UUID `00001101-0000-1000-8000-00805F9B34FB`，兼容失败时自动回退 channel=1 连接方式。
  - BLE：自动识别常见 UART 服务（Nordic UART Service / HM-10 `FFE0` / JDY-08 `FFE5`），支持手动指定 服务 / 写特征 / 通知特征 UUID。
- **RS485 调试**
  - 通过蓝牙转 RS485 模块与 485 总线设备通信（Modbus RTU / 自定义协议均可）。
  - 发送支持 **行尾选择**：无 / `\r` / `\n` / `\r\n`，Modbus RTU 请选「无」。
  - **定时发送**（可调间隔，最小 20ms），适合轮询从站。
- **自定义命令库**
  - 保存常用命令（名称 + 内容 + HEX/ASCII 格式），点击即发送，长按编辑/删除，本地持久化。
- **收发格式独立选择**
  - 接收区与发送区均可独立切换 **HEX / ASCII** 显示与编码。
  - HEX 输入自动忽略空格/逗号等分隔符，并校验偶数长度。
- **接收时间戳**
  - 每条接收数据带 `HH:mm:ss.SSS` 毫秒级时间戳，可一键开关。
  - 接收统计（字节数 / 条数）、暂停滚动、清空、复制。
- **其他**
  - 接收缓冲防溢出（自动裁剪，最长约 40 万字符）。
  - 页面重建（旋转屏幕等）自动回放最近接收数据，不丢包。
  - 自动滚动到底部，翻看历史时暂停滚动。

## 使用说明

1. 打开手机蓝牙，授予 App 所需权限（Android 12+ 授予「附近的设备」；Android 11 及以下授予定位权限）。
2. 进入「设备」页：
   - RS485 经典蓝牙适配器（HC-05/HC-06/JDY-31）→ 选择 **经典蓝牙** 扫描。
   - BLE 模块（JDY-08/HM-10/NUS）→ 选择 **BLE** 扫描。
3. 点击设备连接，自动跳转到「调试」页。
4. 在发送区输入内容（HEX 如 `01 03 00 00 00 02 C4 0B`，或 ASCII 文本），选择格式与行尾，点「发送」。
5. 需要轮询时勾选「定时发送」并设置间隔（ms）。

### Modbus RTU 快速上手

- 发送格式选 **HEX**，行尾选 **无**。
- CRC16 校验需要自行计算后附加在报文尾部（如 `01 03 00 00 00 02 C4 0B`）。
- 可用「定时发送」以固定间隔轮询寄存器。

### BLE 自定义 UUID

部分模块的 UART 服务不在自动识别列表内，可在调试页点击 **「UUID」** 按钮手动填写：

- 服务 UUID（必填，如 `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`）
- 写特征 UUID（可留空自动识别）
- 通知特征 UUID（可留空自动识别）

## 项目结构

```
app/src/main/java/com/example/bluetoothserial/
├── MainActivity.kt            # 主界面、底部导航、权限请求
├── bt/
│   ├── ConnectionManager.kt   # 连接状态/数据分发、发送队列
│   ├── ClassicSppClient.kt    # 经典蓝牙 SPP 客户端
│   ├── BleUartClient.kt       # BLE UART 客户端(自动/自定义 UUID)
│   └── BleUuidPrefs.kt        # 自定义 UUID 持久化
├── data/
│   ├── CustomCommand.kt       # 命令数据模型
│   └── CommandRepository.kt   # 命令库持久化(SharedPreferences+JSON)
├── model/DataFormat.kt        # HEX/ASCII 枚举
├── ui/
│   ├── ConsoleFragment.kt     # 调试页(收发控制台)
│   ├── DeviceFragment.kt      # 设备页(扫描/连接)
│   ├── CommandFragment.kt     # 命令页
│   ├── DeviceAdapter.kt       # 设备列表适配器
│   └── CommandAdapter.kt      # 命令列表适配器
└── util/
    ├── HexUtils.kt            # HEX/ASCII 转换
    └── TimeFormat.kt          # 时间戳格式化
```

## 构建

环境要求：

- Android Studio（建议 Ladybug 或更新版本）
- JDK 17
- Android SDK Platform 34

步骤：

1. 用 Android Studio 打开本项目根目录 `BluetoothSerialAssistant/`。
2. 等待 Gradle 同步完成（首次会自动下载依赖）。
3. 连接手机（开启 USB 调试）或使用模拟器，点击 Run 运行。
4. 命令行构建：`gradlew assembleDebug`，产物在 `app/build/outputs/apk/debug/`。

> 若提示缺少 Gradle Wrapper，可在 Android Studio 的 Gradle 设置中选择本地 Gradle，或执行 `gradle wrapper` 生成。

## 在线编译（无需本地环境）

项目已内置 **GitHub Actions** 工作流（`.github/workflows/build-apk.yml`），云端自动完成编译并产出 APK，全程不需要在本机安装 JDK / Android SDK / Gradle。

### 方式一：GitHub Actions（推荐，免费）

1. 在 [github.com](https://github.com) 注册账号，新建一个仓库（Public 或 Private 均可）。
2. 上传项目文件：可以用 git 推送，也可以直接在网页上 **Add file → Upload files** 把整个 `BluetoothSerialAssistant/` 目录**里面的内容**拖进去（在本地文件夹里全选再拖，不要把文件夹本身拖进去）。
3. ⚠️ **重要：网页上传会自动跳过所有点开头的隐藏文件**（`.github`、`.gitignore` 等）。若文件树里没有 `.github` 文件夹，请手动创建：**Add file → Create new file**，文件名直接输入完整路径 `.github/workflows/build-apk.yml`，把下方的工作流代码粘进去（Create new file 支持输入点开头文件名）。
4. 打开仓库的 **Actions** 标签页，即可看到 `Build APK` 工作流正在自动运行。
5. 构建约 3~5 分钟。完成后进入该次运行页面，在底部 **Artifacts** 区域下载 `bluetooth-serial-apk`，解压即得 `app-debug.apk`。
6. 以后每次推送代码都会自动重新构建；也可在 Actions 页面点 **Run workflow** 手动触发。

> 注意：免费额度为 Public 仓库无限、Private 仓库每月 2000 分钟，个人使用完全足够。

**工作流代码（`build-apk.yml` 内容，不依赖任何隐藏文件，SDK 用镜像预装版）：**

```yaml
name: Build APK

on:
  push:
    branches: ["**"]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Set up Gradle 8.7
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: 8.7

      - name: Build debug APK
        run: gradle :app:assembleDebug --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: bluetooth-serial-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
```

### 方式二：GitLab CI（备选）

在 GitLab 新建项目并推送代码，免费 Runner 同样支持 Android 构建；可在项目根目录添加 `.gitlab-ci.yml`（模板网上搜索 "android gitlab-ci" 即可，需要 JDK 17 + Android SDK 34）。

### 方式三：云端 IDE（在浏览器里编译）

- **GitHub Codespaces**：在 GitHub 仓库页面点 **Code → Codespaces**，打开浏览器版 VS Code，终端执行 `gradle :app:assembleDebug`，产物在 `app/build/outputs/apk/debug/`，可直接下载。
- **Gitpod**（gitpod.io，免费额度）：同样的用法。

### 方式四：在手机上直接编译

- **AndroidIDE**（开源，官网 androidide.com 下载）：手机端完整 Gradle 构建环境，打开项目即可编译出 APK。
- **AIDE**（Play 商店）：支持打开 Gradle 项目并构建 APK。
- **Termux**（进阶）：`pkg install openjdk-17` + 下载 Android cmdline-tools + `sdkmanager` 安装 platform-34 / build-tools-34.0.0，然后 `gradle :app:assembleDebug`。

## 权限说明

| 权限 | 用途 | 系统版本 |
| --- | --- | --- |
| `BLUETOOTH_SCAN` | 扫描蓝牙设备 | Android 12+（运行时） |
| `BLUETOOTH_CONNECT` | 连接/通信 | Android 12+（运行时） |
| `ACCESS_FINE_LOCATION` | BLE 扫描所需 | Android 11 及以下（运行时） |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 经典蓝牙 | Android 11 及以下（安装时） |

## 常见问题

- **扫描不到设备**：确认手机蓝牙已开启、权限已授予；经典蓝牙扫描范围约 10 米，BLE 请靠近设备；部分手机需在系统设置中额外允许「附近设备」权限。
- **BLE 连上但收发无数据**：模块的 UART 服务不在自动识别列表，请在「UUID」中手动填写服务/特征 UUID。
- **经典蓝牙连接失败**：确认模块处于从机模式且未与其他设备连接；本 App 已内置 channel=1 回退。
- **HEX 发送报错**：HEX 字符串字符数必须为偶数（如 `01 03`，不能是 `0 1 3` 的奇数形式）。
- **网页上传后 Actions 编译失败**：多半是点开头文件（`.github`、`.sdkmanager`）被网页上传跳过。修复方法：用 **Add file → Create new file** 手动创建 `.github/workflows/build-apk.yml`（文件名可直接输入完整路径），内容见上方「在线编译」章节；新版工作流不依赖任何隐藏文件，建好后在 Actions 页对失败的任务点 **Re-run all jobs** 即可。
