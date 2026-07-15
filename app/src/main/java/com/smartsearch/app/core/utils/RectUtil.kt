package com.smartsearch.app.core.utils

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 矩形工具类 —— 矩形重叠率计算、命中检测、坐标变换
 *
 * 使用场景：判定两个悬浮窗区域是否重叠、判断用户触摸点是否落在关闭/返回/缩放按钮上。
 * 所有坐标均以屏幕像素坐标系（原点左上角）为准。
 *
 * 兼容 Android 10 (API 29) ~ Android 14 (API 34)，无第三方依赖。
 */
object RectUtil {

    // ==================== 基础矩形运算 ====================

    /**
     * 将 [Rect] 转为 [RectF]（浮点精度），用于后续浮点计算。
     * @param rect 整数矩形
     * @return 等价的浮点矩形
     */
    fun toRectF(rect: Rect): RectF = RectF(rect)

    /**
     * 将 [RectF] 转为 [Rect]（整数截断），注意小数部分直接丢弃。
     * @param rectF 浮点矩形
     * @return 等价的整数矩形
     */
    fun toRect(rectF: RectF): Rect =
        Rect(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())

    /**
     * 计算矩形面积（浮点）。
     * @param rect 目标矩形
     * @return 面积（宽×高），宽高为 0 或负数时返回 0f
     */
    fun area(rect: RectF): Float {
        val w = rect.width()
        val h = rect.height()
        return if (w > 0f && h > 0f) w * h else 0f
    }

    /**
     * 计算矩形面积（整数版本）。
     * @param rect 目标矩形
     * @return 面积
     */
    fun area(rect: Rect): Int {
        val w = rect.width()
        val h = rect.height()
        return if (w > 0 && h > 0) w * h else 0
    }

    // ==================== 重叠率计算 ====================

    /**
     * 计算两个矩形 [a] 与 [b] 的交集面积 / 并集面积（IoU，Intersection over Union）。
     *
     * 返回值范围 [0, 1]，0 表示不相交，1 表示完全重合。
     * 常用于判定悬浮窗是否被遮挡、OCR 扫描区域是否覆盖目标区域。
     *
     * @param a 矩形 A（RectF）
     * @param b 矩形 B（RectF）
     * @return IoU 值
     */
    fun iou(a: RectF, b: RectF): Float {
        val intersection = intersectionArea(a, b)
        if (intersection <= 0f) return 0f
        val union = area(a) + area(b) - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }

    /**
     * 计算矩形交集面积 / 矩形 A 面积（Overlap Ratio）。
     *
     * 用于判定悬浮窗 A 是否被 B 遮挡超过一定比例。
     * 返回值范围 [0, 1]。
     *
     * @param a 被遮挡矩形
     * @param b 遮挡矩形
     * @return 重叠区域占 a 面积的比例
     */
    fun overlapRatio(a: RectF, b: RectF): Float {
        val intersection = intersectionArea(a, b)
        if (intersection <= 0f) return 0f
        val areaA = area(a)
        if (areaA <= 0f) return 0f
        return intersection / areaA
    }

    /**
     * 计算两个矩形交集面积。
     * @param a 矩形 A
     * @param b 矩形 B
     * @return 交集面积，不相交返回 0f
     */
    fun intersectionArea(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val w = right - left
        val h = bottom - top
        return if (w > 0f && h > 0f) w * h else 0f
    }

    /**
     * 判断两个矩形是否相交。
     * @param a 矩形 A
     * @param b 矩形 B
     * @return true 表示存在交集
     */
    fun intersects(a: RectF, b: RectF): Boolean = RectF.intersects(a, b)

    // ==================== 命中检测：判断坐标是否点击了指定按钮 ====================

    /**
     * 热区扩大默认值（dp），实际使用时需转换为 px。
     * 用于提升小按钮的可点击性。
     */
    private const val DEFAULT_TOUCH_SLOP_DP = 8f

    /**
     * 判断触摸点 (x, y) 是否落在目标矩形 [rect] 内。
     *
     * 支持 [expandPx] 参数扩大热区：正值向外扩展，负值向内收缩。
     * 这在检测小按钮（如关闭 X）时非常有用 —— 即使手指稍有偏差也能命中。
     *
     * @param x 触摸点 X 坐标（屏幕像素）
     * @param y 触摸点 Y 坐标（屏幕像素）
     * @param rect 目标矩形
     * @param expandPx 热区扩大像素数，默认 8dp 对应的像素值
     * @return true 表示命中
     */
    fun hitTest(x: Float, y: Float, rect: RectF, expandPx: Float = 0f): Boolean {
        if (expandPx == 0f) return rect.contains(x, y)
        val expanded = RectF(
            rect.left - expandPx,
            rect.top - expandPx,
            rect.right + expandPx,
            rect.bottom + expandPx
        )
        return expanded.contains(x, y)
    }

    /**
     * 判断触摸点是否点击了"关闭 X 按钮"。
     *
     * 关闭按钮通常位于悬浮窗的右上角，是一个边长约 24~28dp 的正方形区域。
     * 本方法默认以 [containerRect] 的右上角为锚点，计算一个 28dp 的方形区域，
     * 并自动扩大 8dp 热区以提升命中率。
     *
     * @param x 触摸点 X 坐标（屏幕像素）
     * @param y 触摸点 Y 坐标（屏幕像素）
     * @param containerRect 悬浮窗整体矩形
     * @param buttonSizePx 关闭按钮边长（像素），推荐 28dp
     * @param marginPx 按钮距容器边缘的内边距（像素），推荐 4dp
     * @param touchSlopPx 热区扩大（像素），推荐 8dp
     * @return true 表示命中关闭按钮
     */
    fun hitCloseButton(
        x: Float,
        y: Float,
        containerRect: RectF,
        buttonSizePx: Float,
        marginPx: Float = 0f,
        touchSlopPx: Float = 0f
    ): Boolean {
        // 关闭按钮位于容器右上角，坐标为 (right - margin - size, top + margin)
        val btnLeft = containerRect.right - marginPx - buttonSizePx
        val btnTop = containerRect.top + marginPx
        val closeRect = RectF(btnLeft, btnTop, btnLeft + buttonSizePx, btnTop + buttonSizePx)
        return hitTest(x, y, closeRect, touchSlopPx)
    }

    /**
     * 判断触摸点是否点击了"返回箭头按钮"。
     *
     * 返回箭头通常位于悬浮窗的左上角，与关闭按钮对称。
     * 计算方式与关闭按钮一致，只是锚点改为左上角。
     *
     * @param x 触摸点 X 坐标（屏幕像素）
     * @param y 触摸点 Y 坐标（屏幕像素）
     * @param containerRect 悬浮窗整体矩形
     * @param buttonSizePx 返回按钮边长（像素），推荐 28dp
     * @param marginPx 按钮距容器边缘的内边距（像素），推荐 4dp
     * @param touchSlopPx 热区扩大（像素），推荐 8dp
     * @return true 表示命中返回按钮
     */
    fun hitBackButton(
        x: Float,
        y: Float,
        containerRect: RectF,
        buttonSizePx: Float,
        marginPx: Float = 0f,
        touchSlopPx: Float = 0f
    ): Boolean {
        val btnLeft = containerRect.left + marginPx
        val btnTop = containerRect.top + marginPx
        val backRect = RectF(btnLeft, btnTop, btnLeft + buttonSizePx, btnTop + buttonSizePx)
        return hitTest(x, y, backRect, touchSlopPx)
    }

    /**
     * 判断触摸点是否点击了"缩放区域"（拖拽右下角缩放手柄）。
     *
     * 缩放区域通常位于悬浮窗右下角，是一个约 40dp 的正方形区域，
     * 用户拖拽此处可改变窗口大小。
     *
     * @param x 触摸点 X 坐标（屏幕像素）
     * @param y 触摸点 Y 坐标（屏幕像素）
     * @param containerRect 悬浮窗整体矩形
     * @param resizeHandleSizePx 缩放手柄边长（像素），推荐 40dp
     * @param touchSlopPx 热区扩大（像素），推荐 8dp
     * @return true 表示命中缩放区域
     */
    fun hitResizeHandle(
        x: Float,
        y: Float,
        containerRect: RectF,
        resizeHandleSizePx: Float,
        touchSlopPx: Float = 0f
    ): Boolean {
        val handleLeft = containerRect.right - resizeHandleSizePx
        val handleTop = containerRect.bottom - resizeHandleSizePx
        val handleRect = RectF(handleLeft, handleTop, containerRect.right, containerRect.bottom)
        return hitTest(x, y, handleRect, touchSlopPx)
    }

    /**
     * 判断触摸点是否位于矩形 [rect] 的四个角 + 四条边的边缘区域，用于检测"调整窗口大小"手势。
     *
     * 返回一个 [ResizeEdge] 枚举，表示触摸点靠近哪个边缘/角。
     * 如果不在边缘区域内，返回 [ResizeEdge.NONE]。
     *
     * @param x 触摸点 X 坐标
     * @param y 触摸点 Y 坐标
     * @param rect 目标矩形
     * @param edgeWidthPx 边缘宽度（像素），推荐 20dp
     * @return 命中的边缘枚举
     */
    fun hitResizeEdge(x: Float, y: Float, rect: RectF, edgeWidthPx: Float): ResizeEdge {
        val onLeft = x >= rect.left && x <= rect.left + edgeWidthPx
        val onRight = x >= rect.right - edgeWidthPx && x <= rect.right
        val onTop = y >= rect.top && y <= rect.top + edgeWidthPx
        val onBottom = y >= rect.bottom - edgeWidthPx && y <= rect.bottom

        // 先判断四角
        if (onLeft && onTop) return ResizeEdge.TOP_LEFT
        if (onRight && onTop) return ResizeEdge.TOP_RIGHT
        if (onLeft && onBottom) return ResizeEdge.BOTTOM_LEFT
        if (onRight && onBottom) return ResizeEdge.BOTTOM_RIGHT

        // 再判断四边
        if (onLeft && y > rect.top && y < rect.bottom) return ResizeEdge.LEFT
        if (onRight && y > rect.top && y < rect.bottom) return ResizeEdge.RIGHT
        if (onTop && x > rect.left && x < rect.right) return ResizeEdge.TOP
        if (onBottom && x > rect.left && x < rect.right) return ResizeEdge.BOTTOM

        return ResizeEdge.NONE
    }

    /**
     * 调整大小边缘枚举。
     */
    enum class ResizeEdge {
        NONE,
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    // ==================== 矩形裁剪约束 ====================

    /**
     * 将矩形 [rect] 约束在 [bounds] 边界内，超出的部分裁剪掉。
     *
     * 悬浮窗拖拽时必须调用此方法，防止窗口超出屏幕边界。
     * 如果矩形完全在边界外，返回一个与边界对齐的零尺寸矩形（位于最近边角）。
     *
     * @param rect 待约束的矩形
     * @param bounds 屏幕/父容器边界
     * @return 裁剪后的矩形，保证不超出 bounds
     */
    fun clampToBounds(rect: RectF, bounds: RectF): RectF {
        val clamped = RectF(rect)
        // 保证宽度不超过边界
        if (clamped.width() > bounds.width()) {
            clamped.left = bounds.left
            clamped.right = bounds.right
        }
        if (clamped.height() > bounds.height()) {
            clamped.top = bounds.top
            clamped.bottom = bounds.bottom
        }
        // 水平方向约束
        if (clamped.left < bounds.left) {
            clamped.offset(bounds.left - clamped.left, 0f)
        }
        if (clamped.right > bounds.right) {
            clamped.offset(bounds.right - clamped.right, 0f)
        }
        // 垂直方向约束
        if (clamped.top < bounds.top) {
            clamped.offset(0f, bounds.top - clamped.top)
        }
        if (clamped.bottom > bounds.bottom) {
            clamped.offset(0f, bounds.bottom - clamped.bottom)
        }
        return clamped
    }

    /**
     * 判断矩形 [rect] 是否完全在 [bounds] 内。
     * @param rect 待检查矩形
     * @param bounds 边界
     * @return true 表示完全包含
     */
    fun isInsideBounds(rect: RectF, bounds: RectF): Boolean =
        rect.left >= bounds.left &&
                rect.top >= bounds.top &&
                rect.right <= bounds.right &&
                rect.bottom <= bounds.bottom

    // ==================== 与状态栏/导航栏交互 ====================

    /**
     * 计算矩形 [rect] 与状态栏的重叠率。
     *
     * 某些场景下悬浮窗不应覆盖状态栏区域，需要检测重叠程度。
     * @param rect 悬浮窗矩形
     * @param statusBarHeight 状态栏高度（像素）
     * @param screenWidth 屏幕宽度（像素）
     * @return 重叠率 [0, 1]
     */
    fun statusBarOverlapRatio(rect: RectF, statusBarHeight: Float, screenWidth: Float): Float {
        if (statusBarHeight <= 0f) return 0f
        val statusBarRect = RectF(0f, 0f, screenWidth, statusBarHeight)
        return overlapRatio(rect, statusBarRect)
    }

    // ==================== 两点距离计算 ====================

    /**
     * 计算两点欧氏距离，用于判断手势（如双击、长按漂移量）。
     * @return 像素距离
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}