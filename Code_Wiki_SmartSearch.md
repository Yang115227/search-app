# SmartSearch 智能搜题 — Code Wiki

> 生成日期：2026-07-15  
> 项目版本：1.0.0  
> 适用平台：Android 10 (API 29) ~ Android 14 (API 34)

---

## 目录

1. [项目概述](#1-项目概述)
2. [整体架构](#2-整体架构)
3. [目录结构与模块职责](#3-目录结构与模块职责)
4. [关键类与函数说明](#4-关键类与函数说明)
5. [数据流与调用链路](#5-数据流与调用链路)
6. [依赖关系](#6-依赖关系)
7. [项目运行方式](#7-项目运行方式)
8. [权限说明](#8-权限说明)

---

## 1. 项目概述

**SmartSearch** 是一款 Android 端的智能搜题应用，帮助用户通过**悬浮球 + 无障碍服务**或**录屏 + OCR 识别**两种方式，自动提取屏幕上的题目文字并匹配本地题库，快速展示答案与解析。

### 核心能力

| 能力 | 说明 |
|------|------|
| 无障碍搜题 | 通过 Android AccessibilityService 提取屏幕文本，匹配题库 |
| 录屏搜题 | 通过 MediaProjection API 截取屏幕，经 PaddleOCR 识别文字后匹配题库 |
| 悬浮球交互 | 可拖拽悬浮球，单击触发搜题，长按关闭 |
| 选题框 | 用户拖拽框选屏幕区域，精确定位题目位置 |
| 答案弹窗 | 展示匹配结果（答案 + 解析），支持返回重新选题 |
| Excel 题库导入 | 从 .xlsx/.xls 文件批量导入题目到本地 Room 数据库 |
| 错题本 / 练习 | 错题记录与随机出题练习（待完善） |

### 技术栈

| 技术 | 版本 |
|------|------|
| 构建工具 | AGP 8.1.3 / Gradle 8.4 |
| 语言 | Kotlin 1.9.22 |
| UI 框架 | Jetpack Compose (Material3) + BOM 2024.01.00 |
| 数据库 | Room 2.6.1 (SQLite) |
| OCR 引擎 | PaddleOCR 2.7.0 (本地 AAR) |
| Excel 解析 | Apache POI 5.2.5 |
| 协程 | Kotlinx Coroutines 1.7.3 |
| 图片加载 | Coil 2.5.0 |
| 相机 | CameraX 1.3.1 (兜底方案) |

---

## 2. 整体架构

项目采用**简洁的分层架构**，核心分为三层：**Core（基础层） → Data（数据层） → Feature（业务层）**，外加 **UI（界面层）**。

```
┌──────────────────────────────────────────────────────────────┐
│                        UI 层 (ui/)                           │
│  HomeActivity / HomeScreen (Compose)                         │
│  PermissionGuideActivity / QuestionImportActivity ...        │
├──────────────────────────────────────────────────────────────┤
│                     Feature 业务层 (feature/)                 │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────┐ │
│  │ Accessibility   │  │ ScreenCapture    │  │ Practice    │ │
│  │ SearchService   │  │ Service + OCR    │  │ 练习/错题本 │ │
│  └────────┬────────┘  └────────┬─────────┘  └─────────────┘ │
│           │                    │                              │
│           └────────┬───────────┘                              │
│                    ▼                                          │
│  ┌────────────────────────────────────────────────┐          │
│  │          FloatView 悬浮窗体系                   │          │
│  │  FloatSelectOverlay (选题框)                    │          │
│  │  AnswerFloatWindow (答案弹窗)                   │          │
│  └────────────────────────────────────────────────┘          │
├──────────────────────────────────────────────────────────────┤
│                      Data 数据层 (data/)                      │
│  ┌──────────────────┐  ┌──────────────────────────────────┐  │
│  │ QuizDatabase     │  │ ExcelImporter                    │  │
│  │ (Room DB)        │  │ (Apache POI 解析 .xlsx/.xls)     │  │
│  │ ├─ QuestionEntity│  └──────────────────────────────────┘  │
│  │ ├─ WrongQuestion │                                        │
│  │ └─ PracticeRecord│                                        │
│  └──────────────────┘                                        │
├──────────────────────────────────────────────────────────────┤
│                   Core 基础层 (core/)                         │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────┐  │
│  │ PermissionManager│  │ FloatWindowManager│  │ RectUtil  │  │
│  │ (权限管理)       │  │ (悬浮窗状态管理)  │  │ (矩形工具)│  │
│  └──────────────────┘  └──────────────────┘  └───────────┘  │
│  ┌──────────────────────────────────────────────────────────┐│
│  │  FloatingWindowService (悬浮球前台服务)                   ││
│  └──────────────────────────────────────────────────────────┘│
├──────────────────────────────────────────────────────────────┤
│                    SearchApplication (入口)                   │
│  初始化: 通知渠道 → 文件I/O适配 → Room → FloatWindowManager  │
│  → PaddleOCR 异步初始化 → Debug 缓存清理                     │
└──────────────────────────────────────────────────────────────┘
```

### 架构要点

- **入口统一初始化**：`SearchApplication` 在 `onCreate()` 中按固定顺序初始化所有全局组件
- **权限集中管理**：`PermissionManager` 统一处理四类特殊权限的检测与引导
- **悬浮窗状态机**：`FloatWindowManager` 管理选题框与答案弹窗的生命周期切换
- **数据流单向**：文本提取 → 题库检索 → 结果展示，数据流清晰可追踪

---

## 3. 目录结构与模块职责

### 3.1 根目录

```
SmartSearch/
├── build.gradle.kts              # 根级构建脚本（插件声明）
├── settings.gradle.kts           # 项目设置（模块引入、仓库配置）
├── gradle.properties             # Gradle 属性（JVM 参数、并行构建等）
├── gradle/libs.versions.toml     # 版本目录（统一管理所有依赖版本）
├── build.sh / build.bat          # 一键编译脚本
├── SmartSearch_*.md              # 项目文档（调用链路、编译指南、目录结构）
└── app/                          # 主应用模块
```

### 3.2 app 模块目录

```
app/src/main/java/com/smartsearch/app/
├── SearchApplication.kt                      # Application 入口
├── core/                                     # 基础层
│   ├── permission/
│   │   └── PermissionManager.kt              # 权限管理器
│   ├── service/
│   │   ├── FloatingWindowService.kt          # 悬浮球前台服务
│   │   └── FloatWindowManager.kt            # 悬浮窗统一管理器
│   └── utils/
│       └── RectUtil.kt                       # 矩形工具类
├── data/                                     # 数据层
│   ├── local/
│   │   ├── QuizDatabase.kt                   # Room 数据库定义
│   │   ├── entity/
│   │   │   ├── QuestionEntity.kt             # 题目实体
│   │   │   ├── WrongQuestionEntity.kt        # 错题实体
│   │   │   └── PracticeRecordEntity.kt       # 练习记录实体
│   │   └── dao/
│   │       ├── QuestionDao.kt                # 题目 DAO
│   │       ├── WrongQuestionDao.kt           # 错题 DAO
│   │       └── PracticeRecordDao.kt          # 练习记录 DAO
│   └── parser/
│       └── ExcelImporter.kt                  # Excel 题库导入解析器
├── feature/                                  # 业务层
│   └── search/
│       ├── accessibility/
│       │   └── AccessibilitySearchService.kt # 无障碍搜题服务
│       ├── capture/
│       │   ├── ScreenCaptureService.kt       # 录屏服务
│       │   ├── PaddleOCREngine.kt            # OCR 引擎
│       │   └── QuestionBankSearcher.kt       # 题库检索器
│       └── floatview/
│           ├── FloatSelectOverlay.kt         # 选题框悬浮窗
│           └── AnswerFloatWindow.kt          # 答案弹窗
└── ui/                                       # 界面层
    └── HomeScreen.kt                         # 首页 (Compose)
```

### 3.3 各模块职责详述

| 模块 | 包路径 | 核心职责 |
|------|--------|----------|
| **Core** | `core/` | 提供全局基础设施：权限检测、悬浮窗管理、矩形运算工具 |
| **Data** | `data/` | 数据持久化：Room 数据库定义、实体映射、DAO 操作、Excel 解析导入 |
| **Feature** | `feature/search/` | 搜题业务核心：无障碍文本提取、录屏采集、OCR 识别、题库检索、悬浮窗交互 |
| **UI** | `ui/` | 用户界面：首页权限状态展示、搜题模式切换、功能入口 |
| **Entry** | `SearchApplication.kt` | 应用入口，负责全局初始化 |

---

## 4. 关键类与函数说明

### 4.1 SearchApplication（应用入口）

**文件**：[SearchApplication.kt](file:///workspace/app/src/main/java/com/smartsearch/app/SearchApplication.kt)

| 方法/属性 | 类型 | 说明 |
|-----------|------|------|
| `onCreate()` | 方法 | 应用启动入口，按顺序执行 6 步初始化 |
| `initNotificationChannels()` | 方法 | 创建悬浮窗和录屏两个通知渠道（Android 8+） |
| `initFileIOAdaptation()` | 方法 | 适配 Android 10+ 分区存储，创建 Debug 截图缓存目录 |
| `initPaddleOCRAsync()` | 方法 | 在后台线程异步初始化 PaddleOCR 引擎 |
| `cleanDebugCache()` | 方法 | 启动时清理 3 天前的 Debug 截图缓存 |
| `applicationScope` | 属性 | 全局协程作用域（SupervisorJob + Dispatchers.IO） |
| `getDebugCaptureDir()` | 方法 | 获取 Debug 截图缓存目录路径 |
| `getExcelImportDir()` | 方法 | 获取 Excel 导入临时目录路径 |

**初始化顺序**：
1. 创建通知渠道（`floating_window_channel` + `screen_capture_channel`）
2. 文件 I/O 适配（分区存储兼容）
3. Room 数据库（懒加载，首次 DAO 调用时初始化）
4. FloatWindowManager 初始化
5. PaddleOCR 异步初始化（不阻塞主线程）
6. Debug 截图缓存清理（删除 3 天前的文件）

### 4.2 PermissionManager（权限管理器）

**文件**：[PermissionManager.kt](file:///workspace/app/src/main/java/com/smartsearch/app/core/permission/PermissionManager.kt)

**类型**：`object`（单例）

| 方法 | 说明 |
|------|------|
| `checkFloatingWindow(context)` | 检测悬浮窗权限（`Settings.canDrawOverlays()`） |
| `checkAccessibility(context, serviceClassName)` | 检测无障碍服务是否开启（解析 `ENABLED_ACCESSIBILITY_SERVICES`） |
| `checkScreenCapture(context, serviceClassName)` | 检测录屏权限（检查 Service 运行状态 + SharedPreferences 缓存） |
| `checkCamera(context)` | 检测相机权限（`ContextCompat.checkSelfPermission`） |
| `getRecommendedMode(...)` | 按优先级返回推荐搜题模式：无障碍 > 录屏 > 相机 > NONE |
| `getAllPermissions(...)` | 批量获取四项权限状态 |
| `getNextMissingPermissionIntent(...)` | 按优先级获取第一个缺失权限的设置页 Intent |

**状态枚举** `PermissionStatus`：
- `GRANTED` — 已授权
- `DENIED` — 未授权
- `NOT_APPLICABLE` — 当前设备不支持

**搜题模式枚举** `SearchMode`（按优先级排列）：
- `ACCESSIBILITY(3)` — 无障碍服务，最无感
- `SCREEN_CAPTURE(2)` — 录屏，需单次授权
- `CAMERA(1)` — 相机，作为兜底
- `NONE(0)` — 无可用模式

### 4.3 FloatWindowManager（悬浮窗统一管理器）

**文件**：[FloatWindowManager.kt](file:///workspace/app/src/main/java/com/smartsearch/app/core/service/FloatWindowManager.kt)

**类型**：`object`（单例）

**状态机**：
```
IDLE ──showSelectOverlay()──▶ SELECTING
SELECTING ──用户确认选区──▶ ANSWERING (同时销毁 SelectOverlay)
ANSWERING ──点击返回──▶ SELECTING (销毁 AnswerWindow，重新打开 SelectOverlay)
ANSWERING ──点击关闭──▶ IDLE
SELECTING ──点击X关闭──▶ IDLE
```

| 方法 | 说明 |
|------|------|
| `init(context)` | 初始化，必须在 Application.onCreate 中调用 |
| `showSelectOverlay(context, onRectSelected)` | 显示选题框，注册选区回调 |
| `showAnswerWindow(context, answer, explanation, onDismissed)` | 显示答案弹窗 |
| `showSelectOverlayForScreenCapture(context)` | 以录屏模式显示选题框（选区回调路由到 ScreenCaptureService） |
| `switchToScreenCaptureMode(context)` | 从无障碍模式切换到录屏模式 |
| `destroyAll()` | 销毁所有悬浮窗，回到 IDLE 状态 |

### 4.4 FloatingWindowService（悬浮球前台服务）

**文件**：[FloatingWindowService.kt](file:///workspace/app/src/main/java/com/smartsearch/app/core/service/FloatingWindowService.kt)

**类型**：`Service`（前台服务）

| 方法/属性 | 说明 |
|-----------|------|
| `onCreate()` | 初始化 WindowManager、通知渠道 |
| `onStartCommand()` | 启动前台通知 + 添加悬浮球到屏幕 |
| `attachFloatingBall()` | 创建并添加悬浮球 View 到 WindowManager |
| `detachFloatingBall()` | 从 WindowManager 移除悬浮球 |
| `handleTouchEvent(event)` | 处理触摸事件：区分点击/拖拽/长按 |
| `triggerSelectionSearch()` | 点击悬浮球触发搜题流程 |
| `snapToEdge()` | 拖拽结束后吸附到屏幕左/右边缘 |

**交互逻辑**：
- **单击**（< 500ms，移动距离 < 15px）：触发搜题 → 显示选题框
- **长按**（> 800ms，移动距离 < 15px）：关闭悬浮球
- **拖拽**：移动悬浮球位置，松手后吸附到最近边缘

**内部类** `AccessibilitySearchServiceHolder`：静态持有无障碍服务实例引用。

### 4.5 RectUtil（矩形工具类）

**文件**：[RectUtil.kt](file:///workspace/app/src/main/java/com/smartsearch/app/core/utils/RectUtil.kt)

**类型**：`object`（单例）

| 方法 | 说明 |
|------|------|
| `iou(a, b)` | 计算两个矩形 IoU（交并比），范围 [0, 1] |
| `overlapRatio(a, b)` | 计算交集占矩形 A 面积的比例 |
| `intersectionArea(a, b)` | 计算两个矩形交集面积 |
| `hitTest(x, y, rect, expandPx)` | 判断触摸点是否命中目标矩形（支持热区扩大） |
| `hitCloseButton(...)` | 判断是否点击了关闭按钮（右上角） |
| `hitBackButton(...)` | 判断是否点击了返回按钮（左上角） |
| `hitResizeHandle(...)` | 判断是否点击了缩放手柄（右下角） |
| `hitResizeEdge(x, y, rect, edgeWidth)` | 判断触摸点是否位于边缘调整区域 |
| `clampToBounds(rect, bounds)` | 将矩形约束在边界内（防止悬浮窗超出屏幕） |
| `statusBarOverlapRatio(rect, statusBarHeight, screenWidth)` | 计算与状态栏的重叠率 |

### 4.6 HomeActivity（首页）

**文件**：[HomeScreen.kt](file:///workspace/app/src/main/java/com/smartsearch/app/ui/HomeScreen.kt)

**类型**：`ComponentActivity`（Compose Activity）

| 方法 | 说明 |
|------|------|
| `onCreate()` | 初始化 FloatWindowManager，设置 Compose 内容 |
| `startAccessibilitySearch()` | 无障碍搜题启动流程：校验悬浮窗 → 无障碍权限 → 启动悬浮球 |
| `startScreenCaptureSearch()` | 录屏搜题启动流程：校验悬浮窗 → 录屏授权 → 启动录屏服务 |
| `startFloatingBallService()` | 启动 FloatingWindowService 前台服务 |
| `openFilePicker()` | 打开文件选择器选择 Excel 文件 |
| `rememberPermissionStatus()` | 响应式获取四项权限状态（onResume 时刷新） |

**Compose 组件**：
- `HomeScreen` — 首页布局（标题 + 权限状态卡片 + 搜题模式按钮 + 功能入口）
- `PermissionStatusCard` — 四项权限状态展示卡片
- `PermissionItem` — 单项权限状态行
- `FunctionCard` — 功能入口卡片（题库导入 / 错题本 / 练习）

### 4.7 QuizDatabase（Room 数据库）

**文件**：[QuizDatabase.kt](file:///workspace/app/src/main/java/com/smartsearch/app/data/local/QuizDatabase.kt)

**类型**：`abstract class` 继承 `RoomDatabase`

| 方法 | 说明 |
|------|------|
| `getInstance(context)` | 双重检查锁定的单例获取 |
| `questionDao()` | 获取题目 DAO |
| `wrongQuestionDao()` | 获取错题 DAO |
| `practiceRecordDao()` | 获取练习记录 DAO |

**实体**：
- `QuestionEntity` — 题目（题干、答案、解析、选项、学科、来源、导入时间）
- `WrongQuestionEntity` — 错题（关联题目 ID、错误次数、最后错误时间）
- `PracticeRecordEntity` — 练习记录（练习时间、正确率、题目数量）

**数据库版本**：v1（开发阶段使用 `fallbackToDestructiveMigration()`）

### 4.8 ExcelImporter（Excel 题库导入解析器）

**文件**：[ExcelImporter.kt](file:///workspace/app/src/main/java/com/smartsearch/app/data/parser/ExcelImporter.kt)

**类型**：`object`（单例）

| 方法 | 说明 |
|------|------|
| `importFromUri(context, uri)` | 从 Content Uri 导入 Excel 文件（挂起函数） |
| `parseAndImport(inputStream, context)` | 解析 Excel 输入流并批量写入数据库 |
| `parseHeader(headerRow)` | 解析表头行，自动匹配列名（支持中英文别名） |
| `parseRow(row, columnMapping, rowIndex)` | 解析单行数据为 QuestionEntity |

**列映射方式**：
1. **自动检测表头**：第一行为表头，支持中英文别名（如"题干"/"question"）
2. **固定列序**：无表头时按 A=题干, B=答案, C=解析, D=选项, E=学科

**结果封装** `ImportResult`：
- `Success(count)` — 导入成功，返回导入数量
- `Error(message)` — 导入失败，返回错误信息

**安全限制**：单次最多导入 5000 行，每批 100 条写入数据库。

### 4.9 未实现的 Feature 层组件

以下组件在文档和架构中有规划，但**当前仓库中尚未实现**：

| 组件 | 路径 | 说明 |
|------|------|------|
| `AccessibilitySearchService` | `feature/search/accessibility/` | 无障碍搜题服务（通过 AccessibilityService 提取屏幕文本） |
| `ScreenCaptureService` | `feature/search/capture/` | 录屏服务（MediaProjection 采集屏幕帧） |
| `PaddleOCREngine` | `feature/search/capture/` | OCR 引擎（PaddleOCR 文字识别） |
| `QuestionBankSearcher` | `feature/search/capture/` | 题库检索器（连接 Room 数据库进行匹配） |
| `FloatSelectOverlay` | `feature/search/floatview/` | 选题框悬浮窗（Canvas 绘制 + 拖拽选区） |
| `AnswerFloatWindow` | `feature/search/floatview/` | 答案弹窗（Canvas 绘制 + 展示答案/解析） |
| `PermissionGuideActivity` | `ui/` | 权限引导页 |
| `QuestionImportActivity` | `ui/` | 题库导入页 |
| `PracticeActivity` | `ui/` | 练习页 |
| `WrongBookActivity` | `ui/` | 错题本页 |

---

## 5. 数据流与调用链路

### 5.1 无障碍搜题完整链路

```
用户点击首页「无障碍搜题」按钮
    │
    ▼
HomeActivity.startAccessibilitySearch()
    │
    ├─ ① checkFloatingWindow() → 未授权 → 跳转设置页
    ├─ ② isServiceEnabled()    → 未开启 → 跳转无障碍设置
    │
    └─ ③ startFloatingBallService()
            │
            ▼
        FloatingWindowService 启动
            │ 显示悬浮球在屏幕右侧
            │ 用户点击悬浮球
            ▼
        triggerSelectionSearch()
            │
            ├─ ④ checkFloatingWindow() 二次确认
            │
            └─ ⑤ FloatWindowManager.showSelectOverlay { rect ->
                    │
                    ▼
                FloatSelectOverlay 显示
                    │ 用户拖拽确认选区
                    │ onRectConfirmed(rect)
                    ▼
                回调 → AccessibilitySearchService.setSelectionRect(rect)
                    │
                    ▼
                performScan()
                    │
                    ├─ ⑥ collectTextNodes() 递归遍历 AccessibilityNodeInfo
                    ├─ ⑦ RectUtil.overlapRatio() ≥ 60% 过滤
                    ├─ ⑧ buildQuestionText() 拼接题干
                    │
                    ├─ 题干为空 → showEmptyResultDialog() 提示切换录屏
                    │
                    └─ 题干有效 → QuestionBankSearcher.search()
                            │
                            ├─ 精确匹配: dao.findByExactQuestion()
                            ├─ 模糊匹配: dao.searchByKeyword()
                            └─ 兜底: "未找到匹配的题目"
                                  │
                                  ▼
                            FloatWindowManager.showAnswerWindow()
                                  │ 显示答案弹窗
                                  │ 用户可点击返回重新选题
                                  │ 或点击关闭退出
                                  ▼
                            AnswerFloatWindow 显示
```

### 5.2 录屏搜题链路

```
HomeActivity.startScreenCaptureSearch()
    │
    ├─ ① checkFloatingWindow()
    │
    └─ ② ScreenCaptureService.switchFromAccessibility()
            │
            └─ screenCaptureLauncher 启动系统录屏授权
                    │
                    ├─ 用户取消 → 保持无障碍模式
                    │
                    └─ 用户授权 → ScreenCaptureService.startWithProjection()
                            │
                            └─ FloatWindowManager.showSelectOverlayForScreenCapture()
                                    │
                                    └─ 用户框选 → ScreenCaptureService.updateSelectionRect()
                                                      + triggerCaptureOnce()
                                                      │
                                                      ├─ PaddleOCREngine.recognize()
                                                      │
                                                      └─ QuestionBankSearcher.search()
                                                              │
                                                              └─ FloatWindowManager.showAnswerWindow()
```

### 5.3 数据流全景

```
┌──────────────┐     ┌────────────────────┐     ┌──────────────────┐
│ ExcelImporter │ ──▶ │  QuizDatabase      │ ◀── │ QuestionBank     │
│ (Excel导入)   │     │  (Room DB)         │     │ Searcher         │
└──────────────┘     │  ┌───────────────┐  │     │ (题库检索)       │
                     │  │ QuestionEntity│  │     └──────────┬───────┘
                     │  │ WrongQuestion │  │                │
                     │  │ PracticeRecord│  │                │
                     │  └───────────────┘  │                │
                     └────────────────────┘                │
                                                            │
┌──────────────────────────┐                               │
│ AccessibilitySearchService│ ── 题干文本 ──────────────────┘
│ (无障碍文本提取)          │
└──────────────────────────┘
                                                            │
┌──────────────────────────┐                               │
│ ScreenCaptureService     │ ── OCR 文本 ──────────────────┘
│ (录屏+OCR识别)           │
└──────────────────────────┘
```

---

## 6. 依赖关系

### 6.1 外部依赖

| 依赖库 | 版本 | 用途 |
|--------|------|------|
| `androidx.core:core-ktx` | 1.12.0 | AndroidX 核心扩展 |
| `androidx.activity:activity-compose` | 1.8.2 | Compose Activity 集成 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | Lifecycle 协程支持 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.7.0 | ViewModel 与 Compose 集成 |
| `androidx.navigation:navigation-compose` | 2.7.7 | Compose 导航 |
| `androidx.compose:compose-bom` | 2024.01.00 | Compose 物料清单（统一版本） |
| `androidx.compose.material3:material3` | BOM 管理 | Material3 组件库 |
| `androidx.room:room-runtime` | 2.6.1 | Room 数据库运行时 |
| `androidx.room:room-ktx` | 2.6.1 | Room 协程扩展 |
| `androidx.room:room-compiler` | 2.6.1 | Room 注解处理器（KSP） |
| `androidx.camera:camera-core` | 1.3.1 | CameraX 核心（相机兜底方案） |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.7.3 | 协程核心库 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | 协程 Android 调度器 |
| `org.apache.poi:poi` | 5.2.5 | Excel .xls 解析 |
| `org.apache.poi:poi-ooxml` | 5.2.5 | Excel .xlsx 解析 |
| `com.google.code.gson:gson` | 2.10.1 | JSON 解析 |
| `io.coil-kt:coil-compose` | 2.5.0 | Compose 图片加载 |
| PaddleOCR AAR (本地) | 2.7.0 | OCR 文字识别引擎 |

### 6.2 模块内部依赖关系

```
SearchApplication
  ├── PermissionManager
  ├── FloatWindowManager
  │     ├── FloatSelectOverlay
  │     └── AnswerFloatWindow
  ├── PaddleOCREngine
  └── QuizDatabase

HomeActivity
  ├── PermissionManager
  ├── FloatWindowManager
  ├── FloatingWindowService
  │     └── AccessibilitySearchServiceHolder (静态引用)
  ├── AccessibilitySearchService
  └── ScreenCaptureService

FloatingWindowService
  ├── PermissionManager
  ├── FloatWindowManager
  └── AccessibilitySearchServiceHolder

ExcelImporter
  └── QuizDatabase
      ├── QuestionDao
      ├── WrongQuestionDao
      └── PracticeRecordDao

RectUtil (无依赖，纯工具类)
```

---

## 7. 项目运行方式

### 7.1 环境准备

| 项目 | 要求 |
|------|------|
| Android Studio | Hedgehog (2023.1.1) 或更新 |
| JDK | 17 |
| Android SDK | API 34 |
| NDK | 25.x 或 26.x（PaddleOCR 需要） |
| Gradle | 8.4（项目自带 wrapper） |
| 真机 | Android 10 ~ 14，ARM64 架构 |

### 7.2 PaddleOCR 模型准备

将以下模型文件放入 `app/src/main/assets/ocr/`：

```
app/src/main/assets/ocr/
├── ch_ppocr_mobile_v2.0_det_opt.nb   # 文字检测模型
├── ch_ppocr_mobile_v2.0_cls_opt.nb   # 方向分类模型
├── ch_ppocr_mobile_v2.0_rec_opt.nb   # 文字识别模型
└── ppocr_keys_v1.txt                  # 中文字典
```

将 PaddleOCR AAR 放入 `app/libs/` 目录，并在 `app/build.gradle.kts` 中取消注释：

```kotlin
implementation(files("libs/PaddleOCR-2.7.0.aar"))
```

### 7.3 编译运行

**命令行编译**：
```bash
./gradlew clean assembleDebug          # 编译 Debug APK
./gradlew assembleRelease              # 编译 Release APK
```

**一键编译脚本**：
```bash
./build.sh debug                       # 编译 Debug
./build.sh release                     # 编译 Release
./build.sh both                        # 同时编译 Debug + Release
```

**真机安装**：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 7.4 使用流程

1. 打开 App → 首页显示四项权限状态卡片
2. 点击「无障碍搜题（推荐）」→ 依次授权悬浮窗 + 无障碍服务
3. 悬浮球出现在屏幕右侧 → 点击悬浮球 → 显示选题框
4. 拖拽选框覆盖题目区域 → 松手自动识别 → 弹出答案弹窗
5. 或点击「录屏搜题（OCR 识别）」→ 授权录屏 → 框选 → OCR 识别

### 7.5 查看日志

```bash
adb logcat -s AccessibilitySearchSvc:* ScreenCaptureService:* PaddleOCREngine:* FloatWindowManager:* SearchApplication:*
```

---

## 8. 权限说明

### 8.1 权限清单

| 权限 | 用途 | 最低版本 | 类型 |
|------|------|----------|------|
| `SYSTEM_ALERT_WINDOW` | 悬浮窗显示（选题框、答案弹窗、悬浮球） | API 1 | 特殊权限（需跳转设置页） |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务绑定 | API 4 | 特殊权限（需跳转设置页） |
| `FOREGROUND_SERVICE` | 前台服务保活 | API 9 | 普通权限 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 录屏前台服务类型（Android 14+） | API 34 | 普通权限 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 悬浮球前台服务类型（Android 14+） | API 34 | 普通权限 |
| `CAMERA` | 相机拍照 OCR 兜底方案 | API 21 | 运行时权限 |
| `INTERNET` | 网络访问（可能用于 OCR 模型下载） | API 1 | 普通权限 |
| `WAKE_LOCK` | 防止录屏时 CPU 休眠 | API 1 | 普通权限 |

### 8.2 权限优先级策略

`PermissionManager` 中的 `getRecommendedMode()` 按以下优先级决定搜题模式：

1. **无障碍服务**（最高优先级）— 无需用户每次确认，最无感
2. **录屏** — 需要用户单次授权，体验次之
3. **相机** — 需要用户每次拍照，体验最差，作为兜底
4. **无可用模式** — 引导用户授权

### 8.3 版本兼容要点

| 版本 | 关键适配点 |
|------|-----------|
| Android 10 (API 29) | 分区存储引入，`requestLegacyExternalStorage=true` |
| Android 11 (API 30) | 分区存储强制启用（`requestLegacyExternalStorage` 无效） |
| Android 13 (API 33) | 前台服务通知渠道重要性必须 ≤ `IMPORTANCE_DEFAULT` |
| Android 14 (API 34) | 前台服务必须声明 `foregroundServiceType`（`mediaProjection` / `specialUse`） |

---

## 附录：现有文档索引

| 文档 | 说明 |
|------|------|
| [SmartSearch_完整调用链路说明.md](file:///workspace/SmartSearch_完整调用链路说明.md) | 从点击按钮到展示答案的全流程说明 |
| [SmartSearch_编译与调试指南.md](file:///workspace/SmartSearch_编译与调试指南.md) | 编译打包、真机调试、Release 打包 |
| [SmartSearch_项目目录结构.md](file:///workspace/SmartSearch_项目目录结构.md) | 工程目录结构总览 |
| [Code_Wiki_SmartSearch.md](file:///workspace/Code_Wiki_SmartSearch.md) | 本文档：项目整体架构、模块职责、类说明、依赖关系、运行方式 |