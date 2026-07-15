@echo off
REM ============================================
REM  SmartSearch 一键编译脚本 (Windows)
REM  产出: debug APK + release APK (arm64-v8a)
REM ============================================
setlocal enabledelayedexpansion

set "BUILD_TYPE=%1"
if "%BUILD_TYPE%"=="" set "BUILD_TYPE=debug"
set "CLEAN_FIRST=%2"
if "%CLEAN_FIRST%"=="" set "CLEAN_FIRST=true"

echo.
echo ============================================
echo   SmartSearch 一键编译 - Windows
echo   构建类型: %BUILD_TYPE%
echo ============================================
echo.

REM ── 第 1 步：环境检测 ──
echo [INFO] 检测编译环境...

REM Java 版本检测
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%i
)
echo [INFO] JDK 版本: %JAVA_VER%

REM Java 17 检查
java -version 2>&1 | findstr /i "17\|18\|19\|20\|21\|22" >nul
if %ERRORLEVEL% NEQ 0 (
    echo [WARN] 推荐使用 JDK 17+，当前可能不是 JDK 17
)

REM Gradle Wrapper 检查
if not exist "gradlew.bat" (
    echo [ERROR] 缺少 gradlew.bat，请确认项目根目录正确
    exit /b 1
)

REM Android SDK 检测
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)
if defined ANDROID_HOME (
    echo [INFO] Android SDK: %ANDROID_HOME%
) else (
    echo [WARN] 未设置 ANDROID_HOME，将使用 local.properties
)

REM ── 第 2 步：签名准备 ──
echo.
echo [INFO] 签名配置...

if not exist "signing.properties" (
    echo [INFO] 生成 signing.properties...
    (
        echo # SmartSearch 签名配置
        echo storeFile=smartsearch.keystore
        echo storePassword=android
        echo keyAlias=smartsearch
        echo keyPassword=android
    ) > signing.properties
    echo [INFO] signing.properties 已生成
)

if not exist "smartsearch.keystore" (
    echo [INFO] 生成签名密钥库...
    keytool -genkey -v ^
        -keystore smartsearch.keystore ^
        -alias smartsearch ^
        -keyalg RSA ^
        -keysize 2048 ^
        -validity 10000 ^
        -storepass android ^
        -keypass android ^
        -dname "CN=SmartSearch, OU=Dev, O=SmartSearch, L=Beijing, ST=Beijing, C=CN" 2>nul
    if %ERRORLEVEL%==0 (
        echo [INFO] 密钥库已生成
    ) else (
        echo [WARN] keytool 不可用，Release 将使用 Debug 签名
    )
)

REM ── 第 3 步：编译 ──
echo.
echo [INFO] 开始编译...

if "%CLEAN_FIRST%"=="true" (
    echo [INFO] 清理构建缓存...
    call gradlew.bat clean
)

if "%BUILD_TYPE%"=="debug" (
    echo [INFO] 编译 Debug APK...
    call gradlew.bat assembleDebug --no-daemon
    if exist "app\build\outputs\apk\debug\app-debug.apk" (
        echo.
        echo ============================================
        echo   Debug APK 编译成功
        echo   路径: app\build\outputs\apk\debug\app-debug.apk
        echo   包名: com.smartsearch.app.debug
        echo   ABI:  arm64-v8a, armeabi-v7a
        echo ============================================
    ) else (
        echo [ERROR] Debug APK 编译失败
        exit /b 1
    )
)

if "%BUILD_TYPE%"=="release" (
    echo [INFO] 编译 Release APK...
    call gradlew.bat assembleRelease --no-daemon
    if exist "app\build\outputs\apk\release\app-release.apk" (
        echo.
        echo ============================================
        echo   Release APK 编译成功
        echo   路径: app\build\outputs\apk\release\app-release.apk
        echo   包名: com.smartsearch.app
        echo   ABI:  arm64-v8a, armeabi-v7a
        echo ============================================
    ) else (
        echo [ERROR] Release APK 编译失败
        exit /b 1
    )
)

if "%BUILD_TYPE%"=="both" (
    echo [INFO] 编译 Debug APK...
    call gradlew.bat assembleDebug --no-daemon
    echo.
    echo [INFO] 编译 Release APK...
    call gradlew.bat assembleRelease --no-daemon
    echo.
    echo ============================================
    echo   Debug:   app\build\outputs\apk\debug\app-debug.apk
    echo   Release: app\build\outputs\apk\release\app-release.apk
    echo ============================================
)

echo.
echo [INFO] 安装命令:
echo   adb install -r app\build\outputs\apk\debug\app-debug.apk
echo.
echo 一键编译完成
endlocal