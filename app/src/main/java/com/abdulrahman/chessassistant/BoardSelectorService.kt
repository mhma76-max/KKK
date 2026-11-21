package com.abdulrahman.chessassistant

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * تحديد الرقعة يدوياً: اسحب إصبعك من الزاوية العلوية اليسرى للرقعة إلى زاويتها السفلية اليمنى، ثم «تأكيد».
 * النافذة قابلة للمس مؤقتاً (فقط أثناء التحديد) وتُغلق بعد التأكيد أو الإلغاء.
 */
class BoardSelectorService : Service() {
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = FrameLayout(this)
        val select = SelectView()
        layout.addView(select, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(this).apply {
            text = "اسحب من الزاوية العلوية اليسرى للرقعة إلى السفلية اليمنى"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 16, 24, 16)
            textSize = 15f
        }
        layout.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 120 })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "تأكيد"
            setOnClickListener {
                val r = select.selected()
                if (r != null) {
                    ScreenCaptureService.deliverBoardRect(r)
                    stopSelf()
                } else hint.text = "ارسم الرقعة أولاً (حجم كافٍ)"
            }
        })
        buttons.addView(Button(this).apply { text = "إلغاء"; setOnClickListener { stopSelf() } })
        layout.addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 160 })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm?.addView(layout, params)
            root = layout
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        root?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class SelectView : View(this@BoardSelectorService) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 220, 90); style = Paint.Style.STROKE; strokeWidth = 5f }
        private val grid = Paint().apply { color = Color.argb(110, 30, 220, 90); strokeWidth = 2f }
        private var x0 = 0f; private var y0 = 0f; private var size = 0f
        private var has = false
        private val loc = IntArray(2)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; size = 0f; has = true }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    size = (((e.x - x0) + (e.y - y0)) / 2f).coerceAtLeast(0f)
                }
            }
            invalidate()
            return true
        }

        /** مستطيل بإحداثيات الشاشة أو null إن كان صغيراً. */
        fun selected(): BoardRect? {
            if (!has || size < 200f) return null
            getLocationOnScreen(loc)
            return BoardRect(x0 + loc[0], y0 + loc[1], size)
        }

        override fun onDraw(c: Canvas) {
            if (!has || size <= 0f) return
            c.drawRect(x0, y0, x0 + size, y0 + size, line)
            for (i in 1..7) {
                val t = size * i / 8f
                c.drawLine(x0 + t, y0, x0 + t, y0 + size, grid)
                c.drawLine(x0, y0 + t, x0 + size, y0 + t, grid)
            }
        }
    }
}
