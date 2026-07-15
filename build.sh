#!/usr/bin/env bash
# ============================================
#  SmartSearch 一键编译脚本
#  适用环境: Linux / macOS / Termux (Android)
#  产出: debug APK + release APK (arm64-v8a)
# ============================================
set -euo pipefail

# ── 颜色输出 ──
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }
step()  { echo -e "\n${CYAN}━━━ $* ━━━${NC}"; }

# ── 项目根目录 ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── 构建参数 ──
BUILD_TYPE="${1:-debug}"          # debug | release | both
CLEAN_FIRST="${2:-true}"          # true | false
SIGN_CONFIG="${3:-}"              # signing.properties 路径（release 签名用）

# ════════════════════════════════════════════
#  第 1 步：环境检测
# ════════════════════════════════════════════
step "检测编译环境"

# ── Java 版本 ──
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '\d+' | head -1 || echo "0")
if [ "$JAVA_VER" -lt 17 ]; then
    error "需要 JDK 17+，当前版本: $(java -version 2>&1 | head -1)"
fi
info "JDK 版本: $(java -version 2>&1 | head -1)"

# ── 操作系统 ──
OS_TYPE="$(uname -s)"
case "$OS_TYPE" in
    Linux*)  OS_NAME="linux" ;;
    Darwin*) OS_NAME="macos" ;;
    *)       OS_NAME="unknown" ;;
esac
info "操作系统: $OS_NAME"

# ── Termux 检测 ──
if [ -d "/data/data/com.termux" ] || [ -n "${TERMUX_VERSION:-}" ]; then
    IS_TERMUX=true
    warn "检测到 Termux 环境，已启用兼容模式"
    # Termux 下 JVM 堆限制
    export GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1536m'"
else
    IS_TERMUX=false
fi

# ── Gradle Wrapper ──
if [ ! -f "./gradlew" ]; then
    error "缺少 gradlew，请确认项目根目录正确"
fi
chmod +x ./gradlew 2>/dev/null || true

# ── Android SDK ──
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/usr/local/lib/android/sdk" ]; then
        export ANDROID_HOME="/usr/local/lib/android/sdk"
    elif [ "$IS_TERMUX" = true ] && [ -d "$HOME/../usr/share/android-sdk" ]; then
        # Termux 安装 android-sdk 后的默认路径
        export ANDROID_HOME="$HOME/../usr/share/android-sdk"
    else
        warn "未设置 ANDROID_HOME，将使用项目 local.properties"
    fi
fi
[ -n "${ANDROID_HOME:-}" ] && info "ANDROID SDK: $ANDROID_HOME"

# ════════════════════════════════════════════
#  第 2 步：签名配置
# ════════════════════════════════════════════
step "签名配置"

generate_keystore() {
    local KS_PATH="$SCRIPT_DIR/smartsearch.keystore"
    if [ -f "$KS_PATH" ]; then
        info "密钥库已存在: $KS_PATH"
        return 0
    fi
    info "生成签名密钥库..."
    keytool -genkey -v \
        -keystore "$KS_PATH" \
        -alias smartsearch \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=SmartSearch, OU=Dev, O=SmartSearch, L=Beijing, ST=Beijing, C=CN" \
        2>/dev/null || {
            warn "keytool 不可用，跳过密钥库生成（Release 编译将使用 Debug 签名）"
            return 1
        }
    info "密钥库已生成: $KS_PATH"
}

generate_signing_properties() {
    local SP_PATH="$SCRIPT_DIR/signing.properties"
    if [ -f "$SP_PATH" ]; then
        info "签名配置文件已存在: $SP_PATH"
        return 0
    fi
    cat > "$SP_PATH" << 'EOF'
# SmartSearch 签名配置
# 首次编译时自动生成，请妥善保管密钥库文件
storeFile=smartsearch.keystore
storePassword=android
keyAlias=smartsearch
keyPassword=android
EOF
    info "签名配置文件已生成: $SP_PATH"
}

generate_keystore
generate_signing_properties

# ════════════════════════════════════════════
#  第 3 步：依赖缓存预热（可选）
# ════════════════════════════════════════════
step "Gradle 编译"

if [ "$CLEAN_FIRST" = "true" ]; then
    info "清理构建缓存..."
    ./gradlew clean 2>&1 | tail -3
fi

# ════════════════════════════════════════════
#  第 4 步：编译
# ════════════════════════════════════════════
build_debug() {
    info "编译 Debug APK..."
    ./gradlew assembleDebug --no-daemon 2>&1 | tail -20

    local APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        local SIZE=$(du -sh "$APK_PATH" | cut -f1)
        echo -e "${GREEN}✓ Debug APK 编译成功${NC}"
        echo -e "  ${CYAN}路径:${NC} $(realpath "$APK_PATH")"
        echo -e "  ${CYAN}大小:${NC} $SIZE"
        echo -e "  ${CYAN}包名:${NC} com.smartsearch.app.debug"
        echo -e "  ${CYAN}ABI:${NC}  arm64-v8a, armeabi-v7a"
    else
        error "Debug APK 编译失败，请检查上方错误信息"
    fi
}

build_release() {
    info "编译 Release APK..."
    ./gradlew assembleRelease --no-daemon 2>&1 | tail -20

    local APK_PATH="app/build/outputs/apk/release/app-release.apk"
    if [ -f "$APK_PATH" ]; then
        local SIZE=$(du -sh "$APK_PATH" | cut -f1)
        echo -e "${GREEN}✓ Release APK 编译成功${NC}"
        echo -e "  ${CYAN}路径:${NC} $(realpath "$APK_PATH")"
        echo -e "  ${CYAN}大小:${NC} $SIZE"
        echo -e "  ${CYAN}包名:${NC} com.smartsearch.app"
        echo -e "  ${CYAN}ABI:${NC}  arm64-v8a, armeabi-v7a"
        echo -e "  ${CYAN}混淆:${NC} 已启用"
    else
        error "Release APK 编译失败，请检查上方错误信息"
    fi
}

# ── 执行编译 ──
case "$BUILD_TYPE" in
    debug)
        build_debug
        ;;
    release)
        build_release
        ;;
    both)
        build_debug
        echo ""
        build_release
        ;;
    *)
        error "未知构建类型: $BUILD_TYPE (可选: debug | release | both)"
        ;;
esac

# ════════════════════════════════════════════
#  第 5 步：输出摘要
# ════════════════════════════════════════════
step "编译完成"

echo -e "  ${CYAN}APK 输出目录:${NC}"
echo -e "    Debug:   app/build/outputs/apk/debug/app-debug.apk"
echo -e "    Release: app/build/outputs/apk/release/app-release.apk"
echo ""
echo -e "  ${CYAN}安装命令:${NC}"
echo -e "    adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo -e "  ${CYAN}仅编译 arm64-v8a:${NC}"
echo -e "    修改 app/build.gradle.kts ndk.abiFilters 为 ['arm64-v8a']"
echo ""
echo -e "  ${GREEN}━━━ 一键编译完成 ━━━${NC}"