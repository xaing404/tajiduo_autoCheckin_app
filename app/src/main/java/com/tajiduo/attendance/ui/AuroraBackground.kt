package com.tajiduo.attendance.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 方案 A+B 动态背景：
 * - 两个大光晕缓慢漂移（紫蓝色调）
 * - 几条极细曲线流动
 * 卡片半透明时透出光晕。离屏自动暂停省电。
 */
class AuroraBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var density = resources.displayMetrics.density
    private var time = 0f

    // 光晕位置（百分比）
    private var glow1X = 0.25f
    private var glow1Y = 0.2f
    private var glow2X = 0.75f
    private var glow2Y = 0.8f
    private var glow1Phase = 0f
    private var glow2Phase = 0f

    private var isDark = true

    init {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 50L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                time += 0.016f
                glow1Phase += 0.003f
                glow2Phase += 0.004f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDark) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 光晕1：紫蓝色，左上区域漂移
        val g1cx = glow1X * w + cos(glow1Phase) * 30f * density
        val g1cy = glow1Y * h + sin(glow1Phase * 1.3f) * 25f * density
        val g1 = RadialGradient(
            g1cx, g1cy, w * 0.55f,
            0x332D2A4A,  // 紫灰 20% 透明度
            0x00000000,
            Shader.TileMode.CLAMP
        )
        paint.shader = g1
        canvas.drawRect(0f, 0f, w, h, paint)

        // 光晕2：深蓝，右下区域漂移
        val g2cx = glow2X * w + cos(glow2Phase * 0.8f) * 35f * density
        val g2cy = glow2Y * h + sin(glow2Phase) * 30f * density
        val g2 = RadialGradient(
            g2cx, g2cy, w * 0.5f,
            0x331A2A3A,  // 深蓝 20% 透明度
            0x00000000,
            Shader.TileMode.CLAMP
        )
        paint.shader = g2
        canvas.drawRect(0f, 0f, w, h, paint)

        // 细曲线流动（3条）
        paint.shader = null
        paint.color = 0x1A4A6A8A  // 极淡蓝灰
        paint.strokeWidth = 1f * density
        paint.style = Paint.Style.STROKE

        val curvePath = Path()
        for (i in 0..2) {
            curvePath.reset()
            val baseY = h * (0.3f + i * 0.2f)
            curvePath.moveTo(0f, baseY + sin(time * 0.5f + i) * 10f * density)
            // 画波浪线
            for (x in 0..w.toInt() step 20) {
                val y = baseY + sin(x * 0.01f + time * 0.8f + i * 1.5f) * 15f * density
                curvePath.lineTo(x.toFloat(), y)
            }
            canvas.drawPath(curvePath, paint)
        }
    }
}
