# SmartSearch 云端构建指南

> 本文档说明如何配置和使用云端 CI/CD 环境自动构建 SmartSearch APK

---

## 目录

1. [方案概述](#1-方案概述)
2. [方案一：GitHub Actions（推荐）](#2-方案一github-actions推荐)
3. [方案二：Docker 容器化构建](#3-方案二docker-容器化构建)
4. [Release 签名配置](#4-release-签名配置)
5. [常见问题](#5-常见问题)

---

## 1. 方案概述

SmartSearch 提供两种云端构建方案：

| 方案 | 适用场景 | 优点 | 要求 |
|------|----------|------|------|
| **GitHub Actions** | 团队协作、CI/CD 自动化 | 无需自建服务器，触发即构建，产物可下载 | 代码托管在 GitHub |
| **Docker 容器化** | 本地/自建 CI 服务器 | 环境一致，可离线编译，适合私有化部署 | 需要 Docker 环境 |

---

## 2. 方案一：GitHub Actions（推荐）

### 2.1 工作流文件

项目已预配置 GitHub Actions 工作流，位于：

[`.github/workflows/build.yml`](file:///workspace/.github/workflows/build.yml)

### 2.2 触发方式

#### 自动触发（Push / PR）

```yaml
push:
  branches: [main, master, develop]    # 推送到这些分支时触发
  paths-ignore: ["**.md"]              # 纯文档变更不触发
pull_request:
  branches: [main, master]             # 向这些分支提 PR 时触发
```

#### 手动触发（Workflow Dispatch）

在 GitHub 仓库页面操作：

```
仓库 → Actions → SmartSearch CI → Run workflow
  ├── build_type: debug | release | both  （选择构建类型）
  └── run_tests: true | false              （是否运行测试）
```

### 2.3 构建产物

构建完成后，在 Actions 运行页面底部的 **Artifacts** 区域下载：

| 产物 | 说明 |
|------|------|
| `smartsearch-debug.zip` | Debug APK（包名: `com.smartsearch.app.debug`） |
| `smartsearch-release.zip` | Release APK（包名: `com.smartsearch.app`） |
| `test-reports.zip` | 单元测试报告（含 Lint 报告） |

### 2.4 工作流包含的 Job

| Job | 名称 | 说明 |
|-----|------|------|
| `lint_and_test` | 🔍 代码检查 + 单元测试 | 运行 `lintDebug` 和 `testDebugUnitTest` |
| `build_debug` | 📦 构建 Debug APK | 编译 Debug 版本并上传 |
| `build_release` | 📦 构建 Release APK | 编译 Release 版本（含签名）并上传 |
| `summary` | 📋 构建摘要 | 汇总各 Job 状态，输出到 GitHub Step Summary |

### 2.5 构建加速

工作流已配置 Gradle 依赖缓存：

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches/
      ~/.gradle/wrapper/
      .gradle/
    key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts', '**/libs.versions.toml') }}
```

- 首次构建约 **5~15 分钟**（下载所有依赖）
- 后续构建（依赖未变）约 **1~3 分钟**

---

## 3. 方案二：Docker 容器化构建

### 3.1 前置条件

- 安装 Docker Engine 24+ 或 Docker Desktop
- 至少 8 GB 可用内存

### 3.2 构建镜像

```bash
# 在项目根目录执行
docker build -t smartsearch-builder .
```

镜像包含：
- Ubuntu 22.04 LTS
- JDK 17 (Temurin)
- Android SDK 34 + Build-Tools 34.0.0
- NDK 25.2.9519653
- Gradle Wrapper

### 3.3 使用 Docker Compose 编译

```bash
# 编译 Debug APK
docker compose run debug

# 编译 Release APK
docker compose run release

# 清理构建缓存
docker compose run clean

# 进入交互式 Shell
docker compose run shell
```

### 3.4 手动使用 Docker 编译

```bash
# Debug 版本
docker run --rm \
  -v $(pwd):/workspace \
  -v gradle-cache:/root/.gradle/caches \
  smartsearch-builder \
  ./gradlew assembleDebug --no-daemon --stacktrace

# Release 版本
docker run --rm \
  -v $(pwd):/workspace \
  -v gradle-cache:/root/.gradle/caches \
  -v $(pwd)/smartsearch.keystore:/workspace/smartsearch.keystore \
  smartsearch-builder \
  ./gradlew assembleRelease --no-daemon --stacktrace
```

### 3.5 构建产出

APK 文件会直接输出到宿主机项目目录：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

---

## 4. Release 签名配置

### 4.1 GitHub Actions 签名配置（推荐）

在仓库 Secrets 中配置以下内容：

```
Settings → Secrets and variables → Actions → New repository secret
```

| Secret 名称 | 说明 | 获取方式 |
|-------------|------|----------|
| `KEYSTORE_BASE64` | 签名密钥库文件的 Base64 编码 | `base64 -w0 smartsearch.keystore` |
| `KEYSTORE_PASSWORD` | 密钥库密码 | 创建密钥库时设置 |
| `KEY_ALIAS` | 密钥别名 | `keytool -list -keystore smartsearch.keystore` |
| `KEY_PASSWORD` | 密钥密码 | 创建密钥时设置 |

**生成密钥库并获取 Base64：**

```bash
# 1. 生成密钥库（如尚未创建）
keytool -genkey -v \
  -keystore smartsearch.keystore \
  -alias smartsearch \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass your_password \
  -keypass your_password \
  -dname "CN=SmartSearch, OU=Dev, O=SmartSearch, L=Beijing, ST=Beijing, C=CN"

# 2. 获取 Base64 编码
base64 -w0 smartsearch.keystore   # Linux/macOS
# 复制输出结果，添加到 GitHub Secrets 的 KEYSTORE_BASE64

# 3. 查看密钥别名
keytool -list -keystore smartsearch.keystore -storepass your_password
```

### 4.2 本地构建签名

在项目根目录创建 `signing.properties` 文件：

```properties
storeFile=smartsearch.keystore
storePassword=your_password
keyAlias=smartsearch
keyPassword=your_password
```

然后将 `smartsearch.keystore` 文件放在项目根目录，执行 `./gradlew assembleRelease` 即可。

### 4.3 未配置签名的行为

如果未配置签名：
- **GitHub Actions**：Release APK 会使用 Debug 签名构建，**不可用于商店上架**，但可安装测试
- **本地构建**：使用 `app/build.gradle.kts` 中配置的 Debug 签名回退

---

## 5. 常见问题

### Q1: PaddleOCR AAR 缺失导致编译失败

**问题**：`app/libs/PaddleOCR-2.7.0.aar` 不存在，编译报找不到依赖。

**解决方案**：
- 将真实 AAR 文件放入 `app/libs/` 目录后提交到仓库
- 或在 CI 中通过脚本从私有存储下载

### Q2: 编译内存不足

**GitHub Actions**：默认 runner 有 7 GB 内存，当前配置 `Xmx4096m` 足够。

**Docker**：通过 `GRADLE_OPTS` 调整：
```bash
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2048m"
```

### Q3: 构建速度慢

- 第一次构建需要下载所有依赖，较慢是正常的
- 后续构建会利用缓存，速度会显著提升
- 可以在 `gradle.properties` 中开启 `org.gradle.parallel=true`（已默认开启）

### Q4: lint 检查失败

lint 警告不会阻断构建（配置了 `continue-on-error: true`），但建议修复。查看测试报告：

```
Actions → 点击运行 → Artifacts → 下载 test-reports.zip
→ 打开 app/build/reports/lint-results-debug.html
```

### Q5: 如何阻止 CI 运行

在 commit message 中包含以下关键字：
```
[skip ci]    # 跳过 CI
[ci skip]    # 跳过 CI
```

### Q6: Docker 构建镜像太大

当前镜像约 2~3 GB。如需减小体积，可以：
- 使用 `openjdk:17-slim` 作为基础镜像
- 只安装必要的 SDK 组件
- 添加 `.dockerignore` 排除不需要的文件

---

## 附录：文件清单

| 文件 | 路径 | 说明 |
|------|------|------|
| GitHub Actions 工作流 | [.github/workflows/build.yml](file:///workspace/.github/workflows/build.yml) | 自动构建 Debug/Release APK |
| Dockerfile | [Dockerfile](file:///workspace/Dockerfile) | 容器化编译环境 |
| Docker Compose | [docker-compose.yml](file:///workspace/docker-compose.yml) | 一键容器编译 |
| Gradle 属性 | [gradle.properties](file:///workspace/gradle.properties) | 构建参数配置 |
| 本地构建脚本 | [build.sh](file:///workspace/build.sh) | Linux/macOS 本地编译 |
| 本地构建脚本 | [build.bat](file:///workspace/build.bat) | Windows 本地编译 |
| Termux 构建脚本 | [build_termux.sh](file:///workspace/build_termux.sh) | Android 手机本地编译 |