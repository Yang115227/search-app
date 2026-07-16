// ============================================
// app 模块构建脚本
// AGP 8.1.3 | Kotlin 1.9.22 | Compose Compiler 1.5.8
// JDK 17 | compileSdk 34 | minSdk 26 | targetSdk 34
// ============================================

plugins {
    id("com.android.application") version "8.1.3"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

import java.util.Properties

android {
    namespace = "com.smartsearch.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.smartsearch.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // ── Room schema 导出目录（可选） ──
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // ── NDK ABI 过滤（PaddleOCR 模型兼容） ──
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // ── 签名配置 ──
    // Release 签名从 signing.properties 读取（如文件不存在则使用 Debug 签名）
    signingConfigs {
        getByName("debug") {
            // 使用默认 debug.keystore，无需额外配置
        }
        create("release") {
            val signingProps = Properties()
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                signingProps.load(propsFile.inputStream())
                storeFile = file(signingProps.getProperty("storeFile", "smartsearch.keystore"))
                storePassword = signingProps.getProperty("storePassword", "android")
                keyAlias = signingProps.getProperty("keyAlias", "smartsearch")
                keyPassword = signingProps.getProperty("keyPassword", "android")
            } else {
                // 无签名配置文件时，回退到 Debug 签名
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            // Debug 模式下保留调试信息
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // ── JDK 17 编译参数 ──
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    // ── Compose 配置 ──
    buildFeatures {
        compose = true
        // ViewBinding 保留（兼容旧版 View 体系悬浮窗）
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    // ── 打包配置 ──
    packaging {
        resources {
            // 排除冲突文件
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            // POI 冲突处理
            excludes += "/META-INF/services/javax.xml.stream.XMLEventFactory"
            excludes += "/META-INF/services/javax.xml.stream.XMLInputFactory"
            excludes += "/META-INF/services/javax.xml.stream.XMLOutputFactory"
            excludes += "/META-INF/services/org.apache.poi.ss.usermodel.WorkbookFactory"
        }
    }
}

// ============================================
// 依赖声明
// ============================================

dependencies {
    // ── AndroidX 核心 ──
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)

    // ── Lifecycle ──
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ── Navigation ──
    implementation(libs.androidx.navigation.compose)

    // ── Compose (BOM 统一版本) ──
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // ── Room 数据库 ──
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── CameraX（OCR 兜底方案） ──
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ── 协程 ──
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ── Excel 解析（Apache POI / EasyExcel 底层） ──
    implementation(libs.poi)
    implementation(libs.poi.ooxml)

    // ── Material Components（提供 XML 主题如 Theme.Material3） ──
    implementation(libs.material)

    // ── JSON ──
    implementation(libs.gson)

    // ── 图片加载 ──
    implementation(libs.coil.compose)

    // ── PaddleOCR 本地 AAR（放入 app/libs/ 目录） ──
    // 格式：implementation(files("libs/PaddleOCR-2.7.0.aar"))
    // 实际使用时取消注释并确认 AAR 文件名
    // compileOnly(files("libs/PaddleOCR.aar"))

    // ── 测试 ──
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}