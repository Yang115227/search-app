// ── 根级构建脚本 ──
// AGP 8.1.3 | Kotlin 1.9.22 | Gradle 8.4+
// 插件通过 settings.gradle.kts 的 pluginManagement 解析

plugins {
    id("com.android.application") version "8.1.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}