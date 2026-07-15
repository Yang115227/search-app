# SmartSearch 编译打包与真机调试步骤说明

> 版本：AGP 8.1.3 | Kotlin 1.9.22 | Gradle 8.4 | JDK 17 | compileSdk 34

---

## 一、环境准备

### 1.1 硬件要求

| 项目 | 最低要求 |
|------|---------|
| 内存 | 16 GB（推荐 32 GB，Gradle 守护进程需 2 GB） |
| 磁盘 | 10 GB 可用空间 |
| 真机 | Android 10 ~ Android 14，ARM64 架构 |

### 1.2 软件安装

| 软件 | 版本 | 说明 |
|------|------|------|
| Android Studio | **Hedgehog (2023.1.1)** 或更新 | 必须 Hedgehog 以上以支持 AGP 8.1.x |
| JDK | **17** | 必须 JDK 17，AGP 8.x 强制要求 |
| Android SDK | API 34 | compileSdk = 34 |
| NDK | 25.x 或 26.x | PaddleOCR 推理需要（arm64-v8a + armeabi-v7a） |
| Gradle | 8.4（自动下载） | 项目自带 wrapper，无需手动安装 |

### 1.3 验证 JDK 版本

```bash
# 终端执行
java -version
# 期望输出：openjdk version "17.0.x" ...

# 如果版本不对，在 Android Studio 中设置：
# File → Project Structure → SDK Location → JDK Location → 选择 JDK 17 路径
```

### 1.4 验证 Android SDK

```bash
# Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK
# 确认已安装：
# - Android SDK Platform 34
# - Android SDK Build-Tools 34.0.0+
# - NDK (Side by side) 25.x+
```

---

## 二、PaddleOCR 模型文件准备

### 2.1 下载模型文件

PaddleOCR 需要以下 4 个模型文件，放入 `app/src/main/assets/ocr/` 目录：

```
app/src/main/assets/ocr/
├── ch_ppocr_mobile_v2.0_det_opt.nb   # 文字检测模型（~2.5 MB）
├── ch_ppocr_mobile_v2.0_cls_opt.nb   # 方向分类模型（~1.5 MB）
├── ch_ppocr_mobile_v2.0_rec_opt.nb   # 文字识别模型（~5.5 MB）
└── ppocr_keys_v1.txt                  # 中文字典（~150 KB）
```

### 2.2 下载地址

从 PaddleOCR 官方 GitHub 下载：

```bash
# 克隆 PaddleOCR 仓库
git clone https://github.com/PaddlePaddle/PaddleOCR.git

# 复制模型文件到项目中
cp PaddleOCR/deploy/android_demo/app/src/main/assets/models/ch_ppocr_mobile_v2.0_det_opt.nb \
   app/src/main/assets/ocr/
cp PaddleOCR/deploy/android_demo/app/src/main/assets/models/ch_ppocr_mobile_v2.0_cls_opt.nb \
   app/src/main/assets/ocr/
cp PaddleOCR/deploy/android_demo/app/src/main/assets/models/ch_ppocr_mobile_v2.0_rec_opt.nb \
   app/src/main/assets/ocr/
cp PaddleOCR/deploy/android_demo/app/src/main/assets/models/ppocr_keys_v1.txt \
   app/src/main/assets/ocr/
```

### 2.3 放置 PaddleOCR AAR

```bash
# 将 PaddleOCR 的 AAR 文件放入 app/libs/ 目录
cp PaddleOCR-2.7.0.aar app/libs/

# 在 app/build.gradle.kts 中取消注释：
# implementation(files("libs/PaddleOCR-2.7.0.aar"))
```

---

## 三、编译步骤

### 3.1 命令行编译

```bash
# 进入项目根目录
cd SmartSearch/

# 赋予 Gradle Wrapper 执行权限（仅 Linux/macOS）
chmod +x gradlew

# 清理构建缓存
./gradlew clean

# 编译 Debug 版本（只编译，不安装）
./gradlew assembleDebug

# 编译 Release 版本
./gradlew assembleRelease
```

### 3.2 Android Studio 编译

```
1. 用 Android Studio 打开项目根目录 SmartSearch/
2. 等待 Gradle Sync 完成（首次约 5~10 分钟，需下载依赖）
3. 菜单：Build → Make Project
4. 菜单：Build → Build Bundle(s) / APK(s) → Build APK(s)
```

### 3.3 常见编译问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| `Unsupported class file major version 61` | JDK 版本不是 17 | 在 Android Studio 中设置 JDK 17 |
| `Could not resolve androidx.compose:compose-bom` | 需添加 Google Maven 仓库 | 检查 `settings.gradle.kts` 中 `google()` 仓库 |
| `Namespace not specified` | AGP 8.x 要求在 build.gradle.kts 中声明 namespace | 已在 `app/build.gradle.kts` 中配置 |
| `Duplicate class` | POI 依赖冲突 | 已在 `packaging.excludes` 中处理 |
| `Cannot find Room processor` | ksp 未正确配置 | 确认 `app/build.gradle.kts` 中 `ksp(libs.room.compiler)` |
| PaddleOCR AAR 找不到 | AAR 未放入 libs 目录 | 将 AAR 放入 `app/libs/` 并取消注释依赖 |

---

## 四、真机调试步骤

### 4.1 连接设备

```bash
# 确认设备已连接（开启 USB 调试）
adb devices
# 期望输出：
# List of devices attached
# XXXXXXXX    device
```

### 4.2 安装 Debug APK

```bash
# 安装到设备
./gradlew installDebug

# 或手动安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4.3 授权流程

安装后按以下顺序操作：

```
1. 打开「智能搜题」App
   → 首页显示四项权限状态卡片

2. 点击「无障碍搜题（推荐）」
   → 弹出悬浮窗权限设置页 → 开启「允许显示在其他应用上层」
   → 返回 App → 弹出无障碍服务设置页 → 找到「智能搜题」→ 开启服务
   → 返回 App → 悬浮球出现在屏幕右侧

3. 点击悬浮球
   → 全屏出现半透明遮罩 + 选题框
   → 拖拽选框覆盖题目区域
   → 松手自动识别
   → 弹出答案弹窗

4. 录屏模式（可选）
   → 点击「录屏搜题（OCR 识别）」→ 授权录屏
   → 框选后 OCR 识别 → 展示答案
```

### 4.4 查看日志

```bash
# 过滤 SmartSearch 相关日志
adb logcat -s AccessibilitySearchSvc:* ScreenCaptureService:* PaddleOCREngine:* FloatWindowManager:* SearchApplication:*

# 查看所有 App 日志
adb logcat | grep -E "SmartSearch|AccessibilitySearch|ScreenCapture|PaddleOCR"
```

### 4.5 提取 Debug 截图

```bash
# 录屏 OCR 识别失败时，截图会自动保存到应用缓存目录
adb pull /data/data/com.smartsearch.app.debug/cache/debug_capture/ ./debug_screenshots/
```

---

## 五、Release 打包

### 5.1 生成签名密钥

```bash
# 生成密钥（仅首次）
keytool -genkey -v \
  -keystore smartsearch.keystore \
  -alias smartsearch \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass your_password \
  -keypass your_password \
  -dname "CN=SmartSearch, OU=Dev, O=SmartSearch, L=Beijing, ST=Beijing, C=CN"
```

### 5.2 配置签名

在 `app/build.gradle.kts` 中取消注释 release 签名配置：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../smartsearch.keystore")
        storePassword = "your_password"
        keyAlias = "smartsearch"
        keyPassword = "your_password"
    }
}
```

### 5.3 打包 Release APK

```bash
./gradlew assembleRelease
# 输出路径：app/build/outputs/apk/release/app-release.apk
```

### 5.4 打包 AAB（Google Play 上架）

```bash
./gradlew bundleRelease
# 输出路径：app/build/outputs/bundle/release/app-release.aab
```

---

## 六、项目文件结构总览

```
SmartSearch/
├── build.gradle.kts                          # 根级构建脚本
├── settings.gradle.kts                       # 项目设置
├── gradle.properties                         # Gradle 属性
├── gradlew / gradlew.bat                     # Gradle Wrapper
├── .gitignore                                # Git 忽略规则
├── gradle/
│   ├── libs.versions.toml                    # 版本目录
│   └── wrapper/
│       └── gradle-wrapper.properties         # Gradle 8.4
│
└── app/
    ├── build.gradle.kts                      # 模块构建脚本
    ├── proguard-rules.pro                    # 混淆规则
    ├── libs/                                 # PaddleOCR AAR
    ├── schemas/                              # Room schema 导出
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/ocr/                   # PaddleOCR 模型文件
        │   ├── java/com/smartsearch/app/
        │   │   ├── SearchApplication.kt
        │   │   ├── core/
        │   │   │   ├── permission/PermissionManager.kt
        │   │   │   ├── service/
        │   │   │   │   ├── FloatingWindowService.kt
        │   │   │   │   └── FloatWindowManager.kt
        │   │   │   └── utils/RectUtil.kt
        │   │   ├── data/
        │   │   │   ├── local/
        │   │   │   │   ├── QuizDatabase.kt
        │   │   │   │   ├── entity/ (QuestionEntity, WrongQuestionEntity, PracticeRecordEntity)
        │   │   │   │   └── dao/ (QuestionDao, WrongQuestionDao, PracticeRecordDao)
        │   │   │   └── parser/ExcelImporter.kt
        │   │   ├── feature/
        │   │   │   ├── search/
        │   │   │   │   ├── accessibility/AccessibilitySearchService.kt
        │   │   │   │   ├── capture/
        │   │   │   │   │   ├── PaddleOCREngine.kt
        │   │   │   │   │   ├── QuestionBankSearcher.kt
        │   │   │   │   │   └── ScreenCaptureService.kt
        │   │   │   │   └── floatview/
        │   │   │   │       ├── FloatSelectOverlay.kt
        │   │   │   │       └── AnswerFloatWindow.kt
        │   │   │   └── practice/ (ViewModel 占位)
        │   │   └── ui/HomeScreen.kt
        │   └── res/
        │       ├── drawable/ (ic_launcher_background, ic_launcher_foreground)
        │       ├── mipmap-anydpi-v26/ (ic_launcher)
        │       ├── values/ (colors, strings, dimens, themes)
        │       └── xml/ (accessibility_service_config, file_paths, backup_rules, data_extraction_rules)
        ├── test/
        └── androidTest/
```

---

## 七、快速验证清单

- [ ] `./gradlew clean` 成功
- [ ] `./gradlew assembleDebug` 成功生成 APK
- [ ] APK 安装后首页正常显示
- [ ] 四项权限状态正确展示
- [ ] 悬浮窗权限 → 跳转设置页 → 开启 → 返回
- [ ] 无障碍服务 → 跳转设置页 → 开启 → 返回
- [ ] 悬浮球显示在屏幕右侧
- [ ] 点击悬浮球 → 选题框弹出 → 半透明遮罩正确
- [ ] 拖拽选区 → 松手 → 正常识别（或提示"未找到匹配"）
- [ ] X 关闭按钮 → 选题框消失
- [ ] 答案弹窗 → 返回箭头 → 重新选题
- [ ] 录屏授权 → 录屏模式 → 框选 → OCR 识别
- [ ] Excel 文件选择 → 导入成功提示
- [ ] `adb logcat` 日志输出正常，无崩溃