package com.abdulrahman.chessassistant

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * نافذة شفافة بحجم الشاشة، FLAG_NOT_TOUCHABLE فلا تعترض اللمس. ترسم سهماً واحداً فقط.
 * الإحداثيات بإحداثيات الشاشة (نفس إحداثيات لقطة MediaProjection).
 */
class ArrowOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var arrowView: ArrowView? = null

    companion object {
        const val EXTRA_DEMO = "arrow_demo"
        @Volatile private var instance: ArrowOverlayService? = null

        fun showArrow(x1: Float, y1: Float, x2: Float, y2: Float) {
            val v = instance?.arrowView ?: return
            v.post { v.setArrow(x1, y1, x2, y2) }
        }

        fun hideArrow() {
            val v = instance?.arrowView ?: return
            v.post { v.clearArrow() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = ArrowView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager?.addView(view, params)
            arrowView = view
            instance = this
        } catch (e: Exception) {
            // إذن الظهور فوق التطبيقات غير ممنوح
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_DEMO, false) == true) {
            arrowView?.setDemo()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        arrowView?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        arrowView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class ArrowView : View(this@ArrowOverlayService) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 220, 90)
            strokeWidth = 13f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 220, 90)
            style = Paint.Style.FILL
        }
        private var has = false
        private var ax1 = 0f
        private var ay1 = 0f
        private var ax2 = 0f
        private var ay2 = 0f
        private val loc = IntArray(2)

        fun setArrow(x1: Float, y1: Float, x2: Float, y2: Float) {
            ax1 = x1; ay1 = y1; ax2 = x2; ay2 = y2
            has = true
            invalidate()
        }

        fun setDemo() {
            post { setArrow(width * 0.38f, height * 0.52f, width * 0.62f, height * 0.40f) }
        }

        fun clearArrow() {
            has = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!has) return
            // تعويض أي إزاحة بين نافذة التراكب وإحداثيات الشاشة الكاملة
            getLocationOnScreen(loc)
            val x1 = ax1 - loc[0]
            val y1 = ay1 - loc[1]
            val x2 = ax2 - loc[0]
            val y2 = ay2 - loc[1]
            val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val length = 40f
            val spread = 0.55
            // الخط ينتهي عند قاعدة الرأس حتى لا يبرز من طرفه
            val bx = x2 - (length * 0.8f) * cos(angle).toFloat()
            val by = y2 - (length * 0.8f) * sin(angle).toFloat()
            canvas.drawLine(x1, y1, bx, by, linePaint)
            val path = Path().apply {
                moveTo(x2, y2)
                lineTo(x2 - length * cos(angle - spread).toFloat(), y2 - length * sin(angle - spread).toFloat())
                lineTo(x2 - length * cos(angle + spread).toFloat(), y2 - length * sin(angle + spread).toFloat())
                close()
            }
            canvas.drawPath(path, headPaint)
        }
    }
}
