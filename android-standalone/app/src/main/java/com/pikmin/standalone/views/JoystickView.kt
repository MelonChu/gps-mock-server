package com.pikmin.standalone.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * 虛擬搖桿 — 觸控拖動控制移動方向
 *
 * 圓形搖桿區域，手指拖動時回傳角度與距離。
 * 放開手指後自動歸零。
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnJoystickListener {
        /** 搖桿移動時回調
         * @param angle 角度 (0~360)，0 = 正北，順時針
         * @param distance 距離 (0~1)，0 = 中心
         */
        fun onJoystickMoved(angle: Double, distance: Float)
    }

    private var listener: OnJoystickListener? = null

    // 搖桿視覺屬性
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 102, 153)
        setShadowLayer(8f, 0f, 0f, Color.argb(150, 255, 102, 153))
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var knobRadius = 0f
    private var isTouching = false

    // 箭頭方向指示
    private val dirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun setOnJoystickListener(l: OnJoystickListener) { listener = l }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.85f
        knobRadius = baseRadius * 0.3f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 背景圓
        canvas.drawCircle(centerX, centerY, baseRadius, bgPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, borderPaint)

        // 方向指示線（十字）
        val crossLen = baseRadius * 0.3f
        dirPaint.alpha = 40
        canvas.drawLine(centerX - crossLen, centerY, centerX + crossLen, centerY, dirPaint)
        canvas.drawLine(centerX, centerY - crossLen, centerX, centerY + crossLen, dirPaint)

        // N 標記
        dirPaint.alpha = 80
        dirPaint.textSize = baseRadius * 0.25f
        canvas.drawText("N", centerX - 6, centerY - baseRadius + 20, dirPaint)

        // 搖桿頭
        canvas.drawCircle(knobX, knobY, knobRadius, knobShadow)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        val maxDist = baseRadius * 0.7f

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouching = true
                if (distance > maxDist) {
                    // 限制在圈內
                    val ratio = maxDist / distance
                    knobX = centerX + dx * ratio
                    knobY = centerY + dy * ratio
                } else {
                    knobX = event.x
                    knobY = event.y
                }

                // 計算角度（0 = 正北，順時針）
                val angle = (atan2(dx.toDouble(), -dy.toDouble()) * 180 / PI + 360) % 360
                val normalizedDist = min(distance / maxDist, 1.0f)

                listener?.onJoystickMoved(angle, normalizedDist)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                knobX = centerX
                knobY = centerY
                listener?.onJoystickMoved(0.0, 0f)
                invalidate()
                return true
            }
        }
        return false
    }
}
