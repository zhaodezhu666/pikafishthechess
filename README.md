# 尖头鳗指导棋 · Pikafish Android 版

原生安卓中国象棋 App，走子由 **Pikafish**（象棋版 Stockfish）计算，等级分 3000+。

- 界面：Kotlin 原生，棋盘用 Canvas 手绘（无图片资源，任意分辨率清晰）
- 引擎：Pikafish 官方 release 的 arm64 二进制
- 构建：GitHub Actions 云端编译，本机无需安装任何安卓工具链

## 功能

### 对弈
- 选择执红 / 执黑
- 原生棋盘（Canvas 绘制，无图片资源），触屏走子，绿点为合法落点
- AI 自动应对，思考时间 1.5 / 3 / 6 / 10 秒可调
- AI 最近一步橙圈+方向箭头、历史落点橙点、被将军红圈
- 悔棋 / 认输 / 重开，**终局后仍可「悔棋再战」**
- 引擎加载期间也能先走棋，不用死等

### 分页面板（底部四个 Tab）
| Tab | 内容 |
|---|---|
| **着法** | 双方着法列表（中文记谱），回合数统计 |
| **分析** | 轮到你走时自动给出前 3 候选着法：中文记谱 + 引擎评分 + 吃子/将军标记 + 后续 5 步预测，并提示「第一选比第二选好多少」 |
| **胜率** | 实时胜率曲线。红线区=你占优，绿线区=AI 占优，中线为 50%；标题栏给出当前胜率与局势描述 |
| **复盘** | 对局结束后：全局摘要（最大领先/最被动时刻/漏着次数）+ 胜率下滑最多的 3 手「胜负手」 |

### 其他
- **布局识别**：只看着法序列判断布局体系（中炮对屏风马 / 仙人指路 / 飞相局 …），并附一句通俗说明
- **棋谱导出**：结果弹窗里可一键「分享棋谱」（中文记谱纯文本，含布局名与结果）
- **诊断**：引擎版本 / CPU 特性 / 权重完整性 / 线程与置换表 / 引擎日志 / 崩溃堆栈，可一键复制

> 关于**为什么没有内嵌开局库**：Pikafish 6 秒能算到 15 层以上，开局质量已超过常见大师谱着；
> 而一个覆盖主流变例的开局库要 250KB 左右。所以这里用「布局识别」替代「谱着匹配」——
> 告诉你现在下的是什么体系，学习价值更高，还不占体积。

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

**2. 引擎二进制必须随仓库分发（踩过的坑）**

Pikafish 官方 release（`Pikafish.2026-09-06.7z`，52MB）解压后**只有一个 `pikafish.nnue`，
没有任何引擎二进制**。第一版流水线写了「找不到 arm64 就从包里随便挑一个 pikafish*」的兜底，
结果把 49MB 的权重拷成了 `libpikafish.so`，打出来的 APK 是废的（构建还显示成功）。

现在改为：

| 文件 | 来源 | 进 git？ |
|---|---|---|
| 引擎二进制 | `prebuilt/libpikafish-arm64-dotprod.so`（1.73MB，静态链接 aarch64） | ✅ 随仓库 |
| `pikafish.nnue` 权重（49MB） | CI 编译时从官方 release 下载 | ❌ 不进仓库 |

并加了三道防呆校验：引擎体积必须在 0.5~6MB、必须是 ELF（magic `7f454c46`）、
APK 打包后必须能在 `unzip -l` 里看到引擎与权重。任一条不过直接构建失败。

引擎二进制由 `tools/prepare_engine.py` 从社区整合包里提取，脚本会校验 ELF 架构与可否执行：

```bash
python tools/prepare_engine.py "D:/临时/皮卡鱼 20260131.zip"
```

> 关于 PIE：安卓 5.0+ 要求 PIE，但这条只针对**动态链接**的可执行文件。
> Pikafish 安卓版是**静态链接的 ET_EXEC**（无 `PT_INTERP`），由内核直接加载，
> 所以能正常执行。判断依据是「有无 `PT_INTERP`」，不是「`e_type` 是否为 3」。

**3. 权重不进 git**

`pikafish.nnue` 有 49MB，提交进仓库会让推送变得极慢。改为**编译时由 CI 下载官方 release 并注入 APK**——云端机器访问 GitHub 是本地速度。

## 下载安装（手机）

**直接点这个链接下载 APK**（不用登录、不用解压）：

```
https://github.com/zhaodezhu666/pikafishthechess/releases/latest/download/xiangqi-master.apk
```

或打开 Releases 页面手动挑版本：

```
https://github.com/zhaodezhu666/pikafishthechess/releases
```

下载后安装时，系统会提示"未知来源应用"，允许即可。

> 为什么走 Release 而不是 Actions Artifact：Artifact 必须登录 GitHub，
> 而且手机上下到的是 zip 还得再解压；Release 附件是直链，点开就能装。

## 构建

推送到 `main` 分支即自动触发，也可在 Actions 页手动 `Run workflow`。

每次构建会：

1. 校验仓库内的引擎二进制（体积 + ELF magic）
2. 从官方 release 下载 NNUE 权重
3. 跑 18 条规则单元测试
4. 编译 Debug APK
5. 校验 APK 内确实含引擎与权重
6. 上传 Artifact **并发布一个 Release**（手机直链下载用）

产物两个地方都有：

- Release 附件：`xiangqi-master.apk` / `app-debug.apk`（推荐）
- Actions → 对应 run → Artifacts → `pikafish-xiangqi-apk`（需登录，得到的是 zip）

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
