# 象棋大师 · Pikafish Android 版

原生安卓中国象棋 App，走子由 **Pikafish**（象棋版 Stockfish）计算，等级分 3000+。

- 界面：Kotlin 原生，棋盘用 Canvas 手绘（无图片资源，任意分辨率清晰）
- 引擎：Pikafish 官方 release 的 arm64 二进制
- 构建：GitHub Actions 云端编译，本机无需安装任何安卓工具链

## 一期功能（当前）

- 选择执红 / 执黑
- 原生棋盘：网格、河界、九宫、兵炮位标记、32 枚棋子
- 触屏走子：点选己方棋子 → 绿点显示合法落点 → 点落点走子
- AI 自动应对（Pikafish，思考时间可调）
- 棋盘高亮：AI 最近一步（橙圈+方向箭头）、AI 历史落点（橙点）、被将军（红圈）
- 双方着法列表（中文记谱）
- 悔棋 / 认输 / 重开一局
- **终局后仍可「悔棋再战」**

## 将要做的（二、三期）

- 二期：走法分析面板（前 3 候选 + 评估 + 后续预测 + 依据/收益/风险）、实时胜率曲线、对局复盘
- 三期：内嵌大师开局库与布局命名、棋谱导出、主题配色

## 项目结构

```
app/src/main/java/com/pikafish/chess/
├── core/                    纯规则层，不含安卓 API，可用普通 JVM 单测
│   ├── Chess.kt             棋盘常量 + 局面表示 + 走法生成 + 攻击判定
│   ├── Notation.kt          中文记谱（炮二平五 / 马8进7 / 前车进一）
│   └── Game.kt              对局状态机（落子/悔棋/将死/困毙/重复局面）
├── engine/UciEngine.kt      引擎进程 + UCI 协议
├── ui/BoardView.kt          原生棋盘控件（Canvas 绘制 + 触屏）
└── MainActivity.kt          界面与流程编排
app/src/test/.../BoardTest.kt   18 条规则单元测试（CI 上必须全过才出包）
.github/workflows/android.yml   云端构建流水线
```

## 两个关键技术点

**1. 安卓上怎么执行引擎二进制**

安卓 10 以后普通存储目录禁止执行二进制。做法是把引擎改名成 `libpikafish.so` 放进 `jniLibs/arm64-v8a/`，系统会把它解压到应用私有的 native 目录，那里才允许 exec。**必须同时设置**：

- `AndroidManifest.xml` 里 `android:extractNativeLibs="true"`
- `app/build.gradle.kts` 里 `packaging { jniLibs { useLegacyPackaging = true } }`

漏掉任何一条，`.so` 会被压缩在 APK 里，无法执行。

**2. 引擎与权重不进 git**

`pikafish.nnue` 有 50.7 MB，提交进仓库会让推送变得极慢。改为**编译时由 CI 下载官方 release 并注入 APK**——云端机器访问 GitHub 是本地速度。

## 构建

推送到 `main` 分支即自动触发，也可在 Actions 页手动 `Run workflow`。

产物在对应 run 的 **Artifacts → pikafish-xiangqi-apk**，下载解压得到 `app-debug.apk`。

安装时需要在手机上允许"安装未知来源应用"。

## 引擎参数

默认 **6 线程 / 256MB 置换表 / 6 秒思考**（"强度优先"）。界面里可切到 1.5 / 3 / 6 / 10 秒。

注意：手机是大核+小核架构，线程数开满 8 个反而可能更慢（小核拖后腿），6 是个比较稳的值。长时间思考会发热降频，属正常。

## 已做的取舍

- `minSdk 26`（Android 8.0）：Pikafish 官方只提供 arm64 二进制，arm64 覆盖 2018 年后几乎所有机型
- `abiFilters = arm64-v8a`：不带 x86/armeabi，APK 更小
- 权重不打进 `assets` 之外的压缩包：`noCompress += "nnue"`，首次启动复制更快

## 引擎来源与许可

引擎与权重来自 [official-pikafish/Pikafish](https://github.com/official-pikafish/Pikafish) 官方 release。
Pikafish 基于 Stockfish，遵循 GPL-3.0。若需分发，请遵守其许可条款。
