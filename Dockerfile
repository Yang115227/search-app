# ============================================
# SmartSearch Docker 编译环境
# 用途: 在容器中编译 Android APK
# 基础镜像: Ubuntu 22.04 + JDK 17 + Android SDK 34
# 用法:
#   docker build -t smartsearch-builder .
#   docker run --rm -v $(pwd):/workspace smartsearch-builder
# ============================================

# ── 第一阶段: 基础环境 ──
FROM ubuntu:22.04 AS base

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    wget \
    unzip \
    git \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ── 第二阶段: Android SDK ──
FROM base AS sdk

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# 下载 Android 命令行工具
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools && \
    mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

# 安装 SDK 组件
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "ndk;25.2.9519653" \
    "platform-tools" \
    --sdk_root=${ANDROID_HOME}

# ── 第三阶段: 编译环境 ──
FROM sdk AS builder

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx4096m"
ENV CI=true

WORKDIR /workspace

# 复制项目文件
COPY . .

# 创建 PaddleOCR 占位目录
RUN mkdir -p app/src/main/assets/ocr app/libs && \
    touch app/libs/PaddleOCR-2.7.0.aar

# 赋予 Gradle Wrapper 执行权限
RUN chmod +x gradlew

# 默认入口: 编译 Debug APK
CMD ["./gradlew", "assembleDebug", "--no-daemon", "--stacktrace"]