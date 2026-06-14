package com.gpsmock.server.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, def: Int = 0
) : View(context, attrs, def) {
    interface Listener { fun onMove(angle: Double, dist: Float) }

    private var listener: Listener? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120,0,0,0); style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180,255,255,255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220,255,255,255); style = Paint.Style.FILL }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80,255,102,153); setShadowLayer(8f,0f,0f,Color.argb(100,255,102,153)) }
    private var cx = 0f; private var cy = 0f; private var r = 0f; private var kr = 0f
    private var kx = 0f; private var ky = 0f

    fun setListener(l: Listener) { listener = l }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f * 0.85f; kr = r * 0.3f; kx = cx; ky = cy
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.drawCircle(cx, cy, r, bg); c.drawCircle(cx, cy, r, border)
        c.drawCircle(kx, ky, kr, shadow); c.drawCircle(kx, ky, kr, knob)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x - cx; val dy = e.y - cy; val dist = sqrt(dx*dx + dy*dy); val max = r * 0.7f
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (dist > max) { val s = max/dist; kx=cx+dx*s; ky=cy+dy*s }
                else { kx=e.x; ky=e.y }
                val angle = (atan2(dx.toDouble(), -dy.toDouble()) * 180 / PI + 360) % 360
                listener?.onMove(angle, min(dist/max, 1.0f)); invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx=cx; ky=cy; listener?.onMove(0.0, 0f); invalidate(); return true
            }
        }; return false
    }
}
