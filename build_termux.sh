#!/usr/bin/env bash
# ============================================
#  SmartSearch Termux 本地编译脚本
#  在 Android 手机上通过 Termux 直接编译 APK
#  使用前请确认已安装: pkg install openjdk-17 gradle android-sdk
# ============================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "${CYAN}"
echo "╔════════════════════════════════════════╗"
echo "║  SmartSearch Termux 本地编译           ║"
echo "║  在 Android 手机上编译 APK             ║"
echo "╚════════════════════════════════════════╝"
echo -e "${NC}"

# ════════════════════════════════════════════
#  第 1 步：安装 Termux 依赖
# ════════════════════════════════════════════
install_deps() {
    info "安装 Termux 编译依赖..."
    pkg update -y 2>/dev/null || true
    pkg install -y openjdk-17 gradle 2>/dev/null || {
        error "依赖安装失败，请手动执行: pkg install openjdk-17 gradle"
    }
    info "依赖安装完成"
}

# ════════════════════════════════════════════
#  第 2 步：配置 Android SDK（Termux 专用）
# ════════════════════════════════════════════
setup_sdk() {
    # Termux 的 Android SDK 路径
    local SDK_PATH="$HOME/../usr/share/android-sdk"

    if [ ! -d "$SDK_PATH" ]; then
        warn "未安装 android-sdk，请执行: pkg install android-sdk"
        warn "安装后运行: sdkmanager --install 'platforms;android-34' 'build-tools;34.0.0'"
        return 1
    fi

    export ANDROID_HOME="$SDK_PATH"
    export ANDROID_SDK_ROOT="$SDK_PATH"

    # 创建 local.properties
    cat > "$SCRIPT_DIR/local.properties" << EOF
sdk.dir=$SDK_PATH
EOF
    info "Android SDK 已配置: $SDK_PATH"
}

# ════════════════════════════════════════════
#  第 3 步：调整 JVM 堆（Termux 内存有限）
# ════════════════════════════════════════════
setup_jvm() {
    # Termux 通常内存受限，限制 Gradle 堆
    export GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1024m -XX:MaxMetaspaceSize=256m'"
    export JAVA_OPTS="-Xmx1024m"
    info "JVM 堆已限制为 1GB"
}

# ════════════════════════════════════════════
#  第 4 步：编译
# ════════════════════════════════════════════
build() {
    local BUILD_TYPE="${1:-debug}"

    info "编译 $BUILD_TYPE APK (arm64-v8a)..."
    info "此过程在手机上可能需要 10~30 分钟，请耐心等待..."

    # 使用 Gradle Wrapper
    chmod +x ./gradlew 2>/dev/null || true

    case "$BUILD_TYPE" in
        debug)
            ./gradlew assembleDebug --no-daemon --info 2>&1 | tee build_termux.log
            local APK="app/build/outputs/apk/debug/app-debug.apk"
            ;;
        release)
            ./gradlew assembleRelease --no-daemon --info 2>&1 | tee build_termux.log
            local APK="app/build/outputs/apk/release/app-release.apk"
            ;;
        *)
            error "未知构建类型: $BUILD_TYPE"
            ;;
    esac

    if [ -f "$APK" ]; then
        info "编译成功!"
        info "APK 路径: $(realpath "$APK")"
        info "文件大小: $(du -sh "$APK" | cut -f1)"
        info ""
        info "安装命令:"
        info "  cd $(realpath "$(dirname "$APK")")"
        info "  termux-open $APK  # 或直接点击安装"
    else
        error "编译失败，请查看 build_termux.log"
    fi
}

# ════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════
case "${1:-build}" in
    install)
        install_deps
        setup_sdk
        setup_jvm
        info "环境准备完成，现在可以运行: bash build_termux.sh build"
        ;;
    build)
        setup_sdk
        setup_jvm
        build "${2:-debug}"
        ;;
    debug)
        setup_sdk
        setup_jvm
        build debug
        ;;
    release)
        setup_sdk
        setup_jvm
        build release
        ;;
    *)
        echo "用法:"
        echo "  bash build_termux.sh install   # 首次使用：安装依赖"
        echo "  bash build_termux.sh build     # 编译 Debug APK"
        echo "  bash build_termux.sh release   # 编译 Release APK"
        ;;
esac