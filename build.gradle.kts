// ── 根级构建脚本 ──
// AGP 8.1.3 | Kotlin 1.9.22 | Gradle 8.4+

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}