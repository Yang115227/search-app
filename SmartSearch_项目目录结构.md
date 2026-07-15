# SmartSearch 安卓工程完整目录结构

> 适配 Android Studio Hedgehog | AGP 8.1.3 | Kotlin 1.9.22 | Gradle 8.4+

---

## 一、工程根目录

```
SmartSearch/
├── .gitignore                          # Git忽略规则（*.iml、build/、.idea/、local.properties等）
├── build.gradle.kts                    # 根级构建脚本，声明全局插件与仓库
├── settings.gradle.kts                 # 项目设置，声明模块、依赖仓库、版本目录
├── gradle.properties                   # Gradle守护进程JVM参数、AndroidX/非传递R类开关
├── gradlew                             # Unix Gradle包装器脚本
├── gradlew.bat                         # Windows Gradle包装器脚本
├── local.properties                    # 本地SDK/NDK路径（不纳入版本控制）
│
├── gradle/
│   ├── libs.versions.toml              # 版本目录：统一管理AGP、Kotlin、Compose、Room等依赖版本
│   └── wrapper/
│       ├── gradle-wrapper.jar          # Gradle包装器JAR
│       └── gradle-wrapper.properties   # 包装器版本配置（distributionUrl=8.4+）
│
├── .idea/                              # IDE配置（codeStyles、runConfigurations、compiler.xml等）
│   ├── codeStyles/
│   ├── inspectionProfiles/
│   └── kotlinc.xml
│
└── app/                                # 主应用模块
    ├── build.gradle.kts                # 模块构建脚本：插件声明、Android配置、依赖声明
    ├── proguard-rules.pro              # 混淆规则
    │
    ├── libs/                           # 本地AAR/JAR（如第三方OCR SDK）
    │
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # 清单文件：四大组件声明、权限声明、Application入口
        │   │
        │   ├── java/com/smartsearch/app/
        │   │   │
        │   │   ├── SearchApplication.kt
        │   │   │   # App全局Application入口，初始化日志、数据库、线程池、悬浮窗权限
        │   │   │
        │   │   ├── core/                # 全局基础工具
        │   │   │   │
        │   │   │   ├── permission/
        │   │   │   │   └── PermissionManager.kt
        │   │   │   │       # 权限判断管理器：运行时权限请求、悬浮窗/无障碍/录屏特殊权限检测与引导
        │   │   │   │
        │   │   │   ├── utils/
        │   │   │   │   ├── RectUtil.kt          # 矩形工具类：坐标转换、裁剪区域计算、屏幕尺寸适配
        │   │   │   │   ├── Logger.kt            # 统一日志工具：支持Debug/Release分级、文件输出
        │   │   │   │   ├── FileUtil.kt          # 文件工具：缓存路径获取、Excel文件读写、图片压缩
        │   │   │   │   ├── ScreenUtil.kt        # 屏幕工具：dp/px转换、状态栏/导航栏高度、屏幕尺寸
        │   │   │   │   └── StringUtil.kt        # 字符串工具：空判断、截断、格式化
        │   │   │   │
        │   │   │   └── service/
        │   │   │       └── FloatingWindowService.kt
        │   │   │           # 悬浮球后台服务：前台Service保活、悬浮球生命周期管理、窗口动画
        │   │   │
        │   │   ├── data/                # 数据层
        │   │   │   │
        │   │   │   ├── local/
        │   │   │   │   ├── AppDatabase.kt        # Room数据库定义：版本管理、迁移策略
        │   │   │   │   ├── entity/
        │   │   │   │   │   ├── QuestionEntity.kt       # 题目实体：题干、选项、答案、解析、学科、来源
        │   │   │   │   │   ├── WrongQuestionEntity.kt  # 错题实体：关联题目ID、错误次数、最后错误时间
        │   │   │   │   │   └── PracticeRecordEntity.kt # 练习记录实体：练习时间、正确率、题目数量
        │   │   │   │   └── dao/
        │   │   │   │       ├── QuestionDao.kt          # 题目DAO：CRUD、按学科/关键词查询、分页
        │   │   │   │       ├── WrongQuestionDao.kt     # 错题DAO：增删查、错误次数统计、按时间排序
        │   │   │   │       └── PracticeRecordDao.kt    # 练习记录DAO：记录写入、历史查询、统计聚合
        │   │   │   │
        │   │   │   └── parser/
        │   │   │       └── ExcelParser.kt
        │   │   │           # Excel题库导入解析器：读取xlsx文件、解析行列映射为QuestionEntity、批量写入Room
        │   │   │
        │   │   ├── feature/             # 功能模块
        │   │   │   │
        │   │   │   ├── search/          # 搜题核心
        │   │   │   │   │
        │   │   │   │   ├── accessibility/
        │   │   │   │   │   └── AccessibilitySearchService.kt
        │   │   │   │   │       # 无障碍搜题服务：监听屏幕内容变化、截取题目文本、触发OCR识别、匹配题库
        │   │   │   │   │
        │   │   │   │   ├── capture/
        │   │   │   │   │   └── ScreenCaptureService.kt
        │   │   │   │   │       # 录屏服务：MediaProjection采集屏幕、输出Bitmap帧供OCR识别
        │   │   │   │   │
        │   │   │   │   └── floatview/
        │   │   │   │       ├── FloatSelectOverlay.kt
        │   │   │   │       │   # 区域性选择悬浮窗：用户拖拽选取屏幕区域后进行OCR识别搜题
        │   │   │   │       └── AnswerFloatWindow.kt
        │   │   │   │           # 答案展示悬浮窗：显示匹配结果、答案、解析，支持拖拽、关闭
        │   │   │   │
        │   │   │   └── practice/       # 题库练习与错题本
        │   │   │       ├── PracticeViewModel.kt
        │   │   │       │   # 练习逻辑ViewModel：随机出题、答题校验、正确率计算
        │   │   │       └── WrongBookViewModel.kt
        │   │   │           # 错题本ViewModel：错题列表、重新练习、自动移除已掌握题目
        │   │   │
        │   │   └── ui/                  # 界面层
        │   │       ├── MainActivity.kt
        │   │       │   # 首页：悬浮球开关、题库管理入口、练习入口、错题本入口
        │   │       ├── PermissionGuideActivity.kt
        │   │       │   # 权限引导页：悬浮窗/无障碍/录屏三项权限的状态检测与跳转引导
        │   │       ├── QuestionImportActivity.kt
        │   │       │   # 题库导入页：文件选择、导入进度、解析结果展示
        │   │       ├── PracticeActivity.kt
        │   │       │   # 练习页：题目展示、选项选择、答题反馈、正确率统计
        │   │       ├── WrongBookActivity.kt
        │   │       │   # 错题本页：错题列表、按学科筛选、重新练习入口
        │   │       └── theme/
        │   │           └── Theme.kt     # Compose主题定义：颜色、排版、形状
        │   │
        │   └── res/
        │       ├── drawable/            # 矢量图标、形状（浮窗、按钮、背景等）
        │       ├── drawable-xxhdpi/     # 3x位图资源（启动图、悬浮球图标等）
        │       ├── layout/             # XML布局文件（如使用View体系而非全Compose）
        │       ├── mipmap-*/           # 启动器图标（各密度）
        │       ├── values/
        │       │   ├── strings.xml     # 字符串资源
        │       │   ├── colors.xml      # 颜色资源
        │       │   ├── themes.xml      # 原生主题（Material3 DayNight）
        │       │   └── dimens.xml      # 尺寸常量
        │       └── xml/
        │           ├── accessibility_service_config.xml   # 无障碍服务声明
        │           └── file_paths.xml                     # FileProvider路径配置
        │
        ├── test/                        # 单元测试
        │   └── java/com/smartsearch/app/
        │       ├── data/parser/ExcelParserTest.kt
        │       └── feature/search/accessibility/
        │
        └── androidTest/                # 仪器化测试
            └── java/com/smartsearch/app/
                └── data/local/dao/QuestionDaoTest.kt
```

---

## 二、各目录用途说明

### 根级文件

| 文件 | 用途 |
|------|------|
| `build.gradle.kts` | 根级构建脚本，声明 Android Gradle Plugin、Kotlin 插件及全局 Maven 仓库 |
| `settings.gradle.kts` | 项目设置，通过 `libs.versions.toml` 统一管理依赖版本，启用 `dependencyResolutionManagement` |
| `gradle.properties` | 配置 Gradle 守护进程内存、启用 AndroidX、非传递 R 类、Compose Compiler 开关 |
| `gradle/libs.versions.toml` | 版本目录：集中声明 AGP 8.1.3、Kotlin 1.9.22、Room、Compose BOM、Coroutines 等版本号 |
| `local.properties` | 本地 SDK 路径，不纳入版本控制 |

### `app/build.gradle.kts` 核心配置项

- `compileSdk = 34`、`minSdk = 26`、`targetSdk = 34`
- 开启 `buildFeatures { compose = true }`
- `composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }`（匹配 Kotlin 1.9.22）
- 依赖：Room、Navigation-Compose、Material3、Coroutines、MediaProjection、Accessibility

### `core/` — 全局基础工具

| 子目录 | 职责 |
|--------|------|
| `permission/` | 封装 `SYSTEM_ALERT_WINDOW`、`BIND_ACCESSIBILITY_SERVICE`、`MEDIA_PROJECTION` 三类特殊权限的检测、请求与引导跳转逻辑 |
| `utils/` | 项目通用工具函数集合，涵盖屏幕坐标计算、日志输出、文件读写、字符串处理 |
| `service/` | 悬浮球前台 Service，承担保活职责，控制悬浮球在系统层面的显示与隐藏 |

### `data/` — 数据层

| 子目录 | 职责 |
|--------|------|
| `local/entity/` | Room 实体类映射数据库表结构，定义主键、索引、外键关系 |
| `local/dao/` | 数据访问对象，提供对题目、错题、练习记录的增删改查及统计查询 |
| `local/AppDatabase.kt` | Room 数据库入口，声明实体列表、版本号、迁移策略 |
| `parser/` | 解析外部 Excel 文件（`.xlsx`），将行数据映射为题目实体并批量入库 |

### `feature/search/` — 搜题核心

| 子目录 | 职责 |
|--------|------|
| `accessibility/` | 无障碍服务，监听前台应用屏幕内容变化，提取可见文本信息用于触发搜题 |
| `capture/` | 录屏服务，通过 `MediaProjection` API 截取当前屏幕帧，输出 Bitmap 供 OCR 引擎识别 |
| `floatview/` | 两个悬浮窗：`FloatSelectOverlay` 让用户框选屏幕区域后搜题，`AnswerFloatWindow` 展示搜索结果（答案、解析） |

### `feature/practice/` — 题库练习

| 职责 |
|------|
| 提供随机出题、答题校验、正确率统计功能；错题本管理：记录错题、按错误次数排序、掌握后自动移除 |

### `ui/` — 界面层

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 首页入口，展示悬浮球开关状态、题库数量、练习/错题本入口按钮 |
| `PermissionGuideActivity.kt` | 权限引导页，三步引导用户分别开启悬浮窗、无障碍、录屏权限 |
| `QuestionImportActivity.kt` | 文件选择器导入 Excel 题库，展示解析进度与结果 |
| `PracticeActivity.kt` | 题目练习界面，显示题目、选项、答题结果反馈 |
| `WrongBookActivity.kt` | 错题列表，按学科筛选，支持重新练习 |
| `theme/Theme.kt` | Jetpack Compose Material3 主题定义 |

### `SearchApplication.kt` — 应用入口

初始化全局组件：日志框架、Room 数据库实例、全局 CoroutineScope、悬浮窗权限预检。

---

## 三、关键依赖版本速查

| 组件 | 版本 |
|------|------|
| Android Gradle Plugin | 8.1.3 |
| Kotlin | 1.9.22 |
| Kotlin Compose Compiler | 1.5.8 |
| Gradle | 8.4 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| Room | 2.6.1 |
| Compose BOM | 2024.01.00 |
| Coroutines | 1.7.3 |