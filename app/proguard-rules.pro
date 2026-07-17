# ── SmartSearch 混淆规则 ──
# AGP 8.1.3 | R8 全模式

# ============================================
# Room 数据库
# ============================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ============================================
# Kotlin 协程
# ============================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ============================================
# Apache POI (Excel 解析)
# ============================================
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemasMicrosoftComOfficeExcel.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn javax.xml.stream.**
-dontwarn org.etsi.**
-dontwarn com.microsoft.schemas.**
-dontwarn aQute.bnd.**
-dontwarn org.osgi.**
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**
-dontwarn org.apache.logging.log4j.**

# ============================================
# PaddleOCR
# ============================================
-keep class com.baidu.paddle.** { *; }
-dontwarn com.baidu.paddle.**

# ============================================
# Gson
# ============================================
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.smartsearch.app.data.** { *; }

# ============================================
# Compose
# ============================================
-keep class androidx.compose.** { *; }

# ============================================
# 无障碍服务
# ============================================
-keep class com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService { *; }

# ============================================
# 通用规则
# ============================================
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-ignorewarnings