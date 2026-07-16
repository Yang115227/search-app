package com.smartsearch.app.core.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限管理器 —— 统一管理悬浮窗、无障碍、录屏、相机四类权限。
 *
 * # 核心职责
 * - 检测各类权限的授予状态，兼容 Android 10 (API 29) ~ Android 14 (API 34)
 * - 定义搜题模式的优先级：**无障碍 > 录屏 > 相机**
 * - 权限缺失时返回明确的 [PermissionStatus] 和推荐的 [SearchMode]
 *
 * # 各版本关键差异
 * | 版本          | 悬浮窗                                 | 无障碍                      | 录屏                          | 相机          |
 * |--------------|---------------------------------------|---------------------------|------------------------------|--------------|
 * | Android 10   | Settings.canDrawOverlays()            | 无障碍服务列表匹配           | MediaProjection 一次性授权    | 运行时权限    |
 * | Android 11   | 同 10，部分厂商需额外跳转              | 同 10                      | 同 10                        | 同 10         |
 * | Android 12   | 同 11                                  | 同 10                      | 同 10                        | 同 10         |
 * | Android 13   | 同 12                                  | 同 10                      | 同 10                        | 同 10         |
 * | Android 14   | 同 13                                  | 同 10                      | 同 10                        | 同 10         |
 *
 * 注：无第三方依赖，仅使用 AndroidX Core KTX 中的 ContextCompat。
 */
object PermissionManager {

    // ==================== 状态枚举 ====================

    /**
     * 单项权限状态。
     */
    enum class PermissionStatus {
        /** 已授权，可直接使用 */
        GRANTED,
        /** 已拒绝 / 未授权，需要引导用户开启 */
        DENIED,
        /** 当前设备/系统版本不支持此能力 */
        NOT_APPLICABLE
    }

    /**
     * 搜题模式，按优先级从高到低排列。
     *
     * 优先级说明：
     * - [ACCESSIBILITY]：无障碍服务，无需用户每次确认，最无感
     * - [SCREEN_CAPTURE]：录屏，需要用户单次授权，介于一、三之间
     * - [CAMERA]：相机，需要用户每次拍照，体验最差，作为兜底
     * - [NONE]：无可用模式，需引导用户授权
     */
    enum class SearchMode(val priority: Int) {
        ACCESSIBILITY(3),
        SCREEN_CAPTURE(2),
        CAMERA(1),
        NONE(0)
    }

    // ==================== 悬浮窗权限 ====================

    /**
     * 检测悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     *
     * ## 版本适配
     * - **Android 10 (API 29)**：引入了 `ACTION_MANAGE_OVERLAY_PERMISSION`，部分 ROM 需要额外跳转
     * - **Android 11 (API 30)**：部分厂商（小米、OPPO）在系统设置中拆分了"后台弹出界面"权限
     * - **Android 12+**：与 11 行为一致，无新增限制
     *
     * @param context 上下文
     * @return [PermissionStatus.GRANTED] 或 [PermissionStatus.DENIED]
     */
    fun checkFloatingWindow(context: Context): PermissionStatus {
        // Android 6+ (API 23) 统一使用此 API，向下兼容到 API 29
        return if (Settings.canDrawOverlays(context)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    /**
     * 获取悬浮窗权限设置页面的 Intent。
     *
     * @param context 上下文
     * @return 跳转到应用悬浮窗权限设置页的 Intent
     */
    fun getFloatingWindowSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ==================== 无障碍权限 ====================

    /**
     * 检测无障碍服务是否已开启。
     *
     * ## 检测原理
     * 读取 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，检查其中是否包含
     * 当前应用的 `{packageName}/{serviceClassName}`。
     *
     * ## 版本适配
     * - **Android 10~14**：无障碍 API 无破坏性变更，检测方式一致
     * - 部分厂商 ROM 无障碍服务开启后会被系统自动关闭（省电策略），需在 Service 中做保活
     *
     * @param context 上下文
     * @param serviceClassName 无障碍服务类全限定名，如 "com.smartsearch.app.feature.search.accessibility.AccessibilitySearchService"
     * @return [PermissionStatus]
     */
    fun checkAccessibility(context: Context, serviceClassName: String): PermissionStatus {
        val expectedService = "${context.packageName}/$serviceClassName"
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: SecurityException) {
            // 极少数 ROM 可能抛出 SecurityException
            return PermissionStatus.DENIED
        }

        return if (!enabledServices.isNullOrEmpty() && enabledServices.contains(expectedService)) {
            // 二次确认：检查无障碍服务是否确实运行中
            // 部分厂商会静默关闭无障碍服务，但列表中仍保留
            val isRunning = checkAccessibilityServiceRunning(context, expectedService)
            if (isRunning) PermissionStatus.GRANTED else PermissionStatus.DENIED
        } else {
            PermissionStatus.DENIED
        }
    }

    /**
     * 通过 AccessibilityManager 二次校验无障碍服务是否实际运行。
     *
     * Android 12+ 某些厂商 ROM 即使无障碍列表中有记录，服务也可能被系统静默停止。
     * 此方法获取当前正在运行的无障碍服务列表做交叉验证。
     */
    private fun checkAccessibilityServiceRunning(context: Context, expectedService: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val runningServices = am?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ) ?: return false
            runningServices.any { serviceInfo ->
                val id = serviceInfo.id ?: return@any false
                id == expectedService || id == "${context.packageName}/"
            }
        } catch (e: Exception) {
            // 获取失败时保守处理：认为未运行
            false
        }
    }

    /**
     * 获取无障碍服务设置页面的 Intent。
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ==================== 录屏权限 ====================

    /**
     * 检测录屏（MediaProjection）权限是否可用。
     *
     * ## 重要说明
     * MediaProjection 是**一次性授权**机制：每次启动录屏都需要用户通过系统弹窗确认，
     * 不存在像运行时权限那样的"持久授权"状态。
     *
     * 因此本方法使用以下策略判断：
     * 1. 检查录屏前台 Service 是否正在运行（说明当前会话已授权）
     * 2. 如果均不满足，返回 DENIED，提示需要重新授权
     *
     * @param context 上下文
     * @param screenCaptureServiceClassName 录屏 Service 全限定类名
     * @return [PermissionStatus]
     */
    fun checkScreenCapture(context: Context, screenCaptureServiceClassName: String): PermissionStatus {
        // 策略：检查录屏前台 Service 是否正在运行
        if (isServiceRunning(context, screenCaptureServiceClassName)) {
            return PermissionStatus.GRANTED
        }
        return PermissionStatus.DENIED
    }

    /**
     * 获取录屏权限的引导文本。
     */
    fun getScreenCaptureGuideMessage(): String {
        return "录屏权限需要每次使用前授权。\n\n" +
                "点击「录屏搜题」按钮后，系统会弹出录屏授权对话框，\n" +
                "请点击「立即开始」或「允许」即可。\n\n" +
                "注意：部分 APP 页面启用了防截屏保护（FLAG_SECURE），\n" +
                "录屏模式下将无法识别这些页面的内容。"
    }

    /**
     * 标记录屏权限已授予（在用户通过系统弹窗授权后调用）。
     *
     * @param context 上下文
     */
    fun markScreenCaptureGranted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_CAPTURE_GRANTED, true)
            .apply()
    }

    /**
     * 清除录屏授权标记（在录屏 Service 停止时调用）。
     *
     * @param context 上下文
     */
    fun clearScreenCaptureGranted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SCREEN_CAPTURE_GRANTED)
            .apply()
    }

    // ==================== 相机权限 ====================

    /**
     * 检测相机权限。
     *
     * ## 版本适配
     * - **Android 10~14**：`CAMERA` 为普通运行时权限，`ContextCompat.checkSelfPermission` 通用
     * - 无版本差异
     *
     * @param context 上下文
     * @return [PermissionStatus]
     */
    fun checkCamera(context: Context): PermissionStatus {
        return when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED
            else -> PermissionStatus.DENIED
        }
    }

    // ==================== 搜题模式优先级 ====================

    /**
     * 按优先级（无障碍 > 录屏 > 相机）获取推荐的搜题模式。
     *
     * 调用方根据返回的 [SearchMode] 决定使用哪种方案进行搜题：
     * - [SearchMode.ACCESSIBILITY]：启动无障碍服务，自动截取屏幕内容
     * - [SearchMode.SCREEN_CAPTURE]：引导用户授权录屏，通过 MediaProjection 采集画面
     * - [SearchMode.CAMERA]：降级为相机拍照 OCR
     * - [SearchMode.NONE]：所有模式均不可用，需引导用户授权
     *
     * @param context 上下文
     * @param accessibilityServiceClassName 无障碍服务类全限定名
     * @param screenCaptureServiceClassName 录屏 Service 类全限定名
     * @return 推荐的搜题模式
     */
    fun getRecommendedMode(
        context: Context,
        accessibilityServiceClassName: String,
        screenCaptureServiceClassName: String
    ): SearchMode {
        // 优先级 1：无障碍
        if (checkAccessibility(context, accessibilityServiceClassName) == PermissionStatus.GRANTED) {
            return SearchMode.ACCESSIBILITY
        }
        // 优先级 2：录屏
        if (checkScreenCapture(context, screenCaptureServiceClassName) == PermissionStatus.GRANTED) {
            return SearchMode.SCREEN_CAPTURE
        }
        // 优先级 3：相机
        if (checkCamera(context) == PermissionStatus.GRANTED) {
            return SearchMode.CAMERA
        }
        return SearchMode.NONE
    }

    /**
     * 获取所有搜题相关权限的批量状态。
     *
     * 返回 Map 包含四项 key：`floating_window`、`accessibility`、`screen_capture`、`camera`。
     *
     * @param context 上下文
     * @param accessibilityServiceClassName 无障碍服务类全限定名
     * @param screenCaptureServiceClassName 录屏 Service 类全限定名
     * @return 权限名 → 状态 的映射
     */
    fun getAllPermissions(
        context: Context,
        accessibilityServiceClassName: String,
        screenCaptureServiceClassName: String
    ): Map<String, PermissionStatus> {
        return linkedMapOf(
            "floating_window" to checkFloatingWindow(context),
            "accessibility" to checkAccessibility(context, accessibilityServiceClassName),
            "screen_capture" to checkScreenCapture(context, screenCaptureServiceClassName),
            "camera" to checkCamera(context)
        )
    }

    /**
     * 判断是否有至少一种搜题模式可用。
     */
    fun hasAnySearchMode(
        context: Context,
        accessibilityServiceClassName: String,
        screenCaptureServiceClassName: String
    ): Boolean {
        return getRecommendedMode(context, accessibilityServiceClassName, screenCaptureServiceClassName) != SearchMode.NONE
    }

    /**
     * 获取缺失的权限列表（仅返回 DENIED 的权限名），用于 UI 引导。
     */
    fun getMissingPermissions(
        context: Context,
        accessibilityServiceClassName: String,
        screenCaptureServiceClassName: String
    ): List<String> {
        return getAllPermissions(context, accessibilityServiceClassName, screenCaptureServiceClassName)
            .filter { it.value == PermissionStatus.DENIED }
            .keys
            .toList()
    }

    // ==================== 权限引导 ====================

    /**
     * 获取权限引导消息文本。
     *
     * @param permissionName 权限名（与 getAllPermissions 返回的 key 一致）
     * @return 引导文本描述
     */
    fun getPermissionGuideMessage(permissionName: String): String {
        return when (permissionName) {
            "floating_window" -> "悬浮窗权限允许应用在其他应用上层显示悬浮球和搜题结果。\n\n" +
                    "请前往：设置 → 应用管理 → 智能搜题 → 显示悬浮窗 → 允许"

            "accessibility" -> "无障碍服务允许应用读取屏幕上的文字内容，实现自动搜题。\n\n" +
                    "请前往：设置 → 无障碍 → 智能搜题 → 开启服务\n\n" +
                    "注意：部分手机（小米、OPPO、vivo）需要在无障碍列表中手动开启开关。"

            "screen_capture" -> "录屏权限允许应用截取屏幕画面，通过 OCR 识别后匹配题库。\n\n" +
                    "点击「录屏搜题」按钮后，系统会弹出录屏授权对话框，\n" +
                    "请点击「立即开始」或「允许」。\n\n" +
                    "注意：每次重启应用后需要重新授权。"

            "camera" -> "相机权限作为兜底方案，当无障碍和录屏均不可用时，\n" +
                    "可通过拍照识别题目。\n\n" +
                    "请前往：设置 → 应用管理 → 智能搜题 → 权限 → 相机 → 允许"

            else -> "未知权限，请前往系统设置中检查应用权限。"
        }
    }

    /**
     * 按优先级顺序获取第一个缺失权限对应的设置页 Intent。
     *
     * 用于权限引导页面的"一键跳转"：先跳转无障碍，再跳转录屏，最后跳转悬浮窗。
     *
     * @return Pair<权限名, Intent>，如果所有权限均已授予则返回 null
     */
    fun getNextMissingPermissionIntent(
        context: Context,
        accessibilityServiceClassName: String,
        screenCaptureServiceClassName: String
    ): Pair<String, Intent>? {
        // 悬浮窗权限（基础前提，优先引导）
        if (checkFloatingWindow(context) == PermissionStatus.DENIED) {
            return "floating_window" to getFloatingWindowSettingsIntent(context)
        }
        // 无障碍（最高优先级搜题模式）
        if (checkAccessibility(context, accessibilityServiceClassName) == PermissionStatus.DENIED) {
            return "accessibility" to getAccessibilitySettingsIntent()
        }
        // 无障碍已授权的情况下，录屏和相机作为备选，不再强制引导
        return null
    }

    // ==================== 内部工具 ====================

    /**
     * 检测指定 Service 是否正在运行。
     *
     * 通过 [ActivityManager.getRunningServices] 实现。
     * 注意：Android 5.1+ 此方法默认只返回调用方自身的 Service，这恰好满足我们的需求。
     *
     * @param context 上下文
     * @param serviceClassName Service 全限定类名
     * @return true 表示正在运行
     */
    private fun isServiceRunning(context: Context, serviceClassName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = am.getRunningServices(Int.MAX_VALUE)
            runningServices.any { it.service.className == serviceClassName }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * SharedPreferences 存储名。
     */
    private const val PREFS_NAME = "smartsearch_permission"

    /**
     * 录屏授权标记 Key。
     */
    private const val KEY_SCREEN_CAPTURE_GRANTED = "screen_capture_granted"
}