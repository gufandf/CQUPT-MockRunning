# AGENT.md

本文件是给 AI 编码助手（以及新加入的人类协作者）看的项目说明书。
**动手改代码之前先读完，提交之前必须执行第 6 节的检查。**

---

## 1. 项目基本信息

| 项 | 内容 |
|---|---|
| 名称 | 鲸鲸约跑（CampusRun） |
| 一句话 | 微信小程序「重邮智慧体育」的打卡工具，**安卓单机版** |
| 当前版本 | **vG1.11.1**（`versionCode 11101` / `versionName "G1.11.1"`）—— 版本号只在 `android-app/version.properties` 一处维护，**不明确要求发版就不进位**，规则见 [§5.1](#51-版本号规则semver) |
| License | MIT |
| 仓库 | https://github.com/gufandf/CQUPT-MockRunning |
| 目标服务 | `https://sport.cqupt.edu.cn/new_wxapp`（重邮智慧体育小程序后端） |

> 本仓库 fork 自原仓库 `https://github.com/WilliTourt/CQUPT-MockRunning`，其余引用一律以上表地址为准。

**设计原则（改代码时不要破坏）：**

- **不申请任何定位权限、不连自建服务器、不需要登录。**
  权限只用这几项，不要加新的：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE(_DATA_SYNC)`、
  `POST_NOTIFICATIONS`（前台服务通知，Android 13+）、`POST_PROMOTED_NOTIFICATIONS`
  （流体云胶囊 / 实时活动，Android 16+，低版本系统自动忽略）、
  `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- 直接构造 HTTP 请求，**不碰手机 GPS**。早期 iOS / Android 定位注入方案已作废，仅留作参考。
- **零第三方网络依赖。** Android 侧只用 `HttpURLConnection` + `org.json`，不要引入 Retrofit / OkHttp / Gson。
- 反面事实：人脸检测在**操场物理摄像头**上做，工具**绕不过去**，只能靠本人到场。

---

## 2. 技术栈

**Android App（主交付物）**

- Kotlin 2.0.21 + Android Gradle Plugin 8.13.2，Gradle 8.14.3（wrapper 已带 jar）
- `compileSdk 36` / `minSdk 24` / `targetSdk 34` / JDK 17
  （compileSdk 必须是 36：流体云走的 Android 16 Live Updates 只有 36 才有；
  targetSdk 仍是 34，没为 35/36 的行为变更做适配，不要顺手往上顶）
- AppCompat `DayNight` 主题，颜色**全部走资源引用**，不要写死色值
- 视觉是**流体设计**（ColorOS 16）：**纯色底** `@color/bg`、24dp 圆角玻璃卡片
  `@drawable/box`、胶囊按钮 `@dimen/radius_pill`、18dp 圆角输入块 `@drawable/field`
- **背景一律纯色，不要渐变**（G1.11.1 起）：页面底是 `@color/bg`，
  状态卡是 `@drawable/card_status`（纯色玻璃面）。`@drawable/box` 那层玻璃高光
  在深色下会显成一条亮带，只留给「内容块」，不要再用到数字密集的区域
- Release 直接用 debug 签名（自用项目，不配 keystore）

**Python 工具链**

- 仅标准库 + `requests`（`wecat_run.py` 需要）；Python 3
- 无打包、无 `requirements.txt`，脚本直接跑

---

## 3. 目录结构

```
CQUPT-MockRunning/
├── android-app/              📱 成品 App（主交付物，绝大多数改动在这里）
│   ├── version.properties    🔢 **版本号唯一来源**（只有发版时才改，见 §5.1）
│   ├── app/src/main/java/com/whale/campusrun/
│   │   ├── Api.kt            重邮智慧体育 API 客户端（无签名、明文 JSON）
│   │   ├── App.kt            Application 入口，只负责在任何 Activity 前定好主题
│   │   ├── AreaStore.kt      场地缓存单例：上手页拉到 → 主页 onResume 直接取用
│   │   ├── MainActivity.kt   **主页**：顶栏 + 地图 + 底部固定【开始打卡】
│   │   ├── MapCanvasView.kt  自绘地图：围栏 / 禁跑区 / 按进度显现的轨迹
│   │   ├── LiquidButton.kt   液体按钮：按压缩放 + 面积守恒形变 + 橡皮筋跟手（逆向自 ColorOS 计算器）
│   │   ├── LiquidSpring.kt   一维弹簧（response/bounce 解析解，纯 JVM 可单测）
│   │   ├── FluidMotion.kt    液体数学：橡皮筋 / 面积守恒形变 / 重力弧（纯 JVM 可单测）
│   │   ├── FluidPopupMenu.kt 液体弹出菜单：贝塞尔重力弧入场 + 三波弹簧
│   │   ├── SettingsActivity.kt **设置首页**：只放七个分组入口 + 当前取值摘要
│   │   ├── SettingsGroupActivity.kt 设置二级页：一个 Activity + 七套布局，按 EXTRA_GROUP 选
│   │   ├── SettingsRow.kt    设置行（标题 + 说明 + 取值 + 箭头 + 分割线），八个设置页共用
│   │   ├── WheelPicker.kt    滚轮选择器（配速 / 距离 / 抖动），自绘 + 吸附 + 甩动
│   │   ├── RunProgressView.kt 主页进度环（百分比在环心，弹簧推进）
│   │   ├── StatsParse.kt     成绩记录的日期解析（记录号时间戳 → MM-dd，纯 JVM 可单测）
│   │   ├── RefreshScrollView.kt 到头/到尾还能再拉一下的刷新容器（零依赖，替代 SwipeRefreshLayout）
│   │   ├── RunController.kt  单例，持有跑步线程，切主题重建 Activity 不丢进度
│   │   ├── RunEngine.kt      打卡状态机：换 token → 生成轨迹 → start2 → 分批上传 → end
│   │   ├── LiveCapsule.kt    **流体云胶囊**：把 RunState 变成一条实时活动通知
│   │   ├── RunService.kt     前台服务，保证息屏后台不被打死（通知内容来自 LiveCapsule）
│   │   ├── TrackGen.kt       轨迹生成：配速标定 / 环线有机化 / 多尺度噪声 / 毛刺（纯 JVM 逻辑，可单测）
│   ├── app/src/main/res/layout/
│   │   ├── settings_topbar.xml        二级页复用的固定顶栏（返回 + 动态标题）
│   │   ├── activity_settings.xml      设置首页：七个 SettingsRow
│   │   └── settings_group_*.xml       二级页布局：account / run / stats / tutorial / live / appearance / about
│   ├── app/src/main/assets/get_openid.py   随 App 分发的取码脚本（与根目录同源）
│   └── app/src/test/java/.../TrackGenTest.kt  用服务器真实多边形做断言
│                              AreaStoreTest.kt 场地缓存的状态机（拉取/失败/换账号）
│                              LiquidSpringTest.kt / FluidMotionTest.kt
│                                                液体动效的数值性质（回弹/面积守恒/重力弧）
├── release.py                🚀 发版唯一入口：进位版本号 → CHANGELOG 改名 → 构建 APK
├── get_openid.py             本地提取 openid（读微信 HTTP 缓存，零解密、不联网）
├── wecat_run.py              命令行版客户端
├── draw_text.py              写字版客户端：轨迹是一行「重庆邮电大学」，手写效果
├── track_engine.py           轨迹引擎 Python 参考实现
├── refs/                     逆向报告与一次性分析脚本
│   ├── FINDINGS.md           ⭐ 完整逆向报告（20+ 接口、包体解密、实测日志）
│   ├── REA-CALCULATOR-FLUID.md  ⭐ ColorOS 计算器触摸动效逆向（液体按钮 / 弹出菜单的公式与参数）
│   ├── wxapkg_unpack.py      V1MMWX 包体解密 + 解包
│   ├── clear_stale.py        清理服务端挂着的未结束记录
│   ├── fetch_tracks.py       拉历史记录 + 轨迹点，导出 GeoJSON / CSV
│   ├── plot_tracks.py        把导出的轨迹叠成图
│   ├── verify_trackgen.py    TrackGen.kt 的 Python 镜像（配速标定 / 围栏判定）
│   ├── verify_real_areas.py  三个真实场地 + 真实 jpq 禁跑区的验证
│   └── verify_pace_profile.py  配速形态验证（3 场地 × 3 形态 × 4 配速 × 8 seed）
├── android/ · ios/ · api/    早期作废的定位注入方案，仅作参考，不要在此基础上开发
├── docs/img/                 README 用的截图
├── README.md                 面向使用者的说明
└── CHANGELOG.md              版本变更记录（Keep a Changelog 格式）
```

**界面结构约定（G1.11.1 起）**：两个页面，各只干一件事。

**设置页是两层的**（G1.11.1 起）：首页只列分组入口，表单在二级页。

| 页 | 放什么 |
|---|---|
| 主页 `MainActivity` / `activity_main.xml` | 顶栏（标题 + 日志 + 设置）、地图、电池提示、仪表盘摘要、底部固定【开始打卡】 |
| 设置首页 `SettingsActivity` | 七个分组入口（`@style/SettingsRow`）+ 每条的当前取值摘要：openid / 场地、配速 / 距离 / 抖动、主题、版本…… |
| 设置二级页 `SettingsGroupActivity` | `EXTRA_GROUP` 决定加载 `settings_group_*.xml` 与绑定哪一组：账号与场地、跑步参数、成绩统计、使用教程、流体云、外观、关于 |

**输入控件只有两种形态**：
- **数字**（配速 / 距离 / 配速抖动）用 `WheelPicker` 鼓轮 + 一排 `@drawable/chip_bg` 快捷值，
  不要再用 EditText 让人手打；值域/步进写在 `SettingsGroupActivity.bindRun()` 里。
- **openid** 用 `R.layout.dialog_openid` 的弹窗（标题 + 说明 + 行内输入 + 28 位计数 +
  粘贴/清除 + 下划线 + 取消/保存），保存后自动连一次。不要在行内直接编辑长串凭据。

**通知胶囊文案**：折叠态只放两段 —— 左「剩余 mm:ss」、右「预计完成 HH:mm」
（`LiveCapsule.build` 的 `setShortCriticalText`），里程留给展开后的进度条。

**成绩统计进页面就自动刷新**（`bindStats()` 里直接 `loadStats()`），
另外支持**下拉刷新和上拉刷新**（`RefreshScrollView`，零依赖）：
滚到顶再往下拉、滚到底再往上拉，过 64dp 松手就刷新。
提示气泡要放在 `RefreshScrollView` **之后**（FrameLayout 里后画的在上层），
放前面会被卡片盖住。

**成绩统计只展示有效成绩**：`StatRow.valid == false` 的记录不进时间轴，
统一收在页面最底部的「无效记录（N）」里，默认折叠。
时间轴用 `item_stats_timeline.xml`（竖线 + 圆点 + 日期/场地 + 配速 + 最新标记），
日期来自 `StatsParse.dateLabel()`（记录号尾部 14 位时间戳），拿不到就显示「第 N 次」，
**不要编假日期**。

**设置页的视觉参照 ColorOS 设置页**：一整组设置是**一张** `@style/SettingsGroup`
（`@drawable/card_group`，28dp 圆角实心面），卡片里一行一项，行是 `SettingsRow`
（`app:rowTitle` / `rowSummary` / `rowValue` / `rowChevron` / `rowAccent` / `rowDivider`），
行与行之间一条左右缩进的 `@color/row_divider` 细线。**不要**再回到「标签 + 大输入框 + 大按钮」
竖着堆的写法，也不要在卡片外面再套玻璃卡片。

二级页共用一个 Activity：骨架（顶栏 + ScrollView + 卡片）完全相同，差异只在布局和绑定；
拆成七个 Activity 会把「顶栏 + 返回 + 转场」复制七份。**新增一组设置 = 加一个
`settings_group_xxx.xml` + `LAYOUT`/`titleRes`/绑定函数各一行**，不要去改七处骨架。

设置页里的「流体云」分段只做两件事：报告胶囊可用状态、发一条测试胶囊。
**不要**为了「让它更像流体云」去接 OPPO 私有 SDK —— ColorOS 16 完整兼容
Android 16 的 Live Updates，公开 API 就是正路，私有 SDK 会把零依赖的招牌砸掉。

主页**不要再塞表单**：场地 / 配速 / 距离 / 抖动都属于「设置」，改参数要进设置页。
**电池白名单也在设置页**（跑步参数 → 电池白名单），主页只留「看进度 + 点开始」。
主页进度用 `RunProgressView` 的环 + 右侧竖排「剩余 / 已跑 / 配速」，
不要再退回成一段「进度 62%　已跑 …　用时 …」的文字墙。
那块状态卡的底是 `@drawable/card_status`（**纯色**），不要换回 `@drawable/box` ——
它的顶部玻璃高光在深色下会显成一条亮度斜坡，而这块全是数字，背景越平越好。

**弹出菜单要留溢血边距**：`PopupWindow` 的内容在窗口边界会被裁掉，
所以 `FluidPopupMenu` 的窗口比面板每边大 16dp（`BLEED_DP`），面板画在中间 ——
否则投影被切成方角、回弹过冲时边框会被切掉一条。
新增非打卡功能一律加到设置页，并按用途归到上面的分段里。

设置页是独立 Activity，侧滑返回由系统提供（清单里
`android:enableOnBackInvokedCallback="true"`），**不要**自己写手势监听。
运行日志仍是独立的 `LogActivity`；页内的导出 / 复制 / 清空收在顶栏「更多」的
液体弹出菜单里，不再平铺成一排按钮。

---

## 4. 构建 / 测试 / 运行

```bash
# 构建 Release APK（需要 JDK 17 + Android SDK Platform 36 + Build-Tools 36.0.0）
cd android-app && ./gradlew assembleRelease        # Windows 用 gradlew.bat
# 产物：android-app/app/build/outputs/apk/release/

# 单元测试（TrackGen 配速标定 + 围栏判定 + AreaStore 缓存状态机，纯 JVM）
cd android-app && ./gradlew :app:testDebugUnitTest
# 当前 56 个用例（TrackGen 12 + AreaStore 13 + FluidMotion 18 + LiquidSpring 9 + StatsParse 4）
# 报告：android-app/app/build/reports/tests/testDebugUnitTest/index.html

# 命令行版
pip install requests
python wecat_run.py probe --openid <OPENID>
python wecat_run.py run --openid <OPENID> --place 0 --pace 5:45 --dist 2.0 --realtime --live

# 轨迹引擎单独验证
python track_engine.py gen --pace 5:45 --dist 2.0 --crs gcj02 --out track.json

# 写字版：不联网只看排版（缺 openid 时必用这条先验收排版）
python draw_text.py --offline --poly feng --png run/plan.png
python draw_text.py --openid <OPENID> --place 太极运动场 --live
```

**改动 `TrackGen.kt` 后必须跑 `testDebugUnitTest`**，它会断言这几条硬指标：
实测配速 == 目标配速（误差 ≤ 1 秒）、所有点落在电子围栏内、没有点落在禁跑区、
**平均配速始终落在「目标 ± 容差」窗口内**、各种配速与种子下都不能越界、
温和起伏既要有快慢差异也要有停顿、汇报速度与实际位移一致。

**毛刺开关的两种模式都要测**（测试里用 `for (burrs in listOf(false, true))` 跑一遍）：
关闭时必须和旧版光滑轨迹等价（5m+ 的一秒级跳点为 0、最大步长 < 5m），
打开后才有突起（5m+ 跳点 ≥ 8、最大跳距 > 5m、平均每秒转向 > 12°），
并且**同一 seed 下两种模式的坐标必须不同**（不能只是换个标注）。
判定用几何量（跳点 / 步长 / 转向角），不要断言内部参数 —— 重写噪声模型时测试仍应有效。

改轨迹算法时，另外跑一遍 Python 镜像做交叉验证（不需要 Android SDK）：

```bash
python refs/verify_trackgen.py        # 配速标定 + 围栏判定（毛刺开/关两遍）
python refs/verify_pace_profile.py    # 配速起伏 × 3 场地 × 4 配速 × 3 seed（毛刺开/关两遍）
python refs/verify_real_areas.py      # 三个真实场地 + 真实 jpq 禁跑区
```

**界面约定**：主页与设置页各一个布局（`activity_main.xml` / `activity_settings.xml`），
页面之间走标准 Activity 转场。顶栏放标题和日志图标 `ic_log`、设置图标 `ic_settings`；
**图标一律用 `android:tint="@color/fg"`**（lint 会报 `UseAppTint`）。

**液体按钮 / 弹出菜单**（G1.11.1 起）：需要「按下去有手感」的按钮一律用
`com.whale.campusrun.LiquidButton`（`app:liquidTone` 选 accent / danger / ghost），
不要用原生 `Button` + `stateListAnimator`。需要「列几个选项」时用
`FluidPopupMenu.build(context, anchor, items) { index -> }.show()`，
**不要**再用 `Spinner`（系统下拉是白底列表，和这套玻璃/流体对不上，也做不了重力弧入场）。
动效公式与参数出处见 `refs/REA-CALCULATOR-FLUID.md`，改手感前先读它。
**按下时边缘光要变弱**：强度与描边宽度都随 `pressDepth` 反向收
（`LiquidButton` 的 `glowPaint`），不要写成「按下点亮到满」——
深色底上那圈满强度蓝晕会被读成按钮外扩的脏阴影，而不是发光。
**按下只有「微微收缩」**：`FluidMotion.pressScale` 的区间是 **0.955~0.985**（最多收 4.5%），
不要退回 ColorOS 原值 0.85~0.97 —— 那是给 90dp 见方小按键定的，
套到 328×92dp 的整宽胶囊上会缩到 0.92，看起来是整条「塌了一下」而不是被指尖压。
夸张的形变和位移归拖动通道，按下只负责给一条「接触到了」的反馈。
**主按钮与卡片同宽**：主页的【开始打卡】不要自己加左右 margin —— 父容器已经有
`@dimen/gap_screen` 的内边距，卡片也是靠它对齐的，按钮再加一层就会比卡片窄一圈。
`LiquidButton` 的 `app:liquidRoom` 是留给形变和发光余量（胶囊本体 = 尺寸 − 2×余量），
默认按高度 17.5%（92dp → 16dp）会再吃掉一圈，主页主按钮显式收到 10dp。
room 上下左右都算，改小它要把高度同步减掉 2×差值（92dp → 84dp），
否则按钮会「更宽又更高」，看起来是整颗被放大而不是和卡片对齐。
地图是 `MapCanvasView` 自绘，颜色取 `@color/map_*`，不要接地图 SDK。
状态栏用 `@color/bg`、导航栏用 `@color/bg_deep`（都是纯色，与页面底连成一体）。

---

## 5. 代码约定

- 注释和用户可见文案一律用中文；注释写**「为什么这么做」**，不写「这行在干什么」。
- **版本号按 5.1 的语义化规则进位**（该动 MAJOR / MINOR / PATCH 哪一位），
  要改哪些文件见 5.2 的清单。**不需要建版本分支** —— 发布时打 tag 就行。
- 不引入新依赖前先想清楚 —— 这个项目的卖点之一就是零依赖。
- 反编译 / 抓包得到的新结论写进 `refs/` 下的报告（打卡链路 → `FINDINGS.md`，
  界面动效 → `REA-CALCULATOR-FLUID.md`），别散落在聊天记录里。
- 一次性的分析脚本可以放在 `refs/`，但产物（解包目录、strings dump）不要提交，见 `.gitignore`。

### 5.1 版本号规则（SemVer）

**版本号只有一份：`android-app/version.properties`。**
`app/build.gradle.kts` 把它读进 `versionName` / `versionCode`，
清单里的 `<meta-data android:name="version">` 用 `${appVersion}` 占位符注入。
**不要在 Gradle 或清单里手写版本号** —— 以前两处各写一份，漏同步过一次。

> 早期还写过「从版本分支 `V1.10.1` 推导版本号」的规则。实践下来没必要 ——
> 一个人开发，`main` 上直接改就行。真正干活的是 **tag**：每个版本一个。

#### 5.1.1 最重要的那条规则：**不发布就不动版本号**

版本号是**给用户看的变更里程碑**，不是提交计数器。
所以：

| 场景 | 版本号 |
|---|---|
| 日常提交（修 bug、加功能、调样式、改文档、重构） | **一个数都不改** |
| 明确说「发版 / 发布版本 / release」 | 跑 `python release.py <major\|minor\|patch>` |

没发布的内容全部堆在 `CHANGELOG.md` 的 `## [Unreleased]` 段落里。
积累到要发版时，`release.py` 会把这个段落改名为正式版本。

> 为什么加这条：1.9 → 1.19 十个版本号里，有一半只是文案、间距、图标微调。
> 版本号涨得比内容快，用户看不出哪一版值得更新，tag 也失去意义。

#### 5.1.2 进位到哪一位

| 位置 | 什么时候动 | 例子 |
|---|---|---|
| `MAJOR` | 对外行为或数据格式**不兼容**的改动（老配置 / 老流程会坏掉） | G1.11.1 → G2.0.0 |
| `MINOR` | **向后兼容**地加功能、加选项 | G1.11.1 → G1.12.0 |
| `PATCH` | **向后兼容**地修缺陷，不改接口与行为约定 | G1.11.1 → G1.11.2 |

一次发版里既有新功能又有修复 → 按**最高的那一位**进（MINOR 吞掉 PATCH）。

#### 5.1.3 映射到 Android

```properties
# android-app/version.properties
version.prefix=G          # 纯展示前缀，可空（留空 → versionName 就是 1.11.1）
version.major=1
version.minor=11
version.patch=1
```

```kotlin
// app/build.gradle.kts 自动算出来，不需要手写
versionName = "G1.11.1"    // prefix + "MAJOR.MINOR.PATCH"
versionCode = 11101        // MAJOR*10000 + MINOR*100 + PATCH（不含前缀）
```

**前缀 `G` 只是展示用，不参与 `versionCode` 计算。**
`versionCode` 永远是三段数字按公式算出的纯整数 —— Android 只认单调递增的整数，
字母进不去，也不该进。前缀只影响 `versionName`（界面显示、清单 meta-data、
APK 文件名、tag 建议名），换个前缀或清空前缀，`versionCode` 都不变。

`versionCode` 用这个公式是为了让**数字大小和版本新旧一致**（Android 只认单调递增），
`MINOR` / `PATCH` 各留两位：`1.9.0 → 10900`、`1.10.0 → 11000`、`2.0.0 → 20000`。
`MINOR` 是数字不是字符串，`1.10.0` 比 `1.9.0` 新。

随时确认「这次构建会打出什么版本」：

```bash
cd android-app && ./gradlew :app:ver     # versionName = G1.11.1   versionCode = 11101
```

#### 5.1.4 发版流程（唯一入口）

```bash
python release.py patch --dry     # 先看一眼：会改成 G1.11.2，动哪些文件
python release.py patch           # 真做：改版本号 + CHANGELOG 改名 + 构建 APK
```

脚本做四件事，任一步失败就停下（不写半个版本号）：

1. 读 `version.properties`，算出新版本号
2. 把 `CHANGELOG.md` 的 `## [Unreleased]` 改名成 `## [<versionName>] — 今天`，
   并补上引用式链接（`[G1.11.2]: #`，前缀版本照样能插进去）；
   **没有 `[Unreleased]` 段落就报错**（说明这次没东西可写）
3. 回写 `version.properties`
4. `./gradlew :app:assembleRelease`，产物复制成根目录的
   `jingjing-yuepao-<versionName>.apk`（当前即 `jingjing-yuepao-G1.11.1.apk`；
   有 `v` 无 `v` 早已不统一，文件名纯 ASCII 才是硬要求）

**脚本不替你 commit / tag / push / 发 Release**，跑完会把手动命令打出来：

```bash
git add -A && git commit -m "G1.11.2"
git tag vG1.11.2
gh release create vG1.11.2 "jingjing-yuepao-G1.11.2.apk" \
  --title "鲸鲸约跑 vG1.11.2" --notes-file notes.md
```

tag 名 = `v` + `versionName`（当前即 `vG1.11.1`），和 Release 一一对应。

> ⚠️ 附件名**必须是纯英文**：GitHub 会把非 ASCII 字符剥掉，
> `鲸鲸约跑-G1.11.1.apk` 会变成 `-G1.11.1.apk`。
> 而且 `#标签` 语法只设 label，**不改真实文件名**，别指望它。

### 5.2 手动发版时要同步的位置

正常走 `release.py` 的话，下面**全都自动**完成，不用手改：

| 位置 | 谁负责 |
|---|---|
| `android-app/version.properties` | `release.py` |
| `app/build.gradle.kts` 的 `versionName` / `versionCode` | Gradle 自动读 properties（`versionName` 拼前缀，`versionCode` 不含前缀） |
| `AndroidManifest.xml` 的 `<meta-data android:name="version">` | Gradle 的 `${appVersion}` 占位符 |
| `CHANGELOG.md` 的 `[Unreleased]` → `[<versionName>]` + 链接 | `release.py` |

只有这两处是**文档**，需要手工跟着改（脚本不碰文档，免得写出半截话）：

| 位置 | 改什么 |
|---|---|
| `AGENT.md` §1 表格 | 「当前版本」一行 |
| `README.md` / `android-app/README.md` | 头部「当前版本」标记与版本记录段落 |

**不要**在代码里写死版本号：界面显示一律用 `BuildConfig.VERSION_NAME`。
文案里出现的小版本号（如「v1.9 起」）描述的是历史事实，不必跟着改。

## 6. ⚠️ 提交前强制检查：个人信息与密钥泄露

> **规则：每一次 `git commit` 之前，必须完成本节全部检查。**
> 本项目要接触真实 openid、token、学号和统一认证码，还要处理微信缓存 dump，
> **一次误提交就会把本人身份永久写进 Git 历史**（即使后续删除，历史里仍在）。

### 6.1 三条必跑命令

```bash
# ① 扫真实 openid（28 位、o 开头）。README/FINDINGS 里的 oXXXX... 是占位符，正常
git grep -nE '\bo[A-Za-z0-9_-]{27}\b' -- . | grep -v 'X\{20,\}'

# ② 扫密钥 / 口令 / 私钥
git grep -nEi '(BEGIN [A-Z ]*PRIVATE KEY|api[_-]?key["'"'"']?\s*[:=]|secret["'"'"']?\s*[:=]|passwd|password["'"'"']?\s*[:=])'

# ③ 扫未被忽略的敏感文件（必须无输出）
git status --porcelain --ignored | grep -E 'openid_extract|openid_hunt|\.flow$|wechat_cache|local\.properties|\.jks|\.keystore'
```

更稳妥的做法是连**待提交内容**一起看，而不只是工作区：

```bash
git diff --cached | grep -nEi '(openid|token|secret|password|学号|统一认证)'
```

### 6.2 检查清单（逐条确认）

- [ ] **openid** —— 真实值**绝不允许**出现在任何被跟踪的文件里（含代码、注释、README、
      FINDINGS、测试数据、日志样例、提交信息、截图）。文档里一律写 `oXXXXXXXXXXXXXXXXXXXXXXXXXXX`。
- [ ] **token / sportRecordNo** —— 真实响应体、`trace` 输出、抓包记录不得提交。
- [ ] **学号 / 姓名 / 手机号 / 统一认证码 / 身份证** —— 任何真实个人信息不得提交。
- [ ] **密钥类** —— API Key、`secret`、私钥、keystore / jks、签名口令、`local.properties`（内含本机 SDK 路径）。
- [ ] **截图** —— `docs/img/*.png` 里不能出现真实 openid、学号、真实姓名或聊天记录。
      换图时重点看状态栏和输入框。
- [ ] **提交信息** —— 不要写「用 xxx 的 openid 测试」这类内容。
- [ ] **单测 / 测试数据** —— 用真实下发的场地多边形没问题（是公开地理信息），
      但**不能**带真实账号数据。

### 6.3 已经受 `.gitignore` 保护、永远不要提交的东西

```
refs/openid_extract.txt     真实 openid / 统一认证码 / 学号
refs/openid_hunt.txt        同上
refs/*.flow                 抓包记录
refs/wechat_cache/          微信缓存拷贝
refs/unpacked/              小程序解包结果（可用 wxapkg_unpack.py 重现）
run/                        运行输出
local.properties            Android 本机 SDK 路径
*.keystore  *.jks           签名材料
```

> **不要**为了方便而把上述文件用 `git add -f` 强行加进去。
> 也不要为了「让某次构建能过」而放宽 `.gitignore` —— 加白名单只允许 `!` 反选**目录内的样板文件**。

### 6.4 万一已经提交了

1. **不要**用一次「删除 + 新提交」了事 —— 历史里的 blob 依然存在，`git log -p` 能翻出来。
2. 立刻作废该凭据：openid 的补救方式是**换绑 / 重新登录**，让旧凭证失效。
3. 改写历史（`git filter-repo` / BFG）后**强推**，并通知所有协作者重新克隆。
4. 如果已推到公开远端，按「已泄露」处理，默认视为不可撤销。

---

## 7. 合规与免责

本项目仅供**个人学习与安全研究**。逆向、接口调用、自动打卡均由使用者自行发起，
使用者需自行承担一切后果（包括但不限于账号处罚、成绩判定、校纪校规处理）。
贡献代码即表示你理解并接受 `LICENSE` 中的免责条款。
